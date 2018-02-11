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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapAuthorization.Role.ADMINISTRATOR;
import static google.registry.rdap.RdapAuthorization.Role.REGISTRAR;
import static google.registry.request.Action.Method.POST;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistDomainAsDeleted;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResources;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static google.registry.testing.TestDataHelper.loadFile;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.Period;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import google.registry.ui.server.registrar.SessionUtils;
import google.registry.util.Idn;
import java.net.IDN;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdapDomainSearchAction}. */
@RunWith(JUnit4.class)
public class RdapDomainSearchActionTest extends RdapSearchActionTestCase {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01T00:00:00Z"));
  private final SessionUtils sessionUtils = mock(SessionUtils.class);
  private final User user = new User("rdap.user@example.com", "gmail.com", "12345");
  private final UserAuthInfo userAuthInfo = UserAuthInfo.create(user, false);
  private final UserAuthInfo adminUserAuthInfo = UserAuthInfo.create(user, true);
  private final RdapDomainSearchAction action = new RdapDomainSearchAction();

  private FakeResponse response = new FakeResponse();

  private Registrar registrar;
  private DomainResource domainCatLol;
  private DomainResource domainCatLol2;
  private DomainResource domainCatExample;
  private DomainResource domainIdn;
  private DomainResource domainMultipart;
  private ContactResource contact1;
  private ContactResource contact2;
  private ContactResource contact3;
  private HostResource hostNs1CatLol;
  private HostResource hostNs2CatLol;
  private HistoryEntry historyEntryCatLolCreate;
  private Map<String, HostResource> hostNameToHostMap = new HashMap<>();

  enum RequestType { NONE, NAME, NS_LDH_NAME, NS_IP }

  private Object generateActualJson(RequestType requestType, String paramValue) {
    return generateActualJson(requestType, paramValue, null);
  }

  private Object generateActualJson(
      RequestType requestType, String paramValue, String cursor) {
    action.requestPath = RdapDomainSearchAction.PATH;
    String requestTypeParam = null;
    switch (requestType) {
      case NAME:
        action.nameParam = Optional.of(paramValue);
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.empty();
        requestTypeParam = "name";
        break;
      case NS_LDH_NAME:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.of(paramValue);
        action.nsIpParam = Optional.empty();
        requestTypeParam = "nsLdhName";
        break;
      case NS_IP:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.of(paramValue);
        requestTypeParam = "nsIp";
        break;
      default:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.empty();
        requestTypeParam = "";
        break;
    }
    if (paramValue != null) {
      if (cursor == null) {
        action.parameterMap = ImmutableListMultimap.of(requestTypeParam, paramValue);
        action.cursorTokenParam = Optional.empty();
      } else {
        action.parameterMap =
            ImmutableListMultimap.of(requestTypeParam, paramValue, "cursor", cursor);
        action.cursorTokenParam = Optional.of(cursor);
      }
    }
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  private HostResource addHostToMap(HostResource host) {
    hostNameToHostMap.put(host.getFullyQualifiedHostName(), host);
    return host;
  }

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);

