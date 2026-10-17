import type { ProjectSummary, VenueSummary } from '../api/projectApi'
import type { VenueHoldState } from '../api/venueHoldApi'
import type { VenueHistory } from '../api/venueApi'
import type { CueStack, CueStackCueRow } from '../api/cueStacksApi'
import { cueRowById, isCueRow } from '../api/cueStacksApi'
import type { CueHoldsState } from '../api/cueHoldsApi'
import type { SyncConfig } from '../store/cloudSync'
import { formatRepoUrl } from './formatRepoUrl'
import { pinChipOf, type PinChip } from './projectSwitch'
import { changesPhrase, publishedDate } from './venue'
import { behindBanner, behindTone, type BehindBanner, type BehindTone } from './venueHold'

/**
 * A project home's status block, in words (navigation plan §5.1, §5.2): a show's and a venue's.
 * Pure, so the lines are pinned by `projectHome.test.ts`; `routes/ProjectHome.tsx` and
 * `components/projectHome/VenueHome.tsx` draw them.
 */

/** The Venue line: the pin and how it stands, coloured as the behind banner is, or null for a standalone show. */
export interface VenueLine {
  text: string
  tone: BehindTone
  /** What the banner would offer — *Review update*, *Run against vN…* — or null when the show is not behind. */
  banner: BehindBanner | null
}

export function venueLineOf(project: Pick<ProjectSummary, 'id' | 'venue'>, hold: VenueHoldState): VenueLine | null {
  const chip = pinChipOf(project, hold)
  if (chip == null) return null
  const banner = behindBanner(project.id, project.venue, hold)
  const text = `On ${chip.label}${chip.status ? ` · ${chip.status}` : ''}`
  const tone: BehindTone = banner ? behindTone(banner) : chip.tone === 'missing' ? 'red' : 'neutral'
  return { text, tone, banner }
}

/** One stack's live cue, for the Live line. */
export interface LiveCue {
  stackId: number
  stack: string
  /** `Q12 · Opening`, or the cue's name alone when it has no number. */
  cue: string
}

/**
 * The live cue of every runnable stack that has one, in show order. The row is the live **entry**'s,
 * since a cue placed twice in one stack has a number per place.
 */
export function liveCuesOf(stacks: readonly CueStack[] | undefined): LiveCue[] {
  return (stacks ?? [])
    .filter((s) => s.type !== 'SEPARATOR' && s.activeCueId != null)
    .map((s) => {
      const row =
        s.cues.find((c): c is CueStackCueRow => isCueRow(c) && c.entryId === s.activeEntryId) ??
        cueRowById(s.cues, s.activeCueId)
      const number = row?.cueNumber ? `Q${row.cueNumber}` : null
      const cue = row == null ? 'a cue' : number ? `${number} · ${row.name}` : row.name
      return { stackId: s.id, stack: s.name, cue }
    })
}

/**
 * The cues a press holds loose (a busk cue pad, a cue slot), live above every stack with no stack's
 * playhead on them, by name.
 */
export function looseCuesOf(holds: CueHoldsState, cues: readonly { id: number; name: string }[] | undefined): string[] {
  return holds.cues
    .filter((c) => c.holds.some((h) => h.kind === 'LOOSE'))
    .map((c) => cues?.find((cue) => cue.id === c.cueId)?.name ?? 'a cue')
}

/**
 * `just now`, `5 min ago`, `3 h ago`, then the date. A time a little ahead of [now] — a sync that
 * landed since the clock last ticked, or a browser clock behind the desk's — is just now too.
 */
export function agoOf(iso: string, now: number): string {
  const ms = now - new Date(iso).getTime()
  if (Number.isNaN(ms)) return ''
  const min = Math.floor(ms / 60_000)
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h} h ago`
  return new Date(iso).toLocaleDateString()
}

/** The Sync line: `Synced · owner/repo · 2 min ago`, or `Not synced`. */
export function syncLineOf(config: SyncConfig | undefined, now: number): string {
  if (config?.synced !== true || config.repoUrl == null) return 'Not synced'
  const repo = formatRepoUrl(config.repoUrl) ?? config.repoUrl
  const when = config.lastSyncedAt ? agoOf(config.lastSyncedAt, now) : 'never synced'
  return `Synced · ${repo} · ${when}`
}

/**
 * A venue's Version line (§5.2): `v8 · 14 Oct 2026 · “re-patched LX2”`. An admin's comes from the
 * venue's history, which carries the note; an operator's — the history route is an admin's — from
 * the project list's `venueSummary`, which carries the version and its date but no note.
 */
export function venueVersionLineOf(
  history: Pick<VenueHistory, 'installed' | 'versions'> | null | undefined,
  summary: Pick<VenueSummary, 'version' | 'publishedAt'> | null | undefined,
): string {
  const number = history ? history.installed : summary?.version
  if (number == null) return 'Nothing published yet'
  const version = history?.versions.find((v) => v.number === number)
  const at = version?.publishedAt ?? (history ? null : summary?.publishedAt)
  const note = version?.note?.trim()
  return [`v${number}`, at ? publishedDate(at) : null, note ? `“${note}”` : null].filter((p) => p != null).join(' · ')
}

/** How loud the Draft line is: the band's chip colours — red-amber when a change would change output. */
export type DraftTone = 'none' | 'drawing' | 'output'

/**
 * A venue's Draft line (§5.2), read from the history the band reads, so the two count alike:
 * `Draft: 3 changes (1 changes output)` — the second count is how many of them change output.
 */
export function draftLineOf(history: Pick<VenueHistory, 'installed' | 'draft'>): { text: string; tone: DraftTone } {
  const { changes, outputChanges } = history.draft
  const output = outputChanges > 0 ? ` (${outputChanges} ${outputChanges === 1 ? 'changes' : 'change'} output)` : ''
  const unpublished = history.installed == null && changes > 0 ? ', unpublished' : ''
  return {
    text: `Draft: ${changesPhrase(changes)}${output}${unpublished}`,
    tone: changes === 0 ? 'none' : outputChanges > 0 ? 'output' : 'drawing',
  }
}

/** A show on a venue, as the venue's home lists it: its pin and how it stands. */
export interface VenueShowRow {
  id: number
  name: string
  version: number
  isCurrent: boolean
  /** The pin chip's status (`held`, `v8 published`, `not on this desk`), or null when it is up to date. */
  status: string | null
  tone: PinChip['tone']
  /** The project, for its actions. */
  project: ProjectSummary
}

/**
 * The shows pinned to venue [venueId], by name, from the project list — which says how far behind
 * each is, where the history's `shows` names only the version. An operator reads it too.
 */
export function venueShowsOf(
  venueId: number,
  projects: readonly ProjectSummary[] | undefined,
  hold: VenueHoldState,
): VenueShowRow[] {
  return (projects ?? [])
    .filter((p) => p.kind !== 'VENUE' && p.kind !== 'VENUE_VERSION' && p.venue?.id === venueId)
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((p) => {
      const chip = pinChipOf(p, hold)
      return {
        id: p.id,
        name: p.name,
        version: p.venue!.version,
        isCurrent: p.isCurrent,
        status: chip?.status ?? null,
        tone: chip?.tone ?? 'none',
        project: p,
      }
    })
}
