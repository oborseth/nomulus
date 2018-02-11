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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.expectThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.beust.jcommander.ParameterException;
import com.google.appengine.tools.remoteapi.RemoteApiException;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.domain.AllocationToken;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.DeterministicStringGenerator.Rule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import google.registry.util.StringGenerator.Alphabets;
import java.io.IOException;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/** Unit tests for {@link GenerateAllocationTokensCommand}. */
public class GenerateAllocationTokensCommandTest
    extends CommandTestCase<GenerateAllocationTokensCommand> {

  @Before
  public void init() throws IOException {
    command.stringGenerator = new DeterministicStringGenerator(Alphabets.BASE_58);
    command.retrier =
        new Retrier(new FakeSleeper(new FakeClock(DateTime.parse("2000-01-01TZ"))), 3);
  }

  @Test
  public void testSuccess_oneToken() throws Exception {
    runCommand("--prefix", "blah", "--number", "1", "--length", "9");
    assertAllocationTokens(createToken("blah123456789", null));
    assertInStdout("blah123456789");
  }

  @Test
  public void testSuccess_threeTokens() throws Exception {
    runCommand("--prefix", "foo", "--number", "3", "--length", "10");
    assertAllocationTokens(
        createToken("foo123456789A", null),
        createToken("fooBCDEFGHJKL", null),
        createToken("fooMNPQRSTUVW", null));
    assertInStdout("foo123456789A\nfooBCDEFGHJKL\nfooMNPQRSTUVW");
  }

  @Test
  public void testSuccess_defaults() throws Exception {
    runCommand("--number", "1");
    assertAllocationTokens(createToken("123456789ABC", null));
    assertInStdout("123456789ABC");
  }

  @Test
  public void testSuccess_retry() throws Exception {
    GenerateAllocationTokensCommand spyCommand = spy(command);
    RemoteApiException fakeException = new RemoteApiException("foo", "foo", "foo", new Exception());
    doThrow(fakeException)
        .doThrow(fakeException)
        .doCallRealMethod()
        .when(spyCommand)
        .saveTokens(Mockito.any());
    runCommand("--number", "1");
    assertAllocationTokens(createToken("123456789ABC", null));
    assertInStdout("123456789ABC");
  }

  @Test
  public void testSuccess_tokenCollision() throws Exception {
    AllocationToken existingToken =
        persistResource(new AllocationToken.Builder().setToken("DEADBEEF123456789ABC").build());
    runCommand("--number", "1", "--prefix", "DEADBEEF");
    assertAllocationTokens(existingToken, createToken("DEADBEEFDEFGHJKLMNPQ", null));
    assertInStdout("DEADBEEFDEFGHJKLMNPQ");
  }

  @Test
  public void testSuccess_dryRun_outputsButDoesntSave() throws Exception {
    runCommand("--prefix", "foo", "--number", "2", "--length", "10", "--dry_run");
    assertAllocationTokens();
    assertInStdout("foo123456789A\nfooBCDEFGHJKL");
  }

  @Test
  public void testSuccess_largeNumberOfTokens() throws Exception {
    command.stringGenerator =
        new DeterministicStringGenerator(Alphabets.BASE_58, Rule.PREPEND_COUNTER);
    runCommand("--prefix", "ooo", "--number", "100", "--length", "16");
    // The deterministic string generator makes it too much hassle to assert about each token, so
    // just assert total number.
    assertThat(ofy().load().type(AllocationToken.class).count()).isEqualTo(100);
  }

  @Test
  public void testFailure_mustSpecifyNumberOfTokens() throws Exception {
    ParameterException thrown =
        expectThrows(ParameterException.class, () -> runCommand("--prefix", "FEET"));
    assertThat(thrown).hasMessageThat().contains("The following option is required: -n, --number");
  }

  private void assertAllocationTokens(AllocationToken... expectedTokens) throws Exception {
    // Using ImmutableObject comparison here is tricky because the creation/updated timestamps are
    // neither easy nor valuable to test here.
    ImmutableMap<String, AllocationToken> actualTokens =
        ofy()
            .load()
            .type(AllocationToken.class)
            .list()
            .stream()
            .collect(ImmutableMap.toImmutableMap(AllocationToken::getToken, Function.identity()));
    assertThat(actualTokens).hasSize(expectedTokens.length);
    for (AllocationToken expectedToken : expectedTokens) {
      AllocationToken match = actualTokens.get(expectedToken.getToken());
      assertThat(match).isNotNull();
      assertThat(match.getRedemptionHistoryEntry())
          .isEqualTo(expectedToken.getRedemptionHistoryEntry());
    }
  }

  private AllocationToken createToken(
      String token, @Nullable Key<HistoryEntry> redemptionHistoryEntry) {
    AllocationToken.Builder builder = new AllocationToken.Builder().setToken(token);
    if (redemptionHistoryEntry != null) {
      builder.setRedemptionHistoryEntry(redemptionHistoryEntry);
    }
    return builder.build();
  }
}
