import { describe, expect, it } from 'vitest'
import {
  EMITTER_ATTRIBUTES,
  EMITTER_TINTS,
  PALETTE_ATTRIBUTES,
  WHITE_POLICIES,
  describePaletteIntent,
  describePaletteRows,
  parsePaletteIntent,
  serializePaletteIntent,
  paletteIntentSwatch,
  paletteRowsSwatch,
  paletteAttributesForFamily,
  paletteAttributeFor,
  type PaletteIntent,
} from './paletteIntent'
import { ATTRIBUTE_FAMILIES } from './attributeFamily'

/**
 * The client half of the intent grammar, pinned against the backend's.
 *
 * `PaletteIntent.kt` is the authority — resolution happens there and only there — so the value of
 * these tests is that the two agree on the *strings*. A client that serialised `pct:75.0` where the
 * server writes `pct:75` would produce a template whose stored value churns on every save, and a
 * client whose vocabulary drifted would offer a property the write boundary refuses.
 */
describe('paletteIntent', () => {
  const cases: [PaletteIntent, string][] = [
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'extract' }, '#FF9D4A;policy=extract'],
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'additive' }, '#FF9D4A;policy=additive'],
    [{ kind: 'colour', hex: '#FF9D4A', policy: 'rgbonly' }, '#FF9D4A;policy=rgbonly'],
    [{ kind: 'percent', value: 75 }, 'pct:75'],
    [{ kind: 'percent', value: 12.5 }, 'pct:12.5'],
    [{ kind: 'position', pan: -12, tilt: 30, unit: 'centre' }, 'pos:-12,30'],
    [{ kind: 'position', pan: -128, tilt: 127, unit: 'centre' }, 'pos:-128,127'],
    [{ kind: 'position', pan: 45, tilt: -12.5, unit: 'degrees' }, 'deg:45,-12.5'],
    [{ kind: 'switch', on: true }, 'on'],
    [{ kind: 'switch', on: false }, 'off'],
  ]

  it('takes a centre position as whole steps on the scale, both ways, as Kotlin does', () => {
    expect(parsePaletteIntent(' POS: 12.6 , -400 ')).toEqual({ kind: 'position', pan: 13, tilt: -128, unit: 'centre' })
    expect(serializePaletteIntent({ kind: 'position', pan: 300, tilt: -2.4, unit: 'centre' })).toBe('pos:127,-2')
  })

  it('reads a per-fixture degree row through its own head where the axes are given', () => {
    // A 630° pan reads 315° as its centre, where the old editor's 540° would read it past centre.
    const pan = { type: 'slider', name: 'pan', min: 0, max: 255, degMin: 0, degMax: 630 } as never
    const tilt = { type: 'slider', name: 'tilt', min: 0, max: 255, degMin: 0, degMax: 270 } as never
    expect(describePaletteIntent('deg:315,135', { pan, tilt })).toBe('0 / 0')
    expect(describePaletteIntent('deg:315,135')).not.toBe('0 / 0')
  })

  it('serialises to exactly the strings the backend writes', () => {
    for (const [intent, serialised] of cases) {
      expect(serializePaletteIntent(intent)).toBe(serialised)
    }
  })

  it('round-trips every arm', () => {
    for (const [intent, serialised] of cases) {
      expect(parsePaletteIntent(serialised)).toEqual(intent)
    }
  })

  it('reads a colour with no policy token as RGB only', () => {
    // Matching Kotlin, and matching what every *other* reader of that string already does with it:
    // both `parseExtendedColour` implementations ignore an unknown `;`-token, so the safe reading of
    // "no policy stated" is the one that drives no extra emitters.
    expect(parsePaletteIntent('#FF9D4A')).toEqual({
      kind: 'colour',
      hex: '#FF9D4A',
      policy: 'rgbonly',
    })
  })

  it('reads an unknown policy as RGB only rather than throwing', () => {
    expect(parsePaletteIntent('#FF9D4A;policy=sideways')).toEqual({
      kind: 'colour',
      hex: '#FF9D4A',
      policy: 'rgbonly',
    })
  })

  it('answers null for anything that is not an intent', () => {
    for (const raw of ['', '   ', '#GGGGGG', 'pct:', 'deg:45', 'deg:a,b', 'pos:12', 'pos:,', 'pos:1,2,3', '#12345', 'maybe', '128']) {
      expect(parsePaletteIntent(raw), raw).toBeNull()
    }
  })

  it('clamps a percentage on the way in', () => {
    expect(parsePaletteIntent('pct:180')).toEqual({ kind: 'percent', value: 100 })
    expect(parsePaletteIntent('pct:-20')).toEqual({ kind: 'percent', value: 0 })
  })

  it('gives a colour row a swatch and everything else none', () => {
    // What the library row and the resolves-to panel key their rendering off.
    expect(paletteIntentSwatch('#FF9D4A;policy=extract')).toBe('#FF9D4A')
    expect(paletteIntentSwatch('pct:75')).toBeNull()
    expect(paletteIntentSwatch('deg:45,12')).toBeNull()
  })

  it('describes each arm in the language a row shows', () => {
    expect(describePaletteIntent('#FF9D4A;policy=extract')).toBe('#FF9D4A · Extract')
    expect(describePaletteIntent('pct:75')).toBe('75%')
    // Every position reads centre-relative (venue-visit plan D3, P3). A stored degree has no head,
    // so it reads through the old editor's 540° / 270°: 270° / 135° is the centre.
    expect(describePaletteIntent('pos:-12,30')).toBe('-12 / +30')
    expect(describePaletteIntent('deg:270,135')).toBe('0 / 0')
    expect(describePaletteIntent('deg:0,270')).toBe('-128 / +127')
    expect(describePaletteIntent('on')).toBe('On')
    // A value it cannot parse is shown as-is rather than as an empty cell: a row that renders blank
    // reads as "no value", which is the one thing it definitely is not.
    expect(describePaletteIntent('nonsense')).toBe('nonsense')
  })

  describe('the property vocabulary', () => {
    it('matches the backend list exactly', () => {
      // Mirrors `PaletteAttribute` in `fx/PaletteIntent.kt`, in declaration order. Kept as a
      // literal rather than derived: an assertion that rebuilds what it is checking passes just as
      // happily when the source is wrong. `maskPicker.test.ts` pins the family list the same way.
      expect(PALETTE_ATTRIBUTES.map((p) => p.attribute)).toEqual([
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
        expect(paletteAttributeFor(refused), refused).toBeNull()
      }
    })

    it('collapses the three colour spellings onto one entry', () => {
      for (const spelling of ['colour', 'color', 'rgbColour', 'RGBCOLOUR']) {
        expect(paletteAttributeFor(spelling)?.attribute, spelling).toBe('rgbColour')
      }
    })

    it('puts strobe in intensity, not beam', () => {
      // Mirrors `AttributeCategory.STROBE.family()`: an intensity modulation, HTP like a dimmer.
      expect(paletteAttributeFor('strobe')?.family).toBe('INTENSITY')
      expect(paletteAttributesForFamily('INTENSITY').map((p) => p.attribute)).toEqual([
        'dimmer',
        'strobe',
      ])
    })

    it('gives every family at least one property, so no family is unauthorable', () => {
      for (const family of ATTRIBUTE_FAMILIES) {
        expect(paletteAttributesForFamily(family).length, family).toBeGreaterThan(0)
      }
    })

    it('has exactly three white policies, matching WhitePolicy', () => {
      expect([...WHITE_POLICIES]).toEqual(['extract', 'additive', 'rgbonly'])
    })

    it('puts the three emitters in colour, taking a level', () => {
      // Mirrors `AttributeCategory.WHITE.family()`: emitters of the same mixed colour, so a
      // template naming a hex *and* an amber is still one family and still one named thing.
      for (const name of EMITTER_ATTRIBUTES) {
        expect(paletteAttributeFor(name)?.family, name).toBe('COLOUR')
        expect(paletteAttributeFor(name)?.intent, name).toBe('level')
      }
    })

    it('gives every emitter a tint, so no surface has to invent one', () => {
      for (const name of EMITTER_ATTRIBUTES) {
        expect(EMITTER_TINTS[name], name).toMatch(/^#[0-9a-f]{6}$/i)
      }
    })
  })

  describe('the level arm', () => {
    it('round-trips a DMX byte', () => {
      expect(serializePaletteIntent({ kind: 'level', value: 180 })).toBe('dmx:180')
      expect(parsePaletteIntent('dmx:180')).toEqual({ kind: 'level', value: 180 })
      expect(parsePaletteIntent('dmx:0')).toEqual({ kind: 'level', value: 0 })
    })

    it('clamps to 0–255, matching the Kotlin parser', () => {
      expect(parsePaletteIntent('dmx:400')).toEqual({ kind: 'level', value: 255 })
      expect(parsePaletteIntent('dmx:-10')).toEqual({ kind: 'level', value: 0 })
    })

    it('keeps its prefix — a bare number is not an intent', () => {
      // The whole reason `dmx:` carries a prefix its payload does not need: a bare `180` is what the
      // *literal* parser reads, so the two grammars would agree on some rows and not others.
      expect(parsePaletteIntent('180')).toBeNull()
      expect(parsePaletteIntent('dmx:')).toBeNull()
      expect(parsePaletteIntent('dmx:abc')).toBeNull()
    })

    it('describes itself as a bare byte, and names the emitter in a row description', () => {
      expect(describePaletteIntent('dmx:180')).toBe('180')
      expect(
        describePaletteRows([
          { attribute: 'rgbColour', value: '#FF9D4A;policy=rgbonly' },
          { attribute: 'uv', value: 'dmx:255' },
        ]),
      ).toBe('#FF9D4A · RGB only · UV 255')
    })
  })

  describe('paletteRowsSwatch', () => {
    it('reads an absent rows field as no rows, because the wire really sends one', () => {
      // `PaletteSummary.rows` is declared required, but the desk's WebSocket `Json` has
      // `encodeDefaults = false`, so an **effect** template — rows empty by construction — arrives
      // over `hand.state` with no `rows` field at all. `HandChip` draws the held record through
      // `padFaceOf` on every route, so this threw and took the whole app down behind the router's
      // error boundary for as long as the desk held one.
      expect(paletteRowsSwatch(undefined)).toBeNull()
      expect(paletteRowsSwatch([])).toBeNull()
    })

    it('reads the colour row, not the first row', () => {
      // Row order is authoring order. Reading `rows[0]` drew a template holding a hex and a UV row
      // as purple whenever the UV row happened to sort first.
      expect(
        paletteRowsSwatch([
          { attribute: 'uv', value: 'dmx:255' },
          { attribute: 'rgbColour', value: '#FF9D4A;policy=rgbonly' },
        ]),
      ).toBe('#FF9D4A')
    })

    it('falls back to an emitter tint when there is no colour row', () => {
      expect(paletteRowsSwatch([{ attribute: 'uv', value: 'dmx:255' }])).toBe(EMITTER_TINTS.uv)
    })

    it('is null for rows with no colour in them at all', () => {
      expect(paletteRowsSwatch([{ attribute: 'dimmer', value: 'pct:50' }])).toBeNull()
      expect(paletteRowsSwatch([])).toBeNull()
    })
  })
})
