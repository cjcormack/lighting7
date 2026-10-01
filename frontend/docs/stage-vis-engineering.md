# Stage visualisation: sources, appearance and the renderer

How the stage surfaces decide **what a fixture looks like**. Two independent seams:

- a **channel source** — which layer of the lighting cascade the numbers come from;
- a **fixture appearance** — how a fixture's colour source turns those numbers into a colour and a
  level.

And, since the stage-view plan's session 0, how the 3D view keeps within a browser's memory and
survives losing its graphics context — §"The 3D renderer"; since session 1, its cameras, the
window's viewpoint and the Positions panel — §"Cameras and viewpoints"; since session 4, how a
window draws a viewpoint offscreen for Claude — §"Rendering for `render_view`"; since session 5,
editing on the Plan, Front and Side sections, which retired the SVG plot — §"Editing on the
sections"; and since session 6, the fixture bodies, their cells and beams that leave the aperture —
§"Fixture bodies" at the end.

## The vis source

*Scenery follows the source too — §"Scenery that moves with the show (session 8)".*

The desk transmits one merged DMX frame, and until this landed every stage surface drew exactly
that. In **Blind** that is the wrong picture: the programmer is gated out of the merge, so the stage
keeps showing the pre-blind look while the operator builds a cue they cannot see. The programmer
sheet already solved this at cell granularity (`fixtures-list/ownership.ts::applyStagedValue`); the
stage did not.

`hooks/useVisSource.ts` holds the operator's choice of four:

| Source | What it draws |
|---|---|
| `output` | Final merged DMX — what the desk is transmitting. |
| `outputProgrammer` | Output with the programmer laid over it. Identical to `output` unless Blind is on. |
| `programmer` | Only what the programmer holds; every other channel reads 0. |
| `nextGo` | The look the next GO would produce, over live output. |

`programmer` is literal on purpose: an empty programmer shows an empty stage, and a fixture given
intensity but no position sits at pan/tilt 0 rather than borrowing a position from the wire.

It is a **module store read through `useSyncExternalStore`**, not `usePersistentState`. Two surfaces
read it — the Stage route's View menu and the globally-mounted Positions panel — and
`usePersistentState` reads its key once in a `useState` initialiser and never listens for changes,
so two components sharing a key drift apart the moment one writes.

**It is the window's, since stage-view plan session 3** — `sessionStorage` (`stage.source`), where it
was one `localStorage` value per profile (`stageVisSource`). A hall screen on Next GO and a desk
screen on Output are two facts, so the source rides `windows.viewOptions` as `source` beside
`viewpoint` under the Stage view (`stageViewOptions` in `lib/stageViewpoint.ts`), and the Screens
sheet's Stage row draws a **Source** segment from `lib/windowViews.ts`'s `STAGE_SOURCE_OPTION` —
*Output · Next GO*, the two `Screens.dc.html` §1 draws; a window on either programmer source shows
neither lit, and the View menu still offers all four. The sheet stays generic: the segment is the
descriptor's `enum` kind. A Screens row's *Copy link* carries `source=`, which the Stage route applies
on arrival and strips with `viewpoint=` (`consumeLaunchStageOptions`); `applyStageViewOptions` and
that consumer ignore a value outside the four. A window with nothing of its own starts from the
legacy profile value, so a desk keeps the source it had across the change.

The stored value is narrowed through `isVisSource` on read. A value written by a later build must
not reach code that has no case for it.

### The fourth source: Next GO

Previewing the look the next GO would produce is the one source that isn't a merge of what the desk
already holds — it needs a *hypothetical* cue set composed. The backend does that:
`POST /project/{id}/cue-stacks/{stackId}/preview` returns the channel values a cue would produce,
run through the real `CueAssignmentResolver`, and "next" is a server-owned concept broadcast on
`cueRunStateChanged`. See lighting7 `docs/cue-stacks-engineering.md` §"Preview compose" and
§"Standby".

`hooks/useNextGoPreview.ts` is the client half. Four decisions in it:

**It is pushed, not derived.** `createPushChannelSource` (`api/channelSource.ts`) is a channel
source whose map is *handed to it*, so it is deliberately **not** a `DerivedChannelSource` — there
is no upstream to subscribe to and `refresh`/`dispose` would mean nothing. It carries `holds`
because that is all `createOverlayChannelSource` structurally needs, and it reuses the same
`createFanOut` as the programmer source, so a channel dropping *out* of a look notifies exactly
like one changing value.

**It overlays the wire, and that is not cosmetic.** The endpoint reports only the channels the cue
asserts; everything else is *absent*, not 0. Reading absent as 0 would black out every fixture the
next cue doesn't touch. So `nextGo` resolves to
`createOverlayChannelSource(outputChannelSource, preview)`, exactly as `outputProgrammer` does, and
"nothing to preview" is expressed by emptying the source rather than by a special case.

**"Which cue" is read out of the RTK cache, not off the socket.** The target is the *current*
project (the route sits behind `withCurrentProject` and 409s otherwise), the project playhead's
stack (`projectProgramState.activeStackId` — the stack the Runner's GO fires), and that stack's
`nextCueId` from `projectCueStackList`. The global `cueRunStateChanged` subscriber in
`store/cueStacks.ts` already patches `nextCueId` into that cache, so reading it here *is* following
the run state: no second socket listener, and no re-request on a standby-only or connect-snapshot
frame that left the next cue where it was. The request names the cue id explicitly rather than
sending `null`, so the response can't answer a different cue than the effect keyed on; a superseded
response is dropped by an `ignore` flag in the effect cleanup.

**The request is a query, not a mutation, despite being a POST.** Two things follow from that, and
both are load-bearing. Several stage surfaces can be mounted at once — the Stage route's canvas and
the Positions panel Layout hangs under the header — and RTK Query collapses their identical
args into one request and one cache entry, so two open surfaces cost one request and a GO produces
one POST rather than one per surface.

The overview panel used to render whether or not it was expanded, and this paragraph used to say a
collapsed panel therefore cost nothing. It no longer renders collapsed: `CollapsiblePanel` unmounts
its body once the collapse animation finishes, taking the panel's `StageChannelSourceProvider` and
this query's subscription with it. Nothing above changes — a collapsed panel now costs nothing
because it isn't there — but reopening is a remount, so it re-subscribes, and past RTK Query's
one-minute retention of an unsubscribed entry it refetches rather than reading cache. And every subscriber sees the
same `isError`, which is what lets the status line report the *request* rather than the target.
`refetchOnMountOrArgChange` is set, so re-selecting the source recomposes instead of replaying a
cached look. `cueId` is a required arg rather than "null means the effective next", because under a
query the arg *is* the cache key: a key meaning "whatever the server currently thinks" would serve
one cue's look under another cue's identity. Being a query also puts it outside
`errorToastMiddleware` and `saveStatusSlice` entirely, both of which only see mutations.

Four limits shape what the source can promise. The first two the View menu states outright,
because an operator would otherwise read them as bugs; the last two it does not:

* **Layer 4 only.** A cue whose look is carried by a cue-band effect or a timed preset previews as
  little or nothing. The menu hint says "cue values only".
* **No show running, no preview.** With no active stack — or at the end of a non-looping stack,
  where the backend answers **400** — the source empties and the stage shows plain output. That is
  indistinguishable from a working preview unless it is stated, so `useNextGoStatus` puts "No cue on
  deck — showing output." under the menu entry, and "Preview unavailable — showing output." when
  the request itself fails. The status is read off the shared query's `isError`/`isSuccess` rather
  than off the target, so it can never name a cue the stage isn't actually drawing — there is no
  toast to fall back on, since a 400 here is an ordinary state and queries don't reach
  `errorToastMiddleware` anyway.
* **Another stack's GO doesn't refresh it.** The preview composes against every *other* stack's
  live rows, so a GO elsewhere changes the answer — but the refresh is keyed on the previewed
  stack's own `nextCueId`, which such a GO leaves alone. Within the previewed stack it is exact.
  Deliberate: the alternative is re-requesting on every run-state frame from every stack.
* **Editing the cue on deck doesn't refresh it either.** Same cause: the request effect is keyed on
  `projectId`/`stackId`/`cueId` alone, so changing the *contents* of the previewed cue — a level, a
  layer, a colour — leaves the previously composed look on the stage until the desk moves the
  next cue on. `projectCueStackList` carries no revision that moves when a cue assignment changes,
  so fixing this needs one; a partial key off `presetCount` would refresh for some
  edits and silently not for others, which is worse than the known limit. The operator's handle in
  the meantime is to reselect the source, which `refetchOnMountOrArgChange` makes recompose.

### Injection is at channel level

`api/channelSource.ts` defines `ChannelSource` — `get`, `getByKey`, `subscribeToChannel` — and
`hooks/useChannelSource.tsx` supplies one by context, defaulting to the wire. Every reader takes a
source; nothing but the source knows about layers.

Channel level rather than property level, because `FixtureModel`'s per-frame beam director reads 13
channels by key (pan, tilt, their fine axes, zoom, focus, two gobo wheels, gobo rotation, prism,
prism rotation, and two macros). Substituting at property level would mean touching each of those
reads and knowing which descriptor backs each; substituting at channel level means they all work
unchanged.

`ChannelSource` deliberately has **no `getAll()`**. A composing source would have to allocate a
merged map per fixture per frame to answer it. `getByKey` keeps every source O(1) and
allocation-free, which is what that frame loop requires.

**The provider wraps only the canvases** — the `Stage3D` element in `routes/Stage.tsx`
and the Positions panel's rows and Plan tab (`components/positions/`). Not `<main>`: the docked
`StageFixtureControlPanel` renders `FixtureDetailView`, a live editing surface that must keep
reading and writing the real wire whatever the stage is previewing. The same reasoning keeps
`useVirtualDimmer` on the default source — all of its consumers are editing controls.

R3F's `<Canvas>` is a separate reconciler root, but context still crosses it: `@react-three/fiber`
v9 wraps the Canvas root in an `its-fine` context bridge (`useBridge()`). Nothing else in this app
depends on that, so it is worth a smoke test after any R3F upgrade.

### Resolving the programmer to channels

`lib/programmerChannels.ts` turns `programmer.state` into a channel map. Two things to know:

**`ProgrammerState.channels` is the sideband, not the programmer's output.** It carries only what
the property model can't lift: raw `updateChannel` writes on unbacked channels, raw pan/tilt axis
writes, and unpark hand-downs. A dimmer or colour written through `programmer.set` lands in a
*property* entry and never appears there. Reading the picture off `channels` alone yields an almost
empty stage.

So the real work is `resolveEntryChannels`, which mirrors lighting7's `PropertyChannelWriter`:
level → one channel; colour → r/g/b plus white/amber/uv **only where the descriptor has that
channel** (the client-side stand-in for the backend's `WithWhite` / `WithAmber` / `WithUv` trait
gate); position → coarse pan/tilt only, because the backend writes no fine channels for a position
assignment either. A shape mismatch resolves to nothing rather than a guess.

The descriptor index covers **element keys as well as fixture keys**: a multi-head fixture's entries
are keyed by element key, so an index built from `Fixture.properties` alone would drop every
per-head write.

**Sideband-last is a documented approximation.** The backend arbitrates a property entry against a
sideband slot on the same channel by write sequence (`ProgrammerStore.Slot.seq`), which never
reaches the client. A deliberate property write already absorbs the sideband beneath it, so a slot
surviving under one is not a state the backend normally holds; where the two could differ, favouring
the sideband keeps raw writes visible, which is what the sideband is for.

**`holds(key)` is not `getByKey(key) !== 0`.** The programmer legitimately holds a dimmer *at* 0, and
treating that as "no opinion" would let the wire value show through in exactly the case Blind most
needs to preview. The overlay source keys off `holds`.

That has a consequence for change notification: `notifyChanged` compares **presence as well as
value**, not value alone. A channel going from absent to held-at-0 is no change to the
programmer-only source (0 either way) but a real one to the overlay (wire value → 0). Comparing
values with absent-treated-as-0 would see nothing and wake nobody, so a cue holding a dimmer at full
would keep painting full after the operator zeroed it in a blind programmer.

Latency: `programmer.state` arrives on the backend's 100 ms provenance debounce, but
`programmerWsApi`'s `applyLocalEntry` echoes our *own* writes into `entries` immediately, so an
operator's fader drag previews at input rate.

## Fixture appearance

`components/fixtures/fixtureAppearance.tsx` answers "what colour is this fixture, and how lit", for
any of five colour sources: an RGB colour property, a colour wheel, a gel, a bare dimmer, or a
multi-element pixel bar (plus a placeholder for a patch with no fixture).

`FixtureAppearance` carries the hue at **full** brightness and the **linear** 0..1 level. Both raw,
because each medium curves them differently:

- the DOM marker folds `perceptualBrightness` into a box-shadow and an opacity;
- the 3D scene splits them — perceptual on the lens, **linear** on the cone and pool, because those
  opacities double as the `LIGHT_OFF_OPACITY` beam cull.

This is why `useColourAppearance` is not the shared piece: it returns only a pre-baked CSS string and
drops the level. It stays as it is for the swatch callers that only want the string.

### Why a render prop

Each colour source needs a *different* set of value hooks — `useColourValue` wants a
`ColourPropertyDescriptor`, `useGroupColourValues` subscribes to a variable-length channel list, a
gel fixture needs neither — so they cannot collapse behind one hook without breaking hook order.
`FixtureAppearanceSource` dispatches to a leaf component per source, each with a fixed hook set, and
hands the result to `children`. That constraint is why the SVG plot (retired in session 5) went
without live colour for so long, and the render prop is the way around it.

Consumers — **four mounting readers**: the Positions panel's `UnitChip` (DOM — one leaf per chip,
the swatch and the level off one subscription; it replaced `StageMarker` with the overview it
drew);
since the busk view's rig band, `RigTile`'s live bar and pips (`components/busking/RigTile.tsx`),
one leaf per fixture tile, which reads a cell's colour off `segments` by the element's position in
the patch's cell list; since the side sheet, `SideSheetFold`'s selection colour dot
(`components/busking/SideSheetFold.tsx`), one leaf for the first selected head; and the Colour
tab's **hidden leaf per selected head** (`components/busking/ColourSheet.tsx`), which renders
nothing and only reports into `lib/liveAppearance.ts` so *Pick* can answer in Pads focus with the
rig tiles folded away. The cues' `MiniStage` borrows only the dispatch's default colour and is not
a reader. `FixtureModel` keeps its own **imperative** mirror of the same dispatch on purpose — R3F is a separate reconciler root and
store-driven re-renders drop beat-rate changes, so the 3D path writes straight to the scene from the
channel callback. Three copies of the shape, two of the code; changing the dispatch means changing
`fixtureAppearance.tsx` and `FixtureModel`'s `ColourSync` together.

