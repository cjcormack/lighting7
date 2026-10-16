import type { VenueChange, VenueFacet, VenueHistory } from '../api/venueApi'
import type { ProjectVenue, VenueSummary } from '../api/projectApi'

/**
 * Venue mode's words (venue plan §3.1, session 2; the boards in lighting7
 * `docs/plans/completed/venue-design/Venue.dc.html`). A venue is a project of kind `VENUE`; its
 * own rows are the **draft**, and publishing freezes the draft as the next **version**. Pure, so
 * the copy is pinned by `venue.test.ts` rather than by a render.
 */

export const FACET_LABELS: Record<VenueFacet, string> = {
  ADDRESS: 'Address',
  TYPE: 'Type',
  IDENTITY: 'Identity',
  POSITION: 'Position',
  FOCUS: 'Focus',
  MEDIA: 'Media',
  OTHER: 'Other',
}

/** The short word a field reads as in a change line — the board's `x −1.20 → −0.90 m`, `gel L201 → L202`. */
const FIELD_WORDS: Record<string, string> = {
  stageX: 'x',
  stageY: 'y',
  stageZ: 'z',
  positionX: 'x',
  positionY: 'y',
  positionZ: 'z',
  centerX: 'x',
  centerY: 'y',
  centerZ: 'z',
  baseYawDeg: 'yaw',
  basePitchDeg: 'pitch',
  baseRollDeg: 'roll',
  yawDeg: 'yaw',
  pitchDeg: 'pitch',
  rollDeg: 'roll',
  lengthM: 'length',
  widthM: 'width',
  depthM: 'depth',
  heightM: 'height',
  stageWidthM: 'width',
  stageDepthM: 'depth',
  stageHeightM: 'height',
  gelCode: 'gel',
  zoomDeg: 'zoom',
  beamAngleDeg: 'beam',
  lampWatts: 'lamp watts',
  lampKelvin: 'lamp colour temperature',
  locate: 'locate',
  lanternType: 'lantern',
  fixtureTypeKey: 'type',
  displayName: 'name',
  headNumber: 'head',
  extraPlacements: 'placements',
  universeConfigUuid: 'universe',
}

const KIND_WORDS: Record<VenueChange['kind'], string> = {
  UNIVERSE: 'universe',
  PATCH: 'fixture',
  PARK: 'park',
  GROUP: 'group',
  RIGGING: 'rigging',
  REGION: 'region',
  ELEMENT: 'scene element',
  VIEWPOINT: 'viewpoint',
  STAGE: 'stage',
  OTHER: 'record',
}

/** What a change is to, in a word — a table outside the rig by its own name (`looks`). */
function kindWord(change: VenueChange): string {
  return change.kind === 'OTHER' ? change.table : KIND_WORDS[change.kind]
}

/** `+`, `−` or `~`: the board's sign column. */
export function changeSign(change: VenueChange): string {
  return change.change === 'ADDED' ? '+' : change.change === 'REMOVED' ? '−' : '~'
}

function fieldWord(field: string): string {
  return FIELD_WORDS[field] ?? field
}

/** `x −1.20 → −0.90`: one field's change, `—` for none. A long value (an object) is cut short. */
export function fieldChangeLine(field: { field: string; from?: string | null; to?: string | null }): string {
  const side = (v: string | null | undefined) => {
    if (v == null || v === '') return '—'
    return v.length > 24 ? `${v.slice(0, 23)}…` : v
  }
  return `${fieldWord(field.field)} ${side(field.from)} → ${side(field.to)}`
}

/**
 * A change as the Publish sheet and History's compare list draw it: a title and an optional detail
 * line. An addition or a removal is the record and where it lives (`LX3 BATTEN 1 · 3-001`); an
 * address change is the old and new address on the title (`HAZER · 1-500 → 1-512`); anything else
 * names what changed and says how underneath (`LX2 FRES position` / `x −1.20 → −0.90`).
 */
