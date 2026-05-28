<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

## Project Branch Strategy

This project uses **one branch per MSA service**. Each branch is a completely independent project.

| Branch | Service | Stack | Port |
|--------|---------|-------|------|
| `auth-service` | Authentication & JWT token management | Spring Boot (Kotlin), PostgreSQL, Redis | 8081 |
| `queue-service` | Waiting queue admission system | Spring Boot (Kotlin), Redis Sorted Set | 8082 |
| `frontend` | Ticket booking UI | Next.js (TypeScript) | 3000 |
| `myRedis` | Custom Redis client library | Kotlin | — |

Ignore `main` and `hhm` branches — they are not active services.

### Service Communication
```
frontend (3000) → auth-service (8081) ← queue-service (8082)
```
queue-service validates every JWT by calling `auth-service /api/auth/verify`.
