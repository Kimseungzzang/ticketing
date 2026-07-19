# queue-service — 대기열 (낙관적 락 · 다중화)

> 티켓팅 MSA의 **대기열**. 유입을 완충하고 순번(sorted set)을 관리하며, 정원(slot)만큼만 입장(admit)시킨다.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 핵심

### ① 낙관적 락으로 정원 초과(oversell) 제거
다중 인스턴스가 동시에 admit할 때:
```
ZPOPMIN 확보 → ZADD 선점 → ZCARD가 slot 초과면 롤백(활성 제거 + 큐 복원)
```
- 분산락(리더 선출) → **낙관적 락**으로 진화: 리더 대기 없이 **병렬 admit**하면서도 정합성 보장

### ② TTL 자동 정리
- 활성 유저를 sorted set(score = 만료시각)으로 관리
- admit 전 **`ZREMRANGEBYSCORE`** 로 이탈(결제시간 초과) 유저의 slot 자동 회수

## 📊 부하 실측 → [`LOAD_TEST_LOG.md`](./LOAD_TEST_LOG.md)

**동시성 제어 Before/After** (3대 동시 admit, slot=100):

| 동시성 제어 | 최대 active | 결과 |
|---|---|---|
| 없음(naive) | 193 | +93 정원 초과 |
| **낙관적 락** | **100** | **초과 0** |

**단계별 부하** 100~1,000rps: 에러율 **0%**, p99 ≤ 3.2ms, 정합성(active ≤ slot) 유지.

## 기술 스택
`Kotlin` · `Spring Boot` · `Lettuce(Spring Data Redis)` · `k6`
