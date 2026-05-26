# Ticketing MSA — Architecture & Progress

대규모 트래픽 티켓팅 시스템을 MSA로 학습하기 위해 인프라부터 직접 구현하는 프로젝트.
외부 매니지드 서비스(AWS MSK, ElastiCache 등)에 의존하지 않고 핵심 부품을 Kotlin/Netty로 직접 짜본 뒤 그 위에 도메인을 얹는다.

---

## 1. 시스템 컴포넌트 한눈에

```
                  ┌────────────────────┐
                  │  frontend (Next.js)│
                  │  port 3000         │
                  └─────────┬──────────┘
                            │ HTTP
                  ┌─────────▼──────────┐
                  │  gateway           │   ← (뼈대만, JWT 검증·라우팅 예정)
                  │  port 8080         │
                  └─────────┬──────────┘
              ┌─────────────┼──────────────────┐
              │             │                  │
       ┌──────▼─────┐ ┌─────▼──────────┐ ┌─────▼──────────┐
       │ auth-      │ │ ticket-command │ │ ticket-query   │
       │ service    │ │ -service       │ │ -service       │
       │ port 8081  │ │ port 8082      │ │ port 8083      │
       │ (JWT+R/W)  │ │ (R/W)          │ │ (Read-only)    │
       └────┬───┬───┘ └────┬───────────┘ └────┬───────────┘
            │   │          │                   │
            │   │  ┌───────▼──────┐    ┌───────▼──────┐
            │   │  │ Postgres     │    │ Postgres     │
            │   │  │ (Primary)    │===>│ (Replica)    │  ← 예정
            │   │  │  ticket_db   │    │  ticket_db   │
            │   │  └──────┬───────┘    └──────────────┘
            │   │         │ events
            │   │  ┌──────▼──────────────────┐
            │   │  │ MyKafka                 │ ← 예정 (PRODUCE/FETCH)
            │   │  │ port 9092               │
            │   │  └──────┬──────────────────┘
            │   │         │ consume
            │   │         └──→ ticket-query (read model 갱신)
            │   │
       ┌────▼───▼───┐                    ┌────────────┐
       │ MyRedis    │                    │ Postgres   │
       │ port 6379  │                    │  auth_db   │
       └────────────┘                    └────────────┘
```

---

## 2. 각 컴포넌트 현황 (2026-05-22)

| 컴포넌트 | 상태 | 비고 |
|---------|------|------|
| `MyRedis/` | DONE | Netty 기반 RESP. workerGroup=1 제한, auth-service synchronized 제거 + socket pool 도입 TODO |
| `MyKafka/` | MVP DONE (untracked) | 단일 노드. Log/Segment/OffsetIndex/RecordCodec. Replication·KRaft·log compaction 미구현 |
| `auth-service/` | DONE | Spring Boot 4 + JWT + Postgres(`auth_db`) + MyRedis token store |
| `frontend/` | DONE | Next.js. queue / seats / payment / confirmation 페이지 + mock data |
| `gateway/` | 뼈대만 (untracked) | `GatewayApplication.kt` 한 개. JWT 검증·라우팅 미구현 |
| `ticket-command-service/` | **방금 골격 완성 (untracked)** | port 8082, R/W, 예약 생성/확정/취소 |
| `ticket-query-service/` | **방금 골격 완성 (untracked)** | port 8083, Read-only, 이벤트/좌석맵/예약 조회 |

---

## 3. MyKafka 정리

> 상세는 `MyKafka/DESIGN.md` 참고. 여기는 한 페이지 요약.

### 3.1 Wire Protocol
모든 메시지는 길이 접두 프레이밍:

```
[ 4 bytes totalLength ][ 1 byte apiKey ][ payload ... ]
totalLength = apiKey(1) + payload 길이 (length 필드 자체 제외)
```

이유: TCP는 바이트 스트림 → 메시지 경계가 없음 → 길이를 먼저 보내야 수신자가 어디까지 한 메시지인지 안다 (실제 Kafka도 동일).

