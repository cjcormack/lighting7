import type { TemplateSummary } from '@/api/templatesApi'
import { FAMILY_LABELS, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { isPaletteSteps, isSlotSteps, parseStepList } from '@/lib/stepEffects'
import { templatePropertyFor } from '@/lib/templateIntent'
import type { EffectLibraryEntry } from '@/store/fixtureFx'

/**
 * What the selected heads can take — the offer's half of palette compatibility (fx-templates D6).
 *
 * `families` is the families a palette may be in (null: any), `emitters` the bundled emitters some
 * head has (null: not known, so not filtered), and `property` narrows a **Beam** offer to the palettes
 * that set it: a zoom cell is offered the Beam palettes with a zoom row, not every Beam palette.
 */
export interface PaletteFit {
  families: readonly AttributeFamily[] | null
  emitters: readonly string[] | null
  property?: string
}

/**
 * What an **effect** palette's fit needs beyond the palette itself, to say which Beam property it
 * drives: the effect library (a beam built-in's one property) and the palette list (Palette Steps'
 * palettes). Without it an effect palette is not narrowed by property.
 */
export interface EffectFitContext {
  library?: readonly Pick<EffectLibraryEntry, 'name' | 'compatibleProperties'>[]
  templates?: readonly TemplateSummary[]
}

/**
 * Does this palette fit the heads? The strip's filter, the bands' and the picker's, written once so
 * they offer one set: the family, every emitter the palette names somewhere in the selection (a
 * **union**, as `targetEmitters` is built — the desk reports a head without it as a skip), and for a
 * Beam property the property the palette sets — a value palette's own rows, an effect palette's
 * driven property ([effectPaletteDrives]) — so a zoom cell is offered *Wide* and Zoom Wave, a gobo
 * cell the Slot Steps that steps its gobo.
 */
export function paletteFits(template: TemplateSummary, fit: PaletteFit, context: EffectFitContext = {}): boolean {
  if (template.family == null) return false
  if (fit.families != null && !fit.families.includes(template.family)) return false
  // `?? []`: a desk mid-upgrade serves rows without it, and absent means "names no emitter".
  if (fit.emitters != null && !(template.requiredEmitters ?? []).every((e) => fit.emitters!.includes(e))) return false
  if (fit.property != null && template.family === 'BEAM') {
    const property = templatePropertyFor(fit.property)?.propertyName ?? fit.property
    if (template.kind === 'value') {
      return (template.rows ?? []).some((row) => templatePropertyFor(row.propertyName)?.propertyName === property)
    }
    const driven = effectPaletteDrives(template, context)
    return driven == null || driven.some((name) => (templatePropertyFor(name)?.propertyName ?? name) === property)
  }
  return true
}

/**
 * The properties an effect palette's effect drives, or null where the context cannot say: Slot
 * Steps the setting it names, Palette Steps the properties its palettes' rows set, a library effect
 * the properties it is compatible with.
 */
export function effectPaletteDrives(template: TemplateSummary, context: EffectFitContext): readonly string[] | null {
  const effect = template.effect
  if (effect == null) return null
  if (isSlotSteps(effect.effectType)) return effect.propertyName ? [effect.propertyName] : []
  if (isPaletteSteps(effect.effectType)) {
    if (context.templates == null) return null
    const named = new Set(parseStepList(effect.parameters?.palettes).map((ref) => ref.replace(/^pal:/, '')))
    return context.templates
      .filter((t) => named.has(t.uuid))
      .flatMap((t) => (t.rows ?? []).map((row) => row.propertyName))
  }
  if (context.library == null) return null
  const entry = context.library.find((e) => effectKey(e.name) === effectKey(effect.effectType))
  return entry?.compatibleProperties ?? null
}

/** An effect's name as the library matches it: `Zoom Wave` and `ZoomWave` are one. */
const effectKey = (name: string) => name.replace(/[\s_]/g, '').toLowerCase()

/**
 * Why the selection's mask leaves this palette out, or null when it is inside (or there is no mask).
 *
 * An **offer**, never a refusal: the desk tests the mask on the on arm only, so a lit palette outside
 * the mask still comes off when pressed. The words name both families, as the desk's
 * `PALETTE_OUTSIDE_MASK` sentence does.
 */
export function outsideMaskReason(
  family: AttributeFamily | null,
  mask: readonly AttributeFamily[] | null,
): string | null {
  if (family == null || mask == null || mask.length === 0 || mask.includes(family)) return null
  return `${FAMILY_LABELS[family].singular} — the selection is ${formatFamilyList(mask, ' · ')}`
}

/**
 * A Look's reason: it lands masked, so only a Look with no family inside the mask is out. A Look with
 * no family at all (scenery alone) lights nothing for a mask to cut, and lands whatever the mask.
 */
export function lookOutsideMaskReason(
  families: readonly AttributeFamily[],
  mask: readonly AttributeFamily[] | null,
): string | null {
  if (mask == null || mask.length === 0 || families.length === 0 || families.some((f) => mask.includes(f))) return null
  return `${formatFamilyList(families, ' · ')} — the selection is ${formatFamilyList(mask, ' · ')}`
}
