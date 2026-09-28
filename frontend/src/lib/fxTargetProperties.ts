import type {
  Fixture,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
} from '@/store/fixtures'
import type { EffectLibraryEntry } from '@/store/fixtureFx'

/**
 * The one answer to "what properties does this FX target have, which effects fit them, and which
 * property does a chosen effect actually write".
 *
 * Every surface that offers effects — the add/edit sheet, the busking pad — has to ask the same
 * three questions, and they used to be answered separately in each, with the extra-slider predicate
 * spelled out four times between them. Adding an emitter category was then a two-file edit with
 * nothing tying the halves together.
 *
 * The sentinels are the subtle part and the reason this is worth sharing. The effect library
 * advertises `compatibleProperties` in fixture-agnostic terms, so a `StaticSetting` says it fits
 * `setting` and a `StaticValue` says it fits `slider` — names no fixture descriptor ever carries.
 * A target therefore grows those two names *synthetically* when it has a property that answers to
 * one, and `resolveEffectProperty` turns the sentinel back into the real property name at apply
 * time. Get either half wrong and the effect is either never offered or applied to nothing.
 */

/** The synthetic name a target grows when it has any `setting` property. */
export const SETTING_SENTINEL = 'setting'

/** The synthetic name a target grows when it has an *extra* slider — see `isExtraSliderProperty`. */
export const SLIDER_SENTINEL = 'slider'

/**
 * An FX target with its member fixtures already resolved.
 *
 * Callers hold their own target unions (`FxTarget` in the sheet, `BuskingTarget` on the pad) and
 * their own route to a fixture list, so they narrow to this rather than this module reaching for
 * the store.
 */
export type FxPropertyTarget =
  | { type: 'fixture'; fixture: Fixture }
  | { type: 'group'; capabilities: string[]; members: Fixture[] }

/** The member fixtures of a named group, from a fixture list that may not have arrived yet. */
export function groupMemberFixtures(
  fixtureList: Fixture[] | undefined,
  groupName: string,
): Fixture[] {
  return (fixtureList ?? []).filter((f) => f.groups.includes(groupName))
}

/** The fixtures a target's property descriptors come from. */
export function targetFixtures(target: FxPropertyTarget): Fixture[] {
  return target.type === 'fixture' ? [target.fixture] : target.members
}

/** A `setting` descriptor — a channel with named positions rather than a level. */
export function isSettingProperty(p: { type: string }): boolean {
  return p.type === SETTING_SENTINEL
}

/**
 * A slider the FX surfaces treat as an *extra* property.
 *
 * Dimmer and UV are excluded because they are addressed by name everywhere — the library's dimmer
 * effects declare `dimmer`, the UV ones declare `uv` — so letting them answer to the generic
 * `slider` sentinel too would offer `StaticValue` a second route onto a channel that already has
 * its own.
 */
export function isExtraSliderProperty(p: { type: string; category: string }): boolean {
  return p.type === 'slider' && p.category !== 'dimmer' && p.category !== 'uv'
}

/**
 * Every property name the targets between them expose, plus the two sentinels.
 *
 * A group contributes its own capabilities *and* its members' descriptors: the capability set says
 * what the group can be driven as, while the members say what a per-property effect could land on.
 */
export function propertyNamesFor(targets: FxPropertyTarget[]): Set<string> {
  const names = new Set<string>()
  let hasSetting = false
  let hasExtraSlider = false

  for (const target of targets) {
    if (target.type === 'group') {
      for (const capability of target.capabilities) names.add(capability)
    }
    for (const fixture of targetFixtures(target)) {
      fixture.properties?.forEach((p) => names.add(p.name))
      fixture.elementGroupProperties?.forEach((p) => names.add(p.name))
      if (fixture.properties?.some(isSettingProperty)) hasSetting = true
      if (fixture.properties?.some(isExtraSliderProperty)) hasExtraSlider = true
    }
  }

  if (hasSetting) names.add(SETTING_SENTINEL)
  if (hasExtraSlider) names.add(SLIDER_SENTINEL)
  return names
}

/** The library entries at least one of whose declared properties the targets can supply. */
export function compatibleEffectsFor(
  library: EffectLibraryEntry[] | undefined,
  propertyNames: Set<string>,
): EffectLibraryEntry[] {
  if (!library) return []
  return library.filter((effect) =>
    effect.compatibleProperties.some((propName) => propertyNames.has(propName)),
  )
}

/**
 * Effects grouped by category, in library order within each.
 *
 * `exclude` is for the pad, which renders the `controls` category as property buttons instead and
 * must not also offer it as a list of effects.
 */
export function effectsByCategory(
  effects: EffectLibraryEntry[],
  options?: { exclude?: readonly string[] },
): Record<string, EffectLibraryEntry[]> {
  const grouped: Record<string, EffectLibraryEntry[]> = {}
  for (const effect of effects) {
    if (options?.exclude?.includes(effect.category)) continue
    if (!grouped[effect.category]) grouped[effect.category] = []
    grouped[effect.category].push(effect)
  }
  return grouped
}

/** The distinct `setting` descriptors across the targets, first occurrence winning. */
export function settingPropertiesFor(targets: FxPropertyTarget[]): SettingPropertyDescriptor[] {
  return distinctByName(targets, isSettingProperty) as SettingPropertyDescriptor[]
}

/** The distinct extra-slider descriptors across the targets, first occurrence winning. */
export function extraSliderPropertiesFor(targets: FxPropertyTarget[]): SliderPropertyDescriptor[] {
  return distinctByName(targets, isExtraSliderProperty) as SliderPropertyDescriptor[]
}

function distinctByName(
  targets: FxPropertyTarget[],
  predicate: (p: { type: string; category: string }) => boolean,
) {
  const seen = new Set<string>()
  const result = []
  for (const target of targets) {
    for (const fixture of targetFixtures(target)) {
      for (const p of fixture.properties ?? []) {
        if (predicate(p) && !seen.has(p.name)) {
          seen.add(p.name)
          result.push(p)
        }
      }
    }
  }
  return result
}

/**
 * The property an effect would write on one target, or null if it fits nothing there.
 *
 * A sentinel match resolves to a real descriptor: `preferred` is the operator's explicit pick where
 * a surface offers one (the sheet's setting/slider dropdowns), honoured only while it still names a
 * property this target has, and otherwise the first such property.
 */
export function resolveEffectProperty(
  target: FxPropertyTarget,
  effect: EffectLibraryEntry,
  preferred?: { setting?: string | null; slider?: string | null },
): string | null {
  const names = propertyNamesFor([target])
  const matched = effect.compatibleProperties.find((name) => names.has(name)) ?? null

  if (matched === SETTING_SENTINEL) {
    return pickPreferred(settingPropertiesFor([target]), preferred?.setting)
  }
  if (matched === SLIDER_SENTINEL) {
    return pickPreferred(extraSliderPropertiesFor([target]), preferred?.slider)
  }
  return matched
}

function pickPreferred(
  properties: Array<{ name: string }>,
  preferred: string | null | undefined,
): string | null {
  if (preferred && properties.some((p) => p.name === preferred)) return preferred
  return properties[0]?.name ?? null
}
