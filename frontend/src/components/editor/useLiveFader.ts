import { useEffect, useRef, useState } from 'react'
import { useLivePush } from './useLivePush'

/** How long a released value is drawn over the desk's before the desk's is believed. */
export const FADER_HOLD_MS = 1500

/**
 * A fader over a desk value that something else may also move — the grand master, a stack's effect
 * size. It writes as it moves through `useLivePush` (floored, deduplicated, the release always
 * landing), and draws what the operator moved over the desk's [desk] value until the desk says the
 * same or [FADER_HOLD_MS] pass, so a release does not snap back to the pre-drag value for a round
 * trip. After that the desk's frame is the truth, which a MIDI fader or another window may move.
 *
 * Hand [onValueChange] and [onValueCommit] to a Radix `Slider` (single thumb) and draw [shown];
 * [set] is a button's jump to a value.
 */
export function useLiveFader(desk: number, write: (value: number) => void) {
  const [held, setHeld] = useState<{ value: number; settling: boolean } | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { push, flush, reset } = useLivePush<number>(write)

  useEffect(() => {
    if (held?.settling && Math.abs(held.value - desk) < 0.001) setHeld(null)
  }, [held, desk])
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  const settle = (value: number) => {
    setHeld({ value, settling: true })
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => setHeld(null), FADER_HOLD_MS)
  }

  return {
    shown: held?.value ?? desk,
    onValueChange: ([v]: number[]) => {
      if (timer.current) clearTimeout(timer.current)
      setHeld({ value: v, settling: false })
      push(v)
    },
    onValueCommit: ([v]: number[]) => {
      flush(v)
      reset()
      settle(v)
    },
    set: (v: number) => {
      reset()
      write(v)
      settle(v)
    },
  }
}
