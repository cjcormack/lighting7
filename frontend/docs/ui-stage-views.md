# Stage views

The Stage view as the UI presents it. `stage-vis-engineering.md` has the drawing. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### Stage views

Two surfaces render live fixture state: the 3D canvas and the **Positions** panel behind the
header's stage toggle (the SVG Plan/Front/Side plot went in stage-view plan session 5). Both read
through a
**`ChannelSource`** rather than
`lightingApi.channels` directly, so the operator can point them at Output / Output + Programmer /
Programmer only — which is what makes Blind previewable. Colour and intensity come from one shared
colour-source dispatch (`components/fixtures/fixtureAppearance.tsx`); the 3D path keeps a separate
imperative copy on purpose.

See [`docs/stage-vis-engineering.md`](stage-vis-engineering.md). Read it before touching a
stage read path, adding a source, or relying on what `ProgrammerState.channels` means — that field
is the backend's channel *sideband*, not the programmer's channel output, and mistaking the two is
the bug that doc exists to prevent.

**The 3D canvas renders on demand** (`frameloop="demand"`), so anything added to the scene that
changes the picture without changing an R3F prop — an imperative material write, an emitter buffer
write, a uniform — must call `invalidate` (`useStageInvalidate` from `stage3d/stageInvalidate.tsx`),
or it shows only when something else happens to draw a frame. The same doc's §"The 3D renderer"
lists what already asks, and covers the other renderer rules: no post-processing — `StageRender`
draws straight to an opaquely cleared canvas and every fragment encodes itself, so beams and pools
add in display space as the prototype's do — DPR at 1.5, context-loss recovery, emitters sized by the rig, regions hanging down from `centerZ` (the top
surface), dark-glass lenses, and the one DOM label layer. **Light lands through one surface shader**
(session 3): every venue, set and region surface loops over a float data texture of the live lights
(`scene/lightTable.ts` — no uniform-array ceiling), capped by a per-machine light budget, and each
fragment is shadowed by the scene's colliders — the boxes beam reach casts at — tested against the
light's own culled list (`scene/occlusion.ts`), capped per machine by *Box shadows*. Since the scrim
plan's session 3 a collider may **transmit**: `segmentTransmit` answers a share — 0 past a solid box,
`open(θ)^gather` through a net (`scene/scrimOpen.ts`), 1 or 0 through a cut cloth's hole or cloth
(its mask in a 32-layer `R8` atlas, `scene/maskAtlas.ts`) — the collider's third texel saying which,
and beam reach skips what transmits, so a beam lands past a gauze; a **landing** passes a cut cloth
whole too (session 5: a transmitting collider is never a landing plane, `isLandingSurface`), while
*Focus here* still takes where the axis stops; the GLSL and its twin change together;
the region, wall and floor cookie instances are gone. The haze governor gives up march steps before frame rate.

**The View button opens a 320 px popover with two tabs** (stage-view menu plan D1–D5, D10, D11;
`stage3d/StageViewMenu.tsx`, the boards in `../docs/plans/completed/stage-view-menu-design/`). **View** is
this window's: Source, Show, Haze, Labels, Work lights, Bodies, Moves in dark. **Performance** is what it costs: a live block (fps and ms
a frame, the lights packed of lit, the haze tier), then the machine's light budget, *Gobos on
surfaces* and *Box shadows* (their `localStorage` keys unchanged), then Test recovery. Every group
says whose it is, *this window* or *this machine*. The button names a source that is not Output
(*View · Next GO*). *Beam cones* is **Light** now, because off unmounts the emitters, which pack the
light table every surface reads; the stored key stays `beamCones`. **Show's toggles and Labels are
per window** (`useStageView` on `createSyncStore`, `sessionStorage stage.viewFlags`, not announced),
and a window with nothing stored seeds once from the old per-browser `localStorage stageViewFlags`,
which is never written. The live block reads a per-canvas **stats store**
(`scene/stageStats.ts`) that the route creates and never subscribes to. The **frame-rate readout**
(`stage3d/FrameRateReadout.tsx`, `scene/frameRate.ts`) is a chip in the canvas's bottom-left corner,
off by default and per window (`sessionStorage stage.frameRate`), and a click opens Performance. It
**never asks for a frame**: the probe writes from inside the frame loop at most four times a second,
the chip paints its own DOM node, and a timer flips it to *idle*. It is hidden while the context is
lost and absent from a `render_view` capture. See the stage-vis doc's §"Frame-rate readout".

