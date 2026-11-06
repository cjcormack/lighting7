// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { useEffect, useState } from 'react'
import type { PressPair } from '@/hooks/usePress'

/**
 * *+ Effect* (apply-model plan §4.6, session 6): the picker's effects arm over the window's pair —
 * the bare effect press, one Local effect over the selection under its mask (`FxPicker.test.tsx`
 * pins the request) — disabled in Output, on a focused palette layer and with nothing selected.
 *
 * Its doors (programmer-toolbar plan D8–D10): row C's **FX** and ⌘K's door open it as the live
 * editor once started, with ⤢ into the FX editor; the picker asks *Replace · Stack* on the
 * programmer page (`ApplyPicker.test.tsx`).
 */
const FOUR: PressPair = {
  targets: [1, 2, 3, 4].map((n) => ({ type: 'fixture' as const, key: `hex-${n}` })),
  families: ['INTENSITY'],
}
const wire = vi.hoisted(() => ({
  pair: null as PressPair | null,
  scope: { kind: 'local' } as { kind: string } | null,
  store: null as object | null,
}))
vi.mock('@/components/fx/selectionFxTarget', () => ({ useWindowFxPair: () => wire.pair, readProgrammerFxPair: () => wire.pair }))
vi.mock('@/hooks/usePress', () => ({ useWindowPaletteFit: () => ({ families: null, emitters: null }) }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => wire.scope }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => wire.store }))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
const follow = vi.hoisted(() => ({ on: false }))
vi.mock('@/lib/deskFollow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/deskFollow')>()),
  isFollowingDesk: () => follow.on,
}))
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: unknown) => {
    picker(props)
    return null
  },
}))

const { ProgrammerAddFxDoor, ProgrammerAddFxSheet, useProgrammerAddFx } = await import('./ProgrammerAddFx')
const { ProgrammerAddFxButton } = await import('./ProgrammerAddFxButton')
const { readProgrammerCommands, runProgrammerCommand } = await import('./commandDoors')
const { getPickedFx, resetFxEditorViewStores, setFxTabView, useFxTabView } = await import('@/lib/fxEditorView')
const { isLitHeld } = await import('@/lib/litHold')
const { selectionWs } = await import('@/test/backendMock')
const { FX_PICKER_CLOSE_FLAG } = await import('@/lib/fxPickerCloseTrace')

type PickerProps = {
  open: boolean
  onOpenChange: (open: boolean, cause?: string) => void
  anchorRef?: { current: HTMLElement | null }
  askStack?: boolean
  editOnStart?: boolean
  onOpenInEditor?: (fxId: number) => void
}
const lastPicker = () => picker.mock.lastCall![0] as PickerProps

/** Where the router is — what ⤢ moves. */
function Where() {
  const location = useLocation()
  return <span data-testid="where">{`${location.pathname}${location.search}`}</span>
}
function onProgrammer(children: React.ReactNode) {
  return (
    <MemoryRouter initialEntries={['/projects/3/programmer?table=values']}>
      <Routes>
        <Route
          path="*"
          element={
            <>
              {children}
              <Where />
            </>
          }
        />
      </Routes>
    </MemoryRouter>
  )
}

afterEach(() => {
  cleanup()
  picker.mockClear()
  follow.on = false
  selectionWs.last = null
  window.localStorage.removeItem(FX_PICKER_CLOSE_FLAG)
  vi.restoreAllMocks()
  wire.scope = { kind: 'local' }
  wire.store = null
  resetFxEditorViewStores()
})

