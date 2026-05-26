# Load Test Log

부하 테스트 결과를 시계열로 누적하는 문서. 각 측정마다 §번호 + 날짜로 항목 추가.
**비교 가능성**이 핵심 — 같은 환경에서 변수 하나 바꾸고 다시 측정해서 효과를 본다.

## 환경 기준선 (모든 측정의 공통 전제)

| 항목 | 값 |
|------|-----|
| 머신 | macOS, Apple Silicon (arm64) |
| JDK | OpenJDK 21.0.11 (brew openjdk@21) |
| Postgres | 16.14 (brew postgresql@16, local 5432) |
| Spring Boot | 4.0.6 |
| Kotlin | 2.2.21 |
| k6 | v2.0.0 |
| ticket-command 포트 | 8082 |
| ticket-query 포트 | 8083 |
| ticket_db 시드 | Event 1 / Section 3 / Seat 292 (S=60, R=120, A=112) |

환경 바뀌면 (예: Worker 도입, DB replica 분리) §X에서 명시.

---

## §1. 시나리오 A — Baseline (경쟁 없음)

**날짜**: 2026-05-24
**구조 단계**: 단일 DB · command 동기 쓰기 · Worker 없음 · Replica 없음 (초기 상태)
**스크립트**: `loadtest/scenario-A-distinct-seats.js`

### 1.1 시나리오 정의
- 100 VU × **서로 다른 좌석** 1회씩 동시 POST `/reservations`
- 좌석 풀: `R-A-1` ~ `R-E-24` (R 섹션 120석 중 앞 100개)
- VU별 좌석 매핑: `R-{ROWS[(VU-1)/24]}-{((VU-1)%24)+1}`
- 비관락(`PESSIMISTIC_WRITE`)이 실제로 잡힐 일은 없음 — 깨끗한 케이스에서 처리량 측정

### 1.2 실행 명령
```bash
# 시드 reset (이전 RESERVED 좌석 원복)
psql -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql

# 시드 검증
psql -U hbrc -d ticket_db -c "SELECT status, COUNT(*) FROM seats GROUP BY status;"
#   AVAILABLE | 292

# k6 실행
k6 run loadtest/scenario-A-distinct-seats.js
```

### 1.3 결과 (raw 핵심)

```
█ THRESHOLDS 
  checks            ✓ 'rate>0.99'   rate=100.00%
  http_req_duration ✓ 'p(95)<500'   p(95)=78.56ms
  http_req_failed   ✓ 'rate<0.01'   rate=0.00%

█ TOTAL RESULTS 
  checks_total ......: 200      2413.88/s   (status 201 + has id, 100 iter × 2 check)
  checks_succeeded ..: 100.00%
  http_req_duration  : avg=66.88ms  min=52.92  med=67.34  max=79.3   p90=77.35  p95=78.56
  http_req_failed    : 0.00%     (0/100)
  http_reqs          : 100       1206.94/s
  iteration_duration : avg=69.08ms  min=55.08  med=69.57  max=81.87  p90=79.53  p95=80.63
  data_received      : 28 kB     333 kB/s
  data_sent          : 18 kB     219 kB/s
running (00m00.1s), 000/100 VUs, 100 complete and 0 interrupted iterations
```

### 1.4 DB 사후 상태
```sql
SELECT status, COUNT(*) FROM seats GROUP BY status;
--  RESERVED  | 100      ← 정확히 시도한 만큼
--  AVAILABLE | 192

SELECT status, COUNT(*) FROM reservations GROUP BY status;
--  PENDING   | 100
```
**검증**: 누락 없음. 100건 POST → 100 RESERVED → 100 Reservation(PENDING). 1:1:1.

### 1.5 해석

#### 잘 나온 것
- **에러율 0%, 100% 성공.** 비관락 + JPA 정상 동작 확인. Spring Boot 동시 100 요청에 안 무너짐.
- **p95 78.56ms.** 첫 부팅 직후라 JVM warm-up 안 됐는데도 한 자리수 ms 단위 안정.
- **분포가 좁다** (min 52.92 ~ max 79.3, ±15ms 정도). 운영 환경의 long-tail 같은 outlier 없음.

