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
  const { data } = useLocateStateQuery()
  const [toggleLocate, { isLoading }] = useToggleLocateMutation()

  const isActive =
    data?.targets.some((t) => t.type === type && t.key === targetKey) ?? false

  const toggle = useCallback(() => {
    toggleLocate({ type, key: targetKey })
      .unwrap()
      .catch((err) => console.error(`Locate toggle failed for ${type} '${targetKey}'`, err))
  }, [toggleLocate, type, targetKey])

  // The toggle writes its answer into the locate state as it resolves, so a second click never
  // acts on the state from before the first.
  return { isActive, toggle, isToggling: isLoading }
}
