# Fixture optics — every beam channel the Stage view can see

> **Document status: IN PROGRESS — sessions 0, 1 and 2 done.** Approved by Chris, 2026-10-02. The survey behind it
> (what the manual says, what each fixture type declares, what the Stage view reads, with the numbers)
> is in [`fixture-optics-design/INDEX.md`](fixture-optics-design/INDEX.md). The same plan for human
> readers is [`fixture-optics-design/fixture-optics.html`](fixture-optics-design/fixture-optics.html), with a live
> copy at <https://claude.ai/artifact/3iPfQt9esafgbnonifFGxk> (the checked-in file is the authority).
>
> This document is the engineering half. Where it and the record disagree, this plan wins.

## 1. Context

Chris's brief, 2026-10-02, about the two ETC Source Four Revolutions on the Commemoration Hall's
balcony (project 15):

- the focus still can't be landed on the stage's back wall;
- the zoom does nothing, though the iris works;
- the framing shutters, gel scroller and beam wheels have no effect at all;
- this should become a general survey of the fixture types, because new features will be needed.

The findings that shape the plan:

- **The zoom declares no angles.** A ZOOM slider is turned into a beam angle through
  `degMin`/`degMax`. The Revolution's has neither, so the view keeps the `mover:profile` family's
  fixed 19°. The manual gives 15°–35°.
- **Focus reaches the wall but cannot be seen to.** The balcony is ~24 m from the back wall. The
  2–40 m declared range puts that at DMX ~246, which the slider reaches. But the depth-of-field
  term scales with a lens radius the Revolution never declares (0.059 m, guessed from the kind's
  default size), so every focal distance past ~4 m draws an identical edge on a 15–24 m wall —
  DMX ~140–255 look the same. The edge is capped at 88% hard at best, and gobos never land on a
  surface, which is how anyone actually judges focus.
- **Nine of the Revolution's beam channels are `SETTING` or `OTHER`**, which the Stage view never
  reads. There is no category for a framing shutter, and the gel scroller's options carry no
  colours.
- **The definition has errors the ETC manual corrects.** With the shutter module fitted (rear bay
  only), channels 20–23 are *reserved*, not a rear beam wheel. Channel 14 is fan speed, not
  reserved. Channel 6 is the internal media frame (two gel wings), not a wheel. Intensity is an
  electronic dimmer, not a douser. The manual documents the reset bands, the wheel bands and the
  stock gel string that the ChamSys capture could not.
- **The library has the same gaps elsewhere.** The Varytec's colour wheel has no previews, so its
  beam always draws black. Every scroll or random colour band draws black. The Robe's zoom is three
  steps, not a sweep. Fixed-lens heads cannot declare their angle. Strobe and closed-shutter bands
  are never drawn. Five fixtures expose a reset as an ordinary setting option a Look can hold.
- **No stored row uses any Revolution beam property** (checked against the desk's database,
  2026-10-02), so the definition can change shape freely.

## 2. Decisions taken

Chris confirmed the shape on 2026-10-02 in two rounds of questions: fitted media per placement,
the full focus treatment, all four optional areas in scope, resets reachable as confirmed fixture
commands, no-colour bands animated, and the build on estimates with the rig checks afterwards.

