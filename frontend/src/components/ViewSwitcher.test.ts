// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import {
  CARDS_LINK_STATE,
  CHANNELS_VIEW_KEY,
  FIXTURES_VIEW_KEY,
  GROUPS_VIEW_KEY,
  SHOW_VIEW_KEY,
  getStoredCardsListView,
  setStoredCardsListView,
  stickyRedirectsToList,
  stickyStackSheetView,
} from './ViewSwitcher'

/**
 * The cards/sheet pairs share one redirect rule: a cards route hands off to its sibling when the
 * sticky says so, unless the arrival is the switcher's own Cards click. Pinned for the two pairs
 * the sheet kit added beside the two that were there, so the four cannot drift — and so the
 * storage vocabulary stays `'list'` whatever the second segment is called on screen.
 */
afterEach(() => localStorage.clear())

describe('the first visit, with nothing stored (venue-visit plan S2)', () => {
  it.each([FIXTURES_VIEW_KEY, GROUPS_VIEW_KEY])('lands %s on its list', (key) => {
    expect(getStoredCardsListView(key)).toBe('list')
    expect(stickyRedirectsToList(null, key)).toBe(true)
  })

  it.each([CHANNELS_VIEW_KEY, SHOW_VIEW_KEY])('keeps %s on its cards', (key) => {
    expect(getStoredCardsListView(key)).toBe('cards')
    expect(stickyRedirectsToList(null, key)).toBe(false)
  })

  it.each([FIXTURES_VIEW_KEY, GROUPS_VIEW_KEY])('still lets the switcher’s Cards click reach %s’s cards', (key) => {
    // The default is the list, so the redirect must not depend on a stored `'cards'` existing:
    // the link state alone carries the operator there.
    expect(stickyRedirectsToList(CARDS_LINK_STATE, key)).toBe(false)
  })

  it('writes nothing on a read, so the default is not frozen into storage', () => {
    getStoredCardsListView(FIXTURES_VIEW_KEY)
    stickyRedirectsToList(null, GROUPS_VIEW_KEY)
    expect(localStorage.length).toBe(0)
  })
})

describe('a stored choice', () => {
  it.each([FIXTURES_VIEW_KEY, GROUPS_VIEW_KEY, CHANNELS_VIEW_KEY, SHOW_VIEW_KEY])(
    'keeps %s on the cards once the cards have been chosen',
    (key) => {
      setStoredCardsListView(key, 'cards')
      expect(getStoredCardsListView(key)).toBe('cards')
      expect(stickyRedirectsToList(null, key)).toBe(false)
    },
  )

  it.each([FIXTURES_VIEW_KEY, GROUPS_VIEW_KEY, CHANNELS_VIEW_KEY, SHOW_VIEW_KEY])(
    'redirects %s to the sheet once the sheet has been chosen',
    (key) => {
      setStoredCardsListView(key, 'list')
      expect(stickyRedirectsToList(null, key)).toBe(true)
      expect(getStoredCardsListView(key)).toBe('list')
    },
  )

  it('is stored as the bare JSON string, the same for all four keys', () => {
    // What a desk already holds: changing the default must not change what a stored value means.
    setStoredCardsListView(SHOW_VIEW_KEY, 'list')
    setStoredCardsListView(FIXTURES_VIEW_KEY, 'cards')
    expect(localStorage.getItem(SHOW_VIEW_KEY)).toBe('"list"')
    expect(localStorage.getItem(FIXTURES_VIEW_KEY)).toBe('"cards"')
  })
})

describe('stickyRedirectsToList', () => {
  it('never redirects the switcher’s own Cards click, even with the sticky still saying list', () => {
    setStoredCardsListView(CHANNELS_VIEW_KEY, 'list')
    expect(stickyRedirectsToList(CARDS_LINK_STATE, CHANNELS_VIEW_KEY)).toBe(false)
    expect(stickyRedirectsToList({ stickyView: 'cards' }, SHOW_VIEW_KEY)).toBe(false)
  })

  it('keeps the four keys apart — choosing the DMX sheet does not send Show to its table', () => {
    setStoredCardsListView(CHANNELS_VIEW_KEY, 'list')
    expect(stickyRedirectsToList(null, SHOW_VIEW_KEY)).toBe(false)
    // …nor choosing the Fixtures cards keep Groups off its list.
    setStoredCardsListView(FIXTURES_VIEW_KEY, 'cards')
    expect(stickyRedirectsToList(null, GROUPS_VIEW_KEY)).toBe(true)
  })

  it('reads a corrupt value as the key’s default', () => {
    localStorage.setItem(SHOW_VIEW_KEY, '"table"')
    expect(getStoredCardsListView(SHOW_VIEW_KEY)).toBe('cards')
    localStorage.setItem(SHOW_VIEW_KEY, 'not json')
    expect(getStoredCardsListView(SHOW_VIEW_KEY)).toBe('cards')
    localStorage.setItem(FIXTURES_VIEW_KEY, '"table"')
    expect(getStoredCardsListView(FIXTURES_VIEW_KEY)).toBe('list')
    localStorage.setItem(GROUPS_VIEW_KEY, 'not json')
    expect(getStoredCardsListView(GROUPS_VIEW_KEY)).toBe('list')
  })
})

describe("a stack's third view, the Timeline (stack-timeline plan D2)", () => {
  it('sends a drilled stack to whichever sheet was chosen last', () => {
    expect(stickyStackSheetView(null)).toBeNull()
    setStoredCardsListView(SHOW_VIEW_KEY, 'timeline')
    expect(getStoredCardsListView(SHOW_VIEW_KEY)).toBe('timeline')
    expect(stickyStackSheetView(null)).toBe('timeline')
    setStoredCardsListView(SHOW_VIEW_KEY, 'list')
    expect(stickyStackSheetView(null)).toBe('list')
    expect(stickyStackSheetView(CARDS_LINK_STATE)).toBeNull()
  })

  it('is Show’s alone: another key reads a stored timeline as its default', () => {
    localStorage.setItem(FIXTURES_VIEW_KEY, JSON.stringify('timeline'))
    expect(getStoredCardsListView(FIXTURES_VIEW_KEY)).toBe('list')
    // Show's Table redirect is the pair's rule, which a stored Timeline does not fire.
    setStoredCardsListView(SHOW_VIEW_KEY, 'timeline')
    expect(stickyRedirectsToList(null, SHOW_VIEW_KEY)).toBe(false)
  })
})
