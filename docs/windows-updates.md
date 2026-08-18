# Windows updates

How a desk finds out about a new release, and how it installs one. Windows only — the version is
reported everywhere, but the apply path is MSI-specific.

## The bug this started from

`packageWindows` passed no `--win-upgrade-uuid`. jpackage's `WinMsiBundler` defaults that to a
fresh `UUID.randomUUID()` **per build**, so every MSI ever produced declared a different *product
line*. Windows Installer therefore had no reason to believe two of them were related: it did not
fail, it installed the new one **side by side** with the old one, into the same directory, leaving
two entries in Add/Remove Programs where uninstalling either broke the other. The build stayed
green throughout, which is why this survived so long.

`windowsUpgradeUuid` in `build.gradle.kts` pins it. **That constant must never change** — changing
it is equivalent to shipping a new product under the same name, and every existing user would end
up with two overlapping installs. It lives as a literal in the build script rather than in
`gradle.properties` precisely so it cannot be overridden by a stray `-P` flag or a line in
`~/.gradle/gradle.properties`.

Because the failure is invisible at build time, `verifyWindowsInstaller` opens the `.msi`
`packageWindows` just produced, reads its `Property` table, and asserts `UpgradeCode` is the pinned
UUID and `ProductVersion` is the version we asked for. Don't remove it on the grounds that the flag
is obviously being passed; the whole point is that "obviously passed" and "actually in the
installer" are different claims.

It reads the **installer**, not the WiX sources. The first version of this check ran jpackage with
`--temp` and grepped the generated `.wxs`/`.wxi` for the UUID, and failed on the first Windows CI
run — jpackage's `main.wxs` template carries `UpgradeCode="$(var.JpProductUpgradeCode)"` and the
value is supplied as a WiX preprocessor variable on the toolchain command line, so the literal
never appears in any generated source on any JDK. The Property table is also the more honest thing
to assert against: it is what Windows Installer itself reads.

Reading it needs the Windows Installer COM automation interface, so the actual query lives in
`gradle/read-msi-properties.ps1` and Gradle shells out to `powershell.exe` for it.

### Versions

Windows Installer compares **only the first three fields** of ProductVersion, and bounds them
(major 1–255, minor 0–255, patch 0–65535). A fourth field is accepted and then *ignored*, so
`1.2.3.4` and `1.2.3.3` compare **equal** and will never upgrade each other.
`requireMsiCompatibleVersion` rejects that shape, plus suffixes and leading zeros, at Gradle
configuration time; the workflow rejects the same shapes at the tag before spending 40 minutes on
a build.

Prerelease tags (`v1.2.3-rc1`) are rejected on purpose. `-rc1` is not a legal ProductVersion, and
every scheme that squeezes candidates into the three usable numeric fields makes the final release
compare *lower* than its own candidates. Ship release candidates as workflow artifacts.

**`1.0.0` is reserved for unversioned local builds.** It is the `?: "1.0.0"` default, so a real
`1.0.0` release would be indistinguishable from a hand-built MSI. The first release is `v1.1.0`.

### Pre-fix installs

Anything installed from an MSI built before the UpgradeCode was pinned carries a random,
unrecorded UpgradeCode and **cannot** be adopted — the list of old codes doesn't exist and never
will. Since no release had ever been published, the affected population is hand-built MSIs only.
The release notes tell those users to uninstall first.

## Knowing what version we are

`:generateBuildInfo` writes `lighting7-build-info.properties` (version, channel, commit sha,
committer timestamp) and **one task stages it into both jars** — `lighting7.jar` and
`launcher.jar`. They are separate jars in one installer and must agree byte-for-byte; a mismatch
would show up as the app reporting one version while the updater compares another, i.e. an update
that reinstalls itself forever.

Nothing here is a wall-clock value. `commitTimestamp` is the *committer date*, constant for a
given commit, so rebuilding a tag reproduces the same file and neither Gradle's up-to-date checks
nor the CI build cache is defeated.

**"Packaged vs dev" is deliberately not in that file.** `stageJpackageInput` packages the very same
`lighting7.jar` that `./gradlew run` executes, so packaged-ness is not knowable at build time. The
launcher detects it at runtime (`jpackage.app-path`, or `.jpackage.xml` beside the jars) and
forwards it to the backend in the environment alongside `LIGHTING7_DATA_DIR`.

Eligibility is **both** gates, always: `channel == release` **and** the install is packaged **and**
the host is Windows. Without the channel gate a locally built `packageWindows` MSI is a genuinely
packaged install and would immediately offer to msiexec over itself.

| Build | version | channel | install kind | offers updates? |
|---|---|---|---|---|
| `./gradlew run` | 1.0.0 | dev | dev | no |
| `./gradlew packageWindows` | 1.0.0 | dev | packaged | **no** — channel gate |
| CI, tag `v1.2.0` | 1.2.0 | release | packaged | yes |

## Checking

The **backend** checks, not the launcher — decisively, because `java.base` has no JSON parser and
hand-rolling one to read GitHub's release payload inside the zero-dependency launcher module is
exactly the cost that module exists to avoid. The launcher's job is narrowed to the one thing only
it can do: outlive the backend and run `msiexec`.

