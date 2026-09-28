import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'
import { relinkBuskPage, showingBuskPage, unlinkBuskPage, useBuskPageFollow, useLocalBuskPage } from './buskPageFollow'

/**
 * **The busk view's per-window facts** — the shape one window gives the view (busk-further plan
 * §3.4, D5–D7): which of the three shapes it is in (`busk.focus`), how tall the split draws the rig
 * region (`busk.rigHeight`, px), and which side-sheet tab is open (`busk.sheet`, where `none` is
 * the fold).
 *
 * All three sit on `lib/buskPageFollow.ts`'s model and **beside** it, never inside it: per-tab
 * `sessionStorage` through `createSyncStore` — `localStorage` is one value per origin per profile
 * and the two desk screens are two windows of one profile — and never the desk's, because two
 * screens at one desk showing two shapes of one view is the whole reason focus exists. Nothing
 * here reads the selection's follow flag or the page's, and nothing there reads these.
 *
 * **A default is only a default.** Each store rests at `null`, meaning *this window has not
 * chosen*, and the reader resolves it against the surface: `focus` defaults to `pads` on a short
 * viewport and `split` otherwise; `sheet` defaults to `speed` where docking the 288px rail leaves
 * the page body its 600px, and to `none` where it would stack the page (iPad portrait) or the
 * viewport is short; `rigHeight` rests at `null`, which the band draws as the first
 * [defaultBuskRigRows] whole lines — three on a desk screen and two where the viewport is cramped
 * or narrower than `lg` — measured once the lines are on screen. Once the window has chosen, the
 * tab fact wins whatever the surface says, and it survives a reload of that tab.
 *
 * **The rig height is pixels, not a line count** (2026-09-22; it was `busk.rigRows`, 1…N whole
 * lines). A count could only ever shrink the *rig*: the page took whatever the lines left, so the
 * one way to give the page less room was a whole extra line, and the bar could not rest between
 * two lines. A height can: the rig region is a scroller under a bar the operator puts anywhere,
 * snapping to a line's bottom while it is dragged and resting between lines otherwise, and past
 * the last line the region simply has room — which is how the page gets smaller. It is **this
 * window's** fact and means nothing on another screen, so unlike the count it is not announced
 * (`useBuskViewOptions`); the Screens sheet never drew a row control for either. The band clamps
 * it on read — a stored height taller than the body leaves the page its minimum — without
 * rewriting the wish, so a window that shrinks and grows back shows what it asked for.
 *
 * **The short and cramped queries are copies**, by the convention `shortViewport.test.ts`
 * enforces: every site that folds at 500px of height says so with a media query of its own, and
 * that test pins each copy's spelling. The width half of the sheet default is Tailwind's `lg`,
 * 1024 — the app's own breakpoint that partitions the `Tablets` ladder exactly (iPad portrait
 * below it, iPad landscape and every desk screen above), chosen over measuring the body because
 * the default has to be answerable off the busk view: the windows announce carries these values
 * from `Layout`, where there is no body to measure.
 *
 * **`?focus=` and `?sheet=` are latched once per tab**, on `buskPageFollow.ts`'s model: the raw
 * strings are read at mount by `BuskingView`, applied through [applyBuskArrival], and mirrored
 * back with `replace` on every change — so the view's own address always carries both. A reload is
 * therefore never an arrival: the mirror would otherwise be read back as a deliberate statement
 * on every refresh. [isBuskWindowDecided] is the tri-state that tells a fresh window from a
 * reloaded one, and **both arms write it** — a window arriving with neither parameter records that
 * it has decided too.
 *
 * **The sheet is one fact.** `none` is the fold; there is no `sheetOpen` beside it (D7, whatever
 * `Focus.dc.html`'s older sketch lists). What *is* kept beside it is the last tab that was open,
 * which is a memory rather than a flag: it exists only so a `{sheet: 'toggle'}` from a MIDI
 * button can flip the fold back onto the tab the operator had, rather than onto Speed regardless.
 */

