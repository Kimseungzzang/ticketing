# booking-service

좌석 예약 및 예약 상태 관리를 담당하는 서비스입니다.

- **Port**: 8083
- **Stack**: Spring Boot (Kotlin), PostgreSQL, myRedis

## 실행 방법

### 1. 사전 요구사항

- JDK 21
- PostgreSQL
- myRedis (port 6379) — [`myRedis` 브랜치](../../tree/myRedis) 참고
- gateway-service 경유 필요 (X-User-Id 헤더)

### 2. 데이터베이스 생성

```bash
psql -U kimseungzzang -f schema.sql
```

좌석 시드 데이터는 서비스 기동 시 자동으로 삽입됩니다 (`SeatDataInitializer`).

### 3. 환경변수 설정

프로젝트 루트에 `.env` 파일 생성:

```env
GITHUB_ACTOR=your_github_username
GITHUB_TOKEN=your_github_token
DB_USERNAME=kimseungzzang
DB_PASSWORD=
```

### 4. Redis 초기화

```bash
redis-cli set "booking:seats:remaining:EVT2026-001" 292
```

### 5. 실행

```bash
./gradlew bootRun
```

## API

### 좌석 조회

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/seats/{eventId}` | 이벤트 좌석 목록 및 잔여 현황 조회 |

### 예약

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/booking` | 예약 생성 (entryToken 필요) |
| GET | `/api/booking/{bookingId}` | 예약 조회 |
| POST | `/api/booking/{bookingId}/confirm` | 예약 확정 |
| DELETE | `/api/booking/{bookingId}` | 예약 취소 |

## 예약 생성 요청 예시

```bash
curl -X POST http://localhost:8080/api/booking \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {accessToken}" \
  -d '{
    "eventId": "EVT2026-001",
    "seatId": "S-A-1",
    "entryToken": "{entryToken}"
  }'
```

## 예약 흐름

```
POST /api/booking
  → entryToken 검증 (Redis queue:entry:{userId})
  → 좌석 잠금 SET NX (Redis booking:lock:{eventId}:{seatId}, TTL 10s)
  → 잔여 좌석 DECR (Redis booking:seats:remaining:{eventId})
  → seats 테이블 status → TAKEN
  → bookings 테이블 INSERT (status: PENDING)
```

## Redis 키

| 키 | 타입 | 용도 | TTL |
|----|------|------|-----|
| `booking:seats:remaining:{eventId}` | String | 잔여 좌석 카운터 | — |
| `booking:lock:{eventId}:{seatId}` | String | 좌석 동시 예약 방지 잠금 | 10s |

## 데이터베이스

| 테이블 | 설명 |
|--------|------|
| `seats` | 이벤트별 전체 좌석 (AVAILABLE / TAKEN) |
| `bookings` | 예약 내역 (PENDING / CONFIRMED / CANCELLED) |
