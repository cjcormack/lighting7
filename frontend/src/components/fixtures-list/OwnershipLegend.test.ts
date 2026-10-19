// @vitest-environment jsdom
// jsdom provides `window`: `ownership.ts` reaches api/lightingApi transitively via the cell
// value helpers, and it reads window.location at import time.
import { createElement } from 'react'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { OwnershipKey, OwnershipLegend } from './OwnershipLegend'
import { OWNERSHIP_LABELS } from './ownership'
import { LEGEND_GLOSS, LEGEND_ORDER, LEGEND_SHORT, LENS_HELD_GLOSS } from './ownershipLegendModel'

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

/**
 * The programmer's cells wear the FX lens chip, not the old wave badge, so the key teaches the
 * chip: whole for an effect that runs, struck through for one held back.
 */
describe('the legend keys the FX lens chip', () => {
  it('draws the chip whole beside Effect and struck through beside Held back, in the footer and the phone key', () => {
    for (const element of [createElement(OwnershipLegend), createElement(OwnershipKey)]) {
      const { container, unmount } = render(element)
      const chips = [...container.querySelectorAll('.bg-violet-500, .line-through')]
      expect(chips.map((c) => c.classList.contains('line-through'))).toEqual([false, true])
      expect(container.textContent).toContain(LENS_HELD_GLOSS)
      unmount()
    }
  })
})
