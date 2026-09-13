#!/usr/bin/env bash
#
# reconcile-encrypt-key.sh — keep the production ENCRYPT_KEY recoverable.
#
# The production key otherwise lives ONLY in the VPS .env, which the deploy
# rsync never overwrites (`--exclude .env`). A wiped disk or a re-provisioned
# server would lose it — and an empty ENCRYPT_KEY is exactly how this API was
# taken down once already.
#
# Usage (CI):
#   ENCRYPT_KEY_SECRET_FILE=/tmp/key bash scripts/reconcile-encrypt-key.sh
# Usage (local):
#   ENCRYPT_KEY_SECRET=<value> ENV_FILE=.env bash scripts/reconcile-encrypt-key.sh
#
# Inputs:
#   ENV_FILE                 target .env (default /opt/rapidstylers/backend/.env)
#   ENCRYPT_KEY_SECRET_FILE  file holding the secret (the CI path; preferred,
#                            because it keeps the value out of the process list)
#   ENCRYPT_KEY_SECRET       the secret itself (local convenience)
#
# Reconciliation rules — deliberately conservative:
#   secret unset            -> warn and change nothing (never block a deploy
#                              that is working; the key is already on the box)
#   key missing or empty    -> restore it from the secret
#   key == secret           -> no-op
#   key != secret           -> FAIL and change nothing
#
# That last rule is the important one. Overwriting a working key would rotate
# the encryption key with no migration, making existing ciphertext unreadable —
# irreversible for data nobody has a copy of. A mismatch is a human decision, so
# this stops the deploy and prints both fingerprints instead of guessing.
#
# The key itself is never printed: the command line, the log and the process
# list only ever see a truncated SHA-256 fingerprint.

set -uo pipefail

ENV_FILE="${ENV_FILE:-/opt/rapidstylers/backend/.env}"
KEY_NAME="ENCRYPT_KEY"

# Read the secret from a file when given one (CI), else from the environment.
secret="${ENCRYPT_KEY_SECRET:-}"
if [[ -z "$secret" && -n "${ENCRYPT_KEY_SECRET_FILE:-}" && -f "${ENCRYPT_KEY_SECRET_FILE}" ]]; then
    secret="$(cat "$ENCRYPT_KEY_SECRET_FILE")"
fi
# Trim whitespace/newlines that a file transfer or `gh secret set` may add.
secret="${secret#"${secret%%[![:space:]]*}"}"
secret="${secret%"${secret##*[![:space:]]}"}"

fingerprint() {
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$1" | sha256sum | cut -c1-16
    else
        printf '%s' "$1" | shasum -a 256 | cut -c1-16
    fi
}

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

if [[ -z "$secret" ]]; then
    echo "::notice::Repository secret ENCRYPT_KEY is not set — skipping reconciliation."
    echo "::warning::The key stays only in $ENV_FILE, so it cannot be recovered after a re-provision."
    exit 0
fi

# A value containing these would be mangled (or break other keys) when written
# into a dotenv file, because Compose strips quotes and interpolates $.
if [[ "$secret" == *'"'* || "$secret" == *'\'* || "$secret" == *'$'* || "$secret" == *'`'* || "$secret" == *[[:space:]]* ]]; then
    echo "::error::The ENCRYPT_KEY secret contains a character that cannot be written safely to a dotenv file"
    echo "::error::(double quote, backslash, dollar sign, backtick or whitespace). Fix the secret and re-run."
    exit 1
fi

# A file we cannot read must never be treated as "key missing": that would turn
# an unreadable .env into an overwrite of a key we simply failed to see.
if [[ -f "$ENV_FILE" && ! -r "$ENV_FILE" ]]; then
    echo "::error::Cannot read $ENV_FILE — refusing to guess whether $KEY_NAME is already set."
    echo "::error::Run the deploy as the user that owns it (rapiddeploy). Nothing was changed."
    exit 1
fi

current="$(read_current)"

if [[ -n "$current" && "$current" == "$secret" ]]; then
    echo "::notice::$KEY_NAME is already in sync with the repository secret (fingerprint $(fingerprint "$secret"))."
    exit 0
fi

if [[ -n "$current" ]]; then
    echo "::error::$KEY_NAME in $ENV_FILE does not match the repository secret — refusing to overwrite it."
    echo "::error::  on the VPS:     fingerprint $(fingerprint "$current")"
    echo "::error::  in the secret:  fingerprint $(fingerprint "$secret")"
    echo "::error::Overwriting would rotate the key with no migration and make existing ciphertext unreadable."
    echo "::error::To adopt the VPS value, set the repository secret to it (Settings > Secrets and variables > Actions)."
    echo "::error::To rotate deliberately, run the rotation first and update both together."
    exit 1
fi

# --- restore path: the key is absent or empty ---------------------------------

if [[ ! -f "$ENV_FILE" ]]; then
    echo "::warning::$ENV_FILE does not exist — creating it with only $KEY_NAME."
    echo "::warning::Every other required value must be restored separately; the pre-flight will list them."
fi

tmp="$(mktemp "${ENV_FILE}.tmp.XXXXXX")" || {
    echo "::error::Cannot create a temporary file next to $ENV_FILE — aborting."
    exit 1
}
trap 'rm -f "$tmp"' EXIT
chmod 600 "$tmp"

# Carry the existing content over, minus any (possibly empty) key definition.
original_bytes=0
removed_bytes=0
if [[ -f "$ENV_FILE" ]]; then
    original_bytes="$(wc -c < "$ENV_FILE")"
    removed_bytes="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${KEY_NAME}=" "$ENV_FILE" | wc -c)"
    grep -vE "^[[:space:]]*(export[[:space:]]+)?${KEY_NAME}=" "$ENV_FILE" > "$tmp"
    grep_rc=$?
    # grep exits 1 when nothing matched (the file held only key lines) — that is
    # fine. Anything else means the read failed, and writing now would replace a
    # file full of production secrets with a nearly empty one.
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

echo "::notice::Restored $KEY_NAME into $ENV_FILE from the repository secret (fingerprint $(fingerprint "$secret"))."
