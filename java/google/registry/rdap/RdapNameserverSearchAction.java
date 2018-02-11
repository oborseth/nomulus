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

import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Booleans;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.rdap.RdapJsonFormatter.BoilerplateType;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.Idn;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * RDAP (new WHOIS) action for nameserver search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in RFCs 7480 through 7485.
 *
 * @see <a href="http://tools.ietf.org/html/rfc7482">RFC 7482: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc7483">RFC 7483: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
@Action(
  path = RdapNameserverSearchAction.PATH,
  method = {GET, HEAD},
  auth = Auth.AUTH_PUBLIC_ANONYMOUS
)
public class RdapNameserverSearchAction extends RdapSearchActionBase {

  public static final String PATH = "/rdap/nameservers";

  @Inject Clock clock;
  @Inject @Parameter("name") Optional<String> nameParam;
  @Inject @Parameter("ip") Optional<String> ipParam;
  @Inject RdapNameserverSearchAction() {}

  @Override
  public String getHumanReadableObjectTypeName() {
    return "nameserver search";
  }

  @Override
  public EndpointType getEndpointType() {
    return EndpointType.NAMESERVERS;
  }

  @Override
  public String getActionPath() {
    return PATH;
  }

  private enum CursorType {
    NAME,
    ADDRESS
  }

