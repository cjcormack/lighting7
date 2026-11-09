import { describe, expect, it } from 'vitest'
import { bindingsForScreens, type ControlSurfaceBinding, type ControlPart } from './surfacesApi'

function row(id: number, controlId: string, part: ControlPart, bank: string | null = null): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: 'xtc',
    controlId,
    part,
    bank,
    binding: { does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 's' } },
    target: { type: 'cueStackGo', stackId: 1, stackUuid: 's' },
    targetType: 'cueStackGo',
    takeoverPolicy: null,
    sortOrder: id,
    health: { type: 'ok' },
  }
}

describe('bindingsForScreens', () => {
  it('shows one row per control and layer: the main part, else the push', () => {
    const rows = [
      row(1, 'enc-1', 'PUSH'),
      row(2, 'enc-1', 'MAIN'),
      row(3, 'enc-2', 'PUSH'),
      row(4, 'enc-1', 'PUSH', 'layer-b'),
      row(5, 'btn-1', 'MAIN'),
    ]
    expect(bindingsForScreens(rows).map((b) => b.id)).toEqual([2, 3, 4, 5])
  })
})
