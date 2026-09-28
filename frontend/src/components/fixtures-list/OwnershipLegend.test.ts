// @vitest-environment jsdom
// jsdom provides `window`: `ownership.ts` reaches api/lightingApi transitively via the cell
// value helpers, and it reads window.location at import time.
import { describe, expect, it } from 'vitest'
import { OWNERSHIP_LABELS } from './ownership'
import { LEGEND_GLOSS, LEGEND_ORDER, LEGEND_SHORT } from './ownershipLegendModel'

/**
 * Two maps describe the same five sources for two audiences — the hover text names the owner, the
 * legend says what it means for you. Adding a sixth source must not leave either silently short.
 */
describe('the ownership legend', () => {
  it('covers exactly the sources the hover text names', () => {
    expect(Object.keys(LEGEND_GLOSS).sort()).toEqual(Object.keys(OWNERSHIP_LABELS).sort())
  })

  it('has a footer word for every source too', () => {
    // The 22px footer draws `LEGEND_SHORT` and swaps to the gloss at `@[1100px]`. A source missing
    // from this map would render an empty label at every width narrower than that, which reads as
    // a swatch that means nothing.
    expect(Object.keys(LEGEND_SHORT).sort()).toEqual(Object.keys(OWNERSHIP_LABELS).sort())
  })

  it('draws every source exactly once', () => {
    expect([...LEGEND_ORDER].sort()).toEqual(Object.keys(OWNERSHIP_LABELS).sort())
  })
})
