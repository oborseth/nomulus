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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.JUnitBackports.expectThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TeeOutputStream}. */
@RunWith(JUnit4.class)
public class TeeOutputStreamTest {
  private final ByteArrayOutputStream outputA = new ByteArrayOutputStream();
  private final ByteArrayOutputStream outputB = new ByteArrayOutputStream();
  private final ByteArrayOutputStream outputC = new ByteArrayOutputStream();

  @Test
  public void testWrite_writesToMultipleStreams() throws Exception {
    // Write shared data using the tee output stream.
    try (OutputStream tee =
        new TeeOutputStream(asList(outputA, outputB, outputC))) {
      tee.write("hello ".getBytes(UTF_8));
      tee.write("hello world!".getBytes(UTF_8), 6, 5);
      tee.write('!');
    }
    // Write some more data to the different streams - they should not have been closed.
    outputA.write("a".getBytes(UTF_8));
    outputB.write("b".getBytes(UTF_8));
    outputC.write("c".getBytes(UTF_8));
    // Check the results.
    assertThat(outputA.toString()).isEqualTo("hello world!a");
    assertThat(outputB.toString()).isEqualTo("hello world!b");
    assertThat(outputC.toString()).isEqualTo("hello world!c");
  }

  @Test
  @SuppressWarnings("resource")
  public void testConstructor_failsWithEmptyIterable() {
    assertThrows(IllegalArgumentException.class, () -> new TeeOutputStream(ImmutableSet.of()));
  }

  @Test
  public void testWriteInteger_failsAfterClose() throws Exception {
    OutputStream tee = new TeeOutputStream(asList(outputA));
    tee.close();
    IllegalStateException thrown = expectThrows(IllegalStateException.class, () -> tee.write(1));
    assertThat(thrown).hasMessageThat().contains("outputstream closed");
  }

  @Test
  public void testWriteByteArray_failsAfterClose() throws Exception {
    OutputStream tee = new TeeOutputStream(asList(outputA));
    tee.close();
    IllegalStateException thrown =
        expectThrows(IllegalStateException.class, () -> tee.write("hello".getBytes(UTF_8)));
    assertThat(thrown).hasMessageThat().contains("outputstream closed");
  }

  @Test
  public void testWriteByteSubarray_failsAfterClose() throws Exception {
    OutputStream tee = new TeeOutputStream(asList(outputA));
    tee.close();
    IllegalStateException thrown =
        expectThrows(IllegalStateException.class, () -> tee.write("hello".getBytes(UTF_8), 1, 3));
    assertThat(thrown).hasMessageThat().contains("outputstream closed");
  }
}
