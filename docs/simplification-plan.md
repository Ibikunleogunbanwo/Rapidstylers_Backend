# RapidStylers — Phased Simplification Review (Verdicts + Execution)

Date: September 13, 2026. Verdicts grounded in the September 2026 codebase
(backend `dev` @ `50aa2f6`, frontend `main` @ `88c485c`).
Input document: [`docs/architecture.md`](./architecture.md) (§7 over-engineering map,
§8.6 single-host reality). Companion: [`docs/ci-cd.md`](./ci-cd.md).

**Baseline at time of writing (the regression floor):**
- Backend: `./mvn21 test` → **375 passed** against local MySQL (test profile loads `.env` like production).
- Frontend: `npm run build` → compiles; `CI=true npm test` → 198/199 with **1 explained local failure** (`IdleTimeout.test.js` — an uncommitted temporary 60s-test edit of `IdleTimeout.js`; committed code is green).
- 27 JPA entities / 27 repos · 6 controllers · 18 service classes · 123 endpoints.
- Tests boot the real context against real MySQL; `ConcurrentTwoCustomerBookingTest`
  exercises a fresh-database path and explicitly invokes `SlotLockUniqueReconciler`
  and `InnoDbReconciler` (they do not run as ApplicationRunners under `@SpringBootTest`).

---

## 1. Verdict summary

