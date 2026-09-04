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
| [`FU-AUTH-STALE-ANON-SOCKET`](#fu-auth-stale-anon-socket) | Ready | Auth | — |
| [`FU-DIST-ICONS`](#fu-dist-icons) | Ready | Dist | — |
| [`FU-SYNC-FORMAT-MIGRATIONS`](#fu-sync-format-migrations) | Blocked | Sync | a real breaking `formatVersion` bump |
| [`FU-DIST-NO-BUNDLED-JRE`](#fu-dist-no-bundled-jre) | Rejected | Dist | decision record — do not re-propose |
| [`FU-PERF-FRAME-TXN-UNIFY`](#fu-perf-frame-txn-unify) | Trigger | Perf | visible flicker where beat + wall-clock share a universe |
| [`FU-FX-ELEMENT-BUNDLED-COLOUR`](#fu-fx-element-bundled-colour) | Ready | Perf | — |
| [`FU-TMPL-REWARM-BOUND`](#fu-tmpl-rewarm-bound) | Trigger | Perf | a template list change visibly stalls a template-heavy show |
| [`FU-FX-TICKFLOW-UNUSED`](#fu-fx-tickflow-unused) | Ready | Perf | — |
| [`FU-PERF-FXSCRIPT-CACHE-BOUND`](#fu-perf-fxscript-cache-bound) | Trigger | Perf | metaspace/classloader growth that tracks FX editing, not show size |
| [`FU-FE-REBIND-INPLACE`](#fu-fe-rebind-inplace) | Rejected | FE | decision record — no surface hosts it |
| [`FU-FE-HEALTH-BADGE`](#fu-fe-health-badge) | Trigger | FE | a 2nd surface renders `AssignmentHealth` |
| [`FU-FE-USE-TARGET-PROPERTIES`](#fu-fe-use-target-properties) | Trigger | FE | a 6th consumer of fixture/group property lookup |
| [`FU-FE-REVISION-REFETCH-DEDUP`](#fu-fe-revision-refetch-dedup) | Trigger | FE | a 4th broadcast needs revision-gated refetch coalescing |
| [`FU-FE-REVISION-NAME-CLASH`](#fu-fe-revision-name-clash) | Ready | FE | — |
| [`FU-FE-DBO-INERT`](#fu-fe-dbo-inert) | Ready | FE | — |
| [`FU-FE-SHARED-LOOK-EDIT-GUARD`](#fu-fe-shared-look-edit-guard) | Ready | FE | — |
| [`FU-SPEED-SURFACE-TAP-LED`](#fu-speed-surface-tap-led) | Trigger | Speed | operator wants tap confirmation on the surface |
| [`FU-SPEED-CUSTOM-RATIO`](#fu-speed-custom-ratio) | Trigger | Speed | an operator asks for a ratio beyond the five chips |
| [`FU-SPEED-SCRIPT-RAW-CLOCK`](#fu-speed-script-raw-clock) | Trigger | Speed | a script retunes a clock and surfaces show stale tempo |
| [`FU-SPEED-LINK-PUT-STALE-BPM`](#fu-speed-link-put-stale-bpm) | Trigger | Speed | a client renders a link PUT's response without the WS state |
| [`FU-SPEED-RATEMASTER-STATEFUL`](#fu-speed-ratemaster-stateful) | Trigger | Speed | a stateful wall-clock effect wants a rate master |
| [`FU-SPEED-PER-ATTRIBUTE`](#fu-speed-per-attribute) | Trigger | Speed | a composite needs split tempos |
| [`FU-BUSK-MOMENTARY`](#fu-busk-momentary) | Trigger | Busk | an operator asks to flash a pad rather than latch it |
| [`FU-BUSK-PAGE-MIDI`](#fu-busk-page-midi) | Trigger | Busk | an operator wants to change busk page from hardware |
| [`FU-BUSK-AI-LAYOUT`](#fu-busk-ai-layout) | Trigger | AI | a prompt asks the AI to put something on a busk page |
| [`FU-BUSK-PAD-SIZE`](#fu-busk-pad-size) | Trigger | Busk | a page needs more density than width and flow give |
| [`FU-BUSK-EDIT-CONCURRENCY`](#fu-busk-edit-concurrency) | Trigger | Busk | two desks edit one busk page at once |
| [`FU-BUSK-ON-PAGES-HINT`](#fu-busk-on-pages-hint) | Trigger | Busk | an operator deletes a record and is surprised pads went with it |
| [`FU-SLOT-DROP-OVERLAY-HIDDEN`](#fu-slot-drop-overlay-hidden) | Trigger | Busk | a palette row dropped at a collapsed cue-slot overlay lands on nothing |
| [`FU-PROG-PER-USER`](#fu-prog-per-user) | Rejected | Prog | decision record — do not re-propose |
| [`FU-PROG-STALE-SOURCE-NAME`](#fu-prog-stale-source-name) | Trigger | Prog | a renamed Look or template shows its old name on a live programmer layer |
| [`FU-PROG-STAGED-CLEAR`](#fu-prog-staged-clear) | Trigger | Prog | the simple Clear bites |
| [`FU-PROG-HIGHLIGHT-PERSONALITY`](#fu-prog-highlight-personality) | Trigger | Prog | a rig big enough to lose a head in |
| [`FU-API-FORCE-FIELDS`](#fu-api-force-fields) | Ready | Prog | — |
| [`FU-LOOK-PERPROP-BLEND`](#fu-look-perprop-blend) | Trigger | Look | an operator wants one property of a layer to mix while the rest override |
| [`FU-LOOK-MIDI-RECALL`](#fu-look-midi-recall) | Trigger | Look | an operator wants a Look on a button |
| [`FU-LOOK-NESTED`](#fu-look-nested) | Trigger | Look | a Look kept hand-synced to another (absorbs `FU-PAL-LINKED`) |
| [`FU-LOOK-STOMP-GRANULAR`](#fu-look-stomp-granular) | Trigger | Look | per-layer stomp proves too coarse |
| [`FU-LOOK-ELEMENT-ROWS`](#fu-look-element-rows) | Ready | Look | — |
| [`FU-LOOK-COMPAT-ROW-COVERAGE`](#fu-look-compat-row-coverage) | Trigger | Look | a rows-only Look offered on a pad where it asserts nothing |
| [`FU-SLOT-LOOK-ELIGIBILITY`](#fu-slot-look-eligibility) | Trigger | Look | a rows-only Look on a cue slot that asserts nothing on the fixtures it names |
| [`FU-TMPL-VIRTUAL-DIMMER`](#fu-tmpl-virtual-dimmer) | Ready | Tmpl | — |
| [`FU-TMPL-MULTI-EFFECT`](#fu-tmpl-multi-effect) | Trigger | Tmpl | Looks made of exactly two deferred effects of one family, no rows |
| [`FU-TMPL-USAGE-RETAG`](#fu-tmpl-usage-retag) | Trigger | Tmpl | retagging a master's usage and expecting stamped templates to follow |
| [`FU-TMPL-FX-EDIT-NO-RETIME`](#fu-tmpl-fx-edit-no-retime) | Trigger | Tmpl | an operator retunes an effect template and the live effect keeps its old timing |
| [`FU-TMPL-CLICK-GROUP-PARTIAL`](#fu-tmpl-click-group-partial) | Trigger | Tmpl | a click on a mixed group spawns nothing where ⌥click lights the capable heads |
| [`FU-TMPL-STROBE-HZ`](#fu-tmpl-strobe-hz) | Trigger | Tmpl | two heads whose strobe rates need to match |
| [`FU-TMPL-WHEEL-PREVIEWS`](#fu-tmpl-wheel-previews) | Trigger | Tmpl | a colour template snaps visibly wrong on a wheel |
| [`FU-TMPL-SECOND-COLOUR-WHEEL`](#fu-tmpl-second-colour-wheel) | Trigger | Tmpl | a two-wheel head's second wheel is wanted |
| [`FU-FE-CUEGRID-PER-CELL-LAYER`](#fu-fe-cuegrid-per-cell-layer) | Trigger | FE | a cue read against two layers reads as against none |
| [`FU-FE-FX-PARAM-RANGE`](#fu-fe-fx-param-range) | Trigger | FE | a script-defined effect declares a numeric parameter outside the guessed range |
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

### `FU-FX-ELEMENT-BUNDLED-COLOUR`

**Elements never receive their bundled W/A/UV component** · Ready · found under sweep item C2,
2026-08-24

`ColourTarget` writes the extended components of an `ExtendedColour` through
`applyExtendedChannel` / `setExtendedChannel`, both gated on `if (fixture is Fixture)`
(`FxTarget.kt:379` in `applyValueToFixture`, `:399` in `resetToFallback`; the same gate is at
`:442` in `composeProgrammerOver` and `:512` in `isPropertyFullyParked`). `FixtureElement` does
**not** extend `Fixture`, so for any element the white / amber / uv half of a colour output is
computed and then silently dropped.

This is visible on real hardware: `LedLightbar12PixelFixture.RgbwPixel.white` is declared
`@FixtureProperty(..., bundleWithColour = true)` (`:225`), so a colour effect or cue on a pixel
group drives RGB and leaves the white emitter dark. `FxEngineBenchmark`'s chase rig has been
running exactly this shape and discarding the white component on every tick.

The fix is not simply widening the gate — the four helpers reach for `Fixture.bundledProperty`,
and the element equivalent is `FixturePropertyCatalogue.of(element::class).bundledByCategory`,
which now exists and is the same shape. Worth checking whether `isPropertyFullyParked`'s
`bundledChannelParked` needs the same treatment for consistency.

**Not** done under C2: that item was a performance change measured on allocation, and folding a
behaviour fix into it would have made the numbers unattributable. Needs a test on element colour
output before the wire behaviour changes — nothing currently asserts an element's white channel.

### `FU-TMPL-REWARM-BOUND`

**Bound `TemplateRegistry.invalidateAll`'s re-warm** · Trigger: a template list change visibly
stalls a template-heavy show · found in sweep item C4's review, 2026-08-26

C4 made `invalidateAll` re-read every requested template on the calling thread before it publishes
the version bump, so the 50 Hz FX pass never takes that transaction. The reads are unbounded and
synchronous, and HikariCP runs `maximumPoolSize = 1` (SQLite has one writer), so a create / rename
/ delete on a show with many templates serialises N round trips against the single connection —
ahead of the cook, the tick pass and any concurrent route.

Deliberately left alone at the time: real shows have tens of templates, the reads are a primary-key
lookup each, and the obvious alternative (hand the re-warm to a background dispatcher) puts the
bump back ahead of the warm cache, which is the ordering bug the review caught in the first place.
If it does bite, the shape that keeps the ordering is a cap plus a log of what was dropped — the
dropped tail then misses on the tick, so the cap is a trade rather than a fix.

Template groups (2026-09-03) widened the trigger's surface without changing its shape: every
drop on `/templates` — a reorder, a move into or out of a group — and every group create, rename
and ungroup fires `templateListChanged`, which is this re-warm. A drag-heavy tidy-up of a large
library is now the likeliest way to make it visible. The reorder itself is metadata (no recook),
so the cost is only ever the re-warm.

The operator-visible symptom to watch for is in `FU-MANUAL-FX-TEMPLATE-COLOUR` step 4.

---

### `FU-FX-TICKFLOW-UNUSED`

**`MasterClock.tickFlow` emits to nobody** · Ready · backend post-refactor sweep C9, 2026-08-24

`MasterClock` publishes every tick twice: `currentTick` (a `@Volatile` field the `SpeedMasterBank`
samples once per engine pass, so that one pass sees one coherent frame) and `_tickFlow.tryEmit(tick)`
at `MasterClock.kt:204`. Production drives off `onTick` / the wake channel and reads `currentTick`;
the only collectors of `tickFlow` anywhere are in `SpeedMasterBankTest`. A `beatFlow` alongside it
was already removed when the `beatSync` push was retired.

It costs almost nothing — `tryEmit` to zero subscribers returns immediately — so this is hygiene,
not performance. The reason to close it is that a public `SharedFlow` on the clock invites a future
consumer to collect it and silently inherit its drop semantics: `extraBufferCapacity = 1`, so the
stream is strictly increasing but *not* gap-free, which `SpeedMasterBankTest` pins and a new caller
would not expect.

**Fix**: delete it and rewrite those two tests against `currentTick` plus `onTick`, or keep it and
gate the emit on `subscriptionCount`. C9's documentation half is already done —
`docs/fx-engineering.md`'s MasterClock table states the truth today — so whichever way this goes,
that row changes with it.

### `FU-PERF-FXSCRIPT-CACHE-BOUND`

**`FxScriptCompiler`'s process-wide cache is never evicted** · Trigger · backend post-refactor
sweep C10, 2026-08-24

`FxScriptCompiler.cache` is a `ConcurrentHashMap<String, CompiledFxScript>` keyed on effect mode +
script body, deliberately shared for the **process** rather than per `Show`. That sharing is
load-bearing and its KDoc explains why: a `Show` is built per project switch (and, in the test
suite, per test), and a per-instance cache made every one of those re-evaluate all 28 built-in
`.fx.kts` effects — 28 jar loads, 28 classloaders and ~70 ms to arrive at lambdas byte-identical to
the previous `Show`'s.

Nothing evicts from it. Each entry carries a compiled script and its classloader, and *failures* are
cached too — also deliberately, because the common case for a failing compile is a user hitting
"Test" on a broken FX definition repeatedly, and each miss runs the whole Kotlin compiler inside
`runBlocking` on a request thread. So a long FX-authoring session accumulates one entry, with a
classloader, per distinct edit, on a process that is meant to stay up for a show. In normal
operation the key set is small and fixed, which is why this was never scheduled into a sweep wave.

**Fix**: an LRU bound. A plain size cap is not quite enough — evicting the built-ins would undo the
sharing argument above — so either pin the `FxFileLoader` entries or set the bound well above the
built-in count.

**Trigger**: metaspace or classloader count that tracks FX-*editing* activity rather than show size.
Metaspace is the one to watch, not heap.

## Frontend polish

### `FU-FE-REBIND-INPLACE`

**In-place "Rebind" for dead assignments** · Rejected · Cue-authoring Phase 6, deferred
2026-04-22; closed 2026-08-29

The item asked for a Rebind quick-action beside each dead row, opening a picker pre-populated with
the dead assignment's property + value, in place of the Remove-and-re-author we shipped. It named
`DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` as the hosts. Neither exists: the second
never did, and the first rendered nowhere and was deleted with `FS-DEAD-ORPHAN-FILES`
(lighting-react `a23b16c`). Cue and Look authoring have no dead-assignment surface at all today —
the only thing that renders health is `BindingMatrix`, over surface bindings, where "rebind" is
already the whole point of the grid.

Closed rather than restated because there is no host to restate it against. If a dead-row surface
is ever built for cues or Looks, decide Rebind-vs-Remove as part of designing it rather than
inheriting this note.

### `FU-FE-HEALTH-BADGE`

**Shared `<HealthBadge>` for `AssignmentHealth`** · Trigger · `moveInDark` row-list editor,
2026-04-25

`AssignmentHealth` used to render in three places. Two of the three named here never survived:
`DeadPresetAssignmentsBanner.tsx` did not exist, and `DeadAssignmentsBanner.tsx` rendered nowhere
and went with `FS-DEAD-ORPHAN-FILES` (lighting-react `a23b16c`). One live renderer is left —
`BindingMatrix.tsx`, which calls `describeHealth()` from `lib/healthDescriptor.ts` and wraps it in
its own markup.

**Trigger**: a second surface needs it. A shared `<HealthBadge>` over a single call site is pure
indirection; `describeHealth()` is already the shared part.

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

### `FU-FE-REVISION-REFETCH-DEDUP`

**Shared primitive for "coalesce a high-frequency broadcast, refetch only on real change"** ·
Trigger · frontend sweep `FS-PERF-PROMPTBOOK-FADE-DRILL` review, 2026-08-30

`programmerWsApi.ts`'s `applyProvenance` gates a state refetch on `message.programmerRevision`
moving since the last frame (landed with `FS-PERF-PROVENANCE-REFETCH`) — the third independent
hand-rolled implementation of this problem class. `channelSource.ts` already notes it uses "the
same reasoning as `changedKeys` in `programmerWsApi.ts`" rather than sharing code, and
`debounceMapUpdates` in `channelsApi.ts` is a third, structurally different coalescing scheme for
the same problem. `wsSubscriptionFactory.ts` has generic subscription primitives but nothing for
revision-gated refetch specifically.

**Trigger**: a fourth high-frequency broadcast needs this treatment (a candidate: `cueRunStateChanged`'s
`fadeElapsedMs`, if it ever needs a value refetch rather than just an animation input). The three
existing implementations differ enough in shape (map-diff signatures vs. a monotonic counter vs. a
per-key debounce) that unifying them now, with no fourth caller driving the design, risks losing a
subtlety one of them depends on for no present benefit.

### `FU-FE-REVISION-NAME-CLASH`

**Two same-named "revision" counters in `programmerWsApi.ts` mean different things** · Ready ·
frontend sweep `FS-PERF-PROMPTBOOK-FADE-DRILL` review, 2026-08-30

The wire field `programmerRevision` (server-driven refetch gate, added by
`FS-PERF-PROVENANCE-REFETCH`) and the pre-existing client-side `revision`/`useProgrammerRevision()`
render-notification counter share the word "revision" in the same module but have unrelated
semantics — the client counter increments only when `touched.length > 0`, decoupling it further
from the wire field's meaning. A future maintainer debugging "why didn't the refetch fire" versus
"why didn't this re-render" is likely to conflate the two given the identical name.

Rename one to make the distinction explicit — e.g. `programmerRenderTick` or
`programmerNotifyRevision` for the client-side render counter, leaving `programmerRevision` for
the wire field it mirrors. Pure rename, no behaviour change; pick it up whenever this file is next
touched.

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

### `FU-SPEED-CUSTOM-RATIO`

**Backend accepts any positive follow ratio; the UI offers five** · Trigger · Busking-view plan
session 1 (2026-08-31), D3/§7

`validateSpeedMasterSettings` accepts any positive `followNum`/`followDen` pair, so 3/4 or 2/3
polyrhythms are a UI affordance away — the schema, wire and sweep all already handle them. The
sheet's vocabulary is deliberately the five chips (2× · 1× · ½ · ⅓ · ¼) until someone asks.

**Trigger**: an operator asks for a ratio the chips don't offer.

### `FU-SPEED-SCRIPT-RAW-CLOCK`

**The script API hands out raw `MasterClock`s, bypassing the bank** · Trigger · Busking-view plan
session 1 review, 2026-09-01

`scriptDef.kt`'s `speedMaster(index)` (and the `masterClock` property behind `bpm`) return the
`MasterClock` itself, so a script can call `setBpm` on it directly. That bypasses everything the
bank's write-through provides: no follower sweep (a script retuning master 1 this way leaves
followers at their old derived tempo until the next bank-routed write), no `SPEED_MASTER_FOLLOWER`
refusal (a script can retune a follower and the value silently sticks until master 1 next moves),
and no `Change` emission — neither the persister nor `speedMasters.changed` sees the move, so
every surface shows a stale tempo. A pre-existing seam (the accessor predates follow) that the
follow feature re-exposes; the top-level `setBpm()`/`tapTempo()` are already bank-routed. The fix
shape is `speedMaster(index)` returning a bank-routed facade rather than the clock — a script-API
semantics change that wants its own decision, which is why it was not folded into the session 1
review fixes.

**Trigger**: a script retunes a master by index and an operator reports surfaces showing the wrong
tempo, or a follower failing to track.

### `FU-SPEED-LINK-PUT-STALE-BPM`

**A link PUT's response DTO carries the pre-link stored bpm** · Trigger · Busking-view plan
session 1 review, 2026-09-01

A `PUT /speed-masters/{mid}` that links a follower (sets `followNum`/`followDen`) responds with a
DTO whose `bpm` is still the stored pre-link value next to the ratio it just accepted — internally
contradictory until the bank's sweep derives m1 × num/den and the persister's debounced flush
writes it through. `speedMasters.state` / `speedMasters.changed` correct it immediately, so a
client consuming the WS stream (as ours does) never shows the contradiction. Left alone in the
session 1 review because a route-side fix means the route re-deriving the tempo the bank already
owns — duplication for a transient the socket already repairs.

**Trigger**: a client renders the link response body without also consuming `speedMasters.state`,
and the contradiction is visible for long enough to matter.

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

`FxInstance.speedMasterUuid` is per *instance*, which already gives per-property speeds — a
position wave on master 2 and a dimmer chase on master 1 are simply two instances. MA3 assigns
masters per-attribute *inside* one phaser; here that would mean a composite
(`Effect.calculateComposite`, e.g. `LightningStrike`) whose outputs advance on different clocks.

Cut deliberately: a composite computes every output from a single phase, and that coupling is why
it's a composite rather than N instances. Splitting it means `calculateComposite` taking a
per-output phase map, plus a picker that can address a constituent.

**Prerequisite as of A4** (2026-08-24): composites are now documented and enforced as
primary-output-only — the never-populated `FxInstance.compositeTargets` and the engine branch
that read it are gone, so a composite applies exactly one property. Per-attribute masters
therefore need multi-output composites *wired at all* first (secondary targets on `FxInstance`
plus an authoring surface that can name a constituent), not just a per-output phase map.

**Trigger**: an operator wants one shipped composite's constituents on different tempos and can't
express it as separate instances.

---

## Busk

### `FU-BUSK-MOMENTARY`

**Every busk pad is a toggle; there is no flash gesture** · Trigger · Busking-view plan
session 3 (2026-09-01), §7

A press latches: a template pad applies or releases, a Look pad adds or removes a layer, a cue pad
applies or stops its cue, a target pad selects or deselects. Real desks also have *momentary* pads — the value holds while the button
is held and drops on release — which is how a busking operator punches a blinder on a downbeat
without a second press to find. Doing it means a press/release pair rather than a click.

Smaller than it was written. The effect and property pads it also named were removed when the busk
view was cut back to the library pads its design draws (see the busking-view plan's §4 note), so
"what does release mean for an effect *instance* as opposed to a programmer value" is no longer part
of the question — every pad left is a toggle of *something*, a layer for a template or Look and the
cue itself, through `CueStackManager`, for a cue. And the three duplicated long-press
implementations it wanted unifying first are now one shared hook,
`lighting-react/src/hooks/useLongPress.ts`, which is where a `onPressStart`/`onPressEnd` pair would
be added.

**Trigger**: an operator asks to flash a pad rather than latch it, or a second surface wants the
same gesture and the press-handling can be shared.

---

### `FU-BUSK-PAGE-MIDI`

**Busk pages are unreachable from hardware** · Trigger · Busk-layout plan (2026-09-04), §7

A control surface can name a cue, a stack or a speed master, but not a busk page: there is no
`BindingTarget` for "next page" / "previous page" / "page *n*", and no target for a pad either.
Deliberate for the first cut — the layout landed as a screen surface, and a pad binding raises the
question of what a *hardware* press means for a pad whose record needs a selection the surface has
no way to make.

Page up/down is the small half and would go in first: two targets, resolved against the page list
the busk view already reads, with the showing page kept where `?page=` keeps it today.

**Trigger**: an operator wants to change busk page from hardware.

---

### `FU-BUSK-AI-LAYOUT`

**The AI surface knows nothing about the busk layout** · Trigger · Busk-layout plan (2026-09-04), §7

There is no `add_busk_pad`, no `create_busk_bank`, and nothing that reads a page. `AiToolSchemas`
gained nothing when the layout landed: the layout is the operator's own arrangement, and the
whole-page write (`PUT /busk/pages/{id}/layout`) is a poor shape for a tool — a model that has to
resend every row to move one pad will lose a bank.

If it lands, the shape is probably additive tools over the page (append a pad to a named bank,
create a bank in a named column) implemented on top of the whole-page write server-side, so the
route keeps its one renumbering.

**Trigger**: a prompt asks the AI to put something on a busk page.

---

### `FU-BUSK-PAD-SIZE`

**A pad is one size, and a bank's height is whatever its pads make it** · Trigger · Busk-layout
plan (2026-09-04), §7

A column carries a width share in twelfths and a bank carries a flow (wrap or one-per-line); there
is nothing else. No pad size, no bank height, no per-pad emphasis. That is deliberate — the design
took MagicQ's fixed grid over QLC+'s free canvas precisely because free positioning is fiddly on a
touch surface and never survives a narrower screen, and every extra dimension is one more thing that
does not survive it either.

If a page genuinely needs more density, the cheap move is a per-bank pad scale (one enum, applied to
the bank's grid) rather than a per-pad size, because a bank is already the unit that owns layout.

**Trigger**: a page needs more density than width and flow give.

---

### `FU-BUSK-EDIT-CONCURRENCY`

**Every edit gesture writes the whole page immediately, so a half-built page is live for a second
operator** · Trigger · Busk-layout plan (2026-09-04), §8

Editing saves per gesture (D8), with *Done* only leaving the mode — the same posture as every other
editor on the desk, and the reason there is no Save button to forget. The cost is that a second desk
watching `busk.layoutChanged` sees each intermediate state: a bank dragged out of one column and not
yet dropped in another is a page with a missing bank, briefly, on someone else's screen.

Accepted because the alternative is worse for one operator: holding edits until *Done* means a
Discard button, an unsaved-changes prompt, and a client-side document that can diverge from the
server's. Nothing here corrupts — the whole-page write is last-writer-wins on a page, and the route
refuses an inconsistent document outright — so the failure mode is confusion, not loss.

**Trigger**: two desks edit one busk page at once.

---

### `FU-BUSK-ON-PAGES-HINT`

**A library row does not say how many pads it has** · Trigger · Busk-layout plan (2026-09-04), §11

Deleting a template, Look or cue deletes its pads, silently: a pad is an enrichment, not a guard, so
the delete guards do not count pads and the confirm dialog does not mention them. An operator who
has built three pages around one template gets no warning that the delete takes six pads with it.

Drafted answer: an "on *n* pages" hint per row on `/templates` and `/looks`, and a line in the
delete confirm. Not built because it needs a count on the list DTOs — cheap per record, but a second
query per list — and nobody has been surprised by it yet.

**Trigger**: an operator deletes a record and is surprised pads went with it.

---

### `FU-SLOT-DROP-OVERLAY-HIDDEN`

**A palette row dropped at a collapsed cue-slot overlay lands on nothing** · Trigger · Busk-layout
plan session 3 (2026-09-04)

The slot droppables live inside `CollapsiblePanel`, which unmounts its body when the FX cue-slots
overlay is hidden. Since session 3 the slots are filled by dragging a row from the busk view's
library palette while busk edit mode is on — but if the overlay is shut, there is nothing to drop
onto and the drag simply ends with no feedback at all.

Not fixed because the two obvious answers both have a cost: opening the overlay on entering busk
edit mode takes header space from an operator who was not editing slots, and a hint on *Edit layout*
is a sentence that only matters occasionally. The third option — keeping the droppables mounted with
the body hidden — reintroduces exactly the zero-height-rect problem `FU-MANUAL-COLLAPSED-PANELS`
already has an open question about.

**Trigger**: an operator tries to fill a slot with the overlay shut.

---

## Programmer

### `FU-PROG-PER-USER`

**Per-user programmers** · **Rejected — decision record, do not re-propose** · promoted from the
programmer redesign 2026-08-14, decided 2026-08-23 while scoping the desk-simplification Session 2

`ProgrammerStore` is a **single shared programmer**, and after review that is the *intended*
design rather than a solo-operator compromise. **One state, shared by every client: a second
device is a second window onto one desk, not a second seat.** Someone signing in elsewhere should
see exactly what the desk sees and be able to edit it. MA3 gives each user their own; this desk
deliberately does not.

That was decided with the desk-simplification plan's D2 (the programmer becomes the only cue
editor) and D11 (Run and Show merge) already in hand, which are the two changes that raise the
stakes — so the decision was taken against the *higher* stakes, not the old ones.

**Two corrections to the original entry, recorded so the economics are not re-derived wrongly:**

1. **"Mechanical rather than structural" was false.** `ProgrammerOwner` is not a spare user
   dimension — it is a subsystem identity with a *release contract* ("owners with a release path
   must clear with the same owner they put with — that is the whole contract"), and nine
   `owner ==` identity comparisons in `src/main` depend on its current shape. Keying by
   `web:{userId}` would give per-user slots inside one shared stack, not per-user programmers.
2. **The expensive half was misidentified.** It is not the singleton *reads* — most of those are
   cold paths with an obvious caller, and `SocketScope.user` already carries the identity on every
   frame. It is `LayerResolver.fallbackFor`, the single 50 Hz read, which returns **one** value per
   (fixture, property) because DMX is one byte per channel. The rig cannot show the union, so
   somebody must win, and nothing expresses that: HTP would reverse the locked "programmer wins HTP
   categories too" decision, and `Slot.seq` bands are the only extensible axis — one whose own doc
   warns that ties resolve *inconsistently* across the four `composeProgrammerOver` overrides.

Also unanswered, and unanswerable without inventing policy: which user owns a write from a **MIDI
fader, a flash button, Locate, the unpark hand-down, the AI tools or the `updateChannel` shim** —
none of which has a session. And `ProgrammerLayerStack` (625 lines) has no per-user story at all;
`putLayerSlots` is a full-map set-difference over one shared property map.

**If this is ever re-opened**, the cheap 80% is not per-user stores. It is per-user **blind** plus
per-user slots: at most one user is live, so the merge rule never has to exist. The per-recipient
fan-out that would need is already demonstrated in eight lines by `ownAccountChanged`
(`plugins/MachineSocket.kt`). The house precedent for the other direction is `cueEdit`, which
chose **exclusion over merging** — one session per project, everyone else gets a 409.

### `FU-PROG-STALE-SOURCE-NAME`

**A programmer layer caches its source's name, so a rename goes stale** · Trigger: an operator
renames a Look or template that is live on the programmer and sees the old name · found fixing the
`toggle` identity bug, 2026-09-02

`ProgrammerLayer.source` and `FxInstance.source` both hold a whole `LayerSource`, captured when the
layer was added. Two of its three fields are immutable identity (`id`, `uuid`); `name` is not. So
renaming a record leaves every already-added layer and already-spawned effect reporting the *old*
name — visible in the layer stack (`ProgrammerLayerDto.source`) and in `FX running`
(`EffectDto.sourceName`). Nothing refreshes it: a rename-only PUT is not a contents change, so it
takes the `templateListChanged` / `lookListChanged` branch and never reaches the layer stack.

This is display-only now. The *functional* half of the same root cause — `toggle` comparing whole
`LayerSource` values, so a rename stopped a pad turning its own layer off — is fixed: it matches on
`source.uuid`. Cue layers are unaffected, because `DaoCueLayer.source` reads the row fresh.

Two shapes, and the reason neither was done inline:

- **Refresh the stored sources on rename.** Correct, but it needs a new `ProgrammerStore` mutation
  and a hook in a path that currently and deliberately does nothing to the programmer on a rename.
  That is a real change to the store's mutation surface for a stale label.
- **Re-derive the name at serialisation time**, the way `IncludedTargetDto` already does with
  `DaoLook.findById(lookId)?.name`. Cheap for the layer stack; **not** cheap for `toEffectDto`,
  which is the only producer of `EffectDto` and is called from the FX state flow — a transaction
  per effect per publish is exactly what this codebase keeps off that path.

If it fires, prefer the first shape, and note that fixing it also removes the last reason
`LayerSource` equality is dangerous — at which point `toggle`'s uuid match becomes belt-and-braces
rather than load-bearing.

---

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

### `FU-API-FORCE-FIELDS`

**`force` survives inert on the Record and Update request bodies** · Ready · frontend sweep §14
`FS-BE-FORCE-FIELDS`, 2026-08-29

`ProgrammerRecordRequest.force` (`routes/programmerRoutes.kt:85`) and
`ProgrammerUpdateRequest.force` (`:297`) are read by nothing. Backend sweep item D1 retired the
`cueEdit.*` family, so there is no session to record underneath and no conflict for a force to
override. They were kept only because `RecordSheet` and `UpdateDialog` sent the field on *every*
submit rather than only on a conflict retry, and the route's `Json` is strict about unknown keys —
so deleting it server-side would have 400'd every Record until the frontend caught up.

**That ordering constraint is spent.** `FS-COORD-CUEEDIT-RETIRE` landed as lighting-react
`62b64eb`, and no client sends `force` on either body today. Both fields and both KDoc blocks
explaining their survival can go whenever wanted; nothing 400s either way now.

This is only the two *programmer* fields. The `force` on the template, Look and speed-master DELETE
routes is the live `?force=true` guard-override convention from
[`../api-conventions.md`](../api-conventions.md) and must stay.

### `FU-LOOK-PERPROP-BLEND`

**Per-property blend override within a layer** · Trigger · Looks-and-layers §3.4, cut as
UI-multiplying

A layer's `blendMode` and `amount` apply to every property it asserts. An operator might want one
property of a layer to mix while the rest override — "take Warm's colour at 50% but its dimmer
outright".

Cheap in the composer: `CueComposer.blend` is already per-(fixture, property), so the override just
needs to reach it. The cost is entirely in the UI, which would go from two controls per layer to two
per property per layer. Recorded rather than silently cut.

**Trigger**: an operator asks for one property of a layer to behave differently from the rest.

### `FU-LOOK-MIDI-RECALL`

**A `RecallLook` binding target** · Trigger · Looks-and-layers §7

No binding target names a Look (or, before, a preset or palette) — `midi/BindingTarget.kt` has
nothing in this space, so the surface needs no redesign to accommodate one. But a Look is an obvious
thing to want on a button, and the busking pads already do it from the web UI.

Decide first *what* the button does: toggle the Look on the current selection, add it as a programmer
layer, or recall it onto a fixed target set baked into the binding. The third is the only one that
behaves identically every press, which is usually what a button should do.

**Trigger**: an operator asks for a Look on the control surface.

### `FU-LOOK-NESTED`

**Nested Looks — a Look row referencing another Look** · Trigger · absorbs `FU-PAL-LINKED`
(Programmer redesign §2, promoted 2026-08-14; re-homed 2026-08-21)

A Look row holds a literal. Consoles also let one palette *reference* another, so "Warm Wash" can be
defined as "House Amber" and follow it — the same touring leverage a layer gives, one level up.

Closer than it looks: `resolveAssignmentValueForFixture` is the single door for `ref:{uuid}` and
already runs before `parseAssignmentValue`, so a row whose stored value is a `ref:` resolves through
the same path. Genuinely new: **cycle detection** (A → B → A refused at write time, not discovered at
50 Hz), depth-bounded resolution inside `LookRegistry`'s version-counter cache, `republishForLookEdit`
walking the reference graph rather than one hop, and a `LOOK_IN_USE` delete guard that counts
look-to-look references as well as layers.

Note the write boundary currently *rejects* a `ref:` in a look row (`validateLookRows`), and that
rejection is what guarantees resolution never recurses — so this item is precisely the work of
replacing that guarantee with a bounded one. The cook step is where it would land.

**Trigger**: a show keeps one Look hand-synced to another. This used to say "do
`FU-PAL-POSITIONAL-CONVERSION` first, it decides how many Looks exist" — that entry is closed
(done differently: the positional form was deleted, not converted), so there is no longer anything
to sequence this behind.

`ProgrammerRecordLookResponse.refsFlattened` — a permanently-0 wire field kept alive on the theory
that this item would want the same count again — was removed in `1788d7d` (lighting-react
`addb53b`) rather than carried speculatively. If this item lands, it needs a fresh field for "how
many `ref:` rows did this record flatten", not a resurrection of the old one; the removal commits
show the shape it had.

### `FU-LOOK-ELEMENT-ROWS`

**A Look's element row composes nowhere** · Ready · Looks-and-layers correction #10, 2026-08-22

`DaoLookRows.elementKey` exists, the migration carries element rows across, and
`RichProjectFixture` seeds one — but `CueComposer.applyLayer` drops every element row, and
`buildCueAssignmentsForCue` has no element path either. So a Look holding a per-element value
(one pixel of a bar, one head of a multi-head fixture) round-trips through the library, the sync
export and the editor, and then contributes nothing when a cue layers it.

**Pre-existing, not a session-3 regression** — the same gap existed for palette entries — and
recorded explicitly rather than left as an implied capability, which is what §3.1 currently reads
as. Cue *ad-hoc effects* do have an element path (`elementMode` / `elementFilter`), so the vocabulary
exists; it is the static-row half that was never wired.

**Decide before implementing**: whether a deferred element row is even meaningful. An element key
identifies a sub-part of a *specific* fixture geometry, so a deferred row carrying one is asking to
be applied to whatever the layer targets — which may not have that element. The bound case is
unambiguous and is probably the whole of it.

### `FU-LOOK-STOMP-GRANULAR`

**Finer-grained within-cue stomp** · Trigger · Looks-and-layers §8

Per-layer `stomp` suppresses lower layers' *effects* on every property the layer asserts. That is
the coarse version: an operator might want to stomp a layer's colour effect while leaving its dimmer
effect running.

The column landed with the layer model and the coarse behaviour with `FU-LOOK-STOMP-WITHIN-CUE`.
Judge granularity only after that has been used on a rig — the Layer 3/4 boundary it exists to work
around may turn out to bite in one specific place rather than generally.

**Trigger**: per-layer stomp proves too coarse in practice.

### `FU-LOOK-COMPAT-ROW-COVERAGE`

**Look compatibility ignores rows, so a rows-only Look is compatible with everything** · Trigger ·
frontend sweep §14 `FS-BE-COMPATIBLEIDS`, 2026-08-29

`compatibleIdsFor` (`routes/lightFixtures.kt`) filters a Look against a target by its *inferred
effect capabilities* only. A Look holding no effects has an empty capability set, `all {}` over it
is vacuously true, and the Look is therefore reported compatible with every fixture and group in
the rig — so `LookTogglePicker` offers pads that assert nothing about the head under them.

`FixtureDetails.compatibleLookIds`'s KDoc says this deliberately ("a Look holding no effect is
compatible with everything"), on the grounds that a Look's *rows* name their own targets so the only
open question is its effects. That is defensible for a Look whose rows happen to cover the target
and wrong for one whose rows cover nothing near it.

**The decision to take** is whether compatibility should also require row coverage — and if so,
whether that means "covers at least one of the target's fixtures" or "covers all of them". Row
coverage is per fixture, so the group case needs its own answer.

`FS-DOCS-COMPATIBLELOOKIDS` landed (`0bcda19`) by documenting the hole rather than claiming it
closed, which is why nothing is blocked on this; it is only still true.

**Trigger**: an operator presses a Look pad the picker offered and nothing on the selected head
moves.

---

### `FU-SLOT-LOOK-ELIGIBILITY`

**A cue slot takes any Look with no deferred effect, including one that asserts nothing on the
fixtures it names** · Trigger · Busk-layout plan (2026-09-04), §8

A slot may hold a Look with no deferred effect, and that is the whole eligibility test (D7): the
rows of such a Look are always bound, so pressing it with no selection derives the targets from the
Look's own rows. `POST /cue-slots` refuses a deferred-effect Look by name
(`CUE_SLOT_LOOK_NEEDS_SELECTION`) and the palette dims it; nothing checks that the Look's fixtures
are still patched, or that its rows assert anything on them.

The press itself is honest — `LOOK_NO_TARGETS` when none of its fixtures is patched — so the failure
is a tile that looks fine and refuses when tapped, rather than a silent no-op. That is the same
question `FU-LOOK-COMPAT-ROW-COVERAGE` asks of a busk pad, one surface along, and it should get the
same answer when it gets one: row coverage is per fixture, and a rule for one surface that is not
the rule for the other would be worse than the hole.

**Trigger**: a rows-only Look sits on a slot and asserts nothing on the fixtures it names.

### `FU-TMPL-VIRTUAL-DIMMER`

**An intensity template on a head with no dimmer** · Ready · desk-simplification §Session 3, 2026-08-23

`BeamColour.dc.html` promises that "a head with no dimmer takes it as a virtual dimmer over its
colour emitters — the existing virtual-dimmer path". **There is no such path on the backend.** The
only virtual dimmer is a *group* gesture the client fans out to members (see
`plugins/ProgrammerSocket`'s doc comment), and `hooks/useVirtualDimmer.ts` is a front-end scaling of
RGB. So `TemplateResolver` reports `Unsupported("no dimmer")` and the editor's resolves-to panel shows
it, which is honest but leaves a real gap: a colour-only PAR takes no part in "Half Up".

The work is a `Percent` arm for a `WithColour`-but-not-`WithDimmer` head that scales the emitters the
way `useVirtualDimmer` does, server-side so the panel and the cook agree. The awkward part is
*composition*: a virtual dimmer writes the colour channels, so an intensity template and a colour
template on the same head would fight over the same bytes — which is a real question about what the
mask means, not an implementation detail.

**Ready**: no gate, and the resolver is the one place it lands.

### `FU-TMPL-MULTI-EFFECT`

**More than one effect per template** · Trigger: an operator keeps making Looks that are exactly two
deferred effects of one family with no rows · fx-templates session 1, 2026-09-02

D2 caps an effect template at one effect, on the grounds that several together is what a Look with
deferred effects already is — and a Look has its own busk pool, so nothing is unreachable. The cap
is enforced twice: `uniqueIndex(template)` on `template_effects`, and by name at the write boundary
so it surfaces as a 400 rather than a constraint violation.

The trigger to watch for is a Look that is *only* a pair of same-family deferred effects with no
rows: that is an operator working around the cap, and it is the shape the cap gets in the way of. If
it fires, the storage change is small (drop the unique index, add `sort_order`) but three things
follow it: `TemplateSnapshot.effect` becomes a list, `TemplateDto.kind` needs to stay a string
because a third arm becomes plausible, and the *Runs on* preview has to say which effect it is
previewing.

---

### `FU-TMPL-USAGE-RETAG`

**Retagging a speed master's usage does not move stamped templates** · Trigger: someone retags a
master's usage and expects existing effect templates to follow it · fx-templates session 1,
2026-09-02

D8 stamps `speed_master_uuid` at *authoring* time from the project master whose `usage` matches the
family. "By usage" is how the default is labelled in the sheet, not a stored mode — deliberately,
because the alternative is a second meaning for `null`, and the `null → slot 0` invariant is what
the bank and the whole `speedMasters.*` wire protocol are built on.

The accepted cost is that retagging a master's usage later leaves templates already stamped
pointing at the old master. That is defensible (the stamp is what an operator saw at the moment of
authoring) right up until someone reorganises their masters and expects the library to follow.

If it fires, resist adding a stored "by usage" mode. The cheaper shape is a *migration on retag*:
the usage PUT already knows the old and new holders, so it can offer to re-stamp the effect
templates of that family — visible, one-time, and leaving the invariant alone.

---

### `FU-TMPL-FX-EDIT-NO-RETIME`

**An effect edit does not tour to what is already on stage** · Trigger: an operator retunes an
effect template's beat division and the running effect keeps its old timing · fx-templates session
1, 2026-09-02

Editing an effect template *is* a contents change: the PUT takes the republish branch, the registry
snapshot refreshes, and the next application runs the new effect. But
`ProgrammerLayerStack.recookIfReferences` cooks `withEffects = false` on purpose — an edit touring
to an already-applied layer "is not the layer arriving", and re-spawning would restart the effect
mid-show on every nudge of a parameter. So the live instance keeps its timing until the layer is
re-applied.

This is **inherited from Looks unchanged**, not new: a deferred Look effect behaves identically, and
has since the layer stack was written. It is recorded here because an effect template makes it much
more visible — the whole *point* of an effect template is the one thing the operator retunes, so
"nothing happened" is a likelier reaction than it was for a Look. `TemplateRoutesTest`'s
`an effect-only edit refreshes the snapshot without restarting what is on stage` pins both halves so
the asymmetry stays deliberate.

Note the plan's desk check 3 ("edit the template's beat division → every layer tracking it
retimes") states the *opposite*, and is wrong as written; it should read "re-press the pad and it
runs at the new division".

If it fires, the shape is not "always re-spawn": it is to distinguish a *timing* edit (division,
speed master) from a parameter edit, and tour only the former — which needs `FxEngine.updateEffect`
to retime in place rather than retract and respawn, or the cure is the disease.

---

### `FU-TMPL-CLICK-GROUP-PARTIAL`

**A clicked effect template is all-or-nothing on a mixed group; a ⌥clicked one is not** · Trigger:
an operator clicks an effect pad with a mixed group selected, gets nothing, and ⌥clicks to find the
capable heads light · fx-templates session 2 review, 2026-09-02

`applyEffectTemplateToProgrammer` gates each target on `fixturesSupportProperty`, which requires
**every** member to have the property — the same all-members rule `POST /groups/{name}/fx` has
always applied. The layer path (`ProgrammerLayerStack.build`) has no capability check at all: it
resolves a `ColourTarget` for the group and lets `FxInstance` warn per head. So on a group of hexes
plus a hazer, click reports the whole group skipped and ⌥click runs the effect on the hexes. The
docs' parity claim is qualified for exactly this case.

Three smaller things sit behind the same gate and are worth doing together if it fires:

- **The skip's `fixtureKey` holds a group key** on this arm, where every value-arm skip holds a real
  fixture key. The panel that renders these notes resolves the field against the patch, so a group
  skip renders as an unknown head. Expanding a group skip to its unsupported members fixes the
  report and the granularity in one move.
- **Overlapping targets duplicate.** The value arm collapses through `expandTargetsToFixtureKeys`;
  this arm iterates refs, so a selection naming both `front-wash` and its member `hex-1` puts two
  band effects on `hex-1`. `effectsForLayer` behaves the same way, which is why it was left.
- **Two capability models meet in series here.** `EffectSpawner.resolveTargetForCue` picks a
  property from `detectCapabilities()` (a reflective element-group-descriptor scan needing ≥2
  elements); `fixturesSupportProperty`'s multi-element fallback tests `elements.first() is
  WithColour`. They disagree on a 1-element `MultiElementFixture`, and on a head exposing an
  `@FixtureProperty` colour without the trait. Pre-existing divergence, newly composed.

Relaxing the gate to "any member supports it" is the obvious shape, but it changes the group FX
route's contract too if the helper stays shared — which it should, since two copies disagreeing
about one fixture is what sharing it prevented.

---

### `FU-TMPL-STROBE-HZ`

**Strobe as a rate rather than a percentage** · Trigger · desk-simplification §Session 3, 2026-08-23

`BeamColour.dc.html` calls Hz "the only unit two fixtures agree on", and it is right — but nothing in
this codebase's fixture definitions declares a Hz range for a strobe channel the way
`@FixtureProperty(degMin=, degMax=)` declares a pan range. A `hz:` intent would have nothing to
resolve against and would be inventing a curve per head, so strobe is a percentage of each head's own
channel (`TemplateIntent` records this).

The work is annotation before code: `hzMin`/`hzMax` on the strobe properties that have a documented
range, then a `Hertz` arm in the grammar and the resolver, with heads lacking the annotation reported
as degraded rather than guessed at.

**Trigger**: two heads on one rig whose strobes need to visibly match. Until then the percentage is
no worse than what a per-fixture value gave.

### `FU-TMPL-WHEEL-PREVIEWS`

**A wheel snap is only as good as its `colourPreview`** · Trigger · desk-simplification §Session 3, 2026-08-23

`TemplateResolver.nearestColourSlot` picks a wheel slot by ΔE76 against
`DmxFixtureColourSettingValue.colourPreview`, and those values are documented in the fixture
definitions themselves as "best-effort approximations for the UI" (`RobeColorSpot575Fixture`). The
number the editor shows is therefore "how close the desk *believes* it got", which is the right thing
to show — but a badly annotated wheel will snap confidently to the wrong slot.

The work is a measurement pass over the wheel fixtures, not code.

**Trigger**: a colour template that snaps visibly wrong on a wheel head. The ΔE in the panel is how
you would notice.

### `FU-TMPL-SECOND-COLOUR-WHEEL`

**A two-wheel head's second colour wheel is unreachable** · Trigger · desk-simplification §Session 3, 2026-08-23

`resolveCell` shows the *first* wheel only (its own doc records the cut), and
`TemplateResolver.resolveColour` picks the first COLOUR-category setting for the same reason. The Robe
ColorSpot 575 has two, so its second wheel takes no part in a colour template — nor in the grid.

Not obviously worth fixing: a colour template asks "be this colour", and answering it on two wheels at
once is a mixing problem the fixture's own manual barely addresses.

**Trigger**: an operator asks for the second wheel by name.

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

If that changes, `adminOnly {}` in `auth/AuthGate.kt` is the whole mechanism (backend sweep F6
replaced the old path-prefix list with it), and the shape matters more than the list: it wraps a
**whole subtree**, so anything method-sensitive (as `PUT /api/rest/install` already is) needs a
per-handler `call.requireAdmin()` instead — patch editing is read-for-everyone, write-for-admin,
so it'd be the second instance of that pattern. Script editing is *not* a candidate: F6 settled
that operators keep scripts, because an operator is trusted local crew who can already do
anything the desk process can (`docs/desk-accounts.md` §Roles). Mirror any addition in
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
`POST /api/rest/projects/{id}/sync/maintenance/gc-tombstones` surfaces the same operation for
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

**Widened 2026-08-24** by the backend sweep's C0: the harness now has three scenarios
(`[beat]`/`[wall]`, `[chase-beat]`/`[chase-wall]`, `[crossfade]`), so the baseline to collect is
five summary lines, not two — and the two wall-clock windows are the jittery ones, since
`processWallClockTickSuspend` takes `deltaMs` from the real clock. First numbers are in
`docs/testing-engineering.md` §"Recorded baselines"; note `[beat]` already reads ~45% faster than
the 2026-04-22 capture on unchanged code, which is itself the argument for the variance study.

### `FU-FE-DBO-INERT`

**Blackout is a cosmetic toggle sitting beside a functional one** · Ready · desk-simplification
session 2b, 2026-08-23

`ShowBar`'s DBO tile is local `useState` with no side effect in every host that mounts it, and
`grep -i blackout` over `src/` finds no API call — the only real blackout is a MIDI/OSC surface
binding target (`api/surfacesApi.ts`, `BlackoutTarget`). It has always been inert, which was
survivable while it was the only control in that cluster.

Session 2b put a **BLIND** tile immediately beside it — Blind being a real gate on what reaches the
rig, wired to `programmer.setBlind`. So the bar now draws two identically-styled tiles, adjacent, of
which one works. An operator who trusts DBO mid-show because Blind next to it plainly does something
is the failure this predicts.

**Fix**: a backend blackout route (grand-master-zero, or a Layer-0 gate), and `ShowBar`'s DBO wired
to it the way Blind is. Out of 2b's scope because it needs backend surface area, and 2b was
deliberately frontend-only.

**Alternative if that is not wanted**: draw DBO differently from Blind, or remove it. Two peers where
one is a placeholder is the part that must not stand.

### `FU-FE-SHARED-LOOK-EDIT-GUARD`

**The shared-Look edit guard was never implemented** · Ready · Noticed during desk-simplification
session 2b, 2026-08-23

Session 2's §"Rules that must hold" says: *"Editing a shared Look edits it everywhere, and this must
be said at the moment of the first edit, not in a tooltip. **Duplicate for this cue** is the primary
action, not 'change all 9 layers': retuning one cue is the common intent, and the other reading is
the one an operator cannot undo across nine cues."*

Nothing implements it. `grep` for the affordance or for a usage count finds no call site, and no
sheet or dialog offers the choice — a layer-scope edit goes straight to `PUT /looks/{id}` and
republishes every cue layering that Look, silently.

This is **2a's rule, not 2b's** (it does not mention the lock, so it fell on the near side of the
split), which is presumably how it was missed: 2a shipped the live-write path that makes it
necessary. Recorded rather than absorbed because 2b was already the larger half.

**Needs first**: a usage count at the point of edit. §6 of the plan already flags that — *"if that is
not already cheap to ask for, log it rather than fetching a Look's full detail per keystroke"*.

### `FU-FE-CUEGRID-PER-CELL-LAYER`

**A cue row fed by two layers shows no layer at all** · Trigger · desk-simplification session 2a,
2026-08-23

`CueValueGrid.layerFor` collapses a whole row to one name — `names.size === 1 ? name : null` — so a
head whose colour came from *Warm Wash* and whose dimmer came from *Half Up* renders **no** `Layers`
glyph, exactly like a head no layer touched. Zero and two are the same answer, and the more
interesting case is the one that disappears.

The programmer's own grid does not have this problem: it marks **per cell** and has a `mixed` state
for a cell more than one layer contributed to (`CellLayer.mixed`, drawn muted). The cue grid marks
per row because that is where there was space at `h-8` with the name column already truncating.

The fix is per-cell attribution — `buildStaticRows` already returns `layerByKey` keyed by
`(target, property)`, so the data is there; it is the row-level `layerFor` and the single glyph in
the name cell that throw it away. Either move the glyph into the cell (as the programmer does) or
give the row a "2 looks" marker.

**Not the paired-slider bug it was first reported as.** A review flagged this as "a position paired
from two axis sliders never picks up the glyph"; that is wrong. `resolutionPropertyNames` yields
`['pan','tilt']` for such a cell and the cook names those same two properties, so they match. The
real loss is the multi-layer row.

**Trigger**: an operator reads a cue drawn from two looks and concludes neither is involved. On a
show whose cues each layer one look, this never fires.

---

### `FU-FE-FX-PARAM-RANGE`

**The FX sheet guesses a numeric parameter's range, because the wire carries no bounds** · Trigger ·
frontend sweep `FS-COORD-FXLIBRARY-PARAMS`, 2026-08-29

`GET /fx/library` describes each parameter with `name` / `type` / `defaultValue` / `description`
(`FxRegistry.ParameterInfo`, filled from each `.fx.kts` file's frontmatter via `FxFileParameter`)
and nothing else. `EffectParameterForm.ParameterInput` therefore has to invent the slider range for
every numeric control: a `double` gets 0–1 when its default is `<= 1.0` and 0–10 otherwise, and an
`int` gets `max(255, default * 2)`.

For the 28 built-ins this is exactly right and was verified parameter by parameter when backend D7
(`84885df`) started sending real defaults: all ten declared doubles are 0–1 ratios with defaults
between 0.1 and 1.0, so the 0–10 arm is unreachable, and the two ints (`ColourFlicker.variation` 50,
`FluorescentFlicker.flickerDurationMs` 800) land in usable ranges. The guess only breaks for an
effect the built-ins don't constrain — a script-defined one registered through `registerEffect` /
an `FX_DEFINITION` script, which may declare a double whose real range is 0–360 (rendered 0–1
because its default is 0) or 0–1000 (rendered 0–10).

The fix is to declare the bounds rather than widen the guess: add nullable `min` / `max` to
`FxFileParameter` and `ParameterInfo`, teach `FxFileLoader.parseSimpleYaml`'s `parameters:`
continuation branch the two extra keys, declare them on the numeric built-ins, and have
`ParameterInput` prefer them with the present heuristic as the fallback for a definition that
declares none. Additive on the wire and additive in canonical JSON, so no `formatVersion` change —
but it is a backend change, which is why the sweep item that found it did not take it.

**Trigger**: a script-defined effect declares a numeric parameter whose sensible range is not the
one the heuristic picks, and the operator finds the slider unusable.

---

## Completed

One line each: slug, what shipped, commit. Full narratives live in the commit messages and in this
file's git history; durable mechanism notes belong in `docs/*-engineering.md`.

### 2026-09

- `FU-TMPL-LAYOUT-SIGNAL` — retired by deletion: `POST /templates/reorder` is gone, so no reorder
  rides `templateListChanged` and there is nothing left to key a `templateLayoutChanged` frame off.
  The busk page owns order now, and its own `busk.layoutChanged` is already keyed — busk-layout
  plan session 3
- `FU-TMPL-GROUP-AI` — retired by deletion: template groups are gone, so there is no group CRUD for
  the AI surface to learn and no `TEMPLATE_GROUP_FAMILY` for a tool to report. `create_template`
  takes no position either, because a template no longer has one — busk-layout plan session 3
- `FU-TMPL-LAYOUT-FAMILY-SCOPE` — retired by deletion: `applyTemplateLayout` and the whole
  `routes/templateLayout.kt` went with template groups, taking the over-broad one-family check and
  the importer path that could reach it — busk-layout plan session 3
- `FU-TMPL-GROUP-MISSING-404` — retired by deletion: `TemplateInput` carries no `groupId`, so there
  is no stale group reference for a PUT to answer 400 to — busk-layout plan session 3
- `FU-SPEED-PHASE-LOCK` — a follower no longer runs a timer of its own: its leader's tick maps
  onto its counter (`1 + floor((leaderTick - 1) × num / den)`), so its beats land on the
  leader's rather than free-running inside them. Shipped together with follow *targets* — a
  master may follow any other master, chains allowed and cycles refused — which is what made
  the cascade worth building rather than a special case for master 1. See
  `docs/fx-engineering.md` §"Usage routing and follow"
- `FU-BUSK-TARGET-CAP` — retired by deletion rather than by the batched query it asked for. The
  eight-slot fan-out existed to read effect presence off the FX list for the busk view's effect
  pads; those pads went when the view was cut back to the library pads its design draws, and both
  surviving pad kinds read the programmer's **layer stack**, which needs only the targets. No cap
  left to raise — lighting-react, busk-view design alignment

### 2026-08

- `FU-CUE-APPLYDATA-ONE-BUILDER` — landed inside sweep item C5, `ab8c791`. `CueStackManager.activateCueInStack` and `AiTools.applyCue` now call `buildCueApplyData`, which gained the four fade/auto-advance fields whose absence forced the second builder; guarded by `CueApplyDataBuilderTest`
- `FU-TEST-MULTI-CONN-CUEEDIT` — retired without implementation: sweep item D1 removed the
  `cueEdit.*` family, so there is no `beginEdit` for two connections to race on
- `FU-PROG-FOCUS-PREVIEW-LAYER` — retired without implementation: sweep item D4 removed the
  programmer's preview layer entirely (`installPreview`, the `isPreview` flag, and the
  `POST`/`DELETE /project/{id}/looks/preview` routes), so there is no unfiltered preview layer left
  for `focusLayer` to admit
- `FU-PAL-POSITIONAL-CONVERSION` — **closed done-differently.** The entry asked for a tool to convert
  positional `P1`/`P2` colour lists into named palettes, waiting on "a show maintaining the same
  colours in both forms". Templates (desk-simplification session 3) made that signal moot by making
  the *other* form strictly better, so the positional list was **deleted** rather than converted:
  gone are `PaletteCascade`, the `palette` column on `cues` / `cue_stacks` / `looks`,
  `Cue.updateGlobalPalette`, `FxEngine`'s global / per-cue / per-stack colour state and its flows,
  `PaletteSocket`, `cueEdit.setPalette`, the `set_palette` AI tool, the script API's
  `palette`/`setPalette`, `fx/effects/PaletteColourEffects.kt` (which was already dead), and the
  `P(\d+)` / `P*` grammar itself.

  In its place, an **effect colour parameter names a colour template**: `tmpl:{uuid}`, owned by
  `fx/TemplateColourSource.kt` and resolved through `TemplateResolver.resolveColourGeneric`. Four
  decisions are worth carrying forward. The seam is **`TypedParams`** — one place every colour
  parameter is read, already built around a supplier plus a version counter, so the swap was a
  rename of what it is given (`TemplateRegistry.version` steps into `paletteVersion`'s role).
  Resolution is **fixture-free but policy-honouring**, resolving as though the head were RGBW so a
  referenced template matches the same template applied as a layer on the common case. A reference is
  legal **only in a parameter**, never in a value — `parseAssignmentValue` returns null for one and
  `validateLookRows` rejects it beside `ref:` — which is what stops this becoming a second, weaker
  layer mechanism. And there is **no successor to `P*`**: a template holds one colour, so a colour
  list is an explicit ordered mix of literals and references.

  Two things fell out for free. `createInstanceFromPresetForCue` and `createInstanceFromPreset`
  collapsed into one: the fork existed because the cue-scoped palette supplier silently reached the
  global list when the cue was not live, and a `tmpl:` reference has one answer everywhere. And a
  **programmer layer's effects became live** — that path deliberately pinned its palette version to
  `0L`, because a Look's colour list was captured at include time; a template reference is a live
  dependency by design.

  Two things the removal needed beyond deleting code, both in `migratePositionalColourListsAway`.
  The columns had to be **dropped**, not merely un-modelled: `cues.palette` and `cue_stacks.palette`
  are `NOT NULL` with no default on every pre-removal database and `createMissingTablesAndColumns`
  only ever adds columns, so leaving them made every `DaoCue.new` / `DaoCueStack.new` fail — a break
  no test can see, because tests build the schema from the current model. And the stored `P1` / `P*`
  parameters had to be **inlined** into literals against the scope that used to resolve them
  (cue, else stack, else the historical red/green/blue), or a running chase would have come back as
  one static white with the list that held the answer already unreadable.

  One invariant to hold when adding a spawn site: `TemplateRegistry.snapshot` can fall back to a DB
  read, so every spawn path calls `prewarmTemplateColours` on the request thread. Positional lookups
  were pure memory reads; this is the only way the replacement is not like-for-like. Sync format
  version 7 (both constants); `MIN_SUPPORTED` stays at 5, since every removed field has a default.

- `FU-LOOK-STOMP-WITHIN-CUE` — per-layer `stomp` is read. `CueComposer.cook` now returns a
  `CookResult` rather than a bare row list, carrying `stompSuppression` (`layerId → targetKey →
  properties`) and `assertedKeys`; `FxInstance` gained `cueLayerId` alongside `programmerLayerId`,
  stamped at all three cue spawn sites; `FxEngine.isSuppressed` checks it per tick, **before** the
  programmer-band exemption, since a programmer-layer effect is in that band by construction and
  exempting it would have made programmer stomp a no-op. Suppression rather than removal, for the
  reason the entry gave: disabling the stomping layer only triggers a recook, so a removed instance
  would be unrecoverable — and keeping it alive means clearing a stomp restores it mid-phase.
  `LookStack`'s STOMP badge became a toggle in the same change, in both hosts, because a control
  writing a field the engine ignores is worse than none.

  Two things were decided rather than merely implemented. **Suppression is published with the
  rows**, as a parameter to `setCueAssignments` / `replaceCueAssignments`, so a republish cannot
  refresh values while leaving stale suppression behind; carrying it separately would have
  reintroduced the `CueApplyData`-two-builders shape the entry itself warns about. And the
  **programmer half is published from `materialise`, not `syncEffects`** — the latter deliberately
  does not rebuild on a mask, amount or order change, and all three move the suppression set.

  Also closed the pre-existing gap the entry named: `buildStompOverlapFromAssignments` read the
  cue's local rows alone, so a cue whose colour came entirely from a layer stomped nothing on
  colour. `buildStompOverlap` unions it with `CookResult.assertedKeys` — which records the group a
  row arrived *through* as well as the fixture, because `stompForCue` matches a group-targeted
  effect on the group's own name and cook's rows are per-fixture by construction.

- `FU-FE-LOOK-SAVE-GUARD-TEST` — covered by `LookEditor.test.tsx` (5 tests) and
  `lookSaveGuard.test.ts` (4) in looks-and-layers session 3b. The entry's "the first test here also
  establishes the harness" was the accurate part of it: the harness is mocked store hooks, per
  `LayersPane.test.tsx`, and once that was written the guard was cheap. Session 3b also found the
  *reason* the guard was needed was worse than recorded — RTK Query's `data` falls back to the
  previous argument's result while a new one is in flight, so editing Look A then opening Look B
  handed the editor A's rows under B's id, and Update would have written A's rows into B.
- `FU-FE-LOOK-WS-COMPAT-INVALIDATION` — `startLooksBridge` now invalidates `Fixture`/`GroupList`
  alongside `Look`/`LookList`, so a Look created, copied or deleted on another client is offered
  here immediately instead of being invisible to `LayerPicker` and `LookTogglePicker` until
  something unrelated refetched. **Recorded as a Trigger item on a mistaken premise and closed the
  same day.** The deferral rested on "widening the bridge makes every client refetch two lists on
  *any* library edit" — which is wrong: `lookListChanged` fires only on CRUD and metadata changes,
  because a *contents* edit goes through `republishForLookEdit` instead (`routes/projectLooks.kt`
  says so where it chooses between them). CRUD is exactly the set that moves `compatibleLookIds`
  membership and is far rarer than contents edits, so the cost that justified waiting was never
  there. The same review found the local half of the original fix was also wrong — `copyLook` gated
  on source-vs-target project, but those two lists belong to the *active* project and take no
  project argument, so the main "copy into this project" flow skipped the invalidation it existed
  to add.
- `FU-LOOK-HEALTH-ARM-CLEANUP` — `AssignmentHealth.PaletteTypeMismatch` deleted, with its
  `describeAssignmentHealth` case, the two stale KDoc references, and the frontend descriptor entry
  and both TS union members, in one change. `MissingPaletteEntry` is now the only diagnosis a failed
  reference gets, which is the honest one: a Look declares no attribute type, so "wrong type" was no
  longer a coherent complaint. Done in the looks-and-layers session-2 pass, which is what supplied
  the frontend half — and with `compileKotlin --rerun-tasks` first, per
  `sealed-subclass-delete-needs-rerun-tasks`
- `FU-PAL-PRESET-MAKE-HARD` — `POST /project/{id}/fx-presets/{presetId}/make-hard`, plus a
  reference banner in the preset editor and the referring presets named in the palette delete
  guard. The target-less problem is answered by never inventing a target set: a row hardens only
  where the palette gives one literal across every fixture of the preset's declared `fixtureType`,
  and a disagreement is reported with its variants. Found and fixed on the way: cue-level Make Hard
  reported every `position` reference as unresolvable, because it read the property catalogue
  directly instead of through `fixtureCategoryFor`'s pan/tilt alias. The cue half of the
  delete-guard loop retired with the palette-ref mechanics it concerned — see
  `docs/lighting-composition-model.md` §Hardening
- `FU-PROG-VIS-NEXTGO` — `Next GO` as a fourth stage vis source: a pushed `ChannelSource` over
  `POST /cue-stacks/{stackId}/preview`, overlaid on the wire so channels the cue doesn't assert
  keep their live values, keyed on the playhead stack's `nextCueId` from the WS-patched RTK
  cache — lighting-react `4872978`, see `lighting-react/docs/stage-vis-engineering.md`
- `FU-PROG-VIS-SOURCE` — Output / Output + Programmer / Programmer only across all three stage
  surfaces, via an injected `ChannelSource`; the item's premise that `ProgrammerState.channels`
  was the programmer's channel output was wrong (it is the sideband), so the programmer is
  resolved from its property entries instead. The 2D plot gained live values on the way. `Next GO`
  split out as `FU-PROG-VIS-NEXTGO` (landed, above) — lighting-react `c91294a`, see
  `docs/stage-vis-engineering.md`
- `FU-PROG-L3RESOLVER-RENAME` — `Layer3Resolver` → `CueAssignmentResolver`, the `*Layer3*`
  engine internals with it, and the layer numbers in the comments — `7d3711e`
- `FU-PERF-BLACKOUT-LATENCY` — latency accepted as expected behaviour: a blackout is no
  different to any other change in lighting state, so it gets no special transmit path. The
  dead `requestTransmit()` hook went with the decision — `cdd97c5`
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
- `FU-QUAL-KEY-CONVERGENCE` — `CueAssignmentResolver.Key` carries a `TargetRef`; `AssignmentKey` deleted —
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
