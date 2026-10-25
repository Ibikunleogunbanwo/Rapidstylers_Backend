# App blueprint: build a new web app the RapidStylers way

This is a playbook for an AI coding agent (or a developer) starting a **new** web app on the same template as RapidStylers:
- a live site and a separate demo site
- one backend image running twice
- CI that tests, scans and deploys
- payments that can't touch real money in the wrong place

Read the whole file before writing code. RapidStylers (`Rapidstylers_Backend`, `Rapidstylers_Frontend`) is the working reference: when this file says "see the reference", open the named file there and copy the pattern.

---

## 0. How the agent should use this file

1. **Collect the inputs in section 1** from the owner before anything else. Don't guess domains, the currency or the payment model.
2. **Work through the phases in section 11 in order.** Each phase ends with a "done when" check. Don't start the next phase until it passes.
3. **Follow the working rules in section 12 the whole time.** They come from real mistakes on RapidStylers.
4. **Never put a secret in a file, commit, chat message or log.** Use variable names only. When you need to confirm a secret is set on a server, ask for the first 8 characters only.

---

## 1. Inputs to collect from the owner

| Input | RapidStylers value | Why it matters |
|---|---|---|
| App name and short code | RapidStylers, `rapid_stylers` | Database name, API path prefix, container names |
| Live domain | `rapidstylers.ca` | Vercel live project, CORS, emails |
| Demo domain | `demo.rapidstylers.ca` | Second Vercel project |
| API domains | `api.<domain>`, `api-demo.<domain>` | nginx sites on the VPS |
| Home time zone | `America/Edmonton` | App default clock (each vendor still has their own) |
| Currency and fee | CAD, 12% platform fee | Stripe settings |
| Payment model | Marketplace: customer pays, vendor gets paid out | Stripe Connect or plain Stripe |
| Who the user types are | Customer, Stylist (vendor), Admin, Manager | Roles and route allowlists |
| Email sender | Resend, `no-reply@<domain>` | Transactional email |
| Image hosting | Cloudinary | Signed uploads |
| VPS host and SSH user | one Linux VPS, deploy user | CI deploy target |

---

## 2. The shape of the system

```text
Browser
  │  https://<domain>              https://demo.<domain>
  ▼                                ▼
Vercel project "live"            Vercel project "demo"        (static React build + one Edge function)
  │  /<api_prefix>/* → api/proxy.mjs adds the API key and signed client-IP headers
  ▼                                ▼
Cloudflare → nginx on the VPS
  │  api.<domain> → 127.0.0.1:9095   api-demo.<domain> → 127.0.0.1:9096
  ▼                                ▼
docker compose (one VPS, one image)
  app        (live)              app-demo  (profile "demo")
      └──── mysql, redis, kafka (shared containers; demo can use its own DB name) ────┘
External: Stripe (test + live), Cloudinary, Resend, Google (Maps/Places, Timezone, Sign-in), Cloudflare Turnstile
```

Rules that keep this safe:
- **The browser never sees the API host or the API key.** It calls its own site at `/<api_prefix>/*`. The Vercel Edge function (`api/proxy.mjs` → `src/proxy/apiProxy.mjs` in the reference) relays the request with the key from a server-only variable. It sends the visitor's IP and geo in `X-RS-*` headers, signed with `FRONTEND_PROXY_SECRET`. The backend trusts those headers only when the secret matches (`FrontendProxyTrust` in the reference).
- **The VPS publishes only the API ports, and only on loopback.** MySQL, Redis and Kafka exist only inside the Docker network.
- **Live and demo run the same image.** They differ only in environment variables (section 4).

---

## 3. Repositories and branches

