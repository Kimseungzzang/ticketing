# ticket-query-service — 읽기 모델 (CQRS Read)

> 티켓팅 MSA의 **읽기 모델(Query)**. MyKafka `booking-events`를 소비해 read projection을 갱신한다.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 역할
- MyKafka **`booking-events` 컨슈머** → read model projection 갱신 (멱등)
- read DB(`ticket_read_db`) **Primary + Replica 분리**로 읽기 부하 분산
- **eventual consistency ~400ms** 실측 (write 후 read 수렴)

## 핵심 — 카프카를 건너뛰는 e2e 분산추적
- 발행 시 W3C `traceparent`를 이벤트 payload에 실어 전파 → 소비 시 remote parent로 복원
- `booking confirm → MyKafka produce ⇢ 카프카 ⇢ ticket-query consume → read DB`가 **하나의 traceID로 관통**(Jaeger)
- 쓰기(booking)와 완전히 분리 — 서로 모른 채 이벤트로만 연결

## 기술 스택
`Kotlin` · `Spring Boot` · `PostgreSQL(Primary/Replica)` · `MyKafka` · `OpenTelemetry/Jaeger`
