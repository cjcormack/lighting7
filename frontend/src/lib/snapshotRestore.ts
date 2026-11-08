import type { BringBackSelection, RestoreReview, SyncErrorCode } from '@/store/cloudSync'
import { formatError } from './formatError'
import { errorCode } from './venueSyncRefusal'

/** A snapshot's moment as the review names it, *Thu 29 Oct, 21:14*, in this browser's locale and time zone. */
export function formatSnapshotWhen(whenMs: number): string {
  const d = new Date(whenMs)
  const day = d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
  const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  return `${day}, ${time}`
}

/** What to do about each refusal a review or a restore can answer; the rest are the desk's own sentence. */
const RESTORE_REFUSALS: Partial<Record<SyncErrorCode, string>> = {
  RESTORE_COMMIT_UNKNOWN: "This snapshot isn't in the show's history any more.",
  RESTORE_NO_CHANGES: 'This snapshot is the show as it is now: there is nothing to restore.',
  RESTORE_TOO_OLD: 'This snapshot was written in a format this desk no longer reads.',
  SESSION_PENDING: 'A sync conflict is open. Resolve or abort it in the Conflicts panel, then restore.',
  VENUE_MISSING: "The snapshot's venue version isn't on this desk. Import or sync the venue first.",
  VENUE_DRAFT_NOT_SYNCED: "A venue's draft has no snapshots to restore.",
  FORMAT_TOO_NEW: 'The repository holds a show written by a newer desk. Update this desk first.',
}

/** A review's or a restore's refusal, in words: what to do where the code says, else the desk's message. */
export function restoreRefusalMessage(err: unknown): string {
  const code = errorCode(err) as SyncErrorCode | undefined
  const known = code ? RESTORE_REFUSALS[code] : undefined
  if (known == null) return formatError(err)
  // VENUE_MISSING's message names the venue and its repo, which the sentence alone cannot.
  return code === 'VENUE_MISSING' ? `${known} ${formatError(err)}` : known
}

/** The new review a `RESTORE_REVIEW_STALE` answer carries, or null for any other error. */
export function staleReview(err: unknown): RestoreReview | null {
  if (errorCode(err) !== 'RESTORE_REVIEW_STALE') return null
  const review = (err as { data?: { review?: unknown } }).data?.review
  return review != null && typeof review === 'object' ? (review as RestoreReview) : null
}

/** The new selection a bring-back's `RESTORE_REVIEW_STALE` answer carries, or null for any other error. */
export function staleSelection(err: unknown): BringBackSelection | null {
  if (errorCode(err) !== 'RESTORE_REVIEW_STALE') return null
  const selection = (err as { data?: { selection?: unknown } }).data?.selection
  return selection != null && typeof selection === 'object' ? (selection as BringBackSelection) : null
}
