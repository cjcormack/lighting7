import { useEffect, useRef } from 'react'

/**
 * A ref that reads `true` for exactly as long as the component is on screen — the guard an
 * `async` body needs after an `await`, where React has no other way to say "and you are still
 * here". A ref rather than state on purpose: reading it must not re-render, and it has to be
 * readable from a closure that was created before the answer changed.
 *
 * **The setup write is the whole reason this is a shared hook.** The obvious spelling —
 * `useRef(true)` plus a cleanup that clears it — is wrong in development, and silently:
 * StrictMode mounts, tears down and remounts effects, so a flag written only by the teardown
 * is left `false` for the entire life of the component *while it is genuinely on screen*.
 * Every guarded continuation then bails as though the component had gone away. Two components
 * have now been written with that bug (`ScriptViewer`'s page-classification walk gave up after
 * one page; `DeviceLoginSection` cancelled every code the moment it arrived), and both times
 * a plain `render()` in a test passed while it was broken — so a test covering a guarded path
 * has to mount under `StrictMode` to be worth anything.
 *
 * It answers unmount only. "Is this component's *feature* still on screen" — a hidden tab, a
 * closed parent — is a different question that the caller composes on top; see `onScreen` in
 * `components/auth/DeviceLoginSection.tsx`, which ands this with a prop tracked in its own ref.
 */
export function useMountedRef() {
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  return mounted
}
