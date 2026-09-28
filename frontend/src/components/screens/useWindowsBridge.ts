import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import type { WindowCommand } from '@/api/windowsApi'
import { followIsForced, isFollowingDesk, relinkToDesk, useDeskFollow } from '@/lib/deskFollow'
import {
  enterFullscreen,
  exitFullscreen,
  requestReturnToFullscreen,
  useFullscreenState,
} from '@/lib/fullscreen'
import { hasUnsavedSheets } from '@/lib/unsavedSheets'
import { renameWindow, useWindowName } from '@/lib/windowIdentity'
import { WINDOW_VIEWS, announcedViewOptions, windowViewLabel, windowViewOf } from '@/lib/windowViews'
import { applyBuskViewOptions, getBuskFocus, useBuskViewOptions } from '@/lib/buskWindow'
import { applyImmersiveViewOption, useImmersive } from '@/lib/immersive'
import { lightingApi } from '@/api/lightingApi'
import { announceThisWindow, thisWindowRowId } from '@/store/windows'
import { unlinkFromDeskNow } from '@/store/selection'
import { isEditableTarget } from '@/lib/domUtils'

/**
 * The half of the `windows.*` family that needs the router (multi-screen plan §3.4): the announce
 * of what this window is showing, and the handler for the five commands another window sends it.
 * Mounted once, in `Layout`, inside `RouterProvider` — a store-level bridge could do neither,
 * because `navigate` and the location exist only there.
 *
 * **The announce is one effect keyed on the five things it carries** — the route, the name,
 * full screen, follow, and the view's options — so every change re-announces and nothing else
 * does. The `open` re-send is `api/windowsApi.ts`'s, from the payload this effect last handed it.
 * The `view` is the pathname alone: the search is a window's private business (`?cue=`,
 * `?select=`), and the facts the busk view mirrors into its search (`?page=`, `?focus=`, `?sheet=`)
 * ride the announce as `viewOptions` instead — **only while this window is on a view that
 * contributes any** (`lib/windowViews.ts`), so a window on a library sends the five-key frame it
 * always did. Every live view contributes since the busk-chrome plan's session B: `immersive`
 * rides `viewOptions` under all four (D9), because the desk's Json is bare and a sixth top-level
 * key would drop the frame. The busk facts are subscribed on every route, because the hooks that
 * read them are the only way to re-announce when they move; they are three `sessionStorage`
 * stores and cost nothing while the view is elsewhere. `announcedViewOptions` is the one place
 * that says which keys go out under which view.
 *
 * **Every socket receives every command, the sender included** (D11), so each handler's first
 * act is comparing `targetId` to this window's row id — read at command time from the last
 * `windows.state`, never through a hook, so a command that lands before this tab's first state
 * frame matches nothing rather than a stale closure. That comparison is also what makes a
 * command this window sent to *another* window a no-op here when it comes back.
 *
 * Three consequences of a `windows.show`, each already answered by existing code (plan §3.4): the
 * show-editing lock is a per-tab Redux slice defaulting to locked, so arriving on `/show` mid-show
 * lands locked; an open cell editor unmounts with its route and the desk bridge never publishes
 * on unmount, so the selection is untouched; and a **guarded sheet declines** — the navigation is
 * refused and a toast with a button that goes says why, rather than a half-edited form leaving
 * with the page. The wire carries no sender, so the toast says *another window* rather than
 * naming it.
 *
 * A `windows.viewOptions` is applied **for that view only** (busk-further plan §3.5): the frame
 * names the view the sender believed the target was on, and a window that has moved since — a
 * busk frame arriving on the Prompt Book — ignores it rather than storing a fact for a view it is
 * not showing. The view is read at command time through a ref, because a *Show Busk on X · Pads*
 * from ⌘K is two frames in a row and the second must see the route the first moved this window
 * to. Applying is `lib/buskWindow.ts`'s for the busk keys and `lib/immersive.ts`'s for
 * `immersive`, which any of the four live views takes; the announce effect re-announces whatever
 * moved. Immersive is a window's fact rather than a view's, and the per-view gate still applies
 * to it on purpose: the frame is a statement about the view the sender was looking at. The busk
 * keys include the page's two (desk-follow plan D6): `page` unlinks this window onto that page, and
 * `pageFollows` pages it with the desk again (`'true'`) or keeps the page it is showing as its own
 * (`'false'`) — the Screens row's Page segment, and ⌘K's page pair.
 *
 * A `windows.rename` is **not applied server-side**: the target renames itself here and the
 * effect above re-announces, which is what makes the new name survive that tab's reload.
 * `windows.fullscreen {on:false}` exits at once (no gesture needed); `{on:true}` cannot call
 * `requestFullscreen` without one, so it raises the *Return to full screen* banner instead.
 *
 * A `windows.follow` (desk-follow plan D4) links or unlinks this window's selection: `on` is
 * `relinkToDesk`, the desk chip's own press; `off` is `unlinkFromDesk` over the desk's fact as it
 * stands, ⌘K's gesture — **refused** while this window is on the busk view in Rig or Pads focus
 * (`followIsForced`, D2), where the Screens row that sent it was stale. An `off` for a window that
 * is already local is a no-op rather than a re-snapshot, which would silently replace the selection
 * the operator built with the desk's. **The window re-announces either way**: a changed flag moves
 * the announce effect above, and an unchanged one — a refusal, a no-op — re-sends the last announce,
 * so a row the sender drew from a stale frame corrects itself rather than waiting for the next
 * unrelated change.
 */
