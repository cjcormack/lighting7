// Profiling harness for the Stage 3D view.
//
// Activate by adding `?profileHarness=1` to the URL. The harness replaces the
// project's real patches/regions/riggings with a deterministic high-load
// synthetic scene (50 fixtures × 16 regions × 8 riggings) so GPU/CPU profiling
// runs against a representative worst-case layout. Consumed by `useStageData`.
//
// `?profileHarness=focus` swaps in the **focus scene** instead (fixture-optics
// plan session 1): the scene the depth-of-field constants (`DEPTH_OF_FIELD` in
// `bodies/archetype.ts`) were tuned in. See [FOCUS_HARNESS_HEADS].
//
// `?profileHarness=drape` swaps in the **drape scene** (stage-light plan session
// 1): one spot over a black backcloth, a pair of tabs and a column, for sweeping
// pan by hand. See [DRAPE_HARNESS_CHANNELS].
//
// `?profileHarness=rake`, `=floor` and `=cyc` are the **material scenes**
// (stage-light plan session 2), where the exposure and the folds are judged: a
// drape lit square on, at 45° and raking; three floor finishes seen from the
// house; and a white cyc, black serge and a red drape side by side under one
// light each. Their lanterns are fixed and at full, so nothing need be written.
// See [buildMaterialHarness].

import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageElementDto } from '../../api/stageElementApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { Fixture, FixtureTypeInfo, PropertyDescriptor } from '../../store/fixtures'

const HARNESS_TYPE_KEY = '__profileHarness_type__'

/** Which synthetic scene: the load profile (`=1`), the focus scene (`=focus`), the drape scene (`=drape`) or a material scene. */
export type HarnessMode = 'load' | 'focus' | 'drape' | MaterialHarness

/** The material scenes (stage-light plan session 2). */
export type MaterialHarness = 'rake' | 'floor' | 'cyc'
const MATERIAL_HARNESSES: readonly string[] = ['rake', 'floor', 'cyc'] satisfies MaterialHarness[]

export function harnessMode(): HarnessMode | null {
  if (typeof window === 'undefined') return null
  try {
    const flag = new URLSearchParams(window.location.search).get('profileHarness')
    if (flag === '1') return 'load'
    if (flag === 'focus' || flag === 'drape') return flag
    return flag != null && MATERIAL_HARNESSES.includes(flag) ? (flag as MaterialHarness) : null
  } catch {
    return null
  }
}

export function isHarnessActive(): boolean {
  return harnessMode() != null
}

export interface HarnessData {
  patches: FixturePatch[]
  regions: StageRegionDto[]
  riggings: RiggingDto[]
  syntheticFixture: Fixture
  syntheticType: FixtureTypeInfo
  /** A fixture of its own for a patch that needs one (the focus scene's heads each have their own
   *  focus channel); every other patch is [syntheticFixture]. */
  fixtureFor?: ReadonlyMap<string, Fixture>
  /** The scene the harness draws in place of the project's, where it has one (the drape scene). */
  elements?: StageElementDto[]
}

// Stage is W (X right) × D (Y upstage) × H (Z up) in metres.
// Beam-shaping channels for the synthetic fixture, so the harness exercises
// the gobo/focus/prism code paths rather than silently skipping them (every
// findXxx returned undefined when `properties` was empty). The gobo option at
// level 0 carries a pattern, so all 50 fixtures sample the gobo texture *at
// rest* — the worst case for the pool shaders. The prism is out at level 0;
// engage it while profiling by writing DMX 1/4 ≥ 8 (all fixtures share the
// channel refs, so one write drives the whole rig).
function makeBeamShapingProperties(): PropertyDescriptor[] {
  const ch = (channelNo: number) => ({ universe: 1, channelNo })
  return [
    {
      type: 'setting',
      name: 'gobo',
      displayName: 'Gobo',
      category: 'gobo',
      channel: ch(1),
      options: [
        { name: 'BREAKUP', level: 0, displayName: 'Breakup', gobo: 'breakup' },
        { name: 'DOTS', level: 64, displayName: 'Dots', gobo: 'dots' },
        { name: 'OPEN', level: 192, displayName: 'Open' },
      ],
    },
    {
      type: 'slider',
      name: 'goboRotation',
      displayName: 'Gobo rotation',
      category: 'gobo_rotation',
      channel: ch(2),
      min: 0,
      max: 255,
    },
    {
      type: 'slider',
      name: 'focus',
      displayName: 'Focus',
      category: 'focus',
      channel: ch(3),
      min: 0,
      max: 255,
    },
    {
      type: 'setting',
      name: 'prism',
      displayName: 'Prism',
      category: 'prism',
      channel: ch(4),
      options: [
        { name: 'OPEN', level: 0, displayName: 'Open' },
        { name: 'PRISM', level: 8, displayName: 'Prism', prismFacets: 3 },
      ],
    },
    {
      type: 'slider',
      name: 'prismRotation',
      displayName: 'Prism rotation',
      category: 'prism_rotation',
      channel: ch(5),
      min: 0,
      max: 255,
    },
  ]
}