- Two repos: `<App>_Backend` (Spring Boot, Java 17+, Maven) and `<App>_Frontend` (React + Vite).
- Branches: **`dev`** is where work lands. **`main`** is what's deployed.
  - Commit on `dev`, run the full test suite, push `dev`, then fast-forward `main` with `git push origin dev:main`.
  - Never commit straight to `main`. Never let `main` and `dev` diverge.
  - The reference has `scripts/push-main.mjs` (`npm run release:push`) to do this atomically, and `branch-sync.yml`, which opens an issue if `dev` and `main` drift.
- Commit messages follow Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `ci:`.
- Optional release gates: `release-gates.json` lists each gate with its evidence. Promotion is refused while a gate is pending.

---

## 4. Environments: what's different where

| Setting | Local | Live (`app`) | Demo (`app-demo`) |
|---|---|---|---|
| `APP_ENV` | `local` | `live` | `demo` |
| Database | local MySQL | `${DB_NAME}` | `${DEMO_DB_NAME}` (keep it separate) |
| `APP_DEMO_SEED` / `DEMO_SEED` | optional | **always `false`** | `true` when you want showroom data |
| `APP_DEMO_READ_ONLY` / `DEMO_READ_ONLY` | false | false | `true` normally; `false` only while QA-ing bookings |
| Stripe | test keys | `STRIPE_MODE` decides test or live | **forced `test`**; live key variables blanked in compose |
| `JWT_SECRET` | dev value | live secret | `DEMO_JWT_SECRET` (a live token must never work on demo) |
| `FRONTEND_PROXY_SECRET` | empty | live secret | `DEMO_FRONTEND_PROXY_SECRET` |
| `CORS_ALLOWED_ORIGINS` | localhost | live domains | demo domain only |
| Stripe webhooks | Stripe CLI | live endpoints | its own test-mode endpoints, `DEMO_STRIPE_TEST_*` secrets |
| Frontend build | `vite` | `vite build` | `vite build --mode demo-qa` |
| Ports | 9095 | 127.0.0.1:9095 | 127.0.0.1:9096 |

**Demo-specific rules:**
- **The seeder only touches rows it owns.** In the reference, IDs starting `DEMO` plus an explicit allowlist (`app.demo.showroom-extra-ids`). It never overwrites an operator's edit and never runs on live. CI reconciles `APP_DEMO_SEED` from a repo secret so a manual change can't drift (`scripts/reconcile-demo-seed.sh`).
- **Seed data must look real and be unique:**
  - names that match their category
  - one photo per profile, never repeated
  - addresses spread across the city
  - hours for every day of the week
  - realistic reviews and prices
- **Seed data must fit the schema.** Add a test that every seeded string fits its column length. A too-long bio crashed RapidStylers' seeder.
- **Evict read caches after seeding**, or the site keeps showing the old data.
- **Show a demo banner.** The demo frontend says it's a preview. The demo-qa build forces Stripe test mode and points only at the demo API or the site's own proxy (`src/utils/demoQaBuild.mjs` in the reference). It throws if the Stripe key isn't `pk_test_`.

---

## 5. Backend conventions (copy these)

1. **Code is grouped by domain:**
   - `auth`, `customer`, `<vendor>`, `booking`, `payment`, `engagement`, `media`, `catalog`, `admin`, `outbox`, `security`, `common`, `config`
   - A domain reaches another domain's data only through that domain's `*Records` doorway class.
   - `DomainWallsTest` enforces this.
2. **Every route is on an allowlist by role** (`security/access/Routes.java`: `PUBLIC`, `CUSTOMER`, `<VENDOR>`, `ADMIN`, `UPLOAD`, …).
   - `RoutesTest` fails if a public route reads the signed-in account.
   - Controllers take the account id from the token (`CurrentAccount.id(request)`), never from the request body.
3. **One response envelope:** `BaseResponse { statusCode, message, data }`. The HTTP status is mapped from it in `ApiResponses.respond`.
4. **Flyway owns the schema.**
   - Migrations are idempotent: use `CREATE TABLE IF NOT EXISTS`, and guard `ALTER`s by checking `information_schema` (see `V41`, `V42`).
   - Tests run with `ddl-auto=validate` against a real MySQL, so drift between entities and schema fails CI.
