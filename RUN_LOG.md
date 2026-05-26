# Run Log — 첫 부팅 walkthrough

ticket-command/query 두 서비스를 처음 부팅하고 동작 검증까지 진행한 실제 실행 기록.
모든 명령·출력·만난 문제·해결을 그대로 보존. 학습용 reference — 두 번째 부팅 때는 "Hiccup" 섹션은 더 이상 안 만남.

세션 환경
- macOS, USER=`hbrc`, brew 있음
- 처음 시작 시 시스템 JDK는 temurin-17만 있었음
- Postgres 미설치, 포트 5432/8082/8083 모두 비어있음

---

## §1. Postgres 설치 & 기동

### 1.1 설치
```bash
brew install postgresql@16
```
**핵심 출력 (caveats)**:
```
This formula has created a default database cluster with:
  initdb --locale=en_US.UTF-8 -E UTF-8 /opt/homebrew/var/postgresql@16
```
brew 설치는 자동으로 default 클러스터를 만들고, superuser는 OS 사용자명(`hbrc`)이 됨. 비밀번호 없음, peer/trust 인증.

### 1.2 서비스 시작 + 준비 대기
```bash
brew services start postgresql@16
PG=/opt/homebrew/opt/postgresql@16/bin
until "$PG/pg_isready" -h localhost -p 5432 -q; do sleep 1; done
"$PG/pg_isready" -h localhost -p 5432
```
**출력**:
```
==> Successfully started `postgresql@16` (label: homebrew.mxcl.postgresql@16)
ready after 1s
localhost:5432 - 접속을 받아드리는 중
```

### 1.3 `ticket_db` 생성
```bash
"$PG/createdb" -U hbrc ticket_db
"$PG/psql" -U hbrc -lqt | cut -d \| -f 1 | sed 's/^ *//;s/ *$//' | grep -v '^$'
```
**출력**:
```
postgres
template0
template1
ticket_db          ← 새로 생긴 것
```

### 1.4 `.env` 조정
brew 기본에 맞춰 두 서비스 모두 수정:
```diff
- DB_USERNAME=postgres
- DB_PASSWORD=postgres
+ DB_USERNAME=hbrc
+ DB_PASSWORD=
```
대상 파일:
- `ticket-command-service/.env`
- `ticket-query-service/.env`

---

## §2. Hiccup 1 — JDK 버전 충돌

### 2.1 첫 bootRun 시도 → 실패
```bash
cd ticket-command-service
./gradlew bootRun
```
**에러**:
```
오류: 기본 클래스 com.example.ticketcommand.TicketCommandApplicationKt을(를) 로드하는 중 LinkageError가 발생했습니다.
  java.lang.UnsupportedClassVersionError: com/example/ticketcommand/TicketCommandApplicationKt
  has been compiled by a more recent version of the Java Runtime
  (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0
```
**원인**: `build.gradle.kts` 가 `JvmTarget.JVM_21` (bytecode 65.0)로 컴파일했는데 Gradle daemon이 시스템 JAVA_HOME(JDK 17, bytecode 61.0)으로 실행 시도.

### 2.2 toolchain 추가 시도 → 실패
`build.gradle.kts` 두 곳에 추가:
```kotlin
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```
**다시 bootRun → 새 에러**:
```
Cannot find a Java installation on your machine (Mac OS X 26.3 aarch64)
matching: {languageVersion=21, vendor=any vendor, ...}.
Toolchain download repositories have not been configured.
```
**원인**: Gradle toolchain auto-provisioning은 기본 비활성. Foojay 플러그인을 `settings.gradle.kts` 에 추가해야 자동 다운로드 가능.

### 2.3 해결 — JDK 21 직접 설치 + JAVA_HOME 지정
```bash
brew install openjdk@21
```
**caveats (중요)**:
```
openjdk@21 is keg-only, which means it was not symlinked into /opt/homebrew,
because this is an alternate version of another formula.

If you need to have openjdk@21 first in your PATH, run:
  echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
```
keg-only 라 PATH 자동 등록 안 됨 → `JAVA_HOME` 을 명시해서 실행.