export function buildHarness(
  stageW: number,
  stageD: number,
  stageH: number,
  mode: HarnessMode = harnessMode() ?? 'load',
): HarnessData {
  if (mode === 'focus') return buildFocusHarness(stageD)
  if (mode === 'drape') return buildDrapeHarness()
  if (mode === 'rake' || mode === 'floor' || mode === 'cyc') return buildMaterialHarness(mode)
  const riggings = makeRiggings(stageW, stageD, stageH)
  const regions = makeRegions(stageW, stageD)
  const patches = makePatches(stageW, stageD, stageH, riggings)
  const beamShaping = makeBeamShapingProperties()
  const syntheticType: FixtureTypeInfo = {
    typeKey: HARNESS_TYPE_KEY,
    manufacturer: 'Harness',
    model: 'Test Spot',
    modeName: 'Default',
    channelCount: 4,
    isRegistered: true,
    capabilities: [],
    properties: beamShaping,
    elementGroupProperties: null,
    acceptsBeamAngle: true,
    acceptsGel: false,
    kind: 'MOVING_HEAD',
  }
  const syntheticFixture: Fixture = {
    key: HARNESS_TYPE_KEY + '__fx',
    name: 'Harness Fixture',
    typeKey: HARNESS_TYPE_KEY,
    universe: 1,
    firstChannel: 1,
    channelCount: 4,
    channels: [],
    properties: beamShaping,
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
  }
  return { patches, regions, riggings, syntheticFixture, syntheticType }
}

// 8 riggings: 3 truss bars across front/mid/back + 2 side trusses + 2 short
// booms + 1 centre drop. Lengths/heights varied for realism.
function makeRiggings(stageW: number, stageD: number, stageH: number): RiggingDto[] {
  const trussHeight = stageH * 0.85
  const midHeight = stageH * 0.7
  const lowBoomHeight = stageH * 0.5
  const r = (
    id: number,
    name: string,
    px: number,
    py: number,
    pz: number,
    length: number,
    yawDeg = 0,
  ): RiggingDto => ({
    id,
    uuid: `harness-rig-${id}`,
    name,
    kind: 'truss',
    positionX: px,
    positionY: py,
    positionZ: pz,
    yawDeg,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: length,
    sortOrder: id,
  })
  return [
    r(1, 'FOH Truss', 0, stageD * 0.05, trussHeight, stageW * 0.9),
    r(2, 'Mid Truss', 0, stageD * 0.45, trussHeight, stageW * 0.9),
    r(3, 'Back Truss', 0, stageD * 0.85, midHeight, stageW * 0.9),
    r(4, 'SR Side', stageW * 0.45, stageD * 0.5, midHeight, stageD * 0.6, 90),
    r(5, 'SL Side', -stageW * 0.45, stageD * 0.5, midHeight, stageD * 0.6, 90),
    r(6, 'SR Boom', stageW * 0.4, stageD * 0.25, lowBoomHeight, 1.2, 0),
    r(7, 'SL Boom', -stageW * 0.4, stageD * 0.25, lowBoomHeight, 1.2, 0),
    r(8, 'Centre Drop', 0, stageD * 0.6, midHeight, 2.0),
  ]
}

