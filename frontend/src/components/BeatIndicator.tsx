import { useCallback, useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import {
  requestSpeedMasterBeat,
  subscribeToSpeedMasterBeat,
  useMaster1Uuid,
  useSpeedMasterBpm,
} from '../store/speedMasters'

/** Which master this dot beats for. Omitted → master 1. */
export interface BeatIndicatorMaster {
  /** May be null for master 1 — callers that only hold the write-side convention. */
  uuid: string | null
  index: number
}

/**
 * A dot that pulses on the beat.
 *
 * Server frames are throttled (one every 16 beats), so the dot free-runs on a local interval
 * between them and uses each frame to re-align. Until the first frame arrives it renders as
 * an empty ring — "not synced" is shown rather than guessed. A *tempo change* is not drift and
 * cannot wait for the next frame: the interval is re-seeded from the live BPM the moment it
 * moves, which is why this reads `speedMasters.changed` as well as the beat stream.
 *
 * Always reads the keyed `speedMasters.beat` stream, so a dot beside a master-2 effect beats
 * at master 2's tempo. Omitting [master], or passing master 1 with a null uuid, resolves to
 * master 1's real uuid via [useMaster1Uuid] — see there for why null cannot be used directly.
 */
export function BeatIndicator({
  className,
  master,
}: {
  className?: string
  master?: BeatIndicatorMaster | null
}) {
  const master1Uuid = useMaster1Uuid()
  const target = master?.uuid ?? master1Uuid
  const liveBpm = useSpeedMasterBpm(target)
  const [beat, setBeat] = useState(false)
  const [synced, setSynced] = useState(false)
  // Mirrors `synced` for the subscribe effect to read without listing it as a dependency:
  // otherwise every regain of sync tears the subscription down and re-creates it (sending a
  // redundant requestBeat), when the only thing that should re-bind it is the master.
  const syncedRef = useRef(false)
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  // The gap between a retune and the first flash on the new grid — see [reseedInterval].
  const reseedTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // The grid the dot is on: when the beat in progress began, and how long a beat is. `flash`
  // moves the anchor to each beat; a re-seed moves it to hold the fraction already travelled.
  const anchorRef = useRef(0)
  const periodRef = useRef(0)
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastFlashTimeRef = useRef(0)

  const stopInterval = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current)
      intervalRef.current = null
    }
    // A pending re-seed is half of a started timer: leaving it armed would let a stale tempo
    // start an interval again after the caller thought it had stopped one.
    if (reseedTimeoutRef.current) {
      clearTimeout(reseedTimeoutRef.current)
      reseedTimeoutRef.current = null
    }
  }, [])

  const flash = useCallback(() => {
    if (flashTimeoutRef.current) {
      clearTimeout(flashTimeoutRef.current)
    }
    lastFlashTimeRef.current = Date.now()
    anchorRef.current = lastFlashTimeRef.current
    setBeat(true)
    flashTimeoutRef.current = setTimeout(() => setBeat(false), 80)
  }, [])

  /** Run a grid of [period] ms whose first beat lands [firstFlashInMs] from now. */
  const runGrid = useCallback((period: number, firstFlashInMs: number) => {
    stopInterval()
    periodRef.current = period
    const begin = () => {
      // Consume the one-shot before arming the repeat, so `reseedTimeoutRef` means "a re-seed
      // is still pending" rather than "one happened at some point" — which is how
      // [stopInterval] reads it.
      reseedTimeoutRef.current = null
      flash()
      intervalRef.current = setInterval(flash, period)
    }
    if (firstFlashInMs <= 0) {
      begin()
      return
    }
    reseedTimeoutRef.current = setTimeout(begin, firstFlashInMs)
  }, [flash, stopInterval])

  // A whole beat from now, so the caller's own moment is a beat boundary. That is right for a
  // server beat frame — it arrives on the master's real boundary — and wrong for anything else.
  const startInterval = useCallback((bpm: number) => {
    const period = 60000 / bpm
    runGrid(period, period)
  }, [runGrid])

  /**
   * Re-space the beat grid without moving it: the dot keeps the fraction of a beat it has
   * already travelled and only the spacing changes, so a retune re-rates the grid instead of
   * restarting it — 40% through a beat before the push is 40% through the new one after.
   *
   * [startInterval] cannot do this job for a tempo push. A live-BPM frame lands wherever the
   * operator's typing or dragging put it — never on a beat boundary — so anchoring there
   * shifts the whole grid by up to a beat, which is what reads as the dot losing the music.
   *
   * Carrying the *fraction* rather than a fixed `lastFlash + period` deadline is what makes a
   * drag safe, and the two look equivalent until you drag the tempo *down*: that deadline
   * recedes as the period grows, and past about 3 BPM per push below 60 BPM it recedes faster
   * than the wall clock advances, so it never expires and the dot goes dark for the whole
   * drag. A fraction only ever advances, and the period is bounded by the desk's 20 BPM floor,
   * so the next beat always arrives.
   */
  const reseedInterval = useCallback((bpm: number) => {
    const period = 60000 / bpm
    const previous = periodRef.current
    const elapsed = Date.now() - anchorRef.current
    const travelled = previous > 0 ? Math.min(elapsed / previous, 1) : 1
    // Re-anchor as though this beat had begun at the new spacing, so the next push in a drag
    // measures its fraction against the grid that is actually running.
    anchorRef.current = Date.now() - period * travelled
    runGrid(period, period * (1 - travelled))
  }, [runGrid])

  // When we lose sync, stop flashing
  useEffect(() => {
    if (!synced) {
      stopInterval()
    }
  }, [synced, stopInterval])

  const markUnsynced = useCallback(() => {
    syncedRef.current = false
    setSynced(false)
  }, [])

  // Switching which master this dot follows invalidates the local timer — it is still
  // ticking at the old master's tempo — so drop back to unsynced until the new master's
  // first frame lands.
  useEffect(() => {
    markUnsynced()
  }, [target, markUnsynced])

  // Detect tab visibility changes — mark as unsynced when returning from a hidden state,
  // since the local interval drifts while backgrounded, and ask for a frame now. The
  // subscription below is still good, so this asks explicitly rather than re-subscribing:
  // waiting out the throttle would leave the dot an empty ring for up to 16 beats (~8s at
  // 120 BPM, ~48s at the 20 BPM floor).
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        markUnsynced()
        requestSpeedMasterBeat(target)
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [markUnsynced, target])

  // Every server frame: re-align to the beat boundary and re-seed the local interval.
  // Keyed on `target` alone — subscribing sends a requestBeat, so re-binding for any other
  // reason costs a redundant round trip.
  useEffect(() => {
    const onServerBeat = (bpm: number) => {
      const wasSynced = syncedRef.current
      if (!wasSynced) {
        syncedRef.current = true
        setSynced(true)
      }

      // Flash unless the local timer just flashed for this beat
      const minGap = (60000 / bpm) / 2
      if (!wasSynced || Date.now() - lastFlashTimeRef.current > minGap) {
        flash()
      }

      startInterval(bpm)
    }

    const subscription = subscribeToSpeedMasterBeat(target, (b) => onServerBeat(b.bpm))
    return () => subscription.unsubscribe()
  }, [flash, startInterval, target])

  // A retune leaves the local interval running at the old tempo, and the server's beat frames
  // are throttled — so the rate is corrected from the live-BPM push instead, which lands at
  // tap rate. Phase carries over from the grid the dot is already on ([reseedInterval]) rather
  // than being re-anchored at the push, and the beat frame the server releases on the next
  // beat after any tempo move trues it up against the clock itself.
  useEffect(() => {
    if (!synced || liveBpm == null) return
    reseedInterval(liveBpm)
  }, [liveBpm, synced, reseedInterval])

  // Cleanup
  useEffect(() => {
    return () => {
      stopInterval()
      if (flashTimeoutRef.current) {
        clearTimeout(flashTimeoutRef.current)
      }
    }
  }, [stopInterval])

  return (
    <div
      className={cn(
        'size-3 rounded-full',
        synced
          ? cn('transition-colors duration-75', beat ? 'bg-primary' : 'bg-muted-foreground/25')
          : 'border border-muted-foreground/40',
        className
      )}
    />
  )
}
