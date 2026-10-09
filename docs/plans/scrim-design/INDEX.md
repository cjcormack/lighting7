# Scrims and painted cloths — design record

Source: Chris, 2026-10-09: "What do we need to do to support a scrim cloth in the stage view?
Ideally it should be modelled correctly in terms of light and be available in different uses (e.g.
fly in or use on tabs)." Then: "Can you create a design document that will show how it looks?"
Answering its questions, he widened the scope to painted cloths of every kind, for a pantomime he is
about to work on: "Can we go further and add support for painting all cloths? … let's include them
now."

This record holds the proposal, the pictures and the answers. There is no plan yet; one would follow
the stage-light plan's shape, at `../scrim-plan.md`.

**Status: proposed — scope agreed (below), not yet planned, nothing built.**

| File | What it is |
| --- | --- |
| `INDEX.md` | This record. |
| `scrim.html` | The proposal as one page. A live stage ray-traced in the page under the proposed rules: a gauze with a cue that runs the bleed-through and flies or draws the cloth, a translucent day/night sky cloth, and a foliage cut cloth. Stills of the reveal, the three ways to hang a scrim, fabrics, colours, seats and painted cloths. A to-scale weave section, the `open(θ)` chart and a section of the stage. The scene-document change with a sheet mock, the image store, the decisions, the order and a code map. There is a live copy at <https://claude.ai/artifact/9QC1N8r43f8nerguRcV77N>; the file here is the authority. |

## Decided — Chris, 2026-10-09

| Question | Answer |
| --- | --- |
| Bobbinet in the first cut? | Yes, both nets. |
| How many scrims may the haze honour at once? | Four. |
| Painted gauzes? | Go further: paint every cloth, now, for the pantomime. |
| Which elements take paint? | Drapes and flats. |
| Does transparency cut the cloth? | Yes: alpha cuts the cloth (cut cloths, foliage borders, cut-out flats). |
| Translucent day/night cloths? | Yes, with front and back paint. |
| Where do images come from? | Upload on the desk, and an MCP upload tool. No fetch by URL. |
| Warn when a scrim reads solid from a seat? | Yes, a hint on seat views. |
| Which lands first? | Whichever makes development cleanest; both are needed before the pantomime, with time to land both properly. |

## Sources

