// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.dns;

import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.DnsMetrics.CommitStatus;
import google.registry.dns.DnsMetrics.PublishStatus;
import google.registry.dns.writer.DnsWriter;
import google.registry.model.registry.Registry;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import google.registry.util.Clock;
import google.registry.util.DomainNameUtils;
import google.registry.util.FormattingLogger;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Task that sends domain and host updates to the DNS server. */
@Action(
  path = PublishDnsUpdatesAction.PATH,
  method = POST,
  automaticallyPrintOk = true,
  auth = Auth.AUTH_INTERNAL_ONLY
)
public final class PublishDnsUpdatesAction implements Runnable, Callable<Void> {

  public static final String PATH = "/_dr/task/publishDnsUpdates";
  public static final String PARAM_DNS_WRITER = "dnsWriter";
  public static final String PARAM_DOMAINS = "domains";
  public static final String PARAM_HOSTS = "hosts";
  public static final String LOCK_NAME = "DNS updates";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  @Inject DnsQueue dnsQueue;
  @Inject DnsWriterProxy dnsWriterProxy;
  @Inject DnsMetrics dnsMetrics;
  @Inject @Config("dnsWriteLockTimeout") Duration timeout;

  /**
   * The DNS writer to use for this batch.
   *
   * <p>This comes from the fanout in {@link ReadDnsQueueAction} which dispatches each batch to be
   * published by each DNS writer on the TLD. So this field contains the value of one of the DNS
   * writers configured in {@link Registry#getDnsWriters()}, as of the time the batch was written
   * out (and not necessarily currently).
   */
  @Inject @Parameter(PARAM_DNS_WRITER) String dnsWriter;

  @Inject @Parameter(PARAM_DOMAINS) Set<String> domains;
  @Inject @Parameter(PARAM_HOSTS) Set<String> hosts;
  @Inject @Parameter(PARAM_TLD) String tld;
  @Inject LockHandler lockHandler;
  @Inject Clock clock;
  @Inject PublishDnsUpdatesAction() {}

  /** Runs the task. */
  @Override
  public void run() {
    // If executeWithLocks fails to get the lock, it does not throw an exception, simply returns
    // false. We need to make sure to take note of this error; otherwise, a failed lock might result
    // in the update task being dequeued and dropped. A message will already have been logged
    // to indicate the problem.
    if (!lockHandler.executeWithLocks(this, tld, timeout, LOCK_NAME)) {
      throw new ServiceUnavailableException("Lock failure");
    }
  }

  /** Runs the task, with the lock. */
  @Override
  public Void call() {
    processBatch();
    return null;
  }

  /** Adds all the domains and hosts in the batch back to the queue to be processed later. */
  private void requeueBatch() {
    for (String domain : nullToEmpty(domains)) {
      dnsQueue.addDomainRefreshTask(domain);
    }
    for (String host : nullToEmpty(hosts)) {
      dnsQueue.addHostRefreshTask(host);
    }
  }

  /** Steps through the domain and host refreshes contained in the parameters and processes them. */
  private void processBatch() {
    DateTime timeAtStart = clock.nowUtc();

    DnsWriter writer = dnsWriterProxy.getByClassNameForTld(dnsWriter, tld);

    if (writer == null) {
      logger.warningfmt(
          "Couldn't get writer %s for TLD %s, pushing domains back to the queue for retry",
          dnsWriter, tld);
      requeueBatch();
      return;
    }

    int domainsPublished = 0;
    int domainsRejected = 0;
    for (String domain : nullToEmpty(domains)) {
      if (!DomainNameUtils.isUnder(
          InternetDomainName.from(domain), InternetDomainName.from(tld))) {
        logger.severefmt("%s: skipping domain %s not under tld", tld, domain);
        domainsRejected += 1;
      } else {
        writer.publishDomain(domain);
        logger.infofmt("%s: published domain %s", tld, domain);
        domainsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishDomainRequests(domainsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishDomainRequests(domainsRejected, PublishStatus.REJECTED);

    int hostsPublished = 0;
    int hostsRejected = 0;
    for (String host : nullToEmpty(hosts)) {
      if (!DomainNameUtils.isUnder(
          InternetDomainName.from(host), InternetDomainName.from(tld))) {
        logger.severefmt("%s: skipping host %s not under tld", tld, host);
        hostsRejected += 1;
      } else {
        writer.publishHost(host);
        logger.infofmt("%s: published host %s", tld, host);
        hostsPublished += 1;
      }
    }
    dnsMetrics.incrementPublishHostRequests(hostsPublished, PublishStatus.ACCEPTED);
    dnsMetrics.incrementPublishHostRequests(hostsRejected, PublishStatus.REJECTED);

    // If we got here it means we managed to stage the entire batch without any errors.
    // Next we will commit the batch.
    CommitStatus commitStatus = CommitStatus.FAILURE;
    try {
      writer.commit();
      // No error was thrown
      commitStatus = CommitStatus.SUCCESS;
    } finally {
      Duration duration = new Duration(timeAtStart, clock.nowUtc());
      dnsMetrics.recordCommit(
          dnsWriter,
          commitStatus,
          duration,
          domainsPublished,
          hostsPublished);
      logger.infofmt(
          "writer.commit() statistics:: TLD: %s, commitStatus: %s, duration: %s, "
              + "domainsPublished: %d, domainsRejected: %d, hostsPublished: %d, hostsRejected: %d",
          tld,
          commitStatus,
          duration,
          domainsPublished,
          domainsRejected,
          hostsPublished,
          hostsRejected);
    }
  }
}
