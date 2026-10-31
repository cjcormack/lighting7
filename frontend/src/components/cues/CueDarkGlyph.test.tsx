// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { StackMoveInDarkPlan } from '@/api/cueStacksApi'

/** A cue card's *Dark* glyph (move-in-dark plan session 4): the table's cell, glyphs and counts only. */
const PLAN: StackMoveInDarkPlan = {
  stackId: 7,
  rigLoaded: true,
  summary: { heads: 3, cues: 1, live: 1, off: 0 },
  entries: [
    { entryId: 110, cueId: 10, heads: [], live: [] },
    {
      entryId: 112,
      cueId: 12,
      heads: [
        { target: 'a', fromEntryId: 110, families: ['POSITION'] },
        { target: 'b', fromEntryId: 110, families: ['POSITION'] },
      ],
      live: [{ target: 'c', reason: 'TOO_QUICK' }],
    },
  ],
}
vi.mock('@/store/cueStacks', () => ({
  useCueStackMoveInDarkQuery: (_arg: unknown, opts: { selectFromResult: (r: { data: StackMoveInDarkPlan }) => unknown }) =>
    opts.selectFromResult({ data: PLAN }),
}))

import { CueDarkGlyph } from './CueDarkGlyph'

afterEach(cleanup)

describe('CueDarkGlyph', () => {
  it('counts what the cue’s entry moves, with the sentence as its title', () => {
    const { container } = render(<CueDarkGlyph projectId={1} stackId={7} entryId={112} />)
    const glyph = container.querySelector('[data-dark-glyph="112"]')!
    expect(glyph).toHaveTextContent('21')
    expect(glyph).toHaveAttribute('title', '2 heads set in the dark for this cue; 1 head moves while lit (too quick)')
  })

  it('is absent for a cue that moves nothing', () => {
    const { container } = render(<CueDarkGlyph projectId={1} stackId={7} entryId={110} />)
    expect(container).toBeEmptyDOMElement()
  })
})