| # | Decision |
|---|---|
| D1 | Optics are fixture-type facts on the annotations. What is *loaded* into a unit (gels, gobos, media) is placement data. Neither ever lives in a Look. |
| D2 | A ZOOM slider must declare its angles; a stepped zoom is a setting whose options carry `zoomDeg`. A test enforces both, as `FocusRangeTest` does for focus. |
| D3 | A fixed-lens head declares `fieldDeg` on `@FixtureType`. The beam angle is: zoom channel, else the patch's `beamAngleDeg`, else the type's `fieldDeg`, else the family default. |
| D4 | Framing shutters are two categories, `SHUTTER` (insertion) and `SHUTTER_ROTATION`. Each names its blade (`TOP`, `BOTTOM`, `LEFT`, `RIGHT` — the lantern focus's wire order) and declares its scale: `depthMax` at DMX max, and `degMin`/`degMax` for rotation. |
| D5 | DMX blades and lantern blades are one shader path. The blade packing keeps two blades per float and widens to ±45° by quantising the angle to 1.5° steps. |
| D6 | Fitted media lives on each placement, beside lantern focus (stage-view D14): a scroller's gel string, a loadable wheel's slot contents, a media frame's gel. A type declares which settings are loadable and its stock contents; the placement overrides them by option. |
| D7 | The gel library moves to a backend resource, served to the client, so the backend can resolve a fitted gel to a colour for templates, `describe_rig` and the wheel snap. The eight stock-string gels it lacks are added. |
| D8 | A colour band with no single colour (scroll, random, rainbow, auto) animates through the wheel's own previews. Every other COLOUR option must declare a preview, enforced by a test. Nothing draws black for want of data. |
| D9 | Focus blur is computed from the relative distance to the focal plane, scaled by a per-type depth-of-field constant, not by a guessed lens radius. At the focal plane the edge is as hard as the optics allow; the 0.88 cap lifts. |
| D10 | Gobos land on surfaces. The surface shader samples the gobo atlas in light space for every light carrying a gobo, blurred by the same focus term. Shadows stay in `FU-STAGE-QUALITY-TIER`. |
| D11 | *Focus here* solves each head's focus DMX for a point (the landed pool, or a point from aim) — the inverse of the declared range — and writes it to the programmer as owner `WEB`, as aim does. |
| D12 | A STROBE channel's bands declare what they do: closed, open, strobe (with `hzMin`/`hzMax`), random or pulse. The view draws closed dark and flashes strobe at its rate up to 3 Hz; faster reads as a reduced-contrast shimmer (WCAG 2.3.1's three-flash limit). |
| D13 | Resets and lamp control become fixture commands (`@FixtureCommand`), not properties or setting options. They are refused by name at every write boundary, as triggers are. The desk holds a command's band for its declared time, then releases it. The fixture panel offers them behind a confirm; MCP and the public listener are refused unless an admin allows them. |
| D14 | Travel time is drawn, never output. A type declares how fast each family moves, and the fixture's own timing channels stretch it. The Stage view eases what it draws toward the DMX value; DMX output is untouched. |
| D15 | Every value not stated by ETC's manual or a published datasheet is marked `// Estimate:` at its source and listed in a manual-validation item. |

The plan adds four decisions of its own:

- **P1 — Nine sessions, one PR each**, in §5's order, per CLAUDE.md §"Git workflow". Session 0 comes
  first. Sessions 1 and 2 need only session 0. Session 4 needs session 3 for the Revolution's
  gobos, but not for the MAC 250 or Robe. Sessions 5–8 are independent of each other.
- **P2 — One `formatVersion` bump**, 22, in session 3 for fitted media (an older reader would drop
  it and push the loss back). Everything else is type data and needs none.
- **P3 — The front bay stays both.** Nobody knows whether TCH's units hold an iris or a wheel in
  the front bay (the shutter module has the rear). The Revolution keeps both the iris and the
  forward wheel live until the rig check answers it.
- **P4 — Project 15's fitted media is Chris's to set on the desk** once session 3 lands. The stock
  12-colour string is the default until then.

## 3. The model

### 3.1 Type vocabulary (annotations, no tables)

