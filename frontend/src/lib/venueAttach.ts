import type { ProjectSummary } from '../api/projectApi'
import type {
  AttachDecision,
  AttachItem,
  AttachKind,
  AttachRename,
  AttachReview,
  DetachedFrom,
  UpdateAnswer,
  UpdateDecisionAnswer,
} from '../api/venueApi'
import { formatError } from './formatError'
import { errorCode } from './venueSyncRefusal'

/**
 * *Attach to venue…*'s rules (venue-attach plan §5), apart from the sheet that draws them: which
 * venues it offers and in what order, which versions, what the matching line says, how a rename's
 * reach reads, and which answers go to the desk. The pairing is the desk's; nothing here judges it.
 */

/** A venue the sheet offers: its installed version (null before v1) and whether the show left it. */
export interface AttachVenueOption {
  id: number
  name: string
  installed: number | null
  /** The venue the show was detached from (`detachedFrom`). */
  origin: boolean
}

/** The desk's venues, the one the show was detached from first, then by name. */
export function attachVenuesOf(
  projects: readonly ProjectSummary[] | undefined,
  detachedFrom: DetachedFrom | null | undefined,
): AttachVenueOption[] {
  const originId = detachedFrom?.venueId ?? null
  return (projects ?? [])
    .filter((p) => p.kind === 'VENUE')
    .map((p) => ({ id: p.id, name: p.name, installed: p.venueSummary?.version ?? null, origin: p.id === originId }))
    .sort((a, b) => Number(b.origin) - Number(a.origin) || a.name.localeCompare(b.name))
}

/** The venue the sheet opens on: the first with a published version, which is the origin when it has one. */
export function defaultAttachVenue(venues: readonly AttachVenueOption[]): AttachVenueOption | null {
  return venues.find((v) => v.installed != null) ?? null
}

export interface AttachVersionOption {
  number: number
  /** Why this version is offered: the installed one, the one the show left, or both. */
  marks: ('installed' | 'origin')[]
}

/**
 * The versions offered on [venue]: its installed one, and — when the show was detached from it at an
 * older version the desk holds — that one too. [originHere] is false once a review has said the desk
 * lacks the origin version (`MISSING`), which would answer `VENUE_VERSION_UNKNOWN`.
 */
export function attachVersionsOf(
  venue: AttachVenueOption | null,
  detachedFrom: DetachedFrom | null | undefined,
  originHere: boolean,
): AttachVersionOption[] {
  if (venue?.installed == null) return []
  const originVersion = venue.origin && originHere ? detachedFrom?.version ?? null : null
  if (originVersion === venue.installed) return [{ number: venue.installed, marks: ['installed', 'origin'] }]
  const versions: AttachVersionOption[] = [{ number: venue.installed, marks: ['installed'] }]
  if (originVersion != null && originVersion < venue.installed) versions.push({ number: originVersion, marks: ['origin'] })
  return versions
}

export const NO_ORIGIN_LINE = 'No record of where this show came from: every venue record the show lacks comes in.'

/**
 * Whether matching is three-way: against the version the show left, or — with no origin, or one this
 * desk cannot use — two-way, every venue record the show lacks coming in. Null while the review that
 * says so is being read.
 */
export function matchingLineOf(
  showName: string,
  detachedFrom: DetachedFrom | null | undefined,
  review: Pick<AttachReview, 'origin'> | undefined,
): string | null {
  if (detachedFrom == null) return NO_ORIGIN_LINE
  if (review == null) return null
  const origin = review.origin
  if (origin == null) return NO_ORIGIN_LINE
  if (origin.status === 'USED') return `Using v${origin.version}, the version ${showName} left.`
  return `${origin.detail}.`
}

