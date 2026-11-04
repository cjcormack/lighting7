// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { CueStack } from '../../api/cueStacksApi'
import type { PromptBookDetails, Region } from '../../api/promptBooksApi'
import type { FlatCue } from './desync'

const upsertAnchor = vi.fn()
const createCue = vi.fn()
const patchCue = vi.fn()
const createStack = vi.fn()
const reorderCues = vi.fn()
const recordProgrammer = vi.fn()
const toastError = vi.fn()
const announceWrite = vi.fn()

const unwrapped = (value: unknown) => ({ unwrap: () => Promise.resolve(value) })

vi.mock('../../store/promptBooks', () => ({
  useUpsertAnchorMutation: () => [upsertAnchor],
  useDeleteAnchorMutation: () => [vi.fn()],
}))
vi.mock('../../store/cues', () => ({
  useCreateProjectCueMutation: () => [createCue],
  usePatchProjectCueMutation: () => [patchCue],
}))
vi.mock('../../store/cueStacks', () => ({
  useCreateProjectCueStackMutation: () => [createStack],
  useReorderCueStackCuesMutation: () => [reorderCues],
}))
vi.mock('../../store/programmerOps', () => ({ useRecordProgrammerMutation: () => [recordProgrammer] }))
vi.mock('../../components/programmer/writeToasts', () => ({
  announceWrite: (note: unknown) => announceWrite(note),
  recordNote: () => ({ title: 'Recorded', warn: false }),
}))
vi.mock('sonner', () => ({ toast: { error: (...args: unknown[]) => toastError(...args) } }))

import { useBookAnchors } from './useBookAnchors'

const region: Region = [{ page: 2, x: 0.1, y: 0.2, w: 0.5, h: 0.03 }]
const book = { anchors: [], annotations: [] } as unknown as PromptBookDetails
const stack = { id: 7, name: 'Act 1', type: 'STACK', cues: [] } as unknown as CueStack

function setUp() {
  const { result } = renderHook(() =>
    useBookAnchors({
      projectId: 1,
      book,
      stacks: [stack],
      cueOrder: [],
      labelByEntry: new Map(),
      followByEntry: new Map(),
      viewerRef: { current: null },
    }),
  )
  act(() => result.current.requestRecord(region))
  return result
}

beforeEach(() => {
  vi.clearAllMocks()
  upsertAnchor.mockReturnValue(unwrapped({}))
})

describe('useBookAnchors · recording a cue from the selection', () => {
  it('records the programmer into the stack and anchors the new entry to the selection', async () => {
    recordProgrammer.mockResolvedValue({ data: { cue: { id: 40, entryId: 90 } } })
    const result = setUp()
    expect(result.current.anchorPicker).toEqual({ region, record: true })

    await act(() =>
      result.current.createCueFromSelection({
        name: 'Storm',
        cueNumber: '12',
        notes: 'Thunder on the GO',
        stack: { kind: 'existing', id: 7 },
        record: true,
        mask: ['COLOUR'],
      }),
    )

    expect(createCue).not.toHaveBeenCalled()
    expect(recordProgrammer).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: 1,
        mode: 'CREATE',
        cueStackId: 7,
        name: 'Storm',
        cueNumber: '12',
        mask: ['COLOUR'],
      }),
    )
    expect(announceWrite).toHaveBeenCalledOnce()
    expect(upsertAnchor).toHaveBeenCalledWith({ projectId: 1, entryId: 90, cueId: 40, region, label: 'Q12' })
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 40, notes: 'Thunder on the GO' })
    expect(result.current.anchorPicker).toBeNull()
  })

  it('says a refused Record, anchors nothing and keeps the picker open', async () => {
    recordProgrammer.mockResolvedValue({ error: { status: 400, data: { error: 'Nothing to record' } } })
    const result = setUp()

    await act(() =>
      result.current.createCueFromSelection({
        name: 'Storm',
        cueNumber: null,
        notes: null,
        stack: { kind: 'existing', id: 7 },
        record: true,
      }),
    )

    expect(toastError).toHaveBeenCalledOnce()
    expect(upsertAnchor).not.toHaveBeenCalled()
    expect(result.current.anchorPicker).not.toBeNull()
  })

  it('still makes an empty cue when not recording', async () => {
    createCue.mockReturnValue(unwrapped({ id: 41, entryId: 91 }))
    const result = setUp()

    await act(() =>
      result.current.createCueFromSelection({
        name: 'Blackout',
        cueNumber: null,
        notes: null,
        stack: { kind: 'existing', id: 7 },
        record: false,
      }),
    )

    expect(recordProgrammer).not.toHaveBeenCalled()
    expect(createCue).toHaveBeenCalledWith(expect.objectContaining({ name: 'Blackout', cueStackId: 7 }))
    expect(upsertAnchor).toHaveBeenCalledWith(expect.objectContaining({ entryId: 91, label: 'Blackout' }))
  })
})

describe('useBookAnchors · followers', () => {
  const flat = (entryId: number, label: string) => ({ entryId, cueId: entryId, label } as unknown as FlatCue)
  const anchor = (entryId: number, y: number) => ({
    cueId: entryId,
    entryId,
    label: null,
    region: [{ page: 0, x: 0.1, y, w: 0.5, h: 0.03 }],
  })
  const followed = () => {
    const { result } = renderHook(() =>
      useBookAnchors({
        projectId: 1,
        book: { anchors: [anchor(1, 0.1), anchor(2, 0.5)], annotations: [] } as unknown as PromptBookDetails,
        stacks: [stack],
        cueOrder: [flat(1, 'Q1'), flat(2, 'Q2'), flat(3, 'Q3')],
        labelByEntry: new Map(),
        followByEntry: new Map([[2, { headEntryId: 1, afterLabel: 'Q1', delayMs: 1000 }]]),
        viewerRef: { current: null },
      }),
    )
    return result
  }

  it('leaves a follower\'s stored anchor out of everything it answers', () => {
    const result = followed()
    expect(result.current.anchors.map((a) => a.entryId)).toEqual([1])
    expect([...result.current.anchorByEntry.keys()]).toEqual([1])
  })

  it('hints only at an ordinary unanchored cue, in words that do not read as a follow', () => {
    expect([...followed().current.anchorHintByEntry]).toEqual([[3, 'after Q1']])
  })

  it('will not arm a follower for placing', () => {
    const result = followed()
    act(() => result.current.togglePlacing(2))
    expect(result.current.placingEntryId).toBeNull()
  })
})
