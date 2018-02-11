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

package google.registry.beam;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.testing.TestDataHelper;
import org.apache.beam.sdk.io.DefaultFilenamePolicy.Params;
import org.apache.beam.sdk.io.FileBasedSink;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InvoicingUtils}. */
@RunWith(JUnit4.class)
public class InvoicingUtilsTest {

  @Test
  public void testDestinationFunction_generatesProperFileParams() {
    SerializableFunction<BillingEvent, Params> destinationFunction =
        InvoicingUtils.makeDestinationFunction("my/directory", StaticValueProvider.of("2017-10"));

    BillingEvent billingEvent = mock(BillingEvent.class);
    // We mock BillingEvent to make the test independent of the implementation of toFilename()
    when(billingEvent.toFilename(any())).thenReturn("invoice_details_2017-10_registrar_tld");

    assertThat(destinationFunction.apply(billingEvent))
        .isEqualTo(
            new Params()
                .withShardTemplate("")
                .withSuffix(".csv")
                .withBaseFilename(
                    FileBasedSink.convertToFileResourceIfPossible(
                        "my/directory/2017-10/invoice_details_2017-10_registrar_tld")));
  }

  @Test
  public void testEmptyDestinationParams() {
    assertThat(InvoicingUtils.makeEmptyDestinationParams("my/directory"))
        .isEqualTo(
            new Params()
                .withBaseFilename(
                    FileBasedSink.convertToFileResourceIfPossible("my/directory/FAILURES")));
  }

  /** Asserts that the instantiated sql template matches a golden expected file. */
  @Test
  public void testMakeQueryProvider() {
    ValueProvider<String> queryProvider =
        InvoicingUtils.makeQueryProvider(StaticValueProvider.of("2017-10"), "my-project-id");
    assertThat(queryProvider.get()).isEqualTo(loadFile("billing_events_test.sql"));
  }

  /** Returns a {@link String} from a file in the {@code billing/testdata/} directory. */
  private static String loadFile(String filename) {
    return TestDataHelper.loadFile(InvoicingUtilsTest.class, filename);
  }
}
