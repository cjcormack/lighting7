import { describe, expect, it } from 'vitest'
import {
  ATTRIBUTE_FAMILIES,
  PALETTE_FX_FAMILIES,
  familyForCategory,
  attributeFamilyForFxFamily,
  BEAM_FX_ATTRIBUTES,
  fxAttributeFamily,
  fxFamilyOnAttribute,
  familySlug,
  fixturesSupportingFamily,
  parseFamilySlug,
  parseAttributeMask,
  serializeAttributeMask,
} from './attributeFamily'
import { PALETTE_ATTRIBUTES } from './paletteIntent'
import { SPEED_MASTER_FAMILIES } from './speedMasterModel'

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
    // `position` is the pan/tilt pair and has no AttributeCategory of its own, so without its
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

describe('attributeMask round-trip', () => {
  it('parses the comma-separated wire form into families', () => {
    expect(parseAttributeMask('COLOUR')).toEqual(['COLOUR'])
    expect(parseAttributeMask('COLOUR,POSITION')).toEqual(['POSITION', 'COLOUR'])
  })

  it('answers "every family" for all three spellings of no mask', () => {
    // null, undefined and '' all mean unmasked, as the backend reads them — and `LookStack`'s layer
    // picker maps this [] to a `MaskValue` of null, *All attributes*.
    expect(parseAttributeMask(null)).toEqual([])
    expect(parseAttributeMask(undefined)).toEqual([])
    expect(parseAttributeMask('')).toEqual([])
  })

  it('normalises whitespace and case, and returns families in canonical order', () => {
    // A mask has one canonical spelling, so re-selecting the same families is not a change and
    // does not PATCH the cue.
    expect(parseAttributeMask(' colour , POSITION ')).toEqual(['POSITION', 'COLOUR'])
    expect(serializeAttributeMask(['COLOUR', 'POSITION'])).toBe('POSITION,COLOUR')
  })

  it('drops a family it does not recognise rather than rejecting the mask', () => {
    // A newer backend can name a family this client has never heard of; the operator should still
    // be able to edit the ones it does know.
    expect(parseAttributeMask('COLOUR,WARP_DRIVE')).toEqual(['COLOUR'])
  })

  it('serializes both an empty and a complete selection to no mask', () => {
    // The load-bearing case. All four families compose identically to no mask, but they are not the
    // same stored value — `attributeMask` distinguishes null from a string, so a four-family mask
    // would render a badge that says nothing and would become noise if a fifth family appeared.
    expect(serializeAttributeMask([])).toBeNull()
    expect(serializeAttributeMask(ATTRIBUTE_FAMILIES)).toBeNull()
  })

  it('round-trips a partial mask unchanged', () => {
    for (const mask of ['INTENSITY', 'POSITION,COLOUR', 'INTENSITY,POSITION,BEAM']) {
      expect(serializeAttributeMask(parseAttributeMask(mask)), mask).toBe(mask)
    }
  })
})

describe('attributeFamilyForFxFamily', () => {
  it('mirrors the backend map, including the `color` spelling', () => {
    // A direct mirror of `attributeFamilyForFxFamily` in projectLooks.kt, which accepts both
    // spellings because the shipped `.fx.kts` files are not consistent about it.
    expect(attributeFamilyForFxFamily('intensity')).toBe('INTENSITY')
    expect(attributeFamilyForFxFamily('colour')).toBe('COLOUR')
    expect(attributeFamilyForFxFamily('color')).toBe('COLOUR')
    expect(attributeFamilyForFxFamily('position')).toBe('POSITION')
    expect(attributeFamilyForFxFamily('beam')).toBe('BEAM')
  })

  it('answers null for a category that names no family, rather than a catch-all', () => {
    // The load-bearing difference from `familyForCategory`, which is total and files anything it
    // does not know under BEAM. Here `controls` has no tempo and `steps` (the Steps FX) steps
    // another family, so neither *has* a family — and answering BEAM would be a wrong answer rather
    // than a default, one the write boundary would then refuse under a family the operator never chose.
    expect(attributeFamilyForFxFamily('controls')).toBeNull()
    expect(attributeFamilyForFxFamily('steps')).toBeNull()
    expect(attributeFamilyForFxFamily('some_future_category')).toBeNull()
  })

  it('is a different vocabulary from familyForCategory, not a widening of it', () => {
    // `position` is an effect category but not a AttributeCategory; the property side reaches
    // POSITION through `pan`/`tilt`, which are not effect categories at all. The two maps share
    // `colour` and nothing else, which is why neither can absorb the other.
    expect(attributeFamilyForFxFamily('pan')).toBeNull()
    expect(familyForCategory('controls')).toBe('BEAM')
  })
})

describe('which families can hold an effect', () => {
  it('is all four, Beam included', () => {
    expect([...PALETTE_FX_FAMILIES].sort()).toEqual(['BEAM', 'COLOUR', 'INTENSITY', 'POSITION'])
    expect(attributeFamilyForFxFamily('beam')).toBe('BEAM')
  })

  it('covers exactly the categories a speed master may be the default for', () => {
    // A master family excludes `controls` and `steps` for want of a tempo, and a template the same two
    // for want of a family.
    const masterFamilies = SPEED_MASTER_FAMILIES.map(attributeFamilyForFxFamily)
    expect([...masterFamilies].sort()).toEqual([...PALETTE_FX_FAMILIES].sort())
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

describe('fxAttributeFamily (fx-settings plan D5)', () => {
  it('is Beam on a beam attribute, else the library family', () => {
    expect(fxAttributeFamily('intensity', 'zoom')).toBe('BEAM')
    expect(fxAttributeFamily('intensity', 'focus')).toBe('BEAM')
    expect(fxAttributeFamily('intensity', 'dimmer')).toBe('INTENSITY')
    expect(fxAttributeFamily('intensity', 'uv')).toBe('INTENSITY')
    expect(fxAttributeFamily('intensity', null)).toBe('INTENSITY')
    expect(fxAttributeFamily('position', 'position')).toBe('POSITION')
    expect(fxAttributeFamily('steps', 'zoom')).toBeNull()
    expect(fxFamilyOnAttribute('intensity', 'iris')).toBe('beam')
    expect(fxFamilyOnAttribute('intensity', 'dimmer')).toBe('intensity')
  })

  it('reads the beam attributes as the palette vocabulary files them', () => {
    expect([...BEAM_FX_ATTRIBUTES].sort()).toEqual(
      PALETTE_ATTRIBUTES.filter((p) => p.family === 'BEAM').map((p) => p.attribute).sort(),
    )
  })
})
