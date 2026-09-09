package com.example.multidb;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Must pass before the separately controlled fault-injection phase is allowed. */
@EnabledIfEnvironmentVariable(named = "RUN_CLOUD_PREFLIGHT", matches = "true")
class CloudReplicationPreflightTests {
    private final String prefix = "codex:multidb:test:" + UUID.randomUUID() + ":";
    private final List<String> keys = new ArrayList<>();
    private final List<String> rows = new ArrayList<>(List.of(
            "direction,write_start_utc,write_ack_utc,first_remote_read_start_utc,first_remote_read_complete_utc,ack_to_observation_ms,polls,status"));

    @Test
    void bidirectionalReplicationMustPassBeforeAnyFaultInjection() throws Exception {
        RedisClient client = RedisClient.create();
        try (StatefulRedisConnection<String, String> a = client.connect(uri("AA_PRIMARY_HOST"));
             StatefulRedisConnection<String, String> b = client.connect(uri("AA_SECONDARY_HOST"))) {
            assertEquals("PONG", a.sync().ping());
            assertEquals("PONG", b.sync().ping());
            try {
                for (int sample = 1; sample <= 3; sample++) {
                    measure(a, b, "A-to-B-" + sample);
                    Thread.sleep(500);
                    measure(b, a, "B-to-A-" + sample);
                    Thread.sleep(500);
                }
            } finally {
                // Only this run's exact keys; never scan or flush the shared database.
                for (String key : keys) {
                    try { a.sync().del(key); }
                    catch (RuntimeException e) { rows.add("# cleanup A failed; TTL remains: " + e.getClass().getSimpleName()); }
                    try { b.sync().del(key); }
                    catch (RuntimeException e) { rows.add("# cleanup B failed; TTL remains: " + e.getClass().getSimpleName()); }
                }
            }
        } finally {
            client.shutdown();
            Path output = Path.of("target", "cloud-preflight-" + prefix.split(":")[3] + ".csv");
            Files.createDirectories(output.getParent());
            Files.write(output, rows);
            rows.forEach(System.out::println);
            System.out.println("Evidence: " + output);
        }
    }

    private void measure(StatefulRedisConnection<String, String> source,
                         StatefulRedisConnection<String, String> target, String direction) throws Exception {
        String key = prefix + direction;
        keys.add(key);
        String value = UUID.randomUUID().toString();
        Instant writeStart = Instant.now();
        assertEquals("OK", source.sync().setex(key, 900, value));
        Instant writeAck = Instant.now();
        long start = System.nanoTime();
        int polls = 0;
        do {
            Instant readStart = Instant.now();
            String observed = target.sync().get(key);
            Instant readComplete = Instant.now();
            polls++;
            if (value.equals(observed)) {
                rows.add(direction + "," + writeStart + "," + writeAck + "," + readStart + ","
                        + readComplete + "," + (System.nanoTime() - start) / 1_000_000.0 + "," + polls + ",PASS");
                return;
            }
            // First GET is immediate after ACK; subsequent polls are bounded.
            Thread.sleep(250);
        } while (System.nanoTime() - start < Duration.ofSeconds(60).toNanos());
        rows.add(direction + "," + writeStart + "," + writeAck + ",,,," + polls + ",TIMEOUT");
        assertTrue(false, direction + " did not converge within observation window");
    }

    private static RedisURI uri(String hostVariable) {
        String host = required(hostVariable);
        String password = required("AA_REDIS_PASSWORD");
        return RedisURI.Builder.redis(host, 11164)
                // Plaintext requires explicit user-authorized AA_TLS=false.
                .withSsl(!"false".equals(System.getenv("AA_TLS"))).withVerifyPeer(true)
                .withAuthentication("default", password)
                .withTimeout(Duration.ofSeconds(3)).build();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing environment variable: " + name);
        return value;
    }
}