  /**
   * Parses the parameters and calls the appropriate search function.
   *
   * <p>The RDAP spec allows nameserver search by either name or IP address.
   */
  @Override
  public ImmutableMap<String, Object> getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest) {
    DateTime now = clock.nowUtc();
    // RDAP syntax example: /rdap/nameservers?name=ns*.example.com.
    // The pathSearchString is not used by search commands.
    if (pathSearchString.length() > 0) {
      throw new BadRequestException("Unexpected path");
    }
    if (Booleans.countTrue(nameParam.isPresent(), ipParam.isPresent()) != 1) {
      throw new BadRequestException("You must specify either name=XXXX or ip=YYYY");
    }
    decodeCursorToken();
    RdapSearchResults results;
    if (nameParam.isPresent()) {
      // syntax: /rdap/nameservers?name=exam*.com
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_NAME);
      if (!LDH_PATTERN.matcher(nameParam.get()).matches()) {
        throw new BadRequestException(
            "Name parameter must contain only letters, dots"
                + " and hyphens, and an optional single wildcard");
      }
      results =
          searchByName(
              recordWildcardType(RdapSearchPattern.create(Idn.toASCII(nameParam.get()), true)),
              now);
    } else {
      // syntax: /rdap/nameservers?ip=1.2.3.4
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_ADDRESS);
      InetAddress inetAddress;
      try {
        inetAddress = InetAddresses.forString(ipParam.get());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Invalid value of ip parameter");
      }
      results = searchByIp(inetAddress, now);
    }
    if (results.jsonList().isEmpty()) {
      throw new NotFoundException("No nameservers found");
    }
    ImmutableMap.Builder<String, Object> jsonBuilder = new ImmutableMap.Builder<>();
    jsonBuilder.put("nameserverSearchResults", results.jsonList());

    rdapJsonFormatter.addTopLevelEntries(
        jsonBuilder,
        BoilerplateType.NAMESERVER,
        getNotices(results),
        ImmutableList.of(),
        fullServletPath);
    return jsonBuilder.build();
  }

  /**
   * Searches for nameservers by name, returning a JSON array of nameserver info maps.
   *
   * <p>When deleted nameservers are included in the search, the search is treated as if it has a
   * wildcard, because multiple results can be returned.
   */
  private RdapSearchResults searchByName(
      final RdapSearchPattern partialStringQuery, final DateTime now) {
    // Handle queries without a wildcard -- just load by foreign key. We can't do this if deleted
    // nameservers are desired, because there may be multiple nameservers with the same name.
    if (!partialStringQuery.getHasWildcard() && !shouldIncludeDeleted()) {
      return searchByNameUsingForeignKey(partialStringQuery, now);
    // Handle queries with a wildcard (or including deleted entries). If there is a suffix, it
    // should be a domain that we manage, so we can look up the domain and search through the
    // subordinate hosts. This is more efficient, and lets us permit wildcard searches with no
    // initial string. Deleted nameservers cannot be searched using a suffix, because the logic
    // of the deletion status of the superordinate domain versus the deletion status of the
    // subordinate host gets too messy.
    } else if (partialStringQuery.getSuffix() != null) {
      if (shouldIncludeDeleted()) {
        throw new UnprocessableEntityException(
            "A suffix after a wildcard is not allowed when searching for deleted nameservers");
      }
      return searchByNameUsingSuperordinateDomain(partialStringQuery, now);
    // Handle queries with a wildcard (or deleted entries included), but no suffix.
    } else {
      return searchByNameUsingPrefix(partialStringQuery, now);
    }
  }

  /**
   * Searches for nameservers by name with no wildcard or deleted names.
   *
   * <p>In this case, we can load by foreign key.
   */
  private RdapSearchResults searchByNameUsingForeignKey(
      final RdapSearchPattern partialStringQuery, final DateTime now) {
    HostResource hostResource =
        loadByForeignKey(HostResource.class, partialStringQuery.getInitialString(), now);
    if ((hostResource == null) || !shouldBeVisible(hostResource, now)) {
      metricInformationBuilder.setNumHostsRetrieved(0);
      throw new NotFoundException("No nameservers found");
    }
    metricInformationBuilder.setNumHostsRetrieved(1);
    return RdapSearchResults.create(
        ImmutableList.of(
            rdapJsonFormatter.makeRdapJsonForHost(
                hostResource, false, fullServletPath, rdapWhoisServer, now, OutputDataType.FULL)));
  }

  /** Searches for nameservers by name using the superordinate domain as a suffix. */
  private RdapSearchResults searchByNameUsingSuperordinateDomain(
      final RdapSearchPattern partialStringQuery, final DateTime now) {
    DomainResource domainResource =
        loadByForeignKey(DomainResource.class, partialStringQuery.getSuffix(), now);
    if (domainResource == null) {
      // Don't allow wildcards with suffixes which are not domains we manage. That would risk a
      // table scan in many easily foreseeable cases. The user might ask for ns*.zombo.com,
      // forcing us to query for all hosts beginning with ns, then filter for those ending in
      // .zombo.com. It might well be that 80% of all hostnames begin with ns, leading to
      // inefficiency.
      throw new UnprocessableEntityException(
          "A suffix after a wildcard in a nameserver lookup must be an in-bailiwick domain");
    }
    List<HostResource> hostList = new ArrayList<>();
    for (String fqhn : ImmutableSortedSet.copyOf(domainResource.getSubordinateHosts())) {
      if (cursorString.isPresent() && (fqhn.compareTo(cursorString.get()) <= 0)) {
        continue;
      }
      // We can't just check that the host name starts with the initial query string, because
      // then the query ns.exam*.example.com would match against nameserver ns.example.com.
      if (partialStringQuery.matches(fqhn)) {
        HostResource hostResource = loadByForeignKey(HostResource.class, fqhn, now);
        if ((hostResource != null) && shouldBeVisible(hostResource, now)) {
          hostList.add(hostResource);
          if (hostList.size() > rdapResultSetMaxSize) {
            break;
          }
        }
      }
    }
    return makeSearchResults(
        hostList,
        IncompletenessWarningType.COMPLETE,
        domainResource.getSubordinateHosts().size(),
        CursorType.NAME,
        now);
  }

  /**
   * Searches for nameservers by name with a prefix and wildcard.
   *
   * <p>There are no pending deletes for hosts, so we can call {@link RdapActionBase#queryItems}.
   */
  private RdapSearchResults searchByNameUsingPrefix(
      final RdapSearchPattern partialStringQuery, final DateTime now) {
    // Add 1 so we can detect truncation.
    int querySizeLimit = getStandardQuerySizeLimit();
    Query<HostResource> query =
        queryItems(
            HostResource.class,
            "fullyQualifiedHostName",
            partialStringQuery,
            cursorString,
            getDeletedItemHandling(),
            querySizeLimit);
    return makeSearchResults(
        getMatchingResources(query, shouldIncludeDeleted(), now, querySizeLimit),
        CursorType.NAME,
        now);
  }

  /** Searches for nameservers by IP address, returning a JSON array of nameserver info maps. */
  private RdapSearchResults searchByIp(final InetAddress inetAddress, DateTime now) {
    // Add 1 so we can detect truncation.
    int querySizeLimit = getStandardQuerySizeLimit();
    Query<HostResource> query =
        queryItems(
                HostResource.class,
                "inetAddresses",
                inetAddress.getHostAddress(),
                Optional.empty(),
                cursorString,
                getDeletedItemHandling(),
                querySizeLimit);
    return makeSearchResults(
        getMatchingResources(query, shouldIncludeDeleted(), now, querySizeLimit),
        CursorType.ADDRESS,
        now);
  }

  /** Output JSON for a lists of hosts contained in an {@link RdapResultSet}. */
  private RdapSearchResults makeSearchResults(
      RdapResultSet<HostResource> resultSet, CursorType cursorType, DateTime now) {
    return makeSearchResults(
        resultSet.resources(),
        resultSet.incompletenessWarningType(),
        resultSet.numResourcesRetrieved(),
        cursorType,
        now);
  }

  /** Output JSON for a list of hosts. */
  private RdapSearchResults makeSearchResults(
      List<HostResource> hosts,
      IncompletenessWarningType incompletenessWarningType,
      int numHostsRetrieved,
      CursorType cursorType,
      DateTime now) {
    metricInformationBuilder.setNumHostsRetrieved(numHostsRetrieved);
    OutputDataType outputDataType =
        (hosts.size() > 1) ? OutputDataType.SUMMARY : OutputDataType.FULL;
    ImmutableList.Builder<ImmutableMap<String, Object>> jsonListBuilder =
        new ImmutableList.Builder<>();
    Optional<String> newCursor = Optional.empty();
    for (HostResource host : Iterables.limit(hosts, rdapResultSetMaxSize)) {
      newCursor =
          Optional.of(
              (cursorType == CursorType.NAME)
                  ? host.getFullyQualifiedHostName()
                  : host.getRepoId());
      jsonListBuilder.add(
          rdapJsonFormatter.makeRdapJsonForHost(
              host, false, fullServletPath, rdapWhoisServer, now, outputDataType));
    }
    ImmutableList<ImmutableMap<String, Object>> jsonList = jsonListBuilder.build();
    if (jsonList.size() < hosts.size()) {
      return RdapSearchResults.create(jsonList, IncompletenessWarningType.TRUNCATED, newCursor);
    } else {
      return RdapSearchResults.create(jsonList, incompletenessWarningType, Optional.empty());
    }
  }
}
