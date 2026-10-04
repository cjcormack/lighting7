# Claude Code Configuration

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
- CI's `Frontend` job runs exactly the gate below, `npm run check`, on every PR. The `Backend` job
  builds this app as well (Gradle's `buildFrontend`), with Gradle's pinned Node rather than
  `.nvmrc`'s.

### Pre-commit gate

```bash
npm run check
```

That's `build` + `test` + `lint`. There's no separate `type-check` step in it:
`build` is `tsc && vite build`, so the standalone `npm run type-check` would run
the same full `tsc` a second time for ~9s of nothing. Use `type-check` on its
own during development when you want types without a build.

Lint is a real gate now. The tree is at 0 errors and 0 warnings, so any finding
ESLint reports is one this change introduced. Fix it rather than committing
over it. `npm run lint` passes `--max-warnings 0`, because plain `eslint` exits
0 on warnings and would wave them through.

**A git hook enforces this.** `.githooks/pre-commit` at the repo root runs
`npm run check` in `frontend/` and refuses the commit if it fails; it skips the
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
  `src/hooks/usePropertyValues.ts`).
- **Memoise the input** when a `?? []` fallback hands out a fresh identity
  every render (see `templates` in `components/fx/FxColourTemplates.tsx`).
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

### Scripts

Kotlin scripts for lighting automation. Editing uses the embedded `kotlin-playground`
widget (`src/kotlinScript/`), whose highlighting and autocomplete are served by **lighting7
itself**, from the same embedded Kotlin compiler that runs the scripts —
`/script-editor/*`, backed by `routes/scriptEditor.kt` + `scripts/ScriptEditorService.kt`.
There used to be a bundled `kotlin-compiler-server` fork behind `/kotlin-compiler-server`,
in a second JVM on port 8321; it is gone.

Two things to keep in step with the backend:

- **`wrapForEditor` in `components/scripts/ScriptEditor.tsx`** hands the widget the
  `//@lighting7-script-type=<TYPE>` marker, then the body between `//sampleStart` and
  `//sampleEnd`, and nothing else. Both halves are load-bearing, for different consumers, and
  neither is presentation:
  - The **fold markers are the widget's**. They fold the editor down to the body, make
    `onChange` hand back only the body, and offset every position it reports or asks about.
    The widget then *strips* them and posts `prefix + editorContents + suffix`, so the
    backend never sees them and `EditorDocument` always takes its no-marker path.
  - The **type marker is the backend's** — how it picks the template. Drop it and that editor
    silently falls back to GENERAL, losing every FX symbol. It cannot move into a query
    param or a per-type base URL: the widget owns the request shape, and its `server` is a
    module-level global that every `playground()` call overwrites, so two editors of
    different types would poison each other.

  There used to be a synthetic base class and import list per type here. Because the widget
  sends everything outside the fold markers verbatim, that stand-in was what the backend
  actually compiled — its constructor signature had to track the real base class by hand.
  With just the marker line, the real `.kts` template is what the body is compiled against.
- **The widget's own Run button is hidden** (`.kotlin-editor .run-button` in `index.css`).
  Every surface supplies its own Run wired to `/{projectId}/scripts/run`, which runs against
  the live show; the widget's button was a second, less correct path to the same thing.

The list is a sheet on the kit — type chips and dividers, a Check column filled by the bar's batch
Compile, Run over one row — and `ScriptForm` is what a row opens; see §Library sheets.

### Scenes & Chases
- **Scenes**: One-shot lighting configurations that run a script with specific settings
- **Chases**: Animated lighting sequences (same component, different mode)

Both use scripts as their base and allow configuring script settings per scene/chase.

### Fixtures
DMX fixture definitions - describes what channels a fixture uses and how to control it.

**Infrastructure fixtures.** A patch marked `infrastructure` (a dimmer on hard power, a relay) is
hidden from every view but **Patches** and **Channels**, the Stage included. The raw
`useFixtureListQuery` / `usePatchListQuery` still carry it, because a cue, group or binding that
already names it must keep resolving — so a view that **lists or offers** fixtures reads
`useVisibleFixtureListQuery` / `useVisiblePatchListQuery` (or `withoutInfrastructure` from
`lib/infrastructure.ts`), and only a view that resolves a key it already holds reads the raw list.
A new picker that reaches for the raw hook will offer the hazer's power dimmer — so ESLint refuses
the raw `useFixtureListQuery` / `usePatchListQuery` imports outside an allowlist of key-resolving
files in `eslint.config.js`; joining it is a claim reviewed there. (Enumerating
`useFixtureLookup().fixtures` is not covered: it is raw for the lookups it feeds.) A picker that
edits an existing reference keeps the one it already names, labelled, as `BindingTargetPicker`
does; the Groups views count and list members through `useVisibleGroupMembers`. The empty busk
rig's fallback (`effectiveRig`) drops it too, mirroring the desk's `BuskRigOrder`. Backend side:
lighting7 `docs/fixtures-engineering.md` §"Infrastructure fixtures".

### Channels
Raw DMX channel control per universe. Shows all 512 channels with current values.

### Stage views

Two surfaces render live fixture state: the 3D canvas and the **Positions** panel behind the
header's stage toggle (the SVG Plan/Front/Side plot went in stage-view plan session 5). Both read
through a
**`ChannelSource`** rather than
`lightingApi.channels` directly, so the operator can point them at Output / Output + Programmer /
Programmer only — which is what makes Blind previewable. Colour and intensity come from one shared
colour-source dispatch (`components/fixtures/fixtureAppearance.tsx`); the 3D path keeps a separate
imperative copy on purpose.

See [`docs/stage-vis-engineering.md`](docs/stage-vis-engineering.md). Read it before touching a
stage read path, adding a source, or relying on what `ProgrammerState.channels` means — that field
is the backend's channel *sideband*, not the programmer's channel output, and mistaking the two is
the bug that doc exists to prevent.

**The 3D canvas renders on demand** (`frameloop="demand"`), so anything added to the scene that
changes the picture without changing an R3F prop — an imperative material write, an emitter buffer
write, a uniform — must call `invalidate` (`useStageInvalidate` from `stage3d/stageInvalidate.tsx`),
or it shows only when something else happens to draw a frame. The same doc's §"The 3D renderer"
lists what already asks, and covers the other renderer rules: no MSAA in the composer, DPR at 1.5,
context-loss recovery, emitters sized by the rig, regions hanging down from `centerZ` (the top
surface), dark-glass lenses, and the one DOM label layer. **Light lands through one surface shader**
(session 3): every venue, set and region surface loops over a float data texture of the live lights
(`scene/lightTable.ts` — no uniform-array ceiling), capped by a per-browser light budget, with the
first surface on each beam's axis standing in for occlusion (`scene/beamReach.ts`); the region,
wall and floor cookie instances are gone. The haze governor gives up march steps before frame rate.

**The Stage view has five cameras on the one scene** (stage-view plan session 1): Orbit, Eye (look
around from a point) and the orthographic Plan · Front · Side sections — the header's toggle
switches cameras. The viewpoint is **per window**, `lib/stageViewpoint.ts` in `sessionStorage`, and
rides `windows.viewOptions` (§Windows, full screen and the hand); the orbit and eye poses are kept in
`sessionStorage` too (`lib/stageCameraPoses.ts`), so a reload or a context-loss *Restore* lands where
the camera was. F frames the selection — the Stage's own, else the desk's — and O goes back to
Orbit. **A viewpoint can also be a saved view or a seat** (session 2): a `stage_viewpoints` row by
its uuid in the same key, landed on the orbit or eye rig **once per pick** (a per-tab landed marker,
so a remount keeps where the operator has looked since and a re-pick lands again), picked from the
header's picker, and saved from it with *Save this view…*. **Or an unsaved seat** (session 3):
*Sit in a seat…* (S) picks one from the seating mesh and holds it as `seat:<uuid>:<seat id>` in the
same key — no new announce key — until *Save this view…* makes it a `SEAT` row. Scene elements
(`stage_elements`) are read only by the Stage route's canvas and built per kind in
`components/stage3d/scene/builders/` — pure functions a node test pins, one per kind, the seats
exactly `lib/stageSeats.ts`'s. The View menu's Venue · Set · Seating · Haze are per window
(`sessionStorage`), and so is the **vis source** now, riding `windows.viewOptions` as `source` beside
`viewpoint`. Two traps, both in the doc's §"Cameras and viewpoints": a camera swapped in must be sized by
`useDefaultCamera`, and the bloom composer rebuilds after the swap's frame, so `Bloom` invalidates on
each new composer or the view sits on the old camera's picture.

**Any window can be asked to render a viewpoint for Claude** (`render_view`, stage-view plan
session 4). `StageRenderHost` (`components/stageRender/`, mounted once in `Layout`) takes the desk's
`stageRender.request` — sent to one socket, so it is always this window's — and mounts the lazily
loaded `stage3d/render/StageRenderJob.tsx`, which draws **the Stage view's own `Stage3D`** with a
`capture` prop: a detached canvas at the asked size (`CaptureCanvas.tsx` — `createRoot`, `dpr: 1`,
`frameloop: 'never'`, driven by `advance`, so a hidden tab renders too), the viewpoint through
`resolveViewpoint` (`savedViewpoints.ts`), the default layers, no labels, and the request's vis
source through the same `StageChannelSourceProvider`, which now takes a `source` and reports
`onSettled`. The rig's `oneShot` lands it and records nothing, so the window's viewpoint, camera
poses, landed marker, layers and source never move, and nothing appears on screen. The PNG goes
back over REST with the request's token; both answers are silent endpoints. The capture root
bridges only `ChannelSourceContext` — a scene component that starts reading another context from
outside the canvas must be bridged there too. See `docs/stage-vis-engineering.md` §"Rendering for
`render_view`".

**Editing on a section is the 3D scene's too** (session 5, D1): with Edit on and the camera on
Plan, Front or Side, `stage3d/edit/SectionEditLayer.tsx` — DOM over the canvas, like the label layer
— owns the pointer and does everything the SVG plot did, in the section's metres: marquee (⇧/⌘ +
drag), click-select, body drags with alignment guides and the snap grid, drop-onto-a-bar, sliding
along a bar, the region and rigging handles (height handles in the elevations), placement clicks, pan
and zoom. The section camera reports where it looks (`StageCameraRig`'s `onSectionView`) and takes
pan, zoom and fit through `StageCameraHandle.section`; a pointer is resolved from the canvas's live
centre, because the report lags a resize. The layer is never mounted by a render, so no edit chrome
reaches `render_view`. The snap grid, nudge, tray, bulk panel and shortcut list moved to
`stage3d/edit/`; `components/stage2d/` is gone and `svgPlotRetired.test.ts` keeps it so. Edit's header
also has **`+ Scenery`**, which places a scene element like a region and opens
`components/stage/EditSceneElementForm.tsx` in the editor panel: the kind's `params`, one partial
`PUT`, and the desk's 400 problems drawn beside the fields they name (`elementProblems.ts`). See the
stage-vis doc's §"Editing on the sections", which has the parity table.

**Fixture bodies are parametric archetypes, instanced per part** (session 6):
`stage3d/bodies/archetype.ts` turns a patch, its fixture and its type into a `BodySpec` — profile,
box profile, fresnel, PAR, flood, downlight, mover, batten, blinder, effect, cannon or tape, chosen
by the **lantern** a generic dimmer is hung with (the desk's library, `GET /lanterns`, read through
`useLanternIndex`), else the type's declared `body`, else the kind, the type's words and its tilt
(session 7) — with its **cells**,
one per coloured element (the Liteobar is three), each its own lens, beam and light (at most four
lights a fixture, averaging runs of cells). `bodySpecOf` in `emitterNeeds.ts` is the one call both
`Stage3D` (sizing) and `FixtureModel` (drawing) make. `bodies/StageBodies.tsx` instances the parts
across the rig with a simple mesh and a billboard at distance; `FixtureModel` keeps an empty node
rig (placement, mount, yoke, head) and an invisible hit proxy, and copies the matrices onto the
instances every frame. Beams **leave the aperture** with the apex behind it (`apexDistanceM`), and
pool and haze share `beamMask.ts`; every beam is raymarched now. A `LEDGE` or `FLOOR_STAND` stands
its units (`bodies/mount.ts`, pinned to the desk's `STANDING_RIGGING_KINDS`), but a mover's
orientation is still its `basePitchDeg`. **A conventional's focus is drawn**: shutters, the gate's
turn, the iris and a PAR's oval are arguments to `beamMask` in the head's frame, **packed into the
light table's six texels** (the blades 12 bits each, two to a float, the angle ±45° in 1.5° steps; a
gate or lamp turn rotates the frame rather than the mask; an oval is a negative aspect) — the GLSL
and its twin change together. A DMX head's **framing shutters** (SHUTTER / SHUTTER_ROTATION sliders
naming their blade) are read every frame and packed the same way, in place of a lantern's — never
both (fixture-optics session 2).
The Stage view's **Focus** tab and the patch editor's **Lantern** box mount one `FocusCard`, and a
pair's lanterns are focused separately. See the stage-vis doc's §"Fixture bodies".

**What is loaded in a unit is the unit's** (fixture-optics session 3): a scroller's string, a module
wheel's slots and a media frame's gel are **fitted media** on the patch and each placement
(`FixturePatch.media`), laid over the type's stock option by option — the placement's, else the
patch's, else the stock — by `lib/fittedMedia.ts`'s `fittedProperties`, which `FixtureModel` and
`FixtureAppearanceSource` both run before they dispatch, so every finder in the two dispatches reads
the unit's own. A gel in a filter slot (a media frame in, a dichroic) multiplies the beam on both
dispatches. The controls that *set* a slot — the programmer's setting cell, the property
visualisers, the fixture card — still name the type's stock string (`FU-MEDIA-CONTROL-SWATCHES`).
The gel library is the desk's (`GET /gels`, `useGelIndex`; `data/gels.ts` is gone — a test reads
`src/main/resources/gels.json`), and the Stage view reads it in `useStageData` and passes it down
like the lanterns, never inside the scene, which a `render_view` capture would not bridge. The patch
sheet's **Media** box edits it, the Focus tab lists it.
See the stage-vis doc's §"Fitted media: where a beam's colour and gobo come from".

**Gobos land on surfaces, and wheels stack** (fixture-optics session 4): the surface shader samples
the gobo atlas per gobo light in the frame `beamMask` cuts in, through the same `goboLayers.ts`
sampler and blur the haze uses, so shutters cut the gobo and it focuses on a wall as in the air. A
beam carries **two layers** packed in one float (`packGobos`: the wheels in DMX-channel order, the
rotation channel turning the wheel it follows), in the haze's `aBeamFx.y` and the light table's
texel 4 `.z` — still six texels, because texel 4's frame became `(cos, sin)` in a basis built from
the axis (`lightTable.ts`'s header says why not an angle). A second **colour** wheel is a filter on
both dispatches (`colourFilters`). The View menu's *Gobos on surfaces* (per browser) can limit the
pass to the selected heads; it defaults to every gobo light. See the stage-vis doc's §"Gobos on
surfaces".

**A colour band with no single colour animates, and the beam angle has one precedence**
(fixture-optics session 5). A scroll, random or rainbow band — an option the desk marks `noColour` —
cycles through its wheel's own previews on **both** dispatches and on a second wheel as a filter,
through one pure decode (`lib/colourBands.ts`, time passed in, never read inside it); a band that
says nothing draws open white, never black. The 3D arms register with `stage3d/colourTicker.ts`
while — and only while — such a band is live, which re-applies them and asks for every frame; the 2D
leaves tick through `useColourBandTime` only while animated. The beam angle is the zoom channel
(a slider's degrees or a stepped zoom's `zoomDeg`), else the patch's, else the type's `fieldDeg`,
else the family's — `resolveBeamDeg` in `bodies/archetype.ts` — and iris and frost hold their end
value past a slider's `activeMax`. See the stage-vis doc's §"Animated colour bands".

**A strobe channel is a level factor, and the view keeps to three flashes a second**
(fixture-optics session 6). A strobe channel's bands are declared (`strobeBands` on a slider,
`strobeKind` on a setting's options; `findStrobeProperties`), and one pure decode,
`lib/strobeBands.ts` (time passed in), turns them into a 0..1 factor **both** colour dispatches
multiply into the dimmer's — the 2D `StrobeGate` around whichever leaf answered, the 3D syncs'
`liveStrobeFactor` (cells take it as their master) — so a closed band is dark on the lens, the beam,
the pool and the markers, and an undeclared value draws open. A strobe flashes at its rate up to 3 Hz,
a random band up to 2 Hz, a pulse swells up to 3 Hz, and anything faster is a shimmer under WCAG's
10% flash threshold (WCAG 2.3.1). The 3D arms ask for frames through the same `colourTicker.ts` while
— and only while — a strobe flashes, and `colourDispatchParity.test.tsx` holds 2D = 3D. See the
stage-vis doc's §"Strobe and closed shutters".

**Scenery moves with the show** (session 8): the desk resolves which state each scene element is in
— a cue's changes tracked from the top of its stack, its stack's set, live Looks above both — and
streams `scenery.state` (`api/sceneryApi.ts`, `store/scenery.ts`'s form-3 `liveScenery`). The Stage
canvas lays each element's interpolated state over its `params.states` before building
(`lib/scenery.ts`'s `sceneryElements`), so the builders draw tabs and trims unchanged and **beam
reach reads closed tabs** for free; `useSceneryClock` invalidates every frame only while a move is in
flight. The vis source picks the scenery too (`StageSceneryContext` from `StageChannelSourceProvider`:
Next GO draws the preview's). Authoring is one `components/scenery/SceneryEditor.tsx` in Cue
properties, `CueStackForm` and `LookDetailSheet`, saving the owner's whole list per gesture and
holding its draft over a refetch. See the stage-vis doc's §"Scenery that moves with the show".

**One-shot effects — the cannons** (session 9; lighting7 `docs/fixtures-engineering.md`
§"@FixtureTrigger", `docs/cues-engineering.md` §"Cue events"). A confetti cannon's tubes are
**triggers**, never values: a `TriggerPropertyDescriptor` (`type: 'trigger'`, `triggersOf` in
`store/fixtures.ts`) at the end of a fixture's properties, which every control-drawing switch skips
(`PropertyVisualizer`, `categoriseProperties`, the programmer's channel map). `api/effectsApi.ts` reads
the three outbound frames — `effects.armed` (form 3 in `store/effects.ts`, its countdown anchored to
this browser's clock from the frame's `remainingMs`), `effects.fired`, `effects.skipped` — and the
REST verbs (arm, fire, reload, a cue's whole-list events) are `store/effects.ts`'s; there is no inbound
frame (lighting7 P2). Four surfaces: the red **`ArmedChip`** on the app header and, while immersive, on
the `ShowHeader` row (`components/effects/`; a tap disarms, nothing is drawn disarmed);
**`EffectsAnnouncer`**, mounted once in `Layout`, toasting every skip on every window; the
**`CannonPanel`**, which `FixtureContent` draws in place of controls for a fixture with triggers —
loaded/spent per tube, arm/disarm, **hold-to-fire** (`HoldToFireButton`, 700 ms, a brush fires
nothing), a confirmed reload, and a fire **rehearsed** while the programmer is blind or this window's
vis source is the programmer (`rehearse: true`: drawn on every window, nothing sent); and
**`CueEventsEditor`** in Cue properties (with `CueEventsReadout` on the cue card), the session-8
scenery editor's shape — a whole-list save per gesture, its draft held over a refetch. The Stage view
throws **confetti** on every fire (`stage3d/StageConfetti.tsx` over the pure `confetti.ts`): one
instanced mesh pooled per canvas, unlit, invalidating each frame only while a flake flies — see
`docs/stage-vis-engineering.md` §"Confetti". The MIDI `fireTrigger` target is mirrored in
`lib/surfaceDrop.ts` and the binding picker; the remote-access tab carries *Allow arming and firing*.

**Positions** (`components/positions/`) replaced `StageOverviewPanel` and `StageMarker`: one row per
rigging, upstage first, the stage edge marked, derived on every render and never stored
(`FU-BUSK-RIG-PLOT`). A chip **sets the desk selection** (`setDeskSelection`, ⇧/⌘ toggles), a group
chip dims the units outside it, and the Plan tab is `Stage3D` on the plan section, lazily imported so
three.js stays out of the app shell. It is mounted on every route, so everything below
`CollapsiblePanel` — queries, channel subscriptions, the canvas — unmounts while it is closed.

**Aim at point** (`components/stage3d/StageAimControls.tsx`) is the Stage view's one live *write*
besides the docked fixture panel it sits in: a stage coordinate, or a region's centre at head
height, sent to `POST /programmer/aim` for the selected moving heads — under the single fixture's
panel, or its own docked panel for a multi-selection in view mode, and only on the live project.
The desk solves pan and tilt (lighting7 `docs/fixtures-engineering.md` §"Aiming a head at a
point"); this side never does, the `templateIntent.ts` rule, and draws the answer's skips by name.

**Focus here** (fixture-optics plan session 1) is its sibling on the Focus tab (`StageFocusPanel`):
for a DMX head whose focus declares a range, on the live project, it sends where the beam lands in
the view (`stage3d/landedPoints.ts`, recorded by the beam director for the selected fixture on an
on-screen canvas only) to `POST /programmer/focus`, and the desk solves the focus channel for that
distance. The blur that makes the result visible is a relative focus error times a per-type depth of
field (`beamMask.ts`'s `focusBlur`; the GLSL and its twin change together), packed into the light
table's texel 0 beside the focal distance. See `docs/stage-vis-engineering.md` §"Focus".

### Looks, templates and layers

**Two library entities, and a Layer applies either.** A **Look** composes cues: any families, its
own fixtures, its own effects, added to a cue's stack at a declared position. A **Template** is
**one named thing** of exactly one attribute family, with no targets of its own, applied to a
selection — either a value, *or* one effect, never both (`fx-templates-plan.md` D1). Which of the
two it holds is `kind`, and it is fixed at creation the way the family is; three rules keep the
effect half narrow, and each removes a way for it to complicate the cook: **a value or an effect**,
**one effect** (several together is what a Look with deferred effects already is), and **always
generic** (an effect fans over whatever the layer names, so `isGeneric` is true for every one).
A **Beam** template holds values only — the effect library has no beam category, and the backend
refuses `beam`, `controls` and `composite` **by name** so a script-registered beam effect cannot
mint one behind the rule. Backend contract in `../docs/lighting-composition-model.md` §"Looks and layers"
and `models/templates.kt`; the completed records are
`../docs/plans/completed/looks-and-layers-plan.md` and
`desk-simplification-plan.md` §Session 3.

They were one entity until session 3, split on the row's targeting mode: a **bound** Look behaved
like a palette, a fully-**deferred** one like a preset. That is now two tables, and the reason it
had to be is the design's own best example — a *per-fixture* template (eight heads aimed at one
spot hold eight different pan/tilts) has only bound rows, so `hasDeferredRows` could never have
told the two apart. What the split deleted: `editorFixtureType`, `LookEditor`'s synthetic-fixture
value grid, `LookDraftContext`, `LookLivePreview`, `syntheticFixture.ts`, `EditorContextValue`'s
`look` arm, and the type gate in `compatibleIdsFor`.

**A Look row is always bound** — `validateLookRows` refuses `deferred` — and a Look is always
*recorded*, from the programmer or by promoting a selection, which is why `/looks` has no New
button and one editor (`LookDetailSheet`, read-only about values on purpose; read its doc comment
before adding a value grid). A Look **effect** may still be deferred, and
`LookSummary.hasDeferredEffects` is what says so: that is what makes a Look eligible for a busking
pad, since a pad supplies the targets on the press.

**A template stores an intent, not a literal**, resolved per head at cook: a colour plus a
white/amber policy, a level or beam role as a percentage of each head's own range, a position in
**degrees**. `fx/TemplateIntent.kt` owns the grammar and `lib/templateIntent.ts` mirrors it — the
client half serialises and parses only, and **never resolves**, because
`fx/TemplateResolver.kt` must be the single answer to what the rig will do (§6 of the plan). All
three consumers go through it: cook, `POST /templates/{id}/apply`, and the editor's resolves-to
panel via `POST /templates/resolve`. Two deliberate degradations in that grammar are documented in
`TemplateIntent.kt`; do not "fix" either by teaching the literal parsers about intents.

**The one literal is a bundled emitter — `dmx:180`.** White, amber and UV are rows of the closed
vocabulary in their own right, family COLOUR, and the departure from "an intent, not a literal" is
deliberate: an emitter has no range to be a proportion of and no room to be a position in, so there
is nothing to re-derive per head. Keep the `dmx:` prefix — a bare `180` is what the *literal* parser
reads, so the two grammars would agree on some rows and silently disagree on others. Rows rather
than fields on the colour intent, because each then writes only its own channel: that is what lets a
UV-only template sit **over** an amber wash instead of replacing it, and it is the only way to say
"UV at 200" at all, since no `WhitePolicy` has ever driven UV. An explicit `white` or `amber` row
**forces the colour row to `rgbonly`** — the policy drives the same byte — which the one rows
builder enforces by rewriting the saved colour row's policy (not just the button's variant: showing
RGB only while the value still said `extract` produced an editor displaying a template it could not
save) and the write boundary refuses by name. UV is exempt. The builder is `templateRowsFromValues`
in `components/templates/familyControls/templateRows.ts`, through `effectiveColourPolicy`;
`ColourControl` reads the same derivation **for display only**, and both hosts of the family
controls — `TemplateEditor` and the template sheet's Value cell — save through the builder, so
neither can send the refused pair (§Library sheets).

**A colour template refuses as a whole**, and this is the one place compatibility is finer than D6.
A head missing any emitter the template names takes *none* of its colour rows —
`TemplateResolver.unmetColourRequirement`, folded in by cook, apply and the resolves-to panel alike —
because the rows are facets of one output and half a colour is a wrong answer, not a partial one.
Scoped to COLOUR: zoom and frost are independent roles and keep their per-row skip.
`TemplateSummary.requiredEmitters` is the derived form the client filters on, paired with
`targetEmitters` in `fixtures-list/rowModel.ts` — a **union** over the selection, like
`targetFamilies` beside it, so a mixed selection still offers the template and the head that cannot
take it reports a skip. `targetEmitters` must read the **colour descriptor's** `whiteChannel` /
`amberChannel` / `uvChannel`: bundled emitters are omitted from the flat descriptor list, so
scanning categories the way `targetFamilies` does finds no emitter on any head.

**A template's property vocabulary is closed** (`TemplateProperty`), and that is where "a template
cannot carry a gobo" actually lives: gobo, colour-wheel and macro slots are per-model, so they are
refused by name at the write boundary and shown *disabled with the reason* in the beam editor
rather than omitted. The three emitters are *in* the vocabulary — not slotted, and the only
per-model question about them is presence. Compatibility is otherwise **capability-only** (D6):
"does this head have colour at all", never "was this authored against that model".

There is **no stored attribute type on either**. `LookSummary.families` is derived server-side from
the rows, so a Look spanning colour and position reports both. A template's single family is derived
the same way and validated to be exactly one at the write boundary. That is why the **family filter
lives on `/templates`, not `/looks`**: a template is in exactly one family, so a family is an exact
partition; a Look spans families by nature, so filtering by one would hide most of the library from
most filters.

`src/lib/attributeFamily.ts` owns the family vocabulary and mirrors the backend's
`PropertyMaskGroup` — `store/programmerOps.ts` exports that name as an *alias* of
`AttributeFamily`, so the wire keeps its spelling without the two becoming separately-extensible
types; `maskPicker.test.ts` pins the two lists against each other, and
`templateIntent.test.ts` pins the template vocabulary the same way. It caught a real divergence
already: `Number('')` is 0 where Kotlin's `toDoubleOrNull()` is null, so the client read `pct:` as
0% while the server rejected the row.

**Within a cue, later layers win — for every attribute, intensity included**, and the cue's
own `propertyAssignments` are the last layer and beat all of them. Across cues, HTP still
governs intensity. That flip is the change an operator coming from presets is most likely to
be surprised by, so it is said out loud rather than left implied: the programmer rail draws its
stack **top wins** — the Local values row first, then the layers strongest to weakest — under a
`VALUES · top wins` label whose hover is the full sentence, and `LookStack`'s wide density keeps
the sentence as its `precedenceNote` paragraph (it was `LayersPane` that said so until session 2a
deleted that pane). The dense density *reverses only the rendering*: the order badge and every
index a handler receives are still the array's, which is `sortOrder` ascending.
A layer's `sortOrder` is authoritative, not its array position: two layers sharing one leaves the
tie to insertion order in the cook step. Nothing renumbers client-side today — the programmer
stack asks the server to move a layer and takes the order back — so a client-side reorder would
have to restate every `sortOrder`, not just the two it moved. `lib/cueUtils.ts` kept two unused
helpers (`reorderCueLayers`, `densifyCueLayerOrder`) saying exactly that; they were deleted, and
the rule lives here instead.

**Layer order does not govern the value/effect boundary**, and per-layer `stomp` is the escape
hatch. Effects are Layer 3 and values Layer 4, so a lower layer's colour *effect* beats a higher
layer's static colour whatever the order says; `stomp` on the higher layer switches off the effects
of every layer below it, on every property it asserts. It is **suppression, not removal** — the
instance keeps running, so clearing the stomp brings it back mid-phase — and it applies to the
programmer stack as well as to a cue. `LookStack`'s `onSetStomp` is the toggle; a read-only row
draws a badge instead, and the dense row draws both — the badge on the row, because stomp is the
one setting that changes what the rows *below* do, and the toggle in its popover. Backend contract in `../docs/lighting-composition-model.md`
§Stomp, which is also where the *other* stomp lives — the cue-level, cross-cue, removing one. Don't
conflate them.

`buildCueInput` rebuilds `layers` and `triggers` **field by field**, and its comment says why. A
field missing from that rebuild is dropped on every inline cue edit; `cueUtils.test.ts` pins every
field of both individually rather than by deep-equal — a deep-equal against a fixture built in the
test file would pass just as happily with the same field missing from both sides.

**The `ref:{uuid}` value grammar is gone** — retired in session 4, on both sides at once,
because a client cannot render rows a server still produces. A cue or the programmer depends on
a Look through a **layer**, which names it by FK, and a layer's `propertyMask` is what expresses
"this property comes from that Look". What went with it: `parsePaletteRefUuid` /
`isPaletteRefValue` / `serializePaletteRef`, `ProgrammerEntry`'s five `palette*` fields,
`CellPaletteRef` and `describePaletteRef`, `PaletteRefNotice` and the four cell editors' notices,
the `FixturesTable` reference rail and its `Link2` corner glyph (the `Layers` glyph beside it
stays), the two `missingPalette*` health arms on both `AssignmentHealth` and `BindingHealth`,
`refRowCount`, and the programmer-wide Make Hard with its dialog.

Two things survived it on purpose. **`validateLookRows` still rejects a `ref:`-shaped value** at
the Look write boundary, as an inlined shape check with its own local constant — that rejection
*is* `FU-LOOK-NESTED`'s non-recursion guarantee, and it must not be deleted along with the last
reader of the grammar. And **`StateMigrations`' `removePrefix("ref:")` is the upgrade path**, not
dead code: it folds a v4 database's ref rows into layers. `LooksMigrationTest` spells the old
form out locally for the same reason.

`LookRefBadge` became **`LookNameBadge`** and changed more than its name: chain iconography and
"References …" titles both misdescribe a layer, so it draws `Layers` and names the Look plainly. It
takes an `isTemplate` flag since session 3 and swaps the glyph for `Palette` — same size, same
shape, because the two sit in the same list at the same rank and a louder chip would make one look
more important. **Never mint a `P<n>` short code for either**; display the name.

"Palette" now means **nothing at all** in this codebase, and that is the point. The word's last
sense — the positional ordered colour list FX parameters indexed as `P1`/`P2`/`P*`, scoped
`look > cue > global` — is gone, along with `PalettePanel`, `CuePaletteEditor`,
`ActiveStackPalettes`, `CuePaletteBar`, the `palette` column on cues / stacks / Looks,
`Cue.updateGlobalPalette`, `FxState.palette` / `stackPalettes`, the whole `PaletteSocket`, the
`set_palette` AI tool and `isPaletteRef` / `parsePaletteIndex` / `resolveColourWithPalette`. Any
remaining occurrence is a lucide icon, a 3D material list, or a comment about the *named* palette
entity that became a Look in session 4. **Don't reintroduce it in either sense.**

**An FX colour parameter names a template instead** — `tmpl:{uuid}`, whose helpers are
`isTemplateRef` / `parseTemplateRefUuid` / `serializeTemplateRef` in
`components/fx/colourUtils.ts`, mirroring `fx/TemplateColourSource.kt`. Five things about it:

- **This half serialises and parses only, and never resolves.** Same rule as `templateIntent.ts`,
  for the same reason: `TemplateResolver` must be the single answer to what the rig will do. The
  backend's `resolveColourGeneric` resolves a colour intent *without a head*, because an effect's
  output is one colour applied to every head it targets — so it resolves as though the head were
  RGBW, which makes an FX-referenced template identical to the same template applied as a layer on
  any RGBW/RGBWA head. **A head with no white emitter pays for that**, and by more than a stop:
  the neutral is already out of RGB and its white byte is dropped, so `#FF9D4A` arrives as
  `#B55300` — dimmer *and* more saturated, which is worse than the RGB-only reading rather than
  equal to it. Accepted trade, documented at `resolveColourGeneric`, one line to invert.
- **A reference is legal only in an effect parameter.** A cue row, a Look row and a programmer entry
  are literals; the dependency mechanism for a *value* is a layer. `validateLookRows` refuses a
  `tmpl:`-shaped value beside its `ref:` refusal, and `parseAssignmentValue` returns null for one
  rather than letting `parseExtendedColour` answer white.
- **Only generic colour *value* templates are offerable.** `family === 'COLOUR' && isGeneric`
  holds on both sides — a per-fixture template holds no single colour, so there is nothing for a
  fixture-agnostic output to take. The third clause was `rows.length === 1` and is now
  `kind === 'value'`, which is exactly the swap that clause's own docblock said to make if it were
  ever relaxed: it excluded an effect template only by the accident of holding no rows. The count
  went because a colour template may hold a hex *and* explicit emitters, and both sides now fold the
  whole colour-family row set into one colour — `templateRowsSwatch` here, `resolveColourGeneric`
  there. **Never read `rows[0]` for a template's swatch**: row order is authoring order, so a
  template whose `uv` row sorted first was drawn purple under an amber name. All three exclusions
  are pinned in `FxColourTemplates.test.tsx`.
- **There is no successor to `P*`.** A template holds one colour, so there is no set to expand; a
  colour list is an explicit ordered mix of literals and references. `FxColourListPicker`'s
  "Use entire palette" checkbox and its `savedValue` machinery went with it.
- **`tmpl:` rather than `ref:`** because `ref:{uuid}` is retired *and* still actively rejected at
  the Look write boundary — reusing it would collide with a live check.

`FxColourTemplates.tsx` owns both halves of the UI: `useColourTemplates()` (the offerable list plus
the three lookups a picker needs to draw a reference) and `FxColourTemplateRow` (the chips, plus
**Save `<hex>` as template…**, which fills the library the way `TemplateStrip`'s
new-from-selection chip does). Both pickers read `projectId` from the **route**, not a prop — the
FX library page has no project, and there the row simply offers nothing and still edits literals.

**The programmer is a layer stack too**, and `LookStack` (`components/looks/LookStack.tsx`) is
the one component that draws both — that sharing is the point rather than a saving, because a
cue *is* a saved programmer stack. Its seam is `LayerHandlers`, which is **index-based on
purpose**: the rows render a list and the operator acts on a position in it, so translating
index → whatever addresses a layer in that world is the host's job. The cue's host PATCHes
whole arrays through `buildCueInput`; `ProgrammerLookStack`'s maps index → `layerId` and sends
`programmer.removeLayer` / `moveLayer` / `patchLayer` (`addLayer` is `ProgrammerAddLayerSheet`'s,
since the rail's footer and strip own adding). It must not renumber
`sortOrder` client-side the way the cue path does — the server renumbers the stack and re-ranks
the running effects **in place**, so a drag doesn't restart any effect's phase.

`programmer.layerState` is the **third broadcast** frame (after `provenanceState` and
`programmer.includeTarget`), because the programmer is shared and a second tab's reorder must
not leave this one showing a stale order. It arrives two ways, and both are needed: as a
**unicast reply** to whichever socket sent the layer op (the acting tab's fast path), and as a
**broadcast** to every socket from `ProgrammerStore.layersFlow`. The broadcast was missing until
session 4, on the reasoning that "every layer mutation also emits `provenanceState`, which
already schedules the value re-read" — which is false for a mutation that moves no value. A
layer whose `targets` don't match its bound Look's rows asserts nothing, so adding, reordering
or disabling it emitted no provenance and left every other tab on a stale layer list. Found on
a desk, not by a test. The flow is emitted from `mutateLayers` rather than from `recook`,
because `reset()` bypasses the recook path and a full programmer clear must reach other tabs
too. Its handler still calls `notifyState()` only and deliberately *not*
`scheduleStateRefetch()` — the frame carries the whole stack, so there is nothing to re-read.
Layers ride a **separate** cache entry
(`useProgrammerLayersQuery`) rather than joining `ProgrammerSummary`, which the always-visible
`ProgrammerIndicator` reads.

Creating a **bound** Look and **update-back after Include** both work now —
`RecordLookSheet` (`POST /programmer/record-look`) and `updateIncludedLook`. `includedTargetIsReadOnly`
and the `INCLUDE_TARGET_READ_ONLY` conflict arm are gone with them.

**All three Make Hard routes are gone, and nothing replaced them.** They existed to swap
value-level palette references for the literals they resolved to, and the `ref:` grammar retired,
so there is nothing left to harden. A successor gesture — "promote a layer's *cooked* values into
local rows and delete the layers" — shipped briefly as `POST /{projectId}/cues/{cueId}/flatten`
and was deleted again in the backend sweep, uncalled. **Do not cite flatten as live**, and do not
add a button for it.

The two constraints that made it hard are worth keeping, because any reimplementation meets them
again. Rows come out **fixture-targeted, never group-targeted** — cook's output is per fixture by
construction and carries no group name, so the old route's "keep a group row when every member
agrees" cannot be reproduced without guessing which of several overlapping groups to name. And a
**single `layerId` can only be the last enabled layer**, because local rows beat every layer:
promoting a middle layer's values would make them win over the layers above and change the cue's
output, which is the opposite of what flattening promises.

Two things about the record sheet that are not arbitrary. The **mask is prominent** rather than
incidental — a palette bank implied its attribute, a Look has no type, so an unmasked record of
a busked state quietly captures position and beam alongside the colour that was meant; the
per-family counts (`familyForCategory`, at last with a caller) exist to make that visible
*before* it happens. And the **selection defaults on**, the opposite of `RecordSheet`: a cue
usually does want everything you busked, a Look named "Warm Amber" almost never does.

**Provenance names the winning layer.** `ProvenanceEntry` gained `layerId` and `layerSource` —
the resolved referent, `kind` + `id` + `name` — which `useRowOwnership` aggregates into
`CellOwnership.layer`, so "why is this fixture this colour?" answers *Warm Wash* rather than *a
cue*. Those fields **must stay in `provenanceSignature`**: a key can move from the cue to one of
the cue's layers with `source` unchanged, and a cell that didn't wake would keep naming the old
answer. The signature reads the whole `layerSource` object rather than its id alone, because a
Look layer and a template layer can share an int PK — that is the reason the entry carries a
source object at all, and matching on `layerId` would let a swap between the two look like no
change.

Both branches fill them in, and the `PROGRAMMER` one only since session 4: session 3a wired the
`CUE` branch from `cueLayerLayerWinners` and left the programmer branch building a bare entry, so
a cell lit by a busking pad answered *the programmer* and the layer-aware hover never appeared
there. The programmer branch has no resolver to ask, so it recovers the winning layer's **rank**
from the reserved `seq` band that `putLayerSlots` stamps
(`ProgrammerStore.layerWinnerRankByKey`), and reports only keys whose *winning* slot is the
layer's — a local busk sits above the layer slot, and naming the Look there would credit a Look
for the operator's own hand.

**The pads still go through `POST /looks/{id}/toggle`**, which is `programmerLayerStack.toggle`
server-side — it adds or removes a layer, matching on the **whole `LayerSource`** + exact `targets`
(matching on an id alone would let a Look and a template that share an int PK cancel each other).
Keeping one owner for that match rule is why they weren't moved to the explicit ops. What did change
is the ring: `lookLayerPresence` reads the desk's **resolved applied state** (`programmer.applied`,
from `useProgrammerAppliedQuery`), not the effect list. The old match was
`FxInstance.presetId === lookId`, which worked by accident (the Look id in a field naming a
`DaoFxPreset`) and could never see a rows-only Look at all. It then read the layer stack directly,
expanding groups client-side; the desk resolves that now — `ProgrammerLayerStack.appliedState` sends
one entry per record naming every target it covers, each group marked `all` or `some` — so all
these functions do is fold the selection into one ring. Two copies of a coverage rule drift, and the
copy in the browser is the one no test against the rig can reach. `templateLayerPresence` is its
twin for templates, and it is the *only* way a template pad can light. That used to be because a template
held no effects; it holds one now, and the rule is *more* load-bearing rather than less — an
effect-template pad's ring would look matchable against the running instance, and matching there
would light for an effect template while leaving every value template's pad dark.

The **busk view**'s pad grid takes both, and takes **cues** besides — but it no longer decides where
any of them go. A pad sits where the operator put it, in a bank on a page they built (see §The busk
layout); the library's family says nothing about the arrangement. What survives from
the old automatic layout is the pad *face*: an effect pad is a pad like any other — same component,
same presence ladder, same long-press — and only its wave glyph says what it holds. The paragraphs
below describe the pad face only.

**`/templates` is a list ordered by name, grouped by family under *All*, and there is nothing to
drag.** Since the library sheets' session 2 it is a sheet (§Library sheets): under *All* the rows sit
under family dividers — Intensity · Colour · Position · Beam, `TEMPLATE_FAMILY_ORDER` — and within
each, as in a filtered view, they are the server's name order. The dividers are a grouping of that
order, not an order of their own. A template carried an
operator-set position and could sit in a *group* whose pads released each other; both went with the
automatic layout, because both were things the busk page does better — order is a pad's place in a
bank, exclusivity is a solo bank's. `TemplateLayoutList`, `TemplateGroupRow` and
`lib/templateLayout.ts` are deleted, `/templates/reorder` and the `/template-groups` CRUD with them,
and `TemplateSummary` carries no `sortOrder` and no `groupId`. **Do not reintroduce a stored library
order**: the only orders a template has are this page's name order and the programmer row's
recency, and neither is a field. The programmer's `TemplateStrip` drew this same name-ordered list
until it became a recents row — see §"The two apply gestures", where the row is the eight most
recently *pressed* and name order is only the fallback for a library nothing has been pressed from.

The **family filter** stays, and is the page's only partition: a template is in exactly one family,
so `?family=colour` is a view of a small library rather than a division of it, and it deep-links
from Cmd+K. It is the library row's `PartitionChips` now, with counts (`LookFamilyFilterBar` is
gone), and `TemplatePicker` mounts the same chips with state of its own. The footer says how many of
the whole library the sheet is showing.

A colour pad carries a **swatch**, but only when the
template is generic and single-row — `templateSwatch` in `components/busking/padFace.ts` makes
the same two exclusions `isOfferable` makes in `FxColourTemplates.tsx`, and for the same reason:
`rows[0]` under a name covering several rows states one of them as the whole thing. An effect
template has no rows, so it draws the wave in the swatch's place and an `EffectPadDetail` line
(`Colour Pulse · ½ · M2`). That detail is a **component** and not a string built by the caller:
the master's label is a live value, hooks cannot be conditional, and a hook in the loop would make
every value pad in the library subscribe to the master bank. (The template sheet's Master column
answers the same question the other way — it names masters from the project's *stored* bank,
fetched once by the route, so no row subscribes to anything.)

### The busk layout

**The busk page is a thing the operator builds, not a view the library lays out.** A page is rows; a
row is columns, each with a **width share in twelfths**; a column stacks banks top to bottom; a bank
has a name, a `solo` flag and a `flow` (`WRAP` | `COLUMN` | `SCROLL` — the last one line of fixed-width
pads scrolling sideways, the rig row's own flow, offered to banks since 2026-09-21) and holds ordered **pads**, each a
reference to exactly one template, Look or cue. One record may sit on several pads, on several
pages. Backend contract in `lighting7/models/buskLayout.kt` and
`../docs/lighting-composition-model.md` §"The busk layout"; the plan is
`../docs/plans/completed/busk-layout-plan.md` and the design authority is `look-groups-design/`.

It replaced an automatic layout — four family columns of templates, a Looks pool, a cue column of
stack cards and pinned-cue pads. The reason is the one a template group could not meet: a group
holds **one family**, and the operator's actual ask (a position palette and a movement pattern that
must not run together) needs a cluster holding both, plus cues. So order and exclusivity moved to
the page, and the library went back to being a flat list.

**A press goes through the pad, and the bank decides the siblings.**
`POST /busk/pads/{padId}/press` is the *only* press on this surface — the three kind-specific
mutations (`toggleTemplate`, `toggleLook`, `goToStack`) are the programmer's ⌥click / touch-hold
strip's and the AI's now. It has a second door since the MIDI surface's session 4: a `PressPad` binding runs the
same `BuskPressService` with the desk selection as the targets, which is why that service exists at
all — the solo rules and the refusals are the pad's behaviour, not the endpoint's. The server reads the pad, its record and, when the bank is **solo**, the records on its
sibling pads in one transaction. Solo has one meaning for every kind: pressing one *on* turns its
siblings off — a layer sibling narrowed on the pressed heads, a live cue sibling stopped, and a cue
press taking its layer siblings off wholesale, because a cue has no targets to narrow by. An **off**
press releases nothing, and a stacking bank has no siblings at all. The request carries the
selection's **`families`** beside its targets — the desk's pair while this tab follows the desk,
the tab's own when unlinked — and the response answers `skippedFamilies`; see §The desk selection
has a mask for what lands under it and why a press is never pre-refused here. The band's one row
carries the family pill and the desk chip for the same pair.

**`src/lib/buskLayout.ts` is the document model**: no React, no store, every gesture a plain call a
unit test can make. Three rules run through it:

- **Addresses, not ids.** A pad, bank or column created by the *previous* gesture has no id yet —
  the layout PUT mints it — so drag ids and every position are index tuples (`bpad:{r}.{c}.{b}.{p}`,
  `bbody:`, `bbank:`, `bunder:`, `bgut:`, `bnewrow`, `palette:`). Ids are read in **one** place,
  `toLayoutRequest`, at commit. React keys are `uuid ?? localKey` for the same reason, and a **row's
  key is its first column's uuid** — a row has no identity on either side of the wire, and an index
  key over a list that gets rows spliced out of the middle is the classic remount bug.
- **Normalise inside every mutator.** The server refuses an empty row or column
  (`BUSK_LAYOUT_INVALID`), so `normalisePage` is not cosmetic — forget it and the gesture 400s. It
  drops an empty column and the row left empty with it, and **keeps an empty bank**. That asymmetry
  is deliberate: pruning a bank would delete the one the operator just made, the instant they
  crossed the last pad off it.
- **Null means nothing would change**, which is what lets a repeat hover write no state.

`applyDrop` runs **once, on drop**, in the order **lift → insert → prune**; object identity carries
the destination across the lift, so a bank leaving the column it is being dropped beside cannot
renumber its own target.

**A pad target is an insertion point, not a destination index** — it names the gap the dashed slot is
drawn in, counted over the document as it stands with the source still in it and ghosted. Both halves
must agree on that, or a pad lands somewhere other than where the operator was shown it would:
`resolveDropTarget` produces the gap, `BuskBank` draws the slot at it, and `applyDrop` has to put the
pad in *that* gap — which means subtracting one when the lifted pad sat before it, since removing it
shifts every later gap down. Reading the index as an `arrayMove` destination instead made every
**downward** drag within a bank overshoot by exactly one place, invisibly, because each half was
self-consistent and only the composition was wrong. `buskDnd.test.ts` composes the two and asserts
the slot and the landing place agree; testing either half alone passes with the bug in.

**There is one *app-wide* `DndContext`**, in `components/dnd/DeskDndProvider.tsx` (it was
`CueSlotDndProvider`; it is not about cue slots). `Layout.tsx` mounts it around **both** the FX
cue-slot overlay and the routed page, and the busk page joins it with `useDndMonitor` rather than
nesting one.

Three surfaces do nest one of their own, and the line between them and the busk page is whether the
drag has to *cross* into the shell's droppables: the show's cue cards (`StackDetail`), the stack
list (`ShowOverview`) and the cue sheet's row drag (`SheetTable`'s `rowDrag`, mounted only for a
sheet that asked for it) each reorder a list **within themselves**, and none of them has a gesture
that ends on a cue slot. A nested context wins for its subtree, which is exactly why the busk page
must not have one: it would hide that page from the overlay's droppables, which is exactly the drop
the plan's session 3 needs. The overlay and the busk page coexist by
**mutual ignorance of ids and of foreign *targets***: `parseBuskDragId` answers null for a `slot-…`
id, and the slot handler returns for any `over` that is not a slot. It is **not** ignorance of
*sources* — a drop onto a slot is resolved in the provider whatever lifted it, today a sibling slot
or a `busk-palette` row from the library palette, because the provider owns the slot droppables
(mounted on every route the app is drawn on — an immersive window takes the cue-slot overlay with
the other three panels, §Windows, full screen and the hand, so there a palette row has no slot to land on), `projectId` and
both slot mutations, while the panel body unmounts with the overlay. The source→assignment mapping is pure in `components/dnd/slotDrop.ts`, imported
type-only, so the shell still reaches no busk runtime code. Three things about that provider are
load-bearing:

- **Collision detection is `pointerWithin` falling back to `closestCenter`.** dnd-kit's default
  `rectIntersection` compares the *dragged* rect by area, so a 20px column gutter could never beat
  the 300px column beside it, and it answers nothing at all in the gaps between banks.
- **`MeasuringStrategy.Always`**, because the page changes its own geometry mid-drag.
- **One `DragOverlay`, content dispatched** through `dnd/dragOverlayRegistry.ts`. A second overlay is
  not an option — it registers itself on the context and two would fight over one ref. A surface
  registers a renderer at **module scope**, so the app shell draws a busk pad without its import
  graph ever reaching one. The ghost face is a **frozen snapshot**: an effect template's detail line
  reads a live speed-master label through a hook, and the overlay must be hookless.

**The FX cue-slot overlay is filled from the busk view's palette, and edited only while the busk
view is.** A slot holds a cue or a **Look with no deferred effect** — `itemType` is `'cue' | 'look'`,
and the cue-*stack* arm is gone. It has no selection, so it can hold only what needs none: a Look
tile presses `POST /looks/{id}/toggle` with an **empty target list**, and the desk derives the
targets from the Look's own patched fixtures. Three rules follow, and each has a reason that is easy
to undo by accident:

- **A Look tile lights from the programmer's applied feed, never from cue liveness.**
  `lookIsApplied` (`busking/lookPresence.ts`) is a selection-*independent* helper for exactly this:
  the target-scoped folds beside it answer `'none'` for an empty target list by design, so reading a
  slot through one would leave every Look tile dark. It matches on the source being a **LOOK**,
  because a template layer can carry the same int PK.
- **Crosses, drags and drop targets follow `useBuskEditMode()`**, the `buskEdit` Redux slice — the
  overlay is a *sibling* of the routed page in `Layout.tsx`, so a context inside the busk view could
  never reach it. The panel's own long-press wiggle mode and its inline assign panel are deleted.
  Outside edit mode the overlay is press-only, with its *View* / *Clear slot* context menu intact.
- **`slotEligible` is decided at the palette and re-checked in `slotDrop.ts`.** An ineligible row
  dims and says *needs a selection* while a slot is the target (`useCueSlotHover`, a `useDndMonitor`
  rather than provider state, so only the palette re-renders at hover rate), and the drop is a no-op
  if it lands anyway.

Known gap: the slot droppables live inside `CollapsiblePanel`, so with the overlay shut a palette
row dropped at the header lands on nothing, silently — `FU-SLOT-DROP-OVERLAY-HIDDEN` in lighting7's
follow-ups.

**The preview is a ghosted source plus one dashed slot, and there is no `SortableContext` anywhere
on this page.** The primary gesture is a *palette* drop, and a palette row is not a member of any
sortable list, so the dashed placeholder has to be hand-drawn regardless; the three bank zones are
not list indices; and the committed artefact is the whole document anyway. Two rules stop the
classic placeholder oscillation: the dashed slot is **`pointer-events: none` and is not a
droppable**, so it can never be the thing you are over, and the reducer answers null for a repeat
hover. The cost is honest — pads snap around the slot rather than sliding. If that ever reads badly,
`view-transition-name` is the additive fix; do not reach back for `SortableContext`.

**Legal targets follow the source, and the hover is fed from `onDragMove`.** Six things went wrong
at once the first time a bank was dragged on a real desk, and each is now stated in code rather than
assumed:

- **A bank lands on the three bank zones and nothing else; a pad or palette row lands on a pad or a
  bank body and nothing else.** `canLand` in `buskDnd.ts` filters the collisions *before* the
  deepest-wins pick, and the pad and body droppables are `disabled` while a bank is lifted (the
  strips, gutters and new-row zone were already disabled otherwise). Both halves are needed: the
  filter is the half a test can reach, the `disabled` flag is what keeps dnd-kit's `over` — and so
  `isOver` — off a place the drop would refuse. Before this, a bank over a pad resolved to a pad
  target, opened a dashed slot inside the bank, and dropped nowhere: `dropBank` refuses a pad target,
  so the monitor returned before `commit` and no request was sent.
- **The `closestCenter` fallback returns one collision.** `closestCenter` answers with every
  droppable nearest-first; a deepest-wins reader then took a pad three banks away while `over` lit the
  nearest strip. `DeskDndProvider` slices it to one, so the highlight and the slot always name the
  same place.
- **`onDragOver` fires only when the over-id changes**, so the leading/trailing half-of-a-pad decision
  was made on entry and never again. `BuskEditProvider` feeds `resolveDropTarget` from `onDragMove`
  as well; `sameTarget` keeps the state write to the moves that change the answer.
- **`MeasuringStrategy.Always` is not a timer.** dnd-kit re-measures when the set of droppables
  changes or a droppable's own ResizeObserver fires — not when the slot opens and shifts every later
  pad without resizing it. The provider calls `useDndContext().measureDroppableContainers()` in an
  effect keyed on the target, after the commit that moved the slot.
- **With fresh rects the open slot needs to be sticky.** Opening it shifts the hovered pad along and
  leaves the pointer on the slot — which is no droppable — inside the bank body; collapsing that to
  the body's append would throw the slot to the end of the bank the instant it opened.
  `resolveDropTarget` takes `current` and keeps it while the pointer is in the body of the bank the
  slot is open in. Entering another bank's body still appends, which is the only way into an empty one.
- **The drop reads a ref.** `targetRef` is written in the same handler as the state, so a drop that
  lands before the re-render following the last hover cannot read the target before that one.

**A seventh, found on the desk later and from the other direction: the operator's pointer moves the
slot, and the slot does not move the slot.** Opening a slot physically displaces the pads after it,
so with a *stationary* pointer the pad underneath it changes — which re-answers "what are you over",
which moves the slot, which displaces the pads back. Dragging a pad near the boundary between two
banks in one column, the slot flipped between them every two or three frames while the pointer moved
**one pixel**; and because the re-measure below runs on every target change, React counted the
nested updates and threw *Maximum update depth exceeded* rather than merely flickering.

`TARGET_HYSTERESIS_PX` (6px, against the pointer sensor's own 8px activation) is the fix:
`resolveDropTarget` returns `current` unchanged until the pointer has travelled that far from
wherever it was when the slot was placed. It is checked **before** the `overId == null` arm, because
the displacement can just as easily carry the pointer off every droppable and close the slot — the
same loop with an extra frame in it. The distance comes from dnd-kit's own `delta`, never
reconstructed from `activatorEvent` plus coordinates, which is the reconstruction `edgeDrag.ts`
refuses for going wrong under browser zoom.

**The `sameBank` stickiness could not catch it, and widening it would be the wrong fix.** That rule
holds `current` only while the pointer is in the body of the *same* bank, and this oscillation is
precisely *between* banks — which is the case it is written to allow ("entering another bank's body
still appends"). Bank scope is the wrong axis; pointer movement is the right one, because it is the
one input the slot cannot change. `BuskEditProvider`'s `measureDroppableContainers([])` now also
runs on an **animation frame** rather than inline, so anything that ever oscillates again is a
flicker and not an error boundary — one measure per frame is all a 60Hz drag can use.

**`newBank` requires a name.** The server refuses a blank one (`BUSK_LAYOUT_INVALID`), and a
default of `''` was how `+ Bank` and `+ Row` shipped never having succeeded once — the optimistic
patch drew the bank, the PUT 400'd, the queue rolled back and toasted "A bank at row 1, column 5 has
a blank name". `nextBankName` mints `Bank N` for the smallest free N, the vocabulary
`lib/buskAdd.ts` already used to *label* a nameless bank; `BankNameField` reverts a blank rename
rather than sending it. `buskLayout.test.ts` "mints documents the server would accept" now asserts
the name, which is the assertion that was missing.

**Every gesture saves the whole page, and the queue holds operations rather than documents.**
`useBuskLayoutCommit` in `store/busk.ts` enqueues `(page) => page` so each gesture can be *replayed*
against the freshest confirmed document at send time. A queue of pre-computed documents could not:
gesture 2's document was built before gesture 1's response existed, so it would name none of the ids
that response minted and the server would recreate every pad it touched. The response is written
into the cache **only when the queue drains** — an intermediate one describes the world a gesture
ago, and writing it is exactly the snap-back the queue exists to avoid. On failure the queue is
dropped, the last confirmed page restored, **and the page re-read**: the write may have been refused
because a record behind one of its pads was deleted, and that is the one frame the echo suppression
below can swallow.

Patching from the **response** rather than optimistically-then-invalidating is a deliberate
departure from `reorderTemplates`: that route answers `void`, so a refetch is its only way to learn
what happened; this one answers the page as written, ids included.

**`busk.layoutChanged {pageIds}` is keyed, and the bridge suppresses our own echo.** The frame fires
for page CRUD, reorder, every layout write **including this client's**, and a record delete that took
pads off a page; a page delete carries the deleted id *plus every survivor*, since their `sortOrder`
moved. Invalidation is per page (`{type:'BuskPage', id: 'page-N'}` — prefixed, because the project
id shares that tag type), so **a page id from another project matches nothing and is a silent
no-op**: the frame needs no project lookup. A single-page frame is dropped while that page is saving
or within a short grace after its response, because our own write would otherwise refetch once per
gesture and could land between an optimistic patch and its own response. One page is the tell — a
layout write announces exactly one, page CRUD and reorder announce several.

**The desk holds a showing page, and a window chooses whether to be on it.** `busk.pageState` /
`busk.setPage` (`api/buskPageApi.ts`, `store/busk.ts`'s `buskShowingPage` entry) stays server-owned,
for the reason it always was: a hardware *next page* button and a tab click are two ways of making
one gesture, so the surface needs one thing to move — `BuskPageNext` / `BuskPagePrev` /
`BuskPageSet` write `BuskPageState` and every *following* window moves with them. What that argument
never established is that **every** window must be pinned to it, and on two screens it is wrong: the
flow this exists for is a colour page on one screen and a position page on the other, pressed onto
one selection. Reported on the desk 2026-09-16.

**The desk's page is a paging group, and a window is either in it or on its own page** (desk-follow
plan D5–D7, D9). Nothing but following windows and the `BuskPageSet` LEDs reads `BuskPageState` — no
binding presses a pad by page position (`PressPad` names a uuid) — so following it means *paging
with* the MIDI Next · Prev · Set and every other window in the group, which is what every surveyed
console does (Eos paging groups, Titan's *Follow World Page Change*, Onyx Wing IDs, MagicQ bank ties).
The words are the job, not the mechanism: ***Paged with the desk*** · ***Own page*** on the Screens
row (*With desk* · *Own* on a narrow one), ***Page: Own*** on the chip (*Own* on its short rung). A
per-window page everywhere was drafted and dropped (D5): the desk page, `busk.pageState` /
`busk.setPage` and the three page targets are unchanged.

So the page gets a **follow/local split of its own**, `lib/buskPageFollow.ts`, on `lib/deskFollow.ts`'s
model — per-tab `sessionStorage` (two desk screens are two windows of one profile), default follow,
unlinking snapshots what the window is showing and leaves the desk's alone, re-linking adopts the
desk's and publishes nothing. The order is **this window's own page (unlinked) or the desk's
(following) > `?page=` > the first page**, all resolved against the fetched list so a stale bookmark
lands somewhere real; a tab click writes the **desk** while following and this window's copy once
unlinked, and the URL mirrors whichever won with `replace` — flipping pages is not a history entry.
A `null` from the desk is *not* "the first page": it means nothing has moved it, and each client
falls back on its own.

**Two flags, two marks, and neither may drive the other.** A single flag cannot express the flow:
following would pin both screens to one page, and unlinking to get two pages would take the shared
selection with it, so the operator would select twice. `DeskChip` governs the selection and
`BuskPageChip` the page, and **each is always drawn, one way or the other** (desk-follow plan D7,
D8, revisiting busk-chrome D18): while linked, the **link badge** (`components/desk/LinkBadge.tsx`,
one component for both so they cannot drift); while not, the dashed `FollowPill`. **The mark is
the toggle, both ways** (desk-follow D11, 2026-09-23, revising D7 and D8's "a mark, never a
control" after Chris tried it: the chip on the row is the first thing an operator reaches for, and
a Screens sheet was the wrong primary door): the badge's press unlinks — the selection's takes the
desk's selection as this window's own (`unlinkFromDeskNow`, ⌘K's gesture), the page's keeps the
page on show as its own (`unlinkBuskPage(activePageId)`, handed down by the strip) — and the dashed
pill's press relinks. The badge is `aria-pressed` like the pill (pressed = linked) and the same
20px box as the mark, so no ladder moved. **Where the window cannot leave it stays a mark**: the
selection in Rig and Pads focus always follows (D2), so there `DeskChip` takes `forcedBy` and draws
`role="img"` with the reason on its hover, rather than a press the D3 relink would undo at once. The
Screens row and ⌘K stay, for setting *another* window. The two badges differ in one thing: the page's
**names the other open busk windows paged with this one** (*⛓ Screen 2*, `+N` for several, every
one on the hover, which also says a tab click here pages them too) — `useCoPagedWindowNames` in
`store/windows.ts`, from `windows.state`: rows on `busk` whose `viewOptions.pageFollows` is not
`'false'`, other than this window's own row (`thisWindowRow`); a row that has not announced the key
counts as paged with, and a row in Rig focus is included, since it still pages with the group. The
names fold to the glyph on each row's ladder and the glyph never does; the name is capped at 96px so
the ladders have a ceiling to be measured against. The page mark sits **beside the tabs** — on the
pad row in Split and Pads, on the short board's merged row, and on Rig focus's folded page strip,
since Rig still pages with the group (§The rig has the pad row's numbers). The ways onto a page of
its own are the Screens row, ⌘K's page pair, a `?page=` arrival and a tab click that never reached
the desk; the ways back are the chip, the Screens row and ⌘K. On this view both chips name their
subject (*Targets:* / *Page:*) so neither reads as governing the whole view. On the programmer's
row C the selection chip is alone and stays bare; there is **no page mark there**. A window that has
unlinked its page has not unlinked its selection and still presses onto the desk's targets and mask.

**Another window can set the page both ways** (D6): the Screens row's Page segment writes
`windows.viewOptions {pageFollows}`, which `applyBuskViewOptions` applies — `'true'` is
`relinkBuskPage`, the chip's own press; `'false'` keeps the page the window is *showing* as its own.
That is the one fact the handler (in `Layout`) cannot know, so `BuskingView` reports its active page
through `reportShowingBuskPage` and the handler reads `showingBuskPage()` — `unlinkBuskPage(null)`
would drop a window on the desk's page to its `?page=` or the first page at the moment it unlinked.
A `{page}` still unlinks onto that page, as a `?page=` arrival does. **A frame carrying both keys
resolves on `pageFollows`**: `'false'` with a page is the unlink onto that page, once; `'true'` with a
page relinks and ignores the page, because a paged-with window's page is the desk's and a frame aimed
at one window must not move it.

**The flag is tri-state, and `?page=` is what the third state is for.** Arriving with a `?page=`
**that resolves against the fetched list** is an explicit statement about *this* window — it composes
with the Screens sheet's launcher, so `?window=Screen%202&page=3` starts a second screen where you
want it — so it **unlinks** the window onto that page. An arrival naming no page, or one that is
gone, records that the window *follows* instead; both arms write the flag, which is what makes a
reload never an arrival. It has to be once per tab: the view mirrors the showing page back into
`?page=` on every change, so a following window reloading would otherwise read its own mirror as a
deliberate statement and unlink on every refresh. `null` means *this tab has not decided yet*, and
it is the only thing that tells a fresh window from a reloaded one — so there is deliberately no
second, in-memory "already ran" flag beside it. The parameter is latched at mount as the **raw
string**, null when absent, because `Number(null)` is 0 and a page whose id were 0 would make every
plain `/busk` load read as an arrival.

**Two consequences of that, both surprising enough to be worth saying out loud.** The busk view's own
address always carries `?page=`, so **a copied URL opened in a fresh window arrives local, not
following** — one click on the chip joins it to the desk, but nothing does it for you. And **there is
no separate offline override any more**: it was a second thing meaning "this tab's page", so a click
that never left the browser (`setShowingBuskPage` returns `sendGesture`'s `false` when the socket is
down) now unlinks the window onto the page clicked. That loses the old override's self-healing — it
was cleared by any change to the desk's value, so a Wi-Fi blip mended itself — and the trade is
deliberate: one mechanism rather than two, and a state that *says* what it is on the chip instead of
a silent override. A window stuck local after a blip is one click from following again.

**The mirror waits for the arrival decision to be rendered.** Both effects run in one commit, in that
order, and `unlinkBuskPage` only *schedules* the re-render that moves the active page — so an
ungated mirror writes the page the window is unlinking *from* into the URL and corrects it a tick
later. `useBuskPageDecided` is the **rendered** tri-state, and lagging by one render is exactly what
makes it the right gate: a live `isBuskPageDecided()` has already been flipped by the arrival effect
beside it, and `useBuskPageFollow()` collapses `null` and `true` to one `true` and so never changes
in the keep-following arm.

It shares only a namespace with `busk.layoutChanged`, which names pages whose *document* changed and
is what the echo suppression is written against; a local page still needs that invalidation like any
other. Edit mode
lives in `store/buskEditSlice.ts` rather than a React context, because the cue-slot overlay is a
*sibling* of the routed page in `Layout.tsx` and could never read a context provided inside the busk
view. It is never persisted, and `BuskingView` **must** exit it on unmount, or that overlay keeps
drawing crosses on whatever page the operator went to next.

### Focus and the side sheet

**Each window gives the busk view its own shape, and the shape is three per-tab facts**
(busk-further plan D5–D7, `lib/buskWindow.ts`): `busk.focus` (`split` · `pads` · `rig`),
`busk.rigHeight` (how tall the split draws the rig region, in px — `null` until the window chooses,
drawn then as the surface's default whole lines) and `busk.sheet` (`none` · `speed` · `colour` ·
`spread`, where `none` is the fold). They sit on `buskPageFollow.ts`'s model and **beside** it —
per-tab `sessionStorage` through `createSyncStore`, never `localStorage`, never the desk's — and
nothing here reads the selection's follow flag or the page's. The reason is the reason focus
exists: two screens at one desk showing two shapes of one view, pressed onto one selection.

**Split** is the band drawing the rig as a scroller at `busk.rigHeight` over the page with the handle between; **Pads**
folds the rig to `RigStrip` (the summary, the family pill, the desk chip or its link badge — what a
press needs to be honest about — labelled *Pads*, since in Pads it is the body's top row, and with
no chevron: the Focus control beside it is the way back) **off the desk board**, and on it draws **no rig
row at all** (busk-chrome plan D17, session A.5): the **pad row** is the body's top row and the page
fills the rest; **Rig** fills the
body with every row and folds the page to `BuskPageStrip`'s **40px folded arm** at the bottom —
the page's name and its bank count, **not the tab strip drawn folded**
(`Phones.dc.html` note 8, busk-further plan §11): Rig focus exists to give the band the height,
the full tab strip wraps on a phone and takes it back, and a page is chosen in Split. **The Cells
menu is not on the folded rig strip** — the compact boards' fold — by decision (session 8): `Focus`
and `Phones` draw the strip without it and `Cells` draws it with, and the strip carries what a press
must be *honest* about, where the menu is a *write*. On the desk board there is no fold: Pads draws
the pad row instead (§The rig), which carries the three selection verbs and not the Cells menu, for
the same reason. **Below `md` Rig focus stacks every row two tiles across, scrolling
vertically with the band** (`Phones` note 6, `RigBand`'s `stackRows`, which `BuskingView` sets
for the **narrow board only** — the short board is compact too but wider than `md`, and keeps its
sideways rows): it is D15's replacement for the narrow-width target sheet, and a sideways scroll
per row on a phone defeats the point of a list; Split there keeps the one sideways row with the
row chip. **The band's chrome is one row, the same row in Split and Rig, and the Focus control and
*Edit layout* / *Done* end whichever row is the body's top row** (busk-chrome plan D13, D17,
2026-09-21): on the desk board the band draws the `RIG` label, the Cells menu and its steps, the
verbs, then the family pill, the **`BLIND` pill** and the desk chip **left-anchored after the
verbs**, the gap, and the host's `controls` — `BuskFocusControl` and `EditLayoutToggle`, filled by
`BuskingView` — right-anchored, in Split and Rig. The blind pill (`busking/BlindMarks.tsx`) is a
**reporter, never a toggle** — Blind is switched on the programmer's action bar and nowhere else —
drawn in the indicator's amber only while `ProgrammerSummary.blind` holds, on the family pill's
terms (absent means nothing to say), read through `hooks/useProgrammerBlind.ts`'s narrowed
subscription so the band does not re-render on the programmer's entry churn; in Pads the pad row
carries it in the same place, and off the desk board `RigStripContent` — the compact strip and the
short board's merged row — does, since Pads is the resting focus there and the sheet is an overlay
with no fold. The pill is the eye-off glyph plus the word, and **the word folds on a rung of the
host's** — the rig row's own `BLIND_WORD_CLASS` (1020, above every other rung, since that row's
ladder has no slack for it beside a mask pill), the pad row's and the compact rows' Focus-words
rung (their summaries are `min-w-0 flex-1 truncate` and absorb it) — with `min-w-0 shrink` as the
last resort, because the ladders were measured without it and the rig row is `flex-nowrap` above
its floor; `RigBand.tsx`'s ladder note names the four narrow bands where, blind *and* masked, the
pill squashes to a sliver rather than moving a control. It exists because D10 put the programmer report on the Show tab's
strip, which is invisible with the sheet folded or another tab open, and an immersive window has no
app header to carry `ProgrammerIndicator` — while on a busk page every press is a programmer write. **In Pads the band is not drawn**: its controls act on tiles —
the Cells menu, the steps and Clear narrow, move or release a selection made on them — and there
are none on screen, so the **pad row** (`BuskPageStrip` with `pads`, §The rig) is the top row and
takes the host's controls instead; the band's `focus="pads"` arm and its chevron pill lasted one
day. The **selection summary is drawn only in Pads**, on the pad row; in Split and Rig the lit
tiles say it. Under the band the pad row is tabs and the page mark and nothing else.
The row was two — a label row over a controls row — from the morning of 2026-09-21 to the evening,
and the merge cost 32px in every shape; before that the Focus control was on the page strip and
moved with the fold, and then briefly on the band's one label row, which a desk width with the
sidebar open filled to wrapping. **The handle is `RigHandle`,
a grip under the rows in Split, and it sets one thing: how tall the rig region is** (D6, rebuilt
2026-09-22). The region is a scroller with every line mounted, so the bar can rest between two
lines and the rows scroll under it; past the last line the region simply has room, which is how the
page is made smaller. The drag (a focusable `role="separator"`) is **live and magnetic**: within
`RIG_SNAP_PX` (10) of a line's bottom edge the height jumps to it and elsewhere it follows the
pointer, the last line's edge a snap point like any other; the arrow keys step between the same
edges, a double press restores the default (read off the two presses, not `dblclick`, since the press cancels `pointerdown` and Safari is the desk browser), and the fact is written on release only if the drag
moved. `snapRigHeight` / `stepRigHeight` are the pure rules, pinned by table in `RigBand.test.tsx`.
It is clamped **on read, never on write**: the band measures a ceiling off the column
(`data-busk-column`'s height less the fixed chrome in it — the band's row above the grid, the
handle and padding below it, the page strip — less the page's `PAGE_MIN_HEIGHT_PX` of 120) and a
floor of `RIG_MIN_HEIGHT_PX` (56), so a stored height taller than a window that has since shrunk
is drawn at the ceiling and comes back when the window does. **Never infer that ceiling from the
page body's remainder**: on the first frame the grid is at its natural height, so a rig taller than
the column has already collapsed the body to nothing and "grid + body − minimum" answers several
times the truth — a stored height past it was accepted and the page drawn off the bottom of the
screen. The chrome boxes do not move when the band overflows, and the column's box is the
viewport's. The handle is **mounted before the measurement** for the same reason: its 16px box is
part of that chrome, and a ceiling measured without it left the page 104 at the bottom of a drag. **The handle never changes focus.** The one it replaced counted whole lines
(`busk.rigRows`) and snapped past its ends into Rig and Pads, and the desk's report was four
inconsistencies from one design: the ends snapped as the pointer crossed them while the lines
snapped on release, the count could shrink only the rig and never the page, the bar could not rest
between lines, and only Rig had a way back drawn under the rows. Focus is the segmented control's
alone now. **The rig strip is the band's compact row without the rows under it** (`RigStrip`): the
same `pt-2.5 pb-2` box and `min-h-7` row, so the row and the Focus control at its end sit where the
band's do whichever is drawn — it was a 36px chrome row of its own, 4px off the band's row centre,
carrying a chevron before the Focus control that pushed the control along on that strip only. The
chevron is gone (the Focus control is the one way between the shapes on every board, D17's desk-board
rule made general) and the label reads **Pads**, since in Pads that row is the body's top row as the
desk board's pad row is. Only Rig's folded page strip keeps a chevron back to Split (`BuskPageStrip`,
40px at the bottom of the body, nothing beside it to line up against); the chevron pill under the
rows went with the ends. The band writes the resolved height
onto the grid from a **layout effect keyed on the value, never a `style` prop**: the handle writes
the same property per move, a prop would undo that on any re-render mid-drag, and a handle unmounted
mid-drag (a `focus: 'rig'` from another window) must leave no fixed height on a grid that has to
flex — its teardown touches the DOM not at all, because a passive cleanup runs after the commit
that cleared it. The grip takes pointer capture so a drag that runs off the bottom of the window
still delivers its release, `pointercancel` ends it as a release does, and the ceiling is read
through a ref so a resize mid-drag does not cancel the drag. The default is **measured, not
stored**: with the fact `null` the band measures the first `defaultBuskRigRows` lines' edges in a
layout effect (so the unmeasured first frame never paints) and again when the rows change or the
column resizes; every other reader of that default is gone. It is drawn for a **one-line rig too**
and **hidden off the desk board** — below `md` and on the short board, where the band is one row
with a row chip and the segmented control is the route. **Edit mode forces Split** for its duration, because a palette drag
needs both regions, and restores the window's focus on Done by never having written it; the
control is disabled while editing rather than hidden, still lighting the stored focus, which is
what Done returns to. A project with **no pages** forces Split too: its first-open screen lives in
the page column, and a folded page strip has nothing to create a page from.

**A default is only a default.** Each store rests at `null` — *this window has not chosen* — and
the reader resolves it against the surface through the module's own copies of the short (500) and
cramped (750) height queries (`shortViewport.test.ts` pins both spellings) plus Tailwind's `lg`
for width: focus defaults to `pads` on a short viewport; the sheet to `speed` where docking the
288px rail leaves the page its 600px (`lg` and up), `none` where it would stack the page (iPad
portrait) or the viewport is short; the rig's default lines to three on a desk screen and two where cramped or
below `lg` — so a 1024×768 iPad in landscape gets two, and an **820-tall** one (1180×820) gets
**three**, since 820 clears the 750 fold; the `Tablets` board's "2 rows" never anticipated the
taller iPad, and `buskWindow.test.ts` pins both cases. `lg` rather than a measured body because the default has to be answerable off the busk
view: the announce carries these values from `Layout`, where there is no body to measure. Once the
window has chosen, the tab fact wins and survives a reload.

**`?focus=` and `?sheet=` are this window's on arrival, once, like `?page=`.** `BuskingView`
latches both raw strings at mount, `applyBuskArrival` applies the valid ones and marks the tab
**decided** whatever they held (both arms write it — a window arriving with neither has decided
too), and the view mirrors the two facts back into the URL with `replace`, gated on the
**rendered** tri-state `useBuskWindowDecided` for `useBuskPageDecided`'s reason. So the view's
address always carries both, a copied link reproduces the shape, and a reload finds the mirror's
own writes and is never an arrival.

**The sheet is one fact, and the fold is `none`.** There is no `sheetOpen` beside it, whatever
`Focus.dc.html`'s older sketch lists — `SideSheet` (`components/busking/SideSheet.tsx`) draws the
tab strip and the tab when the fact names a live tab and `SideSheetFold` (the shared 40px strip:
the beat, master 1's tempo, one glyph per live tab, the selection's colour off the stage's colour
dispatch, the head count) otherwise. What *is* kept beside it is the last tab that was open — a memory, not a flag —
so a MIDI `{sheet: 'toggle'}` **and the fold's chevron** unfold onto the tab the operator had —
both through `toggleBuskSheet`, the one reader of that memory (a glyph on the fold names its own tab). **Speed is `BuskSpeedRail`**
inside the sheet — filling the width the sheet is dragged to since 2026-09-21, where it was a fixed
288px column, and drawing no heading or caption (the usage badge's sentence is on the badge's own
title) — **Colour is `ColourSheet`** and **Spread is `SpreadSheet`** (both below). **None of the three
draws a heading**: the tab strip names the tab, the rig band's row carries the mask (the family
pill and the desk chip), and what is selected is the lit tiles — or, in Pads, the summary in the
row's gap. **Colour and Spread keep their verbs in a static footer** under the
tab's one scroller, the save first on both (*Save as template…* / *Save as Look…*), so the two tabs
put their save in one place; the Colour footer is its own `@container` and *Pick* and *Spread…* fold
to icons below `FOOTER_WORDS` (340px — `ColourEditor`'s, applied on the docked host alone), since
at the sheet's 320px default the worded three wrapped. The Spread footer also carries **Live** beside **Apply** — with its word at every width,
since *Save as Look… · Live · Apply* fits the floor — and while Live is on Apply reads *Send again*
and stays pressable, the un-deduped resend. Their pickers are fluid (`ColourEditor`), taking the sheet's
width, and their picker rows are padded to the knob's half-width — a tab body scrolls, a scroller
clips at its edge, and a knob at 0% hung 14px past the square and was cut off at the 12px gutter. All four have landed, and `LIVE_SHEET_TABS` stays the one list rather than
collapsing into the sheet vocabulary: Show landed exactly the way Colour and Spread did, and a
fifth tab would too — **hidden** there until its session — adding a tab to that list lights it in
the strip, the fold's glyph row, the Screens sheet's Sheet segment and the toggle's memory at once
— because a tab with an empty state is a promise the desk cannot keep, and a fact naming a hidden
tab draws the fold.

**Show is the fourth tab, and it is why the busk view has no `ShowBar`** (busk-chrome plan D1–D6,
`ShowTab.dc.html`; `components/busking/ShowTab.tsx`). It mounts **`RunMobile`** — the phone runner
Show swaps to below 600px, one component and not a copy — over the transport `routes/Busk.tsx`
already holds through `useShowBarProps`, whose whole result the route threads down as `show`
(through `BuskingView` to both sheet forms) so the tab, a pad press and the fold act on one
transport. Its display state is `hooks/useRunnerDisplay.ts`, lifted out of `ShowPage` so the tab
and the phone cannot disagree about which cue is next; *Make live* is `useMakeStackLive`, lifted
beside it for the same reason, because the tab's picker **browses** as the phone's does and
`OffPlayheadBanner` says so, with requeue inert there. `RunMobile` gained two props for it: a
`strip` slot, where the tab puts `ProgrammerIndicator` (the blind report and value count the bar
carried), and `defaultExpansion`, `null` from the tab — **the cards open collapsed here** (D2),
because the rig band is the stage on this view and two mini-stages a column apart would read as
two answers; the phone still opens Current in Stage. BACK · GO is the tab's static footer, the way
Colour and Spread keep their verbs, and DBO is still inert (`FU-FE-DBO-INERT`). **No transport
keys on the busk view** (D5): Space on a focused pad presses the pad, and a key that also fired GO
would be two effects from one press on a live rig — GO is the footer, a MIDI `go` binding, or the
Show view one pill away; `Busk.test.tsx` and `ShowTab.test.tsx` both pin that `useTransportKeys`
is never called. **The tab strip folds its words to glyphs below 400px of sheet** (D3): the
strip's **unpadded wrapper** is the `@container` — `SheetPage.Header`'s convention, since a size
query measures the content box and the row's own `px-3` would fire the fold 24px early — every
tab keeps its glyph, and below 400 only the *open* tab keeps its word (`tabWordClass`), so the
320 floor holds with four tabs, the mode toggle and the chevron on one row. The overlay strip
takes the same rule and the same wrapper: three worded tabs are ~291px, which overran the
right-hand form while it was 288 and ran under the sheet primitive's close cross. **That form is
320 now**, the sheet's own floor: the Show tab's strip — name, list, programmer chip, tempo chip,
DBO — fits at 320 with the stack name shrunk to its minimum (`RunMobile` makes the name the one
item that gives) and clipped DBO at 288. **The fold shows the live cue number under the Show glyph** (D4) — green, an
em-dash with nothing on stage — from the **server** cursor (`transport.serverActiveCueId`, which
holds on the outgoing cue mid-fade), derived in `SideSheet` and handed in as `liveCue`; the strip
subscribes to nothing for it. **The Show glyph also carries an amber dot while the programmer is
blind** (`BlindDot` in `busking/BlindMarks.tsx`), in the tab strip — docked and overlay — and on
the fold: the sheet's one programmer report is the Show tab's strip, so the glyph says there is
something to open it for. A mark only, `aria-hidden`, never a control — the word rides the tab's
and the fold button's accessible names (*Show — programmer blind*), because an `sr-only` span inside
those buttons was silent on the fold, whose `aria-label` wins the name, and *became* the name in the
strip below 400px of sheet, where a non-open tab's word is `display: none`; the pill on the band
(§The rig) is the louder half of the same answer.
Off the desk board the sheet is `SideSheetOverlay` — a bottom sheet on an upright phone, a
right-hand sheet where the viewport is short, through `useEditorForm`'s forms — opened from
the page strip's *Sheet* button onto Colour, carrying **Colour · Spread · Show** and **still no
Speed tab** (D6): Speed was withheld there because the ShowBar had the tempo chip, and the Show
tab's strip carries that chip now, so the reason is met by the tab that replaced the bar. The
palette still replaces the whole region while editing.

**Its chrome is the programmer rail's, and both live in `components/sheet/sidePanel.ts`.** The two
are one instrument in two views — a column against the right edge of a live view, folded to a strip
of glyphs, a 40px header over a scroller — and they had drifted in exactly the measurements the
chrome system exists to settle: the sheet was a transparent column on a 36px header with a 44px
strip, whose chevron was `p-1` and whose tab glyphs were `p-1.5`, against the rail's opaque fill,
40px header and 40px strip cells. The module states each once (`SIDE_PANEL_BODY_CLASS`,
`_HEADER_BUTTON_CLASS`, `_STRIP_CLASS`, `_STRIP_CELL_CLASS`, `_ENTER_CLASS`), `sheetFrame.ts`'s
model — a surface that wants to differ says so at its own import. **Both headers are tab strips
since the editor kit's session 4**, and the fold their words take is the module's too:
`tabWordClass`, moved there from the busk sheet when the rail took Stack · Colour · Spread (§The
rail's tabs), so the two strips fold by one rule. **There is no
`SIDE_PANEL_HEADER_CLASS`**: a panel's header is a 40px chrome row like any other, so both
surfaces take `CHROME_ROW_CLASS` from `sheetFrame.ts` and a panel-specific name for it would have
been the one measurement stated twice — the drift both modules exist to close. The overlay form's
tab row takes it too, at `h-11`, so its inset went from 8px to 12: a tab row is a chrome row off
the desk board as much as on it, and that one carries two tabs and no chevron. Three things carried across with it. The **fill is
opaque** (`color-mix`, not `bg-card/40`), because both panels have an overlay arm over live
content. The **chevron vocabulary is the rail's** — `ChevronLeft` opens, `ChevronRight` folds —
where the sheet drew `PanelRightOpen` / `PanelRightClose`; the fold's chevron is the strip's own
40px cell now, so the two strips start at the same line. And the fold is **40px, not 44**: 44 is
the programmer's *phone handle*, a target for a finger, and both of these strips are drawn only on
a board wide enough to dock. `Sheets.dc.html` §Folded, still cited above as the layout authority,
draws that strip at 44 — **the commit wins**, as elsewhere in this doc.

**What is deliberately not shared is the state.** The rail's `collapsed` is a persisted desk
preference in `ProgrammerWorkspace`; this sheet's fold is `busk.sheet === 'none'`, a per-tab fact
the announce, ⌘K, the Screens sheet and MIDI all write. Nor is Escape: a *docked* panel takes it on
neither view, and only the overlay arms do — the rail's through its own window listener, this one's
through Radix. (The sheet had no resize handle and a fixed 288px until both panels took the one
drag below.)

**Both panels are dragged to width by one piece of code** — `useSidePanelResize` and
`SidePanelResizeHandle`, the rail's drag lifted out so the busk sheet is resized by it rather than
by a second copy. One ceiling for both (`SIDE_PANEL_MAX_WIDTH` 480) and a shared floor
(`SIDE_PANEL_MIN_WIDTH` 260; `RAIL_MIN_WIDTH` / `RAIL_MAX_WIDTH` are aliases the rail's tests are
written in) that a panel may raise for itself, each with its own key and default in
**`localStorage`** — `programmer.rail.width` at 300, `busk.sheet.width` at 320.

**A panel's floor is set by its header, and the sheet's is 320.** Three labelled tabs, the mode
toggle and the fold chevron inside the chrome row's 12px gutters measured 304px when the floor was
set, so at the shared 260 that row overflowed by 38 and put *both buttons outside the panel* —
reported from an iPad. The strip holds four tabs now, and 320 still holds only because of the D3
fold: below 400px of sheet every tab but the open one is its glyph (§Focus and the side sheet).
The rail's header is two short labels with badges and fits at 240, which is why the floor is
per-panel rather than one number raised for everyone. 320 and not 304 because a minimum sitting on
the exact fit clips again the moment anything joins the row, which is how this broke: the row
fitted until the mode toggle was added to it. A width stored below a panel's floor is lifted on
read. And the row **degrades instead of clipping** now — the tab strip is a `min-w-0 flex-1
overflow-hidden` group and the two buttons are `shrink-0`, so a row with no space loses the end of
the last tab rather than pushing a control off the edge.
`localStorage` and not the per-tab `sessionStorage` the other `busk.*` keys use, because a width is
a fact about this desk's screen rather than about which of two windows you are looking at. The
handle is drawn wherever the width on screen *is* the stored one: in overlay mode always (the
operator chose to float it, so their drag still applies), and in push mode only where the panel
docks — the rail's narrow push arm is a fixed 300px overlay a drag would not move.

**The frame that holds the width takes the panel's contents as `children`, and that is
load-bearing.** The width changes at pointer rate, so whatever holds it re-renders at pointer
rate; `RailBodyFrame` and `DockedSideSheet` both take `children`, so the element references are
unchanged between their renders and React skips those subtrees — otherwise a drag would rebuild
the layer list and the FX list, or a colour picker mid-drag, sixty times a second.

**Never give either panel a bare `duration-*` or `ease-*` class.** `tailwindcss-animate`
re-declares `duration-*` as `animation-duration` while Tailwind's own still sets
`transition-duration`, and CSS's initial `transition-property` is `all` — so a panel carrying one
transitions **every** property, for as long as `usePanelEnter` latches the class, which is the
whole time it is open. What it broke was the resize: every width the drag set was *transitioned*
to, so the panel eased toward the pointer instead of following it. Reported from the desk as the
drag feeling like an animation. `SIDE_PANEL_ENTER_CLASS` says `[animation-duration:300ms]` and
`[animation-timing-function:…]` instead, which cannot leak.

**Beside the content, or over it** — `lib/sidePanelMode.ts`, one `push` | `overlay` fact shared by
both panels, per tab in `sessionStorage` like every other fact about how *this window* is arranged,
toggled from `SidePanelModeToggle` in either header. It rests at `push`, which is what both panels
already did, and it can only ever make a panel **float where it would have docked** — never dock
where there is no room, because each surface keeps its own floor (the rail below 1200px of
workspace, the sheet off the desk board). One fact and not one each: the two are deliberately one
instrument, and a desk where the rail floats while the sheet pushes is two answers to one question.
In overlay mode the rail has **one arm at every width**, so `collapsed` says nothing there — its
header and strip each draw a single chevron instead of the docked/overlay pair, the resize handle
is not drawn, and the body is mounted on `overlayOpen` alone (reading `!collapsed` there would hold
a layer list, an FX list and every subscription under them alive behind a shut panel).

**Neither panel ever draws its strip and its body at once.** The fold *is* the closed state of the
panel — one control in two shapes — which is what the busk sheet always did and what the rail did
not: it stood its 40px strip beside the open overlay at 704–1200. Reported from the desk, and the
sheet's reading is the one kept, so the overlay is flush `right-0` rather than inset past a strip
and the strip's chevron is open-only (its `aria-expanded` went with the state it distinguished).
**But a floating panel keeps the strip's 40px in the row.** The overlay is absolute and takes no
room, so a strip that left the flow as it opened handed its width to the view behind, which
reflowed on every open and close — the thing floating is meant to avoid. The rail's strip goes
`invisible` under an open overlay (push mode's narrow arm too), and the busk sheet draws an empty
`w-10` spacer before its floating panel; the overlay covers either. **And both panels stand beside
their view's menus, under nothing but the `ShowHeader`**: the programmer's row A is
`ProgrammerWorkspace`'s `header`, drawn in the grid's column, as the rig band is in the busk
view's — it was drawn above the workspace, which put the rail a row lower than the sheet.

**Both panels animate their opening, and neither animates its closing** (`SIDE_PANEL_ENTER_CLASS`,
`usePanelEnter`). The slide is the panel's **whole width**, bare `slide-in-from-right` as the
`Sheet` primitive uses. It shipped as `slide-in-from-right-4` — 16px — and read as no animation at
all, correctly: the content reflows by the panel's full width in one frame, so the eye takes the
jump and never registers a slide a fifth of its size. **The reflow itself stays instant in push
mode**, deliberately: animating it means animating `width`, which relayouts a virtualised grid every
frame, needs `overflow: hidden` (which clips the rail's resize handle, drawn 3px outside its own
left edge) and needs an inner fixed-width wrapper or the header's tabs reflow the whole way in.
The rail takes **one latch per arm** — `!collapsed` for the docked arm,
`overlayOpen` for the overlay one — because in the overlay arm the body is mounted the whole time
and the container query alone draws it: a single `!collapsed || overlayOpen` rests at true, so
opening the overlay transitions nothing and that arm never animated. The two results are OR-ed as
separate statements, never `usePanelEnter(a) || usePanelEnter(b)`, which short-circuits past the
second hook. The flag is **latched** while the panel is open — `animate-in` is a class, so a
re-render that dropped it mid-flight would cut the animation off part-way, and both panels
re-render freely while open — and it is **false on the first render** whatever the state says, or a
panel whose stored preference is "open" would slide in on every arrival at the route and every
reload. It must be computed in a component that *outlives* the panel: `ProgrammerRail` and
`SideSheet` both render the strip or the body and stay mounted across the swap, so a hook inside
either subtree would see every appearance as a first render. There is no exit animation because an
exiting panel has to stay mounted for its duration, which here means holding a layer list, an FX
list and their subscriptions — or a colour picker mid-drag — alive after the operator asked for
them to go.

**The Colour tab is the docked host of `ColourEditor`, and it writes literals to Local, and only
that** (D8, `components/busking/ColourSheet.tsx`; editor-kit plan D10). What is the host's: the
selection → write-targets planning (`planColourWrites` — a group as a group write, a cell by its
element key, a whole fixture as one write or one per cell for a pixel bar whose colour lives on its
cells; it is about the busk selection, not the editor), the rig order Pick reads in, the Spread
hand-over, the live push and its release, and the save sheet. Everything else — the picker, the
typed R/G/B, the emitter rows, the read-out, Pick and its hidden leaves, Recent, the footer — is
`components/editor/ColourEditor.tsx`'s, docked (§The editor kit). Every drag is
`programmer.setColour` per selected target, which is what a template pad's click does and what the
programmer's colour cell does. There is **no layer arm and no ⌥ arm**: a picked colour has no
library referent for a layer to follow, and *Save as template…* (`NewTemplateFromSelectionSheet`
over the sheet's targets, family Colour) is the route to something trackable. **The family mask is
not consulted**: the desk's mask gates presses, not value writes, so the sheet does not refuse under
a Position marquee — the rig band's family pill, one row up, is where the mask is read (the tab's
own header said *Colour of 14 heads · writes to Local* and repeated the band until 2026-09-21). A
*Recent* chip is a press and goes through `useTemplatePress` under the mask like any other. The
writes go through `components/editor/useLivePush.ts`, the tempo fader's discipline lifted out of
`BuskSpeedRail` and made generic over the value: dedupe on an equality, a 50 ms floor, a deferred
value sent when the floor lifts, the release bypassing both. **A group is one group write only
where its members agree on emitters**; otherwise it fans to one write per member carrying
`sourceGroup`, because the desk writes a group colour verbatim per member and adds white only on a
head that has one — it can fold white for none of them, so pure white as a group write over a mixed
group would black out its RGB heads. **The buffer is seeded from the rig** — the editor's Pick on
mount and on every change of heads, retried until they have reported (`pickOnTargets`) — so a
single-channel edit leaves the other RGB bytes where the rig has them, as it does in the cell; the
emitters start at 0, since the appearance store exposes one folded colour and nothing per emitter.
The host hands the editor its write targets in **rig order** (`rigHeadOrder` over `effectiveRig`,
the desk's own order for an empty rig, as the editor's `headOrder`), which is what makes *the first
head in rig order wins* true there and *the first row of the marquee* true on the programmer. And
**the release is read from the window**, as the tempo fader's is, since the picker binds its own
release to the document; the host keeps its own copy of the channels for that flush and for the
Spread hand-over, told through the editor's `onPick` when Pick moves them. **The knob's seed is a
constant** (`NEUTRAL_CSS`): the editor re-seeds its knob from `combinedCss` whenever it changes,
and here it is always open, so routing every write back into that prop was the ping-pong the
editor's own docblock records — the knob moves only when the editor means it to, on Pick. The
*Spread…* button — a plain button with a verb, since it opens a tab and holds no state a
`role="switch"` could ever read as checked — hands the current channels to `onSpread`, which both
sheet hosts wire (`SideSheet.tsx`'s `useSpreadSeed`): it opens the Spread tab with *From* set to
their **RGB** — a colour intent has no emitter component, so a white or amber the tab was driving
does not travel — through a seed the host holds and the Spread tab drops once it has read it, so a
later visit by any other door is not re-seeded with a stale colour. A host with no Spread tab to
open leaves `onSpread` out and the editor draws the button inert.

**The Spread tab is the docked host of `SpreadPanel`, and the client never lerps** (D9;
`components/busking/SpreadSheet.tsx`, `lib/spreadIntent.ts`; editor-kit plan D2, session 3).
What is the host's: the busk selection → the request's targets (`lookLayerTarget` — a group as a
group, a cell by its element key) and the families the heads can take (`targetFamilies` over the
selection's write targets), the selection's mask, the mutation with the programmer fade read at
send time (`useSpreadMutation` in `store/programmerOps.ts` — REST for that file's own reason, the
structured reply), the colour templates an endpoint may name (`isSpreadColourTemplate` in
`fx/FxColourTemplates.tsx`), the *Over: Cells* count (`selectedCells` in `lib/cellsSubSelection.ts`,
the Cells chip's own expansion, so the chip and the tab cannot count cells two ways), the Colour
tab's seed hand-over, and *Save as Look…* in the footer's `save` slot. Everything else — the form,
the endpoint editors, Curve · Order · Parts · Over, Live and Apply / Send again, the keyboard, the
answer read for `skippedFamilies` alone — is the panel's, shared with the programmer's row C (§Sheet
kit), and nothing changed to the eye when it moved. The tab sends two intents of one property's
shape, a curve, an order, parts and an over-switch to `POST /programmer/spread`; the desk
interpolates in the intent's own space, resolves one literal per head through the same
`TemplateResolver` a template click uses, writes each into Local as an ordinary entry (so it rides
`programmer.entryChanged`, Record captures it, Blind previews it, Clear releases it), and answers
what it wrote. Only the desk knows a group's member order, each head's range, which cells a fixture
has and what a colour means on a head with amber — the same rule that keeps `templateIntent.ts` a
serialiser, and `spreadIntent.ts` keeps it: it serialises `from` / `to` per family over
`templateIntent.ts`'s own serialisers (a colour + policy, or a `tmpl:{uuid}` reference through
`colourUtils`; `pct:` for a level or a beam role; degrees for a position; `dmx:` for an emitter) and
`spreadIntent.test.ts` asserts its import list reaches no resolver — and the panel's and the
popover's reach no lerp but `rawValues`, from the raw arm alone. **There is no preview strip**
(2026-09-21): there was one, drawn from `written[]` and `skipped[]` — one bar per head in the desk's
order — and it went because it cost the tab its height and the rig itself is the preview. The rule
it embodied stands and is why nothing replaced it client-side: the tab never shows what it thinks
the desk *would* do. Only the **latest** request's answer is read: Live keeps several in flight and
their answers can land out of order, so a property or selection change disowns whatever is in
flight (`requestSeq`). The family segment is **Intensity · Colour · Position · Beam** with a
**Property** row beneath it where a family holds more than one (zoom and frost under Beam): the
board drew *Zoom* as a fourth segment, conflating the family with the property. Curves are Titan's
four (`LINE` · `MIRROR` · `ARROW` · `WINGS`, each drawn as a picture — Wings as **two strokes
meeting at a marked centre**, since its eight fractions are Mirror's and one polyline through them
*was* Mirror's V); order is a `DistributionStrategy` name — Rig · Reverse · Centre · Random are
`LINEAR` · `REVERSE` · `CENTER_OUT` · `RANDOM`, and pressing Random again bumps the request's `seed`
for a fresh shuffle. The design's *Stage L→R* is a **footnote under the row with the reason**, not
an option: the desk has no stage order today (`SpreadPlan` feeds `POSITIONAL` a head's index, which
is Rig again), offering it as something it is not would be worse than withholding it, and a
disabled item in the row wrapped at 288px and read as a control that was merely off. A position
spread's defaults are **absolute degrees about the desk's centre** (270 / 135, `TemplateEditor`'s
convention): `deg:` is each head's own `0…degMax`, so a signed value about the centre clamps to the
hard stop. *Over: Cells* carries the cell count where a selected fixture has elements, and stays pressable
where none does — the choice survives a change of selection, an empty one included (it is the
window's, `lib/spreadOver.ts`), and over heads with no cells the request says Heads
(`effectiveSpreadOver` — the desk would make the same substitution, each fixture without elements
being its own unit).
**Live** sends every adjustment through `useLivePush` with an equality over the whole request, the
release read from the window as the Colour tab's is; off, only *Apply* writes. An explicit Apply
always sends — and stays pressable while Live is on, reading *Send again*, because it is the one
un-deduped resend after a write the desk refused; turning Live on sends nothing by itself. The
window release flushes only while Live is on, since the switch can be toggled from the keyboard
with no `pointerup` to clear the gesture; the typed fields keep a draft and commit only a number,
so clearing one writes nothing and a leading minus can be typed; and the `skippedFamilies` toast is
keyed like the endpoint's error toast, since a Live drag under a mask answers it on every write.
Under an empty selection nothing is sent and the panel's own sentence is toasted — the desk would
answer `SPREAD_NEEDS_SELECTION` otherwise, and `errorToastMiddleware` renders every 400 (the
mutation is **not** in `SILENT_ENDPOINTS`, keyed so a failing Live burst replaces one toast), so the
tab must not say it twice. **The mask is honoured by the desk, not pre-refused here**: a property
outside the selection's families writes nothing and answers `skippedFamilies` — a 200, the Look
press's shape — toasted in `skippedRowsMessage`'s vocabulary; the tab opens on the first family the
mask names that the selection can take. The colour endpoints share one `ColourEditor` (compact,
footer and read-out off — the busk tab's shipped measurement, which every host of the panel now
takes) for whichever end is being edited, and its `combinedCss` is a **seed** that moves only when
the panel means the knob to move (switching ends, Swap, the Colour tab's hand-over) — never the
picker's own writes, for the ping-pong reason documented on that prop.

**A spread is a result, not a template** (D10). *Save as Look…* opens `RecordLookSheet` over the
selection — `record-look`, the same gesture every busked state is kept by; the sheet gained an
optional `targets` prop for an opener whose selection is the desk's rather than the programmer
list's — and nothing on the tab mints a template. A "spread template" would need a second grammar
(two intents plus a curve) and a resolver that knows the selection's order at cook time, which no
template does. The band's *Spread…* verb and its compact menu item open the tab, writing
`busk.sheet` and nothing else.

**Short beats narrow.** `BuskingView` draws one of three boards: `md` says desk or narrow, and its
own copy of the 500px height query (`shortViewport.test.ts` pins the spelling) says whether a
window wide enough for the desk board has the height for it. A landscape phone is wider than `md`
with ~350px under the ShowHeader, so on the **short board** the rig strip and the page strip merge into
**one 32px row** — `RigStripContent`'s pieces in `BuskPageStrip`'s `leading` slot while the rig is
folded, the same pieces and not a third strip — Split shows one row of 48px tiles with the row chip
(`RigBand compact`), the side sheet **overlays** rather than docks (neither the fold nor the docked
rail is drawn; `SideSheetOverlay`'s right-hand form, Colour in its compact layout, from the merged
row's *Sheet* button), and *Edit layout* is withheld as it is below `md` (`editable` on the strip),
since a palette drag needs both regions on screen. The defaults — Pads, the sheet folded — were
already the ladder's; only which board is drawn changed.

**The facts ride the announce and the Screens sheet sets them** — see §Windows, full screen and
the hand for `viewOptions`, and `lib/windowViews.ts` for the descriptor the sheet renders from.
⌘K offers *Split · Focus pads · Focus rig* for this window only while it is on the busk view, and
*Show Busk on <window> · Focus …* as a two-frame arm on the show (`buildWindowCommands`).

**The columns of a row stack below 600px of the page body's width, and editing is desktop-only.**
`PageRow` hands its twelfths → `fr` tracks to the grid through a CSS variable so a container query
(`@max-[600px]:grid-cols-1`, on `BuskPageBody`'s `@container`) can override them — an inline
`grid-template-columns` could be overridden by nothing. It is the body's width and not the
viewport's because the rail or the palette has already taken its share: at `md` the body is ~480px
beside the rail and ~408px beside the palette, and four quarter-columns need ~600px before each can
hold one 110px pad. Edit mode stacks too, with a gutter drawn as a strip between two stacked columns
(still "the new column before column N"). Below `md` the palette is not shown and *Edit layout* is
withheld with it — and on the short board too, through the strip's `editable` — by decision:
narrow widths get play mode only, and an edit mode with nothing to drag from is a trap. `Done` stays at every width so a window narrowed mid-edit can leave.

### The rig

**The target band is a document the operator builds, and an empty one is the band as it was.** The
busk view's top region draws the **rig** (`lighting7/models/buskRig.kt`, busk-further plan D1–D3):
rows of tiles, each tile a group, a fixture, or one **cell** of a multi-head fixture; one rig per
project, `GET` / `PUT /busk/rig` whole-document with the page write's three refusals
(`BUSK_RIG_INVALID` / `_IDENTITY` / `_REF`), tiles renumbered dense and answered with the ids the
write minted. `api/buskRigApi.ts` is the wire, `lib/buskRig.ts` the document model (addresses
`rrow:{r}` / `rbody:{r}` / `rgap:{r}` / `rtile:{r}.{t}` / `rpal:{kind}:{key}` / `rnewrow`,
`applyDrop`, `normaliseRig`, `toRigRequest`, `nextRowName`), `components/busking/RigBand.tsx` the
band, `RigTile.tsx` the tile, `RigPalette.tsx` the palette's **Rig** tab, `RigEditProvider.tsx` the
band's half of the one `DndContext`, and `useBuskRigCommit` in `store/busk.ts` the operation queue —
`useBuskLayoutCommit`'s model over one document, response written when the queue drains, a refusal
toasted **by code** (`rigWriteFailureMessage`; `saveBuskRig` is in `SILENT_ENDPOINTS` so the
middleware does not say it a second time, generically). `TargetBand`, `TargetList`,
`TargetListItem` and the narrow-width *Pick targets…* sheet are deleted (D15).

**The show-all fallback is the client's, and its order is the desk's.** The server stores what the
operator built and answers an empty rig as empty (`{}` on the wire — lighting7 omits a defaulted
empty list, so `rows`, `tiles` and a patch's `elements` are all optional on this side, the
`TemplateSummary.rows` rule); `effectiveRig(rig, groups, fixtures)` then draws a `Groups` row and a
`Fixtures` row from the two lists the view already holds, **in the order `GET /groups` and
`GET /fixtures` answer them**. That order is not a choice made here: `state/BuskRigOrder.kt` walks
the rig for `selection.subselect`'s *Next* / *Prev* and answers every group then every fixture for
an empty one, and the two must agree. `buskRig.test.ts` pins `effectiveRig` and `rigSteps` against
the server's own fixture — the test imports `../src/test/resources/busk/rigOrder.fixture.json`,
the file `BuskRigOrderTest` reads, straight from the backend's tree (it was a hand-refreshed copy
while the two were separate repos). The fallback has **one render path** with the
built rig: its rows are rows like any other, only their tiles carry no address, take no drop and
are drawn dimmed behind the new-row zone while editing an empty rig.

**A multi-head tile decides how it shows its cells** (D3), on the tile rather than in a mode:
`cellMode` is `PIPS` (the whole fixture, cells as read-only pips — the default), `WHOLE`, `PER_CELL`
(one tile per cell) or `HALVES` with `cellSplit` (2 up to the cell count, contiguous runs cut the
desk's way — twelve cells in five are 3 · 3 · 2 · 2 · 2, `runsOf`); a tile dragged in as one cell
carries its `elementKey` and is `WHOLE`. `expandTile` is the one place a stored tile becomes what is
drawn and pressed, and **a cell is `{type: 'fixture', key: element.key}`**, the shape
`rowLocateTarget` publishes — element keys come from `patch.elements[].key` and are never parsed.
`useBuskingSelection` rehydrates a cell target by looking the key up in its parent's own
`elements`, so a fixture tile reads `some` with a `1 of 4` count when a cell is selected anywhere,
and `all` when every cell is — with the badge still `4 of 4`, because a cells-all tile and a
parent-selected tile are two different selections (the desk's `TargetCoverage` covers a cell by its
parent and never a parent by its cells) and the press differs: **from `all` a tile goes dark** — the
parent toggled off, or every cell toggled off when only the cells were selected — and from anything
else it is the whole fixture, the run tile's rule. A run tile toggles each of its cells, and
*Spread…* opens the side sheet's Spread tab (§Focus and the side sheet).

**A pip is a press of its own, and a cell is not a new target kind** (busk-further plan D11,
session 7). A `PIPS` tile's cells are checkbox-role buttons in a row **beside** the tile's button —
a button cannot hold buttons — and a tap toggles `{type: 'fixture', key: element.key}` through the
one `toggleTarget`, the shape `rowLocateTarget` publishes; a drag across them is a **run**, each pip
crossed toggled once, with the marquee's arm rule: a mouse runs from `pointerdown` under pointer
capture, a `touch` or `pen` runs only after a 500ms hold (a finger pans; a held one runs, and a
non-passive `touchmove` guard stops the pan for as long as the run lasts), a pip rests at the
board's 8px and the one under a held finger grows to 44px, and the click a run's release generates
is swallowed so it is not a second toggle. **A pip reads checked while its parent is selected**, and the band folds the parent's cells
into `selectedCells` for exactly that: the desk narrows the parent on such a press, and a dark pip
whose press deselects is the reading `presenceOf`'s comment already refuses for a cell tile.
**The desk owns the parent↔cell coverage rule** (`fx/TargetCoverage.kt`): a layer on the
whole bar covers a press on its cells, a layer on four cells does not cover the bar, and
`lookPresence.ts` keeps reading the desk's resolved `applied` extents with no browser copy of that
rule — `lookPresence.test.ts`'s cells block pins that a cell selection lights from what `applied`
names and from nothing here.

**The Cells menu is one desk op, and an unlinked window mirrors it** (D12, revised 2026-09-21). The
seven **filters** — All · Odd · Even · 1st half · 2nd half · Invert · Masters only — in one menu whose
label names the filter last pressed, and *Prev* / *Next* as two **step buttons** beside it
(`SUBSELECT_FILTER_MODES` / `SUBSELECT_STEP_MODES`), because a step moves the selection along the rig
where a filter narrows it, and the two read as one control only while they shared a chip's face —
each a press of `selection.subselect {mode}` (`api/selectionApi.ts`'s `subselect`,
`store/selection.ts`'s `subselectDeskSelection`; `SubselectMode` is the backend's nine names, pinned
against the server's fixture) while the window follows the desk, which rewrites the selection's
**targets** over rig order, keeps the mask, and answers with the ordinary `selection.state` frame.
The desk keeps **no** sub-selection state, so nothing on the menu's face is derived from the
selection: *Cells: Odd* is only the filter last pressed here, and a step is never remembered as it. Unlinked, `useBuskingSelection`'s
`subselect` runs `lib/cellsSubSelection.ts` over the tab's copy — and **only once the rig has
answered** (`RigBand`'s own `rigLoaded` guard), because `effectiveRig(undefined, …)` is the show-all
fallback and a press before the query lands would walk the wrong order with no frame to correct it — the nine modes over
`effectiveRig`'s rows, the fixtures' `elements` and the groups' members derived from each fixture's
`groups` in fixture-list order (`GroupSummary` carries no member list; a session 7 amendment to
D12's "member lists it already fetches") — pure, no React, and **pinned against the server's own
fixture**: `cellsSubSelection.test.ts` imports the backend's
`../src/test/resources/busk/subselect.fixture.json`, as `buskRig.test.ts` does the rig order's, and
runs every case. The Spread tab's *Over: Cells* counts through the same
module (`selectedCells`), so the chip and the tab cannot count cells two ways. Below `md` the nine
modes sit under a *Cells* heading in the band's verbs menu.

**The live bar reads the stage's colour dispatch.** `RigTile` mounts one `FixtureAppearanceSource`
leaf per fixture tile — one of **four** mounting readers of `components/fixtures/fixtureAppearance.tsx`,
beside the Positions panel's chips, the side sheet's fold (one leaf for the selection's colour dot)
and the colour editor's **hidden leaf per parent fixture of the heads it is editing**
(`components/editor/ColourEditor.tsx`, the busk Colour tab's and the programmer's colour cell's
alike since the editor kit's session 2), which reports into `lib/liveAppearance.ts` for *Pick*
(§Focus and the side sheet, §The editor kit); the cues' `MiniStage` borrows only its default colour — and a cell or run tile draws its own cells' colours off
the parent's per-element `segments` by the element's **position in the patch's cell list**, an
index into a list the desk ordered. A group tile has no channels of its own and draws no bar.

**Two wire facts the client has to live with, both recorded as session 3 amendments.** The rig GET
embeds `GroupSummaryDto`, which carries **no id**, while the PUT names every group tile by `groupId`
— on a kept tile as much as a new one — so `toRigRequest` resolves a group through the **patch
list** (`FixturePatchDto.groups[].id`, `rigIdsFromPatches`), the one place the desk publishes one,
and a group with no patched member is refused **here, by name**, before a PUT the server would 400
(`RigRequestError`). And **the hand cannot hold a group or fixture**: `hand.pickUp {kind, id}`
takes a `BuskPadKind`, so the `rig-row` target in `lib/handTargets.ts` answers false for every
kind, the row's `HandPlaceStrip` is mounted and wired (the rig PUT through the queue, then
`hand.drop`, Undo the rig as it stood) and never lights, and `RigBand`'s `rigRecordOf` is the one
function to teach the day the desk can hold one. Neither is worked around client-side beyond that.

**The band joins the one `DndContext` with `r…` ids and `rig-` data**, and the two documents are
mutually foreign: `parseBuskDragId` answers null for every rig id and `parseRigDragId` for every
page and slot id, `canLand` in `buskDnd.ts` refuses a rig source everywhere but the rig and a page
source everywhere on it, and each provider carries a **`foreign`** flag — set when something not its
own is lifted — that its droppables disable on, so a rig tile crossing a bank lights no ring
`canLand` would refuse. `resolveRigDropTarget` is `resolveDropTarget` over the rig with the same
four rules (self-hover ahead of the hysteresis, the hysteresis ahead of the null-over arm, a sticky
slot within one row's body, the half-of-the-tile test only on the tile dnd-kit measured), and
`buskDnd.test.ts` composes the slot and the landing place for a downward drag within a row. **A row
is created by the first thing dropped into it** (`rnewrow`): the server refuses an empty row, so
`+ Row` is the new-row zone rather than a button that mints a placeholder, and `normaliseRig` drops
an empty row while keeping an empty rig. Rows reorder by their grip onto the gaps drawn while a row
is lifted; `NameField` (extracted from `BuskBank`'s private `BankNameField`) is the row's name as it
is the bank's, and **a tile's too**: every in-document tile carries a menu while editing (the cell
modes on a multi-head fixture, *Rename tile…* on every kind, *Remove from rig* last), and *Rename
tile…* swaps the tile's face for that field, seeded with the name shown, writing the tile's
**`label`** — the column the rig has carried since session 3 and the UI §11 found missing. Saving
the record's own name clears the label rather than storing a copy of it (`relabelTile`, which
takes either kind; `setTile` is the cell-mode mutator and leaves a group alone). The seed is
`tileOwnName`, which follows how `expandTile` applies a label: the name shown where the label
*replaces* it (a group, a whole fixture, a single-cell tile — `Bar L · Cell 3`), the fixture's own
name where the cell names *compose on* it (per cell, halves), and every drawn sibling of one stored
tile takes the one label. `tileInput` sets the label **before** the GROUP arm's early return — a
label set only on the patch path silently reverted every group rename on the drained response. **A row is laid
out the way a bank is** (2026-09-21): it carries a `width` share in twelfths and a `flow` — the
bank's two facts, plus `SCROLL`, the sideways-scrolling line every row was and still the default
(`rowFlow` / `rowWidth` read an absent field as the default, for a desk that predates the two
columns) — set from the row's `…` menu in edit mode (`setRowLayout`). The rows fill a twelve-track
grid in order (`rigLines`), so two half-width rows share a line, and the band's unit is the **line**:
the handle snaps to a line's edge and the default height counts lines. `toRigRequest` sends the two only where
they differ from the defaults, since the desk's Json refuses a key it does not know and a desk
mid-upgrade must keep accepting a rig nobody has re-laid-out. The drag handle (D6, §Focus and the
side sheet) reads and writes the window's `busk.rigHeight`, never the focus, is drawn for a
one-line rig and hidden off the desk board (below `md` and on the short board alike, `!compact`);
every line is mounted in Split and in edit mode alike — the one scrolls, the other cannot hide a
row that has to take a drop. **The one row folds in a fixed
order, and its floor is two rows by design** (busk-chrome plan D15, `Band.dc.html`; the thresholds
are re-measured in the app and are `RigBand.tsx`'s to move): the band is its own `@container` and
carries `data-focus`, and the row gives up its words in this order — the **verbs' words first**
(`VERB_WORD_CLASS`: every verb is the desk's outline button with its icon; *Edit layout*'s word
goes with them, `EDIT_WORD_CLASS` — one ladder for Split and Rig, the two shapes the row is drawn
in since session A.5); then the ***Cells:* prefix and the Focus words** at one width in
both (`CELLS_PREFIX_CLASS`, `FOCUS_WORD_CLASS`) — **the Cells control keeps its mode word**
in its short form, All · Odd · Even · 1st · 2nd · Invert · Masters (`CELLS_SHORT_LABELS`), and is
never a bare glyph, since *All* is the state an operator most needs to be sure of; then the `RIG`
**label folds to nothing** (D20, `RIG_LABEL_CLASS`; `data-rig-row` is the handle a test reaches the
row by, since the label no longer is); and **below the floor
the row wraps at exactly one place** — the verbs group on the first row, the state group (pill,
chip, Focus, Edit layout) taking the second whole — through `TWO_ROWS_CLASS` /
`SECOND_ROW_CLASS`, **not `flex-wrap`'s own choice of break**: above the floor the row is
`flex-nowrap`, so a control never lands mid-row; only under it may the verbs group wrap within its
own line (`FIRST_ROW_CLASS`), the last resort for a band narrower than the ~330px the iconic verbs
need, which the desk board reaches with the sidebar open and the sheet at its 480 ceiling, and
which a size-contained `@container` would otherwise paint over the sheet. The desk chip is given
`min-w-0 shrink` on this row — both words, because `FollowPill`'s base is `shrink-0` — so its value
truncates before any control moves; the link badge it draws while following is `shrink-0` and
never gives. The rungs are **990 / 730 / 610 / 570** since desk-follow session 2 put the badge on
the resting row — 21px plus a gap, measured in the app (`RigBand.tsx`'s docblock has the
measurements); they were 960 / 700 / 580 / 540 with nothing there, 1100 / 820 / 700 with a chip,
860 / 680 while the summary had a row of its own, and 1150 / 1000 before that, when the verbs
shared the label row with the summary and the chips. The compact boards keep their one `flex-wrap` row — the row chip, the
summary, the verbs menu — and their Focus words fold at that row's own measure
(`COMPACT_FOCUS_WORD_CLASS`, 680), not the desk row's. **A
tile's cross and menu sit inside its top-right corner**: hanging 7px off the corners they were
clipped by a `SCROLL` row's body and overlapped the next tile's at the 8px gap. **A selected pip is
the accent, solid** (`Cells.dc.html`'s `pip.on`), and **a dark head draws no live bar** — the bar
sits inside the tile's border and is transparent at zero intensity, where a 15% floor painted a
faint line over a dark spot's bottom edge. Below `md` the band is one row with a row chip and the verbs in a menu,
and there is no editing there; in Rig focus below `md` every row is stacked two tiles across
(§Focus and the side sheet).

**In Pads the pad row is the body's top row, and the rig row is not drawn** (busk-chrome plan D17,
session A.5; `BuskPageStrip` with `pads`, `data-pad-row="pads"`). It carries, in order: the `PADS`
label, the page tabs **at the rig row's control size** (an `h-7 p-0.5` group of `h-6 px-2 text-xs`
items — the Focus control's segmented look; they were larger and rounder than everything beside
them) with the **page mark** beside them (§The busk layout: the link badge naming the windows paged
with this one, or *Page: Own*), **Spread · Locate · Highlight** as icon verbs, the selection summary in the gap (`min-w-0`,
truncating, its whole text on the title — nothing else says it in Pads), the family pill while a
mask is set, the selection's **link badge** (Pads always follows the desk selection — desk-follow
D2 — so here the desk chip is only ever the badge; D17 put the chip on this row since there is no
rig row to carry it), then the host's Focus control and
*Edit layout* / *Done*. **No Cells menu, no steps, no Clear**: those act on a selection made on the tiles, and the
use this view is built for is a two-screen desk with the rig on one screen, so a rig operation is
reached there; the three verbs act on the rig from either screen. In Split and Rig the same row
(`data-pad-row="split"`) is the tabs and the page mark only, and it wraps as it always did for edit
mode's name field. **The three verbs are one hook instance**, `useSelectionVerbs` in
`components/busking/selectionVerbs.tsx`, called once by `BuskingView` and handed to whichever row is
drawn — `RigBand`'s `verbs` prop in Split and Rig, the pad row's in Pads — so a window has one
Highlight capture and one locate fold and the two rows cannot answer a press two ways;
`SelectionVerbButtons` draws the three with the band's `aria-label`s, and the band adds Clear after
them. **A held Highlight releases when those buttons unmount**: `useHighlight` releases on its own
host's unmount, and its host is the view now, which outlives a focus change — so a MIDI
`buskFocusSet` mid-hold would otherwise take the band and the held button away with the dimmers
pinned at full. `RigStrip` and the short board's merged row carry the same pieces without a chevron
since 2026-09-22, and `RigHandle` has one arm, Split's. **The pad row's `@container` is a wrapper and the row is its child**, as the band's is:
a query container is the nearest *ancestor* and never the element itself, so a floor class on the
container element has nothing to match — the first cut did that, and below 680 the first group
took `w-full` in a row that never wrapped and pushed the Focus control under the docked sheet.
jsdom evaluates no container query, so `BuskPageStrip.test.tsx` pins the *structure* — and that
the 12px gutter is the wrapper's, so the container's content box is the row's and the rungs are
numbers on that box, as the band's are.

**A chip is drawn while its window is unlinked, and a linked window says so with a badge**
(busk-chrome D18, revisited by desk-follow D7 and D8). Unlinked, `DeskChip` draws the dashed
*This window* and `BuskPageChip` the dashed *Own*, each pressing back. Following, `DeskChip` draws **`LinkBadge`**
(`components/desk/LinkBadge.tsx`) — a 20px glyph, glyph only at every width, accessible name
*Following the desk selection*, whose press unlinks (D11; a mark instead in Rig and Pads), `shrink-0` — where D18 drew
nothing: a pill saying *Desk* all night was noise, but a window saying nothing about which selection
it is on left the operator to remember (Chris, 2026-09-23: "always show when we're linked, even if
it is just a small badge"). `BuskPageChip` draws the same badge while paged with the desk, naming
the other windows paged with it (§The busk layout), its names on each host's fold class. The *· from <window>* readout went with D18 and stays gone: an operator at a
two-screen desk knows which screen they are selecting from, and `deskReading` is deleted. `FollowPill` keeps the solid form and a `from` part all the
same, each part under a class of its own (D19: the host's rung for the suffix and for the subject),
with the **whole reading as `aria-label`** — `Targets: This window`, `Page: Own` — so the
name a test or a screen reader gets never changes with the width. The desk chip is on the rig
row in Split and Rig (`CHIP_SUBJECT_CLASS`, the Focus words' rung) and on the pad row's state line
in Pads (`PAD_CHIP_SUBJECT_CLASS`, both); the page mark is beside the tabs. The way *out* of
following is the badge's own press (D11), with ⌘K's *Stop following the desk selection in this
window* and the Selection segment on the window's row of the Screens sheet, from any window
(`windows.follow`, desk-follow D4), beside it — the Screens row being the door for a touch-only
screen set up from another; there is still no MIDI target (`FU-SURFACE-SELECTION-FOLLOW-SET`).
**Rig and Pads always follow** (§One selection, two shapes), so on those shapes only the badge is
ever drawn, and drawn as a mark.

**The words come back under the floor, as closed ranges** (D19). Below the floor each row has its
whole line, so what the ladder took returns while the line holds it and folds again at a second
measured rung — every re-expansion a **stacked `@min-[…]:@max-[floor]:` variant**, which overlaps
no rung above the floor, so no rung depends on the order Tailwind emits the rules in. On the rig
row the verbs' words return (from 520) only with the *Cells:* prefix folded, and the prefix's range
(from 350) ends at 520 where the words take its place — the verbs line is 546 fully worded, which
fits under the 570 floor, so that split is now conservative by 24px and kept so the two never share
the line; on the state line *Edit layout*'s word returns from 400 and the Focus words and the chip's
subject from 340 (the badge's 29 on that line, and the toggle measured at 36 rather than the 32 the
first note assumed). **The pad row has a ladder of the same shape and its own numbers**
(`BuskPageStrip.tsx`'s docblock: 1200 / 1120 / 900 / 810 / 770, then 610 · 560 · 530 on the first
line and 560 · 490 on the state line under the floor, with both badges) — measured on the dev rig's
two page names, since the tabs have no ceiling and a longer name moves every rung by its width. The
page mark is budgeted at its **wider resting form**, *Own* (57) rather than the glyph (21), since an
own page is an ordinary state, and its names and *Page:* go first, at 1200 — the badge's name is
capped at 96px so that rung has a ceiling. The merged row (names 820, *Page:* 760), the folded strip
(440, 400) and Split (410, 360) carry the mark on rungs of their own, in the same docblock. **The
labels fold last before each floor** (D20): `RIG` at 610 and `PADS` at 810, to nothing, and neither comes back under it. **Row C gave its `New`
word for the badge on the phone arm**: at 375 with a cell selected the row was full to the pixel,
so `TemplateStrip`'s `New` folds to its `+` below `@[600px]` (`PHONE_FOLDED_CLASS`, its title and
`aria-label` carry it) rather than the badge moving Deselect off the screen; above 600 the chip
scroller absorbs the badge. Both ladders
are pinned as orderings, not numbers, by `RigBand.test.tsx` and `BuskPageStrip.test.tsx`.

### The hand

**The desk holds one record between two screens.** A template, a Look or a cue is *picked up* on
any window and *placed* on any other — the cross-window move, deliberately instead of a pointer
drag, because the window that saw the press keeps the pointer for the whole gesture and the
neighbour never receives a pointer event of its own. Backend contract in `lighting7`'s
`state/HandState.kt` and `plugins/HandSocket.kt` (17140b3), the wire in
`docs/websocket-engineering.md` §Hand; the plan is
`../docs/plans/completed/multi-screen-plan.md` §3.5 and D12, and where
that plan's sketch and the shipped commit differ, **the commit wins**.

**Three frames, and none of them places.** `hand.state {item?}` is the connect snapshot and the
broadcast; `hand.pickUp {kind, id}` resolves the record **in the current project and nowhere else**;
`hand.drop {uuid?}` lets go. There is no `hand.place` and **you must not add one** — every target
already has a mutation with its own validation, so one frame that placed would reimplement four of
them behind a single name. **A place is the placing window's own mutation followed by `hand.drop`**,
and Undo is that window's inverse mutation, offered for ten seconds (`HAND_UNDO_MS`).

**A drop after a place names what it is dropping; the chip's × and Escape do not.** A place is two
independent round-trips — the window's mutation, then the drop — and another window may have picked
something up in the gap, so a bare `hand.drop` would clear an item this window never touched, on
exactly the two-screen case the hand exists for. `useHandPlace` in `store/hand.ts` is the one owner
of that sequence and sends `hand.drop {uuid}`; `handDrop()` beside it is the bare form and is what
the × and Escape call, because "let go of whatever is there" is precisely what those two mean.
Getting it backwards is **invisible in one window**, which is why `hand.test.ts` asserts the uuid
rather than the call — a test that accepted a bare drop would pass with the bug in. A failed
mutation drops nothing and toasts nothing: the operator still has the item, and
`errorToastMiddleware` has already reported the refusal.

**Every hold carries a server-stamped `holdId`**, monotonic, which is the identity of *this* hold
rather than of the record. It exists because `MutableStateFlow` conflates by `equals` and two
pick-ups in one clock tick would otherwise be indistinguishable. On this side it is the chip's React
`key` and the `data-hold-id` it draws: two hands of one record differ in nothing else, so without it
neither a test nor a later session can tell "still holding" from "picked the same thing up again".

**`store/hand.ts` is form 3** (`onCacheEntryAdded`), not the form 1 the plan's bullet names, and
`store/windows.ts` is the precedent that settles it in two steps. `HandChip` mounts in `Layout.tsx`,
so the module is on the **earliest render path** — the case §"Where a WS bridge subscribes" reserves
for form 2, where a module-scope `lightingApi.hand.subscribe(…)` can throw a TDZ `ReferenceError`
that `tsc`, `vite build` and the tests all miss. And `hand.state` is a **stream** carrying the whole
item with nothing to refetch, so there is no invalidation for a bridge to dispatch; a `queryFn` that
closes over `lightingApi` touches it only when the first reader mounts and needs no
`startHandBridge()`. There is also **no `open` branch**, and that is `selectionApi`'s rule rather
than `windowsApi`'s: the desk pushes the snapshot on every connect, and a write on connect would be
this window silently changing the desk's hand.

**The ghost is frozen and hookless.** The frame carries the record's **own summary DTOs**, exactly
as `BuskPadDto` does, so `HandChip` builds its face through `padFaceOf` and subscribes to *nothing
about the held item* — `dragOverlayRegistry`'s rule, for its reason: an effect template's detail line
reads a live speed-master label through a hook, and a chip that can sit on screen for five minutes
across every route must not mount one per hold. The cost is `FU-DTO-RECORD-SUMMARY` in lighting7:
the embedded `usage` and `buskPageCount` were computed at pick-up, so a long hold can read "on 3
pages" after a fourth was added. Intended — a pad's face is frozen between reads too — but **do not
build anything that presents those two fields as live**.

**Drawing the held record from its own DTO makes `HandChip` the widest blast radius on the desk, and
an empty list is what found it.** lighting7's **WebSocket and sync converters** set
`encodeDefaults = false` (the REST `json()` is Ktor's `DefaultJson` and *does* encode defaults — a
fact the rig row's `flow` / `width` test pins, and which four docblocks had backwards until
2026-09-21), so on a socket frame a defaulted **empty** collection is not serialised as `[]` — it
vanishes, and a client that declares the field required reads `undefined`. An *effect* template holds no rows, so
`TemplateDto.rows` did exactly that, and `templateRowsSwatch` threw on `rows.find`. Because this
chip is mounted in `Layout.tsx` and draws through `padFaceOf`, **every route** sat behind the error
boundary for as long as the desk held one.

The fix is in three layers, and the middle one is what stops it recurring. `@EncodeDefault(ALWAYS)`
on the backend field — `rows` and `requiredEmitters` both carry it now, as
`ProgrammerLayerStateOutMessage.applied` already did — is the durable half. But **`TemplateSummary.rows`
is declared `rows?:` on this side**, and that is not belt-and-braces about the annotation: a desk
mid-upgrade serves handler bodies without new response fields, which `lastPressedAt`'s own comment
already records, so the client must not crash on a server that predates the fix. Declaring it
required was what hid the other unguarded reads from `strict` — `LayerPicker`'s `.length`,
`seedValues`' `for…of` (then `TemplateEditor`'s, now `familyControls/templateRows.ts`; its `isGeneric` guard lets an effect template through,
since every effect template is generic), and four more. Making the type honest is what found them;
each now reads `?? []`, and `templateRowsSwatch` takes an absent list as an empty one.

The rule to carry forward: **a defaulted list or zero on a DTO needs `@EncodeDefault(ALWAYS)` on the
server *and* an optional type on the client**, because those answer different questions — what this
desk sends, and what any desk might. A lone `?? []` at one call site (which `TemplateStrip` had
grown for `requiredEmitters`) is the same bug caught one layer later, at one of the places it
reaches.

**Escape is the last rung, and the question is asked in the capture phase.** `HandChip` drops only
when nothing else claims the key: a cell editor open anywhere (`editorIsOpen()`), any
`[role="dialog"]` or Radix popper on the page, or an event already `defaultPrevented`. That is
§"The cell editor's three forms"' snapshot rule — *is an editor open*, not *where was the key
pressed*, which `isEditableTarget` and `closest('[role="dialog"]')` answer and answer wrongly the
moment focus leaves the panel. Radix listens on the **document** and closes first, so a bubble-phase
read always says "nothing open"; a **window capture** listener is the first thing any keydown
reaches, and the bubble handler reads what it recorded for that same press. Get it wrong and Escape
in a cue-name field silently drops the operator's held item.

**The timing is `useEscapeEditorSnapshot`'s, shared — only the predicate differs.** That hook takes
an optional `extra` and the chip passes `anyOverlayOpen`; the hand's ladder is longer than the
grid's, but the capture/bubble trick is delicate enough that a second copy of it is how one of the
two silently stops working. The overlay half is a **document-wide** query, never an ancestor walk,
for the same reason the rule is stated the way it is.

**`lib/handTargets.ts` is the one eligibility table.** A bank takes all three kinds (a pad *is* a
reference to one of them); a slot takes a cue or a Look with no deferred effect (it has no
selection, D7); both layer stacks take a Look or a template. So a cue lands only on a bank or a
slot, which falls out of the table rather than being stated a fifth time. It is eligibility and
never permission: every place still runs its own mutation.

**`canHandLand(…, 'slot')` is also what `LibraryPalette` sets each row's `slotEligible` from**, and
that sharing is the fix for a real hole rather than tidiness. The rule used to be written out four
times — three per-kind literals in the palette, `canHandLand`, and the test's own fixture — and
`slotAssignmentFor` re-checks only the *template* refusal, so the deferred-effect-Look half rested
on the flag alone. A test that built the flag from its own copy of the formula and then asserted the
two agreed was comparing two hardcoded copies of one rule: genuine drift in the palette would have
stayed green. With one statement of it that drift is gone by construction, so `handTargets.test.ts`
pins what is *still* two independent pieces of code — `slotAssignmentFor`'s kind re-check, fed a
deliberately wrong `slotEligible`, asserting both that it catches a template and that it does **not**
catch a deferred Look. `slotDrop.ts`'s docblock states that asymmetry; the sentence that stood there
before implied it covered both.

**The rings are buttons, not `useDroppable` sites**, which is a deliberate departure from the plan's
sketch. A hand place is a **tap** — the whole point of the hand is that no drag is in flight — so a
droppable could never be dropped on in this session, while being registered on the app's *one*
`DndContext` for every busk-page and surface drag that is; `DeskDndProvider`'s `closestCenter`
fallback returns one collision in the gaps between banks, and a foreign candidate there is exactly
the "highlight in one place, slot in another" failure §"The busk layout" is written about. Session
4's `elementsFromPoint` hit-test would not have been helped by one either — dnd-kit gives no way
back from a DOM element to a droppable — so **`data-hand-target` is the registration** both sessions
read. The affordance is explicit (`HandPlaceStrip`: a *Place “X” here* band, rendering null otherwise)
rather than a temporary second meaning for a surface's existing press, because a mode is state an
operator forgets.

**It is offered in *Edit layout* too, and draws solid there** (`amongDropTargets`). Both busk
targets withheld it while editing at first, and that was wrong in a way worth recording because the
reasoning sounded right: dashed means "a drag lands here" on that page, and this band is a button no
drag can land on — which is an argument about how it is *drawn*. Turning it into a refusal removed
the gesture in exactly the case the hand exists for, a record picked up on **another** window, which
this window's palette cannot stand in for. The empty cue slot made it plainer still: an empty tile is
not draggable and its click does nothing at all while editing, so the gate prevented no collision and
cost the place. Reported as confusing by Chris on the desk, 2026-09-16.

**`useHandOffer` narrows through `selectFromResult`, and the two layer strips are each split in
two.** This hook runs once per bank and *unconditionally* in every `CueSlotCell` — hooks cannot be
conditional, so the filled-slot path pays for it too — and a plain subscription woke all of them on
every `hand.state` frame desk-wide, including the usual case where their own answer was null before
and after. `useIsDeskConnected` is the same move for the same reason. The strips are split because
`useDeskSelection` is a second standing subscription: the outer component asks the narrowed offer
and renders nothing when the hand holds nothing it can take, so the selection is subscribed to only
while a placeable record is held. Flat, the programmer's strip re-rendered on every marquee that
crossed a row boundary — it sits in the rail footer on every visit to `/programmer` — and the cue
strip multiplied that by however many cue cards are expanded.

**A place that never left the browser is not a place.** `programmer.addLayer` is fire-and-forget, so
"it landed" can only ever mean "it was sent" — but *was it sent* has a real answer: `sendGesture`
refuses a closed socket, toasts "that did not reach the rig", and returns false. `addLayer` and
`programmerAddLayer` now return that boolean, and the programmer strip's `run` answers `null` on
false so `useHandPlace` keeps the record held and stays quiet. Returning `true` regardless — which
is how it was first written — dropped the hand and toasted success **beside** that error toast, on
the one place with no Undo to recover through.

**The four places, and the three Undos.** A **bank** is `useAddBuskPadMutation` — it answers the
whole page, so the busk view's commit queue needs nothing — and its inverse goes back through the
layout PUT, since there is no remove-pad route: `lastPadOfBank` finds the appended pad in the page
the append returned. A **cue slot** is `useAssignCueSlotMutation`, on **empty tiles only** — a filled tile's press is
live, and a hand held over the panel must not quietly become a second meaning for it; replacing an
occupied slot is *Clear slot* and then this. A **cue's stack** is `patchProjectCue`
through `buildCueInput`, appended at the top (later wins within a cue), and its inverse patches back
the layers array read *before* the place. The **programmer's layer stack** is `programmerAddLayer`
and has **no Undo**, which is a decision: that op is fire-and-forget and returns no id, the only way
to find the new layer would be to diff a stack that is *shared*, and a wrong inverse removing another
window's layer on a live rig is worse than none — the row it just added is one click from its own
remove. Both layer places send the **desk selection** as `targets`, which *supplies* them for a
template and *filters* them for a Look, and the family mask is the server's.

**Five doors pick something up.** A busk pad's hold now opens a **menu** — *Pick up* first, *View*
second — where it used to navigate straight to the library, because the hand needed a door there and
a hold cannot mean two things (`CueSlotCell` does the same synthetic-`contextmenu` trick, which
leaves right-click working on a mouse for free). On `/looks` and `/templates` it is a **bar verb over
one row** (library-sheets plan D11): the row menus that carried it are gone with the rows, *Pick up*
sits on the selection bar beside Duplicate and Copy to…, disabled over several rows with the reason,
and it is drawn only for the project the desk is on — the hand is project-scoped server-side, so a
pick-up from another project's library resolves nothing.
`LookStack`'s dense row popover picks up the layer's **referent** and leaves the layer alone; it is
not a `LayerHandlers` member, because those seven are index-based to address *this host's* layer
while a pick-up names a library record the row already carries. And the programmer's template chip
offers it on **right-click only**: the chip's hold is already ⌥click's touch twin (§"The two apply
gestures"), so on touch the routes are the library sheet's bar verb and a pad's hold menu.

**That chip is a `Popover` opened from `onContextMenu`, and must not become a `ContextMenu`.**
Radix's `ContextMenuTrigger` arms a long-press timer of its **own** (~700ms) for `touch`/`pen`,
cleared only by its own pointer handlers or by a `contextmenu` reaching the trigger. The pad and the
cue-slot tile are safe because their holds *dispatch* a synthetic `contextmenu`
(`dispatchSyntheticContextMenu`, shared by both — clearing that timer is what it is for, not just
reaching the menu on touch). The chip dispatches nothing: its hold fires a tracking-layer mutation.
So a stationary touch hold on it added the layer at 500ms and popped the pick-up menu at 700ms —
two effects from one finger, on a live rig, with the primitive breaking the rule the code had
written down. `PopoverAnchor` registers no handlers at all.

**MIDI has three doors of its own** — `pickUpPad(padUuid)`, `handPlaceInBank(bankUuid)` and
`handDrop`, all BUTTON, mirrored in `lib/surfaceDrop.ts` as §The MIDI surface view requires. There is
no `handPlaceInSlot` and no layer-stack place: neither a cue slot nor the programmer's stack has a
uuid a binding could carry. `handPlaceInBank` brought a new health variant, **`missingBank`** — a
*busk* bank, never the device profile's `unknownBank`, which shares only the word — and closing that
arm in `healthDescriptor.ts` was one of two gaps the backend's review found here. The other was
`describeTarget`'s missing `default`: `noImplicitReturns` is off, so an unknown discriminator
returned `undefined` and drew a **blank** label rather than falling through, which reads as a bug in
the panel. Its three siblings already answered properly. The library offers `handPlaceInBank` as one
chip per **bank** and `handDrop` on the Desk row; `pickUpPad` is in the picker only, because a
`Pick up` chip would sit beside the `pressPad` chip for the same pad under the same name.

**A record can also be *dragged* between two screens of one browser, and that gesture ends in the
hand rather than going around it.** Drag a library palette row off the right (or left) edge of one
window and it arrives on the window beyond that edge, under the pointer. The difficulty is the one
the hand exists for: the OS delivers a held button's moves to the window that saw the press, so the
neighbour never receives a pointer event of its own. So the drag is cut in two at the boundary —
`DeskDndProvider` hands the record into the hand (`hand.pickUp`), cancels its own drag, and posts
only the **release point** on a `BroadcastChannel('desk-drag')`; whichever window is under that
point claims it by hit-testing itself. `components/dnd/edgeDrag.ts` is the rule (bounds test,
screen→client, `data-hand-target` hit test, the message) and `useEdgeDrag.ts` the wiring. Seven
things about it:

- **A release no window claims is not an error.** The record is simply still in the hand, every lit
  band is still lit, and nothing is toasted. That is the whole reason the hand-off is a pick-up and
  not a transfer — and it is why the receiving half may never drop the hand on a miss.
- **It is gated on `BroadcastChannel` and on nothing else.** The plan asked for
  `'getScreenDetails' in window` plus a granted `window-management` permission; that is stricter
  than the APIs the gesture uses, so the gate is not built. The bounds test reads
  `screenX / screenY / outerWidth / outerHeight` and the pointer's own `screenX / screenY`, all six
  of which have always reported virtual-desktop coordinates and none of which is permissioned;
  `getScreenDetails` only *enumerates* the other screens, which nothing here needs because the
  channel is a broadcast and the neighbour claims by hit-testing rather than by being addressed.
  Where `BroadcastChannel` is missing the gesture is quiet (D13) and the hand is the route — as it
  is to the iPad, which this channel deliberately never reaches.
- **A presence handshake arms it, and without that it is a regression rather than a feature.** The
  bounds test alone cannot tell *the pointer crossed onto the next screen* from *the pointer
  overshot the edge of a windowed browser with nothing beside it* — and the horizontal chrome offset
  is 0 on every current desktop browser, so a window's outer edge **is** its visible content edge and
  an ordinary drag toward a cue slot overshoots it easily. So windows say `hello` / `here` over the
  same channel and the hand-off arms **only while another window is actually listening**. The probe
  is made **at the boundary, not at drag start**, so a drag that never leaves this window puts
  nothing on the channel at all and is exactly what it was before this session; a first crossing
  that finds presence stale probes and waits one frame for the answer. The same gate is what
  makes the channel's real reach safe: it is one browser instance and profile, so a Chrome window
  beside a Safari one, two profiles, or `localhost` beside the LAN name never hear each other — and
  each of those now gives an ordinary in-window drag rather than one that silently vanishes.
- **One `BroadcastChannel` object per page**, which is why both halves hang off `DeskDndProvider`.
  A channel delivers to every other object of its name *including ones in the same page*, and never
  to the object that posted — so a second object here would make the sending window hit-test its own
  release, place the record on itself, and the gesture would never leave the screen.
- **A posted release becomes a place by synthesising a click** on the hit element.
  `HandPlaceStrip` is already a `<button>` whose `onClick` runs the right mutation with the right
  *where* string and the right Undo, and `useHandOffer` has already refused anything ineligible; the
  empty cue-slot tile is the same shape. A click reuses all of it and states no rule twice, where an
  element→callback registry would be a second copy of what the button already is. Two properties
  come free: a `disabled` tile dispatches no click at all, and a target scrolled out of view is at
  no point. `dispatchSyntheticContextMenu` is the codebase's precedent for the move.
- **The cancel is a synthetic `pointercancel`, never a synthetic Escape.** dnd-kit has no
  programmatic cancel, but its `PointerSensor` binds `pointercancel` on the owner document to the
  very same `handleCancel` as its Escape handler — so this cancels as surely and leaves the keyboard
  alone. An Escape would sit in front of `HandChip`'s Escape ladder and every other document-level
  Escape listener on the page, and keeping it harmless rested on marking it `defaultPrevented`
  before anything else read it: true of the window *node*, but not guaranteed of listener *order* at
  that node. Saying "the pointer was cancelled" is also simply true, where "the operator pressed
  Escape" was a pretence. It runs through the provider's existing `onDragCancel` — the one that
  clears `isDragging` and so keeps the cue-slot panel body mounted — and `handleDragEnd` returns
  early for a handed-off gesture besides, so the cancel is not load-bearing: a dnd-kit that ever
  declined it could not also resolve the drop.
- **The pointer's screen position is read off a window `pointermove`, never reconstructed** from
  dnd-kit's `delta` and activator event, which goes wrong under browser zoom and a non-1
  `devicePixelRatio` — the desk's likely setup. The listeners are attached **imperatively at arm
  time** rather than by an effect, because an effect attaches a commit later and a fast flick can
  cross the boundary inside it; and they **outlive the drag**, because the hand-off *is* a cancel and
  the release they exist for has not happened yet, so the teardown is the pointer's. They refuse
  three things, each a real way to get this wrong: an event from a **different `pointerId`** (a
  second finger must not move or end the first one's drag), **our own cancel** (a flag held across
  the synchronous dispatch, since it would otherwise read as the release and post from the boundary),
  and a **second pick-up after a refused one** (a pick-up that never left the browser leaves the drag
  alone, and without this every later move would try again at up to 120Hz — one attempt per
  crossing, come back inside to retry).
- **A release names its record, and the receiver waits for the hand to hold it.** The channel is
  local and instant while the hand arrives over the WebSocket, so a release can outrun its own
  `hand.state` frame — the receiver would find no band rendered yet, or, if something else was
  already in the hand, find *that* record's band and place the wrong record. `whenHandHolds` is the
  wait — **and the question is asked once more in the instant before the click**, because the hand is
  shared and can move again while this window polls and settles, and the band is the same DOM node
  across renders, so React swaps its `onClick` closure in place: clicking the element found a moment
  ago would place whatever is held *now*, under a band that still looks eligible because it is. A
  release places the record it names or nothing at all. A target that never appears is a release
  nobody claims.
- **There is no arbitration between two claimants, and overlapping windows place twice.** A claim
  message with a lowest-id tie-break was built for that and removed again, which is worth recording
  because the reasoning is the general one. It **cannot happen on this desk**: the release is one
  point in virtual-desktop space, so both windows would have to contain it, and two windows tiled on
  two monitors never overlap — it needs windows stacked on one screen. It was a **mitigation, not a
  guarantee**: a claimant can only wait so long before clicking, so two windows whose target
  discovery differed by more than that settle window both placed anyway — exactly the timing skew
  (differing render latency, differing `hand.state` arrival) the case is about. And it was the
  source of a real double-place bug of its own. Both places are ordinary mutations the operator sees
  toasts for, and the bank place carries Undo. If it ever matters, the fix is arbitration that
  **waits for an acknowledgement** rather than for a timeout — not the tie-break that was here.
- **A palette row, and only a palette row.** A `busk-pad` drag carries a `PadFace` and a position
  and a `slot-item` drag a slot address; neither names a record id, so neither can be handed off
  without widening the busk page's own drag contract. `screenToClient` is an honest heuristic
  (`outerHeight - innerHeight` of chrome, all at the top; half of any side border), exact on
  Chrome and Edge on Windows and affordable against 28px bands, and **unmeasured on Safari**, where
  it fails safe — a point a few pixels out misses the band and the record stays in the hand. If it
  ever reads wrong the fix is to cache `event.screenX - event.clientX` from a real pointer event,
  not to reach for `getScreenDetails`. `handTargetAt` takes the **topmost** hit and walks up from
  it, never down the stack: a band behind an open dialog is still mounted, and burrowing past the
  overlay would place a record on a target the operator cannot see.

There is **no ghost following the posted point**, by decision: the receiving window's `HandChip` and
every eligible band already draw the moment the hand fills, so a following ghost is new UI over an
affordance that is already there. If it is wanted, it is a `move` message plus a **frozen, hookless**
snapshot, for `dragOverlayRegistry`'s stated reason.

### The MIDI surface view

`/projects/:id/settings/surfaces` draws the attached desk as **a picture built from profile data**
(`ControlSurfaceType.layout`), labels every control by re-deriving the backend's own resolution
rules, and — under *Edit bindings* — lets a library be dragged onto it: **a row lands on a strip, a
chip lands on one control**. Read
[`docs/midi-surface-engineering.md`](docs/midi-surface-engineering.md) before touching it; the plan
is `../docs/plans/completed/midi-surface-plan.md` and the layout authority
`../docs/plans/completed/midi-surface-design/`.

The four things that bite, in one line each. **`lib/surfaceResolve.ts` is a mirror of
`ControlSurfaceBindingService.resolve` and `deriveStripTarget`** and its failure is silent — a
precedence read backwards paints a plausible label for a control the desk drives differently.
**Droppables sit on the grid-cell wrapper, never inside the memoized `ControlCell`**, or a hover
costs the whole panel at 20 Hz. **A drop patches the control's *own row at the exact bank* or
creates** — `index.byControl.get(id)?.get(activeBank)`, never `resolveControl`, which would answer a
strip's row or a global one and move a binding the operator was not pointing at. And **the
eligibility dim mirrors a backend rule rather than standing alone**: since session 4,
`ControlSurfaceBindingService.refuseWrongKind` refuses a `fireCue` on a fader at the write boundary
whichever door it comes through, and `controlKinds` / `targetControlKind` in `lib/surfaceDrop.ts`
are the client copy of `midi/BindingControlKind.kt`.

**A colour on a continuous control is one of four HSV axes** — hue (the default, and the only one
a pre-axis row can mean), a fine hue trim, saturation, brightness — carried as an optional
`colourAxis` on the four property targets and on the encoder bank; the bundled emitters (white,
amber, UV) are offered as faders of their own, expanded off the colour descriptor's channels in
`hooks/useTargetProperties.ts`. `lib/colourAxis.ts` mirrors `midi/ColourAxis.kt` and owns the
null-is-hue rule; nothing on this side resolves an axis. The library is sectioned by kind with
**Desk first**, and a row's chips are grouped by family with a hairline — see the two sections at
the end of `docs/midi-surface-engineering.md`.

**Five more since session 7 of the busk-further plan (D14)**: `buskFocusSet` and `buskSheetToggle`
address a desk window by its **registry name**, `selectionNext` / `selectionPrev` / `selectionCells`
reach `DeskSelection.subselect` — see `docs/midi-surface-engineering.md` §"Windows and the
sub-selection".

A button can also press a **record**: `applyLook` onto the Look's *own* fixtures, `pressTemplate`
onto the desk selection, `pressPad` through the pad's whole bank plan, plus the three busk-page
targets. Four things follow on this side. Their library rows are **not** `TargetRowItem`s — that
component exists to mount `useTargetProperties` per target — so they are built in `SurfaceLibrary`'s
`rows` memo from `actionChip`. The **kind row stays at six**: a template files under *Looks*, a busk
page under *Desk*, and *Next page* / *Prev page* sit on the Desk row once, because a chip repeated
per page reads as page-specific. A **Look with a deferred effect is offered with no chip**, since it
has no own fixtures and the write boundary refuses it by name. And `describeTarget` **will not
resolve a uuid to a name** — `components/surfaces/recordOptions.ts` is the one owner of that, shared
by the picker and the inspector, the same split `useSpeedMasterDisplay` makes for a speed master.

**One desk, one selection, server-owned** (`store/selection.ts`), and three surfaces move it: the
busk target band, the programmer's fixture list, and a select button on the desk itself. The list
half is `useDeskSelectionBridge`, whose load-bearing rule is that rows publish through
`rowLocateTarget` — never the `programmer` scope's `targetKeys`, which is already flattened to
member keys, so a marquee over *Front wash* would reach the desk as eight loose fixtures with the
group's select LED dark. Edit mode there is **local state**, deliberately not the busk view's Redux
slice: that one exists only because the cue-slot overlay is a sibling of the routed page.

### The two apply gestures

A template has **two** presses, and the difference is invisible on screen — only the route called
says which happened, so both are stated on the chip's title:

- **click** → `POST /templates/{id}/apply`. Sets **literal** values in Local. Retuning the template
  later does not move them; this is the busking gesture, and it is why the retired `ref:` grammar is
  not missed.
- **⌥click, or a hold** → `POST /templates/{id}/toggle`. Adds a layer that **tracks** it, targeted at the
  selection and masked to the template's family — **the server derives the mask** from the
  template's own rows, because which family a template layer belongs to is a fact about the
  template, not about the press. This repo sends its `propertyMask` anyway, as the belief it is
  acting on; the response reports the mask actually applied, so a disagreement surfaces there rather
  than silently on the rig. Retune the template and every layer moves. The layer *is* the dependency
  mechanism — it already was, for Looks — so "a colour I can change everywhere later" and "a colour I
  want right now" are two gestures on one chip rather than two kinds of template.

**The hold is ⌥click's touch twin — `touch` and `pen` only, never a mouse.** It goes through
`useLongPress` on the chip, and it is a hold rather than a Set/Track switch in the bar because ⌥ is
per-press and a mode is state an operator forgets. It is gated on pointer type because a mouse has
⌥: a mouse hold would be a second, silent door to the tracking mutation, and a slow click on a live
rig would add a layer where literals were meant. That is the same gate, and the same list, the
grid's marquee arm makes, and it is the one place the desk's holds differ — a busk pad's mouse hold
opens an inspector, which changes nothing. A hold is otherwise the press's second meaning everywhere
on the desk: a pad's inspects it, a speed card's is its fader, and since the desk-findings' group B
the **grid's is its marquee**: on a touchscreen a finger pans and only a held one marquees
(`touch-action` cannot say that, so `useCellMarquee` arms by time for `touch`/`pen` and refuses
`touchmove` only while a marquee is live). A chip and a cell never share a point, so the hold never
means two things where a finger lands. The other phone rules on that row: below `@[600px]` the bar
is glyph · cell count · chips · New · Deselect (`SelectionBar.tsx`; the toolbar's Deselect serves
rows and cells alike since the two became one selection), and a tap on the grid's empty background
runs the same cells-then-rows ladder Escape does — only on the grid's own DOM, since React bubbles
a click inside a portalled cell editor up the same tree.

`TemplateStrip` lives in `ProgrammerGrid`'s `renderToolbar`, which hands down the marquee's
`cells` **and three things the container derives from them** — so **the selection is the filter and
the target**, and there is no picker to open or family dropdown to get wrong:

- **Cells selected**: only that family is offered, and the press lands on the cells' heads —
  three colour cells means those three fixtures (which, since the two selections became one, are
  also the fixture selection; see §One selection, two shapes).
- **Rows selected, no cells**: the gesture names no attribute, so the filter is what the heads
  *can take* (`targetFamilies`, from descriptors — capability-only, fx-templates D6): a rig of
  dimmer-only pars is offered no colour template, and nothing with a mover on it offers a position
  one. The press lands on the rows.
- **Nothing selected**: the whole library shows, and a press toasts that it has nowhere to land.

**`targetEmitters` narrows it further, in every one of those arms.** A template naming `white`,
`amber` or `uv` is withheld unless some selected head has every emitter it names — the family cannot
draw that line, since the hex and all three emitters are COLOUR. See §Looks, templates and layers
for why it is a union and why the probe reads the colour descriptor rather than a category.

`templateTargetsFor` in `rowModel.ts` is the target rule, and it is neither sibling: a group row
lands on its *visible* members (the filter rule every group-row action keeps), and an element row
lands on its fixture, because the template route resolves keys against the patch and would drop an
element key silently. The strip reads nothing from Redux; `NewTemplateFromSelectionSheet` takes the
same targets so a press and its "new from selection" cannot name different heads.
`TemplateStrip.test.tsx` pins the filter, the target and the click/⌥click split.

One trap on the response: the desk answers a **value** apply with `effectIds: []` as well as
`written`, so the "nothing started" warning is gated on `template.kind === 'effect'`, not on the
field being present — it toasted red on every successful value press before that. Both presses go
through **`useTemplatePress`**, shared by the chip and the picker's pads, so the two surfaces cannot
answer "what does a press do" a chip apart.

**The row is the eight most recently *pressed*, not the library**, and recency is a **desk** fact
rather than a tab's. lighting7 stamps `last_pressed_at_ms` on every press that *applies* a template
— through four doors: the chip's click, ⌥click / hold, a busk pad press, and a MIDI `pressTemplate`
— so every client and the desk's own hardware agree about what was reached for; a toggle **off** is
not a press, and neither is a click that reached no head. It arrives as the keyed frame
`templatePressed { templateId, lastPressedAt }`, which `startTemplatesBridge` **patches** into every
cached `templateList` entry (all `family` args — the filter is a query argument, so the programmer's
unfiltered list and `/templates?family=` are two entries of one endpoint). It must not be folded
into `templateListChanged`: that bridge invalidates `TemplateList`, `Cue` and `CueList`, and a press
happens at busking rate. `lib/templateRecents.ts` is the one place that orders them, and it orders
by **parsing** the stamps: `Instant.toString()` omits the fraction on an exact second, so `…:34Z`
compares *after* `…:34.500Z` as text. With nothing pressed yet the row falls back to the first eight
by name — all or nothing, never one recent padded out by seven, which would move the row's contents
under the operator's hand on the second press.

**`All · n` opens `TemplatePicker`** — the offerable library as a searchable pad grid, sections
Recent · All A–Z · Per fixture · Effects, built from the busk pad's own face (`padFace.ts`'s
`PAD_SHELL` / `templateSwatch` / `padPresenceClass`, and `templateLayerPresence` against the desk's
resolved applied state). Three things about it: it takes **`useEditorForm`'s three forms** and
neither of its own media queries, so the picker and a cell editor never disagree about which shape a
screen gets; **a press does not close it**, because auditioning three colours in a row is the normal
case; and its popover width is a **media** query rather than a container one — `PopoverContent` is
portalled to `body`, so row C's `@container` is not an ancestor and a container class there would
match nothing, silently.

**Row C sheds twice on the way down.** Below `@[800px]` the fixture count, its separator and Locate
/ Highlight fold (`MID_FOLDED_CLASS` in `SelectionToolbar.tsx`) — the count is already on the cell
count's hover, and both verbs are on the busk target band — which is what gives an iPad portrait two
recent chips instead of none. Below `@[600px]` the chip scroller is not drawn at all and the library
is reached through `All · n` alone, with Recent as the sheet's first section. The design authority
is `../docs/plans/programmer-chrome-design/`, page *Templates*.

**New from selection** is server-side (`POST /templates/from-programmer`), for the same reason apply
is: converting a recorded *literal* back into an **intent** is per-head arithmetic that has to agree
with the resolver. It also decides **generic vs per fixture from the data** — one row per property
where every selected head agrees, one row per head where they do not — rather than from a toggle,
because the operator already said which they meant by putting the heads where they are. The
colour inverse is a documented heuristic (fold the emitters back into RGB, policy `extract` when
either was driven); it lives in one place, `templateRecord.kt`.

### The desk selection has a mask

**Every press sends the pair it is acting on — targets and `families` — and the desk decides what
lands** (multi-screen plan D4, D5; lighting7 af3575a). The four press routes take `families?`
beside `targets`: `POST /busk/pads/{id}/press`, `/templates/{id}/apply`, `/templates/{id}/toggle`
and `/looks/{id}/toggle` (`buskApi.ts`, `templatesApi.ts`, `looksApi.ts`). The pair is the desk's
while the tab follows the desk and the tab's own when unlinked — `usePressFamilies` in
`store/selection.ts` answers it for the strip and the picker (through `useTemplatePress`'s third
argument), `useBuskingSelection` for the pads. It is the *desk's* mask when following even in the
moment another window has moved it under this tab's marquee, because a following tab's press acts
on the desk's selection and must be masked as the desk is; the strip does not pre-filter on it, and
a press outside it is refused by name rather than greyed out — but **row C's family pill reads that
same pair** (`shownFamilies` in `programmer/SelectionBar.tsx`), drawn over a row-only selection
too, so the one state where the two differ (another window's mask landed here as rows) is said on
the bar rather than discovered from a 400. The kit's bar draws the pill outside its cell block for
that reason; the plain lists keep the marquee's own reading, never bridging.

What the desk does under a mask, per kind: a **template** is one family, so it lands whole or is
400 `TEMPLATE_OUTSIDE_MASK` on every door, click and layer alike; a **Look** spans families, so its
layer lands with `propertyMask = mask ∩ its families`, the cook skips the rows outside, and the
response names them in `skippedFamilies` — nothing inside is 400 `LOOK_OUTSIDE_MASK`; a **cue**
ignores it. The two refusals carry a message naming both families and reach the screen through
`errorToastMiddleware`, which toasts every rejected mutation — which is why `useTemplatePress` no
longer toasts its own failures: it said the same sentence twice.

**The mask is tested on the on arm only, so never pre-refuse a press from the mask this tab holds.**
A press that turns a lit record off answers `removed` under any mask (`routes/pressArm.kt` reads
the arm before the press), and only the desk knows which arm a press is on — a lit Intensity pad
must still release while a Colour marquee stands. Send it, and render the desk's answer.

**The skip is toasted on the pressing window** (D6): `<Family> rows skipped — the selection is
<Family>` from `skippedRowsMessage` (`lib/selectionMask.ts`), read off `skippedFamilies` on the busk
press and Look toggle responses (the template toggle response is unchanged). It says **rows** and
must keep saying rows: the layer's mask filters its rows, not its effects, so a Look's effect in a
skipped family still runs while being named — promising the whole family was held back would be
promising more than the desk does. The window that made the marquee learns the way it learns every
layer: `programmer.layerState` carries the mask and `LookStack` draws the badge.

### Sheet kit

**One sheet, four surfaces.** The programmer's grid gestures — drag selection, single click
selects a cell, double click / ⏎ / typing opens one editor for every selected cell, ⌫ clears, one
editor per column spread over the selected columns, the Set · Clear · Spread bar — are a kit in
`components/sheet/`, mounted by the **patch list** (`components/patches/PatchSheet.tsx`), the
**DMX sheet** (`components/channels/DmxSheet.tsx`) and the **cue sheet**
(`components/runner/CueSheet.tsx`) as well as the programmer. The design record is
`../docs/plans/sheet-views-design/` (the `Kit` artboard is the module map, `Spec` the desk
survey and the rules). The surfaces differ in their columns and their verbs, never in the gesture.

**What lifted out of `components/fixtures-list/`**, generic over the column key (`CellRef<C>`,
`CellSelection<C>`): `cellSelectionModel`, `cellSelection`, `cellMarquee`, `listSelectionModel`,
`useCellSelection`, `cellEntry` (the generic half — `orderedSelectedCells`, the two DOM guards,
and the *shapes* `CellKeyboardPermission` / `CellActionCopy`; the programmer's own answers
`cellKeyboardPermission` / `cellActionCopy` stay in `fixtures-list/cellEntry.ts`),
the cell editor's surface and its two hooks, `ValueFieldRow` and `UnsetCellMark` (all four moved on
again since, to `components/editor/` — §The editor kit), the three fold
constants (`toolbarFolds.ts`, re-exported by `SelectionToolbar`), `CellSelectionActions`,
the fan popover and its `fanMath` (both since replaced by `editor/SpreadPanel` + `spreadPlans`,
below), `selectionBand`, `useEscapeEditorSnapshot`. Three were extractions rather
than moves: `useCellMarquee` (a local of `FixturesTable`, now generic over rows with a
`rowHeight` and an `isSelectableRow`), `useCellEditorRequests` (the open/close one-shots and the
Set toggle from the container), and `commitToSelectedCells` / `selectedRowsByColumn`
(`sheetModel.ts` — the container's `commitToCells` / `columnTargets` over rows instead of write
targets). The fixtures list keeps its columns, row model, ownership and scope, and
`FixturesListContainer` keeps its own keyboard listener: its tests pin it, and its selection is
Redux-scoped for readers outside the list.

**A `SheetColumn<Row, C>` per surface** says how to read a row (`value`), which cell it draws
(`cell`, or `display` for a read-out), whether it spreads (`spread` → a `SpreadPlan`), and what a
commit does (`write(rows, value)`, over the **batch**, answering false for a value it refuses) and
what Clear does (`clear`, or `clearRefusal` as the button's reason). **A commit spreads only to the
selected columns that share its origin's `kind`**: the programmer tells commits apart by shape,
but a cue's name, notes and fade are all one string, so each surface column names its vocabulary
(`level` on all sixteen DMX columns, one kind per column on the cue and patch sheets) and a `3s`
typed into Fade over a Fade→Follow marquee cannot switch auto-advance on. A read-out column hangs
no `data-column-header`, so a marquee never selects it. `useSheet` is the container half — one selection in two shapes, the keyboard, the editor
requests, the throttled commit, the batch count — and `SheetTable` the anatomy: 30px uppercase
header, 36px rows (56 on the DMX sheet), a sticky first column with the 3px selection edge, the
same DOM contract as `FixturesTable` (`data-grid-header`, `data-column-header`,
`data-grid-name-header`, `data-row-id`, `data-cell`). Four kit cells go through
`EditorSurface`, so they get the three forms and the double click for free: `TextCell`
(commit on ⏎, Escape reverts), `LevelCell` (the DMX value, live like `SliderCell`), `OptionCell`
(`SettingCell`'s type-ahead over plain options) and `AddressCell`.

**`SelectionBar` is a shell** — counts · family pill · ⏎/⌫ hints · a `strip` slot · a `verbs`
slot — and the programmer's `SelectionBar` wraps it with the template strip in the slot.
`CellSelectionActions` takes a `permission` of `CellKeyboardPermission`'s shape and a `spread`
slot, so a disabled button and a refused key always read one object, and each surface hands in its
own `SpreadPanel` instance. Surface verbs come after Spread; Deselect is always last and ghost.

**Spread is one panel, four plan kinds, every host — and the desk resolves it on both sides**
(editor-kit plan D1–D7, D15; `Spread.dc.html` is the layout authority, the busk tab wins on a
measurement). The verb was *Fan* until session 3 of that plan, and "Fan" survives only in the desk
survey as what other desks call it: the desk already called the route spread, a Look press
"spreads" its rows, and nothing on either side called anything else fan but this one verb.
`components/editor/SpreadPanel.tsx` is the busk tab's body plus the old fan popover's surface,
chooser and keyboard, hosted by the programmer's row C as a popover in the cell editor's three forms
(`fixtures-list/SpreadPopover.tsx` builds its plans from the marquee), by the busk view as the
docked tab (`busking/SpreadSheet.tsx`, §Focus and the side sheet), and by the patch list, the cue
sheet and the DMX sheet with plans of their own. The kinds: **`intent`** — desk-resolved, the
template vocabulary: two intents of one property's shape, a curve, an order, parts and an
over-switch go to `POST /programmer/spread`, and one literal per head lands in Local (D3: the
programmer's rows are heads the desk knows better than the browser does — a group's member order,
each head's range, which cells a fixture has, what a colour means on a head with amber). The ends
are intents — a percent, a colour + policy or a `tmpl:` reference, degrees — so a spread from 0 to
full is *0% → 100%* on every head whatever its range, which the byte form never was, and the old
position exclusion went with the byte lerp it objected to. **`raw`** — bytes on a column outside
the vocabulary, Speed alone (D15; `rawValues` in `editor/spreadPlans.ts` is `fanValues` renamed and
kept for that one column, under the same Curve · Order · Parts · Over, with `spreadFractions`
mirroring `fx/SpreadPlan.kt`; if Speed ever stops offering Spread the kind goes with it).
**`address`** — From · Step in visible order, the patch list's walk (`walkAddresses`, which
`lib/patchAddress.ts` shares with the consecutive Set). **`duration`** — From · To with the curve
row where the one-option select was, the cue sheet's fade times (`spreadDurations`;
`FU-SPREAD-DURATION-CURVES`). `fanColours` is deleted: the client never interpolates an intent, and
`spreadIntent.test.ts` pins the import lists of `spreadIntent.ts`, `SpreadPanel` and
`SpreadPopover` — the panel reaches `spreadPlans.ts` only for the raw arm, the popover walks
nothing. The panel is **store-free**, so the three kit sheets mount it without a store; the two
hosts that draw a colour endpoint hand `ColourEditor` in (`colourEditor`), because its leaves and
Recent reach the store.

**The marquee answers the panel's first two questions** (D4). Targets are the marquee's heads —
`spreadTargetsFor` in `rowModel.ts`, beside `templateTargetsFor` and deliberately **not** it: a
group row expanded to its **visible** members, an element row as `{type: 'fixture', key:
element.key}` (the cells contract the desk already takes), a fixture row as itself, where the
template rule folds an element row into its fixture because that route resolves keys against the
patch. It is `expandSelectionToTargets`'s walk read as cue targets (`spreadTargetsOf`), so the
container's per-column targets and the request cannot name different heads. `families` is the pair
the press sends (`usePressFamilies`) — on the programmer, the one surface that bridges; the two
plain lists send the marquee's own families, as their pill shows (`desk` on `SpreadPopover`). The
family segment is drawn **answered and checked, never
hidden** — a row that appears only in some hosts is how two hosts drift — with the marquee's
families live and the rest the heads can take disabled with the reason, and both live when the
marquee spans two (Dimmer + Colour), which is the chooser Fan drew for the same case. The
**Property** row appears wherever the family holds more than one property the heads can take
(`spreadPropertiesOffered`, from the column → `TemplateProperty` map `spreadPropertyForColumn`):
a Colour marquee over RGBW heads offers Colour · White, a Dimmer marquee over heads with a strobe
Level · Strobe. **Over: Heads is the default on both sides** (D5) — Fan expanded a bar into its
cells always; Spread treats a fixture row as one head unless the switch says Cells, offered with
the count where a selected fixture has cells (`spreadCellCount`, the busk `selectedCells` reading)
and **pressable without it**: the operator's Over survives a marquee moving onto heads with no
cells, which spread as Heads (`effectiveSpreadOver`, one rule for both kinds: the desk request and
the raw arm both say `HEADS` where the plan counts no cells), so moving from a pixel bar to a par
and back keeps Cells. It survives an **empty**
selection too, which unmounts the popover and blanks the rail tab: Over is not the form's but a
per-tab store, `lib/spreadOver.ts`, one fact for every host of the panel (`sidePanelMode.ts`'s
reasoning) that `commit` writes through and a mounting panel reads. A panel mounted *beside* the
one that moved it follows the store into its form and **never pushes**, since its own Live may be
on over a selection nobody is touching. The popover host applies the **chosen** plan only where it
has two points (one point is a set), where the docked tab keeps the busk rule and applies one head
at *from*; a template endpoint is drawn and nudged from the template's swatch colour, on both
hosts. **Live** comes to the programmer too (D7), off by default,
through the same `useLivePush` with an equality over the whole request; Apply always sends and
reads *Send again* while Live is on; the release is read from the window. The popover is
**336px** (`contentClassName`; measured in the app on 2026-09-22 at 1100×700 as 336, the board's
number — read after the open animation settles, since a hidden pane freezes it at 0.95 and reads
319), its colour arm 611px tall with the endpoint picker at 218×176 (`ColourEditor` compact, the
busk tab's shipped measurement), and **its body scrolls under the viewport's room with the footer
outside the scroller**, as the docked host keeps it: a desk window between the short-viewport fold
and ~700px would otherwise put Live · Apply below the fold. The bound takes off the surface's
padding and its border, or the box hangs 2px below the viewport (measured).

**The focused-Look-layer arm is one flag on the route** (D6): `SpreadRequest.write`, default true.
Local sends **no `write` key at all** — the REST Json refuses an unknown key, so a desk mid-upgrade
would 400 every Local spread that carried `write: true`; `spreadRequestOf` in `SpreadPanel` is the
one builder and adds the key only when false. A focused Look layer sends `write: false`: the desk
resolves exactly as it does for Local and answers without writing, and `SpreadPopover` lands each
`written[].value` — the head's **literal** in the Look row grammar (`"0".."255"`, `"#rrggbb;w128"`,
`"pan,tilt"`), which the route answers since session 3 in place of the interpolated intent — in the
layer's draft through `LookRowStore.setValue`, which coalesces and PUTs as every layer-scope edit
does (400 ms, 2 s ceiling; flush cadence is stage cadence). A desk that still answers the intent is
told apart by **shape** (`isIntentString` in `SpreadPopover.tsx`: a `pct:` / `deg:` / `dmx:` /
`tmpl:` prefix or a `;policy=` tag — `parseProgrammerValue` alone would take a colour intent as a
colour, since it splits on `;` and tests only the head) and refused with a toast naming the desk
rather than landing an intent in a Look row; in practice such a desk 400s the request first, its
Json refusing the unknown key. Two things the desk does for this arm: a **colour-wheel** head's
resolution is a wheel *slot*, answered as the slot's level under the head's own `colourWheel`,
which a Look row holds and the cook reads back as that slot; and the curve is spread over the heads
that can take the property, found in a first pass, so a par swept up by a geometric Colour marquee
is skipped and consumes no position on it — two RGB heads among eight rows land at *from* and
*to*, not at 0 and ⅐. Output and a focused template layer
refuse as they always did, with the same words, disabled rather than hidden. The response is read
for `skippedFamilies` (toasted in `skippedRowsMessage`'s vocabulary, keyed) and, in layer scope,
for `written[]`; nothing draws a preview from it — the grid is the preview, as the rig is on the
busk view. `useSheetKeyboard` is the kit's window listener and it is **capture-phase**:
`useTransportKeys` toggles the lock on `L` from a bubble listener whether or not the transport is
enabled, and a name typed into a cue cell begins with a character — so the sheet claims the key
first and the transport now stands aside from a key whose default is already prevented.

**The row keys are every sheet's, and they are the one half that listens in the bubble phase.**
Where a sheet selects rows — every kit sheet but the DMX sheet, whose surface passes `useSheet`
`selectsRows: false`, the one place that is said (`tableProps` carries it to `SheetTable`, so the
pointer and the keyboard cannot disagree) — ⌘A selects every row and ↑ / ↓ step the row selection,
Shift extending, as the programmer's list does: `arrowStepTarget` in `listSelectionModel.ts` is the
one rule both step by (a plain step from the anchor, Shift from the range's moving edge, ↓ onto the
first row and ↑ onto the last with nothing selected), pinned in `listSelectionModel.test.ts`. ⌘A
drops a cell marquee through the row door as a row click does; **↑ / ↓ do not any more** — over a
cell selection the arrows are the cells' (below). They are a second listener
in `useSheetKeyboard` (`rowKeys`), **bubble-phase and standing aside from a `defaultPrevented`
key**, because some controls answer an arrow themselves — a Select's list, a menu, a slider — and
claim it on their own handler, which only a bubble listener runs after; from capture, ↓ in an open
menu would move the menu's highlight and the sheet's row. The partition chips and the bar's verbs
claim nothing — they are plain buttons — so the row keys also stand aside from **any focused
control outside the rows** (`isForeignControl` in `sheet/cellEntry.ts`), exempting the rename
button and cell trigger a click inside a row leaves focused, which is where focus sits while ↓
moves the selection on. Only plain and Shift arrows (⌘, Ctrl and ⌥ arrows are the browser's and
the OS's), and — on the kit — not under an open editor. The programmer's list takes the
foreign-control and modifier rules too, and the dialog guard every
sheet listener and the programmer's ask is `keyTargetIsGuarded` beside it — which matches
`alertdialog` as well as `dialog`, since a Radix `AlertDialog` (the batch delete's confirm, the cue
sheet's unlock question, *Discard changes?*) is the former, and reading `dialog` alone let a key
reach the rows behind one. →/← over a *row* selection stay the programmer's alone: they open and
close a group or a multi-head fixture, and no kit sheet has a tree.

**The arrows move a cell selection, on every sheet and the programmer — a spreadsheet's arrows.**
`cellArrowStep` in `sheet/cellSelectionModel.ts` is the one rule, pinned in
`cellSelectionModel.test.ts`; the kit's sheets reach it through `useSheetKeyboard`'s `cellKeys` —
the same bubble listener as `rowKeys`, under every one of its guards — and the programmer's
container calls it from its own listener, as it calls `arrowStepTarget`. **Which of the two hears
an arrow**: the cells' whenever cells are selected, or the sheet has no row axis; otherwise ↑ / ↓
are the rows' and ← / → nobody's (on the programmer, the tree's). So a row sheet's first arrow still
selects a row, and the arrows walk cells once a click or a marquee has put the sheet in its cell
shape. The rule's terms are `arrowStepTarget`'s: **a plain arrow moves from the anchor** one
cell and collapses the selection onto it; **Shift moves the head** and keeps the anchor, so Shift
back shrinks towards the anchor and then grows past it; with no cursor on the grid ↓ / → land on
the first cell and ↑ / ← on the last; at an edge the key is still claimed, so the page does not
scroll instead. The grid is the selectable rows × the columns that hold cells — the kit derives it
from `rows` and `columns`, skipping dividers and read-out columns (no `data-column-header`, so no
marquee reaches them either), and the programmer from its selectable rows and visible columns.
**A blank cell is stepped past** (`CellGrid.takes`): a column the row has nothing to set in — a
kit cell whose `value` is undefined (`takesValue`), a programmer column the row resolves nothing
for (`buildRowCells`) — is drawn with no `data-cell` and no ring, so landing there would select
something invisible that nothing could scroll to. ↓ in Colour skips the dimmer-only pars and lands
on the next head that has colour; with none before the edge the arrow stays. A Shift rectangle
still covers the blanks between its corners, as the marquee's does. **Shift grows a rectangle on
every sheet, and never wraps** — Shift+↓ ×4 then Shift+→ ×3 from one cell is five rows by four
columns. **A surface says only its `cellFlow`** (a `useSheet` option), and it governs a *plain*
← / → alone: **`grid`**, the default, stops at a row's ends; **`linear`** wraps from a row's end
onto the next — the DMX sheet, whose reading order is address order in every arm, so the kit needs
no notion of an address. ↑ / ↓ are one row in both, stopping at the first and last. Shift on a
`linear` sheet extended a run of addresses until the desk tried it (2026-09-24): a block starting
mid-row took the whole of every row in between, when the gesture on screen was a rectangle.

**The anchor rides in the cell selection** (`useCellSelection`'s `cursor`, beside the keys in one
state object so they cannot describe two different selections): the keys' door, `place`, sets it,
and every pointer change and clear drops it. With none stored, a selection's cursor is derived —
its first cell in reading order as the anchor and its last as the head (`cursorOfSelection`) — so a
marquee dragged either way is anchored at its top-left. A step asks the table to **reveal** the
head (`revealCell` on `SheetTable` and `FixturesTable`, through `sheet/revealCell.ts`): the least
scroll that shows the cell whole clear of the sticky header row and first column, and nothing at
all while it is already on screen, where a row key's `scrollToRowId` centres its row. A cell whose
row is virtualised away is scrolled to by the virtualiser (`align: 'auto'`) and revealed once the
row renders, asked again each frame for up to six (`REVEAL_FRAMES`) — the virtualiser renders from
its own scroll listener, and one frame is not a promise. The sticky column is read as the header's
first child, never `[data-grid-name-header]`, which only a sheet that selects rows hangs. **⌘A on a sheet with no row axis selects every cell** — the DMX sheet's whole universe; on
every other sheet ⌘A is still the rows'. Enter, a typed character and ⌫ then act on whatever the
arrows left selected, so *select, arrow, type, Enter* is the keyboard flow.

Three surface rules, each pinned by its test:

- **Patch list** (`PatchSheet.test.tsx`, `lib/patchAddress.test.ts`): **Set over N addresses lands
  them consecutively by footprint from the typed one**, in visible-row order, each head on its own
  universe (the PUT cannot move a head across universes); Spread on Address is From + Step. An
  overlap is a destructive ring on the Address cell with the other head on its title and a legend
  line under the sheet; the address editor **names the collision before Apply and refuses it**.
  That refusal is the only overlap check there is — **the patch PUT has none today** (only the
  POST checks), and there is no bulk atomic route, so a batch is N PUTs that can half-apply on a
  network failure; both are lighting7 work and Chris's call. Clear is refused on Address, Fixture,
  Key and Stage; offered on Mount, Lantern, Angle and Gel. It is a routed page again (§List shell); row B is
  universe toggle · filter · spacer · Groups · Columns · + Patch, and the universe chips carry a
  fill bar on a 40px chrome row of their own.
  **A double click on a fixture's name opens a rename popover — the kit's `TextCell`, mounted with
  `firstColumnCellProps`** (`sheetModel.ts`), so the first column edits in the same popover, in the
  same three forms, as every value cell; the single click underneath still selects the row and a
  press still starts the row marquee. It was an `InlineEditField`, the one editor on a sheet that was
  not a popover. The pencil beside it — the kit's `firstColumn.onOpen` since the library sheets —
  still opens the full editor, and so does ⏎ with that one row selected (§Library sheets).
  **Set over N *keys* fans one typed key over them**, `lib/fixtureKey.ts`: one head takes it as it
  is, several count up from it, continuing the number, the separator and the zero padding the typed
  key already carries — the vocabulary `AddFixtureSheet` mints keys in, read back off what was typed
  rather than imposed. Two refusals are named before Apply: a key another head holds, and a set that
  cannot be *ordered*. The ordering exists because this PUT, unlike the address one, checks
  uniqueness on **every call** — so `par-1 … par-4` re-keyed from `par-2` walks *down*, and the
  writes go out one at a time and stop on the first failure, saying how far they got. A true swap
  (the visible order is not the key order) is refused rather than broken open by parking a head on a
  synthetic key: a PUT failing after a park leaves a fixture called `par-2-tmp1` on a live rig with
  nothing to put it back. **One batch at a time**, too: `write` has to answer synchronously, so the
  loop runs detached and the editor closes over it — a second batch planned in that window would be
  planned against an `allPatches` the first has not landed in, and two plans each assuming they are
  the only writer is how one walks onto a key the other is mid-way through vacating. **`key` is also outside `METADATA_ONLY_PUT_KEYS`**, so each of those PUTs
  rebuilds the fixture registry and broadcasts — the same cost the address batch has always had, and
  the same answer: a bulk route is lighting7 work and Chris's call.
  **The Head column is the operator's head number** — a ChamSys head number carried across a
  migration (lighting7 `docs/fixtures-engineering.md` §"Head numbers"), unique in the project,
  optional. **Set over N counts up from the typed number** in visible-row order, and — unlike Key —
  goes out as **one atomic request** on the bulk placement route (`useSetHeadNumbersMutation`), which
  the desk judges against the batch's *final* state, so a renumber that swaps two heads needs no write
  ordering and cannot half-apply. A number held by a head outside the batch is named before Apply
  (`lib/headNumber.ts`); a number two heads share anyway (a sync merge imports as it stands) is ringed
  like an overlapping address, with its own legend line. Clear unnumbers; there is no Spread. The add
  and edit forms carry a *Head number* field, and the add sheet advances it by one per fixture patched.
  It is **not** on `GET /fixtures` yet, so nothing outside the patch views can show it.
  **An unnumbered Head opens on the next free number** (`nextHeadNumber`: one past the highest, so
  a run from it always lands clear), selected and noted as a suggestion — `TextCell`'s `emptySeed`
  — where it used to open empty under a `1` placeholder that read as the value about to be applied.
  **The Gel cell is the patch editor's gel picker** (`GelCell` over `GelPicker`, which
  `GelPickerField` also mounts): search, brand chips, swatches, ↑/↓ and Enter — not a typed code.
  **Every `OptionCell` with two or more options draws its filter**, because the filter is the
  cell's keyboard: Stage and Role could not be set by typing while the threshold was three.
  **A batch landing is drawn one head to a line** — `EditorReadout`'s `lines` arm, which was
  `cells/LandingLines.tsx` until the editor kit — shared by the address editor and the Key column,
  rather than joined with `·` into a paragraph read at the worst moment.
- **DMX sheet** (`DmxSheet.test.tsx`): `/projects/:id/channels/:universe/table`, sticky key
  `channels.view`, a grid of 56px cells — the address with the raw 0–255 value in the top-right
  corner, then the fixture's name, then the channel's use, on **every** patched cell alike (no
  first-cell emphasis), a 2px run edge where each fixture's footprint begins, and ownership rings
  read through the desk's own keys for the address (below). No row axis: the row head
  hangs no `data-grid-name-header`, so every press is a cell press, the arrows always walk the
  cells — `cellFlow: 'linear'`, so a plain ← / → wraps `016 → 017`, ↑ / ↓ are ± the row width in
  whichever arm is showing, the ends are `001` and `512`, and Shift grows a rectangle (↓ then →
  from `005` at sixteen wide is `005`, `006`, `021`, `022`) — and ⌘A selects all 512, which ⌫ would then zero. Spread is one `raw` plan
  over every selected cell in address order (an address has no intent for the desk to resolve). Writes are `channels.update` per address; Clear is 0; Park /
  Unpark act on the selection; the desk being offline is the read-only scope; Unpark All keeps its
  confirm and there is no Edit/Done toggle. Raw 0–255 only, no level bar — left for later.

  **A cell's face paints no background, and that is what keeps its ring honest.** The ownership
  ring is an inset `box-shadow` on a wrapper around the face, and an element's own shadow paints
  *beneath* its children — so a background on the face covers the ring and its fill. The alternating
  footprint tint that sat there did exactly that: an owned address on a tinted run read as a fainter
  owner than the same state beside it. The footprint is the run edge now, drawn in the 2px padding
  outside both the ring and the selection overlay, and as a sibling of the ownership wrapper so the
  baseline dim does not fade it. `DmxSheet.test.tsx` pins that nothing under the ring carries a
  `bg-` class.

  **A long name or use is cut at its beginning, and fades rather than taking an ellipsis** — the
  end is what tells two apart (a fixture's number, a head's colour). The line is laid out right-to-left so it
  overflows off its left edge, aligned left, around an LTR `<bdi>`; `text-overflow` cuts at a
  character, and one landing on a space drew `… Lightbar`, so the edge is a mask instead, drawn only
  while `data-overflow` says the line overflows. That flag is written to the DOM by one shared
  `ResizeObserver` watching each line's box *and* its text (`inline-block`, so it has a box), never
  through state — a state per line would re-render ~400 cells on a resize. The cell's hover carries
  all of it (`025 · LED Lightbar 12 Pixel · Strobe — Programmer`). Fixture names, not keys: on a
  desk that minted its keys from names they are no shorter.

  **A row is 16 addresses wide on a desk, 8 on a tablet and 4 on a phone** — `DMX_ROW_WIDTHS`, the
  widest arm whose `48 + 64 × n` the container can draw without scrolling sideways, measured by
  `hooks/useContainerBand.ts`. It halves rather than taking any n so that a fixture's footprint
  still reads across a row and a row's base stays a round address (`001`, `009`, `017`). A
  container query cannot answer it: the count is JavaScript, not a class. **Changing arms drops the
  cell selection, during the render that changes it** — 16, 8 and 4 are multiples, so every wide
  row id exists narrow too, and the same `rowId · col` pair would silently name a different
  address; done in an effect it left one painted frame where `selectedChannels` named an address
  nothing showed as selected.

  **A cell's ring asks the desk which properties drive its address, and never works it out.**
  `channelMappingState` carries `properties` on every entry — every `(target, property)` key whose
  channels include the address, from `PropertyChannelWriter.propertyKeysByChannel`, which is
  `channelsFor` inverted — and the cell aggregates the programmer's state over all of them, plus the
  programmer's channel **sideband** at that address (`ProgrammerState.channels`, one subscription
  for the sheet) — a sideband slot promotes the address over a cue or baseline, never over park or
  an `effect` verdict, because a programmer-band effect outranks the programmer and the desk's
  per-key answer has already weighed any slot it can attribute. The desk builds the frame once per
  `Fixtures.structureVersion`, from one read of the register, and shares it across sockets. The sheet used to build that map itself from the fixture descriptors and drifted
  from the desk three ways at once: a bundled white/amber/UV was filed under `rgbColour` while
  `updateChannel` lifts a write on one to its **own slider**, so a value set here never rang; an
  element's white was missing entirely (its colour descriptor names none); and a pan axis read only
  as `position` where the desk lifts it to `pan`. Two keys per address is normal, so **the address
  takes the strongest source and is never drawn dashed** — the wire carries one byte, and the
  programmer grid's "these heads disagree" means nothing for one channel. An address the desk names
  nothing for reads **baseline** (dimmed) like any idle one; it used to read *nothing* and drew
  brighter than its patched neighbours. A multi-head fixture's heads ring through their own
  element keys: the desk's covering lookup (`resolveChannelCoveringKey`) reaches elements, so a
  write on a head lifts to that head's property (a red to its `rgbColour`, its white to its own
  `white`) and records into a cue as a cell-target row. The sheet still reads the sideband for the
  addresses the desk *cannot* lift — nothing covers them — which ring through no key. A desk that predates
  the field sends no `properties` and the sheet reads every patched address as baseline until it
  restarts.

  Its cells set **`SheetColumn.gutter: false`**: they draw no corner glyph, and the kit's 18px marks
  gutter made a cell's own ownership ring a box 18px narrower than the selection overlay drawn over
  it — the two lines an operator reads a channel by, disagreeing on three of four edges. Without it
  the wrapper is padded 2px all round and `cellSelectionClass` insets the overlay to match.

  **The route is full height, not a `Card` in a scrolling page** (`routes/ChannelsTable.tsx`): as a
  card the page scrolled *and* the table scrolled inside a `calc(100vh - 14rem)` cap, two bars for
  one list, and a 393px-tall landscape phone got 169px of grid. Its breadcrumb header is 48px like
  `StackDetail`'s — a header, not one of the 40px chrome rows (the `ShowHeader` was 48 too until
  the busk-chrome plan's session B made it a 40px chrome row; this one and `StackDetail`'s stay).
- **Cue sheet** (`CueSheet.test.tsx`): `/projects/:id/show/stacks/:stackId/table`, sticky key
  `show.view`, the switcher on the `StackDetail` header. Name · Fade · Curve · Follow · Notes are
  cells, and the cue number is a `TextCell` on the Cue column (`firstColumnCellProps`) **opened by a
  double click** into the same popover as every value cell beside it (a single click there selects
  the row — it was a single click that had to swallow the press, which made the Cue column the one
  column where a click meant something different). **Locked, the number is plain text rather than a
  disabled trigger**, because a click on it must bubble to the column to arm the cue and a browser
  dispatches no click for a press inside a disabled button. Layers · FX are read-outs that open the card on the cards view
  (with `CARDS_LINK_STATE`, so the sticky does not bounce it
  back; a peek is not a change of view); **Book opens the Prompt Book** at that cue instead, through
  `?cue=`, which is that page's arrival contract and the mirror of the one it mints for Show — it is
  the one read-out that names a place in another document. **No Hooks column**: `CueStackCueEntry`
  carries no trigger count, and adding one is a backend field.

  **A cell that cannot be selected is blank**, not an em-dash: the em-dash is the mark an *empty but
  settable* cell wears (Follow, Notes), and wearing it on a read-out and on a snap cue's Curve made
  half the sheet's dashes look editable.

  **The lock is the sheet's read-only scope** the way
  Output is the programmer's — locked, every value cell is inert in all four places, the marquee
  still works, and a click on the Cue column arms
  the cue as next; unlocked, cells edit under the amber wash. **A refused edit asks to unlock**
  rather than doing nothing: Set · Clear · Spread stay live and open a confirm, and so does ⏎ — through
  `useSheetKeyboard`'s `onRefused`, which reports the refused gesture **and the key** and lets the
  *surface* say whether to claim it. The kit takes no view on which keys are safe: that depends on
  what else the surface has bound, and here it is Enter alone, because while a show is locked
  `useTransportKeys` owns Backspace (BACK) and a typed `l` (the lock toggle, the keyboard's own way
  back) and both stand aside on `defaultPrevented`. That reasoning lives in `CueSheet`, beside the
  hook it is about, and `CueSheet.test.tsx` pins both keys as untouched. The offer is withheld where
  the lock is not the operator's to lift (`canEdit` false), where the disabled verbs and their
  reason are the honest answer. Writes are one PATCH per cue carrying
  the field, the cards' own auto-saving contract. The transport is otherwise untouched:
  `useTransportKeys` is enabled exactly while locked, as it always was, and `canOperate` is never
  handed `locked`.
  The sheet consumes `?cue=` by selecting the addressed cue's row and scrolling to it — the
  external contract holds on both views.

  **Unlocked, a grip in the Cue column reorders cues and separators** — `SheetTable`'s `rowDrag`,
  on the cards view's own `reorderCues`, so one gesture and one mutation serve both. The
  `DndContext` is mounted only where a surface asks for it and the rows are turned off through
  dnd-kit's `disabled` rather than by unmounting it (`StackDetail` learned that one); the grip
  stops its own `pointerdown` propagating, or starting a drag would also start a marquee.

  **A sortable row is positioned with `top`, never the virtualiser's `translateY`.** dnd-kit
  measures droppables with transforms discounted, so rows positioned only by a transform all measure
  at the container's origin: every centre-distance ties, `closestCenter`'s stable sort returns the
  rows in DOM order on every frame, and the drop lands on the row the drag began on. It does not
  fail cleanly — dragging **up** still works, because for an upward drag DOM order and distance order
  agree — which is how it survived a first browser check. `CueSheet.test.tsx` pins the `top`; the
  sheets with no row drag keep `translateY`, having nothing else competing for `transform`.

**Cards · List and Cards · Table are one switcher and one storage vocabulary.** Fixtures and
Groups keep the word *List*; Channels and Show say *Table* (their second view is a table and their
first genuinely is cards). The stored value is `'list'` for all four keys, so `getStoredCardsListView`
answers every pair and a rename can never reset a desk's remembered view; `stickyRedirectsToList`
is the one redirect decision, called by all four cards routes (`ViewSwitcher.test.ts`).

### List shell

**Every list view is one column, stated once.** Fixtures › List, Groups › List, the programmer,
Show › Table, Channels › Table and the patch list share one anatomy — a 48px header row (the
programmer's is the `ShowHeader`, a 40px chrome row since the busk-chrome plan's session B), any
number of 40px chrome rows, the 40px `SelectionBar`, the sheet, a 22px footer — and the whole of it
is two files: `components/sheet/sheetFrame.ts` holds the class strings (`PAGE_HEADER_CLASS`,
`CHROME_ROW_CLASS`, `SHEET_SCROLLER_CLASS`, the sheet header row and cell, the sticky cell, the row,
the divider, `SHEET_FOOTER_CLASS`), and `components/sheet/SheetPage.tsx` the thin components over
them (`SheetPage`, `.Header`, `.Row`, `.Footer`, `.Empty`) plus the one `LegendSwatch`. `SheetTable`
and `FixturesTable` draw their frame from the constants; every surface mounts the components and
never writes the classes. A surface that wants to differ says so at the import, in one file. The
design record is `../docs/plans/list-shell-design/` (`Kit.dc.html`'s "Where the code goes"
is the file-by-file map; the five open calls are made on `Spec` under "Called — 2026-09-15").

The rules the two files encode, each of which was a measured inconsistency before them: a list is a
full-height column filling `<main>` — **no `Card`, no page scroll, the sheet is the only scroller**;
a 12px gutter on every row; header 48, every other chrome row 40 with 32px controls, footer 22, sheet
header 30 and rows 36 (56 on the DMX sheet); **three grounds** — the page (`<main>`'s `bg-muted/40`,
which every chrome row sits on with no ground of its own), the sheet (`bg-background` on the header
row, the sticky column and the body alike, so a name column can never read darker than its cells)
and a divider row (`bg-muted/30`); and **one line between neighbours** — a chrome row owns its
`border-b`, the sheet owns no top line, the footer owns its `border-t`. **Two named exceptions, both
by decision:** the programmer's row A keeps its `bg-card/50` wash, and `ShowHeader`'s border stays
transparent until the unlocked wash colours it.

Three things the shell changed that read as bugs if you do not know they are decisions. **The two
plain lists have row C and a footer now** — reserved (`Nothing selected`) when nothing is, the
count in the footer — so `SelectionToolbar` draws no count of its own, and `FixturesListContainer`
has one arm: no `fill`, no `space-y-3` wrapper, and its default toolbar is the shell's row plus
the programmer's own `SelectionBar` with no `projectId`, which is what draws it without the
template strip — one component, so the two bars cannot count a marquee two ways. The bar's own
`@container` sits above it on those two routes as everywhere. **The strip folds — the verbs' words
at 1100 and Locate · Highlight at 800 — apply only on a bar that carries a strip**: the kit's
`SelectionBar` marks itself `group/bar` + `data-strip` (from `foldForStrip`, defaulting to "there
is a strip"), and `WORD_CLASS` / `STRIP_MID_FOLDED_CLASS` in `toolbarFolds.ts` are gated on it.
Those folds exist to give the template strip room; on the plain lists, the DMX sheet, the patch
list and the cue sheet nothing needs it, and a 1100 fold there emptied every word at once on an
ordinary 1160px window.
`SheetPage.Header` and `.Footer` put their `@container` on an unpadded wrapper, never on the `px-3`
row: a size query measures the content box, and a container on the row would fire every threshold
24px early. **Loading and not-found render inside the same `SheetPage`** — an empty header row over
`SheetPage.Empty` — so a list keeps its shape from loading to loaded. And **the patch list is a
route again**, `/projects/:id/patches`, with the chips as a 40px chrome row of 28px chips above row
B: it left Project Settings because a tab body under a settings heading was the one list that could
not have the header row and the gutter. `/settings/patches` redirects there, `?action=new` intact.

### Library sheets

**The five libraries — Scripts, FX Library, Looks, Templates, Speed Masters — are sheets on the
sheet kit, each on the route it always had** (`../docs/plans/completed/library-sheets-plan.md`;
the boards are `../docs/plans/library-sheets-design/`, where the plan won on behaviour and the
boards on layout and copy). Four sessions built it: the kit's library half with **Speed Masters** on
it (1), **Looks and Templates** (2), a generic value template's **Value** edited in the cell (3), and
**Scripts and the FX Library** (4). The rules, which every one of the five keeps:

- **The sheet replaces the list, on the same route** (D1). No Cards · Table switcher and no sticky
  view key: a second view has to earn one. The record editors stay — they are what a row *opens*.
- **The whole list shell** (D2): the 48px header with the breadcrumbs, the **library row**, the
  `SelectionBar`, the sheet, the footer. The library row is row B for a library — filter ·
  partition chips · spacer · the create verb, which leaves the page header for it.
- **Chips filter, dividers group** (D3). Where a library partitions exactly (script type, effect
  category, template family) the partition is a chip set with counts, and under *All* the sheet is
  grouped by divider rows in the partition's declared order. Looks and Speed Masters partition
  nothing and draw no chips. The partition is a **view, never a route**: a `?param=` plus a
  remembered value, both the route's.
- **The name column is the sheet's `firstColumn`, never a `SheetColumn`** (D4), so it is never in
  the marquee. A double click renames it in `TextCell`'s popover — one row at a time by
  construction — and the pencil opens the record, as does **⏎ with exactly one row selected and no
  cells**. That ⏎ is the kit's, so the patch list has it too.
- **What a cell edits** (D5): name and notes everywhere, plus the few values a library holds — a
  template's Value, Fade and effect Master; a master's live BPM, Start, Follows, Ratio and Usage.
  What a Look holds stays recorded and changed by Include. Every other column is a read-out.
- **Row verbs on the bar** (D11): after Set · Clear · Spread come Include, Pick up, Duplicate,
  Copy to…, Fork, Compile, Run, Delete, then Deselect. A verb over one record is disabled over
  several with the reason. The per-row `…` menus go.
- **Read-only by row and by scope** (D12). A row with nothing to set in a column has
  `value: undefined` there; the marquee is geometric and still covers the cell, so **the kit drops
  it before `write`** and names it on the editor's read-out. Such a cell is **blank**, as the
  programmer draws a column a row resolves nothing for — no `display`, so a click there clears the
  selection — unless what it reads out is worth reading (a follower's derived BPM). The boards drew a
  `·` there and were corrected to match. **Another project's library** is the
  sheet's read-only scope, the cue lock's shape: selection works, every value verb is disabled with
  the reason, *Copy to…* is the one live verb (`libraryPermission`). **Speed Masters is half
  exempt**: its REST routes are `withProject`, so its stored fields stay editable from any project,
  but **BPM and TAP are read-only off the current project** — they write the running show's
  clocks, and master 1 is written as a null uuid, so before the sheet a TAP on another project's
  master 1 tapped the live one.
- **One delete for a batch** (D13). Each record is sent plain, the in-use refusals are gathered and
  asked about **once**; *Delete anyway* forces only those, *Keep them* leaves them selected.
  Records that may never be deleted (master 1, built-in effects) are skipped by name before
  anything is sent.
- **The sheets report their own failures** (D14). Their write endpoints stay in
  `SILENT_ENDPOINTS` (the dialogs they replaced rendered refusals inline), so a refused cell write is
  toasted by the sheet **by code, keyed per column** — a batch replaces rather than stacks — and
  the middleware does not say it a second time, generically.

**What the kit gained**, each generic:

- **`SheetTable`'s `firstColumn.onOpen(row)`** draws the pencil — on hover, focus and **while the
  row is selected**, so a touch screen reaches it by tapping the row — and `useSheet`'s
  `onOpenRow` is the same callback on ⏎. Pass both. The patch list's hand-rolled pencil went.
- **`useSheetKeyboard`'s ⏎-opens-row arm**, fed a row count and the callback by `useSheet`. It
  exempts **the selected row's own first-column trigger** from the focused-control guard
  (`firstColumnOwnsKeyTarget`, reading `data-first-column` and the row's `data-state`): clicking a
  name focuses its `TextCell` `<button>`, and without the exemption ⏎ would press that instead. The
  pencil (`data-row-open`) is not exempt — ⏎ on it is its own press. The count is the *visible*
  selected rows, so a selected id whose row has gone does not make one row read as two.
- **Skipped rows in `useSheet`**: `takesValue` / `splitBatch` in `sheetModel.ts`, applied in the
  commit, the row-selection path, Clear and Spread; `batchCount` counts only the rows that take the
  value, and `SheetCellProps.skipped` carries the sentence every kit cell draws on its
  `EditorReadout` — the column's `skipNote` where it knows *why* (*M2 and M4 follow M1*), else the
  rows by the sheet's `rowName`. It changed what `write` receives on the existing sheets too: a snap
  cue's Curve and a head without a beam angle or gel no longer reach their writers.
- **`LibraryRow.tsx`** (the library row) and **`PartitionChips`**, `LookFamilyFilterBar`
  generalised (it is gone) and still controlled, folding to a select below its `fold` of its
  **own** `@container` — so it works inside `TemplatePicker`'s portalled popover, which has no row.
  **Two measured folds, chosen per chip set**: **400**, the default — the template family's five
  chips with counts are 361–367px, and the first cut's 600 folded them on the 1180×820 frame where
  the row leaves them ~430 — and **560** for the FX Library (six categories, 483px; 513 with a
  three-digit *All*) and Scripts (all six types in their short labels, 542). Session 2 wrote "one
  number for every library, re-measured"; measured in session 4, one number moved to 560 would fold
  the templates on an iPad where they fit, so the fold became a choice of two literal class pairs
  (`FOLD_CLASSES` — Tailwind never emits a class built from `${}`).
- **`usePartitionView.ts`** (session 4) — the partition as a view, never a route: a `?param=` plus
  a remembered value, the first render reading the stored one and never writing it back on arrival,
  a `?param=` arriving winning and being remembered, a change mirrored into the URL with `replace`.
  The Scripts (`?type=`, `scripts.type`) and FX Library (`?category=`, `fxLibrary.category`) routes
  take it; `routes/Templates.tsx` still owns `?family=` by hand, as it did first.
- **`groupRows.ts`** interleaves the dividers; the caller mints the divider row, and a row in an
  undeclared partition is never dropped.
- **`cells/NumberCell.tsx`** — `TextCell`'s shape with `EditorField` inside: a unit, a range that is
  **refused rather than clamped**, commit on Enter and Apply rather than per keystroke (a BPM typed
  live would retune a clock through `1`, `12`, `128`).
- **`ReadOutButton.tsx`** — `CueSheet`'s private `ReadOut`, lifted: a read-out whose display is a
  press. TAP is one.
- **`LibraryNameColumn.tsx`** — `libraryNameColumn()`, the `firstColumn` every library spreads in:
  the rename `TextCell`, a prefix (`M2`), badges, the pencil. A render helper, not a column.
- **`useBatchDelete.ts` + `BatchDeleteDialog.tsx`** — D13, answering per entity
  `ok | inUse(summary) | refused(reason)`. The plain pass is sequential, since every delete
  invalidates the list.
- **`libraryScope.ts`** — `libraryPermission(isCurrentProject, projectName)`: the permission, the
  bar's words and the reason.
- **`reportSheetWriteFailure.ts`** — D14's toast, `rigWriteFailureMessage`'s pattern.
- **`LibraryVerb.tsx`** (session 2) — a row verb on the bar, its disabled title the *reason*, and
  `oneRecordReason` for Include and Pick up over several rows. Its word folds below **1100px of
  bar** (`ROW_VERB_WORD_CLASS` in `toolbarFolds.ts`), ungated by a strip and earlier than Set and
  Clear: measured on the template sheet, Set · Clear · Spread, four row verbs and Deselect are
  769px worded — ~1090 with the counts and hints — and the 1180×820 frame gives the bar 940, so
  Delete and Deselect were clipped.
- **`CopyToProjectSheet.tsx`** (session 2) — *Copy to…* over a selection: one request per record,
  the target defaulting to the running project when the library shown is another's, a new name
  offered only for one record, and a record that fails named in the sheet's alert and **kept** for
  a retry while the ones that landed drop out. It replaced `CopyLookDialog`.
- **`useDuplicateBatch.ts`** and **`LibraryReadOuts.tsx`** (session 2) — Duplicate over a selection,
  one copy-route call per record, a refusal toasted once under the sheet's key; and `CountReadOut`
  (a faint em-dash at zero) and `ReadOnlyNote` (the scope's lock and reason on the bar).
- **`lib/duplicateName.ts`** (session 2) — Duplicate's `(Copy)`, `(Copy 2)` …, against the
  library's names **and the ones the batch has already minted** (it adds to the set it is given),
  since the list has not refetched between two copies of one batch.

**The Speed Masters sheet** (`components/speedMasters/SpeedMasterSheet.tsx`, on
`routes/SpeedMasters.tsx`) is beat · BPM · Tap · Start · Follows · Ratio · Usage · Used by · Notes.
Four things about it are easy to get wrong:

- **BPM is the live tempo** (`speedMasters.setBpm`, the null uuid for master 1), **Start the stored
  row** (`PUT {bpm}`). They are two columns because they are two routes, but **on the running
  project the desk couples them both ways**: a live change is written back to the row after 750 ms
  (`Show.kt`'s debounce), and a PUT of a stored tempo retunes the running clock. So a Set on BPM
  moves Start a moment later, and a Set on Start on the current project is a live tempo write.
- **Follows carries the origin's leader to every selected row**, so its `write` also drops a row
  that may not follow it — itself or one of its own followers, which the desk would refuse as
  `SPEED_MASTER_FOLLOW_CYCLE` — and says so in a toast. A new link starts at
  `DEFAULT_FOLLOW_RATIO`, a re-pointed one keeps its ratio, and master 1's two spellings (its uuid,
  and the null that means it) are normalised before comparing, as the detail sheet's
  `canonicalTarget` does.
- **Usage over more than one row is refused before anything is sent** (unless None): a usage is
  one master's per project, and the desk would accept the first and 409 the rest.
- **Off the current project the live bank is not read at all** — no beat, the stored tempo shown —
  because a cloned project's masters can share uuids with the running ones.

**The Looks sheet** (`components/looks/LookSheet.tsx`) is Families · Preview · Contents · Notes · Cue
layers · Busk pages; **the Templates sheet** (`components/templates/TemplateSheet.tsx`) is Holds ·
Value · Fade · Master · Notes · Layers · Pages · Pressed, grouped by the route under family dividers
(§Looks, templates and layers). Session 2's facts:

- **A record with busk pads is held for the batch delete's question, unsent.** Pads are a hint the
  desk does not refuse on — `layerCount` alone gates the delete — but they go silently with the
  record, and the confirm the sheets replaced (*"It has pads on 2 busk pages; those go with it"*)
  was the only warning an operator ever got. So `useLookDelete` and `useTemplateDelete` answer
  `inUse` for one on the plain pass **without sending**, and the dialog lists it beside the desk's
  refusals, as the Kit board draws *Ballyhoo · on 3 busk pages*. **Its *Delete anyway* is sent
  plain, never forced** — pads were the only use the dialog could name, and the desk skips its
  usage check on `force`, so forcing would take a cue layer, an effect reference or a running layer
  with it unsaid. If the desk then answers in use, the dialog reopens with its answer
  (`useBatchDelete`'s force pass reopens on an in-use outcome, handing `remove` the record's earlier
  answer as `previous`), and only that answer's *Delete anyway* forces.
  Both hooks are also the detail sheets' deletes — `LookDetailSheet` and `TemplateEditor`'s
  Delete — so a single delete asks the same question in the same dialog.
- **The template in-use line names all three uses the desk counts**, because each fails
  differently under force: cue layers go with it, effect parameters naming it (`fxReferenceCount`)
  are not rewritten and an unresolved colour runs **white**, and programmer layers applying it now
  (`runningCount`) stop at once.
- **Another project's library is read-only on the server too.** Both libraries' PUT and DELETE are
  `withCurrentProject` (a DELETE there answers *Cannot modify - not current project*), only the copy
  route is `withProject` on its source — so off the current project the rename, Notes, Fade and
  Master are inert, **no row opens** (the detail sheet and the editor edit exactly what the scope
  refuses), Pick up is not drawn, and Include, Duplicate and Delete are disabled with the reason.
  It also means a test record copied *into* another project cannot be deleted from here.
- **Fade is seconds, on value templates only.** A null fade — *default (none)*, which a press
  applies at 0 — draws the em-dash and opens its editor at 0; Clear writes null with
  `fadeDurationMsPresent: true`; Spread is the kit's `duration` plan. Set and Spread compare a null
  fade **as 0**, so Enter on an untouched editor does not turn *default* into an explicit 0s.
  An effect template's Fade is `undefined`: blank, and named when a marquee sweeps it up.
- **Master is one field of `PUT {effect}`, and which field is the effect's timing.**
  `speedMasterUuid` on a `BEAT` effect, `rateSpeedMasterUuid` on a `WALL_CLOCK` one; the body is
  the whole effect with that field changed, since the PUT replaces the effect half — and it
  **recreates the effect**, so a running instance restarts (the cell's title says so). A beat
  effect's null is master 1, compared canonically and written back as null — and it reads as master 1
  even before the bank has loaded (a `master-1` stand-in, never on the wire), or a marquee over it
  would skip a row that takes a master; a wall-clock effect's null is **unscaled**, which it alone
  offers. An effect whose type no longer resolves has no
  `timingSource`, so which field to write is unknown: its Master is blank and skipped.
- **Value reads `rows ?? []`** and never resolves (`templateIntent.ts`'s rule): the swatch and
  `describeTemplateRows` for a generic value, *n heads · per fixture*, or the effect and its speed.

**Session 3 put a generic value template's Value in the cell** (D6) — `TemplateValueCell`, on the
Templates sheet's Value column. What it is and what it learned:

- **It mounts the editor's own family controls, lifted, not the editor kit's editors.** `FamilyControls`
  and its four (`ColourControl`, `PercentControl`, `PositionControl`, `BeamControls`) moved into
  `components/templates/familyControls/` **with the rows half** — `seedValues`, `withIntent`,
  `colourPolicyLocked`, `effectiveColourPolicy`, `templateRowsFromValues` (the editor's `rows` memo)
  and `templateRowsKey` (its dirty check), all in `templateRows.ts` — and `TemplateEditor` imports
  them back, so a template's value has one control and one grammar in two hosts. (`valueChanges`,
  `applyValueChanges` and `templateValuesEmpty` joined them for the cell.) The rgbonly rule is
  the builder's, applied to the saved rows (§Looks, templates and layers); a host that built rows any
  other way could send the pair the write boundary refuses by name. The hex field gained
  `type="text"` and an `aria-label` (*Hex colour*) in the move — `useEditorKeyboard`'s selector
  names the type, so without it Enter in the field did nothing in the cell.
- **Generic value templates only.** A per-fixture template's values were recorded per head and an
  effect template holds no value, so both keep the read-out — `value: undefined`, blank of any
  editor, named when a marquee sweeps them up (*Pulse runs an effect — open to change · skipped*) —
  and change through the pencil. So does a template whose `family` is null (rows naming no known
  property): there is no control to mount.
- **The family rule lives in `write`, not in `kind`.** The column has no `kind`, so it takes commits
  from its own editor alone; the commit is `{family, values}` — the origin's family travels with it
  — and `write` drops every row of another family. The **editor names them**, since the kit's skip
  read-out knows only `value: undefined`: the column hands the cell a `landing()` over the batch, and
  the label line counts the origin's family (*2 templates*, not the marquee's 4) beside *Amber is
  Colour · skipped*.
- **It commits on Enter and Apply, never as the controls move.** A rows PUT republishes every cue
  layering the template, so a colour drag written per frame would be a republish per frame — the
  `NumberCell` / `TextCell` rule, not the level editor's. Three families have no text field, so on a
  desk the **first slider takes focus** (Radix's default is the first button, a *Clear*, which made
  Enter a deletion) and Enter on a focused slider applies.
- **A commit carries what changed, never the origin's draft.** The PUT replaces a template's rows
  whole, so the first cut — the origin's rows written to every same-family template in the marquee —
  deleted a sibling's strobe, white or other beam role on an Enter that changed nothing (found in
  review, the colour editor's unstated-emitter bug again). The cell commits `valueChanges(seed,
  draft)` — each property set or removed — and `write` applies it over **each template's own**
  `seedValues` (`applyValueChanges`) before the builder, so a property the operator did not touch
  stays as each template has it. A removal that would leave a sibling with no rows is skipped and
  toasted by name. `TemplateSheet.test.tsx` pins it with a dimmer + strobe sibling.
- **A no-op writes nothing, compared through the builder on both sides.** No change is no change
  anywhere, and `write` builds each target's current rows from `seedValues` rather than reading them
  raw, so a stored lower-case hex or an older row order is the same intent. An empty draft is refused
  in the editor (the write boundary 400s a value template with no rows); Clear is refused on the
  column and there is no Spread (plan §7). The draft is cached per template object
  (`templateValueDraft`), so a row's cell sees one identity until the list refetches, and the
  editor reads the batch's landing once, on the open.
- **Measured in the app on 2026-09-24**: the popover is **288** for Intensity, Position and Beam and
  **320** for Colour (the 160px picker, a gap and the hex column need 296 inside the padding). Its
  body scrolls under the viewport with Apply outside the scroller, `SpreadPanel`'s bound — Colour is
  ~565px of controls and Beam ~625 (the exclusions list), both taller than half an 820 frame. The
  footer's copy is the board's: *Value edits the intent — the desk resolves it per head*.

**The FX Library sheet** (`components/fxLibrary/FxLibrarySheet.tsx`, on `routes/FxLibrary.tsx`) is
Output · Mode · Timing · Params · Drives · Source under category chips and dividers; **every column
is a read-out**, so nothing is in the marquee and there are no cell verbs. The route was 930 lines
of one file; the sheet and the three sheets it opens — `EffectDetailSheet`, `EditFxDefinitionSheet`,
`NewFxDefinitionSheet` — are `components/fxLibrary/` now, with the rules in `fxLibraryModel.ts`.

- **Source is the definition list read against the library** (`fxSourceOf`): a row is **custom**
  when some definition's `effectId` is the entry's id (`useFxDefinitionListQuery`, the first client
  of `GET fx/definitions`, tagged `FxLibrary` so the `fxDefinitionListChanged` bridge in
  `store/fixtureFx.ts` invalidates it on the library's frame); any other `USER` entry is
  **script**-registered, and the rest are built-ins. The library entry alone cannot tell the two
  `USER` kinds apart — both carry a `sourceDefinitionId`, which is a definition's row id for one and
  the **script's** id for the other — and that was the plan's §1 bug: the old page fetched
  `fx/definitions/<scriptId>` for a script's effect. **What a row opens follows the source**: a
  custom row its definition's editor, a script's effect **its script** (`/scripts/:scriptId`, the
  Scripts route's deep link), a built-in its read-only detail. A custom row is named by its
  definition (`entryName`); everything else by its registry id as words.
- **The library is the running show's, whatever the URL's project.** `GET fx/library` and
  `GET fx/definitions` carry no project and `/fx/definitions` always writes to the running show,
  and there is no copy route — so off the running project the sheet is the read-only scope with
  **nothing** live, and every row opens the read-only detail rather than an editor for the running
  show's record. Its reason is its own (`libraryPermission`'s third argument): the default
  *…library — copy it here to edit* would promise a verb this sheet does not have.
- **Rename is `PUT fx/definitions/{id} {name}`, on custom rows alone** — a built-in's name is the
  desk's and a script's effect's is its script's, so `renameDisabled` refuses both, and the name
  column's *Double-click to rename* hint is drawn only where the rename is (`LibraryNameColumn` asks
  once, for both; the hint stood on refused rows until this session).
- **Fork (D9) takes one built-in** and is refused over several, over a custom or script row, or on a
  built-in that publishes no script, each with the reason; the detail sheet's footer carries the
  same press. `forkRequest` copies the script, category, output type, mode, parameters,
  `compatibleProperties` and `timingSource` into `POST /fx/definitions`, named *<name> (Custom)*,
  and the route opens the new definition's editor. **The `effectId` is what must be unique**:
  `FxRegistry.register` overwrites by id and deleting a definition unregisters its id, so a fork
  sharing its source's id would replace the built-in and its delete would remove the built-in until
  a restart. `uniqueEffectId` mints `<id>Custom`, `<id>Custom2`… against every library id **and**
  every definition's (one whose script failed to compile is not registered but holds its id), under
  the registry's own normalisation (case, spaces, underscores), and the name takes the same ordinal
  — *Pulse (Custom 2)*. **New effect** mints through the same rule: it took `name` without spaces
  as its id, so a new effect called *Pulse* replaced the built-in.
- **A fork starts with `defaultStepTiming` false, and the editor says so.** The library entry does
  not publish a built-in's default, so the fork cannot copy it; `EditFxDefinitionSheet` draws the
  toggle (a PUT field it never exposed) with a note naming the built-in when Fork opened it.
- **Delete takes custom rows only**, through `useFxDefinitionDelete` — the sheet's batch and the
  editor's Delete — which skips built-ins and script-registered effects **by name** before anything
  is sent, and is refused with the reason when the selection holds no custom row. The desk keeps no
  record of what uses a definition, so there is no in-use dialog; `deleteFxDefinition` joined
  `SILENT_ENDPOINTS`, because `useBatchDelete` toasts every refusal itself.

**The Scripts sheet** (`components/scripts/ScriptSheet.tsx`, on `routes/ProjectScripts.tsx`) is
Type · Lines · Check · Used by under type chips — *All* and one per type present, in short labels —
and type dividers. `ScriptTypePanel` (the sidebar and its phone sheet) and `ScriptListContent` are
deleted, and with them `scriptUtils`' usage-glyph helpers, which only ever read the type.

- **Check is this tab's last Compile** (D8), a `Map<scriptId, ScriptCheck>` in the route, never
  stored (`scriptCheck.ts`). The bar's Compile sends each script's **text** and type
  (`POST …/scripts/compile` compiles a literal), **one at a time** in visible order — the rows read
  *Queued*, then *Compiling…*, then ✓ or *2 errors · line 14* (the first error's message on the
  title) as each answer lands. A result carries the text and type it compiled, and `checkFor`
  answers only while the script still has them, so an edit reads *not checked* again with no
  invalidation anywhere, and a rename keeps its check.
- **A rename is `PUT {name, script, scriptType}` from the list's own copy of the row**: the route
  replaces the whole row and `NewScript.scriptType` defaults to `GENERAL`, so a rename that left the
  type out would silently turn a definition script into a general one.
- **Used by** is the effects an `FX_DEFINITION` script registers — `effectsRegisteredBy`, the FX
  Library's discriminator from the other side, so a custom definition whose row id happens to equal
  a script's id is never credited to it. Anything else reads *—* until `FU-SCRIPT-USED-BY`: cue
  hooks are only on full cue details. Off the running project it is *—* throughout, since another
  project's script ids name nothing in the running show's library.
- **Run takes one script** (by id, against the live show; the route shows `ScriptRunDialog`), and
  Compile, Run, the rename and Delete are the running project's alone. Off it, *Copy to…* —
  `CopyToProjectSheet` over `copyScript`, which keeps the type since session 0 — is the one live
  verb, and a row still opens: `ScriptForm` has a read-only arm.
- **Delete** is `useScriptDelete` — the sheet's batch and `ScriptForm`'s Delete, which lost its
  `confirm()` for it. The desk has no in-use refusal for a script, so the one use this side can name
  is held back unsent, a Look's busk pads' way: an `FX_DEFINITION` script whose effects are in the
  library, listed with what it registers. Deleting it does not unregister them — they stay until the
  desk restarts — so *Delete anyway* is the question; it sends plain (there is no `force`). Every
  other script deletes on the plain pass. `deleteProjectScript` joined `SILENT_ENDPOINTS`.
- **The form re-reads its script from the list by id**, as the Look and template routes do, so a
  rename on the sheet reaches an open form, and a script deleted from another client closes it
  rather than turning it into a create form.

### The editor kit

**Every value editor is built from the same five pieces, exported once from `components/editor/`**
(editor-kit plan D8, D9; the design record is `../docs/plans/editor-kit-design/`, whose
`Editors.dc.html` draws the anatomy and each editor on it). They were the busk sheet's anatomy —
9px uppercase labels, 28px fields with the unit inside, a read-out line, a static footer with the
save first — and the programmer's four cell editors and the kit's four each answered the count
line, the label style, the field height and the unit their own way. What lives there now, and what
each was:

- **`EditorSurface`** — `CellEditorSurface` moved (§The cell editor's three forms): the three
  forms, the double click, `wide`, the anchor. Its hooks are `useEditorForm`, `useEditorCramped`
  and `editorIsOpen`; the `data-cell-editor-surface` attribute and the two media constants kept
  their spellings, which `shortViewport.test.ts` pins. `useEditorKeyboard`, `useEditorOpen`,
  `ValueFieldRow` and `UnsetCellMark` moved beside it, and `HandChip` and `useEscapeEditorSnapshot`
  follow the `editorIsOpen` rename.
- **`EditorLabel`** — the busk view's `BuskLabel`, moved and renamed; the busk view imports it
  back. The label over every control.
- **`EditorField`** — one 28px number field with the unit as a trailing muted glyph and
  `useNumberFieldDraft` inside. It replaced `ValueFieldRow`'s bare `Input`, the busk Spread tab's
  private `NumberField` and the colour editor's `ChannelNumberInput` — which survived session 1 as
  a thin wrapper and went in session 2, when `ColourEditor` took the field directly and its byte
  clamp with it. **The caller still clamps**: the field parses, and a channel byte is 0–255 where a
  slider cell's bounds come from its resolution and a percent is 0–100. The native spinner is hidden
  so the unit glyph has the right edge; the arrows still step the value.
- **`EditorReadout`** — the 10px muted line under a panel's controls, saying what the desk holds
  and what was skipped, with a `lines` arm that was `LandingLines` (the address editor's and the
  Key column's one-head-to-a-line landing; `LandingLines.tsx` is deleted).
- **`EditorFooter`** — save slot · note · spacer · verbs, the shape the busk Colour and Spread tabs'
  footers have. Drawn where a panel has **verbs beside the value**: Apply on `TextCell` and
  `AddressCell`, and — since session 2 — the colour editor's Save · Pick · Spread…, which are
  gestures about the batch and not writes of it (that editor writes as it goes; its footer writes
  nothing). Never the level, position or setting editors, which write as they go and carry no
  verb. A panel that docks it full-bleed says so at its own import — the busk Spread tab with its
  `px-3 py-2` (session 3 rewires it), `ColourEditor` for its docked host.
- **`ColourEditor`** — `fixtures/ColourPickerBody` moved and finished (D10, D12; session 2): the
  picker, the typed R/G/B, the emitter rows and the six-channel buffer, **plus everything the busk
  Colour tab had grown around them** — the read-out line (the emitter counts per head from
  `emitterHeadCounts`, *mixed*, the swatch, the hex), **Pick**, **Recent** and the `EditorFooter`
  with *Save as template… · Pick · Spread…*. The host says which pieces it draws: `targets` (the
  heads, in the host's order — the busk selection expanded in rig order through `headOrder`, the
  programmer's marquee column in row order), `projectId` (what the hidden leaves read the patch
  list of), `recent` (where the chips come from and where a press lands, with `forms` for D11),
  `footer`, `counts`, `labelLine`, `docked` (the busk tab's frame: one scroller, the footer static
  under it), `pickOnTargets` (the busk tab's seed from the rig), `onPick`, `onSave`, `onSpread` (absent
  draws the button inert — session 3 wires the programmer's). The cell hands it the batch's targets
  **as a colour commit lands on them** (`colourTargetsOf`: one per RGB colour cell, a bar whose
  colour lives on its cells expanded, a head with no RGB colour dropped — a colour-wheel head
  resolves a `colour-setting` cell the batch counts and a colour commit refuses, so the label line
  counts this list rather than `batch.count`, and the two lines say one number), and the container passes `projectId` for the **programmer alone**, since the
  plain lists draw no template strip and Save records from the programmer. Five hosts: the programmer's colour
  cell through a thinned `ColourPickerPopover` (the popover at 352 — `contentClassName`, measured
  in the app on 2026-09-22 with the picker at 234×200 — and **528 compact**, the side sheet's
  compact width, because the compact body sits its 208px emitter column beside a 256px picker row
  and at 352 it wrapped under, costing the height compact exists to save; and both sheets, `wide`
  on the side sheet),
  the busk tab docked, the Spread tab's colour endpoint (`footer` and `counts` off — a colour intent
  has no emitter component) and the two property visualisers (`channelFields` off as well: the
  picker-only form, in a 224px popover measured the same day). **Pick reads the heads the editor is
  editing** through one hidden `FixtureAppearanceSource` leaf per parent fixture, mounted by the
  editor itself in a store-bound child (`AppearanceLeaves`) so a cell mounted with no project pays
  nothing — the grid's rows do not report into `lib/liveAppearance.ts` and are not made to; it moves
  the knob, the fields and the *mixed* marker and writes nothing, the picked colour becoming the
  buffer the next single-channel edit builds on — and **an emitter the host does not hold stays
  unstated**: the rows are the batch's union but the bytes are one row's, and `writeColour`
  samples the wire only for an undefined component, so folding a missing white to 0 drove every
  RGBW head in the marquee to white 0 on a byte typed from an RGB row's cell (found in review;
  the picker gesture and Pick zero only what the editor holds) — (`targetHeads` in `liveAppearance.ts` is the fold,
  over `WriteTarget`'s new `fixtureKey` / `cellIndex`, which `rowWriteTargets` stamps on an element
  row and `writeTargetsOf` on a busk cell — element keys are never parsed). **Recent is drawn once
  on a screen** (D11): the cell asks for it in the bottom sheet alone, since row C's strip is one
  row up in the popover and folded away below 600px; the busk tab always. Its store hooks run in a
  child mounted only where the chips are drawn, never per cell. **The guidance is the picker's
  title everywhere**, the paragraph gone. **The picker is fluid in every host**: `index.css` keeps
  the picker's look (saturation radius, the 12px hue bar and its 16px gap, the pointer) and the
  `.colour-picker-fluid` and `.colour-picker-compact` rules, and the 200px `!important` pin is gone
  — with two things recorded there. **The picker cannot be sized by a Tailwind utility**: v4 emits
  utilities inside `@layer utilities` and react-colorful's stylesheet is unlayered, which beats any
  layered declaration whatever its specificity (measured on 2026-09-22: `[&_.react-colorful]:h-44`
  was generated, applied and lost to the library's 200px; and an arbitrary variant reads `_` as a
  space, so `__hue` became ` hue`) — so the compact heights stayed in `index.css` after all, where
  the plan had them reduce to the fluid rule. The old fan popover's inline 150×120 pickers went
  with it in session 3: the Spread panel's colour endpoint is `ColourEditor` compact in every host
  — the busk tab's shipped measurement, `.colour-picker-compact`'s 11rem — and never a utility. `ChannelNumberInput`
  is deleted; the editor mounts `EditorField` and clamps the byte itself.
- **`useLivePush`** — `hooks/useLivePush.ts` moved beside its callers, and **`useSheet`'s ~30 Hz
  commit throttle is that hook with `floorMs: 33`** (D16): the busk tabs' hook and the sheet's
  throttle were two copies of one rule — a floor, a dedupe, a release that always lands. Two things
  the hook has no notion of are kept in `useSheet`: a commit for a *different* cell lands the
  pending one first, and an unmount lands whatever is pending. **The sheet switches the hook's
  dedupe off** (`neverEqual`): the hook dedupes a gesture's moves against what it last sent and
  relies on `reset()` for a fresh gesture, and a sheet's value moves by routes the hook never sees
  — ⌫ through `column.clear`, Spread, Park, the wire — so a value set, cleared and retyped would
  never reach the rig while the field showed it. `useSheet.test.ts` pins the cadence as the
  literal 33, the trailing call, and that a repeat is sent.

**The label line replaced the count line** (D8). No editor says *Applying to N targets* any more,
anywhere — `ColourCell`'s trigger `title` was the last place the sentence survived, until session 2.
The popover form draws `EditorLabelLine` first — *4 heads · Local* on the left (the batch and the
scope, or the focused Look's name in layer scope, which no editor said before; *4 cues* / *8
channels* / *4 fixtures* on the kit sheets, from `SheetCellProps.batchLabel` and the sheet's `noun`)
and the column on the right — and **only the popover**: both sheet forms keep their title row, which
already names the column, and the band above the grid already says the count. The programmer's
cells take a **`CellBatch`** (`rowModel.ts`) in place of the old `batchCount` — the count, the
**skipped** heads (targets that resolve nothing for the column, which a geometric marquee sweeps up
along with the rest) and the resolutions — from `FixturesListContainer`'s `batchFor`, **one per marquee column**
(`marqueeBatches`): a commit from a cell in column X lands on column X's targets alone, so what the
editor says about the batch must be that column's, not the marquee's total — summed, the Dimmer
editor said "1 head has no dimmer · skipped" for a par the *Gobo* column skipped. `scopeLabel` rides
beside it from the scope and the Look store's `lookName`. The container's own 33 ms position merge
goes through `mergePositionCommits`, which keeps `panDeg` / `tiltDeg` and one unit per axis. The read-out says the skip (*2 heads have no gobo · skipped*) where the write would
silently drop it. `ColourCell` takes the same `CellBatch` since session 2, and `CellBatch`
gained the **`targets`** themselves beside the count — a count and a resolution list cannot name a
head, and the colour editor needs the heads for its emitter union, its leaves and the template
targets its Save and Recent land on.

**The unit is the cell's** (D13). The programmer's dimmer cell reads *80%*, so `SliderCell`'s field
is a percent — `toPct` / `fromPct`, 0 ↔ 0, 50 ↔ 128, 100 ↔ 255, pinned as literals in
`SliderCell.test.tsx` — and the byte is the read-out (*204 of 255 · 0–255 on every head*, or *on the
first head · ranges differ*). The slider stays in bytes. The DMX sheet reads *204*, so `LevelCell`
keeps bytes; the percent is its read-out. Strobe, zoom, focus and iris are slider cells and take the
percent for the same reason — a template of them is a percent already. The one exception is a
**stepped zoom** (the Robe ColorSpot 575's, fixture-optics session 5): a setting, so its cell is the
setting cell listing the steps, and a template's percent on it snaps to the step nearest that
proportion of its angles.

**Position types degrees where the head annotates, bytes where it does not** (D14). `PositionCell`
draws a 120px XY pad beside Pan · Tilt — a drag writes both axes in one commit — and where **every**
head in the batch carries `degMin` / `degMax` on both its pan and tilt sliders (the movers, 10 of
the 28 models; a real `position` descriptor carries none), the fields and sliders are in travel
degrees and the commit carries **`panDeg` / `tiltDeg`** rather than bytes. `clampCommitToResolution`
resolves a degree to **each head's own byte** — 270° is 128 on a 540° mover and 109 on a 630° one —
through `lib/axisDegrees.ts`, which is `dmxToDegrees` (moved out of `stageCoords.ts`, re-exported
there) beside its inverse; a head whose axis carries no annotation is left alone on that axis. A
batch mixing annotated and silent heads keeps bytes for all of them rather than two units in one
editor; the cell keeps its byte read-out either way. The field's range is the lead head's.

**Widths by content** (D17): `w-72` for level, position, text and address (the busk endpoint editors'
two fields plus a gutter, up from `w-64`), `w-64` for the setting and option lists and `w-[352px]`
for colour (`w-[528px]` compact, the side sheet's compact width) — measured in the app on
2026-09-22 as 288, 256, 352 and 528 (`getBoundingClientRect` on the open popover, with the field at
28, the pad at 120 and the colour picker at 234×200), which is what each component's docblock
records.

### The cell editor's three forms

**A cell editor is a popover on a desk, a bottom sheet on an upright phone, and a right-hand sheet
where the viewport is short.** `components/editor/EditorSurface.tsx` is the one
place that decides, and every cell goes through it — the programmer's four (`SliderCell`,
`ColourCell` via `ColourPickerPopover`, `PositionCell`, `SettingCell`) and the kit's four
(§Sheet kit). There were **five**: the marquee's own typed
field, `CellEntryPopover`, drawn through this same surface precisely so that Enter and a click
would read as one kind of thing. It is gone, and the reason is the stronger form of that argument —
§The programmer's keyboard.

Three rules, each of which was learned rather than designed:

- **Short beats narrow.** A landscape phone matches both queries — 852×393 — and the bottom sheet is
  the one shape that needs the vertical room a short viewport has not got. The width query is the
  `sm` breakpoint the sheet primitive itself uses (`max-width: 639px`); the height one is the space
  plan's own `max-height: 500px` fold, duplicated and pinned by `shortViewport.test.ts` like every
  other site of that number.
- **Width is the content's to ask for, and so is the compact layout.** `wide` on the surface is the
  colour editor's alone — `ColourPickerPopover` asks for it on `ColourEditor`'s behalf, 35rem
  against 22rem for the other three, which looked absurd in that much room. `useEditorCramped()` is a *separate* `max-height: 750px` question, because all three
  forms can be short of height: below it the colour editor draws a two-column layout with its
  emitter rows beside the picker. 750 and not 500 because **a popover must fit beside its cell, and
  a cell can be any row**, so the room it really gets is about half the viewport — below ~725 there
  is a band where neither side holds the full 357px editor, Radix flips it above the cell, and
  `limitShift` then refuses to slide it back down because that would cover the anchor. It renders at
  a negative `top` and the window clips it, silently. Found on a 852×524 screen, not by a test.
- **"Is this a touch surface" is the form, never a `sm:` variant.** `SettingCell`'s option rows are
  the one control that *is* the list, so they need a finger-sized height on both sheets and not in
  the popover. A `sm:` variant says `min-width: 640px`, which is true of the landscape phone the
  side sheet exists for — so the first version shrank the rows on the very surface they were added
  for. Width cannot answer that question on this desk.

Two things the sheets carry that a popover never needed. **The keyboard's bite** is measured from
`visualViewport` and given back — the bottom sheet rises by it, the side sheet shortens — because a
`position: fixed` sheet is laid out against the *layout* viewport, which does not shrink when iOS
opens the keyboard, so a sheet holding a number field would slide neatly behind it. And the
**safe-area inset is added to the side sheet's width rather than padded into it**: as padding alone
it came off the content box and the colour picker no longer fitted, so the sheet scrolled sideways
on whichever rotation puts the notch on that edge. `SheetContent`'s own `sm:max-w-sm` silently caps
an inline width, so `maxWidth: 'none'` goes with it — the symptom of forgetting is a sheet that
stays narrow and scrolls, which looks exactly like the width never being applied.

**On the programmer's grid a single click on a cell selects it, and a double click opens its
editor.** The whole of a single click is the selection: one cell, replacing whatever was there —
including when the cell is already in the marquee, which is how a rectangle is narrowed to one of
its own cells, the one thing a rectangle cannot say. The editor is opened by the selection bar's
**Set**, by Enter, by typing (§The programmer's keyboard), or by a **double** click, so the drag,
the single click and the keys all say *what* to edit and one gesture says *edit it*.

**The double click is that second gesture made with the pointer alone**, and it is the surface's
rather than the four cells': `EditorSurface` decides it once, above the form branch, so all
three forms and all four value kinds answer it identically — the reason `CellClickBehaviour` is one
type rather than four copies of two props. It opens through the same `onOpenChange` every other
opener uses, so it lands in `useEditorOpen`'s click path: beside the cell (Set's toolbar anchor
is Set's alone), with no typed seed, and with whatever a click's open resets reset. It is wired
**only where a single click selects** — in `CueValueGrid` a single click already opens, and two of
them would toggle the editor shut and back open. And it needs no
*permission* gate of its own: a `disabled` trigger fires no click and therefore no double click,
which is how Output scope, a focused template layer and an unreachable desk stay read-only through
this door as much as through the other three.

**The handler is withheld while that cell's editor is already open**, because `setOpen(true)` is
not idempotent: it re-runs the cell's own open reset (`SliderCell`'s `draft.reset`, `SettingCell`'s
filter) over what the operator had half-typed, and re-latches `atButton` to false — which swings a
Set-anchored panel across the screen *and* swaps the branch from `TriggerState` to `PopoverAnchor`,
two component types at one JSX slot, so React remounts the operator's own button under their finger.

**Do not delete the guard on the grounds that Radix already closes the editor first.** At the desk
it does: the gesture's first `pointerdown` is an outside press on the open content, and
`DismissableLayer` listens for `pointerdown` on the document, so the `dblclick` lands on an editor
that has already shut and simply opens it again — verified in the browser on this change and on the
commit before it, where a single click on an open cell behaves identically. But that dismissal is
the *environment's* and not a rule this code states, and a test never reaches it: `fireEvent`
dispatches exactly the one event it names, so no `pointerdown` is seen, the listener never runs, and
the second open goes straight through. Both tests in `EditorSurface.test.tsx` fail if the guard
is dropped. **The difference is not Radix rescuing the browser case** — `PopoverContent` suppresses
an outside press only where it lands on a real `PopoverTrigger`, and this grid renders none in
either environment.

**The handler goes on the wrappers that already clone the trigger, never in a wrapper of its own.**
Each branch hands the trigger through a `Slot` as it is (`PopoverAnchor asChild`, `TriggerState`),
so a `<TriggerDoubleClick>` around it — which is how this was first built — meant a *second* clone
per cell per render, on a grid that mounts one of these per visible cell and re-renders the whole
viewport on every frame of a marquee drag. The nested slot was a trap besides: an outer slot hands
its child the anchor `ref` and `data-state` as props, so the wrapper had to forward them or the
popover would never be positioned and no cell would be marked open.

Three consequences worth knowing before touching any of it:

- **The trigger is a `PopoverAnchor`, not a `PopoverTrigger`** (`triggerOpens` on
  `EditorSurface`, set from `CellClickBehaviour`, which is one type rather than four copies of
  two props precisely so the four cells cannot answer this differently). Radix then has nothing to
  toggle and the button's own `onClick` is free to be the selection. `data-state` is restored by
  hand on the anchor: it is the only thing that says *which cell* the open editor belongs to, and
  the grid's addressing contract is read through it.
- **All three lists answer a click the same way, and `FixturesTable`'s `cellSelection` is
  required.** Fixtures → List and Groups → List were the exception until this session: no
  `cellSelection`, so a click there opened the editor *and* selected the clicked **row** — which
  was the honest answer while they had no cell selection for the toolbar, `commitNow` or
  `batchCountFor` to read, and which made one component answer one gesture two ways depending on
  the route it was mounted under. They have the marquee, the keyboard, Set · Clear · Spread and the
  double click now, and `showOwnership` is back to meaning only what it says: draw provenance.
  `clickSelectsCell` went with the branch — `FixturesListContainer` is this table's one caller, so
  the flag was a constant — and the cells keep their own `clickSelects` for `CueValueGrid`, which
  has no selection and would otherwise lose every way into an editor.
- **What the two plain lists keep of their own is the *row* Spread** (`!showOwnership`), over the whole
  row selection with the column chosen in the panel. A row selection there is made by dragging the
  name column or by ⌘A, and spreading across eight whole heads without first drawing a rectangle over
  one of their columns is a gesture those views already offered; the programmer trades it away
  because there the marquee is what a selection is *for*. They also now mount `useClearCellEffects`
  — a subscription of their own, which the programmer gets free from `ProgrammerFxList` — because
  ⌫ clearing the values while the effect driving them kept running is, on the rig,
  indistinguishable from the key having done nothing.
- **A click on a column the row resolves nothing for clears the selection** — a dimmer-only par's
  Colour cell. It is blank rather than an em-dash, and it is *not* background in the DOM (the row
  carries `data-row-id`, which the scroller's background handler stops at), so it carries
  `onEmptyCellClick` itself. That callback reaches `RowView` through a ref, because the container's
  answer is the cells-then-rows ladder and so changes identity on every marquee frame — passed
  straight down it would re-render every visible row at pointer rate.
- **Escape is a rung longer than it looks.** An open editor takes it first and the selection
  survives; a second Escape, with nothing open, clears as it always did. The question is *is an
  editor open* (`editorIsOpen()`, a DOM read of the surface's own attribute) and not *where was
  the key pressed*, which is what `isEditableTarget` and `closest('[role=dialog]')` answer — a
  different question, and one that answers wrongly the moment focus is not inside the panel.
  **The answer is snapshotted in the window's capture phase**, because Radix listens on the
  *document* and the grid's handler on the *window*: on the way up Radix closes the panel first, and
  a keydown is discrete so React has already flushed the unmount — asking in the bubble handler
  always answers "nothing open" and clears anyway. Capture on the window is the first thing any
  keydown in the document reaches.
- **Set closes the editor it opened**, the way Spread's own button always has — through a `close`
  one-shot mirroring `keyboardOpen` (`closeEditorCell` → `autoClose`). It needs one: a press on Set
  is **not** the outside click that dismisses a popover, because Set is that popover's own anchor.
  Verified at the desk rather than reasoned — pressing Set twice left the panel open with focus
  stranded on the button, which is what made Escape clear the selection. Which cell to shut is read
  from the `data-state` the cell's anchor carries, scoped `[data-cell]` so `SpreadPanel`'s own panel
  — a cell editor in every way but this one — does not read as one.

**The editor opens where the gesture was made.** Set is pressed at the toolbar, so its editor opens
at the Set button (`anchorRef` on `EditorSurface`, `editorAnchorRef` threaded container → table
→ row → cell); **Enter and a typed character are made at the selection, with the operator's eye on
the grid, so theirs opens beside the cell** — the cells express that by withholding the anchor
(`atButton ? editorAnchorRef : undefined`) rather than by a second prop on the surface. Anchoring
*everything* at the cell was the original defect: for a marquee near the bottom of a long list the
panel landed nowhere near the hand that pressed Set. Three rules in it:

- **The choice is latched at open**, in `useEditorOpen`, exactly as `keyboardSeed` is. The
  request is a one-shot the table drops on the very next commit, so a per-render read would
  re-anchor the panel from the button to the cell a frame after opening — visibly jumping across
  the screen.
- **The ref is read at render time and only while open**, because a `virtualRef` whose `current` is
  null sets Radix's anchor to null and the content is then never positioned at all. So no button
  means the cell, which is what a double click gets on any of the three lists.
- **The virtual anchor is rendered after the cell's**, which is what makes it win: it claims the
  anchor from an effect, and effects run after the refs of the same commit.

**An editor closes when the selection it was opened for goes away** (`selectionEmpty`, threaded to
`useEditorOpen`). An editor is open *for* a selection, so a Deselect used to leave one on screen still
writing — to something narrower than the batch its label line had just named. It is
**edge-triggered**, on the false→true crossing and not on the state, or a grid with no selection at
all could never open one: the close would land in the effect immediately after the click that
opened it, and the editor would flicker rather than fail in a way anyone could report. `undefined`
is a third state meaning "the question does not arise", which is what `CueValueGrid` passes.

**React bubbles synthetic events through portals**, and every cell editor is rendered from inside a
row — so the grid's marquee saw a drag on the dimmer slider as a press on the grid, and selected
cells under the operator while they were setting a value. `useCellMarquee`'s `onPointerDown` asks
the DOM (`e.currentTarget.contains(e.target)`), which is the same guard the scroller's background
`onClick` beside it already documented and this one was simply missing. Any new handler on the rows
wrapper needs it too.

### The programmer's keyboard

**There is one editor per column, and the keyboard opens it.** Select cells, press Enter (or just
start typing), and the cell editor for the first selected cell opens with its first text field
focused and selected; what it commits lands on every selected cell through `commitToCells`. Enter
in any field applies and closes, comma steps to the next field, and the marquee stays so the next
Enter opens it again.

**It used to be two editors**, and that is the thing to understand before touching any of this. A
click opened the column's editor; Enter opened `CellEntryPopover`, a single line of text with a
grammar of its own (`parseCellEntry`: `127`, `50%`, `full`/`out`, `#ff8800`, `r,g,b`, `pan,tilt`).
Both are deleted. Two editors for one job drift, only one of them can be improved at a time, and
the text one could only ever set what a line of text can say. What went with the grammar, and where
it went instead:

- **Hex typed as text** — replaced in kind: the picker and the R/G/B boxes say the same thing.
- **`pan,tilt` and `r,g,b`** survive as a **gesture** rather than a grammar: comma steps to the
  next field. That is what `PositionCell`'s new pair of boxes is for — it had none, only a drag —
  and what makes the colour editor's R → G → B → emitters a typed sequence.
- **`50%` came back in kind, `full` and `out` did not.** The programmer's level editor's box is
  a **percent** since the editor kit (D13), so `50` typed there is what `50%` said; the DMX
  sheet's stays a byte. `full` and `out` are still gone and nothing replaced them: the box is
  `type="number"`, so those characters cannot be typed into it at all. Said plainly because it is
  the one part of the deletion that was a real loss rather than a relocation: it was put to the
  desk as a question and answered *leave it as built*, on the same reasoning as hex. Getting them
  back means a text grammar in the number field, which costs that field its arrow-key increment —
  so ask before reaching for it.

`components/editor/useEditorKeyboard.ts` is the one copy of the rule, shared by
every cell — the programmer's four and the kit's — **and by `SpreadPanel`**, which is the same kind
of panel and had the same gap. Two
things in it are not arbitrary. The focus is taken in **`onOpenAutoFocus`**, not in an effect:
Radix's own auto-focus is a *parent* effect and parent effects run after a child's, so a focus set
from inside the content is taken straight back off it. And **comma is left alone in a one-field
editor** — there is nowhere to step to, and a type-ahead may want the character.

**The panel behaves the same however it was opened** — the selection bar's Set, a keystroke, or a
double click on the cell. The first field is focused in all of them, and that is
not a nicety: *drag three dimmer cells, Enter, type `128`, Enter* is the ordinary desk gesture, and
it only works if the open leaves the field focused. A first cut focused the field only for a
keystroke, on the reasoning that a tap must not summon the on-screen keyboard, and quietly broke
exactly that. Refuse any change that makes focus a function of the gesture again.

**A released drag opens nothing, and neither does a single click.** The drag half went first
(`PD-POPUP-AFTER-DRAG`: a single-column marquee opened its first cell's editor behind the release)
when the selection bar gained a **Set** of its own; the click followed it, for the same reason. The
drag and the click say *what* to edit and Set — or Enter, its key, or a **double** click — says
*do it*; a popover springing open under a pointer that had just finished drawing a rectangle was
the second gesture being made for the operator, and it had no equivalent for a drag that spanned
two columns. `singleColumnAnchor` and
`onSingleColumnDrag` went with it; `autoOpenCell` in `FixturesTable` is fed by the container's
`keyboardOpen` alone now, whichever way the operator asked — the double click never reaches it,
being the surface's own.

**The one thing that does differ is the *form*, not the gesture.** Focus is taken in the popover
and in neither sheet (`useEditorForm`), because both sheets are reached by a finger and there
the keyboard rises over the grid for nothing. That is a question about the surface, so on any one
surface every way in still behaves identically. `SpreadPanel` opts out with `autoFocus: false`
where the marquee spans several spreadable plans or families: then its first question is *which*,
and jumping to the From box would skip the chooser that decides what From means. With one column
the selection has answered that, and From is focused like any editor's first field — in the
popover host alone; the docked busk tab is reached by a finger.

**`keyboardSeed` carries the character and nothing else.** A string (`''` for a bare Enter, null
for a click or a drag) threaded container → table → cell, latched by `useEditorOpen` into
`keyboardOpen` for as long as the editor is open, and read only to seed the first field.

**A character typed at the grid opens the editor and lands in its first field**, committing as
though it had been typed there — every field on this desk writes as it is typed. Digits, `.` and
**letters**: a letter is the first character of `SettingCell`'s type-ahead, and a numeric editor
simply ignores one (`numericSeed`). `#` and `,` went with the grammar — there is no hex to start,
and comma now means something inside an editor.

**`SettingCell` is a type-ahead** above three options (below that there is nothing to narrow): the
filter matches on display name, ↑/↓ move the highlight, and Enter takes the highlighted option —
the top match by default. An Enter that matches nothing is swallowed rather than closing, because
closing would throw away a search halfway through being fixed. It answers Enter and the arrows
itself and says so with `preventDefault()`, which is how the shared wrapper knows to stand aside.

**One column's editor for a selection that spans several is not a narrowing.** `commitToCells` fans
a commit across every selected column and drops it from the ones whose shape it does not fit — so a
Dimmer + Colour marquee opens the dimmer's slider, and moving it sets the dimmers and leaves the
colours alone, exactly as `127` did. `orderedSelectedCells` picks the order — topmost displayed
row, leftmost visible column — the display-order rule a released drag's auto-open once shared,
extended to the column axis — and the container takes the first cell that **has an editor**.
That second half is load-bearing: a marquee is geometric (`hitsFor` sweeps a rectangle
over rows and column bands), so it covers Colour cells on dimmer-only pars, and taking the
display-first cell flatly would leave Enter doing nothing on an ordinary mixed selection.

**`cellKeyboardPermission` in `cellEntry.ts` is the scope gate**, the fourth place "read-only" is
said (§The programmer's scoped grid): both keys in Local, neither in Output, entry only into a
focused Look layer (its draft has no removal), neither on a focused template layer. The container
reports `cellEntryKey` / `cellClearKey` false where a key is refused, and the grid's hints read
those rather than restating the rule — so no hint can advertise a key that does nothing. The window
handler only names a cell, and **not from a focused control** (a button, a link, a menu or menu
item) on any arm: a cell trigger is tabbable and Tab-then-Enter opening its popover is a path the
grid already promises, and Backspace is the destructive arm. The one exemption is a cell trigger the
marquee itself covers (`marqueeOwnsKeyTarget`), or Enter there would fall through to that button's
own activation and open *that* cell's editor with nothing focused.

**Backspace / Delete takes the selected cells out of Local** (`CellWriters.clearValue` →
`programmer.clearEntry`, by the programmer fade — the same store the action bar's Clear fades by)
— **and stops the local effects on them**. That second half is not a new gesture: the programmer's
whole-desk Clear has always swept values and programmer-band FX together
(`clearProgrammerCompletely` in lighting7), and three cells under an effect template's press are
three instances that were otherwise removable only one at a time in the rail.
`components/fixtures-list/cellEffects.ts` owns the rule and `useClearCellEffects.ts` the wiring;
two clauses keep the key inside the rectangle. **Local means unowned** — `programmerOwned` *and*
no `lookId` / `templateId` / `programmerLayerId` / `cueId`, since an effect that came out of a
Look, a template layer or a cue is that thing's, and stopping it here would be undone by the next
recook or would quietly edit a library record. And **every head, or none**: an effect drives
whatever its target names (for a group, every member) and there is no "stop it on these heads
only", so a partly-covered one is left running and toasts that it was, rather than half vanishing
from the rig. Matching is on `fixtureKey|propertyName`, the same pairing `FxSheet` places an
effect on a cell by, and the pairs are **collected by the clear loop itself** rather than
re-derived, so the values half and the effects half cannot reach different heads.

The request itself is a **one-shot** on both halves: the container drops it on the commit after it
is set, and `FixturesTable` folds it into `autoOpenCell`, so there is one mechanism for "open that
editor with no click" — Enter and the bar's Set are the same request. A request left standing
re-opens the editor the next time the virtualiser renders the row it names.

### One selection, two shapes

**Rows and cells are one selection**, and either clears the other — on every sheet; `useSheet`
is the kit's copy of the rule for the three surfaces whose row selection is local (§Sheet kit). They were two independent states
— a row selection with checkboxes that Record scoped on, and a cell marquee drawn over it as a
transient edit scope — and an operator had to hold both in their head to know what the next gesture
reached. Now `FixturesListContainer` derives one `selectedRowIds` (the cells' rows under a marquee,
the row selection otherwise) and every consumer reads it: Record's published targets, Locate and
Highlight, the desk-selection bridge, the template strip and the footer count. A marquee *is* the
fixture selection, narrowed to some of their attributes. Enforced at the doors, not by an effect:
the cell door (`selectCells`) clears the rows only when a hit arrives and there are rows to clear,
and every row door (`selectRow`, `selectAllRows`, `setRows`) clears the cells, whose `clear` bails
when there are none. `cellRowIds` is identity-stable while its *members* are unchanged, or a drag
would republish to the desk on every pointer move rather than at each row boundary.

**The desk selection is one fact with three parts — targets, `families`, `source` — and a marquee
publishes two of them** (multi-screen plan D2, D3, D7; backend af3575a). `selection.state` is
`{targets, families?, source?}`, both new fields *omitted* when absent, never null: absent
`families` is every attribute, absent `source` is nobody since the last clear. The rule that keeps
the mask part of the selection rather than a second fact: `set` replaces the whole fact (no
families = clear the mask), `toggle` keeps it, `clear` drops both. So `useDeskSelectionBridge`
publishes `cellFamilies(cells)` beside the rows — `FixturesListContainer` hands it the cells — and a
row selection with no cells publishes *no* mask, which is a `set` that clears the desk's. The
publish is keyed on the mask's **key** (`familiesKey`, `lib/selectionMask.ts`), not on `cells`,
because a drag mints a fresh `cells` array per frame; `normaliseFamilies` is the one spelling —
none and all four are both `null`, declaration order — so an echo compares equal to what was sent.
**The echo FIFO's key is targets *and* families**: a frame with the same heads and a different mask
is exactly what a second window changing the mask under a standing marquee produces, and a key of
the heads alone swallowed it as our own echo. It is applied as a **row** selection — this list
cannot draw a mask it did not make as a marquee, so the marquee is dropped to its rows. A frame the
FIFO *does* treat as an echo still lands its `source` in the cache, because the cache is
`store/selection.ts`'s and is written before the bridge's effect runs (`decodeSelectionState` keeps
the untouched parts' identities, so a source-only frame moves the chip and nothing else).
`source` is stamped by the desk from the window this socket **announced**, never sent: the
`windows.announce` every tab sends on connect and on every change (§Windows, full screen and the
hand) carries its name, and the desk stamps `source` with that name **and the socket-minted row
id** (lighting7 d774fd9). No selection write names this window any more — session 1's `sourceName`
on `selection.set` / `selection.toggle` is gone from this side, and the desk's fallback arm for it is
`FU-WINDOWS-RETIRE-SOURCENAME`, ready to delete.

**Nothing on this side draws `source` any more** (busk-chrome plan D18). The desk still stamps it
— the mover's *row* id, which a tab never sees except in its own registry row, and the name — and
`decodeSelectionState` still carries it into the cache; `deskReading`, which resolved *my* row as
the one whose `windowId` is this tab's (`thisWindowRow`, first match) and read *Desk · from <name>*
for another row's id, went with the following chip. `thisWindowRow` stays the Screens sheet's, for
its *this window* badge, and the caveat it carried stays with it: a duplicated tab copies its
`sessionStorage`, so two rows can share one `windowId` and cannot be told apart from this side.
Cosmetic, accepted by D9, and left; `FU-WINDOWS-OWN-ROW-ID` is the exact fix.

**Follow / local is a per-tab `sessionStorage` fact, default on** (`lib/deskFollow.ts`, D8), and it
gates **both** directions of the bridge, since one bridge is both. `localStorage` is one value per
origin per profile, and the two desk screens are two windows of one profile — `createSyncStore`
took a `storage` parameter for exactly this. Unlinking snapshots the desk's targets *and* families
into the tab's copy and leaves the desk's alone; re-linking adopts the desk's and publishes nothing
(the bridge treats re-enable as a fresh mount, for the mount rule's reason: a window joining must
not clear what another screen has selected). On the programmer the local selection *is* the list's
own row selection — an unlinked window records and presses what it shows; on the busk view it is
the copy in `deskFollow.ts`, read through `useSelectionPair`.

**A window's own selection is offered only where it can both select and act on it** (desk-follow
plan D1, D2): busk **Split** and the **Programmer**. Rig focus selects and has no pads — it exists
to be the selector for another screen's pads, even with the sheet's Colour or Spread tab open —
and Pads focus presses and has no tiles, so a local selection there would be a frozen snapshot
the operator could neither see nor change from that screen. So **Rig and Pads always follow**, and
the effective rule is `follows || followIsForced(view, focus)` on the busk view and the stored
flag alone on the Programmer. `followIsForced` in `lib/deskFollow.ts` is the one statement of it,
read by all four places that need it and restated by none: `BuskingView`'s relink, the
`windows.follow` refusal in `useWindowsBridge`, the Screens row's Selection segment and ⌘K. It
takes the window's **stored** busk focus, never the Split that edit mode or an empty project forces
for their duration — the focus is what the window returns to, and what other windows see
announced.

**Entering Rig or Pads while local drops the local selection and says so** (D3): `BuskingView`
relinks (`relinkToDesk` — adopts the desk's, publishes nothing) and toasts *<Window> follows the
desk selection again* over *<Focus> focus presses onto the desk's selection. Your own was
dropped.* The effect is keyed on the focus and the flag, **never on a mount gate**, because the
focus moves through five doors — the Focus control, the Screens row, ⌘K, a MIDI `BuskFocusSet`,
`?focus=` on arrival — three of them from elsewhere, and a window that *arrives* in Pads while local
(a reload over stale `sessionStorage`, an unlinked Programmer window navigated here) must relink the
same way; the flag in the key makes it a backstop against anything that ever unlinks a forced
window. Holding the selection dormant until Split was declined: it needs a chip, in a focus that
has none, to explain a selection nothing is using.

**The ways out of following are the badge, the Screens row and ⌘K** (D4, D11). The badge's own
press is the primary one (§The rig); the other two set a window from elsewhere. The Screens sheet draws a
*Selection · Desk | This window* segment on every Busk and Programmer row (`viewHasOwnSelection`,
the one list of those two views, which ⌘K's arm reads too), from `row.follows`, and writes the
fifth windows command, `windows.follow` (§Windows, full screen and the hand); on a busk
row in Rig or Pads it is **disabled with its reason** (*Pads focus follows*), never hidden. ⌘K
offers *<Window> · own selection* / *<Window> · follow the desk selection* for every other Busk or
Programmer window, flipping with that row's flag and withholding the unlink for a forced row, and
*Stop following the desk selection in this window* is withheld in Rig and Pads. There is no MIDI
target (`FU-SURFACE-SELECTION-FOLLOW-SET`). The page has the same two doors and its own flag
(§The busk layout): its badge's press, the row's *Page* segment and ⌘K's page pair.

**The desk chip always says which selection this window is on** (D8, revisiting busk-chrome D18):
following, it is **`LinkBadge`** (`components/desk/LinkBadge.tsx`) — a 20px link glyph, glyph only
at every width, accessible name *Following the desk selection*, whose press unlinks — a mark
instead, reason on the hover, in Rig and Pads, which always follow (D11) — `shrink-0`; unlinked,
the dashed `This window`, whose click follows the desk again. The
four readings the following chip once carried (`Desk`, `Desk · from <name>`, `Desk · from the
desk`) stay gone with `deskReading`: an operator at a two-screen desk knows which screen they are
selecting from. It sits on the programmer's row C between the family pill and the strip (a `chip`
slot on the kit's `SelectionBar`, filled only with a `projectId`), on the busk rig row beside the
family pill in Split and Rig, on the pad row in Pads, on the compact rig strip and the short board's
merged row, and nowhere else — the plain lists never bridge (D1), so a mark there would name a link
that does not exist. **The badge must never move a control**, so every row it sits on was
re-measured for it (§The rig): the rig and pad rows' rungs moved by its 29px, and row C gave its
template `New` word on the phone arm. **On the busk band the chip takes `showSubject` and reads
`Targets: This window`**, because there it has a sibling — the page mark (§The busk layout) — and
two bare chips a row apart would be worse than either alone; on row C it is alone and stays bare,
that row being budgeted to the pixel. The pill itself is `components/desk/FollowPill.tsx`, shared
by both chips so they cannot drift apart visually while their flags stay entirely separate; its
parts fold under the host's classes with the whole reading as `aria-label` (§The rig).

**The checkbox column is gone, and a drag from the name column selects rows** — the same
`useCellMarquee`, which decides at the press which side of the first value column it landed on and
never changes its mind mid-gesture (a rectangle dragged from the name column into the values is
still a row marquee, the way a spreadsheet's row-header drag is). A plain drag replaces; a ⌘-drag
unions onto the selection it *began* over, so shrinking the rectangle un-selects, which the cell
marquee's per-frame accumulation does not do. The list hands the whole id list to `onRowMarquee`
only when it changed, and the release's click-swallow is load-bearing twice over there: the name
cell's own `onClick` would otherwise select the one row under the release. Enabled on all three
lists, since the checkbox went from all three: without it the two plain routes would have had no
way to accumulate a selection by touch. The keyboard path is window-level and deliberately has no
per-row control: ⌘A, ↑/↓ with Shift extending, and **→/← open and close the anchor row** — over a
row selection; over a cell marquee every arrow moves the marquee (§Sheet kit) — a group
over its members, a multi-head fixture over its elements — with ← on a member or element climbing
to its parent first, the ARIA tree convention (`treeKeyAction` in `rowModel.ts` is the rule); ↑/↓
step by the kit's `arrowStepTarget`, which every sheet on the kit steps by too (§Sheet kit).

**The grid's rows are `select-none`, and a fixture name is no longer selectable text.** Three
declarations ride together on the rows wrapper — `select-none`, `touch-manipulation` and
`[-webkit-touch-callout:none]` — each for a browser default a marquee was losing to
(`PD-MARQUEE-TOUCH`), and they are unconditional on all three lists. The two plain ones carried a
narrower arm while they had the row marquee alone: text selection was refused only *while* a row
drag was live, so a fixture or group name could be selected and copied straight out of the list,
which their comment named as deliberate. A cell marquee costs them that, and it is a real loss on
two everyday browsing views rather than a tidy-up — the name is still copyable from the detail
sheet, which is weaker. Recorded rather than argued: if it turns out to matter, the fix is to
exempt the name cell's own text, not to put the narrow arm back, because that arm leans on `arm()`
clearing whatever the browser began selecting in the five pixels before the threshold.

**The selection bar's cell verbs are Set · Clear · Spread** (`CellSelectionActions`), drawn before
Locate and Highlight when the selection is cells. Set and Clear are Enter and Backspace with a
button on them, and take the container's gate (`cellKeyboardPermission`) and words
(`cellActionCopy`) so a button cannot promise a gesture the keyboard refuses; Output and a focused
template layer show them disabled with the reason, and Spread makes the same two refusals itself
(it is also drawn on the two plain routes, which have no scope). There is **one** Set —
its title names where the value lands (Local, or the focused Look's rows), and that is the scope's
answer rather than the press's. A "track it" arm the way a template chip has (⌥click) makes no
sense on a value: a typed number has no referent for a layer to follow, and *Make layer* on the
rail is how local literals become something trackable afterwards. Set and Clear keep their icons
at every width and only Spread folds on the phone arm, because on a phone Set is the only way into
a selection's editor now that a drag opens nothing.

**Spread reads the marquee, not the fixture selection**, and opens in `EditorSurface` like the four
cell editors. The column comes from the selection — `SpreadPopover` takes one `SpreadColumn` per
selected column, targets in visible row order, from the same `columnTargets` expansion
`commitToCells`, Backspace and the batch count use — and answers the panel's family from it; a
chooser is drawn only when the selection spans a desk-resolved plan and the raw Speed one. Focus
follows: with one column the first question is From and it is focused on open; with several it is
still *which*, so nothing is. Enter still *applies*, since Spread is the one panel here that does
not write as it is edited until Live is on. The two plain list routes select cells like the
programmer, but they *also* keep the whole-selection row Spread they always had
(`spreadColumnsForTargets`), drawn in the toolbar's `actions` slot with nothing selected but rows —
the cell verbs replace it the moment a cell is. The colour editor's **Spread…** is wired here too
(editor-kit session 3): `ColourCell` hands its RGB to the container as a one-shot the shape
`keyboardOpen` uses (`SpreadSeed` — RGB only, no emitter travels, the busk hand-over's rule), the
container threads it to row C's `SpreadPopover` as `seed`, and the panel opens on Colour with
*From* seeded and asks for the seed to be dropped.

### The programmer's scoped grid

Session 2a gave the programmer's value grid a **scope**, and it is the mechanism most of the session
rests on: Output (the cook, read-only), Local (what *you* set, and nothing else), or one focused Look
layer. Same grid, same cell editors, same drag-select in all three — that sameness is the point,
because it is what makes editing a Look feel local rather than like a trip to the library.
`components/programmer/ProgrammerScope.tsx` owns it; the band above the grid is
`ProgrammerScopeBand`, and a layer is focused by clicking its name badge in the stack rail.

**The programmer's chrome is one spacing system**, and every number in it is stated once here so a
row cannot drift from its neighbours (design record: `../docs/plans/programmer-chrome-design/`,
whose `INDEX.md` has the reasoning). A 12px gutter on every row, the `ShowHeader` included — and
that header is shared, so Show, the Prompt Book and Busk take it too, and on those three it differs
from the `ShowBar`'s own `@[440px]:px-4` by 4px, deliberately. Every chrome row is 40px and holds
32px controls, so the inset is 4px everywhere: row A, row B (`h-10`, `min-h-10` when folded), row
C (`SelectionBar`), the rail header — a tab strip since the editor kit's session 4, §The rail's
tabs — and the strip's chevrons, all level. Three control tiers by
nesting, and nothing else: 32 for a control on a row (`h-8` / `size="sm"`), 28 for a control inside
a control (`h-7`: Update and Revert in the source box, a template chip, `New`), 24 for a toggle
item, 20 for a pill. The header is 40px (`CHROME_ROW_CLASS`, `h-10 px-3`) at every height, not only
under 500 — it was 48 (`py-2`) until the busk-chrome plan's session B made it the shell's chrome row
too (D12). 8px
between controls on a row (`gap-2`), 6px inside a control. Row B's filter is a field from 360px of
row B up, says `Filter…` (`FIXTURE_FILTER_PLACEHOLDER_SHORT` — a placeholder cannot switch by
container query, and the two plain list routes keep the long form) with the whole hint on its
`title` and `aria-label`, and takes the row's slack before the spacer does (`flex-[999_1_0%]`
against the spacer's `flex-1`; the folded arm keeps plain `flex-1`, since there the spacer is
hidden). The key button is not drawn in layer scope — ownership tints are off there, so there is
nothing to explain — and `ScopedKeyPopover` holds the ownership key only. Two phone-arm folds
below `@[600px]`: the source box becomes `Q4 · Update · Revert` with the name and change count
hidden and the dirty state an amber dot on Update (count on its tooltip; a Look keeps its families
badge, being its only name there), and the fade trigger keeps its value and drops only its chevron
(86 → 48px); in layer scope the layer pill is capped at 120 and `Unsaved` / `Saving…` fold to a dot
on it with the word `sr-only`, while `Save failed` keeps its word at every width. The three
measured floors in `ProgrammerGrid` (`min-w-[min(410px,100%)]`, `@max-[739px]`, `@[840px]`) were
derived from a 285px iconic action bar and now carry ~38px of slack on the phone; move all three
together against 852×393 and 945×457, or none of them.

Things that will bite:

- **The grid must never remount on a scope change.** `useListSelection` clears its Redux scope on
  unmount, so a conditional mount or a `key` per scope silently discards the fixture selection
  Record scopes on. `ProgrammerPage.test.tsx` asserts `gridMounts` across a switch; that is the
  load-bearing test of the whole session.
- **A scope switch drops the marquee to its rows, it does not clear the selection.** The cells are
  scope-local and must go, but since the two selections became one (§One selection, two shapes) the
  rows had been cleared when the cells were selected — so clearing the cells outright emptied
  `selectedRowIds`, the desk bridge published `set([])`, and every other screen's target band and
  family pill went dark. `FixturesListContainer` converts instead, through the same row door every
  other row gesture uses, so the heads survive everywhere and only the mask is dropped: the reading
  the bridge already gives a mask another window made. **Never publish an empty selection the
  operator did not make.** Two things ride on that conversion. It **closes any open cell editor**,
  in a second effect and explicitly — the old clear did it by accident, by taking `selectionEmpty`
  across its false→true edge, and rows that survive keep that flag false; an open popover's fields
  are not disabled by a read-only scope (only the cell's trigger is) and `useCellWriters` has no
  Output arm, so a commit from a stale panel lands literals in Local. And the **conversion effect
  must stay declared above `useDeskSelectionBridge`**: effects run in hook order, and that is what
  makes a scope switch and a `selection.state` frame arriving together resolve with the desk
  winning.
- **`null` scope is not Output.** `/fixtures` and `/groups` mount the same table with no scope above
  them and must behave exactly as before — live values, editable cells, no em-dashes. Only an
  *explicit* Output scope is read-only. Pinned in `FixturesTable.test.tsx`.
- **`ChannelSource` is the wrong abstraction here** and was rejected: everything a derived source
  doesn't hold reads 0, and `holds()` is on `DerivedChannelSource` rather than `ChannelSource`, so it
  cannot express *unset* — which is the entire point of Local. A Look row can also name a group,
  which has no channel. `scopedCellValue.ts` instead feeds the *same* `aggregateCellValue` a lookup
  built from entries or rows, so the maths behind a cell is identical in every scope.
- **The Local predicate is `entry.owner !== 'layers'`**, not provenance: under blind, provenance
  reports what is *underneath* the programmer, and a parked property reports `PARKED` while still
  holding the operator's entry that Record would take.
- **An un-busked Local cell shows an em-dash but its editor opens at the live value** (`placeholder`
  on the four cells, `UnsetCellMark`). Local has to answer "what will Record take?" by itself, and a
  busk still has to start from where the rig is.
- **A layer-scope edit is a live write.** It goes through `PUT /looks/{id}`, which republishes every
  cue layering that Look — the point of composing in place. `LookRowDraft` coalesces at 400 ms with
  a 2 s ceiling, and **flush cadence is stage-update cadence**: a colour drag steps the rig rather
  than gliding, which the band says out loud. There is no smooth-preview escape hatch — backend
  sweep item D4 deleted the Look preview routes and `ProgrammerLayerStack.installPreview` with
  them, so no layer can carry an unsaved draft any more and `ProgrammerLayer` has no `isPreview`.
- **`RowCell.targetKeys` is index-parallel to `resolutions`** and `keys` is not: one resolution can
  contribute two keys (a position paired from pan/tilt sliders), so for a group row the two arrays
  share neither length nor indices. Ownership never noticed because it collapses to one verdict.
- **Widening a layer's targets is always explicit** — the `AddToTargetsButton` on the row, never a
  side effect of dragging a marquee across the grid.
- **A focused template layer is a read, never an edit**, and the two kinds read differently. A
  **value** template shows no rows at all: projecting its generic row onto every targeted row would
  silently convert it to a per-fixture one on the first edit, which is a change to what the template
  *is* made by someone adjusting a value. An **effect** template shows the **live** value on the
  cells it drives — ringed by `layerCellClass`, with the wave and the division in `FixturesTable`'s
  bottom-right corner slot (free there, because ownership is switched off in layer scope) — since an
  effect is one rule for every head and what is worth watching is what it is producing now. Per-element
  Look rows stay out too; all three cases are named in `LayerRowNotices` (`FU-LOOK-ELEMENT-ROWS` for
  the last).

  `LookRowStore` engages **only for a LOOK layer**, so a template layer's answer comes from its
  sibling `FocusedTemplateLayer` — one context above the grid, read by `useScopedRowValues`,
  `LayerRowNotices`, `ProgrammerScopeBand`, `AddToTargetsButton` and `SpreadPopover`, because that
  hook runs per row and a query in it would be a subscription per visible row. Until that arm
  existed the layer scope fell through to *no* states, which the grid renders as live editable cells
  writing straight to Local while the band overhead says "One layer" — the notice had been claiming
  the opposite since it shipped.

  **"Read-only" has to be said in four places, not one.** `CellState.editable` reaches only the
  pointer (`pointer-events-none` on the wrapper); the cell trigger stays tabbable, so `PropertyCell`
  takes `disabled` from it too; `SpreadPopover` — which writes through the desk's spread route or
  `useCellWriters` from the toolbar, nowhere near a cell — gates on the focused template as well; and the marquee's
  **keyboard** (the Enter/character arm of the grid's window handler, §The programmer's keyboard)
  gates on `cellKeyboardPermission`,
  because the marquee itself arms in every scope — its `pointerdown` sits on the rows wrapper and a
  read-only cell's `pointer-events-none` only retargets the press there. A commit through any hole
  is not dropped: `useCellWriters` has no arm for a template layer, so it falls through to a **live**
  write and puts literals in Local. The Spread gate is on the *template* case only, not on layer
  scope generally: a focused Look layer has a row draft and the spread lands in it through the
  route's `write: false` arm (§Sheet kit) — and the
  keyboard makes the same split, taking a typed value into a Look layer's draft and refusing it on
  a template layer and in Output.

  An untargeted row in a template layer is painted dashed like any other, so `AddToTargetsButton`
  reads whichever layer context is live — it is the only way a layer widens, and a tone with no way
  out of it is worse than no tone.

  `+ Effect` is disabled on a focused template — not D7 any more, but because a template holds
  **one** thing, chosen when it was made: there is no second effect to add, and adding a first
  to a value template would flip its identity.
- **In Output scope every tint is a destination**: clicking a cell jumps the scope to whatever won
  it. Three guards, and the middle one bites — `ProvenanceEntry.layerId` is present for a **cue's**
  layers too, so `focusLayer` checks membership in the programmer's own stack and reports failure.

**The FX band's row is two lines**, because one could not hold it: name, tempo and division with
the menu on the first, and property, **target** and home on a wrapping second. It was one flex line
of `shrink-0` chips in a 404px rail, so the name — the only thing allowed to give — was squeezed to
nothing and the menu button was pushed past the rail's edge; a `@[320px]` on the home detail was
meant to hide it narrow but the nearest `@container` is the whole workspace. The target is new:
`ActiveEffect` is one instance per target, so eight loose heads under one chase are eight rows, and
the key is what tells them apart. Only the layer *position* is written out beside the home badge;
the other two details ride the badge's title.

`+ Effect` follows the same rule as a value edit: focused layer → into that Look (via
`POST /looks/{id}/absorb-effects`, which *moves* the running instance); Local → the programmer band,
which Record writes onto the cue; Output → disabled, naming the two places that can take one.
**Make layer** promotes a Local selection into a named Look applied here — record-look, then
`addLayer`, then `clearEntry` per row taken, so what you promoted leaves Local and the rest stays
yours. It is a sequence, so a failure part-way leaves the Look and says so.

### The rail's tabs

**The rail's header is a tab strip — Stack · Colour · Spread** (editor-kit plan session 4,
`../docs/plans/editor-kit-design/RailTabs.dc.html`). **Stack** is the rail as it always was,
`LAYERS n · FX n` its face and the body and footer unchanged; **Colour** and **Spread** are the busk
sheet's two docked editors hosted over the marquee (`programmer/RailColourTab.tsx`,
`RailSpreadTab.tsx`) — a long busk over one marquee with the grid uncovered, where a cell's popover
is the quick form. The strip is the header row itself, the mode toggle and the chevron after it; a
second tab row was declined. Its words fold by the busk sheet's rule, `tabWordClass`, which lives in
`components/sheet/sidePanel.ts` now as the panels' shared chrome: the strip's unpadded wrapper is the
`@container`, and below 400px of strip only the open tab keeps its word — every width the rail has,
the strip being the rail less its toggle and chevron — while the Stack tab folds to the collapsed
strip's glyph-and-count pairs, so the counts are never lost. `RailTabs.test.tsx` pins that as an
ordering.

Six rules, each with a reason:

- **Docked only.** The push-mode narrow arm (704–1200) and overlay mode both shut on the next press
  outside the panel, which a picker over the grid has to survive — so the strip carries
  `@max-[1200px]:hidden`, overlay mode draws the plain face, and the strip writes the tab back to
  Stack when it sees its own box go to zero (`RailHandleFrame`'s trick). The collapsed strip's
  palette and wave cells, which expand the rail onto their tab, are docked-only the same way; the
  phone's bottom sheet keeps the stack.
- **The fact rests on Stack and is not persisted** (call 10). `railTab` is plain state beside
  `collapsed` in `RailArm`, a collapse forgets it, and a strip count opens the Stack tab. A rail that
  opened on a picker for a marquee that does not exist yet would be a panel saying nothing.
- **The marquee is published, not moved.** The cells stay local state in `FixturesListContainer`
  (`useCellSelection`'s reason); the container publishes what its own editors are handed —
  `marqueeBatches`, `columnTargets`, the rows' heads for a rows-only selection, the scope's
  `cellKeyboardPermission` and `scopeLabel`, and a throttled `commit(col, …)` — into
  `fixtures-list/marqueeContext.ts`, a **store** `ProgrammerPage` provides above the grid and the
  rail. A store and not a context value: the marquee moves at pointer rate and only a mounted tab
  should hear it. The plain lists mount no store, so they publish into nothing.
- **A tab claims its own column's open gesture** (call 9). With Colour open, Enter, Set, a typed
  character and a double click on a Colour cell land in the tab — R focused, the character seeded —
  and no popover opens; with Spread open, row C's Spread focuses the tab's From, and a colour cell's
  *Spread…* seeds the tab rather than the popover. Every other column opens its popover. The claim
  is `programmer/railTab.ts`'s `RailTabClaim`, which `ProgrammerWorkspace` provides around *both*
  its children, keyed on the drawn tab alone; the keyboard's branch is `useCellEditorRequests`'
  `intercept`, the double click's is `ColourCell`'s `onOpenChange`.
- **The tabs write where the grid is pointed.** The page keeps an outer `live` `EditorContext` for
  the rail's FX controls, so the tabs mount `ScopedEditorContextProvider` — the grid's own
  derivation, extracted — and a value lands in a focused Look layer's draft, not in Local. The
  Colour tab writes through the published commit, which is the container's column commit on its
  ~30 Hz throttle; the Spread tab's plans come from `useMarqueeSpreadPlans`, lifted out of
  `SpreadPopover` so the two build one plan list. Output and a focused template layer are
  read-only in both, with the cell's and the popover's own words (`ColourEditor`'s `readOnly`,
  `SpreadPanel`'s docked refusal); Pick stays live.
- **The Colour tab's emitters start unstated.** Its buffer is its own and seeded by Pick, as the
  busk tab's is, but an emitter stays `undefined` — which the writer samples from the wire — until
  the operator states it, and a new set of heads unstates it again: the appearance store has no
  per-emitter reading, and a 0 would drive every white LED in the marquee dark on the first byte.
  Each tab draws the label line (*4 heads · Local*, call 11 — `EditorLabelLine`'s `docked`) and no
  Recent (D11: row C's strip is on screen). The rail's floor stays 260 (call 12): the Spread panel
  takes its compact curve row below 300 of rail (`SPREAD_COMPACT_WORD_CLASS`, a query on the docked
  panel's root). That root is the busk Spread tab's too, so the busk sheet carries the container as
  well; it never matches there, because that sheet's floor is 320.

### Speed Masters

Named tempo buses. Effects subscribe to one by uuid rather than owning a speed, so
retuning a master moves every look that follows it. **Master 1 is the global tempo**:
every legacy surface means it, every unassigned effect resolves to it, and it cannot
be deleted.

Two different BPMs live on a master and the UI must not conflate them. The **stored**
bpm (`useSpeedMasterListQuery`) is what it boots at; the **live** bpm
(`useSpeedMasterLiveQuery`, streamed over `speedMasters.*`) is what it is running at
now. The sheet's BPM column shows the live one and edits it with TAP / click-to-type; the stored
default is its **Start** column (labelled as the boot tempo) and the detail sheet's Starting BPM
(library-sheets plan D7). On the running project the desk couples the two — a live change is
written back to the row after 750 ms, and a stored tempo PUT retunes the running clock — so they are
two columns because they are two routes, not two independent numbers (§Library sheets).

`/projects/:id/speed-masters` manages the bank — one nav entry, one route, no sibling
switcher. `components/SpeedMasters.tsx` is the desk's performance surface, with **two hosts**
since `PD-SPEED-OVERLAY` — the ShowBar, and the Speed Masters *overview panel* that reaches the
bank from a view with no bar (see §Navigation Registry) — and it shows
**every** master, master 1 included. It used to render 2..N on the reasoning that the
ShowBar's own BPM tile *was* master 1; that tile is gone, and the split was the width bug —
two thresholds fired at 560px in opposite directions, so between 560 and 900px the tiles and
the transport left the live-state block nothing and its cue numbers spilled. Don't
reintroduce it: the component's docblock is the record of why. It picks one of three arms
from the bar's `@container` width **and** the master count (`ARMS.shared`, because a container
query cannot see how many masters there are): a named tile each, one railed tile with a pill
per master, or `SpeedMastersChip` at the bottom.

**A host states one thing — `room` — and it picks a width ladder, not an arm.** `ARMS.shared` is
the bar's and `ARMS.dedicated` the overview panel's. The bar's thresholds were never about whether
the tiles *fit*: a tile is ~150px and every one of them comes out of the live-state block, the only
`flex-1` item on that row, so 1600px for four masters is what they cost the cue numbers beside
them. A panel owns its row and is competing with nothing — measured, four masters are 464px of
tiles at a narrow container and 711px at a wide one — so it tiles from 620px and rails from 240px
instead of 440px. 620 rather than something rounder because 788px is a real panel: what a
landscape phone at 852 leaves after the rail. Each dedicated threshold is sized against the tile's width *at* that threshold,
since the tile grows with its room; a flat per-tile figure is wrong at both ends. **The 5+ ceiling
does not move**, because that one was never a width judgment. What a host must never gain is an
*arm* of its own — its own readout, tile or ladder shape — which is how the split brain comes back.
No dedicated threshold may equal its rail width: the chip's hide class and the rail's show class
are a min-width pair at one breakpoint on one element, at equal specificity, so Tailwind's utility
sort would decide it. Write no container-query class in prose, here or in a comment — Tailwind
scans comments, and a placeholder in that shape is emitted as a real rule that fails the build in
`lightningcss`.

**Which master the rail is showing is a `createSyncStore` singleton**, not `usePersistentState` —
the same move `useVisSource` made, for the same reason and now for a second pair of surfaces. Two
mounted hosts each read the key once in a `useState` initialiser with no storage listener, so they
drift the moment one writes, and here the drift is not cosmetic: the selected master *is* the tile,
so its TAP and click-to-edit BPM are the controls on screen, and a press in one host would retune a
master the operator is reading in the other. The key is unchanged (both paths decode with the same
`JSON.parse`), so desks keep the master they were on. `SpeedMasters.test.tsx` mounts both hosts and
pins that they move together; the store is exported so a suite can `reset()` it, because a
module-level cache outlives `localStorage.clear()`.

**The count of surfaces offering TAP and click-to-type stays at four.** The overview panel mounts
`SpeedMasters` whole — the same `MasterTile` and `MasterRow`, not a copy — so it is one of the
four relocated, never a fifth.

**A master can also declare a `usage` and follow another master.** Both landed with the busking
view's speed-master work, and both are edited in `SpeedMasterDetailSheet` and in the Speed Masters
sheet's Usage, Follows and Ratio columns (§Library sheets):

- **Usage** (`dimmer` / `colour` / `position`) is the **apply-time routing default**. An effect
  created with no explicit master is *stamped* with the usage-matching master's uuid at the moment
  it is created — `useSpeedMasterForCategory` in `store/speedMasters.ts`, the rule itself in
  `lib/speedMasterModel.ts`. Nothing resolves usage later, and `null` still means master 1
  everywhere; that invariant does not move. **Its caller is `TemplateEditor`**: an effect template's
  master is stamped when the effect is chosen, so a Colour effect template picks up the master whose
  usage is `colour` without the operator being asked. The hook spent a while with no caller at all —
  the busk view's ad-hoc effect pads were the original one, and they went when that view was brought
  back onto its design — and was kept for exactly this. Every other half of the rule stands: a master
  declares a usage, the detail sheet sets it, `BuskSpeedRail` badges it.
  Usage is unique per project (the server 409s `SPEED_MASTER_USAGE_TAKEN`), and `controls` and
  `composite` are deliberately not routable — those land on master 1, which is what an unmatched
  category is defined to do. `speedMasterModel.test.ts` pins the vocabulary against
  `EFFECT_CATEGORY_INFO` the way `maskPicker.test.ts` pins the family lists.
- **Follow** (`followNum` / `followDen`, both null = manual, plus `followTargetUuid`) makes a
  master run at `leader.bpm × num/den`. The server owns that arithmetic *and the timing*: a
  follower's clock is **driven** by its leader's tick, so the two beat together rather than merely
  running at proportional speeds — say that, not "derives its tempo", when explaining the switch.
  Live values arrive as ordinary `speedMasters.changed` frames, so **this side never computes a
  follower's live tempo**, only previews and labels. Master 1 itself may never follow, so the
  sheet hides the control for it rather than disabling it.
  - **`followTargetUuid` is the leader; null means master 1** — the spelling every row written
    before targets existed carries, and the one the sheet sends when master 1 is picked, so
    "master 1" has two representations and comparisons must normalise (see `canonicalTarget` in
    the sheet). Chains are legal (M3 → M2 → M1) and loops are refused server-side
    (`SPEED_MASTER_FOLLOW_CYCLE`), which is why the picker filters through
    `eligibleFollowTargets` — self and descendants excluded — rather than letting the operator
    discover the rule by hitting a 400. Every read-only surface names the leader through
    `leaderLabelOf` / `leaderNameOf`: a bank prop is threaded to each of the four so a follower
    of M2 never reads "follows M1" — **including in its `aria-label`**, which is where three of
    the four kept saying "Master 1" after the visible text was fixed.
  - The REST row carries the **stored** target; the WS live frame carries the **resolved** one.
    They normally agree — a forced delete of a leader unlinks its followers server-side, so no
    route leaves a row naming a master that is gone. They can still diverge on a row no route
    wrote (an import, a hand-edited database): the bank degrades a dangling or looped link to
    manual while the row still advertises it, so the manage page would show a follow badge for a
    master the desk is running manually and a ratio-only PUT on it would 400 with
    `SPEED_MASTER_FOLLOW_TARGET_UNKNOWN`. Prefer the live frame wherever the question is "what
    is the desk doing".

**A follower's tempo cannot be typed or tapped, and exactly four surfaces offer those:**
`MasterTile` and `MasterRow` in `components/SpeedMasters.tsx`, the Speed Masters sheet's BPM cell
and TAP read-out in `components/speedMasters/SpeedMasterSheet.tsx`, and `MasterCard` in
`components/busking/BuskSpeedRail.tsx`. The sheet's pair replaced `SpeedMasterRow` in that count
(library-sheets session 1) and keeps its refusals: a follower's BPM is a read-out (`value:
undefined`, so a marquee over it skips it) and its TAP is disabled with the reason. It adds one
refusal of its own — **off the current project both are read-only** — because they write the running
show's clocks. The other three still swap TAP for the ratio and stop opening the draft. Those four
are all of them: the fifth, an unarmed master-1-only TAP in `EffectsOverviewPanel`, went when that
panel did.

**The busk rail is the second surface that can *write* a follow ratio**, after
`SpeedMasterDetailSheet`; its five chips retune a link that already exists. Both write it the same
way and must keep to both of the sheet's rules: **both halves of the pair or neither** (a half-patch
is a 400), and **never `bpm` beside them** — the server refuses that combination on a follower,
whose tempo comes from its leader rather than from a stored default. The chips deliberately send
**no `followTargetUuid`**: they retune an existing link, and a ratio-only patch carries the stored
leader forward server-side, so sending one would let a chip press re-point the link. Linking and
*unlinking* are the detail sheet's **and the Speed Masters sheet's Follows column's** (Manual, or
Clear, unlinks; a leader links or re-points), under the same rules — both halves or neither, never
`bpm` beside them — and its Ratio column retunes as the chips do; the busk rail still only retunes,
since retuning a link mid-show is the half that belongs on a performance surface.
`speedMasters.error` is the backstop for writers with no affordance to remove (a MIDI surface, a
script, a stale tab); `store/speedMasters.ts` toasts it, keyed per master so a burst of hardware
taps replaces rather than stacks.

Two traps in that area:

- **`speedMasterLive`'s field-wise merge must copy every new field.** It writes named fields into
  the Immer draft rather than replacing the array (so a bpm push doesn't churn tile identity), so
  a field it forgets is written once from the first frame and never again — a usage retagged in
  another tab would leave this one routing to the old master for the life of the page.
- **Usage and follow ride `speedMasters.state`, not `.changed`.** The change frame is the tempo
  push and says nothing about routing.
- **Never render a link PUT's response `bpm`** — it is the pre-link stored value beside the ratio
  it just accepted (`FU-SPEED-LINK-PUT-STALE-BPM` in lighting7). The state frame corrects it.

Two independent per-effect references, both uuid-addressed:

- `speedMasterUuid` — which tempo an effect's beats come from. BEAT effects only.
- `rateSpeedMasterUuid` — scales a **WALL_CLOCK** effect's cycle (`bpm / 120`). Beat
  effects never read it.

`EffectParameterForm` gates on the library entry's `timingSource`: a wall-clock effect
gets "Cycle length (seconds)" and the rate picker, a beat effect gets beat divisions and
the speed picker. Showing both to both was the pre-existing bug — a wall-clock effect's
"Speed Master" did nothing at all.

`BeatIndicator` pulses from the keyed `speedMasters.beat` stream, always — the unkeyed
legacy `beatSync` it used to fall back to is gone from both sides (backend D2). Omitting the
master, or passing master 1 with a null uuid, resolves to master 1's **real row uuid** through
`useMaster1Uuid` in `store/speedMasters.ts`: null means master 1 only on the tempo *write*
messages, and an `''`-keyed subscriber matches no frame at all. Server frames are throttled
(one per 16 beats), so the component free-runs a local timer in between — that interpolation
is load-bearing, not decoration.

### Windows, full screen and the hand

**A window is a socket carrying a client-minted identity** (multi-screen plan D9, D10; lighting7
d774fd9 is the wire, and where it and the plan's §3.4 sketch differ, the commit wins).
`lib/windowIdentity.ts` holds both halves in **`sessionStorage`, never `localStorage`** — the two
desk screens are two windows of one browser profile, and `localStorage` is one value per origin per
profile, so an identity kept there would be one identity for both screens. `windowId` is a uuid
kept for the tab's life once minted — a reload keeps it, and only a `?window=` boot mints a fresh
one, even over storage a cloned context inherited (its own bullet below); the name is
`?window=Screen%202` from the launch URL, read
once at boot before the router is created (`main.tsx`) and stripped, else *Window* plus a suffix,
and it can be renamed (`renameWindow`, a subscribable so the chip, the user menu and the announce
all move). Three things about the registry (`api/windowsApi.ts`, `store/windows.ts`):

- **The announce carries exactly `windowId`, `name`, `view`, `fullscreen`, `follows` — and
  `viewOptions` only while the view contributes any.** The desk's Json is bare — no
  `ignoreUnknownKeys` — so one extra key makes the whole frame undeserializable and it is dropped
  with a server-side log line only. The symptom is a window that never appears in `windows.state`;
  `windowsApi.test.ts` pins the key set — five keys plus `type`, or six with `viewOptions` — and
  `id` and `user` are the server's to say. It goes out on every `Status.OPEN` (the second legitimate
  `open` re-send, §Where a WS bridge subscribes) and on every change — route, full screen, follow,
  rename, a busk fact — from one effect in `useWindowsBridge`. It is **handled only once the show
  is warm**: the frame waits in the socket's incoming channel through boot, so `windows.state`
  arrives empty behind the boot overlay and fills itself when the show is ready. There is no retry
  timer; do not add one.
- **`viewOptions` is a free `string → string` map, and the registry never learns its vocabulary**
  (busk-further plan D13; lighting7 `6e2cc72`). On the busk view it carries `focus`,
  `sheet`, `pageFollows` and — only while the page is unlinked — `page` (the split's height is
  pixels on this window's screen and is not announced; the `rigRows` count it replaced was, and
  nothing ever read it); a following window
  announces no page, because the desk's showing page is the desk's to say and the Screens sheet
  reads it for every following row alike. It rides back on `windows.state`, which is what the
  Screens sheet draws a row's Focus · Sheet · Page controls from — generically, off the `options`
  descriptor on the row's *current* entry in `lib/windowViews.ts`, so a Prompt Book row draws only
  its Chrome segment (§Windows, full screen and the hand) and the sheet never learns the word busk. The fourth command,
  **`windows.viewOptions {targetId, view, options}`**, is rebroadcast like the other three; the
  named window applies it **for that view only** — a busk frame arriving at a window on the Prompt
  Book is ignored rather than stored for a later visit — through `applyImmersiveViewOption` on a
  view whose descriptor carries it, `applyBuskViewOptions` on the busk view and
  `applyStageViewOptions` on the Stage view, and re-announces. `{sheet: 'toggle'}` flips
  the fold and the last open tab (a MIDI `BuskSheetToggle`'s spelling); `{page: n}` **unlinks**
  that window onto the page exactly as arriving with `?page=` does; and **`{pageFollows}` is
  applied** since desk-follow session 3 (D6) — `'true'` pages the window with the desk again,
  `'false'` keeps the page it is showing as its own, and a frame carrying both resolves on
  `pageFollows` (§The busk layout). It is what the busk row's **Page segment** writes — *Page ·
  Paged with the desk | Own page*, *With desk · Own* below 390px of row (the descriptor's
  `shortValueLabels`, accessible name *Paging on <window>*), drawn as one group with the page
  picker, whose visible label it stands in for. **The picker follows the row's state**: on a
  paged-with row it pages **the group** (`busk.setPage`, as a tab click there does, so every
  window paged with the desk moves), on an own-page row that window alone (`{page}`); the *follows
  the desk* / *own page* caption beside it went with the segment. The current view is read at
  command time through a ref, because a ⌘K *Show Busk on X · Focus pads* is two frames in a row
  and the second must see the route the first moved the window to. *Copy link for <name>* on a row
  mints `?window=…&page=…&focus=…&sheet=…&immersive=on` (`windowSetupUrl`; the last only while it
  is on): the row's whole setup, which a
  fresh window latches on arrival — the page only while the row holds one of its own, since a
  following row's null desk page is not "the first page" and writing it would unlink the new
  window where this one follows. **A following row's link carries no page even with the desk on
  one**: the code wrote the desk's page whenever it was non-null until session 8, and
  `ScreensSheet.test.tsx` now pins a following row against a non-null desk page.
- **The fifth command, `windows.follow {targetId, on}`, sets a window's selection follow**
  (desk-follow plan D4; lighting7 3f4fb5b) — `windows.fullscreen`'s exact shape, rebroadcast like
  the other four, nothing written server-side. It is **not a view option**, because follow is the
  window's and not a view's and a Programmer row carries no busk options; the Screens row's
  Selection segment and ⌘K's per-window arm send it (`setWindowFollow` in `store/windows.ts`). The
  named window applies it in `useWindowsBridge`: `on` is `relinkToDesk`, `off` is `unlinkFromDesk`
  over the desk's fact as it stands — **refused** on the busk view in Rig or Pads focus
  (`followIsForced`, §One selection, two shapes), and a no-op for a window already local, which
  would otherwise re-snapshot the desk over the operator's own selection. **It re-announces either
  way**: a flag that moved goes out through the announce effect, and one that did not — a refusal,
  a no-op — re-sends the last announce, so a row a sender drew from a stale frame corrects itself.
  Like a show, a follow aimed at a disconnected window is lost (`FU-WINDOWS-SHOW-OFFLINE`).
- **The row's `id` is socket-minted and is what every command addresses**; the `windowId` is how a
  tab recognises its own row (`thisWindowRow`, first match). A duplicated tab copies its storage, so
  two rows can share a `windowId` and cannot be told apart from this side — D9 accepts that, the
  Screens sheet shows two rows, and `FU-WINDOWS-OWN-ROW-ID` is the exact fix.
- **`?window=` at boot mints a fresh `windowId`; a reload keeps the one it has** (session 2.5).
  `sessionStorage` is *cloned* into a top-level context created from an existing one — a
  `window.open` without `noopener`, a `target=_blank` link — so a second desk screen can wake up
  holding the first's id. A window is never told its own row, it *infers* it by matching `windowId`,
  so both screens would match both rows and the chip would read *Desk* — "I moved it" — when the
  twin moved it. The parameter means "a deliberately-named new window", which is exactly the signal
  that this context is not a continuation of the storage it woke up with; presence of the key is
  the signal, a blank value included, because a shared identity is the worse failure. The invariant
  on the other side is the regression to watch for: the parameter is **stripped** at boot, so a
  reload carries none and keeps its id — minting there would churn a registry row on every refresh.
  Both facts are read through one memoised `consumeLaunchParam`, because `main.tsx` calls only
  `windowName()` and the URL is rewritten by whichever of the two asks first. The **name does not
  follow**: a stored name still beats the parameter, so a `?window=` boot over cloned storage is a
  fresh id under the inherited name — two rows sharing a *name* is what D9 already accepts, and
  only the shared *id* was the misattribution. A window opened by the Screens sheet goes out with
  `noopener`, which per spec makes it a new browsing-context group rather than an auxiliary one and
  so should not clone at all — **still asserted from the spec, not observed**: the desktop app's
  Chromium preview pane creates no child context for any of the three routes (`window.open` plain,
  `window.open` with `noopener`, a `target=_blank` click), converting each into a navigation of the
  *current* tab, so neither route could be shown to clone there. What that pane did show is the
  same-tab half of the rule: each of those `?window=` navigations minted a fresh id and kept the
  stored name. The clone itself is the desk's to see, on a tray item, a Dock app or Safari.
- **Every socket receives every command, the sender included** (D11), so each handler's first act
  is comparing `targetId` to this window's row id, read at command time from the last state frame
  — which is also what makes this window's own `show` for another window a no-op when it comes back.
  `windows.show` is a `navigate(view)`: the show-editing lock is per tab and defaults locked, so
  `/show` lands locked; an open cell editor unmounts with its route and the desk bridge never
  publishes on unmount; and a **guarded sheet declines** — the toast names the view and carries a
  button that goes. `windows.rename` is **not applied server-side**: the target renames itself and
  re-announces, which is what makes the name survive that tab's reload, so a rename aimed at a
  disconnected window changes nothing, exactly as a show does (`FU-WINDOWS-SHOW-OFFLINE`; do not
  retry). `windows.fullscreen {on:false}` calls `document.exitFullscreen()`; `{on:true}` cannot call
  `requestFullscreen` without a gesture and raises the *Return to full screen* banner instead.

**Full screen** (`lib/fullscreen.ts`, plan §3.6): `requestFullscreen` on the document element from
a gesture — the user menu's item, ⇧F, the ⌘K command, the Screens sheet's button — then
`navigator.keyboard.lock(['Escape'])` **feature-detected**, so on Chrome at a secure origin Esc
reaches the sheet's clear-selection rather than the browser, and on Safari it quietly does not. A
`fullscreenchange` listener is the truth (Esc, a tab switch and another app all leave without
telling the requester), and it keeps a `sessionStorage` flag so a window that was full screen and is
not now — a reload, a crash, a `{on:true}` from another screen — draws the one-tap banner at the top
of `<main>` (`ReturnToFullscreenBanner`, beside `SyncReauthBanner`). In full screen the app draws a
small exit glyph on the user menu's label line and nowhere else — never a floating button on a live
view. **Safari on the Mac is a first-class desk browser (D13)**: every Chrome-only piece — Keyboard
Lock, `getScreenDetails`, the *Open a window on… Display N* row and the *Open <view> on another
display* commands — is feature-detected and its absence is *quiet*: no item, no gutter, no disabled
control saying "use Chrome".

**Immersive is a per-window fact for all four live views, and it is not full screen** (busk-chrome
plan D7–D10; `Immersive.dc.html` is the layout authority). `desk.immersive` (`off` | `on`, default
`off` on every surface) lives in `lib/immersive.ts` on `createSyncStore` over `sessionStorageArea` —
per tab, beside the follow flag and the full-screen flag, **never `localStorage` and never the
desk's**, because the flow it exists for is a busk window with no app around it beside a programmer
window with the app drawn. `Layout` reads one boolean, `useImmersive() && isLiveViewPath(pathname)`,
and while it holds skips the `<aside>` sidebar (**hidden, not collapsed**), the app `<header>`, the
four overview panels and the sidebar's `marginLeft`; the panels' stored visibility is untouched, so
they return on exit. On every other route the app is drawn whatever the fact says — the fixtures
list is navigated *from* the sidebar — and a window that leaves a live view for it gets the app back
and finds immersive waiting when it returns. Banners, `HandChip`, the AI panel and `DeskDndProvider`
are untouched. Full screen and immersive **compose** (D8): neither flips the other, and a windowed
browser can be immersive while a full-screen one shows the app.

**The `ShowHeader` is the row immersive leaves standing, and it carries the way back** (D11, D12).
It is a 40px chrome row on all four live views now — it takes `CHROME_ROW_CLASS` itself, `h-10
px-3` with its 1px border inside the 40, the shell's 32px controls; it was 48, and the programmer
chrome doc's "48 at every height" is revised by this. The plan spelled the change `px-3 py-1`, which
measured **41** on the box in the browser because the border sits outside the padding; the class
every other chrome row uses is the height, so the header takes the class rather than restating the
number — with the
expand glyph (`ImmersiveToggle`) drawn by the header itself **after the host's `actions` and before
the switcher**, so every host gets it with no per-host wiring: outward arrows with the app drawn,
inward arrows lit while immersive, one control both ways. Folding the header too was declined
because it would take the switcher, Stop, the live dot and the way out, and the way back would be a
floating button over a live view, which the full-screen work already refused. Chrome above the busk
band went 152 → 88 → 40. What the app header took with it comes back **where it is read** (D10): a
red *Offline* chip on the `ShowHeader`, drawn only while immersive *and* the socket is down
(`useIsDeskConnected`) — a pill reading *Connected* all night is what immersive exists to remove;
below `md` the mobile drawer's **button** at the header's left edge while immersive, since the
hamburger in the app header was the only navigation there (the drawer and its open state stay
`Layout`'s, and `components/mobileDrawerContext.ts` lends the opener); the blind report and value
count on the busk Show tab's strip — and, since that strip is invisible with the sheet folded or
another tab open, the band's amber `BLIND` pill and the Show glyph's dot (§The rig, §Focus and the
side sheet); theme, full screen and Screens… through ⌘K — the theme had no
command until this, so `lib/theme.ts` became a module-level store (bare string, not `syncStore`'s
JSON — `getInitialTheme` reads it before React exists) that the user menu's row and the new
*Switch to dark mode* / *Switch to light mode* command both read. The four overview panels' ⌘K
toggles are withheld with the panels, since a row reading *On* for a panel that is not on screen
is a control reporting a state it is not in. **Every live route's loading and not-found arms
draw `ImmersiveEscape`** — the header's own glyph on a chrome row, or bare inside
`SheetPage.Header` — because those arms render no `ShowHeader`, and an immersive window on a
missing project would otherwise show an error card with no control at all: ⌘K still worked, which
is why it survived a desk with a keyboard, but on a touch screen there was no way back and the
fact is per-tab and not in the URL. Not a floating button: the header's row, minus the header, so
D11 holds.

**Nothing new on the wire** (D9): `immersive` rides `viewOptions` under every live view — the
Programmer, Show and Prompt Book entries in `WINDOW_VIEWS` carry a one-entry `options` descriptor
(`IMMERSIVE_OPTION`) and Busk carries it fourth — because the desk's Json is bare and a sixth
top-level announce key would drop the frame (`windowsApi.test.ts` still pins the key set).
**The Stage view is a seventh `WINDOW_VIEWS` entry** (stage-view plan session 1), between Busk and
the libraries, carrying two options — `viewpoint` (`STAGE_VIEWPOINT_OPTION`, *Viewpoint · Orbit | Eye |
Plan | Front | Side*) and, since session 3, `source` (`STAGE_SOURCE_OPTION`, *Source · Output | Next
GO*, an `enum` segment) — and **no immersive** — it is a window view, not a live view, so it has no
`ShowHeader` to leave standing. `announcedViewOptions` takes the Stage options (`stageViewOptions`:
the viewpoint and the source) as its fourth argument and sends them only under Stage;
`applyViewOptionsFor` applies `immersive` only on a view whose descriptor carries it and the two
only on Stage (`lib/stageViewpoint.ts`'s `applyStageViewOptions`, which ignores a value outside each
vocabulary — a camera, a saved view's uuid since session 2 or an unsaved seat since session 3; one
of the four vis sources). ⌘K gains *Show Stage on <window>* and *Open Stage on another display* from
the same list, and `windowSetupUrl` carries `viewpoint=` and `source=`, which the Stage route applies
on arrival and strips (`consumeLaunchStageOptions`). **Since
session 2 the option is a `picker`**, a third `WindowViewOption` kind whose control the *view*
supplies: `Layout` hands `ScreensSheet` a lazily-loaded control per option key (`controls`, today
`StageViewpointRowPicker` for `viewpoint`), so the sheet draws the cameras, saved views and seats
without importing the Stage view or learning its vocabulary, and the scene queries load only when a
Stage row is drawn. A key with no control reads its value as text.
`announcedViewOptions` in `lib/windowViews.ts` is the one place that says which keys go out under
which view; the two libraries still send the five-key frame. `windows.viewOptions {immersive}` is
applied on any of the four through the same per-view gate as the busk keys — a Show frame arriving
on the Prompt Book is still ignored, on purpose: the frame is a statement about the view the sender
was looking at. The Screens sheet draws a **Chrome** segment (*App · Immersive*, the descriptor's
`valueLabels` over `off` | `on`) on every live-view row from the descriptor, generically, and never
learns the word; `windowSetupUrl` carries `immersive=` beside `page`, `focus` and `sheet` **only
while it is `on`**, since `off` is what every window boots with. `?immersive=on` is consumed once at
boot through the same memoised `consumeLaunchParam` read `?window=` uses (`launchImmersive`) and
stripped in the same `replaceState` — one read, because whichever ran first would rewrite the URL
for the other — and it is **not** mirrored into the address the way the busk view mirrors `?focus=`,
because it is a window's fact and not a view's; a plain `?immersive=on` arrival is not a `?window=`
boot and mints no fresh `windowId`. ⌘K offers *Expand over the app* / *Show the app* for this window
only while it is on a live view, the label flipping with the state like the full-screen pair, and a
*Show <view> on <window> · Immersive* arm on every live view beside the busk focus arms. A MIDI
`ImmersiveSet` target is `FU-SURFACE-IMMERSIVE-SET`, not built.

**The one rule underneath all of it is the secure context** (`../docs/desk-screens.md`):
installation, Keyboard Lock and Window Management exist only on a potentially-trustworthy origin,
and the desk serves plain HTTP — so the two desk screens must be opened at `http://localhost:8413/`,
and the iPad at the `.local` name gets the Fullscreen API and nothing else. Nothing in the UI says
so; a desk screen whose Esc keeps leaving full screen has almost certainly been opened at the LAN
name. `public/manifest.webmanifest` (`display: fullscreen`, `display_override: ["fullscreen",
"standalone"]`, no service worker) plus `<link rel="manifest">` and `apple-mobile-web-app-capable`
in `index.html` are the whole of the install story; its icon is `public/icon.svg`, a placeholder
until `FU-DIST-ICONS` — the `/vite.svg` favicon link it replaced pointed at nothing.

**The Screens sheet** (`components/screens/ScreensSheet.tsx`, `Screens.dc.html` §2) is mounted once
in `Layout` and opened from the user menu and from ⌘K through `screensSheetState`, since neither
opener is an ancestor of the other. Every write on it is a `windows.*` command by row id — this
window's included, so a rename of this tab goes out and comes back like any other and there is one
path, not two. *Copy link for another device* mints `<origin>/?window=<name>` with the space as
`%20`, the launcher's spelling (`DeskScreens.screenUrl`); the origin is this tab's, because the desk
mints its LAN address server-side per request and exposes it on no GET route, so a tab at
`localhost` copies a link that names the desk to itself and says so under the button
(`FU-SCREENS-LAN-URL`). **Layouts** is `FU-SCREENS-LAYOUTS`, not built. **The hand** is session 3.

### Desk accounts

Login, roles, and user administration for a desk whose accounts live on the
**machine**, not in a project — see
`../docs/desk-accounts.md` for the backend contract and the break-glass
recovery. Frontend shape:

- `AuthGate` (wrapping `BootGate` in `App.tsx`) decides between `SetupScreen`,
  `LoginScreen`, and the app from `GET /auth/status`. A 401 from **any** endpoint
  invalidates the `Auth` tag, which is the entire logout mechanism; a WS close with
  code **4401** does the same, because the backend revokes live sockets.
- `store/users.ts` is admin-only CRUD (`/api/rest/users`); `store/passwordReset.ts`
  and `store/deviceLogin.ts` are the **public** endpoint pairs the two phone pages
  use. They are separate slices because they are separate audiences —
  cookie-authenticated admin vs. no session at all — not merely separate paths. The
  *desk* side of the device-login QR lives in `store/auth.ts`, because it is
  authenticated and open to any role.
- `routes/ResetPasswordPage.tsx` and `routes/DeviceLoginPage.tsx` are **siblings of
  `Layout`**, not children, and are bypassed past both gates via the `publicPath`
  flag computed at module scope in `App.tsx`: no sidebar, no ShowBar, no project
  context, no session. Whoever opens `/reset/<token>` is by definition locked out;
  whoever opens `/device/<token>` has no session yet either.

  Two traps there. It matches the **routes**, not a bare prefix — `/device/` with no
  token would otherwise render blank with both gates off. And because the flag is
  read once per document, `DeviceLoginPage` finishes with
  `window.location.assign('/')`: a react-router `navigate('/')` from either page
  would render the whole app with no auth check and no boot check. A test pins that.
- `MIN_PASSWORD_LENGTH` lives in `lib/passwordPolicy.ts` and mirrors the backend's
  floor. Five surfaces ask for a password; a form that disagreed with the server
  would read as a bug in that form. `MAX_DISPLAY_NAME_LENGTH` in `lib/userPolicy.ts`
  mirrors the column width the same way, but makes the **weaker** claim: only
  `ProfileSheet` gates on it today, and `SetupScreen` / `CreateUserSheet` /
  `UserDetailSheet` still rely on the server's 400. Don't read it as "every
  display-name field is bounded".
- **`ProfileSheet` is the only self-service surface**, reached from the user menu,
  which holds Log out and the **theme toggle** — the latter since `PD-SPEED-OVERLAY`
  added a ninth icon button to the app header and the row stopped fitting a phone
  (nine controls came to 439px against a 375px viewport, and the avatar was what got
  pushed off). Theme went because it is the one thing on that row that is not a desk
  control but a per-*viewer* display preference, which is what the rest of this menu
  is. **On a bootstrap-open desk the menu still opens**, behind a generic glyph, with
  the per-viewer items — the theme, *Full screen*, *Screens…* — and no account items:
  it used to return a bare `ThemeToggle` there, which kept the theme reachable and
  quietly lost the other two the day they were added. That standalone button is
  deleted; `ThemeMenuItem` is the one theme control, and the theme is
  a `useState` seeded once from storage, so a second mount would drift. It is
  deliberately **not** on `syncStore`, which JSON-encodes: `theme` is stored
  as the bare string `dark` and read at module scope in `main.tsx` before React exists. Four tabs — **Profile / Password / Devices /
  Sign-in** — and **each tab owns its own action button**; the footer is just Close,
  because a footer Save would have to mean "save the display name" while you were
  looking at the devices list. Errors are per-tab state for the same reason: one
  shared alert would follow you to another tab and blame the wrong form.
  The name and the password are **separate saves and must stay that way**: the
  password submit needs `currentPassword` and a rename must not, and the two differ in
  consequence (a password change revokes every other session; a rename revokes
  nothing). Its route, `PUT /auth/profile`, is authenticated but **any role** and
  deliberately outside the admin-only `/api/rest/users` subtree — a self-exception
  inside a prefix-matched admin gate would mean that prefix list no longer describes
  its own subtree.

  **Connected apps** sit on the Devices tab under the sessions: the MCP OAuth grants
  (`../docs/mcp-engineering.md`), one row each with Revoke, via
  `GET`/`DELETE /auth/connected-apps`. Drawn only when there is at least one, so a desk
  that never connected Claude shows nothing new. They are beside the devices because they
  answer the same question — what can act as me — and the backend ends them on the same
  events, which is why `changePassword` and `revokeOtherSessions` invalidate
  `AuthConnectedApps` too.
- **Account changes self-heal across clients**, via two frames from one backend flow rather
  than the show-scoped `FixturesChangeListener` bus every other list rides (users belong to the
  machine; see `../docs/desk-accounts.md` → "Account edits reach other clients").
  `usersWsApi` → `store/users.ts` invalidates `UserList` / `User` on every socket;
  `authWsApi.subscribeOwnAccountChanged` → `store/auth.ts` invalidates `Auth` on **only** the
  affected user's sockets. Keep those two apart: folding the `Auth` invalidation into the
  user-list bridge would make every connected client re-read `auth/status` on any admin edit.
  The own-account subscriber lives on `authWsApi` for the same reason the 4401 one does — `Auth`
  is that module's tag.

  One reliance worth knowing before you add a call site: the backend does **not** role-filter
  `userListChanged`, which is safe only because `UsersTab` is the sole caller of
  `useUsersQuery` and passes `skip: !isAdmin`, so an operator has no subscriber and the dispatch
  is a no-op. A second unguarded call site would make every operator socket a 403 generator on
  every user edit. `store/installs.ts` has the same bridge shape for the install row.
- **"Manage users" is deliberately not in the user menu.** The `users` nav entry is
  `adminOnly`, so the sidebar and Cmd+K already carry that page; a second entry point
  only meant role-filtering the same destination in two places.
- 409 responses carrying `LAST_ADMIN` / `SELF_TARGET` are **ordinary flow steps**
  (you can't demote the last admin, and on your own account you can't disable,
  delete, re-role, or mint a reset QR), rendered inline in `UserDetailSheet` — which
  is why those endpoints are in `SILENT_ENDPOINTS`. The self cases are hidden rather
  than disabled in that sheet: a Password section made of three greyed-out controls
  reads as breakage.
- **The two QR surfaces make opposite calls on the way out, on purpose.**
  `ResetQrSheet` leaves its link alive and `ResetTokenHistory` makes it visible and
  revocable; `DeviceLoginSection` cancels its code, because that code *is* a way into
  the account rather than a way to re-password it. Don't factor them together — and
  don't turn the section back into a sheet, either: being the body of `ProfileSheet`'s
  Sign-in tab is what makes "left the tab", "parent closed" and "tree unmounted" one
  cancel mechanism (the teardown effect, keyed on `active` *and* firing on unmount —
  both are needed).
- **The Sign-in tab has no button: arriving mints, leaving cancels.** Radix mounts a
  tab's content only while it is active, and mounting is what mints — so navigating to
  a tab named for the thing replaces a press with a navigation, and the tab bar above
  the code is the way out. What must not regress is the other half: opening the sheet
  lands on **Profile**, so nothing is minted by opening it, and closing resets `tab` to
  `profile` via an effect on `open` rather than any close handler — because saving a
  name closes the sheet without going through one. Treat that reset as a security
  property, not tidiness.
- **Minting a device-login code must happen exactly once**, which is why the mint
  effect carries `mintedRef` and `onScreen()` reads *two* refs. Both exist because of
  StrictMode's development mount/teardown/remount: a flag only cleared in a teardown
  is left false while the section is on screen (every code then cancels itself on
  arrival), and a second POST is not merely wasteful — `AuthService.createDeviceLogin`
  retires the caller's previous code, so two mints race and resolving them backwards
  displays a QR the server has already cancelled. **The client cannot repair that
  afterwards**; it has no way to know which mint the server saw last, so don't reach
  for a "cancel the displaced code" fix. Two tests in `DeviceLoginSection.test.tsx`
  render under `StrictMode` for exactly this — plain `render` passes while all of it is
  broken. `mintedRef` is released again if the mint *fails*, so the "Try again" button
  is reachable: a failed mint leaves no `code`, so the EXPIRED/CANCELLED retry branch
  can't render and the tab would otherwise be an error with nothing to press.

### Cues, Stacks & Triggers
Cues bundle an ordered stack of **Look layers** (see §Looks and layers), their own property assignments, ad-hoc effects, and **script hooks** into named snapshots. **Every cue belongs to a cue stack** — there are no standalone cues. A project owns an *ordered* list of stacks (the "show"); a stack owns an ordered list of cues. A stack row can also be a **SEPARATOR** (a label-only divider between stacks). A cue can also carry **scenery** — scene elements it moves on GO, each on its own clock — shown on its card (with what it tracks, hatched) and edited in Cue properties; a stack has a *set* and a Look *scenery while live* (stage-view plan session 8, §Stage views). Cues and stacks are authored **and run** entirely in the **Show** view (`/projects/:projectId/show`, drilling into a stack at `/show/stacks/:stackId?cue=:cueId`) — the old separate "FX Cues" view has been removed, Show was itself called Program until the programmer moved out of it into `/programmer`, and the separate **Run** view folded into it in session 2b (see §Navigation Registry and §The show-editing lock).

#### The show-editing lock

**`/show` is the runner and the editor, separated by a lock rather than a route.** `useEditLock`
derives it:

```
locked = !canEdit || (isShowActive && lockRequested)
```

Six things about it are load-bearing:

- **It is a stray-click guard, not access control.** The backend has no notion of it and no route
  refuses a write on its account, so a second client can edit a "locked" show. Dressing it as
  permission would be worse than not having it. `canEdit` is not a role either — the backend
  computes it as "is this the current project".
- **It is not the transport gate.** GO must work while locked; locked *is* the normal running state.
  `canOperate` on `useShowTransport` is a different question and must never be handed `locked`.
- **A stopped show is simply editable**, with no lock chrome at all — there is nothing to protect,
  so there is nothing to warn about. `lockRelevant` gates the chrome.
- **`lockRequested` lives in a Redux slice** (`store/editLockSlice.ts`), shared with the Prompt Book,
  because "I am in a fix-it session" is one fact about the operator and one GO should end it
  everywhere. It is **never persisted**: a running show always opens locked. The re-arm effect
  therefore fires on the stopped→running *transition* and not on mount, or navigating between the two
  surfaces would re-lock and the sharing would be pointless — `useEditLock.test.tsx` pins that.
- **Dragging is disabled through dnd-kit's own `disabled`**, per sortable, never by unmounting the
  `DndContext` — `useSortable` needs that ancestor, so removing it breaks every row. Affordances are
  **hidden rather than greyed out**: a row of disabled destructive buttons reads as breakage.
- **The lock is the cue sheet's read-only scope** (§Sheet kit): locked, the sheet's value cells
  are inert and a click on the Cue column arms the cue as next, exactly as a card's body click
  does; `useTransportKeys` keeps its `enabled: locked` and now stands aside from a key another
  handler has already claimed, which is how a name typed into a cue cell beginning with `l` does
  not toggle the lock.
- **Transport shortcuts act only while locked**, via `useTransportKeys`, on **both** lock surfaces;
  `L` stays bound in both states so there is always a keyboard way back to a safe desk. Unlocked, the
  row's cue number, name and fade are live text fields, and in an editing surface Space is a space.
  That handler took the *union* of the two it replaced — a focused button must not fire GO as well as
  activating itself, which is a double advance the old Run handler allowed.
- **Unlocked-while-running washes the header amber** — `ShowHeader`'s `unlockedWarning`, on both lock
  surfaces. The signal is for the *unlocked* state, not the locked one: locked is the quiet default,
  and believing you are locked when you are not is how a show gets edited by accident. A stopped show
  is simply editable, so there is nothing to warn about and no wash. The border is always present and
  transparent, so colouring it cannot shift the layout as the lock flips. The Prompt Book's toolbar
  drew this itself until 2b; two adjacent amber bars said it twice.
- **The lock control is `ShowLockControl` in `ShowHeader`'s `actions` slot**, on Show *and* the Prompt
  Book. The Prompt Book used to draw its own in its toolbar, so one control sat in two places
  depending on the view. It carries the Prompt Book's extra case: where the backend will not accept
  edits, the control is shown but **inert**, because it is the only thing saying why.

**One `ShowBar`, identical on the two live views that have one — Show and the Prompt Book.** Every
host spreads `showBarProps` from `useShowBarProps` and overrides exactly one prop —
`showShortcuts`, which advertises keys and so can only be answered by the host that binds them.
Everything else comes from the hook, which is what stops the bar drifting into near-copies: it
previously had no Blind on the Prompt Book, a different stack-name rule on Show, and a hand-wired
transport on the Prompt Book that gave that page two transport instances. **The busk view was the
third host until the busk-chrome plan's session A** and draws no bar on any board: it still calls
the hook — for the transport and the `dbo` pair — and hands the result to its side sheet's Show
tab, which mounts the phone runner in the bar's place (§Focus and the side sheet). It is the
second view without a bar and for the opposite reason to the programmer's: not too much chrome for
the job, but the wrong shape of it.

**The programmer is the exception, and draws no bar at all.** It keeps `ShowHeader` — the breadcrumb,
the save pill, the view switcher, Start/Stop and the live dot — and nothing below it until row A.
That is the space plan's session 5, and it is *not* what D9 proposed: D9 was to fold `ShowHeader`
into `ShowBar` on all four views, which was built and then rejected at the desk in favour of this.
The reasoning is D1 applied to a band rather than to a row — everything above the grid earns its
place by the line, and ~60px of blackout, tempo, cue numbers and transport is the largest thing on
that page that is not about editing values. Three consequences, each of which reads as a bug if you
do not know it is a decision:

- **Blind is toggled on the programmer, and only there; blackout is gone outright.** Blind is a
  *programmer* fact, not show chrome — `ProgrammerSummary.blind`, written by `programmerSetBlind`,
  faded by the programmer's own fade — and session 5 leaving the programmer with no press was the
  one failed check of the desk pass (`PD-BLIND-ON-PROGRAMMER`). The control is the action bar's, in
  row A's Stage zone beside Clear and the fade picker; `useShowBarProps` supplies no `onBlind` **for
  any host**, so no bar draws a tile; Show and the Prompt Book *report* it through the
  `ProgrammerIndicator` their bar mounts, and Busk — which has no bar — through the band's `BLIND`
  pill, the Show glyph's dot and the Show tab's own `ProgrammerIndicator` (§The rig, §Focus and the
  side sheet). That is session 2b's arrangement exactly inverted,
  and the rule it was written for still holds: one control, one place. The drift to refuse now is
  the reverse one — a Blind tile back in the bar for one host. Do **not** make `ProgrammerIndicator`
  the toggle either: it is also the link to the programmer, and one control cannot be both without
  one of the two jobs becoming a surprise. It has no `blindShownSeparately` any more — the ShowBar
  tile was its only true caller, and a badge that can be told to stay quiet is one a host can silence
  with nothing else saying it. On the programmer the header's badge and the action bar's button are
  both amber when blind: the reporter and the control, one row apart.
- **GO and BACK are not on the programmer**, which binds no transport keys either
  (`useTransportKeys` is Show's and the Prompt Book's). The switcher in the header is one pill from
  three views that do have a transport.
- **The speed masters are not *resident* on the programmer**, and `PD-SPEED-OVERLAY` did not put
  them there: the bank is summoned from the app header's **Speed Masters overview panel**, which
  hangs over every route and is nobody's view chrome — the programmer gained nothing of its own.
  `ProgrammerFxList` still names each effect's master, and `/speed-masters` still manages the bank.

`ProgrammerPage.test.tsx` pins the absence; `ProgrammerPage.tsx`'s note beside the header is the
long form of all three.

- **The bar is not gated on the show running.** It carries blackout, the speed masters and the
  programmer chip, all of which mean something with the show down, and `goDisabled` already mutes
  BACK/GO. Gating it was what once made **Blind's location depend on the show's state**.
- **Blind is not in the bar.** From session 2b to `PD-BLIND-ON-PROGRAMMER` it was, beside blackout,
  on the reasoning that the two are the same class of thing (a gate on what reaches the rig) — and
  that put the press on the three views whose programmer is usually empty and off the one whose
  whole subject it gates. See the programmer bullet above for where it is now. The fade survived
  both moves and must survive any next one: `lib/programmerFade.ts` is a module-level store, not a
  `usePersistentState` per reader, because as two instances of one key it was two mount-time
  snapshots, and Blind snapped for the rest of the visit. The action bar's Blind and Clear read one
  subscribed value from it; the marquee's Backspace reads it at press time.
- **DBO is still inert** in every host — local state, no side effect
  ([`FU-FE-DBO-INERT`](../docs/plans/followups.md)). It no longer has a working Blind tile
  beside it to read as a peer of, but a tile that does nothing is still the part that must not stand.

**Browsing a stack never moves the playhead.** A tab click used to run
`deactivate(old) → goToStack → deactivate(target)`, so one unconfirmed press took the live cue off
stage and repositioned every other client. `StackTabStrip` now takes `selectedStackId` (the underline)
and `liveStackId` (the green pip) as separate props, and arming is an explicit, confirm-gated control
in `OffPlayheadBanner`. The confirmation is not ceremony: `POST /show/go-to` deactivates the stack
being left and then calls `activateAtFirstCue` on the target, so the target's first cue genuinely
fires and the desk darkens it again — a visible blip on top of losing the current cue.

**And the Stacks button has to be able to leave one.** `/show`, `/show/stacks/:id` and
`/show/stacks/:id/table` are three sibling routes with an `element` each, so going back from a stack
**remounts** `ShowPage` — which reset the `initialDrillDoneRef` that makes the "drill into the live
stack on arrival" auto-navigate fire once, and the auto-drill put the operator straight back where
they had just left. A ref cannot say "they asked for the list" across a remount, so the signal rides
the *location* instead: `STACK_LIST_STATE` on the Stacks button's and the breadcrumb's navigate, read
by that effect. Do not replace it with a ref or a module-level flag — the first cannot survive the
remount and the second would suppress the auto-drill for the rest of the tab's life.

**Two cursors reach a cue row, and neither is a mode.** `serverActiveCueId` places the stable
"on stage" marker; `activeCueId` (the optimistic runner cursor) says which row owns the fade chrome.
During a crossfade those are different rows, so one value cannot serve both. The fade *value* is
never a prop — each row reads its own through `useCueFade`, because `ShowView` is memoized
specifically to stop several hundred rows reconciling at frame rate, and passing `fadeProgress` down
would defeat that with the memo still in place, looking effective.

**Each server run fact has one owner.** The RTK cache owns what the server says
(`stack.activeCueId`, `stack.nextCueId`); the runner slice owns what is local — the animating
cursor, the optimistic next (`standbyCueId`), done ticks, fade/auto descriptors — plus a private
memory of the last frame it adopted (`serverActiveCueId`), which exists because a reducer cannot
read the cache. That slice field is **not** a substitute for `useShowTransport`'s own
change-tracking ref: the optimistic mutation patches move the cache with no frame, and a snapshot
frame moves both stores at once, so "do the stores disagree" and "did the cache move" are
different questions (the reconcile effect's docblock spells this out). Surfaces read cursors
through `useShowTransport`, whose docblock maps who reads which and why; don't hand-compute
`activeStack?.activeCueId` in a view, and don't add a second cache copy of a run cursor (the
armed-only `CueStack.standbyCueId` was exactly that — written twice, read never — and was
removed).

**Expansion is the operator's cards plus the playhead's, derived.** `useCueExpansion` owns one
rule — there are two reasons a card can be open, and closing must silence both — and leaves the
operator's slot to the caller, because its storage and multiplicity genuinely differ: Show keeps one
cue in `?cue=` (an external contract; the Prompt Book mints those links), while the Prompt Book rail
keeps a set in local state so two cues can be compared against the page they anchor to. The rail
also auto-opens the cue on deck (`nextCueId`), which Show does not. Either way a GO opens the new
playhead cards and cannot take away one being read, because nothing in the hook writes the
operator's slot. Run kept a `Set` and never removed from it (five GOs, five open cards); Show kept a
bare scalar a GO would overwrite. Dismissed playhead cards self-clear as their ids stop matching;
`resetKey` exists for the one id that survives a transition — a dismissed *next* card the GO makes
live.

**Cue numbers** are free-form display labels (`sortOrder` is the authoritative playback order). They are parsed as **prefix + decimal run + suffix** (`S1-3.1` → `("S1-", [3,1], "")`) and only ever compared *within a prefix group*, so `Pre-show 1, Pre-show 2, T2-1, S-1, S-2` is correctly ordered. `src/lib/cueNumber.ts` holds that model and drives the "Fix Order" banner; it mirrors `routes/cueNumbering.kt` in lighting7, which performs the fix — **keep the two in step**.

A cue without an explicit number gets one derived from its position (`cueNumberAuto: true`), recomputed by the backend whenever the stack changes. Auto numbers render dimmed via `AUTO_CUE_NUMBER_CLASS`; clearing the `Cue #` field returns a cue to auto.

**A cue is read-only, and edited by Include.** Session 2a deleted the three-pane inline editor
(Targets · Cue properties · Layers) and its tab chrome: those panes restated, in a different shape,
what a value grid and a layer stack already say, and two renderings of one state do not stay in step.
An expanded cue row now shows `CueDetailContent` — transition, notes, **its composed
values** (`CueValueGrid`), layers, effects, hooks — all read-only, with **Edit in Programmer** (which
Includes it) and **Cue properties…** (`CuePropertiesSheet`). Consequences worth knowing:

- **`CueValueGrid` reads `GET /{projectId}/cues/{cueId}/cooked`**, which wraps the same
  `buildCombinedCueLayerRows` the GO path runs. Do **not** compose a cue's values client-side —
  layer order, masks, per-layer amount and blend, group expansion and specificity would all have to
  be reimplemented, and each is a place for the desk and the display to disagree. It borrows the
  four cell components rather than mounting `FixturesListContainer`: that container owns a filter,
  a row marquee and a cell marquee, and its selection is Redux-scoped to one of three scopes.
- **"Add Cue" is gone.** A cue is a captured state, so recording is the only way one is made;
  `StackDetail` offers *Record into `<stack>`*. Separators and stacks keep their create buttons —
  neither is a captured state, and that is the line rather than "no new buttons". The Prompt Book's
  `CueAnchorPickerSheet` also still creates a cue at an anchor, deliberately.
- **`CuePropsPane` survived, relocated.** It was not the problem with the three-pane editor, and a
  per-field autosaving form is right for cue metadata; it is now the body of the properties drawer.
- **`EditorContextValue` has no `cue` arm**, and the `cueEdit.*` protocol no longer exists on
  either side. Session 2a stopped providing the arm; 2b removed it, along with its four session
  helpers, `api/cueEditWsApi.ts` and the fifteen `kind === 'cue'` branches, having decided that
  giving a cue row editable cells would make a cue and the programmer two places to set a value
  again. The backend sweep then deleted the family server-side, so the `409 CUE_EDIT_SESSION_OPEN`
  handling, the `force` request field both Record and Update sent, the two "do it anyway" buttons
  and the Diagnostics `cueEdit` histogram panel are all gone too. Don't reintroduce any of it:
  a cue is edited by Include, and `EditorContext.tsx`'s doc comment is the record of why.
  `INCLUDE_TARGET_GONE` is Update's own 409 and is unrelated — that one is live.

**Timed effects**: Layers and ad-hoc effects can have optional timing (delayMs, intervalMs, randomWindowMs) to fire after a delay or on a recurring interval. Immediate (no timing) is the default. A timed layer re-cooks the whole cue when it fires rather than appending its rows, so an in-flight crossfade weight survives.

**Script hooks** (triggers) automate FX_APPLICATION script execution on cue lifecycle events:
- **ACTIVATION** / **DEACTIVATION** — fire when the cue starts/stops
- **DELAYED** — fire after a configurable delay
- **RECURRING** — fire at an interval with optional randomisation for organic timing

FX definitions have a `timingSource` field (`BEAT` or `WALL_CLOCK`) controlling whether effects sync to BPM or run on a fixed 50Hz wall-clock timer.

### Cloud sync — the GitHub identity

Backend contract in `../docs/sync-engineering.md`. Three traps on this side:

- **`identity.connected === true` does not mean OAuth works.** A rejected identity keeps
  `connected: true` and gains `reauthRequired` — that conflation is why the desk showed
  "Connected as @user" plus a permanent "refreshing soon" badge for 25 days while every
  sync failed. Every gate must read `connected === true && reauthRequired !== true`; the
  five that do are in `IdentityRow`, `routes/CloudSync.tsx` (the hub's "Add remote
  project"), `components/cloudSync/ConfigPanel.tsx`, `components/cloudSync/StatusPanel.tsx`
  and `Projects.tsx`.
- **`/api/rest/oauth/` is admin-gated**, so every caller of `useOauthGithubIdentityQuery`
  passes `skip: !isAdmin` — the same reliance `store/users.ts` documents for
  `useUsersQuery`, and now load-bearing in a new way, because the sidebar badge and the
  global banner mount the query on *every* page rather than only on the sync pages.
  `useOAuthReauthState` bakes the guard in; prefer it to the raw query.
- **`startOAuthIdentityBridge()` is called from `main.tsx`, not on import.** Unlike
  `store/users.ts`, this slice is imported from the earliest render path (the sidebar), so
  touching `lightingApi` in its module body throws a TDZ `ReferenceError` and takes every
  export with it — the sidebar and banner render as "not defined". `tsc`, `vite build` and
  the unit tests all pass anyway, because the cycle exists only at runtime and the tests
  mock the module: it shows up **solely** as a broken app in the browser.

The banner is dismissible against the rejection's timestamp (localStorage), so dismissing
survives reloads but a genuinely new outage still gets seen. It is a banner and not a toast
or modal for the same reason the update panel never nags: an operator mid-show must not be
interrupted — and here they are not shown it at all, since they cannot fix it.

### In-app updates

The **Updates** tab in `InstallSettings` (`components/updates/UpdatePanel.tsx`), backed by
`store/updates.ts` and `api/updateWsApi.ts`. Windows installer builds only; every other build
renders a one-line explanation of why it can't update itself. Backend contract and the MSI
mechanics live in `../docs/windows-updates.md`.

- **`updateStateChanged` is the one payload-carrying machine-socket frame**, so `updateWsApi` is
  modelled on `cloudSyncWsApi`, not `installWsApi`. For a several-hundred-megabyte download the
  frame *is* the progress; a payload-free "refetch" at 2 Hz would mean an HTTP round-trip per
  tick, which is the traffic the socket exists to avoid.
- The bridge in `store/updates.ts` splits deliberately: **`updateQueryData` for progress ticks**
  (zero network), **`invalidateTags` only on a terminal phase** — that's where `latest`, `error`,
  notes and `lastApplyOutcome` arrive. Invalidating per tick would defeat the whole reason the
  frame carries a payload. The panel also polls at 5 s **while busy** as a safety net, because
  `emitMachineEvent` uses `tryEmit` and drops frames when its buffer fills: a dropped progress
  tick is harmless, a dropped phase transition would strand the panel.
- The tab is visible to **everyone** with actions disabled for operators — the version, and that
  the desk is about to restart, are things anyone standing at it should read. **Never toast or
  modal an available update**: an operator mid-show must not be nagged.
- Release notes render as **plain text**. They are untrusted text fetched from the internet, and
  per §Dependencies a sanitising markdown renderer isn't worth adding for this.
- `ApplyUpdateDialog` requires **type-to-confirm only when the rig is live** (effects running or
  a stack active). The asymmetry is the point: making every routine update a typing chore trains
  people to type without reading, destroying the friction exactly when it matters. It also sends
  `confirmVersion`, so a tab left open across a newer check can't apply something its owner never
  saw — the backend 409s on a mismatch.

### Remote access

The **Remote access** tab in `InstallSettings` (`components/remoteAccess/RemoteAccessPanel.tsx`),
backed by `store/remoteAccess.ts` and `api/remoteAccessWsApi.ts`: the desk's own ngrok tunnel,
which puts the **whole desk** on the internet at the operator's ngrok domain. Admin only — the tab
trigger is hidden from operators like Users, the query passes `skip: !isAdmin`, and the desk sends
`tunnel.state` to admin sockets alone. Backend contract in `../docs/mcp-engineering.md`
§"Remote access" and §"Remote hardening".

- **The authtoken is write-only.** `GET /install/tunnel` answers `hasAuthtoken` and never the
  token, so the field is always empty (a password input) and says whether one is stored.
- **Turning it on asks; turning it off does not.** The switch opens a dialog saying the whole
  desk becomes reachable to anyone with a desk password; only its confirm sends `enabled: true`.
- **Saving a changed domain asks**, because the domain is the OAuth issuer and every Claude
  connector added under the old one has to be re-added. A first domain saves without asking, and
  so does any domain while `mcp.publicUrl` overrides it (then it is not the issuer).
- **`tunnel.state` carries its payload**, like `updateStateChanged`: the bridge patches the cached
  settings' `state` and never refetches, and the PUT writes its response into the cache
  (`upsertQueryData`). `saveTunnelSettings` is in `SILENT_ENDPOINTS` — the panel renders a refusal
  (`REMOTE_ACCESS_INVALID`: a bad domain, no desk accounts) itself.
- **No free-plan warning**, by decision (Chris, 2026-09-26): ngrok's quotas are not pre-empted.

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
   blank-looking app in the browser. `store/oauthGithub.ts`'s doc comment is the long version.
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
   neighbours in `hooks/usePropertyValues.ts`.

The census as of this writing, so a new slice can see which company it is in: **27 module-scope
sites across 20 slices** (`grep -n '^lightingApi\.' src/store/*.ts`), and **four deferred**, all
started from `main.tsx` — `oauthGithub`, `looks`, `templates`, `programmerErrors`. The imbalance is
the rule working, not drift: form 1 is the default and form 2 is the exception, and the four are
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
   in whichever module happens to be its destination. `/run`, `/cue-stacks`, `/cues` and `/program`
   all land on `/show`, and `/fx` lands on `/busk`; collecting them keeps `ShowPage.tsx` from
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

### Navigation Registry
- All navigation items are defined in `src/navigation.ts`
- When adding a new page/route, add an entry to the `navItems` array in `src/navigation.ts`
- This automatically registers the page in both the sidebar and the Cmd+K command palette
- Dynamic items (e.g. universes) are handled by the `useUniverseNavItems()` hook (`useNavItems()` just returns the static `navItems`)
- **The ⌘K window commands are actions, not `NavItem`s.** `useWindowCommands()` in
  `src/navigation.ts` builds ⌘K's *Screens* group from the windows registry the way
  `useTemplateFamilyNavItems` builds its four from the family list, and `buildWindowCommands` is the
  pure half `navigation.test.ts` pins: *Go full screen* / *Exit full screen* (⇧F, absent where the
  browser has no Fullscreen API), *Screens…* with the window count, *Show <view> on <window>* for
  every **other** window × the seven views in `lib/windowViews.ts` (this window has the Navigation
  group already), *Open <view> on another display* (Chrome only, absent elsewhere — D13's rule that a
  missing feature is quiet, never a disabled row saying "use Chrome"), *<Window> · own selection* /
  *<Window> · follow the desk selection* for every other Busk or Programmer window (desk-follow D4,
  the unlink withheld where that row's focus forces following), and *Follow the desk selection in
  this window* with its state as the detail — its *Stop following…* arm withheld in Rig and Pads
  focus — and *<Window> · page with the desk* / *<Window> · own page* for every other **busk**
  window (desk-follow D6), offering only the arm that changes that row's announced `pageFollows`
  (a row that has not announced it counts as paged with), written as a `windows.viewOptions`
  frame under the row's view. They carry a `run`, not a `path`, because most of
  them move *another* window; the sidebar never lists them.
- **Exception — cards/list sibling routes**: list views that pair with a cards
  view (`/fixtures/list`, `/groups/list`, `/channels/:universe/table`,
  `/show/stacks/:stackId/table`) deliberately have **no** `navItems`
  entry. They're reached via the in-page Cards/List switcher
  (`src/components/ViewSwitcher.tsx`) and Cmd+K item deep links, and the
  sidebar keeps one entry per resource; the cards route redirects to the list
  when the sticky view preference says so. Follow that pattern for any new
  cards/list pair instead of adding a second sidebar row.
- **There are four live views: Programmer · Show · Prompt Book · Busk.** The programmer is
  `/projects/:id/programmer` (`ProgrammerPage`); `/show` (`ShowPage`) is *both* the
  cue/stack authoring surface and the runner. `/program*` and `/run*` both redirect to
  the `/show` equivalent, and `/program*` **carries the search string**, because
  `?cue=` deep links are how the Prompt Book's "Edit cue" reaches a cue.

  **Busk is `/projects/:id/busk`** (`routes/Busk.tsx` → `components/busking/BuskingView`):
  the rig band, the page the operator built and the side sheet, under the same `ShowHeader` as
  the other three and — since the busk-chrome plan's session A — **no `ShowBar`**: the route still
  calls `useShowBarProps` and hands the transport to the sheet's Show tab (§Focus and the side
  sheet). The page itself is §The busk
  layout; this section is the route and the surface around it. It was `/fx`, which named
  the machinery rather than the job and sat one hyphen from `/fx-library` — the collision
  `lib/navMatch.ts` exists for. `LegacyFxRedirect` keeps both spellings of the old path
  alive; the nav entry keeps `id: "fx"` as its stable handle, the same call `program` made
  when Show was renamed.

  Four things about it that are decisions rather than detail:

  - **The pads are the *library*, and nothing else.** A busk pad presses a **named thing from the
    library** — a template, a Look or a cue — and nothing on this page mints one. The view used to
    draw three pools of ad-hoc effect pads besides, a Controls pool of hold-to-slide property pads
    writing straight to the programmer, and a beat-division toggle to parameterise whatever those
    minted; all of it went when the view was brought back onto its design, because a grid minting
    anonymous FX instances with their own timing model was a different gesture wearing the same
    clothes. Three things follow, and none is a bug:
    - **Nothing on this page mints an FX instance.** An ad-hoc effect reaches the stage through a
      Look with deferred effects, a cue, or the Programmer's `+ Effect`; a raw level through an
      intensity template or the Programmer.
    - **`useSpeedMasterForCategory` lost its caller here.** The effect pads were the only surface
      doing the busking plan's D1 stamping, and it is client-side — the backend serves `usage` but
      does not resolve it. The rail's caption was reworded off the promise it could no longer keep,
      and should stay reworded: nothing on *this* page stamps a master. The hook itself was kept
      rather than deleted, and `TemplateEditor` is its caller now (see §Speed Masters).
    - **`BuskingView` reads no target's running effects.** The eight fixed RTK Query slots that once
      fanned the selection out (and capped it at eight targets — `FU-BUSK-TARGET-CAP`, now retired)
      went with the pads. A template or Look pad reads the programmer's **resolved applied state**
      (`useProgrammerAppliedQuery`; the view does not subscribe to the layer stack at all), which
      needs only `{type, key}` per target, so `lookLayerTarget` is the one place the group-name
      convention is applied and the selection has no ceiling. A **cue** pad reads `useActiveCueIds`
      instead — its stack has that cue on stage, playhead or not, which is what makes a cue pad a
      toggle rather than a playhead move.
  - **It does not pass `canOperate`, and the show-editing lock is not consulted.** GO must
    work from a busk pad: busking *is* the live use, and the lock is a stray-click guard for
    editing surfaces rather than a transport gate — the same reasoning that keeps `locked`
    away from `canOperate` on `/show`.
  - **The target band replaced a sidebar, and a tile is a plain toggle.** It was `TargetBand` —
    two rows of pads in one `grid-flow-col` container, groups then fixtures, scrolling sideways —
    and is the **rig band** now (§The rig), rows of tiles the operator built, with the same
    press. The list the band replaced was left-click-replace / right-click-toggle, which has no
    touchscreen gesture and no discoverable mouse one; `selectTarget`, the replace, survived it
    only for the narrow-width *Pick targets…* sheet, and went with that sheet (D15) — the
    selection hook has one write beside clear, `toggleTarget`, by `{type, key}`.
    `SelectedTargetSummary` went with the sidebar, and `Breadcrumbs`' `extra` / `onExtraClick`
    went with *it* — the busk view was their last consumer, so every breadcrumb trail is now
    `Projects > Project > <View>`.
  - **There is no empty-selection dim, and re-adding one would be a regression.** The pools used to
    grey themselves out with nothing selected. Three of the things a pad can now hold do not need a
    selection at all — a **per-fixture** template names its own heads, a Look with **no deferred
    effect** names its own fixtures, and a **cue** has no targets — and the two cases that genuinely
    need one are refused *by name* server-side (`TEMPLATE_NEEDS_SELECTION`, `LOOK_NEEDS_SELECTION`),
    which is a better answer than a grey page. A bank mixes kinds anyway, so the old per-section dim
    has nothing left to be per. **The band does not dim in edit mode either**, any more — it did,
    on the reasoning that pads do not press then and the selection they would press onto is doing
    nothing; but in edit mode the band is being *edited*, its tiles are drag handles and take
    drops, and a dim over a drop target reads as "not here". Only the show-all fallback is drawn
    dimmed while editing, because nothing on it can move (§The rig).

  **`look-groups-design/` in lighting7 is the layout authority** — `Main.dc.html` for play mode,
  `Edit.dc.html` for edit mode, `Layout.dc.html` for the rows/columns/banks structure and the three
  bank drop zones. It supersedes `busking-view-design/`, which drew the pools this page no longer
  has. Two conventions carried over and should hold for anything added here:

  - **One label, `EditorLabel`** (9px bold uppercase, wide-tracked, muted, **no icon**; it was
    the busk view's `BuskLabel` until the editor kit took it as the label over every control,
    `components/editor/EditorLabel.tsx`), on every
    region — band, palette, rail. Regions once drew a larger icon-bearing heading, which made three
    parts of one instrument read as three surfaces. It renders a `<div>` deliberately, so a test can
    reach a region's body by walking up from its label.
  - **The target band lives inside the left column**, so the rail's border runs from under the
    ShowBar to the bottom of the page rather than starting below the band.

  **Holding a speed-master card turns it into a tempo fader**, which is the busk view's own
  hold-to-slide gesture — the one the property pads carried before they were deleted. It is the
  third way to set a tempo and they do not overlap: TAP finds one you can hear, the number sets one
  you know, and the drag *trims* one that is nearly right, which is what a busking operator does
  most and had no gesture for. Six things:

  - **The whole card is the fader**, armed after `SLIDE_HOLD_MS` and seeded from the point the press
    started at, so nothing jumps when the hold takes. `useLongPress` hands `onLongPress` that
    origin for exactly this. The rest of the drag lives on **window** listeners — a fader is
    followed past the edge of the thing that started it — keyed on the `sliding` *boolean* and never
    on the dragged value, or every `pointermove` would tear the listeners down and rebuild them.
    They must listen for **`pointercancel` as well as `pointerup`**: the rail is a scroller with no
    `touch-action` of its own, so on a touchscreen a drag the browser reclaims as a pan ends with no
    release at all, and a card left `sliding` writes a tempo on the next pointer movement anywhere
    on the page with nothing held down. `useLongPress` cancels on it too, or the armed hold fires on
    a finger that is already scrolling something else.
  - **The travel is `SLIDE_MIN_BPM`..`SLIDE_MAX_BPM` (60..180) in `lib/speedMasterModel.ts`**, not
    the clock's 20..300. Deliberately the same window as lighting7's `BindingTarget.SpeedMasterBpm`
    and for its stated reason: a drag across the whole range spends most of its travel in tempos
    nobody plays at. It is a **control** range, not a limit — typing and TAP still reach the clock's
    ends. Don't "fix" it by widening it to the clock's range.
  - **It applies as it goes**, because that is what a fader is for: the tempo is judged by ear
    against a running show, and a control that only lands on release makes that guess-then-check.
    `useLivePush` (`components/editor/useLivePush.ts`, the editor kit's) is the traffic half of the
    same decision, not a softening of it — writes are
    deduplicated on the whole BPM and floored at `SLIDE_PUSH_MS` (50 ms), a deferred value is held
    and sent when the floor lifts rather than dropped, and the release bypasses both so the value
    let go on always lands. There is deliberately no optimistic pending value after the release,
    unlike the property pads: those waited on a REST refetch, and here the drag has been writing all
    along, so the desk is already at the value being released.
  - **`slideBpmRef` is written by the pointer handlers, never at render time.** A fast drag can
    dispatch a `pointermove` and the `pointerup` that ends it in one task, with no re-render
    between, so a ref assigned during render makes the release send the tempo from the move *before*
    last — silently undoing the operator's final movement. Found by a test, not by inspection.
  - **The click that ends a drag is swallowed** by an `onClickCapture` on the card calling
    `consumeLongPress()`. Capture runs root-to-child, so neither TAP, nor the bpm button, nor a ratio
    chip has to know the gesture exists — which is what lets the drag cross them freely.
  - **A follower cannot be dragged**, alongside its existing TAP and click-to-type refusals: the
    server refuses all three (`SPEED_MASTER_FOLLOWER`). Nor can any master while the desk is offline
    or its bpm field is open.

  **`SpeedMasterDetailSheet` is reached from the sliders glyph in the card's title row**, and only
  from there — the hold belongs to the fader, and a card cannot answer a hold two ways.
  `SlidersHorizontal`, not the footer link's `Settings2`: two identical glyphs a few rows apart read
  as one destination. The glyph is withheld until the REST row arrives, since the sheet edits that
  row rather than the live frame; the fader is not, because it needs only the uuid the live frame
  carries.

  The gesture itself is `hooks/useLongPress.ts`, which replaced two byte-identical hand-rolled
  copies; `PropertyPadButton` had a third (deleted with it), and `CueSlotOverviewPanel` adopted it
  too once its second stage — hold longer and the panel latched its own wiggle-and-cross edit mode —
  went. A slot's cross follows the **busk view's** edit mode now (`useBuskEditMode`), so what is
  left there is one stage: a hold opens the context menu, which is the only way to reach *View* and
  *Clear slot* on touch.

  **The cue column is gone, and a cue is a pad like any other.** There was a fourth region beside
  the Looks pool (`BuskCueStacks.tsx`): a card per runnable stack — name, live pip, current → next,
  Release, GO — and the **pinned cues** as pads below it. All of it went with the automatic layout,
  because a cue that wants a pad is now simply placed in a bank. What that decided, and what it
  costs:

  - **A cue pad is apply / stop, not a playhead move.** It presses through `POST /busk/pads/{id}/press`
    like every other pad, which reaches `CueStackManager` — so the cue goes live *without becoming
    the playhead*, exactly as a cue slot behaves, and pressing it again stops it. `useActiveCueIds`
    is what lights it, for the same reason. `GoToStackRequest.cueId` lost its only caller and is
    gone: `/show/go-to` names a stack and lands on its first cue.
  - **GO and BACK are the transport's, in one place** — the ShowBar's until the busk-chrome plan's
    session A, the side sheet's Show tab's footer since (§Focus and the side sheet). The stack
    cards' GO was two requests behind one gesture — `transport.go()` on the live stack,
    `goToStack` on any other — and a busk page has no room for a transport that means different
    things depending on which card it is on.
  - **`pinnedToBusk` is gone entirely** — the column, the *Pin to Busk* toggle in `CuePropsPane`,
    and the `buildCueInput` round-trip. A cue that wants a pad is placed in a bank.
  - **The transport is not a prop any more either.** `routes/Busk.tsx` still holds the one
    `useShowTransport` through `useShowBarProps` — a second instance would mean a second rAF loop
    and a second reconcile effect on one runner slice — but nothing below it needs one.

  **The Effects Overview panel is gone, and there are four overview panels now** — Stage,
  Fixture, Speed Masters, Cue Slots. Effects Overview held a beat dot, master 1's bpm, a TAP, a
  running-effect count and a
  Kill All, and `/fx` used to force it open and its toggle inert for as long as that route was
  mounted, because the busk view had no tempo readout and no view of what was running. Both
  halves of that reason expired: the ShowBar carries the whole speed-master bank on every live
  view, each tile with its own beat dot and TAP, so the panel was a second and narrower answer
  to "what tempo is the desk at" — narrower because it only ever spoke for master 1 — and the
  busk view has a speed rail and pad presence rings besides. **The count and Kill All were not
  moved anywhere.** What is running is listed, effect by effect and removable by name, in the
  programmer's FX band and in `ActiveEffectSheet`; a "stop everything" gesture, if it is wanted
  again, belongs beside blackout in the ShowBar rather than in a panel the operator has to open
  first. `store/fx.ts` (the `fxState` RTK Query wrapper) went with it — `api/fxApi` stays, since
  `store/groups.ts` still subscribes to the frame.

  **Speed Masters is the fourth, and it is not that panel returning** (`PD-SPEED-OVERLAY`). The
  difference is the whole of why it is allowed: Effects Overview drew a tempo readout *of its own*,
  narrower than the bar's; `SpeedMasterOverviewPanel` mounts `components/SpeedMasters.tsx` — the
  bar's own component, every master, unchanged — in a wider box, so it is the same answer reached
  from a view that has no bar rather than a second one. It hangs under the app header beside the
  other three, which is what makes it *every* view's and no view's: the programmer gains no chrome.
  Nothing came back with it — no count, no Kill All, no readout of its own — and no tempo is
  computed client-side; the live frame is still the readout, and master 1 and the follower rules
  are `SpeedMasters`' unchanged.

  It passes `room="dedicated"`, which is the only thing it tells the component — see §Speed
  Masters for what that picks and why the panel's thresholds are not the bar's.

  **Its visibility persists per panel and app-wide, which is a feature and a cost, and both are
  accepted.** Opened once it stays open on every view and across reloads: on the programmer that is
  a tempo band on screen, and on Show and the Prompt Book — and on Busk while its sheet's Speed
  tab is open — it is the bank drawn twice until dismissed. Neither is a defect and neither is session 5's band returning — the difference is a
  door the operator opened and can close. Do not "fix" it by making visibility per-view; that puts
  one surface in two states again.

  **Run is gone as a route, replaced by a mode.** Run and Show were never different
  destinations — the only real distinction was whether a stray click can change the
  show, which is a *mode*, and one the Prompt Book already modelled. So the lock came
  across instead of the route (see §The show-editing lock). Both levels of the view
  survive in both modes: locked, `/show` is the runner with a state pip, fade chrome
  and click-to-arm; unlocked, it is the same list plus drag, inline edit and the
  create/delete affordances. Two layouts under one switch would have been two views
  with extra steps.

  The programmer's own arrangement is the third it has had, and the reasoning for the
  second is what makes the third safe to state. The programmer was once its own page, then three tabs of a
  collapsed pane inside Program with no nav entry — the argument being that Values /
  Layers / FX are three readings of *one live object* rather than three destinations,
  and that a second sidebar row pointing at one page was the `"/program"` vs
  `"/programmer"` collision. The tabs premise held; the *pane* did not. Three readings
  of one object is an argument for showing them **together**, not for a switcher, and
  a collapsed pane could never do that. So: no tabs, and a page with room. (The rail's
  Stack · Colour · Spread strip since the editor kit's session 4 is a different thing: Stack *is*
  the layers and the effects together, and nothing separates the three readings — §The rail's
  tabs.) Renaming
  Program to Show removes the near-collision outright.

  Two traps that survive both the rename and the merge:

  - **`pathMatch` never uses `startsWith`.** `mostSpecificActiveId` now lives in
    `lib/navMatch.ts` and matches whole trailing segments (`endsWith(m) ||
    includes(m + '/')`), longest wins. `navMatch.test.ts` pins `/programmer` and
    `/show` apart so the collision cannot come back by accident.
  - **`ProgrammerIndicator` does the same test by hand** and must keep the
    segment-aware form. It is a trap in both directions: while it pointed at
    `/program`, the sibling `/projects/1/programmer` *did* start with it.

  `/programmer/fx` still redirects — FX was a route, then a tab, and is now a band of
  the page. The old "reset the FX tab to Values on mount" rule retired with the tabs;
  the diagnostic-read argument lives on as `FxSheet` being a collapsible under
  `ProgrammerFxList`, closed by default and **mounted only when open**, because it
  builds a second full fixture row model and re-renders on every programmer event.
- **Two libraries, two entries, and the filter is on the other one now.** `/looks`
  and `/templates` are separate `navItems` entries and separate routes, because they
  are separate entities (see §Looks, templates and layers). `/looks` has **no family
  filter at all**: a Look's families are *derived* from its rows, so one covering
  colour and position belongs to two banks at once and filtering by one would hide
  most of the library from most filters. `/templates` has the sticky filter — the
  library row's `PartitionChips` since the library sheets, remembered under
  `looks.family`, kept under its old name as a private storage key nobody reads by
  name — and there a family **is** an exact partition: a template holds exactly one.
  `useTemplateFamilyNavItems()` gives Cmd+K four deep links as `?family=` query params
  on the one route, with `pathMatch` the bare `/templates` so the sidebar highlights its
  single row whichever family you arrived in — asserted in `navigation.test.ts`, which
  also pins the two `pathMatch`es apart.

  Note this is no longer the "where sibling routes do not apply" exception it was
  written as: on `/templates` sibling routes *would* partition cleanly, and it is still
  one route because the filter is a **view** of a small library rather than a division
  of it. Reach for sibling routes when the sub-views partition the resource *and* the
  operator navigates between them (cards/list, an editor and its diagnostic); reach for
  a filter when the whole library is worth seeing at once.
- **Role filtering**: set `adminOnly: true` on any entry whose destination is
  behind the backend's admin gate (`ADMIN_ONLY_PREFIXES` / the per-project sync
  subtree in lighting7's `auth/AuthGate.kt`) — currently `users`, `sync` and
  `project-sync`. `filterNavItems(items, isViewingActiveProject, isAdmin)` drops
  them for operators so neither the sidebar nor Cmd+K offers a page that can only
  answer 403. `useIsNavAdmin()` supplies the flag and treats *anything but a
  resolved OPERATOR* as admin: during the `auth/status` round-trip, and on a
  bootstrap-open desk, the API really is reachable, and the backend refuses the
  call either way. This is presentation, never permission.
- **Not every nav path is its own route**: `users`' `/install/users` is served by
  `InstallSettings`' `:tab` route, like `sync` and `diagnostics`. Adding a tab
  means touching `TABS` + a `TabsTrigger` in `routes/InstallSettings.tsx`, not
  `App.tsx`.

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
- **SheetBody**: Use for all scrollable content areas. It provides `flex-1 overflow-y-auto space-y-4 px-4 pb-4`. Override with `className="space-y-0 p-0"` only when embedding components that manage their own padding (e.g. EffectParameterForm, pickers).
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

The backend exposes these main endpoints:

- `GET/POST/PUT/DELETE /api/scripts` - Script CRUD
- `GET/POST/PUT/DELETE /api/scenes` - Scene CRUD
- `GET/POST/PUT/DELETE /api/fixtures` - Fixture CRUD
- `GET/PUT /api/channels/{universe}` - Channel values
- `GET /api/universes` - Available DMX universes
- `POST /api/scripts/compile` - Compile a script
- `POST /api/scripts/run` - Run a script directly
- `POST /api/scenes/{id}/run` - Run a scene

WebSocket messages use JSON with a `type` field for message routing.