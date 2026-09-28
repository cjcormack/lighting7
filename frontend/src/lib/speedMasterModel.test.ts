import { describe, expect, it } from 'vitest'
import {
  FOLLOW_RATIOS,
  SLIDE_MAX_BPM,
  SLIDE_MIN_BPM,
  SPEED_MASTER_USAGES,
  bpmAtSlideFraction,
  bpmSlideFraction,
  derivedBpm,
  describeFollow,
  eligibleFollowTargets,
  followRatioOf,
  followTargetOf,
  leaderLabelOf,
  leaderNameOf,
  formatFollowRatio,
  isFollowing,
  resolveSpeedMasterForCategory,
  usageLabel,
} from './speedMasterModel'
import { EFFECT_CATEGORY_INFO } from '@/components/fx/fxConstants'

describe('usage vocabulary vs the effect library', () => {
  it('names only categories the effect library actually has', () => {
    // Not a coincidence to be kept in step by hand — a master's usage *is* an effect
    // `category`, because routing is the literal comparison `usage === effect.category`. A
    // usage naming a category no effect carries could never match anything, and the operator
    // would be offered a routing rule that silently does nothing.
    for (const usage of SPEED_MASTER_USAGES) {
      expect(EFFECT_CATEGORY_INFO[usage]).toBeDefined()
    }
  })

  it('excludes the two categories that cannot own a tempo', () => {
    // `controls` is settings sliders — a level has no beat. `composite` spans families, so no
    // single master is *the* default for it. Both land on master 1 through a null uuid, which
    // is what an unmatched category is defined to do. lighting7's SpeedMasterUsageVocabularyTest
    // asserts the same two exclusions on the server's copy of this list.
    expect(SPEED_MASTER_USAGES).not.toContain('controls')
    expect(SPEED_MASTER_USAGES).not.toContain('composite')
  })

  it('labels a usage the way the effect library labels the category', () => {
    expect(usageLabel('position')).toBe(EFFECT_CATEGORY_INFO.position.label)
    // A category minted by someone's own .fx.kts has no entry; print it rather than nothing.
    expect(usageLabel('sparkle')).toBe('sparkle')
    expect(usageLabel(null)).toBeNull()
  })
})

describe('follow ratios', () => {
  it('renders the five offered ratios as their glyphs', () => {
    expect(FOLLOW_RATIOS.map((r) => formatFollowRatio(r.num, r.den))).toEqual([
      '2×',
      '1×',
      '½',
      '⅓',
      '¼',
    ])
  })

  it('renders a ratio the chips do not offer rather than hiding it', () => {
    // The backend stores any positive pair (FU-SPEED-CUSTOM-RATIO), so one can arrive from a
    // script or an import. A surface that only knew the five would draw a linked master with
    // no ratio at all.
    expect(formatFollowRatio(3, 4)).toBe('3/4')
  })

  it('treats a half-set pair as manual', () => {
    // The server writes both columns or neither, so this is not a state it produces — but a
    // display that guessed a denominator would invent a tempo.
    expect(isFollowing({ followNum: 1, followDen: 2 })).toBe(true)
    expect(isFollowing({ followNum: 1, followDen: null })).toBe(false)
    expect(isFollowing({})).toBe(false)
    expect(followRatioOf({ followNum: 1, followDen: null })).toBeNull()
    expect(followRatioOf({ followNum: 1, followDen: 3 })).toEqual({ num: 1, den: 3 })
  })

  it('derives a follower tempo exactly at thirds', () => {
    // Ints rather than a stored float is what makes this exact — 120 * (1/3) via a rounded
    // 0.333 would read 39.96.
    expect(derivedBpm(120, 1, 3)).toBeCloseTo(40, 10)
    expect(derivedBpm(128, 1, 2)).toBe(64)
    expect(derivedBpm(90, 2, 1)).toBe(180)
  })

  it('reads a follow target only on a live link', () => {
    // A manual row may still carry a stale target (an unlink writes both ratio columns null;
    // an import writes whatever it was handed). Reading it there would draw a link that isn't.
    expect(followTargetOf({ followNum: 1, followDen: 2, followTargetUuid: 'm2' })).toBe('m2')
    expect(followTargetOf({ followNum: 1, followDen: 2 })).toBeNull()
    expect(followTargetOf({ followTargetUuid: 'm2' })).toBeNull()
  })
})

describe('leader labels', () => {
  const bank = [
    { uuid: 'm1', masterIndex: 1, name: 'House Tempo' },
    { uuid: 'm2', index: 2, name: 'Movement' },
  ]

  it('reads either index spelling, and treats a null target as master 1', () => {
    // A REST row says `masterIndex`, a live frame says `index`; both reach these helpers.
    expect(leaderLabelOf(bank, 'm2')).toBe('M2')
    expect(leaderLabelOf(bank, null)).toBe('M1')
    expect(leaderNameOf(bank, 'm2')).toBe('Movement')
    expect(leaderNameOf(bank, null)).toBe('House Tempo')
  })

  it('falls back to master 1 for a target the bank does not know', () => {
    // A bank mid-refetch, or a master deleted in another tab. "M1" is what a dangling target
    // resolves to on the server too, so the label is not a guess.
    expect(leaderLabelOf(bank, 'gone')).toBe('M1')
    expect(describeFollow(1, 2, leaderLabelOf(bank, 'm2'))).toBe('follows M2 · ½')
  })
})

