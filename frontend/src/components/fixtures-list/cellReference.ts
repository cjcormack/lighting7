import type { CellReference } from './useRowOwnership'

/** One head's reference, as any of the grids knows it: the palette's uuid, and its name where known. */
export interface HeadReference {
  head: string
  reference?: { paletteUuid: string; name?: string; unresolved?: string }
}

/**
 * The palette references over a cell's heads, counted by head (palette-references plan D18, D20):
 * the one rule the programmer's grid, a focused Look and the cue editor share. Null where no head's
 * value is one. The name is the first one any head names; the desk's sentence the first unresolved.
 */
export function referenceAcross(perHead: readonly HeadReference[], cellHeads: readonly string[]): CellReference | null {
  const byHead = new Map<string, string | null>()
  let name: string | undefined
  let unresolved: string | undefined
  for (const { head, reference } of perHead) {
    if (reference == null) continue
    name ??= reference.name
    unresolved ??= reference.unresolved
    const seen = byHead.get(head)
    byHead.set(head, seen === undefined || seen === reference.paletteUuid ? reference.paletteUuid : null)
  }
  if (byHead.size === 0) return null
  const uuids = new Set(byHead.values())
  const severalPalettes = uuids.size > 1 || uuids.has(null)
  return {
    paletteUuid: severalPalettes ? undefined : ([...uuids][0] ?? undefined),
    name: severalPalettes ? undefined : name,
    count: byHead.size,
    of: new Set(cellHeads).size,
    severalPalettes,
    unresolved,
  }
}
