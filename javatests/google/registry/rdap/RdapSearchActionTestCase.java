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

package google.registry.rdap;

import static google.registry.rdap.RdapAuthorization.Role.PUBLIC;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import java.util.Optional;

public class RdapSearchActionTestCase {

  RdapAuthorization.Role metricRole = PUBLIC;
  SearchType metricSearchType = SearchType.NONE;
  WildcardType metricWildcardType = WildcardType.INVALID;
  int metricPrefixLength = 0;
  int metricStatusCode = SC_OK;
  final RdapMetrics rdapMetrics = mock(RdapMetrics.class);

  void rememberWildcardType(String queryString) {
    try {
      RdapSearchPattern partialStringQuery = RdapSearchPattern.create(queryString, true);
      if (!partialStringQuery.getHasWildcard()) {
        metricWildcardType = WildcardType.NO_WILDCARD;
      } else if (partialStringQuery.getSuffix() == null) {
        metricWildcardType = WildcardType.PREFIX;
      } else if (partialStringQuery.getInitialString().isEmpty()) {
        metricWildcardType = WildcardType.SUFFIX;
      } else {
        metricWildcardType = WildcardType.PREFIX_AND_SUFFIX;
      }
      metricPrefixLength = partialStringQuery.getInitialString().length();
    } catch (Exception e) {
      metricWildcardType = WildcardType.INVALID;
      metricPrefixLength = 0;
    }
  }

  void verifyMetrics(
      EndpointType endpointType,
      Action.Method requestMethod,
      boolean includeDeleted,
      boolean registrarSpecified,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      Optional<Long> numContactsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    RdapMetrics.RdapMetricInformation.Builder builder =
        RdapMetrics.RdapMetricInformation.builder()
            .setEndpointType(endpointType)
            .setSearchType(metricSearchType)
            .setWildcardType(metricWildcardType)
            .setPrefixLength(metricPrefixLength)
            .setIncludeDeleted(includeDeleted)
            .setRegistrarSpecified(registrarSpecified)
            .setRole(metricRole)
            .setRequestMethod(requestMethod)
            .setStatusCode(metricStatusCode)
            .setIncompletenessWarningType(incompletenessWarningType);
    numDomainsRetrieved.ifPresent(builder::setNumDomainsRetrieved);
    numHostsRetrieved.ifPresent(builder::setNumHostsRetrieved);
    numContactsRetrieved.ifPresent(builder::setNumContactsRetrieved);
    verify(rdapMetrics).updateMetrics(builder.build());
  }
}
