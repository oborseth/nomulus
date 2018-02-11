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

package google.registry.proxy.handler;

import com.google.common.base.Supplier;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;

/** Handler that processes WHOIS protocol logic. */
public final class WhoisServiceHandler extends HttpsRelayServiceHandler {

  public WhoisServiceHandler(
      String relayHost,
      String relayPath,
      Supplier<String> accessTokenSupplier,
      FrontendMetrics metrics) {
    super(relayHost, relayPath, accessTokenSupplier, metrics);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    metrics.registerActiveConnection("whois", "none", ctx.channel());
    super.channelActive(ctx);
  }

  @Override
  protected FullHttpRequest decodeFullHttpRequest(ByteBuf byteBuf) {
    FullHttpRequest request = super.decodeFullHttpRequest(byteBuf);
    request
        .headers()
        // Close connection after a response is received, per RFC-3912
        // https://tools.ietf.org/html/rfc3912
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
        .set(HttpHeaderNames.ACCEPT, HttpHeaderValues.TEXT_PLAIN);
    return request;
  }
}
