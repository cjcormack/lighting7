import { useMemo } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { familyForCategory, type AttributeFamily } from '@/lib/attributeFamily'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useProgrammerRevision } from '@/store/programmer'
import type { RecordSource } from '@/store/programmerOps'

/** The operator's own values that are palette references, by palette, most first. */
export interface LocalReferences {
  total: number
  palettes: { uuid: string; name: string; count: number }[]
}

/** What a Record writes from the programmer: its source, its mask and its scope. */
export interface RecordScope {
  source: RecordSource
  /** The families the write keeps; null is every attribute. */
  families: readonly AttributeFamily[] | null
  /** The targets a scoped write names; null is every one the programmer holds. */
  targetKeys: readonly string[] | null
}

const NONE: LocalReferences = { total: 0, palettes: [] }

/**
 * How many of the operator's **own** values that a Record would write are palette references, by
 * palette — Local's entries (`layers` is a layer's, not yours), within the Record's [scope]. A whole
 * stage record captures the rig's literals, so it keeps none. Families are read as
 * `useLocalFamilyCounts` reads them, from the rig's property categories.
 */
export function useLocalReferences(scope: RecordScope): LocalReferences {
  const revision = useProgrammerRevision()
  const { data: fixtures } = useVisibleFixtureListQuery()
  const categoryByAttribute = useMemo(() => {
    const map = new Map<string, string>()
    for (const fixture of fixtures ?? []) {
      for (const attribute of fixture.attributes) if (!map.has(attribute.name)) map.set(attribute.name, attribute.category)
    }
    return map
  }, [fixtures])
  const { source, families, targetKeys } = scope
  const familiesKey = families?.join() ?? ''
  const targetsKey = targetKeys?.join('\u0000') ?? null
  return useMemo(() => {
    void revision
    if (source === 'STAGE_SNAPSHOT') return NONE
    const keep = familiesKey === '' ? null : new Set(familiesKey.split(','))
    const targets = targetsKey == null ? null : new Set(targetsKey.split('\u0000'))
    const byUuid = new Map<string, { uuid: string; name: string; count: number }>()
    let total = 0
    for (const entry of lightingApi.programmer.getState().entries.values()) {
      if (entry.owner === 'layers' || entry.reference == null) continue
      if (source === 'TOUCHED' && !entry.touched) continue
      if (targets != null && !targets.has(entry.targetKey)) continue
      if (keep != null && !keep.has(familyForCategory(categoryByAttribute.get(entry.attribute) ?? entry.attribute))) continue
      total += 1
      const { paletteUuid, name } = entry.reference
      const seen = byUuid.get(paletteUuid)
      if (seen) seen.count += 1
      else byUuid.set(paletteUuid, { uuid: paletteUuid, name, count: 1 })
    }
    if (total === 0) return NONE
    return { total, palettes: [...byUuid.values()].sort((a, b) => b.count - a.count || a.name.localeCompare(b.name)) }
  }, [revision, source, familiesKey, targetsKey, categoryByAttribute])
}

/** *2 of your values are references to DSR Focus and stay references: …* */
export function referencesSentence(references: LocalReferences, destination: 'cue' | 'look'): string {
  const count = references.total
  const subject = `${count} of your values ${count === 1 ? 'is a reference' : 'are references'}`
  const to =
    references.palettes.length === 1 ? references.palettes[0].name : `${references.palettes.length} palettes`
  const what = destination === 'look' ? 'this Look' : 'this cue'
  const retune = references.palettes.length === 1 ? 'the palette' : 'one of them'
  return `${subject} to ${to} and ${count === 1 ? 'stays a reference' : 'stay references'}: retune ${retune} and ${what} moves with it.`
}