5. **The server is the source of truth for money.** Prices, durations, travel fees and discounts are computed on the server from stored data. The client's numbers are ignored.
6. **Writes that charge money take an `Idempotency-Key`.** Store it, with a fingerprint of the request, on the row, so a retry replays the first answer instead of charging twice.
7. **Email and side effects go through a transactional outbox.**
   - A row is written in the same transaction, then published to Kafka, then a consumer sends the email.
   - Consumers de-duplicate.
   - HTML-escape any user-typed text in an email.
8. **Redis is optional at runtime.** Rate limits, caches and idempotency fall back to the database when Redis is down.
9. **Every vendor has their own time zone.**
   - Hours are wall-clock times in the vendor's zone.
   - "Open now", "today" and "already passed" are judged on the vendor's clock.
   - Emails name the zone.
   - The zone is derived from the address (Google Timezone) unless the vendor picks one.
10. **Uploads are signed by the server** for an allowlist of folder prefixes, inside an owner folder (`UploadOwnership`). Adding a new upload folder means adding it to that allowlist, its default in `application.properties`, the compose default and `.env.example`. A missing `cover` prefix broke cover photos on RapidStylers.
11. **Audit and alerts.** Security-relevant actions write an audit row. Changing a vendor's name, phone, business name, address or password emails the vendor.
12. **Tests.** JUnit 5, Mockito and AssertJ. Service tests build the service by hand with mocks; `TestServices` wires many at once. Aim for 80%+ coverage, and write the test first.

---

## 6. Frontend conventions (copy these)

1. **Stack:** React 18, Vite (with `--mode demo-qa` for the demo), Tailwind, Redux for the session, Vitest with Testing Library, Playwright for end-to-end tests.
2. **`REACT_APP_*` values are baked into the bundle at build time.**
   - Only put public values there: the Stripe publishable key, the Google client ID, the Turnstile site key, demo flags.
   - Never put the API key there.
   - Changing one means redeploying that Vercel project.
3. **Server-only Vercel variables** (proxy only): `API_UPSTREAM_URL`, `API_KEY`, `FRONTEND_PROXY_SECRET`. Each Vercel project gets its own values: live points to `api.<domain>`, demo to `api-demo.<domain>`.
4. **`vercel.json` holds:**
   - the rewrite `/<api_prefix>/:path* → /api/proxy`
   - security headers (CSP, HSTS, X-Frame-Options and others)
   - long caching for hashed assets
5. **UI rules:**
   - Use one `Button` component.
   - Toasts use the app font.
   - Dialogs go through `AccessibleDialog` (focus trap, Escape to close).
   - Phone menus are fixed drawers under the top bar, so they open wherever the page is scrolled.
   - Never call a role-specific endpoint for the wrong role. It earns a 401 toast; this happened on RapidStylers.
6. **Time-dependent tests pin the clock** with `vi.useFakeTimers({ toFake: ["Date"] })` and `vi.setSystemTime`. A test that built a window around "now" failed CI at 11:59 PM.

---

## 7. Payments (Stripe), if the app takes money

Model used by RapidStylers: **Connect, with separate charges and transfers.**

1. **Booking:**
   - Create a PaymentIntent with `capture_method=manual`; this is a hold.
   - Bookings far in the future save a payment-method reference and authorize later, when the date is close enough.
2. **Vendor accepts:** capture the payment. **Vendor declines or customer cancels:** release the hold or refund.
3. **After completion:** transfer the vendor's share with `source_transaction = <the charge>`. Without it, transfers fail with "insufficient funds" until the charge settles.
4. **Mark every PaymentIntent** with `metadata.environment = APP_ENV`. Reconciliation ignores payments from other environments; otherwise live and demo raise false alarms about each other.
5. **Idempotency keys:** Stripe caches a failed request's result for 24 hours under the same key. After fixing the cause, retry with a **new** key (for example, add a version suffix).
6. **Keys:**
   - `STRIPE_MODE=test|live` picks between the `STRIPE_TEST_*` and `STRIPE_LIVE_*` sets.
   - Demo blanks every live variable in compose.
   - Check that the mode is exactly `test` or `live`. RapidStylers once had `true`.
