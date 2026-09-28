import { EFFECT_CATEGORY_INFO } from '@/components/fx/fxConstants'

/**
 * The client half of the speed-master routing/follow model — the mirror of lighting7's
 * `models/speedMasters.kt`, in the same spirit as `lib/attributeFamily.ts` and
 * `lib/templateIntent.ts`. Pure: no React, no queries.
 *
 * Two facts a master can carry beyond its name and tempo:
 *
 * - a **usage**, the effect-library `category` this master is the apply-time default for. A
 *   busked effect with no explicit master is stamped with the usage-matching master's uuid at
 *   the moment it is created (`resolveSpeedMasterForCategory`); nothing resolves usage later,
 *   and a null `speedMasterUuid` still means master 1 everywhere, forever.
 * - a **follow link** to another master, so that master's clock drives this one: the follower
 *   ticks — and beats — at `num/den` of its leader's rate, in phase with it. The server owns the
 *   arithmetic and the graph (`SpeedMasterBank` drives a follower's clock from its leader's
 *   tick); everything here is display and the picker's cycle guard.
 */

/**
 * The categories a master's usage may name.
 *
 * This is the effect library's own `category` vocabulary, minus the two that cannot sensibly own
 * a tempo: `controls` (a settings slider has no beat) and `composite` (spans families, so no
 * single master is *the* default for it). Effects in either land on master 1, which is what an
 * unmatched category is defined to do. `speedMasterModel.test.ts` pins this against
 * `EFFECT_CATEGORY_INFO`, and lighting7's `SpeedMasterUsageVocabularyTest` pins the server's
 * copy against the shipped `.fx.kts` files — the two ends of one list.
 */
export const SPEED_MASTER_USAGES = ['dimmer', 'colour', 'position'] as const

export type SpeedMasterUsage = (typeof SPEED_MASTER_USAGES)[number]

export function isSpeedMasterUsage(value: string | null | undefined): value is SpeedMasterUsage {
  return value != null && (SPEED_MASTER_USAGES as readonly string[]).includes(value)
}

/**
 * Display label for a usage, borrowed from the effect library's own category labels rather than
 * minted here — the two would otherwise drift, and "Movement" in one place and "Position" in the
 * other describes one thing twice.
 */
export function usageLabel(usage: string | null | undefined): string | null {
  if (usage == null) return null
  return EFFECT_CATEGORY_INFO[usage]?.label ?? usage
}

/** One-line description for the usage select, so the sheet says what routing *does*. */
export function usageOptionLabel(usage: SpeedMasterUsage): string {
  const label = usageLabel(usage) ?? usage
  return usage === 'position' ? `${label} / movement effects` : `${label} effects`
}

/** A follow ratio as the server stores it: two positive integers, never a float. */
export interface FollowRatio {
  num: number
  den: number
}

/**
 * The ratios the UI offers, as int pairs.
 *
 * Ints rather than floats because `1/3 !== 0.333…` on both sides of the wire, and because a pair
 * prints itself. The backend accepts *any* positive pair, so this list is a UI vocabulary and not
 * a constraint — see `FU-SPEED-CUSTOM-RATIO`, and `formatFollowRatio` below, which renders a pair
 * that isn't on this list rather than pretending it can't exist.
 */
export const FOLLOW_RATIOS: readonly (FollowRatio & { label: string })[] = [
  { num: 2, den: 1, label: '2×' },
  { num: 1, den: 1, label: '1×' },
  { num: 1, den: 2, label: '½' },
  { num: 1, den: 3, label: '⅓' },
  { num: 1, den: 4, label: '¼' },
]

/** The chip a fresh link starts on — half time, the ratio the model exists for. */
export const DEFAULT_FOLLOW_RATIO: FollowRatio = { num: 1, den: 2 }

/** Anything carrying the follow columns — a REST row or a live-state entry. */
export interface FollowableMaster {
  followNum?: number | null
  followDen?: number | null
  /** The master being followed; null/absent means master 1. */
  followTargetUuid?: string | null
}

/**
 * Does this master derive its tempo from another one?
 *
 * Both ratio columns or neither, mirroring the server's write-boundary rule; a half-set pair is
 * not a state the server will produce, and treating it as manual is the safe reading. The
 * *target* says nothing about whether a link is live — a manual row may still carry a stale one,
 * so read it through {@link followTargetOf}.
 */
