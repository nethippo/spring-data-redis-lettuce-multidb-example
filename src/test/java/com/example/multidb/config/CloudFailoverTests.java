package com.example.multidb.config;

import com.example.multidb.MultiDbExampleApplication;
import com.example.multidb.customer.Example;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.failover.MultiDbClient;
import io.lettuce.core.failover.api.DatabaseConfig;
import io.lettuce.core.failover.api.InitializationPolicy;
import io.lettuce.core.failover.api.MultiDbOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Dedicated plaintext test proxies, only for databases explicitly configured with TLS off. */
@EnabledIfEnvironmentVariable(named = "RUN_CLOUD_FAILOVER", matches = "true")
class CloudFailoverTests {
    private final String runId = UUID.randomUUID().toString();
    private final List<String> evidence = new ArrayList<>(List.of("utc,event,details"));
    private final List<String> keys = new ArrayList<>();
    private final String value = "https://redis.io/?run=" + runId;

    @Test
    void customerCodeAndAutomaticFailover() throws Exception {
        assertEquals("false", System.getenv("AA_TLS"), "This proxy suite requires explicitly authorized TLS-off endpoints");
        String password = required("AA_REDIS_PASSWORD");
        String hostA = required("AA_PRIMARY_HOST"), hostB = required("AA_SECONDARY_HOST");
        RedisClient direct = RedisClient.create();
        try (var a = direct.connect(uri(hostA, 11164, password));
             var b = direct.connect(uri(hostB, 11164, password));
             var proxyA = new Gate(hostA); var proxyB = new Gate(hostB)) {
            try {
                // Mandatory same-run replication gate: both directions before any connection is cut.
                replicationGate(a, b, "A-to-B");
                replicationGate(b, a, "B-to-A");
                try (var pa = direct.connect(uri("127.0.0.1", proxyA.port(), password));
                     var pb = direct.connect(uri("127.0.0.1", proxyB.port(), password))) {
                    assertEquals("PONG", pa.sync().ping());
                    assertEquals("PONG", pb.sync().ping());
                    event("PROXIES_READY", "A=PONG; B=PONG");
                }
                Map<String, Object> properties = new HashMap<>();
                // Indexed-list overrides replace the entire list; include names and weights.
                properties.put("example.redis.multidb.endpoints[0].name", "primary");
                properties.put("example.redis.multidb.endpoints[0].weight", "1.0");
                properties.put("example.redis.multidb.endpoints[1].name", "secondary");
                properties.put("example.redis.multidb.endpoints[1].weight", "0.5");
                // RedisURI.toURI() renders credentialsProvider as a placeholder, not credentials.
                properties.put("example.redis.multidb.endpoints[0].uri", configurationUri(proxyA.port(), password));
                properties.put("example.redis.multidb.endpoints[1].uri", configurationUri(proxyB.port(), password));
                properties.put("example.redis.multidb.initialization-policy", "MAJORITY_AVAILABLE");
                properties.put("logging.level.root", "OFF");
                properties.put("spring.main.banner-mode", "off");
                try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MultiDbExampleApplication.class)
                        .properties("logging.level.root=OFF", "spring.main.banner-mode=off")
                        .initializers(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cloud-test", properties)))
                        .run()) {
                    var factory = context.getBean(MultiDbRedisConnectionFactory.class);
                    Example customer = context.getBean(Example.class);
                    @SuppressWarnings("unchecked") RedisTemplate<String, String> template = (RedisTemplate<String, String>) context.getBean("redisTemplate");
                    assertSame(factory, template.getConnectionFactory());
                    assertNotSame(context.getBean("redisConnectionFactory"), factory);
                    waitRoute(factory, proxyA.port(), 30);
                    event("STARTED", "route=A; initialization=MAJORITY_AVAILABLE; timeout=2s; failbackCheck=30s; grace=60s");
                    customerWrite(customer, a, b, "normal-A");

                    long cut = System.nanoTime();
                    event("CUT_A", "closing only this test proxy's sockets");
                    proxyA.block();
                    String interrupted = key("during-cut");
                    try {
                        customer.addLink(interrupted, URI.create(value).toURL());
                        event("DURING_CUT_CALL", "returned-success");
                    } catch (RuntimeException e) {
                        event("DURING_CUT_CALL", "exception=" + e.getClass().getSimpleName() + "; elapsed_ms=" + millis(cut));
                    }
                    waitRoute(factory, proxyB.port(), 120);
                    event("FAILOVER_B", "elapsed_ms=" + millis(cut));
                    customerWrite(customer, a, b, "after-failover-B");
                    recordCounts(a, b, interrupted, "during-cut-final-before-recovery");

                    long restore = System.nanoTime();
                    proxyA.unblock();
                    event("RESTORE_A", "proxy re-enabled");
                    waitRoute(factory, proxyA.port(), 150);
                    event("FAILBACK_A", "elapsed_ms=" + millis(restore));
                    customerWrite(customer, a, b, "after-failback-A");
                    recordCounts(a, b, interrupted, "during-cut-final-after-recovery");
                }
                proxyA.block();
                initialization(proxyA.port(), proxyB.port(), password, InitializationPolicy.BuiltIn.ONE_AVAILABLE, true);
                initialization(proxyA.port(), proxyB.port(), password, InitializationPolicy.BuiltIn.MAJORITY_AVAILABLE, false);
                proxyA.unblock();
            } finally {
                proxyA.unblock();
                boolean clean = true;
                for (String key : keys) {
                    try { a.sync().del(key); b.sync().del(key); }
                    catch (RuntimeException e) { clean = false; event("CLEANUP_ERROR", e.getClass().getSimpleName()); }
                }
                // Let asynchronous AA deletes converge, then verify exact keys only.
                Thread.sleep(1000);
                for (String key : keys) {
                    if (a.sync().exists(key) != 0 || b.sync().exists(key) != 0) clean = false;
                }
                event("CLEANUP", "all_absent=" + clean + "; unique_keys=" + keys.size());
                assertTrue(clean, "Test key cleanup must succeed");
            }
        } finally {
            direct.shutdown();
            Path output = Path.of("target", "cloud-failover-" + runId + ".csv");
            Files.createDirectories(output.getParent());
            Files.write(output, evidence);
            System.out.println("Evidence: " + output);
        }
    }

    private void replicationGate(StatefulRedisConnection<String, String> source,
                                 StatefulRedisConnection<String, String> target, String direction) throws Exception {
        String key = key("gate-" + direction);
        Instant start = Instant.now();
        source.sync().setex(key, 900, value);
        Instant ack = Instant.now();
        long clock = System.nanoTime();
        for (int i = 0; i < 240; i++) {
            String found = target.sync().get(key);
            if (value.equals(found)) {
                event("REPLICATION_GATE", "direction=" + direction + "; write_start=" + start + "; write_ack=" + ack + "; remote_read=" + Instant.now() + "; ack_to_read_ms=" + millis(clock));
                return;
            }
            if (millis(clock) >= 60000) break;
            Thread.sleep(250);
        }
        fail("Replication gate failed; no fault injection allowed");
    }

    private void customerWrite(Example customer, StatefulRedisConnection<String, String> a,
                               StatefulRedisConnection<String, String> b, String stage) throws Exception {
        String key = key(stage);
        event("CUSTOMER_START", stage);
        customer.addLink(key, URI.create(value).toURL());
        event("CUSTOMER_ACK", stage);
        long start = System.nanoTime();
        List<String> left = List.of(), right = List.of();
        do {
            Thread.sleep(1000);
            a.sync().expire(key, 900); b.sync().expire(key, 900);
            left = a.sync().lrange(key, 0, 10); right = b.sync().lrange(key, 0, 10);
            if (left.equals(List.of(value, value)) && right.equals(left)) break;
        } while (millis(start) < 60000);
        event("CUSTOMER_RESULT", stage + "; count_A=" + left.size() + "; count_B=" + right.size());
        assertEquals(List.of(value, value), left, stage + " A");
        assertEquals(left, right, stage + " B");
    }

    private void recordCounts(StatefulRedisConnection<String, String> a, StatefulRedisConnection<String, String> b,
                              String key, String label) throws Exception {
        Thread.sleep(1000);
        a.sync().expire(key, 900); b.sync().expire(key, 900);
        event("AMBIGUOUS_WRITE_COUNTS", label + "; count_A=" + a.sync().llen(key) + "; count_B=" + b.sync().llen(key));
    }

    private void initialization(int portA, int portB, String password, InitializationPolicy policy, boolean expected) {
        var configs = List.of(DatabaseConfig.builder(uri("127.0.0.1", portA, password)).weight(1f).build(),
                DatabaseConfig.builder(uri("127.0.0.1", portB, password)).weight(.5f).build());
        var options = MultiDbOptions.builder().initializationPolicy(policy).build();
        MultiDbClient client = MultiDbClient.create(configs, options);
        boolean success = false;
        long start = System.nanoTime();
        try (var connection = client.connect()) {
            assertEquals("PONG", connection.sync().ping());
            assertEquals(portB, connection.getCurrentEndpoint().getPort());
            success = true;
        } catch (RuntimeException e) {
            event("INITIALIZATION_EXCEPTION", "policy=" + policy + "; exception=" + e.getClass().getSimpleName());
        } finally { client.shutdown(); }
        event("INITIALIZATION_RESULT", "policy=" + policy + "; success=" + success + "; elapsed_ms=" + millis(start));
        assertEquals(expected, success);
    }

    private void waitRoute(MultiDbRedisConnectionFactory factory, int port, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (factory.getSharedConnection().getCurrentEndpoint().getPort() == port) return;
            Thread.sleep(250);
        }
        fail("Automatic endpoint transition did not occur within " + seconds + " seconds");
    }
    private String key(String label) { String key = "codex:multidb:test:" + runId + ":" + label; keys.add(key); return key; }
    private void event(String type, String detail) { String row = Instant.now() + "," + type + "," + detail.replace(',', ';'); evidence.add(row); System.out.println(row); }
    private static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), "Missing " + name); }
    private static RedisURI uri(String host, int port, String password) { return RedisURI.Builder.redis(host, port).withAuthentication("default", password).withTimeout(Duration.ofSeconds(3)).build(); }
    private static String configurationUri(int port, String password) throws URISyntaxException {
        return new URI("redis", "default:" + password, "127.0.0.1", port, null, "timeout=3s", null).toASCIIString();
    }

    private static final class Gate implements AutoCloseable {
        final ServerSocket listener;
        final String host;
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        volatile boolean blocked;
        volatile boolean closed;
        Gate(String host) throws IOException {
            this.host = host;
            listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread.startVirtualThread(() -> {
                while (!closed) {
                    try {
                        Socket local = listener.accept(); sockets.add(local);
                        Thread.startVirtualThread(() -> bridge(local));
                    } catch (IOException e) { if (!closed) close(); }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        void bridge(Socket local) {
            Socket remote = new Socket(); sockets.add(remote);
            try {
                if (blocked) return;
                remote.connect(new InetSocketAddress(host, 11164), 2000);
                local.setTcpNoDelay(true); remote.setTcpNoDelay(true);
                if (blocked) return;
                Thread.startVirtualThread(() -> {
                    try { remote.getInputStream().transferTo(local.getOutputStream()); }
                    catch (IOException ignored) { }
                    finally { end(local); end(remote); }
                });
                local.getInputStream().transferTo(remote.getOutputStream());
            } catch (IOException ignored) { }
            finally { end(local); end(remote); }
        }
        void end(Socket socket) { try { socket.close(); } catch (IOException ignored) { } sockets.remove(socket); }
        void block() { blocked = true; for (Socket socket : sockets) end(socket); }
        void unblock() { blocked = false; }
        public void close() { closed = true; block(); try { listener.close(); } catch (IOException ignored) { } }
    }
}
