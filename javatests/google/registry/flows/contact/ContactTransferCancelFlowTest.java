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

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.getOnlyPollMessage;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.JUnitBackports.expectThrows;

import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.flows.exceptions.NotTransferInitiatorException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link ContactTransferCancelFlow}. */
public class ContactTransferCancelFlowTest
    extends ContactTransferFlowTestCase<ContactTransferCancelFlow, ContactResource> {

  @Before
  public void setUp() throws Exception {
    this.setEppInput("contact_transfer_cancel.xml");
    setClientIdForFlow("NewRegistrar");
    setupContactWithPendingTransfer();
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    this.setEppInput(commandFilename);
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);

    // Setup done; run the test.
    contact = reloadResourceByForeignKey();
    TransferData originalTransferData = contact.getTransferData();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));

    // Transfer should have been cancelled. Verify correct fields were set.
    contact = reloadResourceByForeignKey();
    assertAboutContacts().that(contact)
        .hasCurrentSponsorClientId("TheRegistrar").and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc()).and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.CONTACT_TRANSFER_REQUEST,
            HistoryEntry.Type.CONTACT_TRANSFER_CANCEL);
    assertThat(contact.getTransferData())
        .isEqualTo(
            originalTransferData.copyConstantFieldsToBuilder()
                .setTransferStatus(TransferStatus.CLIENT_CANCELLED)
                .setPendingTransferExpirationTime(clock.nowUtc())
                .build());
    assertNoBillingEvents();
    // The poll message (in the future) to the gaining registrar for implicit ack should be gone.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1))).isEmpty();
    // The poll message in the future to the losing registrar should be gone too, but there
    // should be one at the current time to the losing registrar.
    PollMessage losingPollMessage = getOnlyPollMessage("TheRegistrar");
    assertThat(losingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(
            losingPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.CLIENT_CANCELLED);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    this.setEppInput(commandFilename);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  public void testDryRun() throws Exception {
    setEppInput("contact_transfer_cancel.xml");
    dryRunFlowAssertResponse(loadFile("contact_transfer_cancel_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("contact_transfer_cancel.xml", "contact_transfer_cancel_response.xml");
  }

  @Test
  public void testSuccess_withAuthinfo() throws Exception {
    doSuccessfulTest("contact_transfer_cancel_with_authinfo.xml",
        "contact_transfer_cancel_response.xml");
  }

  @Test
  public void testFailure_badContactPassword() throws Exception {
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
            () -> doFailingTest("contact_transfer_cancel_with_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_neverBeenTransferred() throws Exception {
    changeTransferStatus(null);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_clientApproved() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_clientRejected() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_clientCancelled() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_serverApproved() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_serverCancelled() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    EppException thrown =
        expectThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_sponsoringClient() throws Exception {
    setClientIdForFlow("TheRegistrar");
    EppException thrown =
        expectThrows(
            NotTransferInitiatorException.class,
            () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_unrelatedClient() throws Exception {
    setClientIdForFlow("ClientZ");
    EppException thrown =
        expectThrows(
            NotTransferInitiatorException.class,
            () -> doFailingTest("contact_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_deletedContact() throws Exception {
    contact =
        persistResource(contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        expectThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_cancel.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_nonexistentContact() throws Exception {
    deleteResource(contact);
    ResourceDoesNotExistException thrown =
        expectThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_cancel.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-transfer-cancel");
  }
}
