import { useSyncExternalStore } from 'react'
import { sameReading, type FrameRateReading } from './frameRate'

/**
 * What one canvas is doing — frame rate, lights packed of lit, haze tier — for the View popover's
 * Performance tab and the frame-rate readout. One store per canvas, created by the Stage route. Read
 * it from a leaf only: a route that re-rendered on it would hand the canvas props and ask for frames.
 */
export interface StageStats {
  /** Null while idle. */
  frameRate: FrameRateReading | null
  /** How many lights the surfaces took of how many were lit; null while nothing packs them. */
  lights: { packed: number; lit: number } | null
  hazeTier: number
}

export interface StageStatsStore {
  subscribe: (listener: () => void) => () => void
  getSnapshot: () => StageStats
  setFrameRate: (reading: FrameRateReading | null) => void
  setLights: (lights: { packed: number; lit: number } | null) => void
  setHazeTier: (tier: number) => void
}

export const IDLE_STATS: StageStats = { frameRate: null, lights: null, hazeTier: 0 }

export function createStageStats(): StageStatsStore {
  let current = IDLE_STATS
  const listeners = new Set<() => void>()
  const publish = (next: StageStats) => {
    current = next
    listeners.forEach((fn) => fn())
  }
  return {
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    getSnapshot: () => current,
    setFrameRate(reading) {
      if (!sameReading(current.frameRate, reading)) publish({ ...current, frameRate: reading })
    },
    setLights(lights) {
      const was = current.lights
      if (was === lights || (was != null && lights != null && was.packed === lights.packed && was.lit === lights.lit)) return
      publish({ ...current, lights })
    },
    setHazeTier(tier) {
      if (current.hazeTier !== tier) publish({ ...current, hazeTier: tier })
    },
  }
}

const NO_STORE = createStageStats()

/** This canvas's stats, re-rendering the caller on every change; the idle snapshot without a store. */
export function useStageStats(store: StageStatsStore | null | undefined): StageStats {
  const s = store ?? NO_STORE
  return useSyncExternalStore(s.subscribe, s.getSnapshot, s.getSnapshot)
}
