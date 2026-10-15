import { pathHasSegment } from './navMatch'

/**
 * The four live views, as paths — Programmer · Show · Prompt Book · Busk.
 *
 * A module of its own rather than constants in `Layout.tsx`, for the reason
 * `ownershipLegendModel.ts` is one: a component file should export only a component, or Vite's
 * React Refresh stops applying to it (CLAUDE.md §React/Frontend Conventions).
 *
 * These four are where **immersive** applies (busk-chrome plan D7): `Layout` hides the app around
 * them while the window's fact is on, and draws it everywhere else whatever the fact says. They
 * used to set the sidebar's default as well — the 64px rail here, open elsewhere, two remembered
 * preferences (space plan D7) — until venue-visit plan D4 made the sidebar one state on every
 * route; immersive is the deliberate way to hide chrome now. `ShowHeader`'s pill switcher is how
 * the four move between each other.
 *
 * Deliberately **not** derived from `navigation.ts`. A nav entry's `pathMatch` answers "which
 * sidebar row is lit", and the two questions have already diverged once — `/templates` carries
 * four Cmd+K entries sharing one `pathMatch`. Four literals that a test pins are cheaper than a
 * coupling that would make adding a nav entry silently change where immersive applies.
 */
const LIVE_VIEW_SEGMENTS = ['/programmer', '/show', '/prompt-book', '/busk'] as const

/**
 * Whether `pathname` is one of the four.
 *
 * Matching is [pathHasSegment], never `startsWith` and never a bare `includes`: `/program` (the
 * legacy redirect) must not answer for `/programmer`, and `/fx-library` must not answer for a
 * view that used to live at `/fx`. `lib/navMatch.ts` is the record of the three times this tree
 * fell into that, `liveViews.test.ts` pins the near misses, and `Layout.test.tsx` the wiring that
 * reads this answer.
 */
export function isLiveViewPath(pathname: string): boolean {
  return LIVE_VIEW_SEGMENTS.some((segment) => pathHasSegment(pathname, segment))
}
