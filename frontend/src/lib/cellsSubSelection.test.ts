import { describe, expect, it } from 'vitest'
import type { CueTarget } from '@/api/cuesApi'
import type { BuskRigRow, BuskRigTile } from '@/api/buskRigApi'
import { SUBSELECT_MODES, type SubselectMode } from '@/api/selectionApi'
import fixture from './__fixtures__/subselect.fixture.json'
import { rigStepsOver, selectedCells, subselectTargets, type CellsFixture, type CellsRig } from './cellsSubSelection'

/**
 * The unlinked window's mirror of `DeskSelection.subselect` (busk-further plan D12), pinned against
 * the desk's own test data.
 *
 * `__fixtures__/subselect.fixture.json` is a **copy** of
 * `lighting7/src/test/resources/busk/subselect.fixture.json`, the file `DeskSelectionSubselectTest`
 * reads — the `rigOrder.fixture.json` arrangement, for its reason: a cross-repo import would make
 * this suite depend on a sibling checkout, and the app's tsconfig carries no Node types to read one
 * with. The two files are meant to be byte-identical; when the server's changes, copy it again. Every
 * case is run, so a rule the desk changes fails here the day the copy is refreshed.
 */

// ─── The fixture's shape, as much of it as this side reads ──────────────

interface SubselectFixture {
  fixtures: { key: string; type: string }[]
  groups: { name: string; members: string[] }[]
  rigs: Record<
    string,
    { name: string; tiles: { group?: string; fixture?: string; elementKey?: string; cellMode?: string; cellSplit?: number }[] }[]
  >
  cases: { note: string; rig: string; mode: string; selection: CueTarget[]; expected: CueTarget[] }[]
}

const file = fixture as SubselectFixture

/** The 12-pixel bar's cells, as the fixture type names them — test data, not a parse. */
function cellsOf(key: string, type: string): { key: string }[] {
  return type.includes('pixel') ? Array.from({ length: 12 }, (_, i) => ({ key: `${key}.pixel-${i}` })) : []
}

const fixtures: CellsFixture[] = file.fixtures.map((f) => ({
  key: f.key,
  groups: file.groups.filter((g) => g.members.includes(f.key)).map((g) => g.name),
  elements: cellsOf(f.key, f.type),
}))
const groups = file.groups.map((g) => ({ name: g.name }))

/**
 * A rig as the GET would serve it. The tiles carry their patch as the wire does, but the mirror
 * resolves every key against the fixture list — so an unresolvable tile is kept here and must come
 * out absent, exactly as the desk drops one.
 */
function rowsOf(name: string): BuskRigRow[] {
  return file.rigs[name]!.map((r, i) => ({
    id: i,
    uuid: `r${i}`,
    name: r.name,
    tiles: r.tiles.map((t, j): BuskRigTile => {
      if (t.group != null) {
        return {
          id: j,
          uuid: `t${i}.${j}`,
          kind: 'GROUP',
          group: { name: t.group, memberCount: 0, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] },
          cellMode: 'PIPS',
        }
      }
      const type = file.fixtures.find((f) => f.key === t.fixture)?.type ?? 'gone'
      return {
        id: j,
        uuid: `t${i}.${j}`,
        kind: 'FIXTURE',
        patch: { id: j, key: t.fixture!, name: t.fixture!, elements: cellsOf(t.fixture!, type).map((c) => ({ ...c, name: c.key })) },
        elementKey: t.elementKey ?? null,
        cellMode: (t.cellMode ?? 'PIPS') as BuskRigTile['cellMode'],
        cellSplit: t.cellSplit ?? null,
      }
    }),
  }))
}

function rigOf(name: string): CellsRig {
  return { rows: rowsOf(name), groups, fixtures }
}

const t = (type: 'fixture' | 'group', key: string): CueTarget => ({ type, key })

// ─── The pins ───────────────────────────────────────────────────────────

describe('the mode vocabulary', () => {
  it('is the nine names the desk parses, every one of them exercised by the fixture', () => {
    const inFixture = new Set(file.cases.map((c) => c.mode))
    for (const mode of SUBSELECT_MODES) expect(inFixture.has(mode), `${mode} has a fixture case`).toBe(true)
    for (const mode of inFixture) expect(SUBSELECT_MODES as readonly string[]).toContain(mode)
    expect(SUBSELECT_MODES).toHaveLength(9)
  })
})

