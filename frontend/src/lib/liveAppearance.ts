import { useEffect } from 'react'
import type { FixtureAppearance } from '@/components/fixtures/fixtureAppearance'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import type { BuskRigRow } from '@/api/buskRigApi'
import type { Fixture } from '@/store/fixtures'
import { parseCssRgb } from './colourMath'
import { rowTiles } from './buskRig'

/**
 * **What each head on screen looks like right now**, as a store a click handler can read.
 *
 * `FixtureAppearanceSource` is a render prop — each colour source needs a different hook set, so
 * it cannot be a hook, and it cannot be asked from a click. The Colour tab's *Pick* needs exactly
 * that: read the selection's current colour into the picker, on a press, without writing
 * (busk-further plan §5, session 5). So every leaf that already resolves a head's appearance —
 * the rig tiles', and the Colour tab's own hidden ones for the selection — **reports** it here,
 * and Pick reads the report.
 *
 * Reporters are counted: a head can be on two rig tiles and under the Colour tab at once, and the
 * first to unmount must not take the others' report with it. The value is whichever reporter spoke
 * last, which is fine because every reporter of one head resolves the same dispatch.
 */

const reports = new Map<string, Map<symbol, FixtureAppearance>>()

/** Test seam and page-leave: drop every report. */
export function resetLiveAppearance(): void {
  reports.clear()
}

/** The appearance last reported for a head, or null if nothing on screen resolves it. */
export function readLiveAppearance(fixtureKey: string): FixtureAppearance | null {
  const byReporter = reports.get(fixtureKey)
  if (byReporter == null) return null
  let last: FixtureAppearance | null = null
  for (const appearance of byReporter.values()) last = appearance
  return last
}

/**
 * Report a head's appearance for as long as this component is mounted. Called from inside a
 * `FixtureAppearanceSource` render-prop leaf, which is the only place an appearance exists.
 */
export function useReportLiveAppearance(fixtureKey: string, appearance: FixtureAppearance): void {
  useEffect(() => {
    const id = Symbol(fixtureKey)
    let byReporter = reports.get(fixtureKey)
    if (byReporter == null) {
      byReporter = new Map()
      reports.set(fixtureKey, byReporter)
    }
    byReporter.set(id, appearance)
    return () => {
      const current = reports.get(fixtureKey)
      if (current == null) return
      current.delete(id)
      if (current.size === 0) reports.delete(fixtureKey)
    }
    // A fresh symbol per (key, appearance) pair is the simplest correct thing: the effect re-runs
    // on every appearance change, and the previous report is removed before the next is written.
  }, [fixtureKey, appearance])
}

// ─── Heads, in rig order ────────────────────────────────────────────────────

/** One head the picker reads: a fixture, or one cell of it by index into its element list. */
export interface SelectedHead {
  fixtureKey: string
  /** The cell's index in its parent's `elements`, or null for the whole fixture. */
  cellIndex: number | null
}

/**
 * The fixture keys the rig names, in rig order: each row's tiles left to right, a group tile as
 * its members in fixture-list order, a fixture or cell tile as its patch. The desk's own order
 * (`state/BuskRigOrder.kt`), which `effectiveRig` already produces for an empty rig.
 */
export function rigHeadOrder(rows: readonly BuskRigRow[], fixtures: readonly Fixture[] | undefined): string[] {
  const order: string[] = []
  const seen = new Set<string>()
  const add = (key: string) => {
    if (seen.has(key)) return
    seen.add(key)
    order.push(key)
  }
  for (const row of rows) {
    for (const tile of rowTiles(row)) {
      if (tile.kind === 'GROUP' && tile.group != null) {
        const name = tile.group.name
        for (const fixture of fixtures ?? []) if (fixture.groups.includes(name)) add(fixture.key)
      } else if (tile.patch != null) {
        add(tile.patch.key)
      }
    }
  }
  return order
}

const hasColourDescriptor = (properties: readonly { type: string }[]) => properties.some((p) => p.type === 'colour')

