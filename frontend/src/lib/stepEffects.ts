import type { TemplateSummary } from '@/api/templatesApi'
import type { SettingPropertyDescriptor } from '@/store/fixtures'
import type { AttributeFamily } from './attributeFamily'
import { templatePropertiesForFamily, templatePropertyFor } from './templateIntent'

/**
 * Palette Steps and Slot Steps on this side (effects plan D8, §3.6, §4.4): which palettes a step
 * list may name, what a list of them steps, and how the two lists are spelled in an effect's
 * parameters. The desk resolves every step per head (`fx/StepList.kt`); this file never resolves.
 */

/** Palette Steps' library name. */
export const PALETTE_STEPS = 'PaletteSteps'

/** Slot Steps' library name. */
export const SLOT_STEPS = 'SlotSteps'

/**
 * The category Palette Steps registers under, which names no family: it is offered, and stored, in
 * the family of the palettes it is given.
 */
export const PALETTE_STEPS_CATEGORY = 'palette'

export function isPaletteSteps(effectType: string | null | undefined): boolean {
  return effectType != null && effectType.replace(/[\s_]/g, '').toLowerCase() === PALETTE_STEPS.toLowerCase()
}

export function isSlotSteps(effectType: string | null | undefined): boolean {
  return effectType != null && effectType.replace(/[\s_]/g, '').toLowerCase() === SLOT_STEPS.toLowerCase()
}

/** The effect category a family is offered under — the desk's `effectCategoryForFamily`. */
export function categoryForFamily(family: AttributeFamily): string {
  switch (family) {
    case 'INTENSITY':
      return 'dimmer'
    case 'COLOUR':
      return 'colour'
    case 'POSITION':
      return 'position'
    case 'BEAM':
      return 'beam'
  }
}

/** A palette a step list may name: generic, holding a value, in a family. */
export function isSteppablePalette(template: TemplateSummary): boolean {
  return template.isGeneric && template.kind === 'value' && template.family != null
}

/** Whether [template]'s rows name [propertyName] — for colour, any colour palette does. */
function palettesNames(template: TemplateSummary, propertyName: string): boolean {
  const property = templatePropertyFor(propertyName)
  if (property?.family === 'COLOUR') return template.family === 'COLOUR'
  return (template.rows ?? []).some((row) => templatePropertyFor(row.propertyName)?.propertyName === property?.propertyName)
}

/**
 * The palettes of [family] a Palette Steps on [propertyName] may step, in library order (by name):
 * steppable, of the family, and naming the property — any colour palette, for colour.
 */
export function stepPalettesFor(
  templates: readonly TemplateSummary[],
  family: AttributeFamily,
  propertyName: string,
): TemplateSummary[] {
  return templates.filter((t) => isSteppablePalette(t) && t.family === family && palettesNames(t, propertyName))
}

/** The palettes a Palette Steps `palettes` parameter names, found in [templates]; a name not found is left out. */
function namedPalettes(parameter: string | undefined, templates: readonly TemplateSummary[]): TemplateSummary[] {
  const byUuid = new Map(templates.map((t) => [t.uuid, t] as const))
  return parseStepList(parameter).flatMap((entry) => {
    const palette = entry.startsWith('tmpl:') ? byUuid.get(entry.slice('tmpl:'.length)) : undefined
    return palette != null ? [palette] : []
  })
}

/**
 * What a running step effect is as a palette's effect (effects plan §3.6) — the family and category
 * it is banked under and the property it names — or why it cannot be one; null for any other effect,
 * whose library category answers. Palette Steps is its palettes' family and names no property (the
 * desk reads it off the palettes); Slot Steps is Beam and names the setting it steps.
 */
export type StepPaletteShape =
  | { family: AttributeFamily; category: string; propertyName: string | null; reason?: undefined }
  | { reason: string }

