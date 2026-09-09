# Spring Data Redis + Lettuce MultiDbClient Example

English | [한국어](README-ko.md)

For configuration, integration with existing services, and local/Cloud testing,
please refer to the [user guide (Korean)](GUIDE.md).

This example preserves the existing `Example.addLink()` and `ApplicationConfig`,
while separate configuration connects Lettuce `MultiDbClient` to Spring Data Redis's
`RedisTemplate<String, String>`.
`Example` also includes write and read methods for Value, Set, ZSet, and Hash operations.

## How it works

The customer's original `redisConnectionFactory` bean remains in place.
The additional `MultiDbRedisConfiguration` registers two beans:

1. `@Primary MultiDbRedisConnectionFactory`: adapts a `MultiDbClient` connection to
   Spring Data's `RedisConnectionFactory`.
2. A bean named exactly `redisTemplate`: explicitly uses the MultiDB factory above.

Both injection styles in the customer code therefore use the MultiDB connection.

```java
@Autowired
private RedisTemplate<String, String> redisTemplate;

@Resource(name = "redisTemplate")
private ListOperations<String, String> listOps;
```

The customer's original `redisConnectionFactory` is not used by this explicitly configured
`redisTemplate`.

## Key files

- `customer/Example.java`: original List calls and additional Value/Set/ZSet/Hash examples
- `customer/ApplicationConfig.java`: original customer configuration
- `config/MultiDbRedisConfiguration.java`: endpoint and `redisTemplate` wiring
- `config/MultiDbRedisConnectionFactory.java`: Lettuce MultiDB/Spring Data adapter
- `config/MultiDbRedisProperties.java`: external configuration binding
- `MultiDbCustomerCodeIntegrationTests.java`: real command verification of the original `addLink()`

## Endpoint configuration

Use `rediss://` URIs for TLS-enabled environments.

```shell
export REDIS_PRIMARY_URI='rediss://username:password@redis-seoul.example.com:12000'
export REDIS_SECONDARY_URI='rediss://username:password@redis-tokyo.example.com:12000'
export REDIS_MULTIDB_INITIALIZATION_POLICY='ONE_AVAILABLE'
```

Inject production credentials through your deployment platform's secret mechanism.
Do not put them in source code or YAML files.

With exactly two endpoints, `MAJORITY_AVAILABLE` requires both endpoints during initialization.
Use `ONE_AVAILABLE` if the application must start even when one endpoint is unavailable.

## Running and testing

Run the unit tests with:

```shell
mvn test
```

Run bean wiring and command integration tests against two independent local Redis instances:

```shell
docker compose up -d
RUN_REDIS_INTEGRATION_TESTS=true mvn test
docker compose down
```

The integration tests call the customer's unchanged `Example.addLink()` and verify that
two entries are stored in the list. They also verify Value overwrites, Set duplicate handling,
ZSet score updates and ordering, and Hash field updates.
Two independent Redis instances verify command routing and connectivity, but do not reproduce
Active-Active replication. Actual failover/failback testing requires Redis Enterprise
Active-Active endpoints belonging to the same logical database.

## Testing real Cloud endpoints

Cloud tests do not run with the default `mvn test` command. Run them only against a separately
approved test database.
The Cloud fault-injection scenario uses the existing `addLink()` path and does not modify
the production adapter. The additional Value/Set/ZSet/Hash calls are covered by local integration
tests, not by the Cloud fault-injection scenario.

```bash
export AA_PRIMARY_HOST='your-first-db-endpoint'
export AA_SECONDARY_HOST='your-second-db-endpoint'
read -r -s -p 'Redis password: ' AA_REDIS_PASSWORD
export AA_REDIS_PASSWORD
# Set false only when plaintext testing is explicitly approved for a TLS-disabled DB.
export AA_TLS=false
RUN_CLOUD_PREFLIGHT=true mvn -Dtest=CloudReplicationPreflightTests test
# Run only after the bidirectional replication check succeeds. This test also checks replication first.
RUN_CLOUD_FAILOVER=true mvn -Dtest=CloudFailoverTests test
unset AA_REDIS_PASSWORD
```

These tests use port 11164. `CloudReplicationPreflightTests` uses TLS by default and permits
plaintext connections only when `AA_TLS=false` is explicitly set. The local proxy used by
`CloudFailoverTests` is intended only for TLS-disabled databases.
No option is provided to disable certificate validation.

The first test performs three SETEX/GET samples in each direction and records write-start,
write-ACK, and first successful read-start/completion timestamps in a UTC CSV file.
The first GET runs immediately after the ACK; if replication has not converged, it polls again
after 250 ms. Measurements include network round trips and polling intervals.
The subsequent test verifies the original `Example.addLink()` and automatic endpoint switching.
The local proxy binds only to loopback and blocks only this test's connection to endpoint A.
It does not modify server configuration or the Active-Active replication network.
A single fault-injection run does not establish all possible response-loss or duplication outcomes;
it records only the timed-out key's outcome for that run.

Tests use the unique key prefix `codex:multidb:test:<UUID>:` and apply a TTL of up to 900 seconds.
SETEX atomically writes the value and sets its TTL. The customer's LPUSH calls remain unchanged,
so their TTL is applied separately afterward.
If a customer call fails or the process is forcibly terminated, keys may remain before TTL assignment.
Check the exact-key cleanup in finally and the cleanup results in the report.
CSV results from successful and failed runs are retained separately as `target/cloud-*.csv`.

## Supported scope and limitations

- Supported: imperative `RedisTemplate`, String serialization, and ordinary Value/List/Set/ZSet/Hash commands
- Assumption: Lettuce treats each Redis Enterprise endpoint as a standalone server endpoint.
- Not used: Redis Cluster/Sentinel APIs and application-side pipelining
- Rejected immediately with an exception: Spring pipelines, Spring Redis transactions (`MULTI/EXEC`),
  blocking commands requiring a dedicated connection, and Pub/Sub
- Not provided: `ReactiveRedisTemplate`/`ReactiveRedisConnectionFactory`
- Adapter limitation: Spring Data Redis 4.1 does not provide a dedicated MultiDB ConnectionFactory,
  so this code is a compatibility adapter. Regression tests are required when upgrading Spring Data/Lettuce.
- Data consistency: MultiDbClient selects a healthy endpoint; it does not replicate data between endpoints.
  Replication, conflict resolution, and convergence are the responsibility of the Redis Enterprise
  Active-Active backend.
- Preview caution: while the Lettuce MultiDB API remains in Preview, its API and behavior may change.
  Pin the version and include failover testing in your release checks.