// 16 regions: 4×4 grid of risers/blocks, varied sizes so shadow tests fire
// across the scene.
function makeRegions(stageW: number, stageD: number): StageRegionDto[] {
  const out: StageRegionDto[] = []
  const cols = 4
  const rows = 4
  for (let i = 0; i < 16; i++) {
    const col = i % cols
    const row = Math.floor(i / cols)
    const cx = -stageW * 0.35 + (col / (cols - 1)) * stageW * 0.7
    const cy = stageD * 0.1 + (row / (rows - 1)) * stageD * 0.75
    const isBig = col === row
    const w = isBig ? 2.4 : 1.2
    const d = isBig ? 1.6 : 0.9
    const h = isBig ? 0.6 : 0.3 + (i % 3) * 0.15
    out.push({
      id: i + 1,
      uuid: `harness-region-${i + 1}`,
      name: `Riser ${i + 1}`,
      centerX: cx,
      centerY: cy,
      // `centerZ` is a riser's top surface; standing on the deck, that is its height.
      centerZ: h,
      widthM: w,
      depthM: d,
      heightM: h,
      yawDeg: (i % 2) * 15,
      sortOrder: i,
    })
  }
  return out
}

// 50 fixtures: 30 truss-mounted (6 across each of 5 truss-style riggings) +
// 20 perimeter floor/boom-mounted aimed at stage centre.
//
// Note: useNormalizedIntensity with no dimmer property returns 1, so all 50
// render at full. That's the deliberate worst-case for the overdraw profile.
function makePatches(
  stageW: number,
  stageD: number,
  stageH: number,
  riggings: RiggingDto[],
): FixturePatch[] {
  const patches: FixturePatch[] = []
  let id = 1
  const trussRigs = riggings.slice(0, 5)
  const fixturesPerTruss = 6 // 5 × 6 = 30

  trussRigs.forEach((rig, rigIdx) => {
    const len = rig.lengthM ?? 3
    for (let i = 0; i < fixturesPerTruss; i++) {
      const t = (i + 0.5) / fixturesPerTruss
      const localX = (t - 0.5) * len * 0.9
      const panBase = (i - fixturesPerTruss / 2) * 12 + rigIdx * 8
      const tiltBase = 35 + ((i + rigIdx) % 4) * 12
      patches.push(makePatch(id++, rig, localX, panBase, tiltBase))
    }
  })

  let floorIdx = 0
  while (patches.length < 50) {
    const angle = (floorIdx / 20) * Math.PI * 2
    const radius = Math.min(stageW, stageD) * 0.45
    const x = Math.cos(angle) * radius
    const y = stageD * 0.5 + Math.sin(angle) * radius * 0.7
    const z = floorIdx % 3 === 0 ? stageH * 0.3 : 0.05
    const panBase = (angle * 180) / Math.PI + 180
    const tiltBase = 30 + (floorIdx % 5) * 8
    patches.push({
      id: id++,
      key: `harness-patch-${id}`,
      displayName: `H${id - 1}`,
      fixtureTypeKey: HARNESS_TYPE_KEY,
      startChannel: 1 + (id - 1) * 4,
      channelCount: 4,
      manufacturer: 'Harness',
      model: 'Test Spot',
      modeName: 'Default',
      universe: 1,
      subnet: 0,
      sortOrder: id,
      groups: [],
      stageX: x,
      stageY: y,
      stageZ: z,
      baseYawDeg: panBase,
      basePitchDeg: tiltBase,
      riggingUuid: null,
      beamAngleDeg: 22 + (floorIdx % 4) * 6,
      gelCode: null,
      kindOverride: null,
      stageHidden: false,
    })
    floorIdx++
  }
  return patches
}

function makePatch(
  id: number,
  rig: RiggingDto,
  localX: number,
  panBase: number,
  tiltBase: number,
): FixturePatch {
  return {
    id,
    key: `harness-patch-${id}`,
    displayName: `H${id}`,
    fixtureTypeKey: HARNESS_TYPE_KEY,
    startChannel: 1 + (id - 1) * 4,
    channelCount: 4,
    manufacturer: 'Harness',
    model: 'Test Spot',
    modeName: 'Default',
    universe: 1,
    subnet: 0,
    sortOrder: id,
    groups: [],
    stageX: localX,
    stageY: 0,
    stageZ: 0,
    baseYawDeg: panBase,
    basePitchDeg: tiltBase,
    riggingUuid: rig.uuid,
    beamAngleDeg: 18 + (id % 5) * 5,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
  }
}

