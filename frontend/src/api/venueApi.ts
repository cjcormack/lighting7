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

/** `PARK` is a parked channel (output, by channel); `OTHER` is any table outside the rig a venue holds (a Look, a script), named by `table`. */
export type VenueChangeKind = 'UNIVERSE' | 'PATCH' | 'PARK' | 'GROUP' | 'RIGGING' | 'REGION' | 'ELEMENT' | 'VIEWPOINT' | 'STAGE' | 'OTHER'
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
  /** `OVERRIDE | HIDE | ADDITION | PARK | RELEASE | LINK | KEY_CLASH | UNIVERSE_REMOVED`. */
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

/**
 * The records an extract moves, or a detach copies, each named as the Patch list and the rigging views
 * name it (`subnet:universe` for a universe, a key for a patch).
 */
export interface VenueRigRows {
  universes: string[]
  patches: string[]
  riggings: string[]
  regions: string[]
  elements: string[]
  groups: string[]
  viewpoints: string[]
  /** Parked channels, as `1-005` (venue parks); absent when none. */
  parks?: string[]
}

/** The parts of a show's rig *Extract a venue* can move; all of them by default (venue plan §3.5). */
export type ExtractKind = 'UNIVERSES' | 'PATCHES' | 'RIGGINGS' | 'REGIONS' | 'ELEMENTS' | 'SEATING' | 'GROUPS' | 'VIEWPOINTS' | 'PARKS'

/** `POST /projects/{id}/venue/extract` — what an extract did, or with `dryRun` would do. */
export interface ExtractVenueResult {
  dryRun: boolean
  /** The new venue; null on a dry run, which keeps nothing. */
  venueId?: number | null
  venueUuid: string
  name: string
  version: number
  moved: VenueRigRows
  /** Preset seating copied into the venue and kept by the show (D6). */
  copiedPresetSeating: string[]
  relinked: Record<string, number>
  overridesMoved: number
  imagesCopied: number
}

/**
 * Where a standalone show was detached from: the venue by uuid (its id and name when it is on this
 * desk) and the version it was pinned to.
 */
export interface DetachedFrom {
  venueUuid: string
  version: number
  venueId?: number | null
  venueName?: string | null
}

/** What attach pairs, hides and lists: the six hideable kinds, universes, parks and the stage. */
export type AttachKind = 'UNIVERSE' | 'PATCH' | 'RIGGING' | 'REGION' | 'ELEMENT' | 'VIEWPOINT' | 'GROUP' | 'PARK' | 'STAGE'

/** A record the attach review names: a patch's key, a universe's `subnet:universe`. */
export interface AttachRecord {
  uuid: string
  name: string
  key?: string | null
}

/**
 * One line of the attach review. `reason` is the line's code: `CHANGE`, `ADDITION`, `KEPT`; a hide's
 * `KEPT` / `REMOVED_SINCE_ORIGIN` / `KEY_CLASH` / `ANSWERED` / `RELEASE`; an incoming record's
 * `NO_ORIGIN` / `ADDED_SINCE_ORIGIN` / `HIDE_REFUSED` / `UNIVERSE`; a blocker's `KEY_CLASH` /
 * `HEAD_CLASH` / `RENAME_TAKEN`.
 */
export interface AttachItem {
  kind: AttachKind
  show?: AttachRecord | null
  venue?: AttachRecord | null
  detail: string
  reason?: string | null
}

/** A facet the show's patch differs in, written as an override of the venue's. */
export interface AttachOverride {
  venue: AttachRecord
  facet: 'ADDRESS' | 'POSITION' | 'FOCUS' | 'MEDIA'
  value?: Record<string, unknown> | null
  base?: Record<string, unknown> | null
  venueValue?: Record<string, unknown> | null
  drift?: boolean
  /** The drift's decision. */
  decisionId?: string | null
  dormant?: boolean
}

/** Who names a key: `CUE | LOOK | TEMPLATE | BINDING | SCRIPT`. */
export interface AttachUsedBy {
  kind: string
  id: number
  name: string
}

