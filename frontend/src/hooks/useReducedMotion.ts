import { useMediaMatch } from './useMediaMatch'

/** Whether the operator asked for less motion: what holds an animation still. False where nothing can say. */
export function useReducedMotion(): boolean {
  return useMediaMatch('(prefers-reduced-motion: reduce)')
}