// — the focus scene ——————————————————————————————————————————————————————

const FOCUS_TYPE_KEY = '__profileHarness_focus_type__'

/** How far the focus scene's heads throw: the Commemoration Hall's balcony to its back wall. */
export const FOCUS_HARNESS_THROW_M = 24

/**
 * The focus scene (`?profileHarness=focus`, fixture-optics plan session 1): three Source Four
 * Revolutions — a `mover:profile` with the Revolution's declared 2–40 m focus and no depth of field
 * of its own, so the family's constant draws it — on a balcony at the Commemoration Hall's height,
 * each throwing [FOCUS_HARNESS_THROW_M] straight upstage at the stage's back wall, side by side. Each
 * has its own focus channel on universe 1, at nothing until written; write each `dmx` here (the
 * shared vector's levels for those distances, `src/test/resources/stage/focusInverse.fixture.json`)
 * and the wall shows a head focused 3 m short of it, one on it, and one 3 m past it (the bytes draw
 * 21.1, 24.0 and 27.6 m: a DMX step is over a metre out there). Write the
 * middle head's channel a step either side (245, 247) to see what one DMX step does at 24 m. The
 * Front camera looks at the wall square on. The constants are judged here, by eye, against the
 * plan's check: soft at ±3 m, sharp on the wall.
 */
export const FOCUS_HARNESS_HEADS: ReadonlyArray<{ key: string; x: number; focusM: number; channelNo: number; dmx: number }> = [
  { key: 'focus-short', x: -3.4, focusM: 21, channelNo: 21, dmx: 243 },
  { key: 'focus-wall', x: 0, focusM: 24, channelNo: 22, dmx: 246 },
  { key: 'focus-long', x: 3.4, focusM: 27, channelNo: 23, dmx: 249 },
]

/** The balcony's height: the Revolutions hang at z = 2.8 m (fixture-optics design record). */
const FOCUS_HARNESS_Z = 2.8

function buildFocusHarness(stageD: number): HarnessData {
  const focusProperty = (channelNo: number): PropertyDescriptor => ({
    type: 'slider',
    name: 'focus',
    displayName: 'Focus',
    category: 'focus',
    channel: { universe: 1, channelNo },
    min: 0,
    max: 255,
    focusNearM: 2,
    focusFarM: 40,
  })
  const syntheticType: FixtureTypeInfo = {
    typeKey: FOCUS_TYPE_KEY,
    manufacturer: 'Harness',
    model: 'Revolution',
    modeName: 'Focus',
    channelCount: 1,
    isRegistered: true,
    capabilities: [],
    properties: [focusProperty(FOCUS_HARNESS_HEADS[0].channelNo)],
    elementGroupProperties: null,
    acceptsBeamAngle: true,
    acceptsGel: false,
    kind: 'PROFILE',
    body: { archetype: 'mover', head: 'profile', lensDiameterM: 0.15 },
  }
  const fixtureFor = new Map<string, Fixture>()
  const patches: FixturePatch[] = FOCUS_HARNESS_HEADS.map((head, i) => {
    fixtureFor.set(head.key, {
      key: head.key,
      name: `Rev · focus ${head.focusM} m`,
      typeKey: FOCUS_TYPE_KEY,
      universe: 1,
      firstChannel: head.channelNo,
      channelCount: 1,
      channels: [],
      properties: [focusProperty(head.channelNo)],
      capabilities: [],
      groups: [],
      compatibleLookIds: [],
    })
    return {
      id: i + 1,
      key: head.key,
      displayName: `Focus ${head.focusM} m`,
      fixtureTypeKey: FOCUS_TYPE_KEY,
      startChannel: head.channelNo,
      channelCount: 1,
      manufacturer: 'Harness',
      model: 'Revolution',
      modeName: 'Focus',
      universe: 1,
      subnet: 0,
      sortOrder: i + 1,
      groups: [],
      stageX: head.x,
      stageY: stageD - FOCUS_HARNESS_THROW_M,
      stageZ: FOCUS_HARNESS_Z,
      baseYawDeg: 0,
      // A mover's beam runs up its body at rest; −90° lays it level, aimed upstage.
      basePitchDeg: -90,
      riggingUuid: null,
      // Narrow, so the three pools sit side by side on a 10 m stage's wall rather than overlapping.
      beamAngleDeg: 6,
      gelCode: null,
      kindOverride: null,
      stageHidden: false,
    }
  })
  return {
    patches,
    regions: [],
    riggings: [],
    syntheticFixture: fixtureFor.get(FOCUS_HARNESS_HEADS[1].key)!,
    syntheticType,
    fixtureFor,
  }
}

