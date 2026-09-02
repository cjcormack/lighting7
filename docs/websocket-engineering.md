# WebSocket Protocol Engineering Documentation

The desk's real-time channel: one endpoint, one polymorphic message envelope, **96 message types**
(37 inbound, 59 outbound) across eleven domain families. This document is the inventory and the
rules that govern it.

The inventory below is generated from the `@SerialName` declarations, which are the wire contract.
To re-check it after adding a message:

```bash
grep -rn '@SerialName' src/main/kotlin/uk/me/cormack/lighting7/plugins/*.kt
```

Anything in `plugins/` carrying a `@SerialName` and extending `InMessage`/`OutMessage` is on the
wire and belongs in a table here.

## Conventions

These are the rules the socket settled on in the post-refactor sweep (backend item F5). They are
the WS counterpart of [`api-conventions.md`](api-conventions.md), and the same decision sits
behind them: **normalize hard, no aliases.** A renamed message is renamed, not dual-emitted —
there is one client, in one adjacent repo, so a compatibility alias would only be a second
spelling that never dies.

### Naming

Message names are **dotted namespaces**: `family.verb` — `speedMasters.state`,
`programmer.clearAll`, `surfaceLearn.begin`, `surfaceBank.bindingsChanged`. The family names the
client cache or subsystem the frame belongs to; it does **not** name the server component that
emits it (`speedMasters.listChanged` is fired from `BroadcastSocket.kt`'s fixtures listener, and
that is fine).

The flat `somethingHappened` names — `channelState`, `lookListChanged`, `cueRunStateChanged`,
`fixturesChanged` — are the older scheme. They are left alone deliberately: they are whole
families spelled consistently, and renaming them buys nothing but a client edit. What was *not*
left alone is a flat name sitting inside an otherwise-dotted family, because that is the one case
where a reader cannot tell which convention applies. Both were fixed:
`speedMasterListChanged` → `speedMasters.listChanged`, `surfaceBindingsChanged` →
`surfaceBank.bindingsChanged`.

New messages take the dotted form.

### Snapshot rule

**Every stateful family pushes its snapshot on connect.** A client should be able to render the
desk from the connect burst alone, without asking for anything.

The request messages (`channelState`, `fxState`, `programmer.state`, `speedMasters.state`,
`surfaceScaler.state`, …) stay, but their only remaining job is **explicit resync** — a tab
coming back from the background, a client that thinks it has drifted. They are not the way
initial state arrives, and a client that still asks on open just gets the frame twice.

Two mechanisms satisfy the rule, and only two:

- an explicit `scope.sendSnapshot { … }` in the family's `setupXxxSubscriptions`, or
- a subscription to a **`StateFlow`** (or a `combine` of them), which always carries a current
  value.

A **replay-1 `MutableSharedFlow` does not count.** Its replay cache is empty until something has
happened, so the snapshot arrives only on a desk where the thing has already happened once —
which is exactly the case a fresh client cannot detect. `ParkManager.parkStateFlow` and
`FxEngine.fxStateFlow` were both this, and are both `StateFlow` now, so "nothing is parked" and
"no effects are running" are values rather than silence.

`.drop(1)` is likewise not a way to suppress a connect frame. It suppresses the first *event*,
which on a flow whose replay cache happens to be empty is the first real one — the bug that used
to leave a WebSocket's fixtures listener bound to the outgoing project after the very first
project switch.

Pure event streams — `*ListChanged` invalidations, `cloudSync*`, `programmer.entryChanged`,
`speedMasters.changed` — have no snapshot and push nothing on connect. That is not an exception to
the rule; they simply are not state.

### Reply conventions

A write arriving over the socket is answered in one of three ways, and all three are in the
codebase today:

1. **Full family snapshot.** `speedMasters.setBpm` and `.tap` both answer `speedMasters.state`.
2. **Narrow ack or delta.** `removeFx` answers `fxChanged(REMOVED, id)`; `programmer.setBlind`
   answers `programmer.blindState`.
3. **No reply at all** — the mutation lands and the family's change stream carries it back, to
   this client along with every other. `surfaceBank.set`, `surfaceScaler.setBlackout`,
   `parkChannel` and `updateChannel` all work this way.

**For new operations, pick (3).** Everything this socket controls is shared desk state, so a
unicast reply is at best redundant with the broadcast and at worst the reason two tabs disagree:
a reply-only path leaves every other client stale, which is precisely the bug that put the
programmer layer stack on a broadcast subscription rather than trusting `handleProgrammer`'s
unicast reply. If a client needs confirmation that its own frame was the cause, that belongs in
the broadcast payload, not in a second private message.

(1) remains right where a write is a *retune* of something the client is holding a live model of
and the whole family is small — the speed-master bank is four rows. (2) is right for a failure
reply, where there is no state change to broadcast; see the `surfaceLearn.error` family.

## Connection

**Endpoint**: `ws://localhost:8413/api` — the only WebSocket route in the app.

**Plugin configuration** (`Application.configureSockets`, `plugins/Sockets.kt`):