export function isFollowing(master: FollowableMaster | null | undefined): boolean {
  return master?.followNum != null && master?.followDen != null
}

export function followRatioOf(master: FollowableMaster | null | undefined): FollowRatio | null {
  return isFollowing(master) ? { num: master!.followNum!, den: master!.followDen! } : null
}

/** The uuid this master follows, or null for master 1 — and null when it isn't following. */
export function followTargetOf(master: FollowableMaster | null | undefined): string | null {
  return isFollowing(master) ? (master?.followTargetUuid ?? null) : null
}

/** `1/2` -> `½`; a ratio outside {@link FOLLOW_RATIOS} prints as `n/d` rather than vanishing. */
export function formatFollowRatio(num: number, den: number): string {
  return FOLLOW_RATIOS.find((r) => r.num === num && r.den === den)?.label ?? `${num}/${den}`
}

/**
 * "follows M1 · ½" — the one phrasing every read-only surface uses for a linked master.
 *
 * [leaderLabel] is the short form of the master being followed (`M2`, or a name where there is
 * room); it defaults to `M1`, which is what a null follow target means everywhere in this
 * system. {@link leaderLabelOf} builds one from a bank.
 */
export function describeFollow(num: number, den: number, leaderLabel = 'M1'): string {
  return `follows ${leaderLabel} · ${formatFollowRatio(num, den)}`
}

/**
 * Anything the leader lookups need: identity, display index and name.
 *
 * Both index spellings, because both shapes are real and both reach these helpers: a REST row
 * (`SpeedMaster`) calls it `masterIndex`, a live frame (`SpeedMasterLiveState`) calls it `index`.
 * Normalising here beats making every call site spread a renamed copy of the bank.
 */
export interface NamedMaster extends FollowableMaster {
  uuid?: string | null
  index?: number | null
  masterIndex?: number | null
  name?: string
}

/** The display index of either shape. */
export function masterIndexOf(master: NamedMaster): number | null {
  return master.index ?? master.masterIndex ?? null
}

/**
 * The short label for the master [targetUuid] names — `M2` — or `M1` when it names none.
 *
 * Short rather than the full name because it appears inside chips and tiles beside the ratio;
 * the sheet, which has room, shows the name instead.
 */
export function leaderLabelOf(
  masters: readonly NamedMaster[] | undefined,
  targetUuid: string | null | undefined,
): string {
  if (targetUuid == null) return 'M1'
  const leader = masters?.find((m) => m.uuid === targetUuid)
  const index = leader == null ? null : masterIndexOf(leader)
  return index == null ? 'M1' : `M${index}`
}

/** The leader's display name, for prose — "Slow Wash" rather than "M2". */
export function leaderNameOf(
  masters: readonly NamedMaster[] | undefined,
  targetUuid: string | null | undefined,
): string {
  const leader =
    targetUuid == null
      ? masters?.find((m) => masterIndexOf(m) === 1)
      : masters?.find((m) => m.uuid === targetUuid)
  return leader?.name?.trim() || (leader ? `Master ${masterIndexOf(leader)}` : 'Master 1')
}

/**
 * The masters [self] may be pointed at: everything except itself and its own descendants.
 *
 * Chains are legal (M3 → M2 → M1); loops are not, and the server refuses one with
 * `SPEED_MASTER_FOLLOW_CYCLE`. Offering a choice that can only 400 is worse than not offering
 * it, so the picker filters here rather than letting the operator discover the rule by hitting
 * it. The walk is over followers-of-followers from [self], which is the set that would close a
 * loop; the guard against a malformed bank looping this walk forever is the visited set.
 */
export function eligibleFollowTargets<T extends NamedMaster>(
  masters: readonly T[] | undefined,
  self: NamedMaster | null | undefined,
): T[] {
  if (masters == null || self == null) return []
  const banned = new Set<string>()
  if (self.uuid != null) banned.add(self.uuid)
  let grew = true
  while (grew) {
    grew = false
    for (const m of masters) {
      if (m.uuid == null || banned.has(m.uuid)) continue
      const target = followTargetOf(m)
      // A follower of master 1 stores no target; it can only be a descendant of `self` if
      // `self` *is* master 1, which the sheet never offers a ratio for anyway.
      if (target != null && banned.has(target)) {
        banned.add(m.uuid)
        grew = true
      }
    }
  }
  return masters.filter((m) => m.uuid != null && !banned.has(m.uuid))
}

