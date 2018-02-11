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

import static google.registry.util.ResourceUtils.readResourceBytes;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.proxy.HttpsRelayProtocolModule.HttpsRelayProtocol;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.handler.EppServiceHandler;
import google.registry.proxy.handler.ProxyProtocolHandler;
import google.registry.proxy.handler.RelayHandler.FullHttpRequestRelayHandler;
import google.registry.proxy.handler.SslServerInitializer;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.util.FormattingLogger;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.io.IOException;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** A module that provides the {@link FrontendProtocol} used for epp protocol. */
@Module
class EppProtocolModule {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  /** Dagger qualifier to provide epp protocol related handlers and other bindings. */
  @Qualifier
  @interface EppProtocol {};

  private static final String PROTOCOL_NAME = "epp";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideProtocol(
      ProxyConfig config,
      @EppProtocol int eppPort,
      @EppProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders,
      @HttpsRelayProtocol BackendProtocol.Builder backendProtocolBuilder) {
    return Protocol.frontendBuilder()
        .name(PROTOCOL_NAME)
        .port(eppPort)
        .handlerProviders(handlerProviders)
        .relayProtocol(backendProtocolBuilder.host(config.epp.relayHost).build())
        .build();
  }

  @Provides
  @EppProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      Provider<SslServerInitializer<NioSocketChannel>> sslServerInitializerProvider,
      Provider<ProxyProtocolHandler> proxyProtocolHandlerProvider,
      @EppProtocol Provider<ReadTimeoutHandler> readTimeoutHandlerProvider,
      Provider<LengthFieldBasedFrameDecoder> lengthFieldBasedFrameDecoderProvider,
      Provider<LengthFieldPrepender> lengthFieldPrependerProvider,
      Provider<EppServiceHandler> eppServiceHandlerProvider,
      Provider<LoggingHandler> loggingHandlerProvider,
      Provider<FullHttpRequestRelayHandler> relayHandlerProvider) {
    return ImmutableList.of(
        proxyProtocolHandlerProvider,
        sslServerInitializerProvider,
        readTimeoutHandlerProvider,
        lengthFieldBasedFrameDecoderProvider,
        lengthFieldPrependerProvider,
        eppServiceHandlerProvider,
        loggingHandlerProvider,
        relayHandlerProvider);
  }

  @Provides
  static LengthFieldBasedFrameDecoder provideLengthFieldBasedFrameDecoder(ProxyConfig config) {
    return new LengthFieldBasedFrameDecoder(
        // Max message length.
        config.epp.maxMessageLengthBytes,
        // Header field location offset.
        0,
        // Header field length.
        config.epp.headerLengthBytes,
        // Adjustment applied to the header field value in order to obtain message length.
        -config.epp.headerLengthBytes,
        // Initial bytes to strip (i. e. strip the length header).
        config.epp.headerLengthBytes);
  }

  @Singleton
  @Provides
  static LengthFieldPrepender provideLengthFieldPrepender(ProxyConfig config) {
    return new LengthFieldPrepender(
        // Header field length.
        config.epp.headerLengthBytes,
        // Length includes header field length.
        true);
  }

  @Provides
  @EppProtocol
  static ReadTimeoutHandler provideReadTimeoutHandler(ProxyConfig config) {
    return new ReadTimeoutHandler(config.epp.readTimeoutSeconds);
  }

  @Singleton
  @Provides
  @Named("hello")
  static byte[] provideHelloBytes() {
    try {
      return readResourceBytes(EppProtocolModule.class, "resources/hello.xml").read();
    } catch (IOException e) {
      logger.severe(e, "Cannot read EPP <hello> message file.");
      throw new RuntimeException(e);
    }
  }

  @Provides
  static EppServiceHandler provideEppServiceHandler(
      @Named("accessToken") Supplier<String> accessTokenSupplier,
      @Named("hello") byte[] helloBytes,
      FrontendMetrics metrics,
      ProxyConfig config) {
    return new EppServiceHandler(
        config.epp.relayHost,
        config.epp.relayPath,
        accessTokenSupplier,
        config.epp.serverHostname,
        helloBytes,
        metrics);
  }
}
