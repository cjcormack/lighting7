import { describe, expect, it } from 'vitest'
import {
  EMITTER_PROPERTIES,
  EMITTER_TINTS,
  TEMPLATE_PROPERTIES,
  WHITE_POLICIES,
  describeTemplateIntent,
  describeTemplateRows,
  parseTemplateIntent,
  serializeTemplateIntent,
  templateIntentSwatch,
  templateRowsSwatch,
  templatePropertiesForFamily,
  templatePropertyFor,
  type TemplateIntent,
} from './templateIntent'
import { ATTRIBUTE_FAMILIES } from './attributeFamily'

/**
 * The client half of the intent grammar, pinned against the backend's.
 *
 * `TemplateIntent.kt` is the authority — resolution happens there and only there — so the value of
 * these tests is that the two agree on the *strings*. A client that serialised `pct:75.0` where the
 * server writes `pct:75` would produce a template whose stored value churns on every save, and a
 * client whose vocabulary drifted would offer a property the write boundary refuses.
 */
describe('templateIntent', () => {
  const cases: [TemplateIntent, string][] = [
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'extract' }, '#FF9D4A;policy=extract'],
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'additive' }, '#FF9D4A;policy=additive'],
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'rgbonly' }, '#FF9D4A;policy=rgbonly'],
    [{ kind: 'percent', value: 75 }, 'pct:75'],
    [{ kind: 'percent', value: 12.5 }, 'pct:12.5'],
    [{ kind: 'position', panDeg: 45, tiltDeg: -12.5 }, 'deg:45,-12.5'],
    [{ kind: 'switch', on: true }, 'on'],
    [{ kind: 'switch', on: false }, 'off'],
  ]

  it('serialises to exactly the strings the backend writes', () => {
    for (const [intent, serialised] of cases) {
      expect(serializeTemplateIntent(intent)).toBe(serialised)
    }
  })

  it('round-trips every arm', () => {
    for (const [intent, serialised] of cases) {
      expect(parseTemplateIntent(serialised)).toEqual(intent)
    }
  })

  it('reads a colour with no policy token as RGB only', () => {
    // Matching Kotlin, and matching what every *other* reader of that string already does with it:
    // both `parseExtendedColour` implementations ignore an unknown `;`-token, so the safe reading of
    // "no policy stated" is the one that drives no extra emitters.
    expect(parseTemplateIntent('#FF9D4A')).toEqual({
      kind: 'colour',
      hex: '#FF9D4A',
      policy: 'rgbonly',
    })
  })

  it('reads an unknown policy as RGB only rather than throwing', () => {
    expect(parseTemplateIntent('#FF9D4A;policy=sideways')).toEqual({
      kind: 'colour',
      hex: '#FF9D4A',
      policy: 'rgbonly',
    })
  })

  it('answers null for anything that is not an intent', () => {
    for (const raw of ['', '   ', '#GGGGGG', 'pct:', 'deg:45', 'deg:a,b', '#12345', 'maybe', '128']) {
      expect(parseTemplateIntent(raw), raw).toBeNull()
    }
  })

  it('clamps a percentage on the way in', () => {
    expect(parseTemplateIntent('pct:180')).toEqual({ kind: 'percent', value: 100 })
    expect(parseTemplateIntent('pct:-20')).toEqual({ kind: 'percent', value: 0 })
  })

  it('gives a colour row a swatch and everything else none', () => {
    // What the library row and the resolves-to panel key their rendering off.
    expect(templateIntentSwatch('#FF9D4A;policy=extract')).toBe('#FF9D4A')
    expect(templateIntentSwatch('pct:75')).toBeNull()
    expect(templateIntentSwatch('deg:45,12')).toBeNull()
  })

  it('describes each arm in the language a row shows', () => {
    expect(describeTemplateIntent('#FF9D4A;policy=extract')).toBe('#FF9D4A · Extract')
    expect(describeTemplateIntent('pct:75')).toBe('75%')
    expect(describeTemplateIntent('deg:45,-12.5')).toBe('45° / -12.5°')
    expect(describeTemplateIntent('on')).toBe('On')
    // A value it cannot parse is shown as-is rather than as an empty cell: a row that renders blank
    // reads as "no value", which is the one thing it definitely is not.
    expect(describeTemplateIntent('nonsense')).toBe('nonsense')
  })

  describe('the property vocabulary', () => {
    it('matches the backend list exactly', () => {
      // Mirrors `TemplateProperty` in `fx/TemplateIntent.kt`, in declaration order. Kept as a
      // literal rather than derived: an assertion that rebuilds what it is checking passes just as
      // happily when the source is wrong. `maskPicker.test.ts` pins the family list the same way.
      expect(TEMPLATE_PROPERTIES.map((p) => p.propertyName)).toEqual([
        'dimmer',
        'strobe',
        'position',
        'rgbColour',
        'white',
        'amber',
        'uv',
        'zoom',
        'focus',
        'iris',
        'frost',
        'prism',
      ])
    })

    it('is closed — a slotted role is refused, and so is a misspelling', () => {
      // Where "a template cannot carry a gobo" lives on this side. The backend refuses it too; this
      // is what stops the editor offering it in the first place.
      for (const refused of ['gobo', 'goboRotation', 'ledMacro', 'movementMacro', 'dimer', '']) {
        expect(templatePropertyFor(refused), refused).toBeNull()
      }
    })

    it('collapses the three colour spellings onto one entry', () => {
      for (const spelling of ['colour', 'color', 'rgbColour', 'RGBCOLOUR']) {
        expect(templatePropertyFor(spelling)?.propertyName, spelling).toBe('rgbColour')
      }
    })

    it('puts strobe in intensity, not beam', () => {
      // Mirrors `PropertyCategory.STROBE.maskGroup()`: an intensity modulation, HTP like a dimmer.
      expect(templatePropertyFor('strobe')?.family).toBe('INTENSITY')
      expect(templatePropertiesForFamily('INTENSITY').map((p) => p.propertyName)).toEqual([
        'dimmer',
        'strobe',
      ])
    })

    it('gives every family at least one property, so no family is unauthorable', () => {
      for (const family of ATTRIBUTE_FAMILIES) {
        expect(templatePropertiesForFamily(family).length, family).toBeGreaterThan(0)
      }
    })

    it('has exactly three white policies, matching WhitePolicy', () => {
      expect([...WHITE_POLICIES]).toEqual(['extract', 'additive', 'rgbonly'])
    })

    it('puts the three emitters in colour, taking a level', () => {
      // Mirrors `PropertyCategory.WHITE.maskGroup()`: emitters of the same mixed colour, so a
      // template naming a hex *and* an amber is still one family and still one named thing.
      for (const name of EMITTER_PROPERTIES) {
        expect(templatePropertyFor(name)?.family, name).toBe('COLOUR')
        expect(templatePropertyFor(name)?.intent, name).toBe('level')
      }
    })

    it('gives every emitter a tint, so no surface has to invent one', () => {
      for (const name of EMITTER_PROPERTIES) {
        expect(EMITTER_TINTS[name], name).toMatch(/^#[0-9a-f]{6}$/i)
      }
    })
  })

  describe('the level arm', () => {
    it('round-trips a DMX byte', () => {
      expect(serializeTemplateIntent({ kind: 'level', value: 180 })).toBe('dmx:180')
      expect(parseTemplateIntent('dmx:180')).toEqual({ kind: 'level', value: 180 })
      expect(parseTemplateIntent('dmx:0')).toEqual({ kind: 'level', value: 0 })
    })

    it('clamps to 0–255, matching the Kotlin parser', () => {
      expect(parseTemplateIntent('dmx:400')).toEqual({ kind: 'level', value: 255 })
      expect(parseTemplateIntent('dmx:-10')).toEqual({ kind: 'level', value: 0 })
    })

    it('keeps its prefix — a bare number is not an intent', () => {
      // The whole reason `dmx:` carries a prefix its payload does not need: a bare `180` is what the
      // *literal* parser reads, so the two grammars would agree on some rows and not others.
      expect(parseTemplateIntent('180')).toBeNull()
      expect(parseTemplateIntent('dmx:')).toBeNull()
      expect(parseTemplateIntent('dmx:abc')).toBeNull()
    })

    it('describes itself as a bare byte, and names the emitter in a row description', () => {
      expect(describeTemplateIntent('dmx:180')).toBe('180')
      expect(
        describeTemplateRows([
          { propertyName: 'rgbColour', value: '#FF9D4A;policy=rgbonly' },
          { propertyName: 'uv', value: 'dmx:255' },
        ]),
      ).toBe('#FF9D4A · RGB only · UV 255')
    })
  })

  describe('templateRowsSwatch', () => {
    it('reads an absent rows field as no rows, because the wire really sends one', () => {
      // `TemplateSummary.rows` is declared required, but the desk's WebSocket `Json` has
      // `encodeDefaults = false`, so an **effect** template — rows empty by construction — arrives
      // over `hand.state` with no `rows` field at all. `HandChip` draws the held record through
      // `padFaceOf` on every route, so this threw and took the whole app down behind the router's
      // error boundary for as long as the desk held one.
      expect(templateRowsSwatch(undefined)).toBeNull()
      expect(templateRowsSwatch([])).toBeNull()
    })

    it('reads the colour row, not the first row', () => {
      // Row order is authoring order. Reading `rows[0]` drew a template holding a hex and a UV row
      // as purple whenever the UV row happened to sort first.
      expect(
        templateRowsSwatch([
          { propertyName: 'uv', value: 'dmx:255' },
          { propertyName: 'rgbColour', value: '#FF9D4A;policy=rgbonly' },
        ]),
      ).toBe('#FF9D4A')
    })

    it('falls back to an emitter tint when there is no colour row', () => {
      expect(templateRowsSwatch([{ propertyName: 'uv', value: 'dmx:255' }])).toBe(EMITTER_TINTS.uv)
    })

    it('is null for rows with no colour in them at all', () => {
      expect(templateRowsSwatch([{ propertyName: 'dimmer', value: 'pct:50' }])).toBeNull()
      expect(templateRowsSwatch([])).toBeNull()
    })
  })
})