// — the drape scene ——————————————————————————————————————————————————————

const DRAPE_TYPE_KEY = '__profileHarness_drape_type__'
const DRAPE_PATCH_KEY = 'drape-spot'

/**
 * The drape scene's channels on universe 1: a Robe ColorSpot 575's pan and tilt travel (530° and
 * 280°) and a focus over the Revolution's 2–40 m, so the bytes the Commemoration Hall's ADV1 head
 * was reported at land where they did there — pan 40 on the backcloth, 45 on the stage-left tab's
 * onstage edge, both at tilt 204.
 */
export const DRAPE_HARNESS_CHANNELS = { pan: 31, tilt: 32, focus: 33 } as const

/**
 * The drape scene (`?profileHarness=drape`, stage-light plan session 1): one spot hung as the
 * Commemoration Hall's ADV1 Robe is — on a bar 1.45 m downstage of the setting line at 3.15 m,
 * 2 m stage left — over a black backcloth 6.4 m upstage, a pair of tabs part drawn at 3 m and a
 * column between. Write the pan channel by hand to sweep the pool across the cloth, the tab's edge
 * and the column; the spot carries no dimmer, so it is always at full.
 */
function buildDrapeHarness(): HarnessData {
  const ch = (channelNo: number) => ({ universe: 1, channelNo })
  const properties: PropertyDescriptor[] = [
    {
      type: 'slider', name: 'pan', displayName: 'Pan', category: 'pan', axis: 'PAN',
      channel: ch(DRAPE_HARNESS_CHANNELS.pan), min: 0, max: 255, degMin: 0, degMax: 530,
    },
    {
      type: 'slider', name: 'tilt', displayName: 'Tilt', category: 'tilt', axis: 'TILT',
      channel: ch(DRAPE_HARNESS_CHANNELS.tilt), min: 0, max: 255, degMin: 0, degMax: 280,
    },
    {
      type: 'slider', name: 'focus', displayName: 'Focus', category: 'focus',
      channel: ch(DRAPE_HARNESS_CHANNELS.focus), min: 0, max: 255, focusNearM: 2, focusFarM: 40,
    },
  ]
  const syntheticType: FixtureTypeInfo = {
    typeKey: DRAPE_TYPE_KEY,
    manufacturer: 'Harness',
    model: 'Drape spot',
    modeName: 'Drape',
    channelCount: 3,
    isRegistered: true,
    capabilities: [],
    properties,
    elementGroupProperties: null,
    acceptsBeamAngle: true,
    acceptsGel: false,
    kind: 'MOVING_HEAD',
    body: { archetype: 'mover', head: 'spot' },
  }
  const syntheticFixture: Fixture = {
    key: DRAPE_PATCH_KEY,
    name: 'Drape spot',
    typeKey: DRAPE_TYPE_KEY,
    universe: 1,
    firstChannel: DRAPE_HARNESS_CHANNELS.pan,
    channelCount: 3,
    channels: [],
    properties,
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
  }
  const bar: RiggingDto = {
    id: 1,
    uuid: 'harness-drape-bar',
    name: 'ADV1',
    kind: 'BAR',
    positionX: 0,
    positionY: -1.45,
    positionZ: 3.15,
    yawDeg: 0,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: 6,
    sortOrder: 1,
  }
  const spot: FixturePatch = {
    id: 1,
    key: DRAPE_PATCH_KEY,
    displayName: 'Drape spot',
    fixtureTypeKey: DRAPE_TYPE_KEY,
    startChannel: DRAPE_HARNESS_CHANNELS.pan,
    channelCount: 3,
    manufacturer: 'Harness',
    model: 'Drape spot',
    modeName: 'Drape',
    universe: 1,
    subnet: 0,
    sortOrder: 1,
    groups: [],
    stageX: 2,
    stageY: 0,
    stageZ: -0.5,
    baseYawDeg: 180,
    basePitchDeg: 180,
    riggingUuid: bar.uuid,
    beamAngleDeg: 15,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
  }
  return {
    patches: [spot],
    regions: [],
    riggings: [bar],
    syntheticFixture,
    syntheticType,
    elements: [
      drapeElement(1, 'Back cloth', 0, 6.4, 8, 3.7, { operation: 'DEAD', role: 'BACKCLOTH' }),
      drapeElement(2, 'Tabs', 0, 3, 7.5, 4.2, { operation: 'DRAW', role: 'TABS', states: { open: 0.75 } }),
      {
        ...drapeElement(3, 'Column', 0.8, 4.5, 0.4, 3.5, { shape: 'CYLINDER' }),
        kind: 'OBJECT',
        depthM: 0.4,
        finishColour: '#8a8378',
      },
    ],
  }
}

