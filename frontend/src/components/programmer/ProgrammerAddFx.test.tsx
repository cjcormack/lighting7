// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
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
vi.mock('@/components/fx/selectionFxTarget', () => ({ useWindowFxPair: () => wire.pair }))
vi.mock('@/hooks/usePress', () => ({ useWindowPaletteFit: () => ({ families: null, emitters: null }) }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => wire.scope }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => wire.store }))
const picker = vi.hoisted(() => vi.fn())
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: unknown) => {
    picker(props)
    return null
  },
}))

const { ProgrammerAddFxDoor, ProgrammerAddFxSheet, useProgrammerAddFx } = await import('./ProgrammerAddFx')
const { ProgrammerAddFxButton } = await import('./ProgrammerAddFxButton')
const { openAddFxDoor } = await import('./addFxDoor')
const { getPickedFx, resetFxEditorViewStores, setFxTabView, useFxTabView } = await import('@/lib/fxEditorView')

type PickerProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
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
    act(() => void openAddFxDoor())
    expect(picker).toHaveBeenCalledWith(expect.objectContaining({ editOnStart: true, family: 'INTENSITY' }))
    expect(lastPicker().onOpenInEditor).toBeTypeOf('function')
  })
})
