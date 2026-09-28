// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CueLayer } from '@/api/cuesApi'

const mocks = vi.hoisted(() => ({
  picked: {} as CueLayer,
  addLayer: vi.fn(),
  seenKind: undefined as string | undefined,
}))

vi.mock('@/store/programmer', () => ({ programmerAddLayer: mocks.addLayer }))
// The picker drags in the whole look/target/timing wizard. Stubbed down to the two things this
// file cares about: the `CueLayer` it hands back, and the kind it was opened on.
vi.mock('./AddLayerSheet', () => ({
  AddLayerSheet: ({
    open,
    kind,
    onAdd,
  }: {
    open: boolean
    kind?: string
    onAdd: (layer: CueLayer) => void
  }) => {
    mocks.seenKind = kind
    return open ? <button onClick={() => onAdd(mocks.picked)}>confirm picked layer</button> : null
  },
}))

import { ProgrammerAddLayerSheet } from './ProgrammerAddLayerSheet'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.picked = {} as CueLayer
})

describe('ProgrammerAddLayerSheet', () => {
  it('forwards the picker’s property mask, and drops only its timing', () => {
    // The wire frame is rebuilt field by field, so anything the picker sets and this forgets is
    // silently lost. `propertyMask` was: the picker masks a template layer to the template's own
    // family so the row cannot read as "this could touch anything", and the same picker was
    // producing a masked layer in a cue and an unmasked one here. The timing fields stay dropped
    // on purpose — a programmer layer fires now.
    mocks.picked = {
      templateId: 11,
      targets: [{ type: 'group', key: 'front-wash' }],
      propertyMask: 'COLOUR',
      speedMasterUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
      rateSpeedMasterUuid: null,
      delayMs: 3000,
      intervalMs: 500,
      randomWindowMs: 250,
    }
    const onClose = vi.fn()
    render(<ProgrammerAddLayerSheet projectId={1} kind="template" onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'confirm picked layer' }))

    expect(mocks.addLayer).toHaveBeenCalledWith({
      lookId: undefined,
      templateId: 11,
      targets: [{ type: 'group', key: 'front-wash' }],
      propertyMask: 'COLOUR',
      speedMasterUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
      rateSpeedMasterUuid: undefined,
    })
    expect(onClose).toHaveBeenCalled()
  })

  it('opens the picker on the library the door was named for', () => {
    // `+ Look` and `+ Template` are two doors; a door named for one kind that offered the other
    // too would make its name a lie.
    render(<ProgrammerAddLayerSheet projectId={1} kind="look" onClose={vi.fn()} />)
    expect(mocks.seenKind).toBe('look')
    expect(screen.getByRole('button', { name: 'confirm picked layer' })).toBeInTheDocument()
  })

  it('is closed with no kind', () => {
    render(<ProgrammerAddLayerSheet projectId={1} kind={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'confirm picked layer' })).toBeNull()
  })
})
