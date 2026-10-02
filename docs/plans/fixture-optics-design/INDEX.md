# Fixture optics — design record

Source: Chris, 2026-10-02, about the two ETC Source Four Revolutions on the FOH Balcony of
project 15 (*The Commemoration Hall*):

1. The focus still isn't long enough: pointed at the back wall, it can't be focused there.
2. The zoom does nothing, though the iris does.
3. The frames, gel scroller and forward and rear beam wheels have no effect.

He asked for the problem to be widened into a survey of every fixture type. This record holds that
survey and the evidence behind it. The plan is [`../fixture-optics-plan.md`](../fixture-optics-plan.md);
the HTML version for human readers is [`fixture-optics.html`](fixture-optics.html).

**Status: approved, 2026-10-02.**

| File | What it is |
| --- | --- |
| `INDEX.md` | This record. |
| `fixture-optics.html` | The plan and the survey as one page, for engineers. Where it and the plan disagree, the plan wins. There is a live copy at <https://claude.ai/artifact/3iPfQt9esafgbnonifFGxk>; the file here is the authority. |

## Sources

- **ETC, *Source Four Revolution User Manual*, 7160M1200 Rev E** — `Manuals/S4_Revolution_User_Manual_RevE.pdf`.
  It covers units built before May 2007 (7160A1002); later units have manual 7160M1210. Page
  numbers below are the PDF's, with the printed page in brackets.
- **The ChamSys capture** — `Manuals/personalities/ETC_Source4Rev_BaseFrame.md`, taken from MagicQ's
  head editor on 2026-04-26.
- **The TCH patch export** — `Manuals/TCH_2026_.pdf`, a one-page MagicVis patch. It names the
  two Revolutions (heads 39 and 40, "Pipe 1FOH", mode Base Frame) and nothing about their gels,
  gobos or modules.
- **The desk** — project 15's patch and scene through MCP, and its database (read-only).
- **The code** — the fixture definitions, the descriptor path and the Stage view, at `07e473f0`.

## The three symptoms, diagnosed

### Zoom does nothing

The Stage view turns a ZOOM slider into a beam angle with `dmxToDegrees(zoom, zoomProp)`
(`FixtureModel.tsx:1168`), which needs `degMin`/`degMax`. The Revolution's zoom
(`Source4RevolutionFixture.kt:155`) declares neither, so `beamDeg = zoomDeg ?? baseBeamDeg` falls
to the `mover:profile` family's 19° (`archetype.ts:182`). The comment above that line names the
Revolution as a case it knows about.

The manual gives a 15°–35° zoom (p4 [ii]). ChamSys's ranges say DMX 0 is wide; the manual does not
say.

| Spread | Nominal | Beam (½ peak) | Field (1/10 peak) | Candela |
|---|---|---|---|---|
| Narrow | 15° | 10.5° | 15.3° | 376,520 |
| Median | 25° | 17.2° | 25.3° | 153,730 |
| Wide | 35° | 23.7° | 34.3° | 78,410 |

(p47 [43]. The throw table there fits pool diameter ≈ 2·d·tan(field/2).)

### Focus can't be landed on the back wall

The Revolutions are at y = −17.3 m, z = 2.8 m; the back wall is at y = 6.77 m. The throw is about
24 m.

**The range reaches it.** The declared range is 2–40 m, not inverted, interpolated in 1/d
(`resolveDeclaredFocusDistance`, `beamOptics.ts:347`):

| Focal distance | DMX |
|---|---|
| 3 m | 89 |
| 3.8 m | 128 |
| 5 m | 161 |
| 10 m | 215 |
| 15 m | 233 |
| 20 m | 242 |
| 24 m | 246 |
| 40 m | 255 |

10–40 m is the top 16% of the fader, about 0.4 m per step at 15 m with no fine channel. Locate
parks focus at mid-DMX (`LocateValueResolver.kt:162`), which is 3.8 m.

**The renderer hides it.** The blur is `2·near·|f−d| / (f·(near+d))` (`beamMask.ts:160`), where
`near` is the apex-to-aperture distance. The Revolution declares no lens, so the kind's default
0.30 m body gives a 0.059 m lens radius and `near` ≈ 0.355 m at 19°. Hardness reaches the family
cap of 0.88 (`SOFTNESS['mover:profile']` = 0.12) once blur ≤ 0.119 field radii. On a 15 m wall
that holds for every focal distance ≥ 4.2 m: **DMX ~141–255 draw identical edges**. For a 10 m wall
the band starts at DMX ~121; for 20 m, at ~150. The wall only softens below about DMX 140, where the
focal plane sweeps 2–4 m in mid-air.

