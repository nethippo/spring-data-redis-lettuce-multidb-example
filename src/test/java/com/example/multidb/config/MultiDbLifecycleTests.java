package com.example.multidb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.failover.MultiDbClient;
import io.lettuce.core.failover.api.StatefulRedisMultiDbConnection;
import org.junit.jupiter.api.Test;

class MultiDbLifecycleTests {

    private final Duration timeout = Duration.ofSeconds(2);
    private final MultiDbClient client = mock(MultiDbClient.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisMultiDbConnection<byte[], byte[]> connection =
            mock(StatefulRedisMultiDbConnection.class);
    private final MultiDbRedisConnectionFactory factory =
            new MultiDbRedisConnectionFactory(() -> client, timeout);

    @Test
    void connectFailureShutsDownClientAndRetainsOriginalException() {
        RuntimeException failure = new IllegalStateException("connect failed");
        RuntimeException cleanupFailure = new IllegalStateException("shutdown failed");
        when(client.connect(ByteArrayCodec.INSTANCE)).thenThrow(failure);
        doThrow(cleanupFailure).when(client).shutdown();

        assertThatThrownBy(factory::start).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanupFailure);
        verify(client).shutdown();
        assertStopped();
        factory.destroy();
        verify(client, times(1)).shutdown();
    }

    @Test
    void timeoutSetupFailureClosesBothResourcesAndRetainsCleanupFailures() {
        RuntimeException failure = new IllegalStateException("timeout setup failed");
        RuntimeException closeFailure = new IllegalStateException("close failed");
        RuntimeException shutdownFailure = new IllegalStateException("shutdown failed");
        when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
        doThrow(failure).when(connection).setTimeout(timeout);
        doThrow(closeFailure).when(connection).close();
        doThrow(shutdownFailure).when(client).shutdown();

        assertThatThrownBy(factory::start).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(closeFailure, shutdownFailure);
        verify(connection).close();
        verify(client).shutdown();
        assertStopped();
    }

    @Test
    void closeFailureDoesNotPreventShutdownAndDestroyDoesNotRepeatCleanup() {
        startSuccessfully();
        RuntimeException closeFailure = new IllegalStateException("close failed");
        RuntimeException shutdownFailure = new IllegalStateException("shutdown failed");
        doThrow(closeFailure).when(connection).close();
        doThrow(shutdownFailure).when(client).shutdown();

        assertThatThrownBy(factory::stop).isSameAs(closeFailure);
        assertThat(closeFailure.getSuppressed()).containsExactly(shutdownFailure);
        assertStopped();
        factory.destroy();
        verify(connection, times(1)).close();
        verify(client, times(1)).shutdown();
    }

    @Test
    void shutdownFailureIsPropagatedAfterConnectionIsClosed() {
        startSuccessfully();
        RuntimeException failure = new IllegalStateException("shutdown failed");
        doThrow(failure).when(client).shutdown();

        assertThatThrownBy(factory::stop).isSameAs(failure);
        var order = inOrder(connection, client);
        order.verify(connection).close();
        order.verify(client).shutdown();
        assertStopped();
    }

    @Test
    void successfulStartAndStopAreIdempotent() {
        startSuccessfully();
        factory.start();
        assertThat(factory.isRunning()).isTrue();
        assertThat(factory.getSharedConnection()).isSameAs(connection);
        factory.stop();
        factory.stop();
        factory.destroy();

        verify(client, times(1)).connect(ByteArrayCodec.INSTANCE);
        verify(connection, times(1)).setTimeout(timeout);
        verify(connection, times(1)).close();
        verify(client, times(1)).shutdown();
        assertStopped();
    }

    @Test
    void failedStartCanBeRetriedWithFreshResources() {
        MultiDbClient replacement = mock(MultiDbClient.class);
        var clients = java.util.List.of(client, replacement).iterator();
        MultiDbRedisConnectionFactory retryable =
                new MultiDbRedisConnectionFactory(clients::next, timeout);
        when(client.connect(ByteArrayCodec.INSTANCE)).thenThrow(new IllegalStateException("connect failed"));
        when(replacement.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);

        assertThatThrownBy(retryable::start).isInstanceOf(IllegalStateException.class);
        retryable.start();
        assertThat(retryable.isRunning()).isTrue();
        retryable.destroy();
        verify(client).shutdown();
        verify(replacement).shutdown();
        verify(connection).close();
    }

    @Test
    void errorsAlsoTriggerCleanupWithoutBeingMasked() {
        Error failure = new AssertionError("setup error");
        when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
        doThrow(failure).when(connection).setTimeout(timeout);
        doThrow(failure).when(connection).close();

        assertThatThrownBy(factory::start).isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
        verify(client).shutdown();
        assertStopped();
    }

    private void startSuccessfully() {
        when(client.connect(ByteArrayCodec.INSTANCE)).thenReturn(connection);
        factory.start();
    }

    private void assertStopped() {
        assertThat(factory.isRunning()).isFalse();
        assertThatThrownBy(factory::getConnection).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory::getSharedConnection).isInstanceOf(IllegalStateException.class);
    }
}