describe('subselectTargets mirrors DeskSelection.subselect', () => {
  it('rewrites every fixture case exactly as the desk does', () => {
    expect(file.cases.length).toBeGreaterThan(0)
    for (const c of file.cases) {
      expect(subselectTargets(c.selection, c.mode as SubselectMode, rigOf(c.rig)), c.note).toEqual(c.expected)
    }
  })

  // The session 2 amendments, named so a regression in one reads as that rule rather than as
  // "case 14 failed".
  it('ALL widens every selected cell to its whole fixture and keeps heads and groups as written', () => {
    expect(subselectTargets([t('fixture', 'bar-1.pixel-1'), t('fixture', 'bar-1.pixel-2'), t('fixture', 'hex-1'), t('group', 'front-wash')], 'ALL', rigOf('built'))).toEqual([
      t('fixture', 'bar-1'),
      t('fixture', 'hex-1'),
      t('group', 'front-wash'),
    ])
  })

  it('INVERT takes the rig as its universe at the selection’s granularity', () => {
    // A head selected: heads are the universe, and the rig's own cell steps stay cells.
    const heads = subselectTargets([t('fixture', 'hex-1')], 'INVERT', rigOf('built'))
    expect(heads[0]).toEqual(t('fixture', 'hex-2'))
    expect(heads).toHaveLength(1 + 12 + 12)
    // A bar selected: cells are the universe, and the bar's own cells are covered by their parent.
    const cells = subselectTargets([t('fixture', 'bar-1')], 'INVERT', rigOf('built'))
    expect(cells.slice(0, 2)).toEqual([t('fixture', 'hex-1'), t('fixture', 'hex-2')])
    expect(cells.some((target) => target.key.startsWith('bar-1.'))).toBe(false)
    expect(cells).toHaveLength(2 + 12)
  })

  it('NEXT and PREV step at cell granularity when every selected target is a cell, wrapping', () => {
    expect(subselectTargets([t('fixture', 'bar-1.pixel-0')], 'NEXT', rigOf('groupOnly'))).toEqual([t('fixture', 'bar-1.pixel-1')])
    expect(subselectTargets([t('fixture', 'bar-1.pixel-11')], 'NEXT', rigOf('groupOnly'))).toEqual([t('fixture', 'bar-2.pixel-0')])
    expect(subselectTargets([t('fixture', 'bar-2.pixel-0')], 'PREV', rigOf('groupOnly'))).toEqual([t('fixture', 'bar-1.pixel-11')])
    // A group steps as a group, and the member's own lit tile is subsumed rather than stepped twice.
    expect(subselectTargets([t('group', 'front-wash')], 'NEXT', rigOf('built'))).toEqual([t('fixture', 'hex-2')])
  })

  it('NEXT and PREV from an empty selection land on the first and last step', () => {
    expect(subselectTargets([], 'NEXT', rigOf('built'))).toEqual([t('group', 'front-wash')])
    expect(subselectTargets([], 'PREV', rigOf('empty'))).toEqual([t('fixture', 'bar-2')])
  })

  it('MASTERS drops every cell and keeps the parents and groups that were written', () => {
    expect(subselectTargets([t('fixture', 'bar-1.pixel-1'), t('fixture', 'hex-1'), t('fixture', 'bar-1')], 'MASTERS', rigOf('built'))).toEqual([
      t('fixture', 'hex-1'),
      t('fixture', 'bar-1'),
    ])
  })

  it('answers the same array when nothing would change — the desk’s no-op, so the caller writes nothing', () => {
    const targets = [t('fixture', 'hex-1')]
    expect(subselectTargets(targets, 'MASTERS', rigOf('built'))).toBe(targets)
    expect(subselectTargets(targets, 'ALL', rigOf('built'))).toBe(targets)
  })

  it('resolves a tile against the fixture list, so a tile naming nothing the list has is absent', () => {
    // `built` carries a `gone` fixture tile and a `nope` group tile; neither is a step.
    const steps = rigStepsOver(rigOf('built'), 'HEADS')
    expect(steps.flat().some((target) => target.key === 'gone' || target.key === 'nope')).toBe(false)
    // …and the desk's dedupe: the `bar-1.pixel-3` cell tile is a step of its own over heads (a
    // single-cell step differs from the run holding it), but over cells it repeats the bar's cell.
    expect(steps[steps.length - 1]).toEqual([t('fixture', 'bar-1.pixel-3')])
    const cells = rigStepsOver(rigOf('built'), 'CELLS')
    expect(cells.filter((step) => step.length === 1 && step[0]!.key === 'bar-1.pixel-3')).toHaveLength(1)
  })
})

describe('selectedCells', () => {
  it('counts a selected bar’s cells, a group’s multi-head members’ cells, a loose cell once, and no single head', () => {
    const rig = rigOf('built')
    // `bars` holds two twelve-cell bars; `front-wash` two hexes with no cells.
    expect(selectedCells([t('group', 'bars')], rig)).toHaveLength(24)
    expect(selectedCells([t('group', 'front-wash')], rig)).toEqual([])
    expect(selectedCells([t('fixture', 'bar-1.pixel-4')], rig)).toEqual([t('fixture', 'bar-1.pixel-4')])
    // The parent selected too: its cells once, not once plus the loose cell.
    expect(selectedCells([t('fixture', 'bar-1.pixel-4'), t('fixture', 'bar-1')], rig)).toHaveLength(12)
    expect(selectedCells([t('fixture', 'hex-1'), t('fixture', 'bar-2')], rig)).toHaveLength(12)
  })
})