#### 한계 (의식하기)
1. **0.083초 만에 끝났다.** 100 VU가 1회씩 → 단일 burst. RPS 1206은 **burst 처리량**이지 지속 처리량 아님. 지속 RPS·tomcat thread pool warm-up·HikariCP 동작·GC pause 등은 미반영.
2. **경쟁이 없음.** unique seat이라 비관락이 "잡히긴 하는데 대기는 안 함". `SELECT ... FOR UPDATE`가 진짜 일할 때(같은 row 경쟁) latency가 어떻게 변하는지는 §2(시나리오 B)에서 측정 예정.
3. **DB가 거의 idle.** CPU·active connections·lock waits 다 안 봄. 다음 측정 때는 `pg_stat_activity` snapshot 같이 캡처.

#### 학습 포인트
- 첫 baseline의 역할은 "**무너지지 않는다**" 와 "**숫자의 기준**" 두 가지. 두 번째 측정(B)이 이것과 비교될 때만 의미가 생긴다.
- "p95 78ms 잘 나왔네" 는 결과지 학습이 아님. **"같은 환경에서 X를 바꾸니 p95가 Y로 변하더라"** 가 학습.

### 1.6 비교 anchors (이후 측정에서 참조)
| 메트릭 | 시나리오 A 값 |
|--------|--------------|
| p50 latency | 67.34ms |
| p95 latency | 78.56ms |
| max latency | 79.3ms |
| 에러율 | 0% |
| 누락 | 0건 |
| burst throughput | ≈1200 RPS |

### 1.7 다음 측정 후보 (학습 가치 순)
1. **시나리오 B (경쟁)** — 100 VU × 같은 10 좌석. 정확도 검증(정확히 10명 성공) + 비관락 직격 latency.
2. **시나리오 A2 (duration 기반)** — 30초 지속, 좌석 cycle. warm-up + 지속 RPS.
3. **시나리오 C (점진 증가)** — 10 → 500 VU. knee point.
4. **시나리오 D (혼합)** — 75% 조회 + 25% 쓰기. query 부하.

---

---

## §2. 시나리오 B — 경쟁 (100 VU × 같은 10좌석)

**날짜**: 2026-05-26
**구조 단계**: §1과 동일 (단일 DB · command 동기 쓰기)
**스크립트**: `loadtest/scenario-B-contested-seats.js`

### 2.1 시나리오 정의
- 100 VU × 같은 좌석 풀(10개) 1회씩 동시 POST
- 좌석 풀: `A-A-1` ~ `A-A-10` (좌석당 정확히 10명이 경쟁)
- VU 매핑: `A-A-${((__VU-1) % 10) + 1}` — 결정론적, 분포 균등
- 비관락(`PESSIMISTIC_WRITE`)이 진짜 잡힐 케이스
- §1과의 유일한 의도 변수: **경쟁 추가** (좌석 unique → 좌석당 10명)

### 2.2 실행 명령
```bash
psql -U hbrc -d ticket_db -f ticket-command-service/mock-data.sql
k6 run loadtest/scenario-B-contested-seats.js
```

### 2.3 결과 (raw 핵심)
```
█ THRESHOLDS 
  checks                ✓ rate=100.00%
  http_req_duration     ✓ p(95)=61.93ms       (threshold p95<2000)
  http_req_failed       ✓ rate=0.00%
  reservations_success  ✓ count=10            ← 정확성 검증 핵심
  reservations_conflict ✓ count=90
  reservations_other    ✓ count=0

█ TOTAL RESULTS 
  http_req_duration : avg=54.78ms  min=46.2  med=55.06  max=62.49  p90=61.03  p95=61.93
  http_reqs         : 100  1436.43/s
  reservations_success  : 10
  reservations_conflict : 90
running (00m00.1s)
```

### 2.4 DB 사후 상태
```
section_id | status    | count
A          | AVAILABLE | 102      ← 112 - 10
A          | RESERVED  | 10       ← 정확히 10
R          | AVAILABLE | 120      ← 영향 없음
S          | AVAILABLE | 60       ← 영향 없음
```
A-A-1 ~ A-A-10 만 RESERVED, A-A-11 부터는 AVAILABLE 유지. **다른 섹션 미오염**.
`reservations`: 정확히 10 PENDING.

### 2.5 §1 anchors 와 비교

