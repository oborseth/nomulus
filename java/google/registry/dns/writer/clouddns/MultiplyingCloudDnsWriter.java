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

package google.registry.dns.writer.clouddns;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadByForeignKey;

import com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.Change;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.BaseDnsWriter;
import google.registry.dns.writer.DnsWriter;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registries;
import google.registry.util.Clock;
import google.registry.util.Concurrent;
import google.registry.util.FormattingLogger;
import google.registry.util.Retrier;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Duration;

/**
 * {@link DnsWriter} implementation that artificially multiplies the number of domains in a dns.
 *
 * This is a temporary writer, that should be removed once large zone resigning has been tested.
 *
 * It acts just like {@link CloudDnsWriter}, but in addition to registering the requested domain
 * with Cloud DNS, it registers 9 more copies of the domain.
 *
 * The result is a 10x larger zone on the Cloud DNS side than on the nomulus side.
 *
 * The reason for this is a combination of the following requirements:
 * (a) we want to test how Cloud DNS handles large zones (1M+ domain), and we want to find out ASAP
 * so they have time to fix any problems we find, and
 * (b) we currently found problems in nomulus preventing us from creating domains at a fast enough
 * rate to actually have a 1M domain zone. We managed to get up to 300k.
 *
 * So this temporary Writer will allow us to have a 1M domain zone in Cloud DNS while only creating
 * 100k domains in nomulus.
 *
 * TODO(b/71607306): Remove once large zone resigning is tested
 *
 * @see <a href="https://cloud.google.com/dns/docs/">Google Cloud DNS Documentation</a>
 */
public class MultiplyingCloudDnsWriter extends BaseDnsWriter {

  /**
   * The name of the pricing engine, as used in {@code Registry.dnsWriter}. Remember to change
   * the value on affected Registry objects to prevent runtime failures.
   */
  public static final String NAME = "MultiplyingCloudDnsWriter";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();
  private static final ImmutableSet<String> RETRYABLE_EXCEPTION_REASONS =
      ImmutableSet.of("preconditionFailed", "notFound", "alreadyExists");

  private final Clock clock;
  private final RateLimiter rateLimiter;
  private final int numThreads;
  // TODO(shikhman): This uses @Named("transientFailureRetries") which may not be tuned for this
  // application.
  private final Retrier retrier;
  private final Duration defaultATtl;
  private final Duration defaultNsTtl;
  private final Duration defaultDsTtl;
  private final String projectId;
  private final String zoneName;
  private final Dns dnsConnection;
  private final HashMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords = new HashMap<>();

  @Inject
  MultiplyingCloudDnsWriter(
      Dns dnsConnection,
      @Config("projectId") String projectId,
      @DnsWriterZone String zoneName,
      @Config("dnsDefaultATtl") Duration defaultATtl,
      @Config("dnsDefaultNsTtl") Duration defaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration defaultDsTtl,
      @Named("cloudDns") RateLimiter rateLimiter,
      @Named("cloudDnsNumThreads") int numThreads,
      Clock clock,
      Retrier retrier) {
    this.dnsConnection = dnsConnection;
    this.projectId = projectId;
    this.zoneName = zoneName.replace('.', '-');
    this.defaultATtl = defaultATtl;
    this.defaultNsTtl = defaultNsTtl;
    this.defaultDsTtl = defaultDsTtl;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.retrier = retrier;
    this.numThreads = numThreads;
  }

