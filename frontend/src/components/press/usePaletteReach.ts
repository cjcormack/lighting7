import { useCallback, useMemo } from 'react'
import type { PaletteSummary } from '@/api/palettesApi'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { paletteReach, pressReach, reachCount, reachVerb } from '@/lib/paletteOffer'

/**
 * A per-fixture palette's reach over a press's targets (palette-references plan D21), as the chips
 * draw it: the *2 of 4* count and the hold menu's first line, both null where the press leaves none
 * of the palette's heads out. The press's coverage is built once per targets and rig.
 */
export function usePaletteReach(
  targets: readonly { type: string; key: string }[],
): (palette: PaletteSummary) => { count: string | null; label: string | null } {
  const { fixtures } = useFixtureLookup()
  const nameOf = useMemo(() => {
    const names = new Map<string, string>()
    for (const fixture of fixtures ?? []) {
      names.set(fixture.key, fixture.name)
      for (const element of fixture.elements ?? []) names.set(element.key, `${fixture.name} ${element.displayName}`)
    }
    return (key: string) => names.get(key) ?? key
  }, [fixtures])
  const press = useMemo(() => pressReach(targets, fixtures ?? []), [targets, fixtures])
  return useCallback(
    (palette: PaletteSummary) => {
      const reach = paletteReach(palette, press)
      return { count: reachCount(reach), label: reachVerb(reach, nameOf) }
    },
    [press, nameOf],
  )
}