### 3.2 ApiKey (5개)
| code | API | 용도 |
|------|-----|------|
| 0 | PRODUCE | 토픽에 레코드 발행 (batch all-or-none) |
| 1 | FETCH | 오프셋부터 레코드 읽기 (sparse offset index) |
| 2 | CREATE_TOPIC | 토픽 생성 (파티션 수 지정) |
| 3 | COMMIT_OFFSET | consumer 그룹 오프셋 커밋 (`__consumer_offsets`에 자체 발행) |
| 4 | FETCH_OFFSET | consumer 재시작 시 마지막 commit 위치 읽기 |

### 3.3 저장 구조
```
data/
├── <topic-name>/
│   ├── partition-0/
│   │   ├── 00000000000000000000.log    ← Segment (record stream)
│   │   ├── 00000000000000000000.index  ← OffsetIndex (sparse, 4KB마다 1엔트리)
│   │   └── 00000000000000001000.log    ← 1000 오프셋부터 새 segment
│   └── partition-1/
│       └── ...
└── __consumer_offsets/                 ← 자체 토픽, COMMIT_OFFSET이 여기 발행
```
- **Segment rolling**: 크기 임계 도달 시 새 파일로 분리 → 옛 데이터 삭제는 segment 단위.
- **Sparse index**: 모든 record에 인덱스 안 만들고 일정 간격(기본 4KB)마다 (offset → file position) 기록. FETCH 시 인덱스에서 가까운 지점 찾아 거기서 선형 스캔.
- **Partition 분산**: 같은 key sticky partition / null key round-robin.

### 3.4 검증된 동작 (DESIGN.md §8)
- 1000건 single PRODUCE vs 1×1000 batch → **61x speedup**
- Cross-segment FETCH (segment 경계 넘어가는 fetch 안전)
- maxBytes=1로도 최소 1건은 진행 보장
- 컨슈머 재시작 → 마지막 commit 정확히 이어 시작

### 3.5 의도적으로 안 넣은 것
| 영역 | 학습 포인트 |
|------|-----------|
| Replication | Leader/Follower + ISR + acks=all |
| Controller | KRaft (Raft 기반) |
| Log compaction | 같은 key 옛 record 삭제 (consumer_offsets 정리) |
| Exactly-once | producer idempotence + transactions |
| Zero-copy | sendfile() / Netty FileRegion |
| Group coordination | rebalance, sticky assignment |

### 3.6 클라이언트 SDK 상태
**아직 없음.** 서버 측(BrokerServer/RequestRouter)만 구현되어 있고, 외부에서 PRODUCE/FETCH를 보내는 Kotlin 클라이언트 라이브러리는 미작성. ticket-command/query를 MyKafka에 붙일 때 `MyKafka/src/main/kotlin/com/example/mykafka/client/` 아래에 producer/consumer 추가 예정.

---

## 4. ticket-command-service

### 4.1 책임
- Event 등록 (예정), SeatSection/Seat 시드 (시드 SQL 또는 admin API)
- **Reservation 생성** (좌석 잠금)
- **Reservation 확정** (결제 완료 → seat status SOLD)
- **Reservation 취소** (좌석 풀기)

### 4.2 도메인 모델
```
Event ─┬─ id, title, subtitle, artist, venue, startsAt, doorsOpenAt
SeatSection ─┬─ id, eventId, name, korName, price, color
Seat ─┬─ id, sectionId, rowLabel, seatNumber, status, version
       └─ status: AVAILABLE | RESERVED | SOLD
       └─ @Version (optimistic) + 쿼리에서 PESSIMISTIC_WRITE 락
Reservation ─┬─ id, userId, seatId, status, createdAt
              └─ status: PENDING | CONFIRMED | CANCELLED
```

### 4.3 상태 전이
```
Seat:        AVAILABLE ──reserve──> RESERVED ──confirm──> SOLD
                  ▲                     │
                  └──── cancel ─────────┘

Reservation: (new) ──reserve──> PENDING ──confirm──> CONFIRMED
                                    │
                                    └── cancel ──> CANCELLED
```

### 4.4 동시성 제어
`SeatRepository.findAllByIdIn` 에 `@Lock(PESSIMISTIC_WRITE)` 적용 + `SeatEntity.@Version`.

