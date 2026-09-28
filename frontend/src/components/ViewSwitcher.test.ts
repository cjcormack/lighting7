// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import {
  CARDS_LINK_STATE,
  CHANNELS_VIEW_KEY,
  FIXTURES_VIEW_KEY,
  SHOW_VIEW_KEY,
  getStoredCardsListView,
  setStoredCardsListView,
  stickyRedirectsToList,
} from './ViewSwitcher'

/**
 * The cards/sheet pairs share one redirect rule: a cards route hands off to its sibling when the
 * sticky says so, unless the arrival is the switcher's own Cards click. Pinned for the two pairs
 * the sheet kit added beside the two that were there, so the four cannot drift — and so the
 * storage vocabulary stays `'list'` whatever the second segment is called on screen.
 */
afterEach(() => localStorage.clear())

describe('stickyRedirectsToList', () => {
  it.each([FIXTURES_VIEW_KEY, CHANNELS_VIEW_KEY, SHOW_VIEW_KEY])(
    'redirects %s to the sheet once the sheet has been chosen',
    (key) => {
      expect(stickyRedirectsToList(null, key)).toBe(false)
      setStoredCardsListView(key, 'list')
      expect(stickyRedirectsToList(null, key)).toBe(true)
      expect(getStoredCardsListView(key)).toBe('list')
    },
  )

  it('never redirects the switcher’s own Cards click, even with the sticky still saying list', () => {
    setStoredCardsListView(CHANNELS_VIEW_KEY, 'list')
    expect(stickyRedirectsToList(CARDS_LINK_STATE, CHANNELS_VIEW_KEY)).toBe(false)
    expect(stickyRedirectsToList({ stickyView: 'cards' }, SHOW_VIEW_KEY)).toBe(false)
  })

  it('keeps the four keys apart — choosing the DMX sheet does not send Show to its table', () => {
    setStoredCardsListView(CHANNELS_VIEW_KEY, 'list')
    expect(stickyRedirectsToList(null, SHOW_VIEW_KEY)).toBe(false)
    expect(stickyRedirectsToList(null, FIXTURES_VIEW_KEY)).toBe(false)
  })

  it('reads anything but list as cards, so a corrupt value lands on the cards', () => {
    localStorage.setItem(SHOW_VIEW_KEY, '"table"')
    expect(getStoredCardsListView(SHOW_VIEW_KEY)).toBe('cards')
    localStorage.setItem(SHOW_VIEW_KEY, 'not json')
    expect(getStoredCardsListView(SHOW_VIEW_KEY)).toBe('cards')
  })
})