export type BuskFocus = 'split' | 'pads' | 'rig'
export type BuskSheet = 'none' | 'speed' | 'colour' | 'spread' | 'show'
/** A tab the sheet can open onto — every sheet value but the fold. */
export type BuskSheetTab = Exclude<BuskSheet, 'none'>

export const BUSK_FOCUSES: readonly BuskFocus[] = ['split', 'pads', 'rig']
export const BUSK_SHEETS: readonly BuskSheet[] = ['none', 'speed', 'colour', 'spread', 'show']

/**
 * The tabs that have landed — all four, since the busk-chrome plan's session A lit Show, the
 * phone runner in the sheet. The list stays the one gate rather than collapsing into
 * [BUSK_SHEETS]: Show landed exactly the way Colour and Spread did, and a fifth tab would too —
 * hidden here until its session, because a tab with an empty state is a promise the desk cannot
 * keep, and a fact naming a hidden tab draws the fold. Adding a tab here lights it in the sheet's
 * strip, the fold's glyph row, the Screens sheet's Sheet segment and the toggle's memory.
 */
export const LIVE_SHEET_TABS: readonly BuskSheetTab[] = ['speed', 'colour', 'spread', 'show']

export const BUSK_FOCUS_KEY = 'busk.focus'
export const BUSK_RIG_HEIGHT_KEY = 'busk.rigHeight'
export const BUSK_SHEET_KEY = 'busk.sheet'
export const BUSK_LAST_SHEET_KEY = 'busk.lastSheet'
export const BUSK_WINDOW_DECIDED_KEY = 'busk.windowDecided'

/** The three lines a desk screen shows in Split before the operator moves the handle. */
export const DESK_RIG_ROWS = 3
/** Two lines where the viewport is cramped or narrower than `lg` — the tablet boards. */
export const TABLET_RIG_ROWS = 2

// The two height folds, duplicated per site by convention (see the module comment); the width
// half of the sheet default is `lg`.
const SHORT_VIEWPORT = '(max-height: 500px)'
const CRAMPED_VIEWPORT = '(max-height: 750px)'
const SHEET_DOCKS = '(min-width: 1024px)'
// Tailwind's `md`: below it the rail is never drawn and the sheet is an overlay (`BuskingView`'s
// narrow board); a short viewport at or above it is the short board, an overlay too.
const RAIL_DRAWN = '(min-width: 768px)'

/**
 * The tab an overlay unfolds onto when nothing but Speed has been open: the overlay's tab list
 * strips Speed (D7 — it was the ShowBar's chip there, and since the busk-chrome plan's session A it
 * is the Show tab's strip's, `SpeedMastersChip`), so a toggle that remembered only Speed would open
 * a sheet that finds no tab — fold to fold, the dead door the memory exists to prevent.
 */
const OVERLAY_SHEET_TAB: BuskSheetTab = 'colour'

export function isBuskFocus(value: unknown): value is BuskFocus {
  return typeof value === 'string' && (BUSK_FOCUSES as readonly string[]).includes(value)
}

export function isBuskSheet(value: unknown): value is BuskSheet {
  return typeof value === 'string' && (BUSK_SHEETS as readonly string[]).includes(value)
}

const focusStore = createSyncStore<BuskFocus | null>({
  key: BUSK_FOCUS_KEY,
  fallback: null,
  parse: (parsed) => (isBuskFocus(parsed) ? parsed : null),
  storage: sessionStorageArea,
})

const rigHeightStore = createSyncStore<number | null>({
  key: BUSK_RIG_HEIGHT_KEY,
  fallback: null,
  parse: (parsed) => (typeof parsed === 'number' && Number.isFinite(parsed) && parsed > 0 ? parsed : null),
  storage: sessionStorageArea,
})

const sheetStore = createSyncStore<BuskSheet | null>({
  key: BUSK_SHEET_KEY,
  fallback: null,
  parse: (parsed) => (isBuskSheet(parsed) ? parsed : null),
  storage: sessionStorageArea,
})

