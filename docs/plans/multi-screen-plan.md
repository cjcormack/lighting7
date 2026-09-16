# Multi-screen desk — two screens and an iPad on one programmer

> **Document status: IN PROGRESS.** Session 1 landed 2026-09-16 — af3575a here, f8ce5f9 in
> lighting-react; sessions 2–4 are open. Proposed 2026-09-15. The visual design is checked in
> beside this plan at [`multi-screen-design/`](multi-screen-design/INDEX.md): nine generated
> artboards (a survey of five consoles, the model, the three screens at one moment, the Screens
> sheet, the hand, the three options). The live canvas at
> <https://claude.ai/artifact/CrvaTqN1Jun3HaPpMAy9ow> is a convenience copy, private to Chris; the
> checked-in files are the authority. This document is the engineering half: the model, the
> decisions and their reasons, and the session split. Where wording here and the artboards
> disagree, this plan wins on behaviour and the artboards win on layout and copy. The record made
> five calls for Chris to confirm; each is either taken as made below or overturned in §2 with the
> code that overturned it.

## 1. Context

The theatre desk runs two touch screens, and with MagicQ both are the app full screen. The
equivalent here is a full-screen browser window per screen, plus an iPad. The brief was three
asks — controls to go full screen; select on one window and act on another; drag and drop between
windows — plus what the consoles do and where the selection should live.

What already exists, and it is most of the architecture. The fixture selection is a **desk fact**:
`state/DeskSelection.kt` holds one `StateFlow<List<CueTargetDto>>` per desk, cleared on project
switch (`State.kt` ~811, in the `projectChangedFlow` collector beside `encoderBankState` and
`buskPageState`), pruned on fixture and patch reload, broadcast as `selection.state` by
`plugins/SelectionSocket.kt` — a `StateFlow` subscription, so the connect snapshot is free — and
written by `selection.set` / `.toggle` / `.clear` with no reply. On this side `api/selectionApi.ts`
and `store/selection.ts` mirror it as one RTK cache entry, `useDeskSelectionBridge.ts` wires the
programmer list's row selection to it two-way with an echo FIFO, `useBuskingSelection.ts`
rehydrates it for the busk band, and the X-Touch's select buttons toggle it through
`SurfaceActions.selectTarget`. The showing busk page is a second desk fact of the same shape
(`state/BuskPageState.kt`, `busk.pageState` / `busk.setPage`), and it is the precedent for one
window moving another's state: a *next page* button on the surface moved a second browser's tab
in the midi-surface plan's check 7. Blind, the fade, the template recents and the programmer's
layer stack are desk facts too. What is a **window fact** today: the programmer scope (Output ·
Local · a layer — `ProgrammerScope.tsx`, deliberately not persisted), the cell marquee and its
editors, the filter text, the busk view's edit mode.

What the record found: the work is three more desk facts of the same shape — the **attribute
mask** on the selection, a **registry of windows**, and **the hand** (a desk-owned held item) —
one window fact (**follow / local**), and one nameable gesture per ask. Nothing is sent between
windows; every window talks to the desk and the desk answers everyone. The survey's three
takeaways stand: every console makes a second screen a view on one programmer; a remote with its
own programmer (MA, Titan) is a second-*person* feature; nobody drags across screens — the hand's
precedents are the Move key and the mobile clipboard, and the edge drag is the one genuinely new
thing.

Three things the record states that the code does not bear out, each corrected in §2:

