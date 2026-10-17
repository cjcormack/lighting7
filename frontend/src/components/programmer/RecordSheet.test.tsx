// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { selectionWs } from '@/test/backendMock'
import { resetDeskFollowStores } from '@/lib/deskFollow'

/**
 * The Record sheet's S10 polish (venue-visit plan): *New stack…* in the stack select, created only
 * when Record is pressed, and a cue picker for Merge / Replace / Remove — what the Record menu's
 * "An existing cue…" opens — that lists every cue by name and records into the one picked.
 */
const store = {
  stacks: [] as { id: number; name: string; type: string }[] | undefined,
  cues: [] as { id: number; name: string; entries: { stackName: string; cueNumber: string | null }[]; cueType: string }[],
}
const record = vi.fn((_: unknown) => Promise.resolve({ data: undefined }))
const createStack = vi.fn((body: { name: string }) => Promise.resolve({ data: { id: 77, name: body.name } }))
const createState = { error: undefined as unknown }

// The desk selection's frame is what the sheet opens its mask on (apply-model plan D3).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('react-redux', () => ({ useSelector: () => [] }))
vi.mock('@/store/selectionSlice', () => ({ selectTargetKeys: () => [] }))
vi.mock('@/store/programmer', () => ({ useProgrammerScenery: () => ({ projectId: 6, elements: [] }) }))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [] }) }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: store.stacks }),
  useCreateProjectCueStackMutation: () => [createStack, { isLoading: false, error: createState.error, reset: () => {} }],
}))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: store.cues, isLoading: false }) }))
vi.mock('@/store/programmerOps', () => ({
  useRecordProgrammerMutation: () => [record, { isLoading: false, error: undefined, reset: () => {} }],
}))

import { lightingApi } from '@/api/lightingApi'
import { RecordSheet } from './RecordSheet'

afterEach(() => {
  selectionWs.last = null
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

beforeEach(() => {
  createState.error = undefined
  record.mockClear()
  createStack.mockClear()
  store.stacks = [
    { id: 4, name: 'Act 1', type: 'STANDARD' },
    { id: 5, name: 'Act 2', type: 'STANDARD' },
  ]
  store.cues = [
    { id: 11, name: 'Storm', entries: [{ stackName: 'Act 1', cueNumber: '7' }], cueType: 'STANDARD' },
    { id: 12, name: 'Bows', entries: [{ stackName: 'Act 2', cueNumber: '30' }], cueType: 'STANDARD' },
    { id: 13, name: 'Wash', entries: [], cueType: 'STANDARD' },
  ]
})

const recordButton = () => screen.getByRole('button', { name: 'Record' })

function openStackSelect() {
  const trigger = screen.getByRole('combobox', { name: 'Stack' })
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'ArrowDown' })
}

describe('New stack…', () => {
  it('follows the stacks in the stack select, with No stack last', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    openStackSelect()
    const options = screen.getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Act 1', 'Act 2', 'New stack…', 'No stack'])
  })

  it('records a loose cue with No stack — no stack named, none made (cue model plan §10, 19)', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    openStackSelect()
    fireEvent.click(screen.getByRole('option', { name: 'No stack' }))
    expect(screen.getByText('In no stack — find it in the Cues table.')).toBeTruthy()
    // A number is an entry's: a cue in no stack has none to take.
    expect(screen.queryByLabelText('Cue #')).toBeNull()
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(createStack).not.toHaveBeenCalled()
    expect(record.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', cueStackId: undefined, cueNumber: undefined })
  })

  it('sends no number typed before No stack was chosen', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.change(screen.getByLabelText('Cue #'), { target: { value: '14' } })
    openStackSelect()
    fireEvent.click(screen.getByRole('option', { name: 'No stack' }))
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ cueStackId: undefined, cueNumber: undefined })
  })

  it('asks for a name and makes the stack only when Record is pressed', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    openStackSelect()
    fireEvent.click(screen.getByRole('option', { name: 'New stack…' }))

    const name = screen.getByLabelText('New stack name')
    expect(recordButton()).toBeDisabled()
    fireEvent.change(name, { target: { value: '  Act 3 ' } })
    expect(createStack).not.toHaveBeenCalled()

    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(createStack).toHaveBeenCalledWith({ projectId: 6, name: 'Act 3', loop: false })
    expect(record.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', cueStackId: 77, cueId: undefined })
  })

  it('opens on New stack… when the show has no stack yet', async () => {
    store.stacks = []
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.change(screen.getByLabelText('New stack name'), { target: { value: 'Main' } })
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ cueStackId: 77 })
  })

  it('waits for the stack list rather than reading an unloaded one as empty', async () => {
    store.stacks = undefined
    const { rerender } = render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.queryByLabelText('New stack name')).toBeNull()
    expect(recordButton()).toBeDisabled()
    store.stacks = [{ id: 4, name: 'Act 1', type: 'STANDARD' }]
    rerender(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.queryByLabelText('New stack name')).toBeNull()
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(createStack).not.toHaveBeenCalled()
    expect(record.mock.calls[0][0]).toMatchObject({ cueStackId: 4 })
  })

  it("shows a failed create's error only while New stack… is still the choice", () => {
    createState.error = { status: 409, data: { error: 'A stack called Act 1 exists' } }
    store.stacks = []
    const { rerender } = render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.getByText(/A stack called Act 1 exists/)).toBeTruthy()
    store.stacks = [{ id: 4, name: 'Act 1', type: 'STANDARD' }]
    rerender(<RecordSheet open onOpenChange={() => {}} projectId={6} defaultCueStackId={4} />)
    openStackSelect()
    fireEvent.click(screen.getByRole('option', { name: 'Act 1' }))
    expect(screen.queryByText(/A stack called Act 1 exists/)).toBeNull()
  })

  it('records into a picked stack without making one', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(createStack).not.toHaveBeenCalled()
    expect(record.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', cueStackId: 4 })
  })

  it('does not record when the stack could not be made', async () => {
    createStack.mockImplementationOnce(() => Promise.resolve({ error: { status: 400 } } as never))
    store.stacks = []
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.change(screen.getByLabelText('New stack name'), { target: { value: 'Main' } })
    fireEvent.click(recordButton())
    await waitFor(() => expect(createStack).toHaveBeenCalled())
    expect(record).not.toHaveBeenCalled()
  })
})

