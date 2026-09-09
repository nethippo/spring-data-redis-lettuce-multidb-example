package com.example.multidb.config;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.failover.MultiDbClient;
import io.lettuce.core.failover.api.DatabaseConfig;
import io.lettuce.core.failover.api.MultiDbOptions;
import io.lettuce.core.failover.api.StatefulRedisMultiDbConnection;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;
import org.springframework.data.redis.connection.lettuce.LettuceExceptionConverter;

public final class MultiDbRedisConnectionFactory
        implements RedisConnectionFactory, SmartLifecycle, DisposableBean {

    private final Collection<DatabaseConfig> databases;
    private final MultiDbOptions options;
    private final Duration commandTimeout;
    private final AtomicBoolean running = new AtomicBoolean();
    private final LettuceExceptionConverter exceptionConverter = new LettuceExceptionConverter();
    private final LettuceConnectionProvider unsupportedDedicatedConnectionProvider =
            new UnsupportedDedicatedConnectionProvider();

    private volatile MultiDbClient client;
    private volatile StatefulRedisMultiDbConnection<byte[], byte[]> sharedConnection;

    public MultiDbRedisConnectionFactory(Collection<DatabaseConfig> databases,
            MultiDbOptions options, Duration commandTimeout) {
        this.databases = List.copyOf(databases);
        this.options = options;
        this.commandTimeout = commandTimeout;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        MultiDbClient newClient = MultiDbClient.create(databases, options);
        StatefulRedisMultiDbConnection<byte[], byte[]> newConnection =
                newClient.connect(ByteArrayCodec.INSTANCE);

        newConnection.setTimeout(commandTimeout);
        this.client = newClient;
        this.sharedConnection = newConnection;
        running.set(true);
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        StatefulRedisMultiDbConnection<byte[], byte[]> connection = this.sharedConnection;
        MultiDbClient multiDbClient = this.client;
        this.sharedConnection = null;
        this.client = null;

        if (connection != null) {
            connection.close();
        }
        if (multiDbClient != null) {
            multiDbClient.shutdown();
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public RedisConnection getConnection() {
        StatefulRedisMultiDbConnection<byte[], byte[]> connection = this.sharedConnection;
        if (!running.get() || connection == null) {
            throw new IllegalStateException("MultiDbRedisConnectionFactory is not running");
        }

        LettuceConnection lettuceConnection = new MultiDbLettuceConnection(
                connection,
                unsupportedDedicatedConnectionProvider,
                commandTimeout.toMillis());
        lettuceConnection.setConvertPipelineAndTxResults(true);
        return lettuceConnection;
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        throw new UnsupportedOperationException("Redis Cluster API is not supported by MultiDB mode");
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        throw new UnsupportedOperationException("Redis Sentinel API is not supported by MultiDB mode");
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return true;
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException exception) {
        return exceptionConverter.convert(exception);
    }

    StatefulRedisMultiDbConnection<byte[], byte[]> getSharedConnection() {
        StatefulRedisMultiDbConnection<byte[], byte[]> connection = sharedConnection;
        if (connection == null) {
            throw new IllegalStateException("MultiDbRedisConnectionFactory is not running");
        }
        return connection;
    }

    private static final class MultiDbLettuceConnection extends LettuceConnection {

        private MultiDbLettuceConnection(StatefulRedisMultiDbConnection<byte[], byte[]> sharedConnection,
                LettuceConnectionProvider connectionProvider, long timeout) {
            super(sharedConnection, connectionProvider, timeout, 0);
        }

        @Override
        public void openPipeline() {
            throw new UnsupportedOperationException("Pipeline is intentionally disabled in MultiDB mode");
        }

        @Override
        public void multi() {
            throw new UnsupportedOperationException("Spring Redis transactions are disabled in MultiDB mode");
        }
    }

    private static final class UnsupportedDedicatedConnectionProvider implements LettuceConnectionProvider {

        @Override
        public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(Class<T> connectionType) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Dedicated, blocking, transactional, pipeline, and Pub/Sub connections "
                            + "are intentionally disabled in this MultiDB example"));
        }
    }

}
