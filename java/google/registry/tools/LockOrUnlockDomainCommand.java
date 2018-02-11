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
import static google.registry.model.eppcommon.StatusValue.SERVER_DELETE_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_TRANSFER_PROHIBITED;
import static google.registry.model.eppcommon.StatusValue.SERVER_UPDATE_PROHIBITED;
import static google.registry.util.CollectionUtils.findDuplicates;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppcommon.StatusValue;
import java.util.List;

/** Shared base class for commands to registry lock or unlock a domain via EPP. */
public abstract class LockOrUnlockDomainCommand extends MutatingEppToolCommand {

  protected static final ImmutableSet<StatusValue> REGISTRY_LOCK_STATUSES =
      ImmutableSet.of(
          SERVER_DELETE_PROHIBITED, SERVER_TRANSFER_PROHIBITED, SERVER_UPDATE_PROHIBITED);

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true
  )
  String clientId;

  @Parameter(description = "Names of the domains", required = true)
  private List<String> mainParameters;

  protected ImmutableSet<String> getDomains() {
    return ImmutableSet.copyOf(mainParameters);
  }

  @Override
  protected void initEppToolCommand() throws Exception {
    // Superuser status is required to update registry lock statuses.
    superuser = true;
    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate domain arguments found: '%s'", duplicates);
    initMutatingEppToolCommand();
    System.out.println(
        "== ENSURE THAT YOU HAVE AUTHENTICATED THE REGISTRAR BEFORE RUNNING THIS COMMAND ==");
  }
}
