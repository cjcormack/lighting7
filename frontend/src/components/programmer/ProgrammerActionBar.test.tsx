// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IncludedTarget } from '@/api/programmerWsApi'

// Radix positions its tooltip content with `useSize`, which needs a `ResizeObserver` jsdom has no
// implementation of. Only the *content* needs one — every other assertion here reads the trigger —
// so a no-op stub is enough to let the one hover assertion below open the card.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', NoopResizeObserver)

let summary = { blind: false, entryCount: 0, lastIncluded: null as IncludedTarget | null }
let effects: { programmerOwned: boolean }[] = []

const programmerClearAll = vi.fn()
const programmerSetBlind = vi.fn()
vi.mock('@/store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: summary }),
  programmerClearAll: (...a: unknown[]) => programmerClearAll(...a),
  programmerSetBlind: (...a: unknown[]) => programmerSetBlind(...a),
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: effects }) }))
const desk = { connected: true }
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => desk.connected }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: [{ id: 2, name: 'Act 1', cues: [] }] }),
}))

const sheets = {
  openRecord: vi.fn(),
  openRecordLook: vi.fn(),
  openInclude: vi.fn(),
  openUpdate: vi.fn(),
}
vi.mock('./ProgrammerSheets', () => ({ useProgrammerSheets: () => sheets }))

import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { ProgrammerActionBar } from './ProgrammerActionBar'

/**
 * Radix's DropdownMenuTrigger opens on `pointerdown`, not `click` — so `fireEvent.click` alone
 * leaves the menu shut and every assertion below it fails for the wrong reason.
 */
function openRecordMenu() {
  fireEvent.pointerDown(
    screen.getByRole('button', { name: 'Record destination' }),
    { button: 0, ctrlKey: false, pointerType: 'mouse' },
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.localStorage.clear()
  summary = { blind: false, entryCount: 0, lastIncluded: null }
  effects = []
  desk.connected = true
  resetProgrammerFadeStore()
})

const CUE: IncludedTarget = {
  kind: 'CUE',
  cueId: 5,
  cueStackId: 2,
  cueNumber: 'Q4',
  cueName: 'Warm Wash',
}

