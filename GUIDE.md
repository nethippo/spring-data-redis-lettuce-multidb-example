# Spring Data Redis + Lettuce MultiDbClient 사용자 가이드

이 프로젝트는 기존 `RedisTemplate` 사용 코드에 Lettuce MultiDbClient의 엔드포인트 선택과
자동 장애 전환·복귀 기능을 연결하는 예제입니다. Spring Data의 공식 MultiDB 전용 팩토리를
사용하는 것이 아니라 프로젝트의 `MultiDbRedisConnectionFactory` 어댑터를 사용합니다.
이 가이드에는 특정 환경의 접속 정보, 자격 증명 및 테스트 실측 결과를 포함하지 않습니다.

## 1. 준비 사항

- Java 21 이상, Maven 3.9 이상
- 프로젝트의 고정 의존성: Spring Boot 4.1.1, Spring Data Redis 4.1.1, Lettuce 7.5.2.RELEASE
- 실제 AA 검증에는 동일 논리 데이터베이스의 리전별 엔드포인트 2개 이상
- 각 엔드포인트의 사용자, 비밀번호, 포트, TLS/mTLS 여부 및 네트워크 접근 허용 설정
- 로컬 통합 테스트에만 Docker Compose 필요

독립 Redis 서버 두 대로도 연결과 라우팅을 시험할 수 있지만 서로 데이터를 복제하지 않습니다.
MultiDbClient 자체에는 엔드포인트 간 데이터 복제 기능이 없습니다.

## 2. 연결 구조

```text
Example / 서비스 코드
  └─ RedisTemplate 또는 ListOperations
       └─ MultiDbRedisConnectionFactory
            └─ 공유 StatefulRedisMultiDbConnection
                 ├─ 우선 엔드포인트 (weight 1.0)
                 └─ 대체 엔드포인트 (weight 0.5)
```

`MultiDbRedisConfiguration`은 이름이 `redisTemplate`인 빈을 명시적으로 MultiDB 팩토리에
연결합니다. 따라서 아래 두 주입 방식이 같은 팩토리를 사용합니다.

```java
@Autowired
private RedisTemplate<String, String> redisTemplate;

@Resource(name = "redisTemplate")
private ListOperations<String, String> listOps;
```

예제의 고객 `ApplicationConfig.redisConnectionFactory()`도 그대로 존재합니다. 추가 팩토리에
`@Primary`를 지정했지만, 이름으로 `redisConnectionFactory`를 지정하여 주입하는 다른 코드까지
자동으로 MultiDB로 바뀌는 것은 아닙니다. 그 코드는 기존 빈을 사용합니다.

공유하는 것은 Lettuce 네이티브 연결입니다. 각 `getConnection()`은 별도의 Spring
`LettuceConnection` 래퍼를 반환합니다. 애플리케이션이 래퍼를 직접 공유하거나
네이티브 연결을 닫지 말고, `RedisTemplate`과 Spring 생명주기에 관리를 맡기십시오.
팩토리가 시작되기 전에는 Redis를 호출하지 마십시오. `@PostConstruct`에서의 호출도 피하십시오.

## 3. 예제 실행

### Example에서 제공하는 Operations 호출

모든 Operations 필드는 `@Resource(name = "redisTemplate")`로 주입받습니다.
추가 예제의 쓰기는 해당 Operations 필드를, 조회는 `redisTemplate.bound*Ops(key)`를 사용하여
같은 템플릿을 사용하는 두 가지 호출 방식을 보여줍니다.

| 타입 | 쓰기 메서드 | 조회 메서드 |
|---|---|---|
| List | 기존 `addLink(userId, url)` | 기존 테스트에서 `opsForList().range(...)` |
| Value | `setValue(key, value)` | `getValue(key)` |
| Set | `addSetMember(key, member)` | `isSetMember(key, member)` |
| ZSet | `addZSetMember(key, member, score)` | `getZSetScore(key, member)` |
| Hash | `putHashField(key, field, value)` | `getHashField(key, field)` |

