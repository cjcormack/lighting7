// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProgrammerTempo } from '@/api/programmerWsApi'
import type { SpeedMasterLiveState } from '@/api/speedMastersWsApi'

/**
 * Record's *Tempo* row (fx-speed plan D10): absent while no tempo is held, ticked on every open while
 * one is, naming each held master and its tempo; unticked, the request says `tempo: false`.
 */
const store = { tempo: { projectId: 6, masters: [] } as ProgrammerTempo }
const BANK: SpeedMasterLiveState[] = [
  { uuid: 'm2', index: 2, name: 'Chase', bpm: 132, isRunning: true, source: 'TAP' },
]
const record = vi.fn((_: unknown) => Promise.resolve({ data: undefined } as unknown))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
vi.mock('react-redux', () => ({ useSelector: () => [] }))
vi.mock('@/store/selectionSlice', () => ({ selectTargetKeys: () => [] }))
vi.mock('@/store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: { lastIncluded: null } }),
  useProgrammerRevision: () => 0,
  useProgrammerScenery: () => ({ projectId: 6, elements: [] }),
  useProgrammerTempo: () => store.tempo,
}))
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterLiveQuery: () => ({ data: BANK }) }))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [] }), useVisibleFixtureListQuery: () => ({ data: [] }) }))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [] }) }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: [{ id: 4, name: 'Main', type: 'STANDARD' }] }),
  useProjectProgramStateQuery: () => ({ data: { activeStackId: null } }),
  useCreateProjectCueStackMutation: () => [vi.fn(), { isLoading: false, error: undefined, reset: () => {} }],
}))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtureFx', () => ({ useActiveFxQuery: () => ({ data: [] }) }))
vi.mock('@/store/programmerOps', () => ({
  useRecordProgrammerMutation: () => [record, { isLoading: false, error: undefined, reset: () => {} }],
}))

import { toast } from 'sonner'
import { RecordSheet } from './RecordSheet'
import { describeTempoWrite } from '@/lib/heldTempo'

const tempoBox = () => screen.queryByRole('checkbox', { name: /^tempo/i }) as HTMLInputElement | null
const hold = () => {
  store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 132 }] }
}

beforeEach(() => {
  record.mockClear()
  vi.mocked(toast.success).mockClear()
  store.tempo = { projectId: 6, masters: [] }
})
afterEach(cleanup)

describe('the Record sheet’s Tempo row', () => {
  it('draws no row while no tempo is held', () => {
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(tempoBox()).toBeNull()
  })

  it('is on by default when a tempo is held, naming the master and its tempo', async () => {
    hold()
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(tempoBox()!.checked).toBe(true)
    expect(screen.getByTestId('record-tempo-row').textContent).toContain('M2 · Chase 132')
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ tempo: true })
  })

  it('sends tempo: false when turned off', async () => {
    hold()
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(tempoBox()!)
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(record).toHaveBeenCalled())
    expect(record.mock.calls[0][0]).toMatchObject({ tempo: false })
  })

  it('is on again on the next open', () => {
    hold()
    const { rerender } = render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(tempoBox()!)
    rerender(<RecordSheet open={false} onOpenChange={() => {}} projectId={6} />)
    rerender(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(tempoBox()!.checked).toBe(true)
  })

  it('leaves a release still to land out', () => {
    store.tempo = { projectId: 6, masters: [{ speedMasterUuid: 'm2', bpm: 132, staged: true, released: true }] }
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    expect(tempoBox()).toBeNull()
  })

  it('says the tempos written in the toast', async () => {
    hold()
    record.mockImplementationOnce(() =>
      Promise.resolve({
        data: {
          cue: { name: 'Storm' }, created: true, assignmentsWritten: 4, assignmentsRemoved: 0, groupRowsEmitted: 0,
          fxWritten: 0, skipped: [], preserved: {}, warnings: [], republishedLive: false, tempoWritten: 1,
        },
      }),
    )
    render(<RecordSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith('Created “Storm” — 4 values written.', { description: '1 tempo' }),
    )
  })
})

describe('describeTempoWrite', () => {
  it('says only what happened', () => {
    expect(describeTempoWrite()).toEqual([])
    expect(describeTempoWrite(2)).toEqual(['2 tempos'])
    expect(describeTempoWrite(0, 1)).toEqual(['1 tempo removed'])
  })
})
