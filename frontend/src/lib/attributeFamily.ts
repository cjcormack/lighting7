import type { PropertyCategory } from '@/store/fixtures'

/**
 * An attribute family — the backend's `PropertyMaskGroup`, and what a Look's `families` are drawn
 * from. It is derived, never declared: a Look spanning colour and position reports both.
 *
 * Called "family" rather than "palette type" because a Look has no type, and because "palette" now
 * means exactly one thing in this codebase — the positional colour list that FX parameters index
 * as `P1`/`P2`.
 */
export type AttributeFamily = 'INTENSITY' | 'POSITION' | 'COLOUR' | 'BEAM'

export const ATTRIBUTE_FAMILIES: readonly AttributeFamily[] = ['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']

/**
 * The template library's family chips, and its dividers under *All* — Intensity · Colour · Position
 * · Beam (library-sheets plan §3.2), the order an operator builds a state in rather than
 * [ATTRIBUTE_FAMILIES]' declaration order. Shared by `/templates` and `TemplatePicker`, which mount
 * the same `PartitionChips`.
 */
export const TEMPLATE_FAMILY_ORDER: readonly AttributeFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM']

/** Operator-facing labels. Plural names the *bank*, which is what the library's filter shows. */
export const FAMILY_LABELS: Record<AttributeFamily, { singular: string; plural: string }> = {
  INTENSITY: { singular: 'Intensity', plural: 'Intensities' },
  POSITION: { singular: 'Position', plural: 'Positions' },
  COLOUR: { singular: 'Colour', plural: 'Colours' },
  BEAM: { singular: 'Beam', plural: 'Beams' },
}

/**
 * A family list as one display string — `Intensity, Colour`.
 *
 * One helper rather than the `.map(f => FAMILY_LABELS[f].singular).join(…)` that had been retyped
 * at four call sites across three files. The point is not the two lines it saves: it is that
 * `FAMILY_LABELS[f].singular` is the *only* casing anyone may show. `LookStack`'s doc comment
 * already records why — one surface rendering `[COLOUR]` beside another rendering `[Colour]` reads
 * as two different vocabularies — and a shared helper is what stops the next caller reaching for
 * the raw enum.
 *
 * The separator is a parameter because the two contexts genuinely differ: prose and badges take
 * the default comma, while the programmer's selection bar takes a middot to match the interpuncts
 * already separating its counts.
 */
export function formatFamilyList(
  families: readonly AttributeFamily[],
  separator = ', ',
): string {
  return families.map((f) => FAMILY_LABELS[f].singular).join(separator)
}

/**
 * Which attribute family a property belongs to, from its category.
 *
 * A direct mirror of the backend's `PropertyCategory.maskGroup()` (`fx/PropertyMask.kt`), which
 * is the only definition that matters — a family *is* a mask group, so a client that classified a
 * property differently would file a Look under an attribute it cannot cover.
 *
 * Keep in step with that function. The two non-obvious arms: `strobe` is INTENSITY (an intensity
 * modulation, HTP like dimmer), and the extra emitters — amber, white, UV — are COLOUR, because
 * they are emitters of the same mixed colour and splitting them out would record half a look.
 *
 * `position` is the synthetic pan/tilt pair, which has no category of its own; it is handled by
 * the `pan`/`tilt` arms via the descriptors it was built from.
 */
const FAMILY_BY_CATEGORY: Record<PropertyCategory, AttributeFamily> = {
  dimmer: 'INTENSITY',
  strobe: 'INTENSITY',
  pan: 'POSITION',
  tilt: 'POSITION',
  pan_fine: 'POSITION',
  tilt_fine: 'POSITION',
  colour: 'COLOUR',
  amber: 'COLOUR',
  white: 'COLOUR',
  uv: 'COLOUR',
  gobo: 'BEAM',
  gobo_rotation: 'BEAM',
  prism: 'BEAM',
  prism_rotation: 'BEAM',
  focus: 'BEAM',
  zoom: 'BEAM',
  iris: 'BEAM',
  frost: 'BEAM',
  led_macro: 'BEAM',
  movement_macro: 'BEAM',
  speed: 'BEAM',
  setting: 'BEAM',
  other: 'BEAM',
}

