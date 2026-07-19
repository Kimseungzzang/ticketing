# gateway-service — API Gateway (L7)

> 모든 트래픽의 L7 진입점. 경로 라우팅 + 대기열 인스턴스 로드밸런싱.
> 🏠 전체 구조: **[메인 README](https://github.com/Kimseungzzang/ticketing/blob/main/README.md)**

## 역할
- **Spring Cloud Gateway** — `/api/queue/**`, `/api/booking/**` 등 경로 라우팅
- **`lb://queue-service`** — 대기열 다중화 인스턴스에 round-robin 분산
- 앞단에 nginx **L4** 로드밸런서 → **2단 로드밸런싱 (L4 + L7)**

```
[클라이언트] → nginx L4(:8000) → API Gateway ×2(:8080/:8180) → queue-service ×N
```

## 기술 스택
`Kotlin` · `Spring Cloud Gateway(WebFlux)` · `Spring Cloud LoadBalancer`
