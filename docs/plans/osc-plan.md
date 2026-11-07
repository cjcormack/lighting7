# OSC: QLab and other show-control senders drive the desk

> **Document status: READY — written 2026-11-07 from the page Chris and Claude agreed that day.**
> Three sessions, each one PR, each stacked on the one before while that one is unmerged, each run
> by `/next-session` (§5). The page and the record are in
> [`osc-design/INDEX.md`](osc-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-07: add OSC support so the desk responds to cues from QLab: GO, busk
buttons and the like. A QLab Network cue sends one OSC message (an address and optional
arguments) over UDP or TCP to a patch's host and port. It sends whatever address the cue's author
types and reads nothing back.

What the code says, read at `8c35838`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **Surface dispatch.** A MIDI input reaches the show through `K/midi/SurfaceInputRouter.kt`:
  `route` matches the event against the device profile, sets a
  `WriteOrigin(HistorySource.surface(displayKey))` and calls `routeMatched`, which resolves a
  `BindingTarget` (`K/midi/BindingTarget.kt`, a sealed class serialised with a `type`
  discriminator and stored as text in `DaoControlSurfaceBindings.targetPayload`) and dispatches it
  in the private `dispatchButtonPress` and its continuous counterpart through `SurfaceActions`
  (`K/midi/SurfaceActions.kt`). `DefaultSurfaceActions` reaches the show through getters on `state.show`
  for every stack, cue, Look and pad action (the busk page, selection and hand actions read
  `State`'s own holders instead), resolves a stack or cue uuid to the current project's row (`resolveStackId`, `resolveCueId`), and mostly
  returns `Unit`, so a refusal is a log line (the blackout and grand-master toggles answer a
  `Boolean`). `pressPad`, for one, logs `BuskPressService`'s `Outcome` and drops it.
  `K/midi/BindingControlKind.kt`'s `targetControlKind` places every target but `Strip` and `Unknown`
  as `CONTINUOUS` or `BUTTON`, and a new target must be added there. OSC transport is listed under "Not implemented (architecture
  accommodates)" in `docs/midi-control-surface-engineering.md`.
- **The show guard.** The router collects a controller's input with no per-event catch, and the
  actions read `state.show`, which throws while a project switch is in flight; one throw kills the
  collector. That is `FU-MIDI-SHOW-GUARD` in `docs/plans/followups.md`, whose fix is a per-event
  guard at the collector.
- **Stacks.** `K/fx/CueStackManager.kt`: `go` (advance an active stack, else start it),
  `goToEntry(state, stackId, entryId)`, which calls `activateEntry` with `fireEvents = true`,
  `advanceStack` (its BACKWARD arm passes `fireEvents = false`), `deactivateStack(stackId, appState)`
  and `pauseAutoAdvance`, which cancels the live entry's follow timer and is not a toggle.
  `activate` treats a GO onto the entry already live as a deliberate **re-fire**: it lets the hold
  go with a snap and takes it again. Its follow timer restarts and its *Tempo on GO* is set again;
  where that stack was the cue's only hold, the cue comes on afresh, its FX respawning and its
  events firing again, and where another stack or a press also holds it, the hold is only
  restamped. `deactivateStack` calls
  `effectsService.onStackStopped`, which drops the desk's arm: a stack stop is a stop. Standby is
  `runState.setStandby(state, stackId, entryId)` and `clearStandby` (`K/fx/CueRunStateTracker.kt`).
  Surfaces have GO, BACK and PAUSE targets, but no GO TO, standby or stop.
- **Cue events** fire on a cue's first hold (`CueHolds.afterRegister` →
  `EffectsService.onCueGo`), so a GO TO fires the events of the cue it lands on, as a GO does. They
  never track: a cue skipped by a GO TO fires none, and GO BACK fires none
  (`docs/cues-engineering.md` §"Cue events"). They need the desk armed.
- **Loose cues.** `K/fx/CueHolds.kt`: `toggleLooseCue`, which the surface's `fireCue` calls,
  `takeLooseCue` (on a cue already loose-held it takes a fresh hold and sets its tempos again),
  `dropLoose` and `looseHeld`. The hold type there is `sealed interface CueHold`.
- **Looks.** The surface's `applyLook` toggles the Look as a programmer layer, through
  `ProgrammerLayerStack.toggle(source, targets = emptyList())`, so Clear takes it off, Blind stages
  it and Record captures it, as for a busk Look pad. `appliedState()` and `release(sourceUuids)`
  (`K/fx/ProgrammerLayerStack.kt`) are what an explicit on and off need.
