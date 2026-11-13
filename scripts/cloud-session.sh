#!/usr/bin/env bash
# Stand lighting7 up inside a Claude Code cloud session, with real show data pulled from its
# sync repo on Lighting7 Cloud. Idempotent: run it again and it only does what is missing.
#
# Usage:
#   scripts/cloud-session.sh [repo ...]
#
#   repo   a show's or a venue's repo on Lighting7 Cloud, by its name as the portal shows it
#          ("TCH venue") or its URL (https://lighting7.net/git/<org>/<repo>.git). A GitHub repo
#          imports too, as `owner/name` or a https://github.com/… URL, and any other https URL as a
#          plain git repo. An argument shaped `owner/name` is always GitHub's, so a cloud repo whose
#          name has that shape must be given by URL. Several may be given, in any order:
#          venue repos are imported before the shows that pin them (a show whose venue version is
#          not on the desk is refused with VENUE_MISSING), and the first show named is made the
#          current project. With none, the backend starts on an empty "Default" project.
#
# What it does, in order:
#   1. Installs JDK 24 (Temurin, from GitHub) if no JDK 24 is present. The build pins
#      toolchain 24 exactly, the image ships 21, apt only has 25, and the session's network
#      policy blocks the foojay API Gradle would otherwise download one from.
#   2. With a cloud repo named, asks the cloud for the org's repos (GET /v1/repos, the developer
#      token as a bearer) before anything starts: a cloud the session cannot reach fails here,
#      naming its host, and each name is resolved to its repo's URL and kind.
#   3. Writes `local.conf` into the data dir: pull-only sync (`sync.push = false`), the file
#      credential store (no keychain here), no mDNS, and `cloud.baseUrl` the cloud's when a cloud
#      repo is named; with none named it keeps the data dir's earlier one (else no cloud).
#   4. Starts `./gradlew run` in the background if nothing answers on :8413, with the developer
#      token as LIGHTING7_CLOUD_TOKEN, and waits for the show to be ready. That builds frontend/
#      too. Log: <data dir>/run.log.
#   5. Creates the first admin account (or logs in as it), so a browser test gets past the
#      "Set up this desk" screen with the credentials below, and finishes the onboarding that
#      account would otherwise be shown — the repos below are this desk's projects.
#   6. Imports each repo through cloud sync and switches its auto-sync off: every venue repo
#      first, then every show. Which a repo is comes from the cloud's list, or for a GitHub repo
#      from its project.json (`kind`, absent for a show), read from a shallow clone. A venue's import brings
#      every tagged version with it (venue plan session 4); a show's needs its pinned version
#      already here, so the venue repo must be in the list (or imported earlier) — the import of
#      a show without it fails naming the venue and the repo it syncs from.
#
# Cloud sessions only. Three things here only hold inside one:
#   * The cloud's host must be on the session environment's network allowlist, and a read
#     developer token from the portal must reach it: either as the environment variable
#     LIGHTING7_CLOUD_TOKEN, or as a network secret the session's proxy injects into requests to
#     that host. In the second case the desk is given a placeholder token, which the proxy
#     replaces on every request.
#   * A GitHub repo's stored token is a placeholder too: the session's git proxy authenticates
#     github.com requests itself for every repo attached to the session — so the repo must be
#     attached first (ask Claude to add it), or the import fails with AUTH_FAILED.
#   * Pull-only is what keeps a throwaway container from writing to the show's repo: the proxy
#     let a push through on a repo attached read-only, so repo permissions are not the guard.
#     Step 3 refuses to go on if a local.conf in the repo would override it.
#
# Environment:
#   LIGHTING7_DATA_DIR        data dir (default ~/lighting7-data): DB, logs, sync working trees
#   LIGHTING7_ADMIN_USER      first admin's username   (default admin)
#   LIGHTING7_ADMIN_PASSWORD  first admin's password   (default lighting7-dev)
#   LIGHTING7_AUTO_SYNC       1 leaves auto-sync on for imported projects (default off, so data
#                             does not change under a test; pull-only either way)
#   LIGHTING7_CLOUD_URL       the cloud (default https://lighting7.net)
#   LIGHTING7_CLOUD_TOKEN     a read developer token from the portal; unset, the proxy's network
#                             secret for the cloud's host is relied on
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
CLOUD_URL="${LIGHTING7_CLOUD_URL:-https://lighting7.net}"
CLOUD_URL="${CLOUD_URL%/}"
CLOUD_HOST="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.urlsplit(sys.argv[1]).hostname or "")' "$CLOUD_URL")"
# The session's proxy replaces whatever credential is sent to a host it holds a secret for.
CLOUD_TOKEN="${LIGHTING7_CLOUD_TOKEN:-proxy-injected}"
# Set when a cloud repo is named: the cloud's base URL the desk is configured with.
CLOUD_BASE=""
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

