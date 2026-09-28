import { useCallback } from 'react'
import {
  useLocateStateQuery,
  useToggleLocateMutation,
  type LocateTargetType,
} from '../store/locate'

/**
 * Locate toggle state for one fixture or group: whether it is currently located,
 * and a toggle that flips it on the backend (centre position + open white beam on,
 * cascade back to the show on release).
 */
export function useLocate(type: LocateTargetType, targetKey: string) {
  const { data, isFetching } = useLocateStateQuery()
  const [toggleLocate, { isLoading }] = useToggleLocateMutation()

  const isActive =
    data?.targets.some((t) => t.type === type && t.key === targetKey) ?? false

  const toggle = useCallback(() => {
    toggleLocate({ type, key: targetKey })
      .unwrap()
      .catch((err) => console.error(`Locate toggle failed for ${type} '${targetKey}'`, err))
  }, [toggleLocate, type, targetKey])

  // Stay "busy" until the invalidated locate-state refetch lands, not just until the
  // POST resolves — otherwise the button re-enables showing the stale state and a fast
  // second click toggles the locate straight back off.
  return { isActive, toggle, isToggling: isLoading || isFetching }
}
