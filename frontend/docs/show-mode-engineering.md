# Show Mode: Programming & Running

> **Rewritten for desk-simplification sessions 1, 2a and 2b.** The routes, the cue surface, the
> transport and the lock are current.
>
> A few paragraphs in the Playback Flow and Data Model sections still say `activeEntryId` where the
> field is now `activeStackId`, and name a `CueEditor` sheet that no longer exists. Those are
> pre-existing drift rather than anything 2b introduced; where they disagree with "Show Mode, in one
> view" or "The Show view", those two are the authority.
>
> The plan is `lighting7/docs/plans/completed/desk-simplification-plan.md`.

Show Mode is the production show programming and playback system. It lives in **two** routes, each
with its own sidebar entry:

- `/projects/:projectId/show` — the **Show view**: cue and stack authoring **and** playback. Renamed
  from `/program` in session 1, path and all, because the programmer became
  `/projects/:projectId/programmer` and two live views one letter apart was the collision that had
  kept the programmer out of the nav. `/program*` redirects, carrying the search string.
- `/projects/:projectId/programmer` — the **Programmer**: the live value grid, its Look-layer stack
  and its effects, all on one screen. Since session 2a its grid has a **scope** — Output / Local /
  one layer — so the same cells serve the rig, your own busk, and a Look's stored rows.

There used to be a third, `/projects/:projectId/run`, and the argument for it was that splitting
running from authoring "makes the running/stopped state obvious at a glance, puts the Start control
where it's easy to find, and lets each view have its own primary action without conditional UI in
the header". Two thirds of that lapsed on its own: `ShowHeader` came to render identical Start/Stop
and live-dot chrome on both views — which *is* the conditional-header duplication the split existed
to avoid — and Show grew a fully working GO transport, so "Run is where you fire cues" was already
false. `/run*` now redirects to `/show`.

**A project IS a show.** There is no separate ShowSession concept — show entries belong directly to
the project. The show is "running" when the project's `activeStackId` is non-null, broadcast via
WebSocket so reloads and other browser tabs see the same state automatically.

## Show Mode, in one view

What remained of the Run/Show distinction was never a destination: it was **whether a stray click
can change the show**. That is a mode, and one the Prompt Book already modelled well, so session 2b
took the lock instead of the route.

```
locked = !canEdit || (isShowActive && lockRequested)      // useEditLock
```

| | Locked (default while running) | Unlocked (default while stopped) |
|---|---|---|
| `/show` | the stack list, read-only | plus reorder / create / rename / delete |
| `/show/stacks/:id` | one stack's cues, plus the sibling tab strip | the same, plus drag and inline edits |
| A cue row | state pip, fade chrome, click-to-arm | plus grip, inline fields, Remove, Duplicate |
| Transport | Space / Backspace live | off — the operator is typing; `L` still toggles |
| Phone | the phone runner (`RunMobile`) | — always locked |

Both levels of the view survive in both modes on purpose. Two layouts under one switch would be two
views with extra steps, which is what was just deleted.

**The lock is a stray-click guard, not access control.** No backend route refuses a write on its
account, so a second client can edit a "locked" show. `canEdit` is not a role either — the backend
computes it as "is this the current project". And it is emphatically **not** the transport gate: GO
must work while locked, because locked is the normal running state.

**Unlocked mid-show is the one state the chrome shouts about.** `ShowHeader` washes amber via
`unlockedWarning` and `ShowLockControl` pulses beside it — because believing you are locked when you
are not is how a show gets edited by accident. Both are on Show *and* the Prompt Book, which drew its
own wash and badge in its toolbar until 2b. Locked is the quiet default, and a stopped show gets no
chrome at all: there is no lock to be wrong about.

`lockRequested` is a Redux slice (`store/editLockSlice.ts`) shared with the Prompt Book, so a fix-it
session is one fact about the operator and one GO ends it everywhere. It is never persisted, and the
re-arm fires on the stopped→running *transition* rather than on mount — re-arming on mount would
re-lock on every navigation between the two surfaces, defeating the point of sharing it.

### Browsing versus arming

Selecting a stack **never** moves the playhead. It used to: a tab click ran
`deactivate(old) → goToStack(target) → deactivate(target)`, so one unconfirmed press took the live
cue off stage and repositioned every other client via `showChanged`. That is indefensible on a
surface whose whole point is now that a stray click cannot change the show.

- `StackTabStrip` takes `selectedStackId` (owns the underline and the scroll-into-view) and
  `liveStackId` (owns the green pip) as **separate** props. They were one value, which is why the pip
  used to be drawn only on *unselected* tabs — selected-==-live made the two indistinguishable.
- The browse cursor is the **URL** (`/show/stacks/:stackId`), which is deep-linkable and needs no
  reconciliation. Run's local cursor and its `manualSwitchRef` are both gone; the ref existed only to
  suppress one `resetStack` in the stale-cache window the destructive click opened.
- Arming is `OffPlayheadBanner`'s **Make this stack live**, confirm-gated while a cue is on stage.
  The confirmation is not ceremony: `POST /show/go-to` deactivates the stack being left and then
  calls `activateAtFirstCue` on the target, so the target's first cue genuinely fires and the desk
  darkens it again to arrive armed. Mid-show that is a visible blip on top of losing the current cue.
- The **playhead is followed only while standing on it**: a boundary GO brings the view along for an
  operator who was watching the show, and leaves an operator reading elsewhere where they were.

### The two active-cue cursors

Both come from `useShowTransport`, and neither is selected by a mode:

- `serverActiveCueId` — what is on stage. Places the stable marker, so it cannot jitter to the
  incoming cue and back mid-crossfade.
- `activeCueId` — the optimistic runner cursor, set the instant GO is pressed. Says which row owns
  the fade chrome.

