import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { useCoarsePointer } from '@/hooks/useCoarsePointer'
import type { CellClickEvent } from './cellSelectionModel'

/** The *Add* latch's state and toggle, as the selection bar draws it (`AddLatchToggle`). */
export interface AddLatchControl {
  on: boolean
  onToggle: () => void
}

/**
 * The selection bar's *Add* latch (programmer-fixes plan D7): touch's ⌘. While it is on, a tap on a
 * cell is a ⌘-click, a tap on a row's name a ⌘-click on the row, and a held drag accumulates.
 *
 * The host's state, never stored. It turns off when the host clears the selection — Escape,
 * Deselect, a tap on blank space — by calling [off], and when the primary pointer stops being a
 * finger, since the toggle is not drawn for a mouse. A tap that takes out the last cell leaves it on,
 * so a double-tap on the one selected cell opens its editor with Add still on. [ref] is for
 * handlers that must not change identity.
 */
export function useAddLatch(): {
  control: AddLatchControl
  ref: RefObject<boolean>
  off: () => void
} {
  const coarse = useCoarsePointer()
  const [latched, setOn] = useState(false)
  const on = latched && coarse
  const ref = useRef(on)
  ref.current = on
  useEffect(() => {
    if (!coarse) setOn(false)
  }, [coarse])
  const onToggle = useCallback(() => setOn((value) => !value), [])
  const off = useCallback(() => setOn(false), [])
  const control = useMemo(() => ({ on, onToggle }), [on, onToggle])
  return { control, ref, off }
}

/**
 * Whether a click on a blank cell should leave the selection alone (D1): it was made with ⌘, Ctrl
 * or ⇧, or under the Add latch, so it is building a selection rather than clearing one. A clear with
 * no event is the empty space under the rows, which always clears.
 */
export function blankClickKeepsSelection(event: CellClickEvent | undefined, addLatch: boolean): boolean {
  return event != null && (addLatch || event.metaKey || event.ctrlKey || event.shiftKey)
}
