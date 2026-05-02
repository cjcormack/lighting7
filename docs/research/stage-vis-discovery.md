# 3D Stage Visualiser — Discovery Notes

**Project:** lighting7 / lighting-react  
**Date:** May 2026  
**Scope:** Spike to evaluate tooling and architecture for a ChamSys MagicVis-style 3D stage view embedded in the React app, driven by live DMX data.

---

## Objective

Add a real-time 3D stage visualisation view to the lighting control app. Requirements:

- Runs inside the existing React app (not a separate window or external tool)
- Renders fixtures, beam cones, and floor pools with convincing lighting aesthetics
- Driven by decoded DMX data refreshed at ~40ms intervals
- Supports moving heads (pan/tilt) and colour mixing (RGB/CMY)
- Navigable 3D viewport (orbit, pan, zoom)

---

## Tooling Decision

### Recommended stack

| Package | Role |
|---|---|
| `@react-three/fiber` | React renderer for Three.js — declarative JSX scene graph |
| `@react-three/drei` | Helpers: `OrbitControls`, `SpotLight`, `Grid`, `GizmoHelper` |
| `@react-three/postprocessing` | Post-processing pipeline wrapping the `postprocessing` library |
| `three` | Underlying 3D engine |

### Why R3F over alternatives

- **Babylon.js** — more capable in some areas but not React-idiomatic; heavier dependency for this use case.
- **A-Frame** — VR/WebXR focused, not suited to a control surface embedded in a React app.
- **Spline** — design tool output, not programmatically driven.

R3F lets the scene be expressed in JSX and integrates naturally with React component lifecycle and hooks, while still giving direct access to the Three.js object graph when needed for performance-critical updates.

---

## Visual Architecture

Three elements combine to produce the theatrical lighting look:

### 1. Beam cone geometry

Two concentric `ConeGeometry` meshes per fixture — an outer and an inner core:

```javascript
// Outer cone
new THREE.ConeGeometry(beamRadius, beamLength, 48, 1, true)
// MeshBasicMaterial, additive blending, opacity ~0.08

// Inner core (narrower, slightly brighter)
new THREE.ConeGeometry(beamRadius * 0.4, beamLength, 48, 1, true)
// MeshBasicMaterial, additive blending, opacity ~0.14
```

Cone apex sits at the fixture lens position; the base projects toward the floor. Orientation is computed via `Quaternion.setFromUnitVectors` from the default `+Y` axis to the beam direction.

### 2. Floor pool

Two overlapping `CircleGeometry` meshes at `y = 0`:

- Outer: fixture colour, additive blending, opacity ~0.33
- Inner: white, additive blending, opacity ~0.22 (simulates hot centre)

Position is the floor intersection of the beam ray: `t = -fixtureY / dir.y`, `hit = fixturePos + t * dir`.

### 3. Bloom post-processing

`UnrealBloomPass` (via `@react-three/postprocessing`) is essential — without it the scene looks flat. Effective settings found during prototyping:

```jsx
<Bloom luminanceThreshold={0.15} intensity={1.7} radius={0.5} />
```

This is what makes lens heads glow and beams appear volumetric. In the browser prototype (where `EffectComposer` was unavailable), glow sprites — `THREE.Sprite` with a canvas-generated radial gradient and additive blending — were used as a substitute.

---

## Moving Head Implementation

### Fixture geometry

Each fixture has:

- **Clamp** — static `BoxGeometry` attached to truss, does not move
- **Head group** — `THREE.Group` positioned at the fixture origin; rotates to track beam direction
  - Yoke arms (two narrow boxes)
  - Head body (elongated box — tilt is visible when this rotates)
  - Lens disc (`CircleGeometry`, emissive colour material, faces local `-Y`)

The head group rotation uses:

```javascript
headGroup.quaternion.setFromUnitVectors(
  new THREE.Vector3(0, -1, 0),  // default: head points down
  beamDirection                  // target: beam direction unit vector
);
```

### Pan/tilt to beam direction

DMX pan and tilt values (decoded to degrees) are converted to a world-space direction vector. Pan is a rotation around world Y; tilt is a rotation around the fixture's local X axis after pan is applied. `YXZ` Euler order is correct for this:

```javascript
function panTiltToDir(panDeg, tiltDeg) {
  const pan  = THREE.MathUtils.degToRad(panDeg - 270); // centre at 0
  const tilt = THREE.MathUtils.degToRad(tiltDeg);

  return new THREE.Vector3(0, -1, 0)
    .applyEuler(new THREE.Euler(tilt, pan, 0, 'YXZ'));
}
```

The resulting direction is used to update the head group quaternion, cone orientation, spotlight target, and floor pool position.

### Update function

All dynamic elements for a fixture are updated by a single `updateFixtureTarget(fx, targetPoint)` call:

