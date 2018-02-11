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

import static google.registry.request.Action.Method.POST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.registrar.Registrar;
import google.registry.reporting.billing.BillingModule.InvoiceDirectoryPrefix;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.storage.drive.DriveConnection;
import google.registry.util.FormattingLogger;
import google.registry.util.Retrier;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.inject.Inject;

/** Copy all registrar detail reports in a given bucket's subdirectory from GCS to Drive. */
@Action(path = CopyDetailReportsAction.PATH, method = POST, auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public final class CopyDetailReportsAction implements Runnable {

  public static final String PATH = "/_dr/task/copyDetailReports";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final String billingBucket;
  private final String invoiceDirectoryPrefix;
  private final DriveConnection driveConnection;
  private final GcsUtils gcsUtils;
  private final Retrier retrier;
  private final Response response;
  private final BillingEmailUtils emailUtils;

  @Inject
  CopyDetailReportsAction(
      @Config("billingBucket") String billingBucket,
      @InvoiceDirectoryPrefix String invoiceDirectoryPrefix,
      DriveConnection driveConnection,
      GcsUtils gcsUtils,
      Retrier retrier,
      Response response,
      BillingEmailUtils emailUtils) {
    this.billingBucket = billingBucket;
    this.invoiceDirectoryPrefix = invoiceDirectoryPrefix;
    this.driveConnection = driveConnection;
    this.gcsUtils = gcsUtils;
    this.retrier = retrier;
    this.response = response;
    this.emailUtils = emailUtils;
  }

  @Override
  public void run() {
    ImmutableList<String> detailReportObjectNames;
    try {
      detailReportObjectNames =
          gcsUtils
              .listFolderObjects(billingBucket, invoiceDirectoryPrefix)
              .stream()
              .filter(objectName -> objectName.startsWith(BillingModule.DETAIL_REPORT_PREFIX))
              .collect(ImmutableList.toImmutableList());
    } catch (IOException e) {
      logger.severe(e, "Copying registrar detail report failed");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Failure, encountered %s", e.getMessage()));
      return;
    }
    for (String detailReportName : detailReportObjectNames) {
      // The standard report format is "invoice_details_yyyy-MM_registrarId_tld.csv
      // TODO(larryruili): Determine a safer way of enforcing this.
      String registrarId = detailReportName.split("_")[3];
      Optional<Registrar> registrar = Registrar.loadByClientId(registrarId);
      if (!registrar.isPresent()) {
        logger.warningfmt(
            "Registrar %s not found in database for file %s", registrar, detailReportName);
        continue;
      }
      String driveFolderId = registrar.get().getDriveFolderId();
      if (driveFolderId == null) {
        logger.warningfmt("Drive folder id not found for registrar %s", registrarId);
        continue;
      }
      // Attempt to copy each detail report to its associated registrar's drive folder.
      retrier.callWithRetry(
          () -> {
            try (InputStream input =
                gcsUtils.openInputStream(
                    new GcsFilename(billingBucket, invoiceDirectoryPrefix + detailReportName))) {
              driveConnection.createFile(
                  detailReportName,
                  MediaType.CSV_UTF_8,
                  driveFolderId,
                  ByteStreams.toByteArray(input));
              logger.infofmt(
                  "Published detail report for %s to folder %s using GCS file gs://%s/%s.",
                  registrarId, driveFolderId, billingBucket, detailReportName);
            }
          },
          new Retrier.FailureReporter() {
            @Override
            public void beforeRetry(Throwable thrown, int failures, int maxAttempts) {}

            @Override
            public void afterFinalFailure(Throwable thrown, int failures) {
              emailUtils.sendAlertEmail(
                  String.format(
                      "Warning: CopyDetailReportsAction failed.\nEncountered: %s on file: %s",
                      thrown.getMessage(), detailReportName));
            }
          },
          IOException.class);
    }
    response.setStatus(SC_OK);
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    response.setPayload("Copied detail reports.");
  }
}