  /** Publish the domain and all subordinate hosts. */
  @Override
  public void publishDomain(String domainName) {
    // Load the target domain. Note that it can be null if this domain was just deleted.
    Optional<DomainResource> domainResource =
        Optional.ofNullable(loadByForeignKey(DomainResource.class, domainName, clock.nowUtc()));

    // Canonicalize name
    Set<String> absoluteDomainNames = multiplyAbsoluteName(getAbsoluteHostName(domainName));

    // Return early if no DNS records should be published.
    // desiredRecordsBuilder is populated with an empty set to indicate that all existing records
    // should be deleted.
    if (!domainResource.isPresent() || !domainResource.get().shouldPublishToDns()) {
      absoluteDomainNames.forEach(
          absoluteDomainName -> desiredRecords.put(absoluteDomainName, ImmutableSet.of()));
      return;
    }

    for (String absoluteDomainName : absoluteDomainNames) {
      ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

      // Construct DS records (if any).
      Set<DelegationSignerData> dsData = domainResource.get().getDsData();
      if (!dsData.isEmpty()) {
        HashSet<String> dsRrData = new HashSet<>();
        for (DelegationSignerData ds : dsData) {
          dsRrData.add(ds.toRrData());
        }

        if (!dsRrData.isEmpty()) {
          domainRecords.add(
              new ResourceRecordSet()
                  .setName(absoluteDomainName)
                  .setTtl((int) defaultDsTtl.getStandardSeconds())
                  .setType("DS")
                  .setKind("dns#resourceRecordSet")
                  .setRrdatas(ImmutableList.copyOf(dsRrData)));
        }
      }

      // Construct NS records (if any).
      Set<String> nameserverData = domainResource.get().loadNameserverFullyQualifiedHostNames();
      if (!nameserverData.isEmpty()) {
        HashSet<String> nsRrData = new HashSet<>();
        for (String hostName : nameserverData) {
          nsRrData.add(getAbsoluteHostName(hostName));

          // Construct glue records for subordinate NS hostnames (if any)
          if (hostName.endsWith(domainName)) {
            publishSubordinateHost(hostName);
          }
        }

        if (!nsRrData.isEmpty()) {
          domainRecords.add(
              new ResourceRecordSet()
                  .setName(absoluteDomainName)
                  .setTtl((int) defaultNsTtl.getStandardSeconds())
                  .setType("NS")
                  .setKind("dns#resourceRecordSet")
                  .setRrdatas(ImmutableList.copyOf(nsRrData)));
        }
      }

      desiredRecords.put(absoluteDomainName, domainRecords.build());
      logger.finefmt(
          "Will write %s records for domain %s", domainRecords.build().size(), absoluteDomainName);
    }
  }

