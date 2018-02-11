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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.expectThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.DeterministicStringGenerator;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CreateDomainCommand}. */
public class CreateDomainCommandTest extends EppToolCommandTestCase<CreateDomainCommand> {

  @Before
  public void initCommand() {
    command.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  public void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--period=1",
        "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "--password=2fooBAR",
        "--ds_records=1 2 3 abcd,4 5 6 EF01",
        "--ds_records=60485 5  2  D4B7D520E7BB5F0F67674A0CCEB1E3E0614B93C4F9E99B8383F6A1E4469DA50A",
        "example.tld");
    eppVerifier.verifySent("domain_create_complete.xml");
  }

  @Test
  public void testSuccess_minimal() throws Exception {
    // Test that each optional field can be omitted. Also tests the auto-gen password.
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "example.tld");
    eppVerifier.verifySent("domain_create_minimal.xml");
  }

  @Test
  public void testSuccess_multipleDomains() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admins=crr-admin",
        "--techs=crr-tech",
        "example.tld",
        "example.abc");
    eppVerifier
        .verifySent("domain_create_minimal.xml")
        .verifySent("domain_create_minimal_abc.xml");
  }

  @Test
  public void testFailure_duplicateDomains() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "example.tld",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Duplicate arguments found: \'example.tld\'");
  }

  @Test
  public void testFailure_missingDomain() throws Exception {
    ParameterException thrown =
        expectThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech"));
    assertThat(thrown).hasMessageThat().contains("Main parameters are required");
  }

  @Test
  public void testFailure_missingClientId() throws Exception {
    ParameterException thrown =
        expectThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--registrant=crr-admin",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("--client");
  }

  @Test
  public void testFailure_missingRegistrant() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("Registrant must be specified");
  }

  @Test
  public void testFailure_missingAdmins() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--techs=crr-tech",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("At least one admin must be specified");
  }

  @Test
  public void testFailure_missingTechs() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("At least one tech must be specified");
  }

  @Test
  public void testFailure_tooManyNameServers() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google,"
                        + "ns5.zdns.google,ns6.zdns.google,ns7.zdns.google,ns8.zdns.google,"
                        + "ns9.zdns.google,ns10.zdns.google,ns11.zdns.google,ns12.zdns.google,"
                        + "ns13.zdns.google,ns14.zdns.google",
                    "example.tld"));
    assertThat(thrown).hasMessageThat().contains("There can be at most 13 nameservers");
  }

  @Test
  public void testFailure_badPeriod() throws Exception {
    ParameterException thrown =
        expectThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar",
                    "--registrant=crr-admin",
                    "--admins=crr-admin",
                    "--techs=crr-tech",
                    "--period=x",
                    "--domain=example.tld"));
    assertThat(thrown).hasMessageThat().contains("--period");
  }

  @Test
  public void testFailure_dsRecordsNot4Parts() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=1 2 3 ab cd",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("should have 4 parts, but has 5");
  }

  @Test
  public void testFailure_keyTagNotNumber() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=x 2 3 abcd",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  public void testFailure_algNotNumber() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=1 x 3 abcd",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  public void testFailure_digestTypeNotNumber() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=1 2 x abcd",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("\"x\"");
  }

  @Test
  public void testFailure_digestNotHex() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=1 2 3 xbcd",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("XBCD");
  }

  @Test
  public void testFailure_digestNotEvenLengthed() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(
            IllegalArgumentException.class,
            () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--registrant=crr-admin",
                "--admins=crr-admin",
                "--techs=crr-tech",
                "--ds_records=1 2 3 abcde",
                "example.tld"));
    assertThat(thrown).hasMessageThat().contains("length 5");
  }
}
