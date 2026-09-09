# Spring Data Redis + Lettuce MultiDbClient 예제

고객의 `Example`과 `ApplicationConfig`는 변경하지 않고, 별도 설정으로 Lettuce `MultiDbClient`를
Spring Data Redis의 `RedisTemplate<String, String>` 뒤에 연결한 예제입니다.

## 적용 방식

고객 코드의 `redisConnectionFactory` Bean은 그대로 남습니다. 추가 설정인
`MultiDbRedisConfiguration`이 다음 두 Bean을 등록합니다.

1. `@Primary MultiDbRedisConnectionFactory`: `MultiDbClient` 연결을 Spring Data의
   `RedisConnectionFactory`로 변환합니다.
2. 이름이 정확히 `redisTemplate`인 Bean: 위 MultiDB factory를 명시적으로 사용합니다.

따라서 고객 코드의 두 주입 방식 모두 MultiDB 연결을 사용합니다.

```java
@Autowired
private RedisTemplate<String, String> redisTemplate;

@Resource(name = "redisTemplate")
private ListOperations<String, String> listOps;
```

고객이 정의한 기존 `redisConnectionFactory`는 이 명시적 `redisTemplate`에서는 사용되지 않습니다.

## 주요 파일

- `customer/Example.java`, `customer/ApplicationConfig.java`: 고객 코드 원본
- `config/MultiDbRedisConfiguration.java`: endpoint와 `redisTemplate` 조립
- `config/MultiDbRedisConnectionFactory.java`: Lettuce MultiDB/Spring Data 어댑터
- `config/MultiDbRedisProperties.java`: 외부 설정 바인딩
- `MultiDbCustomerCodeIntegrationTests.java`: 원본 `addLink()` 실제 명령 검증

## endpoint 설정

TLS 환경에서는 `rediss://` URI를 사용합니다.

```shell
export REDIS_PRIMARY_URI='rediss://username:password@redis-seoul.example.com:12000'
export REDIS_SECONDARY_URI='rediss://username:password@redis-tokyo.example.com:12000'
export REDIS_MULTIDB_INITIALIZATION_POLICY='ONE_AVAILABLE'
```

운영 환경의 자격 증명은 소스나 YAML에 넣지 말고 배포 플랫폼의 Secret으로 주입해야 합니다.

endpoint가 정확히 2개일 때 `MAJORITY_AVAILABLE` 초기화에는 2개 모두 필요합니다. 한 endpoint가
중단된 상태에서도 애플리케이션이 시작되어야 한다면 `ONE_AVAILABLE`을 사용합니다.

## 실행 및 검증

일반 단위 테스트:

```shell
mvn test
```

로컬의 독립 Redis 두 대를 이용한 wiring/명령 통합 테스트:

```shell
docker compose up -d
RUN_REDIS_INTEGRATION_TESTS=true mvn test
docker compose down
```

이 통합 테스트는 고객의 `Example.addLink()`를 그대로 호출하고, 리스트에 두 항목이 저장되었는지
검증합니다. 독립 Redis 두 대는 명령 라우팅과 연결만 검증하며 Active-Active 데이터 복제를
재현하지 않습니다. 실제 failover/failback 검증에는 같은 논리 데이터베이스에 속한 Redis
Enterprise Active-Active endpoint를 사용해야 합니다.

## 지원 범위와 제약

- 지원: imperative `RedisTemplate`, String 직렬화, 일반 Value/List/Set/ZSet/Hash 명령
- 전제: 각 Redis Enterprise endpoint는 Lettuce 관점에서 standalone endpoint
- 사용하지 않음: Redis Cluster/Sentinel API와 애플리케이션 측 pipeline
- fail-fast 차단: Spring pipeline, Spring Redis transaction (`MULTI/EXEC`), dedicated connection이
  필요한 blocking 명령, Pub/Sub
- 미제공: `ReactiveRedisTemplate`/`ReactiveRedisConnectionFactory`
- 한계: Spring Data Redis 4.1에는 MultiDB 전용 ConnectionFactory가 없으므로 이 코드는
  호환 어댑터입니다. Spring Data/Lettuce 업그레이드 시 회귀 테스트가 필요합니다.
- 데이터 일관성: MultiDbClient는 healthy endpoint를 선택할 뿐, endpoint 간 데이터를 복제하지
  않습니다. 복제·충돌 처리·수렴은 Redis Enterprise Active-Active backend의 책임입니다.
- Preview 주의: Lettuce MultiDB API가 Preview인 동안 API/동작이 바뀔 수 있으므로 사용 버전을
  고정하고 장애 전환 테스트를 배포 게이트에 포함하는 편이 안전합니다.
