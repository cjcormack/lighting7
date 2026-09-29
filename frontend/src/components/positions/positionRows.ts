import { worldPositionLighting } from '../../lib/stageCoords'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'

/**
 * The Positions sheet's rows (stage-view plan session 1; `Positions.dc.html` is the layout
 * authority): one row per rigging position, **upstage first**, the units on it in rig order as the
 * desk sees them — left to right is −X to +X, the Stage view's default orbit and its Plan and Front
 * sections alike.
 *
 * **Derived on every render, never stored** — the rule `FU-BUSK-RIG-PLOT` set for plot
 * arrangements: a stored layout is a second answer to "where is this unit", and it would go stale
 * the first time someone moved a bar. The answer is the rig: a unit's row is the rigging its
 * placement names, the row's depth is where the rigging hangs, and a unit's place in the row is its
 * world X.
 *
 * Three rules:
 *
 * - **A paired dimmer is one chip per row, with its count** ("Front C ×2"). Its lanterns are
 *   separate placements and may hang on different riggings; each row gets a chip for the lanterns
 *   that are on it. It is one circuit, so one chip selects it everywhere.
 * - **A unit on no rigging is still on the sheet**, in one *Free-standing* row placed at the mean
 *   depth of its units — the rows are positions, and a floor unit has a position, just not a named
 *   one.
 * - **A rigging with nothing on it has no row.** The sheet is where the lights are.
 */

export interface PositionChip {
  /** Unique within the sheet: the patch key and the row it is on. */
  id: string
  patch: FixturePatch
  /** The unit's name with its row's name taken off the front ("LX3 Robe 575 SR" → "Robe 575 SR"). */
  label: string
  /** How many of this fixture's lanterns hang on this row — above 1 for a pair. */
  count: number
  /** The leftmost lantern's world X, the chip's place in the row. */
  x: number
}

export interface PositionRow {
  /** The rigging's uuid, or [FREE_STANDING_ROW] for units on none. */
  id: string
  name: string
  /** The rigging's kind as the desk stores it (`TRUSS`, `PIPE`, …); null for the free-standing row. */
  kind: string | null
  /** Lighting Y in metres: upstage positive, the house negative. The sheet is sorted by it. */
  depthM: number
  chips: PositionChip[]
}

export const FREE_STANDING_ROW = 'free-standing'
export const FREE_STANDING_NAME = 'Free-standing'

export function positionRows(
  patches: readonly FixturePatch[],
  riggings: readonly RiggingDto[],
): PositionRow[] {
  const rigByUuid = new Map(riggings.map((rig) => [rig.uuid, rig]))
  interface Building {
    row: PositionRow
    ys: number[]
    byPatch: Map<string, PositionChip>
  }
  const rows = new Map<string, Building>()

  const place = (patch: FixturePatch, placement: Parameters<typeof worldPositionLighting>[0]) => {
    const at = worldPositionLighting(placement, riggings as RiggingDto[])
    if (at == null) return
    const rig = placement.riggingUuid != null ? rigByUuid.get(placement.riggingUuid) : undefined
    const id = rig?.uuid ?? FREE_STANDING_ROW
    let building = rows.get(id)
    if (building == null) {
      building = {
        row: { id, name: rig?.name ?? FREE_STANDING_NAME, kind: rig?.kind ?? null, depthM: 0, chips: [] },
        ys: [],
        byPatch: new Map(),
      }
      rows.set(id, building)
    }
    building.ys.push(at.y)
    const existing = building.byPatch.get(patch.key)
    if (existing) {
      existing.count++
      existing.x = Math.min(existing.x, at.x)
    } else {
      building.byPatch.set(patch.key, {
        id: `${patch.key}@${id}`,
        patch,
        label: shortLabel(patch.displayName, rig?.name),
        count: 1,
        x: at.x,
      })
    }
  }

  for (const patch of patches) {
    if (patch.stageHidden) continue
    place(patch, patch)
    for (const placement of patch.extraPlacements ?? []) place(patch, placement)
  }

  const out: PositionRow[] = []
  const acrossOf = new Map<string, number>()
  for (const { row, ys, byPatch } of rows.values()) {
    const rig = rigByUuid.get(row.id)
    // A rigging's own depth when it has one — where the bar hangs, whatever angle its units sit
    // at — and the mean of its units' otherwise, which is all a free-standing row has.
    row.depthM = rig?.positionY ?? ys.reduce((a, b) => a + b, 0) / ys.length
    row.chips = [...byPatch.values()].sort((a, b) => a.x - b.x || a.label.localeCompare(b.label))
    acrossOf.set(row.id, rig?.positionX ?? row.chips[0]!.x)
    out.push(row)
  }
  // Upstage first. Two rows at one depth (a pair of wall booms) read left to right as the desk sees
  // them, as the units in a row do, and then by name.
  return out.sort(
    (a, b) => b.depthM - a.depthM || acrossOf.get(a.id)! - acrossOf.get(b.id)! || a.name.localeCompare(b.name),
  )
}

/**
 * Where the stage edge goes: the index of the first row downstage of it (depth below 0), or the
 * row count when every row is on stage. The edge is Y = 0, the downstage edge of the deck.
 */
export function stageEdgeIndex(rows: readonly PositionRow[]): number {
  const i = rows.findIndex((row) => row.depthM < 0)
  return i === -1 ? rows.length : i
}

/**
 * A unit's name without its row's name in front — the row already says it. A name that would be
 * left as a bare number ("LX1 3") keeps the row's name, since "3" alone says nothing.
 */
export function shortLabel(displayName: string, rowName: string | undefined): string {
  if (!rowName) return displayName
  const prefix = `${rowName} `
  if (!displayName.toLowerCase().startsWith(prefix.toLowerCase())) return displayName
  const rest = displayName.slice(prefix.length).trim()
  return rest === '' || /^\d+$/.test(rest) ? displayName : rest
}
