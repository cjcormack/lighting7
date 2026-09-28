// @vitest-environment jsdom
import { renderHook, act } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useEditorOpen } from './useEditorOpen'

/**
 * The two rules that open and close a cell editor without anybody clicking it — `PD-POPUP-AFTER-
 * DRAG`'s auto-open, and the close that follows a deselect — tested where each is one rule rather
 * than four. That the first opens a real editor is asserted through a real cell in
 * `FixturesTable.test.tsx`.
 */
describe('useEditorOpen', () => {
  it('opens when the signal arrives', () => {
    const { result, rerender } = renderHook(({ autoOpen }) => useEditorOpen({ autoOpen }), {
      initialProps: { autoOpen: false },
    })
    expect(result.current.isOpen).toBe(false)
    rerender({ autoOpen: true })
    expect(result.current.isOpen).toBe(true)
  })

  it('does not reopen when the operator has closed it', () => {
    // `FixturesTable` drops the signal as soon as it is delivered, so `autoOpen` goes false on its
    // own — but a re-render of the row while it is still true must not fight the operator either.
    const { result, rerender } = renderHook(({ autoOpen }) => useEditorOpen({ autoOpen }), {
      initialProps: { autoOpen: true },
    })
    expect(result.current.isOpen).toBe(true)
    act(() => result.current.setOpen(false))
    expect(result.current.isOpen).toBe(false)
    rerender({ autoOpen: true })
    expect(result.current.isOpen).toBe(false)
  })

  it('latches where the editor was anchored, so the panel cannot jump after it opens', () => {
    // `anchorAtButton` arrives on a one-shot the table drops on the very next commit. Read per
    // render instead of latched, the popover would re-anchor from the Set button to the cell a
    // frame after opening — the panel visibly jumping across the screen.
    const { result, rerender } = renderHook(
      ({ autoOpen, anchorAtButton }) => useEditorOpen({ autoOpen, anchorAtButton }),
      { initialProps: { autoOpen: false, anchorAtButton: true } },
    )
    rerender({ autoOpen: true, anchorAtButton: true })
    expect(result.current.atButton).toBe(true)

    rerender({ autoOpen: false, anchorAtButton: false })
    expect(result.current.isOpen).toBe(true)
    expect(result.current.atButton).toBe(true)

    act(() => result.current.setOpen(false))
    expect(result.current.atButton).toBe(false)
  })

  it('leaves the editor at its cell for a keyboard open', () => {
    // Enter and a typed character are made at the selection, so the panel opens beside the cell.
    const { result, rerender } = renderHook(
      ({ autoOpen }) => useEditorOpen({ autoOpen, keyboardSeed: '' }),
      { initialProps: { autoOpen: false } },
    )
    rerender({ autoOpen: true })
    expect(result.current.isOpen).toBe(true)
    expect(result.current.atButton).toBe(false)
  })

  it('leaves the editor at its cell, unseeded, for a bare open — the double click\'s call', () => {
    // `EditorSurface` opens a double click straight through `onOpenChange`, which is this
    // `setOpen` with neither extra argument. That has to land in the click path: beside the cell
    // (the Set button's anchor is Set's alone) and with no typed character to seed a field with.
    const { result } = renderHook(() => useEditorOpen({}))
    act(() => result.current.setOpen(true))

    expect(result.current.isOpen).toBe(true)
    expect(result.current.atButton).toBe(false)
    expect(result.current.keyboardOpen).toBeNull()
  })

  it('closes on the close signal, and the signal is a one-shot like the open', () => {
    // Set is the only thing that can shut what it opened: the Set button is the open popover's own
    // anchor, so a press on it is not the outside click that dismisses one. Verified on the desk —
    // pressing Set twice used to leave the panel open with focus stranded on the button.
    const { result, rerender } = renderHook(
      ({ autoOpen, autoClose }) => useEditorOpen({ autoOpen, autoClose }),
      { initialProps: { autoOpen: false, autoClose: false } },
    )
    rerender({ autoOpen: true, autoClose: false })
    expect(result.current.isOpen).toBe(true)

    rerender({ autoOpen: false, autoClose: true })
    expect(result.current.isOpen).toBe(false)

    // Dropped by the caller on the next commit, and it must not hold the editor shut: the next
    // open request has to work.
    rerender({ autoOpen: false, autoClose: false })
    rerender({ autoOpen: true, autoClose: false })
    expect(result.current.isOpen).toBe(true)
  })

  it('ignores the signal on a cell that cannot be edited', () => {
    // Output scope, a focused template layer, an unreachable desk.
    const { result } = renderHook(() => useEditorOpen({ autoOpen: true, disabled: true }))
    expect(result.current.isOpen).toBe(false)
  })

  it('does not spring open later when a read-only cell becomes editable', () => {
    // `disabled` is read at the instant the signal flips and never again. A scope switched to
    // Local minutes after a drag must not act on that drag.
    const { result, rerender } = renderHook(
      ({ disabled }) => useEditorOpen({ autoOpen: true, disabled }),
      { initialProps: { disabled: true } },
    )
    rerender({ disabled: false })
    expect(result.current.isOpen).toBe(false)
  })

  it('runs `onOpen` for an auto-open as well as for a click', () => {
    // SliderCell's typed-input reset. A click resets it through `onOpenChange`; an auto-open never
    // goes through that handler, so the reset has to live here or the field would open holding
    // text typed for the previous value.
    const onOpen = vi.fn()
    const { result, rerender } = renderHook(
      ({ autoOpen }) => useEditorOpen({ autoOpen, onOpen }),
      { initialProps: { autoOpen: false } },
    )
    rerender({ autoOpen: true })
    expect(onOpen).toHaveBeenCalledTimes(1)

    act(() => result.current.setOpen(false))
    expect(onOpen).toHaveBeenCalledTimes(1)
    act(() => result.current.setOpen(true))
    expect(onOpen).toHaveBeenCalledTimes(2)
  })

  it('closes when the selection it was opened for goes away', () => {
    const { result, rerender } = renderHook(
      ({ selectionEmpty }) => useEditorOpen({ selectionEmpty }),
      { initialProps: { selectionEmpty: false } },
    )
    act(() => result.current.setOpen(true))
    expect(result.current.isOpen).toBe(true)

    rerender({ selectionEmpty: true })
    expect(result.current.isOpen).toBe(false)
  })

  it('opens normally in a grid that has no selection at all', () => {
    // The edge and not the state, which is the whole reason the ref exists: a cell that mounts
    // with nothing selected — and stays that way — must still open on a click. Closing on the
    // *state* would land in the effect right after the click that opened it, so the editor would
    // flicker rather than fail in a way anyone could report.
    const { result, rerender } = renderHook(
      ({ selectionEmpty }) => useEditorOpen({ selectionEmpty }),
      { initialProps: { selectionEmpty: true } },
    )
    act(() => result.current.setOpen(true))
    rerender({ selectionEmpty: true })
    expect(result.current.isOpen).toBe(true)
  })

  it('leaves an editor alone where there is no selection to speak of', () => {
    // `CueValueGrid` mounts these cells with no selection above them, so the prop is undefined —
    // which is not the same as "empty" and must never close anything.
    const { result, rerender } = renderHook(() => useEditorOpen({}))
    act(() => result.current.setOpen(true))
    rerender()
    expect(result.current.isOpen).toBe(true)
  })

  it('keeps `setOpen` stable across a changing `onOpen`', () => {
    // The cells pass a fresh inline arrow every render of their row, and a rig re-renders these
    // constantly; `setOpen` is a popover's `onOpenChange` and should not churn with it.
    const { result, rerender } = renderHook(() => useEditorOpen({ onOpen: () => {} }))
    const first = result.current.setOpen
    rerender()
    expect(result.current.setOpen).toBe(first)
  })
})
