import { useCallback, useRef } from 'react'

/**
 * A callback with one identity for the life of the component, which still calls the newest function
 * it was given.
 *
 * For a prop of a **memoized** child. A handler rebuilt every render — a `useCallback` over state
 * that changes often, or no `useCallback` at all — defeats `React.memo` at the child, and where the
 * child is a virtualised row that means re-rendering the whole visible list for nothing. Widening
 * the dependency array is the usual answer and is wrong here: the child must not re-render, and the
 * callback must not go stale either.
 *
 * The idiom is a ref assigned during render plus a `useCallback` with no dependencies. Written once
 * rather than in each place that wants it, because the copies were already diverging in shape and
 * the one line that is easy to forget — reassigning `.current` on every render — silently freezes
 * the callback at its mount value with nothing to see.
 *
 * **During render, not in an effect.** An effect would leave the ref one commit behind, so a
 * handler fired between the render and the effect — a pointer event in the same task — would call
 * the previous version. Assigning during render is safe for a ref (it is not state, so it cannot
 * tear a concurrent render's output) and is what makes "always the newest" true.
 *
 * Not for a callback the child *identifies* by reference (a dependency of the child's own effect
 * that is meant to re-run when the handler changes): this one never changes, so that effect would
 * never re-run.
 */
export function useStableCallback<A extends unknown[], R>(
  fn: ((...args: A) => R) | undefined,
): (...args: A) => R | undefined {
  const ref = useRef(fn)
  ref.current = fn
  return useCallback((...args: A) => ref.current?.(...args), [])
}
