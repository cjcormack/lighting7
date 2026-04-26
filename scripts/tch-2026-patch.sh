#!/usr/bin/env bash
# Seed the TCH 2026 fixture patch into a project via the REST API.
#
# Usage:
#   PROJECT_ID=9 ./scripts/tch-2026-patch.sh
#   PROJECT_ID=9 BASE_URL=http://localhost:8413 ./scripts/tch-2026-patch.sh
#
# Idempotent: a 409 Conflict on duplicate keys is treated as "already patched"
# and the row is skipped. Re-running the script is safe.
#
# Source: Manuals/TCH_2026_.pdf (ChamSys MagicVis export 2026-04-19).
# Plan: docs/plans/tch-2026-fixture-patch-plan.md (Tier 9).

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-9}"
BASE_URL="${BASE_URL:-http://localhost:8413}"
ENDPOINT="${BASE_URL}/api/rest/project/${PROJECT_ID}/patches"

created=0
skipped=0
failed=0

# Patch one fixture row.
#   $1 universe (int)
#   $2 fixtureTypeKey
#   $3 unique key (kebab-case)
#   $4 display name
#   $5 startChannel (int)
#   $6 group name (or "" for no group)
patch() {
    local universe="$1" type_key="$2" key="$3" name="$4" channel="$5" group="$6"

    local body
    if [[ -n "$group" ]]; then
        body=$(cat <<EOF
{"universe":${universe},"fixtureTypeKey":"${type_key}","key":"${key}","name":"${name}","startChannel":${channel},"groupName":"${group}"}
EOF
)
    else
        body=$(cat <<EOF
{"universe":${universe},"fixtureTypeKey":"${type_key}","key":"${key}","name":"${name}","startChannel":${channel}}
EOF
)
    fi

    local response status
    response=$(curl -sS -o /tmp/tch-patch-resp.$$ -w "%{http_code}" \
        -H "Content-Type: application/json" \
        -X POST "$ENDPOINT" \
        --data "$body" || echo "000")
    status="$response"

    case "$status" in
        201)
            printf '  + created  u%d ch%-4d  %-40s %s\n' "$universe" "$channel" "$key" "$name"
            created=$((created + 1))
            ;;
        409)
            local err
            err=$(cat /tmp/tch-patch-resp.$$ 2>/dev/null || echo "")
            # Treat as idempotent skip when the conflict is with the same row
            # we are trying to (re-)create:
            #   - "Duplicate key: <key>"
            #   - "Channel overlap with fixture '<name>' (<key>)" where <key> is ours
            if grep -qi "Duplicate key: ${key}" <<<"$err" \
                || grep -q "(${key})" <<<"$err"; then
                printf '  = exists   u%d ch%-4d  %-40s %s\n' "$universe" "$channel" "$key" "$name"
                skipped=$((skipped + 1))
            else
                printf '  ! 409      u%d ch%-4d  %-40s %s — %s\n' "$universe" "$channel" "$key" "$name" "$err" >&2
                failed=$((failed + 1))
            fi
            ;;
        *)
            local err
            err=$(cat /tmp/tch-patch-resp.$$ 2>/dev/null || echo "")
            printf '  ! HTTP %s  u%d ch%-4d  %-40s %s — %s\n' "$status" "$universe" "$channel" "$key" "$name" "$err" >&2
            failed=$((failed + 1))
            ;;
    esac

    rm -f /tmp/tch-patch-resp.$$
}

echo "Seeding TCH 2026 patch into project ${PROJECT_ID} via ${ENDPOINT}"
echo

# ---------------------------------------------------------------------------
# Universe 1 — DMX-controlled fixtures
# ---------------------------------------------------------------------------
echo "Universe 1 — DMX fixtures"

# Note: the MagicVis export lists head 57 (ADJ FogFuryJett 7ch) with no DMX
# address, so it is omitted here. Patch it via the UI if/when it is added.

