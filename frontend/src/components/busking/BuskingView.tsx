import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useDispatch, useSelector } from 'react-redux'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useActiveCueIds } from '@/store/cues'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { enterBuskEdit, exitBuskEdit, selectBuskEdit } from '@/store/buskEditSlice'
import {
  useBuskPagesQuery,
  useCreateBuskPageMutation,
  useDeleteBuskPageMutation,
  useRenameBuskPageMutation,
  useReorderBuskPagesMutation,
  useSaveBuskLayoutMutation,
  usePressBuskPadMutation,
  useCacheBuskPage,
  useBuskShowingPageQuery,
  setShowingBuskPage,
  useAddBuskPadMutation,
  useBuskRigQuery,
} from '@/store/busk'
import { handPickUp, heldName, useHandPlace } from '@/store/hand'
import {
  isBuskPageDecided,
  keepFollowingBuskPage,
  reportShowingBuskPage,
  setLocalBuskPage,
  unlinkBuskPage,
  useBuskPageDecided,
  useBuskPageFollow,
  useLocalBuskPage,
} from '@/lib/buskPageFollow'
import {
  applyBuskArrival,
  setBuskFocus,
  setBuskSheet,
  useBuskFocus,
  useBuskSheet,
  useBuskWindowDecided,
} from '@/lib/buskWindow'
import { useEditorForm } from '@/components/editor/EditorSurface'
import { Button } from '@/components/ui/button'
import { PanelBottomOpen } from 'lucide-react'
import { toast } from 'sonner'
import { lastPadOfBank, libraryStarterLayout, recordsOnPage, removePad, toLayoutRequest } from '@/lib/buskLayout'
import { recordsOnRig } from '@/lib/buskRig'
import { buskAddBody } from '@/lib/buskAdd'
import { skippedRowsMessage } from '@/lib/selectionMask'
import { followIsForced, relinkToDesk, useDeskFollow } from '@/lib/deskFollow'
import { windowName } from '@/lib/windowIdentity'
import type { BuskPad } from '@/api/buskApi'
import { lookLayerPresence, templateLayerPresence } from './lookPresence'
import { COMPACT_FOCUS_WORD_CLASS, EDIT_WORD_CLASS, FOCUS_WORD_CLASS, RigBand, VERB_WORD_CLASS } from './RigBand'
import { RigStrip, RigStripContent } from './RigStrip'
import { SideSheet, SideSheetOverlay, sideSheetTabs } from './SideSheet'
import type { ShowTabSource } from './ShowTab'
import { BuskFocusControl } from './BuskFocusControl'
import { BuskEditProvider } from './BuskEditProvider'
import { BuskPageBody } from './BuskPage'
import { BuskPageStrip, EditLayoutToggle, PAD_EDIT_WORD_CLASS, PAD_FOCUS_WORD_CLASS } from './BuskPageStrip'
import { BuskFirstOpen } from './BuskFirstOpen'
import { LibraryPalette } from './LibraryPalette'
import { useBuskingState } from './useBuskingState'
import { useSelectionVerbs } from './selectionVerbs'
import type { PadBehaviour } from './padBehaviour'
import { type EffectPresence } from './buskingTypes'

/**
 * The short-viewport fold, duplicated per site by convention — see the module note and
 * `shortViewport.test.ts`, which pins this spelling against every other copy.
 */
const SHORT_VIEWPORT = '(max-height: 500px)'

/** Which board this window draws: the desk's, the short one (wide but not tall), or the narrow one. */
export type BuskBoard = 'desk' | 'short' | 'narrow'

