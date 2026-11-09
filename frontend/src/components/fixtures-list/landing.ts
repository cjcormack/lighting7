import { useCallback, useMemo, useRef, useState } from 'react'
import type { Fixture } from '../../store/fixtures'
import type { UseListExpansion } from '../../lib/listExpansion'

/**
 * Where a landing — Include's, or a write that selects targets as rows — puts the selection on the
 * programmer's grid (venue-rig-fixes plan D14).
 */
export interface FoldedLanding {
  /** The keys to select: a fixture's own key where all its heads came in. */
  keys: string[]
  /** Fixtures only some of whose heads came in, opened for this landing only. */
  open: string[]
  /** Each of [keys] the rig knows, to the fixture whose row shows it: itself, or its heads' fixture. */
  fixtureOf: ReadonlyMap<string, Fixture>
}

/**
 * Fold the keys a landing selects onto the rows that show them. A fixture with heads, all of which
 * are in [keys] or whose own key is, is answered as its own key, so its row is selected collapsed; a
 * fixture only some of whose heads are in them is answered as those head keys, and is to be opened;
 * a fixture with no heads, and a key the rig does not know, are kept as they are. Order follows each
 * fixture's first key.
 */
export function foldLandingKeys(keys: readonly string[], fixtures: readonly Fixture[]): FoldedLanding {
  const byKey = new Map(fixtures.map((fixture) => [fixture.key, fixture]))
  const parentOf = new Map<string, Fixture>()
  for (const fixture of fixtures) for (const element of fixture.elements ?? []) parentOf.set(element.key, fixture)

  const order: string[] = []
  const own = new Set<string>()
  const heads = new Map<string, Set<string>>()
  const kept = new Set<string>()
  for (const key of keys) {
    const fixture = byKey.get(key)
    const parent = fixture ?? parentOf.get(key)
    if (parent == null) {
      if (!kept.has(key)) order.push(key)
      kept.add(key)
      continue
    }
    if (!own.has(parent.key) && !heads.has(parent.key)) order.push(parent.key)
    if (fixture != null) own.add(key)
    else heads.set(parent.key, (heads.get(parent.key) ?? new Set()).add(key))
  }

  const folded: string[] = []
  const open: string[] = []
  const fixtureOf = new Map<string, Fixture>()
  for (const key of order) {
    const fixture = byKey.get(key)
    const came = heads.get(key)
    if (fixture == null) {
      folded.push(key)
      continue
    }
    if (came == null || own.has(key) || (fixture.elements ?? []).every((element) => came.has(element.key))) {
      folded.push(key)
      fixtureOf.set(key, fixture)
      continue
    }
    folded.push(...came)
    open.push(key)
    for (const head of came) fixtureOf.set(head, fixture)
  }
  return { keys: folded, open, fixtureOf }
}

/** The groups and fixtures a landing opened for itself. */
export interface LandingOpenings {
  groups: ReadonlySet<string>
  fixtures: ReadonlySet<string>
}

type SetUpdate = (prev: ReadonlySet<string>) => ReadonlySet<string>

const NONE: ReadonlySet<string> = new Set()

const union = (stored: ReadonlySet<string>, opened: ReadonlySet<string>): ReadonlySet<string> =>
  opened.size === 0 ? stored : new Set([...stored, ...opened])

/**
 * A list's open rows with the last landing's openings laid over the stored ones (D14): what a
 * landing opens is drawn while this list stays mounted and is never written to `listExpansion`,
 * so the next mount draws the list as the operator left it. Each landing replaces the last one's.
 *
 * The setters are the operator's: an update is applied to what is drawn, a row it closes is closed
 * whichever opened it, and a row it opens is stored. A row open only for the landing, which the
 * update leaves open, stays the landing's unless it is among [opens] — the rows the gesture means to
 * open (Expand all's, a deep link's group), which are stored though the landing had them open.
 */
export function useLandingOpenings(expansion: UseListExpansion, scope: string) {
  const [landing, setLanding] = useState<LandingOpenings & { scope: string }>({ scope, groups: NONE, fixtures: NONE })
  const current = landing.scope === scope ? landing : null
  const openedGroups = current?.groups ?? NONE
  const openedFixtures = current?.fixtures ?? NONE
  const openedRef = useRef({ groups: openedGroups, fixtures: openedFixtures })
  openedRef.current = { groups: openedGroups, fixtures: openedFixtures }

  const expandedGroups = useMemo(() => union(expansion.expandedGroups, openedGroups), [expansion.expandedGroups, openedGroups])
  const expandedFixtures = useMemo(
    () => union(expansion.expandedFixtures, openedFixtures),
    [expansion.expandedFixtures, openedFixtures],
  )

  const { setExpandedGroups: storeGroups, setExpandedFixtures: storeFixtures } = expansion
  const operatorSetter = useCallback(
    (part: keyof LandingOpenings, store: (update: SetUpdate) => void) =>
      (update: SetUpdate, opens?: Iterable<string>) => {
        const opened = openedRef.current[part]
        const promoted = new Set(opens ?? [])
        const drawn: { next: ReadonlySet<string> | null } = { next: null }
        store((prev) => {
          const next = update(union(prev, opened))
          drawn.next = next
          return new Set([...next].filter((name) => prev.has(name) || !opened.has(name) || promoted.has(name)))
        })
        const next = drawn.next
        const stillLanding = (name: string) => next != null && next.has(name) && !promoted.has(name)
        if (next == null || [...opened].every(stillLanding)) return
        setLanding((prev) =>
          prev.scope !== scope ? prev : { ...prev, [part]: new Set([...prev[part]].filter(stillLanding)) },
        )
      },
    [scope],
  )
  const setExpandedGroups = useMemo(() => operatorSetter('groups', storeGroups), [operatorSetter, storeGroups])
  const setExpandedFixtures = useMemo(() => operatorSetter('fixtures', storeFixtures), [operatorSetter, storeFixtures])

  /** This landing's openings, in place of the last one's. */
  const openForLanding = useCallback(
    (opened: { groups?: Iterable<string>; fixtures?: Iterable<string> }) =>
      setLanding({ scope, groups: new Set(opened.groups ?? []), fixtures: new Set(opened.fixtures ?? []) }),
    [scope],
  )

  return { expandedGroups, expandedFixtures, setExpandedGroups, setExpandedFixtures, openForLanding }
}
