/**
 * A unit's **stored locate** (venue-visit plan D6, D17) as the operator reads it: the pan and tilt
 * Locate points each unit at, on the centre-relative scale every other position takes
 * (`positionScale.ts`). The desk stores bytes and decides everything — what a store reads, which
 * units can hold one, what a show on a venue writes — so this module only words what it answers.
 * Pure: no store, no React.
 */

import type { UnitLocate } from '@/api/patchApi'
import type { StoredLocateResponse } from '@/store/locate'
import { dmxToCentre, formatCentre } from './positionScale'

/** `Fixture` for the fixture's own pan and tilt, `Head 3` for a head (its index from 0). */
export function locateUnitLabel(entry: Pick<UnitLocate, 'head'>): string {
  return entry.head == null ? 'Fixture' : `Head ${entry.head + 1}`
}

/** `Pan +12 · Tilt -32` — the coarse bytes on the centre scale; the fine byte is underneath. */
export function locateValueLabel(entry: UnitLocate): string {
  return `Pan ${formatCentre(dmxToCentre(entry.pan))} · Tilt ${formatCentre(dmxToCentre(entry.tilt))}`
}

/**
 * The bytes as the desk stores them, for a hover: `pan 140 (fine 12), tilt 96 (fine 200)`. The fine
 * byte is named rather than written after a point, where `96.200` would read as a decimal.
 */
export function locateBytesLabel(entry: UnitLocate): string {
  const axis = (coarse: number, fine?: number | null) => (fine == null ? `${coarse}` : `${coarse} (fine ${fine})`)
  return `pan ${axis(entry.pan, entry.panFine)}, tilt ${axis(entry.tilt, entry.tiltFine)}`
}

/**
 * What a store or clear did, as one toast line: how many fixtures it reached and what it skipped,
 * grouped by the desk's own reason. `verb` is the gesture's past tense (*stored* / *cleared*).
 */
export function storedLocateMessage(response: StoredLocateResponse, verb: 'stored' | 'cleared'): {
  ok: boolean
  message: string
} {
  const stored = response.stored ?? []
  const skipped = response.skipped ?? []
  const count = stored.length
  const head = count === 0 ? `No locate ${verb}` : `Locate ${verb} on ${count} fixture${count === 1 ? '' : 's'}`
  if (skipped.length === 0) return { ok: count > 0, message: head }
  return { ok: count > 0, message: `${head} — ${skipLines(skipped)}` }
}

/** How many keys a reason names before it says `+N more`, so a whole rig's skips stay one line. */
const KEYS_PER_REASON = 3

/**
 * The skips grouped by reason, each naming its first few keys — `hex-1, hex-2, par-1 +37 more do not
 * pan and tilt` — so Set over a whole rig of lanterns and a few movers does not toast forty keys.
 */
function skipLines(skipped: NonNullable<StoredLocateResponse['skipped']>): string {
  const byReason = new Map<string, string[]>()
  for (const s of skipped) byReason.set(s.reason, [...(byReason.get(s.reason) ?? []), s.target.key])
  return [...byReason]
    .map(([reason, keys]) => {
      const named = keys.slice(0, KEYS_PER_REASON).join(', ')
      const more = keys.length > KEYS_PER_REASON ? ` +${keys.length - KEYS_PER_REASON} more` : ''
      // The desk's reasons are written for one fixture ("does not pan and tilt"); several take the plural verb.
      const verb = keys.length > 1 ? reason.replace(/^does not /, 'do not ') : reason
      return `${named}${more} ${verb}`
    })
    .join('; ')
}