/**
 * The busk view's body: the rig band, the page the operator built, and the side sheet — or, in
 * edit mode, the palette in the sheet's place, with a Library tab for the page and a Rig tab for
 * the band.
 *
 * **The view has three shapes, and which one is this window's fact** (busk-further plan D5–D7,
 * `lib/buskWindow.ts`): **Split** — the band drawing the rig as a scroller at `busk.rigHeight` over the page, the
 * handle between; **Pads** — the page fills the body: on the desk board the rig band is **not
 * drawn** and the **pad row** is the body's top row (busk-chrome plan D17 — `BuskPageStrip` with
 * [pads]: the three selection verbs, the summary, the pill, and the Focus control and *Edit
 * layout* / *Done* at its end), off it the rig folds to `RigStrip` — the band's row without the rows, labelled *Pads*, whose Focus control is the way back;
 * **Rig** — the band fills the body with every row and the page folds to its strip at the bottom,
 * name, bank count and the chevron back to Split. In Split and Rig the band's one row (D13) ends with the Focus control
 * and the edit toggle, and in Pads the pad row does: `bandControls` is mounted on whichever row is
 * the body's top row. The band drew itself in Pads too, folded to its one row and a chevron, from
 * the morning of 2026-09-21 to the evening — a row of tile controls with no tiles on screen. Edit
 * mode **forces Split** for its duration, because a palette drag needs both regions on screen, and
 * restores the window's focus on Done by never having written it; so does a project with no
 * pages, whose first-open screen lives in the page column. The side sheet is `busk.sheet`'s: a
 * tab, or `none` for the fold.
 *
 * **The three selection verbs are one instance** (`useSelectionVerbs`, `selectionVerbs.tsx`),
 * called here and handed to whichever row is drawn — the band in Split and Rig, the pad row in
 * Pads — so a window has one Highlight capture and one locate fold, and the two rows cannot
 * answer a press two ways.
 *
 * **`?focus=` and `?sheet=` are this window's on arrival**, latched once per tab exactly as
 * `?page=` is below — the raw strings read at mount, applied through `applyBuskArrival`, which
 * marks the tab decided whatever they held — and mirrored back with `replace`, gated on the
 * *rendered* decided flag for `useBuskPageDecided`'s reason. So the view's address always carries
 * both, a copied link reproduces the shape, and a reload is never an arrival.
 *
 * **There is no narrow-width target sheet any more** (D15): below `md` the band is one row with
 * a row chip, and Rig focus is the whole rig stacked. Below `md` the side sheet is a bottom sheet
 * or a right-hand overlay through `useEditorForm`, opened from the page strip's button onto
 * Colour, since that sheet carries no Speed tab (D7) — Colour, Spread and Show.
 *
 * **Three boards, and short beats narrow** (`Phones.dc.html`, `Tablets.dc.html`). `md` says
 * whether this is the desk board or the narrow one; the short-viewport fold — `SHORT_VIEWPORT`,
 * this file's own copy of the 500px height query, by the convention `shortViewport.test.ts`
 * enforces — says whether a window wide enough for the desk board has the height for it. A
 * landscape phone is wider than `md` and has ~350px under the ShowHeader, so by width alone the rail
 * would dock and the palette would be offered; on the **short board** instead the rig strip and
 * the page strip merge into one 32px row (`RigStripContent` in `BuskPageStrip`'s `leading` slot —
 * the same pieces, not a third strip), Split shows one row of 48px tiles with the row chip
 * (`RigBand compact`), the side sheet **overlays** rather than docks (neither the fold nor the
 * docked rail is drawn; `SideSheetOverlay` takes its right-hand form, Colour in its compact
 * layout, from the merged row's *Sheet* button), and *Edit layout* is withheld as it is below
 * `md`, since a palette drag needs both regions on screen. The defaults — Pads focus, the sheet
 * folded — are already the ladder's in `lib/buskWindow.ts`; only which board is drawn changes here.
 *
 * The show chrome above it — the `ShowHeader`, and no bar — belongs to `routes/Busk.tsx`, which
 * also holds the one `useShowBarProps` call and hands its result down as [show] for the side
 * sheet's Show tab and the fold's live cue number (busk-chrome plan D1, D4).
 *
 * **Every press goes through one route.** A pad is pressed by `POST /busk/pads/{id}/press`,
 * whatever it holds, because the pad is what knows its bank and the bank is what decides which
 * siblings a press releases (D4). The three kind-specific mutations this view used to call are the
 * programmer's ⌥click / touch-hold strip's now, and the AI's.
 *
 * **There is no empty-selection dim.** The pools used to grey themselves out with nothing selected,
 * which is now wrong in three ways: a per-fixture template names its own heads, a Look with no
 * deferred effect names its own fixtures, and a cue has no targets at all — so all three are
 * legitimately pressable with an empty selection. The two that genuinely need one are refused *by
 * name* server-side (`TEMPLATE_NEEDS_SELECTION`, `LOOK_NEEDS_SELECTION`), and a sentence saying so
 * is a better answer than a grey page. A bank mixes kinds anyway, so the old per-section dim has
 * nothing left to be per.
 *
 * **No transport of its own.** The stack cards and the pinned-cue grid went with the layout; GO and
 * BACK live on the side sheet's Show tab, and a cue pad's green comes from `useActiveCueIds` — its
 * stack has that cue on stage, playhead or not, which is what makes a cue pad a toggle rather than
 * a playhead move.
 */
