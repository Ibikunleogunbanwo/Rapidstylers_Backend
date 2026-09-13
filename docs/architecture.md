# RapidStylers Backend — System Architecture Preview

_Current state as read from the code and configuration on `dev`, September 2026. This is a preview of the architecture as it stands, not a target design._

> **Update — September 13, 2026 (simplification applied).** This document was the
> input to the phased simplification review. That review's verdicts, evidence,
> and what has since shipped live in [`./simplification-plan.md`](./simplification-plan.md):
> schema authority is now **Flyway-only** (`V13` full baseline, `ddl-auto=validate`,
> the three boot reconcilers deleted), the payment domain is **carved out of
> `AppService`** into `PaymentOpsService` + the shared `AuditService`/`MoneyUtils`,
> and the observability stack was verified as three distinct gated roles and kept.
> The launch acceptance gate lives in [`./launch-gate-uat.md`](./launch-gate-uat.md).

---

## 1. Topology — one Spring Boot jar + five supporting systems

```
                          ┌────────────────────────────────────────────┐
  React SPA (Vercel) ────▶│  nginx → RapidStylers API (Boot 2.7 / Java 17) │
  (x-api-key + JWT)       └──────┬──────────┬──────────────┬───────────┘
                                 │          │              │
                         ┌───────▼───┐ ┌────▼─────┐  ┌─────▼────────┐
                         │ MySQL 8   │ │ Redis    │  │ Kafka broker │
                         │ (27 ent.) │ │ (7 svcs) │  │ 3 topics     │
                         └───────────┘ └──────────┘  └──────────────┘
   External SaaS:  Stripe Connect · Resend (email) · Cloudinary (media uploads)
                   Google (OAuth + Geocoding) · Pexels · Sentry + BetterStack (logs)
```

**Stack summary**

| Layer | Technology |
|---|---|
| Runtime | Spring Boot 2.7, Java 17, single executable jar |
| Persistence | MySQL 8 — **Flyway owns the schema** (`V1`–`V13`, where `V13` is a full 28-table baseline); Hibernate runs `ddl-auto=validate` (see §6) |
| Cache / state | Redis — consumed by 7 services (§4) |
| Messaging | Kafka broker, 3 topics (domain-events / retry / DLQ), outbox pattern (§5) |
| Email | Resend REST API (replaces legacy Gmail SMTP) |
| Payments | Stripe Connect (cards on file, payment intents, Connect payouts, platform commission) |
| Media | Cloudinary signed direct uploads (backend signs) |
| Maps | Google Places/Geocoding |
| Observability | Actuator (health + metrics) · Sentry · BetterStack HTTP appender (logstash encoder) |

---

## 2. Request pipeline — every call passes 3 gates

```
Browser → CORS filter → AppConfig (x-api-key) → JwtAuthFilter (JWT roles) → Controller → Service → Repo/Redis/Kafka
```

1. **CORS filter** — strict origin allowlist (`http://localhost:3000`, `http://localhost:9090`, `https://rapidstylers.ca`, `https://www.rapidstylers.ca` by default), no credentials, no wildcard, narrow methods/headers (incl. `X-Step-Up-Password`).
2. **`AppConfig` — shared `x-api-key` gate.** Everything requires the key *except* three exempt paths:
   - `/rapid_stylers/files/*` (public file serving)
   - `/rapid_stylers/stripe/webhook` (Stripe-signed)
   - `/actuator/health` (Docker/Nginx probe; reports only UP/DOWN)
3. **`JwtAuthFilter` + method-level `@PreAuthorize`** — stateless JWT with roles `CUSTOMER / STYLER / ADMIN`; sensitive admin ops add a **step-up re-auth** via the `X-Step-Up-Password` header. Admin management lives in `AdminAccountController`, auth endpoints in `AdminAuthController`, the long tail of product endpoints in `ApplicationController`.

**Code surface:** controllers (6 files) → service layer (18 classes) → 27 JPA repos. **123 endpoints total**, ~85% living in `ApplicationController` (1,209 lines) backed by `AppService` (**5,570 lines — ~35% of the whole backend**).

---

## 3. Auth & session model (the security layer)

