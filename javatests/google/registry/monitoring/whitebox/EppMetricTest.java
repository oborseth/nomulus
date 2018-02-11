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

package google.registry.monitoring.whitebox;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.eppoutput.Result.Code;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EppMetric}. */
@RunWith(JUnit4.class)
public class EppMetricTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void test_invalidTld_isRecordedAsInvalid() throws Exception {
    EppMetric metric =
        EppMetric.builderForRequest("request-id-1", new FakeClock())
            .setTlds(ImmutableSet.of("notarealtld"))
            .build();
    assertThat(metric.getTld()).hasValue("_invalid");
  }

  @Test
  public void test_validTld_isRecorded() throws Exception {
    createTld("example");
    EppMetric metric =
        EppMetric.builderForRequest("request-id-1", new FakeClock())
            .setTlds(ImmutableSet.of("example"))
            .build();
    assertThat(metric.getTld()).hasValue("example");
  }

  @Test
  public void test_multipleTlds_areRecordedAsVarious() throws Exception {
    createTlds("foo", "bar");
    EppMetric metric =
        EppMetric.builderForRequest("request-id-1", new FakeClock())
            .setTlds(ImmutableSet.of("foo", "bar", "baz"))
            .build();
    assertThat(metric.getTld()).hasValue("_various");
  }

  @Test
  public void test_zeroTlds_areRecordedAsAbsent() throws Exception {
    EppMetric metric =
        EppMetric.builderForRequest("request-id-1", new FakeClock())
            .setTlds(ImmutableSet.of())
            .build();
    assertThat(metric.getTld()).isEmpty();
  }

  @Test
  public void testGetBigQueryRowEncoding_encodesCorrectly() throws Exception {
    EppMetric metric =
        EppMetric.builder()
            .setRequestId("request-id-1")
            .setStartTimestamp(new DateTime(1337))
            .setEndTimestamp(new DateTime(1338))
            .setCommandName("command")
            .setClientId("client")
            .setTld("example")
            .setPrivilegeLevel("level")
            .setEppTarget("target")
            .setStatus(Code.COMMAND_USE_ERROR)
            .incrementAttempts()
            .build();

    assertThat(metric.getBigQueryRowEncoding())
        .containsExactlyEntriesIn(
            new ImmutableMap.Builder<String, String>()
                .put("requestId", "request-id-1")
                .put("startTime", "1.337000")
                .put("endTime", "1.338000")
                .put("commandName", "command")
                .put("clientId", "client")
                .put("tld", "example")
                .put("privilegeLevel", "level")
                .put("eppTarget", "target")
                .put("eppStatus", "2002")
                .put("attempts", "1")
                .build());
  }

  @Test
  public void testGetBigQueryRowEncoding_hasAllSchemaFields() throws Exception {
    EppMetric metric =
        EppMetric.builder()
            .setRequestId("request-id-1")
            .setStartTimestamp(new DateTime(1337))
            .setEndTimestamp(new DateTime(1338))
            .setCommandName("command")
            .setClientId("client")
            .setTld("example")
            .setPrivilegeLevel("level")
            .setEppTarget("target")
            .setStatus(Code.COMMAND_USE_ERROR)
            .incrementAttempts()
            .build();
    ImmutableSet.Builder<String> schemaFieldNames = new ImmutableSet.Builder<>();
    for (TableFieldSchema schemaField : metric.getSchemaFields()) {
      schemaFieldNames.add(schemaField.getName());
    }

    assertThat(metric.getBigQueryRowEncoding().keySet()).isEqualTo(schemaFieldNames.build());
  }
}
