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

package google.registry.model.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.server.Lock.LockState.FREE;
import static google.registry.model.server.Lock.LockState.IN_USE;
import static google.registry.model.server.Lock.LockState.OWNER_DIED;
import static google.registry.model.server.Lock.LockState.TIMED_OUT;
import static google.registry.testing.JUnitBackports.expectThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import google.registry.model.ofy.Ofy;
import google.registry.model.server.Lock.LockState;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.util.RequestStatusChecker;
import java.util.Optional;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Lock}. */
@RunWith(JUnit4.class)
public class LockTest {

  private static final String RESOURCE_NAME = "foo";
  private static final Duration ONE_DAY = Duration.standardDays(1);
  private static final Duration TWO_MILLIS = Duration.millis(2);
  private static final RequestStatusChecker requestStatusChecker = mock(RequestStatusChecker.class);
  private static final FakeClock clock = new FakeClock();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();
  private Optional<Lock> acquire(String tld, Duration leaseLength, LockState expectedLockState) {
    Lock.lockMetrics = mock(LockMetrics.class);
    Optional<Lock> lock = Lock.acquire(RESOURCE_NAME, tld, leaseLength, requestStatusChecker);
    verify(Lock.lockMetrics).recordAcquire(RESOURCE_NAME, tld, expectedLockState);
    verifyNoMoreInteractions(Lock.lockMetrics);
    Lock.lockMetrics = null;
    return lock;
  }

  private void release(Lock lock, String expectedTld, long expectedMillis) {
    Lock.lockMetrics = mock(LockMetrics.class);
    lock.release();
    verify(Lock.lockMetrics)
        .recordRelease(RESOURCE_NAME, expectedTld, Duration.millis(expectedMillis));
    verifyNoMoreInteractions(Lock.lockMetrics);
    Lock.lockMetrics = null;
  }


  @Before public void setUp() {
    inject.setStaticField(Ofy.class, "clock", clock);
    Lock.lockMetrics = null;
    when(requestStatusChecker.getLogId()).thenReturn("current-request-id");
    when(requestStatusChecker.isRunning("current-request-id")).thenReturn(true);
  }

  @Test
  public void testReleasedExplicitly() throws Exception {
    Optional<Lock> lock = acquire("", ONE_DAY, FREE);
    assertThat(lock).isPresent();
    // We can't get it again at the same time.
    assertThat(acquire("", ONE_DAY, IN_USE)).isEmpty();
    // But if we release it, it's available.
    clock.advanceBy(Duration.millis(123));
    release(lock.get(), "", 123);
    assertThat(acquire("", ONE_DAY, FREE)).isPresent();
  }

  @Test
  public void testReleasedAfterTimeout() throws Exception {
    assertThat(acquire("", TWO_MILLIS, FREE)).isPresent();
    // We can't get it again at the same time.
    assertThat(acquire("", TWO_MILLIS, IN_USE)).isEmpty();
    // A second later we still can't get the lock.
    clock.advanceOneMilli();
    assertThat(acquire("", TWO_MILLIS, IN_USE)).isEmpty();
    // But two seconds later we can get it.
    clock.advanceOneMilli();
    assertThat(acquire("", TWO_MILLIS, TIMED_OUT)).isPresent();
  }

  @Test
  public void testReleasedAfterRequestFinish() throws Exception {
    assertThat(acquire("", ONE_DAY, FREE)).isPresent();
    // We can't get it again while request is active
    assertThat(acquire("", ONE_DAY, IN_USE)).isEmpty();
    // But if request is finished, we can get it.
    when(requestStatusChecker.isRunning("current-request-id")).thenReturn(false);
    assertThat(acquire("", ONE_DAY, OWNER_DIED)).isPresent();
  }

  @Test
  public void testTldsAreIndependent() throws Exception {
    Optional<Lock> lockA = acquire("a", ONE_DAY, FREE);
    assertThat(lockA).isPresent();
    // For a different tld we can still get a lock with the same name.
    Optional<Lock> lockB = acquire("b", ONE_DAY, FREE);
    assertThat(lockB).isPresent();
    // We can't get lockB again at the same time.
    assertThat(acquire("b", ONE_DAY, IN_USE)).isEmpty();
    // Releasing lockA has no effect on lockB (even though we are still using the "b" tld).
    clock.advanceOneMilli();
    release(lockA.get(), "a", 1);
    assertThat(acquire("b", ONE_DAY, IN_USE)).isEmpty();
  }

  @Test
  public void testFailure_emptyResourceName() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () -> Lock.acquire("", "", TWO_MILLIS, requestStatusChecker));
    assertThat(thrown).hasMessageThat().contains("resourceName cannot be null or empty");
  }
}
