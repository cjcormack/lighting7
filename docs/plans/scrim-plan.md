# Scrims and painted cloths — light through cloth, and images on it

> **Document status: APPROVED — sessions 1–5 shipped, as `2729120d`, `6c6f4f85`, `14dcb7bd` (2026-10-09), `324c3b99` and `302ac2a1` (2026-10-10).** Scope agreed with Chris, 2026-10-09,
> over three rounds of questions (the answers are in §10 and in the design record). The survey and
> the pictures are in [`scrim-design/INDEX.md`](scrim-design/INDEX.md). The same proposal for human
> readers is [`scrim-design/scrim.html`](scrim-design/scrim.html), with a live copy at
> <https://claude.ai/artifact/9QC1N8r43f8nerguRcV77N> (the checked-in file is the authority).
>
> This document is the engineering half. Where it and the record disagree, this plan wins.

## 1. Context

Chris's brief, 2026-10-09: support a scrim in the Stage view, "modelled correctly in terms of light
and … available in different uses (e.g. fly in or use on tabs)". Answering the record's questions,
he widened it: a pantomime he is about to work on needs painted cloths, so "let's include them now".
Both are needed before the pantomime, and there is time to land both properly, so the order below is
chosen for clean development rather than for which lands first.

The findings that shape the plan (the record's §"Why the view cannot show these cloths today"):

- **Every drape is velour.** `buildDrape` always pleats (`pleat.ts`'s `PLEAT_DEPTH_MIN_M`), and an
  element's finish is a colour and a pattern, never an image.
- **Every cloth stops every beam.** `ScenePart.collides` is a boolean. `beamReach` stops at the
  first collider, and the box occlusion test (`occlusion.ts`, stage-light plan D8) answers blocked
  or not. Nothing behind a cloth is lit through it or through a hole in it.
- **Every surface is opaque and lit from its own side.** Surfaces write depth. The back-face flip in
  `surfaceShader.ts` makes light behind a cloth contribute nothing to its front.
- **The haze cannot see through anything.** Its hull's front faces are depth-tested against the
  scene, and the march itself ignores depth (`beamShaders.ts`). So a see-through cloth that writes
  no depth would let the haze behind it draw at full strength, and one drawn after the haze would
  dim the haze in front of it too.
- **There is a precedent for a synced binary:** the prompt-book PDF, content-addressed at
  `promptScripts/{sha256}.pdf` since v4, kept out of the text machinery.

## 2. Decisions

From the design record, as agreed:

| # | Decision |
|---|---|
| D1 | **Fabric is a cloth's, not a role.** `DrapeParams.fabric: CANVAS \| MUSLIN \| SHARKSTOOTH \| BOBBINET`, absent = velour. Role (a default colour and finish) and operation (how it moves) are unchanged, and every use is a combination of the three: a flown gauze is `BACKCLOTH` + `FLY` + `SHARKSTOOTH`, a traveller scrim `TABS` + `DRAW`, a black scrim before the cyc `CYC` + `DEAD`. |
| D2 | **Only velour pleats.** Every other fabric hangs flat, whatever its `depthM`. Paint follows velour's pleats. A drawn cloth of any fabric folds as it gathers, with an amplitude that grows with the gathering. |
| D3 | **One function of angle for a scrim.** `open(θ) = (1 − r)·max(0, 1 − r/cos θ)`, θ from the cloth's normal, decides both what a beam passes and what an eye sees through. Sharkstooth r = 0.30, bobbinet r = 0.15 (estimates, judged in session 6). Gathered net stacks: `open^c`, with c the cloth's width over the width it is gathered into. |
| D4 | **Drapes and flats take paint.** `paint: {front?, back?}`, each the SHA-256 of a stored image, stretched over the element's face: a flat's downstage and upstage faces, a drape's two sides. The images are a content-addressed binary store, synced at `sceneImages/`. |
| D5 | **Alpha cuts.** An image pixel below half opacity is a hole, in the surface, the shadows, beam reach and the haze alike, through a 256 px mask per image. |
| D6 | **Muslin is translucent.** It is opaque to beams and to the eye. Light from behind lights its front through `τ · paint_front ⊙ paint_back`, with τ = 0.45 (estimate). So a back painting shows only where it is left open, coloured by the dye on the front. That is the day/night cloth. |
| D7 | **A part may transmit.** Besides colliding or not, a part may pass a share of light: by angle (a scrim) or by mask (a painted cloth with holes). Beam reach and the landing planes skip it, so a beam carries on to the next solid surface. *Focus here* still takes the point it is given. |
| D8 | **Shadows become transmittance.** The occlusion test multiplies by each transmitting crossing's share and carries on, instead of returning blocked. The packed collider gains a third texel for its kind. Masks sit in an atlas of at most 32. The landing-plane fallback does the same. |
| D9 | **Seeing through is a blend.** A scrim draws without writing depth, premultiplied over what is behind it, weighted by `1 − open(θ_eye)`. Its threads wrap grazing light (0.45 at grazing) and glow faintly from behind (0.18), both estimates. A cut cloth discards its holes and writes depth elsewhere. |
| D10 | **Haze splits at the plane,** from **one list of eight** transmitting planes shared by scrims and cut cloths, nearest the eye first. Each beam's samples past a plane on its path scale by the beam's crossing, and samples behind a plane from the eye by the eye's. A ninth still passes light on surfaces, but its haze does not split. |
| D11 | **Uploads from the desk and from MCP:** a file picker on the element sheet, a REST upload, and `upload_scene_image`. No fetch by URL. |
| D12 | **The GPU sees a 2048 px copy.** The original is stored and synced. The view loads a derived display copy with mipmaps, and occlusion reads the 256 px mask. A per-element **Full detail** switch loads 4096 px for a hero cloth, kept per machine and never synced. |
| D13 | **Seat views flag a solid-looking scrim.** When a seat sees a scrim at below half its head-on `open`, the seat's row in the viewpoint picker and its popover say so: "Forest gauze reads near-solid from F6 (open 12 %)". |
| D14 | **Out of scope:** moiré, a visible weave, projection mapping, painted surfaces other than drapes and flats, and bounce light (`FU-STAGE-BOUNCE-FILL`). |

The plan adds its own:

- **P1 — Six sessions, one PR each,** in §5's order, per CLAUDE.md §"Git workflow". Each ends with
  `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and its
  done-marker here: a one-line row with the merge SHA.
- **P2 — One format bump, in session 1.** `fabric`, `paint` and the `sceneImages/` folder all land
  together at `formatVersion` 23, so no later session touches sync. The params keys alone would need
  no bump (`sync-engineering.md` §"A params key added later needs no bump"), but the folder does, for
  v4's reason: an older reader's wipe-then-export push would delete it for every peer. `minReader`
  stays 5.
- **P3 — PNG and JPEG only.** The JVM's `ImageIO` decodes both with no new dependency and no native
  payload (the `nativePayloadOs` verifier stays untouched). WebP waits for `FU-SCENE-IMAGE-WEBP`.
- **P4 — The record's renderer is not shipped.** It is a sketch of the model. The harness scenes
  (session 6) are built from real elements through the real pipeline.
- **P5 — The pantomime's cloths are Chris's to build,** as fixture-optics left project 15's fitted
  media to him.

## 3. The model

### 3.1 Vocabulary (no tables)

- `enum class DrapeFabric { CANVAS, MUSLIN, SHARKSTOOTH, BOBBINET }` in `models/stageScene.kt`, read
  case-insensitively like every scene enumeration.
- `@Serializable data class ScenePaint(val front: String? = null, val back: String? = null)`: each a
  64-character lower-case hex SHA-256. An empty `paint` is written as absent.
- The frontend twins: `DRAPE_FABRICS` in `EditSceneElementForm.tsx`, and a `fabric` and `paint`
  reader in `sceneParts.ts` beside `paramEnum`.

### 3.2 Portable fields

| Session | Where | Field | Notes |
|---|---|---|---|
| 1 | `stage_elements.params` (DRAPE) | `fabric`, `paint` | Validated in `parseElementParams`'s DRAPE arm, every problem at once. A paint hash must name an image in this project's store. |
| 1 | `stage_elements.params` (FLAT) | `paint` | The same check. Openings still cut through a painted flat. |
| 1 | the show repo | `sceneImages/{sha256}.{png\|jpg}` | Raw bytes, content-addressed. Only the hashes some element references are exported. No JSON DTO: the file is the record. |

The usual for portable data, at v23: the params travel inside the element's DTO as they already do.
The exporter copies the referenced images and the importer hydrates them (§3.3). `RichProjectFixture`
gains a painted muslin drape (front and back, one with alpha) and a painted flat, all at non-default
values. `ProjectRoundTripTest` asserts the images' bytes survive, and `docs/sync-engineering.md`
gains §"Version 23". Cloning is derived: the importer's hydrate fills the new project's store, so
`ProjectCloner` needs nothing of its own. `SyncCoverageTest` is untouched, since there is no new table.

### 3.3 Machine-local and runtime

- **The store:** `<appDataDir>/scene-images/{projectUuid}/{sha256}.{ext}`, behind
  `State.sceneImagePath`, beside `promptScriptPath`. Raw-byte, crash-atomic writes (write to a
  temp file, then move), as `PromptScriptRepoSync` does. An image no element has referenced for 7
  days is pruned at project load, so an upload whose element was never saved does not pile up.
- **Derived copies:** `…/{projectUuid}/derived/{sha256}-{2048|4096|mask}.png`, made on first
  request, cached per machine and never synced. The display copies are downscaled by successive
  halving with bicubic filtering, keeping alpha. The mask is the alpha channel at 256 px on the
  longest side, one byte a pixel (opaque white for a JPEG).
- **Full detail (D12):** a `machine_overrides` row through `Overrides.setString(projectId,
  "stage_elements", elementUuid, "displayDetail", "4096")`. It is per-record machine-local, like a
  universe's address, so it gets an override row rather than a table.
- **The sync working tree:** `SceneImageRepoSync`, a sibling of `PromptScriptRepoSync`, reconciles
  `sceneImages/` to exactly the referenced hashes. `.gitattributes` gains `sceneImages/** binary`, and
  `JGitClient.walkTree` and `ExportUuidRemapper` skip the folder as they skip `promptScripts/`.
- **Runtime state:** nothing new. The reveal is lighting, and the moves are the drape's existing
  `trimM` and `open` states.

### 3.4 The wire

- **REST**, under `/api/rest/projects/{id}`, with the stage-element routes' role gate and stored data
  only, so ungated by the current project:
  - `POST scene-images` takes raw bytes (`Content-Type: image/png | image/jpeg`, at most 25 MB) and
    answers `{hash, width, height, hasAlpha, mediaType}`. It is idempotent by hash. 400
    `SCENE_IMAGE_INVALID` for a type it does not take, an image that does not decode, or one more
    than 8192 px on a side. 413 over 25 MB.
  - `GET scene-images/{hash}` serves the original, and `?variant=display | detail | mask` a derived
    copy. All are content-addressed, so they carry `Cache-Control: public, max-age=31536000,
    immutable`. 404 `SCENE_IMAGE_UNKNOWN`.
  - `PUT stage-elements/{eid}/display-detail {full}` sets the machine-local switch.
    `StageElementDto` gains `fullDetail: Boolean`, answered from the override and never in a sync DTO.
  - An element write naming an unknown hash is refused in the existing `ELEMENT_INVALID` problem
    list: `params.paint.front names no stored image`.
- **WS:** none new. Uploads need no frame: an image is referenced by an element write, which fires
  `stageElementListChanged` already.
- **MCP:** `upload_scene_image {mediaType, base64}` answers what the REST upload answers, capped at
  16 MB decoded (a larger image goes through the desk). It is stored data only, so it has no
  remote-access gate, as `set_scene` has none. `set_scene` and `get_scene` carry `fabric` and
  `paint`, and the DRAPE and FLAT lines of `SetupToolSchemas.kt` name them. `render_view` shows the
  result with nothing new. Each goes into `docs/mcp-engineering.md`.

### 3.5 The light model (frontend)

- **Parts** (`sceneParts.ts`): `ScenePart.collides: boolean` becomes `light: 'solid' | 'none' |
  Transmit`, where `Transmit = {kind: 'angle', r, gather} | {kind: 'mask', image, uv}`. `uv` maps
  the part's own x and z to the image, so a drawn cloth's two halves each carry their share of the
  image, compressed as they gather. `PartFinish` gains `paint?: {front?, back?}` (hashes) and
  `translucent?: number` (τ).
- **Colliders** (`beamReach.ts`): `Collider` gains `transmit?: Transmit`. `beamReach` skips a
  transmitting collider, or for a mask samples the CPU mask at the crossing and skips it there only
  where it is a hole. `sightBlocked` passes a scrim, and passes a mask where it is a hole.
- **Occlusion** (`occlusion.ts`): `COLLIDER_TEXELS` goes from 2 to 3. Texel 2 is `(kind, r or atlas
  layer, gather or the mask's u0..u1, 0 or its v0..v1)` — a net's `(1, r, gather, 0)`, a mask's
  `(2, layer, u0..u1, v0..v1)`, each uv pair packed in one float, since a mask needs its uv rect (a
  drawn half's is half the image) and has no gather (as built in session 3). The atlas is a 256 × 256 × 32 `R8` `DataArrayTexture` keyed by hash, so a
  33rd distinct mask falls back to solid, named in `stageStats`. `segmentBlocked` and `OCCLUSION_GLSL`
  become `segmentTransmit`, which returns a share: 0 at the first solid box, otherwise the product of
  the crossings' shares. A box the fragment sits inside within its skin is still skipped, so a
  cloth does not shadow itself.
- **Landing** (`landing.ts`): a transmitting collider is never a landing plane. Each beam's row
  gains the transmitting planes it crosses, by index into the haze list (D10).
- **Surfaces** (`surfaceShader.ts`): new defines.
  - `PAINT` samples `uPaintFront` / `uPaintBack` as albedo, by the face drawn (`gl_FrontFacing`),
    and discards below 0.5 alpha.
  - `SCRIM` is the D9 blend: `transparent`, `depthWrite: false`, premultiplied, with the threads'
    wrap and glow. It draws before the beams (`renderOrder`), so the additive haze lands on top and
    attenuates itself.
  - `TRANSLUCENT` adds D6's back term for lights behind the face.
  - `open(θ)` is one GLSL chunk with a TypeScript twin, as `beamMask.ts` has, pinned against each
    other by a test.
- **Haze** (`beamShaders.ts`): a uniform list of eight transmitting planes (centre, half-extents,
  yaw, kind, r or layer, gather), filled each frame nearest the eye first. Per sample, it multiplies
  by the eye-side share of each plane between the eye and the sample, and by the beam-side share of
  each plane the beam's row names between the apex and the sample.

## 4. UX

- **The element sheet** (`EditSceneElementForm.tsx`):
  - A **Fabric** select beside Role and Moves by on a drape: Velour (default), Canvas, Muslin
    (translucent), Sharkstooth scrim, Bobbinet scrim.
  - **Paint, front** and **Paint, back** on drapes and flats, each a thumbnail with Upload, Replace
    and Remove. The upload goes to `POST scene-images`, then the hash goes into params.
  - The aspect hint: "Image 2 : 1, cloth 12.0 × 6.0 m · aspects match", or, more than 2 % off, a
    warning with **Match height to image**. "Transparent pixels cut holes" shows when `hasAlpha`.
  - A **Full detail (4096 px, this machine)** switch.
  - The Depth field's hint says it folds only velour.
  - An image missing on this machine (a hash with no file, after a partial import) shows "Image
    missing on this machine" and draws the cloth unpainted.
- **The Stage view** draws all of it. The seat's row in `StageViewpointPicker` and its popover carry
  D13's hint, computed client-side from the seat's eye to each shown scrim's centre and corners.
- **The View menu:** nothing new. Work lights lift a painted cloth as they lift any finish.

## 5. Implementation — six sessions

Each session is one PR. It ends with `./gradlew test` and `npm run check` green, the CLAUDE.md or
engineering-doc paragraphs written, and its done-marker here: a one-line row with the merge SHA.

### Session 1 — the document and the image store (backend + frontend sheet) — done, `2729120d`

- **Params (D1, D4):** `DrapeFabric`, `ScenePaint`, `DrapeParams.fabric` and `.paint`, and
  `FlatParams.paint`, with their `parseElementParams` arms and the "names no stored image" check.
  `elementTravelS` is unchanged.
- **Store (§3.3):** `State.sceneImagePath`, writes, derived copies and the 7-day prune.
- **Routes (§3.4):** `routes/projectSceneImages.kt`, plus the display-detail switch and
  `StageElementDto.fullDetail`.
- **Sync (P2):** exporter copy, importer hydrate, `SceneImageRepoSync`, `.gitattributes`, the
  `walkTree` and remapper skips, `SUPPORTED_FORMAT_VERSION = 23`, and §"Version 23".
- **MCP (D11):** `upload_scene_image`, and `fabric` and `paint` in `set_scene` / `get_scene` and the
  schema lines.
- **Sheet (§4):** the Fabric select, the paint pickers with thumbnails from `?variant=display`, the
  aspect hint, the Full detail switch, and `elementProblems.ts`'s mirror. **Nothing new is drawn
  yet**, so a painted cloth still renders as today's velour.
- **Tests:** params round-trip and refusals (an unknown hash, a bad enum, every problem at once);
  upload idempotence, type, size and dimension refusals; derived copies' sizes and alpha;
  `ProjectRoundTripTest` with the fixture's images byte-for-byte; a v22 archive still imports; the
  prune keeps referenced and young images; the MCP tool against the route.
- **Docs:** `fixtures-engineering.md` §"The scene document", `sync-engineering.md` §"Version 23",
  `mcp-engineering.md`, and CLAUDE.md's Stage Scene Endpoints and MCP lines.

### Session 2 — painted surfaces (frontend) — done, `6c6f4f85`

- **Flat cloth (D2):** `buildDrape` draws flat geometry for every fabric but velour. A drawn cloth
  folds as it gathers, through `pleat.ts`, with amplitude rising from 0 at `open` 0.
- **Paint (D4, D12):** `PAINT` in the surface shader, front and back by facing. A texture cache keyed
  by hash and variant loads the 2048 or 4096 copy, with GPU mipmaps and anisotropy, and disposes
  unreferenced textures. UVs on flats (openings respected) and on both halves of a drawn cloth.
  Painted velour keeps its pleats with the image on them.
- **Finishes:** canvas is MATTE, muslin MATTE, and the nets get a new `NET` lobe (matte, no sheen).
  Scrims and muslin default to off-white unless `finishColour` is set.
- **Holes are drawn, not yet lit:** this session discards alpha below 0.5 in the surface only.
  Shadows and beams follow in session 3.
- **Tests:** builders give flat parts for each non-velour fabric, and UVs that tile the face exactly
  once across a drawn cloth's halves at any `open`; the texture cache loads each variant once and
  disposes on drop.
- **Docs:** `stage-vis-engineering.md` §"Painted cloths".

### Session 3 — the transmittance core (frontend) — done, `14dcb7bd`

- **Parts and colliders (D7):** `light: 'solid' | 'none' | Transmit` through builders, `partBox` and
  `elementColliders`. `beamReach` skips or samples, and sight lines pass.
- **Occlusion (D8):** the third texel, the mask atlas, `segmentTransmit` in TypeScript and GLSL, and
  the landing fallback multiplying likewise.
- **Scrim and mask shares (D3, D5):** `open(θ)`, with its twin, and the CPU mask sampler. After this
  session, light passes scrims and cut-outs onto what is behind, and a cut cloth's shadow follows its
  edge. The scrim itself still draws opaque.
- **Tests:** `segmentTransmit`'s twin against the GLSL on a fixed scene (solid, scrim at 0°, 45°
  and 80°, a mask hole and a mask cloth); `beamReach` passing a scrim and stopping at mask cloth;
  the 33rd-mask fallback; `open(θ)` pins (49 %, 40 % and 9 % at 0°, 45° and 70° for sharkstooth).
- **Docs:** the occlusion paragraphs in `stage-vis-engineering.md`.

### Session 4 — see-through surfaces (frontend) — done, `324c3b99`

- **Scrim (D9):** the `SCRIM` define, its render order before the beams, and `open(θ_eye)` with the
  gather exponent. The threads' wrap and glow.
- **Muslin (D6):** the `TRANSLUCENT` define and τ.
- **Tests:** the blend's twin (a pixel's weights for a given eye angle and gather); render order
  pinned; muslin's back term zero without back paint.
- **Docs:** §"Light lands through one surface shader" gains the scrim and muslin bullets.

### Session 5 — haze (frontend) — done, `302ac2a1`

- **The list (D10):** eight planes, filled per frame nearest the eye first. Each beam row names the
  planes on its path, and the march applies both shares.
- **Budget:** measured as the stage-light plan's session 3 was, on SwiftShader, Safari on the
  operator's Mac and an iPad, and recorded against `FU-MANUAL-STAGE-LIGHT-BUDGET`. The haze governor
  absorbs the cost. If it cannot, the list shrinks before the visuals do.
- **Tests:** the march's share for a sample behind one, two and nine planes; list order by distance.
- **Docs:** the haze paragraphs.

### Session 6 — tuning and the seat hint (frontend)

- **Harness:** `?profileHarness=scrim` (the classic reveal and a flat FOH), `=cutcloth` (a foliage
  border in haze), and `=daynight` (a muslin front and back). Judge r, the wrap, the glow and τ by eye
  and set the estimates.
- **Seat hint (D13):** in `StageViewpointPicker` and the seat popover.
- **Tests:** the hint's threshold on a seat straight on and one 45° off; harness scenes build.
- **Docs:** the estimates' final values; the record's status line.

## 6. Migration

- **No DB migration.** `fabric` and `paint` are additive params keys, and `displayDetail` is an
  override row.
- **`formatVersion` 23** (P2). A v23 repo is refused by a v22 desk, as v4 refused v3.
- **The pantomime's cloths** are Chris's to build (P5).

## 7. Explicitly out of scope

- Moiré, and a visible weave: below a pixel from the house.
- Projection mapping onto a cloth (`FU-STAGE-PROJECTION`).
- Paint on rooms, prosceniums, platforms and objects (`FU-STAGE-PAINT-ALL-SURFACES`).
- WebP uploads (`FU-SCENE-IMAGE-WEBP`).
- Fetching an image by URL, from the desk or from MCP (D11).
- Bounce light (`FU-STAGE-BOUNCE-FILL`), and shadow maps (`FU-STAGE-QUALITY-TIER`).

## 8. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-SCENE-IMAGE-WEBP` — WebP uploads, gated on a designer's files arriving as WebP (session 1).
- `FU-STAGE-PAINT-ALL-SURFACES` — paint beyond drapes and flats, gated on a set the two cannot dress
  (session 2).
- `FU-STAGE-PROJECTION` — a projector's image landing on a cloth, gated on a show with projection
  (session 4).
- `FU-STAGE-QUALITY-TIER`'s text notes that cloths transmit through boxes now (session 3).

## 9. Verification

Beyond the unit suites, at the desk, in the pantomime's project once Chris has built a cloth or two,
and in the harness:

- **Session 1.** Upload a PNG with alpha and a JPEG from the sheet. Both thumbnails show, the
  aspect hint reads right, and the project syncs and clones with the images. A second desk pulling
  the repo shows the same thumbnails. `upload_scene_image` then `set_scene` dresses a cloth.
- **Session 2.** A painted frontcloth reads from the stalls viewpoint. A painted velour leg's paint
  folds with its pleats. A drawn painted tab keeps its image whole as it opens. Full detail sharpens
  the hero cloth on this machine only.
- **Session 3.** A flat FOH lights the set through a gauze, a steep wash does not, and a foliage
  border's shadow on the floor follows its leaves.
- **Session 4.** The reveal works from the stalls: wash on, solid; wash out and the scene up, gone.
  A day/night cloth turns to night when it is lit from behind. `render_view` shows both.
- **Session 5.** A back light's haze reads through a gauze at the eye's share, shafts cross a cut
  cloth, and the frame budget holds on the three machines.
- **Session 6.** The far-side seat flags the gauze, and the centre seat does not.

## 10. Questions

All answered by Chris, 2026-10-09:

1. **Bobbinet in the first cut?** Yes, both nets (D3).
2. **How many scrims may the haze honour?** Four. Then, once cut cloths joined: **one shared list of
   eight** for scrims and cut cloths together (D10).
3. **Painted gauzes?** Go further: paint every cloth, now, for the pantomime (D4).
4. **Which elements take paint?** Drapes and flats (D4).
5. **Does transparency cut the cloth?** Yes (D5).
6. **Translucent day/night cloths?** Yes, with front and back paint (D6).
7. **Where do images come from?** Upload on the desk and an MCP upload tool (D11).
8. **Warn when a scrim reads solid from a seat?** Yes, a hint on seat views (D13).
9. **Does a painted velour fold its paint?** Yes. Paint follows the pleats, and canvas is the flat
   painted cloth (D2).
10. **Is 2048 px sharp enough?** 2048 px for every cloth, with 4096 px per cloth on request (D12).
11. **Which lands first?** Whichever makes development cleanest, so §5's order.
