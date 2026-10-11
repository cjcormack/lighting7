import { formatError } from './formatError'

/**
 * The desk's venue-sync refusals (venue plan session 4, §3.4, P7), named so a surface can say what
 * to do rather than only what went wrong. Every other refusal keeps its own wording.
 *
 * - `VENUE_MISSING` — a show pins a venue version this desk does not hold: import (or sync) the
 *   venue's repo first. Answered by an import, an add-remote-project and a show's sync.
 * - `VENUE_VERSION_TAKEN` — the number this publish would take is the remote's already, published on
 *   another desk; the pull brought it into History. Answered by a publish and a venue's sync.
 * - `VENUE_PUBLISH_PULL_ONLY` — a pull-only desk (`sync.push = false`) cannot publish a venue that has
 *   a repo, since the version could never reach it.
 */
export const VENUE_SYNC_REFUSALS = {
  VENUE_MISSING: 'Import the venue first',
  VENUE_VERSION_TAKEN: 'That version was published on another desk',
  VENUE_PUBLISH_PULL_ONLY: 'This desk only pulls',
} as const

export type VenueSyncRefusalCode = keyof typeof VENUE_SYNC_REFUSALS

/** The machine-readable `code` an RTK Query error carries in its body, if any. */
export function errorCode(err: unknown): string | undefined {
  if (err === null || typeof err !== 'object') return undefined
  const data = (err as { data?: unknown }).data
  if (data === null || typeof data !== 'object') return undefined
  const code = (data as { code?: unknown }).code
  return typeof code === 'string' ? code : undefined
}

/**
 * [err] as a venue-sync refusal — its code, a title naming what to do, and the desk's own sentence —
 * or null when it is any other error.
 */
export function venueSyncRefusal(
  err: unknown,
): { code: VenueSyncRefusalCode; title: string; message: string } | null {
  const code = errorCode(err)
  if (code == null || !Object.hasOwn(VENUE_SYNC_REFUSALS, code)) return null
  const known = code as VenueSyncRefusalCode
  return { code: known, title: VENUE_SYNC_REFUSALS[known], message: formatError(err) }
}
