import { useMemo } from 'react'
import { useSelector } from 'react-redux'
import type { CueTarget } from '@/api/cuesApi'
import type { GroupSummary } from '@/api/groupsApi'
import type { Fixture } from '@/store/fixtures'
import { useDeskSelection, usePressFamilies } from '@/store/selection'
import { selectRowTargets } from '@/store/selectionSlice'
import { useDeskFollow, useLocalSelection } from '@/lib/deskFollow'
import { fxTargetLabel, selectionFixtures } from '@/lib/fxTargets'
import { headAsFixture } from '@/components/fixtureSheet/sheetPick'
import { useMarquee } from '@/components/fixtures-list/marqueeContext'
import type { PressPair } from '@/hooks/usePress'
import type { FxTarget } from './FxPicker'

/**
 * What `+ Effect` starts on for the selection's rows: one fixture (a cell as itself — the desk takes
 * a head's key as an effect target) or one group alone, or every row as one selection. Null while
 * nothing is selected or the lists have not arrived.
 */
export function fxTargetOf(
  rows: readonly CueTarget[],
  fixtures: readonly Fixture[] | undefined,
  groups: readonly GroupSummary[] | undefined,
): FxTarget | null {
  if (rows.length === 0 || fixtures == null || groups == null) return null
  if (rows.length === 1) {
    const [row] = rows
    if (row.type === 'group') {
      const group = groups.find((g) => g.name === row.key)
      return group ? { type: 'group', group } : null
    }
    const fixture = fixtures.find((f) => f.key === row.key)
    if (fixture) return { type: 'fixture', fixture }
    const head = headAsFixtureOf(fixtures, row.key)
    return head ? { type: 'fixture', fixture: head } : null
  }
  const names = rows.map((t) =>
    t.type === 'group' ? t.key : (fixtures.find((f) => f.key === t.key)?.name ?? headAsFixtureOf(fixtures, t.key)?.name ?? t.key),
  )
  return {
    type: 'selection',
    targets: [...rows],
    fixtures: selectionFixtures(rows, fixtures),
    label: fxTargetLabel({ targetKey: '', isGroupTarget: false, targetType: 'selection', targets: [...rows] }, (t) => names[rows.indexOf(t)] ?? t.key),
  }
}

/** A cell's key as a fixture of its own, as the tray's head pick starts on one (`headAsFixture`). */
function headAsFixtureOf(fixtures: readonly Fixture[], key: string): Fixture | null {
  for (const fixture of fixtures) {
    const element = fixture.elements?.find((e) => e.key === key)
    if (element) return headAsFixture(fixture, { key: element.key, name: element.displayName, attributes: element.attributes })
  }
  return null
}

/**
 * This window's selection, in its order, for an effect to start on: the desk's while the window
 * follows it, else the window's own — the programmer list's rows on the programmer, the busk view's
 * copy anywhere else.
 */
export function useWindowSelectionTargets(onProgrammer: boolean): readonly CueTarget[] {
  const following = useDeskFollow()
  const desk = useDeskSelection()
  const local = useLocalSelection()
  const rows = useSelector((s: Parameters<typeof selectRowTargets>[0]) => selectRowTargets(s, 'programmer'))
  return following ? desk : onProgrammer ? rows : local.targets
}

/**
 * The pair a bare effect press from this window carries — *+ Effect* and ⌘K *Apply FX* alike, so the
 * two doors read one selection (apply-model plan §8 session 6): the targets [useWindowSelectionTargets]
 * gives, a group row kept a group so its effect is one group effect with its distribution, and the
 * mask a press from this window carries — the desk's while following, else the programmer marquee's
 * families or the busk view's own.
 */
export function useWindowFxPair(onProgrammer: boolean): PressPair {
  const targets = useWindowSelectionTargets(onProgrammer)
  const local = useLocalSelection()
  const marquee = useMarquee()
  const families = usePressFamilies(onProgrammer ? (marquee?.press.askedFamilies ?? null) : local.families)
  return useMemo(() => ({ targets, families }), [targets, families])
}
