import { useEffect, useRef } from 'react'

/** How long a field sits untouched before it saves itself. */
const AUTOSAVE_DELAY_MS = 700

/**
 * Save a form field shortly after typing stops, rather than only when it loses focus.
 *
 * Commit-on-blur alone is a trap: it holds an edit hostage to a gesture the operator has no
 * reason to make. Type a note and then collapse the cue card, switch the pane to another tab,
 * or navigate away and React unmounts the input without ever firing `blur` — the edit is gone,
 * silently, and the card looked perfectly saved the whole time. Worse during a show, where the
 * next thing to happen is usually a GO rather than a Tab.
 *
 * So: a save fires a beat after the last keystroke, and anything still outstanding is flushed
 * on the way out. Blur keeps committing immediately — this only removes the *requirement* to
 * blur, it doesn't delay the operator who does.
 *
 * `commit` must no-op when the value already matches the server (every caller here compares
 * against the cue first), because this deliberately calls it more often than blur did.
 */
export function useFieldAutosave(value: string, commit: () => void, delayMs = AUTOSAVE_DELAY_MS) {
  // Always call the newest closure: `commit` captures both the current draft and the current
  // server value, and a stale one would write back something the operator has moved past.
  const latest = useRef(commit)
  latest.current = commit

  const mounted = useRef(false)
  useEffect(() => {
    // The first run is mount, not an edit — the value came from the server, so saving it back
    // would be a pointless round trip on every card that opens.
    if (!mounted.current) {
      mounted.current = true
      return
    }
    const timer = setTimeout(() => latest.current(), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  // The debounce above cancels itself on unmount, so this is what actually rescues the last
  // edit when the field disappears mid-type.
  useEffect(() => () => latest.current(), [])
}