| Session | Where | Addition | Meaning |
|---|---|---|---|
| 0 | `@FixtureProperty` | `fineOf: String` | Names the coarse property this one refines. Generalises `PAN_FINE`/`TILT_FINE` so any 16-bit pair (a wheel's index) decodes as one value. |
| 0 | `PropertyCategory` | `GOBO_ROTATION_MODE` | A wheel's function channel. Its bands name `INDEX`, `ROTATE_FWD`, `ROTATE_REV`. |
| 0 | `@FixtureProperty` | `rpmMax`, `indexDegMax` | A rotation slider's top speed in rotate mode, and its sweep in index mode. |
| 1 | `@FixtureType` | `depthOfField: Double` | The type's depth-of-field constant (D9); the family's when absent. |
| 2 | `PropertyCategory` | `SHUTTER`, `SHUTTER_ROTATION` | D4. |
| 2 | `@FixtureProperty` | `blade: Blade`, `depthMax: Double` | D4. Rotation reuses `degMin`/`degMax`. |
| 3 | `@FixtureProperty` | `media: MediaSlot` (`GEL`, `GOBO`, `GOBO_OR_GEL`) | This setting's options can be loaded. Each option's stock content is its existing `colourPreview` or `gobo`. |
| 5 | `@FixtureType` | `fieldDeg: Double` | D3. |
| 5 | setting option | `DmxFixtureZoomSettingValue.zoomDeg` | D2's stepped zoom. |
| 5 | `@FixtureProperty` | `activeMin`, `activeMax` | The proportional part of a slider whose top is effects (the Robe's iris and frost run 1–179). Beyond `activeMax` the view holds the end value. |
| 6 | setting option / `BandedStrobeChannel` | `DmxFixtureStrobeSettingValue.strobeKind`, `hzMin`, `hzMax` | D12. |
| 7 | new annotation | `@FixtureCommand(label, description, holdMs, confirm)` on a `DmxCommand` | D13. Not a property: nothing that resolves a property by name sees it. |
| 8 | `@FixtureType` | `travel: Travel(panDegPerS, tiltDegPerS, beamMs, colourMs)` | D14's base speeds. |
| 8 | `@FixtureProperty` | `timing: TimingRole` (`POSITION`, `BEAM`, `COLOUR`, `ALL`), `timingSecondsPerStep` or a curve | A fixture timing channel: which families it stretches, and how. |

Each reaches the client through `DmxFixture.generatePropertyDescriptors()` →
`SliderPropertyDescriptor` / `SettingOption` / `FixtureTypeDetails` (`routes/lightFixtures.kt`)
→ `frontend/src/store/fixtures.ts`. Each is documented in `docs/fixtures-engineering.md`, which
today does not document the beam vocabulary at all.

### 3.2 Portable fields

| Session | Table / field | Columns | Notes |
|---|---|---|---|
| 3 | `fixture_patches` + `fixture_patch_placements` | `media` (text, JSON) | `{slots: {<propertyName>: {<optionName>: {gel?: code, gobo?: pattern}}}}`. Validated against the type's loadable settings at the write boundary, every problem at once. On both tables, so each unit is loaded separately (D6). |

The usual for a portable field: the sync DTO, exporter and importer, a non-default
`RichProjectFixture` value, a round-trip case and the `docs/sync-engineering.md` entry for
`formatVersion` 22. Cloning is derived.

### 3.3 Machine-local and runtime

- **A running command's hold** (session 7) is runtime: the desk owns the channel for `holdMs`, then
  returns it to composition. Nothing persisted.
- **Travel easing** (session 8) is per window, in the Stage view's director. Nothing on the backend.

### 3.4 The wire

- **REST**, under `/api/rest/projects/{id}`:
  - `POST programmer/focus` `{targets, point, fadeMs?, write?}` (session 1) — D11. Answers
    `{written: [{target, value, distanceM}], skipped: [{target, reason}]}`, the aim route's shape.
    Skips a head with no focus channel, no declared range, no placement, or a point outside its
    range, by name.
  - `GET /api/rest/gels` (session 3) — the gel library (D7).
  - `POST patches/{pid}/commands/{command}` (session 7) — D13. 409 `COMMAND_BUSY` while another
    command holds the unit; 403 `REMOTE_COMMANDS_DISABLED` on the public listener unless allowed
    (`requireEffectsAccess`'s pattern).
- **WS:** none new. A command's hold is a REST call that answers when the hold ends.
- **MCP:** `aim_fixtures` gains `focus: true` (session 1); `patch_fixtures` and `place_fixtures`
  gain `media` (session 3); `describe_rig` lists fitted media and commands; `run_fixture_command`
  is refused unless an admin allows it (session 7). Each goes into `docs/mcp-engineering.md`.

## 4. UX

- **The Focus tab** (`StageFocusPanel`) stops telling a DMX fixture its zoom is "from its channels"
  when it has none. It gains *Focus here* (session 1), and, for a type with loadable media, the
  unit's fitted media (session 3).
- **The patch sheet's Lantern box** gains a *Media* box for a type with loadable settings: the
  scroller string as an ordered list of gel swatches, each wheel's slots as gobo or gel pickers
  (session 3).
- **The fixture panel** gains a *Commands* menu, each behind a confirm that names the unit and the
  command, with a hold countdown (session 7).

## 5. Implementation — nine sessions

Each session is one PR. It ends with `./gradlew test` and `npm run check` green, the CLAUDE.md or
engineering-doc paragraphs written, and its done-marker here: a one-line row with the merge SHA.

### ~~Session 0 — the Revolution, corrected (backend)~~ — done, `433367f` ([PR #46](https://github.com/cjcormack/lighting7/pull/46))

- **Zoom:** `degMin = 35.0, degMax = 15.0` (DMX 0 wide, per ChamSys; the manual's nominal range).
- **Body:** dimensions from the manual (head 317 mm wide, 344 mm deep) and an estimated
  `lensDiameterM` from them; the manual gives no lens size.
- **Gel scroller:** category `COLOUR`, `GelFrame` options carrying the stock string's previews and
  names — frame 0 and 13 open, then R02, R05, R09, R54, R357, R36, R25, L203, L201, R68, R88,
  L-HT115.
- **Forward wheel:** position as a `GOBO` setting with the manual's bands (0–13 open, then three
  slots; 51–255 repeats slot 3); function as `GOBO_ROTATION_MODE` (`INDEX`, `ROTATE_FWD`,
  `ROTATE_REV`); the 16-bit index/rotation as `GOBO_ROTATION` with `fineOf`, `rpmMax = 30` and an
  estimated `indexDegMax = 360`. The view decodes rotation through the mode.
- **Rear wheel:** the four properties are removed (channels 20–23 are reserved with the shutter
  module fitted).
- **Media frame:** `mediaFrame` becomes a two-band setting, out and in (an estimated split at 128).
- **Fan speed:** channel 14 is exposed as `fanSpeed` (0 full → 255 off; the fixture's thermal
  sensors override it).
- **Decoders:** `beamOptics.ts` learns `fineOf` pairs and the rotation mode.
- **Docs:** `Manuals/personalities/ETC_Source4Rev_BaseFrame.md` is corrected against the manual
  (douser, ch 14, ch 20–23, reset bands, wheel bands, the gel string) and the class KDoc follows.
- **Tests:** a zoom-declares-angles test (D2) beside `FocusRangeTest`; the existing personality
  tests updated for the removed properties.

### ~~Session 1 — focus that reads (frontend, one backend route)~~ — done, `b66091b` ([PR #48](https://github.com/cjcormack/lighting7/pull/48))

- **Depth of field (D9):** `beamMask.ts`'s `focusBlur` takes the relative error
  `|f − d| / f` times the type's `depthOfField`. The family constants are tuned in
  `profileHarness.ts` so a Revolution at 24 m is visibly soft at ±3 m and sharp at the wall. The
  hardness cap lifts at the focal plane.
- **Locate** stops parking a declared-range focus at mid-DMX (3.8 m on the Revolution) and uses the
  range's middle distance instead.
- **Focus here (D11):** `POST programmer/focus`, solving `resolveDeclaredFocusDistance`'s inverse
  in Kotlin (a shared test vector pins both sides). The Focus tab's button sends the landed point;
  `aim_fixtures` gains `focus: true`, which focuses on the aim point.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"Focus" and its stale beam-angle precedence
  line.

### ~~Session 2 — DMX framing shutters (backend + frontend)~~ — done, `70d9436` ([PR #49](https://github.com/cjcormack/lighting7/pull/49))

- **Vocabulary (D4)** in `FixtureProperty.kt`, the descriptor and `fixtures.ts`.
- **The Revolution's frames:** `frame1Pos…frame4Rot` take the categories, with blades mapped
  1 → TOP, 2 → BOTTOM, 3 → LEFT, 4 → RIGHT, `depthMax = 0.5` and rotation ±45°, all marked as
  estimates (the manual states only "±45°, four blades").
- **Renderer (D5):** the director reads the blade channels every frame and packs them with
  `packBlades` into scratch, replacing the per-spec memo when a fixture has DMX blades. A lantern's
  blades and a DMX head's never coexist. The packing's angle step changes from 1° to 1.5°, and its
  test changes with it.
- **Locate:** blades out, rotation square.
- **Docs:** `docs/fixtures-engineering.md` §"Framing shutters".

### Session 3 — fitted media (backend + frontend)

- **The gel library (D7):** `frontend/src/data/gels.ts` moves to a backend resource
  (`src/main/resources/gels.json`), served at `GET /api/rest/gels`; R05, R09, R54, R357, R36, R25,
  R88 and L-HT115 are added.
- **Loadable settings:** `media` on the Revolution's gel scroller (`GEL`), forward wheel
  (`GOBO_OR_GEL`) and media frame (`GEL`).
- **Storage (§3.2):** `media` on patch and placement, `formatVersion` 22 (P2).
- **Resolution:** a descriptor's option previews and gobos are resolved per placement — the fitted
  content, else the stock. `TemplateResolver.nearestColourSlot` snaps against the fitted colours.
- **UX:** the *Media* box (§4), the Focus tab's media list, and MCP's `media` field.

### Session 4 — gobos on surfaces, and stacked wheels (frontend)

- **Surfaces (D10):** `scene/surfaceShader.ts` samples the gobo layer and angle in light space,
  using `aBeamShape.w` for the second layer. Gobos are drawn on every lit surface a gobo light
  reaches, blurred by session 1's term.
- **Stacked wheels:** a second gobo layer multiplied over the first (the Robe's static and rotating
  wheels), and the Robe's second colour wheel multiplied subtractively over the first.
- **Budget:** measured in Safari on the operator's Mac and an iPad before merging; if it runs
  short, the surface sample is limited to the selected heads and the rest keep today's pool.
  Recorded against `FU-MANUAL-STAGE-LIGHT-BUDGET`.

### Session 5 — the library optics pass (backend + frontend)

- **Fixed lenses (D3):** `fieldDeg` on the Fusion 100 (10°), IMG Wash-42 (10°) and Scantastic
  (11°).
- **Stepped zoom (D2):** the Robe's zoom becomes a setting with `zoomDeg` per band (15°, 18°, 22°,
  and the same with focus correction); `findZoomProperty` accepts a setting.
- **Proportional bands:** `activeMax = 179` on the Robe's iris and frost.
- **Colour (D8):** previews on the Varytec's wheel; scroll and random bands animate; a test fails
  any COLOUR option without a preview that is not marked as a no-colour band. The LED Lightbar's
  eight-digit preview is fixed.
- **Corrections:** the Robe's pan/tilt to 530°/280°; the Whex `DmxStrobe` overflow; the Whex
  duplicate program level; a UV-only fixture's single channel found as its dimmer.
- **Docs:** `docs/fixtures-engineering.md` gains §"Beam vocabulary" (categories, option
  annotations, slider optics); its stale `@FixtureProperty` signature and fixture table are
  rewritten.

### Session 6 — strobe and closed shutters (backend + frontend)

- **Vocabulary (D12)** on every STROBE property in the library, from its manual where it has one.
- **Renderer:** a closed band draws the beam dark; a strobe band flashes at its rate under the
  three-flash rule; random and pulse use the same clock. Driven by elapsed time passed into the
  director, so the harness stays reproducible.
- `FU-TMPL-STROBE-HZ`'s annotation half lands here; its template grammar half stays a follow-up.

### Session 7 — fixture commands (backend + frontend)

- **Vocabulary (D13):** `@FixtureCommand` and `DmxCommand`, guarded beside `TriggerGuard`
  (`COMMAND_NOT_STORABLE`).
- **Migration of the hazards:** the MAC 250's and Robe's lamp and reset methods; the Revolution's
  reset (manual bands, 3 s hold); the RESET options on the Varytec, Shehds, Fusion, Orbit and Slender
  settings become commands. A stored row naming a removed option is stripped at startup, logged,
  following `InstallBootstrap.kt`'s precedent.
- **Routes and UX:** §3.4's command route, the panel's *Commands* menu, MCP's refused-by-default
  tool.
- **Docs:** `docs/fixtures-engineering.md` §"@FixtureCommand".

### Session 8 — travel time (backend + frontend)

- **Vocabulary (D14)** on the movers, with base speeds from their manuals where stated.
- **The Revolution's timing channels:** 1 s per DMX step (manual), stretching position, beam and
  colour respectively; 0 means the fixture's own fastest.
- **Renderer:** the director keeps a displayed value per family and moves it toward the DMX value
  at the allowed rate, from elapsed time passed in. The scroller visibly passes through the frames
  between two gels.

## 6. Migration

- **No DB migration.** `media` is additive.
- **Session 7's strip of stored RESET options** is a one-off pass at startup, logged, deleted once it
  has run on the one install.
- **`formatVersion` 22** (P2), with its `docs/sync-engineering.md` entry.
- **Project 15's fitted media** is Chris's to set (P4).

## 7. Explicitly out of scope

- The Revolution's other personalities (14, 15 and 23 channel).
- Custom gobo images; fitted gobos come from the 16-pattern library.
- Shadow maps (`FU-STAGE-QUALITY-TIER`'s other half).
- Dragging blades on the pool (`FU-STAGE-FOCUS-ON-POOL`), for lanterns or DMX heads.
- Laser rendering, and haze that follows the hazer (`FU-STAGE-HAZE-FOLLOWS-HAZER`).
- Recategorising LED and movement programs as macros: the view's macros are canned animations, and
  drawing a guessed program is worse than drawing none.
- A Hertz arm in the template grammar (`FU-TMPL-STROBE-HZ`'s other half).

## 8. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-S4REV-PERSONALITIES` — the 14, 15 and 23-channel modes (session 0).
- `FU-GOBO-CUSTOM-IMAGES` — a venue's own gobo artwork for fitted slots (session 3).
- `FU-STAGE-PROGRAM-MACROS` — the programs §7 leaves as settings (session 5).
- `FU-STAGE-QUALITY-TIER` shrinks to shadows when session 4 lands; `FU-TMPL-STROBE-HZ` shrinks to the
  grammar when session 6 lands; `FU-TMPL-SECOND-COLOUR-WHEEL` keeps its template half when session 4
  draws the second wheel.

## 9. Verification

Beyond the unit suites, at the desk, in project 15:

- **Session 0.** A Revolution's zoom sweeps its pool between 35° and 15°. Frame 9 on the scroller
  draws L201's blue. The rear-wheel channels no longer appear on the fixture card.
- **Session 1.** From *Balcony · desk*, a Revolution on the back wall goes visibly soft when focus
  is moved 3 m either side of the wall, and *Focus here* lands it sharp. `aim_fixtures` with
  `focus: true` does the same through MCP, checked with `render_view`.
- **Session 2.** Each blade cuts the side it is named for, and turns about its own edge. A blade at
  full cuts to the centre.
- **Session 3.** A custom string on one unit draws its own colours while the other keeps stock. The
  pair round-trip through sync and a clone.
- **Session 4.** A MAC 250 gobo lands on the back wall and blurs with focus. The light-budget pass
  holds in Safari and on an iPad.
- **Session 5.** A Varytec beam is never black. The Robe zooms in three steps.
- **Session 6.** A MAC 250 at strobe 0 is dark in the view, as on the rig. A 20 Hz strobe draws a
  shimmer, not 20 flashes.
- **Session 7.** No Look, template, cue or programmer path can hold a reset. *Reset scroller* on a
  Revolution holds 147–152 for 3 s then drops; the public listener refuses it.
- **Session 8.** A Revolution with Colour Timing at 5 scrolls from frame 1 to 9 over 5 s, through
  the frames between.

## 10. Scope honesty

- **Most of the Revolution's numbers are estimates.** The manual states the zoom range, wheel
  bands, reset bands, timing and gel string. It does not state the zoom or focus direction, focus
  range, iris direction, the media frame's bands, which frame is which side, blade depth, rotation
  sign, the index range or which way pan runs. Each is marked (D15) and checked by
  `FU-MANUAL-S4REV-OPTICS`.
- **The manual is for pre-May-2007 units** (7160A1002). Later units have their own manual
  (7160M1210), and nothing says which TCH owns.
- **The front bay is unknown** (P3). Modelling both is right until the check, but one of the iris
  and the forward wheel is dead on the real unit.
- **Depth-of-field constants are judged, not measured.** D9's numbers are tuned by eye in the
  harness until session 1's check passes.
- **Gobos on surfaces have a cost** session 4 must measure; the fallback narrows them to the
  selection.
- **Travel speeds are mostly guesses.** Few manuals publish them.

## 11. Open questions

All of these are about the hall's units, not the code, and are steps of `FU-MANUAL-S4REV-OPTICS` in
[`manual-validation.md`](manual-validation.md):

1. Which module is in each Revolution's front bay, and what is loaded in it?
2. Is the scroller string ETC's stock 12 colours?
3. Are the units pre- or post-May-2007?
