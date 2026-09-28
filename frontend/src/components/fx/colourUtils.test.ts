import { describe, expect, it } from 'vitest'
import { isTemplateRef, parseTemplateRefUuid, serializeTemplateRef } from './colourUtils'

/**
 * The `tmpl:` grammar, pinned against `fx/TemplateColourSource.kt`, which owns it.
 *
 * The file this sits beside did not exist until the positional palette went, and
 * `lib/programmerValue.test.ts` had been claiming for a while that it did. This half of the mirror
 * **serialises and parses only** — resolving a reference to a colour is per-head arithmetic that
 * `TemplateResolver` must be the single answer to — so these are the only three behaviours to pin.
 */
describe('template colour references', () => {
  const uuid = '2f1c8a3e-0000-4000-8000-000000000001'

  it('recognises a reference regardless of case or surrounding space', () => {
    expect(isTemplateRef(`tmpl:${uuid}`)).toBe(true)
    expect(isTemplateRef(`  TMPL:${uuid}  `)).toBe(true)
    expect(parseTemplateRefUuid(`  tmpl:${uuid} `)).toBe(uuid)
  })

  it('round-trips through serialize', () => {
    expect(serializeTemplateRef(uuid)).toBe(`tmpl:${uuid}`)
    expect(parseTemplateRefUuid(serializeTemplateRef(uuid))).toBe(uuid)
  })

  it('treats a literal as a literal', () => {
    for (const literal of ['#ff0000', 'red', '#ff0000;w128;a64', 'P1', '']) {
      expect(isTemplateRef(literal)).toBe(false)
      expect(parseTemplateRefUuid(literal)).toBeNull()
    }
  })

  it('does not answer to `ref:`', () => {
    // `ref:{uuid}` is retired *and* still refused at the Look write boundary as the no-nesting
    // guarantee. The two grammars must stay unconfusable in both directions.
    expect(isTemplateRef(`ref:${uuid}`)).toBe(false)
    expect(parseTemplateRefUuid(`ref:${uuid}`)).toBeNull()
  })

  it('reports a malformed uuid as a reference with a bad target, not as a literal', () => {
    // The caller's question is "which template is this pointing at". Answering null here would make
    // a broken reference render as a colour, which is how a dangling one gets missed.
    expect(isTemplateRef('tmpl:oops')).toBe(true)
    expect(parseTemplateRefUuid('tmpl:oops')).toBe('oops')
    // Nothing after the prefix is not a target at all.
    expect(parseTemplateRefUuid('tmpl:')).toBeNull()
  })
})
