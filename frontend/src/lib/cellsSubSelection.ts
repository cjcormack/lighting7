import type { CueTarget } from '@/api/cuesApi'
import type { BuskRigCellMode, BuskRigRow } from '@/api/buskRigApi'
import type { SubselectMode } from '@/api/selectionApi'
import { rowTiles, runsOf } from './buskRig'

/**
 * The client mirror of `DeskSelection.subselect` (lighting7 `state/DeskSelection.kt`, busk-further
 * plan D12) — for an **unlinked** window only.
 *
 * The sub-selection is the desk's rule: the Cells chip on a following window sends
 * `selection.subselect {mode}` and renders the desk's answer, and a MIDI `SelectionCells` button
 * reaches the same code on the desk. A window that has unlinked its selection (`lib/deskFollow.ts`)
 * holds a copy the desk never sees, so its chip has nothing to send — and this module rewrites the
 * copy by the same rule, over the rig document the busk view already fetches
 * (`useBuskRigQuery`, `effectiveRig` for an empty one) and the two lists it already holds. It is
 * the split `deskFollow.ts` already makes, applied to the one write that needs a rule.
 *
 * **Pinned against the server's own fixture.** `cellsSubSelection.test.ts` reads
 * `__fixtures__/subselect.fixture.json`, a copy of lighting7's
 * `src/test/resources/busk/subselect.fixture.json` that `DeskSelectionSubselectTest` pins
 * `DeskSelection.subselect` against — the `templateIntent.test.ts` way of keeping a mirror honest.
 * When that fixture changes, copy it again.
 *
 * Three things this side has to derive that the desk reads off its live registers, each a place
 * the two could disagree and each recorded here so the disagreement is a known one:
 *
 * - **A group's members are the fixtures whose `groups` name it, in fixture-list order.**
 *   `GroupSummary` carries a count and no member list; the desk walks `group.fixtures` in member
 *   order. The two agree wherever a group's member order is the patch order, which is what
 *   `createGroup { addSpread(...) }` produces.
 * - **A cell's parent is found by lookup**, never by parsing the key: the fixture whose `elements`
 *   name it. Element keys are opaque (`rowModel.ts`'s rule).
 * - **Steps are resolved against the fixture list, not the tile's embedded patch**, as the desk
 *   resolves a tile's key against its `Fixtures`: a tile naming nothing the list has is absent.
 *
 * Pure, no React: every function takes the {@link CellsRig} it walks.
 */

/** What this side knows about a fixture: its key, the groups it is in, and its cells in order. */
export interface CellsFixture {
  key: string
  groups: readonly string[]
  /** Omitted or empty for a single-head fixture. */
  elements?: readonly { key: string }[]
}

export interface CellsRig {
  /** The rig's rows — the built ones, or `effectiveRig`'s fallback. Empty is the fallback too. */
  rows: readonly BuskRigRow[]
  /**
   * Every group the desk has, in `GET /groups` order — the fallback's order, and how a group with
   * no patched member is known to exist. A caller with no rig to walk may pass none: a group is
   * also known through the fixtures that name it.
   */
  groups: readonly { name: string }[]
  /** Every fixture, in `GET /fixtures` order. */
  fixtures: readonly CellsFixture[]
}

/** The desk's two granularities (`fx/SpreadOver.kt`). */
export type CellsOver = 'HEADS' | 'CELLS'

/**
 * The chip's words for the nine modes, shared with the MIDI library's chips and `describeTarget`
 * so a button and the chip name one rule the same way. The *Cells* menu holds the seven
 * **filters** and two step buttons beside it hold *Prev* / *Next* (D12 as revised 2026-09-21; the
 * design's face-and-menu split put five on the face and four in the menu, and the mix read as two
 * controls).
 */
export const SUBSELECT_MODE_LABELS: Record<SubselectMode, string> = {
  ALL: 'All',
  ODD: 'Odd',
  EVEN: 'Even',
  NEXT: 'Next',
  PREV: 'Prev',
  FIRST_HALF: '1st half',
  SECOND_HALF: '2nd half',
  INVERT: 'Invert',
  MASTERS: 'Masters only',
}

/**
 * The seven modes that decide **what a selection covers** — every one a rewrite of the same heads
 * to a sub-set (or, for *All*, back to the whole) — in the Cells menu, in the design's order.
 */
export const SUBSELECT_FILTER_MODES: readonly SubselectMode[] = ['ALL', 'ODD', 'EVEN', 'FIRST_HALF', 'SECOND_HALF', 'INVERT', 'MASTERS']

/**
 * The two modes that **move** the selection along the rig rather than filter it. Semantically a
 * different gesture — a step, not a mask — so they are two buttons beside the menu, never items in
 * it, and pressing one does not become the chip's remembered mode.
 */