/** A head the colour picker can *write*: its own colour descriptor, or one on any of its cells. */
function hasColour(target: WriteTarget): boolean {
  return hasColourDescriptor(target.properties) || (target.elements ?? []).some((element) => hasColourDescriptor(element.properties))
}

/**
 * The heads a set of write targets names — a fixture as itself, an element as its parent and its
 * position (`WriteTarget.fixtureKey` / `cellIndex`, stamped where a row becomes a target) — sorted
 * into [order] with any head the order does not name after them, in target order.
 *
 * The colour editor's own list (editor-kit plan D12): the busk tab hands it the selection expanded
 * in rig order, the programmer's cell the marquee's column in row order, and both read Pick off
 * the same fold. A group never reaches here — the hosts expand one to its members first, so a
 * member the rig names sorts by its own rank.
 *
 * **Only heads with a colour descriptor.** The appearance dispatch answers for every head — a
 * gelled or colourless one with its gel or the default tungsten, an unmatched patch with the
 * placeholder grey — and none of those is a colour the editor could write back. Reading them would
 * count a dimmer-only par as a head and flag *mixed* against a real RGB head beside it; the write
 * planners skip the same heads for the same reason, so the two agree on what a head is.
 */
export function targetHeads(targets: readonly WriteTarget[], order: readonly string[] = []): SelectedHead[] {
  const heads: SelectedHead[] = []
  const seen = new Set<string>()
  const add = (head: SelectedHead) => {
    const id = `${head.fixtureKey}#${head.cellIndex ?? ''}`
    if (seen.has(id)) return
    seen.add(id)
    heads.push(head)
  }
  for (const target of targets) {
    if (target.fixtureKey != null) {
      if (!hasColourDescriptor(target.properties)) continue
      add({ fixtureKey: target.fixtureKey, cellIndex: target.cellIndex ?? null })
    } else if (hasColour(target)) {
      add({ fixtureKey: target.key, cellIndex: null })
    }
  }
  if (order.length === 0) return heads
  const rank = new Map(order.map((key, index) => [key, index]))
  const unranked = order.length
  return heads
    .map((head, index) => ({ head, key: (rank.get(head.fixtureKey) ?? unranked) * 1e6 + index }))
    .sort((a, b) => a.key - b.key)
    .map((entry) => entry.head)
}

export interface PickedColour {
  /** The first readable head's colour in rig order, as `#rrggbb`. */
  hex: string
  /** True when some readable head disagrees with the first. */
  mixed: boolean
  /** How many of the selection's heads had an appearance to read. */
  read: number
}

function headHex(head: SelectedHead): string | null {
  const appearance = readLiveAppearance(head.fixtureKey)
  if (appearance == null) return null
  const segment = head.cellIndex == null ? undefined : appearance.segments?.[head.cellIndex]
  const rgb = parseCssRgb(segment?.css ?? appearance.color)
  if (rgb == null) return null
  return `#${((rgb.r << 16) | (rgb.g << 8) | rgb.b).toString(16).padStart(6, '0')}`
}

/**
 * The selection's current colour, read off the reports: the first head in rig order wins, and
 * `mixed` says whether the rest agree. Null when no selected head has reported.
 */
export function pickSelectionColour(heads: readonly SelectedHead[]): PickedColour | null {
  let first: string | null = null
  let mixed = false
  let read = 0
  for (const head of heads) {
    const hex = headHex(head)
    if (hex == null) continue
    read += 1
    if (first == null) first = hex
    else if (hex !== first) mixed = true
  }
  return first == null ? null : { hex: first, mixed, read }
}

/**
 * The reporter as a component, for use inside a `FixtureAppearanceSource` render prop: mounts the
 * report, draws nothing. A component rather than a bare hook call because the render prop's
 * argument is a value, not a place hooks can be called.
 */
export function LiveAppearanceReporter({
  fixtureKey,
  appearance,
}: {
  fixtureKey: string
  appearance: FixtureAppearance
}): null {
  useReportLiveAppearance(fixtureKey, appearance)
  return null
}
