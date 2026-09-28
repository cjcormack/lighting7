import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { sameSelectionSnapshot, type DeskSelectionSnapshot, type SubselectMode } from '../api/selectionApi'
import type { CueTarget } from '../api/cuesApi'
import type { AttributeFamily } from '../lib/attributeFamily'
import { normaliseFamilies } from '../lib/selectionMask'
import { unlinkFromDesk, useDeskFollow, useLocalSelection } from '../lib/deskFollow'

/**
 * The desk selection — see `api/selectionApi.ts` for what it is and why it is server-owned.
 *
 * A cache entry rather than a hook's `useState`, following `speedMasterLive` and the four surface
 * streams: two components reading one stream then share a subscription and RTK Query owns the
 * teardown. There is no REST endpoint behind it and nothing to invalidate — the frame is the only
 * source — so `queryFn` seeds from the WS layer's own snapshot and everything after arrives by
 * push.
 *
 * The project is not part of the key. The backend keeps one selection and clears it on project
 * switch, so a per-project cache entry would be a second, disagreeing answer to which selection is
 * current.
 *
 * **The entry is written only when the snapshot actually changed.** A `selection.state` frame
 * arrives for a source-only move now — another window setting the same heads — and the desk
 * itself skips a no-op `update`, so a frame that changes nothing this side can see must not churn
 * every reader either. `decodeSelectionState` already keeps the untouched parts' identities; this
 * is the last step, for a frame identical in all three.
 */

/** What the selection is before its first frame, and after a clear — one identity, every reader. */
const NO_TARGETS: CueTarget[] = []
const EMPTY: DeskSelectionSnapshot = { targets: NO_TARGETS, families: null, source: null }

export const selectionApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    deskSelection: build.query<DeskSelectionSnapshot, void>({
      queryFn: () => ({ data: lightingApi.selection.getState() ?? EMPTY }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.selection.subscribe((snapshot) => {
          updateCachedData((draft) => (sameSelectionSnapshot(draft, snapshot) ? undefined : snapshot))
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useDeskSelectionQuery } = selectionApi

/** The whole fact — targets, mask and mover. The desk chip's reader. */
export function useDeskSelectionSnapshot(): DeskSelectionSnapshot {
  const { data } = useDeskSelectionQuery()
  return data ?? EMPTY
}

/** The desk selection, in the order targets were added. */
export function useDeskSelection(): CueTarget[] {
  return useDeskSelectionSnapshot().targets
}

/**
 * The attribute mask a press from this tab should carry (multi-screen plan D4): the **desk's**
 * while following, and this tab's own otherwise. [local] is what the surface itself would say —
 * the programmer's marquee families, the busk view's unlinked copy — and is the answer only when
 * the tab is unlinked, because a following tab's press acts on the desk's selection and must be
 * masked as the desk is, even in the moment another window has moved the mask under it.
 */
export function usePressFamilies(local: readonly AttributeFamily[] | null): AttributeFamily[] | null {
  const following = useDeskFollow()
  const desk = useDeskSelectionSnapshot()
  return following ? desk.families : normaliseFamilies(local)
}

/** The busk view's selection pair: the desk's when following, the tab's own when not (D8). */
export function useSelectionPair(): { targets: CueTarget[]; families: AttributeFamily[] | null } {
  const following = useDeskFollow()
  const desk = useDeskSelectionSnapshot()
  const local = useLocalSelection()
  return following ? desk : local
}

/**
 * The three writes, as plain functions rather than hooks.
 *
 * There is nothing to subscribe to and no cache to patch optimistically: `selection.state` is the
 * acknowledgement, so a write is a socket send and the frame that comes back is what moves every
 * reader. A `useX` wrapper would only be a stable identity around `lightingApi`, which is already
 * a module singleton.
 *
 * No write names this window any more. The desk stamps the selection's `source` from the window
 * this socket **announced** (`api/windowsApi.ts`, re-sent on every connect), which is what the chip
 * on another screen reads — and it carries the row id as well as the name, so the chip can tell
 * "this window" from a twin with the same name.
 *
 * [setDeskSelection] replaces the **whole** fact: the heads and the mask, so a replace with no
 * families — the narrow-width picker's one-thing press — clears the mask, as D2 says it must.
 * [toggleDeskSelection] keeps the mask, and is **not** "add if absent, remove if present". The
 * desk narrows a partly covered group head by head (through `fx/TargetCoverage`), so pressing a
 * fixture that a selected group already covers takes that one head *out of the group's coverage*
 * rather than adding a duplicate entry. That is the behaviour a select button on the surface has,
 * and the reason the busk band's toggle goes through here rather than keeping its own Map.
 */
export function setDeskSelection(
  targets: readonly CueTarget[],
  families: readonly AttributeFamily[] | null = null,
): void {
  lightingApi.selection.set([...targets], families)
}

export function toggleDeskSelection(target: CueTarget): void {
  lightingApi.selection.toggle(target)
}

export function clearDeskSelection(): void {
  lightingApi.selection.clear()
}

/**
 * The fourth write (busk-further plan D12): rewrite the desk selection's **targets** by [mode] —
 * the Cells chip's press while this tab follows the desk. The desk walks its own rig order and
 * answers with the ordinary state frame; nothing here derives the result, because a sub-selection
 * is not a state the desk keeps and the rule lives in `state/DeskSelection.kt`. An unlinked tab
 * never calls this — `useBuskingSelection`'s local arm mirrors the rule over the rig document.
 */
export function subselectDeskSelection(mode: SubselectMode): void {
  lightingApi.selection.subselect(mode)
}

/**
 * Unlink this tab from the desk's selection, taking the desk's fact **as it stands now** as the
 * tab's own — ⌘K's *Stop following…* and a `windows.follow {on: false}` alike. Read at call time
 * rather than subscribed: both callers act once, and a `selection.state` subscription in either
 * would re-render it on every marquee frame for a value only this one call reads.
 */
export function unlinkFromDeskNow(): void {
  const desk = lightingApi.selection.getState()
  unlinkFromDesk({ targets: desk?.targets ?? [], families: desk?.families ?? null })
}
