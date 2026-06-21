# auth-service

JWT 발급 및 검증을 담당하는 인증 서비스입니다.

- **Port**: 8081
- **Stack**: Spring Boot (Kotlin), PostgreSQL, myRedis

## 실행 방법

### 1. 사전 요구사항

- JDK 21
- PostgreSQL
- myRedis (port 6379) — [`myRedis` 브랜치](../../tree/myRedis) 참고

### 2. 데이터베이스 생성

```bash
psql -U kimseungzzang -f schema.sql
psql auth_db -f mock-users.sql   # 테스트용 유저 100명 삽입 (비밀번호: password)
```

### 3. 환경변수 설정

프로젝트 루트에 `.env` 파일 생성:

```env
GITHUB_ACTOR=your_github_username
GITHUB_TOKEN=your_github_token
DB_USERNAME=kimseungzzang
DB_PASSWORD=
```

### 4. 실행

```bash
./gradlew bootRun
```

## API

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/api/auth/register` | 회원가입 | 불필요 |
| POST | `/api/auth/login` | 로그인 → JWT 발급 | 불필요 |
| POST | `/api/auth/refresh` | 토큰 갱신 | 불필요 |
| POST | `/api/auth/verify` | 토큰 검증 (게이트웨이 내부 호출) | 불필요 |
| POST | `/api/auth/logout` | 로그아웃 | Bearer |

## 요청/응답 예시

### 회원가입
```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"id":"user001","name":"홍길동","password":"password"}'
```

### 로그인
```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"id":"user001","password":"password"}'
```

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "user": { "id": "user001", "name": "홍길동" }
}
```

## Redis 키

| 키 | 타입 | 용도 |
|----|------|------|
| `auth:access:{userId}` | String | 발급된 AccessToken 저장 (로그아웃 검증용) |
| `auth:refresh:{userId}` | String | 발급된 RefreshToken 저장 |
