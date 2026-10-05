// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CommandPropertyDescriptor } from '@/store/fixtures'

let resolveRun: ((value: { completed: boolean }) => void) | null = null
const run = vi.fn(() => ({
  unwrap: () => new Promise<{ completed: boolean }>((resolve) => { resolveRun = resolve }),
}))
const toastSuccess = vi.fn()
let parks: { universe: number; channel: number; value: number }[] = []

vi.mock('@/store/commands', () => ({ useRunFixtureCommandMutation: () => [run] }))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 1 } }) }))
vi.mock('@/store/patches', () => ({ useVisiblePatchListQuery: () => ({ data: [{ id: 7, key: 'rev' }] }) }))
vi.mock('@/store/park', () => ({ useGetParkStateListQuery: () => ({ data: parks }) }))
vi.mock('sonner', () => ({ toast: { success: (...a: unknown[]) => toastSuccess(...a), warning: vi.fn(), error: vi.fn() } }))

import { FixtureCommandsMenu, alongsideSentence, holdLabel, parkedChannelOf } from './FixtureCommandsMenu'

const command = (name: string, displayName: string, over: Partial<CommandPropertyDescriptor> = {}): CommandPropertyDescriptor => ({
  type: 'command', name, displayName, category: 'command',
  description: `${displayName} does its thing.`, holdMs: 3000, confirm: true,
  channel: { universe: 0, channelNo: 12 }, dedicated: true, ...over,
})

const COMMANDS = [
  command('resetScroller', 'Reset scroller'),
  command('reset', 'Reset fixture', {
    holdMs: 5000,
    alongside: [{ channel: { universe: 0, channelNo: 3 }, level: 200, why: 'CTC filter in' }],
  }),
]

function openMenu() {
  fireEvent.keyDown(screen.getByRole('button', { name: /Commands/ }), { key: 'Enter' })
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  run.mockClear()
  toastSuccess.mockClear()
  resolveRun = null
  parks = []
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('FixtureCommandsMenu', () => {
  it('lists each command with its hold, and runs nothing on a pick until the confirm', () => {
    render(<FixtureCommandsMenu fixtureKey="rev" fixtureName="Rev 1" commands={COMMANDS} canRun />)
    openMenu()
    expect(screen.getByRole('menuitem', { name: /Reset scroller\s*3 s/ })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: /Reset fixture\s*5 s/ })).toBeTruthy()

    fireEvent.click(screen.getByRole('menuitem', { name: /Reset fixture/ }))
    expect(run).not.toHaveBeenCalled()
    // The confirm names the unit and the command, says what it does, how long and what else it sets.
    expect(screen.getByRole('alertdialog', { name: 'Reset fixture on Rev 1?' })).toBeTruthy()
    expect(screen.getByText('Reset fixture does its thing.')).toBeTruthy()
    expect(screen.getByText(/holds it for 5 s.*also sets: CTC filter in\./)).toBeTruthy()
  })

  it('runs on the confirm, counts the hold down, and says when it is done', async () => {
    render(<FixtureCommandsMenu fixtureKey="rev" fixtureName="Rev 1" commands={COMMANDS} canRun />)
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: /Reset scroller/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Reset scroller' }))
    expect(run).toHaveBeenCalledWith({ projectId: 1, patchId: 7, command: 'resetScroller' })

    const button = screen.getByRole('button', { name: /Reset scroller · 3 s/ })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    act(() => { vi.advanceTimersByTime(1200) })
    expect(screen.getByRole('button', { name: /Reset scroller · 2 s/ })).toBeTruthy()

    await act(async () => { resolveRun?.({ completed: true }) })
    expect(toastSuccess).toHaveBeenCalledWith('Reset scroller on Rev 1: done')
    expect(screen.getByRole('button', { name: /Commands/ })).toBeTruthy()
  })

  it('is inert on a read-only surface, and a command that asks nothing runs at once', () => {
    const { rerender } = render(<FixtureCommandsMenu fixtureKey="rev" fixtureName="Rev 1" commands={COMMANDS} canRun={false} />)
    expect((screen.getByRole('button', { name: /Commands/ }) as HTMLButtonElement).disabled).toBe(true)

    rerender(<FixtureCommandsMenu fixtureKey="rev" fixtureName="Rev 1" commands={[command('blink', 'Blink', { confirm: false })]} canRun />)
    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: /Blink/ }))
    expect(run).toHaveBeenCalledWith({ projectId: 1, patchId: 7, command: 'blink' })
  })

  it('greys out a command whose channel or precondition is parked, naming the channel', () => {
    parks = [{ universe: 0, channel: 3, value: 0 }]
    render(<FixtureCommandsMenu fixtureKey="rev" fixtureName="Rev 1" commands={COMMANDS} canRun />)
    openMenu()
    const reset = screen.getByRole('menuitem', { name: /Reset fixture/ })
    expect(reset.getAttribute('aria-disabled')).toBe('true')
    expect(reset.textContent).toContain('Channel 3 is parked')
    fireEvent.click(reset)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    // The scroller reset holds only channel 12, which is free.
    expect(screen.getByRole('menuitem', { name: /Reset scroller/ }).getAttribute('aria-disabled')).toBeNull()
  })

  it('finds a parked channel on the command first, then its preconditions, on its own universe', () => {
    const reset = COMMANDS[1]
    expect(parkedChannelOf(reset, [])).toBeNull()
    expect(parkedChannelOf(reset, [{ universe: 1, channel: 12, value: 0 }])).toBeNull()
    expect(parkedChannelOf(reset, [{ universe: 0, channel: 3, value: 0 }, { universe: 0, channel: 12, value: 0 }])).toBe(12)
  })

  it('reads the preconditions mid-sentence, keeping an initialism', () => {
    expect(alongsideSentence(['CTC filter in', 'Open gobo', 'Prism in, not rotating'])).toBe('CTC filter in; open gobo; prism in, not rotating')
  })

  it('writes a hold in seconds', () => {
    expect(holdLabel(3000)).toBe('3 s')
    expect(holdLabel(1500)).toBe('1.5 s')
  })
})
