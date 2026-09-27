// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { FixturePatch, PatchPlacement } from '../api/patchApi'
import type { RiggingDto } from '../api/riggingApi'
import type { FixtureTypeInfo } from '../store/fixtures'
import { STAGE_PROJECTIONS, project } from '../lib/stageProjection'

const rig = {
  uuid: 'rig-lx1',
  name: 'LX1',
  positionX: 0,
  positionY: 2,
  positionZ: 6,
  yawDeg: 0,
  pitchDeg: 0,
  rollDeg: 0,
  lengthM: 10,
} as RiggingDto

function placement(overrides: Partial<PatchPlacement>): PatchPlacement {
  return {
    uuid: 'pl-1',
    label: null,
    riggingUuid: null,
    stageX: null,
    stageY: null,
    stageZ: null,
    baseYawDeg: null,
    basePitchDeg: null,
    ...overrides,
  }
}

function patch(overrides: Partial<FixturePatch>): FixturePatch {
  return {
    id: 1,
    key: 'p',
    displayName: 'P',
    fixtureTypeKey: 'generic-dimmer',
    startChannel: 1,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 0,
    subnet: 0,
    sortOrder: 0,
    groups: [],
    stageX: null,
    stageY: null,
    stageZ: null,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    ...overrides,
  }
}

let patches: FixturePatch[] = []
vi.mock('../store/patches', () => ({
  usePatchListQuery: () => ({ data: patches }),
  // The hook reads the visible list — the real rule, over this suite's patches.
  useVisiblePatchListQuery: () => ({ data: patches.filter((p) => !p.infrastructure) }),
}))
vi.mock('../store/riggings', () => ({ useRiggingListQuery: () => ({ data: [rig] }) }))
vi.mock('../store/projects', () => ({ useProjectQuery: () => ({ data: undefined }) }))
let fixtureTypes: Partial<FixtureTypeInfo>[] | undefined = []
vi.mock('../store/fixtures', () => ({ useFixtureTypeListQuery: () => ({ data: fixtureTypes }) }))

import { useProjectedPatches } from './useProjectedPatches'

describe('useProjectedPatches — paired lanterns', () => {
  it('keeps a fixture in points and its lanterns in extraPoints, composed through their rigging', () => {
    patches = [
      patch({
        key: 'pair',
        riggingUuid: 'rig-lx1',
        stageX: -3,
        stageY: 0,
        extraPlacements: [
          placement({ uuid: 'sr', label: 'SR', riggingUuid: 'rig-lx1', stageX: 3, stageY: 0 }),
          // No position yet: not drawn.
          placement({ uuid: 'unplaced' }),
        ],
      }),
      patch({ id: 2, key: 'single', stageX: 1, stageY: 1 }),
    ]
    const { result } = renderHook(() => useProjectedPatches(1))

    expect(result.current.points.map((p) => p.patch.key)).toEqual(['pair', 'single'])
    expect(result.current.extraPoints).toHaveLength(1)
    const [sr] = result.current.extraPoints
    expect(sr.patch.key).toBe('pair')
    expect(sr.placement.label).toBe('SR')
    expect(sr.world).toEqual({ x: 3, y: 2, z: 6 })
    expect(result.current.points[0].world).toEqual({ x: -3, y: 2, z: 6 })
  })

  it('draws a lantern even when the fixture itself is unplaced, and none for a hidden patch', () => {
    patches = [
      patch({ key: 'lantern-only', extraPlacements: [placement({ stageX: 2, stageY: 2 })] }),
      patch({
        id: 2,
        key: 'hidden',
        stageHidden: true,
        stageX: 0,
        stageY: 0,
        extraPlacements: [placement({ uuid: 'h', stageX: 1, stageY: 1 })],
      }),
    ]
    const { result } = renderHook(() => useProjectedPatches(1))

    expect(result.current.points).toHaveLength(0)
    expect(result.current.extraPoints.map((p) => p.patch.key)).toEqual(['lantern-only'])
  })

  it('draws neither an infrastructure patch nor its lanterns — not even as the selected one', () => {
    patches = [
      patch({ key: 'par', stageX: 0, stageY: 0 }),
      patch({
        id: 2,
        key: 'power',
        infrastructure: true,
        stageX: 1,
        stageY: 1,
        extraPlacements: [placement({ uuid: 'i', stageX: 2, stageY: 2 })],
      }),
    ]
    const { result } = renderHook(() => useProjectedPatches(1, { includeKey: 'power' }))
    expect(result.current.points.map((p) => p.patch.key)).toEqual(['par'])
    expect(result.current.extraPoints).toEqual([])
  })

  it('reads a patch from a desk that predates the field as having no lanterns', () => {
    patches = [patch({ key: 'old', stageX: 0, stageY: 0 })]
    const { result } = renderHook(() => useProjectedPatches(1))
    expect(result.current.extraPoints).toEqual([])
  })
})

