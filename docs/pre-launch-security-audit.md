# Pre-launch security audit (20 checks)

Audited **13 September 2026** against the "Pre-launch Security List" in the
MASTER BUILD V3 blueprint, over both repositories:

- `Rapidstylers_Backend` — Spring Boot 2.7.12, MySQL 8, Redis, Kafka, Flyway
- `RapidStyler_frontend` — React 18 + Vite 8, Vercel

**Verdict: 13 pass, 4 partial, 2 not applicable, 1 open failure.**

Counted from the table below, not from the prose: checks 1, 2, 6, 8, 10, 11, 13,
14, 15, 16, 18, 19 and 20 pass. Checks 5, 7, 12 and 17 are partial — 12 because
the challenge is enforced on the login pages but the endpoint-equivalence bypass
is still open, and 16 is now a pass with one documented caveat. Checks 3 and 4
do not apply to a MySQL stack. Check 9 is an open failure.

## How this was run

Every verdict below rests on a file-level check, not on the presence of a
feature in a README: a grep of the source, a repository search, a live request
against production, or a test. Where a check could not be verified from the
repositories, it says so and is scored as partial rather than passed — a check
whose enforcement lives outside the codebase is exactly the kind that gets
assumed to pass. Two blank verdicts are recorded further down as unresolved
rather than quietly omitted.

Rerun the mechanical parts with:

```bash
# Secrets in tracked files, then in the whole history
grep -rInE '(sk_live|whsec_|REACT_APP_STRIPE_LIVE_PUBLISHABLE_KEY=)' --include='*.js' --include='*.json' src
git log --all -p | grep -nE 'sk_live_[0-9a-zA-Z]{10,}'

# Frontend bundle must never carry a server-side key
grep -rInE '(CLOUDINARY_API_SECRET|RESEND_API_KEY|JWT_SECRET|STRIPE_SECRET_KEY|ENCRYPT_KEY)' build/ || echo "no server secrets in the bundle"

# Security headers actually served
curl -sI https://api.rapidstylers.ca/rapid_stylers/testing | grep -iE 'content-security|strict-transport|x-frame'
# NB: the apex domain answers 308 to www, and a 308 carries no CSP — without -L
# this looks like the headers are missing. Verified 13 Sep 2026: the enforcing
# policy below has no 'unsafe-eval' and the report-only one has no 'unsafe-inline'.
curl -sIL https://rapidstylers.ca | grep -iE 'content-security|strict-transport|x-frame'
```

## The checks

| # | Check | Verdict | Evidence |
|---|---|---|---|
| 1 | API keys in `.env`, never the frontend | **PASS** | The only values in the bundle are public by design: the Stripe *publishable* key, the Google client ID, the AdSense client and the shared `x-api-key` (`src/utils/constant.js`). `REACT_APP_API_KEY` is a scraper speed bump, not a secret — the JWT is the auth. |
| 2 | Purge secrets from git history | **PASS** | gitleaks runs on every push over the pushed commit range (`.github/workflows/ci.yml`). A full-history scan of all commits found two findings, both false positives since fixed at the source (see the postmortem). |
| 3 | Public DB key only | **N/A** | Not Supabase. MySQL credentials are server-side only and never bundled. |
| 4 | RLS on every table | **N/A** | MySQL has no row-level security. The equivalent is server-side authorization — see 6 and 7. |
| 5 | Encrypt sensitive data | **PARTIAL** | Passwords are BCrypt (`SecurityFilterConfig.passwordEncoder`); OTPs are hashed (`V12__RehashPlaintextOtpCodes`); refresh tokens are SHA-256 hashed (`RefreshTokenService.sha256Hex`). But card user-IDs are stored **plaintext** by design — `EncryptionConfig` only protects legacy values. Recorded, not changed: nothing currently writes ciphertext. |
| 6 | Server-side auth | **PASS** | `JwtAuthFilter` verifies the token and the role before any handler runs; `accountId` always comes from the token, never the request body; 43 `@Valid` endpoints with 54 constraints. |
| 7 | Lock record access | **PARTIAL** | The pattern is verified at sampled endpoints (`ApplicationController` uses `currentAccountId(request)` and returns 401 rather than trusting a passed id), but with no RLS the isolation rests on per-endpoint checks that have not been exhaustively reviewed. |
| 8 | Block field tampering | **PASS** | `calculateTravelPricing` recomputes the price server-side and overwrites the client's value before the charge is derived from it. |
| 9 | Secure cookies (httpOnly, SameSite) | **FAIL — deferred** | Tokens live in `sessionStorage` (`src/utils/constant.js`), readable by any XSS. See action 1. |
| 10 | Hash passwords | **PASS** | `BCryptPasswordEncoder`. |
| 11 | Rate-limit login | **PASS** | `RateLimiterService` + `LoginAttemptService`, with `RATE_LIMIT_TRUSTED_PROXIES` so per-IP buckets key on the real client behind Cloudflare. |
| 12 | Bot protection (Turnstile/CAPTCHA) | **PARTIAL — challenge added** | Cloudflare Turnstile is enforced on the unified sign-in and the admin sign-in, with the widget on both pages. Not enforced on `/user_sign_in` or `/styler_sign_in`; see action 2. |
| 13 | Parameterized queries | **PASS** | No native-query string concatenation in the repositories; JPA throughout. |
| 14 | Validate all input | **PASS** | Server-side Bean Validation on the request DTOs (`@Valid`, `@NotEmpty`, `@Email`, …). Client-side validation is a convenience layer only. |
| 15 | Escape user content | **PASS** | `AppUtils.sanitizeText` on write, with dedicated tests for reviews, feedback, blog posts, support tickets and sub-services. |
| 16 | Restrict file uploads (mime/size/extension) | **PASS — was unverifiable** | Uploads go directly to Cloudinary against a backend-issued signature. The type and size ceilings are now **part of that signature** (`CloudinaryController`), so Cloudinary itself enforces them and a client cannot widen them. Previously the only limit was in a Cloudinary console upload preset — invisible from the repository. See the caveat on `max_file_size`. |
| 17 | Trim API responses | **PARTIAL** | No stack traces are returned to clients (handlers catch and map), and entities are converted to DTOs; but there is no systematic allowlist of returned fields, so a new field added to a DTO ships by default. |
| 18 | Security headers (CSP, HSTS, X-Frame-Options) | **PASS** | Served on both origins. The frontend CSP now excludes `unsafe-eval`, and a strict report-only policy runs alongside it (`vercel.json`). |
| 19 | Force HTTPS | **PASS** | HSTS `max-age=63072000; includeSubDomains; preload` on both origins; the API redirects at the proxy. |
| 20 | Scan dependencies | **PASS** | Dependabot is enabled on both repositories and CI installs from the lockfile. |