function drapeElement(
  id: number,
  name: string,
  x: number,
  y: number,
  widthM: number,
  heightM: number,
  params: Record<string, unknown>,
): StageElementDto {
  return {
    id,
    uuid: `harness-drape-${id}`,
    name,
    kind: 'DRAPE',
    layer: 'SET',
    positionX: x,
    positionY: y,
    positionZ: 0,
    yawDeg: 0,
    widthM,
    depthM: 0.1,
    heightM,
    finishColour: '#101012',
    finishPattern: null,
    emissive: false,
    params,
    hidden: false,
    sortOrder: id,
  }
}

// — the material scenes ——————————————————————————————————————————————————

const MATERIAL_TYPE_KEY = '__profileHarness_material_type__'

/** One of a material scene's lanterns: a profile at [from], focused on [at], lighting metres. */
interface MaterialSpot {
  key: string
  from: { x: number; y: number; z: number }
  at: { x: number; y: number; z: number }
  beamDeg: number
}

/**
 * A static lantern's base pose to point it from [from] at [at]: yaw 0 aims at the house and turns
 * anticlockwise from above, and +pitch aims down (`staticHeadQuaternion` in `FixtureModel.tsx`).
 */
export function aimStatic(from: MaterialSpot['from'], at: MaterialSpot['at']): { baseYawDeg: number; basePitchDeg: number } {
  const dx = at.x - from.x
  const dy = at.y - from.y
  const dz = at.z - from.z
  const deg = (r: number) => (r * 180) / Math.PI
  return { baseYawDeg: deg(Math.atan2(dx, -dy)), basePitchDeg: deg(Math.atan2(-dz, Math.hypot(dx, dy))) }
}

/** Serge, the default drape's red and a cyc's white, as `buildDrape` and a hall's soft goods draw them. */
const BLACK_SERGE = '#101012'
const DRAPE_RED = '#3b1219'
const CYC_WHITE = '#e8e6df'

