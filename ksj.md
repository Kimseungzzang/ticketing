# 티켓팅 토이 프로젝트 정리

## 1. 프로젝트 목적

이 프로젝트는 MSA 기반 티켓팅 서버를 직접 만들어 보면서, 대규모 트래픽 상황에서 필요한 인증, 인가, 대기열, 캐시 계층의 역할을 이해하는 것을 목표로 한다.

특히 Redis를 단순히 외부 인프라로 사용하는 대신, `myRedis`를 직접 구현해 보면서 아래 내용을 체감하는 데 초점을 둔다.

- 인증 시스템에서 Redis가 어떤 위치에 들어가는지
- 애플리케이션과 Redis 사이에 데이터가 어떻게 흐르는지
- TCP 기반 요청/응답 구조가 어떻게 동작하는지
- 멀티 스레드 환경에서 원자성과 성능을 어떻게 맞바꾸게 되는지

---

## 2. 현재 구현 방향

좌석 선택 이전의 대기열을 구현하기 전에, 먼저 간단한 인증 시스템과 토큰 저장소를 구축하고 있다.

구성은 크게 두 가지다.

- `auth-service`
  - 로그인 시 아이디와 비밀번호로 인증
  - 인가에는 JWT access token 사용
  - 사용자 정보는 RDB에 저장
  - 발급한 access token은 `myRedis`에도 저장
- `myRedis`
  - Kotlin + Netty 기반의 Redis 유사 서버
  - RESP 프로토콜을 디코딩/인코딩
  - 현재는 `String -> (String value, Long expiresAt)` 형태만 저장 가능
  - 실제 Redis 메모리가 아니라 JVM 메모리 위에서 동작

토큰 저장 키 구조는 아래와 같다.

```text
auth:jwt:access:{loginId} -> {accessToken}
auth:jwt:refresh:{loginId} -> {refreshToken}
```

---

## 3. 현재 구현 상태 요약

### auth-service

- 로그인은 아이디/비밀번호 기반이다.
- 로그인 성공 시 JWT access token과 refresh token을 함께 발급한다.
- access token TTL은 5분, refresh token TTL은 15분이다.
- 발급한 access token과 refresh token은 `myRedis`에 저장한다.
- `myRedis`와의 통신은 요청마다 새 연결을 만드는 대신, 크기 4의 소켓 풀을 재사용하는 방식으로 구성했다.
- 일반 요청 검증은 access token의 서명, 만료 시간, 타입을 로컬에서만 확인한다.
- refresh는 `/refresh` 요청에서만 Redis에 저장된 refresh token과 비교한다.
- logout 시 Redis의 access token과 refresh token 키를 삭제한다.

### myRedis

- Netty 서버로 TCP 연결을 받는다.
- RESP 요청을 디코딩해 커맨드 리스트로 변환한다.
- `SET`, `GET`, `DEL`, `EXPIRE`, `TTL`, `EXISTS`, `KEYS`, `PING`, `COMMAND` 등을 처리한다.
- 응답도 RESP 형태로 다시 인코딩해 반환한다.
- 저장소는 `ConcurrentHashMap` 기반이다.
- worker event loop는 1개로 두어, 현재 단계에서는 명령 처리를 단일 worker 흐름으로 이해하기 쉽게 구성했다.
- TTL 만료는 읽기 시점에 확인하는 lazy expiration 방식에 가깝다.

---

## 4. 데이터 흐름

### 4.1 로그인 흐름

1. 클라이언트가 `id/password`로 로그인 요청을 보낸다.
2. `auth-service`가 RDB에서 사용자를 조회한다.
3. 비밀번호가 일치하면 JWT access token과 refresh token을 발급한다.
4. `auth-service`가 `myRedis`에 아래 형태로 저장한다.

```text
SET auth:jwt:access:{loginId} {accessToken} PX 300000
SET auth:jwt:refresh:{loginId} {refreshToken} PX 900000
```

5. 클라이언트는 이후 일반 요청에 access token을 사용하고, access token 만료 시 refresh token으로 재발급을 시도한다.

### 4.2 인가 흐름

일반 요청의 인가 흐름은 아래와 같다.

