#!/usr/bin/env bash
# Stand lighting7 up inside a Claude Code cloud session, with real show data pulled from its
# cloud-sync repo. Idempotent: run it again and it only does what is missing.
#
# Usage:
#   scripts/cloud-session.sh [repo ...]
#
#   repo   a show's sync repo, as `owner/name` or a full https://github.com/… URL, e.g.
#          cjcormack/lighting7-tch-test. Several may be given; the first one imported is made
#          the current project. With none, the backend starts on an empty "Default" project.
#
# What it does, in order:
#   1. Installs JDK 24 (Temurin, from GitHub) if no JDK 24 is present. The build pins
#      toolchain 24 exactly, the image ships 21, apt only has 25, and the session's network
#      policy blocks the foojay API Gradle would otherwise download one from.
#   2. Writes `local.conf` into the data dir: pull-only sync (`sync.push = false`), the file
#      credential store (no keychain here), and no mDNS.
#   3. Starts `./gradlew run` in the background if nothing answers on :8413, and waits for the
#      show to be ready. That builds ../lighting-react too. Log: <data dir>/run.log.
#   4. Creates the first admin account (or logs in as it), so a browser test gets past the
#      "Set up this desk" screen with the credentials below.
#   5. Imports each repo through cloud sync and switches its auto-sync off.
#
# Cloud sessions only. Two things here only hold inside one:
#   * The stored token is a placeholder. The session's git proxy authenticates github.com
#     requests itself for every repo attached to the session, whatever token JGit sends — so
#     the repo must be attached to the session first (ask Claude to add it), or the import
#     fails with AUTH_FAILED.
#   * Pull-only is what keeps a throwaway container from writing to the show's repo: the proxy
#     let a push through on a repo attached read-only, so repo permissions are not the guard.
#     Step 2 refuses to go on if a local.conf in the repo would override it.
#
# Environment:
#   LIGHTING7_DATA_DIR        data dir (default ~/lighting7-data): DB, logs, sync working trees
#   LIGHTING7_ADMIN_USER      first admin's username   (default admin)
#   LIGHTING7_ADMIN_PASSWORD  first admin's password   (default lighting7-dev)
#   LIGHTING7_AUTO_SYNC       1 leaves auto-sync on for imported projects (default off, so data
#                             does not change under a test; pull-only either way)
set -euo pipefail
# import_repo runs inside `$(...)`, where bash otherwise clears -e: a failed API call there
# (the placeholder token, auto-sync off) would be ignored and the import reported as done.
shopt -s inherit_errexit

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${LIGHTING7_DATA_DIR:-$HOME/lighting7-data}"
ADMIN_USER="${LIGHTING7_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${LIGHTING7_ADMIN_PASSWORD:-lighting7-dev}"
BASE="http://localhost:8413/api/rest"
JAR="$DATA_DIR/.cloud-session-cookies"
JDK_URL="https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.2%2B12/OpenJDK24U-jdk_x64_linux_hotspot_24.0.2_12.tar.gz"
JDK_DIR="/usr/lib/jvm/temurin-24"
PLACEHOLDER_TOKEN="cloud-session-proxy-authenticates"
# Set when a backend was already answering: this script did not start it, so the local.conf
# it just wrote may not be the one that backend read.
PULL_ONLY_UNVERIFIED=""

# The image's locale is POSIX, and the JVM then cannot write class files named after test
# names containing non-ASCII ("—"): compileTestKotlin dies with an internal compiler error.
export LC_ALL=C.UTF-8 LANG=C.UTF-8
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx2g}"
export LIGHTING7_DATA_DIR="$DATA_DIR"

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# `api METHOD PATH [JSON]` — prints the body, fails on a non-2xx status with the body shown.
api() {
  local method="$1" path="$2" body="${3:-}" out status
  out="$(mktemp)"
  status="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -b "$JAR" -c "$JAR" \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$BASE$path")" || { rm -f "$out"; return 1; }
  if [[ "$status" != 2* ]]; then
    echo "$method $path -> HTTP $status: $(cat "$out")" >&2
    rm -f "$out"
    return 1
  fi
  cat "$out"
  rm -f "$out"
}

# `json EXPR` — evaluate a Python expression over the JSON on stdin (bound to `d`).
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

