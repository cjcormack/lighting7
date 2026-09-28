import { useMemo } from "react"
import { toast } from "sonner"
import {
  Anchor,
  Braces,
  Sparkles,
  LayoutGrid,
  Layers,
  AudioWaveform,
  Gauge,
  BookOpenText,
  Box,
  Boxes,
  SlidersHorizontal,
  SlidersVertical,
  Theater,
  Cloud,
  Settings,
  Computer,
  TableProperties,
  Sliders,
  SwatchBook,
  Palette,
  Activity,
  Users,
  Maximize2,
  Minimize2,
  MonitorSmartphone,
  MonitorUp,
  Link2,
  Unlink2,
  Rows2,
  Lightbulb,
 Moon, Sun } from "lucide-react"
import { useLocation } from "react-router"
import type { LucideIcon } from "lucide-react"
import { useAuthStatusQuery } from "./store/auth"
import { useGetUniverseQuery } from "./store/universes"
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, familySlug } from "./lib/attributeFamily"
import type { DeskWindow } from "./api/windowsApi"
import { WINDOW_VIEWS, projectIdOfPath, windowViewOf, windowViewPath } from "./lib/windowViews"
import { BUSK_FOCUSES, VIEW_OPTION_PAGE_FOLLOWS, setBuskFocus, useBuskFocus, type BuskFocus } from "./lib/buskWindow"
import { canFullscreen, enterFullscreen, exitFullscreen, useFullscreenState } from "./lib/fullscreen"
import { VIEW_OPTION_IMMERSIVE, setImmersive, useImmersive } from "./lib/immersive"
import { toggleTheme, useTheme, type Theme } from "./lib/theme"
import { isLiveViewPath } from "./lib/liveViews"
import { followIsForced, relinkToDesk, useDeskFollow, viewHasOwnSelection } from "./lib/deskFollow"
import { setWindowFollow, setWindowViewOptions, showOnWindow, thisWindowRow, useDeskWindows } from "./store/windows"
import { unlinkFromDeskNow } from "./store/selection"
import { openScreensSheet } from "./components/screens/screensSheetState"
import { canChooseDisplay, listDisplays, newWindowUrl, nextScreenName, openWindowOn } from "./lib/screens"

export type NavGroup = "setup" | "program" | "live" | "monitor" | "settings" | "install"

export interface NavItem {
  id: string
  label: string
  icon: LucideIcon
  path: (projectId: number) => string
  /** Controls when this item is shown based on project status */
  visibility: "always" | "active-only" | "inactive-only"
  /** Used to match active state against the current pathname */
  pathMatch: string
  /** Workflow group this item belongs to; used by the sidebar to insert separators. */
  group: NavGroup
  /** Optional parent item id; sub-items render indented beneath their parent. */
  parent?: string
  /**
   * Hidden from OPERATOR accounts. Set it wherever the destination's API is behind the
   * backend's admin gate (`ADMIN_ONLY_PREFIXES` / the per-project sync subtree in
   * `auth/AuthGate.kt`), so neither the sidebar nor Cmd+K offers a page that can only
   * answer 403. Not a permission check — the backend is that.
   */
  adminOnly?: boolean
}

/**
 * Shared navigation registry consumed by both the sidebar and command palette.
 * When adding a new page/route, add an entry here and it will automatically
 * appear in both the sidebar navigation and the Cmd+K command palette.
 *
 * Items are grouped by workflow phase (setup → program → live → monitor). The
 * sidebar renders a thin separator between groups; the order within each group
 * is preserved as declared below. "live" is the four show modes and nothing
 * else — it is ordered to match `components/ViewSwitcher.tsx`.
 */