Being on the wall is never sharper than being 4 m from it. The gobo blur, the cue anyone actually
focuses by, never shows: the Revolution's wheels aren't read, and no gobo lands on a surface
(`surfaceShader.ts:34`).

### Frames, scroller and wheels do nothing

The Stage view finds every beam channel by category (`store/fixtures.ts:431–564`). It never reads
`SETTING` or `OTHER`. The Revolution's frames, wheels and scroller are `SETTING`; the media frame is
`OTHER`.

- **Shutters** exist in the renderer only as a lantern's focus data: four `{depth, angleDeg}`
  blades packed into the light table once per body spec (`FixtureModel.tsx:1014`,
  `beamMask.ts:109`). Depth is a fraction of the field's diameter; angle is ±30° in whole degrees,
  six bits each, two blades per float.
- **Colour** comes from one source: an RGB property, else the first COLOUR setting's option preview,
  else a patch gel on an `acceptsGel` type. An option without a preview draws the beam black
  (`FixtureModel.tsx:1655`). The scroller's `GelFrame` options carry none.
- **Gobos** take one layer and one angle per beam, drawn in the air only. A second wheel shows only
  while the first is open.

## What the manual says that the definition gets wrong

| Ch | Definition today | The manual (Base + Framing, p14 [10]) |
|---|---|---|
| 1 | "Mechanical douser" (personality notes) | An integral PWM electronic dimmer (p4 [ii]) |
| 6 | Media frame, `OTHER` slider | The Internal Media Frame: two gel "wings" moved in and out by the front lens (p31–33 [27–29]); in/out only |
| 12 | Reset, bands not captured | 185–190 whole fixture, 147–152 scroller and lenses, 126–129 pan/tilt, 97–102 front module, 72–77 rear module; hold 3 s, then snap to 0 (p15 [11]) |
| 14 | "Reserved" | Fan speed: 0 full, 255 off; thermal sensors override (p16 [12]) |
| 16–19 | Forward wheel, plain sliders | Position 0–13 open, 14–26 / 27–39 / 40–50 slots 1–3, 51–255 = slot 3; function 0–13 index, 14–26 rotate >>, 27–39 rotate <<; 16-bit index or 0–30 RPM (p22–24 [18–20]) |
| 20–23 | Rear wheel, plain sliders | **Reserved.** The shutter module fits the rear bay only (p17 [13]) |
| 24–31 | Frames, plain sliders | Four blades, each rotating ±45° (p17–18 [13–14]) |
| 9–11 | Timing, `SPEED` | 1 s per DMX step to 255 s; record in zero-fade cues; 100% focus timing is a "console response" mode (p16 [12]) |

**The gel scroller** holds up to 20 frames and ships with ETC's standard 12-colour string
(p15 [11], p29 [25]). ChamSys's 14 bands match it exactly:

| Frame | DMX | Gel | Frame | DMX | Gel |
|---|---|---|---|---|---|
| 0 | 0–17 | open | 7 | 128–145 | R25 Orange Red |
| 1 | 18–36 | R02 Bastard Amber | 8 | 146–164 | L203 ¼ CTB |
| 2 | 37–54 | R05 Rose Tint | 9 | 165–182 | L201 Full CTB |
| 3 | 55–72 | R09 Pale Amber Gold | 10 | 183–200 | R68 Sky Blue |
| 4 | 73–90 | R54 Special Lavender | 11 | 201–218 | R88 Light Green |
| 5 | 91–109 | R357 Royal Lavender | 12 | 219–237 | L-HT115 Peacock Blue |
| 6 | 110–127 | R36 Medium Pink | 13 | 238–255 | open |

The desk's gel library (`frontend/src/data/gels.ts`, 34 entries) has R02, L201, L203 and R68, and
lacks the other eight.

**Modules.** The rear bay takes the shutter module; the front bay takes exactly one of a blank
(one fixed metal gobo), the iris (18 leaves, to 2.5°), a static three-slot wheel or a rotating
three-slot wheel (p17 [13]). The wheels hold no stock gobos: their slots are the user's M-size
gobos or dichroic filters. So a Base Frame unit has the iris *or* a wheel, never both, and which one
TCH's units hold is unknown.

**What neither the manual nor ChamSys states:** the focus range and direction; the iris
direction (ChamSys says 0 open); the media frame's bands; which frame is which side; blade depth;
the rotation sign and whether 128 is square (ChamSys locates there); which way ">>" turns; the
index range; which way pan and tilt increase. Each is an estimate in the plan.

## The library survey

Every type in `fixture/dmx/` and what the Stage view can do with it. "Unread" means a channel with a
visible effect on the real fixture that the view cannot draw.

