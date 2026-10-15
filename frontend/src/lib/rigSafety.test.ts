import { describe, expect, it } from 'vitest'
import { NOTHING_SAFE, type CommandRun, type CommandRunUnit, type RigSafetyState } from '@/api/rigSafetyApi'
import { blackoutReason, clockLabel, remainingAt, restrikeLeft, rigBanner, runOutcome, runProgress, runTitle, unitLine } from './rigSafety'

const unit = (fixtureName: string, status: CommandRunUnit['status'], over: Partial<CommandRunUnit> = {}): CommandRunUnit => ({
  fixture: fixtureName.toLowerCase(), fixtureName, command: 'lampOn', status, ...over,
})

const run = (units: CommandRunUnit[], over: Partial<CommandRun> = {}): CommandRun => ({
  id: 'r1', role: 'LAMP_ON', purpose: 'BULK', startedAt: '2026-10-15T21:00:00.000Z', units, finished: false, ...over,
})

const state = (over: Partial<RigSafetyState>): RigSafetyState => ({ ...NOTHING_SAFE, projectId: 1, ...over })

describe('rigSafety', () => {
  it('draws a run over everything, then ready, then the blackout, then a run that ended short', () => {
    const going = run([unit('MAC 1', 'RUNNING')])
    expect(rigBanner(state({ run: going, ready: true }), new Set())?.kind).toBe('run')
    expect(rigBanner(state({ ready: true, blackout: true }), new Set())?.kind).toBe('ready')
    expect(rigBanner(state({ blackout: true }), new Set())?.kind).toBe('blackout')
    const short = run([unit('MAC 1', 'DONE'), unit('MAC 2', 'SKIPPED')], { finished: true })
    expect(rigBanner(state({ run: short }), new Set())?.kind).toBe('finished')
    expect(rigBanner(state({ run: short }), new Set(['r1']))).toBeNull()
    expect(rigBanner(state({ run: run([unit('MAC 1', 'DONE')], { finished: true }) }), new Set())).toBeNull()
    expect(rigBanner(state({ run: run([unit('MAC 1', 'CANCELLED')], { finished: true, cancelled: true }) }), new Set())).toBeNull()
  })

  it('names what a run is doing', () => {
    expect(runTitle(run([], { purpose: 'MAKE_SAFE', role: 'LAMP_OFF' }))).toBe('Making the rig safe')
    expect(runTitle(run([], { purpose: 'UNDO' }))).toBe('Undoing Make rig safe')
    expect(runTitle(run([unit('A', 'QUEUED'), unit('B', 'QUEUED')], { role: 'RESET' }))).toBe('Reset · 2 units')
  })

  it('counts progress, and the next lamp waiting on its restrike down from the frame', () => {
    const r = run([
      unit('MAC 1', 'DONE'),
      unit('MAC 2', 'RUNNING', { remainingMs: 4000 }),
      unit('MAC 3', 'WAITING', { remainingMs: 252_000 }),
      unit('MAC 4', 'WAITING', { remainingMs: 300_000 }),
    ])
    expect(runProgress(r, 1000, 1000)).toBe('1 of 4 done · MAC 2 running · MAC 3 in 4:12 — restrike (+1 waiting)')
    expect(runProgress(r, 1000, 61_000)).toBe('1 of 4 done · MAC 2 running · MAC 3 in 3:12 — restrike (+1 waiting)')
  })

  it('sums a finished run, and words each unit', () => {
    const r = run([unit('A', 'DONE'), unit('B', 'SKIPPED', { message: 'Running reset' }), unit('C', 'CUT_SHORT'), unit('D', 'CANCELLED')])
    expect(runOutcome(r)).toBe('1 done · 1 skipped · 1 cut short · 1 cancelled')
    expect(unitLine(r.units[1], 0, 0)).toBe('Skipped — Running reset')
    expect(unitLine(unit('E', 'WAITING', { remainingMs: 65_000 }), 0, 5_000)).toBe('Restrike in 1:00')
    expect(unitLine(unit('F', 'SKIPPED', { code: 'COMMAND_PARKED' }), 0, 0)).toBe('Skipped (COMMAND_PARKED)')
  })

  it('says why a blackout is not ready', () => {
    expect(blackoutReason(state({ notOff: ['MAC 2'] }))).toBe('a lamp is not known to be off: MAC 2')
    expect(blackoutReason(state({ notOff: ['A', 'B', 'C', 'D'] }))).toBe('4 lamps are not known to be off: A, B, C and 1 more')
    expect(blackoutReason(state({}))).toBe('Output has changed since it was made safe')
  })

  it('counts a restrike and a clock down from the frame, never below zero', () => {
    const s = state({ lampsOff: [{ fixture: 'mac', offAt: 'x', restrikeRemainingMs: 10_000 }], receivedAt: 1000 })
    expect(restrikeLeft(s, 'mac', 4000)).toBe(7000)
    expect(restrikeLeft(s, 'mac', 20_000)).toBe(0)
    expect(restrikeLeft(s, 'other', 0)).toBe(0)
    expect(remainingAt(null, 0, 0)).toBe(0)
    expect(clockLabel(252_000)).toBe('4:12')
    expect(clockLabel(4_001)).toBe('0:05')
  })
})
