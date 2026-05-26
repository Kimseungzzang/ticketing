# Learning Log — 선착순 예매 MSA

지금까지의 작업 과정과 **왜 그렇게 결정했는지**를 학습용으로 정리한 노트.
ARCHITECTURE.md 는 "현재 어떻게 생겼나"의 reference, 이 문서는 "어떻게 거기에 도달했나"의 thought log.

---

## 0. 타임라인 (큰 흐름)

| 단계 | 작업 | 결과물 |
|------|------|--------|
| 0 (이전) | MyRedis 직접 구현 | Netty + RESP, 캐시/세션 동작 |
| 0 (이전) | MyKafka MVP | 단일 브로커, PRODUCE/FETCH/COMMIT_OFFSET 등 5개 ApiKey, sparse index |
| 0 (이전) | auth-service / frontend / gateway 뼈대 | JWT 인증, Next.js UI, 라우팅 자리만 |
| 1 (이번 세션) | MyKafka를 써먹기 위한 도메인 결정 | 티켓팅 적용, CQRS-ish 분리로 방향 잡음 |
| 2 | 도메인 모델 합의 | Event / SeatSection / Seat / Reservation 4개 엔티티 |
| 3 | ticket-command-service 골격 | port 8082, R/W, 예약 생성/확정/취소 |
| 4 | ticket-query-service 골격 | port 8083, Read-only, 좌석맵·예약 조회 |
| 5 | mock-data.sql + 부팅 절차 정리 | ARCHITECTURE.md §7 |
| 6 (현재) | 부하 테스트 진입 직전 | k6 시나리오 작성 대기 |

다이어그램 발견: `~/Downloads/_ _ _.html` 의 React 컴포넌트에서 "선착순 예매 시스템 Architecture"가 발견됨 → 지금 구현 중인 게 이 다이어그램의 일부라는 게 확인됨.

---

## 1. 왜 CQRS-ish 분리인가 (command/query 두 서비스)

### 상황
MyKafka MVP를 끝낸 뒤 "이걸 써먹을 도메인이 필요" → 티켓팅 시스템 선택.
요구 사항(학습 목표): **대규모 트래픽 대비**. 좌석 조회 트래픽이 예약 트래픽보다 훨씬 많음 (한 사람이 좌석을 N번 본 뒤 1번 예약).

### 옵션
- (A) 단일 ticket-service에 read/write 다 넣기
- (B) command/query 별도 서비스로 분리 (CQRS)
- (C) gRPC 내부 통신 + 게이트웨이 어그리게이션

### 선택: B
### 왜
- **읽기/쓰기 스케일링 곡선이 다름** → 분리하면 각각 독립 스케일.
- DB Primary/Replica 분리할 때 query 쪽 코드 변경 거의 없음 (URL만 바꾸면 됨).
- 학습 가치: CQRS는 말로만 들어선 안 와닿음. 두 서비스가 같은 DB를 읽기/쓰기로 나눠 가지는 게 가장 작은 CQRS.

### 트레이드오프 (의식하고 받아들임)
- 코드 중복 (entity 두 벌)
- 배포/관리 복잡도 2배
- 단순 CRUD 앱에서는 과잉. 학습 목적이라 OK.

---

## 2. 왜 Spring Boot 4.0.6 + Kotlin 2.2.21 인가

### 선택 이유
**auth-service가 이미 그 버전**. 새 서비스를 다른 버전으로 시작하면 의존성/JDK 버전 비교 학습 비용만 늘고, 그건 이 프로젝트 학습 목표가 아님. → 일관성.

### 부수 효과
- `tools.jackson.module:jackson-module-kotlin` 사용 (Jackson 3.x, Spring Boot 4 기본)
- `jjwt-gson` 사용 — auth-service가 Jackson 3 충돌 회피로 채택한 것. ticket-service는 JWT 안 쓰니 무관.

---

## 3. 왜 독립 Gradle 프로젝트 (multi-module 아님)

### 옵션
- (A) 루트 multi-module Gradle (모든 서비스 한 settings.gradle.kts)
- (B) 각 서비스 독립 Gradle 프로젝트 (각자 wrapper)

### 선택: B
### 왜
- auth-service가 이미 B 패턴
- 서비스마다 의존성·JDK·플러그인을 독립 진화시키기 쉬움
- MSA 정신에 가까움 (각 서비스가 독립 배포 단위)
- 가까운 미래에 언어가 갈라질 수 있음 (Worker는 더 가벼운 게 좋을 수도)

### 트레이드오프
- 공통 코드(예: 공유 도메인 enum) 추출이 번거로움. **일부러 안 함** — CQRS 분리의 일부는 "엔티티가 서로 독립 진화할 수 있는" 자유를 갖는 것.

---

## 4. 왜 같은 DB를 공유하고 시작했나

### 옵션
- (A) 처음부터 command DB / query DB 둘로 (Kafka로 동기화)
- (B) 같은 DB를 read/write 권한만 나눠서 시작

