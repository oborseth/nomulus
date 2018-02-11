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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistDeletedContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static google.registry.testing.TestDataHelper.loadFile;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactResource;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
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
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdapEntityAction}. */
@RunWith(JUnit4.class)
public class RdapEntityActionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  private final SessionUtils sessionUtils = mock(SessionUtils.class);
  private final User user = new User("rdap.user@example.com", "gmail.com", "12345");
  private final UserAuthInfo userAuthInfo = UserAuthInfo.create(user, false);
  private final UserAuthInfo adminUserAuthInfo = UserAuthInfo.create(user, true);
  private final RdapMetrics rdapMetrics = mock(RdapMetrics.class);

  private RdapEntityAction action;

  private Registrar registrarLol;
  private ContactResource registrant;
  private ContactResource adminContact;
  private ContactResource techContact;
  private ContactResource disconnectedContact;
  private ContactResource deletedContact;

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);
    // lol
    createTld("lol");
    registrarLol = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE, 101L));
    persistSimpleResources(makeRegistrarContacts(registrarLol));
    registrant = makeAndPersistContactResource(
        "8372808-REG",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc(),
        registrarLol);
    adminContact = makeAndPersistContactResource(
        "8372808-ADM",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc(),
        registrarLol);
    techContact = makeAndPersistContactResource(
        "8372808-TEC",
        "(◕‿◕)",
        "lol@cat.みんな",
        ImmutableList.of("1 Smiley Row", "Suite みんな"),
        clock.nowUtc(),
        registrarLol);
    HostResource host1 =
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    HostResource host2 =
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe:0:0:0:15:beef"));
    persistResource(makeDomainResource("cat.lol",
        registrant,
        adminContact,
        techContact,
        host1,
        host2,
        registrarLol));
    // xn--q9jyb4c
    createTld("xn--q9jyb4c");
    Registrar registrarIdn = persistResource(
        makeRegistrar("idnregistrar", "IDN Registrar", Registrar.State.ACTIVE, 102L));
    persistSimpleResources(makeRegistrarContacts(registrarIdn));
    // 1.tld
    createTld("1.tld");
    Registrar registrar1tld = persistResource(
        makeRegistrar("1tldregistrar", "Multilevel Registrar", Registrar.State.ACTIVE, 103L));
    persistSimpleResources(makeRegistrarContacts(registrar1tld));
    // deleted registrar
    Registrar registrarDeleted = persistResource(
        makeRegistrar("deletedregistrar", "Yes Virginia <script>", Registrar.State.PENDING, 104L));
    persistSimpleResources(makeRegistrarContacts(registrarDeleted));
    // other contacts
    disconnectedContact =
        makeAndPersistContactResource(
            "8372808-DIS",
            "(◕‿◕)",
            "lol@cat.みんな",
            ImmutableList.of("1 Smiley Row", "Suite みんな"),
            clock.nowUtc(),
            registrarLol);
    deletedContact =
        makeAndPersistDeletedContactResource(
            "8372808-DEL",
            clock.nowUtc().minusYears(1),
            registrarLol,
            clock.nowUtc().minusMonths(6));
    action = new RdapEntityAction();
    action.clock = clock;
    action.request = request;
    action.requestMethod = Action.Method.GET;
    action.fullServletPath = "https://example.com/rdap";
    action.response = response;
    action.registrarParam = Optional.empty();
    action.includeDeletedParam = Optional.empty();
    action.formatOutputParam = Optional.empty();
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter();
    action.rdapWhoisServer = null;
    action.sessionUtils = sessionUtils;
    action.authResult = AuthResult.create(AuthLevel.USER, userAuthInfo);
    action.rdapMetrics = rdapMetrics;
  }

  private void login(String registrar) {
    when(sessionUtils.checkRegistrarConsoleLogin(request, userAuthInfo)).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(request)).thenReturn(registrar);
  }

  private void loginAsAdmin() {
    action.authResult = AuthResult.create(AuthLevel.USER, adminUserAuthInfo);
    when(sessionUtils.checkRegistrarConsoleLogin(request, adminUserAuthInfo)).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(request)).thenReturn("irrelevant");
  }

  private Object generateActualJson(String name) {
    action.requestPath = RdapEntityAction.PATH + name;
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  private Object generateExpectedJson(String handle, String expectedOutputFile) {
    return generateExpectedJson(handle, "(◕‿◕)", "", expectedOutputFile);
  }

  private Object generateExpectedJson(
      String handle,
      String status,
      String expectedOutputFile) {
    return generateExpectedJson(handle, "(◕‿◕)", status, expectedOutputFile);
  }

  private Object generateExpectedJson(
      String handle,
      String fullName,
      String status,
      String expectedOutputFile) {
    return generateExpectedJson(
        handle, fullName, status, null, expectedOutputFile);
  }

  private Object generateExpectedJson(
      String handle,
      String fullName,
      String status,
      @Nullable String address,
      String expectedOutputFile) {
    return JSONValue.parse(
        loadFile(
            this.getClass(),
            expectedOutputFile,
            new ImmutableMap.Builder<String, String>()
                .put("NAME", handle)
                .put("FULLNAME", fullName)
                .put("ADDRESS", (address == null) ? "\"1 Smiley Row\", \"Suite みんな\"" : address)
                .put("EMAIL", "lol@cat.みんな")
                .put("TYPE", "entity")
                .put("STATUS", status)
                .build()));
  }

  private Object generateExpectedJsonWithTopLevelEntries(
      String handle,
      String expectedOutputFile) {
    return generateExpectedJsonWithTopLevelEntries(
        handle, "(◕‿◕)", "active", null, false, expectedOutputFile);
  }

  private Object generateExpectedJsonWithTopLevelEntries(
      String handle,
      String fullName,
      String status,
      String address,
      boolean addNoPersonalDataRemark,
      String expectedOutputFile) {
    Object obj = generateExpectedJson(handle, fullName, status, address, expectedOutputFile);
    if (obj instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) obj;
      ImmutableMap.Builder<String, Object> builder =
          RdapTestHelper.getBuilderExcluding(
              map, ImmutableSet.of("rdapConformance", "notices", "remarks"));
      builder.put("rdapConformance", ImmutableList.of("rdap_level_0"));
      RdapTestHelper.addNotices(
          builder,
          "https://example.com/rdap/",
          addNoPersonalDataRemark
              ? RdapTestHelper.ContactNoticeType.CONTACT
              : RdapTestHelper.ContactNoticeType.NONE,
          map.get("notices"));
      RdapTestHelper.addNonDomainBoilerplateRemarks(builder, map.get("remarks"));
      obj = builder.build();
    }
    return obj;
  }

  private void runSuccessfulTest(String queryString, String fileName) {
    runSuccessfulTest(queryString, "(◕‿◕)", "active", null, false, fileName);
  }

  private void runSuccessfulTest(String queryString, String fullName, String fileName) {
    runSuccessfulTest(queryString, fullName, "active", null, false, fileName);
  }

  private void runSuccessfulTest(
      String queryString,
      String fullName,
      String rdapStatus,
      String address,
      boolean addNoPersonalDataRemark,
      String fileName) {
    assertThat(generateActualJson(queryString))
        .isEqualTo(
            generateExpectedJsonWithTopLevelEntries(
                queryString, fullName, rdapStatus, address, addNoPersonalDataRemark, fileName));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runNotFoundTest(String queryString) {
    assertThat(generateActualJson(queryString))
        .isEqualTo(generateExpectedJson(queryString + " not found", "", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testInvalidEntity_returns400() throws Exception {
    assertThat(generateActualJson("invalid/entity/handle")).isEqualTo(
        generateExpectedJson(
            "invalid/entity/handle is not a valid entity handle",
            "rdap_error_400.json"));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testUnknownEntity_notFound() throws Exception {
    runNotFoundTest("_MISSING-ENTITY_");
  }

  @Test
  public void testValidRegistrantContact_works() throws Exception {
    login("evilregistrar");
    runSuccessfulTest(registrant.getRepoId(), "rdap_associated_contact.json");
  }

  @Test
  public void testValidRegistrantContact_found_sameRegistrarRequested() throws Exception {
    login("evilregistrar");
    action.registrarParam = Optional.of("evilregistrar");
    runSuccessfulTest(registrant.getRepoId(), "rdap_associated_contact.json");
  }

  @Test
  public void testValidRegistrantContact_notFound_differentRegistrarRequested() throws Exception {
    login("evilregistrar");
    action.registrarParam = Optional.of("idnregistrar");
    runNotFoundTest(registrant.getRepoId());
  }

  @Test
  public void testValidRegistrantContact_found_asAdministrator() throws Exception {
    loginAsAdmin();
    runSuccessfulTest(registrant.getRepoId(), "rdap_associated_contact.json");
  }

  @Test
  public void testValidRegistrantContact_found_notLoggedIn() throws Exception {
    runSuccessfulTest(
        registrant.getRepoId(),
        "(◕‿◕)",
        "active",
        null,
        true,
        "rdap_associated_contact_no_personal_data.json");
  }

  @Test
  public void testValidRegistrantContact_found_loggedInAsOtherRegistrar() throws Exception {
    login("otherregistrar");
    runSuccessfulTest(
        registrant.getRepoId(),
        "(◕‿◕)",
        "active",
        null,
        true,
        "rdap_associated_contact_no_personal_data.json");
  }

  @Test
  public void testValidAdminContact_works() throws Exception {
    login("evilregistrar");
    runSuccessfulTest(adminContact.getRepoId(), "rdap_associated_contact.json");
  }

  @Test
  public void testValidTechContact_works() throws Exception {
    login("evilregistrar");
    runSuccessfulTest(techContact.getRepoId(), "rdap_associated_contact.json");
  }

  @Test
  public void testValidDisconnectedContact_works() throws Exception {
    login("evilregistrar");
    runSuccessfulTest(disconnectedContact.getRepoId(), "rdap_contact.json");
  }

  @Test
  public void testDeletedContact_notFound() throws Exception {
    runNotFoundTest(deletedContact.getRepoId());
  }

  @Test
  public void testDeletedContact_notFound_includeDeletedSetFalse() throws Exception {
    action.includeDeletedParam = Optional.of(false);
    runNotFoundTest(deletedContact.getRepoId());
  }

  @Test
  public void testDeletedContact_notFound_notLoggedIn() throws Exception {
    action.includeDeletedParam = Optional.of(true);
    runNotFoundTest(deletedContact.getRepoId());
  }

  @Test
  public void testDeletedContact_notFound_loggedInAsDifferentRegistrar() throws Exception {
    login("idnregistrar");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundTest(deletedContact.getRepoId());
  }

  @Test
  public void testDeletedContact_found_loggedInAsCorrectRegistrar() throws Exception {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulTest(
        deletedContact.getRepoId(),
        "",
        "removed",
        "",
        false,
        "rdap_contact_deleted.json");
  }

  @Test
  public void testDeletedContact_found_loggedInAsAdmin() throws Exception {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulTest(
        deletedContact.getRepoId(),
        "",
        "removed",
        "",
        false,
        "rdap_contact_deleted.json");
  }

  @Test
  public void testRegistrar_found() throws Exception {
    runSuccessfulTest("101", "Yes Virginia <script>", "rdap_registrar.json");
  }

  @Test
  public void testRegistrar102_works() throws Exception {
    runSuccessfulTest("102", "IDN Registrar", "rdap_registrar.json");
  }

  @Test
  public void testRegistrar102_found_requestingSameRegistrar() throws Exception {
    action.registrarParam = Optional.of("idnregistrar");
    runSuccessfulTest("102", "IDN Registrar", "rdap_registrar.json");
  }

  @Test
  public void testRegistrar102_notFound_requestingOtherRegistrar() throws Exception {
    action.registrarParam = Optional.of("1tldregistrar");
    runNotFoundTest("102");
  }

  @Test
  public void testRegistrar103_works() throws Exception {
    runSuccessfulTest("103", "Multilevel Registrar", "rdap_registrar.json");
  }

  @Test
  public void testRegistrar104_notFound() throws Exception {
    runNotFoundTest("104");
  }

  @Test
  public void testRegistrar104_notFound_deletedFlagWhenNotLoggedIn() throws Exception {
    action.includeDeletedParam = Optional.of(true);
    runNotFoundTest("104");
  }

  @Test
  public void testRegistrar104_found_deletedFlagWhenLoggedIn() throws Exception {
    login("deletedregistrar");
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulTest(
        "104", "Yes Virginia <script>", "removed", null, false, "rdap_registrar.json");
  }

  @Test
  public void testRegistrar104_notFound_deletedFlagWhenLoggedInAsOther() throws Exception {
    login("1tldregistrar");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundTest("104");
  }

  @Test
  public void testRegistrar104_found_deletedFlagWhenLoggedInAsAdmin() throws Exception {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulTest(
        "104", "Yes Virginia <script>", "removed", null, false, "rdap_registrar.json");
  }

  @Test
  public void testRegistrar105_doesNotExist() throws Exception {
    runNotFoundTest("105");
  }

  @Test
  public void testQueryParameter_ignored() throws Exception {
    login("evilregistrar");
    assertThat(generateActualJson(techContact.getRepoId() + "?key=value")).isEqualTo(
        generateExpectedJsonWithTopLevelEntries(
            techContact.getRepoId(), "rdap_associated_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testMetrics() throws Exception {
    generateActualJson(registrant.getRepoId());
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.ENTITY)
                .setSearchType(SearchType.NONE)
                .setWildcardType(WildcardType.INVALID)
                .setPrefixLength(0)
                .setIncludeDeleted(false)
                .setRegistrarSpecified(false)
                .setRole(RdapAuthorization.Role.PUBLIC)
                .setRequestMethod(Action.Method.GET)
                .setStatusCode(200)
                .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
                .build());
  }
}
