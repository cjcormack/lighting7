import { createContext, useContext, type ReactNode } from 'react'
import { useThree } from '@react-three/fiber'

/**
 * The canvas's `invalidate`, for the scene's imperative writers.
 *
 * The Stage canvas renders on `demand`, so anything that changes the picture without changing an
 * R3F prop — a lens colour written from a channel callback, a hidden emitter slot — has to ask for
 * a frame. A context rather than `useThree`, because the colour syncs are rendered outside any
 * canvas by their unit tests, where `useThree` throws; there the default is a no-op.
 */
const StageInvalidateContext = createContext<() => void>(() => {})

export function useStageInvalidate(): () => void {
  return useContext(StageInvalidateContext)
}

/** Provides the canvas's `invalidate` to the scene below it. Must be rendered inside the canvas. */
export function StageInvalidateProvider({ children }: { children: ReactNode }) {
  const invalidate = useThree((s) => s.invalidate)
  return <StageInvalidateContext.Provider value={invalidate}>{children}</StageInvalidateContext.Provider>
}