### 선택: B
### 왜
- (A)는 데이터 정합성·outbox·eventual consistency 같은 학습량이 한꺼번에 폭발. 학습은 한 번에 한 변수만.
- (B)는 일단 동작하는 시스템을 얻고, 이후 단계마다 (Primary/Replica → 완전 분리) 점진적으로 진화 가능.
- query side의 hikari `read-only: true` + `@Transactional(readOnly=true)` 로 **권한만 미리 잠가둠** → replica로 갈아끼울 때 URL 한 줄만 변경하면 끝.

### 학습 포인트
"같은 DB" → "Primary/Replica" → "이벤트로 동기화되는 별도 DB" 각 단계에서 **무엇이 풀리고 무엇이 새로 어려워지는지** 직접 체감하는 게 목표.

---

## 5. 왜 엔티티를 두 패키지에 중복 정의

### 의도
- 같은 `EventEntity`가 `com.example.ticketcommand.entity` 와 `com.example.ticketquery.entity` 양쪽에 있음.
- 표면적 DRY 위반.

### 왜 그렇게 했나
- query side의 엔티티가 **언젠가 read model로 분기**할 자리이기 때문.
- 예: command는 `Reservation(status, createdAt)` 만 가지고, query는 `ReservationProjection(status, createdAt, eventTitle, seatLabel, totalPrice)` 같은 **조회 최적화 모델**을 가질 수 있음.
- 처음부터 공유 모듈로 묶으면 그 분기 비용이 큼.
- 학습: CQRS의 "Q 쪽 모델이 다를 수 있다" 가 핵심인데, 같은 클래스 쓰면 그 감각을 못 얻음.

### 비용
지금은 정의가 동일해서 변경할 때 두 곳 손봐야 함. 의식적으로 짊어진 비용.

---

## 6. 왜 비관락 + `@Version` 둘 다

### 코드
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
fun findAllByIdIn(ids: Collection<String>): List<SeatEntity>