export const navItems: NavItem[] = [
  // ── Setup ───────────────────────────────────────────────────────────
  {
    id: "patches",
    label: "Patch List",
    icon: TableProperties,
    path: (p) => `/projects/${p}/patches`,
    visibility: "always",
    pathMatch: "/patches",
    group: "setup",
  },
  {
    id: "fixtures",
    label: "Fixtures",
    icon: LayoutGrid,
    path: (p) => `/projects/${p}/fixtures`,
    visibility: "active-only",
    pathMatch: "/fixtures",
    group: "setup",
  },
  {
    id: "groups",
    label: "Groups",
    icon: Layers,
    path: (p) => `/projects/${p}/groups`,
    visibility: "active-only",
    pathMatch: "/groups",
    group: "setup",
  },

  // ── Program ─────────────────────────────────────────────────────────
  {
    id: "scripts",
    label: "Scripts",
    icon: Braces,
    path: (p) => `/projects/${p}/scripts`,
    visibility: "always",
    pathMatch: "/scripts",
    group: "program",
  },
  {
    id: "fx-library",
    label: "FX Library",
    icon: Sparkles,
    path: (p) => `/projects/${p}/fx-library`,
    visibility: "always",
    pathMatch: "/fx-library",
    group: "program",
  },
  {
    id: "looks",
    // Named states over named fixtures, applied to a cue as layers. Every one is **recorded** — from
    // the programmer, or by promoting a selection — which is why the page has no New button.
    //
    // **One entry, one route, and no family filter.** The filter that used to live here moved to
    // `/templates` in session 3, where a family really is an exact partition; a Look spans families
    // by nature, so filtering by one would hide most of the library from most filters.
    label: "Looks",
    icon: SwatchBook,
    path: (p) => `/projects/${p}/looks`,
    visibility: "always",
    pathMatch: "/looks",
    group: "program",
  },
  {
    id: "templates",
    // Named values you build looks and cues out of: one attribute family each, no targets of their
    // own, applied to a selection. The other half of what `/looks` used to hold — see
    // `models/templates.kt` for why the two are separate entities rather than one with a flag.
    //
    // **One entry, one route, with a sticky family filter.** Sibling routes would now be legitimate
    // (a template is in exactly one family, so they would partition it exactly), and this is still
    // one route: the filter is a *view* of a small library rather than a division of it, and
    // `useTemplateFamilyNavItems` gives Cmd+K the four deep links as `?family=` params either way.
    label: "Templates",
    icon: Palette,
    path: (p) => `/projects/${p}/templates`,
    visibility: "always",
    pathMatch: "/templates",
    group: "program",
  },
  {
    id: "speed-masters",
    // The tempo buses effects subscribe to. One entry, one route — no cards/list pair and no
    // type switcher, so none of the sibling-route exceptions in CLAUDE.md apply here.
    label: "Speed Masters",
    icon: Gauge,
    path: (p) => `/projects/${p}/speed-masters`,
    visibility: "always",
    pathMatch: "/speed-masters",
    group: "program",
  },

  // ── Live ────────────────────────────────────────────────────────────
  // The four live views, in the order `ViewSwitcher` shows them — Programmer · Show · Prompt Book ·
  // Busk. `components/ViewSwitcher.tsx` owns that order and states the reasoning ("the pills run in
  // the order the work does"); this list follows it, so change one and change the other.
  // Stage and Channels are NOT show modes and live in the Monitor group below.
  {
    id: "programmer",
    // The programmer: values, layers and effects on ONE screen. It was a page, then three tabs of
    // a collapsed pane inside Program with no nav entry of its own, and is a page again — three
    // readings of one live object that could never be seen together is the problem that move
    // solved. The `/program` vs `/programmer` near-collision that argued against a second entry
    // last time went away when Program was renamed to Show.
    label: "Programmer",
    icon: SlidersVertical,
    path: (p) => `/projects/${p}/programmer`,
    visibility: "active-only",
    pathMatch: "/programmer",
    group: "live",
  },
  {
    // `id` stays "program" — it is the stable handle, and `navigation.test.ts` looks entries up by
    // it. Only the name, the path and the match moved.
    id: "program",
    // Show is the cue/stack authoring + running surface (it absorbed the FX Cues view). It was
    // called Program until the programmer moved out of it; two live views one letter apart was
    // exactly the collision that kept the programmer out of this list.
    // Scoped to the active project — running cues from here needs the project live.
    label: "Show",
    icon: Theater,
    path: (p) => `/projects/${p}/show`,
    visibility: "active-only",
    pathMatch: "/show",
    group: "live",
  },
  {
    id: "prompt-book",
    label: "Prompt Book",
    icon: BookOpenText,
    path: (p) => `/projects/${p}/prompt-book`,
    visibility: "active-only",
    pathMatch: "/prompt-book",
    group: "live",
  },
  {
    // `id` stays "fx" — the stable handle, the same call `program` made when Show was renamed.
    // This is one destination under a new name, not a new one.
    id: "fx",
    // Busk: the pad-first performance surface, and the fourth live view. It was "FX", which named
    // the machinery rather than the job and sat one hyphen from `/fx-library` — a genuinely
    // different destination that the route match here got wrong twice.
    label: "Busk",
    icon: AudioWaveform,
    path: (p) => `/projects/${p}/busk`,
    visibility: "active-only",
    pathMatch: "/busk",
    group: "live",
  },

  // ── Monitor ─────────────────────────────────────────────────────────
  // What the rig is actually doing, rather than a surface you drive it from. Their own section
  // below the live views: they sat interleaved with the four show modes, which made neither group
  // readable. Both are active-only, so this section disappears with the live one on an inactive
  // project and its separator collapses with it.
  {
    id: "stage-view",
    label: "Stage",
    icon: Boxes,
    path: (p) => `/projects/${p}/stage`,
    visibility: "active-only",
    pathMatch: "/stage",
    group: "monitor",
  },
  {
    id: "channels",
    label: "Channels",
    icon: SlidersHorizontal,
    // No universe in the path: the route resolves the project's first patched universe
    // (rigs don't always start at 0).
    path: (p) => `/projects/${p}/channels`,
    visibility: "active-only",
    pathMatch: "/channels",
    group: "monitor",
  },

  // ── Settings (per-project) ──────────────────────────────────────────
  // The parent lands on the General tab; the children deep-link to their
  // sibling tabs so common destinations are one click away from the sidebar.
  // The patch list is not one of them any more: it is a routed page under
  // Setup (list-shell-design, called 2026-09-15).
  {
    id: "project-settings",
    label: "Project Settings",
    icon: Settings,
    path: (p) => `/projects/${p}/settings`,
    visibility: "always",
    pathMatch: "/settings",
    group: "settings",
  },
  {
    id: "surfaces",
    label: "Surfaces",
    icon: Sliders,
    path: (p) => `/projects/${p}/settings/surfaces`,
    visibility: "active-only",
    pathMatch: "/settings/surfaces",
    group: "settings",
    parent: "project-settings",
  },
  {
    id: "stage",
    label: "Regions",
    icon: Box,
    path: (p) => `/projects/${p}/settings/stage`,
    visibility: "always",
    pathMatch: "/settings/stage",
    group: "settings",
    parent: "project-settings",
  },
  {
    id: "rigging",
    label: "Rigging",
    icon: Anchor,
    path: (p) => `/projects/${p}/settings/rigging`,
    visibility: "always",
    pathMatch: "/settings/rigging",
    group: "settings",
    parent: "project-settings",
  },
  {
    id: "project-sync",
    label: "Sync",
    icon: Cloud,
    path: (p) => `/projects/${p}/settings/sync`,
    visibility: "always",
    pathMatch: "/settings/sync",
    group: "settings",
    parent: "project-settings",
    adminOnly: true,
  },

  // ── Install (no project context) ────────────────────────────────────
  // Path resolvers ignore the projectId arg — these routes are install-scope.
  {
    id: "install-settings",
    label: "Install Settings",
    icon: Computer,
    path: () => "/install",
    visibility: "always",
    pathMatch: "/install",
    group: "install",
  },
  {
    id: "users",
    label: "Users",
    icon: Users,
    path: () => "/install/users",
    visibility: "always",
    pathMatch: "/install/users",
    group: "install",
    parent: "install-settings",
    adminOnly: true,
  },
  {
    id: "sync",
    label: "Sync",
    icon: Cloud,
    path: () => "/install/sync",
    visibility: "always",
    pathMatch: "/install/sync",
    group: "install",
    parent: "install-settings",
    adminOnly: true,
  },
  {
    id: "diagnostics",
    label: "Diagnostics",
    icon: Activity,
    path: () => "/install/diagnostics",
    visibility: "always",
    pathMatch: "/install/diagnostics",
    group: "install",
    parent: "install-settings",
  },
]