7. **Webhooks:** each environment needs two endpoints.
   - "Your account" events → `STRIPE_*_WEBHOOK_SECRET`.
   - "Connected accounts" events → `STRIPE_*_CONNECT_WEBHOOK_SECRET`. Creating the Connect one as "Your account" silently drops payout events.
8. **Testing:** call the API with Stripe's test payment methods (`pm_card_visa`, `pm_card_chargeDeclined`). An agent must not type card numbers into a browser.
9. **Vendors** can't be booked until Connect onboarding is `COMPLETE`. The payouts page says "Not started" when there's no Stripe account, rather than "Ready".

---

## 8. CI/CD

**Backend (`.github/workflows/ci.yml`)**, on pushes and PRs to `dev` and `main`:
1. `tests`: MySQL 8 and Redis service containers, then `./mvnw -B test`.
2. `gitleaks`: full-history secret scan. Known-safe test fixtures go in `.gitleaksignore`; build fake secrets at runtime instead of writing them literally.
3. `deploy` (on `main` only, needs both jobs above):
   1. Load the SSH key from secrets.
   2. Reconcile `ENCRYPT_KEY` and `APP_DEMO_SEED` from repo secrets.
   3. Pre-flight check of required env on the VPS (`scripts/check-required-env.sh`).
   4. `rsync --delete` to `/opt/<app>/backend/`, excluding `.env`, `backups`, `.git`, `target` and `.github`. Excluding `backups` matters: without it, rsync tried to delete server-side backups and failed.
   5. `docker compose -f docker-compose.prod.yml up -d --build app`.
   6. Restart `app-demo` if it's running.
   7. Wait for `/actuator/health`. On failure, dump compose status, logs and the Flyway history.

