#!/usr/bin/env bash
#
# bootstrap-vps.sh — stand up the RapidStylers production stack on a fresh VPS.
#
# Mirrors docs/duplication-guide.md §3. Installs Docker + nginx, creates the
# deploy user, opens the firewall, syncs the backend repo, and starts the prod
# compose stack (MySQL, Redis, Kafka, API), then waits for health.
#
# Target system design (docs/architecture.md §8, deploy/README.md):
#
#   Browser → Vercel (frontend)         Cloudflare: DNS + TLS + proxy
#              │
#              ▼
#   Cloudflare → nginx (host, TLS) → 127.0.0.1:9095 → Docker network
#        api.<domain>                Spring Boot jar       │
#                                                         ├─ mysql   (source of truth)
#                                                         ├─ redis   (limits/cache/geo/sessions)
#                                                         ├─ kafka   (email outbox, 3 topics)
#                                                         └─ kafka-init (topic creation, once)
#
#   Only HTTP(S) is public. Everything else stays private inside the Docker
#   network or on loopback; this script never publishes those ports.
#
# Usage:
#   sudo -E bash scripts/bootstrap-vps.sh --repo git@github.com:you/Rapidstylers_Backend.git
#   sudo -E bash scripts/bootstrap-vps.sh -s /path/to/local/checkout          # reuse a checkout
#   sudo -E bash scripts/bootstrap-vps.sh -s . --skip-install                 # rerun / app only
#
# Options:
#   -r, --repo <git-url>   Clone the backend from this URL (never copies .env/.git).
#   -s, --source <dir>     Sync from an existing checkout instead of cloning.
#   -e, --env <file>       Install a .env into the app dir (required unless one exists).
#   -u, --user <name>      Deploy user (default: rapiddeploy).
#   -d, --dir <path>       App directory (default: /opt/rapidstylers/backend).
#   --skip-install         Skip apt/docker/nginx install (for reruns).
#   -h, --help             Show this help.
#
# The .env is REQUIRED in the app dir before compose starts. Never commit it;
# rsync and git both exclude it so a rerun can never clobber the host copy.
set -euo pipefail

REPO_URL=""
SRC_DIR=""
ENV_FILE=""
DEPLOY_USER="rapiddeploy"
APP_DIR="/opt/rapidstylers/backend"
COMPOSE_FILE="docker-compose.prod.yml"
SKIP_INSTALL=0

usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -r|--repo) REPO_URL="$2"; shift 2 ;;
    -s|--source) SRC_DIR="$2"; shift 2 ;;
    -e|--env) ENV_FILE="$2"; shift 2 ;;
    -u|--user) DEPLOY_USER="$2"; shift 2 ;;
    -d|--dir) APP_DIR="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1"; usage 1 ;;
  esac
done

[[ $EUID -eq 0 ]] || { echo "Run with sudo: sudo -E bash ${BASH_SOURCE[0]} $*"; exit 1; }
[[ -n "$REPO_URL" || -n "$SRC_DIR" || -n "$ENV_FILE" ]] || { echo "Provide --repo, --source, or both."; usage 1; }

say()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- 1. install
if [[ $SKIP_INSTALL -eq 0 ]]; then
  say "Installing Docker, nginx, rsync, and ufw (Ubuntu packages)..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2 nginx ufw rsync curl
  systemctl enable --now docker nginx
  say "Docker + nginx installed and enabled."
else
  say "Skipping package install (--skip-install)."
fi

docker compose version >/dev/null 2>&1 \
  || { warn "docker compose (v2) not found. Install docker-compose-v2 or use the official Docker repo."; exit 1; }

# -------------------------------------------------------- 2. deploy user
say "Creating deploy user '$DEPLOY_USER' with docker access..."
if ! id -u "$DEPLOY_USER" >/dev/null 2>&1; then
  useradd -m -s /bin/bash "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"
SSH_DIR="/home/$DEPLOY_USER/.ssh"
mkdir -p "$SSH_DIR"
if [[ -f "$SSH_DIR/authorized_keys" && -s "$SSH_DIR/authorized_keys" ]]; then
  warn "authorized_keys already present — leaving it untouched."
elif [[ -f /root/.ssh/authorized_keys ]]; then
  cp /root/.ssh/authorized_keys "$SSH_DIR/authorized_keys"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$SSH_DIR/authorized_keys"
  say "Copied /root/.ssh/authorized_keys so CI can ssh in as '$DEPLOY_USER'."