/** Returns the static navigation items. Used by the sidebar. */
export function useNavItems(): NavItem[] {
  return navItems
}

/**
 * Returns per-universe navigation items ("Universe 0", "Universe 1", …).
 * Only consumed by the Cmd+K command palette so power users can jump
 * directly to a specific universe; the sidebar shows a single "Channels"
 * entry instead — which is why these carry that row's "monitor" group.
 */
export function useUniverseNavItems(): NavItem[] {
  const { data: universes } = useGetUniverseQuery()

  return useMemo(
    () =>
      (universes ?? []).map((universe) => ({
        id: `universe-${universe}`,
        label: `Universe ${universe}`,
        icon: SlidersHorizontal,
        path: (p: number) => `/projects/${p}/channels/${universe}`,
        visibility: "active-only" as const,
        pathMatch: `/channels/${universe}`,
        group: "monitor" as const,
      })),
    [universes],
  )
}

/**
 * Returns one item per attribute family ("Colour Templates", "Position Templates", …).
 *
 * Cmd+K only, on the [useUniverseNavItems] precedent: the sidebar keeps its single "Templates" row
 * and the in-page filter moves between families, but jumping straight to the position bank is exactly
 * the kind of thing the command palette is for.
 *
 * These moved here from `/looks` in session 3, following the filter itself. They are **query params
 * on one route**, not four routes, and `pathMatch` is the bare `/templates`, so the sidebar
 * highlights the one row whichever family you arrived in.
 */