`GET /repos/<repo>/releases/latest` is the feed, unauthenticated, with an `If-None-Match` against a
stored ETag. The unauthenticated limit is 60 requests/hour/IP, so: one automatic check 60 s after
`markReady()` (never during boot — the desk is compiling FX scripts and loading its patch), then
every 6 h; a manual check has a 30 s floor and returns the cached answer with `throttled: true`
rather than a 429, because a throttle is not an error and must not fire the frontend's error-toast
middleware. The result is cached to `<dataDir>/updates/last-check.json` so a fresh boot answers
with no network at all and a crash loop cannot hammer the API.

Version comparison (`SemVer.kt` / `UpdateComparison.kt`) is pure and **fails closed**: anything
unparseable on either side is `UNKNOWN`, never `UPDATE_AVAILABLE`. Tags are typed by hand, and the
cost of being wrong is restarting a lighting desk to install something arbitrary.

Two switches, deliberately distinct: `update.enabled` in `local.conf` is a hard kill-switch for a
locked-down venue install; `installs.update_check_enabled` is the per-machine preference the UI
toggles.

## Downloading

Checksum first (it is tiny, and downloading hundreds of megabytes only to find there is nothing to
verify against would be the wrong order), then a free-space precheck against **2×** the asset size
— Windows Installer also caches the package under `C:\Windows\Installer` — then a single streaming
pass that writes to `<name>.part` and feeds a running `MessageDigest` as the bytes go by. Hashing
afterwards would mean a second full read of a several-hundred-megabyte file on a machine that may
be running a show.

The file is renamed to its real name **only** on a digest match. That is what makes the core
invariant true by construction rather than by convention:

> A non-`.part` file in `updates/staged/` is a verified file.

## Applying

The backend cannot install the update: it is a *child* of the launcher, and every file `msiexec`
must replace — `runtime/`, all three jars, `lighting7.exe` — is held open while it is alive.

The handshake is a **marker file** the launcher's existing 500 ms poll loop also watches. A
loopback HTTP server on the launcher would need `jdk.httpserver` added to the jlink module list, a
port to discover, and a shared secret so no other local process could stop the desk — strictly more
surface for no benefit over a loop that already runs. Having the backend spawn `msiexec` itself is
unworkable: the launcher and compiler-server child are still holding files, with no ordering
guarantee against the installer.

The marker is **`.properties`, not JSON**, and that is the choice that keeps the launcher small:
`java.util.Properties` is in `java.base`, `LauncherMain` already reads `compiler-server.properties`
that way, and `store`/`load` handle Windows backslash escaping correctly where a hand-rolled parser
gets it wrong on roughly one path in a hundred. It is written to `.tmp` and `ATOMIC_MOVE`d, so the
poll loop can never observe a partial write.

Sequence:

1. Backend writes `updates/update-apply.properties`.
2. Launcher notices, **consumes it first** (renames it aside) — so a crash between here and
   `msiexec` cannot re-trigger the same apply on the next boot — then re-validates *everything*:
   schema, Windows, not stale, path inside `updates/staged`, size, SHA-256. Any failure logs loudly
   and leaves the desk running the version it already has.
3. Children stopped and **confirmed dead** (`Wait-Process` covers the launcher's PID, not its
   children's).
4. A detached PowerShell wrapper: `Wait-Process -Id <launcherPid>` — gating on the JVM actually
   exiting, not a sleep — then `msiexec /i <msi> /qb /norestart` under `-Verb RunAs`.
5. The wrapper writes `apply-result.properties` and relaunches `lighting7.exe`
   **unconditionally**. A desk running the old version is a nuisance; a desk that vanishes
   mid-show is not.

`/qb` rather than `/qn` (a silent per-machine install from an unelevated context fails 1925, and
even elevated it shows nothing during a multi-hundred-megabyte install) and not `/passive` (whose
cancel button can leave a half-installed app). `/norestart` so the installer can never reboot a
lighting desk; exit 3010 means "reboot when convenient" and is treated as success.

**Quitting from the tray cancels a pending update**, because `clearStale` drops the marker on the
next boot. That is intended.

On the next boot the backend reads `apply-result.properties`. The `NO_VERSION_CHANGE` branch —
installer reported success, version unchanged — is the backstop against the worst failure this
feature can have: without it, a version-identity mismatch would put an "update available" banner up
on every boot forever. A failed apply **keeps** the staged MSI (a retry shouldn't re-download
hundreds of megabytes) and records the version so the banner stops.

## Security, stated plainly

The marker is an **unauthenticated local control channel**. Any process running as the user can
stage an MSI and a matching marker and get it installed elevated, behind a UAC prompt that says
"lighting7". The SHA-256 does not help, because that attacker controls both sides of the
comparison.

This is no worse than replacing `local.conf` or the Start-menu shortcut, which the same attacker
can already do — the data directory is user-writable by design. **The digest is an integrity check
against corruption and truncation, not a security control.** Don't let a future reader mistake it
for one.

The MSI is also **not code-signed**, so SmartScreen warns on a manually downloaded installer.
(A file fetched by the JVM does not carry the Mark-of-the-Web that a browser download does, so the
in-app path may not prompt at all — believed, not verified.) Authenticode signing remains v2 work.

## Releasing

Dispatch the **Windows build** workflow against a `v*` tag with `create_release: true`. It
validates the tag shape up front, builds, stages `lighting7-<version>-windows-x64.msi` plus a
`.sha256`, and publishes a release that is **not** a draft — `GET /releases/latest` excludes drafts
and prereleases, so a drafted release is invisible to every installed desk.