Spring에서 주입받은 `Example example` 인스턴스는 다음과 같이 사용하실 수 있습니다.

```java
example.setValue("demo:value:greeting", "hello");
String greeting = example.getValue("demo:value:greeting");

Long added = example.addSetMember("demo:set:topics", "redis");
Boolean member = example.isSetMember("demo:set:topics", "redis");

Boolean created = example.addZSetMember("demo:zset:scores", "alice", 100.0);
Double score = example.getZSetScore("demo:zset:scores", "alice");

example.putHashField("demo:hash:profile", "name", "Alice");
String name = example.getHashField("demo:hash:profile", "name");
```

위 문자열은 설명용 키이며 환경별 자격 증명이나 실제 엔드포인트가 아닙니다.
하나의 Redis 키에는 하나의 데이터 타입만 저장할 수 있으므로 타입별로 다른 키를 사용하십시오.
`setValue`는 기존 값을 덮어씁니다. Set은 동일 멤버를 중복 저장하지 않습니다.
ZSet은 동일 멤버의 점수를 갱신하며 새 멤버가 아니면 추가 결과가 `false`입니다.
Hash는 같은 필드의 값을 갱신합니다. Value/Hash/ZSet 조회 대상이 없으면 `null`을 반환합니다.
새 메서드는 각 호출마다 하나의 명령을 실행하며 자동 TTL을 설정하지 않습니다.
필요한 TTL은 애플리케이션에서 추가하십시오. 기존 `addLink()`의 두 번 쓰기는 그대로 유지합니다.

### 실행 명령

```bash
mvn test
```

기본 명령은 외부 DB 없이 단위 테스트를 실행합니다. 로컬 Redis 및 Cloud 통합 테스트는
각각의 활성화 환경변수가 없으면 건너뜁니다.

로컬 Redis 두 대를 이용한 고객 코드 주입·명령 테스트는 다음과 같이 실행하십시오.

```bash
docker compose up -d --wait
RUN_REDIS_INTEGRATION_TESTS=true mvn -Dtest=MultiDbCustomerCodeIntegrationTests test
docker compose down
```

이 테스트는 localhost의 16379와 16380 포트를 사용합니다. 원본 `Example.addLink()`는
두 사용법을 보여주기 위해 `leftPush`를 두 번 실행하므로 리스트 항목 2개가 정상 결과입니다.
같은 통합 테스트 클래스에서 Value 덮어쓰기, Set 중복, ZSet 점수 갱신과 정렬,
Hash 필드 갱신도 검증합니다. 고유 테스트 키는 각 테스트 종료 후 삭제합니다.
추가 타입의 검증 범위는 로컬 명령 실행이며 AA 차단·복귀 검증은 기존 List 경로를 사용합니다.

DB 접속 설정을 완료하신 후 다음 명령으로 애플리케이션을 실행하십시오.

```bash
mvn spring-boot:run
```

이 예제에는 HTTP API나 지속적인 데이터 생성기가 없습니다. 실제 호출은 통합 테스트 또는
프로젝트에 추가한 애플리케이션 코드에서 수행합니다.

## 4. 애플리케이션 접속 설정

`src/main/resources/application.yml`의 환경변수를 사용합니다.

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `REDIS_PRIMARY_URI` | 우선 엔드포인트의 완전한 Redis URI | `redis://localhost:6379` |
| `REDIS_SECONDARY_URI` | 대체 엔드포인트의 완전한 Redis URI | `redis://localhost:6380` |
| `REDIS_MULTIDB_INITIALIZATION_POLICY` | 시작 시 필요한 정상 엔드포인트 수 | `ONE_AVAILABLE` |
| `REDIS_COMMAND_TIMEOUT` | Spring 명령 대기 타임아웃 | `2s` |

URI 형식은 TLS를 사용하는 `rediss://<user>:<password>@<host>:<port>` 또는 TLS가 꺼진 DB의
`redis://<user>:<password>@<host>:<port>`입니다. URI의 사용자 이름이나 비밀번호에 예약 문자가 포함되어 있으면
퍼센트 인코딩(percent-encoding)을 적용하십시오. 예시의 꺾쇠 괄호를 그대로 입력하지 마십시오.

