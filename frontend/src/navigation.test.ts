// @vitest-environment jsdom
// jsdom provides `window`, which navigation.ts pulls in transitively via
// store/universes → api/lightingApi (it reads window.location at import time).
import { describe, it, expect, vi } from "vitest"
import { Box } from "lucide-react"
import { navItems, templateFamilyNavItems, filterNavItems, type NavItem, buildWindowCommands, type WindowCommandInputs } from "./navigation"
import { ATTRIBUTE_FAMILIES, familySlug } from "./lib/attributeFamily"

/** Minimal NavItem factory for exercising filterNavItems in isolation. */
function makeItem(id: string, visibility: NavItem["visibility"]): NavItem {
  return {
    id,
    label: id,
    icon: Box,
    path: (p) => `/projects/${p}/${id}`,
    visibility,
    pathMatch: `/${id}`,
    group: "live",
  }
}

describe("filterNavItems", () => {
  const items = [
    makeItem("always", "always"),
    makeItem("active", "active-only"),
    makeItem("inactive", "inactive-only"),
  ]

  it("keeps 'always' items regardless of active state", () => {
    expect(filterNavItems(items, true).map((i) => i.id)).toContain("always")
    expect(filterNavItems(items, false).map((i) => i.id)).toContain("always")
  })

  it("shows 'active-only' items only when viewing the active project", () => {
    expect(filterNavItems(items, true).map((i) => i.id)).toContain("active")
    expect(filterNavItems(items, false).map((i) => i.id)).not.toContain("active")
  })

  it("shows 'inactive-only' items only when NOT viewing the active project", () => {
    expect(filterNavItems(items, false).map((i) => i.id)).toContain("inactive")
    expect(filterNavItems(items, true).map((i) => i.id)).not.toContain("inactive")
  })

  it("preserves declaration order and does not mutate the input", () => {
    const before = items.map((i) => i.id)
    const result = filterNavItems(items, true)
    expect(result.map((i) => i.id)).toEqual(["always", "active"])
    expect(items.map((i) => i.id)).toEqual(before)
  })
})

