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
import static google.registry.proxy.TestUtils.makeWhoisHttpRequest;
import static google.registry.proxy.TestUtils.makeWhoisHttpResponse;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.fail;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** End-to-end tests for {@link WhoisProtocolModule}. */
@RunWith(JUnit4.class)
public class WhoisProtocolModuleTest extends ProtocolModuleTest {

  public WhoisProtocolModuleTest() {
    super(TestComponent::whoisHandlers);
  }

  @Test
  public void testSuccess_singleFrameInboundMessage() {
    String inputString = "test.tld\r\n";
    // Inbound message processed and passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(inputString.getBytes(US_ASCII))))
        .isTrue();

    FullHttpRequest actualRequest = channel.readInbound();
    FullHttpRequest expectedRequest =
        makeWhoisHttpRequest(
            "test.tld",
            PROXY_CONFIG.whois.relayHost,
            PROXY_CONFIG.whois.relayPath,
            TestModule.provideFakeAccessToken().get());
    assertThat(expectedRequest).isEqualTo(actualRequest);
    assertThat(channel.isActive()).isTrue();
    // Nothing more to read.
    assertThat((Object) channel.readInbound()).isNull();
  }

  @Test
  public void testSuccess_noNewlineInboundMessage() {
    String inputString = "test.tld";
    // No newline encountered, no message formed.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(inputString.getBytes(US_ASCII))))
        .isFalse();
    assertThat(channel.isActive()).isTrue();
  }

  @Test
  public void testSuccess_multiFrameInboundMessage() {
    String frame1 = "test";
    String frame2 = "1.tld";
    String frame3 = "\r\nte";
    String frame4 = "st2.tld\r";
    String frame5 = "\ntest3.tld";
    // No newline yet.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame1.getBytes(US_ASCII)))).isFalse();
    // Still no newline yet.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame2.getBytes(US_ASCII)))).isFalse();
    // First newline encountered.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame3.getBytes(US_ASCII)))).isTrue();
    FullHttpRequest actualRequest1 = channel.readInbound();
    FullHttpRequest expectedRequest1 =
        makeWhoisHttpRequest(
            "test1.tld",
            PROXY_CONFIG.whois.relayHost,
            PROXY_CONFIG.whois.relayPath,
            TestModule.provideFakeAccessToken().get());
    assertThat(expectedRequest1).isEqualTo(actualRequest1);
    // No more message at this point.
    assertThat((Object) channel.readInbound()).isNull();
    // More inbound bytes, but no newline.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame4.getBytes(US_ASCII)))).isFalse();
    // Second message read.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(frame5.getBytes(US_ASCII)))).isTrue();
    FullHttpRequest actualRequest2 = channel.readInbound();
    FullHttpRequest expectedRequest2 =
        makeWhoisHttpRequest(
            "test2.tld",
            PROXY_CONFIG.whois.relayHost,
            PROXY_CONFIG.whois.relayPath,
            TestModule.provideFakeAccessToken().get());
    assertThat(expectedRequest2).isEqualTo(actualRequest2);
    // The third message is not complete yet.
    assertThat(channel.isActive()).isTrue();
    assertThat((Object) channel.readInbound()).isNull();
  }

  @Test
  public void testSuccess_inboundMessageTooLong() {
    String inputString = Stream.generate(() -> "x").limit(513).collect(joining()) + "\r\n";
    // Nothing gets propagated further.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(inputString.getBytes(US_ASCII))))
        .isFalse();
    // Connection is closed due to inbound message overflow.
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testSuccess_parseSingleOutboundHttpResponse() {
    String outputString = "line1\r\nline2\r\n";
    FullHttpResponse response = makeWhoisHttpResponse(outputString, HttpResponseStatus.OK);
    // Http response parsed and passed along.
    assertThat(channel.writeOutbound(response)).isTrue();
    ByteBuf outputBuffer = channel.readOutbound();
    assertThat(outputBuffer.toString(US_ASCII)).isEqualTo(outputString);
    assertThat(channel.isActive()).isTrue();
    // Nothing more to write.
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test
  public void testSuccess_parseMultipleOutboundHttpResponse() {
    String outputString1 = "line1\r\nline2\r\n";
    String outputString2 = "line3\r\nline4\r\nline5\r\n";
    FullHttpResponse response1 = makeWhoisHttpResponse(outputString1, HttpResponseStatus.OK);
    FullHttpResponse response2 = makeWhoisHttpResponse(outputString2, HttpResponseStatus.OK);
    assertThat(channel.writeOutbound(response1, response2)).isTrue();
    // First Http response parsed
    ByteBuf outputBuffer1 = channel.readOutbound();
    assertThat(outputBuffer1.toString(US_ASCII)).isEqualTo(outputString1);
    // Second Http response parsed
    ByteBuf outputBuffer2 = channel.readOutbound();
    assertThat(outputBuffer2.toString(US_ASCII)).isEqualTo(outputString2);
    assertThat(channel.isActive()).isTrue();
    // Nothing more to write.
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test
  public void testFailure_outboundResponseStatusNotOK() {
    String outputString = "line1\r\nline2\r\n";
    FullHttpResponse response = makeWhoisHttpResponse(outputString, HttpResponseStatus.BAD_REQUEST);
    try {
      channel.writeOutbound(response);
      fail("Expected failure due to non-OK HTTP response status");
    } catch (Exception e) {
      assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
      assertThat(e).hasMessageThat().contains("400 Bad Request");
    }
    assertThat(channel.isActive()).isFalse();
  }
}
