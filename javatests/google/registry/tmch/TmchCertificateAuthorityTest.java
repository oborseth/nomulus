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

package google.registry.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PILOT;
import static google.registry.config.RegistryConfig.ConfigModule.TmchCaMode.PRODUCTION;
import static google.registry.testing.JUnitBackports.expectThrows;
import static google.registry.tmch.TmchTestData.loadFile;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static google.registry.util.X509Utils.loadCertificate;

import google.registry.model.tmch.TmchCrl;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.security.SignatureException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TmchCertificateAuthority}. */
@RunWith(JUnit4.class)
public class TmchCertificateAuthorityTest {

  public static final String GOOD_TEST_CERTIFICATE = loadFile("icann-tmch-test-good.crt");
  public static final String REVOKED_TEST_CERTIFICATE = loadFile("icann-tmch-test-revoked.crt");

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();
  @Rule
  public final InjectRule inject = new InjectRule();

  private FakeClock clock = new FakeClock(DateTime.parse("2014-01-01T00:00:00Z"));

  @Before
  public void before() throws Exception {
    inject.setStaticField(TmchCertificateAuthority.class, "clock", clock);
  }

  @Test
  public void testFailure_prodRootExpired() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PRODUCTION);
    clock.setTo(DateTime.parse("2024-01-01T00:00:00Z"));
    CertificateExpiredException e =
        expectThrows(CertificateExpiredException.class, tmchCertificateAuthority::getRoot);
    assertThat(e).hasMessageThat().containsMatch("NotAfter: Sun Jul 23 23:59:59 UTC 2023");
  }

  @Test
  public void testFailure_prodRootNotYetValid() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PRODUCTION);
    clock.setTo(DateTime.parse("2000-01-01T00:00:00Z"));
    CertificateNotYetValidException e =
        expectThrows(CertificateNotYetValidException.class, tmchCertificateAuthority::getRoot);
    assertThat(e).hasMessageThat().containsMatch("NotBefore: Wed Jul 24 00:00:00 UTC 2013");
  }

  @Test
  public void testFailure_crlDoesntMatchCerts() throws Exception {
    // Use the prod cl, which won't match our test certificate.
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT);
    TmchCrl.set(
        readResourceUtf8(TmchCertificateAuthority.class, "icann-tmch.crl"), "http://cert.crl");
    SignatureException e =
        expectThrows(
            SignatureException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(GOOD_TEST_CERTIFICATE)));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  public void testSuccess_verify() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT);
    tmchCertificateAuthority.verify(loadCertificate(GOOD_TEST_CERTIFICATE));
  }

  @Test
  public void testFailure_verifySignatureDoesntMatch() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PRODUCTION);
    SignatureException e =
        expectThrows(
            SignatureException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(GOOD_TEST_CERTIFICATE)));
    assertThat(e).hasMessageThat().contains("Signature does not match");
  }

  @Test
  public void testFailure_verifyRevoked() throws Exception {
    TmchCertificateAuthority tmchCertificateAuthority = new TmchCertificateAuthority(PILOT);
    CertificateRevokedException thrown =
        expectThrows(
            CertificateRevokedException.class,
            () -> tmchCertificateAuthority.verify(loadCertificate(REVOKED_TEST_CERTIFICATE)));
    assertThat(thrown).hasMessageThat().contains("revoked, reason: KEY_COMPROMISE");
  }
}