export const templateFamilyNavItems: NavItem[] = ATTRIBUTE_FAMILIES.map((family) => ({
  // Prefixed rather than bare, so these can never collide with the static `templates` id.
  id: `templates-${familySlug(family)}`,
  label: `${FAMILY_LABELS[family].singular} Templates`,
  icon: Palette,
  path: (p: number) => `/projects/${p}/templates?family=${familySlug(family)}`,
  visibility: "always" as const,
  // The bare path, not `/templates/${slug}` — see above. `navigation.test.ts` asserts this against
  // the real array, which is why the array is module-scope rather than built inside the hook.
  pathMatch: "/templates",
  group: "program" as const,
}))

export function useTemplateFamilyNavItems(): NavItem[] {
  return templateFamilyNavItems
}

/**
 * Whether admin-only nav items should be offered.
 *
 * Anything other than a resolved OPERATOR counts as admin: while `auth/status` is still
 * in flight, and on a bootstrap-open desk with no accounts at all, the API really is
 * reachable — hiding Sync from an admin for one round-trip would be the more visible bug,
 * and the backend refuses the call either way.
 */
export function useIsNavAdmin(): boolean {
  const { data } = useAuthStatusQuery()
  return data?.user?.role !== "OPERATOR"
}

/**
 * Filter nav items by project-activity visibility and, for [NavItem.adminOnly] entries,
 * by role. [isAdmin] defaults to true so callers that predate roles — and tests exercising
 * visibility alone — keep their existing behaviour.
 */
export function filterNavItems(
  items: NavItem[],
  isViewingActiveProject: boolean,
  isAdmin: boolean = true,
): NavItem[] {
  return items.filter((item) => {
    if (item.adminOnly && !isAdmin) return false
    if (item.visibility === "always") return true
    if (item.visibility === "active-only") return isViewingActiveProject
    if (item.visibility === "inactive-only") return !isViewingActiveProject
    return true
  })
}

/**
 * A ⌘K **action** about windows — not a [NavItem], because most of these move *another* window
 * or change this one's framing, and neither is a path this window navigates to. `id` and `label`
 * are what the palette lists and `navigation.test.ts` pins; `detail` is the trailing hint
 * (*switches that window*, *on*); `run` is the gesture.
 */
