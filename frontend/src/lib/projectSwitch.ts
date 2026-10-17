import type { ProjectSummary } from '../api/projectApi'
import type { VenueHoldState } from '../api/venueHoldApi'
import type { CommandRun, RigSafetyState } from '../api/rigSafetyApi'
import { roleLabel, unitsEnded } from './rigSafety'

/**
 * The app bar's switcher and the one switch confirmation, in words and states (navigation plan §4,
 * D1, D5). Pure, so the copy and the ordering are pinned by `projectSwitch.test.ts`.
 *
 * "On the rig" is the server's `isCurrent` (P4): the switcher always names the project the list says
 * is current, never the one the URL is viewing.
 */

/** What the desk calls itself when the install has no name yet. */
export const DEFAULT_DESK_NAME = 'Lighting desk'

/** The desk's friendly name (D5), else [DEFAULT_DESK_NAME]. */
export function deskNameOf(install: { friendlyName?: string | null } | null | undefined): string {
  const name = install?.friendlyName?.trim()
  return name ? name : DEFAULT_DESK_NAME
}

/** `Sleeping Beauty · Hall desk`, or the desk's name alone before the list says what is on the rig. */
export function documentTitleOf(current: { name: string } | null | undefined, deskName: string): string {
  return current ? `${current.name} · ${deskName}` : deskName
}

/**
 * The switcher's dot: **sending** (green), **held** behind its venue (amber, `venue.hold`), dark by
 * *Make rig safe*'s **blackout** (a hollow ring — the rig is not sending, and nothing is wrong with
 * it), or **offline** (the desk cannot say). The blackout wins over the hold: both are dark, and the
 * blackout is the one the operator made.
 */
export type RigDot = 'sending' | 'held' | 'blackout' | 'offline'

export const RIG_DOT_LABEL: Record<RigDot, string> = {
  sending: 'Sending to the rig',
  held: 'Held behind its venue: only parked channels reach the rig',
  blackout: 'Blacked out by Make rig safe',
  offline: 'The desk is offline',
}

export function rigDotOf(
  currentId: number | null | undefined,
  hold: VenueHoldState,
  safety: RigSafetyState,
  connected: boolean,
): RigDot {
  if (!connected) return 'offline'
  if (currentId != null && safety.blackout && safety.projectId === currentId) return 'blackout'
  if (currentId != null && hold.held && hold.projectId === currentId) return 'held'
  return 'sending'
}

/**
 * A show whose pinned venue version is not on this desk (`VENUE_MISSING`): it cannot be made
 * current. The venue is not here at all, or it is and holds no version, or its newest version is
 * older than the pin — the desk's `VenuePins.isMissing`, as far as the list can tell (a gap below
 * the newest version is the desk's to refuse).
 */
export function venueMissing(project: Pick<ProjectSummary, 'venue'>): boolean {
  const venue = project.venue
  if (venue == null) return false
  return venue.id == null || venue.installed == null || venue.version > venue.installed
}

/** Why [project] cannot be made current, or null when it can (§4.2). */
export function makeCurrentRefusal(project: Pick<ProjectSummary, 'venue'>): string | null {
  if (!venueMissing(project)) return null
  const venue = project.venue!
  return `Its venue, ${venue.name ? `${venue.name} ` : ''}v${venue.version}, isn’t on this desk`
}

/**
 * The pin chip after the switcher button (§4.1): a show on a venue names its pin, and *held* while
 * the desk holds it or *vN published* while it is behind. `tone` picks the banner's colour.
 */
export interface PinChip {
  label: string
  status: string | null
  tone: 'held' | 'output' | 'drawing' | 'missing' | 'none'
}

export function pinChipOf(project: Pick<ProjectSummary, 'id' | 'venue'>, hold: VenueHoldState): PinChip | null {
  const venue = project.venue
  if (venue == null) return null
  const label = `${venue.name ?? 'Venue'} v${venue.version}`
  if (venueMissing(project)) return { label, status: 'not on this desk', tone: 'missing' }
  if (hold.held && hold.projectId === project.id) return { label, status: 'held', tone: 'held' }
  if (venue.installed != null && venue.installed > venue.version) {
    return { label, status: `v${venue.installed} published`, tone: venue.behind === 'OUTPUT' ? 'output' : 'drawing' }
  }
  return { label, status: null, tone: 'none' }
}

