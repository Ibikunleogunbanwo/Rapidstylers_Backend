#!/usr/bin/env bash
#
# check-required-env.sh — fail fast when the deploy .env is missing values the
# app cannot start without.
#
# Usage:
#   bash scripts/check-required-env.sh [path/to/.env] [path/to/docker-compose.prod.yml]
#
# Default path: /opt/rapidstylers/backend/.env (the VPS deploy directory).
#
# The optional second argument adds a *forwarding* check, because a correct .env
# is necessary but not sufficient: `docker compose` passes only the variables
# named in the service's `environment:` block, so a required value that is
# present in .env but absent from that block never reaches the container. The
# pre-flight would pass and the app would still crash-loop. Omitted or
# unreadable => that check is skipped with a warning, never failed, so the script
# stays safe to run on its own.
#
# Exit status:  0 when every boot-critical value is present and non-empty,
#               1 otherwise, with one GitHub annotation per problem.
#
# Why this exists: before it, a blank value in the production .env surfaced only
# as a ~6 minute health-gate timeout, with the real cause buried in the app
# container's stdout on the VPS. A missing ENCRYPT_KEY took the API down that
# way. This turns that six-minute mystery into a one-second, self-explaining
# failure — and it fails *before* the image is rebuilt, so nothing is disturbed.
#
# Tiers, mirroring the "STARTUP-CRITICAL VALUES" audit in .env.example:
#   boot      the app refuses to start (explicit guard) or cannot resolve the
#             placeholder at all (no default exists anywhere)
#   silent    the app starts and reports UP on /actuator/health, then breaks in
#             real use — the worst kind, because the deploy looks successful.
#             Currently empty: JWT_SECRET was the only member, and it now has a
#             start-up guard in JwtUtil, so it moved to boot.
#   advisory  only matters the first time the admin account is seeded, or is an
#             optional control that is silently OFF when unset (TURNSTILE_SECRET_KEY
#             — bot protection is simply not applied, and nothing else would say so)
#
# bootstrap-vps.sh complements this: it guarantees the .env *exists*; this
# guarantees the values inside it are usable.

set -uo pipefail

ENV_FILE="${1:-/opt/rapidstylers/backend/.env}"
COMPOSE_FILE="${2:-}"

# Refuses to boot: ENCRYPT_KEY and JWT_SECRET have explicit start-up guards
# (EncryptionConfig, JwtUtil); the other three have no default in
# application.properties, so Spring cannot resolve the placeholder.
BOOT_VARS="DB_PASSWORD APP_API_KEY RESEND_API_KEY ENCRYPT_KEY JWT_SECRET"
# Boots and reports UP, then fails at first use (see .env.example). Empty on
# purpose — the loop below skips it, because an empty word list would otherwise
# be checked as a variable named "".
SILENT_VARS=""
# Only matters on first boot, when the admin row is seeded. TURNSTILE_SECRET_KEY
# sits here too: leaving it out must never block a deploy (the app deliberately
# boots without bot protection), but it must not be forgotten either.
ADVISORY_VARS="ADMIN_PASSWORD TURNSTILE_SECRET_KEY"

# Values that ship in public source. Using them is not a missing value, so the
# app will happily start — which is exactly why they are checked here.
# Mirror of AppConstants.JWT_SECRET_PLACEHOLDER — JwtUtil refuses to boot on it too.
JWT_PLACEHOLDER="run_openssl_rand_-hex_64"
# Mirrors app.kafka.consumers.notifications.group-id in application.properties.
# Used only to warn about the consequence of changing it — see below.
NOTIFICATION_GROUP_DEFAULT="rapidstylers-notification-service"
ENCRYPT_PUBLIC_FALLBACK="D0n!T'T&mp3r@w1Th^&()"
ADMIN_PLACEHOLDER="use_a_strong_unique_passphrase_here"

fail=0