| Control | Design |
|---|---|
| Access tokens | JWT, **15 min** TTL, stateless |
| Refresh tokens | Rotating, **7 days**, family burn on replay (revoked-token replay revokes the whole family), per-account housekeeping job caps live tokens |
| Idle sessions | Redis last-activity: Customer **60 min**, Styler **30 min**, Admin **30 min**; a refresh is refused and the family revoked once idle past the window |
| Admin cap | Absolute **8 h** re-login even when active |
| Step-up | Re-auth password header for refunds / admin-account changes |
| OTPs | Signup/reset codes **hashed at rest** (BCrypt), enumeration-safe endpoints, identical signup/reset email bodies |
| Passwords | **BCrypt(10)**, legacy unsalted MD5 auto-upgrades on login |
| At-rest crypto | AES via `ENCRYPT_KEY` (boot-time guard refuses the hardcoded fallback outside tests); `/decrypt` locked to ADMIN |
| Injection | All queries parameterized (JPQL named params, native `@Query :params`, `JdbcTemplate ?` binding) — no user input reaches SQL |
| Sanitization | Server-side text sanitizer on free-text writes; React escapes on render |
| Headers | nosniff, frame DENY, HSTS (secure requests), CSP (`default-src 'none'` + self/inline allowances for the bundled Swagger UI), no CORS credentials |

---

## 4. Redis — 7 consumers of one broker

| Service | Purpose | Notes |
|---|---|---|
| `RateLimiterService` | Lua bucket limits + lockouts (OTP/sign-in/reset/admin) | Fail-closed in-memory fallback when Redis is down |
| `ReadCacheService` | Read-through cache: single-flight per key, TTL + jitter, degradation → direct DB | Jackson-typed values; warmers + eviction namespaces |
| `IdempotencyService` | Write-safety claims | Plain strings |
| `SessionActivityService` | Idle-window tracking | Plain strings |
| `LocationCacheService` | Stylist **geo index** (Redis GEO), rebuilt at boot + 30-min reconcile | Plain strings |
| `NotificationDedupService` | Email dedup | Plain strings |
| `StepUpService` | Step-up bookkeeping | Plain strings |

Serializer rule (documented in `RedisConfig`): only the read cache uses the Jackson value serializer; everything else deliberately uses `StringRedisTemplate` so plain-string readers never break.

---

## 5. Background machinery — 10 classes with boot/scheduled hooks

_(The three schema reconcilers that used to run on every boot — `InnoDbReconciler`,
`SlotLockUniqueReconciler`, `CardDetailsEncryptionMigration` — are gone; Flyway
`V13` owns the schema and the race-guard index. See §6.1.)_

| Hook | Class | Job |
|---|---|---|
| Boot | `DataInitializer` | Seeds 5 service categories + 4 blog articles on first run |
| Boot | `AdminAccountInitializer` | Seeds the first admin account from env (BCrypt) |
| Boot | `EncryptionConfig` | AES key wiring + boot guard on the fallback key |
| Boot | `RedisStartupMonitor` | Diagnoses Redis connectivity at boot (once per JVM) |
| Boot | `CacheWarmer` | Non-blocking, bounded warm of catalog + approved stylist profiles after cold start |
| 30 min | AppService / geo | Rebuilds + clears the stylist geo index |
| 5 s | `OutboxPublisherJob` | Polls the outbox table (top 50 PENDING) → publishes to Kafka |
| on event | `NotificationEventConsumer` | Kafka listener → Resend emails with retry + DLQ topics, dedup via Redis |
| scheduled | `PaymentReconciliationService` / `PayoutReversalService` | Stripe payment + Connect payout reconciliation |
| scheduled | `SignupFollowUpService` | Stage-based follow-up emails to signups without bookings |
| scheduled | `RefreshTokenService` | Housekeeping: delete expired refresh tokens, cap live per account |
| scheduled | `RateLimiterService` | Lockout cleanup |

---

## 6. Two structural facts most code hangs off

