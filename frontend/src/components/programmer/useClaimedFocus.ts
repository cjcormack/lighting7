import { useEffect, useState, type RefObject } from 'react'
import { useRailArm } from './ProgrammerWorkspace'

/**
 * A claimed grid gesture landing in a rail tab (editor-kit plan session 4, call 9): the rail's
 * focus request for **this** tab, honoured once — its first field focused and selected — and
 * dropped. One copy for the Colour and Spread tabs, so the two cannot drift in how a claimed open
 * lands; they had, the Spread tab missing the read-only guard, in the first cut.
 *
 * `refused` is the scope taking no value (Output, a focused template layer): the request is still
 * dropped, and nothing is focused — a read-only panel is not somewhere to type.
 *
 * Answers the **seed** — the character the gesture carried — for one commit and then null, so the
 * same key pressed twice seeds twice. It is set after the field is focused and selected, so the
 * seed is not the text the selection covers. The Colour tab hands it to its R field; the Spread tab
 * has no seed to take (row C's Spread is a press, not a keystroke).
 */
export function useClaimedFocus(tab: 'colour' | 'spread', root: RefObject<HTMLElement | null>, selector: string, refused: boolean): string | null {
  const { focusRequest, consumeFocusRequest } = useRailArm()
  const [seed, setSeed] = useState<string | null>(null)
  useEffect(() => {
    if (focusRequest?.tab !== tab) return
    consumeFocusRequest()
    if (refused) return
    const first = root.current?.querySelector<HTMLInputElement>(selector)
    first?.focus()
    first?.select()
    if (focusRequest.seed) setSeed(focusRequest.seed)
  }, [focusRequest, consumeFocusRequest, tab, refused, root, selector])
  useEffect(() => {
    if (seed != null) setSeed(null)
  }, [seed])
  return seed
}
