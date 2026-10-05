# Stage light — pools, cloth and focus

> **Document status: DRAFT — awaiting Chris's approval.** The survey behind it (how each
> characteristic should behave, what the Stage view does instead, the options and the numbers) is
> in [`stage-light-design/INDEX.md`](stage-light-design/INDEX.md). The same survey for human
> readers is [`stage-light-design/stage-light.html`](stage-light-design/stage-light.html), with a
> live copy at <https://claude.ai/artifact/X2ZDbbo6d39wwvUgXJAege> (the checked-in file is the
> authority).
>
> This document is the engineering half. Where it and the record disagree, this plan wins.

## 1. Context

Chris's brief, 2026-10-05, with screenshots of the Robe 575 on ADV1 lighting the black backcloth:

- how a surface looks under a beam is inconsistent: pan 40 draws thin stripes, pan 45 a pool cut by
  a straight seam;
- surfaces do not reflect light naturally, the black backcloth at pan 40 especially;
- focus looks weird.

The findings that shape the plan:

- **Occlusion is two planes, and the drape's plane is in the wrong place.** A drape's collider is
  10 cm deep and its drawn pleats 5 cm, so the landing plane sits 2.5 cm in front of the crests and
  the 3 cm skin lets light reach only 20 % of each pleat. The seam at pan 45 is a second landing
  plane, found by one of eight rim rays, cutting the pool into a lit-whole side and a striped side.
- **A 10 % reflectance floor stands in for exposure.** It makes a `#111` cloth reflect 18× its
  finish, and since it is per channel it turns a red drape's pool grey. Lambert is the whole BRDF;
  pleats are shallow, regular and never shadow each other.
- **Defocus is drawn inward.** The edge rolls off inside the field, so a soft pool shrinks by up to
  28 % and a beam in haze looks fattest at its focal plane. Nothing may spill outside the cone, the
  softening stops at 0.55 field radii, and focus is measured on the slant rather than the axis.
- **The blur amount is a deliberate exaggeration** of a real lens's, so that a focus move reads.
  It stays.

## 2. Decisions proposed

| # | Decision |
|---|---|
| D1 | A collider contains its part's drawn relief. Each collider carries a **skin**, how far behind its face the drawn surface can lie: the pleat depth for cloth, the radius for a cylinder, 3 cm (`REACH_EPS`) otherwise. The director shifts a landing plane back by its face's extra skin before packing, so the light table and the haze attribute keep their layout. |
| D2 | A drape's pleat depth and fullness come from its element's `depthM`, through one function the mesh and the collider both read. |
| D3 | Focus is measured **along the beam's axis**, in the surfaces and the haze, as *Focus here* already solves it. |
| D4 | Focus blur spreads the edge **symmetrically** about the field edge — iris and blades too, since they sit in the gate. The outward spread is capped (`FOCUS_SPREAD_MAX`, tuned by eye), and a light's cone bound, its haze hull and its region cull cone widen by that cap, for heads with a focus channel only. The soft-centre term stays for frost and family softness and leaves focus alone. |
| D5 | The blur **amount** stays `dof · |f − d| / f`. A dioptre-shaped blur (`K · |1/d − 1/f|`) was considered and rejected: with one constant per family matched at 24 m it makes short throws extremely blurry, and scaling `K` with `f` gives back today's model. |
| D6 | A finish reflects its own albedo. Visibility on dark finishes comes from exposure and the roll-off curve, not from a reflectance floor. The roll-off works on luminance and keeps chroma. |
| D7 | Pleats shadow each other. The fold is a known sine in its own frame, so the shader tests the crest between a point and the light analytically; no shadow map is involved. |
| D8 | For surfaces, the landing planes are replaced by an analytic test of the segment from fragment to lamp against the colliders in that light's cone. The haze keeps the planes until measurement says it can afford the boxes. |
| D9 | Shadow maps stay `FU-STAGE-QUALITY-TIER`. Bounce light, cloth micro-detail and a chromatic fringe are out of scope (§7). The renderer keeps its no-post-processing rule. |

The plan adds two decisions of its own:

- **P1 — Four sessions, one PR each**, in §5's order, per CLAUDE.md §"Git workflow". Session 1
  first. Sessions 2 and 3 are independent of each other; session 4 needs session 2's lighting
  model.