export function useWindowsBridge(): void {
  const location = useLocation()
  const navigate = useNavigate()
  const name = useWindowName()
  const follows = useDeskFollow()
  const { active: fullscreen } = useFullscreenState()
  const view = location.pathname
  const buskOptions = useBuskViewOptions()
  const immersive = useImmersive()
  const announced = announcedViewOptions(windowViewOf(view), buskOptions, immersive ? 'on' : 'off')
  // A string key rather than the object: the hook mints a fresh map per render, and the effect
  // must re-run only when a value moves.
  const optionsKey = announced == null ? null : JSON.stringify(announced)

  useEffect(() => {
    announceThisWindow({
      name,
      view,
      fullscreen,
      follows,
      ...(optionsKey == null ? {} : { viewOptions: JSON.parse(optionsKey) as Record<string, string> }),
    })
  }, [name, view, fullscreen, follows, optionsKey])

  // Written on commit, never during render: the router's navigations are transitions, and a
  // render React abandons must not leave the ref naming a route this window never showed. The
  // frames it serves arrive as separate socket messages in separate tasks, long after the commit.
  const viewRef = useRef(view)
  useEffect(() => {
    viewRef.current = view
  }, [view])

  useEffect(() => {
    const subscription = lightingApi.windows.subscribeCommands((command) => {
      handleWindowCommand(command, {
        myRowId: thisWindowRowId(),
        currentView: viewRef.current,
        navigate: (to) => void navigate(to),
      })
    })
    return () => subscription.unsubscribe()
  }, [navigate])

  useFullscreenShortcut()
}

export interface WindowCommandContext {
  /** This tab's row id from the last `windows.state`, or null before it has one. */
  myRowId: string | null
  /** The route this window is showing *now* — what a `viewOptions` frame's `view` is held to. */
  currentView: string
  navigate: (to: string) => void
  /** Test seams; production reads the real modules. */
  unsaved?: () => boolean
  rename?: (name: string) => boolean
  exit?: () => void
  askToReturn?: () => void
  /** Apply a view's options; answers false for a view that contributes none. */
  applyViewOptions?: (viewId: string, options: Readonly<Record<string, string>>) => boolean
  /** This window's stored busk focus, read at command time. */
  focus?: () => string
  /** Whether this window follows the desk's selection now. */
  following?: () => boolean
  /** Link this window's selection to the desk's (`true`) or take the desk's as its own (`false`). */
  setFollow?: (on: boolean) => void
  /** Re-send the last announce, unchanged. */
  reannounce?: () => void
}

function reannounceThisWindow(): void {
  const last = lightingApi.windows.lastAnnounce()
  if (last != null) lightingApi.windows.announce(last)
}

/**
 * Apply a frame's options for [viewId]: `immersive` on any view that contributes options (every
 * live view), the busk facts on the busk view. False for a view that contributes none, so a frame
 * aimed at a library is ignored rather than half-applied.
 */
function applyViewOptionsFor(viewId: string, options: Readonly<Record<string, string>>): boolean {
  const view = WINDOW_VIEWS.find((v) => v.id === viewId)
  if (view?.options == null) return false
  applyImmersiveViewOption(options)
  if (viewId === 'busk') applyBuskViewOptions(options)
  return true
}