```javascript
function updateFixtureTarget(fx, tgtP) {
  const dir = tgtP.clone().sub(fx.fxPos).normalize();

  // Floor hit
  const t   = -fx.fxPos.y / dir.y;
  const hit = new THREE.Vector3(
    fx.fxPos.x + t * dir.x, 0, fx.fxPos.z + t * dir.z
  );

  fx.headGroup.quaternion.setFromUnitVectors(DOWN, dir);
  fx.lensGlow.position.copy(fx.fxPos).addScaledVector(dir, 0.38);

  const mid = fx.fxPos.clone().addScaledVector(dir, fx.bLen * 0.5);
  const q   = new THREE.Quaternion().setFromUnitVectors(UP, dir.clone().negate());
  fx.cones.forEach(({ mesh }) => { mesh.position.copy(mid); mesh.quaternion.copy(q); });

  fx.pools.forEach(({ mesh }) => { mesh.position.x = hit.x; mesh.position.z = hit.z; });
  fx.poolGlow.position.copy(hit);

  fx.spot.target.position.copy(hit);
  fx.spot.target.updateMatrixWorld();
}
```

---

## Backend payload (formatVersion 3)

The lighting7 backend exposes everything the visualiser needs to lay out a
scene; the only thing that flows over WebSocket on the 40ms tick is decoded
DMX state. Static rig geometry comes through REST and is updated only when
the rig changes.

### Coordinate system

Right-handed, **Z-up**, units = **metres**, FOH-relative; origin = stage
centre on the deck. See `docs/fixtures-engineering.md` for the full table.

| Axis | + direction      | Zero            |
|------|------------------|-----------------|
| X    | audience-right   | centre stage    |
| Y    | upstage          | downstage edge  |
| Z    | up               | deck level      |

R3F is Y-up by default. Either:

- swap the relevant components when feeding three.js (`new Vector3(x, z, y)`),
  or
- set `THREE.Object3D.DEFAULT_UP = new Vector3(0, 0, 1)` and adjust camera
  framing to match.

### Per-patch geometry — `FixturePatchDto`

Returned by `GET /api/rest/project/{projectId}/patches`:

```typescript
interface FixturePatchDto {
  id: number;
  key: string;            // stable per-rig identifier; matches fixtureId in the live DMX feed
  displayName: string;
  fixtureTypeKey: string; // looks up channelCount + capability flags from /fixture/types
  // …channel + group fields…

  // Patch-frame offsets — interpreted in the rigging's local frame if riggingUuid is set,
  // else in world coordinates.
  stageX: number | null;
  stageY: number | null;
  stageZ: number | null;
  baseYawDeg: number | null;   // body rotation about Z (up)
  basePitchDeg: number | null; // body rotation about X
  riggingUuid: string | null;  // FK to a Rigging if this fixture hangs off one

  // Pre-composed world position — saves the renderer doing the rigging-frame math.
  worldPositionX: number | null;
  worldPositionY: number | null;
  worldPositionZ: number | null;

  beamAngleDeg: number | null;
  gelCode: string | null;
}
```

For a moving head, `baseYawDeg` / `basePitchDeg` is the **yoke mounting**
angle, not the live aim. Live aim = base composed with the runtime
`pan` / `tilt` from the DMX state stream.

### Riggings — `RiggingDto`

Returned by `GET /api/rest/project/{projectId}/riggings`:

```typescript
interface RiggingDto {
  id: number;
  uuid: string;
  name: string;            // operator-facing label (e.g. "FOH", "LX1", "Boom-SL")
  kind: string | null;     // advisory: TRUSS, BAR, BOOM, PIPE, FLOOR_STAND, OTHER
  positionX: number | null; // world coordinates of the rigging's local origin
  positionY: number | null;
  positionZ: number | null;
  yawDeg: number | null;   // rotation about Z
  pitchDeg: number | null; // rotation about X
  rollDeg: number | null;  // rotation about Y
  sortOrder: number;
}
```

Composition order: yaw → pitch → roll (intrinsic Tait-Bryan). For typical
flown trusses with only `yawDeg` set this collapses to a single 2D rotation.

The visualiser can either:

- ignore riggings and use each patch's `worldPositionX/Y/Z` (already
  composed) plus a per-fixture mesh — simplest path, works for the spike;
- or render rigging hardware (truss segments, boom arms) as their own
  meshes positioned by `positionX/Y/Z` + orientation, then place fixtures at
  `(stageX, stageY, stageZ)` in the rigging's local frame — needed once the
  rendered truss should look like the rigged truss.

### Stage shape — `StageRegionDto`

Returned by `GET /api/rest/project/{projectId}/stageRegions`:

```typescript
interface StageRegionDto {
  id: number;
  uuid: string;
  name: string;
  centerX: number | null;
  centerY: number | null;
  centerZ: number | null;  // top of platform: 0 = deck level, > 0 raised
  widthM:  number | null;  // X extent (after yaw)
  depthM:  number | null;  // Y extent (after yaw)
  heightM: number | null;  // Z extent (platform thickness)
  yawDeg:  number | null;
  sortOrder: number;
}
```

Multiple regions describe thrusts, raised platforms, pits, and multi-level
stages. The visualiser unions the rectangles to get the playable surface.
When the regions list is empty, fall back to the project's
`stageWidthM` / `stageDepthM` / `stageHeightM` bounding box for scene extents.

