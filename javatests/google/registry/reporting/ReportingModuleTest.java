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

package google.registry.reporting;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.expectThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.joda.time.YearMonth;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReportingModule}. */
@RunWith(JUnit4.class)
public class ReportingModuleTest {

  private HttpServletRequest req = mock(HttpServletRequest.class);
  private Clock clock;
  @Before
  public void setUp() {
    clock = new FakeClock(DateTime.parse("2017-07-01TZ"));
  }

  @Test
  public void testEmptyYearMonthParameter_returnsEmptyYearMonthOptional() {
    when(req.getParameter("yearMonth")).thenReturn("");
    assertThat(ReportingModule.provideYearMonthOptional(req)).isEqualTo(Optional.empty());
  }

  @Test
  public void testInvalidYearMonthParameter_throwsException() {
    when(req.getParameter("yearMonth")).thenReturn("201705");
    BadRequestException thrown =
        expectThrows(
            BadRequestException.class, () -> ReportingModule.provideYearMonthOptional(req));
    assertThat(thrown)
        .hasMessageThat()
        .contains("yearMonth must be in yyyy-MM format, got 201705 instead");
  }

  @Test
  public void testEmptyYearMonth_returnsLastMonth() {
    assertThat(ReportingModule.provideYearMonth(Optional.empty(), clock))
        .isEqualTo(new YearMonth(2017, 6));
  }

  @Test
  public void testGivenYearMonth_returnsThatMonth() {
    assertThat(ReportingModule.provideYearMonth(Optional.of(new YearMonth(2017, 5)), clock))
        .isEqualTo(new YearMonth(2017, 5));
  }

}