describe("navItems registry", () => {
  it("has unique ids", () => {
    const ids = navItems.map((i) => i.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it("only references parents that exist", () => {
    const ids = new Set(navItems.map((i) => i.id))
    for (const item of navItems) {
      if (item.parent) expect(ids).toContain(item.parent)
    }
  })

  it("resolves project-scoped paths against the given project id", () => {
    for (const item of navItems) {
      const path = item.path(42)
      if (item.group === "install") {
        // Install-scope routes ignore the projectId arg.
        expect(path.startsWith("/install")).toBe(true)
      } else {
        expect(path).toContain("/projects/42")
      }
    }
  })

  // Guards the intent that Stage and Program are only reachable for the
  // active project (they used to be "always"). See the "live" group.
  it("scopes Stage and Program to the active project", () => {
    const stage = navItems.find((i) => i.id === "stage-view")
    const program = navItems.find((i) => i.id === "program")
    expect(stage?.visibility).toBe("active-only")
    expect(program?.visibility).toBe("active-only")
  })

  // The sidebar's four show modes must read in the same order as the in-view switcher's pills
  // (`ShowView` / the JSX order in `components/ViewSwitcher.tsx`, which owns that order and says
  // why). A test cannot read JSX order, so this pins the registry half against the list written
  // out here; the two drifted apart once already, with Stage and Channels interleaved between them.
  it("declares the four live views in ViewSwitcher order", () => {
    // Historic ids: `program` is Show, `fx` is Busk. Both are deliberately stable handles.
    expect(navItems.filter((i) => i.group === "live").map((i) => i.id)).toEqual([
      "programmer",
      "program",
      "prompt-book",
      "fx",
    ])
  })

  // Stage and Channels are monitors, not show modes. The sidebar has no group headings — it draws a
  // separator wherever `group` changes between adjacent visible items — so "their own section
  // below the live views" *is* a distinct group plus declaration order, and both halves are pinned.
  it("puts Stage and Channels in their own section below the live views", () => {
    for (const id of ["stage-view", "channels"]) {
      expect(navItems.find((i) => i.id === id)?.group).toBe("monitor")
    }
    const groups = navItems.map((i) => i.group)
    expect(groups.indexOf("monitor")).toBeGreaterThan(groups.lastIndexOf("live"))
  })

  it("hides Stage/Program (and other active-only items) when viewing a non-active project", () => {
    const ids = filterNavItems(navItems, false).map((i) => i.id)
    expect(ids).not.toContain("stage-view")
    expect(ids).not.toContain("program")
    // Sanity: always-visible items survive the same filter.
    expect(ids).toContain("scripts")
    expect(ids).toContain("install-settings")
  })
})

describe("admin-only navigation", () => {
  // Every entry whose destination is behind the backend's admin gate
  // (ADMIN_ONLY_PREFIXES / the per-project sync subtree in lighting7's auth/AuthGate.kt).
  const adminOnlyIds = ["users", "sync", "project-sync"]

  it("marks the entries whose APIs are admin-gated", () => {
    for (const id of adminOnlyIds) {
      expect(navItems.find((i) => i.id === id)?.adminOnly).toBe(true)
    }
  })

  it("hides them from operators and keeps everything else", () => {
    const ids = filterNavItems(navItems, true, false).map((i) => i.id)
    for (const id of adminOnlyIds) expect(ids).not.toContain(id)
    // The parent they hang off stays: an operator can still read install settings.
    expect(ids).toContain("install-settings")
    expect(ids).toContain("diagnostics")
  })

  it("shows them to admins, and to callers that don't pass a role at all", () => {
    const asAdmin = filterNavItems(navItems, true, true).map((i) => i.id)
    const roleUnknown = filterNavItems(navItems, true).map((i) => i.id)
    for (const id of adminOnlyIds) {
      expect(asAdmin).toContain(id)
      expect(roleUnknown).toContain(id)
    }
  })
})

describe("look and template navigation", () => {
  it("registers exactly one Looks entry, on the bare path", () => {
    const looks = navItems.filter((i) => i.pathMatch.startsWith("/looks"))
    expect(looks.map((i) => i.id)).toEqual(["looks"])
    expect(looks[0].pathMatch).toBe("/looks")
  })

  it("registers no per-family route in the sidebar registry", () => {
    // A Look's families are *derived* and one may span several, so a family cannot own a path at
    // all — the library takes one route with an in-page filter. Anything matching `/looks/`
    // here would be a sub-route that cannot exist.
    expect(navItems.filter((i) => i.pathMatch.includes("/looks/"))).toEqual([])
  })

  it("gives the Cmd+K per-family items ids that can't collide with the sidebar entry", () => {
    // Asserted against the real array, not against ids rebuilt here: an assertion that
    // reconstructs what it is checking passes just as happily when the source is wrong.
    const ids = templateFamilyNavItems.map((i) => i.id)
    expect(ids).toEqual(ATTRIBUTE_FAMILIES.map((family) => `templates-${familySlug(family)}`))
    expect(new Set(ids).size).toBe(ids.length)
    const staticIds = new Set(navItems.map((i) => i.id))
    for (const id of ids) expect(staticIds.has(id)).toBe(false)
  })

  it("keeps the family deep links on the one route, as query params", () => {
    // They are `?family=` on `/templates`, not `/templates/colour`. `pathMatch` therefore stays the
    // bare path so the sidebar highlights its single row whichever family you arrived in — which is
    // what the second half of this pins.
    //
    // The filter and these links moved here from `/looks` in session 3, following the argument that
    // justifies one: a template is in exactly one family, so a family is an exact partition, while a
    // Look spans families and would be hidden from most filters.
    expect(templateFamilyNavItems).toHaveLength(ATTRIBUTE_FAMILIES.length)
    for (const [index, family] of ATTRIBUTE_FAMILIES.entries()) {
      const item = templateFamilyNavItems[index]
      expect(item.path(1)).toBe(`/projects/1/templates?family=${familySlug(family)}`)
      expect(item.pathMatch).toBe("/templates")
    }
  })

  it("gives Looks and Templates separate entries that can't shadow each other", () => {
    // The two libraries are two entities, and their paths are siblings — `/looks` must not match a
    // template route or vice versa. `lib/navMatch.test.ts` pins the matcher itself.
    const looks = navItems.find((i) => i.id === "looks")
    const templates = navItems.find((i) => i.id === "templates")
    expect(looks?.pathMatch).toBe("/looks")
    expect(templates?.pathMatch).toBe("/templates")
    expect(looks?.path(1)).toBe("/projects/1/looks")
    expect(templates?.path(1)).toBe("/projects/1/templates")
  })

  it("gives the programmer and Show separate entries that can't shadow each other", () => {
    // A `programmer` entry was left out of the sidebar once because `/program` and `/programmer`
    // would have collided. Renaming Program to Show removed the collision; these two assertions and
    // `lib/navMatch.test.ts` are what keep it removed.
    const programmer = navItems.find((i) => i.id === "programmer")
    const show = navItems.find((i) => i.id === "program")
    expect(programmer?.pathMatch).toBe("/programmer")
    expect(programmer?.path(7)).toBe("/projects/7/programmer")
    expect(show?.label).toBe("Show")
    expect(show?.pathMatch).toBe("/show")
    expect(show?.path(7)).toBe("/projects/7/show")
    // Neither pathMatch may be a whole-segment suffix of the other.
    expect(programmer!.pathMatch.endsWith(show!.pathMatch)).toBe(false)
  })
})

/**
 * The ⌘K window commands (multi-screen plan §4, `Screens.dc.html` §3), built from the registry
 * the way the template-family items are built from the family list — so their shapes are pinned
 * here without a store: one *Show <view> on <window>* per other window and view, the two
 * Chrome-only families absent rather than disabled, and the follow toggle naming its state.
 */
describe("window commands", () => {
  const actions = () => ({
    enterFullscreen: vi.fn(),
    exitFullscreen: vi.fn(),
    setImmersive: vi.fn(),
    toggleTheme: vi.fn(),
    openScreens: vi.fn(),
    show: vi.fn(),
    openOnDisplay: vi.fn(),
    follow: vi.fn(),
    unlink: vi.fn(),
    setFocus: vi.fn(),
    setViewOptions: vi.fn(),
    setFollow: vi.fn(),
  })
  const row = (id: string, name: string, view = "/projects/1/programmer") => ({
    id,
    windowId: `w-${id}`,
    name,
    view,
    fullscreen: false,
    follows: true,
    user: null,
    viewOptions: null,
  })
  const base = (over: Partial<WindowCommandInputs> = {}): WindowCommandInputs => ({
    windows: [row("s-1", "Screen 1"), row("s-2", "Screen 2", "/projects/1/busk"), row("s-3", "iPad", "/install")],
    thisRowId: "s-1",
    projectId: 1,
    fullscreen: false,
    canFullscreen: true,
    canOpenOnDisplay: false,
    following: true,
    buskFocus: null,
    immersive: null,
    theme: "light",
    actions: actions(),
    ...over,
  })

  it("lists Go full screen, Screens…, a Show per other window and view, and the follow toggle, in that order", () => {
    const commands = buildWindowCommands(base())
    const labels = commands.map((c) => c.label)
    expect(labels.slice(0, 2)).toEqual(["Go full screen", "Screens…"])
    expect(labels.at(-1)).toBe("Stop following the desk selection in this window")
    // Six views × two other windows (the iPad on an install route takes the viewed project),
    // plus the focus arm on each Busk show and the immersive arm on each live-view show.
    const shows = commands.filter((c) => c.id.startsWith("window-show-") && !/-busk-(split|pads|rig)$/.test(c.id) && !/-immersive$/.test(c.id))
    expect(shows).toHaveLength(12)
    expect(shows.map((c) => c.label)).toContain("Show Busk on Screen 2")
    expect(shows.map((c) => c.label)).toContain("Show Prompt Book on iPad")
    // Never this window: the Navigation group already moves it.
    expect(labels.some((l) => l.endsWith("on Screen 1"))).toBe(false)
    expect(new Set(commands.map((c) => c.id)).size).toBe(commands.length)
  })

  it("shows a view on the target’s own project, falling back to the viewed one", () => {
    const inputs = base()
    const commands = buildWindowCommands(inputs)
    commands.find((c) => c.id === "window-show-s-2-busk")!.run()
    commands.find((c) => c.id === "window-show-s-3-looks")!.run()
    expect(inputs.actions.show).toHaveBeenNthCalledWith(1, "s-2", "/projects/1/busk")
    expect(inputs.actions.show).toHaveBeenNthCalledWith(2, "s-3", "/projects/1/looks")
  })

  it("skips a window whose view names no project when there is no viewed project either", () => {
    const commands = buildWindowCommands(base({ projectId: null }))
    expect(commands.some((c) => c.label.endsWith("on iPad"))).toBe(false)
    expect(commands.some((c) => c.label.endsWith("on Screen 2"))).toBe(true)
  })

  it("flips to Exit full screen while full screen, and is absent without the API (D13)", () => {
    expect(buildWindowCommands(base({ fullscreen: true }))[0]!.label).toBe("Exit full screen")
    const without = buildWindowCommands(base({ canFullscreen: false }))
    expect(without[0]!.label).toBe("Screens…")
    expect(without.some((c) => /full screen/i.test(c.label))).toBe(false)
  })

  it("offers Open <view> on another display only with Window Management and a project", () => {
    expect(buildWindowCommands(base()).some((c) => c.id.startsWith("window-open-"))).toBe(false)
    const withIt = buildWindowCommands(base({ canOpenOnDisplay: true }))
    const opens = withIt.filter((c) => c.id.startsWith("window-open-"))
    expect(opens.map((c) => c.label)).toEqual([
      "Open Programmer on another display",
      "Open Show on another display",
      "Open Prompt Book on another display",
      "Open Busk on another display",
      "Open Looks on another display",
      "Open Templates on another display",
    ])
    expect(buildWindowCommands(base({ canOpenOnDisplay: true, projectId: null })).some((c) => c.id.startsWith("window-open-"))).toBe(false)
  })

  it("names the follow state in its label and runs the opposite gesture", () => {
    const on = base()
    const follow = buildWindowCommands(on).at(-1)!
    expect(follow.label).toBe("Stop following the desk selection in this window")
    expect(follow.detail).toBe("on")
    follow.run()
    expect(on.actions.unlink).toHaveBeenCalledTimes(1)

    const off = base({ following: false })
    const relink = buildWindowCommands(off).at(-1)!
    expect(relink.label).toBe("Follow the desk selection in this window")
    expect(relink.detail).toBe("off")
    relink.run()
    expect(off.actions.follow).toHaveBeenCalledTimes(1)
    // Reachable from the same search as every sibling in the Screens group.
    expect(follow.keywords).toEqual(expect.arrayContaining(["screen", "window"]))
  })

  it("withholds Stop following in Rig and Pads, where the focus forces following, and keeps Follow (desk-follow D4)", () => {
    for (const focus of ["rig", "pads"] as const) {
      const commands = buildWindowCommands(base({ buskFocus: focus }))
      expect(commands.some((c) => c.id === "window-follow"), focus).toBe(false)
      // A window caught local there (the D3 relink not yet rendered) is still offered the way back.
      const local = buildWindowCommands(base({ buskFocus: focus, following: false }))
      expect(local.find((c) => c.id === "window-follow")?.label).toBe("Follow the desk selection in this window")
    }
    expect(buildWindowCommands(base({ buskFocus: "split" })).some((c) => c.id === "window-follow")).toBe(true)
  })

  it("gives every other Busk or Programmer window the follow arm that changes its flag (desk-follow D4)", () => {
    const withOptions = (r: ReturnType<typeof row>, over: Partial<WindowCommandInputs["windows"][number]>) => ({ ...r, ...over })
    const inputs = base({
      windows: [
        row("s-1", "Screen 1"),
        withOptions(row("s-2", "Screen 2", "/projects/1/busk"), { viewOptions: { focus: "split" } }),
        withOptions(row("s-4", "iPad"), { follows: false }),
        withOptions(row("s-5", "Screen 3", "/projects/1/busk"), { viewOptions: { focus: "pads" } }),
        row("s-6", "Stage", "/projects/1/show"),
      ],
    })
    const commands = buildWindowCommands(inputs)
    const arms = commands.filter((c) => c.id.startsWith("window-follow-"))
    expect(arms.map((c) => c.label)).toEqual(["Screen 2 · own selection", "iPad · follow the desk selection"])
    // Never this window (it has its own item), never a forced row, never a view with no selection.
    expect(arms.some((c) => /Screen 1|Screen 3|Stage/.test(c.label))).toBe(false)
    arms[0]!.run()
    arms[1]!.run()
    expect(inputs.actions.setFollow).toHaveBeenNthCalledWith(1, "s-2", false)
    expect(inputs.actions.setFollow).toHaveBeenNthCalledWith(2, "s-4", true)
    // Before this window's own follow item, which stays last.
    expect(commands.at(-1)!.id).toBe("window-follow")
  })

  it("gives every other busk window the page arm that changes its flag, as a viewOptions frame under its view (desk-follow D6)", () => {
    const withOptions = (r: ReturnType<typeof row>, over: Partial<WindowCommandInputs["windows"][number]>) => ({ ...r, ...over })
    const inputs = base({
      windows: [
        withOptions(row("s-1", "Screen 1", "/projects/1/busk"), { viewOptions: { pageFollows: "true" } }),
        withOptions(row("s-2", "Screen 2", "/projects/1/busk"), { viewOptions: { focus: "rig", pageFollows: "true" } }),
        withOptions(row("s-3", "Screen 3", "/projects/1/busk"), { viewOptions: { pageFollows: "false", page: "3" } }),
        // Not announced yet: paged with, as its own tab reads it.
        withOptions(row("s-4", "Screen 4", "/projects/1/busk"), { viewOptions: { focus: "split" } }),
        row("s-5", "iPad"),
      ],
    })
    const arms = buildWindowCommands(inputs).filter((c) => c.id.startsWith("window-page-"))
    expect(arms.map((c) => c.label)).toEqual(["Screen 2 · own page", "Screen 3 · page with the desk", "Screen 4 · own page"])
    arms[0]!.run()
    arms[1]!.run()
    expect(inputs.actions.setViewOptions).toHaveBeenNthCalledWith(1, "s-2", "/projects/1/busk", { pageFollows: "false" })
    expect(inputs.actions.setViewOptions).toHaveBeenNthCalledWith(2, "s-3", "/projects/1/busk", { pageFollows: "true" })
    expect(arms.map((c) => c.detail)).toEqual(["paged with the desk", "own page", "paged with the desk"])
  })

  it("offers Split · Focus pads · Focus rig for this window only while it is on the busk view", () => {
    expect(buildWindowCommands(base()).some((c) => c.id.startsWith("window-focus-"))).toBe(false)
    const inputs = base({ buskFocus: "pads" })
    const commands = buildWindowCommands(inputs)
    const focus = commands.filter((c) => c.id.startsWith("window-focus-"))
    expect(focus.map((c) => c.label)).toEqual(["Split", "Focus pads", "Focus rig"])
    // Right after Screens…, before the shows for other windows.
    // Right after the theme command, which follows Screens…
    expect(commands.findIndex((c) => c.id === "window-focus-split")).toBe(commands.findIndex((c) => c.id === "window-theme") + 1)
    expect(focus.map((c) => c.detail)).toEqual(["this window", "current", "this window"])
    focus[2]!.run()
    expect(inputs.actions.setFocus).toHaveBeenCalledWith("rig")
  })

  it("gives Show Busk on <window> a focus arm: the show, then that window's focus on the view it lands on", () => {
    const inputs = base()
    const commands = buildWindowCommands(inputs)
    const arms = commands.filter((c) => /^window-show-s-2-busk-(split|pads|rig)$/.test(c.id))
    expect(arms.map((c) => c.label)).toEqual([
      "Show Busk on Screen 2 · Split",
      "Show Busk on Screen 2 · Focus pads",
      "Show Busk on Screen 2 · Focus rig",
    ])
    // No focus arm on a view that contributes no focus (the immersive arm is every live view's).
    expect(commands.some((c) => /^window-show-s-2-show-(split|pads|rig)$/.test(c.id))).toBe(false)
    arms[1]!.run()
    expect(inputs.actions.show).toHaveBeenCalledWith("s-2", "/projects/1/busk")
    expect(inputs.actions.setViewOptions).toHaveBeenCalledWith("s-2", "/projects/1/busk", { focus: "pads" })
    expect(new Set(commands.map((c) => c.id)).size).toBe(commands.length)
  })

  it("gives Show <view> on <window> an immersive arm on every live view and none on a library (busk-chrome D9)", () => {
    const inputs = base()
    const commands = buildWindowCommands(inputs)
    const arms = commands.filter((c) => /^window-show-s-2-.*-immersive$/.test(c.id))
    expect(arms.map((c) => c.label)).toEqual([
      "Show Programmer on Screen 2 · Immersive",
      "Show Show on Screen 2 · Immersive",
      "Show Prompt Book on Screen 2 · Immersive",
      "Show Busk on Screen 2 · Immersive",
    ])
    // The focus arms come first on Busk, the immersive arm after them.
    const buskIds = commands.filter((c) => /^window-show-s-2-busk/.test(c.id)).map((c) => c.id)
    expect(buskIds).toEqual(["window-show-s-2-busk", "window-show-s-2-busk-split", "window-show-s-2-busk-pads", "window-show-s-2-busk-rig", "window-show-s-2-busk-immersive"])
    arms[1]!.run()
    expect(inputs.actions.show).toHaveBeenCalledWith("s-2", "/projects/1/show")
    expect(inputs.actions.setViewOptions).toHaveBeenCalledWith("s-2", "/projects/1/show", { immersive: "on" })
    expect(new Set(commands.map((c) => c.id)).size).toBe(commands.length)
  })

  it("offers Expand over the app / Show the app for this window only on a live view, flipping with the state (busk-chrome D7)", () => {
    // Off a live view (null): no item — setting it from a library would be a silent write.
    expect(buildWindowCommands(base()).some((c) => c.id.startsWith("window-immersive"))).toBe(false)

    const off = base({ immersive: false })
    const expand = buildWindowCommands(off).find((c) => c.id === "window-immersive")!
    expect(expand.label).toBe("Expand over the app")
    expect(expand.detail).toBe("this window")
    // Beside the full-screen pair — it is a second pair, not an arm of the first (D8).
    const labels = buildWindowCommands(off).map((c) => c.label)
    expect(labels.slice(0, 3)).toEqual(["Go full screen", "Expand over the app", "Screens…"])
    expand.run()
    expect(off.actions.setImmersive).toHaveBeenCalledWith(true)

    const on = base({ immersive: true })
    const back = buildWindowCommands(on).find((c) => c.id === "window-immersive-off")!
    expect(back.label).toBe("Show the app")
    expect(buildWindowCommands(on).some((c) => c.id === "window-immersive")).toBe(false)
    back.run()
    expect(on.actions.setImmersive).toHaveBeenCalledWith(false)
  })

  it("offers the theme as a command after Screens…, naming the theme it switches to (busk-chrome D10)", () => {
    const light = base()
    const commands = buildWindowCommands(light)
    const theme = commands.find((c) => c.id === "window-theme")!
    expect(theme.label).toBe("Switch to dark mode")
    expect(commands.findIndex((c) => c.id === "window-theme")).toBe(commands.findIndex((c) => c.id === "window-screens") + 1)
    theme.run()
    expect(light.actions.toggleTheme).toHaveBeenCalledTimes(1)
    expect(buildWindowCommands(base({ theme: "dark" })).find((c) => c.id === "window-theme")!.label).toBe("Switch to light mode")
  })

  it("keeps the Screens… count in step with the registry", () => {
    expect(buildWindowCommands(base()).find((c) => c.id === "window-screens")!.detail).toBe("3 windows")
    expect(buildWindowCommands(base({ windows: [row("s-1", "Screen 1")] })).find((c) => c.id === "window-screens")!.detail).toBe("1 window")
  })
})
