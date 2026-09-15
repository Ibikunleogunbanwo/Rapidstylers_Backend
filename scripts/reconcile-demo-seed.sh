#!/usr/bin/env bash
#
# reconcile-demo-seed.sh — drive APP_DEMO_SEED on the VPS from a repository secret.
#
# The VPS .env is owned by rapiddeploy (0600) and the deploy rsync never
# overwrites it (`--exclude .env`), so nothing outside this pipeline can change
# it — including an operator working from CI. This script is the write path for
# the demo-seed flag, the same pattern as reconcile-encrypt-key.sh: the value
# travels as a GitHub secret into a 0600 file, then into .env as rapiddeploy,
# never on a command line.
#
# Usage (CI):
#   APP_DEMO_SEED_SECRET_FILE=/tmp/flag bash scripts/reconcile-demo-seed.sh
# Usage (local):
#   APP_DEMO_SEED_SECRET=true ENV_FILE=.env bash scripts/reconcile-demo-seed.sh
#
# Rules — deliberately different from the key script, because a boolean flag
# carries no ciphertext: flipping it is reversible and harmless, while an
# unrecorded flip is the only real danger.
#   secret unset        -> notice and change nothing (flag stays as-is)
#   secret not true/false -> FAIL (a typo must not become a config value)
#   current missing/empty -> write the flag
#   current == secret   -> no-op
#   current != secret   -> update it and say so (old -> new, in the log)
#
# The flag is not secret; the scripts print it. Only the scaffolding (atomic
# write, ownership preservation, never-clobber-on-read-failure) is borrowed
# from the key script.

set -uo pipefail

ENV_FILE="${ENV_FILE:-/opt/rapidstylers/backend/.env}"
KEY_NAME="APP_DEMO_SEED"

# Read the secret from a file when given one (CI), else from the environment.
secret="${APP_DEMO_SEED_SECRET:-}"
if [[ -z "$secret" && -n "${APP_DEMO_SEED_SECRET_FILE:-}" && -f "${APP_DEMO_SEED_SECRET_FILE}" ]]; then
    secret="$(cat "${APP_DEMO_SEED_SECRET_FILE}")"
fi
# Trim whitespace/newlines that a file transfer or `gh secret set` may add.
secret="${secret#"${secret%%[![:space:]]*}"}"
secret="${secret%"${secret##*[![:space:]]}"}"

if [[ -z "$secret" ]]; then
    echo "::notice::Repository secret APP_DEMO_SEED is not set — leaving the flag as it is on the VPS."
    exit 0
fi

secret="$(echo "$secret" | tr '[:upper:]' '[:lower:]')"
if [[ "$secret" != "true" && "$secret" != "false" ]]; then
    echo "::error::The APP_DEMO_SEED secret must be exactly true or false (got something else). Fix the secret and re-run."
    exit 1
fi

# Last definition wins in a dotenv file (verified against docker compose), so
# the effective value is the last one, with surrounding quotes stripped.
read_current() {
    [[ -f "$ENV_FILE" ]] || return 0
    awk -v k="$KEY_NAME" '
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

# A file we cannot read must never be treated as "flag missing": that would
# turn an unreadable .env into a blind write against production secrets.
if [[ -f "$ENV_FILE" && ! -r "$ENV_FILE" ]]; then
    echo "::error::Cannot read $ENV_FILE — refusing to guess whether $KEY_NAME is already set."
    echo "::error::Run the deploy as the user that owns it (rapiddeploy). Nothing was changed."
    exit 1
fi

current="$(read_current)"
current_lower="$(echo "$current" | tr '[:upper:]' '[:lower:]')"

if [[ -n "$current_lower" && "$current_lower" == "$secret" ]]; then
    echo "::notice::$KEY_NAME is already $secret in $ENV_FILE — no change."
    exit 0
fi

tmp="$(mktemp "${ENV_FILE}.tmp.XXXXXX")" || {
    echo "::error::Cannot create a temporary file next to $ENV_FILE — aborting."
    exit 1
}
trap 'rm -f "$tmp"' EXIT
chmod 600 "$tmp"

# Carry the existing content over, minus any (possibly empty) flag definition.
original_bytes=0
removed_bytes=0
if [[ -f "$ENV_FILE" ]]; then
    original_bytes="$(wc -c < "$ENV_FILE")"
    removed_bytes="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${KEY_NAME}=" "$ENV_FILE" | wc -c)"
    grep -vE "^[[:space:]]*(export[[:space:]]+)?${KEY_NAME}=" "$ENV_FILE" > "$tmp"
    grep_rc=$?
    # grep exits 1 when nothing matched (the file held only flag lines) — fine.
    # Anything else means the read failed, and writing now would replace a file
    # full of production secrets with a nearly empty one.
    if [[ "$grep_rc" -gt 1 ]]; then
        echo "::error::Could not read $ENV_FILE (grep exit $grep_rc) — aborting without changing anything."
        exit 1
    fi
fi

printf '%s="%s"\n' "$KEY_NAME" "$secret" >> "$tmp"

# Safety invariant: the result must contain everything we did not set out to
# remove. Guards against a partial write or a surprise read failure.
if [[ "$original_bytes" -gt 0 ]]; then
    new_bytes="$(wc -c < "$tmp")"
    if [[ "$new_bytes" -lt $((original_bytes - removed_bytes)) ]]; then
        echo "::error::Refusing to write $ENV_FILE: the new content ($new_bytes bytes) is smaller than the"
        echo "::error::existing content minus the replaced line ($((original_bytes - removed_bytes)) bytes)."
        exit 1
    fi
fi

# `cat >` keeps the existing inode, owner and mode of a file that exists.
cat "$tmp" > "$ENV_FILE"
chmod 600 "$ENV_FILE"

if [[ -n "$current_lower" ]]; then
    echo "::notice::Updated $KEY_NAME in $ENV_FILE: $current_lower -> $secret. Takes effect on the next app restart."
else
    echo "::notice::Set $KEY_NAME=$secret in $ENV_FILE. Takes effect on the next app restart."
fi
