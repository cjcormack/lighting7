import { lookRowKey } from '@/components/programmer/lookRowKey'
import { parseProgrammerValue } from '@/lib/programmerValue'
import type { CookedRow } from '@/api/cuesApi'
import type { StagedValue } from '@/components/fixtures-list/useRowOwnership'

/**
 * A cue's composed values in the shape the grid's cells read.
 *
 * Pure, and separate from the component that draws it, so the mapping is testable without a query
 * or a DOM. `CueValueGrid` is the only consumer today; it lives apart because "what did this cue
 * compose to?" is a question worth being able to answer in a test.
 */
export interface StaticRowValues {
  /** Values by [lookRowKey], group rows already expanded to their members. */
  rows: ReadonlyMap<string, StagedValue>
  /** Which Look layer won each key, so a row can name it the way the programmer's Output does. */
  layerByKey: ReadonlyMap<string, { layerId?: number | null; name?: string | null }>
  loaded: boolean
}

/**
 * Group rows first, fixture rows second, so a fixture-targeted row overwrites the group one it
 * overlaps — the same specificity the backend applies, and the same order `LookRowStore` uses. The
 * cook resolves per fixture in most cases; a group row survives it where every member agreed, and
 * expanding it here is what puts a value on each member's row.
 *
 * An unparseable value is skipped rather than guessed at. `parseProgrammerValue` returns null for
 * anything outside the canonical grammar, and rendering a confident-looking swatch for a value
 * nobody can read is worse than leaving the cell blank.
 */
export function buildStaticRows(
  rows: readonly CookedRow[] | undefined,
  fixtures: readonly { key: string; groups: string[] }[] | undefined,
  loaded: boolean,
): StaticRowValues {
  const out = new Map<string, StagedValue>()
  const layerByKey = new Map<string, { layerId?: number | null; name?: string | null }>()

  const put = (targetKey: string, row: CookedRow, staged: StagedValue) => {
    const key = lookRowKey(targetKey, row.propertyName)
    out.set(key, staged)
    // Set *or cleared*: a fixture row from the cue's own assignments overriding a group row a
    // layer won must also drop the layer attribution, or the cell would credit a look for a value
    // the cue set itself.
    if (row.layerId != null) {
      layerByKey.set(key, { layerId: row.layerId, name: row.layerSource?.name ?? null })
    }
    else layerByKey.delete(key)
  }

  for (const pass of ['group', 'fixture'] as const) {
    for (const row of rows ?? []) {
      if (row.targetType !== pass) continue
      const staged = parseProgrammerValue(row.value)
      if (!staged) continue
      if (pass === 'fixture') {
        put(row.targetKey, row, staged)
        continue
      }
      for (const fixture of fixtures ?? []) {
        if (fixture.groups.includes(row.targetKey)) put(fixture.key, row, staged)
      }
    }
  }

  return { rows: out, layerByKey, loaded }
}