1. 요청에서 JWT access token을 추출한다.
2. JWT 자체의 서명, 만료 시간(`exp`), 타입(`access`)을 검증한다.
3. 검증에 성공하면 인증된 요청으로 처리한다.
4. 이 과정에서는 Redis를 조회하지 않는다.

즉 access token의 만료 여부는 Redis TTL이 아니라 JWT 내부의 `exp` claim으로 판단한다.

### 4.3 refresh 흐름

1. 일반 요청에서 access token이 만료되면 클라이언트가 `/api/auth/refresh`를 호출한다.
2. 클라이언트는 refresh token을 요청 본문에 담아 보낸다.
3. 서버는 refresh token의 서명, 만료 시간, 타입(`refresh`)을 확인한다.
4. 서버는 Redis의 `auth:jwt:refresh:{loginId}` 값을 조회한다.
5. Redis의 refresh token과 요청 refresh token이 같으면 새 access token을 발급한다.
6. 새 access token은 Redis의 `auth:jwt:access:{loginId}`에도 다시 저장한다.

### 4.4 logout 흐름

1. 인증된 사용자가 `/api/auth/logout`을 호출한다.
2. 서버는 Redis의 access token과 refresh token 키를 모두 삭제한다.
3. 이후 refresh token으로는 재발급이 불가능하다.

주의할 점은, 현재 구조에서는 일반 요청 때 Redis를 조회하지 않으므로 이미 발급된 access token은 남은 5분 동안 로컬 검증만으로 통과할 수 있다는 점이다.  
즉시 로그아웃을 보장하려면 access blacklist가 추가로 필요하다.

---

## 5. myRedis 설계 의도

`myRedis`는 빠른 개발과 학습을 위해 단순한 구조로 만들고 있다.

구성 요소는 다음과 같다.

- RESP 디코더
  - TCP 바이트 스트림을 Redis 호환 커맨드 형태로 해석
- RESP 인코더
  - 처리 결과를 RESP 응답으로 반환
- Store
  - 실제 데이터를 저장하는 메모리 공간
- CommandHandler
  - 디코딩된 명령을 해석하고 CRUD 실행

이 설계는 아래 학습 포인트를 분리해서 보기 좋다.

- 네트워크 계층: TCP + Netty
- 프로토콜 계층: RESP
- 저장 계층: Map 기반 메모리 저장소
- 명령 처리 계층: Redis 커맨드 처리

현재 채택한 실행 모델은 아래와 같다.

- `myRedis`
  - `bossGroup = 1`, `workerGroup = 1`
  - 새 연결 수락과 실제 I/O 처리를 분리하되, worker는 단일 event loop로 둔다.
- `auth-service`
  - 소켓 풀 크기 4
  - 요청 시 연결 하나를 빌려 `요청 전송 -> 응답 수신 -> 풀 반납` 순서로 사용한다.

이 구조를 택한 이유는 두 가지다.

- Redis처럼 요청마다 연결을 새로 맺지 않고 재사용 구조를 학습하기 위해
- 현재 단계에서는 복잡한 멀티 스레드 동시성보다 단일 worker 흐름에서의 데이터 이동과 명령 처리 순서를 더 명확히 보기 위해

---

## 6. 현재까지의 고민과 답변

## 6.1 고민 1

`verify`가 필요할 때 Redis를 매번 조회하지 않고 access token만 로컬 검증하는 구조가 맞는가?  
왜냐하면 대규모 접속에서 빠른 인가를 위해 Redis를 사용하려는 목적이 있기 때문이다.  
그렇다면 세션 방식과 비교했을 때 어떤 차이가 있는가?

### 답변

이 방식은 가능하다. 다만 중요한 점은, 일반 요청은 무상태 access token 검증처럼 동작하지만 refresh와 단일 로그인 제어는 Redis 상태에 의존한다는 것이다.

즉 현재 구조는 "일반 요청은 stateless에 가깝고, 재발급과 세션 통제는 stateful"인 절충안이다.

장점과 단점은 아래와 같다.

장점:

- 일반 요청마다 Redis 조회가 없어서 빠르다.
- 중복 로그인 1회 제한 구현이 쉽다.
- refresh token 기반 재발급 제어가 가능하다.
- 여러 서버 인스턴스가 있어도 Redis를 기준으로 refresh 상태를 공유할 수 있다.

단점:

- logout 직후에도 기존 access token은 남은 TTL 동안 유효할 수 있다.
- 즉시 로그아웃을 원하면 access blacklist 같은 추가 장치가 필요하다.
- refresh 경로는 Redis 장애에 영향을 받는다.

즉, 이 방식은 "access는 stateless, refresh는 stateful" 구조라고 볼 수 있다.

### 세션 방식과의 비교

세션 방식은 보통 아래와 같다.

- 클라이언트는 session id만 가진다.
- 서버는 Redis 같은 저장소에 session id -> 사용자 상태를 저장한다.
- 검증 시 저장소를 조회한다.

현재 채택한 JWT + Redis 방식은 아래와 같다.

- 클라이언트는 access token과 refresh token을 가진다.
- 일반 요청은 access token만 서버에 보낸다.
- 서버는 access token의 서명/만료/타입만 로컬 검증한다.
- access token이 만료되면 클라이언트가 refresh token으로 `/refresh`를 호출한다.
- 서버는 Redis에 저장된 refresh token과 비교해 새 access token을 발급한다.

따라서 매 요청마다 Redis를 조회하는 세션 방식보다 일반 요청 경로는 더 가볍다.  
반면 로그인 상태 통제는 refresh 경로와 Redis 저장소에 의존한다.

### 결론

이 프로젝트의 목적이 "중앙에서 토큰 상태를 통제하면서 Redis 학습까지 하는 것"이라면 JWT + Redis 방식은 충분히 의미 있다.

다만 실무적으로는 아래처럼 정리하는 것이 더 명확하다.

- 순수 성능이 최우선이면: JWT만 로컬 검증
- 일반 요청 성능과 재발급 통제를 함께 가져가고 싶으면: access JWT + refresh Redis
- 완전한 서버 상태 관리와 단순한 운영이 중요하면: 세션 + Redis

### 추천

이 프로젝트에서는 아래 방향을 추천한다.

- access token은 짧은 만료 시간을 둔다.
- 일반 요청 검증은 JWT 서명과 만료 시간으로 처리한다.
- Redis는 refresh token 저장과 재발급 제어 용도로 사용한다.

한 단계 더 나아가면, access token 전체를 저장하기보다 아래 방식이 더 확장성이 좋다.

- `tokenVersion` 저장
- `jti` 저장
- access blacklist 저장

즉, 지금 구조는 학습용으로는 충분히 적절하고, 이후 실무 확장성을 생각하면 refresh 중심 저장 + access blacklist 또는 token version 방식으로 발전시킬 수 있다.

---

## 6.2 고민 2

Spring은 멀티 스레드 환경이고, `myRedis`는 Netty 기반 멀티 스레드 구조다.  
이때 `auth-service -> myRedis` TCP 통신에서 원자성과 속도를 어떻게 같이 가져갈 것인가?

고민한 안은 다음과 같다.

1. 소켓 1개를 공유하고 락으로 직렬화
2. 요청마다 새 소켓 생성 + `myRedis` 싱글 스레드
3. 소켓 풀을 두고 재사용 + `myRedis` 싱글 스레드

### 최종 선택

최종적으로 아래 구조를 채택했다.

- `myRedis`: worker event loop 1개
- `auth-service`: 소켓 풀 4개 재사용

즉, 서버 쪽은 단일 worker 흐름으로 단순하게 두고, 클라이언트 쪽은 연결 재사용으로 불필요한 connect/close 비용을 줄이는 방향이다.

### 1번이 탈락하는 이유

소켓 1개를 여러 스레드가 공유하면, 같은 스트림에 여러 요청 바이트가 섞일 위험이 있다.  
그래서 락으로 직렬화하면 안전성은 올라가지만, 결국 모든 Redis 요청이 한 줄로 서게 된다.

문제는 이 방식이 Redis를 쓰는 이유와 정면으로 충돌한다는 점이다.