개발용 Bash 세션에서는 셸 명령 기록에 접속 URI를 남기지 않고 입력할 수 있습니다.

```bash
read -r -s -p 'Primary Redis URI: ' REDIS_PRIMARY_URI; printf '\n'
read -r -s -p 'Secondary Redis URI: ' REDIS_SECONDARY_URI; printf '\n'
export REDIS_PRIMARY_URI REDIS_SECONDARY_URI
export REDIS_MULTIDB_INITIALIZATION_POLICY=ONE_AVAILABLE
mvn spring-boot:run
unset REDIS_PRIMARY_URI REDIS_SECONDARY_URI
```

운영에서는 Secret 관리 도구로 환경변수를 공급하십시오. URI와 환경변수는 실행 중 메모리에
존재하므로 디버그 환경 덤프나 프로세스 정보로 노출하지 않도록 관리해야 합니다.
Redis Cloud 관리 API 키는 애플리케이션의 Redis 데이터 접속에 필요하지 않습니다.

TLS를 사용하는 경우 엔드포인트의 호스트 이름과 서버 인증서가 일치해야 합니다. 사설 CA 또는
mTLS가 필요하면 해당 신뢰 저장소·클라이언트 인증서 설정을 별도로 구현해야 합니다.
이 예제는 인증서 파일을 지정하는 전용 설정 항목을 제공하지 않습니다.

## 5. 선택·복귀 정책

| 설정 경로 (`example.redis.multidb` 아래) | 예제 기본값 | 의미 |
|---|---|---|
| `endpoints[].weight` | 1.0 / 0.5 | 높은 가중치의 정상 엔드포인트 선호 |
| `failback-enabled` | true | 복구된 우선 엔드포인트로 자동 복귀 |
| `failback-check-interval` | 30s | 복귀 조건 확인 주기 |
| `grace-period` | 60s | 복귀 전 유예 시간 |
| `command-timeout` | 2s | 명령 대기 제한 |

가중치는 트래픽을 비율로 나누는 로드밸런싱 설정이 아닙니다. 복귀 시간도 단순히 30초 또는
60초로 고정되지 않으며 정상 동작 여부 감지와 주기 검사 시점의 영향을 받습니다.

`ONE_AVAILABLE`은 한 엔드포인트만 정상이어도 초기화를 허용합니다. `MAJORITY_AVAILABLE`은
과반수, `ALL_AVAILABLE`은 전부가 정상이어야 합니다. 2개 엔드포인트에서는 과반수가 2개입니다.
이는 시작 조건이며 쓰기의 과반수 합의나 데이터의 강한 일관성을 보장하는 조건이 아닙니다.

엔드포인트 목록을 다른 설정 소스에서 덮어쓰면 배열 전체가 대체될 수 있습니다.
각 항목의 `name`, `uri`, `weight`를 모두 제공하십시오. `RedisURI.toURI()`를 자격 증명 직렬화
수단으로 사용하지 마십시오. 이 버전에서는 자격 증명 공급자(credentials provider)가 실제 값 대신 대체 문자열(placeholder)로 표현될 수 있습니다.

## 6. 기존 Spring 애플리케이션에 적용

1. `config` 패키지의 Properties, Configuration, ConnectionFactory 클래스를 프로젝트로 가져오십시오.
2. 패키지를 컴포넌트 스캔 범위에 포함하거나 `@Import`로 Configuration 클래스를 명시적으로 등록하십시오.
3. `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(MultiDbRedisProperties.class)`로
   설정 속성 빈을 등록하십시오. 이 예제의 main 클래스는 스캔 방식을 사용합니다.
4. `application.yml` 설정과 호환되는 의존성을 추가하십시오.
5. 실제 애플리케이션에 기존 서비스 빈이 있다면 예제용 `example()` 빈 등록은 제거하십시오.
6. 서비스 코드가 사용할 `redisTemplate`의 팩토리와 직렬화 도구를 확인하십시오.