export const SUBSELECT_STEP_MODES: readonly SubselectMode[] = ['PREV', 'NEXT']

function keyOf(target: CueTarget): string {
  return `${target.type}:${target.key}`
}

function fixtureTarget(key: string): CueTarget {
  return { type: 'fixture', key }
}

function groupTarget(name: string): CueTarget {
  return { type: 'group', key: name }
}

function distinct(targets: readonly CueTarget[]): CueTarget[] {
  const seen = new Set<string>()
  const out: CueTarget[] = []
  for (const target of targets) {
    const key = keyOf(target)
    if (seen.has(key)) continue
    seen.add(key)
    out.push(target)
  }
  return out
}

function sameTargets(a: readonly CueTarget[], b: readonly CueTarget[]): boolean {
  return a.length === b.length && a.every((t, i) => t.type === b[i]!.type && t.key === b[i]!.key)
}

/**
 * The rig as the desk reads it, resolved once per call: `TargetCoverage` and `BuskRigOrder` in one
 * object, because both read the same three lookups.
 */
class RigModel {
  private readonly fixtureByKey = new Map<string, CellsFixture>()
  private readonly parentByCell = new Map<string, string>()
  private readonly membersByGroup = new Map<string, string[]>()

  constructor(private readonly rig: CellsRig) {
    for (const fixture of rig.fixtures) {
      this.fixtureByKey.set(fixture.key, fixture)
      for (const element of fixture.elements ?? []) this.parentByCell.set(element.key, fixture.key)
    }
    // A group is known by the list *or* by a fixture naming it: `selectedCells` is called with no
    // group list at all, and a group is then still expandable through its members.
    for (const group of rig.groups) this.membersByGroup.set(group.name, [])
    for (const fixture of rig.fixtures) {
      for (const name of fixture.groups) {
        let members = this.membersByGroup.get(name)
        if (members == null) {
          members = []
          this.membersByGroup.set(name, members)
        }
        members.push(fixture.key)
      }
    }
  }

  // ─── TargetCoverage ───

  /** [targets] with every group replaced by its members; an unresolvable or empty group stands for itself. */
  expand(targets: readonly CueTarget[]): CueTarget[] {
    return targets.flatMap((target) => {
      if (target.type !== 'group') return [target]
      const members = this.membersByGroup.get(target.key) ?? []
      return members.length === 0 ? [target] : members.map(fixtureTarget)
    })
  }

  /** The cells of a multi-head fixture, as fixture-typed targets; empty for anything else. */
  cells(target: CueTarget): CueTarget[] {
    if (target.type !== 'fixture') return []
    const fixture = this.fixtureByKey.get(target.key)
    return (fixture?.elements ?? []).map((element) => fixtureTarget(element.key))
  }

  /** The parent of a cell, as a fixture-typed target; null for anything that is not a cell. */
  parentOf(target: CueTarget): CueTarget | null {
    if (target.type !== 'fixture') return null
    const parent = this.parentByCell.get(target.key)
    return parent == null ? null : fixtureTarget(parent)
  }

  /** D11's rule: [pressed] is in [held] outright, or its parent is. Never the other way. */
  covers(held: ReadonlySet<string>, pressed: CueTarget): boolean {
    if (held.has(keyOf(pressed))) return true
    const parent = this.parentOf(pressed)
    return parent != null && held.has(keyOf(parent))
  }

  /** `DeskSelection.covers`: every head [target] names is covered by the selection. */
  selectionCovers(selection: readonly CueTarget[], target: CueTarget): boolean {
    if (selection.length === 0) return false
    const selected = new Set(this.expand(selection).map(keyOf))
    return this.expand([target]).every((head) => this.covers(selected, head))
  }

  // ─── BuskRigOrder ───

  private resolveFixture(key: string): CellsFixture | null {
    return this.fixtureByKey.get(key) ?? null
  }

  private groupExists(name: string): boolean {
    return this.membersByGroup.has(name)
  }

  private fixtureSteps(
    fixture: CellsFixture,
    over: CellsOver,
    mode: BuskRigCellMode,
    split: number | null | undefined,
  ): CueTarget[][] {
    const cells = this.cells(fixtureTarget(fixture.key))
    if (cells.length === 0) return [[fixtureTarget(fixture.key)]]
    if (over === 'CELLS') return cells.map((cell) => [cell])
    switch (mode) {
      case 'PER_CELL':
        return cells.map((cell) => [cell])
      case 'HALVES':
        return runsOf(cells, split ?? 2)
      default:
        return [[fixtureTarget(fixture.key)]]
    }
  }