export interface WindowCommand {
  id: string
  label: string
  icon: LucideIcon
  keywords: string[]
  detail?: string
  run: () => void
}

export interface WindowCommandInputs {
  /** Every registry row, this window's included. */
  windows: readonly DeskWindow[]
  /** This tab's row id, or null before its announce has landed. */
  thisRowId: string | null
  /** The project a *Show <view> on <window>* lands in when the target's own view names none. */
  projectId: number | null
  fullscreen: boolean
  /** The browser has the Fullscreen API at all (Safari iPhone does not). */
  canFullscreen: boolean
  /** Chrome's Window Management API is present (secure context, `getScreenDetails`). */
  canOpenOnDisplay: boolean
  following: boolean
  /** This window's busk focus while it is on the busk view; null elsewhere, and no focus items. */
  buskFocus: BuskFocus | null
  /**
   * Whether this window is immersive, while it is on one of the four live views; null elsewhere,
   * and no item (busk-chrome plan D7) — the fact only shows on a live view, so setting it from a
   * library would be a silent write for a later visit.
   */
  immersive: boolean | null
  /** The browser's theme — light or dark — for the label of the toggle. */
  theme: Theme
  actions: {
    enterFullscreen: () => void
    exitFullscreen: () => void
    /** This window's immersive fact (busk-chrome plan D7). */
    setImmersive: (on: boolean) => void
    /** The theme, one store for the user menu and this (`lib/theme.ts`). */
    toggleTheme: () => void
    openScreens: () => void
    show: (targetId: string, view: string) => void
    openOnDisplay: (view: string) => void
    follow: () => void
    unlink: () => void
    /** This window's busk focus (busk-further plan D5). */
    setFocus: (focus: BuskFocus) => void
    /** Another window's view options, by row id and the view it will be showing. */
    setViewOptions: (targetId: string, view: string, options: Record<string, string>) => void
    /** Link (`true`) or unlink another window's selection, by row id (`windows.follow`). */
    setFollow: (targetId: string, on: boolean) => void
  }
}

const FOCUS_LABELS: Record<BuskFocus, string> = { split: "Split", pads: "Focus pads", rig: "Focus rig" }

/**
 * The window commands, built the way [templateFamilyNavItems] is built — from a vocabulary, in a
 * fixed order — so a test can pin the shapes without a store (`Screens.dc.html` §3):
 *
 * - *Go full screen* / *Exit full screen* (⇧F), only where the API exists;
 * - *Expand over the app* / *Show the app* — this window's immersive fact, flipping with the state
 *   like the pair above, only while it is on a live view (busk-chrome plan D7, D8: not full screen,
 *   and the two compose);
 * - *Screens…*;
 * - *Switch to dark mode* / *Switch to light mode* — the user menu's theme row as a command, so an
 *   immersive window, which draws no user menu, still has it (busk-chrome plan D10: "theme, full
 *   screen and Screens… are ⌘K's"); the label names the theme it will switch *to*, as the menu
 *   row does;
 * - *Show <view> on <window>* for every **other** window × the six views — this window has the
 *   Navigation group already, and a row whose view names no project is skipped rather than sent
 *   somewhere half-addressed;
 * - … and a *· Immersive* arm on every live view, the same two frames with `{immersive: 'on'}`
 *   (busk-chrome plan D9);
 * - *Open <view> on another display* per view, Chrome only (the display itself is chosen in the
 *   prompt the gesture opens, since `getScreenDetails` needs one);
 * - *<Window> · follow the desk selection* / *<Window> · own selection* for every **other** window
 *   on the Busk or Programmer view — the two places a selection of its own means something
 *   (desk-follow plan D1, D4) — flipping with that row's announced flag, mirroring *Show <view> on
 *   <window>*; the *own selection* arm is withheld for a busk row in Rig or Pads focus
 *   (`followIsForced`), where the target would refuse it;
 * - *<Window> · page with the desk* / *<Window> · own page* for every **other** busk window
 *   (desk-follow plan D6), flipping with that row's announced `pageFollows` the same way, written
 *   as a `windows.viewOptions` frame under the row's view;
 * - *Follow the desk selection in this window*, with its state as the detail — and *Stop
 *   following…* withheld in Rig and Pads focus, for the same reason (D4).
 */