/** Sonner id for the D3 relink, so a second one replaces the toast rather than stacking. */
export const RELINK_TOAST_ID = 'busk-relinked'

export function BuskingView({ projectId, show }: { projectId: number; show: ShowTabSource }) {
  const isDesktop = useMediaQuery('(min-width: 768px)')
  const isShort = useMediaQuery(SHORT_VIEWPORT)
  // Short beats narrow: a window below `md` is the narrow board whatever its height, and the short
  // board is the desk board's width without its height.
  const board: BuskBoard = !isDesktop ? 'narrow' : isShort ? 'short' : 'desk'
  const navigate = useNavigate()
  const dispatch = useDispatch()
  const [searchParams, setSearchParams] = useSearchParams()
  const focus = useBuskFocus()
  const sheet = useBuskSheet()
  const sheetForm = useEditorForm()

  const {
    selectedTargets,
    selectedLayerTargets,
    families,
    toggleTarget,
    clearSelection,
    subselect,
    programmerApplied,
  } = useBuskingState(projectId)

  const verbs = useSelectionVerbs(selectedTargets)
  const { data: pages, isLoading } = useBuskPagesQuery(projectId)
  const { data: rig } = useBuskRigQuery(projectId)
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: looks } = useLookListQuery({ projectId })
  const activeCueIds = useActiveCueIds(projectId)
  const { editing } = useSelector(selectBuskEdit)

  const [createPage, { isLoading: creating }] = useCreateBuskPageMutation()
  const [renamePage] = useRenameBuskPageMutation()
  const [deletePage] = useDeleteBuskPageMutation()
  const [reorderPages] = useReorderBuskPagesMutation()
  const [saveLayout, { isLoading: generating }] = useSaveBuskLayoutMutation()
  const [pressPad] = usePressBuskPadMutation()
  const [addPad] = useAddBuskPadMutation()
  const placeFromHand = useHandPlace()
  const cachePage = useCacheBuskPage(projectId)

  // Which page is showing has two answers — the desk's and this window's — and a per-tab flag says
  // which of them this window is on (`lib/buskPageFollow.ts`). Following:
  //
  //   the desk's showing page  >  `?page=`  >  the first page
  //
  // Local (the chip clicked to *This window*):
  //
  //   this window's own page   >  `?page=`  >  the first page
  //
  // The **desk is still one fact and still moves for the hardware**: a MIDI *next page* press
  // writes `BuskPageState` and every *following* window moves with it, which is the argument that
  // made the showing page server-owned in the first place (midi-surface plan D6) and is untouched
  // here. What that argument never established is that every window must be pinned to it — and on
  // two screens it is wrong, because the flow this exists for is a colour page on one screen and a
  // position page on the other, pressed onto one shared selection. So: two facts, two flags, two
  // chips, and **this one may never read the selection's** (`lib/deskFollow.ts`). A window that has
  // unlinked its page has not unlinked its selection and still presses onto the desk's.
  //
  // A tab click writes the **desk** while following, and this window's own copy once unlinked.
  // Both are resolved against the fetched list, so a stale bookmark or a page deleted in another
  // tab lands somewhere real.
  //
  // **There is no separate offline override any more.** There used to be one — a local page set
  // only when `setShowingBuskPage` failed because the socket was down, cleared by any change to the
  // desk's value — and it was the same shape as a local page: a per-tab page that beats the desk's.
  // Two mechanisms meaning "this tab's page" is one too many, so a click that never left the
  // browser now **unlinks the window** onto the page clicked. That is the honest reading of what
  // just happened (`sendGesture` has already toasted that it did not reach the rig), and unlike the
  // old override it *says so*: the page badge gives way to *Page: Own*.
  const requestedPageId = Number(searchParams.get('page'))
  const { data: deskPageId } = useBuskShowingPageQuery()
  const followingPage = useBuskPageFollow()
  const localPageId = useLocalBuskPage()
  const activePage = useMemo(() => {
    if (pages == null || pages.length === 0) return null
    const preferred = followingPage ? deskPageId : localPageId
    return (
      pages.find((page) => page.id === preferred) ??
      pages.find((page) => page.id === requestedPageId) ??
      pages[0]
    )
  }, [pages, followingPage, deskPageId, localPageId, requestedPageId])

  // What this window is showing, for a `pageFollows: 'false'` from another window's Screens row
  // to keep as this window's own (desk-follow plan D6, `reportShowingBuskPage`). Cleared on the
  // way out: the frame is applied only while this view is showing, but a stale id must not
  // outlive it. The cleanup's null between two ids is never seen: a commit runs cleanups and
  // effects in one synchronous pass, and the reader is a socket frame's handler, a later task.
  const showingPageId = activePage?.id ?? null
  useEffect(() => {
    reportShowingBuskPage(showingPageId)
    return () => reportShowingBuskPage(null)
  }, [showingPageId])

  const onPageSelect = useCallback(
    (pageId: number) => {
      if (!followingPage) {
        setLocalBuskPage(pageId)
        return
      }
      if (!setShowingBuskPage(pageId)) unlinkBuskPage(pageId)
    },
    [followingPage],
  )

  // **`?page=` is *this window's* page when the window arrives carrying one**, and arriving with
  // one unlinks it: a launcher URL (`?window=Screen%202&page=3`, the Screens sheet's spelling plus
  // this) is an explicit statement about this window, and a link that says page 3 opening on
  // whatever the desk happens to hold would be no statement at all. The consequence is worth
  // knowing, because it is surprising: the view mirrors the showing page into `?page=`, so the
  // busk view's own address always carries one — and a **copied URL opened in a fresh window
  // therefore arrives local rather than following**. One click on the chip joins it to the desk.
  //
  // It is consumed **once per tab**, guarded on `isBuskPageDecided`, because the effect below
  // mirrors the showing page back into `?page=` on every change: a guard that died with the mount
  // would let a *reload* of a following window read its own mirror as a deliberate statement and
  // unlink on every refresh. The flag's third state — undecided — is what tells a fresh window from
  // a reloaded one, so both arms write it: a window that arrives with no usable `?page=` records
  // that it follows. There is deliberately no second, in-memory "already ran" flag beside it; the
  // persisted one can never disagree with itself, and a ref could.
  //
  // `launchPageId` is latched at mount, because by the time `pages` resolves the mirror may already
  // have written a `page` this window never asked for. It is latched as the **raw parameter**,
  // null when absent: `Number(null)` is 0, and a page whose id really were 0 would make every plain
  // `/busk` load read as an arrival and unlink an ordinary tab with nobody having asked.
  const [launchPageId] = useState(() => {
    const raw = searchParams.get('page')
    return raw == null ? null : Number(raw)
  })
  useEffect(() => {
    if (pages == null || pages.length === 0 || isBuskPageDecided()) return
    const arrival = launchPageId == null ? undefined : pages.find((page) => page.id === launchPageId)
    if (arrival != null) unlinkBuskPage(arrival.id)
    else keepFollowingBuskPage()
    // `searchParams` is deliberately absent: `launchPageId` is the arrival value, latched.
  }, [pages, launchPageId])

  // Not until the arrival decision above has been *rendered*. Both effects run in one commit, in
  // this order, and `unlinkBuskPage` only schedules the re-render that moves `activePage` — so
  // without this gate the mirror writes the page the window is unlinking *from* into the URL, and
  // corrects it a tick later. `useBuskPageDecided` is the rendered tri-state precisely because it
  // lags by one render; see its doc for why neither the live read nor `useBuskPageFollow` can
  // stand in for it here.
  const pageDecided = useBuskPageDecided()

  // The window's shape: `?focus=` and `?sheet=` latched at mount as raw strings, for the same
  // reason `launchPageId` is, and applied once per tab — `applyBuskArrival` is a no-op once the tab
  // has decided, so a reload finds the mirror's own writes and leaves them be.
  const [launchShape] = useState(() => ({ focus: searchParams.get('focus'), sheet: searchParams.get('sheet') }))
  useEffect(() => {
    applyBuskArrival(launchShape)
  }, [launchShape])

  // **Rig and Pads always follow the desk selection** (desk-follow plan D2, `followIsForced`), so
  // entering either while this window holds its own **drops it and says so** (D3). Keyed on the
  // focus and the flag, never on a mount gate, because the focus moves through five doors — the
  // Focus control, the Screens row, ⌘K, a MIDI `BuskFocusSet`, `?focus=` on arrival — three of
  // them from elsewhere, and a window that *arrives* in Pads while local (a reload over stale
  // `sessionStorage`, a Programmer window unlinked and navigated here) must relink the same way.
  // The flag in the key makes it a backstop too: anything that ever unlinked this window in a
  // forced focus is undone on the next render rather than left pressing onto a frozen snapshot.
  // The stored focus, not `shape`: edit mode and an empty project force Split only for their
  // duration, and the focus is what the window returns to — and what other windows see announced.
  const followingDesk = useDeskFollow()
  useEffect(() => {
    if (followingDesk || !followIsForced('busk', focus)) return
    relinkToDesk()
    toast.message(`${windowName()} follows the desk selection again`, {
      id: RELINK_TOAST_ID,
      description: `${focus === 'rig' ? 'Rig' : 'Pads'} focus presses onto the desk's selection. Your own was dropped.`,
    })
  }, [focus, followingDesk])

  // The shape's mirror has the same gate, on its own rendered tri-state: not until its arrival
  // decision has been rendered, or the mirror writes the shape the window is arriving *from*.
  const windowDecided = useBuskWindowDecided()
  const urlFocus = searchParams.get('focus')
  const urlSheet = searchParams.get('sheet')

  // **One mirror for all three keys.** `setSearchParams`' functional updater does not build on a
  // prior call in the same tick — react-router copies the *current render's* search into `prev` —
  // so two mirrors firing in one commit would each drop the other's key for a frame and navigate
  // twice. Each key is written only where its own decision has been rendered and it has moved.
  const mirrorPage = pageDecided && activePage != null && activePage.id !== requestedPageId ? activePage.id : null
  const mirrorShape = windowDecided && (urlFocus !== focus || urlSheet !== sheet)
  useEffect(() => {
    if (mirrorPage == null && !mirrorShape) return
    // `replace`, never `push`: flipping between pages or shapes is not a history entry.
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        if (mirrorPage != null) next.set('page', String(mirrorPage))
        if (mirrorShape) {
          next.set('focus', focus)
          next.set('sheet', sheet)
        }
        return next
      },
      { replace: true },
    )
  }, [mirrorPage, mirrorShape, focus, sheet, setSearchParams])

  // Leaving the view leaves edit mode. Without this the FX cue-slot overlay, which reads the mode
  // from the store, would keep drawing its crosses on whatever page the operator went to.
  useEffect(() => () => void dispatch(exitBuskEdit()), [dispatch])

  useEffect(() => {
    if (editing && activePage != null) dispatch(enterBuskEdit(activePage.id))
  }, [editing, activePage, dispatch])

  const presenceOf = useCallback(
    (pad: BuskPad): EffectPresence => {
      const applied = programmerApplied ?? []
      if (pad.kind === 'TEMPLATE' && pad.template != null) {
        return templateLayerPresence(applied, selectedLayerTargets, pad.template.id)
      }
      if (pad.kind === 'LOOK' && pad.look != null) {
        return lookLayerPresence(applied, selectedLayerTargets, pad.look.id)
      }
      return 'none'
    },
    [programmerApplied, selectedLayerTargets],
  )

  const behaviour = useMemo<PadBehaviour>(
    () => ({
      presenceOf,
      isLive: (pad) => pad.kind === 'CUE' && pad.cue != null && activeCueIds.has(pad.cue.id),
      onPress: (pad) => {
        // A pad the layout write has not answered for yet has no id to press. It cannot be reached
        // in practice — presses are off while editing — but the guard keeps the type honest.
        if (pad.id == null) return
        // The pair a press acts on — the desk's while following, this tab's own when unlinked
        // (multi-screen plan D4) — and never pre-refused from the mask held here: the mask is
        // tested on the on arm only, so a lit pad still comes off under a mask that excludes it,
        // and only the desk knows which arm this is. A refusal (`TEMPLATE_OUTSIDE_MASK`,
        // `LOOK_OUTSIDE_MASK`) arrives as a 400 whose message names both families, and
        // `errorToastMiddleware` toasts it as it toasts every rejected mutation.
        void pressPad({
          projectId,
          padId: pad.id,
          targets: selectedLayerTargets,
          families: families ?? undefined,
        })
          .unwrap()
          .then((result) => {
            // The skip is reported on the pressing window (D6) — this one. The marquee's window
            // learns the way it learns every layer: `programmer.layerState` carries the mask, and
            // `LookStack` draws the badge. *Rows*, not the family: the cook masks a layer's rows
            // and not its effects, so a Look's effect in a skipped family still runs.
            const message = skippedRowsMessage(result.skippedFamilies ?? [], families)
            if (message != null) toast.warning(message)
          })
          .catch(ignoreReportedError)
      },
      onInspect: (pad) => {
        if (pad.kind === 'TEMPLATE') navigate(`/projects/${projectId}/templates`)
        else if (pad.kind === 'LOOK') navigate(`/projects/${projectId}/looks`)
        else if (pad.cue != null) {
          navigate(`/projects/${projectId}/show/stacks/${pad.cue.cueStackId}?cue=${pad.cue.id}`)
        }
      },
      // *Pick up* names the **record**, not the pad: what the hand holds is the template, Look or
      // cue, and placing it elsewhere makes a second pad rather than moving this one. (The MIDI
      // door is `PickUpPad(padUuid)` and resolves to the same record server-side.)
      onPickUp: (pad) => {
        const id = pad.template?.id ?? pad.look?.id ?? pad.cue?.id
        if (id != null) handPickUp(pad.kind, id)
      },
      // A place is this window's own append followed by `hand.drop` (D12). The append answers the
      // **whole page**, so the busk view's commit queue needs nothing from it — and the Undo has to
      // go back through the layout PUT, since there is no remove-pad route: the append's own
      // response is the page to take the new pad off.
      onHandPlace: (bankId, bankName, held) => {
        const pageId = activePage?.id
        if (pageId == null) return
        void placeFromHand(held, {
          where: bankName,
          run: () =>
            addPad({
              projectId,
              pageId,
              bankId,
              ...buskAddBody({ kind: held.kind, id: held.id, name: heldName(held) }),
            }).unwrap(),
          undo: (page) => {
            const at = lastPadOfBank(page, bankId)
            if (at == null) return
            void saveLayout({
              projectId,
              pageId: page.id,
              ...toLayoutRequest(removePad(page, at)),
            }).unwrap().catch(ignoreReportedError)
          },
        })
      },
    }),
    [
      presenceOf,
      activeCueIds,
      pressPad,
      projectId,
      selectedLayerTargets,
      families,
      navigate,
      activePage,
      addPad,
      saveLayout,
      placeFromHand,
    ],
  )

  const onPageKeys = useMemo(
    () => (activePage != null ? recordsOnPage(activePage) : new Set<string>()),
    [activePage],
  )
  const onRigKeys = useMemo(() => (rig != null ? recordsOnRig(rig) : new Set<string>()), [rig])

  const startFromLibrary = useCallback(async () => {
    const page = await createPage({ projectId, name: 'Page 1' }).unwrap().catch(ignoreReportedError)
    if (page == null) return
    const written = await saveLayout({
      projectId,
      pageId: page.id,
      ...libraryStarterLayout(templates ?? [], looks ?? []),
    })
      .unwrap()
      .catch(ignoreReportedError)
    // The one layout write outside the commit queue, so it has to seed the cache itself — the
    // create's own invalidation refetched this page while it was still empty, and nothing else
    // would show what was just generated until an unrelated frame arrived.
    if (written != null) cachePage(written)
  }, [createPage, saveLayout, cachePage, projectId, templates, looks])

  // Edit mode forces Split (D4): the fact is untouched, so Done restores it by simply reading it.
  // So does a project with no pages, once the list has answered: `BuskFirstOpen` owns that moment
  // and lives in the page column, and Rig focus would fold the column to a strip with nothing on
  // it to create a page from (`+ Page` is edit mode's, and *Edit layout* is disabled with none).
  const noPages = pages != null && pages.length === 0
  const shape = editing || noPages ? 'split' : focus
  // Off the desk board the sheet is an overlay, and these are the tabs it can open onto.
  const overlayTabs = sideSheetTabs(sheetForm)
  const docked = board === 'desk'
  // The short board's merged row: the rig strip's pieces lead the page strip while the rig is
  // folded. In Split and Rig focus the band carries them itself, so the row holds only the page.
  const merged = board === 'short' && shape === 'pads'
  // The desk board's Pads: the pad row is the body's top row and the band is not drawn (D17).
  const padRow = docked && shape === 'pads'

  // The Focus words fold by the row they sit on: the desk board's rig row at its measured fold,
  // the pad row at its own in Pads, the compact boards' strip at the fold that row was measured
  // for (`COMPACT_FOCUS_WORD_CLASS`).
  const focusControl = (
    <BuskFocusControl
      disabled={editing}
      labelClass={padRow ? PAD_FOCUS_WORD_CLASS : docked ? FOCUS_WORD_CLASS : COMPACT_FOCUS_WORD_CLASS}
    />
  )
  // Its word folds with the verbs' on whichever row it sits on — the rig row's rung in Split and
  // Rig, the pad row's in Pads; the compact boards never draw it unfolded.
  const editToggle = (
    <EditLayoutToggle
      editing={editing}
      editable={docked}
      hasPages={(pages?.length ?? 0) > 0}
      labelClass={padRow ? PAD_EDIT_WORD_CLASS : docked ? EDIT_WORD_CLASS : VERB_WORD_CLASS}
      onToggle={() => {
        if (editing) dispatch(exitBuskEdit())
        else if (activePage != null) dispatch(enterBuskEdit(activePage.id))
      }}
    />
  )
  // The body's top row ends with these two — the Focus control and *Edit layout* / *Done*: the
  // band's one row in Split and Rig (the compact board's too), the pad row in Pads.
  const bandControls = (
    <>
      {focusControl}
      {editToggle}
    </>
  )

  const pageStrip = (folded: boolean) => (
    <BuskPageStrip
      pages={pages ?? []}
      activePageId={activePage?.id ?? null}
      editing={editing}
      folded={folded}
      onUnfold={() => setBuskFocus('split')}
      // Not while editing: the editing verbs are sized for the wrapping desk row, and a window
      // shortened mid-edit keeps `editing` until Done. `merged` is already false there, since edit
      // mode forces Split.
      dense={board === 'short' && !folded && !editing}
      // Pads on the desk board: the row carries the selection's verbs, summary and mask (D17).
      pads={padRow && !folded ? { selectedTargets, families, verbs } : undefined}
      leading={
        merged ? (
          <RigStripContent selectedTargets={selectedTargets} families={families} />
        ) : undefined
      }
      // The desk while this window follows it, this window's own copy once unlinked — and
      // the effect above mirrors whichever won into `?page=`. A click that never reached the
      // desk unlinks the window rather than overriding it silently; see `onPageSelect`.
      onSelect={onPageSelect}
      onCreate={(name) => createPage({ projectId, name }).unwrap()}
      onRename={(name) =>
        activePage == null
          ? Promise.resolve()
          : renamePage({ projectId, pageId: activePage.id, name }).unwrap()
      }
      onReorder={(pageIds) => void reorderPages({ projectId, pageIds })}
      onDelete={() => {
        if (activePage == null) return
        void deletePage({ projectId, pageId: activePage.id })
      }}
      controls={
        <>
          {!docked && !editing && (
            <Button
              size="sm"
              variant="outline"
              className="h-7 text-xs"
              disabled={overlayTabs.length === 0}
              title={
                overlayTabs.length === 0
                  ? 'Nothing to open here yet'
                  : `Open the ${overlayTabs[0].label} tab`
              }
              onClick={() => {
                const first = overlayTabs[0]
                if (first != null) setBuskSheet(first.id)
              }}
            >
              <PanelBottomOpen className="size-3.5" /> Sheet
            </Button>
          )}
          {/* Where this *is* the body's top row — the merged row, and the desk board's pad row in
              Pads — the Focus control and the edit toggle are here; Split and Rig put them on
              the band. */}
          {(merged || (padRow && !folded)) && bandControls}
        </>
      }
    />
  )

  return (
    // `min-h-0 flex-1`, never `h-full`: the view is a flex item under the ShowHeader in
    // `routes/Busk.tsx`'s column, and a percentage height there is the whole column's — Chromium
    // shrinks the item back to fit, Safari does not, and the page body then overflowed `<main>` by
    // exactly a header (and, while there was one, a bar). Two scrollers: one hiding the
    // breadcrumbs, one for the pads.
    // `ProgrammerWorkspace` is the same pattern for the same reason.
    <div className="flex min-h-0 flex-1 flex-col">
      {/* `relative` is the side sheet's containing block in overlay mode — it is absolutely
          positioned against this row, over the page body, exactly as the programmer rail's
          overlay arm is against the workspace row. */}
      <div className="relative flex min-h-0 flex-1">
        {/* `data-busk-column`: the band measures the split's ceiling off this column's own box less
            the fixed chrome in it — never off the page body's remainder (`RigBand`). */}
        <div data-busk-column className="flex min-h-0 min-w-0 flex-1 flex-col">
          {/* Not dimmed while editing any more: the band is being *edited* then — its tiles are
              drag handles and take drops — and a dim over a drop target reads as "not here". Pads
              do not press in edit mode and tiles do not select; both say so by their cursors. */}
          {shape === 'pads' ? (
            // Off the desk board the fold is the rig strip — the band's row without the rows; on the short board its pieces lead the
            // merged page row instead (`merged`). On the desk board nothing is drawn here: the pad
            // row below is the body's top row (D17).
            !merged && !docked && (
              <RigStrip selectedTargets={selectedTargets} families={families} controls={bandControls} />
            )
          ) : (
            <RigBand
              projectId={projectId}
              selectedTargets={selectedTargets}
              families={families}
              onToggle={toggleTarget}
              onClear={clearSelection}
              verbs={verbs}
              onSubselect={subselect}
              editing={editing}
              compact={!docked}
              stackRows={board === 'narrow'}
              focus={shape}
              controls={bandControls}
            />
          )}

          {shape !== 'rig' && pageStrip(false)}

          {shape !== 'rig' && (
            <BuskEditProvider editing={editing} projectId={projectId} page={activePage}>
              {pages != null && pages.length === 0 && !isLoading ? (
                <BuskFirstOpen
                  busy={creating || generating}
                  onStartFromLibrary={() => void startFromLibrary()}
                  onStartEmpty={() => void createPage({ projectId, name: 'Page 1' })}
                />
              ) : activePage != null ? (
                <BuskPageBody page={activePage} behaviour={behaviour} />
              ) : (
                <div data-busk-page-body className="min-h-0 flex-1" />
              )}
            </BuskEditProvider>
          )}

          {/* Rig focus: the page folded to its strip at the bottom — name, bank count and the chevron
              back to Split, in the slot the rig strip's has; the Focus control and Edit layout are
              on the band's row above, as in every shape. */}
          {shape === 'rig' && pageStrip(true)}
        </div>

        {editing ? (
          <LibraryPalette projectId={projectId} onPageKeys={onPageKeys} onRigKeys={onRigKeys} />
        ) : (
          docked && <SideSheet projectId={projectId} selectedTargets={selectedTargets} families={families} show={show} />
        )}
      </div>
      {!docked && !editing && (
        <SideSheetOverlay projectId={projectId} selectedTargets={selectedTargets} families={families} show={show} />
      )}
    </div>
  )
}