/** A tab the sheet can actually draw today — one of [LIVE_SHEET_TABS]. */
export function isLiveSheetTab(value: unknown): value is BuskSheetTab {
  return typeof value === 'string' && (LIVE_SHEET_TABS as readonly string[]).includes(value)
}

/**
 * The tab the sheet was last open on — what `toggle` unfolds onto. `speed` until one has been,
 * and **only ever a live tab**: the fact itself accepts the whole vocabulary (a link naming a tab
 * that has not landed is drawn as the fold), but remembering a hidden tab would
 * make the toggle flip fold ↔ fold for the life of the tab, which is the MIDI `BuskSheetToggle`
 * door silently dead.
 */
const lastSheetStore = createSyncStore<BuskSheetTab>({
  key: BUSK_LAST_SHEET_KEY,
  fallback: 'speed',
  parse: (parsed) => (isLiveSheetTab(parsed) ? parsed : 'speed'),
  storage: sessionStorageArea,
})

/** `null` is *undecided*; true once the arrival parameters have been read, whatever they held. */
const decidedStore = createSyncStore<boolean | null>({
  key: BUSK_WINDOW_DECIDED_KEY,
  fallback: null,
  parse: (parsed) => (typeof parsed === 'boolean' ? parsed : null),
  storage: sessionStorageArea,
})

// ─── The surface ───────────────────────────────────────────────────────────

interface MediaEntry {
  matches: boolean
  listeners: Set<() => void>
  dispose?: () => void
  subscribe: (onStoreChange: () => void) => () => void
  getSnapshot: () => boolean
}

const mediaEntries = new Map<string, MediaEntry>()

/**
 * One `MediaQueryList` per query, shared by every reader — `EditorSurface.tsx`'s shape, private
 * here for the same reason its copy is private there: a shared media store would invite a shared
 * threshold, and the thresholds are pinned by spelling per site instead.
 */
function mediaEntry(query: string): MediaEntry {
  const existing = mediaEntries.get(query)
  if (existing != null) return existing
  const entry: MediaEntry = {
    matches: false,
    listeners: new Set(),
    subscribe: (onStoreChange) => {
      entry.listeners.add(onStoreChange)
      return () => {
        entry.listeners.delete(onStoreChange)
      }
    },
    getSnapshot: () => entry.matches,
  }
  mediaEntries.set(query, entry)
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    const list = window.matchMedia(query)
    entry.matches = list.matches
    const onChange = (event: MediaQueryListEvent) => {
      entry.matches = event.matches
      for (const listener of entry.listeners) listener()
    }
    list.addEventListener('change', onChange)
    entry.dispose = () => list.removeEventListener('change', onChange)
  }
  return entry
}

function useMedia(query: string): boolean {
  const entry = mediaEntry(query)
  return useSyncExternalStore(entry.subscribe, entry.getSnapshot, () => false)
}

/** What the surface answers, read live for the `get…` readers a frame handler calls. */
export interface BuskSurface {
  short: boolean
  cramped: boolean
  /** Docking the rail leaves the page body its 600px — `lg` and up. */
  docks: boolean
  /** The sheet is an overlay rather than a docked rail: below `md`, or short (the narrow and short boards). */
  overlay: boolean
}

export function readBuskSurface(): BuskSurface {
  const short = mediaEntry(SHORT_VIEWPORT).getSnapshot()
  return {
    short,
    cramped: mediaEntry(CRAMPED_VIEWPORT).getSnapshot(),
    docks: mediaEntry(SHEET_DOCKS).getSnapshot(),
    overlay: short || !mediaEntry(RAIL_DRAWN).getSnapshot(),
  }
}

function useBuskSurface(): BuskSurface {
  const short = useMedia(SHORT_VIEWPORT)
  const cramped = useMedia(CRAMPED_VIEWPORT)
  const docks = useMedia(SHEET_DOCKS)
  const railDrawn = useMedia(RAIL_DRAWN)
  return { short, cramped, docks, overlay: short || !railDrawn }
}

