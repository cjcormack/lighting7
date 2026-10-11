/**
 * The wire shapes of a venue's versions (lighting7 `routes/venues.kt`, `venue/VenueVersions.kt`,
 * `venue/VenueDiff.kt`) — venue plan session 2. All four routes are admin-only.
 */

/** One published version. `snapshotProjectId` is the frozen project every read route answers for. */
export interface VenueVersion {
  number: number
  note: string
  /** ISO-8601 UTC. */
  publishedAt: string
  publishedBy?: string | null
  snapshotProjectId: number
}

/** `GET /venues/{id}/versions`: newest first, and the draft against the installed version. */
export interface VenueHistory {
  venueId: number
  name: string
  /** The installed — latest published — version, or null before v1. */
  installed?: number | null
  draft: { changes: number; outputChanges: number }
  versions: VenueVersion[]
  /**
   * The shows on this venue (venue plan session 3), by name, each with the version it is pinned
   * to. Absent from a desk older than session 3.
   */
  shows?: VenueShow[]
}

/** A show on a venue, and the version it is pinned to. */
export interface VenueShow {
  id: number
  name: string
  version: number
}

/** `OTHER` is any table outside the rig a venue holds (a Look, a parked channel), named by `table`. */
export type VenueChangeKind = 'UNIVERSE' | 'PATCH' | 'GROUP' | 'RIGGING' | 'REGION' | 'ELEMENT' | 'VIEWPOINT' | 'STAGE' | 'OTHER'
export type VenueChangeType = 'ADDED' | 'REMOVED' | 'CHANGED'
/** The plan's four facets (Address, Position, Focus, Media), plus Type, Identity and Other. */
export type VenueFacet = 'ADDRESS' | 'TYPE' | 'IDENTITY' | 'POSITION' | 'FOCUS' | 'MEDIA' | 'OTHER'

export interface VenueFieldChange {
  field: string
  from?: string | null
  to?: string | null
}

export interface VenueChange {
  kind: VenueChangeKind
  /** The export folder the record lives in (`fixturePatches`). */
  table: string
  uuid?: string | null
  name: string
  change: VenueChangeType
  /** Changes output: a universe or patch added, removed or re-addressed, or a patch re-typed. */
  output: boolean
  /** A patch's only. */
  facets?: VenueFacet[]
  fields?: VenueFieldChange[]
  /** A patch's `1-005`, or a universe's `ARTNET 0:3`. */
  address?: string | null
}

/** `GET /venues/{id}/versions/{from}/diff/{to}`: version numbers, 0 the empty venue, null the draft. */
export interface VenueDiff {
  from?: number | null
  to?: number | null
  output: VenueChange[]
  drawing: VenueChange[]
}

/**
 * How far a show trails its venue (venue plan §3.1, session 6): `OUTPUT` opens it held, `DRAWING` is a
 * banner. On `ProjectVenue.behind`; absent from a desk older than session 6.
 */
export type VenueBehind = 'NONE' | 'DRAWING' | 'OUTPUT'

/** An answer to one of the update review's decisions (P8), or to a drift. */
export type UpdateAnswer = 'KEEP_BOTH' | 'READDRESS_MINE' | 'HIDE_VENUES' | 'REMOVE_MINE' | 'KEEP_MINE' | 'TAKE_VENUES'

/** One line of the review: a record carried over, dropped, or blocking. */
export interface UpdateItem {
  /** `OVERRIDE | HIDE | ADDITION | LINK | KEY_CLASH | UNIVERSE_REMOVED`. */
  kind: string
  name: string
  detail: string
  recordUuid?: string | null
}

/** An override the venue moved underneath: kept unless answered `TAKE_VENUES`. */
export interface UpdateDrift {
  id: string
  recordUuid: string
  patchKey?: string | null
  patchName?: string | null
  facet: 'ADDRESS' | 'POSITION' | 'FOCUS' | 'MEDIA'
  value?: Record<string, unknown> | null
  venue?: Record<string, unknown> | null
  base?: Record<string, unknown> | null
  answer?: UpdateAnswer
}

export interface UpdateUnit {
  uuid: string
  patchId: number
  key: string
  name: string
  address: string
  /** `OWN | OVERRIDE | VENUE`. */
  kind: string
}

/** A clash the update introduces that needs an answer before it can run. */
export interface UpdateDecision {
  id: string
  kind: string
  message: string
  mine: UpdateUnit
  other: UpdateUnit
  answers: UpdateAnswer[]
  suggestedStartChannel?: number | null
  suggestedAddress?: string | null
  decided?: boolean
  answer?: UpdateAnswer | null
  startChannel?: number | null
}

export interface DeadReference {
  /** `FIXTURE | GROUP | OVERRIDE`. */
  kind: string
  key: string
  name?: string | null
  usedBy?: { kind: string; id: number; name: string }[]
  detail: string
}

/** `GET /projects/{id}/venue/update?to=n` — the review — and what `POST` did. */
export interface VenueUpdateReview {
  projectId: number
  venueId: number
  venueName: string
  from: number
  to: number
  fromNote?: string | null
  toNote?: string | null
  output?: VenueChange[]
  drawing?: VenueChange[]
  carryOver?: UpdateItem[]
  drift?: UpdateDrift[]
  dropped?: UpdateItem[]
  decisions?: UpdateDecision[]
  blockers?: UpdateItem[]
  deadReferences?: DeadReference[]
  dryRun?: boolean
  applied?: boolean
}

export interface UpdateDecisionAnswer {
  id: string
  answer: UpdateAnswer
  startChannel?: number
}