```kotlin
install(WebSockets) {
    pingPeriod = 15.seconds
    timeout = 15.seconds
    maxFrameSize = Long.MAX_VALUE
    masking = false
    contentConverter = KotlinxWebsocketSerializationConverter(Json)
}
```

Keep-alive is Ktor's protocol-level ping; **there is no application-level `ping` message**, and a
client that sends one gets the undeserializable-frame path (logged and ignored), not a pong.

**Auth**: the upgrade is accepted and then closed with code **4401** if the desk has accounts and
the request carries no valid session cookie — a browser cannot read a 401 on an upgrade response,
but it can read a close code. Bootstrap-open mode (zero accounts) admits everyone, mirroring the
REST gate. The same 4401 is used for **live revocation**: `AuthService.revocations` is collected
per connection, so disabling an operator or resetting their password closes their already-open
socket instead of leaving it streaming. See [`desk-accounts.md`](desk-accounts.md).

## Architecture

One `webSocket("/api")` handler, one `SocketScope` per connection, and a file per domain. The
handler itself only routes; every family owns its messages, its handler and its subscriptions.

```
                        ┌──────────── WS frame in ────────────┐
                        ▼                                     │
 ┌───────────────────────────────────────────────────┐        │
 │ plugins/Sockets.kt — webSocket("/api")            │        │
 │                                                   │        │
 │  1. resolveSessionUser → close 4401 if no session │        │
 │  2. SocketScope(session, state, user)             │        │
 │  ── pre-warm-up band (touches no state.show) ──   │        │
 │  3. authService.revocations  → close 4401         │        │
 │  4. setupMachineSubscriptions                     │        │
 │  5. bootProgress.flow → bootProgressState, until  │        │
 │     isShowReady (return on FAILED)                │        │
 │  ── show-scoped band ──────────────────────────   │        │
 │  6. setupBroadcast/Park/Fx/Project/Surface/       │        │
 │     CloudSync/Programmer/SpeedMaster Subscriptions│        │
 │  7. for (frame in incoming) → handleXxx(scope, m) │────────┘
 └───────────────────────────────────────────────────┘
        │                              │
        │ scope.send(OutMessage)       │ scope.subscribe(flow) { … }
        ▼                              ▼
 ┌──────────────────┐   ┌───────────────────────────────────────────┐
 │ per-connection   │   │ sources                                   │
 │ session channel  │   │  Fixtures ── FixturesChangeListener ──────│→ BroadcastSocket
 │                  │   │  FxEngine.fxStateFlow / provenance.flow   │
 │                  │   │  ParkManager.parkStateFlow                │
 │                  │   │  ProgrammerStore.layersFlow / lastIncluded│
 │                  │   │  SpeedMasterBank.changes / .beats         │
 │                  │   │  ProjectManager.projectChangedFlow        │
 │                  │   │  MidiRegistry / DeviceMatcher / banks     │
 │                  │   │  State.machineEventsFlow                  │
 │                  │   │  State.cloudSyncEventsFlow                │
 └──────────────────┘   └───────────────────────────────────────────┘
```

**Two bands, and the split matters.** Session revocation and `setupMachineSubscriptions` are
registered *before* the boot warm-up gate, because they read nothing off `state.show`; a change
during warm-up would otherwise be lost rather than delayed, and a `FAILED` boot returns before the
show-scoped cluster is ever reached — leaving a desk whose show didn't start with no live account
administration, which is when you want it most. Everything else touches `state.show` (fixtures, FX
engine, programmer) and must wait for `isShowReady`.

**`FixturesChangeListener` is a per-project listener.** It hangs off `show/Fixtures.kt`, whose
instance is replaced wholesale on project switch, so `setupBroadcastSubscriptions` re-registers on
`projectChangedFlow` and returns an unregister function for teardown. Machine-scoped state
(accounts, install row, updates) deliberately does **not** ride it — see `MachineSocket.kt`.

## Domain families

| Family | File | In | Out | Handler | Subscriptions |
|---|---|---|---|---|---|
| Boot | `BootSocket.kt` | — | 1 | — | inline in `Sockets.kt` |
| Broadcast | `BroadcastSocket.kt` | — | 15 | — | `setupBroadcastSubscriptions` |
| Channel | `ChannelSocket.kt` | 4 | 3 | `handleChannel` | via Broadcast's listener |
| Cloud sync | `CloudSyncSocket.kt` | — | 7 | — | `setupCloudSyncSubscriptions` |
| FX | `FxSocket.kt` | 5 | 2 | `handleFx` | `setupFxSubscriptions` |
| Machine | `MachineSocket.kt` | — | 4 | — | `setupMachineSubscriptions` |
| Park | `ParkSocket.kt` | 3 | 1 | `handlePark` | `setupParkSubscriptions` |
| Programmer | `ProgrammerSocket.kt` | 11 | 9 | `handleProgrammer` | `setupProgrammerSubscriptions` |
| Project | `ProjectSocket.kt` | 1 | 2 | `handleProject` | `setupProjectSubscriptions` |
| Speed masters | `SpeedMasterSocket.kt` | 4 | 3 | `handleSpeedMasters` | `setupSpeedMasterSubscriptions` |
| Surfaces | `SurfaceSocket.kt` | 9 | 11 | `handleSurface` | `setupSurfaceSubscriptions` |

