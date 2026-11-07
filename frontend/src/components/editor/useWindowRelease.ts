import { useCallback, useEffect, useRef } from 'react'

const RELEASE_EVENTS = ['pointerup', 'pointercancel', 'mouseup', 'touchend', 'touchcancel', 'blur'] as const

/**
 * A press that ends wherever it is let go: the handler arms window listeners for the release, as
 * `ColourEditor`'s `holdPicker` does, so a drag that leaves the control — or the popover — still
 * ends. Spread it as the control's capture-phase press (`onPointerDownCapture`,
 * `onMouseDownCapture`, and `onTouchStartCapture` with `{ button: 0 }`); one press arms once. An
 * unmount mid-press releases it.
 */
export function useWindowRelease(onRelease: () => void): (event: { button: number }) => void {
  const onReleaseRef = useRef(onRelease)
  onReleaseRef.current = onRelease
  const armedRef = useRef<(() => void) | null>(null)
  useEffect(() => () => armedRef.current?.(), [])
  return useCallback((event: { button: number }) => {
    // A secondary button's release can be swallowed by a context menu, which would hold for good.
    if (event.button !== 0 || armedRef.current != null) return
    const release = () => {
      for (const name of RELEASE_EVENTS) window.removeEventListener(name, release)
      armedRef.current = null
      onReleaseRef.current()
    }
    for (const name of RELEASE_EVENTS) window.addEventListener(name, release)
    armedRef.current = release
  }, [])
}
