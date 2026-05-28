# Queue Service API

**Base URL:** `http://localhost:8082`

---

## 인증 방식

모든 엔드포인트는 JWT 액세스 토큰이 필요합니다.  
요청 수신 시 auth-service(`/api/auth/verify`)를 호출하여 토큰을 검증합니다.

```
Authorization: Bearer <accessToken>
```

---

## 엔드포인트

### POST /api/queue/enter
대기열 진입

이미 대기 중이면 현재 순서를 반환하고, 입장 토큰이 있으면 READY를 반환합니다.

**Request Body**
```json
{
  "eventId": "string"  // 예: "EVT2026-001"
}
```

**Response** `200 OK`
```json
{
  "status": "WAITING",   // WAITING | READY
  "position": 3,         // 1-indexed 순서 (READY이면 0)
  "total": 100,          // 현재 대기열 전체 인원
  "entryToken": null     // READY일 때만 발급되는 입장 토큰
}
```

---

### GET /api/queue/status
대기열 상태 조회

**Query Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| eventId | string | 이벤트 ID |

**Response** `200 OK`
```json
{
  "status": "WAITING",   // WAITING | READY | NOT_IN_QUEUE
  "position": 3,
  "total": 100,
  "entryToken": null
}
```

**status 값 설명**

| 값 | 설명 |
|----|------|
| WAITING | 대기 중 |
| READY | 입장 가능 (entryToken 발급됨) |
| NOT_IN_QUEUE | 대기열에 없음 |

---

### GET /api/queue/validate
입장 토큰 유효성 검증 (좌석 선택 페이지 진입 시 호출)

**Query Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| entryToken | string | 입장 토큰 |

**Response** `200 OK`
```json
{
  "message": "유효한 입장 토큰입니다"
}
```

**Response** `403 Forbidden`
```json
{
  "message": "유효하지 않은 입장 토큰입니다"
}
```

---

### POST /api/queue/release
입장 토큰 반납 (좌석 선택 이탈 또는 토큰 만료 시 호출)

슬롯을 반납하여 대기 중인 다음 사용자가 입장할 수 있도록 합니다.

**Query Parameters**

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| eventId | string | 이벤트 ID |

**Response** `200 OK`
```json
{
  "message": "입장 토큰이 반납되었습니다"
}
```

---

## 입장 흐름

```
1. POST /enter        → WAITING (대기열 진입)
2. GET  /status 폴링  → status가 READY가 될 때까지 반복 (3초 간격)
3. READY 상태         → entryToken 발급 (유효시간 300초)
4. GET  /validate     → 좌석 선택 페이지 진입 전 토큰 검증
5. POST /release      → 구매 완료 또는 이탈 시 슬롯 반납
```
