import { useMemo } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { familyForCategory } from '@/lib/attributeFamily'
import { useFixtureListQuery } from '@/store/fixtures'
import { useProgrammerRevision } from '@/store/programmer'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * How many of the operator's **own** values fall in each attribute family.
 *
 * A Look has no declared type, so an unmasked record captures whatever is in play — and this is
 * what lets the mask picker say what that is *before* the operator finds out afterwards. Which
 * families are involved is the signal that matters; the magnitude is a hint, since the selection
 * narrows the write and group expansion happens server-side.
 *
 * **Local, not the whole programmer.** A layer already asserting a colour puts its values in the
 * same entry map, so counting everything would promise "8 colours" for a busk that touched one:
 * the operator would record a Look, look at it, and find seven values they never set. `owner`
 * names the winning slot's writer and `layers` is the one the layer stack writes under, so that is
 * the discriminator — the same one Local scope turns on (`isLocalEntry`).
 *
 * The category comes from the fixture list's property descriptors, indexed by **property name
 * across the whole rig** rather than per target. A deliberate simplification: a group entry's
 * `colour` is written through the members' own property so it classifies the same way, and looking
 * a group up properly would mean fetching every group's detail to serve a hint. Where two fixtures
 * disagree on a name's category the first wins, and `familyForCategory`'s catch-all means the
 * answer is always a family, never a blank.
 *
 * **The scan is shared between call sites at one revision.** The loop below walks the whole entry
 * map, and `useProgrammerRevision` ticks on every programmer write — so on the live-busking hot
 * path it runs per value set, per cell commit, per fader tick. A `useMemo` is per *instance*, and
 * the page mounts two independent callers — `ProgrammerScopeBand` on row B and the rail's Local
 * values row (`ProgrammerRail`) — which made that scan run twice per tick for one answer. The
 * module-level cache collapses them: same revision and same property index, same object. It holds
 * exactly one entry, so it cannot grow, and it is keyed on the revision, so it can never serve a
 * stale count.
 */
let cachedCounts: {
  categoryByProperty: Map<string, string>
  revision: number
  counts: Partial<Record<AttributeFamily, number>>
} | null = null

export function useLocalFamilyCounts(): Partial<Record<AttributeFamily, number>> {
  const { data: fixtures } = useFixtureListQuery()
  // The entry map lives outside Redux, so a revision tick is how a component knows to re-read it.
  const revision = useProgrammerRevision()

  const categoryByProperty = useMemo(() => {
    const map = new Map<string, string>()
    for (const fixture of fixtures ?? []) {
      for (const property of fixture.properties) {
        if (!map.has(property.name)) map.set(property.name, property.category)
      }
    }
    return map
  }, [fixtures])

  return useMemo(() => {
    if (
      cachedCounts &&
      cachedCounts.revision === revision &&
      cachedCounts.categoryByProperty === categoryByProperty
    ) {
      return cachedCounts.counts
    }
    const counts: Partial<Record<AttributeFamily, number>> = {}
    for (const entry of lightingApi.programmer.getState().entries.values()) {
      if (entry.owner === 'layers') continue
      const category = categoryByProperty.get(entry.propertyName) ?? entry.propertyName
      const family = familyForCategory(category)
      counts[family] = (counts[family] ?? 0) + 1
    }
    cachedCounts = { categoryByProperty, revision, counts }
    return counts
  }, [categoryByProperty, revision])
}

/** Total local entries — "12 values, yours". */
export function useLocalValueCount(): number {
  const counts = useLocalFamilyCounts()
  return useMemo(() => Object.values(counts).reduce((sum, n) => sum + (n ?? 0), 0), [counts])
}
