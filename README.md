# queue-service

대기열 진입 및 입장 관리를 담당하는 서비스입니다.

- **Port**: 8082
- **Stack**: Spring Boot (Kotlin), myRedis (Sorted Set)

## 실행 방법

### 1. 사전 요구사항

- JDK 21
- myRedis (port 6379) — [`myRedis` 브랜치](../../tree/myRedis) 참고
- gateway-service 경유 필요 (X-User-Id 헤더)

### 2. 환경변수 설정

프로젝트 루트에 `.env` 파일 생성:

```env
GITHUB_ACTOR=your_github_username
GITHUB_TOKEN=your_github_token
```

### 3. 실행

```bash
./gradlew bootRun
```

> 모든 요청은 gateway-service(8080)를 통해 들어옵니다. 직접 호출 시 `X-User-Id` 헤더가 필요합니다.

## API

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/queue/enter` | 대기열 진입 |
| GET | `/api/queue/status` | 대기 순서 조회 |
| GET | `/api/queue/validate` | 입장 토큰 검증 |
| POST | `/api/queue/release` | 입장 슬롯 반납 |

## Redis 키

| 키 | 타입 | 용도 | TTL |
|----|------|------|-----|
| `queue:sorted:{eventId}` | ZSet | 대기열 (score = 진입 시각) | — |
| `queue:admitted:{userId}` | String | 입장 허가 유저 마킹 | — |
| `queue:active:count:{eventId}` | String | 현재 입장 중인 인원 수 | — |
| `queue:entry:{userId}` | String | 입장 토큰 (UUID) | 300s |

## 대기열 흐름

```
POST /enter → ZADD queue:sorted:{eventId} (score=now)
                ↓
스케줄러 (3초마다) → ZPOPMIN → INCR active:count → SET queue:entry:{userId} (TTL 300s)
                ↓
GET /status → ZRANK 조회 → status: WAITING / READY
                ↓
READY → entryToken 발급 → 프론트가 /seats 진입
```