| 지표 | §1 A (경쟁 X) | §2 B (10좌석 경쟁) | Δ |
|------|---------------|--------------------|------|
| p50 latency | 67.34ms | **55.06ms** | **-12.3ms ↓** |
| p95 latency | 78.56ms | **61.93ms** | **-16.6ms ↓** |
| max latency | 79.3ms  | **62.49ms** | **-16.8ms ↓** |
| avg latency | 66.88ms | **54.78ms** | **-12.1ms ↓** |
| 에러율 | 0% | 0% (4xx/5xx 외) | = |
| burst RPS | 1206 | 1436 | +230 |
| 누락 | 0 | 0 | = |

### 2.6 해석 — 직관에 반한 결과

**경쟁이 추가됐는데 latency가 줄었다.** 보통 락 대기가 들어가면 늘어나야 함.

#### 가능한 원인 (순서대로 의심 가는 것)
1. **JVM JIT warm-up** ← 가장 유력
   - §1 실행 시점에 JVM은 cold start 직후, 코드 path가 interpreter mode 또는 C1 컴파일 단계.
   - §1 완료 후 §2 실행까지 사이에 JIT(C2)가 hot path(예약 처리 전체 코드)를 native로 컴파일.
   - 락 추가의 비용 < JIT optimization 이득.
2. **응답 바디 크기 차이**
   - §1: 100건 모두 201 + Reservation JSON (id, UUID, timestamp 포함, 200~300 bytes)
   - §2: 10건만 201, 90건은 409 + 짧은 에러 JSON (~80 bytes)
   - 평균 전송량 감소 → avg latency 감소 (네트워크는 localhost라 무시 가능하지만 직렬화 비용은 있음)
3. **락 자체가 너무 짧음**
   - 좌석 1개 SELECT FOR UPDATE → UPDATE 1행 → COMMIT. 트랜잭션 holding time이 ms 단위.
   - 좌석당 10명 경쟁이라 lock queue 깊이도 얕음.
4. **HikariCP 풀 warm-up**
   - 첫 측정 때 커넥션 신규 생성 비용 일부 발생, 두 번째 측정 때는 풀에서 즉시 재사용.

#### 무엇이 검증되었나
- ✅ **비관락 정확성**: 100명이 같은 10좌석에 동시에 던졌는데 **정확히 10명만** 성공. race condition 없음. 다른 섹션 미오염.
- ✅ **에러 응답 일관성**: 90건 모두 `409 SEAT_NOT_AVAILABLE` 메시지 형식 일관.
- ✅ **시스템 안정성**: max latency가 오히려 줄어듦. 락 대기로 인한 tail latency 폭증 없음.

#### 무엇이 의도와 어긋났나 — 학습
- **"한 번에 한 변수" 원칙 위반 발견.** §1과 §2 사이 의도된 유일 변수는 "경쟁 유무" 였지만, **JVM warm-up 이라는 숨은 변수**가 같이 들어옴.
- baseline §1 의 숫자는 **cold-start 측정**이었음을 사후에 깨달음. anchors §1.6 을 "warm-state baseline" 으로 재측정해야 §2 와 진짜 비교 가능.

### 2.7 다음 측정 후보 (학습 가치 순)
1. **§3 A-rerun** — 같은 시나리오 A를 한 번 더. 이번엔 warm 상태. §1과 비교해 "warm-up 효과만의 크기"를 분리해 측정.
2. **§4 B-극단** — 100 VU × 같은 **1개** 좌석. 락 큐 깊이 100. 비관락이 진짜 무너지는 지점.
3. **시나리오 C** — 점진 증가 (10 → 500 VU). knee point 탐색.
4. **JVM warmup runs 정식화** — 모든 후속 시나리오에 `--summary-trend-stats` + warmup phase 추가.

### 2.8 비교 anchors 업데이트
| 메트릭 | §1 A (cold) | §2 B (warm + 경쟁) |
|--------|-------------|--------------------|
| p50 | 67.34ms | 55.06ms |
| p95 | 78.56ms | 61.93ms |
| max | 79.3ms | 62.49ms |
| 누락 | 0 | 0 |
| 정확성 | N/A | 100% (10/10 의도된 좌석) |

---

## §N. (다음 측정 자리)

새 측정마다 위 §1 / §2 구조로 항목 추가.
**중요**: 환경이 바뀐 측정(예: Worker 도입 후)이면 §N 머리에 "구조 단계" 변화를 명시.
