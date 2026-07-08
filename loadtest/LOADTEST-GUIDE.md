# 부하테스트 가이드 — 시나리오 특성 + 실시간 파이프라인

티켓팅 시스템 부하테스트 6종의 **특성**과, 이를 실시간으로 보는 **파이프라인 뷰(webctl)** 의 측정 방식 정리.

- 실행: `python3 loadtest/webctl/server.py` → http://localhost:8090 (버튼 클릭)
- 개별 실행: `k6 run loadtest/scenario-<이름>.js`
- 관련: [monitoring/GUIDE.md](../monitoring/GUIDE.md)(Grafana/Jaeger), RUN_LOG §18~21

---

## 1. 부하 시나리오 6종 — 무엇을 어떻게 테스트하나

| # | 시나리오 | 테스트 대상 | 부하 방식 | 실무 의미 | 파이프라인 흐름 |
|---|---|---|---|---|---|
| ① | **대기열 폭주** `queue-100k` | queue `enter` + Redis | ramping 500→6000/s, **enter만** | 예매 오픈 **순간 폭주(burst) 흡수** | `Queue` 노드만 (13만 폭증) |
| ⑥ | **대기열 순환** `queue-realistic` | queue **admit/release 처리량** | enter→입장→예약→**release** 순환 | 대기열 **처리 속도·대기시간 SLA** | `Queue` 노드 (평형) |
| ② | **예약 e2e** `segment-B-booking` | booking→카프카→query **CQRS 수렴** | shared-iter 120 (좌석 distinct) | 예약·결제 → 조회 반영까지 | `booking→kafka→query→read_db` |
| ③ | **병목 사냥** `staged-ramp` | ticket-command **처리 한계** | ramping 1000→**6000 RPS** | 병목(CPU/DB/풀) 찾기 | `command→ticket_db` (포화) |
| ④ | **정량 10만** `load-100k` | ticket-command **쓰기 처리량** | shared-iter **정확히 10만** | 순수 쓰기량·좌석경쟁(409) | `command→ticket_db` |
| ⑤ | **게이트웨이 mesh** `gateway-mesh` | **전 서비스 관통** | constant 150/s, 3갈래 | e2e 관통·서비스맵 | **전 노드** (gateway→…→RDB) |

### 시나리오별 핵심 포인트

**① 대기열 폭주** — enter는 Redis `zadd`(줄 세우기)만이라 가벼움. 13만 명 몰려도 **0% 실패**로 흡수, 무거운 예약 로직은 slot 걸린 소수만. **"폭주 받아내기"는 재지만 처리량은 못 잼**(소비 없음).

**⑥ 대기열 순환** — ①의 한계를 보완. enter→입장→release 순환으로 실제 처리량 측정.
- **발견**: slot을 5→40으로 늘려도 admit 2초 배치면 큐 적체(15/s). **admit 주기를 200ms로 줄이니 평형(19/s)**. → **대기열 처리량의 진짜 레버는 slot이 아니라 admit 주기.**
- 튜닝: `QUEUE_MAX_ACTIVE_CAP`(slot), `QUEUE_ADMIT_INTERVAL_MS`(admit 주기) env.

**② 예약 e2e** — gateway→booking confirm→**MyKafka 발행**→ticket-query 소비→read DB. 카프카 건너 ~400ms 갭 = **CQRS eventual consistency**. 좌석 292 제약으로 지속 발행은 한정.
- **발견(수정됨)**: booking 단일소켓 동시성(Broken pipe) + dual-write(checked exception 롤백 안 됨). → synchronized + `@Transactional(rollbackFor)`로 수정, 수렴 lag 116→0.

**③ 병목 사냥** — 6000 RPS까지 밀어 병목 찾기.
- **발견**: p99 폭증·dropped 증가. outbox relay(발행), HikariCP 풀은 무죄, 진짜 천장은 **단일 머신 CPU 포화**(load>코어). + 트레이싱으로 **outbox `SELECT WHERE status='PENDING'` 조회(인덱스 부재)**가 최대 지연으로 드러남.

**④ 정량 10만** — 정확히 10만 회. 실패 35%는 **좌석충돌 409**(120석에 200VU) = 정상, 5xx 아님.

**⑤ 게이트웨이 mesh** — 요청을 3갈래(queue/command/booking)로 나눠 전 서비스 관통. **서비스맵(Jaeger) + 파이프라인 전 노드 흐름**용. booking confirm은 좌석 리셋 병행 시 카프카→query→RDB까지 지속.

---

## 2. 실시간 파이프라인 뷰 (webctl) — 어떻게 측정해 화면과 연결했나

### 데이터 흐름 (5단계)
```
① 실제 트래픽:  k6 → gateway → queue/booking/command → 카프카 → ticket-query → RDB
                    ↓ (micrometer/OTel이 요청을 메트릭으로 기록)
② 수집:         Prometheus(:9090) ← 각 서비스 /actuator/prometheus 2초 스크랩
                자체 exporter(:9105) ← Redis/Postgres 폴링(큐깊이·수렴lag)
                    ↓ (PromQL / redis-cli / sysctl)
③ 백엔드:       server.py  /api/topology  — 노드·엣지 RPS 계산 → JSON
                    ↓ (2초 폴링 fetch)
④ 프론트:       index.html — 받은 RPS로 애니메이션 파라미터 갱신
                    ↓ (requestAnimationFrame 60fps)
⑤ 화면:         canvas — RPS 비례 입자 스폰 + 노드 크기/색
```

### 측정 소스 3가지
| 지표 | 소스 | 방법 |
|---|---|---|
| 노드·엣지 RPS | Prometheus | `rate(http_server_requests_seconds_count{svc="X"}[20s])` |
| 큐 깊이 | Redis | `redis-cli ZCARD queue:sorted:...` |
| 수렴 lag·outbox | 자체 exporter | `ticketing_convergence_lag` 등 |
| CPU load | OS | `sysctl vm.loadavg` |

### 백엔드 (server.py)
- Python 표준 라이브러리(의존 0). `/api/topology`가 매 호출마다 Prometheus에 PromQL을 던져 서비스별 RPS를 받고, 노드(부하)+엣지(흐름)로 재구성.
- 엣지 예: `gateway→queue`=queue 유입 RPS, `booking→kafka`=booking **confirm 요청 rate**(actuator; gauge는 좌석 리셋에 취약해 요청수로).

### 프론트 (index.html)
- **연결 = 2초 폴링**(WebSocket 아님): `setInterval(poll, 2000)` → `/api/topology`.
- **애니메이션 = 60fps 자체 루프**: `requestAnimationFrame`. 입자 수 ∝ RPS(`rps/220`/프레임), 노드 크기 ∝ RPS, 색 ∝ p95 지연.

### 정직한 근사 (한계)
- **입자는 개별 요청이 아님** — RPS 비례 확률적 시각 표현. 개별 요청 추적은 Jaeger(trace).
- **엣지 RPS는 "목적지 유입"으로 근사** — `gateway→queue`는 queue 전체 유입(직접 호출 포함).
- **카프카 소비(kafka→query)는 actuator가 없어**(폴링) booking confirm rate로 근사(비동기라 시간차 있으나 처리량 동일).

### 한 줄 요약
**메트릭을 실시간 폴링해 "흐르는 파이프라인"으로 번역** — Grafana가 같은 메트릭을 그래프로 그린다면, 이건 흐름 애니메이션으로 그린 것. **분석은 Grafana/Jaeger, 흐름 감상은 파이프라인 뷰**로 역할 분담.