#     univ  type_key                            key                 display name           ch    group
patch  1  "equinox-twin-shot-mkii"             "twin-shot-mkii-1"   "Twin Shot MKII 1"     1    "ADV1"
patch  1  "shehds-led19-rgbw-24ch"             "shehds-led19-1"     "Shehds LED19 1"       25   "LX2"
patch  1  "shehds-led19-rgbw-24ch"             "shehds-led19-2"     "Shehds LED19 2"       49   "LX2"
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-1"         "Orbit-70 1"           73   "LX2"
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-2"         "Orbit-70 2"           86   "LX2"
patch  1  "shehds-led19-rgbw-24ch"             "shehds-led19-3"     "Shehds LED19 3"       99   "LX1"
patch  1  "shehds-led19-rgbw-24ch"             "shehds-led19-4"     "Shehds LED19 4"       123  "LX1"
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-3"         "Orbit-70 3"           147  "LX1"
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-4"         "Orbit-70 4"           160  "LX1"
patch  1  "martin-mac-250-mode-4"              "mac-250-1"          "MAC 250 1"            173  "LX2"
patch  1  "martin-mac-250-mode-4"              "mac-250-2"          "MAC 250 2"            186  "LX2"
patch  1  "robe-color-spot-575-mode-2"         "robe-spot-575-1"    "ColorSpot 575 1"      202  "LX3"
patch  1  "robe-color-spot-575-mode-2"         "robe-spot-575-2"    "ColorSpot 575 2"      221  "LX3"
patch  1  "kam-liteobar-252-11ch"              "liteobar-252-1"     "Liteobar 252 1"       240  "LX3"
patch  1  "kam-liteobar-252-11ch"              "liteobar-252-2"     "Liteobar 252 2"       251  "LX3"
patch  1  "varytec-easymove-xl-60-spot-11ch"   "easymove-xl-60-1"   "Easymove XL 60 1"     262  "LX3"
patch  1  "varytec-easymove-xl-60-spot-11ch"   "easymove-xl-60-2"   "Easymove XL 60 2"     273  "LX3"
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-5"         "Orbit-70 5"           284  ""
patch  1  "gear4music-orbit-70-13ch"           "orbit-70-6"         "Orbit-70 6"           297  ""
patch  1  "gear4music-sol-party-12b-8ch"       "sol-party-12b-1"    "SOL Party 12B 1"      310  "LX3"
patch  1  "gear4music-sol-party-12b-8ch"       "sol-party-12b-2"    "SOL Party 12B 2"      318  "LX3"
patch  1  "imgstageline-wash-42led-13ch"       "wash-42led-1"       "Wash-42LED 1"         326  ""
patch  1  "imgstageline-wash-42led-13ch"       "wash-42led-2"       "Wash-42LED 2"         339  ""
patch  1  "robe-color-spot-575-mode-2"         "robe-spot-575-3"    "ColorSpot 575 3"      406  "ADV1"
patch  1  "robe-color-spot-575-mode-2"         "robe-spot-575-4"    "ColorSpot 575 4"      425  "ADV1"
patch  1  "china-2-cell-led-blinder-8ch"       "blinder-1"          "2-Cell Blinder 1"     496  "ADV1"
patch  1  "china-2-cell-led-blinder-8ch"       "blinder-2"          "2-Cell Blinder 2"     504  "ADV1"

echo
echo "Universe 1 — Single-channel dimmers"

#     univ  type_key            key             display name        ch    group
patch  1  "generic-dimmer"   "sr-face"        "SR Face 19°"        5    "ADV2"
patch  1  "generic-dimmer"   "c-face"         "C Face 19°"         6    "ADV2"
patch  1  "generic-dimmer"   "sl-face"        "SL Face 19°"        7    "ADV2"
patch  1  "generic-dimmer"   "dimmer"         "Dimmer"             8    ""
patch  1  "generic-dimmer"   "lx1-fres"       "LX1 Fres"           9    "LX1"
patch  1  "generic-dimmer"   "lx2-fres"       "LX2 Fres"           10   "LX2"
patch  1  "generic-dimmer"   "adv-cans"       "ADV Cans"           12   ""
patch  1  "generic-dimmer"   "foh-l-prof"     "FOH L Prof 19°"     19   "ADV2"
patch  1  "generic-dimmer"   "lx1-c-fres"     "LX1 C Fres"         20   "LX1"
patch  1  "generic-dimmer"   "lx2-c-fres"     "LX2 C Fres"         21   "LX2"
patch  1  "generic-dimmer"   "non-dim-adv"    "Non Dim (ADV R)"    22   ""
patch  1  "generic-dimmer"   "non-dim-lx3"    "Non Dim (LX3 Rob)"  23   ""
patch  1  "generic-dimmer"   "non-dim"        "Non Dim"            24   ""
patch  1  "generic-dimmer"   "ring-red"       "Ring Red"           199  ""
patch  1  "generic-dimmer"   "ring-green"     "Ring Green"         200  ""
patch  1  "generic-dimmer"   "ring-blue"      "Ring Blue"          201  ""
patch  1  "generic-dimmer"   "hazer"          "Hazer"              512  ""

echo
echo "Universe 2 — ETC Source 4 Revolution"

patch  2  "etc-source4-revolution-base-frame"  "source-4-rev-1"   "Source 4 Revolution 1"  1    "Pipe 1FOH"
patch  2  "etc-source4-revolution-base-frame"  "source-4-rev-2"   "Source 4 Revolution 2"  101  "Pipe 1FOH"

echo
echo "Universe 4 — House lights"

patch  4  "generic-dimmer"   "house-1"   "House 1"   1   ""
patch  4  "generic-dimmer"   "house-2"   "House 2"   2   ""
patch  4  "generic-dimmer"   "house-3"   "House 3"   3   ""

echo
echo "Universe 5 — Equinox Twin Shot MKII"

patch  5  "equinox-twin-shot-mkii"   "twin-shot-mkii-3"   "Twin Shot MKII 3"   1   "Pipe 1FOH"

echo
echo "Done. created=${created}  skipped=${skipped}  failed=${failed}"

if [[ "$failed" -gt 0 ]]; then
    exit 1
fi