/** Sonner id for the decline, so a second `show` replaces the toast rather than stacking one. */
export const WINDOW_SHOW_DECLINED_TOAST_ID = 'window-show-declined'

/**
 * Act on one rebroadcast command. Exported so the rules above can be pinned without a router:
 * returns what was done, for the tests.
 */
export function handleWindowCommand(
  command: WindowCommand,
  context: WindowCommandContext,
): 'ignored' | 'navigated' | 'declined' | 'renamed' | 'exited' | 'asked' | 'applied' | 'followed' | 'refused' {
  if (context.myRowId == null || command.targetId !== context.myRowId) return 'ignored'
  switch (command.type) {
    case 'show': {
      const unsaved = context.unsaved ?? hasUnsavedSheets
      if (unsaved()) {
        const label = windowViewLabel(command.view)
        toast(`Another window asked to show ${label} — you have unsaved changes`, {
          id: WINDOW_SHOW_DECLINED_TOAST_ID,
          // This toast only ever shows while a guarded sheet is open, and every sheet is a Radix
          // *modal* dialog, which sets `pointer-events: none` on `<body>`; sonner's toaster is an
          // ordinary sibling in the tree and inherits it, so without this the button draws but
          // cannot be clicked — found at the desk, not by the unit test, which calls `onClick`.
          className: 'pointer-events-auto',
          action: { label: `Go to ${label}`, onClick: () => context.navigate(command.view) },
        })
        return 'declined'
      }
      context.navigate(command.view)
      return 'navigated'
    }
    case 'rename':
      ;(context.rename ?? renameWindow)(command.name)
      return 'renamed'
    case 'fullscreen':
      if (command.on) {
        ;(context.askToReturn ?? requestReturnToFullscreen)()
        return 'asked'
      }
      ;(context.exit ?? (() => void exitFullscreen()))()
      return 'exited'
    case 'viewOptions': {
      const named = windowViewOf(command.view)
      const showing = windowViewOf(context.currentView)
      if (named == null || showing == null || named.id !== showing.id) return 'ignored'
      return (context.applyViewOptions ?? applyViewOptionsFor)(named.id, command.options) ? 'applied' : 'ignored'
    }
    case 'follow': {
      const reannounce = context.reannounce ?? reannounceThisWindow
      const forced = followIsForced(windowViewOf(context.currentView)?.id, (context.focus ?? getBuskFocus)())
      if (!command.on && forced) {
        reannounce()
        return 'refused'
      }
      // Already where it was asked to be: nothing to apply — an `off` here would re-snapshot the desk
      // over the operator's own selection — and nothing moves the announce effect, so the unchanged
      // flag is re-sent here and a stale row corrects itself. A flag that does move re-announces
      // through that effect.
      if (command.on === (context.following ?? isFollowingDesk)()) {
        reannounce()
        return 'followed'
      }
      ;(context.setFollow ?? ((on: boolean) => (on ? relinkToDesk() : unlinkFromDeskNow())))(command.on)
      return 'followed'
    }
  }
}

/**
 * ⇧F, the user-menu item's advertised key. Toggles: full screen from a gesture is exactly what a
 * keypress is.
 *
 * **On `document`, in the capture phase — a deliberate slot, not a default.** Every other keyboard
 * listener on this desk is on `window`: the sheet kit's is capture (`useSheetKeyboard`) and
 * `FixturesListContainer`'s is bubble, and the container's re-binds on every marquee change, so
 * two listeners on the same target and phase decide by registration order — which flipped with
 * how the operator arrived on the page. The event path puts window-capture before
 * document-capture before window-bubble whatever the order of registration, so this runs *after*
 * the kit has had its say (a typed `F` in a cue cell is claimed there, with `preventDefault`, and
 * this stands aside on `defaultPrevented`) and *before* the programmer grid's bubble listener,
 * which now stands aside on `defaultPrevented` in turn. Never from an editable target, where a
 * capital F is a letter.
 */
function useFullscreenShortcut(): void {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.key !== 'F' || !e.shiftKey || e.metaKey || e.ctrlKey || e.altKey) return
      if (isEditableTarget(e.target as Element | null)) return
      e.preventDefault()
      if (document.fullscreenElement != null) void exitFullscreen()
      else void enterFullscreen()
    }
    document.addEventListener('keydown', handler, true)
    return () => document.removeEventListener('keydown', handler, true)
  }, [])
}
