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

package google.registry.reporting.icann;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import org.joda.time.YearMonth;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ActivityReportingQueryBuilder}. */
@RunWith(JUnit4.class)
public class ActivityReportingQueryBuilderTest {

  private ActivityReportingQueryBuilder getQueryBuilder() {
    ActivityReportingQueryBuilder queryBuilder = new ActivityReportingQueryBuilder();
    queryBuilder.yearMonth = new YearMonth(2017, 9);
    queryBuilder.projectId = "domain-registry-alpha";
    return queryBuilder;
  }

  @Test
  public void testAggregateQueryMatch() throws IOException {
    ActivityReportingQueryBuilder queryBuilder = getQueryBuilder();
    assertThat(queryBuilder.getReportQuery())
        .isEqualTo(
            "#standardSQL\nSELECT * FROM "
                + "`domain-registry-alpha.icann_reporting.activity_report_aggregation_201709`");
  }

  @Test
  public void testIntermediaryQueryMatch() throws IOException {
    ImmutableList<String> expectedQueryNames =
        ImmutableList.of(
            ActivityReportingQueryBuilder.REGISTRAR_OPERATING_STATUS,
            ActivityReportingQueryBuilder.MONTHLY_LOGS,
            ActivityReportingQueryBuilder.DNS_COUNTS,
            ActivityReportingQueryBuilder.EPP_METRICS,
            ActivityReportingQueryBuilder.WHOIS_COUNTS,
            ActivityReportingQueryBuilder.ACTIVITY_REPORT_AGGREGATION);

    ActivityReportingQueryBuilder queryBuilder = getQueryBuilder();
    ImmutableMap<String, String> actualQueries = queryBuilder.getViewQueryMap();
    for (String queryName : expectedQueryNames) {
      String actualTableName = String.format("%s_201709", queryName);
      String testFilename = String.format("%s_test.sql", queryName);
      assertThat(actualQueries.get(actualTableName))
          .isEqualTo(ReportingTestData.loadFile(testFilename));
    }
  }

}