이미 `redisTemplate`이라는 빈이 있다면 새 빈과 충돌할 수 있습니다. 해당 빈 정의에서
MultiDB 팩토리를 참조하도록 조정하십시오. 임의의 기존 Spring 설정 전체를 무수정으로 교체한다고
가정해서는 안 됩니다. 동일하게 여러 `@Primary` 팩토리나 템플릿이 있으면 후보를 정리해야 합니다.

예제는 키·값·해시에 `StringRedisSerializer`를 사용합니다. 기존에 JDK 직렬화, JSON 또는
별도 키 규약을 사용했다면 그 설정을 유지해야 기존 데이터를 동일하게 읽을 수 있습니다.
`RedisTemplate<String, String>`의 제네릭만으로 직렬화 형식이 자동으로 보장되지는 않습니다.

## 7. 실제 AA 테스트

전용 테스트 DB를 사용하십시오. 테스트는 키를 생성·조회·삭제합니다. 실제 주소와 비밀번호는
다음 환경변수로 제공하십시오. 이 저장소에는 환경별 접속 값을 넣지 않습니다.

| 변수 | 용도 |
|---|---|
| `AA_PRIMARY_HOST`, `AA_SECONDARY_HOST` | 프로토콜·포트를 제외한 각 호스트 이름 |
| `AA_REDIS_PASSWORD` | 두 엔드포인트의 default 사용자 비밀번호 |
| `AA_TLS` | 사전 복제 검사에서 기본 TLS 사용; 명시적 `false`일 때 평문 |
| `RUN_CLOUD_PREFLIGHT` | `true`일 때 양방향 복제 검사 활성화 |
| `RUN_CLOUD_FAILOVER` | `true`일 때 프록시 차단 검사 활성화 |

현재 Cloud 테스트의 포트는 **11164**, 사용자는 **default**, 비밀번호는 양쪽 공통입니다.
환경이 다르면 테스트의 URI/프록시 구성도 수정해야 합니다. 애플리케이션 본체는 완전한 URI를
받으므로 다른 포트와 엔드포인트별 자격 증명을 사용할 수 있습니다.

```bash
read -r -p 'Primary hostname: ' AA_PRIMARY_HOST
read -r -p 'Secondary hostname: ' AA_SECONDARY_HOST
read -r -s -p 'Redis password: ' AA_REDIS_PASSWORD; printf '\n'
export AA_PRIMARY_HOST AA_SECONDARY_HOST AA_REDIS_PASSWORD

# DB 설정에 맞게 true 또는 false를 선택합니다. 기본값은 TLS 사용입니다.
export AA_TLS=true
RUN_CLOUD_PREFLIGHT=true mvn -Dtest=CloudReplicationPreflightTests test
```

사전 검사는 각 방향별 3회 쓰기와 상대 조회를 수행합니다. UTC 쓰기 시작·ACK·최초 성공 읽기
시각을 `target/cloud-preflight-<UUID>.csv`에 기록합니다. 관측 시간에는 네트워크 RTT와 조회 간격이
포함되므로 서버 내부 복제 지연과 구분하십시오.

차단 검사는 **TLS가 꺼진 DB 전용**입니다. 해당 DB에서 평문 접속과 클라이언트 경로 차단을 허용한 경우,
사전 검사가 성공한 후 실행하십시오.

```bash
export AA_TLS=false
RUN_CLOUD_PREFLIGHT=true mvn -Dtest=CloudReplicationPreflightTests test && \
RUN_CLOUD_FAILOVER=true mvn -Dtest=CloudFailoverTests test
unset AA_REDIS_PASSWORD
```

차단 검사 내부에서도 양방향 복제를 확인한 뒤 원본 고객 호출 → A 프록시 차단 → B 전환 →
A 복구·복귀 → 초기화 정책 비교를 수행합니다. 결과는 `target/cloud-failover-<UUID>.csv`에 기록합니다.
프록시는 루프백에만 바인딩하며 이 테스트의 연결만 차단합니다. DB 자체나 AA 복제망을 차단하지 않습니다.

