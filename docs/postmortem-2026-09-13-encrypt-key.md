# Postmortem — production API outage, 13 September 2026

**Severity:** full API outage (every endpoint returning HTTP 502)
**Duration:** ~40 minutes of customer-visible downtime (first failed deploy 08:13 UTC, healthy again 09:01 UTC)
**Blast radius:** the backend API only. The frontend stayed up. No data was lost, and the database was never modified.
**Status:** resolved; corrective items listed in §9

---

## 1. Summary

A hardening change written on **4 September** and merged to `main` on **13 September** made the application refuse to start unless `ENCRYPT_KEY` was set, and the production `.env` had it present but **empty**. The new container crash-looped, nginx returned 502, and the deploy job failed its health gate.

The change itself was defensible — the only fallback key is a constant published in the repository, so booting on it in production is a real vulnerability. The problem was entirely in the delivery: **configuration was the thing that broke, and configuration is the one part of this system that no environment before production ever exercises.**

Sharpest lesson: the guard was *unit-tested* and the test passed. `EncryptionConfigTest` asserts that a prod-profile context refuses the public fallback key. That test proved the guard works — it said nothing about whether production had a key, and nothing anywhere asked.

---

## 2. Impact

| | |
|---|---|
| Customer-visible | All API endpoints 502 for ~40 minutes (`https://api.rapidstylers.ca/actuator/health` → 502) |
| Deploy pipeline | Three consecutive `main` deploys failed (08:13, 08:29, 08:40 UTC) |
| Data | None lost. No migration ran — Flyway logged *"Schema `rapid_stylers` is up to date. No migration necessary."* |
| Recovery | Manual: set `ENCRYPT_KEY` in the VPS `.env`, recreate the container |

---

## 3. Timeline (UTC)

| Time | Event |
|---|---|
| **03 Sep 04:43** | Last **successful** production deploy (`Merge branch 'dev'`). The API stays healthy from here until 13 Sep. |
| **04 Sep 18:04** | Commit `977e2e3` *"harden crypto: wire ENCRYPT_KEY, guard the public fallback key, lock /decrypt to ADMIN"* authored on `dev`. **Never deployed** — `main` was not pushed. |
| 04–13 Sep | Nine days of green CI on `dev`. The guard is never executed on any deployed environment. |
| **13 Sep 08:13** | PR #6 (the hardening batch) merged; `main` deploy #1 (`a89e40c`) **fails** its health gate. The container is replaced → **outage begins**. |
| 13 Sep 08:29 | Deploy #2 (`c9cc22b`) fails. This was an attempted fix, and it was aimed at the wrong cause (§7). |
| 13 Sep 08:37 | Health gate on deploy #2 exhausts its 30 retries. |
| 13 Sep 08:40 | Deploy #3 (`17d96f0`) starts. Its only change is a **diagnostic dump step**. |
| 13 Sep 08:46 | The dump step captures the container log — and with it the actual cause: `IllegalStateException: Encryption is using the public hardcoded fallback key (ENCRYPT_DECRYPT_KEY_FALLBACK) because no app.encrypt.key / ENCRYPT_KEY is configured. Refusing to start outside the test profile.` |
| 13 Sep ~08:5x | `ENCRYPT_KEY` set in the VPS `.env`; container recreated. |
| **13 Sep 09:01** | Application boots (`The following 1 profile is active: "prod"`), Kafka consumers attach, `RestartCount 0`, health returns `{"status":"UP"}`. |
| 13 Sep ~09:05 | Public endpoints verified: `/actuator/health` → 200, `/rapid_stylers/list_service` → 401 (normal — missing API key), i.e. serving again. |

---

## 4. Root cause

**Primary.** `EncryptionConfig.assertFallbackAllowed()` refuses to start outside the `test` profile when `app.encrypt.key` / `ENCRYPT_KEY` is empty. Production's `.env` contained `ENCRYPT_KEY=` — present but empty — so the guard fired and the context aborted:

```
BeanCreationException: Error creating bean with name 'encryptionConfig':
  nested IllegalStateException: ... Refusing to start outside the test profile.
```

**Contributing.** `.env.example` documented the opposite of what the guard enforced. Lines 59–61 as they stood immediately before the hardening commit:

```properties
# Encryption key for card-details user IDs (run: openssl rand -hex 32)
# Leave empty to use the legacy fallback during migration.   ← stale and dangerous
ENCRYPT_KEY=
```

So the operator did exactly what the documentation said. The template handed out an empty value and called it valid, while the code treated it as fatal. **A config file that contradicts the code is worse than a missing one**, because it looks correct.

