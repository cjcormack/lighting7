// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { Cue, CopyCuesFromResponse } from '@/api/cuesApi'

/**
 * *Copy from another show…* (cue model plan §3.8): the project, its cues, where they go, then the
 * desk's dry run before the copy.
 */
const DRY_RUN: CopyCuesFromResponse = {
  dryRun: true,
  copied: [
    { fromCueId: 12, cueId: 901, entryId: 77 },
    { fromCueId: 11, cueId: 902, entryId: 78 },
  ],
  unresolved: [
    { problem: "project 1 has no fixture 'spot-9'", in: ["cue 'Storm'"] },
    { problem: "element 'Forest gauze' is not in project 1's scene; its scenery change is left out", in: ["cue 'Dawn'"] },
  ],
  stackId: 7,
  looksCopied: ['Night'],
  looksLinked: ['Warm'],
  palettesCopied: [],
  palettesLinked: ['Amber'],
}
const copyCuesFrom = vi.fn((arg: { dryRun?: boolean }) => ({
  unwrap: () => Promise.resolve(arg.dryRun ? DRY_RUN : { ...DRY_RUN, dryRun: false }),
}))
const listArgs = vi.fn()

function cue(id: number, name: string, entries: Cue['entries'] = []): Cue {
  return {
    id, name, layers: [], adHocEffects: [], propertyAssignments: [], triggers: [],
    cueStackId: null, cueStackName: null, sortOrder: 0, autoAdvance: false, autoAdvanceDelayMs: null,
    fadeDurationMs: null, fadeCurve: 'LINEAR', cueNumber: null, cueNumberAuto: false, entries,
    notes: null, cueType: 'STANDARD', canEdit: true, canDelete: true,
  }
}

vi.mock('@/store/projects', () => ({
  useProjectListQuery: () => ({
    data: [
      { id: 1, name: 'Hamlet', description: null, isCurrent: true },
      { id: 2, name: 'Panto', description: null, isCurrent: false, venue: { uuid: 'u', name: 'Commemoration Hall', version: 2 } },
      { id: 3, name: 'Hall', description: null, isCurrent: false, kind: 'VENUE' },
    ],
  }),
}))
vi.mock('@/store/cues', () => ({
  useProjectCueListQuery: (projectId: number, opts: { skip?: boolean }) => {
    listArgs(projectId, opts)
    return {
      currentData: opts.skip
        ? undefined
        : [
            cue(11, 'Storm', [{ entryId: 1, stackId: 4, stackName: 'Act 1', sortOrder: 0, cueNumber: '3', cueNumberAuto: false, autoAdvance: false, autoAdvanceDelayMs: null }]),
            cue(12, 'Dawn'),
            cue(13, 'Blackout'),
          ],
      isFetching: false,
    }
  },
  useCopyCuesFromMutation: () => [copyCuesFrom, { isLoading: false }],
}))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({
    data: [
      { id: 7, name: 'Act 2', type: 'STACK', cues: [] },
      { id: 8, name: '—', type: 'SEPARATOR', cues: [] },
    ],
  }),
}))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
const toastSuccess = vi.fn()
vi.mock('sonner', () => ({ toast: { success: (...a: unknown[]) => toastSuccess(...a) } }))

const { CopyFromShowSheet } = await import('./CopyFromShowSheet')

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function draw() {
  const onOpenChange = vi.fn()
  render(<CopyFromShowSheet projectId={1} open onOpenChange={onOpenChange} />)
  return onOpenChange
}

describe('CopyFromShowSheet', () => {
  it('offers every other project, shows before venues', () => {
    draw()
    const list = screen.getByRole('list', { name: 'Projects' })
    expect(within(list).getAllByRole('button').map((b) => b.textContent)).toEqual([
      'PantoCommemoration Hall',
      'HallVenue',
    ])
  })

  it('reviews the dry run — the cues, the Looks and palettes, what will not resolve — and copies on confirm', async () => {
    const onOpenChange = draw()
    fireEvent.click(screen.getByRole('button', { name: /Panto/ }))
    expect(listArgs).toHaveBeenLastCalledWith(2, { skip: false })

    const cues = screen.getByRole('list', { name: 'Cues' })
    expect(within(cues).getAllByRole('checkbox')).toHaveLength(3)
    expect(within(cues).getByText('Act 1 · Q3')).toBeTruthy()
    expect((screen.getByRole('button', { name: /^Next/ }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(cues).getByText('Storm'))
    fireEvent.click(within(cues).getByText('Dawn'))
    fireEvent.click(screen.getByRole('button', { name: 'Next · 2' }))

    // Only runnable stacks, and loose first.
    const stacks = screen.getByRole('group', { name: 'Where the copies go' })
    expect(within(stacks).getAllByRole('radio')).toHaveLength(2)
    fireEvent.click(within(stacks).getByText('Act 2'))
    fireEvent.click(screen.getByRole('button', { name: 'Review' }))

    // In the list's order — by name — so Dawn is placed before Storm.
    expect(copyCuesFrom).toHaveBeenCalledWith({ projectId: 1, fromProjectId: 2, cueIds: [12, 11], stackId: 7, dryRun: true })
    const review = await waitFor(() => {
      const el = document.querySelector('[data-copy-review]') as HTMLElement
      expect(el).toBeTruthy()
      return el
    })
    expect(within(review).getAllByRole('listitem').slice(0, 2).map((li) => li.textContent)).toEqual(['Dawn', 'Storm'])
    expect(within(review).getByText('Night')).toBeTruthy()
    expect(within(review).getByText('Warm')).toBeTruthy()
    expect(within(review).getByText('Amber')).toBeTruthy()
    const unresolved = within(review).getByRole('list', { name: 'Unresolved' })
    expect(within(unresolved).getByText("project 1 has no fixture 'spot-9'")).toBeTruthy()
    expect(within(unresolved).getByText("cue 'Storm'")).toBeTruthy()
    expect(copyCuesFrom).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Copy 2 cues' }))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(copyCuesFrom).toHaveBeenLastCalledWith({ projectId: 1, fromProjectId: 2, cueIds: [12, 11], stackId: 7 })
    expect(toastSuccess).toHaveBeenCalledWith('Copied 2 cues from Panto into Act 2')
  })

  it('copies loose by default, sending no stack', async () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: /Panto/ }))
    fireEvent.click(screen.getByText('Blackout'))
    fireEvent.click(screen.getByRole('button', { name: 'Next · 1' }))
    expect((screen.getByRole('radio', { name: 'No stack — loose cues' }) as HTMLInputElement).checked).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Review' }))
    expect(copyCuesFrom).toHaveBeenCalledWith({ projectId: 1, fromProjectId: 2, cueIds: [13], dryRun: true })
  })
})