| # | Subsystem | Verdict | Why (one line) | Risk |
|---|---|---|---|---|
| 1 | Dual schema authority (Flyway + `ddl-auto=update`) | **Simplify → Flyway-only** | Two schema owners is the root cause of 5 boot-time reconcilers and silent-drift risk | Medium — schema is load-bearing |
| 2 | Boot-time reconcilers/migrations (`InnoDbReconciler`, `SlotLockUniqueReconciler`, `CardDetailsEncryptionMigration`) | **Cut** (after #1) | Their jobs belong to Flyway migrations or one-time ops, not every boot | Low after #1 |
| 3 | `AppService` (5,570 LOC) / `ApplicationController` (1,209) | **Simplify — carve into domain services** | 35% of the backend in two files blocks review, testing, and parallel work | Medium — churn, mitigated by contract freeze |
| 4 | Kafka + outbox (exists to send email) | **Decision point — keep today** | It works, is tested (retry/DLQ), and runs in the same JVM; removing the broker buys ~1 GB VPS heap but loses DLQ/retry semantics. Decide when worker-splitting is near-term | n/a |
| 5 | Redis read cache + geo index | **Keep** | Bounded, single-flight, fail-open with ops counters already | n/a |
| 6 | Ops sprawl (Sentry + BetterStack + logstash encoder) | **Keep — verified as three distinct, gated roles (see §4)** | No duplicate pipeline exists: the logstash encoder only *formats* stdout; BetterStack is the optional HTTP transport; Sentry owns errors | None |
| 7 | Frontend form libraries (Formik + Yup + Zod) | **Simplify — Zod is the standard** | Newer flows (stylist signup) already use Formik+Zod; Yup only in legacy pages | Low |
| 8 | CRA (react-scripts) | **Migrated to Vite 8 — done (see §Step 7)** | react-scripts is deprecated; the migration ran as its own isolated change, test suite and build green | Medium — resolved; six findings documented in Step 7 |
| 9 | Dead frontend deps | **Cut** | Zero imports in `src/` for `crypto-js`, `react-infinite-scroller`, `redux` (bare v5) | Trivial |

Not touched (already lean): JWT/refresh-token lifecycle, step-up auth, rate
limiting, idempotency, session-idle windows, Stripe payment pipeline, signed
Cloudinary uploads, notification dedup, cache warming.

---

## 2. Execution plan (ordered, one green build after each step)

### Step 1 — Flyway-only schema authority  *(fixes #1, enables #2)*

**Problem.** Flyway (V1–V12, mostly `ALTER`s adopting Hibernate-created tables)
and Hibernate `ddl-auto=update` both mutate the schema. `booking_slot_locks` is
created silently by Hibernate; Flyway cannot add its unique index, which is why
a boot reconciler exists to dedupe + add it. Any drift lands at 3 AM on the VPS.

**Do.**
1. Add `V13__full_schema_baseline.sql` (baseline is raw SQL like V1–V11; V12 was Java for OTP rehashing): a full `CREATE TABLE IF NOT EXISTS` + index/constraint definition of all 27 entities, derived from the entities, matching exactly what Hibernate produces today — plus `booking_slot_locks` with its `uk_booking_slot_styler_date_start` unique index inline.
   - On **existing** environments (VPS, local): every table already exists (Hibernate made them), so `IF NOT EXISTS` makes V13 a no-op except where Flyway must backfill something Hibernate added silently.
   - On a **fresh** database (CI's `MYSQL_DATABASE` container, new dev machines): Flyway now creates the entire schema; Hibernate then connects read-only-schema-wise.
2. Flip `spring.jpa.hibernate.ddl-auto=update` → `validate` in `application.properties`. Hibernate must verify entities against the Flyway-owned schema and fail loudly on drift instead of silently patching it.
3. Iterate until `./mvn21 test` is green: `validate` will surface every column-type/index mismatch between entities and migrations — fix those in V13 (or V14+) until entity definitions and schema agree.
4. Delete `InnoDbReconciler.java`, `SlotLockUniqueReconciler.java`, `CardDetailsEncryptionMigration.java` and their tests where they exist.
5. Update `ConcurrentTwoCustomerBookingTest` to stop invoking the reconcilers (Flyway has run by the time the context is up).
6. Update `.env.example` docs/README notes: `ddl-auto=validate` is now the contract; any new entity column ships as a numbered migration, never as a silent auto-update.

**Accept.** Full suite green twice in a row; boot log shows Flyway V13 applied (fresh DB) or validated; reconcilers gone from boot log; `docker-compose.prod.yml` unchanged.

**Rollback.** Revert `ddl-auto` to `update` and restore the reconcilers — single revert, no data risk (no destructive DDL in V13).

### Step 2 — `AppService` carve-outs  *(fixes #3, contract-frozen)*

**Problem.** 5,570 lines across booking, pricing, payments, auth, notifications,
admin ops in one class; ~35% of the backend. Impossible to review or test in
isolation; merge conflicts on every feature.

**Do.** Carve **one domain at a time**, endpoint contract untouched, all 375 tests
green after each carve, one carve per PR-sized unit:
1. **PaymentOps** — Stripe authorize/capture/refund/webhooks/reconciliation (biggest, most self-contained).
2. **BookingOps** — booking workflow, slot locks, pricing/travel-distance, cancellations.
3. **NotificationOps** — Resend/OTP emails, dedup, signup follow-ups.
4. **AccountOps** — profile, cards, preferences, saved stylists.
5. **CatalogOps** — services, categories, availability, portfolios, blog.
`ApplicationController` stays the single HTTP entry; it delegates. `AppService`
shrinks to an orchestrating shell, then (final step, optional) disappears into the
domain services.

**Accept.** No URL/payload/response change (frontend untouched); suite green after each carve; new services have no cyclic dependencies back into `AppService`.

### Step 3 — Observability: keep the split, document it  *(resolves #6)*

**Investigated (code, not assumptions).** The original "delete the logstash HTTP
appender path" idea does not apply: `logback-spring.xml` has no second HTTP
path. There are exactly three roles, each independently gated:

| Role | Component | Gate |
|---|---|---|
| Log record (format) | `JSON_CONSOLE` + `LogstashEncoder` → stdout | always on; feeds `docker logs` and the deploy checklist greps |
| Log transport (optional) | `BetterStackLogbackAppender` (HTTP, batching, daemon worker) | `LOG_TOKEN` empty → appender never starts; shipping failures degrade to a warning |
| Errors | Sentry starter | `SENTRY_DSN` empty → disabled |

**Verdict:** keep as-is — no duplicate pipeline to remove, and each piece is
token/DSN-gated so an unconfigured environment pays no cost. The genuine
inefficiency the architecture review pointed at is the *memory* of always-on
infra (Kafka), not this logging stack.

**Done in this pass.**
- `BetterStackLogbackAppenderTest` locks in the fail-safe: no token → never starts, never touches the network; with a token → starts, `doAppend` never blocks the caller, `stop()` is clean.
- The three-way split is now documented here (and in the appender's header comment).

**Accept.** ✅ Logging behavior unchanged and now test-covered; exactly one shipping path, one error tracker, one always-on stdout record.

### Step 4 — Frontend cleanup  *(fixes #7, #9)*

**Do.**
1. `npm uninstall crypto-js react-infinite-scroller redux` (zero `src/` imports — verify once more immediately before removal, then remove, then build + test).
2. Review and likely remove `web-vitals` (CRA boilerplate), `date-fns`, `lottie-react`, `react-webcam` if equally unused (grep-verify each first).
3. Form-library convergence on **Zod**: new/edited forms use `zod`; migrate a Yup form only when it is being edited anyway (opportunistic, not bulk). Formik stays as the form-state layer — the target is *one* validation library, not zero state libraries.
4. Re-run `npm test` and `npm run build` — green, and the failure in `IdleTimeout.test.js` must be gone (it is caused by the local temporary edit, not by these changes).

**Accept.** Smaller `package.json`, no behavior change, build + tests green.

### Step 5 — Launch-gate UAT + contract polish *(Phase 3)*

1. Centralize the "HTTP 200 + body `statusCode: 400`" handling: an Axios response interceptor that rejects application-level failures, so pages stop hand-rolling `data.statusCode === "400"` checks (introduce incrementally; audit call sites as pages are touched).
2. Walk the 9 acceptance areas (auth, discovery, booking, pricing, stylist ops, admin ops, notifications, security, exclusions) against the running app as a UAT checklist; file findings as issues.
3. Google Sign-In is wired (`app.google.client-id`, `GoogleSignInTest`) — verify the prod client-id env is set on the VPS at next deploy.

### Step 6 — Docs  *(Phase 4)*

Update `docs/architecture.md` (schema authority now Flyway-only, reconcilers
removed, verdict table linked), README deploy checklist (drop reconciler notes),
and the frontend README where deps changed.

### Step 7 — CRA → Vite migration  *(executed after the Step 3 decision)*

**Do.** Replace `react-scripts` with Vite 8 + `@vitejs/plugin-react`, move the HTML
entry to the repo root, make the Tailwind PostCSS chain explicit, and codemod the
test suite from Jest to Vitest.

**Accept.** ✅ 204/205 tests pass (the one failure is the documented local-only
`IdleTimeout.js` 60 s edit), production build compiles, and the emitted bundle
keeps CRA's shape so no deployment config changed.

**Findings worth keeping** (all now encoded in the frontend repo, so the next
person does not rediscover them):

1. **JSX in plain `.js` does not work out of the box in Vite 8.** `.js` files are
   parsed as plain JS, and neither `oxc.jsx` nor
   `build.rollupOptions.moduleTypes: { '.js': 'jsx' }` changes that for the build.
   The fix is an `enforce: 'pre'` transform that runs
   `transformWithOxc(code, id, { lang: 'jsx' })` over `.js` files — implemented as
   `rapidstylers:jsx-in-js` in `vite.config.js`, with the fast-refresh flag
   mirrored from plugin-react's own gate so HMR still works. The dev dependency
   scanner is a *separate* pipeline that only honours
   `optimizeDeps.rolldownOptions.moduleTypes: { '.js': 'jsx' }`; without that key
   the scan fails on JSX and Vite quietly skips pre-bundling.
2. **Output layout is load-bearing.** Rollup's `entryFileNames` are relative to
   `outDir`, *not* to `assetsDir`, so `js/main.[hash].js` silently produced
   `build/js/…` instead of CRA's `build/static/js/…`. That broke the
   `/static/*` immutable cache rule in `vercel.json` and the boot-time stale-shell
   guard in `src/index.js`. The patterns now carry the `static/` prefix explicitly.
3. **The stale-deploy guards needed Vite wording.** The runtime guard only matched
   webpack messages; Vite/native ESM failures read "Failed to fetch dynamically
   imported module" (Chrome), "error loading dynamically imported module"
   (Firefox), "Importing a module script failed" (Safari) and "Unable to preload
   CSS". Those patterns were added, plus a `vite:preloadError` listener — without
   it a stale chunk during navigation would surface as an unhandled rejection.
4. **Vitest is stricter than Jest in three places** that broke 12 test suites:
   a `vi.mock` factory must return an object (Jest allowed returning a component
   function), a missing named export on a mock throws instead of being
   `undefined`, and default-importing a module that has no default export throws.
   `CONTRIBUTING.md` documents all three.
5. **jsdom 29 has no `AnimationEvent`,** and react-dom only registers its
   animation listeners when the interface exists — so `onAnimationEnd` never fired
   in tests. `src/setupTests.js` installs a minimal shim.
6. **The CI chunk-integrity gate was CRA-specific** (it read
   `asset-manifest.json` and a numeric chunk-id → hash map). Vite emits
   `.vite/manifest.json` instead, so `scripts/verify-build-chunks.js` was rewritten
   to validate the manifest, the page shell's asset references, and that the shell
   loads the manifest's entry chunk. It was re-verified by deleting an emitted
   chunk and confirming it fails.

---

## 3. Decisions (asked and answered, Sep 13, 2026)

- **Kafka — KEEP (decided).** The broker stays. It is test-covered (retry + DLQ),
  single-JVM today, and removing it would trade ~1 GB of VPS heap for a
  reimplementation of retry/dead-letter semantics in outbox row states. Revisit
  only when worker-splitting or a second consumer domain becomes real.
- **CRA → Vite — MIGRATE (decided).** react-scripts is deprecated; the migration
  runs as its own isolated change and must be verified by the same test suite and
  a production build before it is considered done.
- **Loyalty points / referral rewards — UPDATE THE EXCLUSION LIST (decided).**
  Both are already implemented and user-visible (`/loyalty`, `/apply_referral`,
  completion points) though the MVP requirements doc listed them as out-of-MVP.
  Stakeholder sign-off moves them into MVP scope; they then need acceptance
  criteria like any other shipped area. Tracked as gap **G1** in
  [`launch-gate-uat.md`](./launch-gate-uat.md).