Precisely how it got that way, because the sequence is the instructive part: the *documentation* was written on **26 August** (`804ece6 update`) when empty genuinely was supported — it meant "use the legacy fallback". The guard arrived on **4 September** (`977e2e3`), touching 8 files, and **left `.env.example` untouched**:

```
$ git diff 977e2e3^ 977e2e3 --stat -- .env.example
(no output)
```

So this was not a wrong doc shipped alongside a guard. It was **a correct doc silently invalidated by a later change** — the guard redefined "empty" from *supported* to *fatal* and never revisited the file that promised otherwise. That failure mode is harder to catch in review than a new contradiction, because nothing in the diff looks wrong.

**Contributing.** There is no environment between CI and production, and the deploy replaces the live container *before* checking whether the new one can boot (§6).

---

## 5. Why nine days of green CI could not catch this

This is the part worth remembering, because none of it was bad luck.

**1. The failing path was structurally excluded from tests.** `src/test/resources/application.properties` sets `spring.profiles.active=test` for the entire suite, and the guard explicitly exempts the `test` profile:

```java
if (!environment.acceptsProfiles(Profiles.of("test"))) { throw ... }
```

CI therefore *could not* execute the failure, by construction. Every test that booted a Spring context booted it under `test`.

**2. The broken thing was configuration, and config never leaves the VPS.** The deploy rsyncs the repo with `--exclude .env`. CI tests code against a throwaway MySQL container with its own credentials. The `.env` — the only artifact that differed between green CI and broken production — was absent from the pipeline entirely.

**3. The unit test proved the guard worked, not that production was configured.** `EncryptionConfigTest` asserts the throw for a `prod` context and the no-throw for a `test` context. Both passed on 4 September and every day after. It was scoped to the *mechanism*; nobody owned the *assumption* that production had a key.

**4. The guard's own message was unreachable until it mattered.** The failure mode was only reachable outside `test` — i.e. in production, which is where it was discovered.

**5. It rode in on a large batch.** The guard arrived in a merge of ten-plus commits ("sync hardening batch from dev"), so no reviewer saw it as a standalone, risky change. Combined with the stale `.env.example`, even a careful reader would have concluded that empty was a supported configuration.

**6. The deploy job could not report the cause.** It waited on a health endpoint and printed its retry loop; the exception existed only in the container's stdout on the VPS (§6).

---

## 6. Detection and diagnosis: what the dump step changed, and why

**Before.** The `Wait for healthy` step ran `curl` on `127.0.0.1:9095/actuator/health` up to 30 times over ~6 minutes. On timeout it printed:

```
API did not become healthy in time
Error: Process completed with exit code 1.
```

That is all. The actual exception was in the container's stdout, on the host, reachable only by someone with SSH access — so **every failed deploy was undiagnosable from CI**, and each attempt cost another ~6 minutes of outage.

**After.** Commit `17d96f0` added a step that always runs after the gate and prints, from the VPS: `docker compose ps -a`, the last 250 lines of the app log, and `flyway_schema_history`. It is read-only and swallows its own errors, so it can never change a job's verdict.

It paid for itself immediately. On its first run it produced two decisive facts in one deploy cycle:

- `Schema 'rapid_stylers' is up to date. No migration necessary.` — Flyway and the schema baseline were innocent.
- The `encryptionConfig` `IllegalStateException` — the actual cause, verbatim.

**The generalisable lesson:** a deploy that fails must explain itself in its own output. A health gate tells you *that* something is wrong; without a log dump it does not tell you *what*, and the cost is measured in another full retry cycle of downtime.

---

## 7. Misdiagnosis during the response (recorded deliberately)

Two wrong turns, both from acting on a plausible hypothesis before evidence:

1. **Wrong host.** The first probes used `api.rapidstylers.com`, which does not resolve. The real host is `api.rapidstylers.ca`. For a while the 502 was being read from a hostname that did not exist.
2. **Wrong cause.** The first deploy also flipped `ddl-auto` from `update` to `validate`, and that was assumed to be the cause. Commit `c9cc22b` reverted it — **and the API still did not come back**, which is what proved the hypothesis false. The revert was harmless (it restored the previous value), and `update` is still the correct setting until the live schema is aligned for validation, but the *reasoning* was wrong and cost a deploy cycle.

Both were resolved only by getting the container log. This is the argument for §6: without the artifact, the response degrades into guessing.

---

## 8. What went well