export function buildWindowCommands(inputs: WindowCommandInputs): WindowCommand[] {
  const commands: WindowCommand[] = []
  const { actions } = inputs

  if (inputs.canFullscreen) {
    commands.push(
      inputs.fullscreen
        ? { id: "window-fullscreen-exit", label: "Exit full screen", icon: Minimize2, keywords: ["fullscreen", "window", "screen", "esc"], detail: "⇧F", run: actions.exitFullscreen }
        : { id: "window-fullscreen", label: "Go full screen", icon: Maximize2, keywords: ["fullscreen", "window", "screen", "kiosk"], detail: "⇧F", run: actions.enterFullscreen },
    )
  }
  // Immersive is not full screen and the two compose (D8), so this is a second pair beside the
  // first rather than an arm of it; the label flips with the state for the same reason the
  // full-screen pair's does.
  if (inputs.immersive != null) {
    commands.push(
      inputs.immersive
        ? { id: "window-immersive-off", label: "Show the app", icon: Minimize2, keywords: ["immersive", "app", "sidebar", "header", "chrome", "window", "screen"], detail: "this window", run: () => actions.setImmersive(false) }
        : { id: "window-immersive", label: "Expand over the app", icon: Maximize2, keywords: ["immersive", "expand", "app", "sidebar", "header", "chrome", "window", "screen"], detail: "this window", run: () => actions.setImmersive(true) },
    )
  }
  commands.push({
    id: "window-screens",
    label: "Screens…",
    icon: MonitorSmartphone,
    keywords: ["windows", "screens", "display", "monitor", "ipad"],
    detail: `${inputs.windows.length} ${inputs.windows.length === 1 ? "window" : "windows"}`,
    run: actions.openScreens,
  })

  commands.push({
    id: "window-theme",
    label: inputs.theme === "light" ? "Switch to dark mode" : "Switch to light mode",
    icon: inputs.theme === "light" ? Moon : Sun,
    keywords: ["theme", "dark", "light", "mode", "appearance", "colour scheme"],
    detail: "this browser",
    run: actions.toggleTheme,
  })

  // This window's busk focus — Split · Focus pads · Focus rig — only while it is on the busk view:
  // setting a fact for a view the window is not showing would be a silent write for a later visit.
  if (inputs.buskFocus != null) {
    for (const focus of BUSK_FOCUSES) {
      commands.push({
        id: `window-focus-${focus}`,
        label: FOCUS_LABELS[focus],
        icon: focus === "split" ? Rows2 : focus === "pads" ? LayoutGrid : Lightbulb,
        keywords: ["focus", "busk", "rig", "pads", "split", "window", "screen"],
        detail: focus === inputs.buskFocus ? "current" : "this window",
        run: () => actions.setFocus(focus),
      })
    }
  }

  for (const row of inputs.windows) {
    if (row.id === inputs.thisRowId) continue
    const projectId = projectIdOfPath(row.view) ?? inputs.projectId
    if (projectId == null) continue
    for (const view of WINDOW_VIEWS) {
      const path = windowViewPath(view, projectId)
      commands.push({
        id: `window-show-${row.id}-${view.id}`,
        label: `Show ${view.label} on ${row.name}`,
        icon: MonitorSmartphone,
        keywords: ["show", "window", "screen", view.label, row.name],
        detail: "switches that window",
        run: () => actions.show(row.id, path),
      })
      // The focus arm: a show followed by that window's focus, two frames the target takes in
      // order (the second is applied only once it is on the view the first moved it to).
      if (view.options?.some((option) => option.key === "focus")) {
        for (const focus of BUSK_FOCUSES) {
          commands.push({
            id: `window-show-${row.id}-${view.id}-${focus}`,
            label: `Show ${view.label} on ${row.name} · ${FOCUS_LABELS[focus]}`,
            icon: MonitorSmartphone,
            keywords: ["show", "window", "screen", "focus", focus, view.label, row.name],
            detail: "switches that window and its focus",
            run: () => {
              actions.show(row.id, path)
              actions.setViewOptions(row.id, path, { focus })
            },
          })
        }
      }
      // The immersive arm, on every live view (busk-chrome plan D9): the same two frames, the
      // second carrying the window's fact rather than a view's.
      if (view.options?.some((option) => option.key === VIEW_OPTION_IMMERSIVE)) {
        commands.push({
          id: `window-show-${row.id}-${view.id}-immersive`,
          label: `Show ${view.label} on ${row.name} · Immersive`,
          icon: MonitorSmartphone,
          keywords: ["show", "window", "screen", "immersive", "expand", view.label, row.name],
          detail: "switches that window and expands it over the app",
          run: () => {
            actions.show(row.id, path)
            actions.setViewOptions(row.id, path, { [VIEW_OPTION_IMMERSIVE]: "on" })
          },
        })
      }
    }
  }

  if (inputs.canOpenOnDisplay && inputs.projectId != null) {
    const projectId = inputs.projectId
    for (const view of WINDOW_VIEWS) {
      commands.push({
        id: `window-open-${view.id}`,
        label: `Open ${view.label} on another display`,
        icon: MonitorUp,
        keywords: ["open", "display", "monitor", "window", "screen", view.label],
        run: () => actions.openOnDisplay(windowViewPath(view, projectId)),
      })
    }
  }

  // Another window's selection follow (D4) — its flag as it announced it, so the arm offered is
  // the one that changes something, and nothing for a row whose view has no selection to follow.
  for (const row of inputs.windows) {
    if (row.id === inputs.thisRowId) continue
    const viewId = windowViewOf(row.view)?.id
    if (!viewHasOwnSelection(viewId)) continue
    if (row.follows) {
      if (followIsForced(viewId, row.viewOptions?.focus)) continue
      commands.push({
        id: `window-follow-${row.id}-off`,
        label: `${row.name} · own selection`,
        icon: Unlink2,
        keywords: ["follow", "desk", "selection", "local", "unlink", "own", "screen", "window", row.name],
        detail: "follows the desk",
        run: () => actions.setFollow(row.id, false),
      })
    } else {
      commands.push({
        id: `window-follow-${row.id}-on`,
        label: `${row.name} · follow the desk selection`,
        icon: Link2,
        keywords: ["follow", "desk", "selection", "link", "screen", "window", row.name],
        detail: "own selection",
        run: () => actions.setFollow(row.id, true),
      })
    }
  }

  // Another busk window's page (desk-follow plan D6) — the pair as the Screens row's Page segment
  // offers it, by the flag the row announced, so the arm offered is the one that changes
  // something. Written as a `viewOptions` frame under the row's own view, which the target applies
  // only while it is still on the busk view. A row that has not announced the flag counts as
  // paged with, as its own tab reads it.
  for (const row of inputs.windows) {
    if (row.id === inputs.thisRowId || windowViewOf(row.view)?.id !== "busk") continue
    const paged = row.viewOptions?.[VIEW_OPTION_PAGE_FOLLOWS] !== "false"
    commands.push(
      paged
        ? {
            id: `window-page-${row.id}-own`,
            label: `${row.name} · own page`,
            icon: Unlink2,
            keywords: ["page", "busk", "own", "local", "unlink", "paging", "screen", "window", row.name],
            detail: "paged with the desk",
            run: () => actions.setViewOptions(row.id, row.view, { [VIEW_OPTION_PAGE_FOLLOWS]: "false" }),
          }
        : {
            id: `window-page-${row.id}-desk`,
            label: `${row.name} · page with the desk`,
            icon: Link2,
            keywords: ["page", "busk", "desk", "link", "paging", "group", "screen", "window", row.name],
            detail: "own page",
            run: () => actions.setViewOptions(row.id, row.view, { [VIEW_OPTION_PAGE_FOLLOWS]: "true" }),
          },
    )
  }

  // The label flips with the state, like the full-screen pair above: an item that read *Follow…*
  // on a following window and unlinked it said the opposite of what it did. *Stop following* is
  // withheld where this window's focus forces following — it would only be undone at once.
  // `buskFocus` is null off the busk view (see the input's docblock), which the rule reads as free.
  if (!inputs.following || !followIsForced("busk", inputs.buskFocus)) {
    commands.push({
      id: "window-follow",
      label: inputs.following
        ? "Stop following the desk selection in this window"
        : "Follow the desk selection in this window",
      icon: inputs.following ? Link2 : Unlink2,
      keywords: ["follow", "desk", "selection", "local", "unlink", "link", "screen", "window"],
      detail: inputs.following ? "on" : "off",
      run: inputs.following ? actions.unlink : actions.follow,
    })
  }

  return commands
}

