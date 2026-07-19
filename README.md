# MyRedis — skiplist 기반 RESP 서버 (직접 구현)

> **Redis를 매니지드에 의존하지 않고 직접 구현.** Kotlin/Netty 기반 RESP 서버.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 핵심 — skiplist sorted set
- Redis `t_zset.c`를 포팅한 **skip list** 구현 (MAX_LEVEL=32, P=0.25)
- 각 노드에 `forward[]` + **`span[]`** → **ZRANK를 span 누적합으로 O(logN)** 계산
  (naive 배열/heap은 O(N))
- 지원 명령: `ZADD` / `ZRANK` / `ZCARD` / `ZSCORE` / `ZREM` / `ZPOPMIN` / `ZRANGE`

## 왜 직접 구현했나
대기열의 "내 순번 몇 번?"(ZRANK)이 진짜 병목인지, **skiplist가 왜 O(logN)인지**를 코드로 확인하기 위해.

## 실측
skiplist 단일연산 **166K rps** (real Redis 179K의 **93%**), ZRANK 결과 정확도 동일.

## 기술 스택
`Kotlin` · `Netty` · `RESP 프로토콜`
