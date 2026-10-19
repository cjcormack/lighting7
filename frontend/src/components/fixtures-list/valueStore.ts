/**
 * A value held outside React with a listener set — what the programmer page provides for readers
 * that must not re-render with it: the published marquee, the FX table's selection, the FX lens's
 * index. Each reader subscribes through `useSyncExternalStore` with its own selector, so a write
 * re-renders only the readers whose selection moved. [equals] decides what counts as a write.
 */
export interface ValueStore<T> {
  get: () => T
  set: (next: T) => void
  subscribe: (listener: () => void) => () => void
}

export function createValueStore<T>(initial: T, equals: (a: T, b: T) => boolean = Object.is): ValueStore<T> {
  let current = initial
  const listeners = new Set<() => void>()
  return {
    get: () => current,
    set: (next) => {
      if (equals(next, current)) return
      current = next
      for (const listener of listeners) listener()
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
