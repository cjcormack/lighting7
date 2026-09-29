# The stage view — design record

Source: Chris, 2026-09-29, with two screenshots of project 15 (*The Commemoration Hall*, 55 patched,
45 on stage) and a 5-second phone video from the back of the hall during a performance. The
brief had three parts:

1. The 3D view feels clunky and could model the real environment far better, with Claude's help
   over MCP, rather than only placing things from patch data.
2. It should be possible to see the stage from other vantage points, such as a seat in the
   auditorium.
3. The 2D view does not work at this scale. Perhaps nothing does, and it should go.

Later the same day Chris added two more:

4. Better fixture bodies, built per fixture type, with more options for generic dimmers. The
   ball-shaped light sources are distracting and stay lit at zero (§"Fixture bodies and the
   lantern library").
5. Props, scenery, tabs and curtains that can be placed, ideally assigned to a Look, template, cue
   or cue stack (§"Scenery you can place, and scenery that moves with the show").

**Status: approved, 2026-09-29.** Chris confirmed all sixteen decisions (§"Decisions") as
recommended. The implementation plan is [`../stage-view-plan.md`](../stage-view-plan.md). The
research, the findings on today's code and a working prototype are in this folder. Where this
record and the prototype disagree, the record wins; the prototype is there to be looked at, not
copied.

| File | What it is |
| --- | --- |
| `INDEX.md` | This record. |
| `prototype.html` | A standalone three.js prototype of project 15. The rig is the live patch; the venue and set are the proposed scene document, estimated from the video. Open it in a browser. There is a live copy at <https://claude.ai/artifact/MYfoTJJheASwdNemFeedF7>, private to Chris; the file here is the authority. |
| `art-book.html` | The art book: the whole proposal in 30 numbered plates, for reading rather than deciding. There is a live copy at <https://claude.ai/artifact/1QJzfob6sy9fGigbdrm2cw>, also private to Chris. |
| `Stage.dc.html` · `Positions.dc.html` · `Edit.dc.html` · `Patch.dc.html` · `Cue.dc.html` · `StacksLooks.dc.html` · `Screens.dc.html` · `Model.dc.html` | The proposal drawn into the desk as it is today, one artboard each: the Stage view (viewpoint picker, cameras, Focus tab), Positions replacing the overview panel, Edit mode's + Scenery and element form, the Edit Fixture sheet's Lantern box and mounts, a cue's Scenery and Events, a stack's set with a Look's scenery and the cannon's panel, a hall screen on a viewpoint, and the model (stored, wire, code, sessions). Same format and stylesheet as the busk-chrome boards; `canvas.json` is the layout. A live copy of all eight on one page is at <https://claude.ai/artifact/UfcvSEzjFDEe8vs1JgpAys>, private to Chris. Where a board and this record disagree, this record wins on behaviour and the boards on layout and copy. |
| `plates/` | The book's images. The renders were captured from the prototype at 1600×1000; the `before-*` crops are from Chris's screenshots of the desk on 2026-09-29. |
| `foh-wide.jpg`, `foh-zoom.jpg` | Two frames from the video, taken from the balcony at the back of the hall. They are the reference for the venue estimates. |

## What the video shows that the desk cannot say

The frames are of a flat-floored hall. It has:

- panelled side walls, pillars, a suspended tile ceiling and green exit signs;
- a proscenium opening about 5 × 3 m, with a black surround and wood-panelled returns;
- a raised stage with a grey step unit and two plants in front of it;
- a box set: two flats with gothic windows, a French window onto a green garden backing, red
  curtains, a door, a fireplace, a sofa and a standard lamp.

The advance bars hang in the hall just in front of the proscenium. The LX bars are visible above
the set.

The desk can store four things: a stage box on the project, rectangular regions, straight
riggings and fixture placements. The backend has no table, field or tool for walls, masking,
seating, a balcony, a proscenium, set pieces, surfaces or materials. `describe_rig` returns no
geometry at all.

Project 15 shows how that gap gets worked around today:

- **Auditorium** is a region 8.6 × 18.4 × 0.1 m, 0.95 m below the deck, standing in for the hall
  floor. The HUD in the first screenshot shows it.
- **Proscenium** is a rigging of kind `OTHER`, carrying the LED ring.
- **FOH Balcony** is a pipe at y = −17.3 m. That is roughly where the video was shot from.
- The stage box is 8.6 × 11 × 4 m, so the whole auditorium sits outside the wireframe envelope.

## What is wrong today

The frontend has about 8,000 lines in `stage3d/`, 3,000 in `stage2d/` and 700 in `stage/`, plus
about 2,000 lines of shared maths in `lib/`. The findings below are in order of how much each
one hurts.

### 1. Memory, and a blank canvas when the context goes

Safari's "reloaded because it was using significant memory" banner is in the first screenshot.
The likely causes are these, in order:

- **`EffectComposer` defaults to 8× MSAA half-float targets.** `Bloom.tsx` never overrides that
  (`@react-three/postprocessing`'s `multisampling = 8`, `frameBufferType = HalfFloatType`). It sits
  on a canvas with `dpr={[1, 2]}` and `antialias: true` (`Stage3D.tsx:271-273`), which adds a
  second MSAA default framebuffer. At a Retina full-window size, the composer's colour target
  alone is hundreds of MB.
- **Instance buffers are sized for the worst case and drawn every frame.** The region-cookie mesh
  is fixtures × 6 lobes × regions (`StageEmitters.tsx:794`). The wash-region mesh is fixtures × 16
  pixels × regions (`StageEmitters.tsx:872`) — that is 11,520 instances at 45 fixtures and 16
  regions, even on a rig with no pixel strips. Almost all of them are invisible.
- **A region drag rebuilds every emitter buffer on every frame.** The drag writes the RTK cache per
  frame, and `regionGeometry` is memoised on that array.
- **Every label is its own React root with a `backdrop-blur`.** Each one is a drei `<Html>`
  (`StageLabel.tsx:10-17`), and there are 60 or more over a WebGL canvas. The overview panel adds
  45 markers with `filter: blur(6px)` (`StageMarker.tsx:157`).
- **The canvas renders continuously.** `frameloop` is left at its `always` default.

**Nothing handles `webglcontextlost`.** When WebKit takes the context back, the view goes blank
and only the HTML labels remain. I reproduced this in the desktop app's browser pane, which is
WebKit. That pane also dropped the prototype's much smaller context while hidden, so the
reproduction shows the *missing recovery*, not memory pressure on its own. The Safari banner and
the list above are the evidence for memory pressure.

### 2. Regions are drawn upside down in Z

- **Backend.** The model (`models/stageRegions.kt:16`), the MCP schema (`SetupToolSchemas.kt:187`)
  and the aim tool (`StageAimControls.tsx`'s `regionAimPoint`) all treat `centerZ` as the
  platform's **top** surface.
- **3D view.** It lifts the box upward from `centerZ` (`StageRegionMeshes.tsx:116-118`).
- **Result.** "Main stage" (top at 0, 0.95 m thick) is drawn as a slab standing 0.95 m proud of the
  deck, which is the raised block in the screenshot. Beam pools land on the wrong surface.

This is a one-line fix and should not wait for this plan.

### 3. No vocabulary for the room

- Regions are translucent boxes coloured by a hash of their uuid.
- Rigging `kind` has no effect on drawing: every kind is a 0.18 m square bar.
- The floor, back wall and envelope are hard-coded from the project's stage box.
- There are no imported meshes, materials or side walls, and no masking, house, seating or floor
  finish.

### 4. One camera, which forgets

- The view has one perspective camera, which resets every time the canvas mounts.
- There is no saved, named or seat view anywhere.
- 3D / Plan / Front / Side is not a camera choice. The last three unmount the WebGL scene and
  mount an SVG renderer, `Stage2DView`, in its place.

### 5. Both 2D surfaces lose to label density

- **`StageOverviewPanel`** is the slide-down in the second screenshot, mounted from
  `Layout.tsx:272` on every route. It maps the stage plan to percentages of a fixed 420 px box. It
  draws every fixture's name, placement label and rigging badge with no decluttering.
- **`Stage2DView`** hides overlapping fixture labels greedily. It does not account for rigging
  labels, which it never declutters, and draws no region labels at all.
- **The real limit is geometry.** In plan, LX1, LX2 and LX3 carry 7–8 units each on a 6 m bar,
  within a metre of each other in y. At any zoom that shows the whole hall, those units are a few
  pixels apart. No labelling scheme fixes a true-scale plan of this rig.

## What other desks do

These are from vendor manuals. The research agent's full sourced report is summarised here.

- **Venue.**
  - grandMA3 builds rooms from flat "stage elements" that light can hit.
  - Capture and MagicVis ship parametric stage, truss, drape and wall objects.
  - Vectorworks has parametric decks, soft goods and seating layouts.
  - QLC+ 5 ships preset environments, including a Theatre preset.
  - ETC's Augment3d Scanner is an iPhone LiDAR app with a proscenium tool, which exports GLB.
  - USITT RP-2's list of what a plot must show is effectively the minimum primitive set for a
    small theatre: deck and edge, proscenium, masking, back wall, battens, FOH positions and
    seating.
- **Cameras.**
  - Everyone has turntable orbit, orthographic plan / front / side snaps and named saved views.
  - grandMA3's camera pool has a locked **Auto** camera that frames the selection.
  - Vectorworks Vision recalls up to nine saved views over DMX.
  - I found no product documenting a literal "view from seat"; a saved perspective camera at the
    seat does that job.
- **Rendering.**
  - Every product has quality tiers.
  - Capture's performance guide: cost scales with *apertures* (beam sources), not fixtures, and
    smoke is the single biggest cost.
  - In three.js a gobo `SpotLight` needs a shadow map each, so real shadowed lights are limited to
    a handful.
  - Recurring context loss on Safari and iOS is a known three.js issue.
- **2D.**
  - No console documented here keeps a true-scale plan as its operating surface.
  - Eos magic sheets, ONYX's 2D plan and MA layouts are operator-arranged schematics, often seeded
    from 3D positions. Augment3d can generate a magic sheet from channel locations.
- **Interchange.** MVR is a zip of `GeneralSceneDescription.xml` plus GDTF and GLB/3DS geometry,
  in millimetres and Z-up. It is the format Vectorworks, Capture, Depence and Augment3d exchange.
  It is a sensible later import, but venue meshes arrive as unlabelled blobs.

## The proposal

Five parts. The first is independent and should ship regardless.

### 1. Fix the renderer first

This part is frontend only, with no model change:

- **Composer and pixel ratio.** Drop the composer's MSAA (`multisampling={0}`) or the composer
  itself, and cap DPR at 1.5.
- **Context loss.** Handle `webglcontextlost` with a paused state and a *Restore* action, as the
  prototype does.
- **Instance buffers.** Size them by what the rig actually has: prism fixtures × lobes, and strip
  pixels × strips.
- **Drags.** Push region geometry through uniforms, so a drag no longer rebuilds buffers.
- **Labels.** Replace the per-label React roots with one label layer that is decluttered in screen
  space.
- **Regions.** Fix the `centerZ` direction.
- **Fonts.** Bundle drei `Text`'s font, which today comes from jsdelivr — a problem on an offline
  desk.

### 2. A scene document Claude can write

Add a portable table, `stage_elements`. Each element is named, unique per project, and has:

- `kind` and `layer` (`VENUE` or `SET`);
- a pose: x, y, z and yaw;
- a size: width, depth and height;
- a `finish`: colour, a pattern (`plain | panels | tiles | boards`) and emissive;
- `params`, a JSON object validated per kind in Kotlin, like the sealed DTOs elsewhere;
- `hidden` and `sortOrder`.

Z is the element's base, except for `platform`, where it is the top surface, as for a region. The
kinds are the RP-2 minimum and nothing more:

| Kind | Params | Draws |
| --- | --- | --- |
| `room` | `omit[]`, `floor`, `ceiling` finishes | An inward-facing shell. Seen from outside, its near walls vanish (the dollhouse orbit in the prototype); in a section they cut away. |
| `proscenium` | opening width and height, sill, surround | A wall with an arch and a black surround. |
| `flat` | `openings[]` of `{kind, fromM, widthM, heightM, sillM}` | A panel with doors and windows cut in. |
| `drape` | `role`: leg, border, tab, cyc or backcloth | Soft goods. |
| `platform` | `railHeightM`, `railEdge`, optional `region` link | A deck, rostrum or balcony. |
| `seating` | rows, seats per row, row and seat pitch, first row letter, rake | Instanced seats, and the **seat list** that viewpoints use. |
| `object` | `shape`: box, cylinder or shade | Furniture, plants, fireplaces, exit signs. |

`mesh` (GLB import) comes later and needs blob storage like the prompt-book PDFs.

The `VENUE` / `SET` split is how the hall outlives a production. Toggling `SET` off shows the empty
room, and cloning a project copies both layers through the existing export-and-import path.

**Regions stay as they are.** They mean something specific — the playing surface, aim targets and
beam receivers — and a `platform` can point at one instead of duplicating it.

**Sync.** This is a new table, so it needs:
- a `formatVersion` bump to 18 and `stageElements/{uuid}.json` in the export;
- a `SyncCoverageTest` row;
- `RichProjectFixture` values;
- the round-trip test.

**Claude's part.** Three MCP tools:

| Tool | What it does |
| --- | --- |
| `set_scene` | Upserts elements by name, with all-or-nothing validation and the same shape as `set_stage`. It also accepts `template: "proscenium-hall"` plus about a dozen numbers (hall size, stage size, pros opening, rows), which expand server-side into named elements the operator can then edit. |
| `get_scene` | Reads the document back. |
| `describe_rig` | Gains a short stage summary; today it has none. |

The research is clear that an LLM filling in *parameters of a template* works far better than
free-form geometry. The prototype's scene is exactly what such a call would produce.

**Claude seeing its own work.** A `render_view {viewpoint}` tool returns a PNG, so Claude can
compare the model with the operator's photo and correct it. The backend cannot render WebGL, but
the desk already has a registry of signed-in windows (`windows.*`). One of those windows can
render the requested viewpoint offscreen and upload the frame. If no window is open, the tool
says so.

### 3. Viewpoints

- **Storage.** Add a portable table, `stage_viewpoints`: name, kind, eye, target and FOV, or a seat
  reference (`seat: "F6"`).
- **Built-ins.** Orbit, Plan, Front, Side, and **Frame selection**, after grandMA3's Auto camera.
- **Seats.** Seat views come from `seating` elements. **Sit in a seat…** lets you click any seat.
- **Controls.** Eye and seat views use *look-around* controls (drag turns your head, scroll zooms)
  rather than orbit. You are sitting somewhere, not circling it.
- **Per-window.** The current viewpoint rides the window's `viewOptions`, so a second screen can sit
  on "Balcony · desk" all night. The orbit pose is remembered per window.
- **Example viewpoints.** The prototype has Balcony · desk (the video's angle), Row F centre,
  Row A seat 1, and Centre stage — an actor looking out, which is the view for checking what
  blinds whom.

### 4. 2D: keep the plan as a camera, replace the overview with Positions

- **Plan, Front and Side become orthographic cameras on the same 3D scene.** Each cuts away what
  sits between the camera and the section plane: ceilings in plan, the balcony in front, the SL
  wall in side. One renderer, one set of labels, beams and pools included.
- **`StageOverviewPanel` becomes Positions.**
  - One row per rigging position, upstage first, with the stage edge marked.
  - Units in rig order as seen from the desk, each with live colour and level. Paired lanterns show
    as one chip, "×2".
  - It is derived from rigging and x, never stored. That is the rule `FU-BUSK-RIG-PLOT` already
    set for plot arrangements.
  - It reads at 45 fixtures, and on a phone.
- **`Stage2DView` stays for Edit until 3D ortho editing has parity**, then goes: 3,000 lines. Parity
  means marquee, snap-to-rigging, alignment guides, the unplaced tray and nudging. This is D1.
- **`MiniStage` on cue cards stays.** It has a different job: a thumbnail of a cue's targets.

### 5. Light that lands on things

The desk's beams (cones, raymarched gobo volumes, prisms) carry over as they are. What changes is
how surfaces receive light:

- **Today.** Pools are instanced "cookie" boxes per beam × region, capped at 16 region receivers.
- **Proposed.** Every venue and set surface uses one shader that loops over the live lights
  (position, direction, cone, colour × level, and axial reach), up to 64.
- **Cost.** Pixels × lights. It lights flats, the sofa, seats, walls and the pros panels with no
  cap on receivers, and it replaces the two instance buffers above that grow fastest.
- **Occlusion.** Beam reach stops at the first surface on the beam's axis, which is crude
  occlusion.
- **Quality tier.** Adds shadow maps for up to four selected heads, and gobo projection by sampling
  the existing gobo atlas in light space.
- **Haze.** Should follow the hazer's DMX, instead of `washConfig.ts`'s constant.

House lights and practicals are fixtures, so the room responds to them: the prototype's Walk-in
look lights the stalls from `house-1..3`.

## Fixture bodies and the lantern library

Added 2026-09-29, from Chris's second brief. The fixtures should look better, be built per
fixture type, and generic dimmers should get more options. The ball-shaped light sources are
distracting, especially because they stay lit with the dimmer at zero.

### Today

- **Bodies are chosen by kind only.** There are eight body components (`stage3d/fixtureBodies/`,
  642 lines), dispatched on `FixtureKind` alone (`index.tsx`). So a MAC 250 and a Robe 575 are the
  same shape, and every generic dimmer is one of four shapes.
- **Every body ends in a sphere lens.** It is a `MeshBasicMaterial` in `#fff8d5`
  (`ProfileBody.tsx:39-42` and the others). `applyColour` sets its opacity to
  `0.5 + 0.5 × perceptualBrightness(intensity)` (`FixtureModel.tsx:993-1004`). At dimmer zero it is
  therefore half-opaque in the full hue, and bloom (threshold 0.15) makes it glow. Those are the
  balls.
- **Housings are light grey** (`#7a8390`, and `#cfd6df` when active), so the rig reads as bright
  clutter above a dark stage. In the video the lanterns are black silhouettes.
- **The backend knows a kind and some defaults, nothing more.** `KindBeamDefaults` gives default
  dimensions and beam shape (`BeamGeometry.kt:50-62`). A generic dimmer is one class,
  `GenericDimmerFixture`, with `kindOverride`, `beamAngleDeg` and `gelCode` on the patch. It has:
  - no lantern model;
  - no beam angle separate from field angle;
  - no zoom;
  - no oval beam.
- **Extra placements carry no lantern of their own.** They hold only a pose and a length, so a
  profile and a fresnel on one dimmer cannot be told apart.

### What the research found

- **PAR beams are ovals of about 2:1**, set by rotating the lamp in the can. For PAR 64 (beam ×
  field):
  - CP60: 6×12 / 10×24
  - CP61: 7×14 / 14×26
  - CP62: 12×28 / 21×44
  - WFL: 24×48 / 45×71

  (Altman datasheet.) GDTF cannot express an oval beam (`RectangleRatio` applies only to
  `Rectangle`).
- **GDTF models a body as a tree of named geometry** — base, yoke, head and a `Beam` node at the
  lens — with `Model` sizes in metres, a primitive fallback (`Base`, `Yoke`, `Head`,
  `Conventional`, …) and optional GLB. That is the right shape to mirror so a GDTF or MVR import
  can fill it later.
- **GDTF-Share's meshes are not redistributable.** The Open Fixture Library (MIT) has dimensions
  and lens ranges for LED fixtures but no tungsten conventionals. The lantern catalogue has to be
  ours; Theatrecrafts' scanned Strand, CCT and Selecon datasheets are the best source for UK stock.
- **Visualisers agree on how bodies should look:**
  - housings matt black;
  - the lens dark glass when off, emissive only in proportion to level;
  - the gel visible in the colour frame;
  - three levels of detail, down to a billboard;
  - instanced geometry per type.

### Proposal

1. **Lenses are glass, not lamps.** A flat disc, recessed in the barrel. It is dark at zero, and
   the gel or mixed colour × level on a perceptual curve above zero. Bloom applies only to lenses
   that are lit. A generic's gel shows faintly in its colour frame when the lantern is off, as a
   real one does.
2. **Housings are matt black and lit like any surface**, with a small fill so the rig reads
   against the dark. Selection tints the housing instead of lighting the lens.
3. **About ten parametric archetypes replace the eight kind bodies:**
   - profile barrel;
   - box profile (Cantata or Prelude);
   - fresnel / PC;
   - PAR can;
   - flood / cyc cells;
   - downlight;
   - mover (spot, wash or profile head);
   - batten;
   - blinder;
   - effect.

   Each is made of parts — base, yoke, head, lens, accessories (shutters, barn doors, colour frame,
   top hat, scroller) — plus a hanger to the bar. **Static lanterns use the same pan-yoke / tilt-head
   rig as movers**, which is exactly the desk's yaw/pitch convention, so `FixtureAim` does not
   change.
4. **A lantern library** ships with the desk as a resource, like the `.fx.kts` effects. It is
   seeded with about 25 entries from the research table. Each entry holds:
   - maker, family and watts;
   - beam and field angles, or a zoom range;
   - an oval `{x, y}` where the lamp has one;
   - lens diameter, frame size and dimensions;
   - its archetype and accessories.

   Its current entries are in the prototype's Fixtures tab.
5. **Generic dimmers choose a lantern.**
   - The patch gains `lanternType`, `zoomDeg` (within the lantern's range), `lampRotationDeg` for
     PARs, and accessory flags such as barn doors fitted.
   - **Each extra placement gains the same fields**, so a pair on one dimmer can be a profile and a
     fresnel.
   - `kindOverride` becomes derived from the lantern's family; `beamAngleDeg` stays as an explicit
     override.
   - These are portable fields on `FixturePatchJson` and `PatchPlacementJson`, so the sync format
     needs a bump.
6. **DMX fixture types carry a body.** `@FixtureType` gains a `body` descriptor mirroring GDTF's
   Model: archetype or primitive, L/W/H, lens diameter and head type. When it is absent, the body
   defaults from the kind as today. A real GLB per type can come later through the same slot.
7. **Oval beams are rendered as ovals.** The cone mesh is scaled per axis and turned by
   `lampRotationDeg`, and the receiver shader tests an elliptical cone in the light's frame.
8. **A beam leaves its aperture, and its apex falls where the geometry puts it.** Chris,
   2026-09-29, in two steps. First, "the cone should be where the bulb is, not the edge of the
   lens"; then, after the multi-head plate, "some look more like washes, so a cone doesn't make
   sense".
   - **Today.** The desk starts every cone as a point at the lens mesh (`FixtureModel.tsx:755-760`:
     `lensRef ?? head ?? group`).
   - **Proposed.** A beam leaves the **aperture** — the lens, or an LED cell's face — at the
     aperture's own size and shape, and spreads at the field angle. A round aperture throws a cone
     frustum; a segment throws a rectangular one. Its apex sits `aperture radius / tan(field / 2)`
     behind the lens.
     - **For a lantern that is the lamp.** About 0.5 m behind a 19° Source Four's 170 mm lens, so
       the first rule holds with no per-shape lamp constants.
     - **For a wide LED face it is far behind,** so a blinder cell, a wash head or a batten segment
       leaves as a soft column. That is what a wash looks like.

     The light on surfaces uses the same apex for its cone test and the aperture for its distance
     fall-off.
   - **Edge softness is data.** It comes from the family, taking GDTF's split:
     - profiles and spots are hard;
     - fresnels, PCs, PARs, floods, LED washes and battens are soft.

     A conventional's focus knob moves it (item 10). A DMX focus channel already does
     (`beamOptics.ts:264-295`).
   - **Haze is raymarched through the volume.** A hollow additive shell cannot show a flat shutter
     cut, so the prototype marches 20 steps through each beam. The desk already raymarches its gobo
     beams (`beamShaders.ts`).
   - **Aiming is unchanged.** `FixtureAim` solves from the placement point, the yoke's pivot.
9. **Multi-head fixtures keep what the desk already does, and extend it.** Chris, 2026-09-29, after
   the first prototype drew them as one fixture with one beam.
   - **Today.**
     - A multi-element fixture's elements get their own colour (`findGroupColourSource` over
       `elementGroupProperties`).
     - Only the `STRIP` kind draws them apart (`FixtureModel.tsx:220`): `PixelStrip` gives each pixel
       a lens and a soft glow, and `useWashDirector` throws a pool per pixel.
     - The 2-cell blinder is `BLINDER`, so it is drawn as one lamp.
     - `PixelStrip`'s lenses idle at half opacity too.
     - Nothing draws independently tilting heads, such as the Slender Beam Bar Quad's four.
   - **Proposed.** The body declares its cells: count, layout (a row or a grid) and lens shape (disc
     or segment). It also says whether the cells are the type's elements. The cell list replaces the
     kind gate. Each cell has:
     - its own lens;
     - its own colour and level (the element's colour × its dimmer × the master);
     - its own beam from its own lamp point;
     - its own light on surfaces.

     Cells without elements share the fixture's colour, as the Twin Shot's two pinspots do. Heads
     that move on their own get their own tilt node, driven by their element's tilt. That last part
     is later.
   - **Budget.** Haze cones stay one per cell, which is cheap once instanced. Lights on surfaces are
     capped at four per fixture, averaging runs of cells, so a 12-pixel bar does not take 12 of the
     shader's 64 lights.
   - **A correction.** The first prototype gave the Liteobar 252 twelve lenses. The type declares
     three elements (`KamLiteobar252Fixture.kt:117`), and the prototype now draws three segments.
10. **Shutters, barn doors, iris, focus and zoom.** Chris, 2026-09-29: shutters to shape the light
    with straight edges, and "do we have the ability to set the focus and zoom on fixtures that
    support it?"
    - **Today.**
      - DMX zoom is rendered (`dmxToDegrees` on the zoom channel, `FixtureModel.tsx:709`). So is DMX
        focus, as a focal distance plus edge softness (`beamOptics.ts:264-295`). That covers the
        Source Four Revolution, Robe 575, MAC 250 and Shehds.
      - `IRIS` and `FROST` are property categories (`FixtureProperty.kt:52-53`), and the Revolution
        has an iris channel (`Source4RevolutionFixture.kt:172-173`). Neither is drawn.
      - There are no shutters or barn doors anywhere, backend or frontend.
      - Conventionals have no zoom or focus at all, because on a real lantern they are knobs, not
        channels.
    - **A frame, shared by pool and haze.** A beam's cross-section is its field circle, cut by:
      - four blades, each with a depth (0 = out, to past the centre) and an angle (±30°);
      - a gate rotation;
      - an iris;
      - an edge softness.

      One function, `beamMask`, applies it to the pool on every surface and to the haze. A shutter
      therefore cuts both along the same straight line. The beam's frame is the head's own axes, so
      the cut turns with the lantern.
    - **Blades are named for the edge of the light they cut** — top, bottom, left, right — not for
      their place in the gate, where a profile's image is upside down. Nobody thinks "top shutter
      to cut the bottom of the beam" at a focus call.
    - **Focus data is physical, so it lives on the placement.** A patch and each extra placement
      gain `shutters: [{depth, angleDeg} ×4]`, `gateRotationDeg`, `iris`, `focus` (sharp 0 → soft 1)
      and `zoomDeg` (within the lantern's range, item 5). A barn-door lantern uses the same four
      slots, as barn doors.
      - These are portable fields on the same sync bump as the lantern type.
      - A pair on one dimmer is two lanterns, focused separately.
      - Looks never carry them: nobody refocuses a profile between cues.
    - **DMX drives the same frame where a fixture has the channels.**
      - Iris becomes drawn. The prototype's Night look closes the Revolutions' irises to 42%.
      - A fixture with framing-shutter channels (four blades plus rotation) would map them onto the
        blades.
      - Frost maps onto edge softness.
    - **A focus card.** Select a lantern and press Focus:
      - a live cross-section of the gate;
      - depth and angle per blade;
      - rotation, iris, sharp ↔ soft;
      - zoom where the lantern has a range;
      - a switch between the lanterns of a pair.

      For a DMX fixture it says which of these its channels drive. A later step is dragging the
      blades on the pool itself, as Capture's Focus mode does.
    - **Budget.** The surface shader's per-light data grows by three vectors (the frame's right
      axis, blade depths and blade angles) and one small vector for apex, iris and softness. The
      prototype drops to 48 lights to stay inside WebGL's uniform limits. The production path is a
      data texture of lights, which lifts both limits.
11. **Mounts: hung or standing.** Chris, 2026-09-29: the balcony units stand on the balcony; they
    do not hang off a bar.
    - **Today.** Rigging `kind` has no effect on drawing, so every body hangs. Project 15 has the
      balcony as a `PIPE` at 2.8 m, with the two S4 Revs at `basePitchDeg 180`.
    - **That matters beyond looks.** `FixtureAim` solves a hung mover (`basePitchDeg 180`) and a
      standing one (`0`) differently, so the desk would aim those two wrong.
    - **Proposed.** The rigging's kind decides the mount:
      - `BAR`, `PIPE` and `TRUSS` are hung from: a hanger or clamp runs up to the bar, and the base
        is above.
      - `FLOOR_STAND` and a new `LEDGE` are stood on: the base is down, the yoke up, there is no
        hanger, and a mover's rest beam points up.
      - `BOOM` clamps from the side, which comes later.

      A standing body is the hung one turned over, and every aim goes through the same turn. A
      placement can also stand on a scene element — the balcony platform's front ledge — rather
      than on a rigging.
    - **The data fix for project 15 is Chris's to make on the desk:**
      - make FOH Balcony a `LEDGE` at the ledge height, about 1.9 m above the deck, at y ≈ −16.2 m;
      - set the two S4 Revs to `basePitchDeg 0`.

      The prototype assumes the front ledge. If they stand on the balcony floor behind the rail,
      that is a height change and nothing else.
12. **Instancing per archetype part.** Draw calls scale with the number of archetype parts
   (about 40), not with fixtures. Far away a body drops to a simplified mesh, then to a billboard
   glyph.

### In the prototype

- Every body is now built from these archetypes. Lenses are dark until the dimmer rises; hangers
  run up to the bar.
- Select any generic dimmer to change its lantern from the library. The body and beam rebuild
  live.
- The Fixtures tab lists the library and the DMX bodies in this rig. Generic dimmers default by
  kind: profile → Source Four 19°, fresnel → Cantata F, PAR → Par 64 CP62, generic → house
  downlight. The real stock is Chris's to choose.
- Oval beams, LOD, gobos and the gel in the frame are not drawn.

## Scenery you can place, and scenery that moves with the show

Also added 2026-09-29. Chris asked for props, scenery, tabs and curtains that can be placed, and
ideally assigned to a Look, a template, a cue or a cue stack.

### Placing it

Placing is the scene document from §"A scene document Claude can write", with a palette in Edit
mode (flat, drape, tabs, platform, steps, object, flown piece) and the same gizmos regions use
today. Two kinds need more than the table above:

- **Tabs.** A `drape` with `role: tabs` and `operation: draw | fly`. It has one state, `open`
  (0–1). Drawn as two pleated halves, it gathers to the sides as it opens.
- **Flown pieces.** Any element with `flies: true` gets a `trimM` state between its in and out
  trims.

Every element also has the universal state `visible`. A kind declares its states and their
ranges in `params.states`; the element's own values are its **base**, what you see when nothing
has moved it.

### Why scenery should not be a virtual fixture

The first idea was to make a tab a fixture with an `open` slider, so Looks, cues, crossfades,
busk pads and MIDI would all work for free. The composition research rules it out:

- **Everything below the resolver is a DMX channel write.** `PropertyChannelWriter` handles
  sliders, colours and settings and nothing else (`fx/PropertyChannelWriter.kt:56-101`).
- **It needs real channels.** A scenery fixture would need a universe, channels and no overlap.
  An ArtNet universe would transmit it. The `MOCK` controller has no fades and never sends live
  channel deltas. It also keeps an unbounded write log.
- **There is no neutral continuous property category.**
  - DIMMER, UV and STROBE are HTP and die at Blackout.
  - SETTING snaps at 50% of a crossfade.
  - FOCUS, ZOOM, IRIS and PAN already mean something to the 3D view.
  - SETTING / OTHER also fall into the Beam mask family, so a Beam-masked Record would sweep
    scenery with the lighting (`fx/PropertyMask.kt:65-67`).
- **Templates cannot hold it.** Their property vocabulary is closed (`fx/TemplateIntent.kt:230-249`).
- **Cues do not track.** "Non-tracking: cues are complete states"
  (`lighting-composition-model.md`). A tab closed in cue 14 would open again at cue 15 unless every
  later cue re-asserts it. Theatre scenery stays where it was put until something moves it.
- **It would show up everywhere a fixture does** — selections, rig order, counts — and the
  `infrastructure` flag cannot hide it, because infrastructure is also hidden from the stage.

The Hue fixture is not a precedent: it is a stub with no concrete class, a commented-out
controller and no patch path.

**A real motorised tab track on DMX is different.** That is a fixture, and it should be patched as
one. Its element then *follows* it (`drivenBy: {fixture, property}`), reading `open` from the live
channel instead of from scenery changes.

### Scenery changes, attached to the show

A **scenery change** is `{element, state values, timeMs?}`. It is its own record, attached in
three places:

| Attached to | Table | Meaning |
| --- | --- | --- |
| A **cue** | `cue_scenery` | On GO, move these elements, each on its own time (tabs 4 s, whatever the lighting fade is). |
| A **cue stack** | `cue_stack_scenery` | The stack's *set*: the state these elements take while the stack is live. "The Act 2 set appears with stack Act 2." |
| A **Look** | `look_scenery` | While the Look is live — layered in a live cue, or pressed as a programmer or busk layer — its scenery shows. "The sofa is only there in this Look." |

**Not templates.** A template is a generic intent that lands on whatever is selected. It holds no
targets of its own, so it has nothing to say about a particular sofa. This is D11.

**Scenery tracks, unlike lighting.** An element's state is resolved in precedence order:

1. A live Look's scenery, in programmer or busk layer order.
2. The live stack's cues. Every change from the top of the list down to the active cue, the last
   one winning. This is computed from list order, not history, so GO TO cue 20 lands the set as if
   the list had been run.
3. The live stack's set.
4. The element's base.

With two stacks live, the most recently GO'd wins per element. Scenery does *not* inherit the
lighting resolver's quirk of ranking stacks by database id.

**Where it runs.**

- **Resolution is backend-side**, from state the desk already has:
  - live stacks and active cues from `CueStackManager`;
  - live Looks from `ProgrammerLayerStack` and the live cues' layers.
- **One hook covers almost every GO.** `CueStackManager.activateCueInStack` and `deactivateStack`
  cover REST, MIDI, busk and auto-advance. The AI's `apply_cue` bypasses that path, and must be
  routed through it or given the same hook.
- **The result streams as `scenery.state`.** Per element it carries the resolved state, the state
  it is leaving, a start time and a duration. The client animates from that, as it already
  animates the cue fade bar from `cueRunStateChanged`.
- **The vis-source selector applies.**
  - **Next GO:** `/cue-stacks/{id}/preview` already composes without firing. Its response gains
    the incoming scenery beside the channels it returns.
  - **Programmer:** shows the programmer's Looks' scenery.
- **Record does not capture scenery.** Record never touches triggers or timed children today;
  scenery is edited explicitly on the cue, the stack or the Look.

**Sync.** Three new portable child tables, each referencing the element's uuid, plus
`stage_elements` itself. They join the same format bump. Deleting an element sweeps its changes,
as a group delete sweeps its busk-rig tiles today.

**Claude.** `set_scene` places elements. The cue and Look authoring tools gain a `scenery` list,
so "close the tabs on the blackout at the end of Act 1" is one call.

### In the prototype

- **House tabs**, drawn as two pleated halves.
- **A cut-out moon** that flies in behind the French window.
- **An *Act 1* cue list with a GO button:**
  - Q1 closes the tabs;
  - Q2 draws them over 4 s;
  - Q3 flies the moon in over 6 s;
  - Q4 blacks out and leaves the set alone.

Jumping straight to Q3 opens the tabs *and* flies the moon, because the state is tracked from
the top of the list. The closed tabs also stop the beams, since beam reach reads the tabs' state.
The Scene tab lists every cue's scenery.

## One-shot effects: the Twin Shot, and a session to fire it

Chris, 2026-09-29: the Twin Shot is a confetti cannon with two tubes, not a pinspot, and firing it
from the desk would be a good party trick. The fixture class already says what it is. It is a twin
electric confetti/streamer launcher with three channels:
- output 1, which fires at ≥ 51;
- output 2, which fires at ≥ 51;
- a master enable, without which neither output fires.

It deliberately implements no FX trait, so a tempo effect cannot fire it
(`EquinoxTwinShotMkIIFixture.kt`). Each fire spends a cartridge.

### Today

- **It is drawn as a generic effect with a beam.**
- **Its three channels are plain `OTHER` sliders, which leaves holes:**
  - A Look row, a cue's local rows or a programmer value can hold `output1 = 255`, and it will
    fire whenever that record is recalled.
  - `OTHER` falls into the Beam mask family (`fx/PropertyMask.kt:65-67`), so a Beam-masked Record
    sweeps a raised trigger into a cue.
  - A cue crossfade takes an LTP slider through 51 part-way. Nothing marks this as the one fixture
    where that is not a fade.

### In the prototype

- **The body.** A low base with two splayed tubes, hung on ADV1 and standing on the balcony ledge.
  It has no beam.
- **Arm and fire.**
  - Select it for Arm, Fire A, Fire B and Reload, plus loaded and spent per tube.
  - Arming lapses after 60 s and shows a red **ARMED** chip.
  - Firing puts about 380 flakes per tube into the air. They flutter down at a capped speed and
    settle where they land.
- **The cue event.** **Q5 Curtain call** fires both cannons as cue events 0.6–1.1 s after GO, only
  if armed. If not armed, it says it skipped them. It never fires later.

### Proposed, as session 9

1. **Triggers are their own kind of property, not sliders.** Add a `@FixtureTrigger` with an `arm`
   channel. The Twin Shot's two outputs become triggers and its master becomes their arm. A trigger:
   - is never a Look, template or cue row, an effect target, a Record or Update capture, or a
     busk pad;
   - never takes part in composition or crossfades;
   - is never restored from a channel snapshot on reload: triggers are seeded idle.

   The fix strips any stored rows that name these properties, and says how many it stripped.
2. **Firing is an event.** A fire is a backend-timed pulse: raise the output for about 300 ms, then
   idle, written above composition like a park. Three paths lead to it:
   - **A cue event.** A new `cue_events` child of a cue: `{fixture, trigger, atMs}`. It fires on GO
     into that cue only. Events do not track, so GO TO the cue after it does not fire, and neither
     does a Next GO preview. The AI's `apply_cue` path must go through the same hook (see §"Noticed
     on the way").
   - **A button.** On the fixture sheet and the Stage view: hold to fire.
   - **A MIDI binding.** `FireTrigger` requires the desk to be armed.

   MCP and remote sockets are refused unless an admin allows it, as scripts are.
3. **Arming is a desk-wide state.**
   - It is operator-only (the auth gate's role prefixes; see `followups.md` on new WS commands).
   - It is shown on every window.
   - It drives each cannon's master channel.
   - It lapses after a set time, and on a project switch or a stack stop.
   - A cue event on an unarmed desk is skipped, logged and toasted. It never queues.
4. **Loaded and spent are machine-local runtime state.** They are the physical rig, not show
   content. Fire marks a tube spent and Reload marks it loaded. Firing a spent tube warns and
   sends nothing.
5. **The visualiser follows the event.** `effects.fired {fixture, trigger, at}` streams to every
   window, which draws the burst. In Blind, or a Programmer vis source, a fire is **rehearsed**: the
   visualiser shows it and no DMX goes out. That is how a cue event gets plotted before the night.

## Decisions

| # | Question | Recommendation |
| --- | --- | --- |
| D1 | Drop the SVG 2D renderer? | Yes, once ortho editing in 3D has parity. Looking moves to ortho cameras immediately. |
| D2 | One `stage_elements` table with per-kind `params`, or a table per kind? | One table. Kinds will grow; `params` is validated per kind at the write boundary. |
| D3 | Venue and set as layers in a project, or a shared Venue entity across projects? | Layers now. A shared venue changes what a project owns; revisit when a second show plays the same hall. |
| D4 | Give Claude eyes (`render_view` through a desk window)? | Yes. Without it Claude models blind and the operator does all the checking. |
| D5 | Keep regions separate from elements? | Yes. A platform may link to a region. |
| D6 | Are viewpoints portable (synced) or machine-local? | Portable. "Row F" is a fact about the venue, not about this Mac. |
| D7 | Mesh (GLB) or MVR import now? | Later. It needs blob storage and a sync story; parametric elements cover this hall. |
| D8 | Where does the lantern library live? | A catalogue shipped with the desk, as the effects are, with per-project custom lanterns later. A shipped catalogue fixes a typo for every show at once. |
| D9 | Does each extra placement choose its own lantern? | Yes. Two lanterns on one dimmer are routinely different units. |
| D10 | GLB bodies per DMX fixture type? | Not yet. Parametric archetypes now; the `body` slot mirrors GDTF so a GLB or GDTF import can fill it later. Author our own meshes — GDTF-Share's cannot be redistributed. |
| D11 | Can a template carry scenery? | No. A template targets whatever is selected; scenery belongs to cues, stacks and Looks. |
| D12 | Scenery as its own tracked record, or as virtual DMX fixtures? | Its own record. Keep the fixture route for a real DMX-driven tab track, which an element can follow. |
| D13 | Does Record capture scenery? | No, not at first; it is edited on the cue, the stack or the Look. |
| D14 | Where does focus data (shutters, gate, iris, focus, zoom) live for a conventional? | On the placement, portable, never in a look. It is set with a spanner at the focus call. A fixture whose shutters are on DMX drives the same frame from its channels, and its looks carry those like any other attribute. |
| D15 | Are one-shot triggers their own property kind, kept out of composition, Record and FX? | Yes. A value that spends a cartridge must not be something a look can hold. |
| D16 | How is firing gated? | A desk-wide arm that lapses, is operator-only and is shown everywhere. A cue event on an unarmed desk is skipped and said so, never queued. MCP is refused by default. |

## Sessions, as a sketch

| # | Session | Needs a decision |
| --- | --- | --- |
| 0 | Renderer fixes, context loss, the `centerZ` fix, the label layer, and the lens that glows at zero (dark glass, emission only with level). | — |
| 1 | Built-in viewpoints and ortho sections in 3D; Positions replaces the overview panel. | D1 |
| 2 | `stage_elements` and `stage_viewpoints`: tables, REST, WS, sync v18, `set_scene` / `get_scene`, the template and the `describe_rig` summary. | D2, D3, D5, D6 |
| 3 | Scene rendering, the receiver shader and seats; retire the cookie instances. | — |
| 4 | `render_view` through a window. | D4 |
| 5 | Ortho edit parity, then delete `Stage2DView`. | D1 |
| 6 | Fixture bodies: the lens and housing change, the parametric archetypes instanced per part, beams from the aperture, cells for multi-head fixtures, and the DMX iris. Frontend only. | — |
| 7 | The lantern library, and `lanternType` / zoom / lamp rotation / shutters / gate / iris / focus on patches and placements; the focus card; the `body` descriptor on `@FixtureType`; oval beams. | D8, D9, D10, D14 |
| 8 | Scenery changes on cues, stacks and Looks: tables, the resolver, `scenery.state`, Next GO preview, the authoring UI and the MCP `scenery` lists. Builds on session 2. | D11, D12, D13 |
| 9 | The party trick: triggers as a property kind, cue events, arming, loaded/spent, `effects.fired`, confetti in the visualiser, rehearsal in Blind. Strips any stored trigger rows. | D15, D16 |

## What the prototype shows, and leaves out

It shows:
- the scene document built from seven kinds;
- lanterns from the library, with lenses that stay dark at zero;
- tabs and a flown piece moving on a tracked cue list;
- the four looks on the real rig;
- the eye, seat and actor viewpoints with look-around controls;
- ortho sections;
- the dollhouse orbit;
- the Positions sheet with shared selection;
- one decluttered label layer;
- context-loss recovery (*Test recovery*).

The canvas runs at DPR 1.5 with no composer.

It leaves out:
- shadows;
- gobos, oval PAR beams, zoom and pan/tilt limits;
- the desk's beam optics;
- editing;
- any backend.

## Noticed on the way

- **The outer ADV2 profiles may land on the pros.** With the stored yaw and pitch, the two outer
  units (`adv2-front-outer`, x = ±2.8) hit the pros wall in the prototype, not the opening. That is
  either the data or the prototype's reading of the angle convention; worth checking on the desk.
- **`docs/fixtures-engineering.md:747` is stale.** It still describes `worldPositionX/Y/Z`, which
  `FixturePatchDto` no longer carries.
- **The same doc contradicts itself on rigging rotation.** Lines 749–752 say a fixture inherits the
  truss's yaw; lines 919–922 and the code say a rigging's pose moves a fixture but does not turn
  it.
- **Stale comments.** `routes/projectRiggings.kt:171` and `:243` refer to fields that are gone.
- **Clicking empty space does not clear the 3D selection.**
- **The cue docs are stale in four places:**
  - `cues-engineering.md` and `cue-stacks-engineering.md` still describe standalone cues, but
    `cue_stack_id` is non-nullable (`models/cues.kt:175`).
  - `cues-engineering.md`'s "Active Cue Tracking" still reads `FxInstance.cueId`; the client now
    uses the stack's `activeCueId`.
  - Its `cue_layers` schema lacks the template, mask, blend, amount and stomp columns.
  - `cue-triggers-engineering.md` still lists DELAYED and RECURRING trigger types.
- **Cross-stack LTP priority follows each stack's database id** (`fx/CueApply.kt:171-172`), not
  show order or which stack went last. That is worth knowing even outside scenery.
- **The balcony data puts the two S4 Revs as hung** (`basePitchDeg 180`, under a pipe at 2.8 m),
  though they stand on the balcony. `aim_fixtures` and the Stage view's Aim will point them wrong
  until they are `0` on a ledge (item 11 of the fixture proposal).
- **The Twin Shot's fire channels are ordinary `OTHER` sliders.** A Look, a cue or a Beam-masked
  Record can hold a raised one, which fires on every recall. Session 9 closes this; until then it
  is worth checking that no record names `output1` or `output2`.
- **The AI's `apply_cue` bypasses `CueStackManager.activateCueInStack`**
  (`routes/projectCuesHelpers.kt:651`), so anything hooked on GO misses it.