# `fetch OUT CURL_ARGS...` — runs curl with the body written to OUT; prints the HTTP status.
fetch() { local out="$1"; shift; curl -sS -o "$out" -w '%{http_code}' "$@"; }

# `api METHOD PATH [JSON]` — prints the body, fails on a non-2xx status with the body shown.
api() {
  local method="$1" path="$2" body="${3:-}" out status
  out="$(mktemp)"
  status="$(fetch "$out" -X "$method" -b "$JAR" -c "$JAR" \
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
  local repo_conf="$REPO_DIR/local.conf" conf="$DATA_DIR/local.conf" base="$CLOUD_BASE"
  # A run naming no cloud repo keeps the cloud an earlier run's projects sync with.
  if [[ -z "$base" && -f "$conf" ]]; then
    base="$(sed -nE 's/^cloud \{ baseUrl = "([^"]*)" \}$/\1/p' "$conf" | head -1)"
  fi
  [[ "$base" =~ ^[A-Za-z0-9:/._~%-]*$ ]] || die "cloud URL \"$base\" has characters local.conf cannot hold"
  # A local.conf in the working directory wins over the data dir's (Application.kt), so one
  # left in the repo would silently drop pull-only, and with no cloud.baseUrl point the desk at
  # the production cloud.
  if [[ -f "$repo_conf" ]] && ! grep -Eq '^[^#]*(^|[[:space:].{])push[[:space:]]*[=:][[:space:]]*"?(false|off|no|0)"?[[:space:]]*($|[#,}]|//)' "$repo_conf"; then
    die "$repo_conf overrides $conf and does not set sync.push = false. Delete it, or add that key."
  fi
  if [[ -f "$repo_conf" && -n "$base" ]] && ! grep -E '^[^#]*(^|[[:space:].{])baseUrl[[:space:]]*[=:]' "$repo_conf" | grep -Fq "\"$base\""; then
    die "$repo_conf overrides $conf and does not set cloud.baseUrl to $base. Delete it, or set cloud { baseUrl = \"$base\" }."
  fi
  if [[ -f "$repo_conf" ]] && ! grep -Eq '^[^#]*(^|[[:space:].{])baseUrl[[:space:]]*[=:]' "$repo_conf"; then
    die "$repo_conf overrides $conf and does not set cloud.baseUrl, so this desk would use the production cloud. Delete it, or add cloud { baseUrl = \"\" }."
  fi
  mkdir -p "$DATA_DIR"
  {
    cat <<'EOF'
# Written by scripts/cloud-session.sh — a throwaway cloud-session install.
database { path = "" }
mdns { enabled = false }
sentry { environment = "cloud" }
EOF
    printf 'cloud { baseUrl = "%s" }\n' "$base"
    cat <<'EOF'
sync {
    # Pull-only: this install must never write to a show's repo. See
    # docs/sync-engineering.md §"Pull-only installs".
    push = false
    credentialStore = "file"
}
EOF
  } > "$conf"
  log "Wrote $conf (pull-only sync${base:+, cloud $base})"
}

status_ready() { curl -sf -m 3 "$BASE/status" 2>/dev/null | grep -q '"ready":true'; }

start_backend() {
  if curl -sf -m 3 "$BASE/status" >/dev/null 2>&1; then
    log "Backend already answering on :8413"
    PULL_ONLY_UNVERIFIED=1
    printf '\033[33mwarning:\033[0m this script did not start that backend, so it cannot tell whether it read the pull-only %s, or holds the cloud token. Restart it through this script if unsure.\n' "$DATA_DIR/local.conf" >&2
  else
    log "Starting ./gradlew run (first run builds both repos; log: $DATA_DIR/run.log)"
    # Redirect the whole detached group, stdin included, and exec down to Gradle: a
    # subshell left holding this script's stdout keeps anything reading it (a pipe, an
    # agent's tool call) waiting for EOF for as long as the backend runs.
    (cd "$REPO_DIR" && LIGHTING7_CLOUD_TOKEN="$CLOUD_TOKEN" exec setsid nohup ./gradlew run --console=plain) \
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
    api POST /install/onboarding/finish '{}' >/dev/null
    log "Created admin '$ADMIN_USER'"
  else
    api POST /auth/login "$login" >/dev/null || die "could not log in as '$ADMIN_USER' — set LIGHTING7_ADMIN_USER / LIGHTING7_ADMIN_PASSWORD"
    log "Logged in as '$ADMIN_USER'"
  fi
}

# `cloud_repos` — the org's repos as the cloud lists them (GET /v1/repos), or a failure that says
# whether the session cannot reach the cloud at all or the cloud refused the token.
cloud_repos() {
  local out err status
  out="$(mktemp)"; err="$(mktemp)"
  # The header goes in on stdin, so the token is not in curl's argv.
  if ! status="$(printf 'Authorization: Bearer %s\n' "$CLOUD_TOKEN" \
      | fetch "$out" -m 30 -H @- "$CLOUD_URL/v1/repos" 2>"$err")"; then
    local why; why="$(cat "$err")"; rm -f "$out" "$err"
    die "cannot reach $CLOUD_HOST ($why). Allow $CLOUD_HOST in this session environment's network access, then start a new session."
  fi
  rm -f "$err"
  if [[ "$status" != 200 ]]; then
    local said; said="$(json '"%s: %s" % (d.get("code"), d.get("message"))' < "$out" 2>/dev/null || cat "$out")"
    rm -f "$out"
    case "$status" in
      401|403) die "$CLOUD_HOST refused the developer token (HTTP $status, $said). Save a read developer token from the portal as LIGHTING7_CLOUD_TOKEN, or as the environment's network secret for $CLOUD_HOST." ;;
      *) die "$CLOUD_URL/v1/repos answered HTTP $status ($said)" ;;
    esac
  fi
  if ! python3 -c '