ensure_jdk24() {
  local j
  for j in /usr/lib/jvm/*/bin/java; do
    if "$j" -version 2>&1 | grep -q 'version "24'; then
      log "JDK 24 present at ${j%/bin/java}"
      return
    fi
  done
  log "Installing Temurin JDK 24 into $JDK_DIR"
  mkdir -p "$JDK_DIR"
  curl -fsSL "$JDK_URL" | tar -xz -C "$JDK_DIR" --strip-components=1
}

write_conf() {
  local repo_conf="$REPO_DIR/local.conf"
  # A local.conf in the working directory wins over the data dir's (Application.kt), so one
  # left in the repo would silently drop pull-only.
  if [[ -f "$repo_conf" ]] && ! grep -Eq '^[^#]*(^|[[:space:].{])push[[:space:]]*[=:][[:space:]]*"?(false|off|no|0)"?[[:space:]]*($|[#,}]|//)' "$repo_conf"; then
    die "$repo_conf overrides $DATA_DIR/local.conf and does not set sync.push = false. Delete it, or add that key."
  fi
  mkdir -p "$DATA_DIR"
  cat > "$DATA_DIR/local.conf" <<'EOF'
# Written by scripts/cloud-session.sh — a throwaway cloud-session install.
database { path = "" }
mdns { enabled = false }
sync {
    # Pull-only: this install must never write to a show's repo. See
    # docs/sync-engineering.md §"Pull-only installs".
    push = false
    credentialStore = "file"
}
EOF
  log "Wrote $DATA_DIR/local.conf (pull-only sync)"
}

status_ready() { curl -sf -m 3 "$BASE/status" 2>/dev/null | grep -q '"ready":true'; }

start_backend() {
  if curl -sf -m 3 "$BASE/status" >/dev/null 2>&1; then
    log "Backend already answering on :8413"
    PULL_ONLY_UNVERIFIED=1
    printf '\033[33mwarning:\033[0m this script did not start that backend, so it cannot tell whether it read the pull-only %s. Restart it through this script if unsure.\n' "$DATA_DIR/local.conf" >&2
  else
    log "Starting ./gradlew run (first run builds both repos; log: $DATA_DIR/run.log)"
    # Redirect the whole detached group, stdin included, and exec down to Gradle: a
    # subshell left holding this script's stdout keeps anything reading it (a pipe, an
    # agent's tool call) waiting for EOF for as long as the backend runs.
    (cd "$REPO_DIR" && exec setsid nohup ./gradlew run --console=plain) \
      > "$DATA_DIR/run.log" 2>&1 < /dev/null &
    disown
  fi
  local waited=0
  until status_ready; do
    if grep -qE 'BUILD FAILED|FAILURE:' "$DATA_DIR/run.log" 2>/dev/null; then
      tail -30 "$DATA_DIR/run.log" >&2
      die "the backend failed to start"
    fi
    (( waited >= 1200 )) && die "the backend was not ready after 20 minutes — see $DATA_DIR/run.log"
    sleep 5; waited=$((waited + 5))
  done
  log "Backend ready"
}

ensure_admin() {
  rm -f "$JAR"
  # The desk's JSON refuses unknown keys, so setup and login each get exactly their own.
  local login setup
  login="$(python3 -c 'import json,sys; print(json.dumps(dict(username=sys.argv[1], password=sys.argv[2])))' "$ADMIN_USER" "$ADMIN_PASSWORD")"
  setup="$(python3 -c 'import json,sys; print(json.dumps(dict(username=sys.argv[1], displayName="Cloud session", password=sys.argv[2])))' "$ADMIN_USER" "$ADMIN_PASSWORD")"
  if [[ "$(api GET /auth/status | json 'd["setupRequired"]')" == "True" ]]; then
    api POST /auth/setup "$setup" >/dev/null
    log "Created admin '$ADMIN_USER'"
  else
    api POST /auth/login "$login" >/dev/null || die "could not log in as '$ADMIN_USER' — set LIGHTING7_ADMIN_USER / LIGHTING7_ADMIN_PASSWORD"
    log "Logged in as '$ADMIN_USER'"
  fi
}

normalise_repo() {
  local r="${1%.git}"
  [[ "$r" == https://* ]] && echo "$r" || echo "https://github.com/$r"
}

# Import one repo; prints the project id. Tokens are stored per repo URL through a project's
# sync settings, and the import creates the project — so a throwaway project carries the
# placeholder token long enough for the import to find it.
import_repo() {
  local url="$1" existing temp_id project_id
  existing="$(api GET /cloud-sync/configs | URL="$url" json "next((k for k, c in d.items() if c.get('repoUrl') == __import__('os').environ['URL']), '')")"
  if [[ -n "$existing" ]]; then
    log "$url already imported as project $existing" >&2
    echo "$existing"
    return
  fi
  temp_id="$(api POST /projects "{\"name\":\"_cloud-session-token-$$-$RANDOM\"}" | json 'd["id"]')"
  api PUT "/projects/$temp_id/sync/config" "{\"repoUrl\":\"$url\",\"autoSyncEnabled\":false}" >/dev/null
  api PUT "/projects/$temp_id/sync/credentials" "{\"pat\":\"$PLACEHOLDER_TOKEN\"}" >/dev/null
  if ! project_id="$(api POST /cloud-sync/import "{\"repoUrl\":\"$url\"}" | json 'd["projectId"]')"; then
    api DELETE "/projects/$temp_id" >/dev/null || true
    die "import of $url failed — is the repo attached to this session?"
  fi
  api DELETE "/projects/$temp_id" >/dev/null
  api PUT "/projects/$project_id/sync/credentials" "{\"pat\":\"$PLACEHOLDER_TOKEN\"}" >/dev/null
  if [[ "${LIGHTING7_AUTO_SYNC:-0}" != 1 ]]; then
    api PUT "/projects/$project_id/sync/config" '{"autoSyncEnabled":false}' >/dev/null
  fi
  log "Imported $url as project $project_id" >&2
  echo "$project_id"
}

main() {
  [[ -d "$REPO_DIR/../lighting-react" ]] || die "expected lighting-react beside lighting7 at $REPO_DIR/../lighting-react"
  ensure_jdk24
  write_conf
  start_backend
  ensure_admin

  local first="" repo id
  for repo in "$@"; do
    id="$(import_repo "$(normalise_repo "$repo")")"
    [[ -z "$first" ]] && first="$id"
  done
  if [[ -n "$first" ]]; then
    api POST "/projects/$first/set-current" >/dev/null
    log "Project $first is current"
  fi

  cat <<EOF

lighting7 is up at http://localhost:8413/
  admin login:  $ADMIN_USER / $ADMIN_PASSWORD
  data dir:     $DATA_DIR   (log: run.log)
  sync:         ${PULL_ONLY_UNVERIFIED:+UNVERIFIED (backend was already running) — }pull-only — nothing this session does reaches a show's repo
EOF
}

main "$@"
