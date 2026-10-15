import { useEffect, useState } from 'react'

/**
 * `Date.now()`, ticking every [intervalMs] while [active] — for a countdown the desk measured once —
 * and stopping at [untilMs] when given, so a countdown that has run out costs nothing.
 */
export function useNow(active: boolean, untilMs?: number, intervalMs = 500): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const tick = () => {
      const t = Date.now()
      setNow(t)
      return untilMs == null || t < untilMs
    }
    if (!tick()) return
    const id = setInterval(() => {
      if (!tick()) clearInterval(id)
    }, intervalMs)
    return () => clearInterval(id)
  }, [active, untilMs, intervalMs])
  return now
}
