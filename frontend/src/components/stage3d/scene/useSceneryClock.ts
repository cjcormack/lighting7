import { useEffect, useState } from 'react'
import { invalidate } from '@react-three/fiber'
import type { LiveScenery } from '../../../api/sceneryApi'
import { sceneryLandsAt } from '../../../lib/scenery'

/**
 * The clock scenery is drawn at (stage-view plan session 8): `performance.now()`, advanced once a
 * frame **while a move is in flight** and then left alone.
 *
 * The canvas renders on demand, so a tab drawing over four seconds has to ask for every one of its
 * frames: each tick here sets the time the scene's elements are built at — a new prop wherever a
 * piece moved — and invalidates the canvas, until the last move lands. Then it stops, and an idle
 * stage costs nothing (stage-vis doc §"The 3D renderer"). [enabled] false (a one-frame render, or a
 * canvas drawing no scene) never ticks.
 */
export function useSceneryClock(scenery: LiveScenery, enabled: boolean): number {
  const [now, setNow] = useState(() => performance.now())
  useEffect(() => {
    if (!enabled) return
    const lands = sceneryLandsAt(scenery)
    let frame = 0
    const tick = () => {
      const t = performance.now()
      setNow(t)
      invalidate()
      if (t < lands) frame = requestAnimationFrame(tick)
    }
    // At least one tick, on the next frame, so a change that only *retargets* (a new resolution,
    // nothing moving) is still drawn at the time it arrived.
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [scenery, enabled])
  return now
}
