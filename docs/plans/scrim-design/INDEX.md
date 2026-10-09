# Scrim — design record

Source: Chris, 2026-10-09: "What do we need to do to support a scrim cloth in the stage view?
Ideally it should be modelled correctly in terms of light and be available in different uses (e.g.
fly in or use on tabs)." Then: "Can you create a design document that will show how it looks?"

This record holds the proposal and the pictures. There is no plan yet; one would follow the
stage-light plan's shape, at `../scrim-plan.md`.

**Status: proposed — not approved, nothing built.**

| File | What it is |
| --- | --- |
| `INDEX.md` | This record. |
| `scrim.html` | The proposal as one page. A live stage ray-traced in the page under the proposed rules, with a cue that runs the bleed-through and flies or draws the cloth. Stills of the reveal, the three ways to hang a scrim, fabrics, colours and seats. A to-scale weave section, the `open(θ)` chart and a section of the stage. The scene-document change, the decisions, the order and a code map. There is a live copy at <https://claude.ai/artifact/9QC1N8r43f8nerguRcV77N>; the file here is the authority. |

## Sources

- **The code** at `d73c3c7`, under `frontend/src/components/stage3d/scene/`: `builders/drape.ts`,
  `sceneParts.ts` (`collides`, `finishLobes`), `pleat.ts` (`pleatShape`), `beamReach.ts`,
  `occlusion.ts`, `landing.ts`, `surfaceShader.ts`; and `../beamShaders.ts` (the haze march and its
  depth test). On the backend, `models/stageScene.kt` (`DrapeParams`, `parseElementParams`) and
  `ai/SetupToolSchemas.kt`.
- **`docs/sync-engineering.md`** §"A params key added later needs no bump", the `travelS` precedent.
- **[`../completed/stage-light-plan.md`](../completed/stage-light-plan.md)**, whose box occlusion,
  finishes and exposure this builds on.

## Why the view cannot show a scrim today

Every drape is opaque in four places:

1. **Geometry.** `buildDrape` always pleats the cloth (`PLEAT_DEPTH_MIN_M`), and a scrim hangs flat.
2. **Beam reach.** `ScenePart.collides` is a boolean, so a beam stops at the first cloth it meets.
3. **Shadows.** `segmentBlocked` and `OCCLUSION_GLSL` answer blocked or not, so nothing behind a
   cloth is lit through it.
4. **Drawing.** Surfaces write depth and are drawn opaque. The haze hull's front faces are
   depth-tested against them, and the march itself ignores depth, so a see-through cloth needs the
   march to know where the cloth is.

## The proposal in brief

- **D1** `DrapeParams.fabric: SHARKSTOOTH | BOBBINET`, absent = solid. Fabric is a separate choice
  from role and operation, so every use is a combination: a flown gauze is `BACKCLOTH` + `FLY`, a
  traveller scrim `TABS` + `DRAW`, a gauze before the cyc `CYC` + `DEAD`.
- **D2** A scrim hangs flat, and a drawn one folds as it gathers.
- **D3** One function decides what passes, for beams and eyes alike:
  `open(θ) = (1 − r)·max(0, 1 − r/cos θ)`, with r the thread's share of the pitch (sharkstooth 0.30,
  bobbinet 0.15, both estimates). Gathered cloth stacks as `open^c`.
- **D4–D5** A beam does not stop at a scrim. Shadows become transmittance per collider.
- **D6** The scrim draws as a premultiplied blend with no depth write. Its threads wrap grazing light
  (0.45) and glow faintly from behind (0.18), both estimates.
- **D7** The haze splits at the plane: beam-side by the beam's angle, eye-side by the eye's, from a
  capped list of scrims.
- **D8** No `formatVersion` bump and no new state: the reveal is lighting, and the moves are the
  drape's existing `trimM` and `open`.
- **D9** Out of scope: moiré, weave texture, painted or projected images, bounce light.

## What the page's renderer is

A ray tracer in one fragment shader. It traces a model stage: a proscenium, the scrim 50 cm upstage
of it, a room set, a figure and a painted sky cloth. It is lit by eight units, which share the
desk's luminance roll-off but not its optics: no gobos, no lens focus, and `I / (d² + 4)` falloff.
It applies D2–D7 exactly as written above, so the pictures show what the rules do, not what was
painted to look right. The tuning numbers are the page's own and would be re-judged in a
`?profileHarness=scrim` scene (session 4).

## Open questions

1. Bobbinet in the first cut (proposed: yes).
2. How many scrims the haze honours at once (proposed: four).
3. Whether a painted gauze is wanted enough to plan an image finish. The page's forest is not in
   scope.
4. Whether a seat view should warn when a scrim reads as solid from that seat.
