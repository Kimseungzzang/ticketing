# booking-service — 좌석 확정 (CQRS Write)

> 좌석 확정(write)을 처리하고 MyKafka `booking-events`를 발행. CQRS 쓰기 진입점.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 역할
- 좌석 확정 — Redis **분산락(SET NX)** 으로 동시 예약(중복 좌석) 방지
- 확정 시 **MyKafka `booking-events` 발행** → `ticket-query`가 소비해 read model 갱신 (CQRS)
- 발행 payload에 W3C `traceparent`를 실어 카프카 건너 분산추적 연결

## 기술 스택
`Kotlin` · `Spring Boot` · `PostgreSQL` · `Redis` · `MyKafka`