- 병렬 요청을 못 받는다.
- 애플리케이션 스레드가 락 대기에 묶인다.
- 병목이 auth-service 쪽에서 먼저 발생한다.

즉, 안전하지만 지나치게 느리다.

### 2번이 탈락하는 이유

요청마다 새 소켓을 만들면 아래 비용이 반복된다.

- 소켓 생성 비용
- TCP handshake 비용
- 커널 자원 할당 비용
- 연결 종료 비용

학습 목적상 단순하긴 하지만, 성능을 기대하기 어렵다.

즉, 원자성은 맞추기 쉽지만 연결 비용이 너무 크다.

### 3번을 채택한 이유

소켓 풀을 두고 연결을 재사용하면 연결 생성 비용을 줄일 수 있다.  
핵심은 "한 소켓을 동시에 여러 스레드가 같이 쓰지 않게 하는 것"이다.

즉, 아래 규칙이면 된다.

- 풀에서 소켓 하나를 빌린다.
- 그 소켓은 한 요청이 끝날 때까지 한 스레드만 사용한다.
- 응답까지 받은 뒤 반납한다.

이렇게 하면 TCP 스트림이 섞이지 않고, 연결 재사용도 가능하다.

현재 `auth-service`는 이 방식을 실제로 적용했다.  
다만 풀 크기가 4이므로 동시에 4개 연결이 모두 점유되면 이후 요청은 대기하게 된다. 이것은 오류가 아니라 의도된 backpressure다.

### 실제 Redis는 어떻게 동작하는가

실제 Redis는 "요청마다 새 연결을 만드는 구조"가 아니다.  
보통 클라이언트는 아래 방식으로 사용한다.

- 연결을 재사용한다.
- 여러 연결을 풀로 관리한다.
- 서버는 많은 연결을 동시에 받되, 명령 실행 자체는 매우 단순한 방식으로 처리한다.

전통적인 Redis는 단일 스레드 이벤트 루프 기반으로 명령 실행 원자성을 유지하는 것으로 유명하다.  
최근 버전은 I/O thread나 background thread를 활용하는 부분도 있지만, 핵심 명령 처리 모델은 여전히 단순성과 예측 가능성을 매우 중시한다.

즉, 학습용 `myRedis`에서 Redis의 본질을 따라가려면 아래가 핵심이다.

- 네트워크 연결은 여러 개 받아도 된다.
- 하지만 데이터 변경 명령 실행은 단순하고 예측 가능한 흐름으로 직렬화하는 편이 좋다.

현재 프로젝트는 이 방향에 맞춰 `workerGroup = 1`로 두었다.

---

## 7. 중요한 정리: ConcurrentHashMap만으로 Redis 같은 원자성이 생기지는 않는다

현재 `myRedis`는 `ConcurrentHashMap`을 사용하고 있다.  
이것은 "개별 Map 연산"의 스레드 안전성을 높여 줄 뿐, Redis 같은 명령 단위 원자성을 자동으로 보장하지는 않는다.

예를 들어 아래 같은 흐름은 여전히 경쟁 조건이 생길 수 있다.

- `GET` 하면서 TTL 만료 체크
- `EXPIRE` 하면서 기존 값 복사 후 갱신
- 여러 스레드가 같은 키에 동시에 접근

즉, `map[key] = ...` 자체는 안전해도, "읽고 판단한 뒤 다시 쓰는" 복합 연산은 경쟁 조건이 발생할 수 있다.

### 따라서 추천하는 myRedis 실행 모델

초기 버전에서는 아래 모델을 추천한다.

- 연결은 여러 개 허용
- RESP 디코딩은 Netty가 담당
- 실제 커맨드 실행은 단일 실행 스레드에서 처리

이렇게 하면 Redis의 가장 중요한 특성인 "명령 단위 직렬화"를 학습하기 좋다.

이후 확장 단계에서 고려할 수 있는 것은 아래와 같다.

- key hash 기반 shard 분리
- read/write 분리
- 자료구조별 락 또는 actor 모델
- event loop와 command executor 분리

하지만 현재 단계에서는 싱글 스레드 커맨드 실행이 가장 설명력이 좋고, Redis 학습 목적에도 가장 잘 맞는다.

---

