import { describe, expect, it } from 'vitest'
import {
  ATTRIBUTE_FAMILIES,
  TEMPLATE_EFFECT_FAMILIES,
  effectCategoryForFamily,
  familyCanHoldEffect,
  familyForCategory,
  familyForEffectCategory,
  familySlug,
  fixturesSupportingFamily,
  parseFamilySlug,
  parsePropertyMask,
  serializePropertyMask,
} from './attributeFamily'
import { SPEED_MASTER_USAGES } from './speedMasterModel'

describe('attribute family slugs', () => {
  it('round-trips every family', () => {
    for (const type of ATTRIBUTE_FAMILIES) {
      expect(parseFamilySlug(familySlug(type))).toBe(type)
    }
  })

  it('rejects anything else, so a bad URL redirects rather than rendering blank', () => {
    for (const raw of ['COLOUR', 'colours', 'Colour', '', 'gobo', undefined]) {
      expect(parseFamilySlug(raw), String(raw)).toBeNull()
    }
  })
})

describe('familyForCategory', () => {
  it('mirrors the backend mask groups on the two non-obvious families', () => {
    // Strobe with the intensities, and every extra emitter with colour — the backend records
    // "the colour" including amber/white/UV, and splitting them out would record half a look.
    expect(familyForCategory('strobe')).toBe('INTENSITY')
    for (const emitter of ['colour', 'amber', 'white', 'uv']) {
      expect(familyForCategory(emitter), emitter).toBe('COLOUR')
    }
  })

  it('treats the synthetic position category as POSITION, not as an unknown', () => {
    // `position` is the pan/tilt pair and has no PropertyCategory of its own, so without its
    // own case it would fall into the BEAM catch-all and offer beam palettes on a position row.
    expect(familyForCategory('position')).toBe('POSITION')
    expect(familyForCategory('pan')).toBe('POSITION')
    expect(familyForCategory('tilt_fine')).toBe('POSITION')
  })

  it('files an unrecognised category under BEAM rather than throwing', () => {
    // Group property descriptors carry an untyped category off the wire, and a newer backend
    // can name a category this client has never heard of.
    expect(familyForCategory('some_future_category')).toBe('BEAM')
  })
})

describe('propertyMask round-trip', () => {
  it('parses the comma-separated wire form into families', () => {
    expect(parsePropertyMask('COLOUR')).toEqual(['COLOUR'])
    expect(parsePropertyMask('COLOUR,POSITION')).toEqual(['POSITION', 'COLOUR'])
  })

  it('answers "every family" for all three spellings of no mask', () => {
    // null, undefined and '' all mean unmasked, and `MaskPicker` reads [] as "All attributes" —
    // so the three agree without a translation step.
    expect(parsePropertyMask(null)).toEqual([])
    expect(parsePropertyMask(undefined)).toEqual([])
    expect(parsePropertyMask('')).toEqual([])
  })

  it('normalises whitespace and case, and returns families in canonical order', () => {
    // A mask has one canonical spelling, so re-selecting the same families is not a change and
    // does not PATCH the cue.
    expect(parsePropertyMask(' colour , POSITION ')).toEqual(['POSITION', 'COLOUR'])
    expect(serializePropertyMask(['COLOUR', 'POSITION'])).toBe('POSITION,COLOUR')
  })

  it('drops a family it does not recognise rather than rejecting the mask', () => {
    // A newer backend can name a family this client has never heard of; the operator should still
    // be able to edit the ones it does know.
    expect(parsePropertyMask('COLOUR,WARP_DRIVE')).toEqual(['COLOUR'])
  })

  it('serializes both an empty and a complete selection to no mask', () => {
    // The load-bearing case. All four families compose identically to no mask, but they are not the
    // same stored value — `propertyMask` distinguishes null from a string, so a four-family mask
    // would render a badge that says nothing and would become noise if a fifth family appeared.
    expect(serializePropertyMask([])).toBeNull()
    expect(serializePropertyMask(ATTRIBUTE_FAMILIES)).toBeNull()
  })

  it('round-trips a partial mask unchanged', () => {
    for (const mask of ['INTENSITY', 'POSITION,COLOUR', 'INTENSITY,POSITION,BEAM']) {
      expect(serializePropertyMask(parsePropertyMask(mask)), mask).toBe(mask)
    }
  })
})