- 같은 좌석을 동시에 두 명이 누르면 한 쪽 트랜잭션이 `SELECT ... FOR UPDATE` 에서 대기하다가, 먼저 끝낸 쪽이 status=RESERVED로 바꾼 뒤 후속 쪽은 "available 아님" 검사에 걸려서 `SEAT_NOT_AVAILABLE` 409 반환.
- **학습 포인트**: 비관락은 단일 DB에서는 단순하지만 트래픽이 커지면 DB가 병목. 다음 단계로 Redis 분산락 → Kafka 큐잉(좌석을 partition key로 같은 broker로 라우팅) 순으로 진화할 수 있는 지점.

### 4.5 REST API
| Method | Path | Body | Status |
|--------|------|------|--------|
| POST | `/reservations` | `{userId, seatIds[]}` | 201 / 409 |
| POST | `/reservations/{id}/confirm` | - | 200 / 404 / 409 |
| DELETE | `/reservations/{id}` | - | 200 / 404 |

---

## 5. ticket-query-service

### 5.1 책임
조회 전용. **쓰기 API 없음.** 같은 `ticket_db` 스키마를 읽기만 함.

### 5.2 의도적 설계
- 같은 4개 엔티티를 `com.example.ticketquery.entity` 패키지에 **중복 정의** — 추후 read model로 갈라설 여지를 남김 (CQRS의 점진적 분리).
- `ddl-auto: validate` — query는 스키마 변경 권한 없음. command가 먼저 스키마 만들고 query는 검증만.
- `spring.datasource.hikari.read-only: true` — HikariCP 자체를 read-only로 표시. **DB Primary/Replica 분리 시 datasource URL만 갈아끼우면 자연스럽게 read replica로 향함.**
- 모든 `@Service`에 `@Transactional(readOnly = true)` — 읽기 전용 트랜잭션 (커밋 비용 ↓, replica 라우팅 힌트로도 활용 가능).

### 5.3 REST API
| Method | Path | 반환 |
|--------|------|------|
| GET | `/events` | EventView[] |
| GET | `/events/{eventId}` | EventView |
| GET | `/events/{eventId}/seats` | SeatMapView (section → seat 트리) |
| GET | `/reservations/{id}` | ReservationView |
| GET | `/reservations?userId=...` | ReservationView[] |

---

## 6. 데이터 흐름 — 현재 vs 목표

### 6.1 현재 (MyKafka 미연결, 단일 DB)
```
유저 → frontend → gateway → ticket-command → ticket_db (write)
                          ↘ ticket-query   → ticket_db (read, same DB)
```
- query가 같은 DB를 읽기만 함. write 직후 query에서 즉시 보임 (강한 일관성).
- 한계: 트래픽 늘면 같은 DB가 read/write 동시 부담.

### 6.2 1단계 목표: DB Primary/Replica
```
ticket-command → Primary  ──streaming replication──> Replica ← ticket-query
```
- Postgres streaming replication (async).
- query의 datasource host를 Replica로 변경하면 끝.
- 트레이드오프: **replica lag** — 방금 예약한 좌석이 조회에서 잠시 안 보일 수 있음. UI는 "조회는 약간 지연될 수 있음"을 가정한 설계 필요.

### 6.3 2단계 목표: MyKafka 이벤트 발행
```
ticket-command ──PRODUCE──> MyKafka (topic: reservation-events)
                              │
                              └──FETCH──> ticket-query (read model 별도 테이블 갱신)
```
- command는 DB 커밋 후 `ReservationCreated`, `ReservationConfirmed`, `ReservationCancelled` 이벤트 발행.
- query는 자체 read model 테이블(seat 상태 캐시, 사용자별 예약 목록 등)을 consumer로 갱신.
- 이 시점에 query는 더이상 primary DB에 의존하지 않을 수 있음 (완전한 CQRS).
- **outbox 패턴** 학습 포인트: DB 커밋과 Kafka 발행의 원자성 보장.

---

## 7. 부팅 절차 (현재 단계)

### 7.1 한 번만 (환경 셋업)