| Type | Body | Unread or mis-drawn |
|---|---|---|
| ETC Source Four Revolution (31ch) | MOVER/PROFILE | Zoom (no angles); frames ×8; scroller (no previews); forward wheel ×4; media frame; rear wheel ×4 (reserved anyway) |
| Robe ColorSpot 575 (mode 2) | MOVER/SPOT | Zoom is three steps, has no angles; iris and frost proportional only to 179; second colour wheel ignored; scroll/random bands black; strobe 0–31 closed but drawn lit; pan/tilt annotated 540/257°, manual says 530/280° |
| Martin MAC 250 (mode 4) | MOVER/SPOT | Strobe 0–19 closed but drawn lit; scroll/random bands black |
| Varytec Easymove XL 60 (11ch) | MOVER/SPOT | **No colour previews: beam always black**; reset as a setting option |
| Equinox Fusion 100 Spot (5/8/15ch) | MOVER/SPOT | Fixed 10° lens can't be declared; rainbow bands black; strobe 0–9 blackout drawn lit; reset as an option |
| Shehds LED19 RGBW (16/24ch) | MOVER/WASH | Reset as an option |
| Gear4music Orbit-70 (13ch) | MOVER/WASH | Static-colour slider ignored behind RGB; strobe 0–7 off drawn lit; reset as an option |
| IMG Stageline Wash-42 (13ch) | MOVER/WASH | Fixed 10° lens can't be declared; colour macro has no previews |
| Equinox Scantastic 4 | — | Fixed 11° can't be declared; shutter and colour/pattern channels are `SETTING` |
| Slender Beam Bar Quad | — | Reset as an option; heads tilt together (`FU-STAGE-INDEPENDENT-HEADS`) |
| Whex | PAR | `DmxStrobe` overflows near full (`(255/245·i)+10` wraps); duplicate program level 111 |
| LED Lightbar 12 Pixel | — | An eight-digit hex preview |
| UV | — | Its one channel is category `UV`, so nothing finds a dimmer |
| Laserworld CS-1000RGB | — | No laser rendering |
| Hazer, Fog Fury Jett | — | Output not read (`FU-STAGE-HAZE-FOLLOWS-HAZER`) |

Across the library:

- **No STROBE channel is drawn.** A closed band is dark on the rig and lit in the view.
- **No SPEED or timing channel is modelled.** The view snaps to every value.
- **No type declares a lens diameter**, and none can declare a fixed field angle.
- **Resets on five types are ordinary setting options**, selectable from Looks, cues, templates,
  the programmer and effects. The MAC 250's and Robe's are script methods only.
- **The beam vocabulary is undocumented.** Categories, `gobo`, `prismFacets` and `colourPreview`
  live only in KDoc. `docs/fixtures-engineering.md`'s `@FixtureProperty` signature and its fixture
  table are stale.

## Decisions and their reasons

The plan's D1–D15 are each one line. The reasons:

- **D1, D6 — type facts versus loaded media.** The manual settles the bands; the venue settles what
  is in the slots. Per-placement media follows the stage-view plan's D14 (focus data on the
  placement), because two units in one rig can carry different strings.
- **D2 — test-enforced zoom angles.** A zoom without angles is silently inert, which is how the
  Revolution's went unnoticed. `FocusRangeTest` closed the same hole for focus.
- **D5 — ±45° in the same packing.** Seven bits of angle would push two blades past a float's 24-bit
  mantissa. 1.5° steps keep 61 values in six bits, finer than any operator focuses a frame.
- **D7 — the gel library on the backend.** A fitted gel must become a colour for the template
  resolver's wheel snap and for `describe_rig`, both backend; the library is a frontend constant
  today.
- **D8 — animate, don't blacken.** Black is the one answer certain to be wrong. Chris chose
  animation over open white.
- **D9 — relative blur.** Real depth of field depends on the aperture, which the desk will never know
  well; a per-type constant tuned against the harness is honest about that and gives a visible
  focal plane.
- **D10 — gobos on surfaces, not an opt-in tier.** Chris chose the full treatment. The budget check
  and the selection-only fallback keep it safe.
- **D12 — the three-flash rule.** The Stage view is on screens other people watch; it should not
  strobe faster than WCAG 2.3.1 allows.
- **D13 — commands.** A reset in a Look is a show-stopping mistake waiting to be recorded, and it is
  one click away today on five types. Triggers already prove the pattern: a property kind outside
  composition, refused by name at every boundary.
- **D14 — drawn, never output.** The desk must never second-guess what the fixture does with its own
  timing; the view only shows what the fixture will do.

## Noticed on the way

- `frontend/docs/stage-vis-engineering.md` §"Fixture bodies" says the patch's `beamAngleDeg` beats a
  zoom channel; the code does the reverse. Session 1 fixes the doc.
- `StageFocusPanel`'s DMX note tells the Revolution its zoom comes from its channels, which until
  session 0 is not true.
