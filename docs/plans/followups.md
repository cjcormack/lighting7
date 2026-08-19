# Lighting7 Follow-ups

Dormant engineering work left behind by the completed plans in [`completed/`](completed/).
Each item is self-contained so a cold session can pick one up. Operational rig checks live
next door in [`manual-validation.md`](manual-validation.md).

**Don't poll this file.** Read the index below when your change might fire a listed gate, or
when you want a Ready item to pick up.

## Index

Status meanings: **Ready** — pick up and go. **Trigger** — the named signal must fire first.
**Blocked** — the named prerequisite must land first. **Rejected** — a decision record; there
is nothing to pick up, and the reasoning is there so the idea isn't re-litigated.

| Slug | Status | Area | Gate |
|---|---|---|---|
| [`FU-PERF-BLACKOUT-LATENCY`](#fu-perf-blackout-latency) | Ready | Perf | needs a product call between three shapes |
| [`FU-PROG-L3RESOLVER-RENAME`](#fu-prog-l3resolver-rename) | Ready | Prog | — |
| [`FU-PROG-VIS-SOURCE`](#fu-prog-vis-source) | Ready | Prog | — |
| [`FU-PAL-PRESET-MAKE-HARD`](#fu-pal-preset-make-hard) | Ready | Pal | — |
| [`FU-AUTH-STALE-ANON-SOCKET`](#fu-auth-stale-anon-socket) | Ready | Auth | — |
| [`FU-DIST-ICONS`](#fu-dist-icons) | Ready | Dist | — |
| [`FU-SYNC-FORMAT-MIGRATIONS`](#fu-sync-format-migrations) | Blocked | Sync | a real breaking `formatVersion` bump |
| [`FU-TEST-MULTI-CONN-CUEEDIT`](#fu-test-multi-conn-cueedit) | Blocked | Test | cue-authoring `beginEdit` conflict semantics |
| [`FU-DIST-NO-BUNDLED-JRE`](#fu-dist-no-bundled-jre) | Rejected | Dist | decision record — do not re-propose |
| [`FU-PERF-FRAME-TXN-UNIFY`](#fu-perf-frame-txn-unify) | Trigger | Perf | visible flicker where beat + wall-clock share a universe |
| [`FU-FE-REBIND-INPLACE`](#fu-fe-rebind-inplace) | Trigger | FE | operator asks for it |
| [`FU-FE-HEALTH-BADGE`](#fu-fe-health-badge) | Trigger | FE | a 4th surface renders `AssignmentHealth` |
| [`FU-FE-USE-TARGET-PROPERTIES`](#fu-fe-use-target-properties) | Trigger | FE | a 6th consumer of fixture/group property lookup |
| [`FU-SPEED-SURFACE-TAP-LED`](#fu-speed-surface-tap-led) | Trigger | Speed | operator wants tap confirmation on the surface |
| [`FU-SPEED-RATEMASTER-STATEFUL`](#fu-speed-ratemaster-stateful) | Trigger | Speed | a stateful wall-clock effect wants a rate master |
| [`FU-SPEED-PER-ATTRIBUTE`](#fu-speed-per-attribute) | Trigger | Speed | a composite needs split tempos |
| [`FU-PROG-PER-USER`](#fu-prog-per-user) | Trigger | Prog | a second operator programming the same show |
| [`FU-PROG-STAGED-CLEAR`](#fu-prog-staged-clear) | Trigger | Prog | the simple Clear bites |
| [`FU-PROG-HIGHLIGHT-PERSONALITY`](#fu-prog-highlight-personality) | Trigger | Prog | a rig big enough to lose a head in |
| [`FU-PAL-POSITIONAL-CONVERSION`](#fu-pal-positional-conversion) | Trigger | Pal | a show maintains the same colours in both forms |
| [`FU-PAL-APPLY-NEAREST-COVERAGE`](#fu-pal-apply-nearest-coverage) | Trigger | Pal | an operator asks for gap-filling |
| [`FU-PAL-LINKED`](#fu-pal-linked) | Trigger | Pal | a palette kept hand-synced to another |
| [`FU-AUTH-RESET-TOKEN-STALENESS`](#fu-auth-reset-token-staleness) | Trigger | Auth | two admins routinely administering one desk |
| [`FU-AUTH-SESSION-LIST-STALENESS`](#fu-auth-session-list-staleness) | Trigger | Auth | "why isn't my phone in the list?" |
| [`FU-AUTH-ATTRIBUTION`](#fu-auth-attribution) | Trigger | Auth | two accounts co-author, or any `formatVersion` bump |
| [`FU-AUTH-AUDIT-LOG`](#fu-auth-audit-log) | Trigger | Auth | a security question nobody can answer |
| [`FU-AUTH-WS-PER-MESSAGE`](#fu-auth-ws-per-message) | Trigger | Auth | an admin-only operation gains a WS command |
| [`FU-AUTH-OPERATOR-LOCKDOWN`](#fu-auth-operator-lockdown) | Trigger | Auth | an operator changes something they shouldn't have |
| [`FU-AUTH-TLS-COOKIES`](#fu-auth-tls-cookies) | Trigger | Auth | the desk leaves a trusted LAN |
| [`FU-DIST-JLINK-MODULES`](#fu-dist-jlink-modules) | Trigger | Dist | the runtime needs to shrink (1 MB on offer) |
| [`FU-DIST-NATIVE-ARCH`](#fu-dist-native-arch) | Trigger | Dist | the MSI needs a final ~3 MB, or a 2nd arch ships |
| [`FU-SYNC-TOMBSTONE-GC`](#fu-sync-tombstone-gc) | Trigger | Sync | ~1000 tombstone files on a real working tree |
| [`FU-SYNC-JGIT-STRESS-BENCH`](#fu-sync-jgit-stress-bench) | Trigger | Sync | ~1000 synced records, `runSync` >5 s, or an OOM |
| [`FU-SYNC-STREAMING-PROGRESS`](#fu-sync-streaming-progress) | Trigger | Sync | sync feels unresponsive, or a cycle exceeds 5 s |
| [`FU-SYNC-MERGE-ATOMICITY`](#fu-sync-merge-atomicity) | Trigger | Sync | a merged change reverts after a crash |
| [`FU-SYNC-ORDINAL-DOUBLE`](#fu-sync-ordinal-double) | Trigger | Sync | surprising cue order after a merge, or a bump |
| [`FU-SYNC-FETCHING-STATE`](#fu-sync-fetching-state) | Trigger | Sync | a sync "just stopped" with no log trail |
| [`FU-SYNC-FIELD-LEVEL-MERGE`](#fu-sync-field-level-merge) | Trigger | Sync | frequent conflicts on disjoint fields |
| [`FU-SYNC-MANUAL-MULTIFILE`](#fu-sync-manual-multifile) | Trigger | Sync | an operator wants to hand-merge a script conflict |
| [`FU-SYNC-PUSHRETRY-TEST-SEAM`](#fu-sync-pushretry-test-seam) | Trigger | Sync | `FU-SYNC-MERGE-ATOMICITY` is picked up |
| [`FU-SYNC-BINDING-PAYLOAD-UUIDS`](#fu-sync-binding-payload-uuids) | Trigger | Sync | dead MIDI bindings after a clone, or `BindingTarget` work |
| [`FU-TEST-FX-BENCH-CI-GATE`](#fu-test-fx-bench-ci-gate) | Trigger | Test | a week of baseline numbers to judge variance |

**Conventions.** Slugs are stable IDs — cite them, don't renumber. When an item lands, replace
its section with a one-line row in [Completed](#completed); the narrative belongs in the commit
message and, if it's durable, in the relevant `docs/*-engineering.md`. New follow-ups go here
rather than in a new doc.

---

## Performance

### `FU-PERF-BLACKOUT-LATENCY`

**Blackout lands up to one refresh interval late** · Ready · Continuous Art-Net streaming
(2026-08-18, `eb190ef`)

`GlobalScalerState` calls `DmxController.requestTransmit()` when Blackout or Grand Master is
toggled. Under continuous streaming that is a **no-op** — the change reaches the wire on the next
scheduled tick, and an out-of-band packet would push the universe above its configured rate.

At the 25 ms default that's imperceptible. The problem is the ceiling: `MAX_REFRESH_INTERVAL_MS`
is 1000 and is reachable from the universe chip, so an operator who slowed a universe for a fussy
node can hit Blackout mid-show and watch it stay lit for a full second. Blackout is a safety
control; the desk currently promises "up to 1 s" without saying so.

Three shapes, in order of preference:

1. **Lower the ceiling** to ~100 ms — one rule for all output, costs the slow-node case.
2. **Exempt modifier changes** — one out-of-band frame that re-bases the loop deadline, so the
   average rate holds. Needs care that Blackout spam-clicking can't become a packet flood.
3. **Clamp to 25 ms only while a modifier is engaged** — narrowest blast radius, but a rate that
   silently changes under you is its own surprise.

Not deferred for cost — (1) is a one-constant change. It's here because choosing is a product
call. See [dmx-engineering.md §Refresh interval](../dmx-engineering.md#refresh-interval).

### `FU-PERF-FRAME-TXN-UNIFY`

**FX beat + wall-clock frame-transaction unification** · Trigger · Control-surface Phase 8 step 2,
deferred 2026-04-23

The two FX tick loops (`processBeatTickSuspend` ~120 Hz, `processWallClockTickSuspend` 50 Hz)
each build their own `ControlTransaction` and commit independently within the same ~20 ms window
on the same universe.

Continuous streaming (2026-08-18) removed the packet-rate half of this: `ArtNetController` emits
exactly one frame per universe per `refreshIntervalMs` however many transactions committed. What
remains is purely value-domain — two transactions 5 ms apart write different values to the same
channel and the loop transmits whichever landed last. (The per-channel delta filter in
`sendCurrentValues()` no longer suppresses packets at all, only redundant WebSocket
notifications.)

Phase 8 sketched a `FrameTransaction`: the two loops share one `ControlTransaction` when their
tick times fall inside a configurable fuzz window (default 10 ms), else commit independently.
Needs an `AtomicReference<FrameTransaction?>` plus a short mutex around the open/close edge —
both loops run on `Dispatchers.Default`.

**Trigger** (either): an operator reports visible flicker or double-stepping where beat and
wall-clock effects share a universe; or a future effect category pushes wall-clock density high
enough that the tick windows overlap often (today's 50 Hz + ~120 Hz worst case doesn't).

*The old packet-rate trigger is obsolete — under continuous streaming the rate is
`1000 / refreshIntervalMs` by construction, so `GET /api/rest/perf/artnet-rates` now reads as a
configuration check, not a contention one.*

---

## Frontend polish

### `FU-FE-REBIND-INPLACE`

**In-place "Rebind" for dead assignments** · Trigger · Cue-authoring Phase 6, deferred 2026-04-22

`DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` ship one Remove button per dead row. The
plan wanted a Rebind quick-action opening a picker pre-populated with the dead assignment's
property + value; we shipped Remove-and-re-author instead.

**Trigger**: an operator asks for it. Implementation is unblocked — `PropertyChannelWriter`
(Phase 7) can drive live preview of the proposed rebind.

### `FU-FE-HEALTH-BADGE`

**Shared `<HealthBadge>` for `AssignmentHealth`** · Trigger · `moveInDark` row-list editor,
2026-04-25

`AssignmentHealth` renders in three places — `DeadAssignmentsBanner.tsx`,
`DeadPresetAssignmentsBanner.tsx`, `PropertyAssignmentsList.tsx`. All three use `describeHealth()`
from `lib/healthDescriptor.ts` for the label but wrap it in their own `<Badge>` / `<Alert>`.

**Trigger**: a fourth surface needs it (likely the cue detail sheet or the surface bindings list).
Three call sites with stable display patterns don't pay for the abstraction.

### `FU-FE-USE-TARGET-PROPERTIES`

**Shared hook for fixture/group property lookup** · Trigger · `moveInDark` row-list editor,
2026-04-25

`PropertyAssignmentsList.tsx::useTargetProperties` fetches a fixture's or group's properties via
`useFixtureListQuery` / `useGroupPropertiesQuery` and maps to a uniform shape. The same
fetch-and-map appears in `FixtureContent.tsx`, `GroupCard.tsx`, `PresetEditor.tsx`,
`PresetLivePreview.tsx` and the busking target panel, each re-doing the categorisation inline.
Extract `useTargetProperties(selection)` into `src/hooks/`, returning a flat `AvailableProperty[]`
plus a categorised variant for surfaces that need colour/dimmer/position grouping.

**Trigger**: a sixth consumer, or a property-shape change that forces a multi-file edit. Today's
implementations are stable, so pulling them together now is churn that risks visual regressions.

---

## Speed masters

### `FU-SPEED-SURFACE-TAP-LED`

**LED feedback for tempo tap buttons** · Trigger · Speed-master follow-ups (2026-08-14), v1 cut

`BindingTarget.SpeedMasterTap` deliberately gets no LED. Everything `SurfaceFeedbackPublisher`
indexes for LEDs (`Flash`, `Blackout`, `GrandMasterToggle`) reflects a **steady boolean it can
read back**; a tap is momentary with no "on" state, so blink-on-tap needs timer/debounce
machinery that exists nowhere else in that class. The nearest existing shape,
`FlashStateTracker`'s press/release pair, has a release half a tap doesn't.
`SurfaceFeedbackPublisherTest` pins the cut — update that test rather than deleting it.

**Trigger**: an operator taps from hardware and asks why the button doesn't acknowledge, or a
second momentary-with-no-steady-state target appears and the two can share the machinery.

### `FU-SPEED-RATEMASTER-STATEFUL`

**Rate masters are inert for STATEFUL wall-clock effects** · Trigger · Speed-master follow-ups,
2026-08-14

The rate master scales `accumulatedScaledMs`, which reaches an effect as its *phase*. STATEFUL
effects are driven by `deltaMs` and mostly ignore phase — both shipped wall-clock effects
(`CandleFlicker`, `FluorescentFlicker`) do — so assigning them a rate master is legal, persisted,
and silent.

The UI deliberately does **not** gate the picker on `effectMode`: `calculateWallClockPhase` runs
for every wall-clock effect, so a user `.fx.kts` that is STATEFUL *and* reads `phase` scales
correctly, and gating would hide a working control. Making it apply to `deltaMs` too would change
what `deltaMs` means for every stateful effect — a bigger decision than it looks.

**Trigger**: someone writes a stateful wall-clock effect and expects a rate master to speed it up.

### `FU-SPEED-PER-ATTRIBUTE`

**Per-attribute masters inside one FX instance** · Trigger · Programmer redesign §3.6, promoted
2026-08-14

`FxInstance.speedMasterUuid` is per *instance*, which already gives per-property speeds for
everything except composites — a position wave on master 2 and a dimmer chase on master 1 are
simply two instances. MA3 assigns masters per-attribute *inside* one phaser; here that means a
composite (`Effect.calculateComposite`, e.g. `LightningStrike`) whose outputs advance on
different clocks.

Cut deliberately: a composite computes every output from a single phase, and that coupling is why
it's a composite rather than N instances. Splitting it means `calculateComposite` taking a
per-output phase map, plus a picker that can address a constituent.

**Trigger**: an operator wants one shipped composite's constituents on different tempos and can't
express it as separate instances.

---

## Programmer

### `FU-PROG-L3RESOLVER-RENAME`

**Rename `Layer3Resolver` to `CueAssignmentResolver`** · Ready · Programmer redesign Session 1,
decision §5.9

The redesign renumbered the layer stack — cue property assignments are now **Layer 4** — but
`Layer3Resolver`, `Layer3Resolver.Key`, `LayerResolver.currentLayer3State`,
`republishLayer3Assignments` and `publishLayer3ToControllers` keep the old number. A deliberate
Session 1 cut (~25 files of mechanical churn mid-surgery); the KDoc on each names the mismatch.

Do it as its own commit, no behavioural change: `Layer3Resolver` → `CueAssignmentResolver`,
`currentLayer3State` → `currentCueLayerState`, `*Layer3*` internals to match. Grep docs
afterwards — the composition model doc points here.

### `FU-PROG-VIS-SOURCE`

**Stage3D vis-source selector** · Ready · Programmer redesign §3.7 / Session 3

Stage3D renders final merged DMX only. The proposal asks for a source selector: `Output` (today) /
`Output + Programmer` / `Programmer only` / `Next GO`.

- **`Output + Programmer`** is identical to `Output` unless Blind is on, so only the blind case
  needs work.
- **`Programmer only`** has an unused shortcut: `ProgrammerState.channels`
  (`src/api/programmerWsApi.ts`) already arrives on every `programmer.state` snapshot and is
  consumed by nothing. It refreshes on the 100 ms provenance debounce rather than at wire rate,
  and `ProgrammerApi` exposes no per-channel subscribe — a `subscribeToChannelValue` mirroring
  `channelsApi` is the missing piece.
- **`Next GO`** has no data behind it. There is no client-side cue resolver, and turning Layer 4
  assignments into channel values in the browser means reimplementing the backend merge. Needs a
  backend preview-compose endpoint or WS channel first.

Two notes for pickup: `FixtureModel`'s value reads all funnel through `getChannelValue` /
`useLiveColour`, so swapping source is largely "inject a value provider instead of importing
`lightingApi.channels`". And the preview pane is Chromium while the rig runs from Safari, so a
clean preview isn't proof for WebGL-adjacent changes.

### `FU-PROG-PER-USER`

**Per-user programmers** · Trigger · Programmer redesign §5 decision 2, promoted 2026-08-14

`ProgrammerStore` is a **single shared programmer** — a locked decision for a solo-operator
system, and why two browser tabs see each other's edits (the `provenanceState` broadcast tells
the second tab to re-read `programmer.state`). MA3 gives each user their own.

The store is keyed so the change is mechanical rather than structural: `Slot` already carries a
`ProgrammerOwner`, and a user dimension is another key component beside `(target, propertyName)`.
The expensive half is everywhere the programmer is *read* as a singleton — the blind gate, Clear,
Record/Include/Update, provenance colouring, the `programmer.*` fan-out — plus a merge rule for
two users holding the same property.

**Trigger**: two people programming one show at once — a second operator on a tablet whose edits
must not appear on the first's stage.

### `FU-PROG-STAGED-CLEAR`

**MA-style staged three-press Clear** · Trigger · Programmer redesign §5 decision 6, promoted
2026-08-14

Clear ships as one action with a fade time, plus clear-selected and the FX-vs-values split
(`ProgrammerToolbar` disables it only when both are empty). MA stages it: first press clears
selection, second values, third the whole programmer — muscle memory built for a hardware key,
which is why it was cut for a web UI where the three are separate controls anyway.

**Trigger**, as decision §5.6 named it: an operator reports clearing more than they meant to, or
reaches for Clear repeatedly to get a partial release.

### `FU-PROG-HIGHLIGHT-PERSONALITY`

**Highlight/lowlight personality values** · Trigger · Programmer redesign §6, promoted 2026-08-14

`useHighlight` (`lighting-react/src/components/fixtures-list/useHighlight.ts`) takes every
selected target's dimmer to full while held and restores on release. Consoles do more: a
per-fixture-type *highlight personality* — open the shutter, drop the gobo, open white, sometimes
centre the head — so a highlighted moving head is findable; plus **lowlight**, which dims
everything *not* selected.

Scope: a highlight-value set on the fixture type (natural home is the trait /
`FixtureTypeRegistry` layer that already knows a fixture has a shutter or colour wheel), with
`useHighlight` writing the whole set rather than one property. Its capture/restore machinery is
already per-property and per-target, so it widens rather than changes shape. Lowlight is the
cheaper half and needs no personality data — invert the target set and scale.

**Trigger**: an operator can't pick their highlighted head out of a wash, or asks for lowlight.

---

## Palettes

### `FU-PAL-PRESET-MAKE-HARD`

**Make Hard for FX preset assignments** · Ready · Programmer redesign Session 4, deliberately cut

Make Hard ships at programmer level (`POST /programmer/make-hard`) and cue level
(`POST /project/{id}/cues/{cueId}/make-hard`). Preset assignments can hold `ref:` values too —
`buildLayer3AssignmentsForPreset` resolves them per member — but have no equivalent, so a palette
referenced only from a preset can't be detached.

Cut rather than missed: preset property assignments are **target-less**. A cue row names the
fixture or group it applies to; a preset row says "whatever this preset is applied to", which at
hardening time is a set the preset doesn't know. So hardening means inventing a target set —
either the union of every current `CuePresetApplication`'s targets (wrong the moment the preset is
applied somewhere new) or a set the operator supplies (a larger UI). **Decide which before picking
this up**; the route is the easy part.

### `FU-PAL-POSITIONAL-CONVERSION`

**Convert `P1`/`P2` colour lists to named palettes** · Trigger · Programmer redesign §3.5 /
Session 4, deferred

Two unrelated things are called "palette": the positional ordered colour list that FX params index
as `P1`/`P2`/`P*` (global / stack / cue scopes, `PaletteCascade.effective`), and the Session-4
named `Palette` entity. Session 4 left the old one untouched and relabelled it **"Colour List"**
throughout the UI, removing the confusion without removing the duplication.

A conversion tool would take a cue's or stack's colour list and mint a named COLOUR palette per
entry, rewriting the FX params that index it. The hard part isn't the minting: a positional entry
is a bare colour with no fixture attached while a named palette entry is per-fixture, so the
conversion must choose which fixtures the new palette covers — the cue's targets is the obvious
answer and not always the right one.

**Trigger**: a show is found maintaining the same colours in both forms. Until then the two
coexist and the relabel carries the distinction.

### `FU-PAL-APPLY-NEAREST-COVERAGE`

**Fan a palette onto fixtures it doesn't cover** · Trigger · Programmer redesign Session 4, cut as
fuzzy

Applying a palette skips targets it holds no entry for and names them in a warning
(`paletteCoverageWarnings` in `lighting-react/src/components/palettes/applyPalette.ts`).
Skip-and-report is deliberate: resolving an uncovered fixture to its "nearest" covered neighbour
is guesswork — *plausible* for COLOUR (one colour usually suits a wash), actively wrong for
POSITION, and a rule that behaves differently per type is worse than no rule.

**Trigger**: an operator asks. If they do, scope it to COLOUR and make it an explicit gesture
("apply, filling gaps") rather than a mode on the existing one.

### `FU-PAL-LINKED`

**Linked palettes — a palette entry referencing another palette** · Trigger · Programmer redesign
§2, promoted 2026-08-14

A `Palette` entry holds a resolved `PropertyValue` per fixture. Consoles also let one palette
*reference* another, so "Warm Wash" can be defined as "House Amber" and follow it — the same
touring leverage the cue→palette reference gives, one level up.

Closer than it looks: `resolveAssignmentValueForFixture` is the single door for `ref:{uuid}` and
already runs before `parseAssignmentValue`, so a palette entry whose stored value is a `ref:`
resolves through the same path. Genuinely new: **cycle detection** (A → B → A refused at write
time, not discovered at 50 Hz), depth-bounded resolution inside `PaletteRegistry`'s version-counter
cache, republish-on-palette-edit walking the reference graph rather than one hop, and a
`PALETTE_IN_USE` delete guard that counts palette-to-palette references.

**Trigger**: a show keeps one palette hand-synced to another — the same signal
`FU-PAL-POSITIONAL-CONVERSION` waits on. If both fire, do the conversion first; it decides how
many palettes exist.

---

## Desk accounts

Everything here originates in the multi-user-auth plan, closed out 2026-08-17
([`completed/multi-user-auth-plan.md`](completed/multi-user-auth-plan.md)); the durable reference
is [`docs/desk-accounts.md`](../desk-accounts.md). The five Trigger items from
`FU-AUTH-ATTRIBUTION` down are that plan's §"Deliberately out of scope" list, promoted on
close-out. The desk is **locked** — not bootstrap-open — so `RESET-ADMIN` is the way back in if
the admin password is lost.

### `FU-AUTH-STALE-ANON-SOCKET`

**A bootstrap-open socket outlives the bootstrap** · Ready (small, but it's a security boundary —
decide deliberately) · Noticed implementing `FU-WS-USER-INVALIDATION`, 2026-08-17

The socket auth gate runs at **upgrade time only** (`plugins/Sockets.kt`: resolve the cookie,
close 4401 if `hasAnyUser` and there's no session). On a desk with zero accounts it admits
everyone, which is what makes a fresh install usable — but a socket admitted that way keeps full
access after the desk gains its first admin. Nothing re-checks it, and `scope.user` stays null for
the life of the connection.

The frontend closes the window by accident: completing setup calls `reconnect(true)` and the new
socket is gated properly. Not covered: a second tab, or any client already connected that never
re-handshakes. `FU-WS-USER-INVALIDATION` mitigated the visible half — an anonymous socket now gets
`ownAccountChanged` when the first account appears — but that is cache coherence doing a gate's
job.

The fix is two lines in the `userChanges` collector in `plugins/MachineSocket.kt`:

```kotlin
if (scope.user == null && scope.state.authService.hasAnyUser) {
    close(CloseReason(4401, "setup completed"))
}
```

Deliberately not folded into that change: closing a live socket is a different decision from
invalidating a cache, and it wants its own thought about setup-race ordering — the tab that
*performed* setup must not be closed out from under itself before its own cookie lands.

### `FU-AUTH-RESET-TOKEN-STALENESS`

**Reset-link history goes stale between admins** · Trigger · Scoped out of
`FU-WS-USER-INVALIDATION`, 2026-08-17

`userChanges` fires on **account-row writes**, and minting a reset token isn't one. With
`UserDetailSheet` open on user X, two things go unnoticed:

1. **Another admin mints a link for X** — the history misses the new PENDING row *and* the row it
   superseded (minting retires the previous one).
2. **Anything calling `cancelOutstandingResetTokens`** — `rotatePassword`, or
   `updateUser(disabled = true)` — silently turns a PENDING row CANCELLED.

Display-only: `ResetToken` (the QR sheet's own poll) still self-heals, so nobody acts on a dead
link. **Do not "fix" this by adding `ResetTokenList` to the WS bridge in `store/users.ts`** — that
bridge fires only on account writes, so it covers case 2 and misses case 1, which is worse than
covering neither because it looks solved. The honest shapes are a second flow keyed on token
writes, or a poll on the history list while the sheet is open.

### `FU-AUTH-SESSION-LIST-STALENESS`

**A new sign-in doesn't appear in the Devices list** · Trigger · Scoped out of
`FU-WS-USER-INVALIDATION`, 2026-08-17

Sign in on your phone and Profile → Devices doesn't notice until it refetches. Every *other* path
that changes a session list either revokes the observer (4401, panel goes with it) or is the
observer's own mutation, which already invalidates `AuthSessions` locally. The uncovered case is a
**new** session appearing.

Coupled to a deliberate omission and stands or falls with it: the only place to emit from is
`mintSession`, reached from `POST /auth/login` (auth-exempt, throttled) and
`POST /auth/device/{token}` (auth-exempt, LAN-public). Emitting there wires an **unauthenticated
request path to a fan-out across every connected client** — a new amplification surface on the two
endpoints that already needed `awaitThrottle` and `requireLanPeer`. Same reasoning is why
`lastLoginAtMs` in the users list is allowed to be stale.

If it becomes worth doing, the shape that avoids amplification is a *targeted* frame like
`ownAccountChanged` — only the signing-in user's own sockets — plus whatever rate limiting the
login throttle doesn't already give.

### `FU-AUTH-ATTRIBUTION`

**Per-user attribution of edits** · Trigger · Multi-user-auth §out of scope, promoted 2026-08-17

Nothing records *who* made a change. `users.uuid` exists partly so it can be referenced later, and
the auth half is already there — every gated request has an `AuthenticatedUser` in
`call.attributes`. `created_by` / `modified_by` on cues, presets and patches is the obvious shape.

Why it isn't an auth ticket: those tables are **portable show content**, so a user reference means
deciding what an attribution means on an install where that user doesn't exist. Users are
machine-local by requirement, so either the column carries a uuid that dangles after import (and
the UI renders "unknown user" gracefully), or the exporter denormalises a display name at write
time. That's a `formatVersion` question for `docs/sync-engineering.md` — see
`FU-SYNC-FORMAT-MIGRATIONS` for the framework it wants.

**Trigger**: two accounts routinely edit one show and someone asks who changed a cue — or any
`formatVersion` bump is already planned, in which case fold the column in rather than paying for a
second migration.

### `FU-AUTH-AUDIT-LOG`

**A `user_audit` table for logins, resets and user changes** · Trigger · Multi-user-auth §out of
scope, promoted 2026-08-17

Logins, failed logins, reset-token mints and redemptions, and user create/update/delete happen
with no durable trace — INFO logs and nothing else. A machine-local `user_audit` table (same
disposition as the other three, so it never leaves the desk) would make "did someone else sign in
as me?" answerable.

Two things narrowed the gap on 2026-08-17 without closing it: reset tokens keep a 30-day history
with the minting admin's name, and sessions record `created_via` so a QR sign-in is
distinguishable from a password one. **Device-login codes went the other way** — they live in
memory, so a mint and a redemption leave nothing after a restart. The one auth event that hands
out a session is the one with no durable trace at all, which is a decent argument for this item.

The write sites already exist and are all inside `AuthService`, the only class touching the auth
tables — so this is a table, a `record(...)` call per mutation, and a read surface. Settle two
decisions first: **retention** (an append-only table on a desk that runs for years wants a cap or
age prune, alongside `pruneExpiredSessionRows` / `pruneOldResetTokenRows`) and **where it
surfaces** — a tab beside Users is cheap, the cloud-sync activity-log feed is better integrated.

**Trigger**: someone wants to know who did something and the answer is "we can't tell" — or
`FU-SYNC-FETCHING-STATE`'s activity-log extension lands and auth events can ride the same feed.

### `FU-AUTH-WS-PER-MESSAGE`

**Per-message WebSocket authorisation** · Trigger · Multi-user-auth Decision 10, promoted
2026-08-17

The socket is authenticated at handshake and revoked live (`AuthService.revocations` closes it
4401), but **every message is available to any signed-in user**. An explicit decision, not an
oversight: the REST gate's admin-only surfaces (users, install `PUT`, cloud sync, OAuth) have no
socket equivalent, so today the socket carries nothing an operator shouldn't have.

That stops being true the moment an admin-only capability grows a WS command. The hook is in place
— `SocketScope` carries the resolved `AuthenticatedUser` (nullable, for a bootstrap-open desk) —
so the work is a per-message role check at the dispatch site plus a decision about what an
unauthorised message *answers* (an error frame, not a close: the socket is shared by every
subscription).

**Trigger**: an admin-only operation gains a socket command, or `FU-AUTH-OPERATOR-LOCKDOWN` lands
and a locked-down control is also reachable over WS — otherwise the two are the same change made
twice.

### `FU-AUTH-OPERATOR-LOCKDOWN`

**Admin-only lockdown of specific controls** · Trigger · Multi-user-auth §out of scope, promoted
2026-08-17

`OPERATOR` can do everything to show content: patch fixtures, delete projects, edit scripts. The
role split is deliberately coarse — two roles, no per-project or per-fixture permissions — because
the crew is familiar and physically present.

If that changes, `ADMIN_ONLY_PREFIXES` in `auth/AuthGate.kt` is the whole mechanism, and the shape
matters more than the list: the gate matches **routing-normalised path prefixes**, so anything
method-sensitive (as `PUT /api/rest/install` already is) needs a per-route `call.requireAdmin()`
instead — patch editing and script editing are both read-for-everyone, write-for-admin, so they'd
be the second and third instances of that pattern. Mirror any addition in
`lighting-react/src/navigation.ts` (`adminOnly` on the `NavItem`) so Cmd+K stops offering a page
that can only answer 403 — and remember that's presentation, never permission.

**Trigger**: an operator changes a patch or deletes a project and the desk owner asks for it to be
locked. Do it then, driven by the actual case — a speculative split risks locking out the person
who needs the control mid-show.

### `FU-AUTH-TLS-COOKIES`

**HTTPS, `Secure` cookies, and the CSRF answer** · Trigger · Multi-user-auth §out of scope,
promoted 2026-08-17

Sessions ride a cookie with `secure = false` over plain HTTP, deliberately: desks serve a LAN on
`:8413` and a `Secure` cookie would never be sent. Four things are load-bearing on that choice and
would move together:

- **The cookie flag** (`routes/auth.kt`), and whether `SameSite=Lax` stays the CSRF answer. It
  plus **every state-changing endpoint requiring a JSON body** is what stands in for a CSRF token,
  which holds because everything is same-origin. The second half is enforced, not incidental:
  `POST /auth/device/{token}` takes a body it barely reads for exactly this reason, and
  `plugins/ErrorHandling.kt` maps `CannotTransformContentToTypeException` to 400 so refusing a
  form post reads as a refusal. `DeviceLoginRoutesTest` pins it.
- **The QR URL scheme** (`auth/ResetUrls.kt`), which hardcodes the request's own scheme and falls
  back to `http://` for the mDNS/site-local alternates. A phone hitting a self-signed cert gets a
  warning interstitial — on flows whose whole point is getting someone onto the desk who can't
  currently get on it. **Two** flows share `buildLanUrls` now (`/reset/` and `/device/`).
- **The device-login LAN check** (`requireLanPeer` in `routes/auth.kt`), which trusts the socket
  peer precisely because no proxy sits in front. Terminating TLS anywhere but in the JVM means
  installing `ForwardedHeaders`, which silently turns both this check and the IP throttle beside
  it into client-supplied values.
- **Certificate provisioning** for a name a phone will accept — the actual hard part on a machine
  with an mDNS name and a rotating DHCP address.

**Trigger**: the desk is reachable from outside a trusted LAN (a venue network with guests counts),
or a browser starts refusing cookies on plain HTTP for this kind of origin. Not before — a
self-signed cert would degrade the reset flow while adding no real confidentiality against anyone
already on the LAN.

---

## Distribution

### `FU-DIST-ICONS`

**Real macOS / Windows installer icons** · Ready · Windows-distribution Phase 3, 2026-04-28

`packageMac` / `packageWindows` pass `--icon` only when `assets/lighting7.icns` or
`assets/lighting7.ico` exists. Neither does, so jpackage falls back to the default Java cup icon
for dock / taskbar / installer.

Design or commission a 1024×1024 source PNG, then generate `assets/lighting7.icns` (`iconutil`
from an `iconset` directory) and `assets/lighting7.ico` (ImageMagick `convert`, multi-resolution:
16/32/48/64/128/256). Drop both at `assets/`; both package tasks pick them up with no Gradle
change. `launcher/src/main/resources/lighting7.png` is the tray-icon placeholder — too small and
not OS-icon-shaped; inspiration, not source.

### `FU-DIST-NO-BUNDLED-JRE`

**Download the JRE at install time instead of bundling it** · **Rejected — decision record, do not
re-propose** · MSI slimming, 2026-08-18

Recurring suggestion: drop the ~59 MB `runtime/` and fetch a JRE on first run. Four independent
reasons not to, any one sufficient:

1. **It inverts a stated product goal.**
   [`completed/windows-distribution-plan.md`](completed/windows-distribution-plan.md) line 19
   defines the deliverable as launching the whole stack with "no external dependencies — no
   Postgres, no Docker, no node, **no JDK on the target machine**".
2. **It's a launcher rewrite, not a jpackage flag.** `resolveJavaExecutable()` in
   `launcher/.../LauncherMain.kt` resolves only `System.getProperty("java.home")` + `/bin/java` —
   no system-JVM discovery, no version gate, no fallback. A download/verify/unpack/pin component
   would have to exist and be correct before the first launch.
3. **A venue desk may have no internet when it is installed.** "The lighting desk won't start" is
   the worst possible moment to discover a missing prerequisite, and unlike a slow download it is
   not recoverable in the room.
4. **The MSI is unsigned and already trips SmartScreen.** A first-run downloader that fetches and
   executes a JVM makes that story strictly worse.

The defensible half — *trimming* the bundled runtime rather than removing it — was done:
`--include-locales` took 10.5 MB off. What remains is 1 MB; see `FU-DIST-JLINK-MODULES`.

### `FU-DIST-JLINK-MODULES`

**Replace the `java.se` aggregate with an explicit module list** · Trigger · MSI slimming,
2026-08-18

**Trigger**: only if the runtime needs to shrink — 1 MB is not a reason on its own. (The
`FU-DIST-KCS-RETIRE` precondition is met; the old jdeps workaround for the fork's `BOOT-INF/lib/`
layout no longer applies.)

Measured at **1 MB** of image (51 → 50 MB on JDK 26). Everything the surgery removes is `java.se`'s
small transitive tail — `java.xml.crypto` 0.66, `java.security.jgss` 0.55, `java.rmi` 0.22,
`java.sql.rowset` 0.20, `java.management.rmi` 0.08 MB — and it's all-or-nothing, because `java.se`
`requires transitive` each of them.

Candidate list: `java.base, java.compiler, java.datatransfer, java.desktop, java.instrument,
java.logging, java.management, java.naming, java.net.http, java.prefs, java.scripting,
java.security.sasl, java.sql, java.transaction.xa, java.xml, jdk.crypto.ec, jdk.localedata,
jdk.unsupported, jdk.zipfs`.

Non-negotiable, with the API forcing each: **`java.desktop`** (AWT `SystemTray`,
`javax.sound.midi`; drags `java.datatransfer` + `java.prefs`), **`java.sql`** (JDBC; drags
`java.xml` + `java.logging`), **`java.management`** and **`java.naming`** (reached *reflectively*
by Spring Boot and logback), **`java.net.http`** (the launcher's readiness probe and the update
checker), **`jdk.crypto.ec`** (TLS to GitHub).

Derive with `jdeps --multi-release 24 --ignore-missing-deps --print-module-deps` over
`lighting7.jar` and `launcher.jar`, unioned with the baseline above. **jdeps output is a floor,
never an answer** — reflection, `Class.forName`, `ServiceLoader` and JNDI are invisible to it,
which is exactly how the four reflective modules get reached. One-off measurement, not build
machinery.

**Gate before committing**: temporarily add `jvmArgs("--limit-modules", <list>)` to `tasks.test`
and run the whole suite — it simulates the trimmed runtime on a full JDK and exercises far more
than a boot smoke test. Then exercise each native-payload feature on a real install — DB
connection, MIDI enumeration, coremidi4j on Mac, and a keychain write via JNA. The failure mode is
a `NoClassDefFoundError` at the first use of one feature, which for a lighting desk can be
mid-show.

Note `jdk.charsets` (1.63 MB) and `jdk.management` are **already absent** — `java.se` never
included them and the shipped desk works. Don't add them speculatively. And `jdk.localedata` stays
in the list: the shipped 10.5 MB saving is `--include-locales=en-GB,en-US`, a locale **filter**, not
a module removal. `FormatData_en_GB` lives in `jdk.localedata` rather than `java.base`, so dropping
the module silently switches every server-side `en_GB` date, number and currency to US forms —
wrong output, no exception.

### `FU-DIST-NATIVE-ARCH`

**Key the native excludes by architecture as well as OS** · Trigger · MSI slimming, 2026-08-18

**Trigger**: the MSI needs a final couple of MB, or a second architecture ships.

~2.9 MB more on Windows. `nativePayloads` in `build.gradle.kts` is keyed by OS only, so the
Windows jar keeps all four sqlite arches (`x86_64`, `x86`, `aarch64`, `armv7` — 3.7 MB) and all
three `com/sun/jna/win32-*` arches, though the MSI is x64.

Watch the hyphen when writing patterns: `com/sun/jna/win32-x86-64/` is a native payload, while
`com/sun/jna/win32/` is a Java package of 12 `.class` files that must always ship. The trailing
hyphen in the JNA prefixes is the only thing drawing that line.

Deferred deliberately: a second axis on the table for a third of what the OS key gave, and it
would arch-lock the jar in a way the `-windows-x64` filename only implies. If done, keep
`win32-x86-64` **and** the x64 sqlite dll — `docs/windows-updates.md` notes x64-under-emulation on
Windows-on-ARM as supported, and the readiness timeout override exists for it.

---

## Cloud sync

### `FU-SYNC-TOMBSTONE-GC`

**Tombstone garbage collection** · Trigger · Cloud-sync Phases 7–8, deferred 2026-05-01

`tombstones/{tableName}/{uuid}.json` files (~25 bytes each) plus their `sync_state` rows
accumulate forever. GC is tree-size optimisation, not correctness — but the *safety analysis* is
the load-bearing part.

**Why pure age-based GC is unsafe.** A naive 90-day cutoff resurrects records on installs offline
longer than the cutoff: an install catching up after 6 months sees `live record locally, no remote
file, no sync_state row` and treats it as brand new, pushing it. That's the exact resurrection the
tombstone prevented — see
[`RemoteSyncEngineTombstonePropagationTest`](../../src/test/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngineTombstonePropagationTest.kt).

**Safe shape.** Extend `installs.json` so each install records the commit SHA and timestamp it
last synced to. GC only tombstones whose path's last touch
(`git log -1 --format=%ct -- tombstones/{table}/{uuid}.json`) predates the *oldest* install's
last-synced timestamp. That's a `formatVersion` bump (new required field on the install registry)
plus a migration for in-flight repos. Simpler escape hatch: drop installs from `installs.json`
after N months without a push, GC below every remaining install's low-water mark, and document
that reviving a long-dormant install needs a clean clone.

**Where it lives.** A `SyncMaintenance` coroutine in
[`sync/`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/), started from
`Application.module()` parallel to `AutoSyncScheduler`, ticking daily over every
`sync_configs.enabled = true` project — not just auto-sync ones, since manual-sync projects
accumulate tombstones too. A manual
`POST /api/rest/project/{id}/sync/maintenance/gc-tombstones` surfaces the same operation for
operators and tests.

**Ordering is an invariant.** Per project: (1) snapshot a commit that `git rm`s the tombstone,
(2) drop the matching [`sync_state`](../../src/main/kotlin/uk/me/cormack/lighting7/models/syncState.kt)
row. Reverse order leaves a snapshot with no `sync_state` row, re-arming carry-forward
(`SnapshotEngine.snapshot` only writes tombstones for records that *have* one), so a peer behind
would resurrect the deletion.

**Trigger** (any): `find tombstones -type f | wc -l` exceeds ~1000 on a real working tree; an
operator reports tree-size pain or `git gc` reclaiming nothing; or a delete-heavy migration is
planned and the operator wants a clean tree before pushing.

### `FU-SYNC-JGIT-STRESS-BENCH`

**JGit memory stress benchmark** · Trigger · Cloud-sync §Risks, revisited 2026-05-01

The design doc flagged "JGit memory on large projects with thousands of cues" as a Phase-5
stress-test item; Phases 5–8 landed without a fixture. The dominant cost during `runSync` is
[`JGitClient.walkTree`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt)
materialising every blob into a `Map<String, String>` — bounded by ~2× the working tree's blob
size resident at peak. Invisible at real deployment scale (tens to low hundreds of cues); the risk
is the speculative "thousands" tier.

**Harness shape**: a new test under
[`src/test/kotlin/.../sync/`](../../src/test/kotlin/uk/me/cormack/lighting7/sync/) seeding
N cues × M stacks × K assignments at ~5 KB per cue JSON (N = 1000 / 5000 / 10000); a full
`RemoteSyncEngine.runSync` against a local bare repo (same pattern as `JGitRemoteTest`, no
network); peak heap via `totalMemory() - freeMemory()` at phase boundaries (post-fetch, -diff,
-apply, -push) or a JFR recording; one `[stress]` log line per size with peak heap, wall-clock and
allocations. Gate on `-Dsync.stress=true`, mirroring the `dmx.benchmark` / `fx.benchmark`
precedent in `build.gradle.kts`.

**Likely fixes if a threshold is hit**: stream blobs through `walkTree` instead of materialising
every body, or shallow-clone the working tree if push-history walking isn't needed. Both are real
engineering, hence not pre-empted.

**Trigger** (any): a real project hits ~1000 synced records; `runSync` exceeds 5 s; or sync OOMs
in the field. The speculative threshold sits in
[`sync-engineering.md`](../sync-engineering.md) §Operational notes.

### `FU-SYNC-STREAMING-PROGRESS`

**Streaming sync progress (fetch %, conflict count)** · Trigger · Cloud-sync §Risks, revisited
2026-05-01

`runSync` emits exactly one terminal WS message per cycle: `cloudSyncDone` / `cloudSyncFailed` /
`cloudSyncConflictsPending`. Intermediate progress was punted on the basis that data volumes don't
justify the ceremony, and for typical projects the whole `cloudSyncStarted → cloudSyncDone`
interval is sub-second.

**Trigger**: an operator reports the sync UX feels unresponsive, **or** a real project's cycle
consistently exceeds 5 s. Either means someone is staring at a non-progressing spinner. No design
sketch needed pre-emptively — the wire shape follows the actual unmet case.

### `FU-SYNC-MERGE-ATOMICITY`

**Reset-before-import window in the auto-merge path** · Trigger · Surfaced 2026-07-23 fixing the
sibling fast-forward bug

The **fast-forward path is fixed**: `RemoteSyncEngine.fastForwardTo` now imports the remote tree
into the DB first (from a scratch materialisation read via `JGitClient.walkTree`) and only advances
HEAD once the import commits, so a failure leaves DB-ahead-of-git, which the next sync reconciles
to a content-identical no-op merge.

The **auto-merge path shares the hazard and is not fixed**.
[`RemoteSyncEngine.autoMerge`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngine.kt)
does `resetHard(remoteRef)` → overlay local-wins files → `replaceFromWorkingTree` →
`commitWithParents` → push. A crash between the `resetHard` and the DB import commit leaves HEAD
at the remote tip with a pre-merge DB; the next snapshot commits the stale DB as a child of the
remote tip (`LocalAhead`) and pushes it, reverting the merge. The `Diverged`-with-zero-conflicts
case has no session at all; the apply-from-session case is only partially covered (a crashed
`APPLYING` session is demoted to `FAILED` by `ConflictSession.recoverFromCrash`, but the DB/git
inconsistency still relies on the operator aborting).

The FF import-first reorder is the model, but auto-merge is harder: it must also produce the
two-parent merge commit (tree = merged, parents = `[remoteTip, localSha]`), so the reorder has to
build the merged tree in scratch, import it, *then* reset + re-stage + commit. The merge-commit
plumbing wants its own test pass, which is why it wasn't bundled with the FF fix.

**Trigger** (either): a field report of a merged or pulled change reverting after a crash or failed
`runSync`; or any work adding a mid-`autoMerge` failure seam (see `FU-SYNC-PUSHRETRY-TEST-SEAM`) —
fold the fix in while the seam is fresh.

### `FU-SYNC-ORDINAL-DOUBLE`

**Double-precision ordinals for concurrent inserts** · Trigger · Cloud-sync §Ordering; unshipped
through Phase 8, re-logged 2026-07-23

The design (and `sync-engineering.md` §"Ordinal contract") specifies that Phase 5 replaces
`sortOrder: Int` with `ordinal: Double` on the ten ordered tables, so two installs inserting into
the same position pick a midpoint instead of colliding, tiebroken by UUID. **Not implemented** —
`sync/dto/SyncDtos.kt` still carries `sortOrder: Int`. (Exports already sort by `(sortOrder, uuid)`
for deterministic output, which is the pre-work; the type change never landed.)

Consequence: two installs each inserting a cue "between 5 and 6" isn't a conflict (distinct UUIDs)
but both land on the same integer `sortOrder`, so merged ordering is decided only by the UUID
tiebreak — non-deterministic from the operator's point of view, and integer renumbers can cascade.
Fine for solo-multi-machine use; bites once two people routinely reorder the same stack.

**Shape**: `ordinal: Double` column + one-shot migration (renumber to `1.0, 2.0, …`), DTO field
swap, `formatVersion` bump (→ 4) with a v3→v4 reader (see `FU-SYNC-FORMAT-MIGRATIONS`), and
midpoint-pick insert logic in the routes creating ordered rows.

**Trigger**: two installs report cues/stacks landing in a surprising order after a merge, **or** a
`formatVersion` bump is already planned — do it in the same bump.

### `FU-SYNC-FORMAT-MIGRATIONS`

**Repo-format migration framework** · Blocked (needs a real breaking bump) · Cloud-sync §Format
versioning; re-logged 2026-07-23

Both the design and `sync-engineering.md` reference migrations at
`sync/migrations/V{n}_to_V{n+1}.kt`, run on pull before the three-way diff. **The directory does
not exist.** Today an imported or pulled repo whose `formatVersion` differs from `3` is
hard-rejected (HTTP 422 in `ProjectImporter.loadAndValidateArchive`, both too-old and too-new).
The v2→v3 jump was handled by rejecting old repos outright, on the rationale that the project
hadn't shipped beyond the dev box.

That shortcut stops being acceptable once >1 install exists in the field and a breaking bump
ships: without a reader for the previous version, every peer must upgrade in lockstep or lose
access to the repo.

**Shape**: a `SyncMigrations` registry keyed by source version, a `migrate(sourceDir,
fromVersion)` step invoked from both import entry points before validation/diff, and per-version
transformers. The `formatVersion.json` `minReader` field already exists to gate truly-breaking
changes.

**Unblock when**: the next `formatVersion` bump is planned (e.g. `FU-SYNC-ORDINAL-DOUBLE`, or
re-nesting cue children under their stack folder), **or** a second install needs to read a repo
written by a newer one.

### `FU-SYNC-FETCHING-STATE`

**Observe crash-mid-fetch via a `FETCHING` session** · Trigger · Cloud-sync Phase 6 deferral;
re-logged 2026-07-23

`SessionState.FETCHING` is on the enum and documented as reserved, but nothing writes it. `runSync`
opens no session until conflicts are found, so a crash during fetch/classify/snapshot leaves no
row — the mutex releases and the next run starts clean. That's *safe* (the FF and auto-merge
reorder work covers the DB/git corners) but invisible: no audit breadcrumb, no "a previous sync was
interrupted" in the UI.

**Shape**: open a `FETCHING` session at the top of `runSync`, transition it through the existing
states, and extend `ConflictSession.recoverFromCrash` to reap stale `FETCHING` rows on startup
(log + drop, since nothing was applied).

**Trigger**: an operator reports a sync that "just stopped" with no log trail, **or** the
activity-log feed is being extended and a `SYNC_INTERRUPTED` breadcrumb would be cheap alongside.

### `FU-SYNC-FIELD-LEVEL-MERGE`

**Field-level conflict granularity** · Trigger · Cloud-sync three-way-diff design; re-logged
2026-07-23

Conflicts are detected and resolved at **whole-record** granularity — the diff hashes each
record's entire canonical JSON. Two installs editing *different fields* of the same cue (one the
name, one the fade time) is an `EDIT_EDIT` the operator must resolve by picking a whole side or
hand-merging via MANUAL, even though a field-level merge would be unambiguous.

Fine at current scale; friction if two people routinely co-edit the same records. A field-level
three-way merge (diff key-by-key, auto-merge disjoint edits, surface only same-field
disagreements) would cut the conflict rate but adds real complexity to `ThreeWayDiff`, the
resolution model and the conflict UI.

**Trigger**: operators report frequent conflicts on records where the two sides "obviously"
touched different fields.

### `FU-SYNC-MANUAL-MULTIFILE`

**MANUAL resolution for scripts + richer editors** · Trigger · Cloud-sync Phase 6/7; re-logged
2026-07-23

MANUAL resolution only works on records serialising to a single file. `isManualEditAllowed`
returns false for multi-file records — currently just `scripts` (`scripts/{uuid}.kts` +
`scripts/{uuid}.meta.json`) — and for `DELETE_EDIT` conflicts. So a script edited on both sides can
only be resolved whole-side; the user can't hand-merge the body. The conflict DTO carries
`manualEditAllowed` so the UI hides the option cleanly, but the capability gap remains.

**Shape**: a multi-file-aware MANUAL editor (body textarea + meta fields, or two panes) and a
resolve/apply path accepting a per-file payload map instead of a single `manualValueJson`. Worth
pairing with any broader "richer per-table conflict editors" work.

**Trigger**: an operator hits a same-script conflict and wants parts of both sides, **or**
conflict-resolution UX is being reworked anyway.

### `FU-SYNC-PUSHRETRY-TEST-SEAM`

**Deterministic push-retry / merge-failure coverage** · Trigger · `sync-engineering.md`
§"Push-rejected retry → Coverage limitation"; re-logged 2026-07-23

Fully-deterministic coverage of the push-rejected retry path (and the mid-`autoMerge` window from
`FU-SYNC-MERGE-ATOMICITY`) needs a seam to inject a peer push or a failure between our fetch and
our push. The synchronous IO block in `RemoteSyncEngine` has none, so
`RemoteSyncEnginePushRetryTest` exercises the path opportunistically via concurrent `runSync` calls
against a shared bare repo and asserts *eventual consistency* rather than the retry counter or a
specific interleaving.

**Shape**: extract a `JGitClient` interface (the wrapper is already a single object — mechanical)
so tests can substitute a fake that fails or steps the remote at a chosen point. Unlocks
counter-level assertions on the retry budget and a real test for the reset-before-import windows.

**Trigger**: `FU-SYNC-MERGE-ATOMICITY` is picked up (this is a prerequisite for testing that fix),
**or** a push-retry regression is suspected and the non-deterministic test can't pin it.

### `FU-SYNC-BINDING-PAYLOAD-UUIDS`

**Control-surface binding targets address rows by integer id** · Trigger (correctness, latent) ·
Code review of the project-clone rewrite, 2026-07-27

`control_surface_bindings.targetPayload` serialises `midi.BindingTarget` verbatim, and the
cue-facing variants carry **integer row ids** — `FireCue(cueId: Int)`,
`CueStackGo/Back/Pause(stackId: Int)`. The exporter writes the payload as an opaque string, so
those ids cross project and install boundaries unchanged. Nothing can translate them:
`ExportUuidRemapper` only substitutes UUID-shaped strings and by design knows no field schemas.

Consequences, increasing in severity:

* **Clone** (`sync/ProjectCloner.kt`): a cloned project's cue/stack bindings point at the *source*
  project's rows. `State.buildBindingHealthContext` resolves them against the clone's own ids, so
  each evaluates to `MissingCue` / `MissingStack` and `SurfaceInputRouter` drops the press. The
  clone's MIDI surface is dead until rebound — loud rather than silent, but wrong.
* **Import on another install**: same failure, except the stale id may coincidentally *exist* and
  name an unrelated cue, so a button fires the wrong look instead of nothing.
* **Pre-init window**: `buildBindingHealthContext` returns null before the show is initialised and
  health defaults to `Ok`. A press in that window dispatches `CueStackManager.fireCue`, which does
  `DaoCue.findById(id)` with **no project check** — firing another project's cue.

**Shape** — two independent pieces:

1. Make `BindingTarget`'s cue/stack variants carry UUIDs (or have `ControlSurfaceBindingJson`
   translate id ↔ uuid at the export/import boundary, leaving the runtime type alone). Either way
   it's a `formatVersion` bump plus a payload migration, and it fixes clone and cross-install
   import together.
2. Independently, project-scope the lookups in `CueStackManager.fireCue` and its stack equivalents
   so a stale id can never reach another project's row. Worth doing on its own merits.

**Trigger** (any): an operator reports dead or wrong-cue MIDI bindings after a clone or import; any
work touching `BindingTarget`, `ControlSurfaceBindingJson` or the binding-health context (fold it
in rather than adding another id-addressed variant); or a `formatVersion` bump lands for another
reason.

---

## Testing

### `FU-TEST-FX-BENCH-CI-GATE`

**`FxEngineBenchmark` CI regression gate** · Trigger · Cue-authoring Phase 5, deferred 2026-04-22

The benchmark ships track-only; the plan called for a fail-on-regression gate at ±20% against a
committed baseline.

**Trigger**: collect a week of baseline numbers across dev and CI hardware first. 20% is a guess —
real tolerance depends on how jittery the allocation counter and `measureNanoTime` are on the
actual runner, and without that study a fixed threshold either flakes constantly or catches
nothing.

### `FU-TEST-MULTI-CONN-CUEEDIT`

**Multi-connection cueEdit conflict** · Blocked · Control-surface Phase 6

The plan defers to cue-authoring's "reject-second-`beginEdit`" conflict resolution, but no Phase 6
test covers two WS connections racing on `beginEdit` for surface routing.

**Unblock by**: confirming the exact semantics with cue-authoring, then adding the test.

---

## Completed

One line each: slug, what shipped, commit. Full narratives live in the commit messages and in this
file's git history; durable mechanism notes belong in `docs/*-engineering.md`.

### 2026-08

- `FU-DIST-KCS-RETIRE` — compiler-server fork retired, editor served in-process at
  `/script-editor/*`; ~122 MB off the installer — `0199762`
- `FU-DIST-KCS-LIB-PRUNE` — moot: the staged playground jars went with the fork — `0199762`
- `FU-DIST-EDITOR-JAR-CLASSES-ONLY` — moot: `compilerServerLightingJar` no longer exists —
  `0199762`
- `FU-DIST-KCS-SKIP-KLIB-DOWNLOAD` — moot: no fork left to download klibs — `0199762`
- `FU-WS-USER-INVALIDATION` — account and install edits broadcast over a machine-scoped
  `SharedFlow`, not `FixturesChangeListener` — `0730295` + lighting-react `b3c4645`
- `FU-AUTH-PROFILE-SHEET` — four-tab profile sheet and `PUT /auth/profile`, any role — `bbb1a87`
  + lighting-react `3dfee11`
- `FU-AUTH-SELF-ROLE-GUARD` — a self role change answers 409 `SELF_TARGET` — `631a94f` +
  lighting-react `81b3fd9`
- `FU-AUTH-SELF-RESET-GUARD` — reset-token mint refuses your own account — same commits
- `FU-AUTH-RESET-TOKEN-HISTORY` — reset links survive sheet close, 30-day polled history — same
  commits
- `FU-AUTH-LOGIN-QR` — QR sign-in on a phone, in-memory codes, six revocation interlocks — same
  commits
- `FU-PROG-RECORD-SELECTION-SCOPE` — Record scoped to selected fixtures, list selection moved into
  the store — `6b40950` + lighting-react `f2c2a47`
- `FU-SPEED-RATEMASTER-UI` — rate-master picker on every FX authoring surface — `9541322`
- `FU-SPEED-MIDI-BINDING` — `SpeedMasterBpm` / `SpeedMasterTap` binding targets — `9541322`
- `FU-SPEED-BEATINDICATOR-PERMASTER` — `BeatIndicator` pulses from the keyed `speedMasters.beat`
  stream — `9541322`
- `FU-PROG-PROVENANCE-STACKID` — CUE provenance entries carry `cueStackId` — `714d742`
- `FU-PROG-EFFECTSREMOVED-FIELD` — dropped the always-zero `ToggleLocateResponse.effectsRemoved` —
  `d8fa43c`

### 2026-04

- `FU-BE-MOVE-IN-DARK` — Layer 3 position snap during an outgoing fade, authored per cue row —
  `0593d81`
- `FU-PERF-COALESCE-WRITES` — cancelled: profiled at p99 2.1 ms, well under the 5 ms bar
  (`CueEditProfileTest`)
- `FU-PERF-HEX-FORMAT-ALLOC` — cancelled with it: no measurable colour-vs-slider gap to chase
- `FU-TEST-DMX-FX-BENCH-HARNESS` — `AsyncTestDmxController` + `BenchmarkSetValues`, opt-in via
  `-Ddmx.benchmark=true` — `6e1222e`
- `FU-BE-PALETTE-CASCADE` — `PaletteCascade(preset, cue, global)` threaded through every Layer 3
  build site — `3181784`
- `FU-BE-PRESET-FIXTURE-TYPE-NOTNULL` — `fx_presets.fixture_type` made NOT NULL with a migration —
  `83ae4d3`
- `FU-BE-TIMED-PRESETS-LAYER3` — timed presets append and retract Layer 3 rows per fire — `ce5304c`
- `FU-FE-EXT-COLOUR-CHANNELS` — W/A/UV sliders in `ColourPickerPopover` — lighting-react `53a96a0`
- `FU-TEST-PROJECT-SWITCH-CUEEDIT` — test that a project switch clears the cue-edit session cache —
  `ef3cf29`
- `FU-BE-GROUP-LAYER3-ROUNDTRIP` — `captureCurrentState` preserves group-scoped Layer 3 shape —
  `ff578a9`
- `FU-PERF-REGISTRY-INDICES` — secondary indices on the cue-edit session and feedback registries —
  `672c139`
- `FU-FE-PRESET-LIVE-PREVIEW` — preset draft preview endpoints and panel — `bb24302` +
  lighting-react `9929274`
- `FU-QUAL-PUSHDOWN-SESSION-ROUTING` — cue-edit session routing pushed into
  `DefaultSurfaceActions` — `379a845`
- `FU-QUAL-TARGET-REF-SEALED` — `sealed class TargetRef` replaces stringly-typed target pairs —
  `8161820`
- `FU-FE-PICKER-UX-POLISH` — a preselected target skips the picker's first step — lighting-react
  `e97a664`
- `FU-BE-PRESET-PER-ELEMENT` — nullable `element_key` on preset assignments, resolved per element —
  `0106ab4`
- `FU-QUAL-KEY-CONVERGENCE` — `Layer3Resolver.Key` carries a `TargetRef`; `AssignmentKey` deleted —
  `30dd0fc`
- `FU-BE-SCALER-PERSISTENCE` — `project_scaler_states` persists Blackout / Grand Master per project
  — `7bcd109`
- `FU-PERF-FX-TICK-ALLOCS` — beat-tick p50 and per-tick allocation both ~45% down — `a0d5a8c`
- `FU-PERF-INSTRUMENT-ARTNET` — `PacketRateCounter` + `GET /perf/artnet-rates` — `0d19fad`
- `FU-TEST-HTTP-ROUNDTRIP` — `RouteIntegrationTest` harness on embedded Postgres — `4245a7d`
- `FU-PERF-INSTRUMENT-CUEEDIT` — `LatencyHistogram` + `GET /perf/cueedit-histogram` — `1607d91`
- `FU-PERF-INSTRUMENT-MIDI` — per-stage MIDI latency and CC rates + `GET /perf/midi-latency` —
  `b01d4b3`
- `FU-FE-PERF-DASHBOARD` — `/diagnostics` route over all three perf endpoints — lighting-react
  `73f11bb`
- `FU-TEST-COREMIDI-INIT-DEADLOCK` — `State.shutdown()` and a listener-before-start reorder —
  `19fa952`
- `FU-TEST-VITE-BUILD` — validated: `npm run build` clean on Node 24.12 LTS
