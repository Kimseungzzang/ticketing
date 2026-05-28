# Auth Service API

**Base URL:** `http://localhost:8081`

---

## 인증 방식

보호된 엔드포인트는 요청 헤더에 JWT 액세스 토큰이 필요합니다.

```
Authorization: Bearer <accessToken>
```

---

## 엔드포인트

### POST /api/auth/register
회원가입

**인증 불필요**

**Request Body**
```json
{
  "id": "string",       // 3~20자
  "name": "string",     // 최대 50자
  "password": "string"  // 최소 6자
}
```

**Response** `201 Created`
```json
{
  "accessToken": "string",
  "refreshToken": "string",
  "tokenType": "Bearer",
  "user": {
    "id": "string",
    "name": "string"
  }
}
```

---

### POST /api/auth/login
로그인

**인증 불필요**

**Request Body**
```json
{
  "id": "string",
  "password": "string"
}
```

**Response** `200 OK`
```json
{
  "accessToken": "string",
  "refreshToken": "string",
  "tokenType": "Bearer",
  "user": {
    "id": "string",
    "name": "string"
  }
}
```

---

### POST /api/auth/refresh
액세스 토큰 재발급

**인증 불필요**

**Request Body**
```json
{
  "refreshToken": "string"
}
```

**Response** `200 OK`
```json
{
  "accessToken": "string",
  "tokenType": "Bearer"
}
```

---

### POST /api/auth/logout
로그아웃 (Redis에서 토큰 삭제)

**인증 필요**

**Request Body** 없음

**Response** `200 OK`

---

### GET /api/auth/me
내 정보 조회

**인증 필요**

**Response** `200 OK`
```json
{
  "id": "string",
  "name": "string"
}
```

---

### POST /api/auth/verify
JWT 토큰 유효성 검증 (서비스 간 내부 호출용)

**인증 불필요**

**Request Body**
```json
{
  "token": "string"
}
```

**Response** `200 OK`
```json
{
  "valid": true,
  "userId": "string",
  "name": "string"
}
```
```json
{
  "valid": false,
  "userId": null,
  "name": null
}
```