- **Busk.** The surface's `pressPad` calls `BuskPressService.pressByUuid` (`K/routes/BuskPressService.kt`)
  with the desk's shared selection and its attribute mask (`state.deskSelection`), and gets an
  `Outcome` back (`Pressed`, `Refused`, `TargetMissing`, `NotFound`). A generic palette pad lands on
  whatever the desk has selected, and is refused when nothing is (`PALETTE_NEEDS_SELECTION`) or
  when the selection's attribute mask leaves out the palette's family (`PALETTE_OUTSIDE_MASK`).
  `buskPageSet` is
  `state.buskPageState.setByUuid`.
- **Masters and tempo.** `K/midi/GlobalScalerStateHolder.kt`: `setBlackout`, `toggleBlackout` and
  `setGrandMasterLevel` (0–100 %). `SpeedMasterBank.setBpm(uuid, bpm, source)` and `tap(uuid)`. The
  surface's `writeSpeedMasterBpm`, `writeGrandMaster` and `writeStackFxAmount` take a 7-bit value.
- **Names.** A STACK's name is unique in its project (a partial index `WHERE type = 'STACK'` in
  `K/state/State.kt`, so separators may share a stack's name), so is a Look's
  (`uniqueIndex(project, name)`, `K/models/looks.kt`) and a busk page's (`BUSK_PAGE_NAME_TAKEN`,
  `K/routes/projectBusk.kt`). A bank's name may repeat on a page (`K/models/buskLayout.kt`), and a
  cue's name is not unique. Cue numbers are read by `cueNumberValue` and spelt by
  `canonicalCueNumber` (`K/routes/cueNumbering.kt`). The MCP setup tools already slug names (`slug`
  in `K/ai/SetupTools.kt`: lower-case, `[^a-z0-9]+` to `-`, trimmed, at most 100 characters), so a
  name with no ASCII letter or digit slugs to nothing.
- **Change hooks.** `FixturesChangeListener` (`K/show/Fixtures.kt`) has `cueStackListChanged`,
  `cueListChanged`, `lookListChanged`, `speedMasterListChanged` and `buskLayoutChanged(pageIds)`.
  The cue-stack routes and the surface's GO and BACK fire `cueStackListChanged` on every GO and
  BACK they make (an auto-advance does not), so a cache rebuilt on that hook would rebuild on most
  GOs. `SurfaceFeedbackPublisher` does not listen to it.
- **History.** `HistorySource.Kind` is `ACCOUNT | MCP | SURFACE | DESK` (`K/history/WriteOrigin.kt`),
  mirrored in `F/api/historyApi.ts`.
