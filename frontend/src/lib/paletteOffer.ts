import type { PaletteSummary } from '@/api/palettesApi'
import { FAMILY_LABELS, formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'
import { isPaletteSteps, isSlotSteps, parseStepList } from '@/lib/stepFx'
import { paletteAttributeFor } from '@/lib/paletteIntent'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import { listNames } from '@/components/sheet/sheetModel'

/**
 * What the selected heads can take — the offer's half of palette compatibility (fx-templates D6).
 *
 * `families` is the families a palette may be in (null: any), `emitters` the bundled emitters some
 * head has (null: not known, so not filtered), and `attribute` narrows a **Beam** offer to the palettes
 * that set it: a zoom cell is offered the Beam palettes with a zoom row, not every Beam palette.
 */
export interface PaletteFit {
  families: readonly AttributeFamily[] | null
  emitters: readonly string[] | null
  attribute?: string
}

/**
 * What an **effect** palette's fit needs beyond the palette itself, to say which Beam property it
 * drives: the effect library (a beam built-in's one property) and the palette list (Palette Steps'
 * palettes). Without it an effect palette is not narrowed by property.
 */
export interface FxFitContext {
  library?: readonly Pick<FxLibraryEntry, 'name' | 'compatibleAttributes'>[]
  palettes?: readonly PaletteSummary[]
}

/**
 * Does this palette fit the heads? The strip's filter, the bands' and the picker's, written once so
 * they offer one set: the family, every emitter the palette names somewhere in the selection (a
 * **union**, as `targetEmitters` is built — the desk reports a head without it as a skip), and for a
 * Beam property the property the palette sets — a value palette's own rows, an effect palette's
 * driven property ([fxPaletteDrives]) — so a zoom cell is offered *Wide* and a Wave on zoom, a gobo
 * cell the Slot Steps that steps its gobo.
 */
export function paletteFits(template: PaletteSummary, fit: PaletteFit, context: FxFitContext = {}): boolean {
  if (template.family == null) return false
  if (fit.families != null && !fit.families.includes(template.family)) return false
  // `?? []`: a desk mid-upgrade serves rows without it, and absent means "names no emitter".
  if (fit.emitters != null && !(template.requiredEmitters ?? []).every((e) => fit.emitters!.includes(e))) return false
  if (fit.attribute != null && template.family === 'BEAM') {
    const attribute = paletteAttributeFor(fit.attribute)?.attribute ?? fit.attribute
    if (template.kind === 'value') {
      return (template.rows ?? []).some((row) => paletteAttributeFor(row.attribute)?.attribute === attribute)
    }
    const driven = fxPaletteDrives(template, context)
    return driven == null || driven.some((name) => (paletteAttributeFor(name)?.attribute ?? name) === attribute)
  }
  return true
}

/**
 * What a selection is offered, split by its mask: the palettes that fit ([paletteFits]), those the
 * mask keeps, and those it leaves out with the reason ([outsideMaskReason]). The picker's and ⌘K's
 * *Apply a palette…* read this one split, so the two cannot offer different sets.
 */
export function splitByMask(
  palettes: readonly PaletteSummary[],
  mask: readonly AttributeFamily[] | null,
): { inside: PaletteSummary[]; outside: { palette: PaletteSummary; reason: string }[] } {
  const inside: PaletteSummary[] = []
  const outside: { palette: PaletteSummary; reason: string }[] = []
  for (const palette of palettes) {
    const reason = outsideMaskReason(palette.family, mask)
    if (reason == null) inside.push(palette)
    else outside.push({ palette, reason })
  }
  return { inside, outside }
}

/**
 * The properties an effect palette's effect drives, or null where the context cannot say: Slot
 * Steps the setting it names, Palette Steps the properties its palettes' rows set, a library effect
 * the attribute it names, else the properties it is compatible with.
 */
export function fxPaletteDrives(template: PaletteSummary, context: FxFitContext): readonly string[] | null {
  const effect = template.fx
  if (effect == null) return null
  if (isSlotSteps(effect.fxType)) return effect.attribute ? [effect.attribute] : []
  if (isPaletteSteps(effect.fxType)) {
    if (context.palettes == null) return null
    const named = new Set(parseStepList(effect.parameters?.palettes).map((ref) => ref.replace(/^pal:/, '')))
    return context.palettes
      .filter((t) => named.has(t.uuid))
      .flatMap((t) => (t.rows ?? []).map((row) => row.attribute))
  }
  // One that names its attribute drives that one — a Wave in a Beam palette its zoom (fx-settings plan D5).
  if (effect.attribute) return [effect.attribute]
  if (context.library == null) return null
  const entry = context.library.find((e) => fxKey(e.name) === fxKey(effect.fxType))
  return entry?.compatibleAttributes ?? null
}

/** An effect's name as the library matches it: `Zoom Wave` and `ZoomWave` are one. */
const fxKey = (name: string) => name.replace(/[\s_]/g, '').toLowerCase()

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

/** The fixtures a reach is counted over: each one's key, its groups and its cells' keys. */
export interface ReachFixture {
  key: string
  groups: readonly string[]
  elements?: readonly { key: string }[]
}

/**
 * What a press over [targets] reaches, built once per press and rig and asked per palette: whether a
 * palette row's head is pressed — named, its fixture named (a group naming the fixture included), or
 * one of its cells named — the desk's `LayerPressRules` coverage.
 */
export interface PressReach {
  /** False for a press naming nothing, which lands on a palette's own heads. */
  named: boolean
  reaches: (head: string) => boolean
}

export function pressReach(targets: readonly { type: string; key: string }[], fixtures: readonly ReachFixture[]): PressReach {
  const pressed = new Set<string>()
  for (const target of targets) {
    if (target.type === 'group') {
      for (const fixture of fixtures) if (fixture.groups.includes(target.key)) pressed.add(fixture.key)
    } else {
      pressed.add(target.key)
    }
  }
  const parentOf = new Map<string, string>()
  const cellsOf = new Map<string, string[]>()
  for (const fixture of fixtures) {
    const cells = (fixture.elements ?? []).map((element) => element.key)
    cellsOf.set(fixture.key, cells)
    for (const cell of cells) parentOf.set(cell, fixture.key)
  }
  return {
    named: targets.length > 0,
    reaches: (head) => {
      if (pressed.has(head)) return true
      const parent = parentOf.get(head)
      if (parent != null && pressed.has(parent)) return true
      return (cellsOf.get(head) ?? []).some((cell) => pressed.has(cell))
    },
  }
}

/**
 * Which of a **per-fixture** palette's heads a press lands on, and which it leaves out
 * (palette-references plan D21): the chip's *2 of 4* and the hold menu's first line, counted from the
 * palette's rows — the desk's `paletteHeads` and `omittedHeads`. Null for a generic or an effect
 * palette, and for a press naming nothing.
 */
export function paletteReach(
  palette: Pick<PaletteSummary, 'kind' | 'isGeneric' | 'rows'>,
  press: PressReach,
): { reached: string[]; leftOut: string[] } | null {
  if (palette.kind !== 'value' || palette.isGeneric || !press.named) return null
  const rowHeads = [
    ...new Set((palette.rows ?? []).filter((row) => row.targetType === 'fixture').map((row) => row.targetKey)),
  ]
  if (rowHeads.length === 0) return null
  return { reached: rowHeads.filter(press.reaches), leftOut: rowHeads.filter((head) => !press.reaches(head)) }
}

/** The chip's count where a press leaves some of a palette's heads out: *2 of 4*. */
export function reachCount(reach: { reached: readonly string[]; leftOut: readonly string[] } | null): string | null {
  if (reach == null || reach.leftOut.length === 0) return null
  return `${reach.reached.length} of ${reach.reached.length + reach.leftOut.length}`
}

/**
 * The hold menu's first line for a press that leaves heads out (D21): *Put on Spot 2 and Spot 3 —
 * its Spot 1 and Spot 4 are left out*, past four heads a count; *Put on nothing here — …* where it
 * reaches none. Null where it leaves none out.
 */
export function reachVerb(
  reach: { reached: readonly string[]; leftOut: readonly string[] } | null,
  nameOf: (key: string) => string,
): string | null {
  if (reach == null || reach.leftOut.length === 0) return null
  const left = listNames(reach.leftOut.map(nameOf), 'head')
  const are = reach.leftOut.length === 1 ? 'is' : 'are'
  if (reach.reached.length === 0) return `Put on nothing here — its ${left} ${are} not selected`
  return `Put on ${listNames(reach.reached.map(nameOf), 'head')} — its ${left} ${are} left out`
}