This addresses the "Multiple trusses" item in the Open Questions section
below — riggings carry full pose, fixtures attach via `riggingUuid`.

---

## DMX Data Integration

### Data flow

```
DMX engine  ──40ms──▶  applyFixtureState(decodedState)   ← mutates Three.js objects
                                                               (no React re-renders)
render loop ──16ms──▶  renderer.render(scene, camera)
```

The 40ms data tick and the 60fps render loop are fully decoupled. The render loop reads whatever state was last written; there is no synchronisation needed between them.

### Decoded fixture state

The visualiser consumes a decoded representation — not raw DMX bytes:

```typescript
interface FixtureState {
  fixtureId: string;
  pan:    number;  // degrees, e.g. 0–540
  tilt:   number;  // degrees, e.g. 0–135
  r:      number;  // 0–255
  g:      number;  // 0–255
  b:      number;  // 0–255
  dimmer: number;  // 0–255
}
```

### 40ms update callback

```javascript
function applyFixtureState({ fixtureId, pan, tilt, r, g, b, dimmer }) {
  const fx = fixtureMap.get(fixtureId);
  if (!fx) return;

  const dir = panTiltToDir(pan, tilt);
  updateFixtureTarget(fx, fx.fxPos.clone().addScaledVector(dir, 10));
  updateFixtureColor(fx, new THREE.Color(r/255, g/255, b/255), dimmer/255);
}

function updateFixtureColor(fx, col, intensity) {
  fx.lensMat.color.copy(col);
  fx.cones.forEach(c => c.mat.color.copy(col));
  fx.pools[0].mat.color.copy(col);
  fx.lensGlow.material.color.copy(col);
  fx.poolGlow.material.color.copy(col);
  fx.spot.color.copy(col);
  fx.spot.intensity          = 4.5 * intensity;
  fx.cones[0].mat.opacity    = 0.08 * intensity;
  fx.cones[1].mat.opacity    = 0.15 * intensity;
  fx.pools[0].mat.opacity    = 0.33 * intensity;
}
```

### R3F pattern (production)

In the React app, fixture state is held in a Zustand store. The 40ms DMX tick writes to the store; `useFrame` reads from it and mutates Three.js objects imperatively — bypassing React's render cycle entirely:

```jsx
// Store — updated by DMX tick
const useFixtureStore = create(set => ({
  states: {},
  apply:  states => set({ states }),
}));

// R3F component — reads store in useFrame, no re-renders
function FixtureModel({ id, fxPos }) {
  const headRef = useRef();
  const spotRef = useRef();

  useFrame(() => {
    const state = useFixtureStore.getState().states[id];
    if (!state) return;

    const dir = panTiltToDir(state.pan, state.tilt);
    headRef.current.quaternion.setFromUnitVectors(DOWN, dir);
    spotRef.current.target.position.copy(fxPos).addScaledVector(dir, 10);
    spotRef.current.color.setRGB(state.r/255, state.g/255, state.b/255);
    spotRef.current.intensity = 4.5 * (state.dimmer / 255);
  });

  return (
    <group>
      <group ref={headRef} position={fxPos}>
        {/* yoke, head body, lens */}
      </group>
      <spotLight ref={spotRef} castShadow />
      {/* cones, pools */}
    </group>
  );
}
```

React state / re-renders are never used for per-frame visual updates. Only structural changes (adding/removing fixtures from the rig) go through React.

---

## Performance Notes

- At 40ms × 6–30 fixtures, the pan/tilt → direction vector computation and quaternion math is negligible.
- For rigs of 100+ fixtures, compute the direction vector once on the 40ms tick and cache it rather than recomputing in `useFrame`.
- Shadow maps are expensive: limit `castShadow` to key fixtures or disable for fixtures outside camera frustum. `shadow.mapSize.set(512, 512)` is sufficient for a stage overview; drop to 256 if frame time becomes an issue.
- Cone geometry (`ConeGeometry` with 48 segments) is created once per fixture and reused — only position and quaternion are mutated per frame.
- `depthWrite: false` on all additive-blended materials (cones, pools, glows) prevents z-fighting and is required for correct transparency compositing.

---

## Open Questions / Next Steps

- **Gobo projection** — requires a custom `ShaderMaterial` or drei's `SpotLight` with shadow map gobo texture. Not in scope for this spike.
- **Zoom** — beam angle changes cone radius. Currently fixed at 13° half-angle. Needs `bRad` and cone geometry to update when zoom DMX channel changes.
- **Multiple trusses** — architecture supports it; fixture positions are arbitrary world-space vectors.
- **Top-down 2D view** — orthographic camera looking straight down; useful as a secondary panel alongside the 3D view. Camera switch only, no additional geometry needed.
- **Fixture library** — currently all fixtures are generic moving heads. Needs a fixture type system to handle PAR cans (no pan/tilt), strobes, LED battens, etc.
- **Pan/tilt inversion and range** — different fixture models have different pan ranges and some invert tilt. These should be per-fixture-type parameters, not hardcoded.