**검증**:
```bash
/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -version
# openjdk version "21.0.11" 2026-04-21
```

---

## §3. ticket-command 부팅 (성공)

```bash
cd ticket-command-service
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun
```
백그라운드로 띄우고 포트 LISTEN까지 polling:
```bash
until lsof -i :8082 -sTCP:LISTEN >/dev/null 2>&1; do sleep 2; done
```
**확인**: 포트 8082 LISTEN.

**자동 생성된 스키마** (`ddl-auto: update`로 Hibernate가 생성):
- `events`
- `seat_sections`
- `seats`
- `reservations`

---

## §4. 시드 데이터 주입

```bash
PG=/opt/homebrew/opt/postgresql@16/bin
"$PG/psql" -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql
```
**출력**:
```
BEGIN
TRUNCATE TABLE
INSERT 0 1      ← Event 1개 (EVT2026-001)
INSERT 0 3      ← SeatSection 3개 (S/R/A)
INSERT 0 60     ← S석 (A/B/C × 20)
INSERT 0 120    ← R석 (A/B/C/D/E × 24)
INSERT 0 112    ← A석 (A/B/C/D × 28)
COMMIT
```

### 4.1 카운트 검증 쿼리
```sql
SELECT (SELECT COUNT(*) FROM events)        AS events,
       (SELECT COUNT(*) FROM seat_sections) AS sections,
       (SELECT COUNT(*) FROM seats)         AS seats;
```
**결과**:
```
 events | sections | seats 
--------+----------+-------
      1 |        3 |   292
```

### 4.2 섹션별 분포
```sql
SELECT section_id, COUNT(*) FROM seats GROUP BY section_id ORDER BY section_id;
```
**결과**:
```
 section_id | count 
------------+-------
 A          |   112
 R          |   120
 S          |    60
```
(총합 292 = 112+120+60. frontend `mockSections` 와 정확히 일치.)

---

## §5. ticket-query 부팅 (성공)

```bash
cd ticket-query-service
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun
```
**확인**: 포트 8083 LISTEN.

`ddl-auto: validate` 라 만약 엔티티 정의가 command 쪽이 만든 실제 스키마와 다르면 여기서 부팅이 실패해야 함 → 통과한 것 = command/query 엔티티 정의가 완벽히 일치한다는 검증.

---

## §6. curl 4발 검증

### [1] GET /events/EVT2026-001/seats — 좌석맵
```bash
curl -s http://localhost:8083/events/EVT2026-001/seats
```
**파싱 결과**:
```
S: 60 seats, statuses={'AVAILABLE'}
R: 120 seats, statuses={'AVAILABLE'}
A: 112 seats, statuses={'AVAILABLE'}
```
의미: 모든 좌석 AVAILABLE, 카운트도 시드와 일치.

---

### [2] POST /reservations — 좌석 2개 예약
```bash
curl -s -X POST http://localhost:8082/reservations \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","seatIds":["S-A-1","S-A-2"]}'
```
**응답** (HTTP 201):
```json
[
  {
    "id":"8e3b782d-c119-4907-897f-2fab088ab74c",
    "userId":"u1",
    "seatId":"S-A-1",
    "status":"PENDING",
    "createdAt":"2026-05-24T07:17:20.913560Z"
  },
  {
    "id":"6daaa244-5ff2-4e5b-b8b3-bf6df10263fa",
    "userId":"u1",
    "seatId":"S-A-2",
    "status":"PENDING",
    "createdAt":"2026-05-24T07:17:20.913589Z"
  }
]
```
의미:
- 좌석 N개 동시 예약 → Reservation N개 생성 (1:1)
- 상태는 PENDING (아직 결제 전)
- ID는 UUID, createdAt은 Instant (UTC)

---