/**
 * The window commands for this tab, live. ⌘K only. Reads the registry, this tab's follow flag and
 * full-screen state, and hands [buildWindowCommands] the real gestures.
 */
export function useWindowCommands(projectId: number | null): WindowCommand[] {
  const windows = useDeskWindows()
  const { active: fullscreen } = useFullscreenState()
  const following = useDeskFollow()
  const thisRowId = thisWindowRow(windows)?.id ?? null
  const focus = useBuskFocus()
  const { pathname } = useLocation()
  const onBusk = windowViewOf(pathname)?.id === "busk"
  const buskFocus = onBusk ? focus : null
  const immersiveOn = useImmersive()
  const immersive = isLiveViewPath(pathname) ? immersiveOn : null
  const theme = useTheme()

  return useMemo(
    () =>
      buildWindowCommands({
        windows,
        thisRowId,
        projectId,
        fullscreen,
        canFullscreen: canFullscreen(),
        canOpenOnDisplay: canChooseDisplay(),
        following,
        buskFocus,
        immersive,
        theme,
        actions: {
          enterFullscreen: () => void enterFullscreen(),
          exitFullscreen: () => void exitFullscreen(),
          setImmersive,
          toggleTheme,
          openScreens: openScreensSheet,
          show: showOnWindow,
          openOnDisplay: (view) => void openOnAnotherDisplay(view, windows.map((w) => w.name)),
          follow: relinkToDesk,
          setFocus: setBuskFocus,
          setViewOptions: setWindowViewOptions,
          setFollow: setWindowFollow,
          // The desk's fact is read at press time rather than subscribed: the palette is mounted
          // on every route and closed almost always, and a `selection.state` subscription here
          // would re-render it on every marquee frame for a value only this one press reads.
          unlink: unlinkFromDeskNow,
        },
      }),
    [windows, thisRowId, projectId, fullscreen, following, buskFocus, immersive, theme],
  )
}

/**
 * Open [view] in a new named window on the first display that is not this one (Chrome's Window
 * Management API; the permission prompt is the browser's). With one display there is nowhere to
 * go, and the Screens sheet is where a specific display is picked.
 *
 * One gesture does both halves here, and that has a cost the sheet does not pay: `getScreenDetails`
 * consumes the transient activation — and on first use shows the permission prompt — so by the
 * time the `window.open` runs the popup blocker may refuse it, and **silently**: under `noopener`
 * `window.open` returns null either way, so there is nothing to test. The sheet splits *Choose a
 * display* from *Display N* into two gestures for exactly that reason, and is the route to point an
 * operator at if this one opens nothing.
 */
async function openOnAnotherDisplay(view: string, takenNames: readonly string[]): Promise<void> {
  try {
    const other = (await listDisplays()).find((d) => !d.isCurrent)
    if (other == null) {
      toast("Only one display is attached")
      return
    }
    openWindowOn(other, newWindowUrl(nextScreenName(takenNames), view))
  } catch {
    toast.error("The browser did not allow reading the displays")
  }
}
