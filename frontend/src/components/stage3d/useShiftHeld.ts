import { useEffect, useRef, useState } from 'react'

type ModifierKey = 'shiftKey' | 'altKey' | 'ctrlKey' | 'metaKey'

/**
 * Tracks whether a modifier key is currently held. Returns both a state value
 * (so consumers re-render when the modifier toggles, e.g. to feed
 * TransformControls' snap props) and a ref (so drag handlers can read the
 * latest value mid-gesture without forcing a re-render every frame). Listen on
 * both keydown and keyup — every key event carries the current modifier state,
 * so we don't need to watch the modifier key by name.
 */
export function useModifierHeld(
  modifier: ModifierKey,
  enabled = true,
): { held: boolean; ref: React.RefObject<boolean> } {
  const [held, setHeld] = useState(false)
  const ref = useRef(false)
  useEffect(() => {
    if (!enabled) {
      if (ref.current) {
        ref.current = false
        setHeld(false)
      }
      return
    }
    const onKey = (e: KeyboardEvent) => {
      const v = e[modifier]
      if (v === ref.current) return
      ref.current = v
      setHeld(v)
    }
    const onBlur = () => {
      if (!ref.current) return
      ref.current = false
      setHeld(false)
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('keyup', onKey)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('keyup', onKey)
      window.removeEventListener('blur', onBlur)
    }
  }, [modifier, enabled])
  return { held, ref }
}

export function useShiftHeld() {
  return useModifierHeld('shiftKey')
}

export const SNAP_DISTANCE_M = 0.25
export const SNAP_ANGLE_DEG = 15

export function snap(value: number, step: number): number {
  return Math.round(value / step) * step
}