## Paired lanterns (extra placements)

A paired dimmer is one patch drawn more than once: one circuit, an SL and an SR lantern on the same
bar. The patch carries its other lanterns as `FixturePatch.extraPlacements` (backend contract in
`lighting7/docs/fixtures-engineering.md` §"Extra placements (paired dimmers)"), and every stage
surface draws each one **lit from the patch's own channels** — a lantern is the fixture in a
second place, never a second fixture. Four rules keep that true:

- **`useProjectedPatches` returns the lanterns apart from the fixtures** — `points` (one per
  fixture, unchanged) and `extraPoints` (one per placed lantern, carrying `placement`). A surface
  that only draws the rig (the overview panel, `MiniStage`) draws both; the editors keep reading
  `points` for everything a gesture does. Folding the lanterns into `points` would have made every
  drag, snap, count and bulk operation meet a fixture twice, and a drag on a lantern would move the
  fixture's own placement to where that lantern hangs.
- **A lantern selects its fixture and is never dragged.** On a section while editing its press is
  the fixture's selection with no drag, a marquee over it selects the fixture once, and it is an
  alignment guide for the fixture it pairs with. In 3D it is a `FixtureModel` over the patch with the
  placement's seven geometry fields laid on (`patchAtPlacement` in `stage3d/lanterns.ts`), its own emitter slot after the
  fixtures', and **no `onEditFocus`**, so the translate gizmo can never bind to it. Lanterns are
  moved in the patch form's *Also hung at* section (`ExtraPlacementsFields`), which starts a new
  one mirrored across the centre line (`lib/extraPlacements.ts`).
- **Selection lights the pair.** Selection is by patch key, so selecting a fixture highlights every
  lantern it has, which is the answer to "which lanterns is this circuit?".
- **An unpositioned lantern is not drawn**, on either view: the 3D `worldPositionFor` reads a null
  coordinate as 0, and a lantern at the origin would be a claim about the rig.

What is left for later: dragging a lantern on a section (the bulk route already takes
`extraPlacements`, so it is a client change), and a lantern of a different type or gel from its
fixture (additive on the backend if a venue ever needs it).

## Variable-length fixtures

