import { useCallback, useEffect, useRef, useState } from 'react'

/** Re-lock after this long without an edit interaction while unlocked. */
const IDLE_RELOCK_MS = 120_000
/** Countdown shown before the idle re-lock engages. */
const COUNTDOWN_MS = 10_000

/**
 * Auto-re-engage for the prompt-book lock — catches "unlocked to fix one thing
 * and forgot" mid-show:
 *
 *  • GO/advance re-locks immediately (running the show again is the natural end
 *    of a fix-it edit) — the caller invokes `noteGo()` from its GO path.
 *  • A 2-minute idle fallback with a visible countdown; any edit interaction
 *    (`noteEdit()`) resets it, and the operator can tap "stay unlocked".
 *
 * The caller owns the lock state; this hook only says when to snap it back.
 */
export function useAutoRelock({
  locked,
  onRelock,
}: {
  locked: boolean
  onRelock: () => void
}) {
  const [countdownSecondsLeft, setCountdownSecondsLeft] = useState<number | null>(null)
  const idleTimerRef = useRef<number | null>(null)
  const countdownTimerRef = useRef<number | null>(null)
  const onRelockRef = useRef(onRelock)
  onRelockRef.current = onRelock

  const clearTimers = useCallback(() => {
    if (idleTimerRef.current != null) window.clearTimeout(idleTimerRef.current)
    if (countdownTimerRef.current != null) window.clearInterval(countdownTimerRef.current)
    idleTimerRef.current = null
    countdownTimerRef.current = null
    setCountdownSecondsLeft(null)
  }, [])

  const startIdleTimer = useCallback(() => {
    clearTimers()
    idleTimerRef.current = window.setTimeout(() => {
      // Idle limit reached — start the visible countdown before locking.
      const deadline = Date.now() + COUNTDOWN_MS
      setCountdownSecondsLeft(Math.ceil(COUNTDOWN_MS / 1000))
      countdownTimerRef.current = window.setInterval(() => {
        const left = Math.ceil((deadline - Date.now()) / 1000)
        if (left <= 0) {
          clearTimers()
          onRelockRef.current()
        } else {
          setCountdownSecondsLeft(left)
        }
      }, 250)
    }, IDLE_RELOCK_MS - COUNTDOWN_MS)
  }, [clearTimers])

  // Arm while unlocked; fully disarm when locked.
  useEffect(() => {
    if (locked) {
      clearTimers()
      return
    }
    startIdleTimer()
    return clearTimers
  }, [locked, startIdleTimer, clearTimers])

  /**
   * Any edit interaction resets the idle clock (and cancels a running countdown).
   * "Stay unlocked" on the countdown toast is the same operation by design —
   * exposed under both names so call sites read naturally.
   */
  const noteEdit = useCallback(() => {
    if (!locked) startIdleTimer()
  }, [locked, startIdleTimer])

  /** GO/advance while unlocked — re-lock immediately. */
  const noteGo = useCallback(() => {
    if (!locked) {
      clearTimers()
      onRelockRef.current()
    }
  }, [locked, clearTimers])

  return { countdownSecondsLeft, noteEdit, noteGo, stayUnlocked: noteEdit }
}
