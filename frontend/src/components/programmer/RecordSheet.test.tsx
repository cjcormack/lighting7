// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The Record sheet's S10 polish (venue-visit plan): *New stack…* in the stack select, created only
 * when Record is pressed, and a cue picker for Merge / Replace / Remove — what the Record menu's
 * "An existing cue…" opens — that lists every cue by name and records into the one picked.
 */
const store = {
  stacks: [] as { id: number; name: string; type: string }[] | undefined,
  cues: [] as { id: number; name: string; cueNumber: string | null; cueStackName: string | null; cueType: string }[],
}
const record = vi.fn((_: unknown) => Promise.resolve({ data: undefined }))
const createStack = vi.fn((body: { name: string }) => Promise.resolve({ data: { id: 77, name: body.name } }))
const createState = { error: undefined as unknown }

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

import { RecordSheet } from './RecordSheet'

beforeEach(() => {
  createState.error = undefined
  record.mockClear()
  createStack.mockClear()
  store.stacks = [
    { id: 4, name: 'Act 1', type: 'STANDARD' },
    { id: 5, name: 'Act 2', type: 'STANDARD' },
  ]
  store.cues = [
    { id: 11, name: 'Storm', cueNumber: '7', cueStackName: 'Act 1', cueType: 'STANDARD' },
    { id: 12, name: 'Bows', cueNumber: '30', cueStackName: 'Act 2', cueType: 'STANDARD' },
  ]
})

const recordButton = () => screen.getByRole('button', { name: 'Record' })

function openStackSelect() {
  const trigger = screen.getByRole('combobox', { name: 'Stack' })
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'ArrowDown' })
}

describe('New stack…', () => {
  it('is the last item of the stack select', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    openStackSelect()
    const options = screen.getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Act 1', 'Act 2', 'New stack…'])
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
})
