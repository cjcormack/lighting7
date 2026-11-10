import { useEffect, useRef } from 'react'
import { useStableCallback } from '@/hooks/useStableCallback'

/**
 * The Prompt Book's follow scroll, held while the operator works in the script.
 *
 * `follow` runs whenever its identity changes, which the page makes a new live sequence. While the
 * viewer reports a hold, it waits, and runs once when the hold ends; a hold that ends with nothing
 * waiting scrolls nothing, and so does one the viewer drops by unmounting. `cancel` drops a waiting
 * follow, for a scroll the operator asked for.
 *
 * Both functions keep one identity for the life of the page.
 */
export function useHeldFollow(follow: () => void): {
  onHoldChange: (held: boolean, unmounted?: boolean) => void
  cancel: () => void
} {
  const heldRef = useRef(false)
  const pendingRef = useRef(false)

  useEffect(() => {
    if (heldRef.current) pendingRef.current = true
    else follow()
  }, [follow])

  const onHoldChange = useStableCallback((held: boolean, unmounted?: boolean) => {
    heldRef.current = held
    if (held || !pendingRef.current) return
    pendingRef.current = false
    if (!unmounted) follow()
  }) as (held: boolean, unmounted?: boolean) => void
  const cancel = useStableCallback(() => {
    pendingRef.current = false
  }) as () => void
  return { onHoldChange, cancel }
}