describe('ProgrammerActionBar', () => {
  it('carries the three zone labels on the controls they introduced', async () => {
    // The zones were the fix for seven identical outline buttons in one row. Session 1 of the space
    // plan deletes the *labels* — a 9px word above every control is 20px of a 900px screen, on a
    // page whose whole point is the grid below — but not what they said: each one now rides the
    // control it named, as that control's hover text. That is the promise, so this is the test.
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.getByTitle('Load')).toHaveAccessibleName('Include…')
    expect(screen.getByTitle('Save')).toHaveAccessibleName('Record')

    // Stage leads Clear's *Radix* tooltip instead, because Clear is the one control here already
    // inside a `TooltipTrigger` — a native `title` beside it would mean the browser's balloon and
    // Radix's card both answering one hover.
    const clear = screen.getByRole('button', { name: 'Clear' })
    expect(clear.closest('[title]')).toBeNull()
    fireEvent.focus(screen.getByRole('button', { name: 'Clear' }).parentElement!)
    expect((await screen.findAllByText(/Stage —/))[0]).toBeTruthy()
  })

  it('keeps every control reachable by name when its word is hidden', () => {
    // Below `@[800px]` Clear loses its word and Include and Record become icons. An `aria-label`
    // on each is what makes that a *visual* shrink rather than an information one — and it is what
    // these tests address the buttons by, since jsdom applies no container query at all.
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.getByRole('button', { name: 'Clear' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Include…' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Record' })).toBeTruthy()
  })

  it('keeps every control on the surface — no overflow kebab', () => {
    // The old bar hid Record / Record look / Include / Update behind a `MoreHorizontal` below `sm`,
    // which put the whole point of the programmer one tap further away on the surface most likely
    // to be used standing up.
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.queryByRole('button', { name: /more/i })).toBeNull()
  })

  it('hosts no grid tools — Groups and Columns are row B\'s', () => {
    // `sheetControls` went with the zones. Groups and Columns describe what the *grid* shows, and
    // a band spanning the whole page reached across the rail to say it; they render in the grid's
    // own toolbar now, beside the filter and the scope.
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.queryByTitle('Show group rows with their members')).toBeNull()
    expect(screen.queryByTitle('Choose visible columns')).toBeNull()
  })

  it('has no Update — that moved to the source strip, beside what it writes to', () => {
    summary = { ...summary, entryCount: 4, lastIncluded: CUE }
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.queryByRole('button', { name: /^Update/ })).toBeNull()
  })

  it('enables Clear for a busked EFFECT with no value behind it', () => {
    // A busking pad creates a band FX with no entry. Gating on the entry count alone would disable
    // the escape hatch in exactly the case an operator most needs it.
    effects = [{ programmerOwned: true }]
    render(<ProgrammerActionBar projectId={1} />)
    const clear = screen.getByRole('button', { name: 'Clear' })
    expect(clear).not.toBeDisabled()
    fireEvent.click(clear)
    expect(programmerClearAll).toHaveBeenCalled()
  })

  it('disables Clear only when there is neither a value nor an effect', () => {
    render(<ProgrammerActionBar projectId={1} />)
    expect(screen.getByRole('button', { name: 'Clear' })).toBeDisabled()
  })

  it('leaves Include enabled on an empty programmer — it is how you fill it', () => {
    render(<ProgrammerActionBar projectId={1} />)
    const include = screen.getByRole('button', { name: 'Include…' })
    expect(include).not.toBeDisabled()
    fireEvent.click(include)
    expect(sheets.openInclude).toHaveBeenCalled()
  })

  it('offers Record four destinations, with the included cue among them', () => {
    // "Record look" stops being a sibling button and becomes one destination of one act.
    summary = { ...summary, entryCount: 12, lastIncluded: CUE }
    render(<ProgrammerActionBar projectId={1} />)

    openRecordMenu()
    expect(screen.getByText('Write 12 values into')).toBeTruthy()
    expect(screen.getByText('Q4 Warm Wash')).toBeTruthy()
    expect(screen.getByText('A new cue after Q4')).toBeTruthy()
    expect(screen.getByText('A new Look')).toBeTruthy()
    expect(screen.getByText('An existing cue…')).toBeTruthy()
    // NOT a predicted "becomes Q4.5" — the server assigns the number.
    expect(screen.queryByText(/Q4\.5/)).toBeNull()
  })

  it('omits the update-this-cue destination when nothing is included', () => {
    summary = { ...summary, entryCount: 3 }
    render(<ProgrammerActionBar projectId={1} />)
    openRecordMenu()
    expect(screen.queryByText('Update the cue you are editing')).toBeNull()
    expect(screen.getByText('A new Look')).toBeTruthy()
  })

  it('toggles Blind by the fade the picker holds', () => {
    // Blind is a programmer fact and this is its one control (`PD-BLIND-ON-PROGRAMMER`). It sat
    // here, moved to the `ShowBar` in session 2b, and vanished from the programmer when session 5
    // took the bar off this page — which a desk pass found unliveable. The fade is the load-bearing
    // half: Blind used to snap the moment it left this bar, because the picker's value stopped
    // reaching it, so the assertion is on the *argument*, not just the call — and the picker is
    // moved AFTER mount, through the store the picker writes, so a mount-time snapshot would fail.
    render(<ProgrammerActionBar projectId={1} />)
    act(() => setProgrammerFade('2000'))

    const blind = screen.getByRole('button', { name: 'Blind' })
    expect(blind.getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(blind)
    expect(programmerSetBlind).toHaveBeenCalledWith(true, 2000)
  })

  it('reports blind on, and presses it off', () => {
    summary = { ...summary, blind: true }
    render(<ProgrammerActionBar projectId={1} />)

    const blind = screen.getByRole('button', { name: 'Blind' })
    expect(blind.getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(blind)
    expect(programmerSetBlind).toHaveBeenCalledWith(false, 0)
  })

  it('keeps Blind visible but inert while the desk is offline', () => {
    // A WS write on a server-owned flag: a press with the socket down neither reaches the rig nor
    // moves the button. It stays on screen — blind is state the operator must keep reading — and
    // only stops taking the press, saying why.
    desk.connected = false
    summary = { ...summary, blind: true }
    render(<ProgrammerActionBar projectId={1} />)

    const blind = screen.getByRole('button', { name: 'Blind' })
    expect((blind as HTMLButtonElement).disabled).toBe(true)
    expect(blind.getAttribute('title')).toContain('Not connected')
    fireEvent.click(blind)
    expect(programmerSetBlind).not.toHaveBeenCalled()
  })

  it('keeps the fade value on the phone and drops only its chevron', () => {
    // Below `@[600px]` the trigger is 48px, centred, with no chevron — the fade must still be
    // *read* before Clear is pressed, so the value cannot go, and 86 → 48 is what pays for the
    // source box's `Q4 · Update · Revert` on a portrait phone. The width stays 86 above it.
    render(<ProgrammerActionBar projectId={1} />)
    const trigger = screen.getByRole('combobox', { name: 'Fade time' })
    expect(trigger.className).toContain('w-[86px]')
    expect(trigger.className).toContain('@max-[600px]:w-12')
    expect(trigger.className).toContain('@max-[600px]:px-0')
    expect(trigger.className).toContain('@max-[600px]:justify-center')
    expect(trigger.className).toContain('@max-[600px]:[&_svg]:hidden')
    expect(trigger.textContent).toContain('Snap')
  })
})