During a crossfade those are different rows, which is why one value cannot serve both.

**No fade *value* is ever a prop.** `ShowView` is memoized precisely to stop several hundred cue
rows reconciling at frame rate; threading `fadeProgress` down would defeat that with the memo still
in place, looking effective. Each row reads its own via `useCueFade`, whose selector returns `null`
for every row that is not fading — and a `null` that stays `null` is reference-equal, so those rows
never re-render.

### Expansion

At most two cards are open: the one the operator addressed via `?cue=`, and the one on stage, which
is **derived** on top of it (`useCueExpansion`). So a GO opens the new live card and can never take
away the card being read, because it does not write `?cue=` at all.

Both predecessors got this wrong in opposite directions. Run kept a `Set` and added every live cue to
it without ever removing one, so after five GOs five cards were open — and it needed two effects
whose *declaration order* was load-bearing to maintain. Show kept a bare scalar that a GO would
overwrite. The only stored piece left is `collapsedLiveCueId`, because closing a derived card cannot
be a deletion from a set; it self-clears when the show moves on.

### Where the transport lives

`useShowTransport` is the single follow-server transport for every surface — Show, the Programmer and
the Prompt Book. Its docblock used to claim Run's "manual stack-tab browsing model is different code,
not duplication"; that was true only because browsing and the playhead were one variable. Splitting
them removed the difference, and with it the justification for the second copy: Run had restated the
hook block for block, and its version carried two latent defects the shared one does not (it keyed
`resetStack` on the stack id alone, so its own "Fix Order" never recomputed the done/next cursors, and
its reset payload omitted `serverNextCueId`, which the backend owns).

One guard is worth knowing: a cue reorder landing **mid-fade** used to null the optimistic cursor and
stop the fade dead — reachable in one press from the out-of-order banner. The reset is now deferred
until the fade completes, tracked by two refs rather than a disabled lint rule.

## Design Model: Four Phases of Show Programming

The UI is structured around four distinct phases a lighting operator works through:

| Phase | Description | Primary Surface |
|-------|-------------|-----------------|
| Initial programming | Creating core looks (presets) for scenes | Presets view |
| Show assembly | Building cues in stacks from presets, adding FX and triggers | Program view |
| Tech runs | Fine-tuning cues and sequence while running the show | Run view (with occasional jumps to Program) |
| Production run | Stepping through cues live, no editing | Run view |

A key insight: **tech runs live on the Run view, not the Program view**. A tech run is a running phase that occasionally requires a programming detour (switch to Program via the sidebar, edit in the CueEditor sheet, come back), not a programming phase that happens to involve running.

The **Program view** is the single surface for authoring *and* assembling cues: it creates stacks, edits cues (values, layers, ad-hoc effects, triggers), reorders the show, and runs it. It has fully **absorbed the old "FX Cues" view**, which no longer exists — `/cues/*` URLs redirect to `/program`. There are no longer any standalone cues: **every cue belongs to a stack**.

## Concepts

**Show** -- the project's *ordered list of cue stacks* (there is no separate entries table). Each row is either a runnable **STACK** or a **SEPARATOR** (a label-only divider between stacks). Ordering is `cue_stacks.sortOrder`; the show is running when the project has an `activeStackId` set (the playhead).

**Cue Stack** -- an ordered sequence of cues. Has a `type` (`STACK` | `SEPARATOR`), a project-level `sortOrder`, an optional separator `label` and a `loop` flag (repeat after last cue). STACK rows are the playable unit -- the runner steps through one stack at a time; SEPARATOR rows are skipped by activate/advance/go-to.

**Cue** -- a single lighting state, always belonging to a stack (`cueStackId` is non-null). Consists of:
- Its own property assignments (the local layer)
- Preset applications (named FX presets applied to fixture groups)
- Ad-hoc effects (inline effects not from the library)
- Triggers (scripts that run on cue activation/deactivation)
- Timing: fade duration, fade curve, auto-advance delay
- Metadata: cue number (Q1, Q2.5), notes

**Cue types**: `STANDARD` (a real cue) or `MARKER` (a visual separator *within* a stack — distinct from a SEPARATOR *stack* between stacks).

## Show URLs

- Overview (the ordered stack list): `/projects/:projectId/show`
- Drilled into a stack: `/projects/:projectId/show/stacks/:stackId`
- Expanded cue card (transient): `?cue=:cueId` on the stack path
- Legacy `/cues`, `/cues/all`, `/cues/standalone` → `/show`; `/cues/stacks/:stackId` → `/show/stacks/:stackId`. The old `?stack=X&cue=Y` query form is normalized to the path form on load. `?cue=` itself is an external contract — the Prompt Book's rail card mints it.

## Architecture

### Three-Layer Model

```
Component Layer    Route + UI components (React)
                   |
Store Layer        RTK Query endpoints + Redux runner slice
                   |
API Layer          Type definitions + WebSocket subscription factories
```

### File Map

#### API Layer (types + WebSocket)
| File | Purpose |
|------|---------|
| `src/api/cueStacksApi.ts` | Cue stack + cue-entry types AND program-transport types (`ProgramState`, `AdvanceProgramRequest`, `GoToStackRequest`, ...) |
| `src/api/cueStacksWsApi.ts` | WebSocket: `cueStackListChanged` + `showChanged` (program playhead) |
| `src/api/cuesApi.ts` | Full cue types, input types, trigger types |
| `src/api/cuesWsApi.ts` | WebSocket: `cueListChanged` |
| `src/api/lightingApi.ts` | Central API hub -- composes all sub-APIs into a single `lightingApi` object |

(The old `showApi.ts` / `showWsApi.ts` were removed — the show is just the ordered stacks, and the project-level playhead lives in `cueStacksApi`/`cueStacksWsApi`.)