**Backend repo secrets:** `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `ENCRYPT_KEY`, `APP_DEMO_SEED`.

**Frontend (`.github/workflows/ci.yml`):**
1. gitleaks
2. `npm ci`
3. `npm audit --audit-level=moderate` (fix with `npm audit fix`)
4. `npm test`
5. `npm run build`
6. verify the build chunks
7. Playwright end-to-end tests

Vercel deploys both projects from the Git integration. Live builds from `main`; demo builds with `build:demo-qa`.

---

## 9. VPS and edge setup (once per server)

1. Run `scripts/bootstrap-vps.sh`. It installs Docker and nginx, creates a deploy user, sets up the ufw firewall, syncs the repo and starts the stack.
2. **Cloudflare:**
   - DNS: apex and `www` → Vercel; `demo` → the demo Vercel project; `api` and `api-demo` → the VPS (proxied).
   - Create an Origin CA certificate.
3. **nginx:** one site per API host (`deploy/nginx/sites-available/*.conf`) and the Cloudflare real-IP snippet. Then `nginx -t` and reload.
4. **`/opt/<app>/backend/.env`** holds every secret. It lives only on the server and is never synced or committed.
   - After editing it, run `docker compose -f docker-compose.prod.yml up -d app`.
   - For the demo: `docker compose -f docker-compose.prod.yml --profile demo up -d app-demo`.
5. **Backups:** dump each database into `/opt/<app>/backend/backups/` before risky operations, for example before turning off demo read-only.
6. **Verify:**
   - `/actuator/health` over https
   - a public catalog call through the frontend proxy
   - the site's JS bundle contains no API host or key

---

## 10. Environment variable contract (names only)

**Backend (`.env.example`):**
- Database: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DEMO_DB_NAME`
- Auth: `APP_API_KEY`, `JWT_SECRET`, `DEMO_JWT_SECRET`, `JWT_TTL_MINUTES`, `ENCRYPT_KEY`, `SESSION_IDLE_*`
- Proxy and CORS: `FRONTEND_PROXY_SECRET`, `DEMO_FRONTEND_PROXY_SECRET`, `CORS_ALLOWED_ORIGINS`, `DEMO_CORS_ALLOWED_ORIGINS`, `RATE_LIMIT_TRUSTED_PROXIES`
- Stripe: `STRIPE_MODE`, the `STRIPE_TEST_*` set, the `STRIPE_LIVE_*` set, `DEMO_STRIPE_TEST_WEBHOOK_SECRET`, `DEMO_STRIPE_TEST_CONNECT_WEBHOOK_SECRET`, `STRIPE_CURRENCY`, `STRIPE_COMMISSION_PERCENT`
- Media and email: the `CLOUDINARY_*` set (including `CLOUDINARY_ALLOWED_FOLDER_PREFIXES`), `RESEND_API_KEY`, `RESEND_FROM`
- Google and bots: `GOOGLE_API_KEY`, `GOOGLE_CLIENT_ID`, `TURNSTILE_SECRET_KEY`
- Demo and app: `APP_ENV`, `APP_TIME_ZONE`, `APP_DEMO_SEED`, `DEMO_SEED`, `DEMO_READ_ONLY`, `APP_ADMIN_ALERT_EMAIL`
- Infrastructure: the `REDIS_*` set, the `KAFKA_*` set, `LOG_TOKEN`, `LOG_ENDPOINT`, `SENTRY_DSN`

**Frontend (Vercel, per project):**
- Build-time (public): `REACT_APP_API_BASE_URL` (= `/<api_prefix>`), `REACT_APP_STRIPE_MODE`, `REACT_APP_STRIPE_TEST_PUBLISHABLE_KEY`, `REACT_APP_STRIPE_LIVE_PUBLISHABLE_KEY`, `REACT_APP_GOOGLE_CLIENT_ID`, `REACT_APP_TURNSTILE_SITE_KEY`, `REACT_APP_DEMO_MODE`, `REACT_APP_DEMO_BOOKING_OPEN`, `REACT_APP_LIVE_SITE_URL`
- Server-only (proxy): `API_UPSTREAM_URL`, `API_KEY`, `FRONTEND_PROXY_SECRET`

---

## 11. Build order for a new app (phases)

**Phase 1: Skeleton**
- Create both repos with the folder layout above, `dev` and `main` branches, and CI (tests and gitleaks only, no deploy yet).
- Done when: CI is green on both repos, and an empty `/actuator/health` responds locally.

**Phase 2: Accounts and security**
- Build:
  - sign-up with email OTP and Google
  - sign-in with JWT and refresh tokens
  - roles and the route allowlist
  - rate limits
  - the API-key filter
  - the upload signer
- Sign-up tokens: the email is never re-sent from the browser after the OTP step; an encrypted sign-up token carries it.
- Done when: `RoutesTest` and `DomainWallsTest` pass, and a wrong-role call gets 401/403.

**Phase 3: Core domain**
- Build the catalog, vendor profiles (address, hours in the vendor's zone, services), search and the public profile.
- Done when: search finds vendors, "Open now" is correct across time zones, and tests pin the clock.

**Phase 4: Booking and payments**
- Build:
  - the price estimate (server-side)
  - booking with an idempotency key, slot locks, and checks on hours and past times
  - the Stripe hold → capture → transfer flow
  - webhooks and reconciliation
- Done when: the full flow passes against the Stripe test account using test tokens (hold, capture, transfer, cancel refund, decline release, declined card).

**Phase 5: Notifications**
- Build the outbox, Kafka and an email consumer; in-app inboxes for both roles; reminder jobs.
- Done when: every booking event writes an outbox row, and the consumer test escapes user text.

**Phase 6: Frontend proxy and hosting**
- Set up the Vercel Edge proxy, `vercel.json` headers and rewrites, the live Vercel project, the VPS bootstrap, nginx, Cloudflare, and the CI deploy job.
- Done when: the live site works through the proxy, and the bundle contains no API host or key.

**Phase 7: Demo**
- Set up the `app-demo` compose service (profile `demo`) with its own DB, JWT and proxy secrets, Stripe test only, the seeder, the demo Vercel project (`build:demo-qa`) and the demo nginx site.
- Done when:
  - the demo shows the seeded marketplace and live shows none of it
  - a demo token is rejected by live
  - demo payments appear only in Stripe test mode

**Phase 8: Admin and operations**
- Build the admin console (vendors, bookings, refunds, payouts, announcements, content), audit logs, reconciliation reports and backups.
- Done when: an admin can approve a vendor, refund a booking and see reconciliation.

**Phase 9: Polish**
- Accessibility pass, phone layouts, SEO metadata, link previews, a promo video, and Playwright end-to-end tests of the critical flows.

---

## 12. Working rules for the agent

1. **Test before pushing.** Run the full backend and frontend suites and the frontend build, then push `dev`, then `dev:main`. Say in the reply what was tested and what wasn't.
2. **Bugs:** reproduce the bug, find the cause, add a failing test, fix it, and explain the cause in one plain sentence.
3. **Never commit or print secrets.** Run gitleaks on new commits before pushing. To check a server value, ask the owner for the first 8 characters only.
4. **Live stays clean:** `APP_DEMO_SEED=false` on live, always. Demo data never reaches live.
5. **Irreversible or outside actions need the owner's yes:** production data changes, deleting data, live Stripe actions, emailing real people. Back up the database first.
6. **Payments testing** uses API test tokens on the test account, never real cards and never typed card numbers.
7. **After changing `.env` on the VPS**, say exactly which `docker compose ... up -d <service>` command to run. After changing Vercel variables, say which project to redeploy.
8. **When a deploy doesn't seem to show a change**, check whether CI has finished before suspecting the code. The VPS keeps running the old image until then.
9. **New features:** write a short plan first (what fits, what's left for later), get a yes, then build backend → frontend → tests → a local run against Stripe test.
10. **Explain simply.** The owner reads on a phone: lead with the answer, keep it short, no jargon.

---

## 13. Pitfalls this template already paid for

| Symptom | Cause | Fix |
|---|---|---|
| Seeder crashed on boot | Bio longer than its 255-character column | Column-length test for seed data |
| Duplicate demo profiles or photos | Two rows sharing one seat or photo index | Assign seats and photos uniquely, and test it |
| Old data after seeding | Read cache not cleared | Evict the vendor and search cache prefixes |
| Demo broke after hiding the API key | demo-qa build forced the old API URL | Allow the site's own proxy path in demo-qa |
| gitleaks failed CI | A literal fake secret in a test | Build it at runtime and add `.gitleaksignore` |
| Payout "insufficient funds", then stuck for 24 hours | No `source_transaction`; Stripe idempotency replay | Add `source_transaction`; use a new idempotency key |
| Reconciliation false alarms | Live and demo share one Stripe test account | `metadata.environment` filter |
| Connect events missing | Webhook created as "Your account" | Recreate it as "Connected accounts" |
| Deploy failed deleting `backups/` | rsync `--delete` | Exclude `backups` |
| Cover photo wouldn't save | Upload folder not on the allowlist | Add the prefix everywhere (section 5, item 10) |
| 401 toast on profile preview | Customer-only call made while signed in as a vendor | Check the role before calling |
| Phone menu "dead" after scrolling | Menu opened at the top of the page, off screen | Fixed drawer under the top bar |
| A booking for a time already gone that day was accepted | The past check compared dates only, in the app's zone | Compare the start time in the vendor's zone |
| A test failed at midnight | Test used the real clock | Pin the clock in tests |