describe('useProjectedPatches — variable-length spans', () => {
  const strip = { typeKey: 'lightstrip', acceptsLength: true, lengthM: 1 }
  const dimmer = { typeKey: 'generic-dimmer', acceptsLength: false, lengthM: 0.25 }
  const plan = (x: number, y: number, z = 0) => project({ x, y, z }, STAGE_PROJECTIONS.plan)
  const close = (actual: { h: number; v: number }, expected: { h: number; v: number }) => {
    expect(actual.h).toBeCloseTo(expected.h, 9)
    expect(actual.v).toBeCloseTo(expected.v, 9)
  }

  it("spans a lightstrip ring's sides, each at its own length and yaw", () => {
    fixtureTypes = [strip, dimmer]
    patches = [
      patch({
        key: 'ring',
        fixtureTypeKey: 'lightstrip',
        stageX: 0,
        stageY: 0,
        lengthM: 10,
        extraPlacements: [
          // Stage left, running upstage: yaw 90 turns the long axis onto +Y.
          placement({ uuid: 'sl', label: 'SL', stageX: -5, stageY: 4, baseYawDeg: 90, lengthM: 6 }),
          // No length of its own: takes the fixture's.
          placement({ uuid: 'us', label: 'US', stageX: 0, stageY: 8, baseYawDeg: 180 }),
        ],
      }),
    ]
    const { result } = renderHook(() => useProjectedPatches(1))

    const [a, b] = result.current.points[0].span!
    close(a, plan(-5, 0))
    close(b, plan(5, 0))
    const [sl, us] = result.current.extraPoints
    close(sl.span![0], plan(-5, 1))
    close(sl.span![1], plan(-5, 7))
    // Yaw 180 reverses the ends; the span is still the fixture's 10 m.
    close(us.span![0], plan(5, 8))
    close(us.span![1], plan(-5, 8))
  })

  it("draws a strip with no length at its type's default", () => {
    fixtureTypes = [strip]
    patches = [patch({ key: 'ring', fixtureTypeKey: 'lightstrip', stageX: 2, stageY: 0 })]
    const { result } = renderHook(() => useProjectedPatches(1))
    const [a, b] = result.current.points[0].span!
    close(a, plan(1.5, 0))
    close(b, plan(2.5, 0))
  })

  it('never spans a fixed-length type, whatever its patch holds, nor anything before the types load', () => {
    fixtureTypes = [strip, dimmer]
    patches = [patch({ key: 'par', stageX: 0, stageY: 0, lengthM: 4 })]
    expect(renderHook(() => useProjectedPatches(1)).result.current.points[0].span).toBeUndefined()

    fixtureTypes = undefined
    patches = [patch({ key: 'ring', fixtureTypeKey: 'lightstrip', stageX: 0, stageY: 0, lengthM: 4 })]
    expect(renderHook(() => useProjectedPatches(1)).result.current.points[0].span).toBeUndefined()
  })
})