#### A. Postgres 설치 — **사용자 직접**
```bash
brew install postgresql@16
brew services start postgresql@16

# 설치 확인
psql --version
psql -U postgres -c "SELECT version();"
```

#### B. `ticket_db` 데이터베이스 생성
```bash
psql -U postgres -c "CREATE DATABASE ticket_db;"
psql -U postgres -lqt | grep ticket_db   # 확인
```

#### C. `.env` 비밀번호 채우기
`ticket-command-service/.env` 와 `ticket-query-service/.env` 의 `DB_PASSWORD` 를 본인 Postgres 비밀번호로 수정.
비밀번호가 없는 경우 빈 값으로 두되, `DB_USERNAME` 도 본인 OS 사용자로 변경.

### 7.2 매번 (서비스 띄우기)

| 단계 | 명령 | 확인 |
|------|------|------|
| 1. command 첫 부팅 (스키마 자동 생성) | `cd ticket-command-service && ./gradlew bootRun` | 로그에 `Hibernate: create table events ...` 등 4개 테이블 생성 보임. 포트 8082 LISTEN. |
| 2. 시드 데이터 주입 | (별 터미널) `psql -U postgres -d ticket_db -f ticket-command-service/mock-data.sql` | `INSERT 0 1 / INSERT 0 3 / INSERT 0 60 / INSERT 0 120 / INSERT 0 112` |
| 3. query 부팅 | (별 터미널) `cd ticket-query-service && ./gradlew bootRun` | `ddl-auto: validate` 가 스키마 검증 → 통과해야 부팅됨. 포트 8083 LISTEN. |

### 7.3 동작 확인 (curl)

```bash
# 좌석맵 조회 (query)
curl -s http://localhost:8083/events/EVT2026-001/seats | jq '.sections[].id, (.sections[].seats | length)'
#   "S" 60  "R" 120  "A" 112

# 좌석 예약 (command)
curl -s -X POST http://localhost:8082/reservations \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","seatIds":["S-A-1","S-A-2"]}'
#   201 + Reservation 2개 (status: PENDING)

# 좌석 상태 변경 확인 (query) — 위 좌석이 RESERVED 로 보여야 함
curl -s http://localhost:8083/events/EVT2026-001/seats \
  | jq '.sections[] | select(.id=="S") | .seats[] | select(.id=="S-A-1" or .id=="S-A-2")'

# 같은 좌석 다시 예약 시도 (실패 기대)
curl -s -i -X POST http://localhost:8082/reservations \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u2","seatIds":["S-A-1"]}'
#   409 SEAT_NOT_AVAILABLE
```

위 4개가 다 기대대로 나오면 **CQRS 분리 골격 동작 검증 완료** — 다음 단계(부하 테스트)로 진행 가능.

### 7.4 이후 단계 (순서)
1. **부하 테스트** (k6) — 현재 동기 DB 구조의 한계 지점 측정
2. **MyKafka client SDK + Worker** — command가 Kafka publish, Worker가 비동기 DB INSERT
3. **DB Primary/Replica** — query datasource를 replica로
4. **gateway** JWT 검증 + URL 라우팅
5. **Queue API + Scheduler** — 대기열 진입 throttling (다이어그램 §1단계·§2단계)
6. Reservation `expiresAt` + 자동 만료 스케줄러

---

## 8. 디렉토리 구조

```
ticketing/
├── ARCHITECTURE.md            ← 이 문서
├── README.md                  ← 다이어그램 + 한 줄 소개
│
├── MyRedis/                   ← 직접 만든 Redis (committed)
├── MyKafka/                   ← 직접 만든 Kafka (untracked, MVP)
│   └── DESIGN.md              ← MyKafka 상세 설계
│
├── auth-service/              ← JWT 인증 (committed)
├── frontend/                  ← Next.js (committed)
│
├── gateway/                   ← 뼈대만 (untracked)
├── ticket-command-service/    ← 방금 골격 (untracked)
└── ticket-query-service/      ← 방금 골격 (untracked)
```

브랜치: `hhm`. ticket-command/query, MyKafka, gateway는 아직 커밋 전.