**Work lights · Off | On lift the dark** (stage-view menu plan D6–D9, `stage3d/scene/workLights.ts`;
the stage-light plan's *Realistic / Readable*): the room's ambient rises and every surface
material's directional fill gains a lift, reflected off at least a 4 % floor albedo so black serge
shows its folds, while every light keeps the finish's own albedo — pools stay at their exposure.
The housings' own fill rises with the room so the rig still reads on the Plan, and the billboards
follow through `litByFill`. All **uniforms**: `SurfaceLightingProvider` takes the canvas's
`workLights` and writes the shared `uAmbient` / `uLift`, `StageBodies` the housing's `uFill`, and
both `invalidate`, since a uniform write is not an R3F prop — no define, so nothing recompiles.
They are **per window and announced**: `sessionStorage stage.workLights` (default off), riding
`viewOptions` as `workLights` beside `source` (`applyStageViewOptions`, `stageViewOptions`,
`consumeLaunchStageOptions`, a value outside `off | on` ignored), the Screens row's *Work lights ·
Off | On* segment (`STAGE_WORK_LIGHTS_OPTION`), and `?workLights=` on *Copy link*. Every canvas in
the window follows them — the Stage view and the Positions plan. `workLights.ts` is **free of
three.js** (the off row restates `SURFACE_AMBIENT` and `HOUSING_FILL`, pinned by its test), because
`lib/stageViewpoint.ts` and `lib/windowViews.ts` import it on the app shell's path.

**Bodies · Plain | By colour** (tables-colours plan D12, `stage3d/scene/bodyColours.ts`) tints each
housing in its fixture's colour; off by default. It is per window and announced exactly as work
lights are — `sessionStorage stage.bodies`, `viewOptions.bodies`, the Screens row's segment
(`STAGE_BODIES_OPTION`), `?bodies=` on *Copy link* — and every canvas in the window follows it; a
capture draws plain. **A label carries its fixture's swatch either way**, and a selected housing is
still the desk's blue. `StageBodies` repaints a slot's instance colour only when its selection, its
colour or the option resolves to another tint, so an idle frame writes none; the billboard glyphs
take the same through an `aTag` attribute beside `aActive`. See the stage-vis doc's §"Lenses and
housings".

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
(`stage_elements`) are read by the Stage route's canvas — and, drapes and the Set layer only, by
the Positions plan (scenery-programmer plan D16, `sceneSubset`) — and built per kind in
`components/stage3d/scene/builders/` — pure functions a node test pins, one per kind, the seats
exactly `lib/stageSeats.ts`'s. The View menu's Venue · Set · Seating · Haze are per window
(`sessionStorage`), and so is the **vis source** now, riding `windows.viewOptions` as `source` beside
`viewpoint`. One trap, in the doc's §"Cameras and viewpoints": a camera swapped in must be sized by
`useDefaultCamera`.

**Any window can be asked to render a viewpoint for Claude** (`render_view`, stage-view plan
session 4). `StageRenderHost` (`components/stageRender/`, mounted once in `Layout`) takes the desk's
`stageRender.request` — sent to one socket, so it is always this window's — and mounts the lazily
loaded `stage3d/render/StageRenderJob.tsx`, which draws **the Stage view's own `Stage3D`** with a
`capture` prop: a detached canvas at the asked size (`CaptureCanvas.tsx` — `createRoot`, `dpr: 1`,
`frameloop: 'never'`, driven by `advance`, so a hidden tab renders too), the viewpoint through
`resolveViewpoint` (`savedViewpoints.ts`), the default layers, no labels, the request's work
lights (off unless `render_view` asked — the window's own are never read), and the request's vis
source through the same `StageChannelSourceProvider`, which now takes a `source` and reports
`onSettled`. The rig's `oneShot` lands it and records nothing, so the window's viewpoint, camera
poses, landed marker, layers, source and work lights never move, and nothing appears on screen.
The PNG goes back over REST with the request's token; both answers are silent endpoints. The capture root
bridges only `ChannelSourceContext` — a scene component that starts reading another context from
outside the canvas must be bridged there too. See `docs/stage-vis-engineering.md` §"Rendering for
`render_view`".

**Editing on a section is the 3D scene's too** (session 5, D1): with Edit on and the camera on
Plan, Front or Side, `stage3d/edit/SectionEditLayer.tsx` — DOM over the canvas, like the label layer
— owns the pointer and does everything the SVG plot did, in the section's metres: marquee (⇧/⌘ +
drag), click-select, body drags of a selected object (a drag on an unselected one pans) with
alignment guides and the snap grid, drop-onto-a-bar, sliding
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
patch's, else the stock — by `lib/fittedMedia.ts`'s `fittedAttributes`, which `FixtureModel` and
`FixtureAppearanceSource` both run before they dispatch, so every finder in the two dispatches reads
the unit's own. A gel in a filter slot (a media frame in, a dichroic) multiplies the beam on both
dispatches. The controls that *set* a slot — the programmer's setting cell, the fixture sheet's
setting rows (the same cell), the fixture card — still name the type's stock string
(`FU-MEDIA-CONTROL-SWATCHES`).
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
both dispatches (`colourFilters`). *Gobos on surfaces* (per machine, on Performance) can limit the
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
`strobeKind` on a setting's options; `findStrobeAttributes`), and one pure decode,
`lib/strobeBands.ts` (time passed in), turns them into a 0..1 factor **both** colour dispatches
multiply into the dimmer's — the 2D `StrobeGate` around whichever leaf answered, the 3D syncs'
`liveStrobeFactor` (cells take it as their master) — so a closed band is dark on the lens, the beam,
the pool and the markers, and an undeclared value draws open. A strobe flashes at its rate up to 3 Hz,
a random band up to 2 Hz, a pulse swells up to 3 Hz, and anything faster is a shimmer under WCAG's
10% flash threshold (WCAG 2.3.1). The 3D arms ask for frames through the same `colourTicker.ts` while
— and only while — a strobe flashes, and `colourDispatchParity.test.tsx` holds 2D = 3D. See the
stage-vis doc's §"Strobe and closed shutters".

**Heads, wheels and scrollers travel; the view eases toward the DMX** (fixture-optics session 8, D14;
lighting7 `docs/fixtures-engineering.md` §"Travel and timing channels"). A type declares how fast its
pan, tilt, beam and colour move (`FixtureTypeInfo.travel`) and a fixture's own timing channels stretch
it (`SliderAttributeDescriptor.timing`: the Revolution's Focus, Colour and Beam Timing, a **duration**
of one second a step, 0 its own speed). One pure module, `lib/travel.ts` (time passed in), keeps a
displayed value per channel and moves it toward the DMX: the beam director through
`stage3d/beamTravel.ts` (pan/tilt in degrees, every beam channel in DMX, stepped before the dark
returns so a move in the dark has landed), the colour syncs through one axis per channel, so a
scroller in flight reads the unit's **fitted** string at the drawn position and passes through every
frame between. **Drawn, never output** — `travel.test.ts` fails the drawing files on any channel
writer. It **lands, never travels**, on first paint, a vis-source switch, a wholesale replacement of
the source's values (`ChannelSource.epoch`: the wire's snapshot on each connect, a Next GO preview, a
programmer rebuild), a repatch and in a `render_view` capture (`travel={capture == null}`). Only the 3D
view eases: the 2D leaves and Positions chips are readouts of what the desk sends, so
`colourDispatchParity` is unchanged. A move in flight asks for frames (the director's `animating`, the
colour arms' ticker registration) and a settled rig asks for none. See the stage-vis doc's §"Travel
time".

**Scenery moves with the show** (session 8): the desk resolves which state each scene element is in
— a cue's changes tracked from the top of its stack, its stack's set, live Looks above both — and
streams `scenery.state` (`api/sceneryApi.ts`, `store/scenery.ts`'s form-3 `liveScenery`). The Stage
canvas lays each element's interpolated state over its `params.states` before building
(`lib/scenery.ts`'s `sceneryElements`), so the builders draw tabs and trims unchanged and **beam
reach reads closed tabs** for free; `useSceneryClock` invalidates every frame only while a move is in
flight. The vis source picks the scenery too (`StageSceneryContext` from `StageChannelSourceProvider`:
Next GO and Cue in edit draw their previews'). Authoring is one `components/scenery/SceneryEditor.tsx` in the cue
editor's Scenery tab, `CueStackForm` and `LookDetailSheet`, saving the owner's whole list per gesture and
holding its draft over a refetch; since the scenery-programmer plan's session 2 each of its rows is
element select · time · × over a `SceneryControl` (§"The editor kit") in `commit="release"`, so a row
can set a range (`open 40%`, a trim between in and out) and several states at once, and let one go
again — a key the row does not state is drawn muted, and each it does carries an *Unset* ×. **The programmer
holds scenery too** (that plan's D1): `programmer.sceneryState` is bridged in
`api/programmerWsApi.ts` (its own subscription, never `notifyState`) into `store/programmer.ts`'s
form-3 `programmerScenery` (`useProgrammerScenery`), written by `programmerSetScenery` /
`programmerClearScenery` as operator gestures (`sendGesture`; a refusal is `programmer.error` and the
existing toast), and edited on the programmer's Scenery table (§"The programmer's tables").
`scenery.state`'s per-entry `source` and blind `staged` list are parsed into `LiveScenery`, both
optional so an older desk's frame parses as before. **The stage draws Blind** (that plan's D12,
session 4): the Output + Programmer and Programmer sources lay `staged` over the live scenery
(`sceneryForSource`, in `StageChannelSourceProvider`), Output draws live. **And a click moves a
piece** (D11): with Edit off, in every camera, a click on a drawn, flown or Set-layer element —
once nothing of the rig is under the pointer (fixture, then rigging, then the piece; a drag or a
finger pan never counts) — opens `stage3d/SceneryPopover.tsx` through `EditorSurface`: its
`SceneryControl` writing the programmer's scenery at the window's fade (`useSceneryScope`, the
band's own), *Release*, *Edit element…* where Edit exists, and *Moves with* from `GET
stage-elements/{id}/scenery` (`MovesWithList`, which the element form draws too, each entry opening
its owner's editor in place — `stage/OwnerEditor.tsx`; the form also takes `travelS`). The popover
is anchored at a virtual element fed by the label layer's per-frame projection of the piece's centre
and box (`labelStore.track`, `EditorSurface`'s `followAnchor`), so it sits beside the piece and
follows it while orbiting; a
bottom sheet on an upright phone; never in a `render_view` capture. See the stage-vis doc's
§"Scenery that moves with the show" and §"The label layer".

**One-shot effects — the cannons** (session 9; lighting7 `docs/fixtures-engineering.md`
§"@FixtureTrigger", `docs/cues-engineering.md` §"Cue events"). A confetti cannon's tubes are
**triggers**, never values: a `TriggerAttributeDescriptor` (`type: 'trigger'`, `triggersOf` in
`store/fixtures.ts`) at the end of a fixture's attributes, which every control-drawing switch skips
(the fixture sheet's `buildSheetRows`, the programmer's channel map). `api/effectsApi.ts` reads
the three outbound frames — `effects.armed` (form 3 in `store/effects.ts`, its countdown anchored to
this browser's clock from the frame's `remainingMs`), `effects.fired`, `effects.skipped` — and the
REST verbs (arm, fire, reload, a cue's whole-list events) are `store/effects.ts`'s; there is no inbound
frame (lighting7 P2). Four surfaces: the red **`ArmedChip`** on the app header and, while immersive, on
the `ShowHeader` row (`components/effects/`; a tap disarms, nothing is drawn disarmed);
**`EffectsAnnouncer`**, mounted once in `Layout`, toasting every skip on every window; the
**`CannonPanel`**, which the fixture sheet (§The fixture sheet) draws above its rows for a fixture with triggers —
loaded/spent per tube, arm/disarm, **hold-to-fire** (`HoldToFireButton`, 700 ms, a brush fires
nothing), a confirmed reload, and a fire **rehearsed** while the programmer is blind or this window's
vis source is the programmer (`rehearse: true`: drawn on every window, nothing sent); and
**`CueEventsEditor`** in the cue editor's Events tab (with `CueEventsReadout` on the phone and Prompt
Book cards), the session-8
scenery editor's shape — a whole-list save per gesture, its draft held over a refetch. The Stage view
throws **confetti** on every fire (`stage3d/StageConfetti.tsx` over the pure `confetti.ts`): one
instanced mesh pooled per canvas, unlit, invalidating each frame only while a flake flies — see
`docs/stage-vis-engineering.md` §"Confetti". A surface binds it as the catalogue's *Fire*
function, on a fixture; the Cloud tab's Remote access section carries *Allow arming and firing*.

**Fixture commands — resets and lamp control** (fixture-optics session 7; lighting7
`docs/fixtures-engineering.md` §"@FixtureCommand") take the trigger's shape: a
`CommandAttributeDescriptor` (`type: 'command'`, `commandsOf` in `store/fixtures.ts`) at the very end of
a fixture's attributes, skipped by every control-drawing switch and by `useTargetAttributes`' `expand`
(which now drops triggers too — a picker offering either offered only a 400). The fixture sheet draws
**`FixtureCommandsMenu`** (`components/fixtures/`) above the controls: a *Commands* dropdown, each item
its hold, each behind an `AlertDialog` naming the unit and the command with its description, its hold
and what it sets for the hold (`alongside`). Confirmed, it is one REST call
(`useRunFixtureCommandMutation`, `store/commands.ts`) that **answers when the hold ends**, and the
button counts the hold down from the descriptor's `holdMs` meanwhile — only in this window, since there
is no socket frame. Refusals (`COMMAND_BUSY`, `COMMAND_BLIND`, `COMMAND_PARKED`,
`REMOTE_COMMANDS_DISABLED`) toast by the desk's words; a cut-short hold warns. The Cloud tab's Remote access section
carries *Allow fixture commands over remote access* (`allowCommands`), which MCP is held to as well.

**Painted cloths hang flat, and carry their pictures** (scrim plan session 2;
`docs/stage-vis-engineering.md` §"Painted cloths"). Only velour pleats: every other `fabric` builds a
flat `sheet` part, and a drawn half of one folds only as it gathers (`pleat.ts`'s `gatherShape`, flat
at `open` 0, its depth a uniform so a draw rebuilds no material). Canvas and muslin are `MATTE`, the
nets a new `NET` preset, nets and muslin off-white unless `finishColour` says otherwise. A drape's or a
flat's `paint` is the finish's (`PartFinish.paint`) and each painted part carries a `uv` rect — a flat's
pieces their share of its face, a drawn cloth's halves ½ each, compressed as they gather so the image
stays whole — which `partGeometry` writes with an `aPaintFace` attribute (downstage 1, upstage −1,
edges 0). The surface shader's `PAINT` define samples the images as the albedo by face, so every
light and the work lights' lift see them as any finish, and discards where **either** image's alpha
is below ½. **Holes and nets pass light** (session 3): `ScenePart.light` is `'solid' | 'none' |
Transmit` — a net `{kind: 'angle', r, gather}`, a painted cloth or flat `{kind: 'mask', image, uv}`
over its paint rect — so the shadows, beam reach and sight lines pass a gauze and a cut cloth's holes,
through masks from a second module-level cache, `scene/sceneMasks.ts` (`?variant=mask`, keyed by
hash, held by `useSceneMaskHolds`, a mask not loaded or missing counting as solid, its landing a
re-pack and an `invalidate`). **And the eye passes them** (session 4, `scene/seeThrough.ts`): a net
is drawn as a premultiplied blend (`SCRIM`, no depth write, `renderOrder` after the opaque surfaces and
before the beams) covering `1 − open(θ_eye)^gather` of the pixel, θ_eye from the cloth's normal to the
eye — the camera's axis on a section — so a gauze is about half open square on and solid edge-on, its
round threads wrapping a grazing light and glowing from behind; a muslin stays opaque but is lit from
behind (`TRANSLUCENT`) through `τ · paint_front ⊙ paint_back`; a cut cloth stays opaque cloth with
holes. Both are defines chosen when the material is made, the net's gather and τ uniforms. **And the haze
splits at them** (session 5, `scene/hazePlanes.ts`): one list of eight transmitting planes, scrims and
cut cloths, filled every frame nearest the eye first, reaches the march as uniforms; each sample keeps
the eye's share of each plane the view ray crossed before it (a gauze's `open(θ_eye)^gather`, a cut
cloth's mask) and the beam's share of each plane its row names (`aBeamFx.z`, `hazePlanesCrossed`)
between it and the aperture — through the shadows' own crossing, `open(θ)` and mask lookup
(`occlusion.ts`'s `TRANSMIT_GLSL`), never a copy. A ninth passes light on surfaces but does not split
the haze. The textures come from one
**module-level** cache, `scene/paintTextures.ts` — keyed by hash and variant, `?variant=display` or
`detail` for an element with `fullDetail`, mipmapped and anisotropic, loaded once, disposed 5 s after
nothing holds it — never a React context, which a `render_view` capture would not bridge. A part binds
its images as uniforms when they land (`setPaintTextures`, no recompile) and **asks for the frame**;
a missing image settles unpainted, never an error; a Full detail switch keeps the 2048 copy until the
4096 one is in; and `StageRenderJob` awaits `paintTextures.settled()` and `sceneMasks.settled()` before it draws. The element
sheet's Depth hint says it folds only velour (`DRAPE_DEPTH_HINT`). **The estimates were judged and
kept** (session 6) in three cloth scenes, `?profileHarness=scrim`, `=cutcloth` and `=daynight`
(`stage3d/profileHarness.ts`, their paint drawn by `harnessImages.ts` and stored through the desk's own
scene-image route; their lanterns on universe 1's channels 41 front and 42 back, and 43 / 44 for the
gauze's grazing and back light): the nets' thread share — sharkstooth 0.30, bobbinet 0.15 — the
threads' wrap 0.45 and glow 0.18, and muslin's τ 0.45; the desk's confirmation is
`FU-MANUAL-SCRIM-ESTIMATES`. **A seat that sees a gauze as near-solid says so** (D13,
`stage3d/seatScrimHint.ts`): its row in the viewpoint picker and its canvas caption carry *Forest gauze
reads near-solid from F6 (open 12 %)* when a shown net's worst point (its centre and corners, read
through `scrimOpen.ts`'s one `open(θ)^gather`) falls below half its head-on open — nets only, the live
scenery laid over first, so a hidden or flown-out gauze never flags. See the stage-vis doc's §"Saved
views and seats" and §"Painted cloths".

**Positions** (`components/positions/`) replaced `StageOverviewPanel` and `StageMarker`: one row per
rigging, upstage first, the stage edge marked, derived on every render and never stored
(`FU-BUSK-RIG-PLOT`). A chip **sets the desk selection** (`setDeskSelection`, ⇧/⌘ toggles), a group
chip dims the units outside it, and the Plan tab is `Stage3D` on the plan section, lazily imported so
three.js stays out of the app shell — drawing the drapes and the Set layer at the state the
window's vis source has them (scenery-programmer plan D16) and the window's Venue · Set · Haze
layers allow, so a closed tab or a flown piece shows where the light lands. It is mounted on every route, so everything below
`CollapsiblePanel` — queries, channel subscriptions, the canvas — unmounts while it is closed.

**Aim at point** (`components/stage3d/StageAimControls.tsx`) is the Stage view's one live *write*
besides the docked fixture panel it sits in: a stage coordinate, or a region's centre at head
height, sent to `POST /programmer/aim` for the selected moving heads — from the single fixture's
sheet as an *Aim…* popover on its Position row (fixture-fx-sheets session 2; it was a block pinned
under the panel; on a phone, session 6, the same popover over the phone sheet), or its own docked
panel for a multi-selection in view mode (tablet width and up; ⇧-click adds a fixture, ⌘-click
toggles one), and only on the live project.
The desk solves pan and tilt (lighting7 `docs/fixtures-engineering.md` §"Aiming a head at a
point"); this side never does, the `paletteIntent.ts` rule, and draws the answer's skips one line
per reason (`AimSkips`). **A click on the Plan aims too** (venue-visit plan S13): *Aim on the plan*
puts the window on the Plan and arms it, `PlanAimLayer` then owns the pointer over the canvas (a
click is a point in the Plan's metres, a drag pans, Esc stops; the fixture click and the scenery
popover stand down only while armed), the height is head height above the region under the click
(else the deck, or the house floor off the stage) or the typed Z, and the aim panel docks for one
head too until *Done*. Edit disarms it. The Plan is fitted to the drawn room as well as the rig, so
the house is on it. The programmer's position popup offers
the same controls as *Aim…* in its footer (§The editor kit). See `docs/stage-vis-engineering.md`
§"Aiming on the Plan".

**Focus here** (fixture-optics plan session 1) is its sibling on the Focus tab (`StageFocusPanel`):
for a DMX head whose focus declares a range, on the live project, it sends where the beam lands in
the view (`stage3d/landedPoints.ts`, recorded by the beam director for the selected fixture on an
on-screen canvas only) to `POST /programmer/focus`, and the desk solves the focus channel for that
distance. The blur that makes the result visible is a relative focus error times a per-type depth of
field (`beamMask.ts`'s `focusBlur`; the GLSL and its twin change together), packed into the light
table's texel 0 beside the focal distance. See `docs/stage-vis-engineering.md` §"Focus".