Four families are outbound-only and therefore have no dispatch arm in `Sockets.kt`: Boot,
Broadcast, Cloud sync and Machine. Channel is the odd one: its three messages are declared in
`ChannelSocket.kt` and answered there on request, but every *unsolicited* one is fired from
`BroadcastSocket.kt`'s `FixturesChangeListener`, which is also where its connect snapshot lives —
so the family has no `setupChannelSubscriptions` of its own.

## Client → Server (37)

Every inbound frame is `{ "type": "<name>", …fields }`. Fields with a default are optional.

### Channel — `ChannelSocket.kt`

| Message | Fields | Effect |
|---|---|---|
| `channelState` | — | Resync: replies `channelState` with the whole output buffer |
| `universesState` | — | Resync: replies `universesState` |
| `channelMappingState` | — | Resync: replies `channelMappingState` |
| `updateChannel` | `universe: Int`, `id: Int`, `level: UByte`, `fadeTime: Long` | Raw channel write, routed through the programmer. No reply — the change arrives via `channelState` |

`updateChannel` is a compatibility shim for the Channels debug view and legacy fixture sliders.
Slider- and setting-backed channels lift to a property-level programmer entry; a colour
sub-channel lifts to the whole `rgbColour` property (freezing the sibling components); position
axes and channels with no backing property stay channel-shaped in the programmer's sideband. All
three are released by Clear.

### Park — `ParkSocket.kt`

| Message | Fields | Effect |
|---|---|---|
| `parkState` | — | Resync: replies `parkState` |
| `parkChannel` | `universe: Int`, `channel: Int`, `value: UByte` | Parks a channel; lands on the next frame of that universe. No reply |
| `unparkChannel` | `universe: Int`, `channel: Int` | Releases the park. No reply |

### FX — `FxSocket.kt`

| Message | Fields | Effect |
|---|---|---|
| `fxState` | — | Resync: replies `fxState` |
| `removeFx` | `effectId: Long` | Replies `fxChanged(REMOVED, id)` |
| `pauseFx` | `effectId: Long` | Replies `fxChanged(UPDATED, id)` |
| `resumeFx` | `effectId: Long` | Replies `fxChanged(UPDATED, id)` |
| `clearFx` | — | Replies `fxChanged(CLEARED)` |

Adding and updating effects is REST (`POST /api/rest/fx/add`), not WS.

### Project — `ProjectSocket.kt`

| Message | Fields | Effect |
|---|---|---|
| `projectState` | — | Resync: replies `projectState` |

Switching project is REST; the socket only reports it (`projectChanged`).

### Speed masters — `SpeedMasterSocket.kt`

The desk's only WS tempo surface. A master is addressed by uuid, and `masterUuid` null/omitted
means **master 1** on every inbound message, for a client that has no uuid to hand yet. Note the
asymmetry: outbound frames report master 1 by its *real* uuid once the bank has loaded, so a
client can ask about master 1 with a null uuid but must not expect to recognise its frames by one.

| Message | Fields | Effect |
|---|---|---|
| `speedMasters.state` | — | Resync: replies `speedMasters.state` |
| `speedMasters.setBpm` | `bpm: Double`, `masterUuid: String?` | Retunes; replies with the full bank state (a `speedMasters.error` first if refused) |
| `speedMasters.tap` | `masterUuid: String?` | Tap tempo; replies with the full bank state (a `speedMasters.error` first if refused) |
| `speedMasters.requestBeat` | `masterUuid: String?` | One-shot: releases the next `speedMasters.beat` frame past the throttle. No reply |

A present-but-garbled or unknown `masterUuid` **drops** a tempo write rather than degrading it to
master 1 — a corrupt frame must not retune the global tempo — and answers a `speedMasters.error`
with `SPEED_MASTER_UNKNOWN`. A write to a *follower* (a master with a `followNum`/`followDen`
ratio, whose tempo is derived from master 1) is refused with `SPEED_MASTER_FOLLOWER`. The state
reply still goes out in every case, so a stale client re-syncs. CRUD (create / rename / delete) is REST, with a `speedMasters.listChanged`
invalidation broadcast.

### Programmer — `ProgrammerSocket.kt`

`targetType` is `fixture` or `group`; `targetKey` is that target's key. `value` uses the same
canonical grammar as a stored cue assignment: `"0".."255"` for sliders and settings, `"#rrggbb"`
(plus optional `w`/`a`/`uv` tags) for colours, `"pan,tilt"` for `position`. A programmer value is
always a **literal** — a `tmpl:{uuid}` reference is legal only in an effect parameter.