  /** A group tile's steps: the group over heads, its members' units over cells; nothing for an unknown group. */
  private groupSteps(name: string, over: CellsOver): CueTarget[][] {
    if (!this.groupExists(name)) return []
    if (over === 'HEADS') return [[groupTarget(name)]]
    const members = this.membersByGroup.get(name) ?? []
    return members.flatMap((key) => {
      const fixture = this.resolveFixture(key)
      return fixture == null ? [] : this.fixtureSteps(fixture, over, 'PIPS', null)
    })
  }

  /**
   * The steps of the band at [over]'s granularity — `BuskRigOrder.steps`. A step already produced
   * is not repeated (a `LinkedHashSet` over the step's targets on the desk; a signature here).
   */
  steps(over: CellsOver): CueTarget[][] {
    const out: CueTarget[][] = []
    const seen = new Set<string>()
    const add = (steps: CueTarget[][]) => {
      for (const step of steps) {
        const signature = JSON.stringify(step)
        if (seen.has(signature)) continue
        seen.add(signature)
        out.push(step)
      }
    }
    if (this.rig.rows.length === 0) {
      for (const group of this.rig.groups) add(this.groupSteps(group.name, over))
      for (const fixture of this.rig.fixtures) add(this.fixtureSteps(fixture, over, 'PIPS', null))
      return out
    }
    for (const row of this.rig.rows) {
      for (const tile of rowTiles(row)) {
        if (tile.kind === 'GROUP') {
          if (tile.group != null) add(this.groupSteps(tile.group.name, over))
          continue
        }
        const key = tile.patch?.key
        if (key == null) continue
        const fixture = this.resolveFixture(key)
        if (fixture == null) continue
        if (tile.elementKey != null) {
          if (this.parentByCell.get(tile.elementKey) === fixture.key) add([[fixtureTarget(tile.elementKey)]])
        } else {
          add(this.fixtureSteps(fixture, over, tile.cellMode, tile.cellSplit))
        }
      }
    }
    return out
  }

  /**
   * Where every head sits along the rig — `BuskRigOrder.positions`: a fixture at its first
   * appearance, its cells fractionally after it, a cell tile at its own place. A head the rig does
   * not reach has no entry.
   */
  positions(): Map<string, number> {
    const out = new Map<string, number>()
    let rank = 0
    const place = (fixture: CellsFixture) => {
      const key = keyOf(fixtureTarget(fixture.key))
      if (out.has(key)) return
      const at = rank++
      out.set(key, at)
      const cells = this.cells(fixtureTarget(fixture.key))
      cells.forEach((cell, i) => {
        const cellKey = keyOf(cell)
        if (!out.has(cellKey)) out.set(cellKey, at + (i + 1) / (cells.length + 1))
      })
    }
    const placeGroup = (name: string) => {
      for (const key of this.membersByGroup.get(name) ?? []) {
        const fixture = this.resolveFixture(key)
        if (fixture != null) place(fixture)
      }
    }
    if (this.rig.rows.length === 0) {
      for (const group of this.rig.groups) placeGroup(group.name)
      for (const fixture of this.rig.fixtures) place(fixture)
      return out
    }
    for (const row of this.rig.rows) {
      for (const tile of rowTiles(row)) {
        if (tile.kind === 'GROUP') {
          if (tile.group != null) placeGroup(tile.group.name)
          continue
        }
        const key = tile.patch?.key
        if (key == null) continue
        const fixture = this.resolveFixture(key)
        if (fixture == null) continue
        if (tile.elementKey != null) {
          const cellKey = keyOf(fixtureTarget(tile.elementKey))
          if (this.parentByCell.get(tile.elementKey) === fixture.key && !out.has(cellKey)) out.set(cellKey, rank++)
        } else {
          place(fixture)
        }
      }
    }
    return out
  }

  /** [heads] in rig order; unplaced heads last, in the order given. Stable. */
  sort(heads: readonly CueTarget[]): CueTarget[] {
    const positions = this.positions()
    return heads
      .map((head, index) => ({ head, index, at: positions.get(keyOf(head)) ?? Number.MAX_VALUE }))
      .sort((a, b) => a.at - b.at || a.index - b.index)
      .map((entry) => entry.head)
  }

  // ─── DeskSelection.subselect ───

  /** True at cell granularity: any selected head has cells, or is one. */
  isCellular(targets: readonly CueTarget[]): boolean {
    return this.expand(targets).some((head) => this.parentOf(head) != null || this.cells(head).length > 0)
  }

