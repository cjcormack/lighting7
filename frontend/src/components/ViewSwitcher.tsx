import { type ReactNode } from 'react'
import {
  AudioWaveform,
  BookOpenText,
  LayoutGrid,
  SlidersVertical,
  TableProperties,
  Theater,
} from 'lucide-react'
import { Link } from 'react-router'
import { cn } from '@/lib/utils'
import { parseFamilySlug, type AttributeFamily } from '@/lib/attributeFamily'

export type ShowView = 'programmer' | 'show' | 'prompt-book' | 'busk'

const ITEM = 'inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold'

/**
 * When a segment's text label appears, as a **container** query.
 *
 * Every switcher in this file therefore REQUIRES a `@container` ancestor — see `Breadcrumbs.tsx`
 * for the same contract. An unnamed `@[NNpx]:` with no container above it never matches, so a host
 * that forgets it loses its labels permanently and silently, and no test can see that. The hosts
 * are `ShowHeader`, `Fixtures`, `FixturesList`, `Groups` and `GroupsList`. (The template library's
 * family filter lived here too, with a five-pill threshold; it is `PartitionChips` now, in
 * `sheet/LibraryRow.tsx`, and folds to a select on its own container.)
 *
 * These used to be one viewport `sm:`, which was wrong twice over: the app sidebar insets the
 * content region, so viewport width is not the width these sit in; and one number cannot serve a
 * two-pill switcher and a four-pill one. Written out per switcher rather than computed, because a
 * template literal produces no CSS — the scanner only reads whole class strings.
 */
const LABEL_AT_560 = 'hidden @[560px]:inline' // Cards · List / Cards · Table — two pills
/**
 * Four pills in a header that also carries a breadcrumb trail (~220-320px above 640), the save
 * indicator, per-page actions, Start/Stop and the live dot: roughly 570px is spoken for before any
 * label, and four labels add ~230.
 *
 * This number has moved twice, in both directions, and both moves were the same rule: **the
 * threshold tracks the pill count.** It was `@[820px]` for four when Run was its own view, dropped
 * to `@[760px]` when session 2b merged Run into Show and left three, and is back at `@[820px]` now
 * that Busk is the fourth. Written out in full because a template literal produces no CSS — the
 * scanner only reads whole class strings — which is also why this cannot be computed from the
 * count.
 */
const LABEL_AT_820 = 'hidden @[820px]:inline' // Programmer · Show · Prompt Book · Busk

/**
 * Programmer · Show · Prompt Book · Busk switcher, shared across the four live views. Run used to
 * sit between Show and the Prompt Book; session 2b folded it into Show, because the two were one
 * destination in two modes rather than two destinations. The `current`
 * view renders as a static pill; the others are links. Icons show at every width — only the text
 * labels collapse, at `LABEL_AT_820` — so the switch stays usable on phones (the sole in-view way
 * to move between the views on a narrow screen).
 *
 * Programmer comes first because it is where values are edited and Show is where they are arranged:
 * the pills run in the order the work does. Busk comes last because it is the one you stand at
 * once the other three are done — and it is here at all because a live view reachable only from
 * the sidebar would be the one destination these pills could not get you to.
 *
 * This order is the sidebar's too: the `live` group in `navigation.ts` follows it, and
 * `navigation.test.ts` pins the ids against this list. Each pill also carries the **same icon** as
 * its sidebar row — Show's was `Pencil` here and `Theater` there, which made one destination read
 * as two — so an icon change belongs in both files or neither.
 */
export function ViewSwitcher({ current, projectId }: { current: ShowView; projectId: number }) {
  return (
    <nav className="inline-flex items-center gap-0.5 rounded-lg border bg-card p-0.5">
      <Segment
        labelClass={LABEL_AT_820}
        active={current === 'programmer'}
        to={`/projects/${projectId}/programmer`}
        icon={<SlidersVertical className="size-3.5" />}
        label="Programmer"
      />
      <Segment
        labelClass={LABEL_AT_820}
        active={current === 'show'}
        to={`/projects/${projectId}/show`}
        icon={<Theater className="size-3.5" />}
        label="Show"
      />
      <Segment
        labelClass={LABEL_AT_820}
        active={current === 'prompt-book'}
        to={`/projects/${projectId}/prompt-book`}
        icon={<BookOpenText className="size-3.5" />}
        label="Prompt Book"
      />
      <Segment
        labelClass={LABEL_AT_820}
        active={current === 'busk'}
        to={`/projects/${projectId}/busk`}
        icon={<AudioWaveform className="size-3.5" />}
        label="Busk"
      />
    </nav>
  )
}

