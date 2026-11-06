// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { HistoryMoveArg, HistoryPage } from '@/store/history'

/**
 * `useHistoryMove`'s toasts and their way back (undo plan §4.4, D11): a gesture's entries undone
 * newest first and announced as one toast, whose Redo brings them back oldest first, whose Undo takes
 * them back newest first again.
 */
const desk = vi.hoisted(() => ({
  sent: [] as HistoryMoveArg[],
  page: null as HistoryPage | null,
  running: false,
  lockRequested: false,
  inProduction: true,
}))
const toasts = vi.hoisted(() => ({ last: null as null | { message: string; action?: { label: string; onClick: () => void } } }))

vi.mock('sonner', () => ({
  toast: {
    success: (message: string, options?: { action?: { label: string; onClick: () => void } }) => {
      toasts.last = { message, action: options?.action }
    },
    error: (message: string) => {
      toasts.last = { message }
    },
    dismiss: () => {},
  },
}))
vi.mock('@/store/history', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/history')>()),
  useHistoryState: () => ({ projectId: 1, revision: 0, showRevision: 0, undo: null, redo: null, programmer: { undo: [], redo: [] } }),
  useHistoryMoveMutation: () => [
    (arg: HistoryMoveArg) => {
      desk.sent.push(arg)
      const id = arg.entryId ?? 'e-?'
      return { unwrap: () => Promise.resolve({ moved: [{ id, label: `Change ${id}` }] }) }
    },
  ],
  historySlice: { endpoints: { historyPage: { initiate: () => 'page' } } },
}))
vi.mock('react-redux', () => ({
  useDispatch: () => () => ({ unwrap: () => Promise.resolve(desk.page) }),
  useSelector: () => desk.lockRequested,
}))
vi.mock('@/store/cueStacks', () => ({
  useProjectProgramStateQuery: () => ({ data: desk.running ? { activeStackId: 10 } : undefined }),
}))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1, inProduction: desk.inProduction } }),
}))
vi.mock('@/lib/programmerFade', () => ({ useProgrammerFade: () => '0' }))

const { useHistoryMove } = await import('./HistoryMove')
const { parseHistoryPage } = await import('@/store/history')

afterEach(() => {
  desk.sent = []
  desk.running = false
  desk.lockRequested = false
  desk.inProduction = true
  toasts.last = null
})

const moves = () => desk.sent.map((arg) => `${arg.direction} ${arg.entryId}`)

describe("a toast's Undo, and its way back", () => {
  it('undoes newest first in one toast, redoes oldest first, and undoes newest first again', async () => {
    desk.page = parseHistoryPage({
      projectId: 1,
      rows: [
        { id: 'e-2', kind: 'SHOW', at: '', label: 'Record “Q12”', requestIds: ['h-2'], mine: true },
        { id: 'e-1', kind: 'SHOW', at: '', label: 'Stack “Act 2” added', requestIds: ['h-1'], mine: true },
      ],
    })
    const { result } = renderHook(() => useHistoryMove())

    await act(async () => {
      await result.current.undoRequests(1, ['h-1', 'h-2'])
    })
    expect(moves()).toEqual(['undo e-2', 'undo e-1'])
    expect(toasts.last?.message).toBe('Undone · Change e-2 and 1 more')
    expect(toasts.last?.action?.label).toBe('Redo')

    desk.sent = []
    await act(async () => toasts.last!.action!.onClick())
    await act(async () => {})
    expect(moves()).toEqual(['redo e-1', 'redo e-2'])
    expect(toasts.last?.action?.label).toBe('Undo')

    desk.sent = []
    await act(async () => toasts.last!.action!.onClick())
    await act(async () => {})
    expect(moves()).toEqual(['undo e-2', 'undo e-1'])
  })

  it('says a change already undone is, rather than that it is gone', async () => {
    desk.page = parseHistoryPage({
      projectId: 1,
      rows: [{ id: 'e-1', kind: 'SHOW', at: '', label: 'Delete “Warm”', requestIds: ['h-1'], undone: true }],
    })
    const { result } = renderHook(() => useHistoryMove())
    await act(async () => {
      expect(await result.current.undoRequests(1, ['h-1'])).toBe(false)
    })
    expect(desk.sent).toEqual([])
    expect(toasts.last?.message).toBe('That change is already undone')
  })
})

describe('the show-editing lock', () => {
  it('asks to unlock before undoing a show entry on a running show in production', async () => {
    desk.running = true
    desk.lockRequested = true
    const { result } = renderHook(() => useHistoryMove())
    await act(async () => {
      void result.current.move('undo', { entryId: 'e-1' })
    })
    expect(desk.sent).toEqual([])
  })

  it('undoes at once on a running show that is not in production', async () => {
    desk.running = true
    desk.lockRequested = true
    desk.inProduction = false
    const { result } = renderHook(() => useHistoryMove())
    await act(async () => {
      await result.current.move('undo', { entryId: 'e-1' })
    })
    expect(moves()).toEqual(['undo e-1'])
  })
})
