# MSI slimming plan

## Status

| Step | State | Notes |
| --- | --- | --- |
| 0 — move `hostOs` / `runtimeOsLabel` above the fat-jar block | **Done** | Mechanical; `shadowJar` needs them in scope |
| 1 — ship only two of the fork's six library dirs | **Done** | −37.2 MB staged, verified |
| 2 — keep `static/**` out of the editor jar | **Done** | −1.4 MB CI, −35.8 MB against the pre-step-3 tree |
| 3 — `copyFrontend` → `Sync` | **Done** | 0 CI; 123 MB → 5.3 MB locally |
| 4 — strip non-target-OS native payloads | **Done** | −17.9 MB staged on Windows |
| 5 — `jlink --include-locales` | **Done** | −10.5 MB, measured on the produced runtime |
| Windows MSI number | **Pending** | Needs one `workflow_dispatch` run; everything else was measured on macOS |

## Context

The Windows MSI was **312 MB**. That is a lot to ask of anyone on a venue
connection, and the desk self-updates (`docs/windows-updates.md`), so every
user re-downloads it on every release.

The question that started this was whether the JVM could be **downloaded at
install time** rather than bundled. It can't, and shouldn't — see
`FU-DIST-NO-BUNDLED-JRE` in [`followups.md`](followups.md) for the four
reasons, the load-bearing one being that
[`completed/windows-distribution-plan.md`](completed/windows-distribution-plan.md)
line 19 states the product goal as "no external dependencies — no Postgres, no
Docker, no node, **no JDK on the target machine**". A venue desk may have no
internet when it is installed, and "the lighting desk won't start" is the worst
possible moment to find that out. It would have saved 59 MB; the five steps
below save 67 MB without touching that property, and the *good* half of the same
idea — trimming the bundled runtime rather than removing it — is step 5.

### Measured inventory

Baseline is a clean CI build (no stale frontend), staged bytes:

| Item | Baseline | After | Notes |
| --- | --- | --- | --- |
| `lighting7.jar` | 136.3 | **118.4** | natives for one OS only |
| `kotlin-compiler-server.jar` | 100.3 | 100.3 | untouched — see `FU-DIST-KCS-RETIRE` |
| `runtime/` | 60.5 | **50.0** | locale-filtered |
| four unused `2.4.10-*/` lib dirs | 37.2 | **0** | JS / Wasm / Compose-Wasm |
| `2.4.10/` | 17.9 | **16.5** | slimmer editor jar |
| `2.4.10-compiler-plugins/` | 0.5 | 0.5 | needed |
| `launcher.jar` | 1.8 | 1.8 | |
| **staged total** | **354.6 MB** | **287.7 MB** | **−67.0 MB** |

The observed 312 MB MSI against a 354.6 MB staged tree gives a staged→MSI ratio
of **0.879** (the CAB can't re-compress already-deflated jars, but `runtime/` and
the many small files in it do compress). At that ratio the new tree projects to
**~253 MB**. Treat that as a projection: confirm it with one non-tag
`workflow_dispatch` (`create_release=false`), which already prints the artifact
name and SHA-256.

## What changed

### Step 1 — only two of the six library directories ship

`compilerServerLibDirNames` (`build.gradle.kts`, next to
`compilerServerKotlinVersion`) is a two-name allowlist: `<version>` and
`<version>-compiler-plugins`. It is applied in **both** `assembleCompilerServer`
(stop staging them) and `stageJpackageInput` (guarantee pre-existing ones don't
ride along), and `assembleCompilerServer` now prunes non-allowlisted
`<version>*` dirs from `build/distributions`.

Safe because the desk only ever compiles for the JVM —
`lighting-react/src/components/scripts/ScriptEditor.tsx` mounts kotlin-playground
with `mode="kotlin"` and no target-platform prop — and because the fork tolerates
the other four being absent *by design*: its
`compiler/components/KotlinEnvironment.kt` reads five of six as
`listFiles()?.toList() ?: emptyList()` and escalates only `jvm`.

The old `require(libDirs.isNotEmpty())` was replaced with a per-name check. The
old guard passed on *any* matching directory, so a fork that had emitted only
`2.4.10-js/` satisfied it and produced an installer whose editor had no JVM
classpath — visible as "completion returns only stdlib", never as a build error.

All six `--libraries.folder.*` flags are still passed (launcher and the dev
`Exec`). Dropping the four would fall back to the jar's *relative*
`application.properties` values, which resolve against the child's working
directory — exactly what passing absolute paths avoids.

