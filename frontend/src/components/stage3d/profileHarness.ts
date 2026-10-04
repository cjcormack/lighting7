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

import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { Fixture, FixtureTypeInfo, PropertyDescriptor } from '../../store/fixtures'

const HARNESS_TYPE_KEY = '__profileHarness_type__'

/** Which synthetic scene: the load profile (`=1`) or the focus scene (`=focus`). */
export type HarnessMode = 'load' | 'focus'

export function harnessMode(): HarnessMode | null {
  if (typeof window === 'undefined') return null
  try {
    const flag = new URLSearchParams(window.location.search).get('profileHarness')
    return flag === '1' ? 'load' : flag === 'focus' ? 'focus' : null
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
