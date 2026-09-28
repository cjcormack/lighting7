import { useEffect, useRef } from 'react'
import { isEditableTarget } from '../../lib/domUtils'
import { nudgeTargets, type BulkTarget } from '../../lib/stageBulkOps'
import type { StageProjection } from '../../lib/stageProjection'
import type { PlacementChange } from '../../store/stagePlacement'

/** Shift multiplies the step, for coarse moves. */
const COARSE_MULTIPLIER = 10
/** Idle time after the last keypress before the accumulated move is committed. */
const FLUSH_DELAY_MS = 250

interface UseStageNudgeOptions {
  enabled: boolean
  projection: StageProjection
  /** Metres per arrow press. Normally the grid step, so nudging tracks the grid. */
  stepM: number
  /** Read lazily, at flush time, so the listener needn't re-bind per selection. */
  targets: () => BulkTarget[]
  commit: (changes: PlacementChange[], label: string) => void
}

/**
 * Arrow-key nudging for the current selection.
 *
 * Moves along the **active projection's** screen axes, so ← always means "left as
 * drawn" whether that's stage-left or downstage. The maths goes through
 * `nudgeTargets`, which lifts to world space and lowers per-fixture — so nudging a
 * truss-mounted fixture keeps it in its own frame instead of drifting off the bar.
 *
 * **Coalesced.** A held arrow key autorepeats at ~30 Hz; committing each one would
 * be 30 PUTs, 30 WebSocket broadcasts and 60 list refetches per second. Presses
 * accumulate into a pending delta that is flushed on keyup or after a short idle,
 * so one gesture is one write.
 */
export function useStageNudge({
  enabled,
  projection,
  stepM,
  targets,
  commit,
}: UseStageNudgeOptions): void {
  // Everything the handler needs goes through refs, so the listener binds once
  // per enable rather than on every selection or projection change.
  const latest = useRef({ projection, stepM, targets, commit })
  latest.current = { projection, stepM, targets, commit }

  const pending = useRef<{ h: number; v: number }>({ h: 0, v: 0 })
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (!enabled) return

    const flush = () => {
      if (timer.current != null) {
        clearTimeout(timer.current)
        timer.current = null
      }
      const { h, v } = pending.current
      pending.current = { h: 0, v: 0 }
      if (h === 0 && v === 0) return
      const { projection: proj, targets: getTargets, commit: doCommit } = latest.current
      const ts = getTargets()
      if (ts.length === 0) return
      doCommit(nudgeTargets(ts, proj, h, v), 'Nudge')
    }

    const onKeyDown = (e: KeyboardEvent) => {
      // Never hijack typing — the side panels are full of numeric inputs.
      if (isEditableTarget(document.activeElement)) return
      if (e.metaKey || e.ctrlKey || e.altKey) return

      const step = latest.current.stepM * (e.shiftKey ? COARSE_MULTIPLIER : 1)
      let dh = 0
      let dv = 0
      switch (e.key) {
        case 'ArrowLeft':
          dh = -step
          break
        case 'ArrowRight':
          dh = step
          break
        case 'ArrowUp':
          // v is screen-down, so up is negative.
          dv = -step
          break
        case 'ArrowDown':
          dv = step
          break
        default:
          return
      }
      if (latest.current.targets().length === 0) return
      e.preventDefault()

      pending.current = { h: pending.current.h + dh, v: pending.current.v + dv }
      if (timer.current != null) clearTimeout(timer.current)
      timer.current = setTimeout(flush, FLUSH_DELAY_MS)
    }

    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key.startsWith('Arrow')) flush()
    }

    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('keyup', onKeyUp)
      // Commit whatever was accumulated rather than dropping the user's move on
      // unmount or on leaving edit mode.
      flush()
    }
  }, [enabled])
}
