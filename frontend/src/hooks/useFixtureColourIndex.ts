import { useMemo } from 'react'
import { usePatchListQuery } from '@/store/patches'
import type { FixturePatch } from '@/api/patchApi'
import { FIXTURE_COLOURS, readColour, type FixtureColour } from '@/lib/fixtureColours'

const EMPTY: ReadonlyMap<string, FixtureColour> = new Map()

const signatures = new WeakMap<readonly FixturePatch[], string>()

/** `key=colour` for every coloured patch, cached per list so a selector run costs a lookup. */
function colourSignature(patches: readonly FixturePatch[] | undefined): string {
  if (patches == null) return ''
  let signature = signatures.get(patches)
  if (signature == null) {
    signature = patches
      .flatMap((p) => {
        const colour = readColour(p.colour)
        return colour == null ? [] : [`${p.key}=${colour}`]
      })
      .join('\n')
    signatures.set(patches, signature)
  }
  return signature
}

function indexOf(signature: string): ReadonlyMap<string, FixtureColour> {
  if (signature === '') return EMPTY
  const index = new Map<string, FixtureColour>()
  for (const line of signature.split('\n')) {
    const at = line.lastIndexOf('=')
    index.set(line.slice(0, at), line.slice(at + 1) as FixtureColour)
  }
  return index
}

/**
 * Each fixture's colour by key, from the cached patch list (tables-colours plan §3: resolved once,
 * never per cell). The index keeps its identity until a colour moves, and the caller re-renders only
 * then — a patch list refetched for an address or a name leaves every table row's props equal.
 */
export function useFixtureColourIndex(projectId: number | null | undefined): ReadonlyMap<string, FixtureColour> {
  const { signature } = usePatchListQuery(projectId ?? 0, {
    skip: projectId == null,
    selectFromResult: ({ data }) => ({ signature: colourSignature(data) }),
  })
  return useMemo(() => indexOf(signature), [signature])
}

/**
 * The fixtures of each colour among [visibleKeys] — the rig as an operator is offered it, never an
 * infrastructure head — in the keys' order, leaving out the colours none of them carries.
 */
export function colourCounts(
  index: ReadonlyMap<string, FixtureColour>,
  visibleKeys: ReadonlySet<string>,
): { colour: FixtureColour; keys: string[] }[] {
  return FIXTURE_COLOURS.map((colour) => ({
    colour,
    keys: [...index].filter(([key, c]) => c === colour && visibleKeys.has(key)).map(([key]) => key),
  })).filter((entry) => entry.keys.length > 0)
}