| Message | Fields | Reply |
|---|---|---|
| `programmer.set` | `targetType`, `targetKey`, `propertyName`, `value`, `fadeMs?`, `sourceGroup?` | `programmer.entryChanged` \| `programmer.error` |
| `programmer.setColour` | `targetType`, `targetKey`, `propertyName = "rgbColour"`, `r`,`g`,`b`, `w?`,`a?`,`uv?`, `fadeMs?`, `sourceGroup?` | `programmer.entryChanged` \| `programmer.error` |
| `programmer.setPosition` | `targetType`, `targetKey`, `pan`, `tilt`, `fadeMs?`, `sourceGroup?` | `programmer.entryChanged` \| `programmer.error` |
| `programmer.clearEntry` | `targetType`, `targetKey`, `propertyName`, `fadeMs?` | `programmer.entryCleared` \| `programmer.error` |
| `programmer.clearAll` | `fadeMs?` | `programmer.cleared` |
| `programmer.setBlind` | `blind: Boolean`, `fadeMs?` | `programmer.blindState` |
| `programmer.state` | — | `programmer.state` |
| `programmer.addLayer` | exactly one of `lookId`/`templateId`, `targets`, `propertyMask?`, `blendMode?`, `amount?`, `speedMasterUuid?`, `rateSpeedMasterUuid?`, `fadeMs?` | `programmer.layerState` \| `programmer.error` |
| `programmer.removeLayer` | `layerId: Int`, `fadeMs?` | `programmer.layerState` |
| `programmer.moveLayer` | `layerId: Int`, `toIndex: Int` | `programmer.layerState` |
| `programmer.patchLayer` | `layerId`, `enabled?`, `amount?`, `propertyMask?`, `blendMode?`, `targets?`, `stomp?`, `fadeMs?` | `programmer.layerState` \| `programmer.error` |

`sourceGroup` is for clients that fan a group-scoped gesture out to member fixtures rather than
sending `targetType: "group"` — a group virtual dimmer over heterogeneous members, a Highlight
release restoring per-fixture values. It is validated server-side, so a client cannot assert a
hint it has not earned. On `patchLayer`, a null field means "leave alone", not "clear".

The unicast replies above are the *acknowledgement*; the authoritative update reaches every tab
through `programmer.layerState`, `programmer.includeTarget` and `provenanceState`, which are
broadcast because the programmer is shared desk state.

### Surfaces — `SurfaceSocket.kt`

| Message | Fields | Reply |
|---|---|---|
| `surfaceLearn.begin` | `projectId: Int`, `deviceTypeKey?` | `surfaceLearn.started` |
| `surfaceLearn.cancel` | `sessionId: String` | `surfaceLearn.cancelled` \| `surfaceLearn.error` |
| `surfaceLearn.commit` | `sessionId`, `bank?`, `target: BindingTarget`, `takeoverPolicy?` | `surfaceLearn.committed` \| `surfaceLearn.error` |
| `surfaceBank.set` | `deviceTypeKey: String`, `bank: String?` | none — `surfaceBank.changed`/`.state` broadcast |
| `surfaceBank.state` | — | `surfaceBank.state` |
| `surfaceScaler.state` | — | `surfaceScaler.state` |
| `surfaceScaler.setBlackout` | `enabled: Boolean` | none — `surfaceScaler.state` follows |
| `surfaceScaler.setGrandMaster` | `enabled: Boolean` | none — `surfaceScaler.state` follows |
| `surfaceDevices.state` | — | `surfaceDevices.state` |

Learn sessions are **connection-owned**: `SocketScope.ownedLearnSessions` bounds the inbound event
broadcast so two `/surfaces` tabs don't see each other's captures, and teardown cancels any
session this connection started.

## Server → Client (58)

### Boot — `BootSocket.kt`

| Message | Payload | When |
|---|---|---|
| `bootProgressState` | `status: BootStatus` (`phase`, `message`, `percent`, `ready`, `error?`) | On connect, then on every change until `isShowReady` or `FAILED` |

Outbound-only, and the only frame a client sees before the show-scoped families come up. A
`FAILED` boot sends the terminal frame and returns without wiring show subscriptions.

### Broadcast — `BroadcastSocket.kt`

Fired from the per-project `FixturesChangeListener`. Everything here except `showChanged`,
`cueRunStateChanged` and `cuesRecomposed` is a **payload-free cache invalidation**: the client
refetches over REST.

| Message | Payload | Meaning |
|---|---|---|
| `lookListChanged` | — | A Look was created, renamed or deleted (**not** contents — that pushes `provenanceState`) |
| `templateListChanged` | — | A template was created, renamed or deleted |
| `cuesRecomposed` | `cueIds` | A Look/template **contents** edit changed what these cues compose to |
| `cueListChanged` | — | Cue CRUD |
| `cueStackListChanged` | — | Cue-stack CRUD |
| `cueSlotListChanged` | — | Cue-slot CRUD |
| `patchListChanged` | — | Patch CRUD |
| `riggingListChanged` | — | Rigging CRUD |
| `stageRegionListChanged` | — | Stage-region CRUD |
| `speedMasters.listChanged` | — | Speed-master CRUD only; live BPM rides `speedMasters.changed` |
| `scriptListChanged` | — | A script was created, renamed, edited or deleted |
| `fxDefinitionListChanged` | — | A user-defined effect was created, edited or deleted |
| `fixturesChanged` | — | Fixtures re-registered; `channelMappingState` follows immediately |
| `promptBookChanged` | — | Prompt-book content changed |
| `showChanged` | `projectId`, `activeStackId?`, `activeStackName?` | The active show/stack moved |
| `cueRunStateChanged` | `projectId`, `stackId`, `activeCueId?`, `nextCueId?`, `nextIsArmed`, `transition`, `fadeDurationMs?`, `fadeElapsedMs?`, `autoAdvance`, `autoAdvanceDelayMs?` | One frame per transition; the client animates the fade locally |