/** Where a material scene's lanterns hang and what they light. */
export function materialScene(mode: MaterialHarness): { spots: MaterialSpot[]; elements: StageElementDto[] } {
  switch (mode) {
    case 'rake': {
      // One black serge cloth 12 m across, 4 m upstage, lit 2 m up at three angles off its normal,
      // each lantern 6 m from its pool: square on, 45° and 75° from stage right.
      const pool = (x: number, offDeg: number, key: string): MaterialSpot => {
        const a = (offDeg * Math.PI) / 180
        return { key, from: { x: x - 6 * Math.sin(a), y: 4 - 6 * Math.cos(a), z: 2 }, at: { x, y: 4, z: 2 }, beamDeg: 10 }
      }
      return {
        spots: [pool(-4, 0, 'rake-front'), pool(0, 45, 'rake-45'), pool(4, 75, 'rake-75')],
        elements: [materialDrape(1, 'Serge', 0, 4, 12, 4, BLACK_SERGE, 0.12, { operation: 'DEAD', role: 'BACKCLOTH' })],
      }
    }
    case 'floor': {
      // Three decks 2.4 m square and 2 cm high across the stage — a black dance floor, the default
      // grey deck and timber boards — each under a spot from a front-of-house bar, 45° up.
      const decks = [
        { x: -3, colour: '#141414', pattern: null, name: 'Dance floor' },
        { x: 0, colour: '#4a443d', pattern: null, name: 'Deck' },
        { x: 3, colour: '#8a6a48', pattern: 'BOARDS', name: 'Timber' },
      ]
      return {
        spots: decks.map((d, i) => ({ key: `floor-${i}`, from: { x: d.x, y: -2, z: 5 }, at: { x: d.x, y: 3, z: 0.02 }, beamDeg: 20 })),
        elements: decks.map((d, i) => ({
          ...materialDrape(i + 1, d.name, d.x, 3, 2.4, 0.02, d.colour, 2.4, {}),
          kind: 'PLATFORM',
          positionZ: 0.02,
          finishPattern: d.pattern,
        })),
      }
    }
    case 'cyc': {
      // A white cyc, black serge and the default drape red, each 3 m wide and 5 m upstage, each under
      // its own spot from a front-of-house bar: the same light on all three.
      const cloths = [
        { x: -3.2, colour: CYC_WHITE, role: 'CYC', depth: 0.05, name: 'Cyc' },
        { x: 0, colour: BLACK_SERGE, role: 'LEG', depth: 0.1, name: 'Serge' },
        { x: 3.2, colour: DRAPE_RED, role: 'LEG', depth: 0.1, name: 'Red drape' },
      ]
      return {
        spots: cloths.map((c, i) => ({ key: `cyc-${i}`, from: { x: c.x, y: -3, z: 5 }, at: { x: c.x, y: 5, z: 2 }, beamDeg: 15 })),
        elements: cloths.map((c, i) => materialDrape(i + 1, c.name, c.x, 5, 3, 4, c.colour, c.depth, { operation: 'DEAD', role: c.role })),
      }
    }
  }
}

/**
 * A material scene (`?profileHarness=rake`, `=floor`, `=cyc`; stage-light plan session 2): the
 * finishes a hall is made of under fixed profiles at full, in white, so the exposure, the colour of
 * a pool and a fold's shadow are judged against one another with nothing written to the desk.
 */
function buildMaterialHarness(mode: MaterialHarness): HarnessData {
  const { spots, elements } = materialScene(mode)
  const syntheticType: FixtureTypeInfo = {
    typeKey: MATERIAL_TYPE_KEY,
    manufacturer: 'Harness',
    model: 'Material profile',
    modeName: 'Fixed',
    channelCount: 1,
    isRegistered: true,
    capabilities: [],
    properties: [],
    elementGroupProperties: null,
    acceptsBeamAngle: true,
    acceptsGel: false,
    kind: 'PROFILE',
    body: { archetype: 'profile' },
  }
  const syntheticFixture: Fixture = {
    key: MATERIAL_TYPE_KEY + '__fx',
    name: 'Material profile',
    typeKey: MATERIAL_TYPE_KEY,
    universe: 1,
    firstChannel: 1,
    channelCount: 1,
    channels: [],
    properties: [],
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
  }
  const patches: FixturePatch[] = spots.map((spot, i) => ({
    id: i + 1,
    key: spot.key,
    displayName: spot.key,
    fixtureTypeKey: MATERIAL_TYPE_KEY,
    startChannel: 1,
    channelCount: 1,
    manufacturer: 'Harness',
    model: 'Material profile',
    modeName: 'Fixed',
    universe: 1,
    subnet: 0,
    sortOrder: i + 1,
    groups: [],
    stageX: spot.from.x,
    stageY: spot.from.y,
    stageZ: spot.from.z,
    ...aimStatic(spot.from, spot.at),
    riggingUuid: null,
    beamAngleDeg: spot.beamDeg,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
  }))
  return { patches, regions: [], riggings: [], syntheticFixture, syntheticType, elements }
}

function materialDrape(
  id: number,
  name: string,
  x: number,
  y: number,
  widthM: number,
  heightM: number,
  finishColour: string,
  depthM: number,
  params: Record<string, unknown>,
): StageElementDto {
  return { ...drapeElement(id, name, x, y, widthM, heightM, params), uuid: `harness-material-${id}`, finishColour, depthM }
}
