import type { Row, RowId } from './rowModel'

/**
 * Expand all / Collapse all over a list's rows (programmer-fixes plan D12, D13): pure, so the
 * programmer grid, the plain lists and the cue editor's Values view answer it one way.
 */
export type ExpandLevel = 'all' | 'groups' | 'fixtures'

export interface ShownExpandable {
  /** Group rows the filter shows. */
  groups: readonly string[]
  /** Multi-head fixtures the filter shows: their own rows, and the shown members of every shown group, open or not. */
  fixtures: readonly string[]
}

export function shownExpandable(rows: readonly Row[]): ShownExpandable {
  const groups = new Set<string>()
  const fixtures = new Set<string>()
  for (const row of rows) {
    if (row.kind === 'group') {
      groups.add(row.name)
      for (const member of row.members) if ((member.elements ?? []).length > 0) fixtures.add(member.key)
    } else if (row.kind === 'fixture' && (row.fixture.elements ?? []).length > 0) {
      fixtures.add(row.fixture.key)
    }
  }
  return { groups: [...groups], fixtures: [...fixtures] }
}

/** Is anything [shown] closed, so the button reads *Expand all*? */
export function anyClosed(
  shown: ShownExpandable,
  isGroupOpen: (name: string) => boolean,
  isFixtureOpen: (key: string) => boolean,
): boolean {
  return shown.groups.some((name) => !isGroupOpen(name)) || shown.fixtures.some((key) => !isFixtureOpen(key))
}

/**
 * What a collapse leaves open, so it never empties the selection: a fixture whose element rows
 * hold a selected row or cell, and a group whose member rows, or their element rows, do.
 */
export function heldOpen(rows: readonly Row[], selectedRowIds: ReadonlySet<RowId>): ShownExpandable {
  const groups = new Set<string>()
  const fixtures = new Set<string>()
  if (selectedRowIds.size === 0) return { groups: [], fixtures: [] }
  for (const row of rows) {
    if (!selectedRowIds.has(row.id)) continue
    if (row.kind === 'element') fixtures.add(row.fixture.key)
    if ((row.kind === 'element' || row.kind === 'fixture') && row.parentGroup != null) groups.add(row.parentGroup)
  }
  return { groups: [...groups], fixtures: [...fixtures] }
}

/**
 * The open sets after Expand all (`open`) or Collapse all over [level]: an expand adds every shown
 * one, a collapse removes every shown one [held] does not keep. A set nothing changes is returned
 * as it was.
 */
export function applyExpandAll(
  current: { groups: ReadonlySet<string>; fixtures: ReadonlySet<string> },
  shown: ShownExpandable,
  held: ShownExpandable,
  level: ExpandLevel,
  open: boolean,
): { groups: ReadonlySet<string>; fixtures: ReadonlySet<string> } {
  const move = (set: ReadonlySet<string>, names: readonly string[], keep: readonly string[]): ReadonlySet<string> => {
    const kept = new Set(keep)
    const touched = open ? names.filter((n) => !set.has(n)) : names.filter((n) => set.has(n) && !kept.has(n))
    if (touched.length === 0) return set
    const next = new Set(set)
    for (const name of touched) {
      if (open) next.add(name)
      else next.delete(name)
    }
    return next
  }
  return {
    groups: level === 'fixtures' ? current.groups : move(current.groups, shown.groups, held.groups),
    fixtures: level === 'groups' ? current.fixtures : move(current.fixtures, shown.fixtures, held.fixtures),
  }
}
