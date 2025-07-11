/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.transport.grpc;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.buffer.PooledByteBufAllocator;
import io.grpc.netty.shaded.io.netty.buffer.PooledByteBufAllocatorMetric;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import nl.altindag.ssl.SSLFactory;
import org.apache.pinot.common.config.GrpcConfig;
import org.apache.pinot.common.config.TlsConfig;
import org.apache.pinot.common.datatable.DataTable;
import org.apache.pinot.common.metrics.ServerGauge;
import org.apache.pinot.common.metrics.ServerMeter;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.common.metrics.ServerTimer;
import org.apache.pinot.common.proto.PinotQueryServerGrpc;
import org.apache.pinot.common.proto.Server.ServerRequest;
import org.apache.pinot.common.proto.Server.ServerResponse;
import org.apache.pinot.common.utils.tls.PinotInsecureMode;
import org.apache.pinot.common.utils.tls.RenewableTlsUtils;
import org.apache.pinot.core.operator.blocks.InstanceResponseBlock;
import org.apache.pinot.core.operator.streaming.StreamingResponseUtils;
import org.apache.pinot.core.query.executor.QueryExecutor;
import org.apache.pinot.core.query.logger.ServerQueryLogger;
import org.apache.pinot.core.query.request.ServerQueryRequest;
import org.apache.pinot.core.query.scheduler.resources.ResourceManager;
import org.apache.pinot.server.access.AccessControl;
import org.apache.pinot.server.access.GrpcRequesterIdentity;
import org.apache.pinot.spi.query.QueryThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// TODO: Plug in QueryScheduler
public class GrpcQueryServer extends PinotQueryServerGrpc.PinotQueryServerImplBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(GrpcQueryServer.class);
  // the key is the hashCode of the TlsConfig, the value is the SslContext
  // We don't use TlsConfig as the map key because the TlsConfig is mutable, which means the hashCode can change. If the
  // hashCode changes and the map is resized, the SslContext of the old hashCode will be lost.
  private static final Map<Integer, SslContext> SERVER_SSL_CONTEXTS_CACHE = new ConcurrentHashMap<>();

  private static final int DEFAULT_GRPC_QUERY_WORKER_THREAD =
      Math.max(2, Math.min(8, ResourceManager.DEFAULT_QUERY_WORKER_THREADS));

  private final String _instanceId;
  private final QueryExecutor _queryExecutor;
  private final ServerMetrics _serverMetrics;
  private final Server _server;
  private final ExecutorService _executorService;
  private final AccessControl _accessControl;
  private final ServerQueryLogger _queryLogger = ServerQueryLogger.getInstance();
  // Memory allocator and throttling configuration
  private final PooledByteBufAllocator _bufAllocator;
  private final long _memoryThresholdBytes;

  // Filter to keep track of gRPC connections.
  private class GrpcQueryTransportFilter extends ServerTransportFilter {
    @Override
    public Attributes transportReady(Attributes transportAttrs) {
      LOGGER.info("gRPC transportReady: REMOTE_ADDR {}",
          transportAttrs != null ? transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) : "null");
      _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_TRANSPORT_READY, 1);
      return super.transportReady(transportAttrs);
    }

    @Override
    public void transportTerminated(Attributes transportAttrs) {
      // transportTerminated can be called without transportReady before it, e.g. handshake fails
      // So, don't emit metrics if transportAttrs is null
      if (transportAttrs != null) {
        LOGGER.info("gRPC transportTerminated: REMOTE_ADDR {}", transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
        _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_TRANSPORT_TERMINATED, 1);
      }
    }
  }

  @Deprecated
  public GrpcQueryServer(int port, GrpcConfig config, TlsConfig tlsConfig,
      QueryExecutor queryExecutor, ServerMetrics serverMetrics, AccessControl accessControl) {
    this("unknown-server", port, config, tlsConfig, queryExecutor, serverMetrics, accessControl);
  }

  public GrpcQueryServer(String instanceId, int port, GrpcConfig config, TlsConfig tlsConfig,
        QueryExecutor queryExecutor, ServerMetrics serverMetrics, AccessControl accessControl) {
    _instanceId = instanceId;
    _executorService = QueryThreadContext.contextAwareExecutorService(
        Executors.newFixedThreadPool(config.isQueryWorkerThreadsSet()
            ? config.getQueryWorkerThreads()
            : DEFAULT_GRPC_QUERY_WORKER_THREAD)
    );
    _queryExecutor = queryExecutor;
    _serverMetrics = serverMetrics;

    _bufAllocator = new PooledByteBufAllocator(true);

    if (config.isRequestThrottlingMemroyThresholdSet()) {
      _memoryThresholdBytes = config.getRequestThrottlingMemoryThresholdBytes();
    } else {
      _memoryThresholdBytes = GrpcConfig.DEFAULT_REQUEST_THROTTLING_MEMORY_THRESHOLD_BYTES;
    }

    try {
      NettyServerBuilder builder = NettyServerBuilder.forPort(port);
      if (tlsConfig != null) {
        builder.sslContext(buildGrpcSslContext(tlsConfig));
      }

      // Add metrics for Netty buffer allocator
      PooledByteBufAllocatorMetric metric = _bufAllocator.metric();
      ServerMetrics metrics = ServerMetrics.get();
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_USED_DIRECT_MEMORY, metric::usedDirectMemory);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_USED_HEAP_MEMORY, metric::usedHeapMemory);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_ARENAS_DIRECT, metric::numDirectArenas);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_ARENAS_HEAP, metric::numHeapArenas);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_CACHE_SIZE_SMALL, metric::smallCacheSize);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_CACHE_SIZE_NORMAL, metric::normalCacheSize);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_THREADLOCALCACHE, metric::numThreadLocalCaches);
      metrics.setOrUpdateGlobalGauge(ServerGauge.GRPC_NETTY_POOLED_CHUNK_SIZE, metric::chunkSize);

      _server = builder
          .maxInboundMessageSize(config.getMaxInboundMessageSizeBytes())
          .addService(this)
          .addTransportFilter(new GrpcQueryTransportFilter())
          .withOption(ChannelOption.ALLOCATOR, _bufAllocator)
          .withChildOption(ChannelOption.ALLOCATOR, _bufAllocator)
          .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to start secure grpcQueryServer", e);
    }

    _accessControl = accessControl;
    LOGGER.info("Initialized GrpcQueryServer on port: {} with numWorkerThreads: {}", port,
        ResourceManager.DEFAULT_QUERY_WORKER_THREADS);
  }

  public static SslContext buildGrpcSslContext(TlsConfig tlsConfig)
      throws IllegalArgumentException {
    LOGGER.info("Building gRPC server SSL context");
    if (tlsConfig.getKeyStorePath() == null) {
      throw new IllegalArgumentException("Must provide key store path for secured gRPC server");
    }
    return SERVER_SSL_CONTEXTS_CACHE.computeIfAbsent(tlsConfig.hashCode(), tlsConfigHashCode -> {
      try {
        SSLFactory sslFactory =
            RenewableTlsUtils.createSSLFactoryAndEnableAutoRenewalWhenUsingFileStores(
                tlsConfig, PinotInsecureMode::isPinotInInsecureMode);
        SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(sslFactory.getKeyManagerFactory().get())
            .sslProvider(SslProvider.valueOf(tlsConfig.getSslProvider()));
        sslFactory.getTrustManagerFactory().ifPresent(sslContextBuilder::trustManager);
        if (tlsConfig.isClientAuthEnabled()) {
          sslContextBuilder.clientAuth(ClientAuth.REQUIRE);
        }
        return GrpcSslContexts.configure(sslContextBuilder).build();
      } catch (Exception e) {
        throw new RuntimeException("Failed to build gRPC server SSL context", e);
      }
    });
  }

  public void start() {
    LOGGER.info("Starting GrpcQueryServer");
    try {
      _server.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void shutdown() {
    LOGGER.info("Shutting down GrpcQueryServer");
    try {
      _server.shutdown().awaitTermination();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void submit(ServerRequest request, StreamObserver<ServerResponse> responseObserver) {
    long startTime = System.nanoTime();
    _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_QUERIES, 1);
    _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_BYTES_RECEIVED, request.getSerializedSize());

    // Check memory usage before processing the request
    long usedDirectMemory = _bufAllocator.metric().usedDirectMemory();
    if (usedDirectMemory > _memoryThresholdBytes) {
      LOGGER.warn("Request rejected due to memory pressure. Used direct memory: {} bytes, threshold: {} bytes",
          usedDirectMemory, _memoryThresholdBytes);
      _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_MEMORY_REJECTIONS, 1);
      responseObserver.onError(Status.RESOURCE_EXHAUSTED
          .withDescription(String.format("Server under memory pressure (used: %d bytes, threshold: %d bytes)",
              usedDirectMemory, _memoryThresholdBytes))
          .asException());
      return;
    }

    try (QueryThreadContext.CloseableContext closeme = QueryThreadContext.open(_instanceId)) {
      QueryThreadContext.setQueryEngine("sse-grpc");
      // Deserialize the request
      ServerQueryRequest queryRequest;
      try {
        queryRequest = new ServerQueryRequest(request, _serverMetrics);
      } catch (Exception e) {
        LOGGER.error("Caught exception while deserializing the request: {}", request, e);
        _serverMetrics.addMeteredGlobalValue(ServerMeter.REQUEST_DESERIALIZATION_EXCEPTIONS, 1);
        responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Bad request").withCause(e).asException());
        return;
      }
      queryRequest.registerOnQueryThreadLocal();

      // Table level access control
      GrpcRequesterIdentity requestIdentity = new GrpcRequesterIdentity(request.getMetadataMap());
      if (!_accessControl.hasDataAccess(requestIdentity, queryRequest.getTableNameWithType())) {
        Exception unsupportedOperationException = new UnsupportedOperationException(
            String.format("No access to table %s while processing request %d: %s from broker: %s",
                queryRequest.getTableNameWithType(), queryRequest.getRequestId(), queryRequest.getQueryContext(),
                queryRequest.getBrokerId()));
        final String exceptionMsg = String.format("Table not found: %s", queryRequest.getTableNameWithType());
        LOGGER.error(exceptionMsg, unsupportedOperationException);
        _serverMetrics.addMeteredGlobalValue(ServerMeter.NO_TABLE_ACCESS, 1);
        responseObserver.onError(
            Status.NOT_FOUND.withDescription(exceptionMsg).withCause(unsupportedOperationException).asException());
        return;
      }

      // Process the query
      InstanceResponseBlock instanceResponse;
      try {
        LOGGER.info("Executing gRPC query request {}: {} received from broker: {}", queryRequest.getRequestId(),
            queryRequest.getQueryContext(), queryRequest.getBrokerId());
        instanceResponse = _queryExecutor.execute(queryRequest, _executorService,
            new GrpcResultsBlockStreamer(responseObserver, _serverMetrics));
      } catch (Exception e) {
        LOGGER.error("Caught exception while processing request {}: {} from broker: {}", queryRequest.getRequestId(),
            queryRequest.getQueryContext(), queryRequest.getBrokerId(), e);
        _serverMetrics.addMeteredGlobalValue(ServerMeter.UNCAUGHT_EXCEPTIONS, 1);
        responseObserver.onError(Status.INTERNAL.withCause(e).asException());
        return;
      }

      ServerResponse serverResponse;
      try {
        DataTable dataTable = instanceResponse.toDataTable();
        serverResponse = queryRequest.isEnableStreaming() ? StreamingResponseUtils.getMetadataResponse(dataTable)
            : StreamingResponseUtils.getNonStreamingResponse(dataTable);
      } catch (Exception e) {
        LOGGER.error("Caught exception while serializing response for request {}: {} from broker: {}",
            queryRequest.getRequestId(), queryRequest.getQueryContext(), queryRequest.getBrokerId(), e);
        _serverMetrics.addMeteredGlobalValue(ServerMeter.RESPONSE_SERIALIZATION_EXCEPTIONS, 1);
        responseObserver.onError(Status.INTERNAL.withCause(e).asException());
        return;
      }
      responseObserver.onNext(serverResponse);
      _serverMetrics.addMeteredGlobalValue(ServerMeter.GRPC_BYTES_SENT, serverResponse.getSerializedSize());
      responseObserver.onCompleted();
      _serverMetrics.addTimedTableValue(queryRequest.getTableNameWithType(), ServerTimer.GRPC_QUERY_EXECUTION_MS,
          System.nanoTime() - startTime, TimeUnit.NANOSECONDS);

      // Log the query
      if (_queryLogger != null) {
        _queryLogger.logQuery(queryRequest, instanceResponse, "GrpcQueryServer");
      }
    }
  }
}
