// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CueStack } from '@/api/cueStacksApi'

const reorderStacks = vi.fn()
// The list's drag ends here; the test calls it as dnd-kit would.
let dragEnd: ((event: { active: { id: number }; over: { id: number } | null }) => void) | undefined
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: ({ onDragEnd, children }: { onDragEnd: typeof dragEnd; children: React.ReactNode }) => {
      dragEnd = onDragEnd
      return children
    },
  }
})

vi.mock('@/store/cueStacks', () => ({
  useCreateProjectCueStackMutation: () => [vi.fn(), { isLoading: false }],
  useSaveProjectCueStackMutation: () => [vi.fn(), { isLoading: false }],
  useDeleteProjectCueStackMutation: () => [vi.fn()],
  useReorderCueStacksMutation: () => [reorderStacks],
  useSortCueStackByCueNumberMutation: () => [vi.fn()],
}))
vi.mock('@/components/cues/CueStackForm', () => ({ CueStackForm: () => null }))
vi.mock('./DeleteStackDialog', () => ({ DeleteStackDialog: () => null }))
vi.mock('./StackFxAmountControl', () => ({ StackFxAmountControl: () => null }))
vi.mock('@/components/cues/StackMovingChip', () => ({ StackMovingChip: () => null }))

import { ShowOverview } from './ShowOverview'
import { assignTargetNow, closeAssignToSurface } from '@/components/surfaces/AssignToSurface'

const mkStack = (over: Partial<CueStack>): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

afterEach(() => {
  cleanup()
  reorderStacks.mockClear()
  dragEnd = undefined
})

describe('ShowOverview', () => {
  it('opens Assign to surface… on a stack from its menu', async () => {
    render(<ShowOverview projectId={1} stacks={[mkStack({ id: 2, uuid: 'st-2', name: 'Act 1' })]} activeStackId={null} onDrillStack={vi.fn()} />)
    fireEvent.keyDown(screen.getByRole('button', { name: 'Stack actions' }), { key: 'Enter' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Assign to surface…' }))
    expect(assignTargetNow()).toEqual({ name: 'Act 1', subjects: [{ type: 'record', kind: 'STACK', uuid: 'st-2' }], projectId: 1 })
    closeAssignToSurface()
  })

  it('lists the busk stacks after a Busk divider, whatever their place in the order', () => {
    const stacks = [
      mkStack({ id: 1, name: 'Song 3', sortOrder: 0, inRunningOrder: false }),
      mkStack({ id: 2, name: 'Act 1', sortOrder: 1 }),
      mkStack({ id: 3, name: 'Act 2', sortOrder: 2 }),
    ]
    render(<ShowOverview projectId={1} stacks={stacks} activeStackId={null} onDrillStack={vi.fn()} />)
    const text = document.body.textContent ?? ''
    const at = (word: string) => text.indexOf(word)
    expect(screen.getByLabelText('Busk stacks')).toBeInTheDocument()
    expect(at('Act 1')).toBeLessThan(at('Act 2'))
    expect(at('Act 2')).toBeLessThan(at('Busk'))
    expect(at('Busk')).toBeLessThan(at('Song 3'))
  })

  it('numbers the running order and leaves busk stacks unnumbered', () => {
    const stacks = [
      mkStack({ id: 1, name: 'Act 1', sortOrder: 0 }),
      mkStack({ id: 2, name: 'Song', sortOrder: 1, inRunningOrder: false }),
    ]
    const { container } = render(<ShowOverview projectId={1} stacks={stacks} activeStackId={null} onDrillStack={vi.fn()} />)
    const numbers = Array.from(container.querySelectorAll('span.font-mono.w-6')).map((n) => n.textContent)
    expect(numbers).toEqual(['1', ''])
  })

  it('draws no divider when every stack is in the running order', () => {
    render(<ShowOverview projectId={1} stacks={[mkStack({ id: 1 })]} activeStackId={null} onDrillStack={vi.fn()} />)
    expect(screen.queryByLabelText('Busk stacks')).toBeNull()
  })

  it('a drag within a side sends the whole order, show part then busk part, and one across the divider sends nothing', () => {
    const stacks = [
      mkStack({ id: 1, name: 'Song A', sortOrder: 0, inRunningOrder: false }),
      mkStack({ id: 2, name: 'Act 1', sortOrder: 1 }),
      mkStack({ id: 3, name: 'Song B', sortOrder: 2, inRunningOrder: false }),
      mkStack({ id: 4, name: 'Act 2', sortOrder: 3 }),
    ]
    render(<ShowOverview projectId={1} stacks={stacks} activeStackId={null} onDrillStack={vi.fn()} />)

    act(() => dragEnd!({ active: { id: 4 }, over: { id: 2 } }))
    expect(reorderStacks).toHaveBeenCalledWith({ projectId: 1, stackIds: [4, 2, 1, 3] })

    reorderStacks.mockClear()
    act(() => dragEnd!({ active: { id: 3 }, over: { id: 1 } }))
    expect(reorderStacks).toHaveBeenCalledWith({ projectId: 1, stackIds: [2, 4, 3, 1] })

    reorderStacks.mockClear()
    act(() => dragEnd!({ active: { id: 1 }, over: { id: 2 } }))
    expect(reorderStacks).not.toHaveBeenCalled()
  })
})