## 8. 내 프로젝트에 대한 현재 선택과 다음 확장 포인트

현재 프로젝트 목적과 구현 난이도를 함께 고려해 아래 구성을 채택했다.

### auth-service

- 요청마다 새 소켓을 만들지 않는다.
- 소켓 풀 크기는 4로 둔다.
- 한 소켓은 동시에 하나의 요청만 처리하게 한다.
- access token TTL은 5분, refresh token TTL은 15분으로 둔다.
- 일반 요청 검증은 access token의 JWT 서명/만료/타입만 확인한다.
- refresh 요청에서만 Redis의 refresh token과 비교한다.
- logout 시 Redis의 access token과 refresh token 키를 삭제한다.

### myRedis

- TCP 연결은 여러 개 받아도 된다.
- RESP 파싱은 지금 구조를 유지한다.
- worker event loop는 1개로 둔다.
- 학습용 1단계에서는 String + TTL만 유지해도 충분하다.

### 인증 전략

- 지금 방식은 "완전한 stateless JWT"가 아니라 "access는 stateless, refresh는 stateful"에 가깝다.
- 이 점을 문서에 명확히 적어 두는 것이 좋다.
- 이후 대기열 시스템과 연결할 때도 "중앙에서 상태를 통제한다"는 장점이 있다.

### 이후 확장 포인트

- 소켓 풀 크기 4가 실제 부하에서 적절한지 측정
- 풀 고갈 시 timeout 정책 추가
- `verify` 시 Redis 토큰 비교 로직 완성
- 필요 시 worker 1개 구조와 command executor 분리 구조의 성능 비교

---

## 9. 세션과 JWT + Redis 중 무엇이 더 맞는가

이 질문의 답은 "무엇을 제어하고 싶은가"에 달려 있다.

### 세션이 더 잘 맞는 경우

- 완전한 서버 주도 상태 관리가 필요할 때
- 로그아웃과 만료 제어를 단순하게 가져가고 싶을 때
- JWT의 self-contained 특성이 크게 필요 없을 때

### JWT + Redis가 더 잘 맞는 경우

- JWT 구조 자체를 학습하고 싶을 때
- 게이트웨이나 여러 서비스에서 토큰 기반 인증을 연습하고 싶을 때
- 중앙에서 토큰 무효화도 같이 제어하고 싶을 때

### 이 프로젝트에서의 판단

이 프로젝트는 티켓팅 시스템을 위한 MSA 학습과 Redis 학습이 핵심이므로, JWT + Redis 조합이 더 교육적이다.  
다만 문서에서는 반드시 아래처럼 표현하는 것이 좋다.

> 이 프로젝트의 인증 구조는 완전 무상태 JWT가 아니라, Redis를 통해 토큰 상태를 함께 관리하는 상태 기반 JWT 구조이다.

이 문장 하나가 설계 의도를 매우 명확하게 해 준다.

---

## 10. 다음 단계 제안

### 1단계

- `verify` 시 Redis 토큰 비교 로직 추가
- 로그아웃 시 Redis 키 삭제 또는 토큰 폐기 처리 추가

### 2단계

- `auth-service`에 소켓 풀 적용
- 풀 크기, 대기 큐, 타임아웃 정책 추가

### 3단계

- `myRedis` 명령 실행부를 싱글 스레드 executor로 직렬화
- race condition 실험 코드 추가

### 4단계

- benchmark 진행
- 아래 세 가지 비교

```text
1. JWT만 로컬 검증
2. JWT + Redis 조회
3. Session + Redis 조회
```

이 비교를 하면 "왜 Redis를 쓰는지", "언제 세션이 더 나은지", "JWT를 붙이면 무엇이 달라지는지"가 훨씬 명확해진다.

---

## 11. 한 줄 결론

이 프로젝트의 현재 방향은 학습용으로 매우 좋다.  
다만 핵심은 `ConcurrentHashMap` 자체가 아니라 "명령 실행을 어떻게 직렬화할 것인가", 그리고 JWT를 정말 무상태로 쓸 것인지 아니면 Redis와 결합한 상태 기반 인증으로 운영할 것인지를 명확히 정의하는 것이다.