A lightstrip's length is a fact of the install, not of the model: the type says so with
`FixtureTypeInfo.acceptsLength`, and a patch then carries its own `lengthM` (backend contract in
`lighting7/docs/fixtures-engineering.md` §"Variable-length fixtures"). A run laid round several
sides — a ring round the stage edge on one controller — is one patch whose **extra placements are
the other sides**, each with a `lengthM` of its own (null takes the patch's). Everything above about
paired lanterns holds for a side: lit from the patch, selects the patch, never dragged.

- **`lib/fixtureLength.ts` is the one reading of it.** `drawnLengthM` answers side → patch → type
  default for a type that takes a length, and the type's own length for every other type — so a
  stored length that reached a fixed-length patch some other way (an import is written as stored)
  is never drawn. The editors ask `acceptsLength` whether to offer the field at all.
- **3D**: `FixtureModel` sizes its body from `drawnLengthM`, and a side's length reaches it because
  `patchAtPlacement` lays the placement's `lengthM` over the patch's.
- **On a section**: `useProjectedPatches` gives such a fixture (and each side) a `span` — its two
  ends, projected — and the section edit layer takes a press anywhere along it
  (`edit/sectionHits.ts`), since a long run is pressed at its ends as often as its middle. A span
  that projects to a point (a run seen end-on in an elevation) is its point. The long axis is `longAxisLighting`, which
  mirrors `FixtureModel`'s body rotation exactly — a YXZ Euler of `(basePitchDeg, baseYawDeg,
  baseRollDeg)` in world space, so the rigging's pose places the body but does not turn it, pitch
  never moves the long axis, and roll is the only turn that lifts it off level (90 stands a run on
  end). Change one, change both.
- **The form**: *Length* sits under the fixture's placement, and the extra-placements section reads
  *Other sides of this run*, each side with its own *Length*. An out-of-range value is kept and
  flagged, never clamped mid-keystroke, and the form will not save it.

## The 3D renderer

Session 0 of `../docs/plans/stage-view-plan.md` fixed the Stage view's renderer before anything is
added to it. The findings are in the design record (`stage-view-design/INDEX.md` §"What is wrong
today"); what follows is what the code does now and why, since each piece is easy to undo by
"tidying".

### Memory: no MSAA in the composer, DPR at 1.5

`Bloom.tsx` passes `multisampling={0}`. `@react-three/postprocessing` defaults to 8× MSAA on
half-float targets, and at a Retina full-window size that colour target alone ran to hundreds of MB —
the likeliest cause of Safari's "reloaded because it was using significant memory". Beams and pools
are soft additive shapes MSAA does nothing for. The canvas keeps `antialias`, but once the composer
draws the scene it no longer reaches the bodies' edges; that is accepted.

The canvas's `dpr` is `[1, 1.5]`: at 2 every target behind the canvas holds four times the pixels of
1×. `StageEmitters` drops the volumetric march by a third above 1×, since 1.5 is still 2.25× the
shaded area.

### The frameloop renders on demand

`frameloop="demand"`: the canvas draws while something moves and not at all when the stage is still.
The rule for anything new in the scene is **a write that is not an R3F prop must ask for a frame**.
R3F already invalidates on an applied prop change, and drei's `OrbitControls` and
`TransformControls` on their own `change` events (the orbit's damping included). What else asks:

- **Channels.** `FixtureModel`'s beam director subscribes to the thirteen keys it reads per frame on
  the *active* source — so a programmer-only or Next GO preview moves the heads — and `useLiveColour`
  asks after every imperative colour write. Both go through `useStageInvalidate`
  (`stageInvalidate.tsx`), a context rather than `useThree`, because the colour syncs are rendered
  outside a canvas by their tests.
- **Time.** A movement or LED macro, a spinning gobo and a turning prism move with the clock, not with
  DMX, so while one runs the director asks for the next frame itself. That is the one case where the
  canvas keeps rendering with no channel moving. Their `delta` is clamped to 0.1 s, which also covers
  the long gap after an idle spell.
- **Imperative buffer writes from effects** — `hideSlot` when a fixture loses its beam or
  unmounts, a body's `hide` and `setActive` — and the region uniforms (below). A body's parts are
  written from its own frame loop (§"Fixture bodies"), so they need no request of their own. The light table is packed in the emitters'
  flush, which runs inside a frame (`EMITTER_FLUSH_PRIORITY`, before the composer), so it needs no
  request of its own; a budget change does ask.
- **The label layer** hands the store the canvas's `invalidate`.

A hidden browser pane or background tab gets no `requestAnimationFrame`, so it draws nothing until it
is shown; that was true of the old `always` loop as well.

### Context loss

Nothing handled `webglcontextlost`, so WebKit taking the context back left a blank canvas under a
layer of labels. `ContextLossWatcher` (in `Stage3D.tsx`) now cancels the event's default — without
which the browser never offers the context back — and the view draws **3D paused** with the reason
and **Restore 3D**. The label layer is hidden with it. A browser that restores on its own
(`webglcontextrestored`) clears the state; **Restore** does not wait for that and remounts the canvas
(`canvasKey`), a fresh renderer and a fresh context, because a context taken to save memory may
never come back. The camera comes back where it was: the pose is in `sessionStorage` (§"Cameras and
viewpoints" below). The View menu's **Test recovery** (3D only) drops the context through
`WEBGL_lose_context` so the path can be exercised on purpose.

### The emitters are sized by the rig

The shared emitter meshes (`StageEmitters`) used to give every slot `MAX_PRISM_LOBES` beam instances
and `MAX_WASH_PIXELS` wash instances, times the region count on the receivers — at 45 fixtures and 16
regions, 4,320 region cookies and 11,520 wash-region instances whether or not a prism or a pixel bar
was hung, every one through the vertex shader each frame. (Session 3 retired the receivers
altogether — §"Light lands through one surface shader" below.) Now `emitterNeedsForSpec` (`emitterNeeds.ts`)
says per slot what it can draw, from the fixture's **body** (§"Fixture bodies"): **no lobes** for a
body that does not emit, **one** for a single cell, **six** where it has a prism, or **one per cell**
for a body of several (a batten, a blinder, a bar of heads); and **lights**, a row of the light table
for each lobe of a single cell and at most four for a body of several, each averaging a run of cells
(`lightRuns`). `buildEmitterLayout` (`emitterLayout.ts`) turns that into per-slot offsets for the
two blocks. The per-pixel wash block a pixel strip had until session 6 is gone: a bar's cells are
beams now.

- **One statement of the rule.** `FixtureModel` draws from the same `bodySpecOf` the layout sizes
  by; two copies would drift, and a fixture that drew more than its slot was given would write into
  the next fixture's block.
- **The handle drops out-of-block writes.** Every writer bounds-checks `(slot, lobe)` and
  `(slot, light)` against the layout, and the directors draw at most `lobesFor(slot)` lobes and
  `lightsFor(slot)` lights. A frame where the patch and the layout disagree draws less, never into a
  neighbour.
- **The rebuild keys on `layout.signature`**, not the layout object, which is fresh every render;
  equal needs address identically, and rebuilding would throw away every slot's written state.

### Light lands through one surface shader

Until session 3 a beam drew its pool as instanced **cookies** — one per lobe per receiver: the stage
floor, the back wall, and every region (the region and wall and floor instances, beam and wash). A
receiver the cookies did not know about took no light, which is why the plan capped regions at 16,
and a venue made of rooms, flats and drapes would have needed cookies for each. They are gone, with
the pool shaders and `makePoolMaterial`. Light lands through **one receiver shader** instead
(`scene/surfaceShader.ts`), on every venue and set surface, every region, the stage floor, and —
without a room — the back wall and the catch floor:

- **The lights are a data texture, not a uniform array** (`scene/lightTable.ts`). The prototype's
  48-light ceiling was the uniform budget; a float `DataTexture` of six texels a light
  (`MAX_LIGHT_BUDGET` = 256 rows, read with `texelFetch` in a GLSL3 loop over `uLightCount`) has
  none. Every light has a slot in the `LightTable`, sized from the emitter layout; the directors
  write their slot each frame (`writeLight`, `clearLight`), and the emitters' flush packs the
  **budget**'s worth of the brightest lit slots into the texture, in slot order. Since session 6 a
  light carries its beam's **apex** (behind the aperture), its frame (the head's right axis and the
  tangent of the half-field) and its aperture (the apex → aperture distance, the iris and a
  segment's aspect) — what the surface shader's `beamMask` shapes the pool with.
- **The light budget is the viewer's** — the View menu's *Light budget*, 32 · 64 · 128 · 256, default
  64, per browser in `localStorage` (`stage.lightBudget`, `scene/sceneView.ts`): the shader's cost is
  pixels × lights, and what a machine's GPU affords is the machine's fact, not a window's. A light
  the budget drops still draws its beam; it lands on nothing that frame. The container carries
  `data-lights="<packed>/<lit>"` for measurement.
- **Axial beam reach stands in for occlusion** (`scene/beamReach.ts`), until the quality tier's
  shadow maps (`FU-STAGE-QUALITY-TIER`). The director casts each lobe's axis against the scene's
  **colliders** — oriented boxes in three.js space turned about y, the regions' OBB maths: a wall a
  2 cm slab behind its face, a deck its whole box — out to `MAX_THROW_M` (40 m) and writes the first
  hit's plane into the light's fourth texel. The surface shader lights nothing behind that plane
  (`REACH_EPS` 3 cm), so a pool on the floor lands whole however oblique the beam, while the floor
  under a deck the beam landed on stays dark. The **cone** is drawn to the hit, capped at the desk's
  stylised `BEAM_LENGTH` (8 m) — the light reaches further than the cone is drawn, and a hall-length
  throw no longer draws a 40 m spear across the house.
- **No falloff with distance**, for `washConfig.ts`'s reason: a pool that dimmed with throw would
  disagree with the uniform cone above it. The design record's item 8 asks for the aperture to set
  a distance fall-off; the desk keeps its uniform pool, and the aperture sets the distance the
  **focus** is measured from instead (§"Fixture bodies"). The lit colour is the finish × (fill + an exponential
  roll-off of the light) plus a little of the light itself (`uSheen`), so a pool still reads as the
  beam's colour on the near-black finishes a hall is painted in, and a rig at full does not clip to
  white. Gobos show in the air, not yet on surfaces.
- **The beam volumes still shadow on regions** — `regionShadowMask` (`beamLobes.ts`, which was
  `beamCookies.ts`) and the region uniforms below. Only the receivers went.

The region uniforms survive for that: a region drag writes the RTK cache per frame, so the volume
shaders read each region from `uRegionCenter` / `uRegionHalf` / `uRegionYawCs` rather than a baked
buffer, and a drag is `writeRegionUniforms` and an `invalidate`. The yaw pair is `(cos yaw, sin yaw)`,
the region's own turn — `rayObbT` rotates a ray in by −yaw, and `beamReach` makes the same turn. It
was `(cos −yaw, sin −yaw)` once, which turned every shadow box the wrong way; invisible at 0° and 90°.

**A modelled room replaces the stage's own shell** (`scene/stageSurfaces.ts`). While a `ROOM` is
drawn the stage box's back wall, the catch floor and the grid (outside edit mode, where it is a
measure) give way to it, and the beams in the air clip to the lowest room floor and the furthest
upstage wall (`beamClipFor`). The stage floor stays; a room's faces sit 4 mm outside its box
(`ROOM_FACE_INSET_M`) so a region's top at the room's floor does not z-fight it.

### Haze degrades before frame rate

The raymarched beam volumes are the costliest thing drawn per pixel and the least essential — a gobo
through fewer march steps is grainier, not wrong — and since session 6 **every** beam marches
(§"Fixture bodies"), so `HazeGovernorProbe` (in `Stage3D`) feeds
`scene/hazeGovernor.ts` the time between frames of a **continuous run** (a frame counts only when the
frame before asked for it from inside the loop, R3F's `internal.frames > 1`; on demand, the gap is
otherwise how long nothing moved). A run averaging over 28 ms steps the march down a tier (step
scale 1 → 0.66 → 0.42 → 0.25); one under 19 ms for three seconds takes a tier back; 24 frames hold a
tier before it may move. Each run is judged on its own frames: when one ends the governor drops
what it measured (`endRun`), so the recovery clock never counts through idle time and a new run
never steps on the last one's average; the tier itself is kept. A gap over a second is a pause, not
a frame — deliberately far above any frame worth governing, because a software renderer at ~700 ms
a frame is exactly the case the rule is for. The light budget is the viewer's and never touched by
it. The View menu's **Haze** switches the air off for the window — the cone and volume meshes are
then not drawn at all, rather than marched to nothing; the container carries `data-haze-tier`.

The haze *level* is still `washConfig.ts`'s constant. The plan wanted it to follow the hazer's DMX,
but nothing in a rig says which fixture is the hazer (the Commemoration Hall's is a generic dimmer),
so that waits for a typed identity (`FU-STAGE-HAZE-FOLLOWS-HAZER`).

### Regions hang down from `centerZ`

`centerZ` is a region's **top surface** — the deck — which is what the backend
(`models/stageRegions.kt`), the MCP schema and the aim tool always meant. The frontend drew the box
*up* from it, so project 15's "Main stage" (top at 0, 0.95 m thick) stood 0.95 m proud of the deck
and pools landed on the wrong surface. Every reader agrees now: `StageRegionMeshes`,
`computeRegionGeometry` (the emitters' OBBs), `worldCornersFor` (a section's outline and both
handle sets), and the height handles on the orbit camera (`RegionEditHandles`) and on the Front and
Side sections (`edit/SectionEditHandles.tsx`) — dragging the top moves `centerZ` and keeps the
floor, dragging the floor changes only `heightM`. The orbit camera's rotation handles sit on the
deck, where a deck at 0 keeps them above the stage floor. A region placed by a click
(`routes/Stage.tsx`) stands on the clicked height — `centerZ` is the click plus its height — and a
section's bounds reach down to the lowest region floor (`sceneBoundsLighting`). Regions authored while the frontend read `centerZ` as the floor now
draw one thickness lower; they are re-set on the desk, not migrated (the plan's P5).

### Lenses and housings

Every lens was a sphere at `0.5 + 0.5 × brightness` opacity, so a lamp at dimmer zero was half-lit in
its full hue and bloomed. `lensColour` (`bodies/palette.ts`, `paintLens` until session 6) makes a
lens dark glass at level 0 and the hue at level 1, never brighter than its level — every cell of
every body goes through it. Since session 6 a lens is a flat disc or segment on the barrel's face,
one instance per cell, and housings, yokes and hangers are matt near-black **lit by the surface
shader** like any surface, so a beam crossing a lantern lights it; the active highlight tints them
to a slate. The torus ring still marks the selection.

### The label layer

Labels were a drei `<Html>` each — a React root and a `backdrop-blur` per label, sixty-odd over the
canvas. `StageLabel` now renders an empty anchor group and registers it with a `StageLabelStore`
(`stageLabels.ts`), which owns one plain `<div>` per label in a single layer over the canvas.
`StageLabelDriver` lays them out once per rendered frame: project each anchor, place greedily by rank
— hovered or selected first, then positions (rigging and regions), then fixtures — and hide whatever
collides, with a couple of pixels' gap. Three rules:

- **The mode is the store's, not the call sites'.** *Positions* (the default) shows the rigging and
  the regions and a fixture only while hovered or selected; *All fixtures* every fixture that fits;
  *None* nothing at all — not even the selection, unlike the prototype. So `FixtureModel`,
  `RiggingMeshes` and `StageRegionMeshes` mount their label unconditionally and say only its `kind`
  and whether it is `emphasised`.
- **A label is registered once per store** and restyled in place on a rename or a hover; tearing the
  `<div>` down on every hover was the churn the layer exists to avoid.
- **The flag was a boolean.** `StageViewFlags.labels` is a `StageLabelMode` now, and a desk's stored
  `true` / `false` reads as *Positions* / *None* (`toStageLabelMode`).

### The 3D text font

drei's `Text` loads its default font from jsdelivr, which an offline desk cannot reach. The two floor
labels (*FOH*, *upstage*) are given the Liberation Sans TTF that react-pdf's pinned pdf.js ships,
imported as an asset URL so Vite bundles it — the same transitive reach `ScriptViewer` makes for
pdf.js's worker. If react-pdf ever drops pdf.js, the import fails the build rather than falling back
to the network.

## Cameras and viewpoints

Session 1 of the stage-view plan gave the one scene five cameras and put the plan in the overview
panel. `Stage.dc.html` and `Positions.dc.html` are the layout authority; the record's §4 "One camera,
which forgets" and §5 "Both 2D surfaces lose to label density" are the why.

### Five cameras, one scene

`StageCameraRig` (in `Stage3D`'s canvas) mounts one of three rigs for the window's viewpoint:

- **Orbit** — a perspective camera under drei's `OrbitControls`, as before.
- **Eye** — a person standing somewhere. Drag turns the head, grabbing the scene as a panorama
  does; scroll narrows or widens the lens rather than walking. It starts where the orbit camera
  stands, looking where it looks — so *orbit there, then look around* is the gesture until session
  2's saved points and seats. Pitch stops at ±85°, the lens at 15–90°.
- **Plan · Front · Side** — orthographic sections under `OrbitControls` with rotation off. Their
  screen axes are exactly `lib/stageProjection.ts`'s `h` and `v` (`sectionHits.test.ts` pins all
  three), which is what lets the section edit layer work in the projection's metres: Plan has
  upstage at the top, Front has +X to the right, Side looks from +X (audience right, stage left) with
  the house on the left.

Four rules:

- **A section's camera stands on its section plane.** `orthoSection` (`stageCameras.ts`) puts the
  camera just outside the scene's bounds on its own side, with a near plane of a centimetre. What
  lies between the section and the camera is nothing by construction, and anything beyond the
  plane on the camera's side is clipped. The bounds are the stage, every rigging's reach, every
  region (hanging **down** from `centerZ`) and every placed fixture, so today nothing of the rig is
  cut. Session 3's venue is what the planes will cut through — the ceiling in plan, the balcony in
  front, the stage-left wall in side.
- **A section refits itself until the operator moves it** — to the canvas as it resizes, and to the
  rig as its lists arrive — and then it is theirs. *Frame* slides it **within** the plane, so framing
  never changes what is cut.
- **Each camera is a plain three.js object made the default through the store**, not drei's
  `<PerspectiveCamera makeDefault>`. drei's orthographic camera allocates a render target for a
  feature this never uses, and R3F sizes a default camera only on the *next* resize, so
  `useDefaultCamera` fits the frustum itself when it swaps one in.
- **A camera swap must ask for a frame after the composer has rebuilt.** `@react-three/postprocessing`
  rebuilds the composer (new passes, new targets) whenever the default camera changes, in an effect
  *after* the swap's own frame — which the old composer drew through the old camera. On a `demand`
  frameloop nothing asked again, so the view sat on the previous camera's picture while the labels
  had already moved. `Bloom` hands the composer a ref callback that invalidates on each new instance.

The volumetric beam shader built each pixel's ray from `cameraPosition`, which fans out like a
perspective camera's. Under an orthographic camera the rays are parallel: the shader reads
`isOrthographic` (three's built-in uniform) and casts along the camera's forward axis from the
camera plane, so an ortho view draws the same haze as orbit, and `t >= 0` is the section's cut. The
beam shell's rim and the pixel strip's glow take the same flag: under ortho their view vector is the
constant view axis. The label layer needed nothing — it projects every anchor through whatever the default camera is.

### The viewpoint is the window's, and the pose is kept

`lib/stageViewpoint.ts` holds the viewpoint — a camera, `orbit | eye | plan | front | side`, or
since session 2 a saved view's uuid (below) — in **`sessionStorage`** — per tab, `lib/immersive.ts`'s reason: the hall screen sits on Front all night
while the desk screen orbits. It replaced the `stageViewMode` key in `localStorage`, one value per
profile. It rides `windows.viewOptions` as `viewpoint` under the new **Stage** entry in
`lib/windowViews.ts` (Stage is a window view but not a live view — no immersive), so the Screens row
draws a *Viewpoint* picker over the cameras, saved views and seats (a segment over the five cameras
until session 2) and sets it on another window. A Screens row's
*Copy link* carries `viewpoint=`, which the Stage route applies on arrival and strips. The toggle in
the route's header is the camera segment, and the **viewpoint picker** beside it lists the built-ins
and *Frame the selection*; session 2 added saved views and seats (below).

`lib/stageCameraPoses.ts` keeps the orbit pose (and the eye's, while on Eye) in `sessionStorage`,
written as the camera moves (at most every 150 ms, and once more on unmount) and read on mount — so
a route change, a reload and the context-loss *Restore* all land where the camera was left. Moving
*into* Eye from another camera forgets the eye's pose, so it seeds afresh from the orbit camera; a
remount already on Eye keeps it. The seed reads the orbit pose **noted in memory on every move**
(`noteOrbitPose`), not storage: the eye's seed is read in the render that mounts it, before the
orbit rig's unmount has flushed its pending write, so storage can be a move behind. An embedded camera (the Positions panel's plan) passes
`persistCamera={false}` and never touches the Stage view's.

### Saved views and seats (session 2)

A viewpoint can also be a **saved view** — a `stage_viewpoints` row, by its uuid, in the same
`viewpoint` key and the same `sessionStorage` slot. `isStageViewpoint` accepts a camera or a uuid, so
`applyStageViewOptions`, `consumeLaunchStageOptions` and the stored value all take one whether or not
this window has fetched the row yet (a reload, or a view another window names). A name that is
neither is still ignored. `components/stage3d/savedViewpoints.ts` is the pure half:

- **An `ORBIT` row lands the orbit camera** at its eye, circling its target; **an `EYE` row lands the
  eye** where it stands, facing its target, through its lens; **a `SEAT` row lands the eye at the
  seat's seated eye** (`lib/stageSeats.ts`, the backend's seat maths — 1.15 m up, 5 cm back), facing
  its target or the stage's centre line when it has none. So the three-way camera split of session 1
  holds: a saved view draws through the orbit or the eye rig, never a camera of its own.
- **Landing is once per pick, not per mount.** The rig is keyed by *camera*, and it lands a saved
  view when the window has not already landed on it — `lib/stageViewpoint.ts`'s **landed marker**
  (`stage.landedViewpoint`, per tab), which the rig writes once it has landed and a pick clears. So a
  remount (a route change, *Restore*, a reload) keeps wherever the operator has looked since, and
  picking the view again — even the one already current — lands it afresh. Two saved orbit views in
  a row move the mounted rig rather than remount it. Every landing moves the camera imperatively and
  invalidates; the bloom composer's rebuild on a *camera swap* is `Bloom`'s as before.
- **Moving into Eye from a saved eye or seat view keeps the pose** — you are already standing there;
  from a saved orbit view, or any camera but the eye, it forgets it and seeds from the orbit camera,
  as session 1 did. Which camera a uuid draws through is noted from the rows
  (`noteSavedViewpointCameras`), falling back to the landed marker until they arrive.
- **A view that cannot be landed** — its seating deleted or reshaped so the seat is gone — is listed
  disabled (*seat gone*) in both pickers, and a window sitting on one keeps its camera where it was.

**The picker** (`StageViewpointPicker`, `Stage.dc.html` §4) lists the built-ins, then *Saved views*,
then *Seats*, *Frame the selection* and ***Save this view…***. Saving reads the live pose
(`readOrbitPose`, and `readEyePose`, which now prefers a pose noted in memory as the eye moves, for
the orbit's reason) and builds the row with `viewpointFromCamera`: the orbit saves an `ORBIT` view, the
eye an `EYE` view — or, sitting in a seat view, a `SEAT` view of the same seat with the head turned
where it now is, so it keeps following the seat. A section cannot be saved. The window moves onto the
new view, which is what announces it.

**Sit in a seat…** (session 3; the picker's item, or S — Escape cancels) arms a pick on the seating:
the seating mesh takes the pointer while armed, the seat under it is tinted and named (*Sit in row F,
seat 6*), and a click lands the Eye rig at that seat's seated eye facing the stage. Arming turns the
window's Seating layer on, since a pick needs seats to click. **An unsaved seat is a viewpoint of its
own, in the same key**: `seat:<seating uuid>:<seat id>` (`seatViewpointRef`, `parseSeatViewpointRef`),
in the same `sessionStorage` slot and announced as `viewpoint` like any other — no new top-level
announce key, which the desk's bare Json would drop. It lands through the same landed marker as a
saved view, the trigger reads *Row F, seat 6* with an *unsaved* caption, and the Screens row's picker
lists it as unsaved. *Save this view…* from there saves a `SEAT` row (`viewpointFromCamera` already
built one from a seat), and the window moves onto the new row. A seat whose seating has gone is
dropped once the scene has loaded, as a deleted saved view is.

**The Screens row's Viewpoint is a picker now** (`Screens.dc.html` §1): `lib/windowViews.ts`'s
`STAGE_VIEWPOINT_OPTION` is a `picker` kind, and the control is `StageViewpointRowPicker` — the Stage
view's own, handed to the sheet by `Layout` through `ScreensSheet`'s `controls` and loaded lazily, so
the sheet never imports the Stage view (it never learns the word) and nothing loads the scene queries
until a Stage row is on screen. It lists the cameras, the target project's saved views and its seats,
and writes the value as `windows.viewOptions {viewpoint}`.

### The scene, built by kind (session 3)

**Each element kind has a builder** in `components/stage3d/scene/builders/`, one file each, replacing
`StageSceneBoxes`. A builder is pure — an element in, **parts** out (`scene/sceneParts.ts`: boxes,
quads, cylinders, discs and pleated cloth in the element's own lighting frame, each with a finish and
whether it stops a beam) — so `builders.test.ts` pins each kind without a canvas, and one renderer
(`StageSceneElements.tsx`) turns parts into meshes on the surface shader. The group is placed at the
element's origin — its base, a platform's top, a flown piece's trim — and turned by its yaw.

- **Room**: inward-facing quads — floor, ceiling and four walls, leaving out the sides `omit` names —
  so a camera outside still sees in. The floor and ceiling take their own finishes.
- **Proscenium**: the opening centred across the wall above its sill — two piers, a sill and a head
  that stop beams — and a surround that does not.
- **Flat**: a wall with its openings cut from its stage-right end, a pier before each and a sill and
  head around it (a door has no sill); an arch is drawn square-topped.
- **Drape**: one pleated cloth, or for a `DRAW` operation two halves gathered to their sides by the
  `open` state (closed when unstated), each hanging from its own edge. A cyc defaults pale.
- **Platform**: the deck hangs **below** its Z, which is its top; a rail on the edge it names. A
  platform linked to a drawn region leaves the deck to the region.
- **Seating**: no parts, only seats — `seatList` in `lib/stageSeats.ts`, the same seat maths the
  backend's `SeatingParams.seat` is mirrored from, so the seats drawn are exactly the seats a `SEAT`
  view can name. One `InstancedMesh` for the block; it raycasts only while a seat pick is armed.
- **Object**: a box, cylinder, shade or disc by its `shape`. **Flown** pieces stand at `trimM`.
  **Emissive** finishes glow at their colour.
- **`hidden` and `states.visible: false`** build nothing, for every kind.

**The View menu's Venue · Set · Seating layers** choose which elements a window draws — per window in
`sessionStorage` (`stage.sceneLayers`, `scene/sceneView.ts`), not announced; a seating element
follows **Seating** whatever its layer. Only the Stage route's canvas reads the scene (`showScene`);
the Positions plan does not, so the collapsed panel stays as cheap as it was.

**The ortho sections cut on purpose now.** The section plane stays **rig-derived** — Front cuts at
the rig's downstage edge, so the house and its seats in front of the plane are not drawn over the
stage — but the far plane reaches the drawn venue's bounds (`orthoSection`'s `beyond`,
`sceneElementBounds`), so a room's back wall, a balcony and the upstage flats stay in the picture
behind it. The plan section cuts the ceiling away by the same rule. Rooms face inward, so a section
camera outside the hall sees in through the side it cuts.

### Frame the selection (F)

F — bare, never from a field; ⇧F is full screen — and the picker's item bring the selection into
view in whatever way the current camera moves: the orbit pulls in along the direction it had until
the selection's sphere fills the lens (grandMA3's *Auto*), the eye turns its head, a section slides
within its plane and zooms to fit. What it frames is the Stage view's own selection
(`framingPoints.ts`: every lantern of a fixture, a region's middle, a rigging's position), and **the
desk selection when that is empty** — so a chip pressed in Positions, the busk band or the
programmer is framed by the Stage window that pressed nothing. A group target frames its members; a
cell's key is opaque on this side and frames nothing, and so does a patch the view does not draw
(`stageHidden`, infrastructure). The orbit's pull-in takes the narrower half of the lens — on a
portrait canvas the horizontal field is the tighter. O goes back to Orbit.

### Positions

`components/positions/PositionsPanel.tsx` replaced `StageOverviewPanel` and `StageMarker` behind the
header's stage toggle, on every route. The old panel placed every name by percentage in a 420 px box
with no decluttering; no labelling scheme fixes a true-scale plan of three bars a metre apart.

- **Rows are positions, upstage first**, the stage edge marked at Y = 0. `positionRows.ts` derives
  them on every render — a unit's row is its placement's rigging, the row's depth is where the
  rigging hangs, the unit's place is its world X — and stores nothing (`FU-BUSK-RIG-PLOT`'s rule).
  A paired dimmer is one chip per row with its count; a unit on no rigging goes in one
  *Free-standing* row at its units' mean depth; a rigging with nothing on it has no row.
- **A chip is the desk selection** (`setDeskSelection`): a tap selects that unit on every window,
  ⇧ or ⌘ toggles it, and a tap on the sole selected unit clears. The swatch is live colour at
  level; a unit with neither a dimmer nor a colour (a hazer, the Twin Shot) shows no level.
- **A group chip dims** the units outside it; every row keeps its place. The filter remembers the
  project it was set in, since group ids are the desk's and would match nothing in another.
- **The Plan tab is `Stage3D` on the plan section**, lazily imported (`PositionsPlan.tsx`) so
  three.js stays the Stage route's weight. A fixture clicked there sets the desk selection, and a
  click on the one fixture selected clears it, as a chip does; *Open in Stage* from the Plan tab
  opens the Stage view on Plan.
- **Closed costs nothing.** The tab and the group filter live above `CollapsiblePanel`; every query,
  channel subscription and the canvas unmount with the body.

## Rendering for `render_view`

Session 4 of the stage-view plan gave Claude eyes (D4): the MCP tool `render_view` answers a PNG of
a stage viewpoint, and since the desk cannot draw WebGL, **a desk window draws it**. The desk half —
which window, the one-shot request, the bound upload, the named errors — is lighting7
`docs/mcp-engineering.md` §"`render_view`"; this is the window's half.

**Any window, on any route.** `StageRenderHost` (`components/stageRender/`) is mounted once in
`Layout`, so a window on Busk or the Programmer is asked as readily as one on the Stage view. It
subscribes to `stageRender.request` (`api/stageRenderApi.ts`) — a frame the desk sends to the one
socket it chose, so a frame that arrives is this window's — and draws nothing of its own. While a
request is in hand it mounts `stage3d/render/StageRenderJob.tsx`, **lazily**: the job imports
`Stage3D` and with it three.js, which therefore still loads only when the Stage view, the Positions
plan or a render first needs it, never with the app shell. The outcome goes back over REST
(`store/stageRenders.ts`) with the request's token; both endpoints are silent in
`errorToastMiddleware`, because a refused answer is the model's to hear and the operator at this
window asked for nothing. One render at a time: a request arriving mid-render is answered *busy*.

**It is the Stage view's own scene, not a copy.** The job mounts `Stage3D` itself, with a `capture`
prop, and every other prop means what it does on screen:

- **The camera** comes from `resolveViewpoint` (`savedViewpoints.ts`): a camera draws through
  itself; a saved view and an unsaved seat land through `resolveSavedViewpoint` /
  `resolveSeatViewpoint`, the maths the picker lands with. `orbit` and `eye` are the poses a fresh
  window opens on (`defaultOrbitPose`, and the eye seeded from it), not any window's current pose.
- **The scene** is `showScene` with the default layers (Venue, Set, Seating, Haze all on), the
  default view flags **minus labels** — the label layer is DOM over the canvas, so a frame read off
  the canvas never had them — and the machine's light budget, read, not written.
- **The light** comes through the same `StageChannelSourceProvider` as the Stage view, now with a
  `source` it can be handed (the request's, not this window's) and an `onSettled` that says when a
  derived source holds what it will hold: the programmer source once it is built over the fixture
  list, Next GO once the cue on deck is known and its preview has answered
  (`useNextGoSourceState`). The Stage view draws the wire for the frame it takes to build one; a
  one-frame render waits it out.

**The capture canvas** (`CaptureCanvas.tsx`) is what `Stage3D` renders into instead of R3F's
`<Canvas>`: an R3F root (`createRoot`) on a **detached** `<canvas>` — never put on the page, so the
window's own canvas, layout and pointer are untouched — configured with its size, `dpr: 1` (the size
asked for is the picture's), `frameloop: 'never'` and a preserved drawing buffer, and drawn with
`advance` for that root alone. Two reasons it is not `<Canvas>`: `<Canvas>` sizes itself from a
`ResizeObserver` and draws on `requestAnimationFrame`, and a background tab delivers neither — this
one renders in a hidden tab, yielding between frames by `MessageChannel` rather than timers, which a
hidden tab clamps. And an on-demand frameloop would keep drawing while a live show's channels move;
this one draws exactly the frames it is told to. `flat`, no tone mapping and `antialias` are the
view's; the clear colour is the view's CSS background, which a PNG has to have drawn. Two things
`<Canvas>` does that a bare root does not, both found by rendering from a window that had never
shown the Stage view: it registers the THREE catalogue (`extend(THREE)`) as it mounts, without which
`<ambientLight>` throws in a window that has drawn no canvas yet, so the capture canvas calls it too;
and it connects an event system, so on this root `events.connected` is `false` — anything reading
it must fall back on falsy, not on `??` (the Eye rig did). A throw inside the root is caught there
(`SceneBoundary`) and ends the render with its reason at once, since nothing above a detached
canvas would hear it. **Contexts do not cross into an R3F root by themselves**: `<Canvas>` bridges every one, and the capture canvas
bridges the one the scene reads from outside it — the vis source. A scene component that starts
reading another outside context must be bridged there too, or a render silently draws its default.

**Read-only in every sense.** No DMX and no programmer write — the view in view mode writes neither.
None of this window's facts move: `StageCameraRig`'s `oneShot` lands the render's viewpoint
**unconditionally** and records nothing — no pose in `sessionStorage`, no landed marker (it was
`persist` that marked, and a render's rig is never persisted); the layers and the source are the
render's own, not read from or written to this window's stores. The job's container sits far
offscreen, `aria-hidden` and `inert`, holding only the scene's DOM overlays, all empty.

**When it draws.** The job fetches afresh every read the scene draws from — the project, saved
views, scene elements, patches, regions, riggings, fixtures and types — with `forceRefetch`, so a
`set_scene` a moment before is in the picture and an entry that errored earlier cannot fail this
render before its own fetch has run. Then it resolves the viewpoint once (a refetch mid-render moves
the cache `Stage3D` draws from, never the camera, and never unmounts the canvas), mounts `Stage3D`,
and waits for the capture canvas to report the scene mounted — a `Suspense` boundary around it, so
the stage text's font counts — and for the source to settle. Then four frames a task apart (the
emitters lay out and pack the light table; the bloom composer rebuilds after its camera swap and
needs the next), and `toBlob`. It gives up three seconds before the desk would, naming what never
arrived, so Claude hears *waiting for the scene to mount* rather than a bare timeout.

**Disposal.** The host unmounts the job on its outcome, before the upload goes; R3F's `unmount`
disposes the renderer and forces the context's loss — session 0's memory rule, and a second
context on an iPad is the expensive case. A context lost mid-render (`ContextLossWatcher`) ends the
render with that reason: a render has no *Restore* to offer.

Tests: `api/stageRenderApi.test.ts` (the frame), `savedViewpoints.test.ts` (`resolveViewpoint`),
`stageRender/StageRenderHost.test.tsx` (a request mounts one render, its outcome goes back bound to
its id and token, busy, a throwing render, and nothing toasted) and
`stage3d/render/StageRenderJob.test.tsx` (the props `Stage3D` is handed, drawing once the scene
reports in, giving up with a reason, and this window's viewpoint and landed marker untouched).

## Editing on the sections

Session 5 of the stage-view plan (D1) moved editing on Plan, Front and Side onto the 3D scene and
deleted the SVG plot (`components/stage2d/`, about 3,100 lines). There is one renderer now: the
scene on a section is drawn by `Stage3D`, and editing there is **`edit/SectionEditLayer.tsx`**, DOM
over the canvas as the label layer is. `svgPlotRetired.test.ts` keeps the plot gone.

**The layer owns the pointer while the view edits on a section** (`sectionEditing` in `Stage3D`:
Edit on, a section camera, not a render, no seat pick armed). It hit-tests, drags, marquees, pans and
zooms in the section's own metres — `lib/stageProjection.ts`'s `{h, v}`, v screen-down — and the
scene's own gizmo, 3D handles, placement plane and grid stand down, so R3F and `OrbitControls` never
see a press there. Three pieces make that possible:

- **The section camera reports where it looks** (`StageCameraRig`'s `onSectionView`): after every
  frame drawn, its centre in the section's metres, its zoom (pixels a metre) and the canvas's size,
  into a small store (`edit/sectionView.ts`). A section's screen axes are the projection's `h` and
  `v` exactly, so nothing more is needed; `sectionHits.test.ts` pins that for all three cameras.
  The canvas draws on demand, so **the rig asks for a frame when a listener arrives** — Edit turned
  on over a still stage draws nothing new, and without it the layer would wait, blank, for a fader —
  and `Stage3D` clears the store when editing stops or the section changes, so a layer never
  hit-tests against another camera's view.
- **A pointer is resolved from the canvas's live centre** (`offsetToSection`), never its top-left
  and the reported size. The camera keeps its centre and zoom as the canvas resizes, but the report
  lags a frame or two — and arming `+ Scenery` folds the side panel away, widening the canvas at the
  very moment of the click that places. Resolving against the reported width put that click 180 px
  off; the SVG's viewBox is `xMidYMid meet` for the same reason, so the chrome stays on the scene
  through the lag.
- **The camera takes pan, zoom and fit from the layer** (`StageCameraHandle.section`): a pan slides
  the camera and its target within the section plane, a zoom keeps the point under the pointer
  still, and both mark the section moved so it stops refitting — the rule a section's own controls
  already kept. Neither moves along the view axis, so editing never changes what a section cuts.

**What a press lands on** (`edit/sectionHits.ts`, pure and node-tested): a fixture within 12 px (a
lantern pressing as its fixture; a variable-length run along its whole span), over a bar within 7 px
(or half its thickness), over the **areas** — regions and scenery — where the **smallest outline
under the pointer wins**, so a prop on a deck is the prop and the deck beside it is the deck; a tie
goes to the region. A `ROOM` is never pressed on the canvas (it is the whole hall, and a press
anywhere would take it); it is picked from the editor's list. The plot used paint order for the
areas, which let a region hide everything standing on it.

**A drag settles exactly once, however it ends** (`useSectionDrag`). Its moves write the RTK cache
and only the settle writes the desk or rolls back, so the layer unmounting mid-drag (a section
left, Edit turned off, the context lost) settles it where it had got to, and so does a second
pointer starting a drag before the first lifts. *Sit in a seat…* is not offered while editing on a
section (`canSit`), for the same reason the SVG plot withheld it: the pick needs the pointer the
layer holds.

**Scenery on a section** is selected and dragged like a region — body drag once selected, guides and
grid snap, out-of-plane preserved — and written per frame to the RTK cache (`writeElementPlacement`)
so the builders redraw it as it moves — `sceneBuilds` keeps each element's build while the list
holds the same object (`SceneBuildCache`), so only the dragged piece is rebuilt — then `PUT` once on
release and rolled back if refused. A flown
piece with a trim slides across only in an elevation, since its Z is not where it is drawn. The
layer draws the selected element's outline, which the scene has no highlight for. Resize and yaw
handles for scenery are not built; the element form takes the numbers.

**Edit chrome never reaches a render.** The layer is `Stage3D`'s DOM outside the canvas, mounted only
while `capture` is null; the section camera's report is only wired while editing on screen; a
render's root has no event system (`events.connected` is `false` there), and nothing edit-only reads
it. `render_view` of a section drawn from a window that is editing on one draws the scene and no
grid, handles or guides.

### Parity with the SVG plot

The SVG view's gestures as they stood at the start of session 5 (plan §10), and where each lives now.
Route-level gestures were always renderer-blind; they are listed because the claim is the whole
list.

| SVG plot (`Stage2DView` and friends) | On the sections now | Plan | Front / Side |
|---|---|---|---|
| Background drag pans (4 px threshold), middle button too | the layer, through `section.panBy` | ✓ | ✓ |
| Wheel zooms about the pointer | a native non-passive listener, `section.zoomAt` | ✓ | ✓ |
| HUD: axis legend, cursor read-out, snap step, zoom − / + / fit, edge-on notice | `edit/SectionHud.tsx` | ✓ | ✓ |
| Snap-step grid (minor lines dropped under 6 px, major every 5, datum axes) | `edit/SectionGrid.tsx` over the canvas | ✓ | ✓ |
| Click selects; ⇧ adds, ⌘ toggles; click on nothing clears | the layer, `selectionIntentFor` | ✓ | ✓ |
| ⇧ / ⌘ + drag on empty space marquees fixtures (lanterns select their fixture; ⌘ adds) | `marqueeHits`, `sel.selectMany` | ✓ | ✓ |
| Drag a selected free fixture: in-plane, out-of-plane axis kept, guides then grid | `fixtureDrag` | ✓ | ✓ |
| Drop a free fixture on a bar within 12 px: hung on it, the bar lit green | `riggingUnderPoint`, `dropOntoRigging` | ✓ | ✓ |
| Drag a hung fixture: slides along its bar, snapped in bar metres, clamped to its ends; refused edge-on | `localXAlongBar` | ✓ | ✓ |
| A lantern is never dragged | `dragFor` | ✓ | ✓ |
| Drag a selected region / bar (rigid; pitch kept); bar refused edge-on | `regionDrag`, `riggingDrag` | ✓ | ✓ |
| Region corner resize (opposite corner pinned) and yaw handles, 15° snap | `RegionSectionHandles` | ✓ | — |
| Region top / floor height handles (top keeps the floor), vertical only | `RegionSectionHandles` | — | ✓ |
| Bar endpoint handles (other end pinned); hidden edge-on | `RiggingSectionHandles` | ✓ | ✓ |
| Alignment guides drawn while snapped | `AlignmentGuides` | ✓ | ✓ |
| Armed `+ Region` / `+ Rigging` / tray click places, snapped, the unseen axis from `placementDefault` | the layer's click | ✓ | ✓ |
| Unplaced tray: arm (⇧/⌘ extends), Select all, Hang all on truss…, fanned along X by the step | `edit/UnplacedTray.tsx`, on the sections | ✓ | ✓ |
| Arrow nudge by the step (⇧ ×10), coalesced, along a bar for a hung fixture | `edit/useStageNudge.ts`, the section's axes | ✓ | ✓ |
| ⌫ removes from stage, ⌘D duplicates, Esc cancels placing | the route | ✓ | ✓ |
| Bulk panel for a multi-selection (align, distribute, hang, set depth, mirror) | `edit/StageBulkPanel.tsx` | ✓ | ✓ |
| ⇧ held suspends snapping; the Snap toggle and step | `edit/useSnapGrid.ts` | ✓ | ✓ |
| Shortcut list | `edit/StageShortcutsPopover.tsx` | ✓ | ✓ |
| View flags hide regions, rigging, fixtures (and their presses) | the scene, and `SectionScene` | ✓ | ✓ |

Not carried over, on purpose: the plot's own fixture shapes, colours and label declutter (the scene
and the label layer draw those), and its stage-envelope rectangle (the scene's box outline is it).
Session 5 also made **a click on nothing clear the selection in view mode** too, on every camera
(`handlePointerMissed`), which the record listed as noticed on the way.

### `+ Scenery` and the element form

The Edit header's `+ Scenery` (`Edit.dc.html` §1) lists the seven kinds plus tabs and a flown piece
(`edit/sceneryKinds.ts`): each is armed and placed with a click like a region, with sizes to start
from, and opens **`EditSceneElementForm`** (`components/stage/`) in the `StageEditorPanel`, beside
the region's and the rigging's forms. The editor's list gains a *Scenery* section, the only way to
pick a room. The form draws the kind's `params` fields and saves one partial `PUT`, `params` whole
(`elementDraft.ts`); it checks nothing but a name. The desk checks it, through the
`validateStageElement` `set_scene` uses: a 400 lists every problem, and `elementProblems.ts` files
each beside the field it leads with (`params.openings[1]` under that opening), or the first it
names, or at the top — none is dropped. A reshaped seating that seat views sit in is a 409 the form
asks about (*Save anyway* forces). *Moves with* is read-only and empty until session 8. The three
element endpoints are in `SILENT_ENDPOINTS`, so the route's placement and drag toast their own
failures. The design record's two builders (*Proscenium hall from measurements…*, *Ask Claude*) are
not built: the template is `set_scene`'s over MCP, which the in-app chat does not carry.

## Fixture bodies

Session 6 of the stage-view plan replaced the eight kind bodies (`stage3d/fixtureBodies/`, deleted)
with parametric **archetypes** built from parts, gave every coloured element its own lens and beam,
started every beam at its **aperture**, and shaped pool and haze with one **beam mask**. The design
is the record's §"Fixture bodies and the lantern library", items 1–3, 8, 9, 11 and 12; the
prototype proved the maths and is not the code.

### The archetype: the lantern, the type's body, then the words

`bodies/archetype.ts` is pure and node-tested: a patch, its fixture and its type in, a `BodySpec`
out — the archetype, its size, its **cells**, whether it emits, its field angle and its edge
softness. `emitterNeeds.ts`'s `bodySpecOf` is the one call, read by `Stage3D` (to size the emitters
and the body instances) and by `FixtureModel` (to draw), so the two cannot disagree about a cell.

**The archetype is chosen in three steps, first answer wins** (session 7 moved the first two in
front of what used to be the whole rule):

1. **The lantern** — for a type that takes one (`acceptsLantern`, the generic dimmer), the lantern
   the patch names, else its kind's default, from the library (`GET /lanterns`, read through
   `useLanternIndex` and handed to `bodySpecOf` as an index). The lantern gives the archetype, its
   dimensions, its lens, its field (zoomed where the focus sets one) and its oval, and the patch
   takes its family's **kind** (`LANTERN_FAMILY_KIND`: a PC is a `FRESNEL`, a flood and a cyc a
   `WASH`, a downlight `GENERIC`) — the desk derives `kindOverride` from it at the write boundary,
   and reads the lantern first in `RigBriefing.isMovingHead`, so the two sides agree. A lantern is
   never a mover. See `docs/fixtures-engineering.md` §"Lanterns and focus".
2. **The type's declared `body`** (`FixtureTypeInfo.body`, from `@FixtureType(body = …)`): an
   archetype, a mover's head and a lens diameter. A type that declares none answers `null`.
3. **The words, as they were** — the fallback for every type that says nothing:

- **A mover** is anything with a tilt axis, or of kind `MOVING_HEAD` / `SCANNER` — so a Source Four
  Revolution, a `PROFILE` that tilts, is a mover with a profile head. The head is a spot, a wash or a
  profile by the words in the type key and model (`spot`, `beam`, `mac-250`, `wash`), else by the
  type's beam edge; a mover with several coloured elements is a bar of heads (the Slender Beam Bar
  Quad — its heads tilt together until `FU-STAGE-INDEPENDENT-HEADS`).
- **A Twin Shot** is the cannon.
- **Otherwise the kind** — the patch's `kindOverride` over the type's: `PROFILE` a profile barrel (a
  box profile where the type names a Cantata, Prelude or Harmony — the library's are box profiles by
  their own archetype), `FRESNEL` a fresnel (a PC is the same archetype without the barn doors,
  which only a PC lantern from the library tells apart),
  `PAR` a can, `WASH` a flood, `STRIP` a batten — a strip of **tape** where the type takes a
  per-install length (`acceptsLength`) — `BLINDER` a blinder, `LASER` and `EFFECT` an effect box,
  `GENERIC` a house downlight (the prototype's default for a generic dimmer).

The field angle is the patch's `beamAngleDeg`, then a zoom channel, then the **lantern's** (at the
focus's zoom where it has one), then the **family's**
(`FIELD_DEG`: a profile 26°, a fresnel 45°, a PAR 32°, a spot 16°, a wash 25°, a batten 30°, a
blinder 60°…) — it was 30° for everything. Which bodies emit: a batten and a blinder always (their
types have no beam angle to set, so `acceptsBeamAngle` could not say), tape and the cannon never,
everything else as `acceptsBeamAngle` said before.

### Parts, instanced; three levels of detail

`bodies/bodyGeometry.ts` builds each archetype from parts — the **base** (a mover's), the **yoke**
that pans, the **head** that tilts with its accessories (shutter handles, barn doors, a colour
frame), a **lens** per cell and a **hanger** — in two levels, `full` and `simple`, shared by spec,
mount and level and **reference-counted by the canvases that draw them**: a key no canvas holds is
disposed, so every length a strip of tape is dragged through does not stay behind (a new layout
takes its hold before the old one lets go, or a key both share would be disposed mid-swap). The
numbers a body is placed by — its pivot, a standing lantern's lift, where a hanger attaches, the hit
box — are `bodyFrames`, which builds no geometry. `bodies/StageBodies.tsx` instances them **per part across the
rig**: one `InstancedMesh` per (geometry, level, part), one for every disc lens and one for every
segment lens in the rig, one for the hangers and one for the billboards. Sized by a `BodyLayout`
(each slot's spec key and mount) built beside the emitter layout in the same slot order, and rebuilt
only when its signature changes, as the emitters are — a drag, a pan or a tilt rebuilds nothing.

- **The fixture is still a node rig.** `FixtureModel` keeps its placement group (which
  TransformControls drags), a mount, a yoke and a head as empty groups, and its body director copies
  their world matrices onto the instanced parts every frame it renders, at the level its size on
  screen calls for (`bodyShownFor`: under 44 px the simple mesh, under 9 px a billboard glyph — a
  dark dot in its lens colour). It runs after the beam director, so a head's tilt is this frame's.
  A write that stores what the instance buffer already holds is dropped, so a still rig flags
  nothing for upload however often the canvas draws.
- **The pointer presses an invisible hit proxy** — a box round the body at rest, `visible: false`
  on its material so it still raycasts — so the click, the hover and the gizmo keep their plumbing.
  The instanced meshes take no pointer (`NO_RAYCAST`), and the section edit layer's hit radii never
  touched meshes.
- **Housings are lit by the surface shader**, tinted per instance (the selection's slate), so a
  beam that crosses a lantern lights it. That surfaced a bug from session 3: three defines
  `USE_INSTANCING_COLOR` in the vertex stage only (the fragment stage gets `USE_COLOR`), so the
  receiver never tinted any instance — the seat pick's hover tint included. It now tests both.

**A static lantern keeps the mover's rig** (item 3). Its yoke turns by its yaw about the vertical and
its head by its pitch, then roll, inside it, then a fixed quarter-turn that lays the barrel level
(`staticHeadQuaternion`) — so the yoke hangs or stands plumb whatever the lantern is focused at.

**Pitch 0 is level, and +pitch aims down**, as `docs/fixtures-engineering.md` defines
`base_pitch_deg` and the MCP schema states it (yaw 0 facing the audience, +yaw towards audience
right). Until session 6 the view drew a static lantern 90° of pitch off: pitch 0 straight down, and
+pitch tilting it *upstage* at yaw 0 — while the rigs authored through the schema, the Commemoration
Hall's among them, were entered level-is-zero (its ADV2 fronts at pitch 10–14, its house lights at
90, its Liteobars at 60). The quarter-turn is about the body's own X, the long axis, so
`longAxisLighting` (`lib/fixtureLength.ts`) is unchanged and roll still stands a strip on end; roll now
turns a static lantern about its own beam. Movers are untouched — their mount is their base
orientation, 0 standing and 180 hung, which `FixtureAim` solves from. `FixtureModel.test.tsx` pins the
documented directions, the long axis and the roll.

### Mounts: hung or standing

`bodies/mount.ts` (item 11): a fixture on a `LEDGE` or `FLOOR_STAND` **stands**, on anything else it
hangs, and on no rigging it hangs from nothing. The kinds are the desk's `STANDING_RIGGING_KINDS`,
which `describe_rig` reads to say a unit stands on its rigging and to flag a moving head whose base
orientation disagrees with it; `src/test/resources/stage/standingRiggingKinds.json` pins the two
lists (`mount.test.ts`, `StandingRiggingKindsTest`).

**The kind decides how a body is carried, never which way it points.** A moving head's mount is
already its `basePitchDeg` — 0 stands, 180 hangs — and `FixtureAim` solves from that, so turning a
mover over by its rigging's kind would draw a beam `aim_fixtures` does not aim. So: a standing static
lantern's yoke runs down to a plate, its pivot lifted by the yoke's height so the plate rests on the
ledge; a hung one's runs up to a clamp and a **hanger** up to the bar, drawn when the bar is more
than 2 cm above it. A mover hangs from its base only while its base is up (`basePitchDeg` about 180);
one at 0 on a bar stands on top of it. The Commemoration Hall's balcony Revolutions draw and aim
hung under a pipe until P5 makes the balcony a ledge and sets them to 0 — which `describe_rig` now
says.

### Cells

A body's **cells** (item 9) are its apertures, in the head's frame: one lens for a lantern or a
mover, and for a batten, a blinder or a bar of heads one per element that has a colour or a level of
its own — a colour property, a colour-wheel setting, a white or amber slider, or a dimmer (the
2-cell blinder's cells are warm and cold white sliders). The Kam Liteobar 252 is three segments, as
its type declares (`KamLiteobar252Fixture.kt:117`), not twelve. Cells without elements share the
fixture's colour. At most `MAX_CELLS` (16).

- **Every cell is its own lens, level, beam and light.** `CellColourSync` (in `FixtureModel`) reads
  each cell's element — its colour, its own dimmer, and the fixture's master — paints its lens and
  writes its beam's colour and opacity; a body of one cell keeps `ColourSync`, the fixture's own
  dispatch. Cells are 3D only: the 2D dispatch answers one colour a fixture, folding a pixel bar's
  elements into one in its multi-element arm. `ColourSync` lost that arm (and `MultiPixelColourSync`)
  with session 6, since a bar is drawn as cells; its single-colour arms still mirror the 2D ones,
  which `colourDispatchParity.test.tsx` pins.
- **Lights on the surfaces are capped at four a fixture** (`MAX_LIGHTS_PER_FIXTURE`), each averaging
  a contiguous run of cells (`lightRuns`) — its aperture the run's span, its colour × level their
  mean — so a 12-pixel bar takes four of the surface shader's lights, not twelve. The air keeps a
  beam per cell.

### A beam leaves its aperture

Item 8: a beam leaves the **aperture** — the lens, or a cell's face — at the aperture's own size and
shape, and spreads at the field angle, so its **apex** sits `aperture radius / tan(half-field)`
behind it (`apexDistanceM`). For a 19° Source Four's 170 mm lens that is half a metre back — the
lamp — and the cone passes the lens at the lens's width; for a wide LED face it is far behind, so a
batten segment or a wash leaves as a column. It replaced the lens-point origin (`lensRef ?? head ??
group`).

- **The volume is a frustum**: the hull is laid from the apex (`composeBeamHull`, built from the
  head's own axes so a segment's rectangle lines up with the head), and the march runs from the
  aperture (`near`) to where the beam lands. A disc throws a cone frustum; a **segment** a
  rectangular one, whose chord is four half-spaces through the apex, each linear in the ray.
- **The light on the surfaces uses the same apex** for its cone test and measures its focus from
  the aperture; its reach is cast from the aperture.
- **Edge softness is data**: the family's (`SOFTNESS` — profiles and spots hard, everything else
  soft), moved towards soft by a **frost** channel (`resolveSoftness`); a focus channel still
  sharpens the edge at its focal distance. `FixtureTypeInfo.beamEdge` still only picks a mover's
  head.
- **A DMX iris is drawn** (`resolveIris`: the channel runs open → closed, down to 12 % of the field).

### One beam mask, and every beam marched

`beamMask.ts` is the cross-section both the surfaces and the haze shape a beam by — the field circle
or a segment's rectangle, the iris, the softness — in the head's frame, with the field edge at 1;
the shutter blades are arguments in the same frame (§"The lantern's focus: the cut, the gate and
the oval"). The GLSL and a TypeScript twin are written from
one set of constants and the twin is pinned by `beamMask.test.ts`.

**Every beam in the air is raymarched** now, round or rectangular: the hollow cone shell an open beam
used to be could not show a soft edge, an iris or a shutter cut, so it went, with `makeConeMaterial`
and the shell's buffers. Three things keep the march honest:

- **The beam's opacity is the ceiling.** The alpha is `opacity × (1 − exp(−gain × density × chord))`,
  so side-on a beam sits at the opacity the shell drew it at, and a long chord — the camera looking
  down a barrel — never adds up past it. A full look on the Commemoration Hall is as bright as it was
  on `main`.
- **The air thins as a beam spreads** (`VOL_SPREAD`): the same light over a wider cross-section.
- **The hull is depth-tested again**, face by face: its front face while the view ray starts outside
  the beam, so the stalls, a pros wall or a flat in front of a beam hide it as they hid the shell,
  and its back face while the ray starts inside (the camera in a beam, or a section's plane cutting
  one) — the prototype's rule. With `depthTest` off, as the gobo volume had it, every beam drew over
  the seats in front of it.

**Budget.** The march is the costliest thing per pixel and the haze governor is its only brake: 12
steps (8 above DPR 1), stepped down to a quarter before the frame rate gives. Measured once, on
2026-09-30, in the desktop app's Chromium pane on the operator's Mac — **not Safari, and not an
iPad**, which §10 of the plan still asks for — on the Commemoration Hall at a full look with haze
on (every dimmer up but the house lights, 58 of 58 lights packed, a 1606 × 2236 px canvas at the 1.5
DPR cap): an orbit drag ran at a median of 17–21 ms a frame and the governor held tier 0. So the
sizes stay as they were — 12 steps, a 64-light default budget — until the Safari and iPad passes.
`data-lights` and `data-haze-tier` on the container are what to read.

### The lantern's focus: the cut, the gate and the oval

Session 7 gave a conventional its focus (`docs/fixtures-engineering.md` §"Lanterns and focus"), and
`BodySpec` carries what the beam needs of it: `blades` (null while every blade is out, or the lantern
has neither shutters nor barn doors), `frameTurnDeg` (the gate's turn where it has blades, plus a
PAR lamp's where it has an oval), `iris` (where it has one; a DMX iris closes it further, the
smaller winning) and `ovalRatio` (narrow over wide, as tangents of the half-angles). The focus knob
sets the edge softness within the family's range (`softnessForFocus` in `lib/lanterns.ts`: a
profile 0.04–0.9, a PC 0.3–0.85, a fresnel 0.6–1), so a fresnel at its sharpest is still softer
than a profile at its softest. Focus applies to a single-cell body only; a lantern is always one.

**The blades are arguments to `beamMask`**, in the same head frame the field circle is drawn in —
`u` along the head's right axis, `v` = beam × `u`, up for a level lantern — so the pool and the haze
cut by one rule. A blade is a straight line `depth` in from the field's edge (0.5 reaches the centre)
turned by its angle about the middle of its edge; everything past it is dark, softened by the same
edge as the field. **They are packed, not given a texel**: each blade is 12 bits — 63 depth steps
× 64 angle steps (±30° by the degree, `packBlade`) — two to a float, so the four fit texel 5's `.y`
and `.w` (`packBlades`), and the iris moved into texel 2's alpha beside the edge hardness
(`packEdgeIris`, both to 1/1023). The haze takes them as one more instanced attribute
(`aBeamBlades`, flat varying). The GLSL and the TypeScript twin changed together and
`beamMask.test.ts` pins them — a quarter-in top blade cuts a straight edge, an angled one a sloped
one, a gate turn turns the lot.

**A gate or lamp turn rotates the frame, not the mask**: the director turns the head's right axis
about the beam by `frameTurnDeg` before it writes the light row and the hull, so the mask never
learns an angle it would have to spend a texel on. That is also why a lantern's own roll turns its
cut — the right axis is the head's.

**An oval is a negative aspect.** A disc is aspect 0 and a segment a positive depth-over-width; an
oval PAR writes −`ovalRatio`, and `v` is divided by its magnitude before the mask (in the surface
shader and the haze alike), so the unit circle becomes the lamp's ellipse — its wide axis along the
turned right axis. The hull is widened along the wide axis only. `FixtureModel.test.tsx` pins the
three §9 checks in unit form: a Source Four 19°'s apex puts the cone at the lens's width at the lens
(`archetype.test.ts`), a quarter-in top shutter draws a straight edge on the floor, and an oval
PAR's long axis turns with `lampRotationDeg`.

**The Focus tab** (`StageFocusPanel`, a tab of `StageFixtureControlPanel`) and the patch editor's
**Lantern** box both mount `components/lanterns/FocusCard.tsx`: the lantern and a live cross-section
of its gate (`GatePreview`), then only what the lantern can take — depth and angle per blade (or per
barn door), the gate's turn, the iris, sharp ↔ soft, zoom within its range, a PAR's lamp — and a
switch between the lanterns of a pair, which are focused separately. The tab writes each change
into the patch list's cache at once, so the Stage redraws the cut as a blade moves — **by value**
(`paintDraft`): RTK lands a cache update by applying Immer patches, which *clones* the shutters
array into the cache, so a paint that assigned the draft's own array would differ every time, and
the effect that repaints on each new `patch` looped until React gave up (found on a desk: any blade
key crashed the Stage view). It saves one `PUT` after a 350 ms pause (a placement's through the whole `extraPlacements` list, which
`toPlacementInput` carries the focus in); a refused write re-reads the list. A DMX fixture's tab
names the optics its channels drive instead. The patch list's **Lantern** column picks the lantern
from the library, or reads out a DMX type's declared body, and its **Mount** column says *standing*
for a unit on a ledge or a floor stand.


## Scenery that moves with the show (session 8)

**The desk resolves, the canvas draws.** Cues, stacks and Looks can move scene elements — a drawn
drape's `open`, a flown piece's `trimM`, any element's `visible` — and the desk resolves what the
stage shows (lighting7 `show/SceneryResolver.kt`, `docs/cue-stacks-engineering.md` §"Scenery") and
streams it as `scenery.state` (`api/sceneryApi.ts`, `store/scenery.ts`'s form-3 `liveScenery`
entry). Each entry is a move: the state an element is going to, the one it is leaving, and how long.
This side never resolves precedence; it only draws a move.

**The source picks the scenery too.** `StageChannelSourceProvider` provides `StageSceneryContext`
beside the channel source: the live scenery for Output and the two programmer sources (the desk
already counts the programmer's live Looks, blind excepted), and for **Next GO** the preview's
`scenery` (the whole stage as it would resolve with the cue on deck, each element with `from` and the
GO's duration), its moves started when the answer lands — so selecting Next GO draws Q2's tabs
opening as the GO would open them. `Stage3D` reads the context itself, outside its canvas, so the
capture root's bridge carries nothing new; a `render_view` draws every move landed.

**Drawn by laying the state over the element.** `lib/scenery.ts`'s `sceneryElements` writes each
element's interpolated state into its `params.states` before `sceneBuilds` — the builders already
read `open`, `trimM` and `visible` from there (a drawn drape's halves, a flown piece's `elementBaseZ`,
`hidden` building nothing) — so nothing in the builders changed, and **beam reach follows for free**:
the colliders come from the builds, so closed tabs stop a beam and the surface shader lights nothing
behind them. An element left at its base comes back as the same object, and one whose drawn state
has not moved since the last call too (a `WeakMap` per element), so the build cache rebuilds only
what is moving.

**The clock ticks only while something moves.** `useSceneryClock` advances `performance.now()` on
`requestAnimationFrame` and `invalidate()`s the canvas every frame **until the last move lands**,
then stops (§"The frameloop renders on demand"): a tab drawing over four seconds asks for its
frames, an idle stage asks for none. Moves are eased sine in-out, the desk's own curve
(`SceneryService.ease` / `easeSceneryT`); a piece appearing shows at once, one disappearing goes at
the end. A frame is anchored at receipt from its `elapsedMs`, never its wall-clock `startedAt`, so a
skewed tablet does not replay a finished move.

**Authoring** is one shared editor, `components/scenery/SceneryEditor.tsx` — element · state ·
time · remove, the state a per-kind choice (`sceneryChoices`: Closed / Half open / Drawn, Trim · in /
out, Shown / Hidden; a state no choice writes stays selectable as itself) — mounted as *Scenery* in
Cue properties (with a time: blank moves with the cue's fade), *Set for this stack* in
`CueStackForm` and *Scenery while live* in `LookDetailSheet`. Each gesture saves the owner's
**whole list**, as the desk's `PUT …/scenery` takes it, and the rows are a draft the editor holds, so
a refetch landing mid-save (or while a time is typed) never puts an older list back — the
`StageFocusPanel` rule. The cue card (`CueDetailContent`) reads the cue's own changes and, hatched,
what it shows by tracking (`CueDetails.trackedScenery`).

## Confetti (session 9)

**Every fire throws confetti, real or rehearsed.** `stage3d/StageConfetti.tsx` subscribes to
`effects.fired` (`api/effectsApi.ts`) and throws `FLAKES_PER_TUBE` (380) flakes from the fired tube's
muzzle: the patch's world position through `worldPositionLighting`, the tube's axis from its base
yaw and pitch (`muzzleDirection` — up for a standing cannon, down for a hung one, the Twin Shot's two
tubes splayed ±12°, A left and B right). The frame is broadcast, so every window throws the same
burst; a rehearsed fire (Blind, or a window whose vis source is the programmer — lighting7
`docs/fixtures-engineering.md` §"@FixtureTrigger") sends nothing to the wire and is drawn exactly the
same way. A fire for a fixture this canvas has not placed draws nothing.

**The model is pure** (`confetti.ts`): a fixed pool of typed arrays — position, velocity, the floor it
settles on, a state (idle · flying · settled) and a seed per flake — stepped by `stepConfetti` with
drag, gravity capped at a flutter terminal of 0.85 m/s, and a sway, until a flake reaches its floor
and **settles**: it never moves again until its slot is thrown anew. The floor is the deck inside
the stage's footprint and the house floor (`houseFloorZ`, a modelled room's base) outside it. The
pool is `MAX_FLAKES` = five tubes plus a margin (2000), and it is a **ring**: a sixth tube in the air
reuses the oldest flakes rather than growing anything. `confetti.test.ts` pins the count, the
settle, the terminal and the ring.

**One instanced mesh, one draw call, unlit.** The pool is one `InstancedMesh` of a 3 × 2 cm plane,
allocated once per canvas, with a `MeshBasicMaterial`: it reads no light, so it touches neither the
surface shader's light table (§"Light lands through one surface shader") nor the haze program, and
the four-lights-per-fixture cap is not involved. Its vertex attributes are the plane's three
(position, normal, uv), the instance matrix's four and the instance colour — **eight**. Colours are
written once per throw, matrices every frame a flake flies; a settled flake's matrix is written flat
on the floor at a fixed turn and then left alone. The flakes are never raycast, so a click on the
stage reaches what is under them.

**It asks for frames only while a flake flies** (§"The frameloop renders on demand"). A fire
invalidates once; each frame that steps a flying flake asks for the next; when the last one settles
nothing asks again, and the settled flakes are a static draw like the rest of the stage. A hidden
pane or tab gets no `requestAnimationFrame`, so its burst waits and resumes when shown — `dt` is
clamped to 50 ms, so it does not jump.

**Measured (2026-10-01).** In the browser (Chromium, a 1280 × 800 Stage view on a bare stage with
one floor-standing Twin Shot), one rehearsed tube drew **305 frames over 5.1 s** and then **no draw
calls at all** across the next two seconds; the confetti is one `drawElementsInstanced` of 2000
instances per frame beside the rest of the scene's ~41 calls. The CPU side — `stepConfetti` plus the
matrix write, measured in Node on the same code — is a mean of **0.04 ms** a frame for one tube,
**0.07 ms** for two and **0.18 ms** for five (1900 flakes) once V8 has warmed up; a flight lasts
~5.2 s (≈315 frames at 60 Hz) whatever the count. The buffers are 128 KB of matrices and 24 KB of
colours, allocated once. Not measured: Safari on the operator's Mac, and an iPad.
