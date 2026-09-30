// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, renderHook } from '@testing-library/react'
import type { FixturePatch } from '../../../api/patchApi'
import type { RiggingDto } from '../../../api/riggingApi'
import { STAGE_PROJECTIONS, type StageProjection } from '../../../lib/stageProjection'
import type { BulkTarget } from '../../../lib/stageBulkOps'
import { useStageNudge } from './useStageNudge'

afterEach(cleanup)

const free: BulkTarget = {
  patch: { id: 7, key: 'par', riggingUuid: null, stageX: 1, stageY: 2, stageZ: 1 } as FixturePatch,
  rig: null,
  world: { x: 1, y: 2, z: 1 },
}

function nudge(projection: StageProjection, targets: BulkTarget[] = [free]) {
  const commit = vi.fn()
  renderHook(() => useStageNudge({ enabled: true, projection, stepM: 0.25, targets: () => targets, commit }))
  return commit
}

function press(key: string, shiftKey = false) {
  fireEvent.keyDown(window, { key, shiftKey })
  fireEvent.keyUp(window, { key, shiftKey })
}

describe('useStageNudge on a section (stage-view plan session 5)', () => {
  it('in Front, ↑ raises a free fixture by the grid step and leaves how far upstage it is', () => {
    const commit = nudge(STAGE_PROJECTIONS.front)
    press('ArrowUp')
    expect(commit).toHaveBeenCalledWith([{ patchId: 7, stageX: 1, stageY: 2, stageZ: 1.25 }], 'Nudge')
  })

  it('in Side, → moves it upstage, and ⇧ takes ten steps', () => {
    const commit = nudge(STAGE_PROJECTIONS.side)
    press('ArrowRight', true)
    expect(commit.mock.calls[0]![0][0]).toMatchObject({ stageX: 1, stageY: 4.5, stageZ: 1 })
  })

  it('in Plan, a fixture on a bar slides along it, and a press across the bar moves nothing', () => {
    const bar = { uuid: 'lx1', positionX: 0, positionY: 4, positionZ: 5, yawDeg: 0, pitchDeg: 0, rollDeg: 0, lengthM: 6 } as RiggingDto
    const hung: BulkTarget = {
      patch: { id: 8, key: 'hung', riggingUuid: 'lx1', stageX: 0, stageY: 0, stageZ: -0.3 } as FixturePatch,
      rig: bar,
      world: { x: 0, y: 4, z: 4.7 },
    }
    const commit = nudge(STAGE_PROJECTIONS.plan, [hung])
    press('ArrowRight')
    expect(commit.mock.calls[0]![0][0]).toMatchObject({ patchId: 8, stageX: 0.25, stageY: 0, stageZ: -0.3 })
    // It stays on its bar: a nudge never names a new rigging.
    expect(commit.mock.calls[0]![0][0]).not.toHaveProperty('riggingUuid')
    press('ArrowUp')
    expect(commit.mock.calls[1]![0][0]).toMatchObject({ stageX: 0, stageY: 0 })
  })

  it('stands aside for a field the operator is typing in', () => {
    const commit = nudge(STAGE_PROJECTIONS.plan)
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()
    press('ArrowUp')
    expect(commit).not.toHaveBeenCalled()
    input.remove()
  })
})
