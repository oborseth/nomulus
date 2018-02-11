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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.proxy.ProxyConfig.Environment.TEST;
import static google.registry.proxy.ProxyConfig.getProxyConfig;
import static google.registry.testing.JUnitBackports.expectThrows;
import static org.junit.Assert.fail;

import com.beust.jcommander.ParameterException;
import google.registry.proxy.ProxyConfig.Environment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ProxyModule}. */
@RunWith(JUnit4.class)
public class ProxyModuleTest {

  private static final ProxyConfig PROXY_CONFIG = getProxyConfig(TEST);
  private final ProxyModule proxyModule = new ProxyModule();

  @Test
  public void testSuccess_parseArgs_defaultArgs() {
    String[] args = {};
    proxyModule.parse(args);
    assertThat(proxyModule.provideWhoisPort(PROXY_CONFIG)).isEqualTo(PROXY_CONFIG.whois.port);
    assertThat(proxyModule.provideEppPort(PROXY_CONFIG)).isEqualTo(PROXY_CONFIG.epp.port);
    assertThat(proxyModule.provideHealthCheckPort(PROXY_CONFIG))
        .isEqualTo(PROXY_CONFIG.healthCheck.port);
    assertThat(proxyModule.provideEnvironment()).isEqualTo(Environment.LOCAL);
    assertThat(proxyModule.log).isFalse();
  }

  @Test
  public void testFailure_parseArgs_wrongArguments() {
    String[] args = {"--wrong_flag", "some_value"};
    try {
      proxyModule.parse(args);
      fail("Expected ParameterException.");
    } catch (ParameterException e) {
      assertThat(e).hasMessageThat().contains("--wrong_flag");
    }
  }

  @Test
  public void testSuccess_parseArgs_log() {
    String[] args = {"--log"};
    proxyModule.parse(args);
    assertThat(proxyModule.log).isTrue();
  }

  @Test
  public void testSuccess_parseArgs_customWhoisPort() {
    String[] args = {"--whois", "12345"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideWhoisPort(PROXY_CONFIG)).isEqualTo(12345);
  }

  @Test
  public void testSuccess_parseArgs_customEppPort() {
    String[] args = {"--epp", "22222"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideEppPort(PROXY_CONFIG)).isEqualTo(22222);
  }

  @Test
  public void testSuccess_parseArgs_customHealthCheckPort() {
    String[] args = {"--health_check", "23456"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideHealthCheckPort(PROXY_CONFIG)).isEqualTo(23456);
  }

  @Test
  public void testSuccess_parseArgs_customEnvironment() {
    String[] args = {"--env", "ALpHa"};
    proxyModule.parse(args);
    assertThat(proxyModule.provideEnvironment()).isEqualTo(Environment.ALPHA);
  }

  @Test
  public void testFailure_parseArgs_wrongEnvironment() {
    ParameterException e =
        expectThrows(
            ParameterException.class,
            () -> {
              String[] args = {"--env", "beta"};
              proxyModule.parse(args);
            });
    assertThat(e).hasMessageThat().contains("Invalid value for --env parameter");
  }
}