const KIND_NOUNS: Record<AttachKind, [string, string]> = {
  UNIVERSE: ['universe', 'universes'],
  PATCH: ['fixture', 'fixtures'],
  RIGGING: ['rigging', 'riggings'],
  REGION: ['stage region', 'stage regions'],
  ELEMENT: ['scene element', 'scene elements'],
  VIEWPOINT: ['viewpoint', 'viewpoints'],
  GROUP: ['group', 'groups'],
  PARK: ['parked channel', 'parked channels'],
  STAGE: ['stage', 'stages'],
}

const KIND_ORDER: AttachKind[] = ['PATCH', 'UNIVERSE', 'RIGGING', 'REGION', 'ELEMENT', 'VIEWPOINT', 'GROUP', 'PARK', 'STAGE']

/** A kind's noun, as the review's own lines name it: *fixture*, *scene element*. */
export function kindNounOf(kind: AttachKind): string {
  return KIND_NOUNS[kind][0]
}

function count(n: number, [one, many]: [string, string]): string {
  return `${n} ${n === 1 ? one : many}`
}

/** *54 fixtures, 4 universes, 7 riggings…*: what the venue's records replace, by kind; null for none. */
export function inheritedLineOf(inherited: AttachReview['inherited']): string | null {
  const parts = KIND_ORDER.filter((k) => (inherited?.[k] ?? 0) > 0).map((k) => count(inherited![k]!, KIND_NOUNS[k]))
  return parts.length > 0 ? parts.join(', ') : null
}

const USER_NOUNS: Record<string, [string, string]> = {
  CUE: ['cue', 'cues'],
  LOOK: ['Look', 'Looks'],
  PALETTE: ['palette', 'palettes'],
  BINDING: ['binding', 'bindings'],
}

/**
 * A rename's reach (§5): *renames hex-7 → hex-07 in 12 cues, 3 Looks, 1 palette, 1 binding*, from who
 * names the key as the review read it. Scripts are never rewritten, so they are said apart.
 */
export function reachLineOf(rename: AttachRename): string {
  const users = rename.usedBy ?? []
  const counts = new Map<string, number>()
  for (const u of users) counts.set(u.kind, (counts.get(u.kind) ?? 0) + 1)
  const rewritten = Object.keys(USER_NOUNS)
    .filter((k) => counts.has(k))
    .map((k) => count(counts.get(k)!, USER_NOUNS[k]))
  const head = `renames ${rename.from} → ${rename.to}`
  const where = rewritten.length > 0 ? `${head} in ${rewritten.join(', ')}` : `${head}; nothing names ${rename.from}`
  const scripts = counts.get('SCRIPT') ?? 0
  return scripts > 0 ? `${where}; ${count(scripts, ['script', 'scripts'])} still name${scripts === 1 ? 's' : ''} ${rename.from}` : where
}

/** One answer the operator gave, by id: a decision's or an overlap's. */
export interface AttachAnswer {
  answer: UpdateAnswer
}

/** Every id the review asks: the pairing's decisions and P8's overlaps. */
export function askedIdsOf(review: Pick<AttachReview, 'decisions' | 'overlaps'> | undefined): Set<string> {
  return new Set([...(review?.decisions ?? []).map((d) => d.id), ...(review?.overlaps ?? []).map((d) => d.id)])
}

/**
 * The answers to send, in id order, so a request is the same however the answers were given.
 *
 * Without [review] — a dry run — *Re-address mine* goes without a start channel, so the desk picks the
 * free one it suggests. With it — the attach — only answers to what it asks go (an answer to a decision
 * the desk no longer makes is refused as a whole, `ATTACH_INVALID`), and *Re-address mine* carries the
 * start channel that review suggested: the address its button showed, which another answer may have
 * moved since it was pressed.
 */
export function attachAnswersFor(
  answers: Readonly<Record<string, AttachAnswer>>,
  review?: Pick<AttachReview, 'decisions' | 'overlaps'>,
): UpdateDecisionAnswer[] {
  const asked = review ? askedIdsOf(review) : null
  const suggested = new Map((review?.overlaps ?? []).map((d) => [d.id, d.suggestedStartChannel ?? null]))
  return Object.entries(answers)
    .filter(([id]) => asked == null || asked.has(id))
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([id, { answer }]) => {
      const startChannel = answer === 'READDRESS_MINE' ? suggested.get(id) : null
      return { id, answer, ...(startChannel != null ? { startChannel } : {}) }
    })
}