`cuesRecomposed` is the one broadcast that is keyed rather than payload-free, and the reason is the
rule its two neighbours state: `lookListChanged` / `templateListChanged` are deliberately **not**
fired for a contents edit, because a client treats them as "drop every cached expansion" and a
retune would be an invalidation storm. A retune does still change what the cues layering that record
compose to, so it gets its own frame naming them — a handful of ids, at save cadence, refreshing
exactly those reads.

Its ids are **every cue layering the edited record**, which is deliberately wider than the
`cuesRepublished` field on the REST responses (`lookRepublish.kt`, `programmerRoutes.kt`,
`lookRecord.kt`): that field is the live cues whose Layer 4 rows `republishForSourceEdit` actually
replaced, while `GET /cues/{id}/cooked` composes on read, so a dark cue reads stale from the same
edit. The frame answers "what should be re-read", the REST field answers "what moved on stage";
they are different questions and only accidentally the same list. **That is why the frame is
`cuesRecomposed` and not `cuesRepublished`** — the two names sat one line apart in
`republishForSourceEdit` during review and were read as the same set twice.

`cueRunStateChanged` is snapshotted on connect for every stack with run state, and the snapshot is
captured **synchronously** at setup and only *sent* from the launched coroutine — reading it inside
the coroutine would describe whenever it happened to be scheduled, which can be after a GO the
listener has already queued a `transition = true` frame for.

### Channel — `ChannelSocket.kt`

| Message | Payload | When |
|---|---|---|
| `channelState` | `channels: [{universe, id, currentLevel}]` | Connect snapshot (whole buffer, parked values overlaid), then per-change deltas |
| `universesState` | `universes: [Int]` | Connect snapshot; on `controllersChanged` |
| `channelMappingState` | `mappings: {universe: {channel: {fixtureKey, fixtureName, description}}}` | Connect snapshot; after `fixturesChanged` |

### Park — `ParkSocket.kt`

| Message | Payload | When |
|---|---|---|
| `parkState` | `channels: [{universe, channel, value}]` | Every emission of `ParkManager.parkStateFlow` — a `StateFlow`, so the empty set is a value and arrives on connect |

### FX — `FxSocket.kt`

| Message | Payload | When |
|---|---|---|
| `fxState` | `activeEffects: [EffectDto]` | Every emission of `FxEngine.fxStateFlow` (a `StateFlow`), and as the reply to a `fxState` request |
| `fxChanged` | `changeType: added\|removed\|updated\|cleared`, `effectId?` | Unicast ack for the four FX writes |

`fxState` is purely an effect frame. It carried `bpm` / `isClockRunning` before the speed-master
bank existed; tempo now lives on `speedMasters.*`, per-master and keyed. `EffectDto` is defined in
`fx/EffectDto.kt` and is the same object `GET /api/rest/fx/active` returns.

### Project — `ProjectSocket.kt`

| Message | Payload | When |
|---|---|---|
| `projectState` | `projectId`, `projectName`, `description?` | Connect snapshot, and the reply to a `projectState` request |
| `projectChanged` | `previousProjectId?`, `newProjectId`, `newProjectName` | Purely an event — fires on switches only |

### Speed masters — `SpeedMasterSocket.kt`

| Message | Payload | When |
|---|---|---|
| `speedMasters.state` | `masters: [{uuid?, index, name, bpm, isRunning, source, usage?, followNum?, followDen?}]` | Connect snapshot, on request, and as the reply to every write |
| `speedMasters.changed` | `masterUuid?`, `index`, `bpm`, `source`, `timestampMs` | Live BPM push, at tap rate — a follower's derived moves ride this like any other |
| `speedMasters.beat` | `masterUuid?`, `index`, `beatNumber`, `bpm`, `timestampMs` | Every 16 beats (~8 s at 120 BPM), plus any `speedMasters.requestBeat`, plus the first beat after that master's tempo moves |
| `speedMasters.error` | `masterUuid?`, `code`, `message` | Unicast failure ack for a refused tempo write (`SPEED_MASTER_FOLLOWER`) or a dropped one (`SPEED_MASTER_UNKNOWN`); always followed by the state reply |

`source` is `MANUAL` or `TAP`. `uuid`/`masterUuid` is null only for the synthetic pre-load master 1.
`usage` and the `followNum`/`followDen` ratio are the routing/follow settings from the busk-view
work — all additive with null defaults, so a pre-follow client decodes today's bank untouched.
Beats are throttled deliberately: the client runs a local timer off `bpm` between frames and only
needs the server to correct its drift. A tempo move is the exception — it makes that timer wrong
about the *rate* rather than merely drifted — so `speedMasters.changed` arms the same one-shot
`requestBeat` uses and the next beat frame goes out immediately.

