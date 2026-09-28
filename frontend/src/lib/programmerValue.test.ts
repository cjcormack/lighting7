import { describe, expect, it } from 'vitest'
import {
  parseProgrammerEntryValue,
  parseProgrammerValue,
  serializeColour,
  serializeLevel,
  serializePosition,
} from './programmerValue'

/** A well-formed palette uuid, shared by the reference cases below. */
const PALETTE_UUID = '2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02'

describe('parseProgrammerValue', () => {
  it('reads slider and setting levels', () => {
    expect(parseProgrammerValue('0')).toEqual({ kind: 'level', value: 0 })
    expect(parseProgrammerValue('200')).toEqual({ kind: 'level', value: 200 })
    expect(parseProgrammerValue('255')).toEqual({ kind: 'level', value: 255 })
  })

  it('rejects levels outside the DMX byte range', () => {
    expect(parseProgrammerValue('256')).toBeNull()
    expect(parseProgrammerValue('999')).toBeNull()
  })

  it('reads a plain hex colour', () => {
    expect(parseProgrammerValue('#ff0080')).toEqual({
      kind: 'colour',
      r: 255,
      g: 0,
      b: 128,
      w: 0,
      a: 0,
      uv: 0,
    })
  })

  it('reads the extended colour tags the backend emits for non-zero components', () => {
    expect(parseProgrammerValue('#102030;w40;a50;uv60')).toEqual({
      kind: 'colour',
      r: 16,
      g: 32,
      b: 48,
      w: 40,
      a: 50,
      uv: 60,
    })
  })

  it('reads a position pair', () => {
    expect(parseProgrammerValue('12,34')).toEqual({ kind: 'position', pan: 12, tilt: 34 })
  })

  it('does not mistake a position pair for a level', () => {
    // "127" and "127,0" must not collide — the comma is the only discriminator.
    expect(parseProgrammerValue('127')).toEqual({ kind: 'level', value: 127 })
    expect(parseProgrammerValue('127,0')).toEqual({ kind: 'position', pan: 127, tilt: 0 })
  })

  it('accepts the named colours the backend can emit', () => {
    expect(parseProgrammerValue('red')).toEqual({
      kind: 'colour',
      r: 255,
      g: 0,
      b: 0,
      w: 0,
      a: 0,
      uv: 0,
    })
  })

  it('returns null for values it cannot represent, so callers fall back to live DMX', () => {
    // `P1` was the positional colour-list reference. It retired with `ref:`, so it is now just
    // another unparseable string — kept here because a stale cue row could still hold one.
    expect(parseProgrammerValue('P1')).toBeNull()
    expect(parseProgrammerValue('')).toBeNull()
    expect(parseProgrammerValue('   ')).toBeNull()
    expect(parseProgrammerValue('1,2,3')).toBeNull()
  })

  it('returns null for a named-palette reference, because a reference is not a value', () => {
    // `ref:` is retired and rejected at the Look write boundary, so nothing should produce one —
    // but a stale row could still hold one, and reading it as a colour would be worse than
    // falling back to live DMX. This parser must never invent a literal for a reference.
    expect(parseProgrammerValue(`ref:${PALETTE_UUID}`)).toBeNull()
  })

  it('does not coerce arbitrary words into colours', () => {
    // The underlying colour parser treats any three letters in a-f as hex shorthand and
    // returns black for anything else it doesn't recognise, so a gate of "is it alphabetic"
    // silently turned junk into a confident-looking swatch: "bad" → lilac, "gobo" → black.
    expect(parseProgrammerValue('bad')).toBeNull()
    expect(parseProgrammerValue('ace')).toBeNull()
    expect(parseProgrammerValue('gobo')).toBeNull()
    expect(parseProgrammerValue('unknownvalueform')).toBeNull()
  })
})

describe('serialization round-trips', () => {
  it('round-trips a level', () => {
    expect(parseProgrammerValue(serializeLevel(180))).toEqual({ kind: 'level', value: 180 })
  })

  it('clamps and rounds out-of-range levels rather than emitting junk', () => {
    expect(serializeLevel(-5)).toBe('0')
    expect(serializeLevel(300)).toBe('255')
    expect(serializeLevel(127.6)).toBe('128')
  })

  it('round-trips a position', () => {
    expect(parseProgrammerValue(serializePosition(10, 250))).toEqual({
      kind: 'position',
      pan: 10,
      tilt: 250,
    })
  })

  it('round-trips a colour with extended channels', () => {
    expect(parseProgrammerValue(serializeColour(1, 2, 3, 4, 5, 6))).toEqual({
      kind: 'colour',
      r: 1,
      g: 2,
      b: 3,
      w: 4,
      a: 5,
      uv: 6,
    })
  })

  it('omits zero extended channels, matching the backend serializer', () => {
    expect(serializeColour(255, 0, 128)).toBe('#FF0080')
  })
})

describe('parseProgrammerEntryValue', () => {
  // A `describe('named-palette references')` block of four tests stood above this one, covering
  // `parsePaletteRefUuid` / `isPaletteRefValue` / `serializePaletteRef` — including that they were
  // strict about the uuid shape (a corrupt value had to read as "not a reference" rather than as a
  // reference to nothing) and that they were never confused with the positional `P1` form. Both
  // grammars retired in session 4. The one live reference grammar is `tmpl:`, which is an FX
  // colour *parameter* rather than a value, and `colourUtils.test.ts` covers it.
  it('reads a literal entry directly', () => {
    expect(parseProgrammerEntryValue({ value: '200' })).toEqual({ kind: 'level', value: 200 })
  })

  it('reads each of the four value shapes', () => {
    expect(parseProgrammerEntryValue({ value: '120,64' })).toEqual({
      kind: 'position',
      pan: 120,
      tilt: 64,
    })
  })
})