describe('An existing cue…', () => {
  it('opens on Merge with the picker and nothing picked', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} existingCue />)
    expect(screen.getByRole('radio', { name: 'Merge' })).toHaveAttribute('aria-checked', 'true')
    const picker = screen.getByRole('radiogroup', { name: 'Cue' })
    expect(within(picker).getAllByRole('radio').map((r) => r.closest('label')!.textContent)).toEqual([
      'BowsQ30 · Act 2',
      'StormQ7 · Act 1',
      'WashLoose',
    ])
    expect(recordButton()).toBeDisabled()
  })

  it('records into the cue picked, whatever its stack', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} existingCue />)
    fireEvent.click(screen.getByRole('radio', { name: /Storm/ }))
    expect(screen.getByText('Write the programmer into “Storm”.')).toBeTruthy()
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ mode: 'MERGE', cueId: 11, cueStackId: undefined })
  })

  it('offers every mode from the plain Record too, with the picker for the three that need a cue', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.queryByRole('radiogroup', { name: 'Cue' })).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'Remove' }))
    expect(screen.getByRole('radiogroup', { name: 'Cue' })).toBeTruthy()
  })

  it('preselects the cue it was opened for', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} targetCueId={12} targetCueName="Bows" />)
    expect(screen.getByRole('radio', { name: /Bows/ })).toBeChecked()
    fireEvent.click(screen.getByRole('radio', { name: 'Replace' }))
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ mode: 'UPDATE_EXISTING', cueId: 12 })
  })

  it('filters the cues by name, number or stack', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} existingCue />)
    fireEvent.change(screen.getByLabelText('Filter cues'), { target: { value: 'act 2' } })
    const picker = screen.getByRole('radiogroup', { name: 'Cue' })
    expect(within(picker).getAllByRole('radio')).toHaveLength(1)
    expect(within(picker).getByRole('radio', { name: /Bows/ })).toBeTruthy()
  })
})

describe('the mask', () => {
  it('sends none for All attributes, refuses nothing ticked, and sends what is ticked', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
    for (const family of ['Intensity', 'Position', 'Colour', 'Beam']) {
      fireEvent.click(screen.getByRole('checkbox', { name: family }))
    }
    expect(recordButton()).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Colour' }))
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ mask: ['COLOUR'] })
  })

  it('sends no mask while the switch is on, or off with all four ticked', async () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(screen.getByRole('switch', { name: 'All attributes' }))
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ mask: undefined })
  })

  /**
   * The sheet opens on the selection's mask (apply-model plan D3) — the desk's while this window
   * follows it — and from then on the mask is this Record's own: a change here writes nothing to the
   * selection, and a selection that moves while the sheet is open does not move the sheet's.
   */
  it('opens on the selection’s mask, and a change here leaves the selection alone', async () => {
    // Every write that could move the selection: the whole fact, and the mask alone.
    const writes = (['set', 'setFamilies', 'toggle', 'clear'] as const).map((name) => vi.spyOn(lightingApi.selection, name))
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.getByRole('switch', { name: 'All attributes' })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('checkbox', { name: 'Colour' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Position' })).not.toBeChecked()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Position' }))
    // The selection moving under the open sheet does not move the sheet's mask either.
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['BEAM'], source: null }
    fireEvent.click(recordButton())
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ mask: ['POSITION', 'COLOUR'] })
    for (const write of writes) expect(write).not.toHaveBeenCalled()
  })

  it('opens on All attributes over an unmasked selection, and on the new mask the next time it opens', () => {
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null }
    const { rerender } = render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.getByRole('switch', { name: 'All attributes' })).toHaveAttribute('aria-checked', 'true')
    rerender(<RecordSheet open={false} onOpenChange={() => {}} projectId={6} />)
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['INTENSITY'], source: null }
    rerender(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(screen.getByRole('checkbox', { name: 'Intensity' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Colour' })).not.toBeChecked()
  })
})
