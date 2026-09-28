import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **How a docked side panel sits on the page: beside the content, or over it.**
 *
 * The programmer's rail and the busk view's side sheet are one instrument in two views
 * (`components/sheet/sidePanel.ts`), and until this store the answer was the *width*'s alone: the
 * rail pushed at 1200px of workspace and floated below it, the sheet pushed on the desk board and
 * floated off it. That is a sensible default and a poor rule — on a 1400px screen an operator
 * busking a colour wants the page at full width with the sheet over it, and on the same screen an
 * operator building a page wants the two side by side. So the width still decides where pushing is
 * *possible*, and this decides whether to.
 *
 * **One fact for both panels, not one each.** They are deliberately the same instrument, and a
 * desk where the rail floats while the sheet pushes is two answers to one question. If the two
 * ever need to differ, this is the module to split — not the surfaces.
 *
 * **Per tab, in `sessionStorage`**, like every other fact about how *this window* is arranged
 * (`lib/buskWindow.ts`, `lib/buskPageFollow.ts`): `localStorage` is one value per origin per
 * profile, and the two desk screens are two windows of one profile, so a panel mode kept there
 * would be one mode for both screens — which is the opposite of what a second screen is for.
 *
 * Unlike `buskWindow.ts`'s stores this one does **not** rest at `null`. There is nothing for a
 * surface to resolve a null against: `push` *is* the behaviour both panels had, and each surface
 * already refuses to push at a width where pushing would not fit — the rail below 1200px of
 * workspace, the sheet off the desk board. So the mode can only ever make a panel float where it
 * would otherwise have docked, never dock where there is no room.
 */
export type SidePanelMode = 'push' | 'overlay'

const MODE_KEY = 'desk.sidePanel.mode'

const modeStore = createSyncStore<SidePanelMode>({
  key: MODE_KEY,
  fallback: 'push',
  parse: (parsed) => (parsed === 'overlay' ? 'overlay' : 'push'),
  storage: sessionStorageArea,
})

export function useSidePanelMode(): SidePanelMode {
  return useSyncExternalStore(modeStore.subscribe, modeStore.getSnapshot, modeStore.getServerSnapshot)
}

export function getSidePanelMode(): SidePanelMode {
  return modeStore.getSnapshot()
}

export function setSidePanelMode(mode: SidePanelMode): void {
  modeStore.set(mode)
}

/** The header button's press: the two modes are a pair, so there is one control, not two. */
export function toggleSidePanelMode(): void {
  modeStore.set(modeStore.getSnapshot() === 'push' ? 'overlay' : 'push')
}

/** Test seam, as `resetBuskWindowStores` is — a module-level cache outlives `sessionStorage.clear()`. */
export function resetSidePanelModeStore(): void {
  modeStore.reset()
}