### Machine — `MachineSocket.kt`

Machine-scoped, so registered in the pre-warm-up band and unaffected by project switches.

| Message | Payload | Recipients |
|---|---|---|
| `userListChanged` | — | **Every** socket |
| `ownAccountChanged` | — | Only sockets belonging to the changed account (and bootstrap-open sockets) |
| `installChanged` | — | Every socket |
| `updateStateChanged` | `phase`, `availability`, `latestVersion?`, `downloadedBytes`, `totalBytes?` | Every socket |

The account frames are payload-free **deliberately, not just by convention**: sockets are open to
operators while `/api/rest/users` is admin-only, so a body here would leak exactly what that gate
withholds. `ownAccountChanged` is sent first, so a demoted admin flips `isAdmin` before it
refetches the user list and takes a 403.

`updateStateChanged` is the one payload-carrying exception, and only to the payload-free half of
the convention: for an installer download the frame *is* the progress, and a bare "something
changed" at 2 Hz would mean an HTTP round-trip per tick for a several-hundred-megabyte transfer.
It carries no user data. Disabling and deleting an account are felt through a different mechanism
entirely — they revoke sessions, and the socket closes 4401.

### Cloud sync — `CloudSyncSocket.kt`

Emitted from REST handlers via `State.cloudSyncEventsFlow`; outbound-only, transitions only.

| Message | Payload |
|---|---|
| `cloudSyncStarted` | `projectId` |
| `cloudSyncDone` | `projectId`, `outcome`, `headSha`, `pushed`, `pulled`, `replaced`, `message` |
| `cloudSyncFailed` | `projectId`, `errorCode`, `message` |
| `cloudSyncConflictsPending` | `projectId`, `sessionId`, `conflictCount` |
| `cloudSyncLogAppended` | `projectId`, `entry: SyncLogEntryDto` |
| `cloudSyncProjectImported` | `projectId`, `projectUuid`, `name` |
| `oauthIdentityChanged` | `provider`, `connected`, `login?`, `accessExpiresAtMs?`, `refreshExpiresAtMs?`, `reauthRequired` |

`oauthIdentityChanged` is a nudge, not the detail: clients invalidate their identity cache and
re-read `GET /oauth/github/identity`. See [`sync-engineering.md`](sync-engineering.md).

### Programmer — `ProgrammerSocket.kt`

| Message | Payload | Cast |
|---|---|---|
| `programmer.state` | `blind`, `entries: [ProgrammerEntryDto]`, `channels: [ProgrammerChannelDto]`, `lastIncluded?`, `layers: [ProgrammerLayerDto]` | Connect snapshot + reply |
| `programmer.entryChanged` | `targetType`, `targetKey`, `propertyName`, `value` | Unicast reply |
| `programmer.entryCleared` | `targetType`, `targetKey`, `propertyName` | Unicast reply |
| `programmer.cleared` | `cleared: Int`, `effectsCleared: Int` | Unicast reply |
| `programmer.blindState` | `blind: Boolean` | Unicast reply |
| `programmer.layerState` | `layers: [ProgrammerLayerDto]` | **Broadcast** — every tab, on `layersFlow` |
| `programmer.includeTarget` | `target: IncludedTargetDto?` | **Broadcast** — set by Include or Record, cleared by Clear |
| `programmer.error` | `message: String` | Unicast reply |
| `provenanceState` | `entries: [ProvenanceEntryDto]`, `programmerRevision: Long` | **Broadcast** — on every layer event, coalesced to ≤1 per 50 ms |

The three broadcast frames are broadcast for the same reason: the programmer is shared, so a
second tab reordering the stack or pressing Include must not leave the first showing a stale view.
Without the `layersFlow` subscription, a mutation that moved no value pushed no `provenanceState`
either, and other tabs kept a stale layer list indefinitely.

`ProgrammerEntryDto` carries `targetKey`, `propertyName`, `value` (the canonical literal), `owner`,
`touched`, `sourceGroup?` and `owners` (every owner holding the property, most recent first).
`ProvenanceEntryDto` carries `targetKey`, `propertyName`, `source`
(`PARKED`|`PROGRAMMER`|`EFFECT`|`CUE`), `cueId?`, `cueStackId?`, `effectId?`, `layerId?` and
`layerSource?` — the last two so the desk can answer "why is this fixture this colour?" by naming
*Warm Wash* rather than *a cue*.

`provenanceState` doubles as the client's cue to refetch `programmer.state` — it is the one
broadcast that fires for a programmer write made by a MIDI surface, a locate, or another tab.
A crossfade's weight ticks are layer events too, so a running fade republishes provenance at up
to ~20 Hz; each frame carries `programmerRevision`, a monotonic count of the triggers that could
have moved the programmer's value set, which a weight-only republish (winner maps carried forward
unchanged) does not bump — so the client refetches only when the revision moved, not ~10×/s for
the whole fade. It is a counter on every frame rather than a per-frame flag because the broadcast
flow is replay-1 + DROP_OLDEST and a slow tab can skip frames mid-fade: whatever frame does
arrive carries the latest revision, so an off-connection write is never stranded. A frame
omitting the field (an older server) makes the client refetch every frame — the old behaviour.

