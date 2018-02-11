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

package google.registry.flows.contact;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.getContactAutomaticTransferLength;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.assertPollMessagesEqual;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.JUnitBackports.expectThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.exceptions.AlreadyPendingTransferException;
import google.registry.flows.exceptions.MissingTransferRequestAuthInfoException;
import google.registry.flows.exceptions.ObjectAlreadySponsoredException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link ContactTransferRequestFlow}. */
public class ContactTransferRequestFlowTest
    extends ContactTransferFlowTestCase<ContactTransferRequestFlow, ContactResource> {

  public ContactTransferRequestFlowTest() {
    // We need the transfer to happen at exactly this time in order for the response to match up.
    clock.setTo(DateTime.parse("2000-06-08T22:00:00.0Z"));
  }

  @Before
  public void setUp() throws Exception {
    setEppInput("contact_transfer_request.xml");
    setClientIdForFlow("NewRegistrar");
    contact = persistActiveContact("sh8013");
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);
    DateTime afterTransfer = clock.nowUtc().plus(getContactAutomaticTransferLength());

    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));

    // Transfer should have been requested. Verify correct fields were set.
    contact = reloadResourceByForeignKey();
    assertAboutContacts().that(contact)
        .hasCurrentSponsorClientId("TheRegistrar").and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.CONTACT_TRANSFER_REQUEST);
    Trid expectedTrid =
        Trid.create(
            getClientTrid(),
            contact.getTransferData().getTransferRequestTrid().getServerTransactionId());
    assertThat(contact.getTransferData())
        .isEqualTo(
            new TransferData.Builder()
                .setTransferRequestTrid(expectedTrid)
                .setTransferRequestTime(clock.nowUtc())
                .setGainingClientId("NewRegistrar")
                .setLosingClientId("TheRegistrar")
                // Period is meaningless for contact transfers, but this is the default.
                .setTransferPeriod(Period.create(1, Unit.YEARS))
                .setTransferStatus(TransferStatus.PENDING)
                .setPendingTransferExpirationTime(afterTransfer)
                // Make the server-approve entities field a no-op comparison; it's easier to
                // do this comparison separately below.
                .setServerApproveEntities(contact.getTransferData().getServerApproveEntities())
                .build());
    assertNoBillingEvents();
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc())).hasSize(1);
    PollMessage losingRequestMessage =
        getOnlyElement(getPollMessages("TheRegistrar", clock.nowUtc()));

    // If we fast forward AUTOMATIC_TRANSFER_DAYS the transfer should have happened.
    assertAboutContacts().that(contact.cloneProjectedAtTime(afterTransfer))
        .hasCurrentSponsorClientId("NewRegistrar");
    assertThat(getPollMessages("NewRegistrar", afterTransfer)).hasSize(1);
    assertThat(getPollMessages("TheRegistrar", afterTransfer)).hasSize(2);
    PollMessage gainingApproveMessage =
        getOnlyElement(getPollMessages("NewRegistrar", afterTransfer));
    PollMessage losingApproveMessage =
        getPollMessages("TheRegistrar", afterTransfer)
            .stream()
            .filter(not(equalTo(losingRequestMessage)))
            .collect(onlyElement());

    // Check for TransferData server-approve entities containing what we expect: only
    // poll messages, the approval notice ones for gaining and losing registrars.
    assertPollMessagesEqual(
        Iterables.filter(
            ofy().load()
                // Use toArray() to coerce the type to something keys() will accept.
                .keys(contact.getTransferData().getServerApproveEntities().toArray(new Key<?>[]{}))
                .values(),
            PollMessage.class),
        ImmutableList.of(gainingApproveMessage, losingApproveMessage));
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  public void testDryRun() throws Exception {
    setEppInput("contact_transfer_request.xml");
    dryRunFlowAssertResponse(loadFile("contact_transfer_request_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testFailure_noAuthInfo() throws Exception {
    EppException thrown =
        expectThrows(
            MissingTransferRequestAuthInfoException.class,
            () -> doFailingTest("contact_transfer_request_no_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_badPassword() throws Exception {
    // Change the contact's password so it does not match the password in the file.
    contact =
        persistResource(
            contact
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("badpassword")))
                .build());
    EppException thrown =
        expectThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_clientApproved() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testSuccess_clientRejected() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testSuccess_clientCancelled() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testSuccess_serverApproved() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testSuccess_serverCancelled() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doSuccessfulTest("contact_transfer_request.xml", "contact_transfer_request_response.xml");
  }

  @Test
  public void testFailure_pending() throws Exception {
    contact =
        persistResource(
            contact
                .asBuilder()
                .setTransferData(
                    contact
                        .getTransferData()
                        .asBuilder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setPendingTransferExpirationTime(clock.nowUtc().plusDays(1))
                        .build())
                .build());
    EppException thrown =
        expectThrows(
            AlreadyPendingTransferException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sponsoringClient() throws Exception {
    setClientIdForFlow("TheRegistrar");
    EppException thrown =
        expectThrows(
            ObjectAlreadySponsoredException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_deletedContact() throws Exception {
    contact =
        persistResource(contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        expectThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_nonexistentContact() throws Exception {
    deleteResource(contact);
    ResourceDoesNotExistException thrown =
        expectThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_clientTransferProhibited() throws Exception {
    contact =
        persistResource(
            contact.asBuilder().addStatusValue(StatusValue.CLIENT_TRANSFER_PROHIBITED).build());
    ResourceStatusProhibitsOperationException thrown =
        expectThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("clientTransferProhibited");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_serverTransferProhibited() throws Exception {
    contact =
        persistResource(
            contact.asBuilder().addStatusValue(StatusValue.SERVER_TRANSFER_PROHIBITED).build());
    ResourceStatusProhibitsOperationException thrown =
        expectThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("serverTransferProhibited");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_pendingDelete() throws Exception {
    contact =
        persistResource(contact.asBuilder().addStatusValue(StatusValue.PENDING_DELETE).build());
    ResourceStatusProhibitsOperationException thrown =
        expectThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("contact_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-transfer-request");
  }
}