/** What *Take venue's* renames: the show's references move from `from` to `to`. */
export interface AttachRename {
  kind: AttachKind
  from: string
  to: string
  show: AttachRecord
  venue: AttachRecord
  usedBy?: AttachUsedBy[]
}

/** `IDENTITY` (D2), `HALL` and `GROUP` (D3), `DRIFT` — each `KEEP_MINE | TAKE_VENUES` — and `HIDE`. */
export type AttachDecisionKind = 'IDENTITY' | 'HALL' | 'GROUP' | 'DRIFT' | 'HIDE'

/** A choice the review offers, with its default applied unless answered. */
export interface AttachDecision {
  id: string
  kind: AttachDecisionKind
  recordKind: AttachKind
  show?: AttachRecord | null
  venue: AttachRecord
  message: string
  answers: UpdateAnswer[]
  /** The answer in force: the operator's, else the default. */
  answer: UpdateAnswer
  decided?: boolean
  bothChanged?: boolean
  rename?: AttachRename | null
}

/** The origin a detached show recorded, and whether the review used it. */
export interface AttachOrigin {
  venueUuid: string
  version: number
  status: 'USED' | 'MISSING' | 'OTHER_VENUE'
  detail: string
}

/**
 * `GET /projects/{id}/venue/attach?venue=&to=` — the review, the attach rolled back on the desk — and
 * what `POST` did (venue-attach plan §4.1).
 */
export interface AttachReview {
  projectId: number
  venueId: number
  venueName: string
  from?: number | null
  to: number
  installed: number
  behind?: VenueBehind
  /** The show is live and attaches behind on output: it runs against v`to` rather than going dark. */
  runAgainst?: boolean
  origin?: AttachOrigin | null
  output?: AttachItem[]
  drawing?: AttachItem[]
  inherited?: Partial<Record<AttachKind, number>>
  overrides?: AttachOverride[]
  kept?: AttachItem[]
  hides?: AttachItem[]
  additions?: AttachItem[]
  incoming?: AttachItem[]
  renames?: AttachRename[]
  decisions?: AttachDecision[]
  /** Address overlaps the attach introduces (P8); one unanswered refuses it. */
  overlaps?: UpdateDecision[]
  blockers?: AttachItem[]
  deadReferences?: DeadReference[]
  notes?: string[]
  dryRun?: boolean
  applied?: boolean
}

/** `POST /projects/{id}/detach` — what a detach did, or with `dryRun` would do (venue plan D9, session 7). */
export interface DetachResult {
  dryRun: boolean
  projectId: number
  venueId: number
  venueName: string
  /** The version the show was pinned to, and is flattened at. */
  version: number
  installed?: number | null
  /** How far behind the pin was: the venue's later changes do not come with it. */
  behind?: VenueBehind
  /** The origin the show now records; absent from a desk older than venue attach. */
  detachedFrom?: DetachedFrom
  copied: VenueRigRows
  /** `key: facet, facet` for each venue patch whose overrides were written in. */
  overridesApplied: string[]
  hidesDropped: number
  /** The show's releases of venue parks, gone with the venue; absent when none. */
  parkReleasesDropped?: number
  relinked: Record<string, number>
  dropped?: UpdateItem[]
  machineOverridesCopied: number
  imagesCopied: number
}

/** A seating block: a venue's preset, or a show's own seating. */
export interface SeatingBlock {
  uuid: string
  name: string
  seats: number
  /** A show's copy that keeps a preset's uuid: it reads as that preset. */
  fromPreset?: boolean
}

/**
 * `GET /projects/{id}/seating/presets` (venue plan D6, session 7): the Preset seating a venue offers at
 * its installed version, or a show at its pinned one, and a show's own seating as it stands.
 */
export interface SeatingPresets {
  venueId?: number | null
  venueName?: string | null
  /** Null for a venue with nothing published. */
  version?: number | null
  publishedAt?: string | null
  note?: string | null
  presets?: SeatingBlock[]
  current?: SeatingBlock[]
}

/** What *Replace from preset* did. */
export interface ReplaceSeatingResult {
  seating: SeatingBlock
  removed: string[]
  viewsReseated: number
  viewsWithoutSeat?: string[]
  sceneryMoved?: number
  scenerySwept?: number
}