#### Store Layer (state management)
| File | Purpose |
|------|---------|
| `src/store/cueStacks.ts` | RTK Query: stack CRUD, project reorder, per-stack activate/advance, AND program transport (`projectProgramState`, `activate/deactivate/advance/goToStack`) |
| `src/store/cues.ts` | RTK Query: cue CRUD |
| `src/store/runnerSlice.ts` | Redux slice: per-stack runner state (active, standby, progress) |

(The old `store/show.ts` was removed — its endpoints moved into `store/cueStacks.ts`.)

#### Component Layer (UI)
| File | Purpose |
|------|---------|
| `src/routes/ShowPage.tsx` | Route for `/projects/:projectId/show` (and `/show/stacks/:stackId`) — **the whole of Show Mode's UI since the Run merge**. Header (with the lock control) + `ShowBar`, then either the phone runner or the tab strip / off-playhead banner / `ShowView`. Owns the drill state, the `?cue=` contract, the playhead follow, the edit lock, the transport keyboard, make-live, and the two `RecordSheet` mounts. |
| `src/routes/ProgrammerPage.tsx` | Route for `/projects/:projectId/programmer`. **Two rows of chrome, not six bands** since the space plan's session 1: row A (the source box and the verbs, one 40px line), then the workspace (grid + layer/FX rail). Row B — the scope toggle, filter, Lit, Groups, Columns — is *not* a band here: it lives inside `ProgrammerGrid`'s own toolbar, because it describes the grid and has no business spanning the rail. |
| `src/routes/legacyRedirects.tsx` | **Redirects only.** Every retired path that lands on Show — `/run`, `/cue-stacks`, `/cues*`, `/program*` — collected in one module rather than parked in whichever page happens to be the destination. `/program*` carries its search string, because `?cue=` is an external contract. |
| `src/components/ShowBar.tsx` | Row 3, **identical on the two live views that have one** — Show and the Prompt Book (Busk had one until the busk-chrome plan's session A moved its transport into the side sheet's Show tab): DBO, speed masters, programmer chip, active→next, BACK/GO. Every host spreads `showBarProps`; only `showShortcuts` is overridden. **No Blind tile, for any host** — Blind is the programmer's action bar's since `PD-BLIND-ON-PROGRAMMER`, and the programmer chip is how a bar reports it. The **programmer draws no bar at all** since the space plan's session 5; see this repo's `CLAUDE.md` §Cues, Stacks & Triggers for what that deliberately costs. |
| `src/lib/programmerFade.ts` | The programmer's fade time, as a `lib/syncStore.ts` singleton: the action bar's picker writes it, Clear and Blind beside it subscribe, and the marquee's Backspace reads it at press time. A store, not a `usePersistentState` per reader, so a value the picker writes reaches every reader. |
| `src/components/runner/StackTabStrip.tsx` | Sibling-stack switcher. `selectedStackId` owns the underline, `liveStackId` the green pip — **selecting never moves the playhead**. |
| `src/components/runner/OffPlayheadBanner.tsx` | Shown while reading a stack that is not the playhead: *Jump to live* (navigation) and *Make this stack live* (confirm-gated `go-to`). |
| `src/components/runner/ShowLockControl.tsx` | The lock toggle and its re-lock countdown, for `ShowHeader`'s `actions` slot. |
| `src/components/runner/MarkerRow.tsx` | Separator row (shared desktop + mobile) |
| `src/components/runner/OutOfOrderBanner.tsx` | Warning when cue numbers are not ascending. Withheld while locked — "Fix Order" re-sorts a whole stack in one press. |
| `src/components/runner/mobile/RunMobile.tsx` | The **phone runner**: top strip, active-cue hero, standby card, GO/BACK footer with safe-area padding. Always locked. What is left of what was `runner/run/`. |
| `src/components/runner/StackPickerSheet.tsx` | Bottom sheet listing show entries for mobile stack switching |
| `src/components/runner/MobileCueListSheet.tsx` | Bottom sheet exposing the full cue list on mobile; tapping a cue opens CueEditor |
| `src/components/runner/MobileCueRow.tsx` | Lean cue row used inside `MobileCueListSheet` (no fixed notes/auto-pill columns) |
| `src/components/runner/ShowView.tsx` | Show body: routes between ShowOverview and StackDetail on `drillStackId`. **Memoized**, which is why no fade *value* passes through it — only `fadeStackId`; see `useCueFade`. |
| `src/components/runner/ShowOverview.tsx` | The project's ordered stack list: drag reorder, **Create Stack** (in place) + **Add Separator**, per-stack actions menu (edit settings / sort-by-cue-number / delete). Activation controls live in `ShowHeader`, not here; every edit affordance is hidden while locked. |
| `src/components/runner/StackDetail.tsx` | Cue list within a stack, dnd-kit reorder, **Record into `<stack>`** + Separator, "Stacks" back button. There is no "Add Cue": a cue is a captured state, so recording is the only way one is made (session 2a). |
| `src/components/runner/ShowMarkerRow.tsx` | Interactive marker with inline rename/delete. Locked, it renders `MarkerRow` — one separator, every surface. |
| `src/components/runner/CueCardEditor.tsx` | The expandable cue row, rendered straight by `StackDetail`. **No longer an editor**: session 2a deleted its three panes (Targets · Cue properties · Layers) and their tab chrome, and the expanded body is now the read-only cue surface — `CueDetailContent`, which includes `CueValueGrid`, the same cells the programmer's grid draws. Two ways out: **Edit in Programmer** (Includes the cue) and **Cue properties…** (`CuePropertiesSheet`, which reuses the surviving `CuePropsPane`, now in `components/cues/`). |
| `src/components/cues/CueValueGrid.tsx` | What a cue asserts, per head and property, read-only — built from `GET /cues/{id}/cooked` so the composition is the server's, not a second implementation of it. |
| `src/hooks/useRunnerAnimation.ts` | requestAnimationFrame hook for fade/auto-advance progress |
| `src/hooks/useNarrowContainer.ts` | ResizeObserver hook, true while a container is below a threshold. `ShowPage` uses it at 600px to switch to the phone runner. |
| `src/hooks/useShowTransport.ts` | The one follow-server transport, for Show, the Programmer and the Prompt Book. Returns **both** cue cursors (`serverActiveCueId` and `activeCueId`) plus the fade, completions and auto-advance progress. |
| `src/hooks/useShowBarProps.ts` | Everything `ShowBar` and `ShowHeader` need from a project id, including the boundary-GO hint (`→ Act 2`) and the resolved live/armed cue entries. |
| `src/hooks/useEditLock.ts` | The show-editing lock, shared with the Prompt Book. State in `store/editLockSlice.ts`. |
| `src/hooks/useAutoRelock.ts` | The idle re-lock: 2-minute fallback, 10-second visible countdown, "stay unlocked", and GO re-locks at once. |
| `src/hooks/useTransportKeys.ts` | Space/Backspace/L, with the union of the guards the two hand-rolled handlers used to have between them. |
| `src/hooks/useCueExpansion.ts` | One addressed card (`?cue=`) plus the live one, derived. |
| `src/hooks/useCueFade.ts` | A single row's fade, read from the runner so animating one row does not re-render the stack. |
| `src/components/cues/CueRowParts.tsx` | The collapsed row's shared pieces — target chip, state pip — and `useExpandedCue`, the skip-while-collapsed cue fetch. Show and Run each had their own copies and they had drifted. |
| `src/lib/cueUtils.ts` | `buildCueInput()` -- converts a Cue to CueInput for mutations |

