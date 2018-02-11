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

package google.registry.reporting.billing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Jobs;
import com.google.api.services.dataflow.Dataflow.Projects.Jobs.Get;
import com.google.api.services.dataflow.model.Job;
import com.google.common.net.MediaType;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.joda.time.YearMonth;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PublishInvoicesActionTest {

  private Dataflow dataflow;
  private Projects projects;
  private Jobs jobs;
  private Get get;
  private BillingEmailUtils emailUtils;

  private Job expectedJob;
  private FakeResponse response;
  private PublishInvoicesAction uploadAction;

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withTaskQueue().build();

  @Before
  public void setUp() throws IOException {
    dataflow = mock(Dataflow.class);
    projects = mock(Projects.class);
    jobs = mock(Jobs.class);
    get = mock(Get.class);
    when(dataflow.projects()).thenReturn(projects);
    when(projects.jobs()).thenReturn(jobs);
    when(jobs.get("test-project", "12345")).thenReturn(get);
    expectedJob = new Job();
    when(get.execute()).thenReturn(expectedJob);
    emailUtils = mock(BillingEmailUtils.class);
    response = new FakeResponse();
    uploadAction =
        new PublishInvoicesAction(
            "test-project", "12345", emailUtils, dataflow, response, new YearMonth(2017, 10));
  }

  @Test
  public void testJobDone_enqueuesCopyAction_emailsResults() throws Exception {
    expectedJob.setCurrentState("JOB_STATE_DONE");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(emailUtils).emailOverallInvoice();
    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/copyDetailReports")
            .method("POST")
            .param("yearMonth", "2017-10");
    assertTasksEnqueued("retryable-cron-tasks", matcher);
  }

  @Test
  public void testJobFailed_returnsNonRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_FAILED");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    verify(emailUtils).sendAlertEmail("Dataflow job 12345 ended in status failure.");
  }

  @Test
  public void testJobIndeterminate_returnsRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_RUNNING");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_MODIFIED);
  }

  @Test
  public void testIOException_returnsFailureMessage() throws IOException {
    when(get.execute()).thenThrow(new IOException("expected"));
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Template launch failed: expected");
    verify(emailUtils).sendAlertEmail("Publish action failed due to expected");
  }
}