1. **Schema authority — resolved (was: dual).** Flyway used to run V1–V12 *while*
   Hibernate ran `ddl-auto=update`, so Hibernate silently created tables Flyway
   didn't know about (e.g. `booking_slot_locks`) and boot-time reconcilers existed
   to patch the constraints Flyway couldn't add. There is now **one owner**:
   `V13__full_schema_baseline.sql` defines the complete 28-table schema (including
   the `uk_booking_slot_styler_date_start` race-guard index on
   `booking_slot_locks`), Hibernate runs `ddl-auto=validate`, and
   `InnoDbReconciler` / `SlotLockUniqueReconciler` / `CardDetailsEncryptionMigration`
   are deleted. On an existing database `V13` applies as a no-op (`IF NOT EXISTS`);
   on a fresh one it builds everything before Hibernate validates it. **Any future
   schema change ships as the next numbered migration.**

2. **Kafka exists to send email.** The transactional-outbox → Kafka → consumer pipeline (8 files, 3 topics with retry + DLQ) exists so booking/notification emails are not lost on transaction failure. Nothing else consumes those domain events. This remains a deliberate carry: removing the broker would free roughly 1 GB of VPS memory but lose the tested retry/DLQ semantics — revisit when worker-splitting or a second consumer domain becomes real (see `simplification-plan.md` §4).

---

## 7. Where the weight is (over-engineering map)

| Tier | What's heavy | Status (Sep 13, 2026) |
|---|---|---|
| Infra duplication | Read cache + geo index + outbox each replicate what MySQL + one simple queue job already give the app at its current scale | **Keep** — bounded, hardened, single-flight; the only open question is the Kafka broker (see §6.2) |
| Boot-time fixes as components | 5 reconcilers/migrations that belong in Flyway or one-time ops commands instead of running on every boot | **Resolved** — all three reconcilers deleted; Flyway `V13` owns the schema |
| Monolith services | `AppService` (was 5,570 LOC) + `ApplicationController` (1,209) with overlapping sign-in/auth paths | **In progress** — payment domain carved into `PaymentOpsService` (629 LOC) + `AuditService` + `MoneyUtils`; `AppService` is 5,080 LOC. Booking, notification, account and catalog carves remain |
| Ops sprawl | Kafka broker + Redis + Sentry + BetterStack + logstash encoder + Resend + Cloudinary + Google APIs behind a single jar | **Resolved (verified)** — not a duplicate pipeline: the logstash encoder only formats stdout, BetterStack is the token-gated transport, Sentry is the DSN-gated error tracker. All three are gated and cost nothing when unconfigured |
| Log noise | Degradation fallbacks and boot jobs produced per-call/per-context log floods (now throttled once per 60 s window and surfaced as `throttledlog.*` actuator metrics) | **Keep** — already throttled + metered |

---

---

# 8. VPS / production architecture (as deployed)

## 8.1 Domain & routing shape

```
rapidstylers.ca / www.rapidstylers.ca  →  Vercel (frontend, auto-deployed from frontend main)
api.rapidstylers.ca                    →  Cloudflare → VPS nginx → 127.0.0.1:9095 (API container)

Cloudflare : DNS + TLS + proxy + security layer (its IP ranges are trusted for X-Forwarded-For)
VPS        : single host, Docker Compose network — MySQL, Redis, Kafka, and the app
```

Only HTTP(S) is public. MySQL, Redis, Kafka, and admin tooling stay private behind Docker
networking, loopback bindings, firewall rules, or SSH tunnels.

## 8.2 Single-host Docker Compose (`docker-compose.prod.yml`)

| Service | Image | Visibility | Purpose |
|---|---|---|---|
| `mysql` | mysql:8.0 | private (no published ports) | Core marketplace data; InnoDB buffer pool 1 GB; strict durability for snapshot backups |
| `redis` | redis:7-alpine | private | Rate limits, read cache, geo, idempotency, sessions, dedup; AOF on |
| `kafka` | apache/kafka:4.1.2 | private | Domain-events broker; retention bounded (72 h / 5 GB) so disk can't fill |
| `kafka-init` | apache/kafka:4.1.2 | private, runs once | Creates the 3 topics (domain-events / retry / DLQ) |
| `app` | rapidstylers-api:latest (built here) | **only** `127.0.0.1:9095:9095` | The Spring Boot jar; healthcheck curls `/actuator/health` |

- Everything `depends_on` healthchecks (`service_healthy`), so the API waits for MySQL, Redis,
  and Kafka before starting. Volumes: `mysql-data`, `redis-data`, `kafka-data`.
