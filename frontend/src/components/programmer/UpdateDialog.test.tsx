// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { selectionWs } from '@/test/backendMock'
import type { IncludedTarget } from '@/api/programmerWsApi'

/**
 * Update takes a mask like Record and Include (venue-visit plan S10): sent as `mask`, absent for
 * every attribute, and in Mode B the checklist is fetched again under a changed mask, since the
 * mask decides which cues the programmer is over.
 */
type Body = { projectId: number; targets?: number[]; mask?: string[] }
const checklist = (cueIds: number[]) => ({
  applied: false,
  mode: 'CHECKLIST',
  checklist: {
    stacks: [
      {
        cueStackId: 4,
        cueStackName: 'Act 1',
        isActive: false,
        cues: cueIds.map((cueId) => ({
          cueId, cueName: `Cue ${cueId}`, cueNumber: String(cueId), cueNumberAuto: false,
          isActive: false, keyCount: 1, viaFxKeyCount: 0, sample: [],
        })),
      },
    ],
    unattributed: [],
    totalKeys: cueIds.length,
  },
})
const answer = { current: (_: Body): unknown => checklist([1, 2]) }
const update = vi.fn((body: Body) => ({ unwrap: () => Promise.resolve(answer.current(body)) }))

// The desk selection's frame is what the dialog opens its mask on (apply-model plan D3).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('sonner', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
vi.mock('@/store/programmerOps', () => ({
  useUpdateProgrammerMutation: () => [update, { isLoading: false, error: undefined, reset: () => {} }],
}))

import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { UpdateDialog } from './UpdateDialog'

const cueTarget = { kind: 'CUE', cueId: 9, cueStackId: 4, name: 'Storm' } as unknown as IncludedTarget
const box = (name: string | RegExp) => screen.getByRole('checkbox', { name }) as HTMLInputElement
const allSwitch = () => screen.getByRole('switch', { name: 'All attributes' })
/** Switch *All attributes* off and untick every family but [keep]. */
function narrowTo(keep: string) {
  fireEvent.click(allSwitch())
  for (const family of ['Intensity', 'Position', 'Colour', 'Beam']) if (family !== keep) fireEvent.click(box(family))
}

beforeEach(() => {
  vi.clearAllMocks()
  answer.current = () => checklist([1, 2])
})
afterEach(() => {
  selectionWs.last = null
})

