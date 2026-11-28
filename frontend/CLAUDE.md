# Claude Code Configuration

**Keep this file to rules and pointers.** It loads into every session and every subagent, so a feature's design, endpoints and history go in its doc under `docs/ui-*.md`, with at most one line here pointing at it.

## Project Overview

This is the React frontend for the DMX lighting controller system. It lives in `frontend/` of the
lighting7 repo; the backend (Kotlin/JVM) is the repo root, `..` from here, and its own guidance is
`../CLAUDE.md`. They were two repos, `lighting7` and `lighting-react`, until they were merged with
both histories intact — every pre-merge commit keeps its hash, so a hash cited below still
resolves; use `git log --follow` for a file's history from before its move into `frontend/`.
Prose below still says "lighting7" for the backend.

## Git workflow

Every change reaches `main` through a pull request — the branch, merge and shipping-verb rules are
the root's, in `../CLAUDE.md` §"Git workflow". Two points bear repeating from this side:

- A change that crosses the wire is **one PR**: the backend route and this side's client land
  together, never as two PRs that are each broken without the other.
- CI's `Frontend` check runs the full gate below, `npm run check`, on every PR, split into a
  build-and-lint job and three Vitest shards. The `Backend` job builds this app as well
  (Gradle's `buildFrontend`), with Gradle's pinned Node rather than `.nvmrc`'s.

### Pre-commit gate

```bash
npm run check:quick
```

That's `build` + the Vitest files affected by changes since `origin/main` + `lint` + `licences`
(the licence gate, `../docs/licensing.md`). The full
suite, `npm run check`, runs in CI on every PR; run it locally only for a change CI is a slow
place to find out about (see `../CLAUDE.md` §"Pre-commit checks"). A change to `package.json`
or the Vite/Vitest config makes `--changed` run every test file (Vitest's `forceRerunTriggers`).

Lint is cached in `node_modules/.cache/eslint/`, content-hashed: a cold run is minutes (mostly
`import/no-cycle` walking the import graph), a warm one seconds. That rule reads other files, so
a cached file can keep a stale result; a new cycle is still caught, because the import that
closes it is in an edited file, which is re-linted. After changing `tsconfig` path aliases,
delete the cache. CI's run on `main` lints without it.

`check` is `build` + `test` + `lint` + `licences`. There's no separate `type-check` step in it:
`build` is `tsc && vite build`, so the standalone `npm run type-check` would run
the same full `tsc` a second time for ~9s of nothing. Use `type-check` on its
own during development when you want types without a build.

Lint is a real gate now. The tree is at 0 errors and 0 warnings, so any finding
ESLint reports is one this change introduced. Fix it rather than committing
over it. `npm run lint` passes `--max-warnings 0`, because plain `eslint` exits
0 on warnings and would wave them through.

**A git hook enforces this.** `.githooks/pre-commit` at the repo root runs
`npm run check:quick` in `frontend/` and refuses the commit if it fails; it skips the
run when nothing buildable under `frontend/` is staged (docs, `.idea/`, assets,
backend-only changes). Enable it in a fresh clone with
`git config core.hooksPath .githooks` from the repo root. Bypass a single commit with
`git commit --no-verify`. It checks the working tree rather than the staged
snapshot — see the comment at the top of the hook for why.

Warnings count. `react-hooks/exhaustive-deps` in particular is left at `warn`
because the right answer is case-by-case, not because it can be ignored —
adding a dependency changes when an effect re-runs, so decide deliberately:

- **Add the dep** when the hook genuinely reads a value that can change.
- **Narrow the input** when only part of an object matters — destructure the
  fields the hook actually uses and depend on those (see `useSliderValue` in
  `src/hooks/useAttributeValues.ts`).
- **Memoise the input** when a `?? []` fallback hands out a fresh identity
  every render (see `templates` in `components/fx/FxColourPalettes.tsx`).
- **Disable with a reason** only when the narrow deps are provably complete —
  say *why* they're complete, naming the callee whose fields you checked (see
  `rigEuler` in `components/stage3d/Stage3D.tsx`).

A bare `eslint-disable` with no justification is not an acceptable fix.

## Tech Stack

- **React 19** with TypeScript
- **Vite** for bundling and development
- **Radix UI primitives + Tailwind** for UI components (via `src/components/ui/`)
- **Redux Toolkit** with RTK Query for state management and API calls
- **React Router v8** for routing — note there is no `react-router-dom` package in
  v8: import hooks and components from `react-router`, and `RouterProvider` from
  `react-router/dom`.
- **WebSockets** for real-time backend communication

### The `engines.node` range

`^22.22.2 || ^24.15.0 || >=26.0.0` is the intersection of what the dependency
set actually supports, not a tidy floor. **Don't "simplify" it to `>=22.22.2`
or `>=24.15.0`** — both are wrong:

- React Router 8 sets the hard floor at `>=22.22.0`.
- jsdom 30 accepts `^22.22.2 || ^24.15.0 || >=26.0.0` — it skips the
  odd-numbered 25 line entirely, and 24.0–24.14 with it. That's what carves
  the range into three clauses.

Recompute it when a dependency bumps its own `engines`; `npm install` warns
(`EBADENGINE`) rather than failing, so a wrong range is easy to miss.

## Project Structure

```
src/
├── api/              # API layer - WebSocket and REST communication
│   ├── lightingApi.ts    # Main API facade combining all sub-APIs
│   ├── internalApi.ts    # WebSocket connection management
│   └── *Api.ts           # Individual API modules (channels, scenes, etc.)
├── store/            # Redux store configuration and RTK Query slices
│   ├── index.ts          # Store configuration
│   ├── restApi.ts        # Base RTK Query API
│   └── *.ts              # Entity-specific query hooks
├── routes/           # Route components (pages) — see "Components" for what may live here
│   ├── Channels.tsx      # DMX channel control
│   ├── Fixtures.tsx      # Fixture management
│   ├── Scenes.tsx        # Scenes and chases
│   ├── Scripts.tsx       # Kotlin script editor
│   └── legacyRedirects.tsx  # Redirects for paths that no longer name a view
├── App.tsx           # Router configuration
├── Layout.tsx        # Main layout with navigation drawer
├── boot.ts           # The page's only script: decides remote mode, then loads main.tsx
└── main.tsx          # Application entry point
```

## Development

### Prerequisites

- Node.js (check package.json for version)
- The lighting7 backend running on port 8413

### Commands

```bash
npm install          # Install dependencies
npm run dev          # Start dev server with hot reload
npm run build        # Build for production (runs tsc first)
npm run preview      # Preview production build
npm run lint         # Run ESLint
npm run lint:fix     # Fix ESLint issues
npm run format       # Format code with Prettier
npm run type-check   # Run TypeScript type checking
npm test             # Run Vitest test suite
npm run test:watch   # Run Vitest in watch mode
```

### Development Server

The Vite dev server proxies API requests:
- `/api/*` -> `http://localhost:8413/api/`
- `/script-editor/*` -> `http://localhost:8413/script-editor/`

WebSocket URL is automatically derived from the current host or can be overridden with `VITE_SOCKET_URL`.

## Key Features

Each view's and component's design rules are in `docs/ui-*.md`, under the headings this file used to
have. **A citation of `CLAUDE.md §<heading>` that is not a heading here is a heading in one of these**
(or a `####` inside one: grep `docs/ui-*.md` for it). Read the section before changing what it covers.

- [docs/ui-kit.md](docs/ui-kit.md): Sheet kit; List shell; Library sheets; The editor kit; The cell editor's three forms
- [docs/ui-programmer.md](docs/ui-programmer.md): The fixture sheet; Applying to a selection; The desk selection has a mask; One selection, two shapes; The programmer's keyboard; ⌘K on the programmer; The programmer's undo; Undo; The programmer's scoped grid; The programmer's tables; Fixture colours
- [docs/ui-busk.md](docs/ui-busk.md): Looks, palettes and layers; The busk layout; Focus and the side sheet; The rig; The hand
- [docs/ui-show.md](docs/ui-show.md): Cues, Stacks & Triggers; Speed Masters; A stack's FX amount; Move in dark; The grand master; Make rig safe and Quit desk
- [docs/ui-stage-views.md](docs/ui-stage-views.md): Stage views
- [docs/ui-shell.md](docs/ui-shell.md): Scripts; Scenes & Chases; Fixtures; Channels; The MIDI surface view; Windows, full screen and the hand; Desk accounts; Cloud sync — a project on GitHub; In-app updates; Remote access; Lighting7 Cloud; Sentry; Navigation Registry; The app bar and the switcher; A project's home

## API Communication

The app maintains a persistent WebSocket connection to the backend for:
- Real-time status updates
- Channel value streaming
- Track status updates

REST API is used for CRUD operations on scripts, scenes, fixtures, etc.

**Reconnect resync is central and derived.** `store/status.ts` invalidates every tag in
`REST_TAG_TYPES` (exported from `store/restApi.ts`) on a CLOSED→OPEN transition, minus a short,
argued exclusion set — `Auth` only, because `AuthGate` already fetches `auth/status` on the first
connect and `authWsApi` carries the `seenOpen`-guarded catch-up for genuine re-opens. Do **not**
add an `open` branch to a WS bridge just to re-invalidate its own tag: that duplicates the central
dispatch, and the hand-maintained list it replaced had drifted to 15 tags of 47 while claiming to
cover them all. An `open` branch is still right when it re-sends something the *server* forgot —
`speedMastersWsApi` re-requests its one-shot beat subscriptions, which live on the server's
per-connection scope — but it should then do only that.

The dispatch is **debounced and waved**, not one tick: a reconnect usually means the backend has
just restarted, and lighting7 serves REST from a single pooled SQLite connection, so the whole set
arriving at once serialises behind a show that is still warming up. `RESYNC_DEBOUNCE_MS` lets a
flapping link settle, then `RECONNECT_RESYNC_WAVES` goes out `RESYNC_WAVE_SIZE` tags at a time,
operator-visible caches first; a drop mid-sequence abandons the rest. The waves are a transport
detail only — `src/store/status.test.ts` pins that they concatenate to exactly the resync set, so
a tag can never fall out by landing in no wave.

### Where a WS bridge subscribes

A "bridge" is a store slice's standing `lightingApi.<x>.subscribe(…)` that turns a pushed frame
into a `dispatch` — usually an invalidation. There are three places to put one, and the choice is
not stylistic:

1. **At module scope — the default.** A bare `lightingApi.x.subscribe(...)` statement at the top
   level of the slice. Use this unless one of the other two applies. It runs once, when something
   first imports the slice, and lives for the life of the tab; that is right for a bridge whose job
   is to keep a cache honest whether or not anything is currently rendering it.
2. **Deferred, started from `main.tsx`** — an exported `startXBridge()` the slice does *not* call
   itself. Use this **only when the slice sits on the earliest render path**: imported, directly or
   transitively, by something that renders before or during the first paint — the sidebar and its
   nav registry (`src/navigation.ts`), `Layout`, `AuthGate`, the boot overlay, or a picker those
   mount. The hazard is a runtime import cycle: if any module in `api/lightingApi`'s own import
   closure reaches back to the slice, the slice's body can run while `lightingApi` is still
   mid-initialisation, and touching it there throws a TDZ `ReferenceError` that takes *every export
   of the slice* with it. `tsc`, `vite build` and the unit tests all pass anyway — the symptom is a
   blank-looking app in the browser. `store/looks.ts`'s doc comment is the long version.
3. **Per cache entry, inside `onCacheEntryAdded`** — not a bridge at all, but the right answer for
   a *stream* rather than a notification: the value itself arrives over WS and there is nothing to
   refetch. Subscribe when the entry is created, `updateCachedData` on each frame, unsubscribe on
   `cacheEntryRemoved`, and seed `queryFn` from the WS layer's cached snapshot so a late mount does
   not render empty. `store/speedMasters.ts` (`speedMasterLive`) and `store/surfaces.ts` (devices,
   banks, pickups, scaler) are the worked examples. Prefer this over `useState` + `useEffect` in a
   hook: two components reading one stream then share a subscription, and RTK Query owns teardown.
   **Not for a stream that moves at frame rate**: `updateCachedData` is a dispatch, so a per-channel
   entry over `channelState` costs the whole store a reducer pass and a subscriber scan per channel
   per frame, for a value nothing outside the reading component consumes. Those read the WS layer's
   own per-key subscription through `useSyncExternalStore` instead — `useChannelValue` and its
   neighbours in `hooks/useAttributeValues.ts`.

The census as of this writing, so a new slice can see which company it is in: **33 module-scope
sites across 23 slices** (`grep -n '^lightingApi\.' src/store/*.ts`), and **three deferred**, all
started from `main.tsx` — `looks`, `palettes`, `programmerErrors`. The imbalance is
the rule working, not drift: form 1 is the default and form 2 is the exception, and the three are
exactly the slices the sidebar and the first paint reach. `store/windows.ts` is on the sidebar's
path too (`UserMenu` reads the window count) and is **neither**: its only subscription is form 3, a
`queryFn` that closes over `lightingApi` and touches it when the first reader mounts, so it needs no
`startWindowsBridge()`. The half of that family that turns a frame into an action — the announce
and the three command handlers — lives in a hook (`components/screens/useWindowsBridge.ts`, mounted
once in `Layout`), because it needs the router's location and `navigate`, which exist only inside
`RouterProvider`. `stageRender.request` is the same kind of thing and is **not** a bridge either:
`StageRenderHost` subscribes in an effect because a request mounts a component (the render), not a
cache entry, and it is mounted once in `Layout` for the same reason the windows hook is.

Nothing is being migrated toward form 2. `import/no-cycle` is an ESLint **error** in this repo, so
the precondition for the TDZ hazard — an import cycle through `api/lightingApi` — cannot reappear
silently; the four deferred bridges stay deferred as defence in depth for the render-order half,
which the lint rule does not see.

**There are two legitimate `open` re-sends, and both re-send what the *server* forgot.** The
first is `speedMastersWsApi`'s beat requests, which live on the server's per-connection scope. The
second is `windows.announce` in `api/windowsApi.ts`: the desk's windows registry keys its rows by
socket, so a reconnect is a new socket with no row until this tab says again what it is. That
branch re-sends the last announce and nothing else — no `windows.state` request, since the desk
pushes the snapshot on every connect — and it is the frame that carries the window's name, which is
why `api/selectionApi.ts` still has no `open` branch and never will: a `selection.set` on connect
would be a *write* that replaces the desk's selection and makes a reconnecting tab its last mover.

## Patterns and Conventions

### State Management
- Use RTK Query hooks (`useXxxQuery`, `useXxxMutation`) for all API interactions
- Queries auto-subscribe to WebSocket updates where relevant
- Avoid local state for data that should be synchronized with the backend

### Components
- Route components in `src/routes/`
- Shared/utility components in `src/`
- Use Radix UI primitives (via `src/components/ui/`) and Tailwind for UI

**The desk's shell is one fixed screen** (venue-rig-fixes plan D13). While `Layout` is mounted it
sets `desk-shell` on `html`, and `index.css` gives `html`, `body` and `#root` full height and `html`
and `body` `overflow: hidden` and `overscroll-behavior: none`, so the document never scrolls or
rubber-bands; only a view's own scrollers do. The pages drawn beside `Layout` (onboarding, a reset
link, a device login) have no class and scroll the document as before. The kit's scrollers
(`SheetTable`'s, the Cards list, `EditorSurface`'s popover, `SheetBody` and the busk side sheets'
bodies) take `overscroll-contain`, so a wheel past a list's end stops at it. **A scroller must be
positioned (`relative`) when an absolute descendant could outrun it**: an `overflow` box clips an
absolute child only when it is that child's containing block, and the Cards list was not, so each
cue number's `sr-only` copy hung off `body` and stretched the document to 3098px on a 60-cue stack
at 1440×900. Check `document.scrollingElement.scrollHeight` against the window after building a
scrolling view.

