#!/usr/bin/env bash
# Claude Code PreToolUse guard for the Bash tool.
#
# The lighting desk is normally running as `./gradlew run` in the operator's own
# terminal — the app *is* a Gradle daemon. `gradle --stop` is registry-wide: it
# stops every daemon for that Gradle version, so an agent tidying up its own
# build kills the live show. When that happens the app's non-daemon threads keep
# the JVM alive, so the registry keeps a stale "busy" entry and the operator has
# to kill the process by hand before `./gradlew run` works again.
#
# Wire up from ~/.claude/settings.json:
#   "hooks": { "PreToolUse": [ { "matcher": "Bash", "hooks": [
#     { "type": "command",
#       "command": "/Users/chris/Development/Personal/lighting7/scripts/claude-gradle-guard.sh" } ] } ] }
#
# Exit 0 = allow, exit 2 = block the tool call and show stderr to the agent.
set -uo pipefail

cmd=$(python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("tool_input", {}).get("command", ""))
except Exception:
    pass' 2>/dev/null) || exit 0

[ -n "$cmd" ] || exit 0

if printf '%s' "$cmd" | grep -Eq '(gradlew?|gradle)[^;&|]*--stop|--stop[^;&|]*gradlew?'; then
    cat >&2 <<'MSG'
Blocked: `gradle --stop` stops EVERY Gradle daemon on this machine, including the
one hosting the operator's `./gradlew run` — that is the live lighting desk, and
stopping it kills the show and leaves a stale busy daemon behind.

Never stop or kill Gradle daemons. If a build is misbehaving, use `--no-daemon`,
`--rerun-tasks`, or `--offline` instead, and if you genuinely believe a daemon is
wedged, ask the operator to deal with it.
MSG
    exit 2
fi

# Command-position only, so `grep pkill …` and prose mentioning it stay allowed.
if printf '%s' "$cmd" | grep -Eq '(^|[;&|])[[:space:]]*(sudo[[:space:]]+)?(pkill|killall)\b[^;&|]*(java|gradle|Gradle|kotlin)'; then
    cat >&2 <<'MSG'
Blocked: killing java/gradle/kotlin processes would take down the operator's
running lighting7 desk (the app runs as a Gradle daemon) and any Kotlin compile
daemons in use. Don't kill JVMs — ask the operator instead.
MSG
    exit 2
fi

exit 0
