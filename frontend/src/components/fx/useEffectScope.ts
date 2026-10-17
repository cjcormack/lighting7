import { useMemo } from 'react'
import { groupMemberFixtures } from '@/lib/fxTargetProperties'
import { selectionFixtures, selectionItems, selectionUnits, spreadsOverMembers, type EffectTargetFields } from '@/lib/effectTargets'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { Fixture, SettingPropertyDescriptor } from '@/store/fixtures'
import { detectExtendedChannels } from './colourUtils'
import { selectionHeadCount, spreadHeadCount, type FxScope } from './fxEditorModel'

/**
 * What an effect's **target** says about its editor (fixture-fx-sheets plan D11): which optional
 * fields `FxEffectFields` draws (`FxScope` — the distribution and step timing over heads, the
 * element mode and filter), how many heads the Spread section's dials run over, the extended
 * channels a colour effect may name, and the setting a setting effect drives. One derivation for the
 * live editor's running effect and the cue editor's stored one, read off the target through
 * `lib/effectTargets.ts` — a selection by its items, never its key.
 */
export function useEffectScope({
  target,
  propertyName,
  entry,
  elementMode,
  elementFilter,
}: {
  target: EffectTargetFields
  propertyName: string | null
  entry: EffectLibraryEntry | undefined
  elementMode: string
  elementFilter: string
}): {
  scope: FxScope
  targetFixtures: Fixture[]
  extendedChannels: ReturnType<typeof detectExtendedChannels>
  settingProperty: SettingPropertyDescriptor | undefined
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
    const multiElement = targetFixtures.some((f) => (f.elements?.length ?? 0) > 1)
    if (overMembers) return { heads: true, elementMode: multiElement, elementFilter: multiElement }
    const heads = targetFixtures.some((f) => (f.elementGroupProperties?.length ?? 0) > 0)
    return { heads, elementMode: false, elementFilter: heads }
  }, [overMembers, targetFixtures])

  const extendedChannels = useMemo(
    () => (entry?.category === 'colour' ? detectExtendedChannels(targetFixtures.map((f) => f.properties ?? [])) : undefined),
    [entry, targetFixtures],
  )
  const settingProperty = useMemo(() => {
    for (const fixture of targetFixtures) {
      const p = fixture.properties?.find((d) => d.name === propertyName && d.type === 'setting')
      if (p) return p as SettingPropertyDescriptor
    }
    return undefined
  }, [targetFixtures, propertyName])

  // The Spread section's dials are drawn over the effect's own heads, as the head filter and element
  // mode the draft now says narrow them.
  const scope = useMemo((): FxScope => {
    const property = propertyName ?? ''
    return {
      ...baseScope,
      headCount:
        items != null
          ? selectionHeadCount(selectionUnits(items, fixtures), property, elementMode, elementFilter)
          : spreadHeadCount(targetFixtures, isGroupTarget, property, elementMode, elementFilter),
    }
  }, [baseScope, items, fixtures, targetFixtures, isGroupTarget, propertyName, elementMode, elementFilter])

  return { scope, targetFixtures, extendedChannels, settingProperty }
}