### Step 2 — the editor jar loses the frontend

`compilerServerLightingJar` is a new `Jar` task writing to
`build/compiler-server-libs/`, built from `sourceSets["main"].output` minus
`static/**`. The fork puts this jar on the *script* classpath so the editor can
resolve this project's API; it has no use for the React bundle, which was
shipping inside the MSI a second time.

A dedicated task because `exclude("static/**")` on the old `Copy` would have been
a no-op (its source is a single jar *file*; Ant patterns don't reach inside an
archive), and excluding it from `jar` itself would leave a Lighting7 jar that
can't serve its own UI.

`static/**` only, not classes-only: `routes/kotlinCompilerServer.kt` proxies
`{action}` as a wildcard, so `/compiler/run` is reachable and the fork can
*execute* against this jar. See `FU-DIST-EDITOR-JAR-CLASSES-ONLY`.

### Step 3 — `copyFrontend` is a `Sync`

Vite emits content-hashed filenames, so the old `Copy` never removed the previous
build's chunks. `src/main/resources/static` was found at **123 MB / 44 files**
(~22 stale `index-*.js`), which inflated the thin jar to 45 MB and every locally
built installer with it. CI never saw it (gitignored, `clean` deletes it), so this
buys nothing in the MSI — but it is what makes local size measurement mean
anything, which is why it landed first.

`Sync` owns the directory and deletes anything it didn't put there. Acceptable
only because the directory is entirely machine-generated. The `onlyIf` is kept
deliberately: a failed Vite run skips the pruning too, leaving the last good
bundle in place.

### Step 4 — native payloads follow the target OS

`-PnativePayloadOs` (`windows` | `mac` | `linux` | `all`, defaulting to the host)
selects which OS's native binaries `shadowJar` keeps, driven by the
`nativePayloads` table of (Ant pattern, entry prefix, owning OS).

A **target** property rather than plain host-keying, because it makes the Windows
jar reproducible from a Mac: `-PnativePayloadOs=windows` produces exactly what CI
stages, so the installer's size is measurable without a Windows host. `all`
restores the old portable jar and is byte-for-byte identical to the pre-change
output — that equality is the regression control.

Five dependencies ship natives for every platform they support:

| Artifact | Payload | Non-Windows weight |
| --- | --- | --- |
| `sqlite-jdbc` | `org/sqlite/native/<OS>/` | Linux 7.9 + Musl 3.1 + FreeBSD 3.1 + Mac 2.3 MB |
| `libremidi-panama` | `jne/<os>/` | Linux 30.6 + Mac 1.4 MB |
| `jna` | `com/sun/jna/<platform>-<arch>/` | ~4.4 MB across 22 platforms |
| `alsa-javacpp` | `linux-x86_64/` | 1.6 MB |
| `coremidi4j` | `libCoreMidi4J.dylib` | 0.2 MB |

Three things the table gets right that a `**/*.so` sweep would not:

- **Classes always ship.** Only binary payloads are listed. `coremidi4j` is
  referenced statically by `midi/MidiDeviceRegistry.kt`, and jne/javacpp choose
  what to load from `os.name` at runtime.
- **`com/sun/jna/win32-x86-64/` is a payload; `com/sun/jna/win32/` is a Java
  package of 12 `.class` files.** The trailing hyphens in the JNA prefixes are
  what draws that line.
- **`jne/windows/` is kept** even though `midi/KtmidiAccessSource.kt` routes
  Windows to `JvmMidiAccess`, so libremidi's DLL is provably never loaded there.
  It is 0.35 MB, and dropping it would plant a trap that springs only when
  someone fixes the LLP64 ABI bug and flips that branch back.

An Ant exclude that matches nothing fails **silently, in both directions** — a
renamed payload directory upstream means either tens of MB quietly return, or the
one native the target needs quietly leaves. So a `doLast` verifier opens the
produced jar and asserts every owned prefix is present and every other absent.
Both directions were confirmed to fail the build. `registerJpackageTask` also
refuses to package a jar whose `nativePayloadOs` doesn't match the host, because
that installs cleanly and then dies at its first database connection.

**`./gradlew build` on a Mac now produces a macOS-only `lighting7.jar`.** Copy it
to Linux and SQLite fails with `No native library found for os.name=Linux`. Use
`-PnativePayloadOs=all` for a portable one. Tests and `run` are unaffected — both
resolve against `runtimeClasspath`, not the fat jar.

