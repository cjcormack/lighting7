import { describe, expect, it } from 'vitest'
import { isPaletteRef, parsePaletteRefUuid, serializePaletteRef } from './colourUtils'

/**
 * The `pal:` grammar, pinned against `fx/PaletteColourSource.kt`, which owns it.
 *
 * The file this sits beside did not exist until the positional palette went, and
 * `lib/programmerValue.test.ts` had been claiming for a while that it did. This half of the mirror
 * **serialises and parses only** — resolving a reference to a colour is per-head arithmetic that
 * `PaletteResolver` must be the single answer to — so these are the only three behaviours to pin.
 */
describe('palette colour references', () => {
  const uuid = '2f1c8a3e-0000-4000-8000-000000000001'

  it('recognises a reference regardless of case or surrounding space', () => {
    expect(isPaletteRef(`pal:${uuid}`)).toBe(true)
    expect(isPaletteRef(`  PAL:${uuid}  `)).toBe(true)
    expect(parsePaletteRefUuid(`  pal:${uuid} `)).toBe(uuid)
  })

  it('round-trips through serialize', () => {
    expect(serializePaletteRef(uuid)).toBe(`pal:${uuid}`)
    expect(parsePaletteRefUuid(serializePaletteRef(uuid))).toBe(uuid)
  })

  it('treats a literal as a literal', () => {
    for (const literal of ['#ff0000', 'red', '#ff0000;w128;a64', 'P1', '']) {
      expect(isPaletteRef(literal)).toBe(false)
      expect(parsePaletteRefUuid(literal)).toBeNull()
    }
  })

  it('does not answer to `ref:`', () => {
    // `ref:{uuid}` is retired *and* still refused at the Look write boundary as the no-nesting
    // guarantee. The two grammars must stay unconfusable in both directions.
    expect(isPaletteRef(`ref:${uuid}`)).toBe(false)
    expect(parsePaletteRefUuid(`ref:${uuid}`)).toBeNull()
  })

  it('reports a malformed uuid as a reference with a bad target, not as a literal', () => {
    // The caller's question is "which template is this pointing at". Answering null here would make
    // a broken reference render as a colour, which is how a dangling one gets missed.
    expect(isPaletteRef('pal:oops')).toBe(true)
    expect(parsePaletteRefUuid('pal:oops')).toBe('oops')
    // Nothing after the prefix is not a target at all.
    expect(parsePaletteRefUuid('pal:')).toBeNull()
  })
})
