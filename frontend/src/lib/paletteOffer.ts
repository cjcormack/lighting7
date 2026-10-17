import type { TemplateSummary } from '@/api/templatesApi'
import { FAMILY_LABELS, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { templatePropertyFor } from '@/lib/templateIntent'

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
 * Does this palette fit the heads? The strip's filter, the band's and the picker's, written once so
 * the three offer one set: the family, every emitter the palette names somewhere in the selection (a
 * **union**, as `targetEmitters` is built — the desk reports a head without it as a skip), and for a
 * Beam property the palette's own rows.
 */
export function paletteFits(template: TemplateSummary, fit: PaletteFit): boolean {
  if (template.family == null) return false
  if (fit.families != null && !fit.families.includes(template.family)) return false
  // `?? []`: a desk mid-upgrade serves rows without it, and absent means "names no emitter".
  if (fit.emitters != null && !(template.requiredEmitters ?? []).every((e) => fit.emitters!.includes(e))) return false
  if (fit.property != null && template.family === 'BEAM' && template.kind === 'value') {
    const property = templatePropertyFor(fit.property)?.propertyName ?? fit.property
    return (template.rows ?? []).some((row) => templatePropertyFor(row.propertyName)?.propertyName === property)
  }
  return true
}

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
