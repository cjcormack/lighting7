import { describe, expect, it } from 'vitest'
import { selectionBandState } from './selectionBand'

/**
 * `PD-SELECTION-BAR-SHIFT`. The defect it guards is a *layout* one — 40px arriving under a live
 * pointer — which no unit test can see, so what is pinned here is the decision behind it: on a
 * desk the band never moves, and on a phone it never moves *while a drag is in flight*.
 */
describe('selectionBandState', () => {
  describe('on a desk', () => {
    const tall = { shortViewport: false, heldPresence: null }

    it('reserves the height with nothing selected', () => {
      expect(selectionBandState({ ...tall, hasSelection: false })).toBe('reserved')
    })

    it('fills it once there is a selection', () => {
      expect(selectionBandState({ ...tall, hasSelection: true })).toBe('filled')
    })

    it('is never absent, mid-drag or not — which is the whole point of the tall arm', () => {
      // Nothing the marquee does can change whether the band is in the flow, so nothing it does
      // can move the rows under the pointer drawing it.
      for (const heldPresence of [null, true, false]) {
        for (const hasSelection of [true, false]) {
          expect(
            selectionBandState({ shortViewport: false, heldPresence, hasSelection }),
          ).not.toBe('absent')
        }
      }
    })
  })

  describe('on a landscape phone', () => {
    const short = { shortViewport: true }

    it('stays out of the flow with nothing selected', () => {
      // 40px of 393 is worth keeping while reading the grid.
      expect(selectionBandState({ ...short, heldPresence: null, hasSelection: false })).toBe(
        'absent',
      )
    })

    it('holds the band back for a drag begun with nothing selected', () => {
      // The marquee's first cells make `hasSelection` true immediately; the hold is what stops the
      // band arriving on them and pushing every row down mid-gesture.
      expect(selectionBandState({ ...short, heldPresence: false, hasSelection: true })).toBe(
        'absent',
      )
    })

    it('lets it arrive the moment the drag ends', () => {
      expect(selectionBandState({ ...short, heldPresence: null, hasSelection: true })).toBe(
        'filled',
      )
    })

    it('holds the band in place for a drag begun with a selection showing', () => {
      // The other direction of the same shift: a drag that resolves to no cells at all would
      // otherwise take the band away and pull the rows up by the same 40px.
      expect(selectionBandState({ ...short, heldPresence: true, hasSelection: false })).toBe(
        'reserved',
      )
    })

    it('keeps a held band filled while the drag still covers cells', () => {
      expect(selectionBandState({ ...short, heldPresence: true, hasSelection: true })).toBe(
        'filled',
      )
    })
  })
})
