import type { VenueHoldState } from '../api/venueHoldApi'
import type { ProjectVenue } from '../api/projectApi'

/**
 * A show that is behind its venue, in words (venue plan §3.1, P5, D7, session 6; the board is
 * lighting7 `docs/plans/completed/venue-design/Update.dc.html`). Pure, so the copy is pinned by
 * `venueHold.test.ts`.
 *
 * - **held** — the live show is behind on output: every controller sends zero, the chip says *Blind ·
 *   venue*, and the banner offers *Review update* and *Run against vN…*.
 * - **running** — the operator ran against the older pin for this session: output is live on the old
 *   patch, the installed version's trigger and command channels stay guarded.
 * - **drawing** — only drawing changed: a banner, output live, dismissable for the session.
 * - **output** — a show behind on output that is not the live one: opening it will hold it.
 */
export type BehindBanner =
  | { kind: 'held' | 'running' | 'output' | 'drawing'; venue: string; pinned: number; installed: number }

/** Which banner [venue] — the viewed show's — calls for, given the desk's [hold]. */
export function behindBanner(
  projectId: number | undefined,
  venue: ProjectVenue | null | undefined,
  hold: VenueHoldState,
): BehindBanner | null {
  if (projectId == null || venue == null) return null
  const name = venue.name ?? 'The venue'
  const live = hold.projectId === projectId
  // The frame is the desk's word on the live show, whatever the project read says.
  if (live && (hold.held || hold.runAgainst) && hold.pinned != null && hold.installed != null) {
    return { kind: hold.held ? 'held' : 'running', venue: name, pinned: hold.pinned, installed: hold.installed }
  }
  if (venue.installed == null || venue.installed <= venue.version) return null
  if (venue.behind === 'DRAWING') return { kind: 'drawing', venue: name, pinned: venue.version, installed: venue.installed }
  if (venue.behind === 'OUTPUT' && !live) return { kind: 'output', venue: name, pinned: venue.version, installed: venue.installed }
  return null
}

/** `2 drawing changes`, `1 drawing change`; `drawing changes` while the count is unknown. */
export function drawingChangesPhrase(n: number | undefined): string {
  if (n == null) return 'drawing changes'
  return n === 1 ? '1 drawing change' : `${n} drawing changes`
}

/** The banner's sentence. [showName] is the show's, for the drawing-only copy. */
export function bannerMessage(banner: BehindBanner, showName: string, drawingChanges?: number): string {
  const { venue, pinned, installed } = banner
  switch (banner.kind) {
    case 'held':
      return `Output held — this show is in Blind. It uses ${venue} v${pinned}; the hall has been re-patched since ` +
        `(v${installed} is as hung). Its addresses would drive whatever is hung there now.`
    case 'running':
      return `Running against v${pinned}. Live output uses the old house patch. v${installed}'s trigger and command ` +
        'channels stay guarded.'
    case 'drawing':
      return `${venue} has moved on. ${showName} uses v${pinned}; v${installed} is the hall as hung — ` +
        `${drawingChangesPhrase(drawingChanges)}, nothing that changes output.`
    case 'output':
      return `${venue} has been re-patched since v${pinned} (v${installed} is as hung). Opening ${showName} holds its ` +
        'output until it is updated or run against its own version.'
  }
}

/** Whether the app header's Blind chip carries the venue reason: the live show is held. */
export function heldByVenue(hold: VenueHoldState, currentProjectId: number | undefined): boolean {
  return hold.held && currentProjectId != null && hold.projectId === currentProjectId
}

/** The session's *Later* on a drawing-only banner: per show and installed version, so a newer one asks again. */
export function laterKey(projectId: number, installed: number): string {
  return `venue-later:${projectId}:v${installed}`
}