#### Navigation
Registered in `src/navigation.ts` with `visibility: "active-only"` and `group: "live"` — four entries
in switcher order: `programmer`, `program` (**labelled "Show"**, id kept for stability), `run`,
`prompt-book`. `/program*` redirects to `/show*` with the search string preserved; `/cue-stacks`
redirects to `/run`.

Note `/show` is no longer a legacy alias for `/run`: it was the old name for the *playback* view, and
now names cue authoring, so an old bookmark lands on the better of the two answers.

## Data Model

### ShowDetails
```typescript
interface ShowDetails {
  projectId: number
  activeEntryId: number | null    // Currently playing entry; null = show not running
  entries: ShowEntryDto[]
  canEdit: boolean
}

interface ShowEntryDto {
  id: number
  entryType: 'STACK' | 'MARKER'
  sortOrder: number
  label: string | null            // Marker label
  cueStackId: number | null       // Stack ID (STACK entries only)
  cueStackName: string | null     // Stack name (STACK entries only)
}
```

### CueStack
```typescript
interface CueStack {
  id: number
  name: string
  loop: boolean
  cues: CueStackCueEntry[]
  activeCueId: number | null      // Server-tracked active cue
  canEdit: boolean
  canDelete: boolean
}

interface CueStackCueEntry {
  id: number
  name: string
  sortOrder: number
  presetCount: number
  adHocEffectCount: number
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string               // LINEAR, EASE_IN_OUT, SINE_IN_OUT, etc.
  cueNumber: string | null        // Theatre cue number (Q1, Q2.5)
  notes: string | null
  cueType: 'STANDARD' | 'MARKER'
}
```

### Cue (full detail)
```typescript
interface Cue {
  id: number
  name: string
  presetApplications: CuePresetApplicationDetail[]
  adHocEffects: CueAdHocEffect[]
  triggers: CueTriggerDetail[]
  cueStackId: number | null
  cueStackName: string | null
  sortOrder: number
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string
  cueNumber: string | null
  notes: string | null
  canEdit: boolean
  canDelete: boolean
}
```

### Cue Composition Types

**Preset applications** apply named FX presets to fixture groups, with optional timing:

```typescript
interface CuePresetApplicationDetail {
  presetId: number
  presetName: string | null
  targets: CueTarget[]           // { type: 'group' | 'fixture', key: string }
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  sortOrder?: number
}
```

**Ad-hoc effects** are inline effects stored directly on the cue (not from the library):

```typescript
interface CueAdHocEffect {
  targetType: 'group' | 'fixture'
  targetKey: string
  effectType: string
  category: string
  propertyName: string | null
  beatDivision: number
  blendMode: string
  distribution: string
  phaseOffset: number
  parameters: Record<string, string>
  delayMs?: number | null         // Optional timed trigger
  intervalMs?: number | null
  randomWindowMs?: number | null
}
```

All three composition types (presets, ad-hoc effects, triggers) support optional timing fields (`delayMs`, `intervalMs`, `randomWindowMs`) for delayed or recurring execution.

### Triggers
```typescript
type TriggerType = 'ACTIVATION' | 'DEACTIVATION'

interface CueTriggerDetail {
  triggerType: TriggerType
  scriptId: number
  scriptName?: string | null
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  sortOrder?: number
}
```

### Runner State (per-stack, following the server)
```typescript
interface StackRunnerState {
  activeCueId: number | null       // Cue currently fading in (null once the fade is done)
  standbyCueId: number | null      // What the next GO fires — the backend's `nextCueId`
  completedCueIds: number[]        // Cues that have finished
  fadeProgress: number             // 0.0 - 1.0
  autoProgress: number | null      // 0.0 - 1.0 (auto-advance countdown)
  fadeStartElapsedMs: number       // How far in the server already was when it told us
  serverTransition: number         // Bumped per server transition; part of the animation key
  serverActiveCueId: number | null // The live cue as the server last reported it
}
```