모든 키는 `codex:multidb:test:<UUID>:` 접두사를 사용합니다. SETEX는 TTL 900초를 동시에 설정하고,
원본 LPUSH에는 후속 명령으로 TTL을 적용합니다. finally에서 생성한 정확한 키만 정리합니다.
프로세스 강제 종료나 네트워크 장애로 후속 TTL/삭제가 실패하면 키가 남을 수 있으므로 정리 결과를 확인하십시오.
CSV 등 생성 결과는 `.gitignore` 대상이며 커밋하지 않습니다.

## 8. 지원 범위와 운영 시 주의점

이 예제의 대상은 단일 서버 형태의 엔드포인트를 사용하는 명령형 RedisTemplate의 일반
비차단 명령입니다. Cluster/Sentinel 연결 API, Spring 파이프라인 및 `multi()`는 예외로 거부하며,
전용 연결이 필요한 기능에도 연결을 제공하지 않습니다. Reactive 팩토리는 구현하지 않았습니다.
Pub/Sub 구독, 블로킹 소비자, 트랜잭션을 포함하는 애플리케이션은 별도 설계가 필요합니다.
이러한 제한은 범용 Redis 명령 보안 필터가 아니므로 네이티브 연결이나 원시 명령으로 우회해서 사용하지 마십시오.

AA의 비동기 복제 때문에 엔드포인트 전환 직후 최신 쓰기가 아직 보이지 않을 수 있습니다.
현재 설정에서는 복제 지연을 고려하는 상태 확인 전략을 별도로 지정하지 않습니다. PING 정상 여부와 데이터 수렴
완료는 다르므로 읽기 일관성이 중요하면 복제 지연을 포함한 상태 확인 정책을 검토하십시오.

명령 타임아웃은 쓰기가 수행되지 않았음을 보장하지 않습니다. LPUSH 같은 비멱등 명령을 무조건
재시도하면 중복될 수 있습니다. 요청 식별자와 중복 처리 등은 업무 요구에 맞게 설계해야 합니다.
또한 프록시 소켓 단절 한 번의 시험으로 서버 장애·응답 유실·재전송의 모든 조합을 검증할 수 없습니다.

이 코드는 예제 어댑터입니다. 버전을 변경하거나 사용 명령·직렬화 도구·인증·TLS 방식을 확장할 때는
해당 환경에서 통합 테스트를 수행하십시오. 운영 배포 전에는 시작 실패 시 자원 정리, 동시 종료와 요청,
관측 지표 및 오류 처리 등 생명주기 경계도 검토해야 합니다.

## 9. 자주 발생하는 문제

| 증상 | 확인할 사항 |
|---|---|
| 빈 이름 충돌/주입 모호성 | 기존 redisTemplate 정의와 Primary 후보 |
| `Each MultiDB endpoint must have a name` | 목록 재정의 시 name/uri/weight 누락 |
| 초기화 정책 오류 | 실제 인증, 네트워크, 정상 엔드포인트 수 |
| `TLS hostname mismatch` | TLS 설정 및 인증서에 포함된 엔드포인트 호스트 이름 |
| QueryTimeoutException | 클라이언트 타임아웃, 전환 중 상태, 결과가 불확실한 쓰기 처리 |
| 이전 데이터가 읽히지 않음 | 직렬화 도구, 키 규약, 실제 선택 엔드포인트 및 AA 수렴 |
| Cloud 테스트가 건너뛰어짐 | 해당 `RUN_CLOUD_*` 활성화 변수 |

관리 API는 별도 구성 조회에만 필요합니다. 데이터 접속의 default 비밀번호와 관리 API 시크릿을
혼용하지 마십시오. 실제 엔드포인트, API 키, 비밀번호, 인증서 개인 키, `.env`, 테스트 보고서는
저장소에 추가하지 마십시오.
