import { pathHasSegment } from './navMatch'
import { BUSK_FOCUSES, LIVE_SHEET_TABS, VIEW_OPTION_PAGE_FOLLOWS } from './buskWindow'
import { IMMERSIVE_VALUES, VIEW_OPTION_IMMERSIVE, type Immersive } from './immersive'

/**
 * The views one window can put on another (multi-screen plan §4, `Screens.dc.html` §2): the four
 * live views and the two libraries. A `windows.show` carries a **route path**, so this is the
 * vocabulary that turns a picker's choice into one and a row's `view` back into a label.
 *
 * Six literals rather than a read of `navigation.ts`, for `lib/liveViews.ts`'s reason: a nav
 * entry's `pathMatch` answers "which sidebar row is lit" and has already diverged from "what is
 * this view called" once (`/templates` carries four ⌘K entries on one `pathMatch`). The order is
 * `ViewSwitcher`'s for the four, then the two libraries.
 *
 * Matching is [pathHasSegment], never `startsWith`: `/programmer` must not answer for `/program`
 * (the legacy redirect) and `/fx-library` must not answer for `/busk`'s old `/fx`.
 */
export interface WindowView {
  id: string
  label: string
  /** The trailing route segment, `/busk`. */
  segment: string
  /**
   * The per-view options a window on this view announces and the Screens sheet can set
   * (busk-further plan D13). Absent for a view that contributes none — the two libraries — so the
   * sheet renders whatever a row's *current* view contributes and never learns the word busk. The
   * write is one generic command, `windows.viewOptions {targetId, view, options}`, and the values
   * ride the announce as a free string map. Every live view carries [IMMERSIVE_OPTION]
   * (busk-chrome plan D9): immersive is a window's fact, but it rides *here* because a top-level
   * announce key would drop the frame, and it is drawn on the row as the view's Chrome segment.
   */
  options?: readonly WindowViewOption[]
}

/**
 * One row control. An `enum` draws a segmented control over `values` — labelled by `valueLabels`
 * where a value is not its own label — and a `page` draws a picker over the target project's busk
 * pages, resolved against the fetched list. Both are things a remote set cannot lose anything by
 * (D13's test): what the operator at that window *built* is never on the row, only how the window
 * shows it.
 */
export type WindowViewOption =
  | {
      key: string
      label: string
      kind: 'enum'
      values: readonly string[]
      /** A label per value, for an enum whose wire spelling is not what the row should say. */
      valueLabels?: Readonly<Record<string, string>>
      /**
       * A shorter label per value, drawn instead of [valueLabels] on a narrow row (the Screens
       * sheet's rung). The accessible name stays the long one at every width.
       */
      shortValueLabels?: Readonly<Record<string, string>>
      /** The segment's accessible name where [label] would collide with another control's. */
      name?: string
    }
  | { key: string; label: string; kind: 'page' }

/**
 * *Page · Paged with the desk | Own page* (desk-follow plan D6, D9): whether that window pages with
 * the desk's paging group — the MIDI Next · Prev · Set and every window in the group — or shows a
 * page of its own. The wire is the announce's own `'true'` | `'false'`, which the target applies
 * (`applyBuskViewOptions`); the row says what the window does, *With desk* · *Own* where it is
 * narrow. It sits immediately before the page picker, whose own label it stands in for on the row,
 * so its accessible name is *Paging* — two controls called *Page on Screen 2* would be one too many.
 */
export const PAGE_FOLLOWS_OPTION: WindowViewOption = {
  key: VIEW_OPTION_PAGE_FOLLOWS,
  label: 'Page',
  name: 'Paging',
  kind: 'enum',
  values: ['true', 'false'],
  valueLabels: { true: 'Paged with the desk', false: 'Own page' },
  shortValueLabels: { true: 'With desk', false: 'Own' },
}

/** Sheet as one enum with `none` (D7), offering only the tabs that have landed. */
const BUSK_SHEET_VALUES: readonly string[] = ['none', ...LIVE_SHEET_TABS]

/**
 * The Chrome segment, *App · Immersive*, on every live view (busk-chrome plan D9): the wire says
 * `off` | `on`, the row says what the window looks like. One object, shared by the four entries,
 * so the segment cannot read differently on a Show row and a Busk row.
 */
export const IMMERSIVE_OPTION: WindowViewOption = {
  key: VIEW_OPTION_IMMERSIVE,
  label: 'Chrome',
  kind: 'enum',
  values: IMMERSIVE_VALUES,
  valueLabels: { off: 'App', on: 'Immersive' },
}

export const WINDOW_VIEWS: readonly WindowView[] = [
  { id: 'programmer', label: 'Programmer', segment: '/programmer', options: [IMMERSIVE_OPTION] },
  { id: 'show', label: 'Show', segment: '/show', options: [IMMERSIVE_OPTION] },
  { id: 'prompt-book', label: 'Prompt Book', segment: '/prompt-book', options: [IMMERSIVE_OPTION] },
  {
    id: 'busk',
    label: 'Busk',
    segment: '/busk',
    options: [
      { key: 'focus', label: 'Focus', kind: 'enum', values: BUSK_FOCUSES },
      { key: 'sheet', label: 'Sheet', kind: 'enum', values: BUSK_SHEET_VALUES },
      PAGE_FOLLOWS_OPTION,
      { key: 'page', label: 'Page', kind: 'page' },
      IMMERSIVE_OPTION,
    ],
  },
  { id: 'looks', label: 'Looks', segment: '/looks' },
  { id: 'templates', label: 'Templates', segment: '/templates' },
]

/**
 * What this window announces as `viewOptions` for [view] (busk-chrome plan §3.2): the busk facts
 * on the busk view, and `immersive` under every live view; nothing at all — the key absent, not
 * an empty map — for a view that contributes none, so a window on a library still sends the
 * five-key frame it always did. Pure, so `windowsApi.test.ts` can pin which views carry the key
 * without a router.
 */
export function announcedViewOptions(
  view: WindowView | null,
  buskOptions: Readonly<Record<string, string>>,
  immersive: Immersive,
): Record<string, string> | undefined {
  if (view?.options == null) return undefined
  const options: Record<string, string> = view.id === 'busk' ? { ...buskOptions } : {}
  if (view.options.some((option) => option.key === VIEW_OPTION_IMMERSIVE)) options[VIEW_OPTION_IMMERSIVE] = immersive
  return options
}

/** The route for [view] in [projectId]. */
export function windowViewPath(view: WindowView, projectId: number): string {
  return `/projects/${projectId}${view.segment}`
}

/**
 * Which of the six a route path is showing, or null for any other page. Longest segment wins so
 * a future `/show/…` sibling still reads as Show, not as whatever shorter segment it also ends in.
 */
export function windowViewOf(pathname: string): WindowView | null {
  let best: WindowView | null = null
  for (const view of WINDOW_VIEWS) {
    if (!pathHasSegment(pathname, view.segment)) continue
    if (best == null || view.segment.length > best.segment.length) best = view
  }
  return best
}

/** A label for any route: one of the six by name, else the path itself. */
export function windowViewLabel(pathname: string): string {
  return windowViewOf(pathname)?.label ?? pathname
}

/** The project a route path names, or null for an install-scope page. */
export function projectIdOfPath(pathname: string): number | null {
  const match = /^\/projects\/(\d+)(?:\/|$)/.exec(pathname)
  if (match == null) return null
  const id = Number(match[1])
  return Number.isSafeInteger(id) ? id : null
}