@Version
var version: Long = 0
```

### 왜 둘 다
- **비관락(PESSIMISTIC_WRITE)**: 두 명이 같은 좌석을 동시에 누를 때 트랜잭션 직렬화. `SELECT ... FOR UPDATE`로 DB가 직접 막음.
- **@Version (낙관락)**: 비관락이 풀린 직후의 짧은 race window를 한 번 더 차단. 또한 미래에 비관락을 풀고 낙관락만 쓰는 실험으로 갈아탈 때 사전 준비.

### 학습 포인트
선택지 비교:
| 방식 | 장점 | 단점 |
|------|------|------|
| 비관락 | 명확, 정확 | DB 락 대기 → 트래픽 폭주 시 병목 |
| 낙관락 | 락 안 잡음 | 충돌 시 재시도 로직 필요 |
| Redis SETNX | DB 부담 X | Redis 의존, 일관성 검증 어려움 |
| Kafka 파티션 큐잉 | 자연 직렬화 | 응답 지연, 시스템 복잡도 ↑ |

다이어그램의 잔여좌석 Redis(SETNX + Lua)는 위 표의 3번. 우리는 1번에서 시작해 부하 테스트로 한계 측정 후 진화.

---

## 7. 왜 `ddl-auto: command=update / query=validate`

### 의도
스키마 변경 권한을 **command 한 군데로 집중**.

- command: `update` — 엔티티 추가/필드 변경 시 DB 스키마 자동 생성·변경
- query: `validate` — 부팅 시 엔티티 vs 실제 스키마만 검증. 다르면 부팅 실패.

### 왜
- query가 마음대로 스키마 만들면 두 서비스의 엔티티가 어긋날 수 있음 (race condition).
- 부팅 순서가 강제됨: command 먼저 → query 나중. 이 강제가 **학습 의도** — query는 항상 command가 정의한 스키마의 소비자.
- production에서는 둘 다 `validate`로 두고 flyway/liquibase로 별도 마이그레이션. 우리는 학습용이라 단순.

---

## 8. 왜 mock-data.sql (admin API 아님)

### 옵션
- (A) `POST /events`, `POST /events/{id}/sections`, `POST /events/{id}/seats:bulk` admin API
- (B) `mock-data.sql` 한 파일

### 선택: B
### 왜
- A는 "더 서비스다운" 느낌이지만 지금 학습 목표(트래픽 처리)와 무관한 코드가 늘어남.
- B는 한 번 주입하고 끝. 멱등(`TRUNCATE ... CASCADE`)이라 반복 가능.
- 좌석 292개를 `generate_series`로 한 줄에 폭발시킬 수 있어서 SQL이 압도적으로 짧음.

### 학습 포인트
**언제 API로 노출하고 언제 SQL로 끝낼지** — 운영자만 쓸 init 데이터는 SQL이 정답. 사용자 트래픽을 받는 endpoint와 구분하는 감각.

---

## 9. 왜 지금은 Scheduler / Worker 안 만드나

다이어그램에는 Scheduler(대기열에서 100/s pop), Worker(MQ → DB INSERT)가 명시되어 있는데 지금 구조에는 없음. **의도적 보류**.

### 왜 보류
- 이 둘은 **트래픽 폭주를 가정한 구조**. 지금 트래픽이 폭주하지 않음.
- 한계를 직접 측정하지 않고 도입하면 "왜 만들었는지" 감각이 안 옴.
- 학습은 한 번에 한 변수씩.

### 도입 트리거
- **Worker**: 부하 테스트에서 DB write가 병목 → API p95 latency 폭증 시. 그때 "command가 Kafka publish만 하고 즉시 응답"으로 전환.
- **Scheduler**: 대기열 자체(Queue API)를 도입할 때 동반. 폴링만으로 throttling 안 됨.

### 일종의 안티패턴 회피
다이어그램을 보자마자 "Scheduler, Worker 다 만들어야 한다" 모드로 가면 **다이어그램 그대로 베끼는 prototype**이 됨. 학습 목적은 "이 다이어그램의 각 부품이 왜 거기 있는가"를 한 부품씩 직접 만들어가며 이해하는 것.

---

## 10. 부수적으로 알게 된 것

### MyKafka의 위치
DESIGN.md를 읽어보니 broker 측만 구현되어 있고 **client SDK가 없음**. ticket-command를 MyKafka에 붙이려면 `client/Producer.kt`, `client/Consumer.kt` 부터 만들어야 함. 학습량 추가. (Worker 단계에서.)

### frontend가 도메인 단서
`frontend/lib/mock-data.ts` 의 `mockSections` 가 사실상 도메인 specification 역할. backend 엔티티를 이 모양에 맞춤. 보통은 backend 먼저인데, **frontend가 먼저 정의되어 있으면 그걸 정답으로 받아들이는 게 합리적** (이미 UI가 그 모양으로 짜였으므로).

### Postgres 연결 환경
사용자 환경은 로컬 5432가 비어있고 EC2 Postgres에 dbeaver/Java로 붙어있는 상태였음. 학습 + replica 실습을 위해 로컬 Postgres 따로 띄우기로 결정. 환경 셋업은 사용자 직접.

---

## 11. 다음에 학습할 것 (예정 순서)

### A. 부하 테스트 (k6) — 다음 단계
**측정해야 할 것**:
- API: RPS, p50/p95/p99 latency, 에러율
- DB: active connections, lock waits, slow query
- JVM: heap, GC pause, tomcat thread pool

**시나리오**:
1. 100 VU × 서로 다른 좌석 동시 POST → 순수 처리량
2. 100 VU × 같은 10개 좌석 동시 POST → 비관락 정확성 검증 (정확히 10명만 성공해야 함)
3. 점진 증가 (10 → 500 VU) → knee point 탐색
4. 75% 조회 + 25% 쓰기 → query 부하

**기대 학습**: 어느 지표가 먼저 무너지는지가 다음 단계를 결정. DB CPU가 먼저 터지면 → Worker. 커넥션 풀이 먼저 터지면 → 풀 튜닝 또는 Replica.

### B. MyKafka client SDK + Worker
- `MyKafka/src/main/kotlin/com/example/mykafka/client/` 신규
- Netty 기반 Producer (PRODUCE 프레임 보내기)
- Netty 기반 Consumer (FETCH 폴링 루프)
- ticket-command에 `KafkaPublisher` 주입 → 예약 커밋 후 `ReservationCreated` 발행
- 별도 `ticket-worker-service` 신규 → consume → DB INSERT
- **outbox 패턴** 학습 포인트: DB 커밋과 Kafka 발행을 어떻게 원자적으로 묶는가

### C. DB Primary/Replica
- Postgres streaming replication 직접 셋업 (`pg_basebackup`, `primary_conninfo`)
- query의 datasource URL을 replica로
- **replica lag 측정** + UI 어떻게 대응할지 정책 결정

### D. Queue API + Scheduler (대기열)
- 다이어그램 §1단계·§2단계
- Redis ZSET 기반 순번
- 폴링 5s → 1s 가변
- Scheduler 1초마다 ZPOPMIN 100

### E. Gateway 완성
- Spring Cloud Gateway에 JWT 검증 + URL 라우팅
- `/queue/*` → Queue API, `/booking/*` → Booking API

---

## 12. 의식적으로 두는 원칙

세션 내내 따른 메타 규칙:

1. **학습은 한 번에 한 변수**. 새 개념과 새 도구를 동시에 도입하지 않는다.
2. **동작하는 최소 → 측정 → 한계 → 진화**. prototype-first가 아니라 evolve-first.
3. **다이어그램 그대로 베끼지 않는다**. 다이어그램은 도착지 힌트, 길은 직접 걸어야 학습.
4. **공유 모델로 묶지 않는다 (지금은)**. CQRS의 핵심 자유도 보존.
5. **권한을 미리 잠가둔다** (query read-only). 미래 분리가 쉬워지는 방향으로 결정.
6. **인프라 설치는 사용자가 직접**. Claude는 안내·시드 파일 작성까지.

---

이 문서는 살아있는 노트. 새 결정마다 §번호 추가하면서 갱신.