**Not frontend-only any more.** Standby and the fade used to live purely in this slice, which
meant each browser computed "next" for itself: a cue armed on a tablet was invisible to the desk,
and the fade animation only played in the session that pressed GO. The backend now owns both
(`CueStackManager`, see lighting7 `docs/cue-stacks-engineering.md` §"Standby") and broadcasts
`cueRunStateChanged`; the slice's `applyServerRunState` adopts it.

`serverActiveCueId` is deliberately separate from `activeCueId`: the latter is *the cue being
animated* and goes back to null when the fade completes, so it can't be used to tell whether the
live cue moved. A following session never sets it at all until a frame arrives.

## The Show view

Two levels, both of which survive in both lock modes (see the mode table above).

**Show Overview** (`ShowOverview.tsx`) — the ordered stack list, shown when no stack is drilled into:
- STACK rows: cue count, loop badge, live pill, drill chevron
- SEPARATOR rows: a labelled divider
- Unlocked it adds drag-to-reorder (dnd-kit), inline rename, **Create Stack**, **Add Separator**, and a
  per-stack menu (settings / fix order / delete). Locked, all of that is *absent* rather than greyed
  out — a row of disabled destructive buttons reads as breakage.

**Stack Detail** (`StackDetail.tsx`) — one stack's cues, plus the sibling `StackTabStrip` and, when
reading a stack that is not the playhead, the `OffPlayheadBanner`:
- Each row is `CueCardEditor`: state pip, Q number, name, target chips, fade.
- **Running** it shows a fade countdown, a played tick, a blue armed accent, and the prompt-book
  reading position; the row body arms the cue as next GO and the chevron expands it.
- **Unlocked** the body expands instead, the pip's cell reveals a drag grip on hover, the Q number /
  name / fade become inline editable, and the expanded card gains Remove and Duplicate. Arming is
  deliberately not offered unlocked: it changes what GO fires, which is the show.
- Expanding shows `CueDetailContent` — read-only, always. A cue is edited by Include (D2).
- **"Add Cue" does not exist.** A cue is a captured state, so recording is the only way one is made:
  *Record into &lt;stack&gt;*. Separators and stacks keep their create buttons — neither is a captured
  state, and that is the line rather than "no new buttons".

### Page header

`ShowHeader` renders a breadcrumb (`Projects > Project > Show`), the save indicator, the view
switcher, one Start/Stop button and a live dot. The breadcrumb reads identically on all three live
views — Show used to append the drilled stack's name via an `extra` prop, which no other view had;
the stack's name is on its navigation row and in the tab strip, so the trail said it a third time. Its `actions` slot — held
open across two sessions for exactly this — carries the merged view's **lock toggle and re-lock
countdown** (`ShowLockControl`), shown only while the lock is a live concern.

Start and Stop both live here, and neither navigates anywhere: there is nowhere else to go.

### Auto-drill and deep links

`ShowPage` resolves its drill state from the URL, in this order:

1. **Legacy `?stack=<id>&cue=<id>`** — rewritten to the path form `/show/stacks/<id>?cue=<id>`. This
   must run before any playhead follow, which is why it is a separate effect.
2. **Show running, no `:stackId`** — drill into the live stack on first mount, so the operator lands
   where the action is. Tracked via `initialDrillDoneRef` so it fires once; navigating back to the
   overview afterwards is not undone.
3. **Show stopped, no `:stackId`** — start at the overview. Standard pre-show prep.

Thereafter the **playhead follow** takes over: when the live stack changes and the operator was
standing on the old one, the view comes along (`replace: true`). An operator who had navigated
elsewhere is left where they were.

A stale `:stackId` redirects to the overview — but **not while the stack list is refetching**, or the
refetch that follows creating a stack would bounce the operator straight back out of it.

### Sync with runner state

- **Live pill on the active stack** in the overview — findable at a glance.
- **Active-cue marker** in `StackDetail`, gated on `drillStackId === activeStackId` so it never
  lights up on a stack that is merely being read. It reads the *server* cursor, so it holds on the
  outgoing cue through a crossfade while the fade chrome moves to the incoming one.
- **Armed-cue accent** — blue. `CueCardEditor` had drawn this since 2a and `StackDetail` had accepted
  the prop, but nothing supplied it, so the affordance was unreachable in Show until the transport
  merged.


## The runner, inside Show

Since session 2b there is no separate Run view. `/show` is the runner whenever the lock is engaged;
see "Show Mode, in one view" above for the mode table and "Browsing versus arming" for the tab strip.
What follows is only what is specific to *running* rather than to the merge.

**When the show is stopped** there is no Start hero any more — the stack list is simply editable, and
Start lives in `ShowHeader` like Stop. The hero existed because `/run` had nothing else to show with
the show down; the merged view always has the show to show.

**Stop is confirmed** ("Stop the show?"), owned by `ShowHeader` so every surface gets the same guard:
accidentally cancelling cue state mid-show is more disruptive than the extra click costs. The
deactivate mutation patches the playhead cache on success, so the transition is flicker-free.

**The phone runner** is `RunMobile`, swapped in below **600 px** of *container* width — not viewport
width, so side panels opened on desktop (AI chat, and the overview panels — stage, fixture, speed
masters, cue slots) that squeeze
the body below the threshold also flip the view. It replaces the ShowBar entirely, carrying its own
transport footer, and it is **always locked**: it is a running surface with no room for editing
chrome, so there is nothing an unlocked state could reveal. Its `MobileExpansion` model
(`{card, mode}` across two hero cards) is untouched by the desktop expansion rules — it is not a
cue-list model at all.