describe('UpdateDialog mask', () => {
  it('sends no mask for every attribute, and the families once narrowed (Mode A)', async () => {
    answer.current = () => ({ applied: true, mode: 'A', results: [], warnings: [] })
    render(<UpdateDialog open onOpenChange={() => {}} projectId={6} includeTarget={cueTarget} />)
    expect(update).not.toHaveBeenCalled()
    expect(allSwitch()).toHaveAttribute('aria-checked', 'true')
    narrowTo('Colour')
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][0]).toEqual({ projectId: 6, mask: ['COLOUR'] })
  })

  it('fetches the checklist again under a new mask, and commits with it (Mode B)', async () => {
    const onOpenChange = vi.fn()
    render(<UpdateDialog open onOpenChange={onOpenChange} projectId={6} includeTarget={null} />)
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][0]).toEqual({ projectId: 6, preview: true, mask: undefined })
    await screen.findByText('Cue 2')

    // An untick survives the re-fetch; a cue the new checklist lacks is gone.
    fireEvent.click(box(/Cue 1/))
    answer.current = () => checklist([1, 3])
    // Switching off is all four — the same mask, so no request.
    fireEvent.click(allSwitch())
    expect(update).toHaveBeenCalledTimes(1)
    // Each untick on the way down is a narrowing of its own; the last, to nothing, asks nothing
    // and disables Update.
    for (const family of ['Intensity', 'Position', 'Colour', 'Beam']) fireEvent.click(box(family))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(4))
    expect(update.mock.calls[3][0]).toEqual({ projectId: 6, preview: true, mask: ['BEAM'] })
    expect(screen.getByText('Pick at least one attribute.')).toBeTruthy()
    expect(screen.getByRole('button', { name: /^Update/ })).toBeDisabled()
    fireEvent.click(box('Position'))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(5))
    expect(update.mock.calls[4][0]).toEqual({ projectId: 6, preview: true, mask: ['POSITION'] })
    await screen.findByText('Cue 3')
    expect(box(/Cue 1/).checked).toBe(false)
    expect(box(/Cue 3/).checked).toBe(true)

    answer.current = () => ({
      applied: true,
      mode: 'B',
      results: [{ cueId: 3, cueName: 'Cue 3', assignmentsWritten: 2, republishedLive: false }],
      warnings: [],
    })
    fireEvent.click(screen.getByRole('button', { name: 'Update 1 cue(s)' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(6))
    expect(update.mock.calls[5][0]).toEqual({ projectId: 6, targets: [3], mask: ['POSITION'] })
    // A write that landed closes the dialog and says what it wrote; no checklist is fetched after it.
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(toast.success).toHaveBeenCalledWith('Updated “Cue 3” — 2 values written.', { description: undefined })
    // Still mounted while it animates out, and its Update takes no second press.
    expect(screen.getByRole('button', { name: 'Update 1 cue(s)' })).toBeDisabled()
    expect(update).toHaveBeenCalledTimes(6)
  })

  it('stays open and fetches the checklist again under a new mask after a commit that wrote nothing', async () => {
    const onOpenChange = vi.fn()
    render(<UpdateDialog open onOpenChange={onOpenChange} projectId={6} includeTarget={null} />)
    await screen.findByText('Cue 2')
    answer.current = () => ({ applied: false, mode: 'B', results: [], warnings: ['Cue 1 not found'] })
    fireEvent.click(screen.getByRole('button', { name: 'Update 2 cue(s)' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    answer.current = () => checklist([5])
    narrowTo('Colour')
    await screen.findByText('Cue 5')
    expect(update.mock.calls.at(-1)![0]).toEqual({ projectId: 6, preview: true, mask: ['COLOUR'] })
    expect(onOpenChange).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('closes and says so when an Update into an included Look writes nothing (Mode A)', async () => {
    const onOpenChange = vi.fn()
    const lookTarget = { kind: 'LOOK', lookId: 12, lookName: 'Set — Market' } as unknown as IncludedTarget
    answer.current = () => ({
      applied: false, mode: 'A', results: [], warnings: [],
      lookResult: { lookId: 12, lookName: 'Set — Market', rowsWritten: 0, rowsRemoved: 0, cuesRepublished: [] },
    })
    render(<UpdateDialog open onOpenChange={onOpenChange} projectId={6} includeTarget={lookTarget} />)
    fireEvent.click(screen.getByRole('button', { name: 'Update' }))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(toast.success).toHaveBeenCalledWith('Nothing written to “Set — Market”.', { description: undefined })
    expect(screen.getByRole('button', { name: 'Update' })).toBeDisabled()
  })

  /**
   * It opens on every attribute whatever the selection's mask, as Record does, and in Mode B asks for
   * the checklist once. A change here is the dialog's own and writes nothing to the selection.
   */
  it('opens on every attribute and fetches the checklist once (Mode B)', async () => {
    // Every write that could move the selection: the whole fact, and the mask alone.
    const writes = (['set', 'setFamilies', 'toggle', 'clear'] as const).map((name) => vi.spyOn(lightingApi.selection, name))
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
    render(<UpdateDialog open onOpenChange={() => {}} projectId={6} includeTarget={null} />)
    await screen.findByText('Cue 2')
    expect(update).toHaveBeenCalledTimes(1)
    expect(update.mock.calls[0][0]).toEqual({ projectId: 6, preview: true, mask: undefined })
    expect(allSwitch()).toHaveAttribute('aria-checked', 'true')
    narrowTo('Colour')
    await waitFor(() =>
      expect(update).toHaveBeenLastCalledWith({ projectId: 6, preview: true, mask: ['COLOUR'] }),
    )
    for (const write of writes) expect(write).not.toHaveBeenCalled()
  })

  it('opens on every attribute again after a close, whatever was picked here', async () => {
    answer.current = () => ({ applied: false, mode: 'A', results: [], warnings: [] })
    const { rerender } = render(<UpdateDialog open onOpenChange={() => {}} projectId={6} includeTarget={cueTarget} />)
    narrowTo('Beam')
    expect(allSwitch()).toHaveAttribute('aria-checked', 'false')
    rerender(<UpdateDialog open={false} onOpenChange={() => {}} projectId={6} includeTarget={cueTarget} />)
    rerender(<UpdateDialog open onOpenChange={() => {}} projectId={6} includeTarget={cueTarget} />)
    expect(allSwitch()).toHaveAttribute('aria-checked', 'true')
  })
})
