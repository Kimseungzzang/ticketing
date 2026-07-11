# queue-service 부하/정합성 실측 로그

> 실측 원칙: 부풀리기 없이 실제 측정값만 기록. 측정 조건·한계를 함께 명시한다.

---

## 2026-07-11 — 다중화 3대 + 낙관적 락 + TTL 자동 정리

### 측정 환경 (그대로 재현 가능하도록)

| 항목 | 값 |
|---|---|
| 머신 | 단일 Mac (Darwin 25.3.0, arm64) — **k6·queue 3대·Redis·nginx 모두 같은 머신** |
| Redis | localhost real Redis **8.6.2** standalone (Lettuce 클라이언트) |
| queue-service | **3 프로세스** (:8085/:8086/:8087), commit `1ace8ba` |
| — 동시성 | admit = **낙관적 락**(ZPOPMIN 확보 → ZADD 선점 → ZCARD 초과 시 롤백) |
| — 활성 관리 | `active:z` sorted set(score=만료시각), admit 전 `ZREMRANGEBYSCORE`로 이탈 회수 |
| — slot | `QUEUE_MAX_ACTIVE_CAP=100` |
| 로드밸런서 | nginx **L4**(stream) :8100, `least_conn` 3대 분산 |
| 부하생성기 | k6 v2.0.0 |

> ⚠️ **한계 명시**: 부하생성기(k6)와 서버(queue 3대·Redis·nginx)가 **동일 머신에서 CPU를 공유**한다.
> 따라서 아래 수치는 "서버의 처리량 천장"이 아니라 "이 구성에서 관측된 값"이다.
> 진짜 처리량 한계는 부하생성기와 서버를 물리 분리한 뒤 재측정해야 한다.

### ① 다중화 정합성 — active ≤ slot (낙관적 락)

- **방법**: 큐(`EVT-LT`)에 1,000명 pre-fill → 3대가 admit 스케줄러(2s)로 동시에 빼감 → `ZCARD active:z` 0.6s 간격 샘플링.
- **결과**:
  - active 추이: `0 → 47 → 100 → 100 …` — **정확히 slot(100)에서 정지**, 큐 잔여 900.
  - **최대 관측 active = 100, slot 초과 0회.** (낙관적 락 없으면 3대가 각자 빼서 최대 300까지 가능했음)
  - admit 분산(한 라운드): :8085 **47건** + :8086 **53건** = 100. (두 인스턴스가 동시에 나눠 처리)
- **주의(정직)**: admit 분산은 **선착순 경쟁**이라 균등하지 않다. slot을 먼저 채운 인스턴스가 대부분 처리하고, 늦게 도는 인스턴스(:8087)는 slot이 이미 차 admit을 거의 못 했다. **정합성(초과 0)은 완벽하지만 admit 부하 균등 분산은 아님.** (읽기=status 폴링은 nginx least_conn으로 3대 균등)

### TTL 자동 정리 — 이탈 유저 slot 회수

- **방법**: active 100 상태에서 만료된(score=1=먼 과거) 가짜 유저 `stale-ghost`를 `active:z`에 주입(→101) → 큐에 신규 유저 추가로 admit 트리거.
- **결과**: admit 진입 시 `ZREMRANGEBYSCORE(-inf, now)`가 실행돼 **`stale-ghost` 자동 제거 → active 100 복귀**. release 없이 이탈한 유저의 slot이 자동 회수됨을 확인.

### ② 단계별 부하 — status 폴링(ZRANK) 500→1000→2000→4000 rps

- **방법**: nginx :8100 경유, 큐 5,000명 pre-fill 상태에서 status 폴링, 45초(15s×3 stage). 2회 측정(일관).

| 지표 | 측정1 | 측정2 |
|---|---|---|
| 총 요청 | 78,727 | 78,735 |
| 평균 RPS | 1,749 | 1,750 *(계단 평균; 마지막 stage 4,000rps 도달)* |
| **에러율(http_req_failed)** | **0.00%** | **0.00%** (0/78,735) |
| latency avg | 0.37 ms | 0.34 ms |
| latency p90 | — | 0.45 ms |
| latency p95 | 0.62 ms | **0.50 ms** |
| latency p99 | — | **0.79 ms** |
| latency max | 82.27 ms | 23.84 ms |
| 부하 중 active | 최대 100 | 최대 100 *(정합성 유지)* |

- **해석(정직)**:
  - **에러율 0%** — 이전에 보이던 Broken pipe(과부하 신호)가 이 부하 수준에선 없음. 서버가 여유.
  - p95 0.5ms / p99 0.79ms로 매우 낮은 건 **① status=ZRANK가 마이크로초 연산 + ② 3대 분산 + ③ 4,000rps는 아직 저부하**이기 때문. **이 수치를 "고성능"으로 과장하면 안 된다.** 서버 천장을 보려면 rps를 더 올리고(현재 failed 0%라 여유 있음) 부하생성기를 분리해야 한다.
  - max 82ms(측정1)는 초기 warmup/JIT/GC 스파이크로 추정. 재측정(측정2)에선 23.84ms로 내려감.

### 검증된 것 / 남은 것

- ✅ 다중화 3대 동시 admit에도 **active ≤ slot** (낙관적 락 정합성)
- ✅ 이탈 유저 slot **TTL 자동 회수** (ZREMRANGEBYSCORE)
- ✅ 폴링 부하 4,000rps에서 **에러율 0%**, p99 < 1ms
- ⏳ 서버 처리량 천장: 부하생성기 분리 + 더 높은 rps로 재측정 필요 (현재는 저부하 구간)
- ⏳ admit 부하 균등 분산: 선착순이라 불균등 — 필요 시 인스턴스별 admit 배치 상한 등 검토