export type CardsListView = 'cards' | 'list'

/**
 * localStorage keys remembering which view (cards or list) each page last used, so its single
 * sidebar entry lands on the one you left.
 *
 * The stored vocabulary is `'cards' | 'list'` for all four pairs, whatever the second segment is
 * *called*: Fixtures and Groups say Cards · List, Channels and Show say Cards · Table (their second
 * view is a table and their first genuinely is cards — the sheet-views design's own word). One
 * storage value for one meaning, "the sheet rather than the cards", so `getStoredCardsListView`
 * answers every pair and a rename of the label can never reset a desk's remembered view.
 */
export const FIXTURES_VIEW_KEY = 'fixtures.view'
export const GROUPS_VIEW_KEY = 'groups.view'
export const CHANNELS_VIEW_KEY = 'channels.view'
export const SHOW_VIEW_KEY = 'show.view'

export function setStoredCardsListView(key: string, view: CardsListView) {
  try {
    localStorage.setItem(key, JSON.stringify(view))
  } catch {
    // Storage unavailable (private mode, quota) — stickiness just degrades.
  }
}

/** Read-only counterpart for the cards routes' sticky redirect. Deliberately
 *  not usePersistentState: that hook writes the value back on every mount,
 *  and the redirect only needs a one-shot read. */
export function getStoredCardsListView(key: string): CardsListView {
  try {
    return JSON.parse(localStorage.getItem(key) ?? '"cards"') === 'list' ? 'list' : 'cards'
  } catch {
    return 'cards'
  }
}

/**
 * Whether a cards route should hand off to its sheet sibling on arrival: the sticky says the sheet,
 * and this arrival is not the switcher's own Cards click (which carries [CARDS_LINK_STATE]).
 *
 * The one decision behind four redirects — Fixtures, Groups, Channels, Show — so the pairs cannot
 * drift; `ViewSwitcher.test.ts` pins it for both halves.
 */
export function stickyRedirectsToList(locationState: unknown, storageKey: string): boolean {
  return !isCardsLinkState(locationState) && getStoredCardsListView(storageKey) === 'list'
}

/** Location state the Cards segment attaches to its navigation. The cards
 *  routes skip their sticky redirect when they see it, so reaching Cards
 *  never depends on the localStorage write above having succeeded (writes
 *  can fail on quota-exhausted storage while reads keep returning 'list'). */
export const CARDS_LINK_STATE = { stickyView: 'cards' } as const

export function isCardsLinkState(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { stickyView?: unknown }).stickyView === 'cards'
  )
}

/**
 * Cards · List switcher shared by a card-grid route and its list sibling.
 * Each segment records the choice BEFORE navigating — the cards route
 * redirects to the list when the stored view is 'list', so the Cards click
 * must overwrite the preference first or it could never get back.
 */
function CardsListSwitcher({
  current,
  cardsTo,
  listTo,
  storageKey,
  listLabel = 'List',
}: {
  current: CardsListView
  cardsTo: string
  listTo: string
  storageKey: string
  /** What the second segment is called — `List` on Fixtures and Groups, `Table` on Channels and Show. */
  listLabel?: string
}) {
  return (
    <nav className="inline-flex items-center gap-0.5 rounded-lg border bg-card p-0.5">
      <Segment
        active={current === 'cards'}
        to={cardsTo}
        icon={<LayoutGrid className="size-3.5" />}
        label="Cards"
        state={CARDS_LINK_STATE}
        onClick={() => setStoredCardsListView(storageKey, 'cards')}
      />
      <Segment
        active={current === 'list'}
        to={listTo}
        icon={<TableProperties className="size-3.5" />}
        label={listLabel}
        onClick={() => setStoredCardsListView(storageKey, 'list')}
      />
    </nav>
  )
}

/** Fixture cards (`/fixtures`) vs the flat spreadsheet list (`/fixtures/list`). */
export function FixturesViewSwitcher({
  current,
  projectId,
}: {
  current: CardsListView
  projectId: number
}) {
  return (
    <CardsListSwitcher
      current={current}
      cardsTo={`/projects/${projectId}/fixtures`}
      listTo={`/projects/${projectId}/fixtures/list`}
      storageKey={FIXTURES_VIEW_KEY}
    />
  )
}