    // cat.lol and cat2.lol
    createTld("lol");
    registrar = persistResource(
        makeRegistrar("evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainCatLol = persistResource(
        makeDomainResource(
            "cat.lol",
            contact1 = makeAndPersistContactResource(
                "5372808-ERL",
                "Goblin Market",
                "lol@cat.lol",
                clock.nowUtc().minusYears(1),
                registrar),
            contact2 = makeAndPersistContactResource(
                "5372808-IRL",
                "Santa Claus",
                "BOFH@cat.lol",
                clock.nowUtc().minusYears(2),
                registrar),
            contact3 = makeAndPersistContactResource(
                "5372808-TRL",
                "The Raven",
                "bog@cat.lol",
                clock.nowUtc().minusYears(3),
                registrar),
            hostNs1CatLol = addHostToMap(makeAndPersistHostResource(
                "ns1.cat.lol",
                "1.2.3.4",
                clock.nowUtc().minusYears(1))),
            hostNs2CatLol = addHostToMap(makeAndPersistHostResource(
                "ns2.cat.lol",
                "bad:f00d:cafe:0:0:0:15:beef",
                clock.nowUtc().minusYears(2))),
            registrar)
        .asBuilder()
        .setSubordinateHosts(ImmutableSet.of("ns1.cat.lol", "ns2.cat.lol"))
        .setCreationTimeForTest(clock.nowUtc().minusYears(3))
        .build());
    persistResource(
        hostNs1CatLol.asBuilder().setSuperordinateDomain(Key.create(domainCatLol)).build());
    persistResource(
        hostNs2CatLol.asBuilder().setSuperordinateDomain(Key.create(domainCatLol)).build());
    domainCatLol2 = persistResource(
        makeDomainResource(
            "cat2.lol",
            makeAndPersistContactResource(
                "6372808-ERL",
                "Siegmund",
                "siegmund@cat2.lol",
                clock.nowUtc().minusYears(1),
                registrar),
            makeAndPersistContactResource(
                "6372808-IRL",
                "Sieglinde",
                "sieglinde@cat2.lol",
                clock.nowUtc().minusYears(2),
                registrar),
            makeAndPersistContactResource(
                "6372808-TRL",
                "Siegfried",
                "siegfried@cat2.lol",
                clock.nowUtc().minusYears(3),
                registrar),
            addHostToMap(makeAndPersistHostResource(
                "ns1.cat.example", "10.20.30.40", clock.nowUtc().minusYears(1))),
            addHostToMap(makeAndPersistHostResource(
                "ns2.dog.lol", "12:feed:5000:0:0:0:15:beef", clock.nowUtc().minusYears(2))),
            registrar)
        .asBuilder()
        .setCreationTimeForTest(clock.nowUtc().minusYears(3))
        .build());
    // cat.example
    createTld("example");
    registrar = persistResource(
        makeRegistrar("goodregistrar", "St. John Chrysostom", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainCatExample = persistResource(
        makeDomainResource(
            "cat.example",
            makeAndPersistContactResource(
                "7372808-ERL",
                "Matthew",
                "lol@cat.lol",
                clock.nowUtc().minusYears(1),
                registrar),
            makeAndPersistContactResource(
                "7372808-IRL",
                "Mark",
                "BOFH@cat.lol",
                clock.nowUtc().minusYears(2),
                registrar),
            makeAndPersistContactResource(
                "7372808-TRL",
                "Luke",
                "bog@cat.lol",
                clock.nowUtc().minusYears(3),
                registrar),
            hostNs1CatLol,
            addHostToMap(makeAndPersistHostResource(
                "ns2.external.tld", "bad:f00d:cafe:0:0:0:16:beef", clock.nowUtc().minusYears(2))),
            registrar)
        .asBuilder()
        .setCreationTimeForTest(clock.nowUtc().minusYears(3))
        .build());
    // cat.みんな
    createTld("xn--q9jyb4c");
    registrar = persistResource(makeRegistrar("unicoderegistrar", "みんな", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainIdn = persistResource(
        makeDomainResource(
            "cat.みんな",
            makeAndPersistContactResource(
                "8372808-ERL",
                "(◕‿◕)",
                "lol@cat.みんな",
                clock.nowUtc().minusYears(1),
                registrar),
            makeAndPersistContactResource(
                "8372808-IRL",
                "Santa Claus",
                "BOFH@cat.みんな",
                clock.nowUtc().minusYears(2),
                registrar),
            makeAndPersistContactResource(
                "8372808-TRL",
                "The Raven",
                "bog@cat.みんな",
                clock.nowUtc().minusYears(3),
                registrar),
            addHostToMap(makeAndPersistHostResource(
                "ns1.cat.みんな", "1.2.3.5", clock.nowUtc().minusYears(1))),
            addHostToMap(makeAndPersistHostResource(
                "ns2.cat.みんな", "bad:f00d:cafe:0:0:0:14:beef", clock.nowUtc().minusYears(2))),
            registrar)
        .asBuilder()
        .setCreationTimeForTest(clock.nowUtc().minusYears(3))
        .build());
    // cat.1.test
    createTld("1.test");
    registrar =
        persistResource(makeRegistrar("multiregistrar", "1.test", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainMultipart = persistResource(makeDomainResource(
            "cat.1.test",
            makeAndPersistContactResource(
                "9372808-ERL",
                "(◕‿◕)",
                "lol@cat.みんな",
                clock.nowUtc().minusYears(1),
                registrar),
            makeAndPersistContactResource(
                "9372808-IRL",
                "Santa Claus",
                "BOFH@cat.みんな",
                clock.nowUtc().minusYears(2),
                registrar),
            makeAndPersistContactResource(
                "9372808-TRL",
                "The Raven",
                "bog@cat.みんな",
                clock.nowUtc().minusYears(3),
                registrar),
            addHostToMap(makeAndPersistHostResource(
                "ns1.cat.1.test", "1.2.3.5", clock.nowUtc().minusYears(1))),
            addHostToMap(makeAndPersistHostResource(
                "ns2.cat.2.test", "bad:f00d:cafe:0:0:0:14:beef", clock.nowUtc().minusYears(2))),
            registrar)
        .asBuilder()
        .setSubordinateHosts(ImmutableSet.of("ns1.cat.1.test"))
        .setCreationTimeForTest(clock.nowUtc().minusYears(3))
        .build());

    persistResource(makeRegistrar("otherregistrar", "other", Registrar.State.ACTIVE));

    // history entries
    historyEntryCatLolCreate = persistResource(
        makeHistoryEntry(
            domainCatLol,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainCatLol2,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainCatExample,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainIdn,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainMultipart,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));

    action.clock = clock;
    action.request = request;
    action.requestMethod = Action.Method.GET;
    action.fullServletPath = "https://example.com/rdap";
    action.requestUrl = "https://example.com/rdap/domains";
    action.parameterMap = ImmutableListMultimap.of();
    action.requestMethod = POST;
    action.response = response;
    action.registrarParam = Optional.empty();
    action.includeDeletedParam = Optional.empty();
    action.formatOutputParam = Optional.empty();
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter();
    action.rdapWhoisServer = null;
    action.sessionUtils = sessionUtils;
    action.authResult = AuthResult.create(AuthLevel.USER, userAuthInfo);
    action.rdapMetrics = rdapMetrics;
    action.cursorTokenParam = Optional.empty();
    action.rdapResultSetMaxSize = 4;
  }

  private void login(String clientId) {
    when(sessionUtils.checkRegistrarConsoleLogin(request, userAuthInfo)).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(request)).thenReturn(clientId);
    metricRole = REGISTRAR;
  }

  private void loginAsAdmin() {
    when(sessionUtils.checkRegistrarConsoleLogin(request, adminUserAuthInfo)).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(request)).thenReturn("irrelevant");
    action.authResult = AuthResult.create(AuthLevel.USER, adminUserAuthInfo);
    metricRole = ADMINISTRATOR;
  }

  private Object generateExpectedJsonForTwoDomains() {
    return generateExpectedJsonForTwoDomains("cat.example", "21-EXAMPLE", "cat.lol", "C-LOL");
  }

  private Object generateExpectedJsonForTwoDomains(
      String domain1Name,
      String domain1Handle,
      String domain2Name,
      String domain2Handle) {
    return JSONValue.parse(loadFile(
        this.getClass(),
        "rdap_domains_two.json",
        ImmutableMap.of(
            "TYPE", "domain name",
            "DOMAINNAME1", domain1Name,
            "DOMAINHANDLE1", domain1Handle,
            "DOMAINNAME2", domain2Name,
            "DOMAINHANDLE2", domain2Handle)));
  }

  private Object generateExpectedJsonForFourDomains(
      String domain1Name,
      String domain1Handle,
      String domain2Name,
      String domain2Handle,
      String domain3Name,
      String domain3Handle,
      String domain4Name,
      String domain4Handle,
      String expectedOutputFile) {
    return generateExpectedJsonForFourDomains(
        domain1Name,
        domain1Handle,
        domain2Name,
        domain2Handle,
        domain3Name,
        domain3Handle,
        domain4Name,
        domain4Handle,
        "none",
        expectedOutputFile);
  }

  private Object generateExpectedJsonForFourDomains(
      String domain1Name,
      String domain1Handle,
      String domain2Name,
      String domain2Handle,
      String domain3Name,
      String domain3Handle,
      String domain4Name,
      String domain4Handle,
      String nextQuery,
      String expectedOutputFile) {
    return JSONValue.parse(
        loadFile(
            this.getClass(),
            expectedOutputFile,
            new ImmutableMap.Builder<String, String>()
                .put("TYPE", "domain name")
                .put("DOMAINPUNYCODENAME1", domain1Name)
                .put("DOMAINNAME1", IDN.toUnicode(domain1Name))
                .put("DOMAINHANDLE1", domain1Handle)
                .put("DOMAINPUNYCODENAME2", domain2Name)
                .put("DOMAINNAME2", IDN.toUnicode(domain2Name))
                .put("DOMAINHANDLE2", domain2Handle)
                .put("DOMAINPUNYCODENAME3", domain3Name)
                .put("DOMAINNAME3", IDN.toUnicode(domain3Name))
                .put("DOMAINHANDLE3", domain3Handle)
                .put("DOMAINPUNYCODENAME4", domain4Name)
                .put("DOMAINNAME4", IDN.toUnicode(domain4Name))
                .put("DOMAINHANDLE4", domain4Handle)
                .put("NEXT_QUERY", nextQuery)
                .build()));
  }

  private Object generateExpectedJson(String name, String expectedOutputFile) {
    return generateExpectedJson(name, null, null, null, null, expectedOutputFile);
  }

  private Object generateExpectedJson(
      String name,
      String punycodeName,
      String handle,
      @Nullable List<String> contactRoids,
      @Nullable List<String> nameservers,
      String expectedOutputFile) {
    ImmutableMap.Builder<String, String> substitutionsBuilder = new ImmutableMap.Builder<>();
    substitutionsBuilder.put("NAME", name);
    substitutionsBuilder.put("PUNYCODENAME", (punycodeName == null) ? name : punycodeName);
    substitutionsBuilder.put("HANDLE", (handle == null) ? "(null)" : handle);
    substitutionsBuilder.put("TYPE", "domain name");
    if (contactRoids != null) {
      for (int i = 0; i < contactRoids.size(); i++) {
        substitutionsBuilder.put("CONTACT" + (i + 1) + "ROID", contactRoids.get(i));
      }
    }
    if (nameservers != null) {
      for (int i = 0; i < nameservers.size(); i++) {
        HostResource host = checkNotNull(hostNameToHostMap.get(nameservers.get(i)));
        substitutionsBuilder.put("NAMESERVER" + (i + 1) + "PUNYCODENAME", nameservers.get(i));
        substitutionsBuilder.put(
            "NAMESERVER" + (i + 1) + "NAME", Idn.toUnicode(nameservers.get(i)));
        substitutionsBuilder.put("NAMESERVER" + (i + 1) + "ROID", host.getRepoId());
        substitutionsBuilder.put(
            "NAMESERVER" + (i + 1) + "ADDRESS",
            InetAddresses.toAddrString(host.getInetAddresses().asList().get(0)));
      }
    }
    return JSONValue.parse(
        loadFile(this.getClass(), expectedOutputFile, substitutionsBuilder.build()));
  }

  private Object generateExpectedJsonForDomain(
      String name,
      String punycodeName,
      String handle,
      @Nullable List<String> contactRoids,
      @Nullable List<String> nameservers,
      String expectedOutputFile) {
    Object obj =
        generateExpectedJson(
            name, punycodeName, handle, contactRoids, nameservers, expectedOutputFile);
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    builder.put("domainSearchResults", ImmutableList.of(obj));
    builder.put("rdapConformance", ImmutableList.of("rdap_level_0"));
    RdapTestHelper.addNotices(builder, "https://example.com/rdap/");
    RdapTestHelper.addDomainBoilerplateRemarks(builder);
    return new JSONObject(builder.build());
  }

  private void deleteCatLol() {
    persistResource(
        domainCatLol
            .asBuilder()
            .setCreationTimeForTest(clock.nowUtc().minusYears(1))
            .setDeletionTime(clock.nowUtc().minusMonths(6))
            .build());
    persistResource(
        historyEntryCatLolCreate
            .asBuilder()
            .setModificationTime(clock.nowUtc().minusYears(1))
            .build());
    persistResource(
        makeHistoryEntry(
            domainCatLol,
            HistoryEntry.Type.DOMAIN_DELETE,
            Period.create(1, Period.Unit.YEARS),
            "deleted",
            clock.nowUtc().minusMonths(6)));
  }

  private void createManyDomainsAndHosts(
      int numActiveDomains, int numTotalDomainsPerActiveDomain, int numHosts) {
    ImmutableSet.Builder<Key<HostResource>> hostKeysBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> subordinateHostnamesBuilder = new ImmutableSet.Builder<>();
    String mainDomainName = String.format("domain%d.lol", numTotalDomainsPerActiveDomain);
    for (int i = numHosts; i >= 1; i--) {
      String hostName = String.format("ns%d.%s", i, mainDomainName);
      subordinateHostnamesBuilder.add(hostName);
      HostResource host = makeAndPersistHostResource(
          hostName, String.format("5.5.%d.%d", 5 + i / 250, i % 250), clock.nowUtc().minusYears(1));
      hostKeysBuilder.add(Key.create(host));
    }
    ImmutableSet<Key<HostResource>> hostKeys = hostKeysBuilder.build();
    // Create all the domains at once, then persist them in parallel, for increased efficiency.
    ImmutableList.Builder<DomainResource> domainsBuilder = new ImmutableList.Builder<>();
    for (int i = numActiveDomains * numTotalDomainsPerActiveDomain; i >= 1; i--) {
      String domainName = String.format("domain%d.lol", i);
      DomainResource.Builder builder =
          makeDomainResource(
              domainName, contact1, contact2, contact3, null, null, registrar)
          .asBuilder()
          .setNameservers(hostKeys)
          .setCreationTimeForTest(clock.nowUtc().minusYears(3));
      if (domainName.equals(mainDomainName)) {
        builder.setSubordinateHosts(subordinateHostnamesBuilder.build());
      }
      if (i % numTotalDomainsPerActiveDomain != 0) {
        builder = builder.setDeletionTime(clock.nowUtc().minusDays(1));
      }
      domainsBuilder.add(builder.build());
    }
    persistResources(domainsBuilder.build());
  }

  private Object readMultiDomainFile(
      String fileName,
      String domainName1,
      String domainHandle1,
      String domainName2,
      String domainHandle2,
      String domainName3,
      String domainHandle3,
      String domainName4,
      String domainHandle4) {
    return readMultiDomainFile(
        fileName,
        domainName1,
        domainHandle1,
        domainName2,
        domainHandle2,
        domainName3,
        domainHandle3,
        domainName4,
        domainHandle4,
        "none");
  }

  private Object readMultiDomainFile(
      String fileName,
      String domainName1,
      String domainHandle1,
      String domainName2,
      String domainHandle2,
      String domainName3,
      String domainHandle3,
      String domainName4,
      String domainHandle4,
      String nextQuery) {
    return JSONValue.parse(loadFile(
        this.getClass(),
        fileName,
        new ImmutableMap.Builder<String, String>()
            .put("DOMAINNAME1", domainName1)
            .put("DOMAINHANDLE1", domainHandle1)
            .put("DOMAINNAME2", domainName2)
            .put("DOMAINHANDLE2", domainHandle2)
            .put("DOMAINNAME3", domainName3)
            .put("DOMAINHANDLE3", domainHandle3)
            .put("DOMAINNAME4", domainName4)
            .put("DOMAINHANDLE4", domainHandle4)
            .put("NEXT_QUERY", nextQuery)
            .build()));
  }

  private void checkNumberOfDomainsInResult(Object obj, int expected) {
    assertThat(obj).isInstanceOf(Map.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) obj;

    @SuppressWarnings("unchecked")
    List<Object> domains = (List<Object>) map.get("domainSearchResults");

    assertThat(domains).hasSize(expected);
  }

  private void runSuccessfulTestWithCatLol(
      RequestType requestType, String queryString, String fileName) {
    runSuccessfulTest(
        requestType,
        queryString,
        "cat.lol",
        null,
        "C-LOL",
        ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
        ImmutableList.of("ns1.cat.lol", "ns2.cat.lol"),
        fileName);
  }

  private void runSuccessfulTestWithCat2Lol(
      RequestType requestType, String queryString, String fileName) {
    runSuccessfulTest(
        requestType,
        queryString,
        "cat2.lol",
        null,
        "17-LOL",
        ImmutableList.of("F-ROID", "11-ROID", "D-ROID"),
        ImmutableList.of("ns1.cat.example", "ns2.dog.lol"),
        fileName);
  }

  private void runSuccessfulTest(
      RequestType requestType,
      String queryString,
      String name,
      String punycodeName,
      String handle,
      @Nullable List<String> contactRoids,
      @Nullable List<String> nameservers,
      String fileName) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString))
        .isEqualTo(
            generateExpectedJsonForDomain(
                name, punycodeName, handle, contactRoids, nameservers, fileName));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runSuccessfulTestWithFourDomains(
      RequestType requestType,
      String queryString,
      String domainRoid1,
      String domainRoid2,
      String domainRoid3,
      String domainRoid4,
      String fileName) {
    runSuccessfulTestWithFourDomains(
        requestType,
        queryString,
        domainRoid1,
        domainRoid2,
        domainRoid3,
        domainRoid4,
        "none",
        fileName);
  }

  private void runSuccessfulTestWithFourDomains(
      RequestType requestType,
      String queryString,
      String domainRoid1,
      String domainRoid2,
      String domainRoid3,
      String domainRoid4,
      String nextQuery,
      String fileName) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString))
        .isEqualTo(
            readMultiDomainFile(
                fileName,
                "domain1.lol",
                domainRoid1,
                "domain2.lol",
                domainRoid2,
                "domain3.lol",
                domainRoid3,
                "domain4.lol",
                domainRoid4,
                nextQuery));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runNotFoundTest(
      RequestType requestType, String queryString, String errorMessage) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString))
        .isEqualTo(generateExpectedJson(errorMessage, "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  private void verifyMetrics(SearchType searchType, Optional<Long> numDomainsRetrieved) {
    verifyMetrics(
        searchType, numDomainsRetrieved, Optional.empty(), IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(searchType, numDomainsRetrieved, Optional.empty(), incompletenessWarningType);
  }

  private void verifyMetrics(
      SearchType searchType, Optional<Long> numDomainsRetrieved, Optional<Long> numHostsRetrieved) {
    verifyMetrics(
        searchType, numDomainsRetrieved, numHostsRetrieved, IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType, long numDomainsRetrieved, long numHostsRetrieved) {
    verifyMetrics(
        searchType,
        Optional.of(numDomainsRetrieved),
        Optional.of(numHostsRetrieved),
        IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    metricSearchType = searchType;
    verifyMetrics(
        EndpointType.DOMAINS,
        POST,
        action.includeDeletedParam.orElse(false),
        action.registrarParam.isPresent(),
        numDomainsRetrieved,
        numHostsRetrieved,
        Optional.empty(),
        incompletenessWarningType);
  }

  private void verifyErrorMetrics(SearchType searchType) {
    verifyErrorMetrics(searchType, Optional.of(0L), Optional.empty(), 404);
  }

  private void verifyErrorMetrics(
      SearchType searchType, Optional<Long> numDomainsRetrieved, int statusCode) {
    verifyErrorMetrics(searchType, numDomainsRetrieved, Optional.empty(), statusCode);
  }

  private void verifyErrorMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      int statusCode) {
    metricStatusCode = statusCode;
    verifyMetrics(searchType, numDomainsRetrieved, numHostsRetrieved);
  }

  /**
   * Checks multi-page result set navigation using the cursor.
   *
   * <p>If there are more results than the max result set size, the RDAP code returns a cursor token
   * which can be used in a subsequent call to get the next chunk of results.
   *
   * @param requestType the type of query (name, nameserver name or nameserver address)
   * @param paramValue the query string
   * @param expectedNames an immutable list of the domain names we expect to retrieve
   */
  private void checkCursorNavigation(
      RequestType requestType, String paramValue, ImmutableList<String> expectedNames)
      throws Exception {
    String cursor = null;
    int expectedNameOffset = 0;
    int expectedPageCount =
        (expectedNames.size() + action.rdapResultSetMaxSize - 1) / action.rdapResultSetMaxSize;
    for (int pageNum = 0; pageNum < expectedPageCount; pageNum++) {
      Object results = generateActualJson(requestType, paramValue, cursor);
      assertThat(response.getStatus()).isEqualTo(200);
      String linkToNext = RdapTestHelper.getLinkToNext(results);
      if (pageNum == expectedPageCount - 1) {
        assertThat(linkToNext).isNull();
      } else {
        assertThat(linkToNext).isNotNull();
        int pos = linkToNext.indexOf("cursor=");
        assertThat(pos).isAtLeast(0);
        cursor = URLDecoder.decode(linkToNext.substring(pos + 7), "UTF-8");
        Object searchResults = ((JSONObject) results).get("domainSearchResults");
        assertThat(searchResults).isInstanceOf(JSONArray.class);
        assertThat(((JSONArray) searchResults)).hasSize(action.rdapResultSetMaxSize);
        for (Object item : ((JSONArray) searchResults)) {
          assertThat(item).isInstanceOf(JSONObject.class);
          Object name = ((JSONObject) item).get("ldhName");
          assertThat(name).isNotNull();
          assertThat(name).isInstanceOf(String.class);
          assertThat(name).isEqualTo(expectedNames.get(expectedNameOffset++));
        }
        response = new FakeResponse();
        action.response = response;
      }
    }
  }

  @Test
  public void testInvalidPath_rejected() throws Exception {
    action.requestPath = RdapDomainSearchAction.PATH + "/path";
    action.run();
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.NONE, Optional.empty(), 400);
  }

  @Test
  public void testInvalidRequest_rejected() throws Exception {
    assertThat(generateActualJson(RequestType.NONE, null))
        .isEqualTo(generateExpectedJson(
            "You must specify either name=XXXX, nsLdhName=YYYY or nsIp=ZZZZ",
            "rdap_error_400.json"));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.NONE, Optional.empty(), 400);
  }

  @Test
  public void testInvalidWildcard_rejected() throws Exception {
    assertThat(generateActualJson(RequestType.NAME, "exam*ple"))
        .isEqualTo(generateExpectedJson(
            "Suffix after wildcard must be one or more domain"
                + " name labels, e.g. exam*.tld, ns*.example.tld",
            "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testMultipleWildcards_rejected() throws Exception {
    assertThat(generateActualJson(RequestType.NAME, "*.*"))
        .isEqualTo(generateExpectedJson("Only one wildcard allowed", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNoCharactersToMatch_rejected() throws Exception {
    rememberWildcardType("*");
    assertThat(generateActualJson(RequestType.NAME, "*"))
        .isEqualTo(
            generateExpectedJson(
                "Initial search string is required for wildcard domain searches without a TLD"
                    + " suffix",
                "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testFewerThanTwoCharactersToMatch_rejected() throws Exception {
    rememberWildcardType("a*");
    assertThat(generateActualJson(RequestType.NAME, "a*"))
        .isEqualTo(
            generateExpectedJson(
                "Initial search string must be at least 2 characters for wildcard domain searches"
                    + " without a TLD suffix",
                "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testDomainMatch_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_foundWithUpperCase() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "CaT.lOl", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_found_sameRegistrarRequested() throws Exception {
    login("evilregistrar");
    action.registrarParam = Optional.of("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_notFound_differentRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_found_asAdministrator() throws Exception {
    loginAsAdmin();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_found_loggedInAsOtherRegistrar() throws Exception {
    login("otherregistrar");
    runSuccessfulTestWithCatLol(
        RequestType.NAME, "cat.lol", "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  /*
   * This test is flaky because IDN.toASCII may or may not remove the trailing dot of its own
   * accord. If it does, the test will pass.
   */
  @Ignore
  @Test
  public void testDomainMatchWithTrailingDot_notFound() throws Exception {
    runNotFoundTest(RequestType.NAME, "cat.lol.", "No domains found");
  }

  @Test
  public void testDomainMatch_cat2_lol_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCat2Lol(RequestType.NAME, "cat2.lol", "rdap_domain_cat2.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_example_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTest(
        RequestType.NAME,
        "cat.example",
        "cat.example",
        null,
        "21-EXAMPLE",
        null,
        ImmutableList.of("ns1.cat.lol", "ns2.external.tld"),
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_idn_unicode_found() throws Exception {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.みんな",
        "cat.みんな",
        "cat.xn--q9jyb4c",
        "2D-Q9JYB4C",
        null,
        ImmutableList.of("ns1.cat.xn--q9jyb4c", "ns2.cat.xn--q9jyb4c"),
        "rdap_domain_unicode_no_contacts_with_remark.json");
    // The unicode gets translated to ASCII before getting parsed into a search pattern.
    metricPrefixLength = 15;
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_idn_punycode_found() throws Exception {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.xn--q9jyb4c",
        "cat.みんな",
        "cat.xn--q9jyb4c",
        "2D-Q9JYB4C",
        null,
        ImmutableList.of("ns1.cat.xn--q9jyb4c", "ns2.cat.xn--q9jyb4c"),
        "rdap_domain_unicode_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_1_test_found() throws Exception {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.1.test",
        "cat.1.test",
        null,
        "39-1_TEST",
        ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
        ImmutableList.of("ns1.cat.1.test", "ns2.cat.2.test"),
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_castar_1_test_found() throws Exception {
    runSuccessfulTest(
        RequestType.NAME,
        "ca*.1.test",
        "cat.1.test",
        null,
        "39-1_TEST",
        ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
        ImmutableList.of("ns1.cat.1.test", "ns2.cat.2.test"),
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_castar_test_notFound() throws Exception {
    runNotFoundTest(RequestType.NAME, "ca*.test", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_catstar_lol_found() throws Exception {
    rememberWildcardType("cat*.lol");
    assertThat(generateActualJson(RequestType.NAME, "cat*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains("cat.lol", "C-LOL", "cat2.lol", "17-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_cstar_lol_found() throws Exception {
    rememberWildcardType("c*.lol");
    assertThat(generateActualJson(RequestType.NAME, "c*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains("cat.lol", "C-LOL", "cat2.lol", "17-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_qstar_lol_notFound() throws Exception {
    runNotFoundTest(RequestType.NAME, "q*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_star_lol_found() throws Exception {
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains("cat.lol", "C-LOL", "cat2.lol", "17-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_star_lol_found_sameRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("evilregistrar");
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains("cat.lol", "C-LOL", "cat2.lol", "17-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_star_lol_notFound_differentRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    rememberWildcardType("*.lol");
    runNotFoundTest(RequestType.NAME, "*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_cat_star_found() throws Exception {
    rememberWildcardType("cat.*");
    assertThat(generateActualJson(RequestType.NAME, "cat.*"))
        .isEqualTo(
            generateExpectedJsonForFourDomains(
                "cat.1.test", "39-1_TEST",
                "cat.example", "21-EXAMPLE",
                "cat.lol", "C-LOL",
                "cat.xn--q9jyb4c", "2D-Q9JYB4C",
                "rdap_domains_four_with_one_unicode.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(4L));
  }

  @Test
  public void testDomainMatch_cat_star_foundOne_sameRegistrarRequested() throws Exception {
    login("evilregistrar");
    action.registrarParam = Optional.of("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_star_notFound_differentRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NAME, "cat.*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_cat_lstar_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_catstar_found() throws Exception {
    rememberWildcardType("cat*");
    assertThat(generateActualJson(RequestType.NAME, "cat*"))
        .isEqualTo(
            generateExpectedJsonForFourDomains(
                "cat.1.test", "39-1_TEST",
                "cat.example", "21-EXAMPLE",
                "cat.lol", "C-LOL",
                "cat.xn--q9jyb4c", "2D-Q9JYB4C",
                "name=cat*&cursor=Y2F0LnhuLS1xOWp5YjRj",
                "rdap_domains_four_with_one_unicode_truncated.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatchWithWildcardAndEmptySuffix_fails() throws Exception {
    // Unfortunately, we can't be sure which error is going to be returned. The version of
    // IDN.toASCII used in Eclipse drops a trailing dot, if any. But the version linked in by
    // Blaze throws an error in that situation. So just check that it returns an error.
    rememberWildcardType("exam*..");
    generateActualJson(RequestType.NAME, "exam*..");
    assertThat(response.getStatus()).isIn(Range.closed(400, 499));
  }

  @Test
  public void testDomainMatch_dog_notFound() throws Exception {
    runNotFoundTest(RequestType.NAME, "dog*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound() throws Exception {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound_deletedNotRequested() throws Exception {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_found_loggedInAsSameRegistrar() throws Exception {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound_loggedInAsOtherRegistrar() throws Exception {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L), 404);
  }

  @Test
  public void testDomainMatchDeletedDomain_found_loggedInAsAdmin() throws Exception {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatchDeletedDomainWithWildcard_notFound() throws Exception {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lo*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L), 404);
  }

  @Test
  public void testDomainMatchDeletedDomainsWithWildcardAndTld_notFound() throws Exception {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatLol2, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testDomainMatchDomainInTestTld_notFound() throws Exception {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_fullResultSet() throws Exception {
    // There are enough domains to fill a full result set; deleted domains are ignored.
    createManyDomainsAndHosts(4, 4, 2);
    rememberWildcardType("domain*.lol");
    Object obj = generateActualJson(RequestType.NAME, "domain*.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 4);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(16L));
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_partialResultSetDueToInsufficientDomains()
      throws Exception {
    // There are not enough domains to fill a full result set.
    createManyDomainsAndHosts(3, 20, 2);
    rememberWildcardType("domain*.lol");
    Object obj = generateActualJson(RequestType.NAME, "domain*.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(60L));
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_partialResultSetDueToFetchingLimit()
      throws Exception {
    // This is not exactly desired behavior, but expected: There are enough domains to fill a full
    // result set, but there are so many deleted domains that we run out of patience before we work
    // our way through all of them.
    createManyDomainsAndHosts(4, 50, 2);
    rememberWildcardType("domain*.lol");
    assertThat(generateActualJson(RequestType.NAME, "domain*.lol"))
        .isEqualTo(readMultiDomainFile(
            "rdap_incomplete_domain_result_set.json",
            "domain100.lol",
            "A7-LOL",
            "domain150.lol",
            "75-LOL",
            "domain200.lol",
            "43-LOL",
            "domainunused.lol",
            "unused-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(
        SearchType.BY_DOMAIN_NAME,
        Optional.of(120L),
        IncompletenessWarningType.MIGHT_BE_INCOMPLETE);
  }

  @Test
  public void testDomainMatch_nontruncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(4L));
  }

  @Test
  public void testDomainMatch_truncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "name=domain*.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_tldSearchOrderedProperly() throws Exception {
    createManyDomainsAndHosts(4, 1, 2);
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(readMultiDomainFile(
            "rdap_domains_four_truncated.json",
            "cat.lol",
            "C-LOL",
            "cat2.lol",
            "17-LOL",
            "domain1.lol",
            "46-LOL",
            "domain2.lol",
            "45-LOL",
            "name=*.lol&cursor=ZG9tYWluMi5sb2w%3D"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_reallyTruncatedResultsSet() throws Exception {
    // Don't use 10 or more domains for this test, because domain10.lol will come before
    // domain2.lol, and you'll get the wrong domains in the result set.
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "name=domain*.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_truncatedResultsAfterMultipleChunks() throws Exception {
    createManyDomainsAndHosts(5, 6, 2);
    rememberWildcardType("domain*.lol");
    assertThat(generateActualJson(RequestType.NAME, "domain*.lol"))
        .isEqualTo(readMultiDomainFile(
            "rdap_domains_four_truncated.json",
            "domain12.lol",
            "55-LOL",
            "domain18.lol",
            "4F-LOL",
            "domain24.lol",
            "49-LOL",
            "domain30.lol",
            "43-LOL",
            "name=domain*.lol&cursor=ZG9tYWluMzAubG9s"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(27L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_cursorNavigationWithInitialString() throws Exception {
    createManyDomainsAndHosts(11, 1, 2);
    checkCursorNavigation(
        RequestType.NAME,
        "domain*.lol",
        ImmutableList.of(
            "domain1.lol",
            "domain10.lol",
            "domain11.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol",
            "domain9.lol"));
  }

  @Test
  public void testDomainMatch_cursorNavigationWithTldSuffix() throws Exception {
    createManyDomainsAndHosts(11, 1, 2);
    checkCursorNavigation(
        RequestType.NAME,
        "*.lol",
        ImmutableList.of(
            "cat.lol",
            "cat2.lol",
            "domain1.lol",
            "domain10.lol",
            "domain11.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol",
            "domain9.lol"));
  }

  @Test
  public void testNameserverMatch_foundMultiple() throws Exception {
    rememberWildcardType("ns1.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 1);
  }

  @Test
  public void testNameserverMatch_foundMultiple_sameRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("ns1.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 1);
  }

  @Test
  public void testNameserverMatch_notFound_differentRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithWildcard_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
 }

  @Test
  public void testNameserverMatchWithWildcard_found_sameRegistrarRequested() throws Exception {
    login("evilregistrar");
    action.registrarParam = Optional.of("TheRegistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchWithWildcard_notFound_differentRegistrarRequested()
      throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.l*", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithWildcardAndDomainSuffix_notFound() throws Exception {
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns5*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithNoPrefixAndDomainSuffix_found() throws Exception {
    rememberWildcardType("*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithOneCharacterPrefixAndDomainSuffix_found()
      throws Exception {
    rememberWildcardType("n*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "n*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void
      testNameserverMatchWithOneCharacterPrefixAndDomainSuffix_found_sameRegistrarRequested()
          throws Exception {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("n*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "n*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithPrefixAndDomainSuffix_notFound_differentRegistrarRequested()
      throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "n*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithTwoCharacterPrefixAndDomainSuffix_found()
      throws Exception {
    rememberWildcardType("ns*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithWildcardAndEmptySuffix_unprocessable() throws Exception {
    rememberWildcardType("ns*.");
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatchWithWildcardAndInvalidSuffix_unprocessable() throws Exception {
    rememberWildcardType("ns*.google.com");
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.google.com");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatch_ns2_cat_lol_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns2_dog_lol_found() throws Exception {
    login("evilregistrar");
    runSuccessfulTestWithCat2Lol(RequestType.NS_LDH_NAME, "ns2.dog.lol", "rdap_domain_cat2.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns1_cat_idn_unicode_badRequest() throws Exception {
    // nsLdhName must use punycode.
    metricWildcardType = WildcardType.INVALID;
    metricPrefixLength = 0;
    generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.みんな");
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 400);
  }

  @Test
  public void testNameserverMatch_ns1_cat_idn_punycode_found() throws Exception {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns1.cat.xn--q9jyb4c",
        "cat.みんな",
        "cat.xn--q9jyb4c",
        "2D-Q9JYB4C",
        null,
        ImmutableList.of("ns1.cat.xn--q9jyb4c", "ns2.cat.xn--q9jyb4c"),
        "rdap_domain_unicode_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns1_cat_1_test_found() throws Exception {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns1.cat.1.test",
        "cat.1.test",
        null,
        "39-1_TEST",
        ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
        ImmutableList.of("ns1.cat.1.test", "ns2.cat.2.test"),
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_nsstar_cat_1_test_found() throws Exception {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns*.cat.1.test",
        "cat.1.test",
        null,
        "39-1_TEST",
        ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
        ImmutableList.of("ns1.cat.1.test", "ns2.cat.2.test"),
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_nsstar_test_unprocessable() throws Exception {
    rememberWildcardType("ns*.1.test");
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.1.test");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatchMissing_notFound() throws Exception {
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.missing.com", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testNameserverMatchDomainsInTestTld_notFound() throws Exception {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No matching nameservers found");
  }

  @Test
  public void testNameserverMatchDeletedDomain_notFound() throws Exception {
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedDomain_found_loggedInAsSameRegistrar() throws Exception {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchDeletedDomain_notFound_loggedInAsOtherRegistrar()
      throws Exception {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedDomain_found_loggedInAsAdmin() throws Exception {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchOneDeletedDomain_foundTheOther() throws Exception {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns1.cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchTwoDeletedDomains_notFound() throws Exception {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserver_notFound() throws Exception {
    persistResource(
        hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserverWithWildcard_notFound() throws Exception {
    persistResource(
        hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.l*", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserverWithWildcardAndSuffix_notFound()
      throws Exception {
    persistResource(
        hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomains() throws Exception {
    // 40 nameservers for each of 3 domains; we should get back all three undeleted domains, because
    // each one references the nameserver.
    createManyDomainsAndHosts(3, 1, 40);
    rememberWildcardType("ns1.domain1.lol");
    Object obj = generateActualJson(RequestType.NS_LDH_NAME, "ns1.domain1.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomainsWithWildcard() throws Exception {
    // Same as above, except with a wildcard (that still only finds one nameserver).
    createManyDomainsAndHosts(3, 1, 40);
    rememberWildcardType("ns1.domain1.l*");
    Object obj = generateActualJson(RequestType.NS_LDH_NAME, "ns1.domain1.l*");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomainsWithSuffix() throws Exception {
    // Same as above, except that we find all 40 nameservers because of the wildcard. But we
    // should still only return 3 domains, because we merge duplicate domains together in a set.
    // Since we fetch domains by nameserver in batches of 30 nameservers, we need to make sure to
    // have more than that number of nameservers for an effective test.
    createManyDomainsAndHosts(3, 1, 40);
    rememberWildcardType("ns*.domain1.lol");
    Object obj = generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(40L));
  }

  @Test
  public void testNameserverMatch_nontruncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(4L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatch_truncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "nsLdhName=ns1.domain1.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(5L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testNameserverMatch_reallyTruncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "nsLdhName=ns1.domain1.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(9L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testNameserverMatch_duplicatesNotTruncated() throws Exception {
    // 60 nameservers for each of 4 domains; these should translate into 2 30-nameserver domain
    // fetches, which should _not_ trigger the truncation warning because all the domains will be
    // duplicates.
    createManyDomainsAndHosts(4, 1, 60);
    rememberWildcardType("ns*.domain1.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol"))
        .isEqualTo(readMultiDomainFile(
            "rdap_nontruncated_domains.json",
            "domain1.lol",
            "BA-LOL",
            "domain2.lol",
            "B9-LOL",
            "domain3.lol",
            "B8-LOL",
            "domain4.lol",
            "B7-LOL"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(4L), Optional.of(60L));
  }

  @Test
  public void testNameserverMatch_incompleteResultsSet() throws Exception {
    createManyDomainsAndHosts(2, 1, 2500);
    rememberWildcardType("ns*.domain1.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol"))
        .isEqualTo(readMultiDomainFile(
            "rdap_incomplete_domains.json",
            "domain1.lol",
            "13C8-LOL",
            "domain2.lol",
            "13C7-LOL",
            "x",
            "x",
            "x",
            "x"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(2L),
        Optional.of(2500L),
        IncompletenessWarningType.MIGHT_BE_INCOMPLETE);
  }

  @Test
  public void testNameserverMatch_cursorNavigation() throws Exception {
    createManyDomainsAndHosts(8, 1, 2);
    checkCursorNavigation(
        RequestType.NS_LDH_NAME,
        "ns*.domain1.lol",
        ImmutableList.of(
            "domain1.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol"));
  }

  @Test
  public void testAddressMatchV4Address_invalidAddress() throws Exception {
    rememberWildcardType("1.2.3.4.5.6.7.8.9");
    generateActualJson(RequestType.NS_IP, "1.2.3.4.5.6.7.8.9");
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), 400);
  }

  @Test
  public void testAddressMatchV4Address_foundMultiple() throws Exception {
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 2, 1);
  }

  @Test
  public void testAddressMatchV4Address_foundMultiple_sameRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(generateExpectedJsonForTwoDomains());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 2, 1);
  }

  @Test
  public void testAddressMatchV4Address_notFound_differentRegistrarRequested() throws Exception {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testAddressMatchV6Address_foundOne() throws Exception {
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP,
        "bad:f00d:cafe:0:0:0:15:beef",
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchLocalhost_notFound() throws Exception {
    runNotFoundTest(RequestType.NS_IP, "127.0.0.1", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testAddressMatchDomainsInTestTld_notFound() throws Exception {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    persistResource(Registry.get("example").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NS_IP, "127.0.0.1", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS);
  }

  @Test
  public void testAddressMatchDeletedDomain_notFound() throws Exception {
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runNotFoundTest(RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedDomain_found_loggedInAsSameRegistrar() throws Exception {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchDeletedDomain_notFound_loggedInAsOtherRegistrar() throws Exception {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedDomain_found_loggedInAsAdmin() throws Exception {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchOneDeletedDomain_foundTheOther() throws Exception {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(
            generateExpectedJsonForDomain(
                "cat.lol",
                null,
                "C-LOL",
                ImmutableList.of("4-ROID", "6-ROID", "2-ROID"),
                ImmutableList.of("ns1.cat.lol", "ns2.cat.lol"),
                "rdap_domain.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchTwoDeletedDomains_notFound() throws Exception {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedNameserver_notFound() throws Exception {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testAddressMatch_nontruncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 4, 1);
  }

  @Test
  public void testAddressMatch_truncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "nsIp=5.5.5.1&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_ADDRESS,
        Optional.of(5L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testAddressMatch_reallyTruncatedResultsSet() throws Exception {
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "nsIp=5.5.5.1&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_ADDRESS,
        Optional.of(9L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testAddressMatch_cursorNavigation() throws Exception {
    createManyDomainsAndHosts(7, 1, 2);
    checkCursorNavigation(
        RequestType.NS_IP,
        "5.5.5.1",
        ImmutableList.of(
            "domain1.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol"));
  }
}
