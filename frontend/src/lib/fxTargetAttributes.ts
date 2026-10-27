import type {
  Fixture,
  SettingAttributeDescriptor,
  SliderAttributeDescriptor,
} from '@/store/fixtures'
import type { FxLibraryEntry } from '@/store/fixtureFx'

/**
 * The one answer to "what properties does this FX target have, which effects fit them, and which
 * property does a chosen effect actually write".
 *
 * Every surface that offers effects — the FX picker, the busking pad — has to ask the same
 * three questions, and they used to be answered separately in each, with the extra-slider predicate
 * spelled out four times between them. Adding an emitter category was then a two-file edit with
 * nothing tying the halves together.
 *
 * The sentinels are the subtle part and the reason this is worth sharing. The effect library
 * advertises `compatibleAttributes` in fixture-agnostic terms, so a user effect may say it fits
 * `setting` or `slider` — names no fixture descriptor ever carries.
 * A target therefore grows those two names *synthetically* when it has a property that answers to
 * one, and `resolveFxAttribute` turns the sentinel back into the real property name at apply
 * time. Get either half wrong and the effect is either never offered or applied to nothing.
 */

/** The synthetic name a target grows when it has any `setting` property. */
export const SETTING_SENTINEL = 'setting'

/** The synthetic name a target grows when it has an *extra* slider — see `isExtraSliderAttribute`. */
export const SLIDER_SENTINEL = 'slider'

/** Both sentinels — a kind of property, never one; the desk's `FX_ATTRIBUTE_SENTINELS`. */
export const FX_ATTRIBUTE_SENTINELS: ReadonlySet<string> = new Set([SETTING_SENTINEL, SLIDER_SENTINEL])

/**
 * An FX target with its member fixtures already resolved.
 *
 * Callers hold their own target unions (`FxTarget` in the picker, `BuskingTarget` on the pad) and
 * their own route to a fixture list, so they narrow to this rather than this module reaching for
 * the store.
 */
export type FxAttributeTarget =
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
export function targetFixtures(target: FxAttributeTarget): Fixture[] {
  return target.type === 'fixture' ? [target.fixture] : target.members
}

/** A `setting` descriptor — a channel with named positions rather than a level. */
export function isSettingAttribute(p: { type: string }): boolean {
  return p.type === SETTING_SENTINEL
}

/**
 * A slider the FX surfaces treat as an *extra* property.
 *
 * Dimmer and UV are excluded because they are addressed by name everywhere — the library's intensity
 * effects declare `dimmer`, the UV ones declare `uv` — so letting them answer to the generic
 * `slider` sentinel too would offer a `slider` effect a second route onto a channel that already has
 * its own.
 */
export function isExtraSliderAttribute(p: { type: string; category: string }): boolean {
  return p.type === 'slider' && p.category !== 'dimmer' && p.category !== 'uv'
}

/**
 * Every property name the targets between them expose, plus the two sentinels.
 *
 * A group contributes its own capabilities *and* its members' descriptors: the capability set says
 * what the group can be driven as, while the members say what a per-property effect could land on.
 */
export function attributesFor(targets: FxAttributeTarget[]): Set<string> {
  const names = new Set<string>()
  let hasSetting = false
  let hasExtraSlider = false

  for (const target of targets) {
    if (target.type === 'group') {
      for (const capability of target.capabilities) names.add(capability)
    }
    for (const fixture of targetFixtures(target)) {
      fixture.attributes?.forEach((p) => names.add(p.name))
      fixture.elementGroupAttributes?.forEach((p) => names.add(p.name))
      if (fixture.attributes?.some(isSettingAttribute)) hasSetting = true
      if (fixture.attributes?.some(isExtraSliderAttribute)) hasExtraSlider = true
    }
  }

  if (hasSetting) names.add(SETTING_SENTINEL)
  if (hasExtraSlider) names.add(SLIDER_SENTINEL)
  return names
}

/**
 * The library entries a picker or listing may offer: all but a legacy FX (fx-settings plan D3), kept
 * only for what is stored. Every lookup by name and every taken id reads the whole library instead.
 */
export function offeredFx(library: readonly FxLibraryEntry[]): FxLibraryEntry[] {
  return library.filter((effect) => !effect.legacy)
}

/** The offered library entries the targets can take: at least one declared property they supply. */
export function compatibleFxFor(
  library: FxLibraryEntry[] | undefined,
  attributes: Set<string>,
): FxLibraryEntry[] {
  if (!library) return []
  return offeredFx(library).filter((effect) => effect.compatibleAttributes.some((propName) => attributes.has(propName)))
}

/**
 * Effects grouped by category, in library order within each.
 *
 * `exclude` is for the pad, which renders the `controls` category as property buttons instead and
 * must not also offer it as a list of effects.
 */
export function fxByFamily(
  effects: FxLibraryEntry[],
  options?: { exclude?: readonly string[] },
): Record<string, FxLibraryEntry[]> {
  const grouped: Record<string, FxLibraryEntry[]> = {}
  for (const effect of effects) {
    if (options?.exclude?.includes(effect.family)) continue
    if (!grouped[effect.family]) grouped[effect.family] = []
    grouped[effect.family].push(effect)
  }
  return grouped
}

/** The distinct `setting` descriptors across the targets, first occurrence winning. */
export function settingAttributesFor(targets: FxAttributeTarget[]): SettingAttributeDescriptor[] {
  return distinctByName(targets, isSettingAttribute) as SettingAttributeDescriptor[]
}

/** The distinct extra-slider descriptors across the targets, first occurrence winning. */
export function extraSliderAttributesFor(targets: FxAttributeTarget[]): SliderAttributeDescriptor[] {
  return distinctByName(targets, isExtraSliderAttribute) as SliderAttributeDescriptor[]
}

function distinctByName(
  targets: FxAttributeTarget[],
  predicate: (p: { type: string; category: string }) => boolean,
) {
  const seen = new Set<string>()
  const result = []
  for (const target of targets) {
    for (const fixture of targetFixtures(target)) {
      for (const p of fixture.attributes ?? []) {
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
 * a surface offers one (the picker's *On* row), honoured only while it still names a
 * property this target has, and otherwise the first such property.
 */
export function resolveFxAttribute(
  target: FxAttributeTarget,
  effect: FxLibraryEntry,
  preferred?: { setting?: string | null; slider?: string | null },
): string | null {
  const names = attributesFor([target])
  const matched = effect.compatibleAttributes.find((name) => names.has(name)) ?? null

  if (matched === SETTING_SENTINEL) {
    return pickPreferred(settingAttributesFor([target]), preferred?.setting)
  }
  if (matched === SLIDER_SENTINEL) {
    return pickPreferred(extraSliderAttributesFor([target]), preferred?.slider)
  }
  return matched
}

function pickPreferred(
  attributes: Array<{ name: string }>,
  preferred: string | null | undefined,
): string | null {
  if (preferred && attributes.some((p) => p.name === preferred)) return preferred
  return attributes[0]?.name ?? null
}