On a warm desk the connect snapshot and the replayed flow value can each deliver
`programmer.includeTarget`, `programmer.layerState` and `provenanceState` twice. Harmless: all
three are idempotent, and the client coalesces repeated provenance frames into one debounced
refetch.

### Surfaces — `SurfaceSocket.kt`

| Message | Payload | Cast |
|---|---|---|
| `surfaceLearn.started` | `sessionId`, `projectId`, `deviceTypeKey?`, `deadlineMs` | Unicast reply |
| `surfaceLearn.captured` | `sessionId`, `projectId`, `deviceTypeKey`, `controlId` | Owning connection only |
| `surfaceLearn.committed` | `sessionId`, `bindingId`, `projectId` | Unicast reply |
| `surfaceLearn.cancelled` | `sessionId`, `reason` (`cancelled` \| `timeout`) | Owning connection only |
| `surfaceLearn.error` | `sessionId?`, `message` | Unicast reply |
| `surfaceBank.bindingsChanged` | `projectId`, `changeType: added\|updated\|removed\|reloaded`, `bindingId?` | Broadcast |
| `surfaceBank.state` | `activeBanks: {deviceTypeKey: bank}` (null values elided) | Connect snapshot + broadcast |
| `surfaceBank.changed` | `deviceTypeKey`, `previousBank?`, `newBank?` | Broadcast delta |
| `surfaceScaler.state` | `blackoutEnabled`, `grandMasterEnabled` | Connect snapshot + broadcast |
| `surfacePickup.changed` | `displayKey`, `controlId`, `state`, `target?` | Broadcast — soft-takeover pickup indicator |
| `surfaceDevices.state` | `devices: [{displayKey, displayName, typeKey?, isMatched, hasInputPort, hasOutputPort, activeBank?}]` | Connect snapshot + broadcast |

`surfaceBank.changed` carries previous→new only, so a client that never saw a switch has nothing to
render from — hence `surfaceBank.state` as its own frame, taken as a subscription to the `active`
`StateFlow` rather than a one-shot read, so a switch landing between snapshot and subscription
isn't lost by both.

`surfaceScaler.state` re-subscribes through `flatMapLatest` off `projectChangedFlow`, because
`state.show.globalScalerState` is re-created on project switch and a plain `combine` at connect
time would observe the previous project's facade forever.

## Connection lifecycle

### On connect

1. Session-cookie auth check; unauthenticated sockets are accepted and then closed `4401`.
2. `SocketScope` opened; the revocation stream is subscribed so a session revoked mid-boot closes.
3. Machine-scoped subscriptions registered (`setupMachineSubscriptions`) — these predate the
   warm-up gate because they read nothing off `state.show`.
4. `bootProgressState` sent, then streamed until `isShowReady`; a `FAILED` boot returns here.
5. `SocketConnection` created and added to the global `connections` set.
6. Each domain's `setupXxxSubscriptions` runs: the `FixturesChangeListener` is registered, live
   subscriptions are opened, and **every stateful family's snapshot is pushed** (§"Snapshot rule").
   The client needs to request nothing.

The snapshot burst has **no guaranteed order**: the families are set up in separate coroutines, so
a client must key off message type, never position. A client may also receive machine frames while
still showing the boot overlay — harmless, since the invalidations are idempotent.

### Message loop

```kotlin
for (frame in incoming) {
    try {
        when (val message = converter?.deserialize<InMessage>(frame)) {
            is ChannelInMessage -> handleChannel(scope, message)
            is ParkInMessage -> handlePark(scope, message)
            is FxInMessage -> handleFx(scope, message)
            is ProjectInMessage -> handleProject(scope, message)
            is SurfaceInMessage -> handleSurface(scope, message)
            is ProgrammerInMessage -> handleProgrammer(scope, message)
            is SpeedMasterInMessage -> handleSpeedMasters(scope, message)
            null -> System.err.println("WS /api: undeserializable frame ignored")
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { /* logged */ }
}
```

The per-message guard is load-bearing: one bad frame (unknown universe, stale fixture key,
malformed payload) must not tear down the operator's whole socket.

### On disconnect

1. Connection removed from `connections`.
2. `scope.cancelAll()` cancels every subscription and pending snapshot job.
3. Learn sessions this connection owns are cancelled.
4. The fixtures listener is unregistered from whatever `Fixtures` instance is current — the project
   may have switched mid-connection, which is why `setupBroadcastSubscriptions` returns a closure
   rather than the caller holding the instance.

A send racing teardown becomes a quiet `CancellationException` (`SocketScope.send`), so collectors
unwind instead of pumping frames into a dead socket. Anything else — a serialization bug in an
`OutMessage` — stays loud and fails the session scope, so a broken message type can't silently
stale the UI.

