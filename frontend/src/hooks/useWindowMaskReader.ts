import { useCallback, useContext } from 'react'
import { lightingApi } from '@/api/lightingApi'
import { MarqueeStoreContext } from '@/components/fixtures-list/marqueeContext'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { getLocalSelection, isFollowingDesk } from '@/lib/deskFollow'
import { normaliseFamilies } from '@/lib/selectionMask'

/**
 * Read **this window's selection mask once** — what a sheet that takes a mask opens on (apply-model
 * plan D3): Record, Record Look, Include, Make layer and Update. `null` is every attribute.
 *
 * The same answer as `useWindowPressPair`'s `families` — the desk's while this tab follows it; once
 * unlinked, the programmer's marquee where there is one and the tab's own copy elsewhere — but read
 * at call time rather than subscribed. A sheet takes the mask once, on open, and then the mask is
 * the sheet's own (a change there never moves the selection, and a selection moved while it is open
 * never moves the sheet's), so a subscription would only re-render a mounted-but-closed sheet on
 * every marquee frame for a value nothing reads.
 *
 * The marquee is found through `MarqueeStoreContext`, which `ProgrammerPage` provides above its
 * sheets; elsewhere there is none and the tab's own copy is the unlinked answer.
 */
export function useWindowMaskReader(): () => AttributeFamily[] | null {
  const marqueeStore = useContext(MarqueeStoreContext)
  return useCallback(() => {
    if (isFollowingDesk()) return lightingApi.selection.getState()?.families ?? null
    const marquee = marqueeStore?.get()
    return marquee != null ? normaliseFamilies(marquee.press.askedFamilies) : getLocalSelection().families
  }, [marqueeStore])
}
