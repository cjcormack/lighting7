// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import type { HistoryMoveAnswer, HistoryMoveArg, HistoryRefusal } from '@/store/history'
import { movedMessage, runHistoryMove, type HistoryMoveDeps, type HistoryQuestion } from './undoFlow'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
const { historyRefusalOf } = await import('@/store/history')

const ANSWER: HistoryMoveAnswer = { moved: [{ id: 'e-3', label: 'Delete cue Q12' }] }
const ARG: HistoryMoveArg = { projectId: 1, direction: 'undo', entryId: 'e-3', fadeMs: 0 }

/** The desk's 409 bodies, as `routes/projectHistory.kt` answers them. */
const conflict = historyRefusalOf({
  status: 409,
  data: {
    error: '“Delete cue Q12” has been changed since by 1 later change',
    code: 'HISTORY_CONFLICT',
    later: [{ id: 'e-4', label: 'Renumber Act 1', source: { kind: 'ACCOUNT', name: 'Sam' } }],
  },
})
const confirm = historyRefusalOf({
  status: 409,
  data: { error: 'This undo moves the stage', code: 'HISTORY_CONFIRM', moves: ['Live cue “Q11” uses Look “Warm wash”'] },
})
const blocked = historyRefusalOf({
  status: 409,
  data: { error: 'Fixture “spot-3” cannot be put back: a fixture called “spot-3” exists now', code: 'HISTORY_UNDO_BLOCKED', record: 'Fixture “spot-3”', reason: 'a fixture called “spot-3” exists now' },
})

function deps(answers: Array<HistoryRefusal | 'ok'>, replies: boolean[] = [], locked = false, mine = true) {
  const sent: HistoryMoveArg[] = []
  const asked: HistoryQuestion[] = []
  const d: HistoryMoveDeps & { sent: HistoryMoveArg[]; asked: HistoryQuestion[] } = {
    sent,
    asked,
    send: vi.fn(async (arg: HistoryMoveArg) => {
      sent.push(arg)
      const next = answers.shift()
      return next === 'ok' || next == null ? { ok: true as const, answer: ANSWER } : { ok: false as const, refusal: next }
    }),
    ask: vi.fn(async (question: HistoryQuestion) => {
      asked.push(question)
      return replies.shift() ?? false
    }),
    locked,
    mine,
    unlock: vi.fn(),
    done: vi.fn(),
    refused: vi.fn(),
  }
  return d
}

describe('historyRefusalOf', () => {
  it('reads each 409 by its code', () => {
    expect(conflict).toMatchObject({ kind: 'conflict', later: [{ id: 'e-4' }] })
    expect(confirm).toMatchObject({ kind: 'confirm', moves: ['Live cue “Q11” uses Look “Warm wash”'] })
    expect(blocked).toMatchObject({ kind: 'blocked', record: 'Fixture “spot-3”' })
    expect(historyRefusalOf({ status: 409, data: { error: 'Nothing to undo', code: 'HISTORY_EMPTY' } }).kind).toBe('empty')
    expect(historyRefusalOf({ status: 'FETCH_ERROR', error: 'down' }).kind).toBe('other')
  })
})

describe('runHistoryMove', () => {
  it('sends once and announces what came back', async () => {
    const d = deps(['ok'])
    expect(await runHistoryMove(d, ARG)).toBe(ANSWER)
    expect(d.sent).toEqual([ARG])
    expect(d.done).toHaveBeenCalledWith(ARG, ANSWER)
    expect(d.asked).toEqual([])
  })

  it('asks about a conflict, and resends back to here when the operator takes the later changes', async () => {
    const d = deps([conflict, 'ok'], [true])
    expect(await runHistoryMove(d, ARG)).toBe(ANSWER)
    expect(d.asked[0]).toMatchObject({ kind: 'conflict', later: [{ id: 'e-4', label: 'Renumber Act 1' }], mine: true })
    expect(d.sent[1]).toEqual({ ...ARG, backToHere: true })
  })

  it("says whose entry it is, since back to here on your own takes every later one of yours too", async () => {
    const d = deps([conflict, 'ok'], [true], false, false)
    await runHistoryMove(d, ARG)
    expect(d.asked[0]).toMatchObject({ kind: 'conflict', mine: false })
  })

  it('never sends back to here before a conflict has been asked about', async () => {
    const d = deps(['ok'])
    await runHistoryMove(d, ARG)
    expect(d.sent[0].backToHere).toBeUndefined()
  })

  it('sends nothing more when a conflict is cancelled', async () => {
    const d = deps([conflict], [false])
    expect(await runHistoryMove(d, ARG)).toBeNull()
    expect(d.sent).toHaveLength(1)
    expect(d.refused).not.toHaveBeenCalled()
  })

  it('asks before moving the stage, and resends with confirm', async () => {
    const d = deps([confirm, 'ok'], [true])
    await runHistoryMove(d, ARG)
    expect(d.asked[0]).toEqual({ kind: 'confirm', direction: 'undo', moves: ['Live cue “Q11” uses Look “Warm wash”'] })
    expect(d.sent[1]).toEqual({ ...ARG, confirm: true })
  })

  it('takes a conflict and then a stage move, each asked once', async () => {
    const d = deps([conflict, confirm, 'ok'], [true, true])
    await runHistoryMove(d, ARG)
    expect(d.asked.map((q) => q.kind)).toEqual(['conflict', 'confirm'])
    expect(d.sent[2]).toEqual({ ...ARG, backToHere: true, confirm: true })
  })

  it('reports a blocked undo in the record’s words, once, and does not retry', async () => {
    const d = deps([blocked])
    expect(await runHistoryMove(d, ARG)).toBeNull()
    expect(d.refused).toHaveBeenCalledWith(ARG, blocked)
    expect(d.sent).toHaveLength(1)
  })

  it('has no back-to-here for a redo: its conflict is a refusal', async () => {
    const redo = { ...ARG, direction: 'redo' as const }
    const d = deps([conflict])
    await runHistoryMove(d, redo)
    expect(d.asked).toEqual([])
    expect(d.refused).toHaveBeenCalledWith(redo, conflict)
  })

  it('asks to unlock first while the show is locked, and sends nothing on Cancel', async () => {
    const cancelled = deps(['ok'], [false], true)
    expect(await runHistoryMove(cancelled, ARG)).toBeNull()
    expect(cancelled.asked).toEqual([{ kind: 'lock', direction: 'undo' }])
    expect(cancelled.sent).toEqual([])

    const unlocked = deps(['ok'], [true], true)
    await runHistoryMove(unlocked, ARG)
    expect(unlocked.unlock).toHaveBeenCalled()
    expect(unlocked.sent).toEqual([ARG])
  })
})

describe('movedMessage', () => {
  it('says what came back', () => {
    expect(movedMessage('undo', ANSWER)).toBe('Undone · Delete cue Q12')
    expect(movedMessage('redo', { moved: [{ id: 'e-3', label: 'A' }, { id: 'e-2', label: 'B' }] })).toBe('Redone · A and 1 more')
  })
})
