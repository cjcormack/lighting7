# Manual hardware validation

Operational checks pending an operator session on the rig (most on the X-Touch Compact). No
engineering scope — each is 10–20 minutes end-to-end. Engineering follow-ups live in
[`followups.md`](followups.md); if a check here fails, promote the finding to a `FU-` item there
rather than fixing inline.

| Item | What it proves | Origin |
|---|---|---|
| [`FU-MANUAL-EDITOR-INPROCESS`](#fu-manual-editor-inprocess) | in-process editor compiles don't stutter live output | KCS retire, 2026-08-18 |
| [`FU-MANUAL-PALETTE-TOURING`](#fu-manual-palette-touring) | a palette edit moves a live look | Programmer S4, 2026-08-14 |
| [`FU-MANUAL-SPEED-MASTERS-RIG`](#fu-manual-speed-masters-rig) | two masters drive one show — **restart required first** | Programmer S5, 2026-08-14 |
| [`FU-MANUAL-AUTH-QR-SCAN`](#fu-manual-auth-qr-scan) | both QR flows resolve on a real phone | Multi-user-auth S3, 2026-08-17 |
| [`FU-MANUAL-DIST-INSTALL`](#fu-manual-dist-install) | the installers work on clean Mac + Windows | Windows-dist P1–3, 2026-04-28 |
| [`FU-MANUAL-UPDATE-APPLY`](#fu-manual-update-apply) | the in-app update upgrades in place — *blocked on the above* | Windows updates, 2026-08-17 |
| [`FU-MANUAL-SCALER-PROJECT-SWITCH`](#fu-manual-scaler-project-switch) | scaler state survives a project switch | Control-surface P9 |
| [`FU-MANUAL-SUSPEND-PATH`](#fu-manual-suspend-path) | the suspend path doesn't stutter under load | Control-surface P8 |
| [`FU-MANUAL-CUEEDIT-HARDWARE`](#fu-manual-cueedit-hardware) | cueEdit works from a bound fader | Control-surface P6 |
| [`FU-MANUAL-SURFACES-FLOW`](#fu-manual-surfaces-flow) | the `/surfaces` MIDI-learn flow works | Control-surface P5 |
| [`FU-MANUAL-DEAD-ASSIGNMENTS`](#fu-manual-dead-assignments) | dead markers render after a fixture rename | Cue-authoring P6 |

---

## `FU-MANUAL-EDITOR-INPROCESS`

**Script editor compiling while the rig is live** · from `FU-DIST-KCS-RETIRE`, 2026-08-18

The one property the retired compiler server actually provided beyond `/highlight` was **process
isolation**. Editor highlighting (every pause in typing) and completion (most keystrokes) used to
run in a separate JVM on port 8321; they now run in the JVM that drives DMX.

`ScriptEditorService` bounds this — one below-normal-priority daemon thread, superseded requests
dropped rather than queued, a 10 s response cap — but that caps *concurrency*, not cost. A compile
is 0.2–0.6 s of real work now competing for heap and CPU with the output loop, and no unit test can
speak to that.

**Test**: with a cue stack running and effects live on real fixtures, open the script editor and
type continuously for a minute in a GENERAL script — enough for many highlight and completion
round-trips. Watch for output stutter, dropped frames, or audible hesitation in moving-head motion.
Repeat with an FX_CALC script, whose template differs.

**If it stutters**, levers in order of bluntness: raise the client-side debounce, drop autocomplete
on lower-powered desks, or gate the editor routes on the rig being idle. A second process is the
last resort — it's what this change removed.

## `FU-MANUAL-PALETTE-TOURING`

**A palette edit moves a live look** · Programmer redesign Session 4, 2026-08-14

Session 4 was verified on a live rig for record, include, apply, cue-side badges and health, and
Make Hard at both levels. The behaviour **not** verified on stage is the point of the feature:
edit a palette while a cue referencing it is live and watch the output move without re-firing.

Republish-on-palette-edit is unit-covered but the path is long — `PaletteRegistry` invalidation →
version-counter re-check → `replaceCueAssignments` (which preserves `cueFadeWeights`, unlike
`setCueAssignments`) → the controller write. A stale cache or dropped republish looks exactly like
"nothing happened", indistinguishable from operator error unless someone is watching lamps.

**Test**: record a COLOUR palette from two heads → author a cue referencing it → GO → edit the
palette (change one head's colour). The live heads move, other cue content doesn't, no re-fire
needed. Repeat with a POSITION palette on a moving head, where per-fixture resolution matters (the
same `ref:` must resolve to a different pan/tilt per head). Then mid-crossfade: edit a palette
referenced by the *incoming* cue while the fade runs and confirm it continues rather than snapping
— that's the `cueFadeWeights` preservation.

## `FU-MANUAL-SPEED-MASTERS-RIG`

**Two masters driving one show** · **backend restart required first (new classes)** · Programmer
redesign Session 5, 2026-08-14

Session 5 shipped with a restart outstanding, so **no part of the speed-master bank has run against
the rig**. `SpeedMasterBankTest` pins the tick-interval arithmetic (the old `toLong()` truncation
ran 120 BPM at ~125, which with two masters is *relative* drift at 120:60 ⇒ 2.05:1) and
`SocketMessageWireFormatTest` pins master-1 wire compatibility, but the single-engine-pass
composition — one `ControllerTransaction` per frame however many masters ticked — has only ever
been exercised by tests.

**Coordinate the restart with the user first; it may be driving a live rig.**

**Test**: restart → confirm the default bank seeds and the existing BPM tile still reads and taps
master 1 (the wire-compat promise, from a client that never learned about masters). Then put a
position wave on master 2 at half master 1's BPM and a dimmer chase on master 1, both on the same
fixtures → confirm a visibly 2:1 ratio held over several minutes (drift is what the deadline timer
fixes and only shows up over time). Tap master 2 → only its effect changes rate. Check the legacy
surfaces still land on master 1: script `setBpm`, REST `/fx/clock/*`, WS `setFxBpm`.

While there, check the per-master beat dots — `BeatIndicator` pulses from the keyed
`speedMasters.beat` stream, so the dot beside a master-2 effect should track master 2. Watch the
**master 1** dot in particular: `beatSync` used to (accidentally) arrive every beat and now
genuinely arrives every 16, so the client's local interpolation is load-bearing for the first time.

## `FU-MANUAL-AUTH-QR-SCAN`

**The two QR flows on an actual phone** · Multi-user-auth Session 3, 2026-08-17

Both flows are covered end to end by `UsersRoutesTest`, `PasswordResetRoutesTest`,
`DeviceLoginRoutesTest`, `ResetPasswordPage.test.tsx` and `DeviceLoginPage.test.tsx`. The one thing
no test can prove is the **two-device path**: that the URL behind the QR is an address a phone on
the same Wi-Fi can actually reach. `auth/ResetUrls.kt` builds it from the request's own `Host`
header — correct by construction when the admin browsed by mDNS name or LAN IP, falling back to the
mDNS name plus site-local IPv4s when that host is loopback — but a QR that resolves to the phone
itself is the one failure this flow can't recover from, because the person scanning it is by
definition already locked out.

**Still outstanding as of `631a94f`.** Everything below *except the actual camera scan* was walked
against a running desk on 2026-08-17. Two flows share `buildLanUrls`, so a failure in one implicates
the other.

Needs a backend on `631a94f` or later — confirm `GET /api/rest/auth/device-logins` answers rather
than 404.

**Password-reset QR:**

1. As admin, create a throwaway operator. Sign in as them in a private window; confirm the Users
   tab and sync nav entries are absent while lighting control works.
2. Their detail sheet → **Reset with a QR code…** → scan with a phone on the same Wi-Fi. The phone
   shows *that operator's* display name, not a login form and not a connection error.
3. Set a new password on the phone → the admin's sheet flips to "used" within ~2 s (2 s poll), and
   the operator's private window drops to the login screen on its next click, socket closing 4401.
4. Sign in on the desk with the phone-set password.
5. With the operator's socket open, disable them from the detail sheet → the socket closes
   immediately (`AuthService.revocations`, the half that exists because a REST-only check would
   wait for their next request).
6. Repeat step 2 while browsing the desk as `localhost` — the loopback fallback, where the QR must
   carry the mDNS/LAN address instead. Check the alternates disclosure lists something reachable.
7. Delete the throwaway operator.

**Device-login QR** — shares `buildLanUrls` but hands out a *session*, so the negative cases matter
more than the happy one:

8. User menu → **Sign in on a phone…** → scan. It should name your account and wait for a tap —
   confirm nothing is signed in *before* you tap, since a scanner that prefetches must not burn the
   code. Tap; the phone lands in the app and the desk's sheet flips to success naming the device.
9. The devices list in the user menu says that session came in by QR.
10. Mint another and **close the sheet** — the phone must then be refused (`CANCELLED`). Mint
    another and leave it two minutes untouched — refused as `EXPIRED`.
11. Mint one and press **"Sign out everywhere else"** before scanning: refused. Repeat with plain
    **Log out**: also refused. These two are the interlocks review found missing.
12. Off-LAN if you can arrange it (cellular, or a port-forward): the exchange must answer 404. That
    check reads the socket peer, so a VPN or flat venue network is how it gets weaker.

Step 2 is the only irreplaceable one. If the phone can't reach the URL, capture what it *was* (the
sheet shows it as selectable text) before changing anything — that string is the whole diagnosis.

## `FU-MANUAL-DIST-INSTALL`

**End-to-end installer validation on Mac + Windows** · Windows-distribution Phases 1–3, 2026-04-28

All three phases are build-side green — the backend boots from `lighting7.jar` on the trimmed JRE,
`packageMac` produces a `.pkg` with the right layout, the launcher's `ensureDefaultConfig` writes
`local.conf` on first launch. Never exercised end-to-end:

**Mac** — install the `.pkg` on a clean machine (or wipe `/Applications/lighting7.app` +
`~/Library/Application Support/lighting7`). Double-click → tray icon in the menu bar, browser opens
to `localhost:8413`, an iPad on the same Wi-Fi reaches `http://lighting7-<hostname>.local:8413/`,
Quit from the tray leaves no `java` processes (`pgrep -f lighting7`).

**Windows** — `gradlew.bat packageWindows` on a Windows host, install the `.msi` on a clean VM with
no JDK. Same checks plus: `%APPDATA%\lighting7\` writable for the launcher's first-run `local.conf`;
mDNS resolves from the iPad (Bonjour ships with iTunes / Apple Software Update, else JmDNS is the
responder); Defender doesn't quarantine `lighting7.exe`.

**Smoke checklist** in either install: BPM tap, fixture patch CRUD, run a cue, edit a script in the
embedded editor, iPad WebSocket reconnects after a brief Wi-Fi drop.

**Native payloads** (the MSI ships binaries for the target OS only — see
[`msi-slimming-plan.md`](msi-slimming-plan.md)). One feature per stripped artifact, because each
fails independently and none is covered by a test:

- **sqlite-jdbc** — the app boots and fixture patch CRUD survives a restart. A missing payload is
  `No native library found for os.name=…` at the first connection.
- **libremidi / javax.sound.midi** — a MIDI surface enumerates under `/surfaces`. Windows
  deliberately uses `JvmMidiAccess`, so this exercises different code per OS.
- **coremidi4j** (Mac only) — the `midi-enum:` debug line from `midi/MidiDeviceRegistry.kt` reports
  `coremidi4j=loaded=true`.
- **JNA** — store a GitHub PAT and confirm it survives a restart (`java-keyring` → JNA → the
  platform keychain). **Easiest of the four to forget**, and the only one arriving transitively.

**Editor completion** — type a Lighting7 receiver and `.` in the script editor and confirm
**project** completions, not just stdlib.

**Size** — record the MSI byte count. Baseline: 312 MB before the slimming pass, ~253 MB projected
after, ~130 MB projected after the compiler-server retirement.

10–20 minutes per OS.

## `FU-MANUAL-UPDATE-APPLY`

**In-app update, end to end on Windows** · Windows in-app updates, 2026-08-17 ·
**Blocked on `FU-MANUAL-DIST-INSTALL`** — no point testing an upgrade from a build nobody has
confirmed installs

Everything from `POST /update/apply` onward is unautomatable: the marker protocol is round-tripped
in one JVM by `UpdateMarkerRoundTripTest` and the PowerShell command line is pinned by
`WindowsUpdateApplyTest`, but no test can observe a real launcher exiting, a UAC prompt, or
`msiexec` replacing files in `C:\Program Files`.

**Upgrade mechanics** — this is what proves the UpgradeCode fix worked:

1. Install `v1.1.0` on a clean VM. Note the install directory and **whether UAC prompted** — that
   answers per-machine vs per-user, a one-way door that must be settled before the first release.
2. Add/Remove Programs shows **one** `lighting7`.
3. Install `v1.1.1` by double-click → still **one** entry, version bumped, one Start-menu shortcut,
   `%APPDATA%\lighting7\` untouched (DB, `local.conf`, logs survive).
4. Repeat with `msiexec /i <msi> /qb /norestart /l*v %TEMP%\l7.log` — the exact command the apply
   flow issues. Confirm the behaviour **while the app is running**, and grep the log for
   `RemoveExistingProducts`.
5. Install `v1.1.0` over `v1.1.1` → expect a downgrade refusal, not a silent side-by-side.
6. Confirm a user-chosen install directory (`--win-dir-chooser`) is honoured across the upgrade.

**The in-app path**, in order: dev-build state → check with no release published (expect the
404/"nothing yet" state, not an error) → check with a release → download and verify →
**deliberately corrupt the staged MSI and confirm the launcher refuses it and keeps running** →
real apply → confirm the relaunch and the new reported version.

**Failure paths**: cancel the UAC prompt (expect exit 1602 recorded, desk back on the old version);
kill the wrapper mid-install; unplug the network mid-download (expect the `.part` gone, nothing
staged); fill the disk.

See [`docs/windows-updates.md`](../windows-updates.md).

## `FU-MANUAL-SCALER-PROJECT-SWITCH`

**Scaler state across project switches** · Control-surface Phase 9

Connect device → toggle **Blackout** on project A (confirm LED + stage) → switch to project B via
`/projects` → Blackout off on B (fresh holder) → switch back to A → Blackout still on, stage still
dark. Same for **Grand Master**. Verify a WS client open across the switch sees the correct
`surfaceScaler.state` payload at each switch and after toggling within the new project. A backend
restart no longer resets them — `FU-BE-SCALER-PERSISTENCE` landed in `7bcd109`, so confirm they
survive one.

## `FU-MANUAL-SUSPEND-PATH`

**Suspend-path sanity check** · Control-surface Phase 8

Run a script adding and removing 100 effects/sec while a MIDI fader runs at full 60 Hz on the same
property. Confirm no stage stutter, no WebSocket `channelState` lag, no coroutine leak on a thread
dump. No functional change expected — the suspend path delivers the same per-channel acks as the old
blocking path. Regression sanity check, not new validation.

## `FU-MANUAL-CUEEDIT-HARDWARE`

**cueEdit integration on hardware** · Control-surface Phase 6

Open a cue for edit in Live mode via the frontend → wiggle a bound fader → confirm the cue's
`dimmer` row updates (`GET /cues/{id}`) → stage reflects the new value → close the editor →
retrigger the cue → the edit reproduces. Repeat in Blind: stage unaffected during the edit, value
still persists.

## `FU-MANUAL-SURFACES-FLOW`

**End-to-end `/surfaces` flow** · Control-surface Phase 5

Connect the device → `/surfaces` shows it attached. Click **+** on a fader row, open MIDI Learn,
wiggle the physical fader → the binding appears. Switch banks via `BankSwitcher` → matrix rows
update. Validates the Phase 5 UI and Phase 3/4 wiring against real hardware edges (debounce,
device-side bank events, motor drive under load).

## `FU-MANUAL-DEAD-ASSIGNMENTS`

**Dead-assignment banner on a live rig** · Cue-authoring Phase 6, 2026-04-22

Backend logic for `DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` is stateless and
unit-covered; WS fan-out plus React rendering of dead markers after a fixture rename was never
validated end-to-end.

**Test**: rename a fixture in a patch, reload the cue editor, confirm dead markers appear on the
affected rows and Remove clears them. 10 minutes.