- **Machine-local settings.** Remote access's `remote_access_settings` (`K/models/remoteAccess.kt`)
  is recorded `MachineLocal` in `K/sync/SyncDispositions.kt`, served by `K/routes/installTunnel.kt`,
  and drawn by `F/components/remoteAccess/RemoteAccessPanel.tsx` (`F/store/remoteAccess.ts`) on
  `F/routes/InstallSettings.tsx`, whose tabs show only where `F/navigation.ts` shows their row
  (remote access's is `adminOnly`, held by `F/navigation.test.ts`). `call.isRemote` is how a route
  refuses the public listener (`K/routes/installCloud.kt`); install routes are mounted on both.
- **Machine-scoped frames.** `setupMachineSubscriptions` (`K/plugins/MachineSocket.kt`) serves the
  sockets of both listeners, and a frame for the desk's own listener only checks `scope.remote`.
- **Transport precedents.** `K/midi/KtMidiController.kt` runs each device's transmit loop on its own
  thread; input arrives on the MIDI library's listener and is `tryEmit`ted into a
  `MutableSharedFlow` of 256 that drops the oldest, uncounted, since `tryEmit` on a `DROP_OLDEST`
  flow always succeeds. `K/dmx/PacketRateCounter.kt` counts packets (the MIDI controller uses one
  for inbound rates).
- **Bindings.** `control_id` is `varchar(100)` (`K/models/surfaceBindings.kt`), and every device
  profile in `K/midi/devices/` declares its controls statically.
- **Telemetry.** Every `cue.go` transaction is kept (`ALWAYS_KEEP` in `K/telemetry/TraceSampling.kt`),
  whichever door the GO came through. The 30-second metrics are `K/perf/DeskMetrics.kt`.
- **Tests.** `RecordingActions` is private to `src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouterTest.kt`.
  `CloudOutageOutputTimingTest` holds a fade's output timing while the cloud misbehaves, on
  `src/test/kotlin/uk/me/cormack/lighting7/testsupport/FadeTiming.kt`.
- **Ports and versions.** REST is 8413 and the public listener 8414 (`mcp.port`). Sync is at
  `formatVersion` 49. Nothing here needs a migration: new tables are created by
  `createMissingTablesAndColumns` once they are in `ALL_TABLES` (`K/models/Schema.kt`).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **A fixed address set comes first; learnable addresses come later.** The desk publishes addresses under `/lighting7/`, named after the records that already exist (§3), so a QLab cue needs no setup on the desk. Learn is for senders whose addresses can't be changed (session 3). |
| D2 | **Every address resolves to a `BindingTarget` dispatched through `SurfaceActions`.** No OSC-specific show logic: OSC and MIDI share one dispatcher (§3 "Dispatch"). A new action is added as a target, so a MIDI surface gains it too, and an OSC press does what a surface press does, the desk selection included. |
| D3 | **Names appear in addresses as slugs, and an ambiguous match is refused, never guessed.** OSC forbids spaces and reserves `#*,?[]{}`. The slug is `SetupTools.slug`'s rule, and an incoming segment is lower-cased before it is matched. An address that matches two records is refused as ambiguous and the log names both. Separators are never matched. A name that slugs to nothing has no address, and the address listing (session 2) says so. A cue address works only for a cue whose name is unique; a cue in a stack is addressed by number. There is no second, exact-name argument form. |
| D4 | **Explicit forms beside every toggle, and each lands the same state however many times it arrives.** GO TO cue *n* beside GO, and on and off beside press, for loose cues and Looks. GO TO onto the entry already live, `on` for a cue already loose-held or a Look already applied, and `off` for one that is not are **no-ops**, answered done ("already live"), so a QLab cue fired twice changes nothing: no re-fire, no second *Tempo on GO*, no second events. The toggles and bare GO stay for buttons pressed by hand, and keep today's re-fire. |
| D5 | **Off by default, allowlisted, machine-local, LAN only.** An admin on the desk's own listener turns OSC on. Only listed senders are heard: each entry is an IP address and a name. A packet from anywhere else is dropped before parsing and counted, one row per refused IP with *Allow this sender*. A source address can be forged by anyone on the LAN, so the allowlist keeps out the wrong machine, not an attacker; the bind address (default every interface) is how a desk keeps OSC off a network it doesn't trust. Settings and senders never sync. Nothing about OSC is served on the public listener: `/install/osc` refuses a remote caller and `osc.state` goes only to the desk's own listener's sockets. |
| D6 | **No arming, triggers, fixture commands, recording, undo or project switching over OSC.** A QLab cue can GO a cue whose events fire, but only once someone at the desk has armed it. OSC can drop the arm, as any stack stop does (`/stop`), and never raise it. |
| D7 | **OSC acts on the current project only**, as MIDI does. |
| D8 | **The receive path never blocks the show.** A dedicated `osc-1` thread reads, checks the sender and parses, and nothing more: it never touches the database or `state.show`. It hands messages to a `Channel(64, DROP_OLDEST)` whose `onUndeliveredElement` counts each drop. One collector resolves and dispatches each message in turn, reading the database as a REST request does, inside a per-message guard that turns any throw (a project switch in flight among them) into a *failed* log row and carries on. |
| D9 | **A hand-rolled OSC 1.0 parser, over UDP first.** A null-padded address, a type-tag string, big-endian `i`, `f`, `s` and `b` arguments; `T` and `F` read as 1 and 0, `h` and `d` read and narrowed; any other tag drops the message with a reason. Where an address wants a float it takes any number (`i`, `h`, `d`, `T`, `F`), since QLab sends a whole number as `i`; where it wants a flag it takes any number, non-zero as on. Bundles are unpacked to a depth of 8 and at most 64 messages a packet; deeper or more drops the packet with a reason. Timetags are ignored and every message runs on arrival. No dependency, so the licence gate is untouched. TCP with SLIP framing is session 3's. |
| D10 | **Continuous values bypass the 7-bit path.** The grand master, tempo and a stack's FX amount take floats whole, rather than through the `UByte` arms MIDI uses. |
| D11 | **Cue events keep today's rule.** A GO TO fires the events of the cue it lands on, armed, as a GO does; a skipped cue's events never fire. An OSC GO TO is an ordinary GO TO (D4's no-op aside), so nothing about events changes. |
| D12 | **Every signed-in window on the desk's own listener sees the address log**; only admins change the settings and the allowlist. The operator at the desk is the one who needs to see that a QLab cue did nothing. |
| D13 | **The default port is UDP 8415**, beside 8413 and 8414, changeable in settings. |
| D14 | **Replies and feedback wait for session 3.** QLab reads nothing back. They come with Learn, when a TouchOSC-style sender needs them. |

The plan adds three of its own:

- **P1 — Three sessions, three stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged.
- **P2 — One address table.** The address set is one declarative table in `K/osc/OscAddresses.kt`:
  each entry's pattern, arguments, what it does and the target it builds. The resolver, the address
  listing (session 2) and the tests all read it, so the parser, the cheat sheet and §3 cannot drift
  apart. §3 is updated in the session that changes the table.
- **P3 — Show safety is tested.** A fade's output timing holds under a flood of OSC packets from an
  allowed sender and from a refused one, on `FadeTiming` as `CloudOutageOutputTimingTest` does for
  the cloud.

## 3. The model

### The address set (D1, D4)

Every address starts `/lighting7/` and acts on the current project (D7). `{stack}`, `{cue}`,
`{look}` and `{page}` are slugs (D3); `{n}` is a cue number, matched by value; `{bank}` and `{pad}`
count from 1 in layout order; `{master}` is a speed master's 1-based index, and one past the last
is not found. *New* marks a target this plan adds.

| Address | Args | Does | Target | Session |
|---|---|---|---|---|
| `/stack/{stack}/cue/{n}/go` | – | GO TO cue *n*, starting the stack if it is stopped; nothing if *n* is already live | new `CueStackGoTo` → `goToEntry` | 1 |
| `/stack/{stack}/cue/{n}/standby` | – | Put cue *n* on standby | new `CueStackStandby` → `setStandby` | 1 |
| `/stack/{stack}/go` | – | GO | `CueStackGo` | 1 |
| `/stack/{stack}/back` | – | GO BACK | `CueStackBack` | 1 |
| `/stack/{stack}/pause` | – | Cancel the live entry's auto-advance timer | `CueStackPause` | 1 |
| `/stack/{stack}/stop` | – | Stop the stack, which drops the arm as any stop does | new `CueStackStop` → `deactivateStack` | 1 |
| `/stack/{stack}/fx-amount` | `f` 0–1 | The stack's FX amount | `StackFxAmount`, float arm (D10) | 1 |
| `/cue/{cue}/on`, `/off` | – | Take or let go a loose hold; nothing if already so | new `LooseCueSet(on)` | 1 |
| `/cue/{cue}/press` | – | Toggle the loose hold | `FireCue` | 1 |
| `/look/{look}/on`, `/off` | – | The Look on whole, or every layer of it off; nothing if already so | new `LookSet(on)` | 1 |
| `/look/{look}/press` | – | Toggle the Look | `ApplyLook` | 1 |
| `/busk/{page}/{bank}/{pad}/press` | – | Press a pad, on the desk selection | `PressPad` | 1 |
| `/busk/{page}/show` | – | Show that busk page | `BuskPageSet` | 1 |
| `/blackout` | `i` 0/1, optional | Set blackout; toggle with no argument | new `BlackoutSet` · `Blackout` | 2 |
| `/grandmaster` | `f` 0–1 | Grand master level | new `GrandMasterLevel` | 2 |
| `/tempo/{master}` | `f` BPM | Set a speed master's tempo (20–300) | new `SpeedMasterSetBpm` | 2 |
| `/tempo/{master}/tap` | – | One tap | `SpeedMasterTap` | 2 |

A cue number matches by `cueNumberValue`, so `12.5` and `12.50` reach the same entry. A tempo write
goes where the surface's go, so the programmer's hold rule (a write on a held master moves the hold)
applies unchanged.

**Two kinds of state, and which to use from QLab.** A Look is a programmer layer, whether it comes
from OSC, a busk pad or a surface: Clear takes it off, Record captures it, and in Blind it is staged
rather than sent, which its log row says ("staged: Blind"). A busk pad presses on whatever the desk
has selected (D2), so from QLab only Look and cue pads are predictable. A loose cue is a hold above
every stack that the programmer does not touch. The page and `docs/osc-engineering.md` recommend
cues and loose cues for show control, and Looks where the operator may take them over.

**Addresses that move.** Names fail loudly when they change: the old address answers not found.
Numbers and positions do not: renumbering a cue, an insert or delete that relabels auto numbers,
moving a bank or pad, or deleting a speed master can point an address at a different record. So every *done* row
names the record it reached ("GO TO 31 *Storm builds* in Act 2"), and session 2's warning covers
each of those as well as rename.

### Dispatch (D2, D8)

Session 1 lifts the router's private dispatch into `K/midi/SurfaceDispatch.kt`: one function from a
resolved `BindingTarget` (and a value for a continuous one) to an `ActionOutcome` (`Done(detail)`,
`Refused(code, message)`, `NotFound(message)`), shared by `SurfaceInputRouter` and the OSC collector,
with the per-event guard both need. That guard closes `FU-MIDI-SHOW-GUARD` for MIDI too. The
`SurfaceActions` methods the address set reaches return an `ActionOutcome` instead of `Unit`
(`pressPad` its `BuskPressService` outcome, a stack action a missing stack or a marker), and the
router keeps logging what it ignores.

### Outcomes and the log (D12)

Every received message is logged with its time, sender (the allowlist name), address, arguments and
outcome: **done**, **not found**, **ambiguous**, **refused**, **failed** or **bad message** (an
unknown address, a type tag it cannot read, a bundle too deep), with a one-line detail ("No stack
`act-3` in this show"). A message the channel drops never reaches the collector, so drops are a
count, not log rows. The last 50 are kept in
memory. Refused senders are a separate list, one row per IP with a count and when last seen (at most
20), so a flood from one cannot push real rows out. Every address resolved since the desk started is
kept in a set of its own (at most 1,000), which session 2's warning reads. Received, dropped and
refused counts go into `DeskMetrics`.

### History (D6)

A new `HistorySource.Kind.OSC`, keyed by the allowlist entry's id and named by it, so History shows
"QLab". Each message runs under its own `WriteOrigin`, as a surface input does. Most OSC traffic
takes no step (a GO writes nothing stored); a Look on or off takes one, as a busk press does. There
is no undo address.

### Wire

- `GET/PUT /api/rest/install/osc`: `{enabled, bindAddress, port, senders: [{id, name, address}]}`,
  admin only. Both are 403 `OSC_SETTINGS_LOCAL_ONLY` when `call.isRemote`; the `PUT` is 400
  `OSC_SETTINGS_INVALID` naming every problem at once (a port outside 1024–65535, a bad address, a
  sender twice).
- `GET /api/rest/projects/{id}/osc/addresses` (session 2): every address the project answers to,
  from the table (P2), with what it does, the record it reaches, any ambiguity and any record with no
  address. Any project, both roles.
- `osc.state`: outbound, `StateFlow`-backed, machine-scoped (registered in
  `setupMachineSubscriptions` and skipped when `scope.remote`), to every signed-in socket on the
  desk's own listener, conflated to 4 a second:
  `{enabled, listening, port, error?, received, dropped, refused, refusedSenders: [{address, count, lastAt}], recent: [{at, sender, address, args, outcome, detail}]}`.
  A bind failure (the port in use) is `error`, never a failed startup.

### Storage

`osc_settings` (one row) and `osc_senders`, both `MachineLocal` in `SyncDispositions`. No
`formatVersion` change and no migration. Learned bindings (session 3) are ordinary
`control_surface_bindings` rows, `Portable` as today.

## 4. UX

- **Install settings → OSC** (session 1): a row in `F/navigation.ts` shown to both roles. An operator
  sees the status and the log, from `osc.state` alone; an admin also sees the *Enable* switch, the
  port and bind address and the senders (name and IP, add and remove), from `GET /install/osc`. Each
  log row's outcome is a chip, and each refused sender's row carries *Allow this sender* for an
  admin, which adds it with its IP filled in and asks for a name.
- **Copy OSC address** (session 2) on a stack's, a cue entry's, a Look's and a busk pad's menu, and a
  printable cheat sheet of every address the show answers to, for the sound department.
- **The warning** (session 2): renaming a stack, a loose cue, a Look or a busk page, renumbering a cue, an
  insert or delete that relabels auto numbers, moving a bank or a pad, or deleting a speed master,
  where an address it changes was received since the desk
  started, says which QLab addresses will stop working or now reach something else.

## 5. Implementation — three sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names
written, and its row marked *in review* with its branch. A session's PR is titled
*OSC: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Listener, stacks, Looks, cues and busk | planned | | | |
| 2 | Masters, tempo and finding addresses | planned | | | |
| 3 | Learnable addresses, feedback and TCP | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Listener, stacks, Looks, cues and busk

- **Transport (D8, D9):** `K/osc/OscPacket.kt`, the parser and an encoder for tests;
  `K/osc/OscReceiver.kt`, the `DatagramSocket` on `osc-1`, the allowlist check before parsing, the
  channel and its counters. It starts when OSC is enabled, restarts on a port or bind-address
  change, and closes on shutdown.
- **Settings (D5, D13):** `K/models/oscSettings.kt` (`osc_settings`, `osc_senders`), in `ALL_TABLES`
  with `MachineLocal` dispositions; `K/routes/installOsc.kt` for `GET/PUT /install/osc`.
- **Dispatch (D2):** `K/midi/SurfaceDispatch.kt` and `ActionOutcome` (§3 "Dispatch"), the router
  moved onto it with its per-event guard, closing `FU-MIDI-SHOW-GUARD` (its row to Completed). The
  `SurfaceActions` methods the session-1 rows reach return an outcome. `RecordingActions` moves to
  `src/test/kotlin/uk/me/cormack/lighting7/testsupport/RecordingSurfaceActions.kt` so both packages'
  tests share it.
- **The table and the resolver (D3, P2):** `K/osc/OscAddresses.kt` with every session-1 row of §3,
  resolving against the database on the collector; `SetupTools.slug` moves to a shared helper both
  use.
- **New targets (D4):** `CueStackGoTo(stackUuid, entryUuid)`, `CueStackStandby(stackUuid, entryUuid)`,
  `CueStackStop(stackUuid)`, `LooseCueSet(cueUuid, on)` and `LookSet(lookUuid, on)` in
  `BindingTarget.kt`, placed in `targetControlKind` (all `BUTTON`), each with D4's no-op.
  `StackFxAmount` gains a float arm (D10). Each is offered to MIDI too (D2), so the client's
  mirrors take them: `F/api/surfacesApi.ts`, `F/components/surfaces/targetUtils.ts`,
  `F/components/surfaces/BindingTargetPicker.tsx`, `F/components/surfaces/SurfaceLibrary.tsx`,
  `F/store/surfaces.ts`, `F/lib/surfaceDrop.ts`, `F/components/surfaces/recordOptions.ts`
  (`recordTargetName` and `describeBindingTarget`, whose `default` arm the compiler will not flag)
  and `F/routes/Surfaces.tsx` (`describePlacements`, which counts `applyLook`).
- **The service:** `K/osc/OscService.kt` on `State`, owning the receiver, the collector, the log,
  the refused senders, the received set and `osc.state`; each message dispatched under
  `WriteOrigin(HistorySource.osc(sender))`. `HistorySource.Kind.OSC` in `K/history/WriteOrigin.kt`
  and `F/api/historyApi.ts`.
- **The client:** the *OSC* row in `F/navigation.ts` (and `F/navigation.test.ts`), the tab in
  `F/routes/InstallSettings.tsx`, `F/components/osc/OscPanel.tsx`, and `F/store/osc.ts` for the
  settings and the `osc.state` subscription.
- **Tests:** the parser against the OSC 1.0 specification's examples, bundles, nested bundles past
  the depth cap, padding, truncated packets, unknown tags and `i` where `f` is wanted; the resolver
  over every session-1 row, slug collisions, a separator sharing a stack's name, an empty slug, upper
  case, cue numbers by value, a deleted stack; D4's no-ops (a repeated GO TO keeps the cue's FX
  phase and fires no second events); the dispatcher's guard with `state.show` throwing; the receiver
  end to end on loopback with `RecordingSurfaceActions`, a refused sender and a port in use; the
  settings route's refusals, a remote caller's included, and `osc.state` absent from a remote socket;
  the channel's drop count (that `onUndeliveredElement` fires for each element `DROP_OLDEST`
  drops); P3's flood test; `SyncCoverageTest` for the two tables; `MetricsReporterTest` for the new metric
  keys; `OscPanel.test.tsx` for both roles.
- **Docs:** a new `docs/osc-engineering.md`; `docs/midi-control-surface-engineering.md` (OSC no
  longer "not implemented", `SurfaceDispatch`, the new targets in §"BindingTarget variants");
  `docs/websocket-engineering.md` for `osc.state`; the root `CLAUDE.md` (an OSC entry under External
  Integrations, the endpoints and the frame); `docs/glossary.md` if a new word is coined;
  `docs/undo-engineering.md` (OSC as a fifth door in its door table);
  `docs/observability-engineering.md` (a row per new metric in its metrics table);
  `FU-MANUAL-OSC-S1` in `docs/plans/manual-validation.md` (§6).
- **Local:** the full `./gradlew test` (the dispatcher moves across `midi`, `routes`, `plugins`, `ai`
  and `history`) and `npm run check:quick`.

### Session 2 — Masters, tempo and finding addresses

- **Addresses:** §3's session-2 rows in `OscAddresses.kt`, with new targets `BlackoutSet(on)`,
  `GrandMasterLevel(level)` and `SpeedMasterSetBpm(masterUuid, bpm)`, floats taken whole (D10), each
  in the client's mirrors as in session 1.
- **Finding addresses:** `GET /projects/{id}/osc/addresses` from the table (P2); *Copy OSC address*
  on the stack, cue entry, Look and busk pad menus (`F/components/cues`, `F/components/looks`,
  `F/components/busking`); the printable cheat sheet; the warning (§4), from the received set.
- **Tests:** the new rows, the listing route against the table, the copy items, the warning on each
  of its eight changes.
- **Docs:** `docs/osc-engineering.md` and the root `CLAUDE.md` for the new rows and route;
  `FU-MANUAL-OSC-S2`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `osc`, `midi` and `routes`
  packages, and `npm run check:quick`.

### Session 3 — Learnable addresses, feedback and TCP

**Needs:** operator: a sender whose addresses can't be set (TouchOSC or similar) is in use.

- **Learn (D1):** an `osc` device type whose controls are its bindings' addresses, outside
  `/lighting7/`. Every profile in `K/midi/devices/` declares its controls statically, so this is the
  first whose controls are dynamic, which `ControlSurfaceDevice` and `ControlSurfaceRegistry` must
  allow. An address with a float argument is continuous and one without a button, a float at 0 a
  release. OSC hands the router a resolved input through a public entry, skipping `matchEvent`.
  `MidiLearnSessionManager` captures the next address from an allowed sender. A binding on an address
  under `/lighting7/`, one longer than `control_id`'s 100 characters, or to a target D6 excludes is
  refused at bind time.
- **Feedback (D14):** `K/osc/OscController.kt` sends fader positions and LEDs to a bound sender's
  address and port, fed by `SurfaceFeedbackPublisher`; a `/lighting7/reply` with each fixed
  address's outcome, behind a per-sender switch.
- **TCP:** OSC 1.1 SLIP framing on the same port.
- **Tests:** Learn over OSC, the bind-time refusals, feedback against a loopback receiver, SLIP
  framing.
- **Docs:** `docs/osc-engineering.md`, `docs/midi-control-surface-engineering.md`;
  `FU-MANUAL-OSC-S3`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `osc` and `midi` packages,
  and `npm run check:quick`.

## 6. Operator checks

One row in `manual-validation.md` per merged session, at the desk with QLab 5 on the show network:

- `FU-MANUAL-OSC-S1`: a QLab Network cue sending `/lighting7/stack/act-1/cue/1/go` lands cue 1;
  fired again mid-fade, nothing moves; a Look's `on` and `off`; a busk Look pad; a QLab machine not
  on the allowlist shows in the log with *Allow this sender*; an operator sees the log.
- `FU-MANUAL-OSC-S2`: the grand master from a QLab fade cue sending floats; a copied address pasted
  into QLab works first time; renumbering a received cue warns.
- `FU-MANUAL-OSC-S3`: a TouchOSC fader learned onto a group dimmer moves the rig and follows it back.

## 7. Explicitly out of scope

- MIDI Show Control and timecode.
- Network MIDI (RTP-MIDI).
- OSC address patterns (`*`, `?`, `[…]`, `{…}`) in received addresses.
- Arming, firing triggers, fixture commands, Record, undo and project switching over OSC (D6).
- OSC on the public listener or through the tunnel.
- A second, exact-name argument form for addresses (D3).
- Authenticating senders beyond their source address (D5).