export function stepEffectPaletteShape(
  effect: { effectType: string; parameters?: Record<string, string>; propertyName?: string | null },
  templates: readonly TemplateSummary[] | undefined,
): StepPaletteShape | null {
  if (isSlotSteps(effect.effectType)) {
    return effect.propertyName
      ? { family: 'BEAM', category: categoryForFamily('BEAM'), propertyName: effect.propertyName }
      : { reason: 'This Slot Steps names no setting, so a palette of it would not know which to step.' }
  }
  if (!isPaletteSteps(effect.effectType)) return null
  if (templates == null) return { reason: 'Reading the palette library…' }
  const palettes = namedPalettes(effect.parameters?.palettes, templates)
  const families = new Set(palettes.map((p) => p.family))
  const family = families.size === 1 ? palettes[0].family : null
  if (family == null || palettes.length < 2) {
    return { reason: 'Its palettes are not two or more of one family any more, so its family cannot be read.' }
  }
  return { family, category: categoryForFamily(family), propertyName: null }
}

/**
 * A family Palette Steps can be offered in on a target (§4.4): two generic value palettes or more
 * naming a property the target has. [preferred] wins where it qualifies — the row the sheet came
 * from — else the family's vocabulary order decides.
 */
export interface PaletteStepsOffer {
  family: AttributeFamily
  propertyName: string
  palettes: TemplateSummary[]
}

export function paletteStepsOffers(
  templates: readonly TemplateSummary[],
  targetHas: (propertyName: string) => boolean,
  preferred?: string | null,
): PaletteStepsOffer[] {
  const offers: PaletteStepsOffer[] = []
  for (const family of ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM'] as const) {
    const candidates = family === 'COLOUR' ? ['rgbColour'] : templatePropertiesForFamily(family).map((p) => p.propertyName)
    const ordered =
      preferred != null && candidates.includes(templatePropertyFor(preferred)?.propertyName ?? '')
        ? [templatePropertyFor(preferred)!.propertyName, ...candidates]
        : candidates
    for (const propertyName of ordered) {
      if (!targetHas(propertyName)) continue
      const palettes = stepPalettesFor(templates, family, propertyName)
      if (palettes.length >= 2) {
        offers.push({ family, propertyName, palettes })
        break
      }
    }
  }
  return offers
}

/** A step list parameter's entries, in order — the palettes' `tmpl:` references, or slot names. */
export function parseStepList(value: string | null | undefined): string[] {
  return (value ?? '')
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

export function serializeStepList(entries: readonly string[]): string {
  return entries.join(',')
}

/** A slot name as the desk compares it (`normaliseSlotName`): lower case, no spaces, underscores or dashes. */
export function normaliseSlotName(name: string): string {
  return name.toLowerCase().replace(/[\s_-]/g, '')
}

/** The option a stored slot name means on [setting], or undefined where it has none. */
export function slotOption(setting: SettingPropertyDescriptor | undefined, name: string) {
  const wanted = normaliseSlotName(name)
  return setting?.options.find((o) => normaliseSlotName(o.name) === wanted || normaliseSlotName(o.displayName) === wanted)
}

/**
 * The rig's settings by name for Slot Steps on a palette (effects plan §3.6): one descriptor per
 * setting name, its options every patched head's of that name in the order first met, a name two
 * models share kept once (the desk matches a slot by name on each head, ignoring case, spaces and
 * underscores) — so a name only some heads have is offered, and one no head has reads as missing.
 */
export function rigSettingUnion(fixtures: readonly { properties?: readonly { type: string; name: string }[] }[]): SettingPropertyDescriptor[] {
  const byName = new Map<string, SettingPropertyDescriptor>()
  for (const fixture of fixtures) {
    for (const p of fixture.properties ?? []) {
      if (p.type !== 'setting') continue
      const setting = p as SettingPropertyDescriptor
      const known = byName.get(setting.name)
      if (known == null) {
        byName.set(setting.name, { ...setting, options: [...setting.options] })
        continue
      }
      const have = new Set(known.options.map((o) => normaliseSlotName(o.name)))
      for (const option of setting.options) {
        if (!have.has(normaliseSlotName(option.name))) {
          known.options.push(option)
          have.add(normaliseSlotName(option.name))
        }
      }
    }
  }
  return [...byName.values()]
}

/**
 * The slots a new Slot Steps starts on, so a tap shows it stepping: the setting's first two options
 * past its first (its open or white slot) where it has three or more.
 */
export function seedSlots(setting: SettingPropertyDescriptor | undefined): string {
  const options = setting?.options ?? []
  const from = options.length >= 3 ? 1 : 0
  return serializeStepList(options.slice(from, from + 2).map((o) => o.name))
}
