import { useCallback, useEffect, useRef, useState } from 'react'
import { lightingApi } from '../../api/lightingApi'
import { resolveTargetCells } from './rowModel'
import type { WriteTarget } from './rowModel'

/** What the programmer held on one dimmer property before Highlight took it. */
interface CapturedDimmer {
  targetKey: string
  propertyName: string
  /** The programmer entry that was there, or null if the property was unheld. */
  previousValue: string | null
}

/**
 * Momentary Highlight: while held, every selected target's dimmer goes to full; on release
 * the property goes back to what it was. Dimmer lookup goes through resolveTargetCells, so
 * it lifts exactly the properties the Dimmer column shows (parent master first, else
 * per-element dimmers); targets with no dimmer anywhere are skipped, and `isActive` stays
 * false when nothing was captured so the button never claims a no-op did something. Live
 * context only — this is a stage tool, not an editing operation.
 *
 * Release restores the *programmer* state, not a channel snapshot:
 *
 * - the property was already busked → put that value back, so highlighting over a busk
 *   leaves the busk intact;
 * - the property was unheld → `clearEntry`, which releases it down the cascade to whatever
 *   cue or effect owns it. The old channel-snapshot restore would instead have pinned the
 *   observed value into the programmer, quietly converting "the cue owns this" into "the
 *   operator owns this" every time Highlight was used.
 *
 * `clearEntry` releases *every* owner on the property, which is why the busked-value branch
 * exists rather than clearing unconditionally.
 *
 * Kept deliberately simple: the selection is captured at press time — changing it mid-hold
 * doesn't re-capture, and we restore exactly what we highlighted.
 */
export function useHighlight(getTargets: () => WriteTarget[]) {
  const capturedRef = useRef<CapturedDimmer[] | null>(null)
  const [isActive, setIsActive] = useState(false)

  const release = useCallback(() => {
    const captured = capturedRef.current
    capturedRef.current = null
    setIsActive(false)
    if (!captured) return
    for (const { targetKey, propertyName, previousValue } of captured) {
      if (previousValue === null) {
        lightingApi.programmer.clearEntry('fixture', targetKey, propertyName)
      } else {
        lightingApi.programmer.set('fixture', targetKey, propertyName, previousValue)
      }
    }
  }, [])

  const press = useCallback(() => {
    if (capturedRef.current) return
    const captured: CapturedDimmer[] = []
    const seen = new Set<string>()
    for (const target of getTargets()) {
      for (const { target: resolved, resolution } of resolveTargetCells(target, 'dimmer')) {
        if (resolution.kind !== 'slider') continue
        const targetKey = resolved.key
        const propertyName = resolution.property.name
        const id = `${targetKey}|${propertyName}`
        if (seen.has(id)) continue
        seen.add(id)
        captured.push({
          targetKey,
          propertyName,
          previousValue:
            lightingApi.programmer.getKeyState(targetKey, propertyName).entry?.value ?? null,
        })
        lightingApi.programmer.set('fixture', targetKey, propertyName, '255')
      }
    }
    capturedRef.current = captured
    setIsActive(captured.length > 0)
  }, [getTargets])

  // Restore on unmount so navigating away mid-hold doesn't strand the rig at full.
  useEffect(() => release, [release])

  return { press, release, isActive }
}
