import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { DeskWindow, WindowAnnounce } from '../api/windowsApi'
import { useMemo } from 'react'
import { windowId } from '../lib/windowIdentity'
import { windowViewOf } from '../lib/windowViews'
import { VIEW_OPTION_PAGE_FOLLOWS } from '../lib/buskWindow'

/**
 * The desk's windows registry — see `api/windowsApi.ts` for the wire and its rules.
 *
 * **Where this bridge subscribes** (CLAUDE.md §"Where a WS bridge subscribes"): **form 3**, per
 * cache entry, and nothing at module scope. The registry is a *stream* — the value itself arrives
 * over WS and there is nothing to refetch — so it is `deskSelection`'s shape exactly: `queryFn`
 * seeds from the WS layer's snapshot and every later frame is `updateCachedData`. That also
 * settles the form-1-or-2 question the plan left open: `UserMenu` imports this module and sits on
 * the earliest render path, so a bare `lightingApi.x.subscribe(…)` here would need to be deferred
 * to `main.tsx`; a `queryFn` that *closes over* `lightingApi` touches it only when the first reader
 * mounts, long after every module has evaluated, and needs no `startXBridge()`.
 *
 * The rest of the family lives in a hook rather than here: the announce needs the router's
 * location and the `windows.show` handler needs its `navigate`, both of which exist only inside
 * `RouterProvider` — `components/screens/useWindowsBridge.ts`, mounted once in `Layout`.
 *
 * Not project-keyed, for the reason `deskSelection` is not: the registry is machine-scoped and a
 * window outlives a project switch.
 */

const NO_WINDOWS: DeskWindow[] = []

export const windowsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    deskWindows: build.query<DeskWindow[], void>({
      queryFn: () => ({ data: lightingApi.windows.getState() ?? NO_WINDOWS }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.windows.subscribe((windows) => {
          updateCachedData(() => windows)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useDeskWindowsQuery } = windowsApi

/** Every signed-in window, in announce order. Empty until the show is warm and this tab has heard. */
export function useDeskWindows(): DeskWindow[] {
  const { data } = useDeskWindowsQuery()
  return data ?? NO_WINDOWS
}

/**
 * This tab's row: the one whose `windowId` is ours. **First match** — a duplicated tab copies its
 * `sessionStorage`, so two rows can share the id and neither tab can tell which is which (D9
 * accepts that as cosmetic; `FU-WINDOWS-OWN-ROW-ID` is the exact fix). Null before this tab's
 * announce has been handled.
 */
export function thisWindowRow(windows: readonly DeskWindow[], id: string = windowId()): DeskWindow | null {
  return windows.find((w) => w.windowId === id) ?? null
}

export function useThisWindow(): DeskWindow | null {
  return thisWindowRow(useDeskWindows())
}

/** The row id this tab answers to, read at command time rather than through a hook. */
export function thisWindowRowId(): string | null {
  return thisWindowRow(lightingApi.windows.getState() ?? NO_WINDOWS)?.id ?? null
}

/**
 * The other open windows **paged with the desk**, by name, in announce order (desk-follow plan D7):
 * rows on the busk view whose `pageFollows` is not `'false'`, other than this window's own. It is
 * who a tab click on this window pages too, when this window is paged with the desk — what the
 * page badge names. A row that has not announced the key yet counts as paged with, the reading
 * `buskPageFollow.ts` gives its own undecided tab. A row in Rig focus is included: its page is
 * folded away, but it still pages with the group.
 */
export function coPagedWindowNames(windows: readonly DeskWindow[], id: string = windowId()): string[] {
  const me = thisWindowRow(windows, id)
  return windows
    .filter(
      (w) =>
        w !== me &&
        windowViewOf(w.view)?.id === 'busk' &&
        w.viewOptions?.[VIEW_OPTION_PAGE_FOLLOWS] !== 'false',
    )
    .map((w) => w.name)
}

/**
 * [coPagedWindowNames], live — narrowed to its answer, so a `windows.state` frame about anything
 * else (a rename elsewhere, a focus change, a full-screen flip) does not re-render the row. The
 * answer crosses `selectFromResult` as one joined string, because a fresh array would compare
 * unequal on every frame.
 */
export function useCoPagedWindowNames(): string[] {
  const { key } = useDeskWindowsQuery(undefined, {
    selectFromResult: ({ data }) => ({ key: coPagedWindowNames(data ?? NO_WINDOWS).join('\u0000') }),
  })
  return useMemo(() => (key === '' ? [] : key.split('\u0000')), [key])
}

/** Say what this window is. `windowId` is always this tab's; the caller supplies the rest. */
export function announceThisWindow(facts: Omit<WindowAnnounce, 'windowId'>): void {
  lightingApi.windows.announce({ windowId: windowId(), ...facts })
}

/** Ask another window (or this one, by its row id) to show a view. */
export function showOnWindow(targetId: string, view: string): void {
  lightingApi.windows.show(targetId, view)
}

/**
 * Rename a window by its row id. Nothing is applied here even when the target is this tab: the
 * command is rebroadcast and the target — this tab included — renames itself in its handler,
 * so one path serves both and the sheet cannot rename a row the desk will not.
 */
export function renameWindowRow(targetId: string, name: string): void {
  lightingApi.windows.rename(targetId, name)
}

/** Ask a window to enter (banner on the target) or leave (immediate) full screen. */
export function setWindowFullscreen(targetId: string, on: boolean): void {
  lightingApi.windows.fullscreen(targetId, on)
}

/**
 * Set a window's per-view options — its busk focus, sheet or page (busk-further plan D13). [view]
 * is the route the row announced, and the target applies the options only while it is still
 * showing that view. Like a rename, nothing is applied here even for this tab: the command comes
 * back rebroadcast and the target — this tab included — applies it in its handler, so the sheet
 * cannot set a fact the desk never heard of.
 */
export function setWindowViewOptions(targetId: string, view: string, options: Readonly<Record<string, string>>): void {
  lightingApi.windows.viewOptions(targetId, view, options)
}

/**
 * Link (`on`) or unlink another window's selection from the desk's — the Screens row's Selection
 * segment and ⌘K's per-window arm (desk-follow plan D4). Nothing is applied here even for this tab,
 * as for a rename: the command comes back rebroadcast and the target — this tab included — applies
 * it in its handler, refusing an unlink its focus forbids (`followIsForced`) and re-announcing
 * either way, so the row corrects itself.
 */
export function setWindowFollow(targetId: string, on: boolean): void {
  lightingApi.windows.follow(targetId, on)
}
