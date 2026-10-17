// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { selectionWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { createMarqueeStore, MarqueeStoreContext, type MarqueeSnapshot } from '@/components/fixtures-list/marqueeContext'
import { resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'
import { useWindowMaskReader } from './useWindowMaskReader'

/**
 * What a sheet that takes a mask opens on (apply-model plan D3): `useWindowPressPair`'s families,
 * read once — the desk's while following; once unlinked, the programmer's marquee where the page
 * provides one and the tab's own copy elsewhere.
 */
afterEach(() => {
  selectionWs.last = null
  window.sessionStorage.clear()
  resetDeskFollowStores()
})

function withMarquee(asked: MarqueeSnapshot['press']['askedFamilies']) {
  const store = createMarqueeStore()
  store.set({ press: { targets: [], askedFamilies: asked } } as unknown as MarqueeSnapshot)
  return function MarqueeWrapper({ children }: { children: ReactNode }) {
    return <MarqueeStoreContext.Provider value={store}>{children}</MarqueeStoreContext.Provider>
  }
}

describe('useWindowMaskReader', () => {
  it('reads the desk’s mask while following, marquee or not', () => {
    selectionWs.last = { targets: [], families: ['POSITION'], source: null }
    expect(renderHook(() => useWindowMaskReader()).result.current()).toEqual(['POSITION'])
    expect(renderHook(() => useWindowMaskReader(), { wrapper: withMarquee(['COLOUR']) }).result.current()).toEqual([
      'POSITION',
    ])
  })

  it('reads every attribute before the desk has said anything', () => {
    expect(renderHook(() => useWindowMaskReader()).result.current()).toBeNull()
  })

  it('reads the marquee once unlinked on the programmer, folded to the one spelling', () => {
    selectionWs.last = { targets: [], families: ['POSITION'], source: null }
    unlinkFromDesk({ targets: [], families: ['BEAM'] })
    expect(renderHook(() => useWindowMaskReader(), { wrapper: withMarquee(['COLOUR']) }).result.current()).toEqual([
      'COLOUR',
    ])
    expect(
      renderHook(() => useWindowMaskReader(), {
        wrapper: withMarquee(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM']),
      }).result.current(),
    ).toBeNull()
  })

  it('reads the tab’s own copy once unlinked where there is no marquee — the busk view', () => {
    selectionWs.last = { targets: [], families: ['POSITION'], source: null }
    unlinkFromDesk({ targets: [], families: ['BEAM'] })
    expect(renderHook(() => useWindowMaskReader()).result.current()).toEqual(['BEAM'])
  })
})
