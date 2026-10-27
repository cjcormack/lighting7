import { useMemo } from 'react'
import { groupMemberFixtures } from '@/lib/fxTargetAttributes'
import { selectionFixtures, selectionItems, selectionUnits, spreadsOverMembers, type FxTargetFields } from '@/lib/fxTargets'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { Fixture, SettingAttributeDescriptor } from '@/store/fixtures'
import { detectExtendedChannels } from './colourUtils'
import { carriesFxAttribute, selectionHeadCount, spreadHeadCount, type FxScope } from './fxEditorModel'

/**
 * What an effect's **target** says about its editor (fixture-fx-sheets plan D11): which optional
 * fields `FxFields` draws (`FxScope` — the distribution and step timing over heads, the
 * element mode and filter), how many heads *Across the heads* is drawn on, the extended
 * channels a colour effect may name, and the setting a setting effect drives. One derivation for the
 * live editor's running effect and the cue editor's stored one, read off the target through
 * `lib/fxTargets.ts` — a selection by its items, never its key.
 */
export function useFxScope({
  target,
  attribute,
  entry,
  elementMode,
  elementFilter,
}: {
  target: FxTargetFields
  attribute: string | null
  entry: FxLibraryEntry | undefined
  elementMode: string
  elementFilter: string
}): {
  scope: FxScope
  targetFixtures: Fixture[]
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingAttribute: SettingAttributeDescriptor | undefined
} {
  const { fixtures, fixtureByKey } = useFixtureLookup()
  const selection = selectionItems(target)
  const selectionKey = selection == null ? null : JSON.stringify(selection)
  // Held by content: a selection's items arrive as a fresh array on every read of the effect.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- `selectionKey` is the items' content
  const items = useMemo(() => selection, [selectionKey])
  const { isGroupTarget, targetKey } = target
  const overMembers = spreadsOverMembers(target)

  const targetFixtures = useMemo((): Fixture[] => {
    if (items != null) return selectionFixtures(items, fixtures)
    if (isGroupTarget) return groupMemberFixtures(fixtures, targetKey)
    const fixture = fixtureByKey.get(targetKey)
    return fixture ? [fixture] : []
  }, [items, isGroupTarget, targetKey, fixtures, fixtureByKey])

  const baseScope = useMemo((): FxScope => {
    // Over says what the spread counts on elements, so it is drawn only where the FX lands on them,
    // as the desk's expansion decides: a group or a fixture by its first fixture, a selection by any unit.
    const landsOnElements = (f: Fixture | undefined) =>
      f != null && (f.elements?.length ?? 0) > 0 && (attribute == null || !carriesFxAttribute(f.attributes, attribute))
    const onElements = items != null ? targetFixtures.some(landsOnElements) : landsOnElements(targetFixtures[0])
    const multiElement = targetFixtures.some((f) => (f.elements?.length ?? 0) > 1)
    if (overMembers) return { heads: true, elementMode: multiElement && onElements, elementFilter: multiElement }
    const heads = targetFixtures.some((f) => (f.elementGroupAttributes?.length ?? 0) > 0)
    return { heads, elementMode: heads && onElements, elementFilter: heads }
  }, [overMembers, targetFixtures, attribute, items])

  const extendedChannels = useMemo(
    () => (entry?.family === 'colour' ? detectExtendedChannels(targetFixtures.map((f) => f.attributes ?? [])) : undefined),
    [entry, targetFixtures],
  )
  const settingAttribute = useMemo(() => {
    for (const fixture of targetFixtures) {
      const p = fixture.attributes?.find((d) => d.name === attribute && d.type === 'setting')
      if (p) return p as SettingAttributeDescriptor
    }
    return undefined
  }, [targetFixtures, attribute])

  // Across the heads is drawn over the effect's own heads, as the head filter and element
  // mode the draft now says narrow them.
  const scope = useMemo((): FxScope => {
    const name = attribute ?? ''
    return {
      ...baseScope,
      headCount:
        items != null
          ? selectionHeadCount(selectionUnits(items, fixtures), name, elementMode, elementFilter)
          : spreadHeadCount(targetFixtures, isGroupTarget, name, elementMode, elementFilter),
    }
  }, [baseScope, items, fixtures, targetFixtures, isGroupTarget, attribute, elementMode, elementFilter])

  return { scope, targetFixtures, extendedChannels, settingAttribute }
}