### Step 5 — `jlink --include-locales=en-GB,en-US`

10.5 MB of the 60.5 MB runtime was `jdk.localedata`: CLDR data for ~800 locales, of
which the desk uses two. Measured on the produced runtime: **60.5 → 50.0 MB**.

A locale **filter**, not a module removal — that is the obvious and wrong version
of this change. `FormatData_en_GB` lives in `jdk.localedata`, not `java.base`, so
dropping the module would silently switch every server-side `en_GB` date, number
and currency to US forms. Verified identical on the real image: `18/08/2026`,
`Tuesday, 18 August 2026`, `£1,234.50`. Note the failure mode for a *missing*
locale is wrong formatting, not an exception — nothing throws to tell you.

`java.se` was **not** replaced with an explicit module list. Measured at **1 MB**
(51 → 50 MB): everything the surgery can remove is `java.se`'s small tail
(`java.xml.crypto` 0.66, `java.security.jgss` 0.55, `java.rmi` 0.22,
`java.sql.rowset` 0.20, `java.management.rmi` 0.08 MB), all-or-nothing because
`java.se` `requires transitive` each of them. A module this list gets wrong is a
`NoClassDefFoundError` at first use of one feature, potentially mid-show, and the
easiest ones to miss are precisely those jdeps can't see (Spring Boot and logback
reach `java.naming` / `java.management` reflectively). See
`FU-DIST-JLINK-MODULES`.

## Verification

Everything except the final MSI byte count was verified on macOS. `packageMac`
shares `shadowJar`, both library dirs and the editor jar, so steps 1–4 are
functionally checkable here.

```bash
# The Windows staged number, from a Mac, exactly as CI will stage it.
./gradlew stageJpackageInput -PnativePayloadOs=windows && du -sk build/jpackage-input

# Regression control: must be byte-identical to a pre-change jar.
./gradlew shadowJar -PnativePayloadOs=all

# Per-OS payload contents.
unzip -l build/libs/lighting7.jar | grep -E 'org/sqlite/native|jne/|com/sun/jna/[a-z]+-|linux-x86_64|dylib'
```

What was checked, and what still needs a rig:

- **Done**: `-PnativePayloadOs=all` byte-identical to baseline; per-OS payload
  contents correct with the `win32` *package* and the MIDI SPI service file intact
  in both; the payload verifier fails on both a stale exclude and a vanished
  payload; `failOnDuplicateEntries` still bites after the excludes (removing
  `include("jnijavacpp.cpp")` still fails the build, so the guard has not gone
  decorative); `stageJpackageInput` excludes planted decoy `-js`/`-compose-wasm`
  dirs; the editor jar has no `static/` but keeps
  `META-INF/uk.me.cormack_Lighting7.kotlin_module`, `application.conf`,
  `logback.xml` and `fx/`; `copyFrontend` prunes 123 MB → 5.3 MB; `en-GB`
  formatting identical on the locale-filtered runtime.
- **Still needed** — none of it automatable:
  1. `./gradlew runCompilerServer`, then the script editor: type a Lighting7
     receiver and `.`, confirm **Lighting7** completions and highlighting. This is
     the gate for steps 1 and 2 and the one thing that proves the slimmer editor
     jar still resolves symbols. Requires the fork checkout and a `bootJar` run.
  2. One `workflow_dispatch` for the real MSI size.
  3. The four native-payload features on an installed package, per
     `FU-MANUAL-DIST-INSTALL`: SQLite persistence, MIDI enumeration, coremidi4j
     `loaded=true` on Mac, and a keychain PAT store (JNA — the least obvious of the
     four, and the one most likely to be missed).

## Rejected and deferred

Recorded as follow-ups so they aren't re-proposed or re-derived:
`FU-DIST-NO-BUNDLED-JRE` (**Rejected**), `FU-DIST-KCS-RETIRE` (~122 MB, the
largest remaining item and the reason this can't get much below 240 MB),
`FU-DIST-JLINK-MODULES` (1 MB), `FU-DIST-KCS-LIB-PRUNE` (~2.9 MB),
`FU-DIST-NATIVE-ARCH` (~2.9 MB), `FU-DIST-EDITOR-JAR-CLASSES-ONLY`,
`FU-DIST-KCS-SKIP-KLIB-DOWNLOAD` (0 shipped bytes, CI time only).
