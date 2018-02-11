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

package google.registry.rdap;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import google.registry.request.RequestParameters;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for the RDAP package. */
@Module
public final class RdapModule {

  @Provides
  @Parameter("name")
  static Optional<String> provideName(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "name");
  }

  @Provides
  @Parameter("nsLdhName")
  static Optional<String> provideNsLdhName(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "nsLdhName");
  }

  @Provides
  @Parameter("nsIp")
  static Optional<String> provideNsIp(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "nsIp");
  }

  @Provides
  @Parameter("ip")
  static Optional<String> provideIp(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "ip");
  }

  @Provides
  @Parameter("fn")
  static Optional<String> provideFn(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "fn");
  }

  @Provides
  @Parameter("handle")
  static Optional<String> provideHandle(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "handle");
  }

  @Provides
  @Parameter("registrar")
  static Optional<String> provideRegistrar(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "registrar");
  }

  @Provides
  @Parameter("subtype")
  static Optional<String> provideSubtype(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "subtype");
  }

  @Provides
  @Parameter("includeDeleted")
  static Optional<Boolean> provideIncludeDeleted(HttpServletRequest req) {
    return RequestParameters.extractOptionalBooleanParameter(req, "includeDeleted");
  }

  @Provides
  @Parameter("formatOutput")
  static Optional<Boolean> provideFormatOutput(HttpServletRequest req) {
    return RequestParameters.extractOptionalBooleanParameter(req, "formatOutput");
  }

  @Provides
  @Parameter("cursor")
  static Optional<String> provideCursor(HttpServletRequest req) {
    return RequestParameters.extractOptionalParameter(req, "cursor");
  }
}
