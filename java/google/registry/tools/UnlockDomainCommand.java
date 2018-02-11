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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.util.FormattingLogger.getLoggerForCallerClass;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.tools.soy.DomainUpdateSoyInfo;
import google.registry.util.FormattingLogger;
import org.joda.time.DateTime;

/**
 * A command to registry unlock domain names via EPP.
 *
 * <p>A registry lock consists of server-side statuses preventing deletes, updates, and transfers.
 */
@Parameters(separators = " =", commandDescription = "Registry unlock a domain via EPP.")
public class UnlockDomainCommand extends LockOrUnlockDomainCommand {

  private static final FormattingLogger logger = getLoggerForCallerClass();

  @Override
  protected void initMutatingEppToolCommand() throws Exception {
    // Project all domains as of the same time so that argument order doesn't affect behavior.
    DateTime now = DateTime.now(UTC);
    for (String domain : getDomains()) {
      DomainResource domainResource = loadByForeignKey(DomainResource.class, domain, now);
      checkArgument(domainResource != null, "Domain '%s' does not exist", domain);
      ImmutableSet<StatusValue> statusesToRemove =
          Sets.intersection(domainResource.getStatusValues(), REGISTRY_LOCK_STATUSES)
              .immutableCopy();
      if (statusesToRemove.isEmpty()) {
        logger.infofmt("Domain '%s' is already unlocked and needs no updates.", domain);
        continue;
      }

      setSoyTemplate(DomainUpdateSoyInfo.getInstance(), DomainUpdateSoyInfo.DOMAINUPDATE);
      addSoyRecord(
          clientId,
          new SoyMapData(
              "domain", domain,
              "add", false,
              "addNameservers", ImmutableList.of(),
              "addAdmins", ImmutableList.of(),
              "addTechs", ImmutableList.of(),
              "addStatuses", ImmutableList.of(),
              "remove", true,
              "removeNameservers", ImmutableList.of(),
              "removeAdmins", ImmutableList.of(),
              "removeTechs", ImmutableList.of(),
              "removeStatuses",
                  statusesToRemove.stream().map(StatusValue::getXmlName).collect(toImmutableList()),
              "change", false,
              "secdns", false,
              "addDsRecords", ImmutableList.of(),
              "removeDsRecords", ImmutableList.of(),
              "removeAllDsRecords", false));
    }
  }
}
