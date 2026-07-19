# ticket-command-service — 예약 커맨드 (CQRS Write)

> 티켓팅 MSA의 **쓰기 모델(Command)**. 예약 커맨드를 처리하고 Transactional Outbox로 이벤트를 발행한다.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 역할
- 예약 생성/확정/취소 **커맨드 처리** (write DB: `ticket_db`)
- **Transactional Outbox** — 비즈니스 트랜잭션과 이벤트 발행의 원자성 보장 (at-least-once)
- `OutboxRelay`(@Scheduled)가 PENDING 이벤트를 **MyKafka**로 배치 발행

## CQRS에서의 위치
```
ticket-command (write) ─┐
                        ├─▶ MyKafka ─▶ ticket-query (read)
      booking (write) ─┘
```
쓰기와 읽기를 분리해 각각 독립적으로 확장.

## 기술 스택
`Kotlin` · `Spring Boot` · `PostgreSQL` · `MyKafka(직접 구현)`