- **The code** at `d73c3c7`, under `frontend/src/components/stage3d/scene/`: `builders/drape.ts`,
  `sceneParts.ts` (`collides`, `finishLobes`), `pleat.ts` (`pleatShape`), `beamReach.ts`,
  `occlusion.ts`, `landing.ts`, `surfaceShader.ts`; and `../beamShaders.ts` (the haze march and its
  depth test). On the backend, `models/stageScene.kt` (`DrapeParams`, `FlatParams`,
  `parseElementParams`), `ai/SetupToolSchemas.kt`, and the prompt-book PDF's binary sync path:
  `sync/ProjectExporter.kt`, `ProjectImporter.kt`, `PromptScriptRepoSync.kt`, `JGitClient.kt`
  (`walkTree`'s skip) and `ExportUuidRemapper.kt`.
- **`docs/sync-engineering.md`** §"Version 4 — Prompt-book PDF binaries" (the contract the image
  store follows, and why it bumps `formatVersion`) and §"A params key added later needs no bump".
- **[`../completed/stage-light-plan.md`](../completed/stage-light-plan.md)**, whose box occlusion,
  finishes and exposure this builds on.

## Why the view cannot show these cloths today

Every drape is plain velour, opaque, in five places:

1. **Geometry.** `buildDrape` always pleats the cloth (`PLEAT_DEPTH_MIN_M`); a scrim, a canvas
   backcloth and a muslin hang flat.
2. **Finish.** An element's finish is a colour and a pattern; there is no image.
3. **Beam reach.** `ScenePart.collides` is a boolean, so a beam stops at the first cloth it meets.
4. **Shadows.** `segmentBlocked` and `OCCLUSION_GLSL` answer blocked or not, so nothing behind a
   cloth is lit through it or through a hole in it.
5. **Drawing.** Surfaces write depth and are drawn opaque; light from behind a cloth never reaches
   its front. The haze hull's front faces are depth-tested against them and the march ignores depth,
   so a see-through cloth needs the march to know where the cloth is.

## The proposal in brief

- **D1** `DrapeParams.fabric: CANVAS | MUSLIN | SHARKSTOOTH | BOBBINET`, absent = velour. Fabric is a
  separate choice from role and operation, so every use is a combination.
- **D2** Only velour pleats; a drawn cloth folds as it gathers.
- **D3** A scrim passes `open(θ) = (1 − r)·max(0, 1 − r/cos θ)` for beams and eyes alike (sharkstooth
  r 0.30, bobbinet 0.15, estimates); gathered net stacks as `open^c`.
- **D4** `paint: {front?, back?}` on drapes and flats, each a stored image's SHA-256, stretched over
  the face. Images are a content-addressed binary store, synced at `sceneImages/` — `formatVersion`
  23, for v4's reason; `minReader` stays 5.
- **D5** Alpha below 0.5 is a hole — surface, shadows, beam reach and haze alike, through a 256 px
  mask per image.
- **D6** Muslin is opaque to beams and eyes; light from behind lights its front through
  `τ · paint_front ⊙ paint_back` (τ 0.45, estimate).
- **D7–D8** A part may transmit, by angle or by mask; beams carry on through it, and occlusion
  multiplies by each crossing's share (a collider kind, a mask atlas of at most 32).
- **D9** A scrim draws as a premultiplied blend with no depth write, threads wrapping grazing light
  and glowing faintly from behind; a cut cloth discards its holes.
- **D10** The haze splits at each transmitting plane, by the beam's crossing and by the eye's, from a
  list of four.
- **D11** Uploads from the element sheet, REST, and MCP `upload_scene_image`.
- **D12** The Stage view loads a derived 2048 px display copy with mipmaps; the original is stored
  and synced.
- **D13** Seat views flag a scrim the seat sees at below half its head-on `open`.
- **D14** Out of scope: moiré, a visible weave, projection mapping, painted surfaces other than drapes
  and flats, and bounce light.

## Proposed order

Chosen for clean development, as asked. Session 1 settles the document and the store once, so no
later session touches sync. Session 3 builds the one transmittance core that scrims and cut-outs both
use. Both features are complete after session 5.

1. **The document and the image store** — params, store, REST, derived copies, sync v23, MCP, the
   element sheet. Nothing new drawn (D1, D4, D11, D12).
2. **Painted surfaces** — flat cloth, front and back images on drapes and flats (D2, D4, D12).
3. **The transmittance core** — transmitting parts by angle and by mask, in occlusion, beam reach
   and the landing fallback (D3, D5, D7, D8).
4. **See-through surfaces** — the scrim blend, cut-out discard, muslin translucency (D6, D9).
5. **Haze** — transmitting planes in the march, measured against the frame budget (D10).
6. **Tuning and the seat hint** — harness scenes for scrim, cut cloth and day/night; D13.

## What the page's renderer is

A ray tracer in one fragment shader. It traces a model stage: a proscenium, the gauze 50 cm upstage
of it, a room set, a figure, a foliage cut cloth and a muslin sky cloth painted procedurally for day
on its front and night on its back. It is lit by ten units, which share the desk's luminance roll-off
but not its optics: no gobos, no lens focus, and `I / (d² + 4)` falloff. It applies D2, D3, D5, D6
and D8–D10 as written, so the pictures show what the rules do, not what was painted to look right.
Its tuning numbers would be re-judged in the harness scenes (session 6).

## Open, for the plan to settle

1. Whether scrims and cut cloths share the haze's list of transmitting planes, and its size (a panto
   flies several borders at once; proposed: one shared list of eight, nearest the eye first).
2. Whether a painted velour leg folds its paint with its pleats (proposed) or paint forces a cloth
   flat.
3. Whether the 2048 px display copy is sharp enough for a hero frontcloth from the front row (about
   6 mm a pixel on 12 m), with an optional 4096 px copy as the fallback.