if [[ ! -f "$ENV_FILE" ]]; then
    echo "::error::No .env at $ENV_FILE — the deploy cannot proceed."
    exit 1
fi
if [[ ! -r "$ENV_FILE" ]]; then
    echo "::error::Cannot read $ENV_FILE. The deploy step must run as the user that owns it (rapiddeploy)."
    exit 1
fi

# Count every definition of a key, so a shadowing duplicate can be reported.
count_all() {
    grep -cE "^[[:space:]]*(export[[:space:]]+)?$1=" "$ENV_FILE" || true
}
# The effective value: dotenv files let later lines win, so report the last one.
value_of() {
    awk -v k="$1" '
        /^[[:space:]]*#/ { next }
        {
            line = $0
            sub(/\r$/, "", line)
            sub(/^[[:space:]]*export[[:space:]]+/, "", line)
            if (line ~ "^" k "=") {
                v = substr(line, index(line, "=") + 1)
                sub(/^[[:space:]]+/, "", v)
                sub(/[[:space:]]+$/, "", v)
                gsub(/^["\047]/, "", v)
                gsub(/["\047]$/, "", v)
                last = v
            }
        }
        END { print last }
    ' "$ENV_FILE"
}
# An advisory problem never blocks a deploy: ADMIN_PASSWORD is legitimately
# absent once the admin row exists, and failing the build for that would be a
# false positive that stops unrelated deploys.
problem() {
    local tier="$1" msg="$2"
    if [[ "$tier" == "advisory" ]]; then
        echo "::warning::$msg"
    else
        echo "::error::$msg"
        fail=1
    fi
}

check() {
    local tier="$1" var="$2" all effective
    all="$(count_all "$var")"
    # Judge emptiness on the effective value (the last definition), not on
    # whether *any* definition is non-empty: a duplicate block that ends with
    # `VAR=` would otherwise pass here and still fail at runtime.
    effective="$(value_of "$var")"

    if [[ "$all" -eq 0 ]]; then
        problem "$tier" "$var is not set in $ENV_FILE"
    elif [[ -z "$effective" ]]; then
        problem "$tier" "$var is EMPTY in $ENV_FILE"
    fi

    # A duplicate is how a good value gets shadowed by an empty one.
    if [[ "$all" -gt 1 ]]; then
        echo "::warning::$var is defined $all times in $ENV_FILE — remove the extras, the last one wins"
    fi

    printf '  %-24s %-9s defined=%s effective=%s\n' \
        "$var" "$tier" "$all" "$([[ -n "$effective" ]] && echo 'set' || echo 'EMPTY')"
}

echo "Pre-flight: startup-critical configuration in $ENV_FILE"
for v in $BOOT_VARS;    do check boot     "$v"; done
if [[ -n "$SILENT_VARS" ]]; then
    for v in $SILENT_VARS; do check silent "$v"; done
fi
for v in $ADVISORY_VARS; do check advisory "$v"; done

# Public/placeholder values: present and non-empty, so the checks above pass,
# but they defeat the point of having a secret.
jwt_value="$(value_of JWT_SECRET)"
if [[ "$jwt_value" == "$JWT_PLACEHOLDER" ]]; then
    echo "::error::JWT_SECRET is still the .env.example placeholder. That string is in public"
    echo "::error::source, so anyone could mint admin tokens. Run: openssl rand -hex 64"
    fail=1
fi
encrypt_value="$(value_of ENCRYPT_KEY)"
if [[ "$encrypt_value" == "$ENCRYPT_PUBLIC_FALLBACK" ]]; then
    echo "::warning::ENCRYPT_KEY is the public fallback constant from AppConstants. The app accepts"
    echo "::warning::it, but anything encrypted under it is readable by anyone with the repo."
fi
if [[ -z "$(value_of TURNSTILE_SECRET_KEY)" ]]; then
    echo "::warning::TURNSTILE_SECRET_KEY is not set, so the sign-in endpoints have NO bot protection."
    echo "::warning::The app boots and sign-in works — that is why it is easy to miss. Add the key"
    echo "::warning::(and REACT_APP_TURNSTILE_SITE_KEY on the frontend) to enable the challenge."
fi
if [[ "$(value_of ADMIN_PASSWORD)" == "$ADMIN_PLACEHOLDER" ]]; then
    echo "::warning::ADMIN_PASSWORD is still the .env.example placeholder; the bootstrap admin"
    echo "::warning::(and any new admin account) will be refused as a known-weak password."
fi

# ── Value changes that take effect, but are not safe in passing ─────────────────
# Now that .env actually controls these, setting one is the point — except here,
# where "taking effect" is itself the hazard. Changing the notification consumer
# group makes Kafka start a brand-new group, so events are replayed or skipped and a
# recipient can be notified twice for anything older than NOTIFICATION_DEDUP_TTL_HOURS.
#
# Warned rather than blocked, deliberately, and the distinction is the same one that
# governs its sibling reconcile-encrypt-key.sh: an encryption-key mismatch would
# silently and irreversibly make existing ciphertext unreadable, so that refuses to
# proceed; a duplicate notification is recoverable and mostly suppressed by the dedup
# window. Irreversible => block. Recoverable => warn loudly.
group_id="$(value_of KAFKA_NOTIFICATIONS_GROUP_ID)"
if [[ -n "$group_id" && "$group_id" != "$NOTIFICATION_GROUP_DEFAULT" ]]; then
    echo "::warning::KAFKA_NOTIFICATIONS_GROUP_ID in $ENV_FILE is '$group_id', but the app's default is"
    echo "::warning::'$NOTIFICATION_GROUP_DEFAULT'. This switches the Kafka consumer group, so notification"
    echo "::warning::events are replayed or skipped and recipients may be notified twice. Dedup only covers"
    echo "::warning::the last NOTIFICATION_DEDUP_TTL_HOURS, so expect this once, on the deploy that applies it."
fi

# ── Forwarding: does compose actually hand these to the app container? ────────
# Checked against the compose file *this commit* is about to deploy, not the one
# already on the VPS, so a commit that adds a variable to the guard and to
# compose together cannot be judged against the old definition.
#
# The match is whole-file, so it can only ever miss a variable that is forwarded
# to a different service — it cannot produce a false failure and block a good
# deploy, which is the direction that matters here.
forward_checked=0
if [[ -z "$COMPOSE_FILE" || ! -f "$COMPOSE_FILE" ]]; then
    echo "::warning::No compose file supplied (argument 2) — skipped the check that required variables"
    echo "::warning::are actually forwarded to the app container."
elif grep -qE '^[[:space:]]+env_file:' "$COMPOSE_FILE"; then
    echo "::notice::$(basename "$COMPOSE_FILE") uses env_file:, which forwards every entry — "
    echo "::notice::skipping the per-variable forwarding check."
else
    forward_checked=1
    echo
    echo "Forwarding: does $(basename "$COMPOSE_FILE") pass these to the app container?"
    for v in $BOOT_VARS $SILENT_VARS; do
        if grep -qE "^[[:space:]]+$v:" "$COMPOSE_FILE"; then
            printf '  %-24s forwarded\n' "$v"
        else
            echo "::error::$v is required at start-up but the app service in $(basename "$COMPOSE_FILE") does not forward it."
            echo "::error::Setting it in .env has no effect — the container never receives it, so the app boots without it."
            fail=1
        fi
    done
fi

if [[ "$fail" -ne 0 ]]; then
    echo "Pre-flight FAILED: fix the values above in $ENV_FILE on the VPS and re-run."
    echo "No image was rebuilt and no container was restarted."
    exit 1
fi

if [[ "$forward_checked" -eq 1 ]]; then
    echo "Pre-flight passed: every startup-critical variable is present, non-empty, and forwarded to the app."
else
    echo "Pre-flight passed: every startup-critical variable is present and non-empty."
fi
