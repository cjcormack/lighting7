import { pathHasSegment } from './navMatch'

/**
 * The four live views, as paths — Programmer · Show · Prompt Book · Busk.
 *
 * A module of its own rather than constants in `Layout.tsx`, for the reason
 * `ownershipLegendModel.ts` is one: a component file should export only a component, or Vite's
 * React Refresh stops applying to it (CLAUDE.md §React/Frontend Conventions).
 *
 * Space plan D7: these four are where 176px of sidebar is worth less than 176px of page, so the
 * sidebar starts on its 64px rail there and open everywhere else, remembered as two separate
 * preferences. `ShowHeader`'s pill switcher is how the four move between each other anyway.
 *
 * Deliberately **not** derived from `navigation.ts`. A nav entry's `pathMatch` answers "which
 * sidebar row is lit", and the two questions have already diverged once — `/templates` carries
 * four Cmd+K entries sharing one `pathMatch`. Four literals that a test pins are cheaper than a
 * coupling that would make adding a nav entry silently change the sidebar's default.
 */
const LIVE_VIEW_SEGMENTS = ['/programmer', '/show', '/prompt-book', '/busk'] as const

/**
 * Whether `pathname` is one of the four.
 *
 * Matching is [pathHasSegment], never `startsWith` and never a bare `includes`: `/program` (the
 * legacy redirect) must not answer for `/programmer`, and `/fx-library` must not answer for a
 * view that used to live at `/fx`. `lib/navMatch.ts` is the record of the three times this tree
 * fell into that, and `useSidebarOpen.test.tsx` pins the near misses alongside the wiring that reads
 * this answer.
 */
export function isLiveViewPath(pathname: string): boolean {
  return LIVE_VIEW_SEGMENTS.some((segment) => pathHasSegment(pathname, segment))
}