- **"The pad press already reads the desk selection server-side."** Only the **MIDI** door does
  (`SurfaceActions.pressPad` passes `state.deskSelection.targets.value` to
  `BuskPressService.pressByUuid`). The HTTP door — `POST /busk/pads/{padId}/press` — takes
  `BuskPressRequest.targets` from the body, and `BuskingView.tsx:179` sends the targets it holds
  (`selectedLayerTargets`, this tab's reading of the desk frame). The same is true of
  `/templates/{id}/apply`, `/templates/{id}/toggle`, `/looks/{id}/toggle` and `programmer.addLayer`:
  the selection is what the *clients* pass them (midi-surface plan §3.2, by decision). So the mask
  cannot ride the desk fact alone; it has to ride the press (D4).
- **"A flag in that tab's localStorage."** `localStorage` is per origin per browser profile, and
  the two desk screens are two windows of one profile — a flag or a window name kept there would
  be one value for both screens. Per-tab state on this desk is `sessionStorage` (D8, D10).
- **"Per target, the cell set."** A marquee is a rectangle (`useCellMarquee` sweeps rows ×
  column bands), so its cells are targets × families; a ragged set arises only from ⌘-union, and
  a layer's `propertyMask` — the mechanism a masked press lands through — is per layer, not per
  target. The wire carries `families` beside `targets`, not a cell set (D3).

## 2. Decisions taken

- **D1 — follow the desk by default, unlink per window.** *Record call 1, taken.* Every console
  makes a second screen a view on one programmer; a per-user programmer is a multi-person feature
  and stays a later desk fact (§7). A window that starts unlinked would look broken — the band on
  the other screen stays dark. The plain lists (`/fixtures/list`, `/groups/list`) stay unlinked and
  draw no chip, which is today's rule: `useDeskSelectionBridge` is enabled only for the `programmer`
  scope, because those two are browsing surfaces whose selection scopes a Record.
- **D2 — the mask is a property of the selection: one desk fact, not two.** *Record call 2,
  taken, with the rule stated.* A mask without targets means nothing, and clearing the selection
  must clear it. The rule that makes it one fact: **`set` replaces the whole fact** (targets and
  families; an absent `families` is *all attributes*), **`toggle` edits the heads and keeps the
  mask**, **`clear` clears both**. So a marquee publishes both halves; a tap on the busk band or an
  X-Touch select button adds a head under whatever mask is standing; the narrow-width picker's
  `selectTarget` and a `SelectTarget(REPLACE)` button replace the heads and, having no column axis
  to speak with, clear the mask. `prune()` keeps it. The alternative — a second fact with its own
  frame — would need every `set` to say whether it meant the mask too, and would let the two
  disagree on connect.
- **D3 — `families`, not cells.** The frame carries `families: [COLOUR]` beside `targets`. A ⌘-union
  of two rectangles over different columns publishes the union of both — an accepted approximation,
  because the press it feeds can only apply one `propertyMask` per layer anyway (§3.3). Stated on
  `SelectionBar`'s family pill, which already draws `cellFamilies(cells)` — the union — today.
- **D4 — the mask rides the press, and the press routes learn one field.** Because every HTTP
  press takes explicit `targets` (§1), each gains an optional `families` beside them, and the
  client sends the pair it is acting on — the desk's, or its own when local (D8). Server-side the
  two MIDI doors pass `deskSelection.families` in the same way. `BuskPressService.apply`,
  `ProgrammerLayerStack.toggle` and `applyTemplateToProgrammer` take a `families:
  Set<PropertyMaskGroup>?`; null is *all*. Reading the desk fact inside the route instead would
  break the unlinked window, whose targets are not the desk's.
- **D5 — a masked press lands through the layer's own `propertyMask`, and a press outside the
  mask is refused by name.** A template is one family: family ∉ mask → 400 `TEMPLATE_OUTSIDE_MASK`
  (the same posture as `TEMPLATE_NEEDS_SELECTION` — nothing would land, and a 200 that lit nothing
  is the dead-pad reading the service already refuses). A Look spans families: the layer is added
  with `propertyMask = mask ∩ look.families`, so rows outside it are **skipped by the cook**, the
  response names the families skipped, and a Look with nothing inside the mask is 400
  `LOOK_OUTSIDE_MASK`. A cue **ignores** the mask — it has no targets to be masked on. A `Sel ·
  property` fader **ignores** it: the control names its own attribute, as a cell edit is not masked
  by a marquee elsewhere. Full table in §3.3.
- **D6 — the skip is reported on the pressing window, not the marquee's.** The press response is
  unicast to whoever pressed; that window toasts *Position rows skipped — the selection is Colour*.
  The window that made the marquee learns the way it learns every layer: `programmer.layerState`
  carries the new layer with its mask, and `LookStack` already draws a mask badge. A broadcast
  "press outcome" frame would be a new family whose only reader is a toast; not built.
- **D7 — `source` is who moved the selection last, and a press does not touch it.** The record's
  "a press on any window clears the name" would make every press route write to the selection for
  a chip that is already quiet on the window that moved it. Last-mover is true information; a chip
  reading *from Screen 1* an hour later is still correct. `source` is `{windowId, name}` from the
  writing socket's announced identity, or `{kind: 'surface', name}` for a MIDI write, or null
  after a project switch.
- **D8 — follow / local is a per-tab `sessionStorage` fact, default on, and it gates both
  directions of the bridge.** `localStorage` is shared by both desk windows (§1). The flag is the
  `enabled` of `useDeskSelectionBridge` — publish *and* apply, since one bridge is both — and for
  the busk view the `useBuskingSelection` hook's arm: desk cache when following, a tab-local Map
  when not, with pad presses sending whichever is live. **Unlinking snapshots the desk's selection
  into the local copy and leaves the desk's alone; re-linking adopts the desk's** rather than
  publishing the local one — the bridge's "never publish on mount" rule, for its reason: a window
  joining must not clear what another screen has selected. Record scopes on the container's
  `selectedRowIds` today and keeps doing so, so an unlinked window records what it shows.
- **D9 — a window is a socket carrying a client-minted identity.** `windowId` is a uuid minted
  once per tab into `sessionStorage`, so it survives reload and reconnect and is never shared by
  two windows of one profile; the registry entry lives exactly as long as the socket
  (`SocketScope`'s lifetime, the scope `pendingBeatRequests` and `ownedLearnSessions` already use)
  and is re-announced on every open — the one kind of `open` branch CLAUDE.md §"Where a WS bridge
  subscribes" allows: re-sending what the *server* forgot, as `speedMastersWsApi` re-sends its
  beat subscriptions. A duplicated tab copies `sessionStorage` and appears as a second window with
  the same name; the registry keys by socket, so nothing breaks and the sheet shows two rows.
- **D10 — a window's name is durable when it arrives in the launch URL.** `?window=Screen%202` on
  the URL a shortcut, a home-screen icon or the Screens sheet's *Copy link* mints is read once at
  boot into `sessionStorage` and stripped. A tab opened by hand gets *Window* plus a short suffix and
  can be renamed for the life of the tab. This is what makes *Open on Display 2* and *Add to Home
  Screen* name their windows without a per-browser store the two desk screens would share.
- **D11 — `windows.show` and `windows.rename` are keyed command frames, broadcast, acted on by the
  window whose id they name.** The pattern of `busk.layoutChanged {pageIds}`: an id that is not
  this window's matches nothing. Broadcast rather than unicast so the handler needs no session
  lookup and the Screens sheet on every window sees the gesture. A command that lands while the
  target is disconnected is lost, and that is visible: its `view` in `windows.state` does not move.
- **D12 — the hand, not a pointer drag, is the cross-window move; "place" is the existing
  mutation plus `hand.drop`.** *Record call 3, taken.* Every target already has a mutation with its
  own validation — `POST /busk/banks/{bankId}/pads` appends a pad, `assignCueSlot` fills a slot,
  `programmer.addLayer` adds a layer, `patchProjectCue` through `buildCueInput` adds a cue layer —
  so a server-side `hand.place` would reimplement four of them behind one frame. The wire is
  `hand.state` / `hand.pickUp` / `hand.drop`; a place is the placing window's mutation followed by
  `hand.drop`, and Undo is that window's inverse mutation, held for ten seconds.
- **D13 — installed for the desk screens, the in-app item for ad-hoc use and the iPad.** *Record
  call 4, taken, with two constraints the record did not name.* Installation, Keyboard Lock and
  the Window Management API are all **secure-context** features (MDN), and the desk serves plain
  HTTP — so the two desk screens must open the desk at `http://localhost:8413/`, which is a
  potentially-trustworthy origin, and the iPad at `http://lighting7-<host>.local:8413/` gets none
  of the three. And Safari on iPadOS does not honour `display: fullscreen` at all (`false` in
  MDN's compat data; `standalone` since 11.3), so the manifest's fallback chain is the whole of
  the iPad story. Facts in §3.6. **Safari on the Mac is a first-class desk browser, not a
  fallback** (Chris's call, 2026-09-15): it has the Fullscreen API and `standalone` but no Keyboard
  Lock, no Window Management and no `--app=`, so every Chrome-only piece is feature-detected and
  its absence is *quiet* — no menu item, no tray item, no gutter — rather than a disabled control
  saying "use Chrome". The Safari route to a chromeless screen is *Add to Dock* and macOS's own
  full screen, which is not the Fullscreen API and does not exit on Esc.
- **D14 — nothing new in the app header row.** *Record call 5, taken.* `Layout.tsx`'s header
  comment already says the row cannot wrap and is at its width on a phone. Full screen and
  Screens… go in `UserMenu` under the theme item and in ⌘K's *Actions* group; the hand chip is a
  fixed chip at the bottom of `<main>`, below every bar.

## 3. The model

### 3.1 Desk facts and window facts

| Fact | Owner | Wire | Change |
| --- | --- | --- | --- |
| Fixture / group selection | desk | `selection.state` | gains `families` and `source` |
| Attribute mask | desk (part of the selection, D2) | same frame | new |
| Who moved it | desk | same frame | new |
| Follow / local | window | never sent; `windows.announce` reports it | new |
| Programmer scope · fade · blind | window / desk | unchanged | — |
| Template recents · busk page | desk | unchanged | — |
| Windows | desk, per socket | `windows.state` · `windows.announce` · `windows.show` · `windows.rename` · `windows.fullscreen` | new |
| The hand | desk | `hand.state` · `hand.pickUp` · `hand.drop` | new |
| Cell editor open · marquee in flight · filter text | window | never sent | — |

All new frames take the dotted form (`docs/websocket-engineering.md` §Naming). The three
stateful ones are `StateFlow`-backed, so a subscription is the snapshot and reconnect is free; the
commands are reply convention 3 (no reply; the state frame or the target's own announce is the
acknowledgement). None is persisted; none is a table, so `SyncCoverageTest` has no row to gain.

### 3.2 The selection

```kotlin
class DeskSelection {                                    // state/DeskSelection.kt, extended
    data class Snapshot(
        val targets: List<CueTargetDto>,
        val families: Set<PropertyMaskGroup>?,           // null = every attribute
        val source: SelectionSource?,                    // null after clear / project switch
    )
    val state: StateFlow<Snapshot>                       // replaces `targets: StateFlow<List<…>>`
    fun set(targets, families = null, source)            // D2: the whole fact
    fun toggle(target, source): Boolean                  // heads only; mask kept
    fun clear(source)
    fun prune()                                          // keeps families and source
}
@Serializable data class SelectionSource(val kind: String /* window | surface */, val id: String?, val name: String)
```

`selection.state` becomes `{targets: [{type, key}], families: ["COLOUR"] | null, source: {kind, id?, name} | null}`.
`selection.set` gains `families?: [String]` (absent = null = all) and every inbound
`selection.*` frame's `source` is **stamped by the handler** from the socket's announced identity
(`SocketScope.window`, §3.4) — never trusted from the payload, so a window cannot claim to be
another. The families vocabulary is `PropertyMaskGroup`'s four names; an unknown name is dropped,
as `parsePropertyMask` drops one on the client.

**Emitted** on every mutation that changes the snapshot (one `update`, one frame, as today).
**On reconnect**: the subscription delivers the current snapshot. **On project switch**:
`clear(null)` in the existing collector. **Consumers of `targets.value`** — `SurfaceActions`
(×4), `SurfaceFeedbackPublisher` (×5), `AiTools` — read `state.value.targets` and are otherwise
untouched; the AI's `selection` object gains `families` and `source`.

Client: `SelectionWsApi` and the `deskSelection` cache entry carry the snapshot; `useDeskSelection()`
keeps its shape (targets) and `useDeskSelectionSnapshot()` is the new reader for the chip. The
bridge's echo FIFO closes at the **target** level today (`publishedRef` holds target-key sets);
it becomes a set of `targets + families` strings, so a frame with the same heads and a different
mask is *not* an echo — which is exactly the case a second window changing the mask under a
standing marquee produces, and the case the record's "is this frame mine?" warning is about.

### 3.3 Where a press lands

Every server-side reader of the selection, with what it does under a mask:

| Consumer | Door | Under a mask |
| --- | --- | --- |
| `BuskPressService.apply` — template | pad press, HTTP and MIDI | **refuses by name** when the template's family ∉ mask (`TEMPLATE_OUTSIDE_MASK`); otherwise unchanged — a template's layer is already masked to its own family, so the intersection is the family itself |
| `BuskPressService.apply` — Look | pad press | **honours**: `propertyMask = mask ∩ look.families`; empty → `LOOK_OUTSIDE_MASK`; response gains `skippedFamilies` |
| `BuskPressService.apply` — cue | pad press | **ignores** (no targets) |
| `applyTemplateToProgrammer` | `/templates/{id}/apply`, the click | **refuses by name** as the template arm above; both value and effect arms |
| `/templates/{id}/toggle` | ⌥click / hold | same as the pad's template arm |
| `/looks/{id}/toggle`, `programmer.addLayer` with a Look | strip, AI, add-layer sheet | **honours** through `propertyMask`; the add-layer sheet already lets the operator pick a mask and the request's explicit `propertyMask` wins over the selection's |
| `SurfaceActions.pressTemplate` / `.pressPad` | MIDI | the same two rules, refusals logged (no reply channel) |
| `SurfaceActions.writeSelectionProperty` | `Sel · property` fader / encoder | **ignores** — the control names its attribute |
| `SurfaceActions.locateSelection`, `selectTarget`, `clearSelection` | MIDI | ignore / keep / clear per D2 |
| `SurfaceFeedbackPublisher` | LEDs and rings | ignores — membership and values, not families |
| `AiTools.get_current_state` | AI | reports |

Two consequences worth stating. A Look already on the stack under a Colour mask, pressed again
with no mask, reads as **off**: `toggle` compares coverage by targets, not by mask, so the second
press removes the layer rather than widening it — pressing twice still means off. And the layer
stack does not move: `ProgrammerLayerStack.toggle` already takes `propertyMask`; the only engine
change is that `BuskPressService` computes it from two inputs instead of one.

### 3.4 The windows registry

```kotlin
class WindowRegistry {                                          // state/WindowRegistry.kt
    data class Window(val id: String /* socket-minted */, val windowId: String, val name: String,
                      val view: String /* route path */, val fullscreen: Boolean, val follows: Boolean,
                      val user: String? /* displayName */)
    val windows: StateFlow<List<Window>>                          // insertion order
    fun announce(scope: SocketScope, w: Window); fun remove(scope: SocketScope)
}
```

Machine-scoped, not project-scoped — a window outlives a project switch and its `view` carries
the project id — so it is **not** cleared in the project collector and it registers in the
machine band of `Sockets.kt`, beside `setupMachineSubscriptions`, so a window announces before
the show is warm. `SocketScope` gains `var window: WindowRegistry.Window?`, set by the announce
handler and read by the selection handler for `source` (D7). `remove` runs in the connection's
`finally`, beside `ownedLearnSessions`.

Frames. `windows.announce {windowId, name, view, fullscreen, follows}` — inbound, sent on every
socket open and on every change (route, fullscreen, follow, rename). `windows.state {windows:
[…]}` — `StateFlow`, snapshot and broadcast; the client keys its own row by `windowId`.
`windows.show {targetId, view}`, `windows.rename {targetId, name}`, `windows.fullscreen
{targetId, on}` — inbound, rebroadcast as-is to every socket (D11); the named window acts.

On the receiving window, `windows.show` is a `navigate(view)`. Three interactions, each already
answered by existing code: the show-editing lock is a Redux slice per tab, never persisted and
defaulting to locked, so arriving on `/show` mid-show lands locked and leaving it changes nothing;
an open cell editor is unmounted with its route (`useListSelection` clears its scope on unmount
and the bridge never publishes on unmount, so the desk selection is untouched); a **guarded
sheet** — one reporting `unsavedChanges` through `sheet.tsx`'s `SheetUnsavedContext` — declines
the navigation and toasts *Screen 2 asked to show Busk — you have unsaved changes*, with a button
that goes. That needs a module-level count of dirty sheets the provider already computes per
sheet (`hasUnsaved`) and does not yet expose; `lib/unsavedSheets.ts`, three lines.
`windows.fullscreen {on: false}` calls `document.exitFullscreen()` (no gesture needed); `{on:
true}` cannot call `requestFullscreen` — MDN: "Transient user activation is required" — so it
raises the *Return to full screen* banner on that window.

*Open on Display 2* is client-only: `window.getScreenDetails()` (Chrome 100+, secure context,
prompts for `window-management`), then `window.open(url + '?window=' + name, '_blank',
'left=,top=,width=,height=')` at the chosen screen's bounds. The new window announces itself and
the registry learns of it that way; it shows the banner rather than going full screen unasked.
*Copy link for another device* mints `http://<lanUrl>/?window=<name>`.

### 3.5 The hand

```kotlin
class HandState {                                               // state/HandState.kt
    data class Held(val kind: BuskPadKind, val id: Int, val uuid: UUID,
                    val template: TemplateDto?, val look: LookDto?, val cue: BuskCueDto?,   // the pad's own summaries
                    val pickedUpOn: SelectionSource, val pickedUpAtMs: Long, val expiresAtMs: Long)
    val held: StateFlow<Held?>
    fun pickUp(held): replaces; arms a 5-minute Job that drops
    fun drop()
    fun reconcile(): drops when the record no longer resolves — lookListChanged / templateListChanged / cueListChanged
}
```

Project-scoped: cleared in the project collector. `hand.state {item | null}` is a `StateFlow`
snapshot and broadcast; `hand.pickUp {kind, id}` (the handler resolves the record in this project
and stamps `pickedUpOn` from the socket's window) and `hand.drop` are inbound with no reply. The
frame carries **the record's own summary DTOs**, exactly as `BuskPadDto` does, so every window
builds the ghost through `padFaceOf` and the face is frozen and hookless — `padFace.ts`'s rule,
which is why an effect template's detail line has no live master label there either.

Pick-up: the pad's existing hold menu (`BuskPadButton`'s `onInspect`, reached through
`useLongPress`) gains *Pick up* at the top; so do the library rows of `/looks`, `/templates`, the
programmer's template chip and `LookStack`'s row menu, and a cue card. A second pick-up replaces.
Drop: the chip's ×, Escape when nothing else claims it, the timeout, a record delete, a project
switch. Place: a tap on a lit target on any window — a busk bank (`useAddBuskPadMutation`, which
answers the whole page so the busk view's commit queue needs nothing), a cue slot
(`useAssignCueSlotMutation`, refused for a template or a deferred-effect Look exactly as
`slotAssignmentFor` refuses it today), the programmer layer stack (`programmerAddLayer` with the
desk selection as targets, a template masked by the server), a cue's stack (`patchProjectCue`
with `buildCueInput` plus one layer). A cue in the hand can land only on a bank or a slot. Each
of those mutations exists; what session 3 adds is the *target affordance* — `useDroppable` sites
lit while `hand.state` holds something they can take, sharing `canLand`-style eligibility in
`lib/handTargets.ts` — and the `hand.drop` after.

MIDI: `BindingTarget.PickUpPad(padUuid)` (the record on that pad into the hand),
`HandPlaceInBank(bankUuid)`, `HandDrop`; all `BUTTON` in `targetControlKind` and its client mirror
`lib/surfaceDrop.ts`; health `MissingPad` / `MissingBank` as the record variants have. Not
`HandPlaceInSlot` or a layer-stack place: neither has a uuid a binding could carry.

**The same-machine edge drag** (session 4) is where a drag leaves dnd-kit. `DeskDndProvider`'s
`onDragMove` sees the pointer's `screenX/Y`; when it leaves this window's bounds
(`screenX + outerWidth`, in the multi-screen coordinate space the Window Management permission
grants), the provider sends `hand.pickUp` for the lifted record, dispatches a synthetic Escape so
dnd-kit runs `onDragCancel`, and from then on posts pointer positions and the release on a
`BroadcastChannel('desk-drag')` — same origin, same profile, which is exactly the two desk
screens and never the iPad. The neighbour draws the ghost at the posted screen position; on the
posted release it hit-tests its own droppables (`document.elementsFromPoint` against elements
registered in `lib/handTargets.ts`) and, if one can take the item, places through §3.5's mutation
and drops the hand. **The source window keeps the pointer for the whole drag** — the OS delivers a
held button's moves to the window that saw the press — so the target window never receives a
pointer event of its own, which is why the channel carries positions and not the item, and why
the hand is what makes it safe: if no window claims the release, the item is simply in the hand.

### 3.6 Full screen, from MDN

- `Element.requestFullscreen()`: Chrome 71, Firefox 64, Safari 16.4; Safari iOS 16.4 **partial**
  — "Only available on iPad, not on iPhone." and "Shows an overlay button which can not be
  disabled. Swiping down exits fullscreen mode, making it unsuitable for some use cases like
  games." Needs transient user activation everywhere. Not a secure-context API. Users exit with
  Esc or F11, and "navigating to another page, changing tabs, or switching to another application
  … will likewise exit fullscreen mode".
- `Keyboard.lock()`: Chrome 68; Firefox and Safari **not supported**; secure context; transient
  activation; "This method can only capture keys that are granted access by the underlying
  operating system." Feature-detected, called with `['Escape']` after the fullscreen request
  resolves, so Esc reaches the sheet's clear-selection and the dialogs' close rather than the
  browser.
- Window Management (`getScreenDetails`): Chrome 100; Firefox and Safari **not supported**; secure
  context; `window-management` permission, prompted.
- Manifest `display`: Chrome 39 (`display` required for installability); Safari 17 and Safari iOS
  11.3 support the member, **`standalone` only** — `fullscreen` and `minimal-ui` are `false` on
  both, so the chain `fullscreen → standalone` is what an iPad gets. Firefox: none.

What is built: `public/manifest.webmanifest` (`display: fullscreen`, `display_override:
["fullscreen", "standalone"]`, `start_url: /`, name, icons — which are `FU-DIST-ICONS`'s, so that
item is a prerequisite rather than a new one), `<link rel="manifest">` and
`apple-mobile-web-app-capable` in `index.html`; no service worker. The *Full screen* item and ⌘K
command call `document.documentElement.requestFullscreen()` then the lock; a `fullscreenchange`
listener updates the registry and a `sessionStorage` flag; a window that was full screen last time
and is not now (a reload, a crash) shows the one-tap *Return to full screen* banner at the top of
`<main>`. Installed-app launches are chromeless with no gesture and no Esc trap, which is what the
desk screens run; the launcher's tray menu (Windows builds only, `launcher/LauncherMain.kt`, which
today opens one browser at `http://localhost:8413/` through `Desktop.browse`) gains *Open Screen 1
/ Screen 2*, each spawning the installed browser with `--app=http://localhost:8413/?window=Screen%20N`
and `--window-position` from the tray's remembered layout. Kiosk mode is a note in the docs. On
the iPad: the same menu item works (with Safari's overlay button and swipe-down exit, above); *Add
to Home Screen* on the copied link is the durable route and gives `standalone`.

**Safari on the Mac desk**, per browser feature:

- *Full screen* from the menu works (Safari 16.4, unprefixed, gesture required). There is no
  Keyboard Lock, so **Esc leaves full screen before the sheet sees it** — in a Safari tab, the
  in-app item is for ad-hoc use only, and the exit glyph in the user menu is the way back.
- The durable route is **Add to Dock** (Safari 17 on macOS Sonoma: a web app with `standalone`
  from the manifest, its own window and Dock icon) and then macOS full screen from the window's
  green button — OS full screen, not the Fullscreen API, so Esc does not exit it and no lock is
  needed. Whether Safari lets one origin be added to the Dock **twice** with two `?window=` start
  URLs, and whether it keeps the query on launch, is not documented on MDN and is desk check
  S2.7; the fallback if it does not is one Dock app opened twice, each window named in the
  Screens sheet for the life of that window (D10's undurable arm).
- *Open on Display 2* is hidden (no `getScreenDetails`); the operator opens the second window by
  hand and drags it across. Session 4's edge drag is unavailable and the hand is the route.
- The launcher's macOS tray gains no `--app=` items — Safari has no such flag — but may gain
  *Open Screen N* items that `open` the Dock app when S2.7 finds a stable bundle name; otherwise
  they are omitted rather than half-built (`FU-LAUNCHER-SCREEN-POSITION` covers both platforms).

### 3.7 Sync

None. Every new fact is transient, none is a table, and no DTO in `ProjectExporter` changes.

## 4. UX — what the design draws

Grep-able; the artboards are the authority on layout and copy.

- **The desk chip** (`Main.dc.html`, `Screen2.dc.html`): on the programmer's `SelectionBar`
  between the family pill and the strip, and in the busk band's label row — `Desk` alone when
  this window moved the selection, `Desk · from Screen 1` when another did, `Desk · from the desk`
  for a surface; dashed `This window` when local. A click flips it.
- **The band** lights the selected heads and carries the family pill beside the count.
- **The user menu** (`Screens.dc.html` §1): *Full screen ⇧F* and *Screens… (3 windows)* between
  the theme item and Log out; the label line gains *this window is Screen 1*.
- **The Screens sheet** (§2): one row per window — name (editable), `this window`, full screen or
  in a browser tab, follows the desk or not, a view picker, a Full screen / Exit full screen
  button; below, *Open a window on… Display 2 · 1920×1080* (Chrome only; hidden without
  `getScreenDetails`), *Copy link for another device*; **Layouts is §8's `FU-SCREENS-LAYOUTS`**,
  not built.
- **⌘K** (§3): *Go full screen*, *Screens…*, *Show <view> on <window>* per window and view, *Open
  <view> on Display N*, *Follow the desk selection in this window*.
- **Esc** (§4): in full screen the app draws a small exit glyph in the user menu only.
- **The hand** (`Hand.dc.html`): a chip `Warm Wash · Look · picked up on Screen 2` fixed at the
  bottom of `<main>` on every window, with ×; targets that can take it grow a dashed ring; a tap
  places; the placing window toasts *Placed in Looks on Chris's iPad · Undo*.
- **The edge drag**: a chip dragged off the right edge arrives on the screen to its right under
  the pointer, drawn as the same frozen ghost `dragOverlayRegistry` draws today.

## 5. Implementation — four sessions

Backend first inside each session where there is a backend half, each session green
(`./gradlew test`; `npm run check`), each ending with a desk check on the two-screen desk and an
iPad (§9). The first ships the wire and the chip alone, and is useful alone: a marquee on one
screen becomes a masked press on the other before any window has a name in a registry.

### Models

| Session | Model | Effort | Why |
| --- | --- | --- | --- |
| 1 — the mask and the chip | Fable 5.1 | high | Invariant-dense and silent: a `StateFlow` snapshot changing shape under five readers, the echo FIFO's identity widening, a press refused or masked across four doors that must agree. |
| 2 — windows and full screen | Opus 5 | xhigh | A new registry family plus browser-API feature detection across three browsers; failures are visible but platform-specific. |
| 2.5 — the inherited `windowId` | Opus 5 | high | Three lines, one sharp invariant: mint on the param, *not* on a reload. Small, but it sits on a storage lifetime nothing else in the app depends on. |
| 3 — the hand | Opus 5 | xhigh | Four place targets over four existing mutations, eligibility shared with the busk drop rules, a timeout Job, and MIDI arms. |
| 4 — the edge drag | Opus 5 | high | One gesture, two windows, a `BroadcastChannel`; the risk is the dnd-kit hand-off, not reasoning. |

**Reviews run on `/code-review-lite`** or a plain Opus 5 review after every session. `max` nowhere.

### ~~Session 1 — the mask and the desk chip (both repos) — Fable 5.1, high~~ — af3575a · f8ce5f9

- lighting7: `DeskSelection.Snapshot` with `families` and `source` (§3.2); `SelectionSocket`
  frames; `SocketScope.window` stub (a name-only identity until session 2 — `selection.set` may
  carry `sourceName` this session, replaced by the announced identity in session 2, so the chip can
  say *from Screen 1* before the registry exists); `BuskPressService.apply(families)`,
  `ProgrammerLayerStack.toggle` unchanged, `applyTemplateToProgrammer(families)`, the two refusal
  codes, `BuskPressRequest.families`, `ApplyTemplateRequest.families`, `ToggleTemplateRequest.families`,
  `ToggleLookRequest.families`, `ProgrammerAddLayerInMessage` unchanged (its `propertyMask` is the
  mask); `SurfaceActions.pressTemplate` / `.pressPad` pass the desk's; AI `selection` gains the two
  fields. Docs: `websocket-engineering.md` rows; `lighting-composition-model.md` §"A press is per
  target" gains a paragraph on the mask.
- lighting-react: `selectionApi.ts` snapshot; `store/selection.ts` (`useDeskSelectionSnapshot`,
  `setDeskSelection(targets, families)`); `useDeskSelectionBridge` publishes `cellFamilies` and
  widens the FIFO key; `FixturesListContainer` hands the bridge `cells`; `lib/deskFollow.ts`
  (`sessionStorage`, `createSyncStore` shape with a `storage` parameter); `useBuskingSelection`'s
  local arm; the desk chip (`components/desk/DeskChip.tsx`) on `programmer/SelectionBar` and
  `TargetBand`; every press sends `families`; the skip toast (D6) in `BuskingView` and
  `useTemplatePress`.
- Tests. lighting7: `DeskSelectionTest` (set replaces families; toggle keeps; clear drops; prune
  keeps; a stamped source); `SelectionSocketTest` (snapshot carries the three fields; source is the
  socket's, never the payload's); `BuskPressRouteTest` (template outside mask → 400 by code; Look
  intersected → layer mask and `skippedFamilies`; Look with nothing inside → 400; cue ignores);
  `TemplateRoutesTest` (apply and toggle refuse by name); `SurfaceInputRouterTest` (a MIDI press
  under a mask); `PropertyMaskTest` unchanged. lighting-react: `useDeskSelectionBridge.test.tsx`
  (a marquee publishes families; a frame with the same heads and a new mask is applied, not
  swallowed); `selection.test.ts`; `deskFollow.test.ts` (per-tab storage, default on, snapshot on
  unlink, adopt on relink); `TargetBand.test.tsx` and `SelectionBar.test.tsx` (the chip's three
  states); `useBuskingSelection.test.tsx` (local arm); **the vocabulary pin**: `maskPicker.test.ts`
  already pins `MASK_GROUPS` against `ATTRIBUTE_FAMILIES`; `selection.test.ts` pins the
  `families` parser against `ATTRIBUTE_FAMILIES` the same way, and `PropertyMaskTest` on the other
  side names the four in order, so a fifth family fails both suites.
- Desk check: `FU-MANUAL-MULTI-SCREEN-S1` (§9).

### Session 2 — windows, full screen and the Screens sheet (both repos) — Opus 5, xhigh

- ~~lighting7: `state/WindowRegistry.kt`, `plugins/WindowsSocket.kt`, the machine-band
  registration, `SocketScope.window` and the `source` stamp, the two tray items, and
  `docs/desk-screens.md`.~~ — d774fd9. `sourceName` is kept as the fallback until the
  lighting-react half lands (`FU-WINDOWS-RETIRE-SOURCENAME`).
- ~~lighting-react: `lib/windowIdentity.ts` (`?window=` at boot, `sessionStorage`, the default
  name); `api/windowsApi.ts`; `store/windows.ts` — bridge form 1 (module scope) unless the sidebar
  reaches it, in which case form 2 from `main.tsx`; the announce on every `Status.OPEN` (an `open`
  branch that re-sends only that); `windows.show` handler with the guarded-sheet decline;
  `lib/unsavedSheets.ts`; `components/screens/ScreensSheet.tsx`; the two `UserMenu` items; ⌘K
  entries built the way `useTemplateFamilyNavItems` builds its four (a `useWindowCommands()` over
  the registry); `lib/fullscreen.ts` (request, feature-detected lock, the `fullscreenchange`
  listener, the return banner); `public/manifest.webmanifest`, `index.html` meta and link; *Open on
  Display N* behind `'getScreenDetails' in window`.~~ — cb26499b. `store/windows.ts` landed as
  neither form 1 nor 2 but form 3 (a per-entry `queryFn`), with the announce and the command
  handlers in a `Layout`-mounted hook because they need the router; the copied link uses the tab's
  own origin (`FU-SCREENS-LAN-URL`).
- Tests. lighting7: `WindowRegistryTest` (announce, re-announce replaces, removal on close, a
  duplicate `windowId` is two rows); `WindowsSocketTest` (snapshot on connect before any announce;
  `show` rebroadcast as-is; a selection write is stamped with the announcing window).
  lighting-react: `windowIdentity.test.ts` (the URL param wins once and is stripped; a reload keeps
  it; two tabs differ); `windows.test.ts` (re-announce on OPEN and on route change; `show` for
  another id is a no-op; the guarded decline); `ScreensSheet.test.tsx`; `UserMenu.test.tsx` (the two
  items, the exit glyph in full screen); `fullscreen.test.ts` (lock called only when present);
  `navigation.test.ts` for the command shapes.
- Desk check: `FU-MANUAL-MULTI-SCREEN-S2`.

### ~~Session 2.5 — the inherited `windowId` (lighting-react) — Opus 5, high~~ — a72c24b

Added after session 2's backend landed, from a gap in D9/D10 read together. **`sessionStorage` is
cloned into a top-level context created from an existing one** — `window.open` without `noopener`,
a `target=_blank` link — so the child of *Open a window on… Display 2* wakes up holding its
parent's `windowId`. `?window=` overwrites the *name*, so the result is Screen 1 and Screen 2
sharing one identity. Since a client resolves "my row" in `windows.state` by matching `windowId`
(the socket-minted `id` is the desk's, and a window is never told its own), both match two rows,
and the desk chip then reads *Desk* — "I moved it" — when the twin moved it. This is the designed
route for opening the second desk screen, so it lands on first use rather than as an edge case.

- ~~lighting-react: `lib/windowIdentity.ts` mints a **fresh** `windowId` when `?window=` is present
  at boot rather than keeping an inherited one — the param means "a deliberately-named new
  window", which is exactly the signal that this context is not a continuation of the storage it
  woke up with. The param is stripped at boot, so a reload carries none and keeps its id: that is
  the invariant, and minting on reload is the regression. `noopener` on the Screens sheet's
  `window.open`, unless the sheet needs the returned handle to place the child.~~ — a72c24b. The
  sheet does not need the handle; the `noopener` already there stayed.
- ~~lighting7: nothing. `windows.show` / `.rename` / `.fullscreen` address a row id and were never
  ambiguous; only self-identification was.~~ — nothing needed.
- ~~Tests. lighting-react: `windowIdentity.test.ts` (a boot with `?window=` *and* an existing
  `windowId` mints a new one; a boot with no param keeps it; a reload after a `?window=` boot keeps
  the id minted at that boot; two tabs differ); `ScreensSheet.test.tsx` (the open call carries
  `noopener`, or the documented reason it does not).~~ — a72c24b.
- Not fixed here: right-click *Duplicate Tab*, which clones the storage on a URL whose `?window=`
  was already stripped. Operator-initiated, rare, and what D9 says should happen — two rows. The
  residual damage is cosmetic and its real fix is backend-side (`FU-WINDOWS-OWN-ROW-ID`, §8).
- ~~Desk check: folded into `FU-MANUAL-MULTI-SCREEN-S2` — the *Open on Display 2* step gains "and
  the two rows carry distinct `windowId`s".~~ — done.

### Session 3 — the hand (both repos) — Opus 5, xhigh

- lighting7: `state/HandState.kt`, `plugins/HandSocket.kt`, the project collector clears it, the
  three list-changed listeners reconcile it, the timeout Job; `BindingTarget.PickUpPad` /
  `HandPlaceInBank` / `HandDrop` with `SurfaceActions` methods, router arms, `targetControlKind`
  arms and health arms. Docs: `websocket-engineering.md`; `lighting-composition-model.md` §"The
  busk layout" gains "The hand".
- lighting-react: `api/handApi.ts`, `store/hand.ts` (form 1); `components/hand/HandChip.tsx`
  (fixed at the bottom of `<main>` in `Layout.tsx`, drawn from `padFaceOf` over the frame's DTOs);
  `lib/handTargets.ts` (what each target kind can take — a slot refuses a template and a
  deferred-effect Look through `slotAssignmentFor`; a bank takes all three; the layer stack takes
  a Look or a template; a cue's stack takes a Look or a template); target rings on `BuskBank`, the
  cue-slot tiles, `LookStack`'s footer and `StackDetail`'s cue cards; *Pick up* in the pad's hold
  menu, the library rows, the template chip and the layer row; the Undo toast; `lib/surfaceDrop.ts`
  mirror.
- Tests. lighting7: `HandStateTest` (replace, drop, timeout, reconcile on each list change, project
  switch); `HandSocketTest` (snapshot; `pickUp` resolves in this project only and stamps the
  window); `SurfaceInputRouterTest` arms. lighting-react: `handTargets.test.ts` (the eligibility
  table, pinned against `slotAssignmentFor`); `HandChip.test.tsx` (hookless ghost from the frame;
  × drops; Escape drops only when no editor is open — the `cellEditorIsOpen()` snapshot rule);
  `hand.test.ts` (place = mutation then drop, Undo inverse per kind); `BuskPad.test.tsx` (*Pick up*
  in the menu).
- Desk check: `FU-MANUAL-MULTI-SCREEN-S3`.

### Session 4 — the same-machine edge drag (lighting-react) — Opus 5, high

- Gated on `'getScreenDetails' in window` and a granted `window-management` permission; otherwise
  nothing changes and the hand is the route. `DeskDndProvider` gains the `onDragMove` bounds test,
  the `hand.pickUp` + synthetic-Escape hand-off, and a `BroadcastChannel('desk-drag')` publisher;
  `components/dnd/edgeDrag.ts` is the pure half (bounds → left / right / none; a posted point →
  the droppable under it) so the hand-off is testable without two windows. The receiving window's
  `HandChip` follows posted points and resolves the posted release through `lib/handTargets.ts`.
- Tests: `edgeDrag.test.ts` (the pure half); `DeskDndProvider.test.tsx` (leaving the bounds sends
  one pick-up and cancels the drag; a drag inside the bounds sends nothing; no permission → no
  channel).
- Desk check: `FU-MANUAL-MULTI-SCREEN-S4`.

### CLAUDE.md paragraphs each session owes (not written here)

- lighting-react: §"One selection, two shapes" (families and source on the wire; the chip; the
  FIFO key); a new §"The desk selection has a mask" beside §"The two apply gestures" (where a press
  lands, D5/D6); §"The busk layout" (`families` on the press; the hand's targets); §"Navigation
  Registry" (the ⌘K window commands); §"Where a WS bridge subscribes" (the census: three new
  bridges, and the announce as the second legitimate `open` branch); a new §"Windows, full screen
  and the hand" (identity in `sessionStorage`, the secure-context rule, the manifest, the chip, the
  edge drag's hand-off); §"Sheets vs Dialogs" (the dirty-sheet count).
- lighting7: §"WebSocket Messages" (three families); `docs/websocket-engineering.md` (Client →
  Server 40 → 48, Server → Client 62 → 66 or thereabouts); `docs/lighting-composition-model.md`
  §"A press is per target" and §"The busk layout"; `CLAUDE.md` §"Busk Endpoints" (`families`);
  `docs/midi-control-surface-engineering.md` (three targets).

## 6. Migration

None. No table or column changes; every new fact is transient; every new request field is
optional with null meaning today's behaviour, so an older client pressing a newer desk is
unmasked and a newer client pressing an older desk sends a field the server ignores.

## 7. Explicitly out of scope — what this rules out

- **No window-to-window messages.** Every frame is to the desk or from it; the `BroadcastChannel`
  in session 4 carries pointer positions between two windows of one browser and is the one
  exception, and it carries no item — the hand does.
- **No second programmer per user.** A later plan. It would need: a programmer keyed by
  `AuthenticatedUser`, a per-user `DeskSelection` and layer stack, a merge rule at the cook step
  (whose output wins on one byte), `ProgrammerIndicator` and `provenance` naming a user, and the
  MIDI surface bound to one of them — the MA/Titan model the survey read as a second-person
  feature. `FU-PROG-PER-USER` stands.
- **Nothing new in the app header row** (D14).
- **No HTML5 native DnD** (Options B) unless a session after the hand chooses to add it as the
  desk-machine fast path; session 4 takes the pointer-position route instead because native DnD
  and dnd-kit fight over one element's pointer and the ghost would be the browser's.
- **No Send to… menu** (Options C); it stays the accessible fallback if a screen reader user asks.
- **Saved screen layouts** (`FU-SCREENS-LAYOUTS`).
- **A cue-*stack* in the hand**; a slot could hold one once and nothing else can.
- **Per-target cell sets on the wire** (D3).

## 8. Follow-ups to record

- `FU-SCREENS-LAYOUTS` — Trigger: the operator wants *Desk · Programmer + Busk* as one press. A
  named list of `{windowId → view}` in `localStorage` on the desk machine, applied through
  `windows.show`.
- `FU-HAND-SEND-TO` — Trigger: an accessibility ask; Options C as a submenu of the hold menu.
- `FU-HAND-STACK` — Trigger: someone wants a cue stack on a pad or in the hand.
- `FU-SELECTION-RAGGED-MASK` — Trigger: a ⌘-union across columns lands a press on cells the
  operator did not draw; the fix is one layer per family group, not cells on the wire.
- `FU-WINDOWS-SHOW-OFFLINE` — Trigger: a `windows.show` lost to a disconnected target matters;
  the fix is a per-window `requestedView` in the registry the target reads on re-announce.
- `FU-WINDOWS-OWN-ROW-ID` — Trigger: an operator duplicates a desk tab and the chip attributes the
  twin's write to itself. A window infers its registry row by matching `windowId`, which session
  2.5 makes reliable but not exact; the exact fix is the announce handler unicasting the minted
  row id back to its own socket, so a window is *told* its identity instead of inferring it.
- `FU-DESK-TLS` — Trigger: the iPad needs install, Keyboard Lock or Window Management; the desk
  would have to serve HTTPS with a certificate the iPad trusts. Recorded because D13 leans on it.
- `FU-LAUNCHER-SCREEN-POSITION` — Trigger: the two desk windows should remember which display
  each opens on. Windows: `--window-position` on the `--app=` items. Mac: whatever check S2.7
  finds about two Dock apps of one origin; until then the macOS tray has no screen items.

## 9. Verification

Backend: the tests in §5. `ProgrammerLayerStackTest` unchanged — the engine does not move; if it
has to, the plan is wrong. Frontend: §5. Desk checks, staged as four items in
`manual-validation.md` when each session lands, in that file's format:

**`FU-MANUAL-MULTI-SCREEN-S1`** · *A marquee on one screen is a masked press on the other* · from
this plan §5 S1. Two browser windows on the desk and an iPad, all following.

1. Marquee three Colour cells on Screen 1. Screen 2's band lights the three heads and shows a
   Colour pill; the iPad's too; both chips read *Desk · from Screen 1*; Screen 1's reads *Desk*.
2. Press a colour template pad on Screen 2. The three cells on Screen 1 ring *You* and the colour
   lands; nothing else moves. Press a position template pad: Screen 2 toasts the refusal by name
   and the rig does not move.
3. Press a Look holding colour and position rows: the colour lands, Screen 2 toasts *Position rows
   skipped*, and `LookStack` on Screen 1 shows the layer badged Colour.
4. Tap a fourth head on the iPad's band: it joins under the Colour mask (Screen 1's marquee grows
   a row, the pill stays Colour). Press the X-Touch select button for a group: same.
5. Pick one head in the narrow-width picker: the mask clears (no pill anywhere).
6. Click Screen 2's chip → *This window*, dashed. Marquee on Screen 1: Screen 2's band does not
   move. Press a pad on Screen 2: it lands on Screen 2's own heads. Record on Screen 2: the sheet
   scopes on Screen 2's rows. Click the chip again: Screen 2 adopts the desk's selection.
7. Reload Screen 2: it comes back following; the desk's selection and mask are intact.

**`FU-MANUAL-MULTI-SCREEN-S2`** · *Windows have names, and one moves another*.

1. Launch both desk windows from the tray items: each announces as Screen 1 / Screen 2, chromeless,
   `http://localhost:8413/` (Chrome or Edge, whichever is installed). The Screens sheet on either
   lists both plus the iPad once it opens the copied link.
2. From the iPad, *Show Busk on Screen 2*: Screen 2 navigates; the sheet's row updates. With a
   guarded sheet open on Screen 2, it declines and toasts. With the show running and Screen 2 on
   `/show`, the move lands locked.
3. *Full screen* from the user menu on the iPad: Safari goes full screen with its overlay button;
   swipe down exits; *Add to Home Screen* on the copied link opens standalone.
4. On a desk window in a plain tab: *Full screen*, then Esc — with Keyboard Lock (Chrome), Esc
   clears the selection and the window stays full screen; reload → the *Return to full screen*
   banner; one tap restores it.
5. *Open Busk on Display 2* from Screen 1 (permission prompt on first use): a new window opens on
   the other display named Screen 2 and announces; the registry shows three desk windows.
6. Rename the iPad from Screen 1: the iPad's chip and its `windows.state` row change; reload the
   iPad: the name survives (it came from the URL).
7. **Safari on the Mac.** *Full screen* from the menu: full screen; Esc leaves it (expected, no
   lock) and the selection is untouched. *Add to Dock* on `http://localhost:8413/?window=Screen%201`,
   then again with `Screen%202`: does Safari allow two, and does each launch carry its name? Green
   button on each: OS full screen, Esc clears the selection and stays full screen. The Screens
   sheet shows no *Open on Display* row. Record the answers against `FU-LAUNCHER-SCREEN-POSITION`.

**`FU-MANUAL-MULTI-SCREEN-S3`** · *Pick up here, place there*.

1. Hold a Look pad on Screen 2 → *Pick up*. The chip appears on all three windows with the same
   face; every bank, slot and the layer stack light. Tap a bank on the iPad: the pad appears at the
   end of that bank on every window, the chip vanishes everywhere, the iPad toasts with Undo; Undo
   removes it.
2. Pick up a template; place it in the programmer's layer stack on Screen 1 with two heads
   selected: a layer masked to its family, on those heads.
3. Pick up a cue: slots and banks light, the layer stack does not. Pick up a deferred-effect Look:
   slots do not light.
4. Pick up a Look, then delete it from `/looks` on another window: the chip vanishes. Pick up and
   wait five minutes: it vanishes. Pick up and switch project: it vanishes.
5. A `PickUpPad` button and a `HandPlaceInBank` button on the X-Touch: pick up on one, place on
   the other, the page changes on every window.

**`FU-MANUAL-MULTI-SCREEN-S4`** · *A chip dragged off one screen arrives on the next*.

1. With Window Management granted on both desk windows, drag a template chip off Screen 1's
   right edge: the ghost appears on Screen 2 under the pointer; release over a bank: placed; the
   hand is empty on the iPad.
2. Release over nothing: the item stays in the hand on every window; × drops it.
3. Deny the permission on one window: the drag ends at the edge as an ordinary pick-up.

One session, 60–90 minutes, with an X-Touch for the two hardware items.

## 10. Scope honesty

This is four new socket families, a change to the shape of a fact five readers hold, and three
browser APIs with three different support matrices. What keeps it bounded: `ProgrammerLayerStack`,
`ProgrammerStore`, `CueStackManager`, the DMX tick path, every press's *plan*, and every place
mutation are untouched; the mask lands through a field the layer stack already has; and the
registry and the hand copy `DeskSelection`'s and `BuskPageState`'s shape exactly. The riskiest
lines are the bridge's echo key (widening it wrongly re-opens the ping-pong the FIFO closed), the
`set` / `toggle` split on the mask (a `set` that keeps families would let a surface's replace carry
a stale mask onto new heads), the machine-band registration (a registry in the show band would
announce nothing until warm-up, and the chip would say *Desk* for the wrong reason), and the
secure-context rule — every full-screen feature that fails on the desk will fail *because the
window was opened at the `.local` URL*, and nothing in the UI will say so unless the banner does.

## 11. Open questions

**All four answered as drafted, 2026-09-15**, with one addition: **Chris also uses Safari**, which
is now a first-class desk browser (D13 amendment, §3.6's Safari arm, check S2.7).

- ~~**Which browser do the desk screens run?**~~ Windows: whichever of `msedge` / `chrome` the
  launcher finds, Edge first. Mac: Safari, by the route in §3.6 — Add to Dock and OS full screen.
- ~~**Re-linking: adopt or publish?**~~ Adopt the desk's (D8).
- ~~**Should the family pill clear the mask alone?**~~ No.
- ~~**Icons.**~~ `FU-DIST-ICONS` is a prerequisite of the manifest; session 2 ships with the SVG
  favicon if the real ones are not there. Chrome's install prompt may refuse without them, and
  Safari's *Add to Dock* uses whatever icon it finds — so the item moves up the list.
