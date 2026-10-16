// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { cueRow } from '@/test/cueEntries'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'

const deleteStack = vi.fn((_args: Record<string, unknown>) => ({
  unwrap: () => Promise.resolve({ looseCueIds: [], deletedCueIds: [] }),
}))
vi.mock('@/store/cueStacks', () => ({
  useDeleteProjectCueStackMutation: () => [deleteStack, { isLoading: false }],
}))
const previewArgs = vi.fn()
vi.mock('@/store/cues', () => ({
  useCueDeletePreviewQuery: (args: unknown, options: { skip?: boolean }) => {
    previewArgs(args, options)
    return options.skip
      ? { data: undefined, isFetching: false }
      : {
          data: {
            deleted: [],
            uses: [
              {
                cueId: 2,
                name: 'Moon rise',
                entries: [],
                pads: [{ padId: 1, pageId: 1, pageName: 'Main' }],
                slots: [{ slotId: 3, label: 'A1' }],
                anchors: [],
                bindings: [],
              },
            ],
          },
          isFetching: false,
        }
  },
}))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))

import { DeleteStackDialog, cuesOnlyIn } from './DeleteStackDialog'

function stack(id: number, name: string, cues: CueStackCueEntry[]): CueStack {
  return {
    id, name, loop: false, sortOrder: id, type: 'STACK', label: null, cues,
    activeCueId: null, nextCueId: null, activeEntryId: null, nextEntryId: null, standbyEntryId: null,
    canEdit: true, canDelete: true,
  }
}

// Act 1 holds cues 1, 2 and 3; cue 1 is also in Act 2, so deleting Act 1 leaves 2 and 3 in no stack.
const ACT1 = stack(1, 'Act 1', [cueRow(1), cueRow(2), cueRow(3)])
const ACT2 = stack(2, 'Act 2', [cueRow(1, { entryId: 201 })])

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function draw(target: CueStack = ACT1, stacks: CueStack[] = [ACT1, ACT2]) {
  const onClose = vi.fn()
  render(<DeleteStackDialog projectId={1} stack={target} stacks={stacks} onClose={onClose} />)
  return { onClose }
}

describe('DeleteStackDialog', () => {
  it('counts the cues no other stack uses', () => {
    expect(cuesOnlyIn(ACT1, [ACT1, ACT2])).toEqual([2, 3])
  })

  it('says the cues stay, and offers deleting the loose ones unticked', () => {
    draw()
    expect(
      screen.getByText('This deletes “Act 1” and its list. Its 3 cues stay — 2 of them in no stack.'),
    ).toBeInTheDocument()
    const box = screen.getByRole('checkbox', { name: /Also delete the 2 cues no other stack uses/ })
    expect(box).not.toBeChecked()
    // Nothing is asked of the desk until it is ticked.
    expect(previewArgs).toHaveBeenLastCalledWith({ projectId: 1, cueIds: [2, 3] }, { skip: true })
  })

  it('deletes the stack alone by default', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(deleteStack).toHaveBeenCalledWith({ projectId: 1, stackId: 1, deleteLooseCues: false })
  })

  it('ticked, lists what goes with the cues and deletes them too', () => {
    draw()
    fireEvent.click(screen.getByRole('checkbox'))
    expect(previewArgs).toHaveBeenLastCalledWith({ projectId: 1, cueIds: [2, 3] }, { skip: false })
    expect(screen.getByText('Moon rise: a pad on Main · slot A1')).toBeInTheDocument()
    expect(screen.getByText('This deletes “Act 1” and its list. The 1 cue other stacks use stays.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(deleteStack).toHaveBeenCalledWith({ projectId: 1, stackId: 1, deleteLooseCues: true })
  })

  it('offers no checkbox when every cue is in another stack too', () => {
    const shared = stack(3, 'Encore', [cueRow(1, { entryId: 301 })])
    draw(shared, [ACT1, ACT2, shared])
    expect(screen.queryByRole('checkbox')).toBeNull()
    expect(screen.getByText('This deletes “Encore” and its list. Its cue stays.')).toBeInTheDocument()
  })
})