/**
 * Its caller is `RecordLookSheet`'s per-family counts: a Look has no declared type, so an unmasked
 * record captures whatever the programmer holds, and this is what lets the mask picker say what
 * that is before the operator finds out afterwards.
 *
 * There was a sibling constant here, `FAMILY_COLUMNS`, mapping a family to the sheet columns it
 * covers. It was deleted rather than kept a third time: its two callers went with the value-level
 * reference surfaces they belonged to, and the programmer rewrite that was supposed to want it
 * needed the question the other way round — from an entry's category to a family, which is this.
 */
export function familyForCategory(category: string): AttributeFamily {
  // `position` is the synthetic pan/tilt pair. It has no entry in `PropertyCategory` — the
  // backend answers it before its own category lookup for the same reason — but it does appear
  // as a descriptor's category, so it is answered here rather than falling into BEAM.
  if (category === 'position') return 'POSITION'
  // Loose `string` rather than `PropertyCategory`, because group property descriptors carry an
  // untyped category off the wire. The Record above still forces every known category to be
  // classified; an unrecognised one lands in BEAM, which is where the backend's own catch-all
  // categories (`SETTING`, `OTHER`) go.
  return FAMILY_BY_CATEGORY[category as PropertyCategory] ?? 'BEAM'
}

/**
 * Which family an **effect library `category`** belongs to — `familyForCategory`'s sibling, and a
 * different question from a different vocabulary.
 *
 * A mirror of `familyForEffectCategory` in lighting7's `routes/projectLooks.kt`. Keep the two in
 * step: it is what derives an effect template's family, and a client that classified a category
 * differently would offer the operator a template the write boundary then refuses.
 *
 * **Do not fold this into [familyForCategory].** That one is keyed by `PropertyCategory` — the
 * *property* vocabulary, which has `pan` and `tilt` and no `position` at all — and it is total,
 * answering BEAM for anything it does not recognise. This one is over the effect library's own
 * `category`, and its `null` is load-bearing: `controls` has no tempo and `composite` spans
 * families, so neither can name a family, and saying BEAM for them would be a wrong answer rather
 * than a catch-all. The two vocabularies share two spellings (`dimmer`, `colour`) and nothing else.
 */
export function familyForEffectCategory(category: string): AttributeFamily | null {
  switch (category.toLowerCase()) {
    case 'dimmer':
      return 'INTENSITY'
    case 'colour':
    case 'color':
      return 'COLOUR'
    case 'position':
      return 'POSITION'
    case 'beam':
      return 'BEAM'
    default:
      return null
  }
}

/**
 * The families an **effect template** can be banked under (fx-templates D4).
 *
 * Not all four: BEAM is absent because the effect library has no beam category, so the column would
 * have nothing to put in it. The backend refuses it **by name** rather than by the library happening
 * to ship no beam effect — a script-registered one must not be able to mint a Beam effect template
 * behind the rule — and this list is the client half of that refusal, which is why the sheet shows
 * *Effect* disabled under Beam rather than omitting Beam from the family row.
 *
 * Mirrors `TEMPLATE_EFFECT_FAMILIES` in `routes/projectTemplates.kt`.
 */
export const TEMPLATE_EFFECT_FAMILIES: readonly AttributeFamily[] = ['INTENSITY', 'COLOUR', 'POSITION']

/** True when a template of this family may hold an effect rather than values. */
export function familyCanHoldEffect(family: AttributeFamily): boolean {
  return TEMPLATE_EFFECT_FAMILIES.includes(family)
}