export function changeLine(change: VenueChange): { title: string; detail: string | null } {
  // A park is named by its address already (`Park 0-012`), so it is not said twice.
  const where = change.address && change.kind !== 'PARK' ? ` · ${change.address}` : ''
  if (change.change !== 'CHANGED') {
    const what = change.kind === 'PATCH' || change.kind === 'UNIVERSE' || change.kind === 'PARK' ? '' : ` (${kindWord(change)})`
    return { title: `${change.name}${what}${where}`, detail: null }
  }
  const fields = change.fields ?? []
  const address = fields.find((f) => f.field === 'address')
  if (address) {
    const rest = fields.filter((f) => f !== address)
    return {
      title: `${change.name} · ${address.from ?? '—'} → ${address.to ?? '—'}`,
      detail: rest.length > 0 ? rest.slice(0, 2).map(fieldChangeLine).join('; ') : null,
    }
  }
  // A park's key is its channel, so its value is all that can change: `Park 1-005` / `value 128 → 200`.
  if (change.kind === 'PARK') {
    return { title: change.name, detail: fields.length > 0 ? fields.map(fieldChangeLine).join('; ') : null }
  }
  const facets = (change.facets ?? []).filter((f) => f !== 'OTHER')
  const what = facets.length > 0
    ? facets.map((f) => FACET_LABELS[f].toLowerCase()).join(', ')
    : kindWord(change)
  const more = fields.length > 2 ? ` (+${fields.length - 2} more)` : ''
  return {
    title: `${change.name} ${what}`,
    detail: fields.length > 0 ? fields.slice(0, 2).map(fieldChangeLine).join('; ') + more : null,
  }
}

/**
 * A patch row's Draft marker: `Added`, `Removed`, or what changed — a single field's facet with its
 * values (`Media · L201 → L202`, `Address · 1-500 → 1-512`), else the facets (`Position · Focus`).
 */
export function draftMarker(change: VenueChange): string {
  if (change.change === 'ADDED') return 'Added'
  if (change.change === 'REMOVED') return 'Removed'
  const facets = change.facets ?? []
  const fields = change.fields ?? []
  if (facets.length === 1 && fields.length === 1) {
    const f = fields[0]
    if (f.field === 'address' || facets[0] === 'MEDIA' || facets[0] === 'TYPE') {
      return `${FACET_LABELS[facets[0]]} · ${f.from ?? '—'} → ${f.to ?? '—'}`
    }
  }
  return facets.length > 0 ? facets.map((f) => FACET_LABELS[f]).join(' · ') : 'Changed'
}

/** `6 changes`, `1 change`, `no changes`. */
export function changesPhrase(n: number): string {
  return n === 0 ? 'no changes' : n === 1 ? '1 change' : `${n} changes`
}

/** The header chip: `Draft · 6 changes since v5`, or before v1 `Draft · 6 changes, unpublished`. */
export function draftChipLabel(history: Pick<VenueHistory, 'installed' | 'draft'>): string {
  const phrase = changesPhrase(history.draft.changes)
  if (history.installed == null) return `Draft · ${phrase}, unpublished`
  return `Draft · ${phrase} since v${history.installed}`
}

/** The version the next publish makes. */
export function nextVersion(history: Pick<VenueHistory, 'installed'> | undefined): number {
  return (history?.installed ?? 0) + 1
}

/** `3 Oct 2026`: a version's publish date as History writes it. */
export function publishedDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}

/**
 * A show card's venue badge (`Projects.dc.html`'s callout 2): the venue and the version the show is
 * pinned to, and — when a newer version is published — what that means for it. `drawing`: a newer
 * version exists whose changes reach the show as drawing only, or not at all (amber, *v5 available*);
 * `output`: the newer version changes output, so the show opens held (red-amber, *v5 · re-patched*).
 * A venue this desk does not hold reads as its uuid's start.
 */
export function venueBadge(venue: ProjectVenue): {
  label: string
  tone: 'current' | 'drawing' | 'output'
  newer: string | null
} {
  const name = venue.name ?? `Venue ${venue.uuid.slice(0, 8)}`
  const label = `${name} · v${venue.version}`
  const installed = venue.installed ?? null
  if (installed == null || installed <= venue.version) return { label, tone: 'current', newer: null }
  if (venue.behind === 'OUTPUT') return { label, tone: 'output', newer: `v${installed} · re-patched` }
  return { label, tone: 'drawing', newer: `v${installed} available` }
}

/**
 * A venue card's two lines (`Projects.dc.html`'s Venues row): what its installed version holds —
 * `50 house fixtures · 4 universes · 6 riggings · fixed balcony` — and its shows —
 * `3 shows · 2 behind`, `no shows yet`.
 */
export function venueSummaryLines(summary: VenueSummary): { rig: string; shows: string } {
  const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? '' : 's'}`
  const parts = [
    plural(summary.patches ?? 0, 'house fixture'),
    plural(summary.universes ?? 0, 'universe'),
    plural(summary.riggings ?? 0, 'rigging'),
    ...(summary.fixedSeating ?? []).map((name) => `fixed ${name.toLowerCase()}`),
  ]
  const shows = summary.shows ?? 0
  const behind = summary.behind ?? 0
  return {
    rig: parts.join(' · '),
    shows: shows === 0 ? 'no shows yet' : behind > 0 ? `${plural(shows, 'show')} · ${behind} behind` : plural(shows, 'show'),
  }
}