/** The overlaps still waiting on an answer: one refuses the attach (`ATTACH_UNDECIDED`). */
export function undecidedOverlapsOf(review: Pick<AttachReview, 'overlaps'> | undefined, answers: Readonly<Record<string, AttachAnswer>>) {
  return (review?.overlaps ?? []).filter((d) => answers[d.id] == null)
}

/** Why the attach cannot be pressed yet, or null when it can. */
export function attachRefusalOf(
  review: Pick<AttachReview, 'overlaps' | 'blockers'> | undefined,
  answers: Readonly<Record<string, AttachAnswer>>,
): string | null {
  if (review == null) return 'Reading the review…'
  const blockers = review.blockers?.length ?? 0
  if (blockers > 0) return `Change the show first: ${blockers === 1 ? 'a clash' : `${blockers} clashes`} can't be kept.`
  const undecided = undecidedOverlapsOf(review, answers).length
  if (undecided > 0) return `${undecided} address overlap${undecided === 1 ? '' : 's'} still need${undecided === 1 ? 's' : ''} a decision.`
  return null
}

/** The `HIDE` decision a *Hide in this show* toggle answers for [item]: the one naming its venue record. */
export function hideDecisionOf(
  item: Pick<AttachItem, 'kind' | 'venue'>,
  hides: readonly AttachDecision[],
): AttachDecision | undefined {
  return item.venue == null ? undefined : hides.find((d) => d.recordKind === item.kind && d.venue.uuid === item.venue!.uuid)
}

/** The decisions grouped as the sheet draws them: drift with the overrides, hides with the incoming, the rest together. */
export function decisionGroupsOf(decisions: readonly AttachDecision[] | undefined): {
  choices: AttachDecision[]
  drift: AttachDecision[]
  hide: AttachDecision[]
} {
  const all = decisions ?? []
  return {
    choices: all.filter((d) => d.kind === 'IDENTITY' || d.kind === 'HALL' || d.kind === 'GROUP'),
    drift: all.filter((d) => d.kind === 'DRIFT'),
    hide: all.filter((d) => d.kind === 'HIDE'),
  }
}

/**
 * The answer a refused dry run names as one the desk no longer takes: answering one decision can retire
 * another (`No decision 'x' in this attach`), or take an answer off it — an overlap's *Re-address mine*
 * once no free start is left (`'READDRESS_MINE' does not answer 'x': …`). The answer must go. The two
 * sentences are pinned by `VenueAttachTest`.
 */
export function staleAnswerIdOf(error: unknown): string | null {
  if (errorCode(error) !== 'ATTACH_INVALID') return null
  const message = formatError(error)
  return (/^No decision '(.+)' in this attach$/.exec(message) ?? /^'[A-Z_]+' does not answer '(.+)': it takes /.exec(message))?.[1] ?? null
}

/** What being attached at v[to] does to output, when the show is behind its venue; null when it is not. */
export function behindLineOf(showName: string, review: Pick<AttachReview, 'behind' | 'runAgainst' | 'to' | 'installed' | 'venueName'>): string | null {
  if (review.behind === 'OUTPUT') {
    return review.runAgainst
      ? `v${review.installed} changes output, so ${showName} is behind ${review.venueName} once attached. It's on the rig, so it carries on, run against v${review.to}, rather than going dark; update it from its home.`
      : `v${review.installed} changes output, so ${showName} is behind ${review.venueName} once attached: it opens held, every channel at zero but a parked one, until it is updated or run against v${review.to}.`
  }
  if (review.behind === 'DRAWING') {
    return `v${review.installed} changes only the drawing: ${showName} attaches at v${review.to} and says it is behind.`
  }
  return null
}
