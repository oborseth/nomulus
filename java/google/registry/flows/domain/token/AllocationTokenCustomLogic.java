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

package google.registry.flows.domain.token;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.model.domain.AllocationToken;
import google.registry.model.registry.Registry;

/**
 * A no-op base class for allocation token custom logic.
 *
 * <p>Extend this class and override the hook(s) to perform custom logic.
 */
public class AllocationTokenCustomLogic {

  /** Performs additional custom logic for verifying a token. */
  public AllocationToken verifyToken(
      InternetDomainName domainName, AllocationToken token, Registry registry, String clientId)
      throws EppException {
    // Do nothing.
    return token;
  }

  /** Performs additional custom logic for performing domain checks using a token. */
  public ImmutableMap<String, String> checkDomainsWithToken(
      ImmutableMap<String, String> checkResults, AllocationToken tokenEntity, String clientId) {
    // Do nothing.
    return checkResults;
  }
}