- The host runs nginx to terminate TLS on `api.rapidstylers.ca` and proxy to the loopback port.
  From the app's perspective the proxy peer is the Docker bridge gateway — hence the
  `RATE_LIMIT_TRUSTED_PROXIES` default of loopback + `172.18.0.1/32` + Cloudflare ranges.

## 8.3 The app container

- **Multi-stage Dockerfile**: `maven:3.9-eclipse-temurin-17` build stage (deps cached via `dependency:go-offline`),
  then `eclipse-temurin:17-jre` runtime with a non-root `spring` user. Only `curl` is added (healthcheck).
- JVM sized by container limits: `-XX:MaxRAMPercentage=60.0`; compose sets `JAVA_TOOL_OPTIONS=-Xms512m -Xmx2g`.
- `SPRING_PROFILES_ACTIVE=prod`; full env matrix injected from the VPS `.env` — `APP_API_KEY`,
  `JWT_SECRET`/TTL, `ENCRYPT_KEY`, Stripe key sets + `STRIPE_MODE`, Resend, Cloudinary, Google,
  per-role session idle windows, Kafka topics/flags, dedup TTL, payout retry knobs, log/sentry tokens.
- Prod Redis runs at internal `redis:6379` **without a password by default** (it's private to the
  Docker network); `REDIS_PASSWORD` exists for the day `--requirepass` is enabled.
- Kafka is internal-only (`INTERNAL://kafka:19094`), single node, `replication-factor 1` — no UI, no
  external listener in prod (local compose adds `kafka-ui` and an external listener for dev only).

## 8.4 Deploy pipeline (GitHub Actions `ci.yml`)

```
push to dev         → Backend tests (MySQL service container; Redis/Kafka absent → fail-closed paths exercised)
                    → gitleaks secret scan
merge/push to main  → tests + gitleaks green → Deploy to VPS job:
    1. rsync tested commit to rapiddeploy@VPS:/opt/rapidstylers/backend/
       (excludes .env, .git, target, .github)
    2. docker compose -f docker-compose.prod.yml up -d --build app
    3. wait-for-health: curl 127.0.0.1:9095/actuator/health up to ~5 min
```

The frontend deploys independently on Vercel from its own repo; the only shared secret is the
`x-api-key` (`APP_API_KEY` / `REACT_APP_API_KEY`). The VPS `.env` lives only on the host — rsync
and the repo both exclude it, and gitleaks gates every push.

> Full CI/CD design and rationale: [`docs/ci-cd.md`](./ci-cd.md).

## 8.5 Ops checklist (from README) — the non-negotiables each deploy

1. All `rapidstylers-*` containers healthy (`docker compose ps`).
2. Boot log grep: `Redis connected ... probe=OK (PONG)` — if it says `Redis connection FAILED` the
   API starts anyway while rate limiting, geo, cache, idempotency, and dedup **silently degrade**.
3. Bootstrap admin seeded (weak passwords refused at boot).
4. Public smoke: `https://api.rapidstylers.ca/actuator/health` → 200 `UP`; catalog endpoint with the API key → 200.

## 8.6 What single-host reality means for the phase plan

- **One VPS, one app instance today.** Kafka consumers run inside the same JVM as the HTTP API;
  "split workers later" is aspirational, not current load.
- **Memory is the real budget**: JVM (up to 2 GB) + Kafka (1 GB heap) + MySQL (1 GB buffer pool) +
  Redis on one box. Every infra component must justify its runtime cost, not just its code cost.
- The prod shape is what makes Kafka/outbox, the read cache, and the geo index the prime
  de-over-engineering candidates: they are the only pieces whose removal changes what must run
  on the VPS at all.

---

---_This document was the input to the phased simplification review — every subsystem
got a keep / simplify / cut verdict, recorded with evidence in
[`docs/simplification-plan.md`](./simplification-plan.md). The launch acceptance
gate is [`docs/launch-gate-uat.md`](./launch-gate-uat.md). To stand up a working
copy of this system from scratch (local dev, VPS, CI/CD), see
[`docs/duplication-guide.md`](./duplication-guide.md)._