- **P2 — No schema, wire or sync change.** Everything is frontend rendering. Material parameters
  (session 4) are defaulted by element kind, role and part on the frontend; a per-element override
  would be a portable field and is not planned.

## 3. The model

- **Collider** (`scene/beamReach.ts`) gains `skin`, in metres; `BeamHit` and the director's
  `SurfaceHit` carry it from the face that was hit. `packLanding` subtracts each face's skin beyond
  `REACH_EPS` from its plane's offset. The landed **point** is unchanged, so beam length,
  `coneLandingDepth` and *Focus here*'s landed point are unaffected.
- **Pleat geometry** moves into one pure function of the element (pitch, amplitude, seeded
  irregularity from the element's uuid) that `partGeometry`, `partBox` and the surface material all
  read.
- **The mask** (`beamMask.ts`, GLSL and twin) takes the focus blur as its own argument beside the
  softness: `soft` keeps today's inward roll-off for frost and family softness; `blur` centres a
  penumbra on each gate edge (field, iris, blades).
- **The light row** (`scene/lightTable.ts`) is unchanged in layout. `cosBound` (texel 1's alpha)
  carries the widened bound for focus-capable heads.

## 4. UX

No controls change. The view looks different:

- pools land whole on cloth and columns, and stay continuous as a head pans (session 1);
- a defocused pool keeps its size and feathers outward, and a beam in haze widens steadily with its
  gobo sharpest at the focal plane (session 1);
- dark finishes read dark, coloured finishes keep their colour, and raking light bands a drape
  (session 2);
- flats, legs and platforms cast hard shadows on walls and floors (session 3);
- floors carry a soft reflection of the rig and cloth a grazing sheen (session 4).

## 5. Implementation — four sessions

Each session is one PR. It ends with `npm run check` green (and `./gradlew test` if anything
outside `frontend/` changed), the engineering-doc paragraphs written, and its done-marker here: a
one-line row with the merge SHA.

### ~~Session 1 — the visible bugs (frontend)~~ — done, `4eea82ed`

- **Confirm the diagnosis first** with the record's two checks: raise the pleat collider's skin
  and the pan 40 stripes become a whole pool; log `edgeHit` for the head at pan 40 and 45.
- **Skin (D1):** `Collider.skin`, carried by `beamReach`'s hit, `landBeam`, `edgeLanding`'s rim
  casts and `packLanding`. Pleat skin is its peak-to-peak depth plus `REACH_EPS`; a cylinder's is its
  radius (it lights its whole lit face, and anything within its radius behind it, until session 3).
- **Pleat collider:** its half-depth is the drawn amplitude, through the shared pleat function
  (D2's function lands here at today's 5 cm; `depthM` drives it in session 2).
- **Axial focus (D3):** `axial − aperture.x` in `surfaceShader.ts`, `relLen · cosAngle − near` in
  `beamShaders.ts`.
- **Symmetric defocus (D4):** `beamMask`'s `blur` argument and the GLSL twin; `beamHardness` no
  longer folds focus into `soft`; `writeLightRow`'s bound, `composeBeamHull`'s radius and
  `regionShadowMask`'s cone widen by `FOCUS_SPREAD_MAX` for heads with a focus channel.
  `FOCUS_SPREAD_MAX` is tuned by eye in the focus harness.
- **Re-tune check:** `?profileHarness=focus` (the Revolution at 24 m) still reads sharp on the wall
  and soft at ±3 m; update `archetype.test.ts`'s pins if the numbers move.
- **Harness:** a drape scene in `profileHarness.ts` — a black backcloth, a pair of tabs and a
  column under one spot — for sweeping pan by hand.
- **Tests:** every part shape's collider contains its drawn relief within its skin (a node test
  over `partGeometry`'s vertices and `partBox`); `packLanding` shifts by skin; the mask twin's
  half-brightness point sits at the field edge for every blur, reaches past it when blurred, and is
  unchanged at blur 0; axial focus in the twins.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"Light lands through one surface shader" (the
  skin, the landing bullet) and §"Focus" (axial, symmetric, the spread cap).

### Session 2 — materials (frontend)

- **Exposure (D6):** drop `uReflectFloor`; raise the gain; a luminance roll-off that keeps chroma.
  Retune `POOL_SCALE`, `VOL_GAIN` and the fills together so the prototype's balance between pools,
  haze and housings holds. `litByFill` follows the same curve.
- **Pleats (D2, D7):** `depthM` drives depth and fullness (1.5–2×), the pitch varies by a seeded
  noise, and the surface material gets the pleat's frame and parameters as uniforms for the
  analytic fold shadow and a trough darkening on the ambient.
- **Harness:** a raking-light drape scene, a floor seen from the house, and a white cyc beside black
  serge.
- **Tests:** `stageLook.test.ts`'s roll-off pins move with D6; the fold shadow's twin.
- **Docs:** §"Light lands through one surface shader" (the colour bullet).

### Session 3 — box occlusion (frontend)

- **Colliders as a texture:** centre, half-extents, yaw and skin, two texels each.
- **Per-light lists:** the CPU culls each lit light's cone against the colliders
  (`coneReachesSphere` is the precedent) into a second texture of indices, capped per light; a light
  that overflows its cap falls back to its landing planes.
- **Surface shader (D8):** after the mask, the segment from fragment to apex is tested against the
  light's colliders (`rayObbT` with a `tMax`), skipping any box the fragment sits inside within its
  skin. `behindLanding` leaves the surface shader.
- **Haze:** keeps `aBeamLand` and `edgeLanding`; whether it moves to the boxes is decided by
  measurement.
- **Budget:** measured on SwiftShader, Safari on the operator's Mac and an iPad, as fixture-optics
  session 4 measured the gobo pass; recorded against `FU-MANUAL-STAGE-LIGHT-BUDGET`.
- **Docs:** the occlusion paragraphs; `FU-STAGE-QUALITY-TIER`'s text (boxes cast shadows now; shadow
  maps would be for meshes).

### Session 4 — lobes and gobo blur (frontend)

- **Per-finish lobes:** Oren–Nayar diffuse for rough matte surfaces; the Charlie sheen lobe
  (Estevez & Kulla 2017) for velour and serge; GGX specular with a roughness for floors and paint.
  `PartFinish` gains the parameters, defaulted in `sceneParts.ts` by kind, role and part (P2).
- **Gobo mips:** `goboAtlas.ts` builds its mip chain with a Gaussian or Kaiser filter instead of
  `generateMipmaps`.
- **Docs:** the colour bullet again; §"Gobos on surfaces".

## 6. Migration

None. No stored data changes shape.

## 7. Explicitly out of scope

- Shadow maps — `FU-STAGE-QUALITY-TIER`.
- One-bounce fill from landed pools, cloth weave micro-detail, and a chromatic fringe on profile
  edges: recorded as follow-ups if the sessions leave them wanted (§8).
- A dioptre-shaped blur amount (D5).
- Bloom or any post-processing (`stage-vis-engineering.md` §"No post-processing").
- A per-element material override in the scene document (P2).

## 8. Follow-ups to record

Added to `followups.md` by the session that makes them concrete, not before:

- one-bounce fill (a virtual light at each landed pool, coloured by the hit finish);
- cloth micro-detail;
- a chromatic fringe on imaging families' penumbra.

## 9. Verification

- **Session 1:** the drape harness swept by hand from pan 30 to 50 shows a pool that slides with no
  jump; the column is lit across its face; a 50° profile focused on a square wall has a hard edge;
  the focus harness still reads at 24 m.
- **Session 2:** black serge reads dark but clearly lit; a red drape's pool is red; raking light
  bands a drape; the Commemoration Hall's FOH view keeps its overall balance.
- **Session 3:** a flat in front of a wall throws a shadow on it; the frame budget holds on the
  three machines.
- **Session 4:** the stage floor shows the rig's reflection from the house; a gobo racked through
  focus blurs without blocks or pulsing.

## 10. Open questions

1. **Exposure:** one realistic look, or a View menu choice between *Realistic* and *Readable* if
   dark venues become hard to plot in?
2. **Cylinders between sessions 1 and 3:** is lighting a little of what sits just behind a column
   acceptable for that stretch?
3. **Order of sessions 2 and 3:** materials first (the larger visible gain once session 1 lands) is
   the proposal.