**The ShowBar** (Row 3) is **identical on the two live views that have one** — Show and the Prompt
Book; the programmer has drawn none since the space plan's session 5, and Busk none since the
busk-chrome plan's session A, whose side-sheet Show tab mounts the phone runner over the same
`useShowBarProps` result instead. Every host spreads
`showBarProps` from `useShowBarProps` and overrides exactly one prop — `showShortcuts`, which
advertises keys and can only be answered by the host that binds them.

That uniformity is the point rather than a saving. Each host used to wire the bar itself, and the
result drifted: the Prompt Book's copy had no Blind tile and derived the stack name its own way, Show
suppressed the stack name beside the tab strip, and the Prompt Book called `useShowTransport`
directly — so adopting the hook there also collapsed that page from two transport instances to one.
`canOperate` and `onBeforeGo` are hook parameters for the two things that genuinely are per-host:
book-level permission, and re-locking on GO.

Two 2b changes worth knowing:

- **It is no longer gated on the show running.** It carries blackout, the speed masters and the
  programmer chip, all of which mean something with the show down, and `goDisabled` already mutes
  BACK/GO. Gating it was what once made Blind's *location* depend on the show's state.
- **Blind moved into it in 2b, and out again with `PD-BLIND-ON-PROGRAMMER`.** 2b took it out of the
  programmer's action-bar Stage zone so one control was in one place on every view *that has the
  bar* — and when the space plan's session 5 took the bar off the programmer, that left the press on
  the three views whose programmer is usually empty and off the one whose output it gates, which the
  desk pass found unliveable. Blind is a programmer fact (`ProgrammerSummary.blind`,
  `programmerSetBlind`, the programmer's fade), so it is the action bar's control again, and the bar
  *reports* it: `useShowBarProps` supplies no `onBlind` for any host, no tile is drawn, and
  `ProgrammerIndicator` — which lost its `blindShownSeparately` arm with the tile — draws its amber
  badge in the bar exactly as it does in the app header. One control, one place, still; the place
  changed. See this repo's `CLAUDE.md` §Cues, Stacks & Triggers.
  The fade is the thing to keep an eye on across any further move: `lib/programmerFade.ts` is a
  store rather than a `usePersistentState` per reader, because two instances of one key held two
  mount-time snapshots and Blind snapped for the rest of the visit.

> Note for whoever wires blackout up: **DBO is currently local state with no side effect** in every
> host — `FU-FE-DBO-INERT`. Between 2b and `PD-BLIND-ON-PROGRAMMER` a functional Blind tile sat
> immediately beside it and the two read as peers; DBO stands alone now, and still does nothing.


## Playback Flow

### GO Command
Keyboard: `Space` | Button: `GO`

1. **Normal GO** (standbyCueId exists):
   - Redux: `go()` -- moves standby to active, guesses the next standby (optimistic only; the
     server's frame is what both settle on)
   - Backend: `POST /cue-stacks/{id}/activate` (first GO) or `POST /cue-stacks/{id}/advance`
     (subsequent). No `go-to`: the backend holds the armed standby and `advance` FORWARD fires
     it, so a cue re-queued via click-to-requeue fires correctly *and* the MIDI surface's GO
     fires the same cue the tablet has on deck. Arming itself POSTs
     `/cue-stacks/{id}/standby`.
   - Animation starts fade progress from `fadeStartElapsedMs` (0 for a local GO)

2. **Boundary GO** (standbyCueId is null, end of stack):
   - ShowBar shows `-> {nextStackName}` hint in blue
   - Redux: cancels animations, sets `activeEntryId` to next STACK entry
   - Backend: `POST /project/{id}/show/advance` (direction: FORWARD)
   - WS event confirms the entry switch

### BACK Command
Keyboard: `ArrowLeft` | Button: `BACK`

- If mid-fade (activeCueId exists): cancels fade, returns cue to standby
- If idle (no activeCueId): moves standby cursor back to previous cue
- Backend: `POST /cue-stacks/{id}/advance` (direction: BACKWARD)
- BACK never crosses stack boundaries automatically

### Auto-Advance
When a cue has `autoAdvance: true`:
1. After fade completes, starts countdown timer (`autoAdvanceDelayMs`)
2. Blue progress bar shows countdown
3. On expiry, auto-fires GO

### Stack Switching (entry strip click)
1. Deactivates current stack on backend if active
2. Calls `POST /project/{id}/show/go-to` with target entry ID
3. Runner state resets: standby = first cue, completed = empty

## Show Activation

### Lifecycle
1. **Show not active** (`show.activeEntryId == null`):
   - Program header shows a Start Show button (disabled when no stacks), body shows the editable overview / drill-in detail.
   - Run header shows only the breadcrumb; body shows the Start CTA hero.
   - Clicking Start from either surface calls `POST /project/:id/show/activate`. From Program we additionally navigate to `/run` on success so the operator lands on the runner. From Run we stay on `/run` — the body flips from the CTA to the runner.
2. **Show active**:
   - Program header shows a "Go to Run" button (green dot + arrow). Auto-drills into the active stack on mount so editing detours land where the live cue is.
   - Run header shows the Edit Cue button + green dot + Stop. Body is the runner.
3. **Stop / Deactivate**: clicking Stop opens a "Stop the show?" confirmation Dialog, owned by `ShowHeader`. On confirm, backend `/deactivate` runs; the server clears the playhead and broadcasts; the refetch updates `isShowActive`. The view stays where it is and becomes simply editable — a stopped show has nothing to protect, so the lock chrome disappears with it.
4. **Activate details**: backend short-circuits if already active (no cue stack reset on repeat activates). On first activate, picks the first STACK entry and starts its cue stack at the first STANDARD cue.

### No-flicker activate / deactivate
Both `activateShow` and `deactivateShow` mutations use `onQueryStarted` to patch `projectShow` cache (`activeEntryId`) the moment the server responds, before the `invalidatesTags`-triggered refetch completes. This means:
- Start on Program → navigate to `/run` → RunPage mounts already seeing `isShowActive: true`. The Start CTA never flashes.
- Stop on Run → body flips to the Start CTA without a transient re-render of the runner with stale state.

### Initial Load Sync
The backend is the source of truth. On mount each page fetches the show via `useProjectShowQuery(projectId)`; `isShowActive = show.activeEntryId != null`. A reload on `/run` lands the user on the same `activeEntryId` as before (entry persisted server-side). A reload on `/program` preserves drill state only as component-local state — reloading re-opens the Show Overview, which is fine since drilling is a one-click action.

## REST API Endpoints

### Show (project-level playhead over the ordered stacks)
```
GET    /project/{id}/show                       Get playhead state { projectId, activeStackId, canEdit }

POST   /project/{id}/show/activate              Start playback (first runnable stack)
POST   /project/{id}/show/deactivate            Stop playback
POST   /project/{id}/show/advance               GO to next/prev runnable stack (skips separators)
POST   /project/{id}/show/go-to                 Jump to a specific stack { stackId } (rejects separators)
```
Stack *content and order* (including separators) live under `/cue-stacks` below — there is no
separate show-entries collection.

### Cue Stacks
```
GET    /project/{id}/cue-stacks                             List stacks + separators in show order
GET    /project/{id}/cue-stacks/{stackId}                   Get stack details
POST   /project/{id}/cue-stacks                             Create stack OR separator (type=SEPARATOR)
PUT    /project/{id}/cue-stacks/{stackId}                   Update stack / separator
DELETE /project/{id}/cue-stacks/{stackId}                   Delete stack (cascades its cues) / separator
POST   /project/{id}/cue-stacks/reorder                     Reorder the project's stacks + separators { stackIds }

POST   /project/{id}/cue-stacks/{sid}/reorder               Reorder cues within a stack
POST   /project/{id}/cue-stacks/{sid}/add-cue               Add / move a cue into the stack
POST   /project/{id}/cue-stacks/{sid}/sort-by-cue-number    Sort cues by Q number

POST   /project/{id}/cue-stacks/{sid}/activate              Start stack playback
POST   /project/{id}/cue-stacks/{sid}/deactivate            Stop stack playback
POST   /project/{id}/cue-stacks/{sid}/advance               GO forward/backward
POST   /project/{id}/cue-stacks/{sid}/go-to                 Jump to specific cue
```

### Cues
```
GET    /project/{id}/cues                                   List all cues
GET    /project/{id}/cues/{cueId}                           Get cue details
POST   /project/{id}/cues                                   Create cue
PUT    /project/{id}/cues/{cueId}                           Update cue
DELETE /project/{id}/cues/{cueId}                           Delete cue
POST   /project/{id}/cues/{cueId}/copy                     Duplicate cue
```

## WebSocket Events

All messages are JSON with a `type` field, received on the shared WebSocket connection.

| Message Type | Payload | Effect |
|-------------|---------|--------|
| `showEntriesChanged` | (none) | Invalidates `ShowEntries` RTK Query tag. Fired on entry CRUD operations (add, remove, reorder, update). |
| `showChanged` | `projectId`, `activeStackId`, `activeStackName` | Fired on any playhead change — activate, deactivate, advance, go-to. When deactivating, `activeStackId`/`activeStackName` are `null`. |
| `cueStackListChanged` | (none) | Invalidates `CueStackList` RTK Query tag |
| `cueListChanged` | (none) | Invalidates `CueList` RTK Query tag |

### Subscription Pattern
Each WS API module exposes subscribe methods returning a `{ unsubscribe }` handle. The store layer subscribes globally to invalidate RTK Query tags (e.g. `show.subscribeToEntriesChanged` → invalidate `ShowEntries`). Components subscribe locally for real-time state updates (e.g., `RunPage` subscribes to `show.subscribeToChanged` to track `activeEntryId`).

## State Management

### RTK Query Cache
All CRUD operations go through RTK Query with tag-based cache invalidation:
- `ShowEntries` -- invalidated by any show mutation or WS `showEntriesChanged`
- `CueStackList` -- invalidated by stack mutations or WS `cueStackListChanged`
- `CueList` -- invalidated by cue mutations or WS `cueListChanged`
- `FixtureEffects`, `GroupActiveEffects` -- invalidated by playback mutations (activate, advance, deactivate)

### Optimistic Updates
Several mutations apply optimistic cache patches for instant UI feedback:
- `activateCueStack`: immediately sets `activeCueId` on the stack
- `deactivateCueStack`: immediately clears `activeCueId`
- `advanceCueStack`: computes next cue locally, patches cache
- `reorderCueStackCues`: immediately rewrites `sortOrder` in cue list cache

All optimistic updates are rolled back on server error.

### Runner Redux Slice
The `runnerSlice` manages per-stack playback state entirely on the frontend:
- `go`: moves standby -> active, computes next standby (respects loop flag, skips MARKERs)
- `back`: reverses cursor (mid-fade: active -> standby; idle: standby -> previous)
- `setStandby`: re-queues a specific cue as the next GO target (click-to-requeue). Optimistic — the caller also POSTs `/standby`, and the server's frame is what every session ends up believing. Clears the cue from `completedCueIds` so the "done" tick doesn't linger.
- `resetStack`: initializes runner for a stack, restoring from the server's `activeCueId` / `nextCueId`
- `markDone`: marks fade complete, clears active
- `applyServerRunState`: adopts a `cueRunStateChanged` frame. Always takes the armed next and the
  done-marking; starts the *animation* only when the frame is a `transition` (a GO just happened)
  or carries a non-null `fadeElapsedMs` (we joined mid-fade). Without that gate the snapshot sent
  on connect would replay the live cue's fade from zero every time a page loaded.

### Auto-advance is the server's timer

`useRunnerAnimation`'s auto-advance countdown is a *display* of what the backend is doing —
`CueStackManager.scheduleAutoAdvance` fires the next cue itself and broadcasts. The client
deliberately does **not** call the server when its countdown completes: with several sessions
open, that stepped the stack once per session.

Because it is only a display, the countdown runs off the frame's `autoAdvance`
(`runner.serverAutoAdvance`), not the cue's own flag — a cue-edit Live session and the surface's
Pause binding both cancel the server's timer on a cue still configured for one, and a bar
completing into nothing is worse than no bar. The cue's flag is the fallback until the first
frame arrives.

### Effective Active Cue (`effectiveActiveCueId`)

SNAP cues (no fade) complete in a single frame: `go` sets `runner.activeCueId`, then `markDone` immediately clears it. The server's `stack.activeCueId` still points to the cue on stage. `RunPage` therefore derives:

```typescript
const effectiveActiveCueId = runner.activeCueId ?? stack?.activeCueId ?? null
```

This composite id drives the green active highlight in the cue list, the ShowBar's active-cue label, and click-to-requeue's "already active" guard. During a fade `runner.activeCueId` is authoritative (it drives progress bars); after `markDone` the server-tracked value takes over so the cue stays visibly active. Program view uses `activeStack.activeCueId` directly since it never runs fades.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Project IS the show (no ShowSession layer) | The 1:N relationship between project and sessions added complexity without practical value — operators treat a project as a show. Entries live directly on the project. |
| Split Program and Run into separate routes | Each surface has a different primary action (Start vs GO). Collapsing them under a single `/show?mode=` route made the running/stopped state hard to read and buried the Start button. Separate sidebar entries make the split obvious and let each view own its own header. |
| Start on Program and on Run; Stop on Run only | You can kick off from either surface — Program's Start navigates to Run post-activate; Run shows a big Start CTA when inactive so re-starting after a Stop is one click. Stop belongs with the live controls on Run. |
| Breadcrumb shows drilled stack on Program but not Run | Program's drill state isn't otherwise visible above the StackDetail header — the breadcrumb segment makes it scannable and clickable. Run is always at one stack and the strip / picker already names it, so a breadcrumb segment would just duplicate. |
| Confirmation Dialog on Stop, immediate Start | Stopping mid-show is destructive (clears active cue, breaks the live performance) — worth a click to guard against fat-finger. Starting is recoverable (Stop is one click away on the same view), so no confirmation. |
| Edit Cue deep-link from Run | An operator who notices a problem with the live cue should be one click from editing it, not asked to navigate via Program → drill into the active stack → click the cue. Query params (`?stack=&cue=`) keep the link stateless and shareable, then strip on first read. |
| Auto-drill on Program mount when running | Brings back the tech-run ergonomic from the old mode-toggle world without coupling to any URL state — `initialDrillDoneRef` ensures it fires once per mount so the operator can still escape to Show Overview without being snapped back. |
| Optimistic patch on activate / deactivate via `onQueryStarted` | `invalidatesTags` triggers a refetch but doesn't update the cache until the refetch completes, leaving a brief window where the page could render the wrong state (e.g. Start CTA after Start click). Patching `activeEntryId` directly on success closes that window. |
| Show-driven stack strip (not all-stacks) | Run only displays stacks in the show, in order, with marker dividers |
| Right-side Sheet for cue editing | Matches existing Sheet pattern used throughout the app |
| Expandable rows in Program view | Allows the Program view to eventually replace the FX Cues view |
| BACK never crosses stack boundaries | Intentional safety constraint -- only GO advances between stacks |
| Show "active" = `activeEntryId != null` | Single source of truth on the project; no separate `isActive` flag to keep in sync |
| `stackMap` for O(1) lookups | UseMemo'd `Map<number, CueStack>` avoids repeated `.find()` in entry strip and show overview |
| Container-width switch to remote-control view | Responds to the runner's actual available space, not just viewport — side panels on desktop can also trigger the compact layout. Single-tree mount via `useNarrowContainer` rather than CSS `@container` hide/show keeps the DOM lean. |
| Green = active, blue = standby (not amber/green) | Green reads as "on / go" and has higher contrast against the dark runner background than amber. Blue for standby (next) avoids confusion with the "show is running" green dot in the header — the standby colour is a cooler tone that implies "queued, not yet firing". |
| Click-to-requeue in Run view (not Program) | Run is the playback surface; the operator's main action is controlling cue order. Program is for editing — a click-to-edit there (opening CueEditor) is more valuable than re-queueing. In Run, the eye icon provides the detail-view affordance so the row click is freed for re-queue. |
| `advance` on GO, not an explicit cue id | This row used to argue the other way: `advance` couldn't see a frontend re-queue, so GO sent the standby cue id through `goToCueInStack`. Standby became server-owned (`setCueStackStandby`), so `advance` FORWARD now fires whatever the backend has on deck and the two cannot disagree. `goToCueInStack` was left with no caller and has been deleted. |
| `CueDetailSheet` read-only view | A lighter alternative to opening the full CueEditor in Run view. Operators want to inspect cue composition (presets, effects, triggers) at a glance without the risk or overhead of the edit form. The sheet has an Edit button that deep-links to Program's CueEditor if the operator decides to make changes. |

## Known Gaps

1. **No script quick-fire panel** -- no way to fire scripts ad-hoc during a show without attaching them to a cue trigger.
2. **Boundary GO end-to-end** -- `advanceShow` has only been tested locally; needs full lifecycle verification with the backend.
3. **Program view on narrow viewports** -- Show Overview and Stack Detail still use the desktop layout on phone-sized containers; phone-first authoring is a separate effort.
