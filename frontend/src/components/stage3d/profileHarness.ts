// Profiling harness for the Stage 3D view.
//
// Activate by adding `?profileHarness=1` to the URL. The harness replaces the
// project's real patches/regions/riggings with a deterministic high-load
// synthetic scene (50 fixtures × 16 regions × 8 riggings) so GPU/CPU profiling
// runs against a representative worst-case layout. Consumed by `useStageData`.

import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { Fixture, FixtureTypeInfo, PropertyDescriptor } from '../../store/fixtures'

const HARNESS_TYPE_KEY = '__profileHarness_type__'

export function isHarnessActive(): boolean {
  if (typeof window === 'undefined') return false
  try {
    return new URLSearchParams(window.location.search).get('profileHarness') === '1'
  } catch {
    return false
  }
}

export interface HarnessData {
  patches: FixturePatch[]
  regions: StageRegionDto[]
  riggings: RiggingDto[]
  syntheticFixture: Fixture
  syntheticType: FixtureTypeInfo
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

export function buildHarness(stageW: number, stageD: number, stageH: number): HarnessData {
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
      centerZ: 0,
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