  private void publishSubordinateHost(String hostName) {
    logger.infofmt("Publishing glue records for %s", hostName);
    // Canonicalize name
    String absoluteHostName = getAbsoluteHostName(hostName);

    // Load the target host. Note that it can be null if this host was just deleted.
    // desiredRecords is populated with an empty set to indicate that all existing records
    // should be deleted.
    Optional<HostResource> host =
        Optional.ofNullable(loadByForeignKey(HostResource.class, hostName, clock.nowUtc()));

    // Return early if the host is deleted.
    if (!host.isPresent()) {
      desiredRecords.put(absoluteHostName, ImmutableSet.of());
      return;
    }

    ImmutableSet.Builder<ResourceRecordSet> domainRecords = new ImmutableSet.Builder<>();

    // Construct A and AAAA records (if any).
    HashSet<String> aRrData = new HashSet<>();
    HashSet<String> aaaaRrData = new HashSet<>();
    for (InetAddress ip : host.get().getInetAddresses()) {
      if (ip instanceof Inet4Address) {
        aRrData.add(ip.toString());
      } else {
        checkArgument(ip instanceof Inet6Address);
        aaaaRrData.add(ip.toString());
      }
    }

    if (!aRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultATtl.getStandardSeconds())
              .setType("A")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aRrData)));
    }

    if (!aaaaRrData.isEmpty()) {
      domainRecords.add(
          new ResourceRecordSet()
              .setName(absoluteHostName)
              .setTtl((int) defaultATtl.getStandardSeconds())
              .setType("AAAA")
              .setKind("dns#resourceRecordSet")
              .setRrdatas(ImmutableList.copyOf(aaaaRrData)));
    }

    desiredRecords.put(absoluteHostName, domainRecords.build());
  }

  /**
   * Publish A/AAAA records to Cloud DNS.
   *
   * <p>Cloud DNS has no API for glue -- A/AAAA records are automatically matched to their
   * corresponding NS records to serve glue.
   */
  @Override
  public void publishHost(String hostName) {
    // Get the superordinate domain name of the host.
    InternetDomainName host = InternetDomainName.from(hostName);
    Optional<InternetDomainName> tld = Registries.findTldForName(host);

    // Host not managed by our registry, no need to update DNS.
    if (!tld.isPresent()) {
      logger.severefmt("publishHost called for invalid host %s", hostName);
      return;
    }

    // Extract the superordinate domain name. The TLD and host may have several dots so this
    // must calculate a sublist.
    ImmutableList<String> hostParts = host.parts();
    ImmutableList<String> tldParts = tld.get().parts();
    ImmutableList<String> domainParts =
        hostParts.subList(hostParts.size() - tldParts.size() - 1, hostParts.size());
    String domain = Joiner.on(".").join(domainParts);

    // Refresh the superordinate domain, since we shouldn't be publishing glue records if we are not
    // authoritative for the superordinate domain.
    publishDomain(domain);
  }

  /**
   * Sync changes in a zone requested by publishDomain and publishHost to Cloud DNS.
   *
   * <p>The zone for the TLD must exist first in Cloud DNS and must be DNSSEC enabled.
   *
   * <p>The relevant resource records (including those of all subordinate hosts) will be retrieved
   * and the operation will be retried until the state of the retrieved zone data matches the
   * representation built via this writer.
   */
  @Override
  protected void commitUnchecked() {
    ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecordsCopy =
        ImmutableMap.copyOf(desiredRecords);
    retrier.callWithRetry(() -> mutateZone(desiredRecordsCopy), ZoneStateException.class);
    logger.info("Wrote to Cloud DNS");
  }

  /**
   * Returns the glue records for in-bailiwick nameservers for the given domain+records.
   */
  private Stream<String> filterGlueRecords(String domainName, Stream<ResourceRecordSet> records) {
    return records
        .filter(record -> record.getType().equals("NS"))
        .flatMap(record -> record.getRrdatas().stream())
        .filter(hostName -> hostName.endsWith(domainName) && !hostName.equals(domainName));
  }

  /**
   * Mutate the zone with the provided {@code desiredRecords}.
   */
  @VisibleForTesting
  void mutateZone(ImmutableMap<String, ImmutableSet<ResourceRecordSet>> desiredRecords) {
    // Fetch all existing records for names that this writer is trying to modify
    ImmutableSet.Builder<ResourceRecordSet> flattenedExistingRecords = new ImmutableSet.Builder<>();

    // First, fetch the records for the given domains
    Map<String, List<ResourceRecordSet>> domainRecords =
        getResourceRecordsForDomains(desiredRecords.keySet());

    // add the records to the list of exiting records
    domainRecords.values().forEach(flattenedExistingRecords::addAll);

    // Get the glue record host names from the given records
    ImmutableSet<String> hostsToRead =
        domainRecords
            .entrySet()
            .stream()
            .flatMap(entry -> filterGlueRecords(entry.getKey(), entry.getValue().stream()))
            .collect(toImmutableSet());

    // Then fetch and add the records for these hosts
    getResourceRecordsForDomains(hostsToRead).values().forEach(flattenedExistingRecords::addAll);

    // Flatten the desired records into one set.
    ImmutableSet.Builder<ResourceRecordSet> flattenedDesiredRecords = new ImmutableSet.Builder<>();
    desiredRecords.values().forEach(flattenedDesiredRecords::addAll);

    // Delete all existing records and add back the desired records
    updateResourceRecords(flattenedDesiredRecords.build(), flattenedExistingRecords.build());
  }

  /**
   * Fetch the {@link ResourceRecordSet}s for the given domain names under this zone.
   *
   * <p>The provided domain should be in absolute form.
   */
  private Map<String, List<ResourceRecordSet>> getResourceRecordsForDomains(
      Set<String> domainNames) {
    logger.finefmt("Fetching records for %s", domainNames);
    // As per Concurrent.transform() - if numThreads or domainNames.size() < 2, it will not use
    // threading.
    return ImmutableMap.copyOf(
        Concurrent.transform(
            domainNames,
            numThreads,
            domainName ->
                new SimpleImmutableEntry<>(domainName, getResourceRecordsForDomain(domainName))));
  }

  /**
   * Fetch the {@link ResourceRecordSet}s for the given domain name under this zone.
   *
   * <p>The provided domain should be in absolute form.
   */
  private List<ResourceRecordSet> getResourceRecordsForDomain(String domainName) {
    // TODO(b/70217860): do we want to use a retrier here?
    try {
      Dns.ResourceRecordSets.List listRecordsRequest =
          dnsConnection.resourceRecordSets().list(projectId, zoneName).setName(domainName);

      rateLimiter.acquire();
      return listRecordsRequest.execute().getRrsets();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Update {@link ResourceRecordSet}s under this zone.
   *
   * <p>This call should be used in conjunction with {@link #getResourceRecordsForDomains} in a
   * get-and-set retry loop.
   *
   * <p>See {@link "https://cloud.google.com/dns/troubleshooting"} for a list of errors produced by
   * the Google Cloud DNS API.
   *
   * @throws ZoneStateException if the operation could not be completely successfully because the
   *     records to delete do not exist, already exist or have been modified with different
   *     attributes since being queried.
   */
  private void updateResourceRecords(
      ImmutableSet<ResourceRecordSet> additions, ImmutableSet<ResourceRecordSet> deletions) {
    // Find records that are both in additions and deletions, so we can remove them from both before
    // requesting the change. This is mostly for optimization reasons - not doing so doesn't affect
    // the result.
    ImmutableSet<ResourceRecordSet> intersection =
        Sets.intersection(additions, deletions).immutableCopy();
    logger.infofmt(
        "There are %s common items out of the %s items in 'additions' and %s items in 'deletions'",
        intersection.size(), additions.size(), deletions.size());
    // Exit early if we have nothing to update - dnsConnection doesn't work on empty changes
    if (additions.equals(deletions)) {
      logger.infofmt("Returning early because additions is the same as deletions");
      return;
    }
    Change change =
        new Change()
            .setAdditions(ImmutableList.copyOf(Sets.difference(additions, intersection)))
            .setDeletions(ImmutableList.copyOf(Sets.difference(deletions, intersection)));

    rateLimiter.acquire();
    try {
      dnsConnection.changes().create(projectId, zoneName, change).execute();
    } catch (GoogleJsonResponseException e) {
      List<ErrorInfo> errors = e.getDetails().getErrors();
      // We did something really wrong here, just give up and re-throw
      if (errors.size() > 1) {
        throw new RuntimeException(e);
      }
      String errorReason = errors.get(0).getReason();

      if (RETRYABLE_EXCEPTION_REASONS.contains(errorReason)) {
        throw new ZoneStateException(errorReason);
      } else {
        throw new RuntimeException(e);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the presentation format ending in a dot used for an absolute hostname.
   *
   * @param hostName the fully qualified hostname
   */
  private static String getAbsoluteHostName(String hostName) {
    return hostName.endsWith(".") ? hostName : hostName + ".";
  }

  private static ImmutableSet<String> multiplyAbsoluteName(String absoluteName) {
    return IntStream.range(0, 10)
        .mapToObj(i -> i == 0 ? absoluteName : String.format("%d-%s", i, absoluteName))
        .collect(ImmutableSet.toImmutableSet());
  }

  /** Zone state on Cloud DNS does not match the expected state. */
  static class ZoneStateException extends RuntimeException {
    public ZoneStateException(String reason) {
      super("Zone state on Cloud DNS does not match the expected state: " + reason);
    }
  }
}