#### What may live in `routes/`

`routes/` is not "anything page-shaped" — it is one module per **routed resource**, and the
convention has three parts. It is worth stating because the tree looks messier than it is: most
modules export a page *and* one or two redirects, which reads like drift and is not.

1. **A module owns a resource, and everything that resolves that resource lives in it.** So
   `Fixtures.tsx` exports both `ProjectFixtures` (the page) and `FixturesRedirect` (bare
   `/fixtures` → the current project's fixtures). The redirect is part of the resource: it answers
   "which project?", not "where did this view go?".
2. **A former route that became a settings tab keeps its module and its identity**, exporting the
   tab body alongside the redirect that survives its old path — `Surfaces.tsx`, `CloudSync.tsx`.
   This is the uniform pattern, not a stray. The test is whether the module is still *routed*: if
   nothing in `App.tsx` renders it, it is a component, not a route, and belongs under
   `components/<feature>/` — which is where `RiggingsContent` and `StageRegionsContent` went.
   `Patches.tsx` was this pattern's third example and is rule 1 again: the patch list is routed at
   `/projects/:id/patches` since the list shell (§List shell), and the module exports the page
   (`ProjectPatches`) and its bare-path redirect.
3. **A redirect for a path that no longer names a view goes in `routes/legacyRedirects.tsx`**, not
   in whichever module happens to be its destination. `/run`, `/cue-stacks`, `/cues/all`,
   `/cues/standalone` and `/program` all land on `/show` (`/cues` itself names the Cues table again,
   and bare `/cues` is `Cues.tsx`'s `CuesRedirect`, rule 1), `/fx` lands on `/busk` and `/templates` on `/palettes` (search kept, for the old
   `?family=` links); collecting them keeps `ShowPage.tsx` from
   accumulating four unrelated histories, and keeps `/program` out of `ProgrammerPage.tsx`, where
   it reproduced the `/program` vs `/programmer` confusion in the file layout.

   The line between rules 1 and 3 is what a redirect *answers*. `BuskRedirect` lives in
   `Busk.tsx` because it answers "which project's busk view?" — part of the resource.
   `LegacyFxRedirect` lives here because it only answers "where did `/fx` go?".

Anything else — a pure helper, a shared type — belongs in `lib/` even when only one route uses it
(`formatRepoUrl` was exported from `CloudSync.tsx` until it moved).

**Redirect targets are frozen.** `?cue=` deep links are an external contract minted by the Prompt
Book's "Edit cue" card, so a redirect that carries `search` must keep carrying it, and no legacy
path may quietly change where it lands.

The navigation registry, the app bar and switcher, and a project's home: [docs/ui-shell.md](docs/ui-shell.md).

### Sheets vs Dialogs

Use **Sheets** (slide-in from right) for any UI that involves editing, forms, or multi-step workflows. Use **Dialogs** (centered modal) only for confirmations, alerts, and status displays.

#### Sheet structure

All sheets must follow this structure using the shared primitives from `src/components/ui/sheet.tsx`:

```tsx
<Sheet open={open} onOpenChange={onOpenChange}>
  <SheetContent className="flex flex-col sm:max-w-md">
    <SheetHeader>
      <SheetTitle>Title</SheetTitle>
    </SheetHeader>
    <SheetBody>
      {/* Scrollable form content — space-y-4 and px-4 pb-4 are built in */}
    </SheetBody>
    <SheetFooter className="flex-row justify-end gap-2">
      <Button variant="outline">Cancel</Button>
      <Button>Save</Button>
    </SheetFooter>
  </SheetContent>
</Sheet>
```

#### Key rules

- **SheetContent**: Always include `flex flex-col`. Use `sm:max-w-md` for standard forms, `sm:max-w-lg` for complex/wide content. On mobile, sheets are fullscreen by default (`w-full` in base class).
- **SheetBody**: Use for all scrollable content areas. It provides `flex-1 overflow-y-auto space-y-4 px-4 pb-4`. Override with `className="space-y-0 p-0"` only when embedding components that manage their own padding (e.g. pickers).
- **SheetFooter patterns**:
  - Create/Edit (no delete): `className="flex-row justify-end gap-2"`
  - Edit with delete: `className="flex-row justify-between"` — Delete button on left, Cancel+Save on right in a `<div className="flex gap-2">`
  - Equal-width actions (busking): `className="flex-row gap-2"` with `flex-1` on each button
- **Buttons**: Use default size in footers (no `size="sm"`). Cancel is always `variant="outline"`. Delete is `variant="destructive"`.
- **Multi-step sheets**: Use `p-0 gap-0` on SheetContent when step 1 needs edge-to-edge content (e.g. picker lists). Use SheetBody in subsequent steps for form content.
- **Sub-view footers** (content embedded inside a parent sheet, e.g. CueEffectFlow): Use `<div className="border-t p-4 flex items-center gap-2">` since SheetFooter can only be a direct child of SheetContent.
- **Unsaved changes**: a guarded sheet asks *"Discard changes?"* before Escape, a click outside or
  the X take it away. There are **two ways to say a sheet is dirty, and which one you need depends
  on where you are**:
  - `<Sheet unsavedChanges={isDirty}>` — for the component that renders the `<Sheet>` itself,
    which is the common case (it usually owns the form state too).
  - `useUnsavedChanges(isDirty)` — for a component **mounted inside `SheetContent`**, which is the
    only place it works. It reports through a context `Sheet` provides, so a call in the component
    that *renders* the `<Sheet>` resolves against providers **above** that component and finds
    nothing: `register?.()` then no-ops and **the sheet is silently unguarded**. Four sheets
    shipped that way. It is a deliberate no-op outside a sheet entirely, which is why the mistake
    is invisible (`CueTriggerEditor` relies on that for its inline mode).

  The two combine, so a parent's prop and a body's hook can both contribute. `sheet.test.tsx` pins
  both directions of the trap. **Both also feed a module-level count**, `lib/unsavedSheets.ts`,
  which `Sheet`'s provider writes from the same `hasUnsaved` it guards on — gated on `open`, since a
  controlled sheet's `unsavedChanges` prop can stay true after the panel closed. It exists for one
  reader nowhere near a sheet: a `windows.show` from another screen declines to navigate while the
  count is non-zero (§Windows, full screen and the hand). A count and not a flag, because a picker
  can sit over an editor and the second closing must not clear the first's claim. Only a close **Radix** drives reaches the question, so a Cancel
  button must be wrapped in `<SheetClose asChild>` rather than calling the parent's own
  `setOpen(false)` — and must not also carry an `onClick` that closes, since `asChild` would run
  both. Only controlled sheets can be guarded — an uncontrolled one closes itself inside Radix.
- **The Kotlin editor's completion popup** is a bare `<ul>` on `<body>`, invisible to Radix's
  layer stack, so `SheetContent` special-cases it twice: Escape while it is open closes the popup
  and not the sheet, and clicking a suggestion doesn't count as clicking outside. Both are in the
  primitive, not in the editor's own sheets, because every sheet that mounts a script editor
  would otherwise need them.

### TypeScript
- Strict mode enabled
- Prefer explicit types over `any`
- Use interface for object shapes

### Dependencies
- Reaching for a well-maintained library is fine — often better — when the
  alternative is rebuilding non-trivial functionality yourself (a testing
  framework, date/time math, virtualization, etc.). Don't reinvent that.
- But don't add a dependency to solve a trivial problem you could write in a few
  lines (the left-pad trap), and weigh the transitive cost — avoid dragging in a
  large or poorly-maintained tree for a small need ([xkcd 2347](https://xkcd.com/2347/)).
- When it's a genuine judgment call, flag the trade-off and get a quick yes
  before adding, rather than silently growing (or silently avoiding) the
  dependency set.

## Backend API Reference

Every REST endpoint and WebSocket message is in `../docs/api-reference.md`, grouped by area. REST is
under `/api/rest`; WebSocket messages use JSON with a `type` field for routing.
