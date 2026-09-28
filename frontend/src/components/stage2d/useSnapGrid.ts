import { useMemo, useRef } from 'react'
import { usePersistentState } from '../../hooks/usePersistentState'
import { useModifierHeld, SNAP_DISTANCE_M } from '../stage3d/useShiftHeld'

export const SNAP_STEPS_M = [0.1, 0.25, 0.5, 1] as const
export type SnapStep = (typeof SNAP_STEPS_M)[number]

interface StoredSnap {
  snapOn: boolean
  step: SnapStep
}

const DEFAULT_SNAP: StoredSnap = { snapOn: true, step: SNAP_DISTANCE_M as SnapStep }

export interface SnapGrid {
  /** Grid step in metres. Also what the drawn grid uses, so what you see is what you snap to. */
  step: SnapStep
  setStep: (s: SnapStep) => void
  /** The persisted preference, independent of the Shift override. */
  snapOn: boolean
  setSnapOn: (v: boolean) => void
  /** Whether snapping applies right now — for rendering. */
  active: boolean
  /** The same, readable mid-gesture without re-rendering per frame. */
  activeRef: React.RefObject<boolean>
  /** Snap a value when active, else pass it through. */
  snapValue: (v: number) => number
}

/**
 * Grid snapping, shared by the 2D editor and the 3D scene.
 *
 * **Snap is on by default and Shift temporarily disables it** — the inverse of
 * this codebase's original behaviour, where Shift *enabled* snapping. Laying out
 * a rig is the case that matters: you want fixtures on the grid nearly always, so
 * holding a modifier for every placement drag fights the user. Free positioning
 * is the exception, so it gets the modifier.
 *
 * The 3D handles were flipped to match rather than left as they were. Having the
 * same key mean opposite things on the same route, depending only on which view
 * is showing, would be worse than either convention.
 */
export function useSnapGrid(enabled: boolean): SnapGrid {
  const [stored, setStored] = usePersistentState<StoredSnap>('stage2dSnap', DEFAULT_SNAP, {
    merge: true,
  })
  const { held: shiftHeld } = useModifierHeld('shiftKey', enabled)

  const active = enabled && stored.snapOn && !shiftHeld
  // Mirror into a ref so drag handlers can read the current value mid-gesture
  // without the hook needing to re-run them.
  const activeRef = useRef(active)
  activeRef.current = active

  return useMemo(
    () => ({
      step: stored.step,
      setStep: (step: SnapStep) => setStored((prev) => ({ ...prev, step })),
      snapOn: stored.snapOn,
      setSnapOn: (snapOn: boolean) => setStored((prev) => ({ ...prev, snapOn })),
      active,
      activeRef,
      snapValue: (v: number) =>
        activeRef.current ? Math.round(v / stored.step) * stored.step : v,
    }),
    [stored.step, stored.snapOn, active, setStored],
  )
}
