# Aviation API Wrapper

A Spring Boot microservice that looks up airport details by **ICAO code** using the public
[Aviation Weather API](https://aviationweather.gov/data/api/) provided by NOAA.

Features: Redis caching, full resilience layer (retry + circuit breaker + timeout +
automatic fallback to a second provider), API key authentication, distributed rate limiting,
correlation ID request tracing, custom clean response format, and observability via
Prometheus + Grafana.

---

## Table of Contents

1. [Project Structure](#1-project-structure)
2. [Full Request Flow](#2-full-request-flow)
3. [Response Format](#3-response-format)
4. [API Key Authentication](#4-api-key-authentication)
5. [Rate Limiting](#5-rate-limiting)
6. [Correlation ID / Request Tracing](#6-correlation-id--request-tracing)
7. [Redis Cache — Fail-Open](#7-redis-cache--fail-open)
8. [Resilience Layer](#8-resilience-layer)
9. [Fallback — Provider 2 (AirportDB)](#9-fallback--provider-2-airportdb)
10. [Prerequisites](#10-prerequisites)
11. [Setup & Run](#11-setup--run)
12. [API Reference](#12-api-reference)
13. [Running Tests](#13-running-tests)
14. [Configuration Reference](#14-configuration-reference)
15. [Assumptions](#15-assumptions)
16. [Architecture Decisions](#16-architecture-decisions)
17. [Error Handling](#17-error-handling)
18. [Observability](#18-observability)
19. [Deployment, Scaling & Monitoring](#19-deployment-scaling--monitoring)

---

## 1. Project Structure

```
├── Dockerfile                                         # Multi-stage build (Maven → JRE runtime, multi-arch)
├── docker-compose.yml                                 # 2 app instances + Redis + Nginx + Prometheus + Grafana
├── nginx.conf                                         # Nginx load balancer (round-robin, passive health check)
├── prometheus.yml                                     # Prometheus scrape config (both app instances, 15s)
├── grafana/provisioning/datasources/prometheus.yml    # Auto-provisions Prometheus as Grafana datasource
│
src/
├── main/java/com/sporty/aviation/
│   ├── AviationApplication.java
│   ├── client/
│   │   ├── AviationWeatherFeignClient.java     # Primary Feign client → aviationweather.gov
│   │   ├── FeignClientConfig.java              # Primary client: timeout, logging, error decoder
│   │   ├── AirportDbFeignClient.java           # Fallback Feign client → airportdb.io (Provider 2)
│   │   └── AirportDbFeignClientConfig.java     # Provider 2: shorter timeout, API token interceptor
│   ├── config/
│   │   ├── RedisConfig.java                    # RedisCacheManager — TTL, JSON serializer, fail-open handler
│   │   ├── RedisCacheErrorHandler.java         # Swallows Redis errors → cache miss (Redis is optional)
│   │   └── OpenApiConfig.java                  # OpenAPI 3 spec — title, version, X-API-Key security scheme
│   ├── controller/
│   │   └── AirportController.java              # GET /api/v1/airports/{icaoCode}
│   ├── dto/
│   │   ├── AirportResponse.java                # Clean public response (icao, iata, name, city, …)
│   │   ├── AviationWeatherAirportDto.java      # Internal: raw Aviation Weather API response
│   │   ├── Provider2AirportDto.java            # Internal: raw AirportDB (Provider 2) response
│   │   └── ErrorResponse.java                  # Uniform error body returned on all failures
│   ├── exception/
│   │   ├── AirportNotFoundException.java       # Thrown when ICAO code returns no results → 404
│   │   ├── AviationApiException.java           # Thrown on upstream API errors (5xx, timeout) → 502
│   │   └── GlobalExceptionHandler.java         # @RestControllerAdvice — maps exceptions → HTTP status
│   ├── security/
│   │   ├── CorrelationIdFilter.java            # Filter 0: injects X-Correlation-ID into MDC (Order 0)
│   │   ├── ApiKeyAuthFilter.java               # Filter 1: validates X-API-Key header → 401 (Order 1)
│   │   ├── ApiKeyProperties.java               # Holds configured API keys (Set<String>)
│   │   └── RateLimitFilter.java                # Filter 2: per-key Redis counter → 429 (Order 2)
│   └── service/
│       ├── AirportService.java                 # Primary service interface — returns AirportResponse
│       ├── FallbackAirportService.java         # Fallback service interface — implemented by each fallback provider
│       └── impl/
│           ├── AirportServiceImpl.java         # Cache + CircuitBreaker(fallback) + Retry + Feign + map
│           └── Provider2AirportService.java    # Implements FallbackAirportService: CB + Retry + map Provider 2 DTO
│
├── main/resources/
│   └── application.yml                         # All configuration (Redis, Feign, Resilience4j, logging)
│
└── test/java/com/sporty/aviation/
    ├── client/
    │   └── AviationWeatherFeignClientTest.java # Integration tests (WireMock)
    ├── controller/
    │   └── AirportControllerTest.java          # Web layer: MockMvc, auth, rate limit, response shape
    ├── security/
    │   ├── CorrelationIdFilterTest.java        # Unit: UUID generation, MDC cleanup, header echo
    │   ├── ApiKeyAuthFilterTest.java           # Unit: auth filter — valid/invalid/bypass paths
    │   └── RateLimitFilterTest.java            # Unit: Lua script counter, 429, bypass paths, fail-open
    └── service/
        ├── AirportServiceTest.java             # Unit: primary API path, uppercase fix, AirportResponse mapping
        └── Provider2AirportServiceTest.java    # Unit: fallback mapping, error handling
```

---

## 2. Full Request Flow

Every incoming request passes through layers in a fixed order.
Each layer is only reached if the one above it does not short-circuit the call.
When the primary API is unavailable, the flow **automatically falls back to Provider 2**
(AirportDB.io) — transparently, with no change to the API response shape.

```
GET /api/v1/airports/EGLL
        │
        ▼
CorrelationIdFilter (Order 0)
  Reads X-Correlation-ID header or generates UUID → stores in MDC
  Every log line for this request includes the ID automatically
        │
        ▼
ApiKeyAuthFilter (Order 1)
  Valid X-API-Key? ── NO ──► 401 Unauthorized
        │ YES
        ▼
RateLimitFilter (Order 2)
  Redis Lua: INCR counter for this API key
  Over 60 req/min? ── YES ──► 429 Too Many Requests
        │ NO
        ▼
┌─── @Cacheable ─────────────────────────────────────────────────────┐
│  Redis key: airports::EGLL                                         │
│                                                                    │
│  HIT  ──────────────────────────────────────────► 200 OK (<1ms)  │
│       (Redis DOWN? → treated as miss, continues below)            │
│  MISS ─────────────────────────────────────────────────────────── ┤
└────────────────────────────────────────────────────────────────────┘
        │
        ▼
@CircuitBreaker(aviationApi) ── OPEN? ──────────────────────┐
        │ CLOSED                                             │
        ▼                                                    ▼
@Retry (up to 3×, 1s → 2s → 4s backoff)           fetchFromProvider2()
        │                                                    │
        ▼                                                    ▼
Feign → aviationweather.gov                    @CircuitBreaker(provider2Api)
(3s connect / 10s read)                        @Retry (up to 2×, 1s)
        │                                                    │
        │ success                                            ▼
        │                                     Feign → airportdb.io
        │                                     (2s connect / 5s read)
        │                                                    │
        │◄──────────────── success ──────────────────────────┘
        │
        ▼
AirportResponse.from(rawDto)      ← maps internal DTO to clean public response
        │
        ▼
Result stored in Redis (TTL 10 min)   ← caches BOTH primary and fallback results
(Redis DOWN? → write silently skipped, no error)
        │
        ▼
200 OK — AirportResponse

Both providers fail → 502 / 503 from GlobalExceptionHandler
```

| Layer | Library | What it does |
|---|---|---|
| `CorrelationIdFilter` | SLF4J MDC | Injects `X-Correlation-ID` into every log line for the request |
| `ApiKeyAuthFilter` | Servlet filter | Validates `X-API-Key` header — rejects unknown keys with `401` |
| `RateLimitFilter` | Redis Lua script | Atomically increments a per-key counter — rejects with `429` when exceeded |
| `@Cacheable` | Spring Cache + Redis | Returns cached data instantly. **Fail-open: Redis down = cache miss, not an error.** |
| `@CircuitBreaker(aviationApi)` | Resilience4j | Opens after 3 failures (50% threshold). When OPEN, skips primary and calls fallback immediately. |
| `@Retry(aviationApi)` | Resilience4j | Retries the primary Feign call up to 3× (1s → 2s → 4s backoff) on transient errors. |
| `Feign → aviationweather.gov` | Spring Cloud OpenFeign | Primary HTTP call. 3s connect / 10s read timeout. |
| `fetchFromProvider2()` | Resilience4j fallback | Triggered by CB OPEN, timeout, or 5xx. Delegates to `FallbackAirportService` (currently `Provider2AirportService`). |
| `@CircuitBreaker(provider2Api)` | Resilience4j | Independent CB for Provider 2. Opens after 2 failures. Stays open 60s. |
| `@Retry(provider2Api)` | Resilience4j | Retries Provider 2 up to 2×. Fewer retries — we've already spent time on primary. |
| `Feign → airportdb.io` | Spring Cloud OpenFeign | Fallback HTTP call. 2s connect / 5s read — shorter, fail fast in fallback path. |
| `AirportResponse.from()` | Static factory | Maps the internal raw DTO to the clean, documented public response. |

---

## 3. Response Format

The API always returns a clean, documented `AirportResponse` object.
The raw upstream DTOs (`AviationWeatherAirportDto`, `Provider2AirportDto`) stay
**internal only** — callers never depend on raw upstream field names.

### Success — `200 OK`

```json
{
  "icao":        "EGLL",
  "iata":        "LHR",
  "name":        "LONDON HEATHROW",
  "city":        "London",
  "country":     "GB",
  "latitude":    51.4775,
  "longitude":   -0.461389,
  "timezone":    null,
  "elevationFt": 83,
  "type":        "large_airport"
}
```

| Field | Type | Description |
|---|---|---|
| `icao` | `String` | 4-character ICAO airport identifier |
| `iata` | `String` | 3-character IATA code (may be `null` for military/private airfields) |
| `name` | `String` | Full official airport name |
| `city` | `String` | Nearest city/municipality (populated by fallback provider; `null` for primary) |
| `country` | `String` | ISO 3166-1 alpha-2 country code (e.g. `GB`, `US`) |
| `latitude` | `Double` | Latitude in decimal degrees — WGS-84, positive = North |
| `longitude` | `Double` | Longitude in decimal degrees — WGS-84, positive = East |
| `timezone` | `String` | IANA timezone — always `null` (not provided by current sources; reserved) |
| `elevationFt` | `Integer` | Elevation above mean sea level in feet |
| `type` | `String` | Airport type (`large_airport`, `medium_airport`, `small_airport`, or raw facility code) |

> **`city` note:** the primary Aviation Weather API (NOAA) does not include a city field.
> `city` is only populated when the response comes from the fallback provider (AirportDB).

---

## 4. API Key Authentication

All requests to `/api/v1/**` must include a valid API key in the `X-API-Key` header.

```bash
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost:8080/api/v1/airports/EGLL
```

Missing or invalid key → **`401 Unauthorized`**

```json
{
  "status":    401,
  "error":     "Unauthorized",
  "message":   "Missing or invalid API key. Include a valid 'X-API-Key' header.",
  "path":      "/api/v1/airports/EGLL",
  "timestamp": "2026-03-23T10:00:00"
}
```

**Excluded from auth (always accessible without a key):**

| Path | Why |
|---|---|
| `/actuator/**` | Health checks must be reachable by Docker / load balancer |
| `/swagger-ui/**` | Developers must be able to read the API docs |
| `/v3/api-docs/**` | OpenAPI spec download |

**Configure keys** in `application.yml` or via environment variable:

```yaml
# application.yml — dev keys only, never commit real keys
security:
  api-keys: sporty-dev-key-abc123,sporty-dev-key-xyz789
```

```bash
# Production — override via environment variable
SECURITY_API_KEYS=prod-key-1,prod-key-2
```

---

## 5. Rate Limiting

Each API key is limited to **60 requests per minute** (fixed window per key).
The counter is stored in **Redis** using an atomic Lua script (INCR + EXPIRE in one round-trip).

```
X-RateLimit-Limit: 60          ← always present on every response
X-RateLimit-Remaining: 45      ← requests left in the current window
Retry-After: 42                ← only on 429 — seconds until window resets
```

**Why a Lua script?**

The Lua script runs atomically on the Redis server — both `INCR` and `EXPIRE` succeed
or neither does. This guarantees the counter always has a TTL from the first request,
preventing a permanently blocked API key if the process stops mid-operation.

**If Redis is down:** counter returns `null` → treated as `0` → request passes through.
Rate limiting is temporarily disabled but the service stays available (fail-open).

**Override the limit:**
```yaml
security:
  rate-limit:
    requests-per-minute: 100
```
Or via environment variable: `SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE=100`

---

## 6. Correlation ID / Request Tracing

Every request is tagged with a **Correlation ID** — a UUID that appears in every log line
produced during that request. This makes it trivial to trace a single request across
all log entries, even in a multi-instance deployment.

**How it works:**

1. `CorrelationIdFilter` (`@Order(0)`) runs before all other filters.
2. It reads the `X-Correlation-ID` request header if the caller provides one
   (useful for end-to-end distributed tracing).
3. If not provided, a new UUID is generated.
4. The ID is stored in **SLF4J MDC** under the key `correlationId`.
5. The ID is echoed back in the `X-Correlation-ID` response header.
6. The MDC entry is **always cleared** in a `finally` block — critical for Tomcat
   thread-pool reuse (prevents the previous request's ID from leaking into the next request).

**Log pattern** (configured in `application.yml`):
```
2026-03-23 10:30:00 [a1b2c3d4-e5f6-...] INFO  c.s.a.s.i.AirportServiceImpl - Cache miss — fetching...
```

**Pass your own ID (distributed tracing):**
```bash
curl -H "X-API-Key: sporty-dev-key-abc123" \
     -H "X-Correlation-ID: my-trace-id-123" \
     http://localhost:8080/api/v1/airports/EGLL
```
The same ID appears in the response header and in all server-side logs for that request.

---

## 7. Redis Cache — Fail-Open

- **Cache name:** `airports`
- **Redis key format:** `airports::<UPPERCASE_ICAO>` (e.g. `airports::EGLL`)
- **TTL:** 10 minutes (configurable via `aviation.cache.ttl-minutes`)
- **Serialisation:** JSON (human-readable with `redis-cli GET "airports::EGLL"`)

### Redis is optional — the service works without it

A custom `RedisCacheErrorHandler` is registered in `RedisConfig`. When Redis is down:

| Cache operation | Without the handler | With the handler |
|---|---|---|
| GET (cache read) | `RedisConnectionFailureException` → **500 error** | Logged as `WARN` → treated as **cache miss** |
| PUT (cache write) | `RedisConnectionFailureException` → **500 error** | Logged as `WARN` → **silently skipped** |

**Result:** Redis down = slightly slower responses (every call hits the external API)
but the service keeps returning **correct 200 responses**. When Redis recovers,
caching resumes automatically — no restart needed.

**Inspect cache entries directly:**
```bash
redis-cli KEYS "airports::*"         # all cached airports
redis-cli GET  "airports::EGLL"      # read a specific entry (JSON)
redis-cli TTL  "airports::EGLL"      # seconds until expiry
redis-cli DEL  "airports::EGLL"      # manually evict (forces fresh fetch)
```

---

## 8. Resilience Layer

### Timeout

Configured in `application.yml` under `spring.cloud.openfeign.client.config` (single source of truth — no hardcoded `Request.Options` bean):

| Provider | Config key | Connect timeout | Read timeout |
|---|---|---|---|
| Primary (aviationweather.gov) | `default` | 3 s | 10 s |
| Fallback (airportdb.io) | `airport-db-api` | 2 s | 5 s (fail fast — already spent time on primary) |

### Retry

On a transient failure, the call is retried automatically with **exponential backoff**:

**Primary API:**

| Attempt | Wait before next |
|---------|-----------------|
| 1 (original) | — |
| 2 | 1 second |
| 3 | 2 seconds |
| — (exhausted) | throws → CB records failure |

**Provider 2:** max 2 attempts, 1s fixed wait (fewer — we've already waited on primary).

**What triggers a retry:** `AviationApiException`, `IOException`, `feign.RetryableException`
(connection refused / DNS failure — extends `RuntimeException`, not `IOException`, so must
be listed explicitly).

**What is never retried:** `AirportNotFoundException` — the ICAO simply doesn't exist,
retrying will not change the result.

### Circuit Breaker

Two independent circuit breakers — primary and Provider 2 do not share state.

| Setting | Primary (`aviationApi`) | Provider 2 (`provider2Api`) |
|---|---|---|
| Sliding window | 10 calls | 5 calls |
| Min calls before opening | 3 | 2 |
| Failure rate to open | 50% | 50% |
| Wait when OPEN | 30 s | 60 s |
| Trial calls in HALF-OPEN | 3 | 2 |
| Transitions OPEN → HALF-OPEN | Automatic | Automatic |

**States:**

| State | Behaviour |
|-------|-----------|
| `CLOSED` | Normal operation |
| `OPEN` | All calls rejected immediately → fallback triggered. Stays open for wait duration. |
| `HALF-OPEN` | Limited trial calls sent. Success → `CLOSED`. Failure → `OPEN` again. |

### Aspect order

```
@Cacheable      — outermost  (Spring CacheInterceptor, Integer.MIN_VALUE)
  @CircuitBreaker — middle   (circuit-breaker-aspect-order = 1)
    @Retry        — innermost (retry-aspect-order = 2)
      Feign call
```

- **Cache hit** → method body never executes. No circuit breaker, no HTTP call.
- **Cache miss + CB OPEN** → fallback called immediately. No retry attempted.
- **Cache miss + CB CLOSED + failure** → retried up to 3×, then CB records the failure.

---

## 9. Fallback — Provider 2 (AirportDB)

When the primary Aviation Weather API is unavailable, the request is automatically
routed to **Provider 2 — AirportDB.io**. The caller receives the same `AirportResponse`
shape regardless of which provider served the data.

**Triggers:**

| Trigger | How it happens |
|---------|---------------|
| **Timeout** | Feign connect/read timeout → `IOException` → retries exhausted → CB failure → fallback |
| **5xx errors** | `AviationErrorDecoder` converts → `AviationApiException` → retries exhausted → fallback |
| **Circuit OPEN** | CB opens after 3 failed calls → `fetchFromProvider2()` called immediately |

**Provider 2 field mapping:**

| AirportDB field | Internal field (`AviationWeatherAirportDto`) | Public field (`AirportResponse`) |
|---|---|---|
| `icao` | `icaoId` | `icao` |
| `iata` | `iataId` | `iata` |
| `name` | `name` | `name` |
| `municipality` | `municipality` | `city` |
| `iso_country` | `country` | `country` |
| `latitude_deg` | `lat` (String → Double) | `latitude` |
| `longitude_deg` | `lon` (String → Double) | `longitude` |
| `elevation_ft` | `elev` | `elevationFt` |
| `type` | `type` | `type` |

**Fallback result is also cached:**
`@Cacheable` wraps the entire method including the fallback. A successful Provider 2
response is stored in Redis. The next request for the same ICAO hits the cache instantly —
no call to either provider is made, even while the primary API is still down.

**Both providers down:**
```
Primary CB OPEN → fetchFromProvider2()
    Provider 2 CB OPEN → AviationApiException propagates
        GlobalExceptionHandler → 503 Service Unavailable
```

**Setup for Provider 2:**
The token is read from the `AVIATION_PROVIDER2_API_TOKEN` environment variable — never hardcoded.
Register for a free token at [airportdb.io](https://airportdb.io), then set it before starting the app:
```bash
# Option A — inline (one-time run)
AVIATION_PROVIDER2_API_TOKEN=your_token_here ./mvnw spring-boot:run

# Option B — export in your terminal session
export AVIATION_PROVIDER2_API_TOKEN=your_token_here
./mvnw spring-boot:run

# Option C — .env file (gitignored, safe for secrets)
echo "AVIATION_PROVIDER2_API_TOKEN=your_token_here" >> .env
docker-compose up   # Docker Compose reads .env automatically
```

---

## 10. Prerequisites

### Option A — Run locally

| Tool | Minimum version |
|------|----------------|
| Java | 17 |
| Maven | 3.8+ |
| Redis | 6+ |

> Redis is **optional** — the app starts and serves responses without it.
> Without Redis: no caching (every call hits the external API), no rate limiting.

### Option B — Run with Docker

| Tool | Minimum version |
|------|----------------|
| Docker Desktop | 4.0+ (includes Docker Compose v2) |

> Supports **Apple Silicon (M1/M2/M3)** — the Dockerfile uses multi-arch images
> (`maven:3.9-eclipse-temurin-17` and `eclipse-temurin:17-jre-jammy`).

---

## 11. Setup & Run

### Option A — Run locally (development)

#### Step 1 — Start Redis (optional but recommended)

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
redis-cli ping   # expected: PONG
```

> If Redis is not running, the app still starts and serves requests —
> caching and rate limiting are silently bypassed.

#### Step 2 — Build

```bash
git clone <repository-url>
cd "Aviation API Wrapper"
./mvnw clean package -DskipTests
```

#### Step 3 — Run

```bash
./mvnw spring-boot:run
```

Or run the JAR directly:
```bash
java -jar target/aviation-api-wrapper-1.0.0.jar
```

The service starts on **port 8080**.

#### Step 4 — Try it

```bash
# London Heathrow
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost:8080/api/v1/airports/EGLL

# JFK New York
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost:8080/api/v1/airports/KJFK

# Dubai
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost:8080/api/v1/airports/OMDB
```

**Example response:**
```json
{
  "icao":        "EGLL",
  "iata":        "LHR",
  "name":        "LONDON HEATHROW",
  "city":        null,
  "country":     "GB",
  "latitude":    51.4775,
  "longitude":   -0.461389,
  "timezone":    null,
  "elevationFt": 83,
  "type":        "ARP"
}
```

---

### Option B — Run with Docker (2 instances + Nginx + Prometheus + Grafana)

Starts the full stack:
- 2 app instances (active-active, shared Redis)
- 1 Redis instance
- 1 Nginx load balancer (port 80)
- Prometheus (port 9090)
- Grafana (port 3000)

```
Client → Nginx (port 80) → aviation-api-wrapper-1 (internal :8080)
                          → aviation-api-wrapper-2 (internal :8080)
                                    ↕
                               Redis (shared)
```

#### Step 1 — Build and start

```bash
docker-compose up --build
```

#### Step 2 — Verify all containers are healthy

```bash
docker-compose ps
```

All services should show `healthy` or `running`:
```
NAME                   STATUS
aviation-redis         running (healthy)
aviation-api-wrapper-1     running (healthy)
aviation-api-wrapper-2     running (healthy)
aviation-nginx         running
aviation-prometheus    running
aviation-grafana       running
```

#### Step 3 — Try it (all requests go through Nginx on port 80)

```bash
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost/api/v1/airports/EGLL
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost/api/v1/airports/KJFK
```

#### Step 4 — Simulate instance failure (zero-downtime test)

```bash
# Stop instance 1 — Nginx detects the failure and routes all traffic to instance 2
docker stop aviation-api-wrapper-1

# Still works
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost/api/v1/airports/EGLL

# Restart — Nginx automatically adds it back into rotation
docker start aviation-api-wrapper-1
```

#### Step 5 — Verify Redis is shared across instances

```bash
# Stop instance 1, populate cache via instance 2
docker stop aviation-api-wrapper-1
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost/api/v1/airports/OMDB

# Restart instance 1 — it immediately benefits from instance 2's cache
docker start aviation-api-wrapper-1
curl -H "X-API-Key: sporty-dev-key-abc123" http://localhost/api/v1/airports/OMDB
# Returns instantly from Redis — no API call made
```

#### Step 6 — Tail live logs from each instance

Log files are mounted from inside the containers to your host machine:

```bash
tail -f logs/app1/aviation.log   # instance 1
tail -f logs/app2/aviation.log   # instance 2
```

A **cache miss** (first call for an ICAO) logs:
```
Cache miss — fetching from primary API (aviationweather.gov) for ICAO: EGLL
```
A **cache hit** (second call for the same ICAO) produces **no log line** from the service — Spring intercepts it before the method is entered.

#### Stop everything

```bash
docker-compose down
```

---

### Override configuration without redeployment

```bash
SPRING_DATA_REDIS_HOST=redis.internal ./mvnw spring-boot:run
AVIATION_CACHE_TTL_MINUTES=5         ./mvnw spring-boot:run
SECURITY_API_KEYS=key1,key2          ./mvnw spring-boot:run
AVIATION_PROVIDER2_API_TOKEN=token   ./mvnw spring-boot:run
```

---

## 12. API Reference

### `GET /api/v1/airports/{icaoCode}`

#### Required header

| Header | Value | Description |
|---|---|---|
| `X-API-Key` | a valid API key | Configured in `security.api-keys` |

#### Path parameter

| Parameter | Rules |
|---|---|
| `icaoCode` | Exactly 4 alphanumeric characters — case-insensitive (`EGLL` = `egll`) |

#### Responses

| Status | When |
|---|---|
| `200 OK` | Airport found — returns `AirportResponse` |
| `400 Bad Request` | ICAO code is not exactly 4 alphanumeric characters |
| `401 Unauthorized` | `X-API-Key` header is missing or invalid |
| `404 Not Found` | No airport exists for that ICAO code |
| `429 Too Many Requests` | API key exceeded 60 requests/minute; check `Retry-After` header |
| `502 Bad Gateway` | Both providers returned an error after all retries |
| `503 Service Unavailable` | Circuit breaker OPEN — back off ~30 s and retry |

All error responses share the same JSON structure:
```json
{
  "status":    404,
  "error":     "Not Found",
  "message":   "No airport found for ICAO code: ZZZZ",
  "path":      "/api/v1/airports/ZZZZ",
  "timestamp": "2026-03-23T10:30:00"
}
```

### Swagger UI

| What | URL |
|---|---|
| Interactive UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| OpenAPI YAML | `http://localhost:8080/v3/api-docs.yaml` |

When running with Docker: use `http://localhost/…` (port 80, via Nginx).

---

## 13. Running Tests

```bash
./mvnw test
```

### Run a specific test class

```bash
./mvnw test -Dtest=AirportServiceTest
./mvnw test -Dtest=AirportControllerTest
./mvnw test -Dtest=ApiKeyAuthFilterTest
./mvnw test -Dtest=RateLimitFilterTest
./mvnw test -Dtest=CorrelationIdFilterTest
./mvnw test -Dtest=Provider2AirportServiceTest
./mvnw test -Dtest=AviationWeatherFeignClientTest
```

### Test coverage summary

| Test class | Type | What it covers |
|---|---|---|
| `AirportServiceTest` | Unit (Mockito) | Primary API returns `AirportResponse`; empty list → `AirportNotFoundException`; lowercase ICAO uppercased before Feign call; `city` field populated when `municipality` present |
| `Provider2AirportServiceTest` | Unit (Mockito) | Provider 2 response mapped to `AviationWeatherAirportDto`; `AviationApiException` on failure; null/invalid coordinates handled safely |
| `AirportControllerTest` | Web layer (MockMvc) | `200 OK` with clean `AirportResponse` fields; `401` on missing/invalid key; `429` on rate limit; `404` on unknown ICAO; `400` on invalid format; swagger/api-docs bypass auth |
| `ApiKeyAuthFilterTest` | Unit (Mockito) | Missing/empty/invalid key → `401`; valid key passes; actuator, swagger-ui, api-docs bypass auth |
| `RateLimitFilterTest` | Unit (Mockito) | Lua script called once per request; separate `expire()` never called; under-limit passes; `429` with `Retry-After`; rate-limit headers present; Redis down → fail-open; actuator/swagger/api-docs bypass |
| `CorrelationIdFilterTest` | Unit (Mockito) | UUID generated when no header; caller-supplied ID reused; ID echoed in response header; MDC cleared after request; filter chain always called |
| `AviationWeatherFeignClientTest` | Integration (WireMock) | Full JSON deserialization; empty list on unknown ICAO; `AviationApiException` on 500/429 |

> **Note on resilience tests:** `@Retry` and `@CircuitBreaker` are AOP proxies — they are
> bypassed when service classes are instantiated directly with `@InjectMocks`.
> Circuit-breaker and fallback-routing behaviour requires a full Spring context integration
> test (a known gap — planned as a future addition).

---

## 14. Configuration Reference

All configuration lives in `src/main/resources/application.yml`.
All values can be overridden via environment variables without redeployment.

```yaml
spring:
  application:
    name: aviation-api-wrapper
  data:
    redis:
      host: localhost          # override: SPRING_DATA_REDIS_HOST
      port: 6379               # override: SPRING_DATA_REDIS_PORT

  cloud:
    discovery:
      enabled: false           # Feign uses explicit URLs — no service registry needed
    openfeign:
      client:
        config:
          default:             # Primary API (Aviation Weather)
            connect-timeout: 3000    # ms
            read-timeout: 10000      # ms
          airport-db-api:      # Provider 2 (AirportDB) — shorter, fail fast
            connect-timeout: 2000
            read-timeout: 5000

server:
  port: 8080

security:
  api-keys: sporty-dev-key-abc123,sporty-dev-key-xyz789  # override: SECURITY_API_KEYS
  rate-limit:
    requests-per-minute: 60                               # override: SECURITY_RATE_LIMIT_REQUESTS_PER_MINUTE

aviation:
  api:
    base-url: https://aviationweather.gov/api/data
  cache:
    ttl-minutes: 10                                       # override: AVIATION_CACHE_TTL_MINUTES
  provider2:
    base-url: https://airportdb.io/api/v1
    api-token: ${AVIATION_PROVIDER2_API_TOKEN:}           # NEVER hardcode — set via env variable

resilience4j:
  circuitbreaker:
    circuit-breaker-aspect-order: 1      # CB wraps Retry (CB=1 outer, Retry=2 inner)
    instances:
      aviationApi:
        register-health-indicator: true  # exposes CLOSED/OPEN/HALF_OPEN in /actuator/health
        sliding-window-size: 10
        minimum-number-of-calls: 3       # open after just 3 failures — prevents cascade
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
        record-exceptions:
          - com.sporty.aviation.exception.AviationApiException
          - java.io.IOException
        ignore-exceptions:
          - com.sporty.aviation.exception.AirportNotFoundException
      provider2Api:
        register-health-indicator: true  # exposes CLOSED/OPEN/HALF_OPEN in /actuator/health
        sliding-window-size: 5
        minimum-number-of-calls: 2
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 2
        automatic-transition-from-open-to-half-open-enabled: true
  retry:
    retry-aspect-order: 2
    instances:
      aviationApi:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - com.sporty.aviation.exception.AviationApiException
          - java.io.IOException
          - feign.RetryableException    # connection refused / DNS failure
        ignore-exceptions:
          - com.sporty.aviation.exception.AirportNotFoundException
      provider2Api:
        max-attempts: 2
        wait-duration: 1s

logging:
  file:
    name: logs/aviation.log              # written to project-root/logs/aviation.log
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%X{correlationId:-no-id}] %-5level %logger{36} - %msg%n"
    file:    "%d{yyyy-MM-dd HH:mm:ss} [%X{correlationId:-no-id}] %-5level %logger{36} - %msg%n"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,circuitbreakers,retries,caches,loggers,env
  endpoint:
    health:
      show-details: always
    env:
      show-values: always                # shows real values instead of ******
```

---

## 15. Assumptions

The following assumptions were made during design and implementation:

### API & data
- **ICAO codes are case-insensitive.** The app normalises every input to uppercase before calling any external API, so `egll`, `EGLL`, and `Egll` all resolve to the same airport and the same Redis cache key.
- **Airport data is stable enough to cache for 10 minutes.** Runway, frequency, and location data changes infrequently. A 10-minute TTL is a reasonable balance between freshness and API load. It is configurable via `AVIATION_CACHE_TTL_MINUTES`.
- **The primary API (aviationweather.gov) is publicly accessible with no authentication.** No API key or OAuth token is required for the Aviation Weather endpoint used here.
- **Provider 2 (airportdb.io) requires a token.** The token is injected via `AVIATION_PROVIDER2_API_TOKEN` and is never committed to source control.

### Infrastructure
- **Redis is optional.** If Redis is unavailable, the app starts and serves requests without caching or rate limiting — it does not refuse to start. This makes local development simpler and avoids hard infrastructure dependencies.
- **The app runs behind Nginx.** TLS termination, load balancing, and port exposure are delegated to the reverse proxy. The Spring app itself only listens on `8080` internally.
- **Two app instances are sufficient for the demo deployment.** The Docker Compose setup runs exactly two instances to demonstrate Redis-shared caching and Nginx round-robin routing. A real deployment would use a container orchestrator.

### Security
- **Rate limiting is enforced per API key, not per IP.** This is more meaningful for a B2B API where each consumer has a dedicated key. The limit (60 req/min) is configurable.
- **API keys are long-lived secrets passed via the `X-API-Key` header.** No token rotation or OAuth flow is implemented; this matches the scope of the task.
- **Actuator endpoints are intentionally exposed without auth.** In production these would be on a separate internal port or protected by network policy. For this task they are open for ease of review.

### Resilience
- **The circuit breaker counts failures after all retries are exhausted.** A single transient failure that succeeds on retry does not penalise the circuit breaker. Only calls that fail all retry attempts count as failures.
- **The fallback provider is a best-effort supplement, not a guaranteed mirror.** Provider 2 (AirportDB) covers most ICAO codes but may lack some military or private aerodromes that the primary API includes.

---

## 16. Architecture Decisions

### 16.1 Clean response DTO — `AirportResponse`

The raw upstream DTOs (`AviationWeatherAirportDto`, `Provider2AirportDto`) stay internal.
The API always returns `AirportResponse` with clean, self-describing field names.
The static factory method `AirportResponse.from(rawDto)` is the **single mapping point** —
any upstream field renames are isolated there.

### 16.2 Redis is optional — `RedisCacheErrorHandler`

`RedisConfig` implements `CachingConfigurer` and registers `RedisCacheErrorHandler`.
Every Redis exception during a cache operation (GET, PUT, EVICT, CLEAR) is caught,
logged as a `WARN`, and swallowed. Spring treats the result as a cache miss.
The service continues to serve correct responses whether Redis is up or down.

### 16.3 Annotation order: `@Cacheable` → `@CircuitBreaker` → `@Retry`

```
@Cacheable      — outermost (Spring AOP, Integer.MIN_VALUE)
  @CircuitBreaker — middle   (circuitBreakerAspectOrder = 1)
    @Retry        — innermost (retryAspectOrder = 2)
      Feign call
```

Cache hit short-circuits everything. CB OPEN short-circuits retry.

### 16.4 ICAO key normalised to upper-case

The `@Cacheable` key is `#icaoCode.toUpperCase()`. The Feign call also uppercases before
sending: `aviationWeatherFeignClient.getAirportByIcao(icaoCode.toUpperCase())`.
`egll`, `EGLL`, `Egll` → same Redis entry `airports::EGLL`.

### 16.5 `AirportNotFoundException` is never retried

When the Aviation Weather API returns `[]`, the airport does not exist.
Retrying makes 3 identical calls to the same result. Both `retry-exceptions` and
`record-exceptions` exclude `AirportNotFoundException`.

### 16.6 `FallbackAirportService` interface + `Provider2AirportService` implementation

Resilience4j annotations work via Spring AOP proxies — a private method in the same
class is never proxied. `fetchFromProvider2()` in `AirportServiceImpl` is private and
delegates to `FallbackAirportService.getAirportByIcao()`, which is public on a separate
Spring bean (`Provider2AirportService`). This guarantees `@CircuitBreaker(provider2Api)`
and `@Retry(provider2Api)` are actually enforced.

`AirportServiceImpl` depends on the `FallbackAirportService` interface, not the concrete
class. Adding a Provider 3 requires only a new implementation of `FallbackAirportService`
in the `impl` package — the caller (`AirportServiceImpl`) does not change.

### 16.7 Atomic rate limiting with Lua script

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
```

Runs atomically on the Redis server — both commands succeed or neither does.
This guarantees the counter always has a TTL, preventing a permanently blocked API key.

### 16.8 Two instances + Nginx for scalability

| Concern | How handled |
|---|---|
| **Cache** | Redis — instance 1 caches EGLL, instance 2 reads the same entry |
| **Rate limiting** | Redis — 60 req/min per API key, shared across all app instances |
| **Session state** | None — fully stateless |
| **Circuit breaker state** | Per-instance (JVM memory) — known trade-off, acceptable because Redis cache reduces actual API calls dramatically |
| **Real client IP** | Nginx sets `X-Real-IP`; both filters read it for accurate logging |

---

## 17. Error Handling

All exceptions are handled in `GlobalExceptionHandler` (`@RestControllerAdvice`).

| Exception | HTTP | Cause |
|---|---|---|
| `AirportNotFoundException` | `404` | ICAO code valid but not in the Aviation Weather database |
| `AviationApiException` | `502` | Upstream returned 4xx/5xx, or all retries exhausted |
| `CallNotPermittedException` | `503` | Circuit breaker OPEN — back off ~30 s |
| `ConstraintViolationException` | `400` | ICAO code failed `@Size`/`@Pattern` validation |
| `Exception` (catch-all) | `500` | Unexpected runtime error |

**502 vs 503:**
- `502` — the upstream API responded (or timed out). Problem is external.
- `503` — this service has stopped trying because too many recent calls failed. Back off.

---

## 18. Observability

### Spring Actuator endpoints

| Endpoint | URL | Description |
|---|---|---|
| Health | `/actuator/health` | Overall status + circuit breaker states (CLOSED/OPEN/HALF_OPEN) |
| Circuit breakers | `/actuator/circuitbreakers` | CB metrics per instance (failure rate, call counts) |
| Retries | `/actuator/retries` | Retry attempt statistics |
| Metrics | `/actuator/metrics` | All Micrometer metrics |
| Prometheus | `/actuator/prometheus` | Prometheus-format metrics (scraped by Prometheus) |
| Caches | `/actuator/caches` | Cache names and statistics |
| Loggers | `/actuator/loggers` | Change log levels at runtime without restart |
| Env | `/actuator/env/{property}` | Inspect any config value and see which source set it (e.g. env var vs yml) |

### Prometheus + Grafana

When running with Docker:

| Tool | URL | Credentials |
|---|---|---|
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3000` | admin / admin |

Prometheus scrapes `/actuator/prometheus` from both app instances every 15 seconds.
Grafana auto-provisions Prometheus as a datasource on startup.

### Correlation IDs in logs

Every log line includes `[correlationId]` from SLF4J MDC.
Pass `X-Correlation-ID: my-trace-id` in the request to correlate server logs with your
own client-side traces.

```
2026-03-23 10:30:00 [a1b2c3d4-e5f6-7890-...] INFO  c.s.a.s.i.AirportServiceImpl - Cache miss — fetching from primary API for ICAO: EGLL
2026-03-23 10:30:00 [a1b2c3d4-e5f6-7890-...] WARN  c.s.a.s.i.AirportServiceImpl - Primary API unavailable [IOException] — routing to Provider 2 for ICAO: EGLL
```

---

## 19. Deployment, Scaling & Monitoring

### How to scale

**Add more instances:** duplicate the service entry in `docker-compose.yml` and add
the new instance to `nginx.conf`. Nginx automatically distributes traffic.

**Kubernetes:** each instance is fully stateless and identical. Deploy as a `Deployment`
with `replicas: N`. Use a `Service` for internal routing. Redis can be a managed service
(AWS ElastiCache, GCP Memorystore) rather than a container.

### What makes the service production-ready

| Requirement | How it is met |
|---|---|
| **Resilience** | Circuit breaker + retry + fallback provider — the service degrades gracefully at every failure point |
| **Cache independence** | `RedisCacheErrorHandler` — Redis outage does not affect correctness, only performance |
| **Statelessness** | No session state; all shared state (cache, rate limit counters) lives in Redis |
| **Observability** | Prometheus metrics, Grafana dashboards, structured logs with correlation IDs |
| **Security** | API key auth on all endpoints; rate limiting per key; keys injected via env variables |
| **Multi-arch Docker** | `eclipse-temurin:17-jre-jammy` (multi-arch) — runs on both amd64 and arm64 (Apple Silicon) |
| **Graceful shutdown** | JVM is PID 1 (exec form `ENTRYPOINT`) — receives `SIGTERM` from `docker stop` directly |
| **Non-root container** | Runs as dedicated `aviation` user — no root privileges inside the container |
| **Configuration** | All values overridable via environment variables — no redeployment needed |
