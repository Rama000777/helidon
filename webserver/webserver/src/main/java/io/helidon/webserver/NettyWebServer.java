/*
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.webserver;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.helidon.common.HelidonFeatures;
import io.helidon.common.HelidonFlavor;
import io.helidon.common.context.Context;
import io.helidon.common.reactive.Single;
import io.helidon.media.common.MessageBodyReaderContext;
import io.helidon.media.common.MessageBodyWriterContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.concurrent.Future;

/**
 * The Netty based WebServer implementation.
 */
@SuppressWarnings("deprecation")
class NettyWebServer implements WebServer {
    static final String TRACING_COMPONENT = "web-server";

    private static final Logger LOGGER = Logger.getLogger(NettyWebServer.class.getName());
    private static final String EXIT_ON_STARTED_KEY = "exit.on.started";
    private static final boolean EXIT_ON_STARTED = "!".equals(System.getProperty(EXIT_ON_STARTED_KEY));

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Map<String, ServerBootstrap> bootstraps = new HashMap<>();
    private final ServerConfiguration configuration;
    private final CompletableFuture<WebServer> startFuture = new CompletableFuture<>();
    private final CompletableFuture<WebServer> shutdownFuture = new CompletableFuture<>();
    private final CompletableFuture<WebServer> channelsUpFuture = new CompletableFuture<>();
    private final CompletableFuture<WebServer> channelsCloseFuture = new CompletableFuture<>();
    private final CompletableFuture<WebServer> threadGroupsShutdownFuture = new CompletableFuture<>();
    private final Context contextualRegistry;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();
    private final List<HttpInitializer> initializers = new LinkedList<>();
    private final MessageBodyWriterContext writerContext;
    private final MessageBodyReaderContext readerContext;

    private volatile boolean started;
    private final AtomicBoolean shutdownThreadGroupsInitiated = new AtomicBoolean(false);

