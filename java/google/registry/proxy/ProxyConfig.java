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

package google.registry.proxy;

import static google.registry.config.YamlUtils.getConfigSettings;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import java.util.List;

/** The POJO that YAML config files are deserialized into. */
public class ProxyConfig {

  enum Environment {
    PRODUCTION,
    SANDBOX,
    ALPHA,
    LOCAL,
    TEST,
  }

  private static final String DEFAULT_CONFIG = "config/default-config.yaml";
  private static final String CUSTOM_CONFIG_FORMATTER = "config/proxy-config-%s.yaml";

  public String projectId;
  public List<String> gcpScopes;
  public int accessTokenValidPeriodSeconds;
  public int accessTokenRefreshBeforeExpirySeconds;
  public String sslPemFilename;
  public Kms kms;
  public Epp epp;
  public Whois whois;
  public HealthCheck healthCheck;
  public HttpsRelay httpsRelay;
  public Metrics metrics;

  /** Configuration options that apply to Cloud KMS. */
  public static class Kms {
    public String location;
    public String keyRing;
    public String cryptoKey;
  }

  /** Configuration options that apply to EPP protocol. */
  public static class Epp {
    public int port;
    public String relayHost;
    public String relayPath;
    public int maxMessageLengthBytes;
    public int headerLengthBytes;
    public int readTimeoutSeconds;
    public String serverHostname;
    public Quota quota;
  }

  /** Configuration options that apply to WHOIS protocol. */
  public static class Whois {
    public int port;
    public String relayHost;
    public String relayPath;
    public int maxMessageLengthBytes;
    public int readTimeoutSeconds;
    public Quota quota;
  }

  /** Configuration options that apply to GCP load balancer health check protocol. */
  public static class HealthCheck {
    public int port;
    public String checkRequest;
    public String checkResponse;
  }

  /** Configuration options that apply to HTTPS relay protocol. */
  public static class HttpsRelay {
    public int port;
    public int maxMessageLengthBytes;
  }

  /** Configuration options that apply to Stackdriver monitoring metrics. */
  public static class Metrics {
    public int stackdriverMaxQps;
    public int stackdriverMaxPointsPerRequest;
    public int writeIntervalSeconds;
  }

  /** Configuration options that apply to quota management. */
  public static class Quota {

    /** Quota configuration for a specific set of users. */
    public static class QuotaGroup {
      public List<String> userId;
      public int tokenAmount;
      public int refillSeconds;
    }

    public int refreshSeconds;
    public QuotaGroup defaultQuota;
    public List<QuotaGroup> customQuota;
  }

  static ProxyConfig getProxyConfig(Environment env) {
    String defaultYaml = readResourceUtf8(ProxyConfig.class, DEFAULT_CONFIG);
    String customYaml =
        readResourceUtf8(
            ProxyConfig.class, String.format(CUSTOM_CONFIG_FORMATTER, env.name().toLowerCase()));
    return getConfigSettings(defaultYaml, customYaml, ProxyConfig.class);
  }
}
