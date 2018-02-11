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

package google.registry.model.registry.label;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.JUnitBackports.expectThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListRevision;
import google.registry.testing.AppEngineRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PremiumList}. */
@RunWith(JUnit4.class)
public class PremiumListTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Before
  public void before() throws Exception {
    // createTld() overwrites the premium list, so call it first.
    createTld("tld");
    PremiumList pl =
        persistPremiumList(
            "tld",
            "lol,USD 999 # yup",
            "rich,USD 1999 #tada",
            "icann,JPY 100",
            "johnny-be-goode,USD 20.50");
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
  }

  @Test
  public void testSave_badSyntax() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> persistPremiumList("gtld1", "lol,nonsense USD,e,e # yup"));
  }

  @Test
  public void testSave_invalidCurrencySymbol() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> persistReservedList("gtld1", "lol,XBTC 200"));
  }

  @Test
  public void testProbablePremiumLabels() throws Exception {
    PremiumList pl = PremiumList.get("tld").get();
    PremiumListRevision revision = ofy().load().key(pl.getRevisionKey()).now();
    assertThat(revision.getProbablePremiumLabels().mightContain("notpremium")).isFalse();
    for (String label : ImmutableList.of("rich", "lol", "johnny-be-goode", "icann")) {
      assertWithMessage(label + " should be a probable premium")
          .that(revision.getProbablePremiumLabels().mightContain(label))
          .isTrue();
    }
  }

  @Test
  public void testParse_cannotIncludeDuplicateLabels() {
    IllegalStateException thrown =
        expectThrows(
            IllegalStateException.class,
            () ->
                PremiumList.get("tld")
                    .get()
                    .parse(
                        ImmutableList.of(
                            "lol,USD 100",
                            "rofl,USD 90",
                            "paper,USD 80",
                            "wood,USD 70",
                            "lol,USD 200")));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "List 'tld' cannot contain duplicate labels. Dupes (with counts) were: [lol x 2]");
  }
}
