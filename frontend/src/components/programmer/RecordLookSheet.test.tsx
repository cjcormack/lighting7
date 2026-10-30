// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RecordLookResponse } from '@/store/programmerOps'

/**
 * The Record look sheet's ending: a Record that lands closes the sheet and says what went in as a
 * toast; one the desk refuses keeps the sheet, and the operator's form, open.
 */
const RESULT = {
  look: { name: 'Night' }, created: true, rowsWritten: 3, rowsRemoved: 0, groupRowsEmitted: 0,
  skipped: [], programmerKeysRefreshed: 0, cuesRepublished: [], sceneryWritten: 0, sceneryRemoved: 0,
} as unknown as RecordLookResponse
const recordLook = vi.fn((_: unknown) => ({ unwrap: () => Promise.resolve(RESULT) }))
const readSelectionMask = vi.fn((): string[] | null => null)

vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
vi.mock('react-redux', () => ({ useSelector: () => [] }))
vi.mock('@/store/selectionSlice', () => ({ selectTargetKeys: () => [] }))
vi.mock('@/store/programmer', () => ({ useProgrammerScenery: () => ({ projectId: 6, elements: [] }) }))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: [] }) }))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtureFx', () => ({ useActiveFxQuery: () => ({ data: [] }) }))
vi.mock('./useLocalFamilyCounts', () => ({ useLocalFamilyCounts: () => undefined }))
vi.mock('@/hooks/useWindowMaskReader', () => ({ useWindowMaskReader: () => readSelectionMask }))
vi.mock('@/store/programmerOps', () => ({
  useRecordLookMutation: () => [recordLook, { isLoading: false, error: undefined, reset: () => {} }],
}))

import { toast } from 'sonner'
import { RecordLookSheet } from './RecordLookSheet'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  recordLook.mockImplementation(() => ({ unwrap: () => Promise.resolve(RESULT) }))
  readSelectionMask.mockImplementation(() => null)
})

function recordNight(onOpenChange: (open: boolean) => void) {
  render(<RecordLookSheet open onOpenChange={onOpenChange} projectId={6} />)
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Night' } })
  fireEvent.click(screen.getByRole('button', { name: 'Record' }))
}

describe('Record look', () => {
  it('closes once the look is recorded, and says what went in', async () => {
    const onOpenChange = vi.fn()
    recordNight(onOpenChange)
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(toast.success).toHaveBeenCalledWith('Created “Night” — 3 rows written.', { description: undefined })
    // The sheet animates out still mounted: its Record takes no second press meanwhile.
    expect(screen.getByRole('button', { name: 'Record' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    expect(recordLook).toHaveBeenCalledTimes(1)
  })

  it('warns rather than celebrates when the desk skipped rows', async () => {
    recordLook.mockImplementation(() => ({
      unwrap: () => Promise.resolve({ ...RESULT, skipped: [{ reason: 'MASKED_OUT' }] } as unknown as RecordLookResponse),
    }))
    const onOpenChange = vi.fn()
    recordNight(onOpenChange)
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(
      'Created “Night” — 3 rows written.',
      expect.objectContaining({ description: expect.any(String), duration: 10_000 }),
    )
  })

  it('stays open when the desk refuses the record', async () => {
    recordLook.mockImplementation(() => ({ unwrap: () => Promise.reject(new Error('refused')) }))
    const onOpenChange = vi.fn()
    recordNight(onOpenChange)
    await waitFor(() => expect(recordLook).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Name')).toHaveValue('Night')
  })

  it('opens on every attribute, and sends no mask', async () => {
    recordNight(() => {})
    expect(screen.getByRole('switch', { name: 'All attributes' })).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(recordLook).toHaveBeenCalled())
    expect(recordLook.mock.calls[0][0]).toMatchObject({ mask: undefined, mode: 'CREATE' })
  })

  it('opens on Merge into the Look it is given', async () => {
    render(<RecordLookSheet open onOpenChange={() => {}} projectId={6} targetLookId={12} />)
    expect(screen.getByRole('radio', { name: 'Merge' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(recordLook).toHaveBeenCalled())
    expect(recordLook.mock.calls[0][0]).toMatchObject({ mode: 'MERGE', lookId: 12, mask: undefined })
    expect(recordLook.mock.calls[0][0]).not.toHaveProperty('asLayer', true)
    expect(screen.queryByLabelText('Use it here as a programmer layer')).toBeNull()
  })

  it('uses a new look here as a programmer layer unless told not to', async () => {
    recordNight(() => {})
    await waitFor(() => expect(recordLook).toHaveBeenCalledTimes(1))
    expect(recordLook.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', asLayer: true })
    cleanup()

    render(<RecordLookSheet open onOpenChange={() => {}} projectId={6} />)
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Night' } })
    fireEvent.click(screen.getByLabelText('Use it here as a programmer layer'))
    fireEvent.click(screen.getByRole('button', { name: 'Record' }))
    await waitFor(() => expect(recordLook).toHaveBeenCalledTimes(2))
    expect(recordLook.mock.calls[1][0]).toMatchObject({ asLayer: undefined })
  })

  it('opens as Make layer: a new look as a layer, over the selection, on its mask', async () => {
    readSelectionMask.mockImplementation(() => ['COLOUR'])
    const targets = [{ type: 'fixture' as const, key: 'hex-1' }]
    render(<RecordLookSheet open onOpenChange={() => {}} projectId={6} targets={targets} makeLayer />)
    expect(screen.getByText('Make a layer from your values')).toBeTruthy()
    expect(screen.queryByRole('radio', { name: 'Merge' })).toBeNull()
    expect(screen.queryByLabelText('Use it here as a programmer layer')).toBeNull()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Night' } })
    fireEvent.click(screen.getByRole('button', { name: 'Make layer' }))
    await waitFor(() => expect(recordLook).toHaveBeenCalled())
    expect(recordLook.mock.calls[0][0]).toMatchObject({ mode: 'CREATE', mask: ['COLOUR'], asLayer: true, targets })
  })

  it('has nothing to make as a layer until heads are selected', () => {
    render(<RecordLookSheet open onOpenChange={() => {}} projectId={6} makeLayer />)
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Night' } })
    expect(screen.getByRole('button', { name: 'Make layer' })).toBeDisabled()
    expect(screen.getByText(/Select the heads this look is about first/)).toBeTruthy()
  })
})