### [3] GET 다시 — 변경 확인 (CQRS 일관성 체크)
```bash
curl -s http://localhost:8083/events/EVT2026-001/seats | jq '.sections[]
  | select(.id=="S") | .seats[]
  | select(.id=="S-A-1" or .id=="S-A-2" or .id=="S-A-3")'
```
**결과**:
```
S-A-1: RESERVED
S-A-2: RESERVED
S-A-3: AVAILABLE
```
의미: command가 POST로 RESERVED 만든 걸 query가 **즉시** 본다. 같은 DB라 강한 일관성. Replica 분리하면 여기에 lag 등장 예정.

---

### [4] POST 같은 좌석 재예약 — 409 기대
```bash
curl -i -X POST http://localhost:8082/reservations \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u2","seatIds":["S-A-1"]}'
```
**응답**:
```
HTTP 409
{"error":"SEAT_NOT_AVAILABLE","message":"Seats not available: [S-A-1]"}
```
의미: 비관락(`@Lock(PESSIMISTIC_WRITE)`) + 서비스의 status 검증이 동작. 다른 사용자가 같은 좌석을 잡지 못함.

---

## §7. 최종 상태 (검증 직후)

### 떠있는 프로세스
| 서비스 | 포트 | 비고 |
|--------|------|------|
| Postgres 16 | 5432 | brew services, 자동 재시작 |
| ticket-command | 8082 | JDK 21 (JAVA_HOME 지정), bootRun foreground in BG task |
| ticket-query | 8083 | 위와 동일 |

### DB 데이터 상태
- `events`: 1
- `seat_sections`: 3 (S/R/A)
- `seats`: 292 (그 중 S-A-1, S-A-2 → RESERVED. 나머지 290개 AVAILABLE)
- `reservations`: 2 (둘 다 userId=`u1`, status=PENDING)

### 셸 환경 메모
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 를 매번 줘야 함.
- 영구화하려면 `~/.zshrc` 에:
  ```bash
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"
  ```
- 단, `auth-service` 도 동일 문제를 겪을 것 — 이미 21 가정으로 짜였으므로.

---

## §8. 다음 부팅 때는 (cheat sheet)

처음 한 번이 끝났으니 이후 사이클은 짧다:

```bash
# 0) 환경 (현재 셸에 안 잡혀있다면)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/opt/postgresql@16/bin:$PATH

# 1) command (터미널 1)
cd ticket-command-service && ./gradlew bootRun

# 2) (선택) 시드 초기화 — 멱등이라 언제든 OK
psql -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql

# 3) query (터미널 2)
cd ticket-query-service && ./gradlew bootRun
```

DB도 재시작 안 해도 됨 (`brew services` 가 자동 재시작).

---

## §9. 이 walkthrough에서 얻은 학습 포인트

이번 부팅에서 의도하지 않게 마주친 것 (LEARNING_LOG §10에도 반영해도 좋음):

1. **Kotlin은 시스템 JDK보다 높은 jvmTarget으로 컴파일 가능, 실행은 불가** — bytecode와 runtime 분리. `class file version 65.0` 같은 숫자가 자바 21에 해당함을 외워두면 디버깅이 빠름.
2. **Gradle toolchain ≠ JDK 자동 설치**. `languageVersion(21)` 만으로는 다운로드 안 함. Foojay plugin이 있어야 함. 안 쓰려면 OS에 JDK 깔고 `JAVA_HOME` 지정.
3. **brew의 keg-only 패키지**는 PATH에 자동 등록 안 됨 (`openjdk@21`, `postgresql@16` 둘 다). 절대경로나 `JAVA_HOME` 명시 필요. `brew --prefix openjdk@21` 으로 경로 확인 가능.
4. **`ddl-auto: validate` 의 실용성**: 두 서비스의 엔티티 정의가 어긋나면 query 부팅이 거부됨. 이게 **무료로 얻는 CI** 역할 — 엔티티 한쪽 고치고 다른 쪽 안 고치면 즉시 발견.
5. **CQRS의 강한 일관성 단계**: 같은 DB를 공유할 때는 write 즉시 read 가능. 이걸 기준선으로 잡고 나중에 replica 도입하면 처음으로 **eventual consistency** 의 lag를 체감하게 됨.