- The guard itself was correct, and failed **closed** — the API refused to serve rather than encrypting with a publicly known key.
- No data loss, and no migration was attempted against the live schema.
- The health gate did its job: it prevented a broken build from being reported as a successful deploy.
- The database schema (`V13`) behaved exactly as designed on the existing database — a documented no-op.

---

## 9. Corrective actions

**Done**

| # | Action | Where |
|---|---|---|
| 1 | Deploys now dump compose state, app log and migration history | `.github/workflows/ci.yml` (`17d96f0`) |
| 2 | `.env.example` no longer documents an empty `ENCRYPT_KEY` as valid; it documents the guard and the full startup-critical audit | `.env.example` |
| 3 | Pre-flight validates required config **before** the rsync, so a bad value fails in seconds and leaves the VPS untouched. It also checks the compose file, because a value that compose does not forward is a silent no-op even when `.env` is correct | `scripts/check-required-env.sh` |
| 4 | `ENCRYPT_KEY` is restorable from a repository secret, and a mismatch fails loudly instead of silently rotating the key | `scripts/reconcile-encrypt-key.sh` |
| 5 | `JWT_SECRET` now fails closed at start-up, so an empty, published or sub-256-bit key stops the deploy instead of passing the health gate | `JwtUtil`, `JwtUtilTest`, `AppConstants`, `scripts/check-required-env.sh` |

Two notes on #5, because the design choices are deliberate rather than incidental:

- **It has no `test`-profile exemption**, unlike the `ENCRYPT_KEY` guard. That exemption is *necessary* there — the suite pins the public fallback key — whereas the suite here uses a real 48-byte default, so nothing ever needs a weak signing key. Exempting `test` would only create a profile under which the guard is off, which is the shape of hole that caused this outage.
- **A key under 256 bits is rejected too**, not just an empty one. That case had the same symptom as the outage in reverse: it boots, reports UP, and fails on the first sign — and `parseToken` swallows the same failure, so every existing token is rejected as well.

The guard was verified not to fire on production's real configuration before being written: the live `JWT_SECRET` is 113 bytes and its fingerprint (`400073e9e89ee2ed`) is not the published placeholder, so the next deploy still passes.

**Open**

| # | Action | Why |
|---|---|---|
| 6 | **Add the `ENCRYPT_KEY` repository secret.** Production key fingerprint: `b500d34481fc767a` <!-- gitleaks:allow — this is a SHA-256 fingerprint of the key, not the key; recorded so the value can be verified when the secret is set --> | Until it exists, the key still lives only in the VPS `.env` and cannot be recovered after a re-provision |
| 7 | Boot-check the new image before swapping the live container | The deploy replaces first and asks afterwards. A throwaway `docker run --rm` that must reach a listening port would have kept production up entirely |
| 8 | Add a CI job that boots the app under the `prod` profile | Nothing exercises a non-`test` start, which is exactly why 9 days of green CI meant nothing here — and a prod-profile boot would now also exercise both start-up guards |
| 9 | ~~Resolve the compose forwarding gap~~ **Done** — 15 app-consumed variables were being silently ignored; all are now forwarded as `${VAR:-<the app's own default>}`, and two tests fail the build if a variable the app reads is neither forwarded nor declared container-fixed | `docker-compose.prod.yml`, `EnvForwardingContractTest` |
| 10 | Alert on a failed production deploy / unhealthy API | Detection came from a manual check. The pipeline reported a timeout, not a cause |

---

## 10. Appendix — reproducing the evidence

```bash
# the guard and its exemption
grep -n 'acceptsProfiles' src/main/java/com/macrotel/rapidstylers/config/EncryptionConfig.java   # -> 103
sed -n '99,110p' src/main/java/com/macrotel/rapidstylers/config/EncryptionConfig.java
grep -n 'profiles.active' src/test/resources/application.properties   # -> 24: test

# the stale documentation: written 26 Aug, never updated by the 4 Sep guard
git log --oneline -S'Leave empty to use the legacy fallback' -- .env.example   # -> 804ece6
git show 977e2e3^:.env.example | sed -n '59,61p'                             # the promise
git diff 977e2e3^ 977e2e3 --stat -- .env.example                             # -> no output

# the JWT start-up guard (note: no profile exemption, unlike the encryption one)
grep -n -A3 '@PostConstruct' src/main/java/com/macrotel/rapidstylers/security/JwtUtil.java

# the boot failure as the dump step captured it
gh run view 34748284536 --log

# current state of the deploy job's steps
ruby -ryaml -e 'd=YAML.load_file(".github/workflows/ci.yml"); d["jobs"]["deploy"]["steps"].each{|s| puts s["name"]}'
```