describe('eligibleFollowTargets', () => {
  // m3 → m2 → m1, and m4 stands alone.
  const bank = [
    { uuid: 'm1', masterIndex: 1, name: 'Master 1' },
    { uuid: 'm2', masterIndex: 2, name: 'Movement', followNum: 1, followDen: 2, followTargetUuid: 'm1' },
    { uuid: 'm3', masterIndex: 3, name: 'Crawl', followNum: 1, followDen: 2, followTargetUuid: 'm2' },
    { uuid: 'm4', masterIndex: 4, name: 'Strobe' },
  ]

  it('offers a follower as a leader — chains are legal', () => {
    expect(eligibleFollowTargets(bank, bank[3]).map((m) => m.uuid)).toEqual(['m1', 'm2', 'm3'])
  })

  it('excludes the master itself and everything descending from it', () => {
    // m2 may not follow m3, because m3 already follows m2 — that is the loop the server
    // refuses with SPEED_MASTER_FOLLOW_CYCLE, and a picker should never offer it.
    expect(eligibleFollowTargets(bank, bank[1]).map((m) => m.uuid)).toEqual(['m1', 'm4'])
  })
})

describe('resolveSpeedMasterForCategory', () => {
  const bank = [
    { uuid: 'm1', usage: null },
    { uuid: 'm2', usage: 'position' },
    { uuid: 'm3', usage: 'colour' },
  ]

  it('routes an effect to the master claiming its category', () => {
    expect(resolveSpeedMasterForCategory(bank, 'position')).toBe('m2')
    expect(resolveSpeedMasterForCategory(bank, 'colour')).toBe('m3')
  })

  it('falls back to master 1 for anything unclaimed', () => {
    // null is master 1 on every wire in this system, so these three are one answer, not three
    // failure modes: an untagged family, a composite effect, and a category a user's own
    // .fx.kts invented.
    expect(resolveSpeedMasterForCategory(bank, 'dimmer')).toBeNull()
    expect(resolveSpeedMasterForCategory(bank, 'composite')).toBeNull()
    expect(resolveSpeedMasterForCategory(bank, 'sparkle')).toBeNull()
    expect(resolveSpeedMasterForCategory(bank, null)).toBeNull()
    expect(resolveSpeedMasterForCategory(undefined, 'position')).toBeNull()
  })

  it('ignores a claimant with no uuid', () => {
    // The pre-load synthetic master 1 has a null uuid; stamping an effect with it would be
    // stamping nothing.
    expect(resolveSpeedMasterForCategory([{ uuid: null, usage: 'dimmer' }], 'dimmer')).toBeNull()
  })
})

/**
 * The window a *continuous* control maps onto, which is narrower than the clock's own 20..300 for
 * the reason lighting7's MIDI tempo binding gives: a drag spread over the whole range spends most
 * of its travel in tempos nobody plays at.
 */
describe('the tempo slide range', () => {
  it('sits inside the clock range and covers the musical middle', () => {
    // MasterClock.MIN_BPM / MAX_BPM in lighting7. A control window outside them could ask the
    // clock for a tempo it would silently clamp.
    expect(SLIDE_MIN_BPM).toBeGreaterThanOrEqual(20)
    expect(SLIDE_MAX_BPM).toBeLessThanOrEqual(300)
    expect(SLIDE_MIN_BPM).toBeLessThan(SLIDE_MAX_BPM)
  })

  it('maps the ends of the travel onto the ends of the window', () => {
    expect(bpmAtSlideFraction(0)).toBe(SLIDE_MIN_BPM)
    expect(bpmAtSlideFraction(1)).toBe(SLIDE_MAX_BPM)
    expect(bpmAtSlideFraction(0.5)).toBe((SLIDE_MIN_BPM + SLIDE_MAX_BPM) / 2)
  })

  it('clamps rather than running past either end', () => {
    // A drag is followed on the window, so the pointer routinely leaves the card it started on.
    expect(bpmAtSlideFraction(-2)).toBe(SLIDE_MIN_BPM)
    expect(bpmAtSlideFraction(4)).toBe(SLIDE_MAX_BPM)
    expect(bpmSlideFraction(10)).toBe(0)
    expect(bpmSlideFraction(600)).toBe(1)
  })

  it('round-trips a tempo through the fraction the fill is drawn from', () => {
    for (const bpm of [SLIDE_MIN_BPM, 90, 120, SLIDE_MAX_BPM]) {
      expect(bpmAtSlideFraction(bpmSlideFraction(bpm))).toBe(bpm)
    }
  })
})
