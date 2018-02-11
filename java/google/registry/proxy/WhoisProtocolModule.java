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

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.proxy.HttpsRelayProtocolModule.HttpsRelayProtocol;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.handler.RelayHandler.FullHttpRequestRelayHandler;
import google.registry.proxy.handler.WhoisServiceHandler;
import google.registry.proxy.metric.FrontendMetrics;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** A module that provides the {@link FrontendProtocol} used for whois protocol. */
@Module
class WhoisProtocolModule {

  /** Dagger qualifier to provide whois protocol related handlers and other bindings. */
  @Qualifier
  @interface WhoisProtocol {};

  private static final String PROTOCOL_NAME = "whois";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideProtocol(
      ProxyConfig config,
      @WhoisProtocol int whoisPort,
      @WhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders,
      @HttpsRelayProtocol BackendProtocol.Builder backendProtocolBuilder) {
    return Protocol.frontendBuilder()
        .name(PROTOCOL_NAME)
        .port(whoisPort)
        .handlerProviders(handlerProviders)
        .relayProtocol(backendProtocolBuilder.host(config.whois.relayHost).build())
        .build();
  }

  @Provides
  @WhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      @WhoisProtocol Provider<ReadTimeoutHandler> readTimeoutHandlerProvider,
      Provider<LineBasedFrameDecoder> lineBasedFrameDecoderProvider,
      Provider<WhoisServiceHandler> whoisServiceHandlerProvider,
      Provider<LoggingHandler> loggingHandlerProvider,
      Provider<FullHttpRequestRelayHandler> relayHandlerProvider) {
    return ImmutableList.of(
        readTimeoutHandlerProvider,
        lineBasedFrameDecoderProvider,
        whoisServiceHandlerProvider,
        loggingHandlerProvider,
        relayHandlerProvider);
  }

  @Provides
  static WhoisServiceHandler provideWhoisServiceHandler(
      ProxyConfig config,
      @Named("accessToken") Supplier<String> accessTokenSupplier,
      FrontendMetrics metrics) {
    return new WhoisServiceHandler(
        config.whois.relayHost, config.whois.relayPath, accessTokenSupplier, metrics);
  }

  @Provides
  static LineBasedFrameDecoder provideLineBasedFrameDecoder(ProxyConfig config) {
    return new LineBasedFrameDecoder(config.whois.maxMessageLengthBytes);
  }

  @Provides
  @WhoisProtocol
  static ReadTimeoutHandler provideReadTimeoutHandler(ProxyConfig config) {
    return new ReadTimeoutHandler(config.whois.readTimeoutSeconds);
  }
}