## Real-time channel updates

When DMX values change anywhere in the system:

```
ArtNetController
    │ channelChanged
    ▼
Fixtures (ChannelChangeListener)
    │ channelsChanged(universe, changes)
    ▼
FixturesChangeListener (one per WebSocket, BroadcastSocket.kt)
    │ scope.send
    ▼
WebSocket client
```

Each connected client receives only the channels that changed:

```kotlin
override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {
    if (universe.subnet != 0) return
    fire(ChannelStateOutMessage(changes.map { ChannelState(universe.universe, it.key, it.value) }))
}
```

The connect snapshot is the whole buffer with parked values overlaid, so clients see what fixtures
are actually emitting rather than the underlying buffered value.

**Subnet limitation**: only subnet 0 is on the wire. Universe numbers are sent as-is (0–15 within
subnet 0); frames for other subnets are dropped at the listener.

## Thread safety

- `connections`: `Collections.synchronizedSet`.
- `SocketScope.pendingBeatRequests` / `ownedLearnSessions`: synchronized sets, mutated from both
  the frame loop and subscription collectors.
- `FixturesChangeListener` callbacks are **non-suspending and may arrive on the DMX transmission
  thread**; `BroadcastSocket.fire` bridges them with `session.launch { scope.send(…) }`, which is
  safe because `DefaultWebSocketServerSession` is its own `CoroutineScope`.
- Every subscription job is tracked by `SocketScope.subscribe`/`sendSnapshot`, so teardown is one
  `cancelAll()` rather than per-job bookkeeping — historically a source of forgotten cleanups.

## Message serialization

kotlinx.serialization polymorphism over two sealed roots, with a per-domain sealed layer between:

```kotlin
@Serializable sealed class InMessage
@Serializable sealed class OutMessage

@Serializable sealed class FxInMessage : InMessage()

@Serializable
@SerialName("removeFx")
data class RemoveFxInMessage(val effectId: Long) : FxInMessage()
```

`@SerialName` is the JSON `type` discriminator. The intermediate sealed class is what lets the
top-level dispatcher enumerate *domains* while each `handleXxx` matches its own leaves
exhaustively — the compiler then refuses to let a new message ship without a handler.

## Adding a domain

One file, four edits:

1. Define `sealed class XxxInMessage : InMessage()` (and `XxxOutMessage : OutMessage()`) with the
   leaf messages and their `@SerialName`s.
2. Add `handleXxx(scope, message)` with an exhaustive `when`.
3. Add `setupXxxSubscriptions(scope)` if the family has live state — and push its connect snapshot
   there, per §"Snapshot rule".
4. Add one arm to the dispatch `when` in `Sockets.kt` and one call to setup.

Register in the **pre-warm-up band** only if the family reads nothing off `state.show`; everything
show-scoped goes after the gate. Then add the family to the tables above.

## File reference

| File | Purpose |
|---|---|
| `plugins/Sockets.kt` | Plugin config, `/api` endpoint, auth + warm-up gates, frame dispatch, teardown |
| `plugins/SocketScope.kt` | Per-connection context: `send`, `subscribe`, `sendSnapshot`, `cancelAll` |
| `plugins/SocketMessages.kt` | The `InMessage` / `OutMessage` sealed roots |
| `plugins/BootSocket.kt` | `bootProgressState` |
| `plugins/BroadcastSocket.kt` | `FixturesChangeListener` wiring and the 15 broadcast frames |
| `plugins/ChannelSocket.kt` | DMX channel state, mapping, `updateChannel` programmer shim |
| `plugins/CloudSyncSocket.kt` | Sync lifecycle and OAuth identity frames |
| `plugins/FxSocket.kt` | Active-effect state and the four FX writes |
| `plugins/MachineSocket.kt` | Accounts, install row, update state (machine-scoped band) |
| `plugins/ParkSocket.kt` | Park state and park/unpark writes |
| `plugins/ProgrammerSocket.kt` | Programmer values, layer stack, include target, provenance |
| `plugins/ProjectSocket.kt` | Current project and switch events |
| `plugins/SpeedMasterSocket.kt` | Per-master tempo: state, BPM writes, tap, beat stream |
| `plugins/SurfaceSocket.kt` | MIDI learn, banks, scaler, devices, pickup |
| `plugins/ErrorHandling.kt` | REST `StatusPages` net — not on the WS path, listed only because it shares the package |
| `plugins/HTTP.kt` | OpenAPI / Swagger UI config — likewise not WebSocket |
| `show/Fixtures.kt` | The `FixturesChangeListener` interface itself |

## Related documentation

- [API Conventions](api-conventions.md) — the REST counterpart of §"Conventions"
- [Desk Accounts](desk-accounts.md) — the 4401 close path and live revocation
- [FX System](fx-engineering.md) — what `fxState` and the `speedMasters.*` family describe
- [Composition Model](lighting-composition-model.md) — what `provenanceState` is reporting on
- [Cloud Sync](sync-engineering.md) — the `cloudSync*` lifecycle
