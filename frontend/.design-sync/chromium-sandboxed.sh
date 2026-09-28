#!/bin/sh
# Launch wrapper for Playwright's Chromium, used via DS_CHROMIUM_PATH by the
# design-sync scripts (package-validate / package-capture).
#
# Claude Code's macOS sandbox refuses Chromium's Mach bootstrap check-in
# ("bootstrap_check_in ... MachPortRendezvousServer: Permission denied"), which
# the browser process needs to spawn its renderer/GPU children. Running the
# whole browser in one process sidesteps that; screenshots are unaffected.
# It must be the *headless shell* build, not full Chrome: full Chrome also
# creates a profile-lock socket the sandbox refuses ("Failed to create a
# ProcessSingleton for your profile directory").
#
#   DS_CHROMIUM_PATH="$PWD/.design-sync/chromium-sandboxed.sh" node .ds-sync/package-validate.mjs ./ds-bundle
#
# Outside the sandbox this wrapper is unnecessary; plain launches work.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REV="$(node -p "JSON.parse(require('fs').readFileSync('$HERE/../.ds-sync/node_modules/playwright-core/browsers.json','utf8')).browsers.find(b=>b.name==='chromium-headless-shell').revision")"
CACHE="${PLAYWRIGHT_BROWSERS_PATH:-$HOME/Library/Caches/ms-playwright}"
BIN="$(ls "$CACHE"/chromium_headless_shell-"$REV"/chrome-headless-shell-*/chrome-headless-shell | head -1)"
exec "$BIN" --single-process --no-zygote "$@"