import json, sys
repos = json.load(sys.stdin)["repos"]
assert isinstance(repos, list) and all(isinstance(r.get("name"), str) and isinstance(r.get("url"), str) for r in repos)
' < "$out" 2>/dev/null; then
    rm -f "$out"
    die "$CLOUD_URL/v1/repos did not answer a list of repos; is $CLOUD_HOST a Lighting7 Cloud?"
  fi
  cat "$out"
  rm -f "$out"
}

# The repo path of a URL on the cloud's origin (scheme and host in any case, a default port
# spelled or not), without a trailing slash or .git; None for any other URL.
CLOUD_PATH_PY='
import os, urllib.parse
def cloud_path(url):
    def origin(u):
        return (u.scheme, u.hostname, u.port or {"https": 443, "http": 80}.get(u.scheme))
    try:
        u, base = urllib.parse.urlsplit(url), urllib.parse.urlsplit(os.environ["CLOUD_URL"])
        if u.scheme not in ("http", "https") or u.username or u.query or u.fragment or origin(u) != origin(base):
            return None
    except ValueError:
        return None
    path = u.path.rstrip("/").removesuffix(".git")
    return path if path.startswith("/git/") else None
'

is_url() { [[ "${1,,}" == https://* || "${1,,}" == http://* ]]; }

is_cloud_url() {
  ARG="$1" CLOUD_URL="$CLOUD_URL" python3 -c "$CLOUD_PATH_PY"'
import sys
sys.exit(0 if cloud_path(os.environ["ARG"]) else 1)'
}

is_github_repo() { [[ "$1" == https://github.com/* || "$1" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; }

# `resolve_cloud_repo LIST ARG` — "KIND URL" for a cloud repo named by its URL or its name (exact,
# else the one name matching ignoring case); fails naming the org's repos otherwise.
resolve_cloud_repo() {
  ARG="$2" CLOUD_URL="$CLOUD_URL" python3 -c "$CLOUD_PATH_PY"'
import json, sys
repos = json.loads(sys.argv[1])["repos"]
arg = os.environ["ARG"]
if cloud_path(arg):
    hits = [r for r in repos if cloud_path(r["url"]) == cloud_path(arg)]
    if not hits:
        sys.exit("the cloud lists no repo at %s" % arg)
else:
    hits = [r for r in repos if r["name"] == arg] or [r for r in repos if r["name"].casefold() == arg.casefold()]
if len(hits) != 1:
    names = ", ".join(sorted("\"%s\"" % r["name"] for r in repos)) or "none"
    sys.exit("%s names %s of the cloud'"'"'s repos (the org has: %s)" % (arg, "several" if hits else "none", names))
if hits[0].get("kind") not in ("VENUE", "SHOW"):
    sys.exit("the cloud lists %s with kind %s, neither VENUE nor SHOW" % (hits[0]["name"], hits[0].get("kind")))
print(hits[0]["kind"], hits[0]["url"])
' "$1"
}

# `repo_kind URL` — VENUE or SHOW, from the repo's project.json (a shallow clone through the
# session's git proxy). A repo that cannot be read is treated as a show: its import then fails
# with the desk's own reason.
repo_kind() {
  local url="$1" tmp kind
  tmp="$(mktemp -d)"
  if git clone --quiet --depth 1 "$url" "$tmp/repo" >/dev/null 2>&1 && [[ -f "$tmp/repo/project.json" ]]; then
    kind="$(json 'd.get("kind", "SHOW")' < "$tmp/repo/project.json")"
  else
    # Said out loud: a venue repo misread as a show is imported after the shows that pin it, and
    # their VENUE_MISSING would otherwise read as the show's fault.
    printf '\033[33mwarning:\033[0m could not read %s/project.json; importing it as a show\n' "$url" >&2
    kind="SHOW"
  fi
  rm -rf "$tmp"
  echo "$kind"
}

normalise_repo() {
  local r="${1%.git}"
  if is_url "$r"; then echo "$r"; else echo "https://github.com/$r"; fi
}

# `import_repo SOURCE URL` — SOURCE `cloud` or `git`; prints the project id. A git repo's token is
# stored per repo URL through a project's sync settings, and the import creates the project — so
# a throwaway project carries the placeholder token long enough for the import to find it.
import_repo() {
  local source="$1" url="$2" existing temp_id project_id
  existing="$(api GET /cloud-sync/configs | URL="$url" json "next((k for k, c in d.items() if c.get('repoUrl') == __import__('os').environ['URL']), '')")"
  if [[ -n "$existing" ]]; then
    log "$url already imported as project $existing" >&2
    echo "$existing"
    return
  fi
  if [[ "$source" == cloud ]]; then
    # The desk's developer token is the cloud repo's credential: nothing to store.
    project_id="$(api POST /cloud-sync/import "{\"repoUrl\":\"$url\"}" | json 'd["projectId"]')" \
      || die "import of $url failed"
  else
    temp_id="$(api POST /projects "{\"name\":\"_cloud-session-token-$$-$RANDOM\"}" | json 'd["id"]')"
    api PUT "/projects/$temp_id/sync/config" "{\"repoUrl\":\"$url\",\"autoSyncEnabled\":false}" >/dev/null
    api PUT "/projects/$temp_id/sync/credentials" "{\"pat\":\"$PLACEHOLDER_TOKEN\"}" >/dev/null
    if ! project_id="$(api POST /cloud-sync/import "{\"repoUrl\":\"$url\"}" | json 'd["projectId"]')"; then
      api DELETE "/projects/$temp_id" >/dev/null || true
      die "import of $url failed — is the repo attached to this session?"
    fi
    api DELETE "/projects/$temp_id" >/dev/null
    api PUT "/projects/$project_id/sync/credentials" "{\"pat\":\"$PLACEHOLDER_TOKEN\"}" >/dev/null
  fi
  if [[ "${LIGHTING7_AUTO_SYNC:-0}" != 1 ]]; then
    api PUT "/projects/$project_id/sync/config" '{"autoSyncEnabled":false}' >/dev/null
  fi
  log "Imported $url as project $project_id" >&2
  echo "$project_id"
}

main() {
  ensure_jdk24

  # Venues before shows: a show's import resolves its links against its pinned venue version.
  # Every repo is classified before the desk starts, so an unreachable cloud fails first.
  local first="" repo url id kind source resolved list="" venues=() shows=()
  for repo in "$@"; do
    if { is_url "$repo" && ! is_cloud_url "$repo"; } || { ! is_url "$repo" && is_github_repo "$repo"; }; then
      source=git
      url="$(normalise_repo "$repo")"
      kind="$(repo_kind "$url")"
    else
      source=cloud
      if [[ -z "$list" ]]; then
        list="$(cloud_repos)"
        CLOUD_BASE="$CLOUD_URL"
        log "$CLOUD_HOST lists $(json 'len(d["repos"])' <<<"$list") repos"
      fi
      resolved="$(resolve_cloud_repo "$list" "$repo")" || die "no single cloud repo for \"$repo\""
      kind="${resolved%% *}"; url="${resolved#* }"
    fi
    log "$repo: ${kind,,} repo $url"
    if [[ "$kind" == VENUE ]]; then venues+=("$source $url"); else shows+=("$source $url"); fi
  done

  write_conf
  start_backend
  ensure_admin

  for repo in "${venues[@]}"; do
    import_repo "${repo%% *}" "${repo#* }" >/dev/null
  done
  for repo in "${shows[@]}"; do
    id="$(import_repo "${repo%% *}" "${repo#* }")"
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
