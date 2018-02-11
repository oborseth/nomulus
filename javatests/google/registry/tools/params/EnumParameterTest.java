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

package google.registry.tools.params;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.expectThrows;

import google.registry.model.registry.Registry.TldState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EnumParameter}. */
@RunWith(JUnit4.class)
public class EnumParameterTest {

  // There's no additional functionality exposed by this (or any other) EnumParameter, but using
  // this in the test as EnumParameter is abstract.
  private final TldStateParameter instance = new TldStateParameter();

  @Test
  public void testSuccess_convertEnum() throws Exception {
    assertThat(instance.convert("PREDELEGATION")).isEqualTo(TldState.PREDELEGATION);
  }

  @Test
  public void testFailure_badValue() throws Exception {
    IllegalArgumentException thrown =
        expectThrows(IllegalArgumentException.class, () -> instance.convert("GENERAL_SUNRUSH"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "No enum constant google.registry.model.registry.Registry.TldState.GENERAL_SUNRUSH");
  }
}