// ─── The defaults ladder (`Tablets.dc.html`) ───────────────────────────────

export function defaultBuskFocus(surface: Pick<BuskSurface, 'short'>): BuskFocus {
  return surface.short ? 'pads' : 'split'
}

export function defaultBuskSheet(surface: Pick<BuskSurface, 'short' | 'docks'>): BuskSheet {
  if (surface.short || !surface.docks) return 'none'
  return 'speed'
}

/** How many whole lines the split draws while the window has not chosen a height ([useBuskRigHeight]). */
export function defaultBuskRigRows(surface: Pick<BuskSurface, 'cramped' | 'docks'>): number {
  return surface.cramped || !surface.docks ? TABLET_RIG_ROWS : DESK_RIG_ROWS
}

// ─── Readers ───────────────────────────────────────────────────────────────

function useStored<T>(store: ReturnType<typeof createSyncStore<T>>): T {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** Which shape this window is in: its choice, or the surface's default until it makes one. */
export function useBuskFocus(): BuskFocus {
  const chosen = useStored(focusStore)
  const surface = useBuskSurface()
  return chosen ?? defaultBuskFocus(surface)
}

export function getBuskFocus(): BuskFocus {
  return focusStore.getSnapshot() ?? defaultBuskFocus(readBuskSurface())
}

export function setBuskFocus(focus: BuskFocus): void {
  focusStore.set(focus)
}

/**
 * The rig region's height in Split, in px — **the wish, unclamped**, or `null` while this window
 * has not chosen one. The band resolves `null` to the first [useDefaultBuskRigRows] lines by
 * measuring them, and clamps either against the body on read (the page keeps its minimum) without
 * writing the clamp back: the stored fact is what the operator asked for, and a window that shrinks
 * and grows back shows it again.
 */
export function useBuskRigHeight(): number | null {
  return useStored(rigHeightStore)
}

/** The lines the split draws while [useBuskRigHeight] answers `null`: the surface's default. */
export function useDefaultBuskRigRows(): number {
  return defaultBuskRigRows(useBuskSurface())
}

export function getBuskRigHeight(): number | null {
  return rigHeightStore.getSnapshot()
}

/** Choose a height. Rounded to whole px, and never zero — the band's floor is the least it draws. */
export function setBuskRigHeight(px: number): void {
  rigHeightStore.set(Math.max(1, Math.round(px)))
}

/** Back to the surface's default — two still presses on the grip. */
export function resetBuskRigHeight(): void {
  rigHeightStore.set(null)
}

/** Which side-sheet tab is open, `none` for the fold: the window's choice, else the default. */
export function useBuskSheet(): BuskSheet {
  const chosen = useStored(sheetStore)
  const surface = useBuskSurface()
  return chosen ?? defaultBuskSheet(surface)
}

export function getBuskSheet(): BuskSheet {
  return sheetStore.getSnapshot() ?? defaultBuskSheet(readBuskSurface())
}

/** Open a tab or fold the sheet. Opening a live tab remembers it, which is what `toggle` unfolds onto. */
export function setBuskSheet(sheet: BuskSheet): void {
  if (isLiveSheetTab(sheet)) lastSheetStore.set(sheet)
  sheetStore.set(sheet)
}

/**
 * Fold an open sheet, or unfold onto the tab it was last open on (`speed` before any). A fact
 * naming a tab that has not landed is drawn as the fold, so it toggles as the fold does: open.
 * Where the sheet is an overlay, a memory of Speed unfolds onto [OVERLAY_SHEET_TAB] instead — the
 * overlay offers no Speed tab, so opening onto it would open nothing.
 */
export function toggleBuskSheet(): void {
  if (isLiveSheetTab(getBuskSheet())) {
    setBuskSheet('none')
    return
  }
  const remembered = lastSheetStore.getSnapshot()
  setBuskSheet(remembered === 'speed' && readBuskSurface().overlay ? OVERLAY_SHEET_TAB : remembered)
}

// ─── Arrival ───────────────────────────────────────────────────────────────

/** Live read, for the arrival effect — see [useBuskWindowDecided] for why the render lags it. */
export function isBuskWindowDecided(): boolean {
  return decidedStore.getSnapshot() !== null
}

/**
 * The same question as a **rendered** value, one render behind the live one, which is what makes
 * it the right gate for the `?focus=` / `?sheet=` mirror: the arrival effect and the mirror run in
 * one commit, in that order, and a mirror gated on the live read would write the value the
 * window is arriving *from*. `buskPageFollow.ts`'s `useBuskPageDecided` is the same trick.
 */
export function useBuskWindowDecided(): boolean {
  return useStored(decidedStore) !== null
}

/**
 * Apply what a window arrived with, **once per tab**: a valid `?focus=` sets the focus, a valid
 * `?sheet=` sets the sheet, and either way the tab is marked decided so a reload — which finds the
 * mirror's own writes in the URL — is never read as an arrival. A no-op after the first call.
 * Both parameters are the raw strings, null when absent, so an unrecognised value is simply ignored.
 */
export function applyBuskArrival(params: { focus: string | null; sheet: string | null }): void {
  if (isBuskWindowDecided()) return
  if (isBuskFocus(params.focus)) setBuskFocus(params.focus)
  if (isBuskSheet(params.sheet)) setBuskSheet(params.sheet)
  decidedStore.set(true)
}

// ─── The wire ──────────────────────────────────────────────────────────────

/** The keys the busk view announces, and the two the Screens sheet and MIDI can set. */
export const VIEW_OPTION_FOCUS = 'focus'
export const VIEW_OPTION_SHEET = 'sheet'
export const VIEW_OPTION_PAGE = 'page'
export const VIEW_OPTION_PAGE_FOLLOWS = 'pageFollows'
/** `{sheet: 'toggle'}` — the MIDI `BuskSheetToggle`'s spelling, folded here into [toggleBuskSheet]. */
export const VIEW_OPTION_TOGGLE = 'toggle'

/**
 * The busk facts as the announce carries them (`windows.announce {viewOptions}`), so a Screens sheet
 * on another window can draw this one's row: focus, the sheet, whether the page follows the desk
 * and — only while it does not — which page this window holds. A following window announces no
 * `page`: the desk's showing page is the desk's to say, and the sheet reads it from
 * `busk.pageState` for every following row alike. **The rig height is not announced**: it is
 * pixels on this window's screen, which says nothing about another's, and no row control ever read
 * the line count it replaced (the `rigRows` key went with the count, 2026-09-22).
 *
 * Values, not choices: a window that has not chosen announces the surface's default, because that
 * *is* what it is showing. A subscription, for the announce effect in `useWindowsBridge`.
 */
export function useBuskViewOptions(): Record<string, string> {
  const focus = useBuskFocus()
  const sheet = useBuskSheet()
  const follows = useBuskPageFollow()
  const local = useLocalBuskPage()
  return buskViewOptions(focus, sheet, follows, local)
}

function buskViewOptions(
  focus: BuskFocus,
  sheet: BuskSheet,
  pageFollows: boolean,
  localPage: number | null,
): Record<string, string> {
  const options: Record<string, string> = {
    [VIEW_OPTION_FOCUS]: focus,
    [VIEW_OPTION_SHEET]: sheet,
    [VIEW_OPTION_PAGE_FOLLOWS]: pageFollows ? 'true' : 'false',
  }
  if (!pageFollows && localPage != null) options[VIEW_OPTION_PAGE] = String(localPage)
  return options
}

/**
 * What one `windows.viewOptions` frame changed on this window. Everything is optional because the
 * frame carries only the keys the sender meant; a page id that is not a positive integer is
 * ignored rather than unlinking the window onto nothing.
 */
export interface AppliedBuskViewOptions {
  focus?: BuskFocus
  sheet?: BuskSheet
  page?: number
  /** Whether the frame left this window paged with the desk (`true`) or on its own page. */
  pageFollows?: boolean
}

/** The page seams, for the tests; production reads `buskPageFollow.ts`. */
export interface BuskPageSeams {
  /** Unlink onto [pageId] — `?page=`'s gesture. Null keeps whatever this window shows. */
  unlinkPage?: (pageId: number | null) => void
  /** Page with the desk again — the page chip's own press. */
  relinkPage?: () => void
  /** The page this window is showing now, or null before the view has resolved one. */
  showingPage?: () => number | null
}

/**
 * Apply a `windows.viewOptions` frame's `options` to this window's busk facts (D13). Focus and
 * sheet are the tab's own stores. The page is `buskPageFollow.ts`'s, two keys and one fact
 * (desk-follow plan D6):
 *
 * - **`page`** unlinks this window onto that page exactly as arriving with `?page=` does, so the
 *   page chip says *Page: Own* afterwards.
 * - **`pageFollows: 'true'`** pages the window with the desk again — the chip's own press,
 *   `relinkBuskPage` — and **`'false'`** keeps the page it is showing as its own, which is why it
 *   needs [BuskPageSeams.showingPage]: the window may be showing the desk's page, its `?page=` or
 *   the first page, and only the view knows which (`BuskingView` reports it).
 *
 * **A frame carrying both resolves on `pageFollows`.** `false` with a page is the unlink onto that
 * page, one gesture, not two. `true` with a page relinks and ignores the page: the two contradict
 * each other, and following is the statement — a paged-with window's page is the desk's, which a
 * frame aimed at one window must not move (the Screens row pages the group with `busk.setPage`,
 * never through here). Keys this view does not contribute are ignored, and so is a value outside
 * the vocabulary. Returns what changed, for the caller and the tests.
 */
export function applyBuskViewOptions(
  options: Readonly<Record<string, string>>,
  seams: BuskPageSeams = {},
): AppliedBuskViewOptions {
  const applied: AppliedBuskViewOptions = {}
  const focus = options[VIEW_OPTION_FOCUS]
  if (isBuskFocus(focus)) {
    setBuskFocus(focus)
    applied.focus = focus
  }
  const sheet = options[VIEW_OPTION_SHEET]
  if (sheet === VIEW_OPTION_TOGGLE) {
    toggleBuskSheet()
    applied.sheet = getBuskSheet()
  } else if (isBuskSheet(sheet)) {
    setBuskSheet(sheet)
    applied.sheet = sheet
  }
  const unlink = seams.unlinkPage ?? unlinkBuskPage
  const follows = options[VIEW_OPTION_PAGE_FOLLOWS]
  const page = parsePageId(options[VIEW_OPTION_PAGE])
  if (follows === 'true') {
    ;(seams.relinkPage ?? relinkBuskPage)()
    applied.pageFollows = true
  } else if (page != null) {
    unlink(page)
    applied.page = page
    applied.pageFollows = false
  } else if (follows === 'false') {
    unlink((seams.showingPage ?? showingBuskPage)())
    applied.pageFollows = false
  }
  return applied
}

/** A page id off the wire: a positive safe integer, or null for anything else. */
function parsePageId(value: string | undefined): number | null {
  if (value == null || !/^\d+$/.test(value)) return null
  const pageId = Number(value)
  return Number.isSafeInteger(pageId) && pageId > 0 ? pageId : null
}

/** Test seam: every store back to its fallback, and the cached media answers dropped. */
export function resetBuskWindowStores(): void {
  focusStore.reset()
  rigHeightStore.reset()
  sheetStore.reset()
  lastSheetStore.reset()
  decidedStore.reset()
  for (const entry of mediaEntries.values()) entry.dispose?.()
  mediaEntries.clear()
}
