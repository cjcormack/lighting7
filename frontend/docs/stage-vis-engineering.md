# Stage visualisation: sources, appearance and the renderer

How the stage surfaces decide **what a fixture looks like**. Two independent seams:

- a **channel source** — which layer of the lighting cascade the numbers come from;
- a **fixture appearance** — how a fixture's colour source turns those numbers into a colour and a
  level.

And, since the stage-view plan's session 0, how the 3D view keeps within a browser's memory and
survives losing its graphics context — §"The 3D renderer"; since session 1, its cameras, the
window's viewpoint and the Positions panel — §"Cameras and viewpoints"; since session 4, how a
window draws a viewpoint offscreen for Claude — §"Rendering for `render_view`"; and since session 5,
editing on the Plan, Front and Side sections, which retired the SVG plot — §"Editing on the
sections" at the end.

## The vis source

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
- **Imperative buffer writes from effects** — `hideSlot` / `hideWashSlot` when a fixture loses its
  beam or unmounts — and the region uniforms (below). The light table is packed in the emitters'
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
altogether — §"Light lands through one surface shader" below.) Now `emitterNeedsFor` (`emitterNeeds.ts`)
says per slot what it can draw — **no lobes** without a beam (`acceptsBeamAngle`, which is
`FixtureModel`'s `showCone`), **one**, or **six** where there is a prism; a wash block only for a
pixel strip, of its pixel count — and `buildEmitterLayout` (`emitterLayout.ts`) turns that into
per-slot offsets. On project 15 that is 73 beam instances where there were several hundred.

- **One statement of the rule.** `FixtureModel` decides whether it washes per pixel with the same
  `isPixelStrip` and `pixelCountOf` the layout sizes by; two copies would drift, and a fixture that
  drew more than its slot was given would write into the next fixture's block.
- **The handle drops out-of-block writes.** Every writer bounds-checks `(slot, lobe)` and
  `(slot, pixel)` against the layout, and the directors draw at most `lobesFor(slot)` lobes and
  `washPixelsFor(slot)` pixels. A frame where the patch and the layout disagree draws less, never
  into a neighbour.
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
  48-light ceiling was the uniform budget; a float `DataTexture` of four texels a light
  (`MAX_LIGHT_BUDGET` = 256 rows, read with `texelFetch` in a GLSL3 loop over `uLightCount`) has
  none. Every beam lobe and every washing pixel has a slot in the `LightTable`, sized from the
  emitter layout; the directors write their slot each frame (`writeLight`, `writeWashLight`,
  `clearWashLight`), and the emitters' flush packs the **budget**'s worth of the brightest lit slots
  into the texture, in slot order.
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
  disagree with the uniform cone above it. The lit colour is the finish × (fill + an exponential
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
through fewer march steps is grainier, not wrong — so `HazeGovernorProbe` (in `Stage3D`) feeds
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
its full hue and bloomed. `paintLens` (`fixtureBodies/palette.ts`) makes a lens dark glass at level 0
and the hue at level 1, opaque, never brighter than its level — every body and each `PixelStrip`
head go through it. Housings and yokes are matt near-black, as lanterns read in a hall; the active
highlight lifts them to a slate. The torus ring still marks the selection.

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