/**
 * Why a follower's tempo cannot be typed or tapped here, naming the fix.
 *
 * Deliberately the same sentence the server sends back when it refuses such a write
 * (`TempoWriteOutcome.RefusedFollower.describe`), so the tooltip on the affordance and the toast
 * from a stale write say the same thing — [leaderName] included, which is why it defaults to the
 * master a null target means rather than being optional prose.
 */
export function followerTempoLockedReason(
  name: string,
  num: number,
  den: number,
  leaderName = 'Master 1',
): string {
  return `${name} follows ${leaderName} at ${num}/${den} — unlink it in the speed-master sheet to set its tempo`
}

/**
 * What a follower runs at. The server owns this arithmetic; this is for previews and labels.
 *
 * Note it is *not* clamped to the clock's 20..300: a follower has no timer for that range to
 * protect, so 2× of 200 really is 400 and the server reports it as such.
 */
export function derivedBpm(leaderBpm: number, num: number, den: number): number {
  return (leaderBpm * num) / den
}

/** The shape routing needs: a uuid and the usage it claims. */
export interface RoutableMaster {
  uuid?: string | null
  usage?: string | null
}

/**
 * The apply-time routing rule (busking-view plan D1): stamp a busked effect with the master whose
 * usage matches the effect's library category.
 *
 * Returns null — meaning master 1 — when nothing matches, which covers an untagged bank, an
 * effect in `composite`/`controls`, and a category a user's own `.fx.kts` invented. That is the
 * defined behaviour rather than a gap: `null` is master 1 on every wire in this system, and the
 * routing is visible and editable afterwards on the effect itself.
 *
 * Usage is unique per project (the server 409s a second claimant), so "the first match" is "the
 * only match" in practice; taking the first keeps this total even if a stale cache shows two.
 */
export function resolveSpeedMasterForCategory(
  masters: readonly RoutableMaster[] | undefined,
  category: string | null | undefined,
): string | null {
  if (category == null) return null
  return masters?.find((m) => m.usage === category && m.uuid != null)?.uuid ?? null
}

/**
 * The tempo window a **continuous** control maps onto — a busk card held and dragged, as opposed
 * to typed or tapped.
 *
 * Deliberately the same 60..180 as lighting7's `BindingTarget.SpeedMasterBpm`, and for the reason
 * that binding's comment gives rather than by coincidence: a drag spread over the clock's full
 * 20..300 (`MasterClock.MIN_BPM`/`MAX_BPM`) spends most of its travel in tempos nobody plays at,
 * which is what makes trimming one by hand awkward. Across a 288px rail card, this window is about
 * 0.4 BPM a pixel; the whole range would be nearer 1.
 *
 * It is a *control* range, not a limit. The clock still accepts 20..300, and the card's other two
 * gestures still reach there — click the number to type one, or TAP it in. Nothing here is a
 * ceiling on what a master can run at.
 */
export const SLIDE_MIN_BPM = 60
export const SLIDE_MAX_BPM = 180

/** How long the card is held before a press becomes a drag. The busk pads' own hold. */
export const SLIDE_HOLD_MS = 300

/**
 * The floor between tempo writes while a drag is running.
 *
 * A drag **applies as it goes** — the operator is watching the rig and listening to the tempo, and
 * a control that only lands on release turns that into guess-then-check. What this constant buys is
 * the traffic half of that: a `pointermove` fires up to once a frame, and every write is broadcast
 * to every other socket on the desk, so the sends are deduplicated on the rounded BPM and floored
 * at this interval. 50 ms is 20 a second, far below what any drag can perceive and far above what
 * the wire notices. The release always sends the final value regardless.
 */
export const SLIDE_PUSH_MS = 50

/** Where along the control a tempo sits, 0..1 — for the fill behind a card mid-drag. */
export function bpmSlideFraction(bpm: number): number {
  const f = (bpm - SLIDE_MIN_BPM) / (SLIDE_MAX_BPM - SLIDE_MIN_BPM)
  return Math.min(1, Math.max(0, f))
}

/** The tempo at a fraction along the control, rounded to whole BPM. */
export function bpmAtSlideFraction(fraction: number): number {
  const f = Math.min(1, Math.max(0, fraction))
  return Math.round(SLIDE_MIN_BPM + f * (SLIDE_MAX_BPM - SLIDE_MIN_BPM))
}
