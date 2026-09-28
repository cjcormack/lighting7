import { useCallback, useEffect, useRef } from 'react'

/**
 * The floor between two live writes, in ms. 20 a second is far below what any drag can perceive
 * and far above what the wire notices; `SLIDE_PUSH_MS` in `lib/speedMasterModel.ts` is the same
 * number said for the tempo fader, and the speed rail passes it in.
 */
export const LIVE_PUSH_MS = 50

export interface LivePushOptions<T> {
  /** The floor between two sends. Defaults to {@link LIVE_PUSH_MS}. */
  floorMs?: number
  /** When two values are the same write — `Object.is` unless the value is an object. */
  equals?: (a: T, b: T) => boolean
}

export interface LivePush<T> {
  /** A value from a running gesture: sent now if the floor has lapsed, else held for when it does. */
  push: (value: T) => void
  /** The release: sent now whatever the floor says, unless it is the value last sent. */
  flush: (value: T) => void
  /** Forget what was last sent, for the start of a fresh gesture. */
  reset: () => void
}

/**
 * Live writes for a running gesture — a tempo drag, a colour drag — applied as they go, floored at
 * an interval, and deduplicated on the value. Generic over the value; the speed rail's private
 * tempo hook was the first instance and this is that hook with the number lifted out
 * (busk-further plan §5, session 5).
 *
 * The gesture applies live because that is what a fader is for: the operator is watching the rig
 * and a control that only lands on release makes that a guess-then-check loop. The throttle is the
 * traffic half of the same decision, not a softening of it — a `pointermove` fires up to once a
 * frame and every write is broadcast to every socket on the desk.
 *
 * Three things it does beyond the interval:
 *
 * - It **deduplicates on the value**: a move that lands on the value last sent is worth nothing on
 *   the wire. `equals` says what "the same value" means — a whole BPM, or six colour bytes.
 * - A deferred value is not dropped but **held and sent when the floor lifts**, so the value keeps
 *   moving through a fast drag rather than stalling until the pointer slows.
 * - {@link LivePush.flush} is the release: it bypasses both the interval and any armed timer,
 *   because the value the operator let go on is the one that must land. It still dedupes — a
 *   release that changed nothing since the last send has nothing to say.
 *
 * `send` is read through a ref, so a caller may hand in an inline closure over its current
 * selection or master without `push` and `flush` changing identity — they are stable for the life
 * of the component, which is what lets a drag's window listeners be keyed on a boolean.
 */
export function useLivePush<T>(send: (value: T) => void, options: LivePushOptions<T> = {}): LivePush<T> {
  const floorMs = options.floorMs ?? LIVE_PUSH_MS
  const sendRef = useRef(send)
  sendRef.current = send
  const equalsRef = useRef(options.equals ?? Object.is)
  equalsRef.current = options.equals ?? Object.is

  const lastSent = useRef<{ value: T } | null>(null)
  const lastSentAt = useRef(0)
  const deferred = useRef<{ value: T } | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const sameAsLast = useCallback((value: T) => {
    const last = lastSent.current
    return last != null && equalsRef.current(last.value, value)
  }, [])

  const sendNow = useCallback((value: T) => {
    lastSent.current = { value }
    lastSentAt.current = Date.now()
    deferred.current = null
    sendRef.current(value)
  }, [])

  const push = useCallback(
    (value: T) => {
      // The deferred value is recorded **before** the dedupe, not after it. Dropping a move that
      // happens to land back on the value last sent would leave the previous move's value armed:
      // drag 120 → 125 → 121 → 120 inside one window and the timer would fire with 120's
      // predecessor, putting the rig at a value the finger has already left until the release. The
      // dedupe still happens — at both the places that actually send.
      deferred.current = { value }
      if (timer.current) return
      if (sameAsLast(value)) return
      const wait = floorMs - (Date.now() - lastSentAt.current)
      if (wait <= 0) {
        sendNow(value)
        return
      }
      timer.current = setTimeout(() => {
        timer.current = null
        const pending = deferred.current
        if (pending != null && !sameAsLast(pending.value)) sendNow(pending.value)
      }, wait)
    },
    [floorMs, sameAsLast, sendNow],
  )

  const flush = useCallback(
    (value: T) => {
      if (timer.current) {
        clearTimeout(timer.current)
        timer.current = null
      }
      if (!sameAsLast(value)) sendNow(value)
      deferred.current = null
    },
    [sameAsLast, sendNow],
  )

  /**
   * Forget what was last sent, for the start of a fresh gesture.
   *
   * `lastSent` is only a dedupe against *this* gesture's own moves. Between gestures the value
   * moves by every other route — TAP, a typed field, another tab, a MIDI surface — so carrying it
   * over means a gesture that arms on exactly the value the previous one ended at sends nothing,
   * while the control immediately reads that value and draws itself at it.
   */
  const reset = useCallback(() => {
    lastSent.current = null
    deferred.current = null
  }, [])

  // A gesture interrupted by a re-render that unmounts the control leaves nothing armed behind it.
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )

  return { push, flush, reset }
}