/** A row's detail in the menu: the pin (`TCH venue v7 · held`), `no venue`, or a venue's `v8 · 2 shows`. */
export function switcherDetailOf(project: ProjectSummary, hold: VenueHoldState): string {
  if (project.kind === 'VENUE') {
    const s = project.venueSummary
    if (s?.version == null) return 'no version yet'
    const shows = s.shows ?? 0
    return `v${s.version} · ${shows} ${shows === 1 ? 'show' : 'shows'}`
  }
  const chip = pinChipOf(project, hold)
  if (chip == null) return 'no venue'
  return chip.status ? `${chip.label} · ${chip.status}` : chip.label
}

/** The menu's three groups (§4.2): the project on the rig, then shows and venues, each by name. */
export interface SwitcherGroups {
  onRig: ProjectSummary | null
  shows: ProjectSummary[]
  venues: ProjectSummary[]
}

const byName = (a: ProjectSummary, b: ProjectSummary) =>
  a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }) || a.id - b.id

/** [projects] grouped, filtered to names containing [query] (case-insensitive). */
export function switcherGroupsOf(projects: readonly ProjectSummary[], query: string): SwitcherGroups {
  const needle = query.trim().toLowerCase()
  const matches = projects.filter((p) => p.kind !== 'VENUE_VERSION' && (needle === '' || p.name.toLowerCase().includes(needle)))
  return {
    onRig: matches.find((p) => p.isCurrent) ?? null,
    shows: matches.filter((p) => !p.isCurrent && p.kind !== 'VENUE').sort(byName),
    venues: matches.filter((p) => !p.isCurrent && p.kind === 'VENUE').sort(byName),
  }
}

/** The groups in the order the menu draws them: what Enter's "first match" means. */
export function switcherOrderOf(groups: SwitcherGroups): ProjectSummary[] {
  return [...(groups.onRig ? [groups.onRig] : []), ...groups.shows, ...groups.venues]
}

/**
 * The one confirmation (§4.3): what a switch does to the rig (`docs/dmx-engineering.md` §"Handing the
 * rig from one show to the next"), plus each line that applies — the incoming show opening held, a
 * bulk lamp run on the outgoing show cut off, the incoming show still blacked out by *Make rig safe*.
 */
export interface SwitchConfirm {
  title: string
  body: string
  /** Each consequence that applies to this switch, in the order the plan lists them. */
  lines: { kind: 'held' | 'run' | 'blackout'; text: string }[]
  confirmLabel: string
}

export function switchConfirmOf(
  incoming: ProjectSummary,
  current: Pick<ProjectSummary, 'id' | 'name'> | null,
  safety: RigSafetyState,
): SwitchConfirm {
  const name = incoming.name
  const parks = incoming.parks
  const dark =
    parks == null
      ? `every channel goes dark except those ${name} parks.`
      : parks === 0
        ? `every channel goes dark: ${name} parks none.`
        : `every channel goes dark except those ${name} parks (${parks}).`
  const body = current ? `${current.name}’s cues, programmer and effects stop, and ${dark}` : capitalise(dark)

  const lines: SwitchConfirm['lines'] = []
  const venue = incoming.venue
  if (venue != null && venue.behind === 'OUTPUT' && venue.installed != null) {
    lines.push({
      kind: 'held',
      text: `${name} is behind ${venue.name ?? 'its venue'} on output (v${venue.version}, v${venue.installed} published). It opens held: only parked channels reach the rig.`,
    })
  }
  const run = safety.run
  if (current != null && run != null && !run.finished && safety.projectId === current.id) {
    lines.push({ kind: 'run', text: runLine(run, current.name) })
  }
  if (incoming.safeBlackout === true) {
    lines.push({
      kind: 'blackout',
      text: `${name} was made safe and is still blacked out: it opens dark, with Undo on its banner.`,
    })
  }
  return { title: `Put ${name} on the rig?`, body, lines, confirmLabel: `Put ${name} on the rig` }
}

function runLine(run: CommandRun, showName: string): string {
  const what =
    run.purpose === 'MAKE_SAFE'
      ? 'Make rig safe'
      : run.purpose === 'UNDO'
        ? 'Undo of Make rig safe'
        : `${roleLabel(run.role).toLowerCase()} run`
  return `${showName}’s ${what} stops at ${unitsEnded(run)} of ${run.units.length}: the units it hasn’t reached are left as they are.`
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}
