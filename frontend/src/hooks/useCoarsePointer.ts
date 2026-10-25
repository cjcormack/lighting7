import { useMediaMatch } from './useMediaMatch'

/** Whether the primary pointer is a finger: what drops a control too fine to drag by touch. False where nothing can say. */
export function useCoarsePointer(): boolean {
  return useMediaMatch('(pointer: coarse)')
}