## Fixed in this change

### Check 12 — bot protection on the sign-in endpoints

`TurnstileVerifier` verifies the challenge against Cloudflare's `siteverify`
endpoint; `signIn` and `adminSignIn` refuse the request when it fails. Two
deliberate decisions, both documented in the class javadoc:

- **Enforcement is configuration-driven, not a boot guard.** A missing
  `TURNSTILE_SECRET_KEY` logs a loud warning and lets the app start. A deploy
  that refuses to boot over an *optional* control is the ENCRYPT_KEY outage
  again, so the pre-flight script reports it instead.
- **Fail open on transport failure, fail closed on rejection.** An explicitly
  rejected token is fatal; Cloudflare being unreachable allows the request with
  an ERROR log, because the sign-in path already has per-IP rate limiting and
  per-account lockout, and a Cloudflare incident must not lock every user out.

`signInWithGoogle` is deliberately **not** gated: it requires a Google ID token
whose signature, issuer, audience and `email_verified` are verified in
`GoogleTokenVerifier`, so the caller is already cryptographically attested.

### Check 16 — upload constraints moved into the signature

The signature the backend issues now covers `allowed_formats` and
`max_file_size`, so Cloudinary enforces them. The client forwards the signed
parameter set verbatim (`buildSignedUploadFormData`), which means a constraint
added on the server cannot drift from what the browser sends.

**Deploy ordering:** the frontend must be deployed **before** the backend. A
browser tab still running the previous bundle signs only `folder` + `timestamp`
and will see `Invalid signature` until it reloads — `index.html` is served
`no-store`, so a normal reload or navigation picks up the new bundle. The
frontend falls back to the flat legacy fields when the response has no `params`
key, which covers the reverse order (new frontend, old backend).

**Caveat — `max_file_size`:** Cloudinary documents `max_file_size` primarily as
an upload-preset setting. It is sent as a signed parameter here, and signed
request parameters take precedence over preset values, but this should be
confirmed once against this environment with an intentionally oversized image
before it is relied on. `allowed_formats` is a documented direct-upload
parameter and needs no such confirmation.

### Check 18 — CSP hardened

`unsafe-eval` was removed from the enforcing policy. It was there for
`lottie-web`, whose After Effects expression evaluator calls `eval` — dead code
unless an animation carries expressions, and the only animation in the repository
has none (verified in the built bundle: one `eval`, inside `lottie-web`).
`src/utils/contentSecurityPolicy.test.js` asserts both halves of that coupling,
plus the presence of every origin the app depends on.

`script-src 'unsafe-inline'` remains in the enforcing policy, because Stripe,
Google Sign-In and AdSense each inject inline snippets and breaking payments or
ads to satisfy a checklist would be the wrong trade. A **report-only** policy
without `unsafe-inline` runs alongside it so promoting that to enforcement can
be a data-driven decision.

## Open actions

1. **Move tokens out of `sessionStorage` (check 9).** The only real fix is a
   backend change: issue the refresh token as an `httpOnly; Secure; SameSite`
   cookie, accept it from either the cookie or the body during the transition so
   the cutover has no dead window, and require the `x-api-key` header plus a
   same-site cookie for CSRF. The frontend has ~20 call sites and several tests
   that assert `sessionStorage` contents, so this is a staged change, not a
   one-line one. Not started.
2. **Close the bot-protection bypass (check 12).** `/user_sign_in` checks the
   same credentials as `/sign_in` but is not gated, because it is also the
   automatic sign-in that follows account creation in the signup journey, the
   secure-account step and the booking flow — none of which has a form to render
   a challenge on. Gating it as-is would break real signups while leaving an
   attacker free to hit the endpoint directly, so the gate sits on the two real
   login pages instead. The correct fix is a server-issued, single-use
   auto-login ticket for the post-signup case, so the challenge can be required
   without a challenge surface. `/styler_sign_in` has no caller in this
   repository and is left ungated so an unknown legacy client is not broken.
3. **Store `ENCRYPT_KEY` as a repository secret (from the outage postmortem).**
   The deploy job can already reconcile it into the VPS `.env`; the secret itself
   has still not been created, so the key exists only on the server.
4. **Confirm `max_file_size`** with one oversized upload (see check 16).
5. **Review response shaping (check 17)** and the per-endpoint authorization
   coverage behind check 7 — both are honest partials rather than passes.

## Related

- `docs/postmortem-2026-09-13-encrypt-key.md` — the outage that motivated the
  boot guards and the deploy pre-flight.
- `.env.example` — the startup-critical audit, including the fourth category
  (a control that is silently off when unset).
- `scripts/check-required-env.sh` — the deploy pre-flight that fails in seconds
  rather than crash-looping for six minutes.