  /** [heads] as units: themselves, or each one's cells when [cellular] and it has any. */
  unitsOf(heads: readonly CueTarget[], cellular: boolean): CueTarget[] {
    if (!cellular) return distinct(heads)
    return distinct(
      heads.flatMap((head) => {
        const cells = this.cells(head)
        return cells.length === 0 ? [head] : cells
      }),
    )
  }

  /** The selection's units in rig order: its heads, or their cells when cellular. */
  units(targets: readonly CueTarget[]): CueTarget[] {
    const heads = distinct(this.expand(targets))
    return this.unitsOf(this.sort(heads), this.isCellular(targets))
  }

  rewrite(targets: readonly CueTarget[], mode: SubselectMode): CueTarget[] {
    switch (mode) {
      case 'ALL':
        return distinct(targets.map((target) => this.parentOf(target) ?? target))
      case 'MASTERS':
        return targets.filter((target) => this.parentOf(target) == null)
      case 'ODD':
      case 'EVEN':
      case 'FIRST_HALF':
      case 'SECOND_HALF': {
        const units = this.units(targets)
        const half = Math.floor((units.length + 1) / 2)
        switch (mode) {
          case 'ODD':
            return units.filter((_, i) => i % 2 === 0)
          case 'EVEN':
            return units.filter((_, i) => i % 2 === 1)
          case 'FIRST_HALF':
            return units.slice(0, half)
          default:
            return units.slice(half)
        }
      }
      case 'INVERT': {
        const cellular = this.isCellular(targets)
        const universe = this.steps(cellular ? 'CELLS' : 'HEADS').flat()
        const selected = new Set(this.expand(targets).map(keyOf))
        return this.unitsOf(this.expand(universe), cellular).filter((unit) => !this.covers(selected, unit))
      }
      case 'NEXT':
      case 'PREV': {
        const cellular = targets.length > 0 && targets.every((target) => this.parentOf(target) != null)
        const steps = this.steps(cellular ? 'CELLS' : 'HEADS')
        if (steps.length === 0) return [...targets]
        // A step is occupied when the selection covers every target on it — and not when a
        // *larger* occupied step already covers it: with a group selected, its member's own tile
        // is lit too, but the selection is one thing and steps as one thing.
        const covered = steps.map((_, i) => i).filter((i) => steps[i]!.every((t) => this.selectionCovers(targets, t)))
        const expanded = new Map(covered.map((i) => [i, new Set(this.expand(steps[i]!).map(keyOf))]))
        const occupied = covered.filter(
          (i) => !covered.some((j) => j !== i && this.stepSubsumes(steps[j]!, expanded.get(j)!, steps[i]!, expanded.get(i)!)),
        )
        const delta = mode === 'NEXT' ? 1 : -1
        const moved =
          occupied.length === 0
            ? [mode === 'NEXT' ? 0 : steps.length - 1]
            : occupied.map((i) => (i + delta + steps.length) % steps.length).sort((a, b) => a - b)
        return distinct(moved.flatMap((i) => steps[i]!))
      }
    }
  }

  /** [outer] covers every target of [inner] and [inner] does not cover all of [outer]. */
  private stepSubsumes(
    outer: readonly CueTarget[],
    outerHeads: ReadonlySet<string>,
    inner: readonly CueTarget[],
    innerHeads: ReadonlySet<string>,
  ): boolean {
    if (!inner.every((t) => this.covers(outerHeads, t))) return false
    return !outer.every((t) => this.covers(innerHeads, t))
  }
}

/**
 * The selection's targets rewritten by [mode], exactly as the desk would rewrite them — the mask is
 * the caller's to keep. Answers the **same array** when nothing would change, which is what lets a
 * caller skip the write the desk would also skip.
 */
export function subselectTargets(targets: readonly CueTarget[], mode: SubselectMode, rig: CellsRig): readonly CueTarget[] {
  const next = new RigModel(rig).rewrite(targets, mode)
  return sameTargets(next, targets) ? targets : next
}

/**
 * The cells a selection reaches, each once: a selected multi-head fixture's cells (through a group
 * too), a selected cell unless its parent is selected as well, and nothing for a head with no cells.
 * What the Spread tab's *Over: Cells* counts, and it is the desk's `SpreadOver.CELLS` unit — a
 * single-head fixture is not a cell and is skipped by name there.
 */
export function selectedCells(targets: readonly CueTarget[], rig: CellsRig): CueTarget[] {
  const model = new RigModel(rig)
  return model.unitsOf(model.expand(targets), true).filter((unit) => model.parentOf(unit) != null)
}

/** The steps of the rig at [over]'s granularity, as the desk walks them. Exported to be pinned. */
export function rigStepsOver(rig: CellsRig, over: CellsOver): CueTarget[][] {
  return new RigModel(rig).steps(over)
}