else
  warn "No public key found. Add one before relying on CI deploys:"
  warn "  sudo mkdir -p $SSH_DIR"
  warn "  echo '<your pubkey>' | sudo tee $SSH_DIR/authorized_keys"
fi
chmod 700 "$SSH_DIR"; chown -R "$DEPLOY_USER:$DEPLOY_USER" "$SSH_DIR"
echo "$DEPLOY_USER ALL=(ALL) NOPASSWD: /usr/bin/docker" > "/etc/sudoers.d/${DEPLOY_USER}-docker" 2>/dev/null || true
chmod 440 "/etc/sudoers.d/${DEPLOY_USER}-docker" 2>/dev/null || true

# ------------------------------------------------------------- 3. firewall
say "Configuring ufw (22, 80, 443 only)..."
ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
ufw status | sed -n '1,8p'

# ------------------------------------------------------ 4. app dir + .env
say "Preparing app dir $APP_DIR ..."
mkdir -p "$APP_DIR"
if [[ -n "$ENV_FILE" ]]; then
  cp "$ENV_FILE" "$APP_DIR/.env"
  say "Installed .env from $ENV_FILE"
fi
if [[ ! -f "$APP_DIR/.env" ]]; then
  echo "No .env at $APP_DIR/.env and none given with --env." >&2
  echo "Create it first (cp .env.example .env on a checkout, or scp your prod .env) and rerun." >&2
  exit 1
fi
chmod 600 "$APP_DIR/.env"

# ------------------------------------------------- 5. sync the backend repo
if [[ -n "$REPO_URL" ]]; then
  say "Cloning backend from $REPO_URL ..."
  TMP_CLONE="$(mktemp -d)"
  git clone --depth 1 "$REPO_URL" "$TMP_CLONE/backend"
  SRC_DIR="$TMP_CLONE/backend"
fi
if [[ -n "$SRC_DIR" ]]; then
  SRC_DIR="$(cd "$SRC_DIR" && pwd)"
  [[ -f "$SRC_DIR/pom.xml" ]] || { echo "$SRC_DIR has no pom.xml — not a backend checkout." >&2; exit 1; }
  say "Syncing $SRC_DIR -> $APP_DIR (excluding .env/.git/target/.github) ..."
  rsync -az --delete \
    --exclude '.env' --exclude '.git' --exclude 'target' --exclude '.github' \
    "$SRC_DIR/" "$APP_DIR/"
  if [[ -n "$REPO_URL" ]]; then rm -rf "$TMP_CLONE"; fi
fi

# ------------------------------------------------- 6. build + start the stack
cd "$APP_DIR"
say "Starting the prod compose stack (first build pulls images + compiles the jar; be patient)..."
docker compose -f "$COMPOSE_FILE" up -d --build

# ------------------------------------------------------------- 7. wait for health
say "Waiting for the API to report healthy (up to ~10 min)..."
healthy=0
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:9095/actuator/health >/dev/null 2>&1; then healthy=1; break; fi
  sleep 10
done
if [[ $healthy -eq 0 ]]; then
  echo "API did not become healthy in time. Diagnose with:" >&2
  echo "  cd $APP_DIR && docker compose -f $COMPOSE_FILE logs --tail=100 app" >&2
  exit 1
fi

# ------------------------------------------------------------------ 8. report
say "Stack is up. Verification (run these every deploy):"
docker compose -f "$COMPOSE_FILE" ps
printf '\n'
echo "  # 1. Redis actually connected (silent-degradation check):"
echo "  docker logs rapidstylers-api 2>&1 | grep -iE 'Redis connected|Redis connection FAILED'"
echo "  # 2. Flyway applied V1-V12 on a fresh DB, admin seeded:"
echo "  docker logs rapidstylers-api 2>&1 | grep -iE 'Successfully applied 12 migrations|Refusing to seed bootstrap admin'"
echo "  # 3. Public smoke (once DNS/TLS point here):"
echo "  curl -i https://api.YOURDOMAIN.com/actuator/health"
echo "  curl -i -H 'x-api-key: \$APP_API_KEY' https://api.YOURDOMAIN.com/rapid_stylers/list_service"
echo
warn "Remaining host work (not automated): nginx site for api.YOURDOMAIN.com proxying to"
warn "127.0.0.1:9095 with TLS certs (see docs/duplication-guide.md §3), DNS at Cloudflare,"
warn "and a nightly MySQL backup cron."
