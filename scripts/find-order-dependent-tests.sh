#!/usr/bin/env bash
# Find tests that pass in isolation but fail as a suite (or the reverse).
#
# The suite runs in one JVM, sequentially, and shares more than it looks like it does:
# Exposed's process-wide `TransactionManager` registry, the compiled-script cache under
# `build/test-data`, `Dispatchers.Default`, and a handful of tests that assert real elapsed
# time. Any of those can make a test's result depend on what ran before it. See
# docs/testing-engineering.md §"Order dependence".
#
# This runs the full suite once, then every test class on its own, and reports the classes whose
# verdict differs between the two. It is a debugging tool — it runs the suite ~180 times over,
# so expect it to take a long while. Run it when something smells, not routinely.
#
# Usage:
#   scripts/find-order-dependent-tests.sh            # all classes
#   scripts/find-order-dependent-tests.sh 'routes.*' # only classes matching a regex
#
# Exits non-zero if any class disagrees between the two modes.

set -uo pipefail
cd "$(dirname "$0")/.."

FILTER="${1:-.}"
WORK="build/order-dependence"
mkdir -p "$WORK"

# Agent sessions must not land on the operator's daemon; see CLAUDE.md.
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx2g}"

# Read the pass/fail verdict for every class out of the JUnit XML Gradle just wrote.
verdicts() {
  python3 - "$1" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
out = {}
for f in glob.glob('build/test-results/test/*.xml'):
    try:
        r = ET.parse(f).getroot()
    except Exception:
        continue
    name = r.get('name')
    bad = int(r.get('failures') or 0) + int(r.get('errors') or 0)
    # A class whose tests were all skipped has no verdict to compare.
    if int(r.get('tests') or 0) == int(r.get('skipped') or 0):
        continue
    out[name] = 'FAIL' if bad else 'PASS'
with open(sys.argv[1], 'w') as fh:
    for k in sorted(out):
        fh.write(f"{k} {out[k]}\n")
PY
}

echo "==> Full-suite run"
./gradlew :test --rerun >"$WORK/suite.log" 2>&1
verdicts "$WORK/suite.txt"
echo "    $(wc -l <"$WORK/suite.txt" | tr -d ' ') classes with a verdict"

CLASSES=$(cut -d' ' -f1 "$WORK/suite.txt" | grep -E "$FILTER" || true)
if [ -z "$CLASSES" ]; then
  echo "No classes matched /$FILTER/" >&2
  exit 2
fi

: >"$WORK/alone.txt"
TOTAL=$(printf '%s\n' "$CLASSES" | wc -l | tr -d ' ')
N=0
for cls in $CLASSES; do
  N=$((N + 1))
  printf '==> Alone: %d/%d %s\n' "$N" "$TOTAL" "${cls##*.}"
  if ./gradlew :test --tests "$cls" --rerun >"$WORK/alone-last.log" 2>&1; then
    echo "$cls PASS" >>"$WORK/alone.txt"
  else
    echo "$cls FAIL" >>"$WORK/alone.txt"
    cp "$WORK/alone-last.log" "$WORK/fail-${cls##*.}.log"
  fi
done

echo "==> Differences"
# Plain files rather than process substitution: /dev/fd is not always readable (it is denied
# under the agent sandbox), and this comparison is not worth a shell-feature dependency.
sort "$WORK/alone.txt" -o "$WORK/alone.txt"
python3 - "$WORK/suite.txt" "$WORK/alone.txt" <<'PY'
import sys

def load(path):
    out = {}
    with open(path) as fh:
        for line in fh:
            name, _, verdict = line.strip().rpartition(' ')
            if name:
                out[name] = verdict
    return out

suite, alone = load(sys.argv[1]), load(sys.argv[2])
differing = [c for c in alone if c in suite and suite[c] != alone[c]]
for c in sorted(differing):
    print(f"    {c:<70} suite={suite[c]} alone={alone[c]}")
if not differing:
    print("    None — every class agrees with itself in isolation.")
sys.exit(1 if differing else 0)
PY
status=$?
if [ "$status" -ne 0 ]; then
  echo
  echo "    Logs for isolated failures: $WORK/fail-*.log"
fi
exit "$status"