/** Group cards (`/groups`) vs the grouped spreadsheet list (`/groups/list`). */
export function GroupsViewSwitcher({
  current,
  projectId,
}: {
  current: CardsListView
  projectId: number
}) {
  return (
    <CardsListSwitcher
      current={current}
      cardsTo={`/projects/${projectId}/groups`}
      listTo={`/projects/${projectId}/groups/list`}
      storageKey={GROUPS_VIEW_KEY}
    />
  )
}

/** Channel cards (`/channels/:u`) vs the 16-wide DMX sheet (`/channels/:u/table`). */
export function ChannelsViewSwitcher({
  current,
  projectId,
  universe,
}: {
  current: CardsListView
  projectId: number
  universe: number
}) {
  return (
    <CardsListSwitcher
      current={current}
      cardsTo={`/projects/${projectId}/channels/${universe}`}
      listTo={`/projects/${projectId}/channels/${universe}/table`}
      storageKey={CHANNELS_VIEW_KEY}
      listLabel="Table"
    />
  )
}

/** A stack's cue cards (`/show/stacks/:id`) vs its cue sheet (`/show/stacks/:id/table`). */
export function ShowStackViewSwitcher({
  current,
  projectId,
  stackId,
}: {
  current: CardsListView
  projectId: number
  stackId: number
}) {
  return (
    <CardsListSwitcher
      current={current}
      cardsTo={`/projects/${projectId}/show/stacks/${stackId}`}
      listTo={`/projects/${projectId}/show/stacks/${stackId}/table`}
      storageKey={SHOW_VIEW_KEY}
      listLabel="Table"
    />
  )
}

/**
 * localStorage key remembering which attribute family the **template** library was last filtered to,
 * so the sidebar's single "Templates" row lands where you left it.
 *
 * The value is still `looks.family`, deliberately: it is a private storage key, nobody reads it by
 * name, and renaming it would silently reset every desk's remembered filter for no gain.
 */
export const LOOK_FAMILY_KEY = 'looks.family'

/**
 * The library's filter value. `'ALL'` is a first-class choice rather than "no filter" — an operator
 * building a state wants to see every named value they have, not one attribute's worth.
 */
/**
 * The family filter's value. Named for the family rather than for a library, because the same shape
 * now serves the **template** library — where a family is an exact partition, which is the case this
 * control was always the right shape for.
 */
export type LookFamilyFilter = AttributeFamily | 'ALL'

export function setStoredLookFamily(family: LookFamilyFilter) {
  try {
    localStorage.setItem(LOOK_FAMILY_KEY, JSON.stringify(family))
  } catch {
    // Storage unavailable (private mode, quota) — stickiness just degrades.
  }
}

/**
 * Read-only counterpart, for the library's initial state. Deliberately not `usePersistentState`,
 * for the same reason as [getStoredCardsListView]: this is a one-shot read, and a hook that wrote
 * back on mount would make the *arrival* the choice.
 *
 * Defaults to ALL. The palette banks defaulted to COLOUR because there was no "all" to default to;
 * there is now, and a library you cannot see the whole of is a worse first impression than a long
 * list.
 */
export function getStoredLookFamily(): LookFamilyFilter {
  try {
    const raw = String(JSON.parse(localStorage.getItem(LOOK_FAMILY_KEY) ?? '""'))
    if (raw.toUpperCase() === 'ALL') return 'ALL'
    return parseFamilySlug(raw.toLowerCase()) ?? 'ALL'
  } catch {
    return 'ALL'
  }
}

function Segment({
  active,
  to,
  icon,
  label,
  state,
  onClick,
  labelClass = LABEL_AT_560,
}: {
  active: boolean
  to: string
  icon: ReactNode
  label: string
  state?: unknown
  onClick?: () => void
  labelClass?: string
}) {
  if (active) {
    return (
      <span className={cn(ITEM, 'bg-muted text-foreground')} aria-current="page" aria-label={label}>
        {icon}
        <span className={labelClass}>{label}</span>
      </span>
    )
  }
  return (
    <Link
      to={to}
      state={state}
      aria-label={label}
      onClick={onClick}
      className={cn(ITEM, 'text-muted-foreground hover:text-foreground')}
    >
      {icon}
      <span className={labelClass}>{label}</span>
    </Link>
  )
}
