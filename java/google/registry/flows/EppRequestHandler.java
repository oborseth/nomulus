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

package google.registry.flows;

import static google.registry.flows.EppXmlTransformer.marshalWithLenientRetry;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_AND_CLOSE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.net.MediaType;
import google.registry.model.eppoutput.EppOutput;
import google.registry.request.Response;
import google.registry.util.FormattingLogger;
import javax.inject.Inject;

/** Handle an EPP request and response. */
public class EppRequestHandler {

  private static final MediaType APPLICATION_EPP_XML =
      MediaType.create("application", "epp+xml").withCharset(UTF_8);

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  @Inject EppController eppController;
  @Inject Response response;

  @Inject
  EppRequestHandler() {}

  /** Handle an EPP request and write out a servlet response. */
  public void executeEpp(
      SessionMetadata sessionMetadata,
      TransportCredentials credentials,
      EppRequestSource eppRequestSource,
      boolean isDryRun,
      boolean isSuperuser,
      byte[] inputXmlBytes) {
    try {
      EppOutput eppOutput =
          eppController.handleEppCommand(
              sessionMetadata, credentials, eppRequestSource, isDryRun, isSuperuser, inputXmlBytes);
      response.setPayload(new String(marshalWithLenientRetry(eppOutput), UTF_8));
      response.setContentType(APPLICATION_EPP_XML);
      // Note that we always return 200 (OK) even if the EppController returns an error response.
      // This is because returning a non-OK HTTP status code will cause the proxy server to
      // silently close the connection without returning any data. The only time we will ever return
      // a non-OK status (400) is if we fail to muster even an EPP error response message. In that
      // case it's better to close the connection than to return garbage.
      response.setStatus(SC_OK);
      // Per RFC 5734, a server receiving a logout command must end the EPP session and close the
      // TCP connection. Since the app itself only gets HttpServletRequest and is not aware of TCP
      // sessions, it simply sets the HTTP response header to indicate the connection should be
      // closed by the proxy. Whether the EPP proxy actually terminates the connection with the
      // client is up to its implementation.
      // See: https://tools.ietf.org/html/rfc5734#section-2
      if (eppOutput.isResponse()
          && eppOutput.getResponse().getResult().getCode() == SUCCESS_AND_CLOSE) {
        response.setHeader("Epp-Session", "close");
      }
    } catch (Exception e) {
      logger.warning(e, "handleEppCommand general exception");
      response.setStatus(SC_BAD_REQUEST);
    }
  }
}