/**
 * The effect-library category an effect template of this family draws its effects from — the
 * inverse of [familyForEffectCategory], and the filter behind "the family is the filter".
 *
 * Null for BEAM, which is [TEMPLATE_EFFECT_FAMILIES]' absence read the other way round.
 */
export function effectCategoryForFamily(family: AttributeFamily): string | null {
  switch (family) {
    case 'INTENSITY':
      return 'dimmer'
    case 'COLOUR':
      return 'colour'
    case 'POSITION':
      return 'position'
    case 'BEAM':
      return null
  }
}

/**
 * How many patched heads an effect template of this family would run on.
 *
 * Deliberately **not** a reuse of `BuskingView`'s `familiesFit`: that is a private closure asking
 * "does the *current selection* satisfy this family", where this asks "how many heads in the patch
 * have it at all" — the same capability strings, a different question, and no selection involved.
 *
 * BEAM counts every fixture, matching `familiesFit`'s deliberate refusal to filter on it: the beam
 * roles are per-model and a capability set does not summarise them.
 */
export function fixturesSupportingFamily(
  fixtures: readonly { capabilities?: string[] }[] | undefined,
  family: AttributeFamily,
): number {
  if (fixtures == null) return 0
  const capability =
    family === 'INTENSITY' ? 'dimmer' : family === 'COLOUR' ? 'colour' : family === 'POSITION' ? 'position' : null
  if (capability == null) return fixtures.length
  return fixtures.filter((fixture) => fixture.capabilities?.includes(capability) === true).length
}

/** URL slug for a family, e.g. `COLOUR` → `colour`. */
export function familySlug(type: AttributeFamily): string {
  return type.toLowerCase()
}

/**
 * Parse a URL slug back to a family, or null when it isn't one.
 *
 * Null means "no family filter", which the library renders as All rather than as a blank page — a
 * hand-edited or stale `?family=` should land somewhere. Deliberately strict: only the exact
 * lowercase slug, so a typo can't be coerced into a plausible-looking family.
 */
export function parseFamilySlug(raw: string | undefined): AttributeFamily | null {
  if (!raw) return null
  return ATTRIBUTE_FAMILIES.find((type) => familySlug(type) === raw) ?? null
}

/**
 * Parse a layer's `propertyMask` — the comma-separated wire form — into families.
 *
 * `null` / `undefined` / empty means **no mask**, i.e. every family, and this returns `[]` for all
 * three. That is the same convention `MaskPicker` uses (an empty selection is "All attributes") and
 * the same one the backend applies, so the three agree without a translation step.
 *
 * Unrecognised names are dropped rather than rejected: the mask is a display and editing input, and
 * a stored value naming a family this client has never heard of should still let the operator edit
 * the ones it does know instead of rendering nothing.
 */
export function parsePropertyMask(mask: string | null | undefined): AttributeFamily[] {
  if (!mask) return []
  const names = mask.split(',').map((part) => part.trim().toUpperCase())
  return ATTRIBUTE_FAMILIES.filter((family) => names.includes(family))
}

/**
 * The wire form for a set of families, or `null` for "no mask".
 *
 * **Both an empty selection and a complete one serialize to `null`**, and that is the load-bearing
 * part. A mask naming all four families composes identically to no mask at all, but it is not the
 * same value: `propertyMask` distinguishes null from a string, so storing `"INTENSITY,POSITION,
 * COLOUR,BEAM"` would make the row render a four-family badge that says nothing and would survive
 * as noise if a fifth family were ever added. Normalising to null keeps "unmasked" one value.
 *
 * Output is in [ATTRIBUTE_FAMILIES] order regardless of selection order, so a mask has one
 * canonical spelling and re-selecting the same families is not a change.
 */
export function serializePropertyMask(families: readonly AttributeFamily[]): string | null {
  const selected = ATTRIBUTE_FAMILIES.filter((family) => families.includes(family))
  if (selected.length === 0 || selected.length === ATTRIBUTE_FAMILIES.length) return null
  return selected.join(',')
}