    /**
     * Creates a new instance.
     *
     * @param config a server configuration instance
     * @param routing       a default routing instance
     * @param namedRoutings the named routings of the configured additional server sockets. If there is no
     *                      named routing for a given named additional server socket configuration, a default
     *                      routing is used.
     */
    NettyWebServer(ServerConfiguration config,
                   Routing routing,
                   Map<String, Routing> namedRoutings,
                   MessageBodyWriterContext writerContext,
                   MessageBodyReaderContext readerContext) {
        Set<Map.Entry<String, SocketConfiguration>> sockets = config.sockets().entrySet();

        HelidonFeatures.print(HelidonFlavor.SE, config.printFeatureDetails());
        this.bossGroup = new NioEventLoopGroup(sockets.size());
        this.workerGroup = config.workersCount() <= 0 ? new NioEventLoopGroup() : new NioEventLoopGroup(config.workersCount());
        this.contextualRegistry = config.context();
        this.configuration = config;
        this.readerContext = MessageBodyReaderContext.create(readerContext);
        this.writerContext = MessageBodyWriterContext.create(writerContext);

        for (Map.Entry<String, SocketConfiguration> entry : sockets) {
            String name = entry.getKey();
            SocketConfiguration soConfig = entry.getValue();

            if (!soConfig.enabled()) {
                LOGGER.info("Channel '" + name + "' is disabled.");
                continue;
            }

            ServerBootstrap bootstrap = new ServerBootstrap();
            // Transform java SSLContext into Netty SslContext
            JdkSslContext sslContext = null;
            if (soConfig.ssl() != null) {
                // TODO configuration support for CLIENT AUTH (btw, ClientAuth.REQUIRE doesn't seem to work with curl nor with
                // Chrome)
                String[] protocols;
                if (soConfig.enabledSslProtocols().isEmpty()) {
                    protocols = null;
                } else {
                    protocols = soConfig.enabledSslProtocols().toArray(new String[0]);
                }

                // Enable ALPN for application protocol negotiation with HTTP/2
                // Needs JDK >= 9 or Jetty’s ALPN boot library
                ApplicationProtocolConfig appProtocolConfig = null;
                if (configuration.isHttp2Enabled()) {
                    appProtocolConfig = new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1);
                }

                sslContext = new JdkSslContext(
                        soConfig.ssl(), false, null,
                        IdentityCipherSuiteFilter.INSTANCE, appProtocolConfig,
                        soConfig.clientAuth().nettyClientAuth(), protocols, false);
            }

            if (soConfig.backlog() > 0) {
                bootstrap.option(ChannelOption.SO_BACKLOG, soConfig.backlog());
            }
            if (soConfig.timeoutMillis() > 0) {
                bootstrap.option(ChannelOption.SO_TIMEOUT, soConfig.timeoutMillis());
            }
            if (soConfig.receiveBufferSize() > 0) {
                bootstrap.option(ChannelOption.SO_RCVBUF, soConfig.receiveBufferSize());
            }

            HttpInitializer childHandler = new HttpInitializer(sslContext, namedRoutings.getOrDefault(name, routing), this);
            initializers.add(childHandler);
            bootstrap.group(bossGroup, workerGroup)
                     .channel(NioServerSocketChannel.class)
                     .handler(new LoggingHandler(LogLevel.DEBUG))
                     .childHandler(childHandler);

            bootstraps.put(name, bootstrap);
        }
    }

    @Override
    public ServerConfiguration configuration() {
        return configuration;
    }

    @Override
    public MessageBodyReaderContext readerContext() {
        return readerContext;
    }

    @Override
    public MessageBodyWriterContext writerContext() {
        return writerContext;
    }

    @Override
    public synchronized Single<WebServer> start() {
        if (!started) {

            channelsUpFuture.thenAccept(this::started)
                            .exceptionally(throwable -> {
                                if (channels.isEmpty()) {
                                    startFailureHandler(throwable);
                                }
                                for (Channel channel : channels.values()) {
                                    channel.close();
                                }
                                return null;
                            });

            channelsCloseFuture.whenComplete((webServer, throwable) -> shutdown(throwable));

            Set<Map.Entry<String, ServerBootstrap>> bootstrapEntries = bootstraps.entrySet();
            int bootstrapsSize = bootstrapEntries.size();
            for (Map.Entry<String, ServerBootstrap> entry : bootstrapEntries) {
                ServerBootstrap bootstrap = entry.getValue();
                String name = entry.getKey();
                SocketConfiguration socketConfig = configuration.socket(name);
                if (socketConfig == null) {
                    throw new IllegalStateException(
                            "no socket configuration found for name: " + name);
                }
                int port = Math.max(socketConfig.port(), 0);
                if (channelsUpFuture.isCompletedExceptionally()) {
                    // break because one of the previous channels already failed
                    break;
                }

                try {
                    bootstrap.bind(configuration.bindAddress(), port).addListener(channelFuture -> {
                        if (!channelFuture.isSuccess()) {
                            LOGGER.info(() -> "Channel '" + name + "' startup failed with message '"
                                    + channelFuture.cause().getMessage() + "'.");
                            Throwable cause = channelFuture.cause();

                            String message = "Channel startup failed: " + name;
                            if (cause instanceof BindException) {
                                message = message + ", failed to listen on " + configuration.bindAddress() + ":" + port;
                            }

                            channelsUpFuture.completeExceptionally(new IllegalStateException(message,
                                                                                             channelFuture.cause()));

                            return;
                        }

                        Channel channel = ((ChannelFuture) channelFuture).channel();
                        LOGGER.info(() -> "Channel '" + name + "' started: " + channel);
                        channels.put(name, channel);

                        channel.closeFuture().addListener(future -> {
                            LOGGER.info(() -> "Channel '" + name + "' closed: " + channel);
                            channels.remove(name);
                            if (channelsUpFuture.isCompletedExceptionally()) {
                                // we're in a startup failure handler
                                if (channels.isEmpty()) {
                                    channelsUpFuture.exceptionally(this::startFailureHandler);
                                    // all the channels are down
                                } else if (future.cause() != null) {
                                    LOGGER.log(Level.WARNING,
                                               "Startup failure channel close failure",
                                               new IllegalStateException(future.cause()));
                                }
                            } else {
                                if (!future.isSuccess()) {
                                    channelsCloseFuture.completeExceptionally(new IllegalStateException("Channel stop failure.",
                                                                                                        future.cause()));
                                } else if (channels.isEmpty()) {
                                    channelsCloseFuture.complete(this);
                                }
                                // else we're waiting for the rest of the channels to start, successful branch
                            }
                        });

                        if (channelsUpFuture.isCompletedExceptionally()) {
                            channel.close();
                        }

                        if (channels.size() >= bootstrapsSize) {
                            LOGGER.finer(() -> "All channels started: " + channels.size());
                            channelsUpFuture.complete(this);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    if (shutdownThreadGroupsInitiated.get()) {
                        // a rejected exception is expected and we shall stop starting the rest of the sockets
                        break;
                    } else {
                        throw e;
                    }
                }
            }

            started = true;
            LOGGER.fine(() -> "All channels startup routine initiated: " + bootstrapsSize);
        }
        return Single.create(startFuture);
    }

    private void started(WebServer server) {
        if (EXIT_ON_STARTED) {
            LOGGER.info(String.format("Exiting, -D%s set.", EXIT_ON_STARTED_KEY));
            System.exit(0);
        } else {
            startFuture.complete(server);
        }
    }

    private WebServer startFailureHandler(Throwable throwable) {
        shutdownThreadGroups()
                .whenComplete((webServer, t) -> {
                    if (t != null) {
                        LOGGER.log(Level.WARNING, "Netty Thread Groups were unable to shutdown.", t);
                    }
                    shutdownFuture.complete(this);
                    startFuture.completeExceptionally(new IllegalStateException("WebServer was unable to start.",
                                                                                throwable));
                });
        return null;
    }

    private void shutdown(Throwable cause) {
        shutdownThreadGroups()
                .whenComplete((webServer, throwable) -> {
                    if (cause == null && throwable == null) {
                        shutdownFuture.complete(this);
                    } else if (cause != null) {
                        if (throwable != null) {
                            LOGGER.log(Level.WARNING, "Netty Thread Groups were unable to shutdown.", throwable);
                        }
                        shutdownFuture.completeExceptionally(
                                new IllegalStateException("WebServer was unable to stop.", cause));
                    } else {
                        shutdownFuture.completeExceptionally(
                                new IllegalStateException("WebServer was unable to stop.", throwable));
                    }
                });
    }

    private CompletionStage<WebServer> shutdownThreadGroups() {
        if (shutdownThreadGroupsInitiated.getAndSet(true)) {
            return threadGroupsShutdownFuture;
        }

        forceQueuesRelease();

        // there's no need for a quiet time as the channel is not expected to be used from now on
        Future<?> bossGroupFuture = bossGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);
        Future<?> workerGroupFuture = workerGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);

        workerGroupFuture.addListener(workerFuture -> {
            bossGroupFuture.addListener(bossFuture -> {
                if (workerFuture.isSuccess() && bossFuture.isSuccess()) {
                    threadGroupsShutdownFuture.complete(this);
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(workerFuture.cause() != null ? "Worker Group problem: " + workerFuture.cause().getMessage() : "")
                            .append(bossFuture.cause() != null ? "Boss Group problem: " + bossFuture.cause().getMessage() : "");
                    threadGroupsShutdownFuture
                            .completeExceptionally(new IllegalStateException("Unable to shutdown Netty thread groups: " + sb));
                }
            });
        });
        return threadGroupsShutdownFuture;
    }

    private void forceQueuesRelease() {
        initializers.removeIf(httpInitializer -> {
            httpInitializer.queuesShutdown();
            return true;
        });
    }

    @Override
    public Single<WebServer> shutdown() {
        if (!startFuture.isDone()) {
            startFuture.cancel(true);
        }
        if (channels.isEmpty()) {
            channelsCloseFuture.complete(this);
        }
        for (Channel channel : channels.values()) {
            channel.close();
        }
        return Single.create(shutdownFuture);
    }

    @Override
    public Single<WebServer> whenShutdown() {
        // we need to return a new single each time
        return Single.create(shutdownFuture);
    }

    @Override
    public boolean isRunning() {
        return startFuture.isDone() && !shutdownFuture.isDone();
    }

    @Override
    public Context context() {
        return contextualRegistry;
    }

    @Override
    public int port(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            return -1;
        }
        SocketAddress address = channel.localAddress();
        return address instanceof InetSocketAddress ? ((InetSocketAddress) address).getPort() : -1;
    }
}
