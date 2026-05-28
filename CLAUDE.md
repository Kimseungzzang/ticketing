# Ticketing Project

## Branch Strategy

This project uses **one branch per MSA service**. Each branch is a completely independent project — not a feature branch or a release branch. Treat each branch as its own standalone application with its own build system, dependencies, and deployment lifecycle.

### Active Service Branches

| Branch | Service | Stack | Port |
|--------|---------|-------|------|
| `auth-service` | Authentication & JWT token management | Spring Boot (Kotlin), PostgreSQL, Redis | 8081 |
| `queue-service` | Waiting queue admission system | Spring Boot (Kotlin), Redis Sorted Set | 8082 |
| `frontend` | Ticket booking UI | Next.js (TypeScript) | 3000 |
| `myRedis` | Custom Redis client library (myredis-client-starter) | Kotlin | — |

### Branches to Ignore

- `main` — aggregation snapshot only, not a runnable service
- `hhm` — experimental, not in scope

## Working Rules

- When working on a task, **check out the relevant branch first** before reading or editing files.
- Code changes on one branch do not affect other branches — they are separate services that communicate over HTTP.
- Each service has its own `build.gradle.kts`, `application.yml`, and source root.
- The `myRedis` branch publishes `myredis-client-starter` to GitHub Packages; `auth-service` and `queue-service` depend on it as a library.

## Service Communication

```
frontend (3000)
    ↓ REST
auth-service (8081)   ←── issues JWT
    ↑ /api/auth/verify (internal)
queue-service (8082)  ←── validates JWT via auth-service on every request
```

## Credentials

- GitHub Packages credentials required to resolve `myredis-client-starter`.
- Store in `.env` at the project root (gitignored) or in `~/.gradle/gradle.properties`.
- See `.env.example` for the required keys (`GITHUB_ACTOR`, `GITHUB_TOKEN`).

## JVM Version

All Spring Boot services require **JDK 21** (myredis-client-starter is compiled for Java 21).
