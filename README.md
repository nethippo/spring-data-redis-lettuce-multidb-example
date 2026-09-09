# Spring Data Redis + Lettuce MultiDbClient 예제

설정, 기존 서비스 적용, 로컬/Cloud 테스트 방법은 [사용자 가이드](GUIDE.md)를 참고하십시오.

기존 `Example.addLink()`와 `ApplicationConfig`를 유지하면서, 별도 설정으로 Lettuce `MultiDbClient`를
Spring Data Redis의 `RedisTemplate<String, String>` 뒤에 연결한 예제입니다.
`Example`에는 Value, Set, ZSet, Hash의 쓰기·조회 메서드도 포함되어 있습니다.

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

- `customer/Example.java`: 기존 List 호출 및 Value/Set/ZSet/Hash 추가 예제
- `customer/ApplicationConfig.java`: 고객 설정 원본
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
검증합니다. Value의 덮어쓰기, Set의 중복 처리, ZSet의 점수 갱신·정렬, Hash의 필드 갱신도 검증합니다.
독립 Redis 두 대는 명령 라우팅과 연결만 검증하며 Active-Active 데이터 복제를
재현하지 않습니다. 실제 failover/failback 검증에는 같은 논리 데이터베이스에 속한 Redis
Enterprise Active-Active endpoint를 사용해야 합니다.

## 실제 Cloud endpoint 검증

Cloud 테스트는 기본 `mvn test`에서 실행하지 않는다. 별도 승인된 테스트 DB에만 사용한다.
Cloud 차단 시나리오는 기존 `addLink()` 경로를 사용하며 운영 어댑터를 변경하지 않는다.
추가된 Value/Set/ZSet/Hash 호출은 로컬 통합 테스트에 포함되며 Cloud 차단 시나리오의 검증 대상은 아니다.

```bash
export AA_PRIMARY_HOST='your-first-db-endpoint'
export AA_SECONDARY_HOST='your-second-db-endpoint'
read -r -s -p 'Redis password: ' AA_REDIS_PASSWORD
export AA_REDIS_PASSWORD
# TLS-off DB에 대해 평문 테스트가 명시적으로 승인된 경우에만 false를 설정한다.
export AA_TLS=false
RUN_CLOUD_PREFLIGHT=true mvn -Dtest=CloudReplicationPreflightTests test
# 앞선 양방향 복제 검사가 성공한 뒤 실행한다. 내부에도 복제 선행 검사가 있다.
RUN_CLOUD_FAILOVER=true mvn -Dtest=CloudFailoverTests test
unset AA_REDIS_PASSWORD
```

포트는 이 테스트에서 11164를 사용한다. `CloudReplicationPreflightTests`는 TLS를 기본으로
사용하며 `AA_TLS=false`를 명시해야 평문 연결을 허용한다. `CloudFailoverTests`의 로컬 프록시는
TLS-off DB 전용이다. 인증서 검증을 해제하는 옵션은 추가하지 않았다.

첫 테스트는 방향별 3회 SETEX/GET으로 쓰기 시작·ACK와 최초 읽기 시작·완료 시각을 UTC CSV로 남긴다.
최초 GET은 ACK 직후 실행하고 미수렴 시 250ms 후 재조회한다. 측정값에는 네트워크 왕복과 조회 간격이
포함된다. 이어지는 테스트는 원본 `Example.addLink()`와 실제 자동 endpoint 전환을 검증한다.
로컬 프록시는 loopback에만 바인딩하고 해당 테스트의 A 연결만 차단한다. 서버 설정·AA 복제망은 변경하지 않는다.
단일 차단 시험은 응답 유실·중복 가능성 전체를 증명하지 않으며 그 실행의 타임아웃 키 결과만 기록한다.

고유 키 접두사 `codex:multidb:test:<UUID>:`를 사용하고 최대 900초 TTL을 적용한다.
SETEX는 쓰기와 TTL이 원자적이다. 고객 코드의 LPUSH는 그대로 두므로 TTL은 이후 별도 적용한다.
고객 호출 실패·프로세스 강제 종료 시 TTL 적용 전의 키가 남을 수 있으므로 finally의 정확한 키 정리와
보고서의 cleanup 결과를 확인해야 한다. 성공/실패 실행의 CSV는 `target/cloud-*.csv`에 각각 보존한다.

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
