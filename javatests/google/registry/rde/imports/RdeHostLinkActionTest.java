// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.imports.RdeImportsTestData.loadBytes;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.domain.DomainResource;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.ofy.Ofy;
import google.registry.request.Response;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdeHostLinkAction}. */
@RunWith(JUnit4.class)
public class RdeHostLinkActionTest extends MapreduceTestCase<RdeHostLinkAction> {

  private static final ByteSource DEPOSIT_1_HOST = loadBytes("deposit_1_host.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  @Rule
  public final InjectRule inject = new InjectRule();

  private MapreduceRunner mrRunner;

  private Response response;

  private final Optional<Integer> mapShards = Optional.empty();

  private final FakeClock clock = new FakeClock();

  @Before
  public void before() throws Exception {
    clock.setTo(DateTime.parse("2016-05-07T14:55:38Z"));
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("test");
    response = new FakeResponse();
    mrRunner = new MapreduceRunner(Optional.empty(), Optional.empty());
    action =
        new RdeHostLinkAction(mrRunner, response, IMPORT_BUCKET_NAME, IMPORT_FILE_NAME, mapShards);
  }

  @Test
  public void test_mapreduceSuccessfullyLinksHost() throws Exception {
    // Create host and domain first
    persistResource(
        newHostResource("ns1.example1.test")
            .asBuilder()
            .setRepoId("Hns1_example1_test-TEST")
            .build());
    DomainResource superordinateDomain = persistActiveDomain("example1.test");
    Key<DomainResource> superOrdinateDomainKey = Key.create(superordinateDomain);
    pushToGcs(DEPOSIT_1_HOST);
    // set transaction time to slightly after resource save
    clock.advanceOneMilli();
    runMapreduce();
    // verify that host is linked to domain
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    assertThat(hosts.get(0).getSuperordinateDomain()).isEqualTo(superOrdinateDomainKey);
    assertThat(hosts.get(0).getLastSuperordinateChange())
        .isEqualTo(DateTime.parse("2016-05-07T14:55:38.001Z"));
    // verify that domain is linked to host
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains).hasSize(1);
    assertThat(domains.get(0).getSubordinateHosts()).containsExactly("ns1.example1.test");
  }

  @Test
  public void test_mapreduceIgnoresHostWithWrongRepoid() throws Exception {
    // in-zone host with different repoid is ignored, since it would already be linked
    // from a separate epp create or import.
    // Create host and domain first
    HostResource newHost = persistResource(
        newHostResource("ns1.example1.test")
            .asBuilder()
            .setRepoId("wrong-repoid")
            .build());
    DomainResource superordinateDomain = persistActiveDomain("example1.test");
    ForeignKeyDomainIndex.create(superordinateDomain, END_OF_TIME);
    pushToGcs(DEPOSIT_1_HOST);
    // set transaction time to slightly after resource save
    clock.advanceOneMilli();
    runMapreduce();
    // verify that host has not been updated
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    assertThat(hosts.get(0).getUpdateAutoTimestamp()).isEqualTo(newHost.getUpdateAutoTimestamp());
  }

  @Test
  public void test_mapreduceIgnoresHostWithSuperordinateDomainPendingDeletion() throws Exception {
    HostResource newHost = persistResource(
        newHostResource("ns1.example1.test")
            .asBuilder()
            .setRepoId("Hns1_example1_test-TEST")
            .build());
    persistResource(
        newDomainResource("example1.test")
            .asBuilder()
            .setStatusValues(ImmutableSet.of(PENDING_DELETE))
            .build());
    pushToGcs(DEPOSIT_1_HOST);
    // set transaction time to slightly after resource save
    clock.advanceOneMilli();
    runMapreduce();
    // verify that host has not been updated
    List<HostResource> hosts = ofy().load().type(HostResource.class).list();
    assertThat(hosts).hasSize(1);
    assertThat(hosts.get(0).getUpdateAutoTimestamp()).isEqualTo(newHost.getUpdateAutoTimestamp());
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private static void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
            new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
                .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }
}
