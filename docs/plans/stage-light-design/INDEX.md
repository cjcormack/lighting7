# Stage light — design record

Source: Chris, 2026-10-05, with two screenshots of the Robe 575 on ADV1 lighting the black
backcloth, one at pan 40 and one at pan 45. He asked for a survey before any fix of three things in
the Stage view:

1. How a surface looks under a beam is inconsistent: pan 40 draws thin vertical stripes, and pan 45
   a pool split by a straight seam into a whole half and a striped half.
2. How surfaces reflect light does not look natural, the black backcloth at pan 40 especially.
3. How focus looks is weird.

This record holds the diagnosis. The plan is [`../stage-light-plan.md`](../stage-light-plan.md); the
HTML version for human readers is [`stage-light.html`](stage-light.html).

**Status: draft, awaiting approval.**

| File | What it is |
| --- | --- |
| `INDEX.md` | This record. |
| `stage-light.html` | The survey as one page: how each characteristic should behave, what the view does, the options, a to-scale pleat section, a reflectance chart, an edge explorer and a side view of the beam in haze under each focus model. There is a live copy at <https://claude.ai/artifact/X2ZDbbo6d39wwvUgXJAege>; the file here is the authority. |

## Sources

- **The code** at `e3782cd3`, under `frontend/src/components/stage3d/`: `scene/surfaceShader.ts`,
  `scene/landing.ts`, `scene/beamReach.ts`, `scene/StageSceneElements.tsx`,
  `scene/builders/drape.ts`, `FixtureModel.tsx` (`landBeam`, `edgeLanding`, `writeLightRow`),
  `beamMask.ts`, `beamShaders.ts`, `goboAtlas.ts`, `goboLayers.ts`, `washConfig.ts`,
  `bodies/archetype.ts`.
- **`frontend/docs/stage-vis-engineering.md`** §"Light lands through one surface shader" and
  §"Focus".
- **Not** the show's scene: the screenshots were not reproduced against project data. The
  diagnosis follows from the geometry and the shader; the plan's session 1 begins by confirming it.

## The three symptoms, diagnosed

### 1. Stripes at pan 40, a seam at pan 45

The view has no shadows. Each light carries up to two **landing planes** (`landing.ts`), and the
surface shader skips a fragment more than `REACH_EPS` (3 cm) behind both.

- **Stripes.** A drape's collider is a box 10 cm deep (`partBox`, `pleat`: half-depth 0.05 m); its
  drawn cloth is a sine 5 cm peak to peak (`PLEAT_DEPTH_M`) centred in it. The landing plane, the
  box's front face, sits 2.5 cm in front of the crests, and only cloth within 3 cm behind it is lit:
  the crest caps, 20.5 % of each 14 cm pleat (`acos(0.8) / π`), thinned further by `n·L`.
- **Seam.** A straight vertical seam needs a plane with a level normal, and only the second landing
  plane can supply one. `edgeLanding` casts eight rim rays and keeps a face when the geometry looks
  like a convex edge; fragments are culled only behind both planes. In front of the second plane
  the cloth is lit whole; behind it the stripes return. Whether a rim ray clips such a face (a
  cloth's end, the inside of a pair of tabs, a leg) changes between two pan values with nothing in
  between.
- **The same class.** An `OBJECT` cylinder's collider is a square box, so a column lights only a
  3 cm sliver at its tangent. The haze reads the same planes (`aBeamLand`). `buildDrape` documents
  `depthM` as the cloth's fullness and nothing reads it.

### 2. Reflection

`lit = 1 − exp(−(albedo·(ambient + fill) + max(albedo, 0.1)·light) · 1.1)`, with Lambert's `n·L`
as the whole BRDF.

- `#111111` is 0.0056 linear; floored it reflects 0.10, 18× its finish, like 35 % sRGB grey paint.
- The floor is per channel. The default drape `#3b1219` (0.044, 0.006, 0.010) floors to neutral
  (0.10, 0.10, 0.10), so a white pool on a red drape lands grey.
- Pleats are about 1.25× fullness (stage drapes hang at 1.5–2×), a perfect sine, and never shadow
  one another. No sheen, no specular, no bounce.
- Typical diffuse reflectances, for scale: black velour 1–2 %, wool serge 2–5 %, matte black paint
  3–6 %, black dance floor 4–8 % plus a satin specular, grey card 18 %, timber deck 20–40 %, white
  cyc 60–80 %.

### 3. Focus

The focal distance is sound (linear in 1/distance, solved on the desk by *Focus here*). How a blur
is drawn is not:

- `beamMask` rolls the edge off from `1 − w` to `1`, so defocus moves the half-brightness point in:
  0.96 of the radius at blur 0.1, 0.87 at 0.25, 0.725 from 0.55. The soft-centre term dims the rim
  up to a further 35 %. A soft pool shrinks; in haze the beam looks fattest at the focal plane.
- Nothing is drawn outside the field: the surface shader's `c < axis.w` and the haze's cone hull.
- Hardness saturates at `FOCUS_SOFT_BLUR` (0.55 field radii).
- Focus is measured on the slant (`dist − aperture.x`, `relLen − near`), not the axis. Focused
  exactly on a square wall the rim still blurs by `dof · (1/cos θ − 1)`: 0.022 field radii on the
  Robe at 15°, 0.31 on a 50° profile, whose edge is then 11× its in-focus width.
- Gobos blur by mip level (`log2(1 + blur × 64)`) through box-filtered mips of 128 px tiles: blocky
  from level 3, pulsing between levels.
- The blur **amount** is exaggerated on purpose. A thin lens 15 cm across, focused at 21 m, blurs a
  24 m wall by about 0.002 field radii; the view's `dof` was tuned so a focus move reads. A
  dioptre-shaped blur with one constant per family (`K · |1/d − 1/f|`) matched at 24 m would blur
  a 3.5 m surface by 2.6 field radii when focused at 4 m, so the amount stays relative.