describe('familyForEffectCategory', () => {
  it('mirrors the backend map, including the `color` spelling', () => {
    // A direct mirror of `familyForEffectCategory` in projectLooks.kt, which accepts both
    // spellings because the shipped `.fx.kts` files are not consistent about it.
    expect(familyForEffectCategory('dimmer')).toBe('INTENSITY')
    expect(familyForEffectCategory('colour')).toBe('COLOUR')
    expect(familyForEffectCategory('color')).toBe('COLOUR')
    expect(familyForEffectCategory('position')).toBe('POSITION')
    expect(familyForEffectCategory('beam')).toBe('BEAM')
  })

  it('answers null for a category that names no family, rather than a catch-all', () => {
    // The load-bearing difference from `familyForCategory`, which is total and files anything it
    // does not know under BEAM. Here `controls` has no tempo and `composite` spans families, so
    // neither *has* a family — and answering BEAM would be a wrong answer rather than a default,
    // one the write boundary would then refuse under a family the operator never chose.
    expect(familyForEffectCategory('controls')).toBeNull()
    expect(familyForEffectCategory('composite')).toBeNull()
    expect(familyForEffectCategory('some_future_category')).toBeNull()
  })

  it('is a different vocabulary from familyForCategory, not a widening of it', () => {
    // `position` is an effect category but not a PropertyCategory; the property side reaches
    // POSITION through `pan`/`tilt`, which are not effect categories at all. The two maps share
    // `dimmer` and `colour` and nothing else, which is why neither can absorb the other.
    expect(familyForEffectCategory('pan')).toBeNull()
    expect(familyForCategory('composite')).toBe('BEAM')
  })
})

describe('which families can hold an effect', () => {
  it('excludes BEAM, and by name rather than by the library being empty', () => {
    // The backend refuses `beam` explicitly so a *script-registered* beam effect cannot mint a
    // Beam effect template behind the rule — so this list must not be derived from whatever
    // categories the shipped library happens to contain.
    expect(TEMPLATE_EFFECT_FAMILIES).not.toContain('BEAM')
    expect(familyCanHoldEffect('BEAM')).toBe(false)
    expect(effectCategoryForFamily('BEAM')).toBeNull()
  })

  it('round-trips every effect-holding family through its category', () => {
    for (const family of TEMPLATE_EFFECT_FAMILIES) {
      const category = effectCategoryForFamily(family)
      expect(category, family).not.toBeNull()
      expect(familyForEffectCategory(category as string), family).toBe(family)
    }
  })

  it('covers exactly the categories a speed master may be the default for', () => {
    // The two lists coincide, and pinning them together is worth doing precisely because they
    // coincide *for different reasons*: a usage excludes `controls` and `composite` for want of a
    // tempo, while a template excludes `beam` for want of a column. If a beam category is ever
    // added to the library, this is the assertion that should be argued with rather than deleted.
    const templateCategories = TEMPLATE_EFFECT_FAMILIES.map(effectCategoryForFamily)
    expect([...templateCategories].sort()).toEqual([...SPEED_MASTER_USAGES].sort())
  })
})

describe('fixturesSupportingFamily', () => {
  const patch = [
    { capabilities: ['dimmer', 'colour'] },
    { capabilities: ['dimmer'] },
    { capabilities: ['dimmer', 'colour', 'position'] },
    { capabilities: [] },
    {},
  ]

  it('counts the heads that declare the family\'s capability', () => {
    expect(fixturesSupportingFamily(patch, 'INTENSITY')).toBe(3)
    expect(fixturesSupportingFamily(patch, 'COLOUR')).toBe(2)
    expect(fixturesSupportingFamily(patch, 'POSITION')).toBe(1)
  })

  it('counts every fixture for BEAM, matching the busk view\'s refusal to filter on it', () => {
    // The beam roles are per-model and a capability set does not summarise them, so filtering
    // would hide heads that can in fact take a beam value.
    expect(fixturesSupportingFamily(patch, 'BEAM')).toBe(patch.length)
  })

  it('answers 0 rather than throwing while the patch is still loading', () => {
    expect(fixturesSupportingFamily(undefined, 'COLOUR')).toBe(0)
  })
})
