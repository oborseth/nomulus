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

package google.registry.module.frontend;

import com.google.appengine.api.LifecycleManager;
import com.google.monitoring.metrics.MetricReporter;
import dagger.Lazy;
import google.registry.util.FormattingLogger;
import java.io.IOException;
import java.security.Security;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Servlet that should handle all requests to our "default" App Engine module. */
public final class FrontendServlet extends HttpServlet {

  private static final FrontendComponent component = DaggerFrontendComponent.create();
  private static final FrontendRequestHandler requestHandler = component.requestHandler();
  private static final Lazy<MetricReporter> metricReporter = component.metricReporter();
  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  @Override
  public void init() {
    Security.addProvider(new BouncyCastleProvider());

    // If metric reporter failed to instantiate for any reason (bad keyring, bad json credential,
    // etc), we log the error but keep the main thread running. Also the shutdown hook will only be
    // registered if metric reporter starts up correctly.
    try {
      metricReporter.get().startAsync().awaitRunning(10, TimeUnit.SECONDS);
      logger.info("Started up MetricReporter");
      LifecycleManager.getInstance()
          .setShutdownHook(
              () -> {
                try {
                  metricReporter.get().stopAsync().awaitTerminated(10, TimeUnit.SECONDS);
                  logger.info("Shut down MetricReporter");
                } catch (TimeoutException e) {
                  logger.severe(e, "Failed to stop MetricReporter.");
                }
              });
    } catch (Exception e) {
      logger.severe(e, "Failed to initialize MetricReporter.");
    }
  }

  @Override
  public void service(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    logger.info("Received frontend request");
    requestHandler.handleRequest(req, rsp);
  }
}