describe('+ Effect', () => {
  it('opens the effects arm over a four-head selection, on its masked family', () => {
    wire.pair = FOUR
    const { result } = renderHook(() => useProgrammerAddFx())
    expect(result.current.disabled).toBe(false)
    render(onProgrammer(<ProgrammerAddFxSheet projectId={3} open offer={result.current} onClose={() => {}} />))
    expect(picker).toHaveBeenCalledWith(expect.objectContaining({ projectId: 3, library: 'fx', pair: FOUR, family: 'INTENSITY' }))
  })

  it('is disabled with nothing selected, in Output, and on a focused palette layer', () => {
    wire.pair = { targets: [], families: null }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.pair = FOUR
    wire.scope = { kind: 'output' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.scope = { kind: 'layer' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.disabled).toBe(true)
    wire.store = { lookName: 'Warm wash' }
    expect(renderHook(() => useProgrammerAddFx()).result.current.reason).toContain('Warm wash')
  })
})

describe('the doors', () => {
  it('the FX table’s + FX keeps the list after a start', () => {
    wire.pair = FOUR
    const { result } = renderHook(() => useProgrammerAddFx())
    render(onProgrammer(<ProgrammerAddFxSheet projectId={3} open offer={result.current} onClose={() => {}} />))
    expect(lastPicker().editOnStart).toBe(false)
    expect(lastPicker().onOpenInEditor).toBeUndefined()
  })

  it('row C’s FX opens the picker under itself on the mask’s family, as the live editor once started', () => {
    wire.pair = FOUR
    render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    expect(picker).not.toHaveBeenCalled()
    const button = screen.getByRole('button', { name: 'Add FX on the selection' })
    fireEvent.click(button)
    expect(picker).toHaveBeenCalledWith(expect.objectContaining({ library: 'fx', pair: FOUR, family: 'INTENSITY', editOnStart: true }))
    expect(lastPicker().anchorRef?.current).toBe(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    act(() => lastPicker().onOpenChange(false))
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('⤢ picks the FX, puts the FX tab on Selection and shows the FX table, closing the picker', () => {
    wire.pair = FOUR
    setFxTabView('all')
    render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    fireEvent.click(screen.getByRole('button', { name: 'Add FX on the selection' }))
    act(() => lastPicker().onOpenInEditor!(42))
    expect(getPickedFx()).toBe(42)
    expect(renderHook(() => useFxTabView()).result.current).toBe('selection')
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/programmer?table=fx')
    expect(screen.getByRole('button', { name: 'Add FX on the selection' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('is disabled in Output with the reason', () => {
    wire.pair = FOUR
    wire.scope = { kind: 'output' }
    render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    const button = screen.getByRole('button', { name: 'Add FX on the selection' })
    expect(button).toBeDisabled()
    // On a wrapper, since a disabled button takes no hover.
    expect(button.parentElement!.getAttribute('title')).toMatch(/^Output is a read of everything composed together/)
  })

  it('closes when the selection leaves under it, and does not come back unasked', () => {
    wire.pair = FOUR
    const { rerender } = render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    fireEvent.click(screen.getByRole('button', { name: 'Add FX on the selection' }))
    expect(lastPicker().open).toBe(true)
    wire.pair = { targets: [], families: null }
    rerender(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    expect(screen.getByRole('button', { name: 'Add FX on the selection' })).toHaveAttribute('aria-expanded', 'false')
    picker.mockClear()
    wire.pair = FOUR
    rerender(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    expect(picker).not.toHaveBeenCalled()
  })

  it('opens in a focused Look too, where the picker absorbs it', () => {
    wire.pair = FOUR
    wire.scope = { kind: 'layer' }
    wire.store = { lookName: 'Warm wash' }
    render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    const button = screen.getByRole('button', { name: 'Add FX on the selection' })
    expect(button).toBeEnabled()
    expect(button.parentElement!.getAttribute('title')).toContain('Warm wash')
    fireEvent.click(button)
    expect(picker).toHaveBeenCalled()
  })

  it('⌘K’s door opens the same picker, the editor included', () => {
    wire.pair = FOUR
    render(onProgrammer(<ProgrammerAddFxDoor projectId={3} />))
    expect(picker).not.toHaveBeenCalled()
    act(() => void runProgrammerCommand('selection.addFx'))
    expect(picker).toHaveBeenCalledWith(expect.objectContaining({ editOnStart: true, family: 'INTENSITY' }))
    expect(lastPicker().onOpenInEditor).toBeTypeOf('function')
  })

  it('⌘K’s door names what it acts on, and is offered only where it can act', () => {
    wire.pair = FOUR
    const view = render(onProgrammer(<ProgrammerAddFxDoor projectId={3} />))
    const offered = () => readProgrammerCommands().find((c) => c.id === 'selection.addFx')
    expect(offered()).toMatchObject({ label: 'Add FX on the selection…', hint: '4 heads · Intensity', canAct: true })
    wire.scope = { kind: 'output' }
    view.rerender(onProgrammer(<ProgrammerAddFxDoor projectId={3} />))
    expect(offered()?.canAct).toBe(false)
    wire.scope = { kind: 'layer' }
    wire.store = { lookName: 'Warm wash' }
    view.rerender(onProgrammer(<ProgrammerAddFxDoor projectId={3} />))
    expect(offered()).toMatchObject({ hint: '4 heads · into Warm wash', canAct: true })
    view.unmount()
    expect(offered()).toBeUndefined()
  })
})

describe('row C’s FX holds Lit while open (programmer-fixes plan D21)', () => {
  it('takes the hold on open and gives it back on a close and an unmount', () => {
    wire.pair = FOUR
    const view = render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    expect(isLitHeld()).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Add FX on the selection' }))
    expect(isLitHeld()).toBe(true)
    act(() => lastPicker().onOpenChange(false, 'escape'))
    expect(isLitHeld()).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Add FX on the selection' }))
    view.unmount()
    expect(isLitHeld()).toBe(false)
  })
})

describe('the close trace (programmer-fixes plan D25)', () => {
  const traced = (warn: { mock: { calls: unknown[][] } }) => warn.mock.calls.map(([line]) => line)
  const open = () => fireEvent.click(screen.getByRole('button', { name: 'Add FX on the selection' }))

  it('logs nothing without the flag', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    wire.pair = FOUR
    const view = render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    open()
    act(() => lastPicker().onOpenChange(false, 'outside press'))
    open()
    open()
    open()
    wire.pair = { targets: [], families: null }
    view.rerender(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    wire.pair = FOUR
    view.rerender(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    open()
    view.unmount()
    expect(warn).not.toHaveBeenCalled()
  })

  it('logs one warning per close naming the door, read at the close', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    wire.pair = FOUR
    render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    // Set after load: nothing is read until a close.
    window.localStorage.setItem(FX_PICKER_CLOSE_FLAG, '1')
    for (const cause of ['escape', 'outside press', 'focus outside', 'done']) {
      open()
      act(() => lastPicker().onOpenChange(false, cause))
    }
    open()
    open()
    open()
    act(() => lastPicker().onOpenInEditor!(42))
    expect(traced(warn)).toEqual([
      '[fx picker closed] picker: escape',
      '[fx picker closed] picker: outside press',
      '[fx picker closed] picker: focus outside',
      '[fx picker closed] picker: done',
      '[fx picker closed] FX button pressed',
      '[fx picker closed] picker: open in FX editor',
    ])
  })

  it('names the desk selection’s change and who moved it', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    window.localStorage.setItem(FX_PICKER_CLOSE_FLAG, '1')
    follow.on = true
    wire.pair = FOUR
    const view = render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    open()
    selectionWs.last = { targets: [], families: null, source: { kind: 'window', id: 'w-7', name: 'Tablet' } }
    wire.pair = { targets: [], families: null }
    view.rerender(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" />))
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalledWith('[fx picker closed] offer disabled', {
      reason: 'Select the heads the FX should drive first.',
      followingDesk: true,
      deskHeads: 0,
      deskMovedBy: { kind: 'window', id: 'w-7', name: 'Tablet' },
    })
  })

  // Following the desk, the offer empties a commit before row C does (the list follows the desk in an
  // effect), so row C goes in the same flush as the disabled close: that is still one close.
  it('logs one warning when row C goes with the disabled close, not two', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    window.localStorage.setItem(FX_PICKER_CLOSE_FLAG, '1')
    follow.on = true
    wire.pair = FOUR
    function RowC() {
      const empty = wire.pair!.targets.length === 0
      const [shown, setShown] = useState(true)
      useEffect(() => {
        if (empty) setShown(false)
      }, [empty])
      return shown ? <ProgrammerAddFxButton projectId={3} wordClass="" closeTrace={{ current: { visibleSelectedRows: 0 } }} /> : null
    }
    const view = render(onProgrammer(<RowC />))
    open()
    wire.pair = { targets: [], families: null }
    view.rerender(onProgrammer(<RowC />))
    expect(screen.queryByRole('button', { name: 'Add FX on the selection' })).toBeNull()
    expect(traced(warn)).toEqual(['[fx picker closed] offer disabled'])
  })

  it('names row C unmounting under an open picker, with what its host says', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    window.localStorage.setItem(FX_PICKER_CLOSE_FLAG, '1')
    wire.pair = FOUR
    const rowC = { current: { selectedRows: 1, visibleSelectedRows: 0, onlyLit: false, filtered: true } }
    const view = render(onProgrammer(<ProgrammerAddFxButton projectId={3} wordClass="" closeTrace={rowC} />))
    open()
    view.unmount()
    expect(warn).toHaveBeenCalledWith('[fx picker closed] row C actions unmounted', rowC.current)
    expect(warn).toHaveBeenCalledTimes(1)
  })
})
