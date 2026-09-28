import { useCallback, useMemo, useRef, useSyncExternalStore } from 'react'
import { lightingApi } from '../../api/lightingApi'
import { channelKey } from '../../hooks/usePropertyValues'
import { findDimmerProperty } from '../../store/fixtures'
import type { Fixture } from '../../store/fixtures'

const EMPTY_SET: ReadonlySet<string> = new Set()

/** The dimmer channels that decide whether one fixture counts as lit. */
interface FixtureDimmers {
  fixtureKey: string
  /** Parent master dimmer channel key, when the fixture has one. */
  master?: string
  /** Element-level dimmer channel keys, for multi-head fixtures. */
  heads: string[]
}

/** A master at zero gates the heads dark; a master up over all-zero heads emits nothing either. */
function isLit({ master, heads }: FixtureDimmers, all: ReadonlyMap<string, number>): boolean {
  if (master !== undefined && (all.get(master) ?? 0) <= 0) return false
  return heads.length === 0 || heads.some((key) => (all.get(key) ?? 0) > 0)
}

/**
 * The set of fixture keys currently emitting light — drives the "only lit"
 * filter. A fixture is lit when its master dimmer (if any) is above zero AND
 * at least one head dimmer (if any) is above zero: a master at zero gates the
 * heads dark, and a master up over all-zero heads emits nothing either.
 * Fixtures with no dimmer property at any level never count as lit.
 *
 * One whole-map channel subscription for the entire rig (not per-fixture),
 * notifying React only when set *membership* changes, so fades don't churn
 * the row list. Mount this hook only while the filter is on.
 */
export function useLitFixtureKeys(fixtures: readonly Fixture[]): ReadonlySet<string> {
  const dimmers = useMemo(() => {
    const perFixture: FixtureDimmers[] = []
    const watched = new Set<string>()
    for (const fixture of fixtures) {
      const master = findDimmerProperty(fixture.properties)
      const heads: string[] = []
      for (const element of fixture.elements ?? []) {
        const headDimmer = findDimmerProperty(element.properties)
        if (headDimmer) heads.push(channelKey(headDimmer.channel))
      }
      if (!master && heads.length === 0) continue
      const masterKey = master ? channelKey(master.channel) : undefined
      if (masterKey) watched.add(masterKey)
      for (const key of heads) watched.add(key)
      perFixture.push({ fixtureKey: fixture.key, master: masterKey, heads })
    }
    return { perFixture, watched }
  }, [fixtures])

  const cachedRef = useRef<ReadonlySet<string>>(EMPTY_SET)

  const subscribe = useCallback((callback: () => void) => {
    // Passing an empty fixture list is the "filter off" idle state — skip the
    // whole-map subscription entirely.
    if (dimmers.watched.size === 0) return () => undefined
    const subscription = lightingApi.channels.subscribe((updates) => {
      for (const key of updates.keys()) {
        if (dimmers.watched.has(key)) {
          callback()
          return
        }
      }
    })
    return () => subscription.unsubscribe()
  }, [dimmers])

  const getSnapshot = useCallback((): ReadonlySet<string> => {
    const all = lightingApi.channels.getAll()
    const cached = cachedRef.current
    // Membership is checked against the cached set as it is computed, and a Set is allocated
    // only once the two are known to differ. The unchanged case — every read during a fade that
    // moves levels without lighting or darkening anything — then costs no allocation at all,
    // which matters because this runs per 33 ms channel batch *and* per render.
    let litCount = 0
    let diverged = false
    for (const entry of dimmers.perFixture) {
      if (!isLit(entry, all)) continue
      litCount++
      if (!cached.has(entry.fixtureKey)) {
        diverged = true
        break
      }
    }
    // Equal sizes with no key outside the cache means equal sets: the loop counted every lit
    // fixture as a member of `cached`.
    if (!diverged && litCount === cached.size) return cached

    const lit = new Set<string>()
    for (const entry of dimmers.perFixture) {
      if (isLit(entry, all)) lit.add(entry.fixtureKey)
    }
    cachedRef.current = lit
    return lit
  }, [dimmers])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
