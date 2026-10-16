# Navigation — find the show, switch the show, and see all of it

> **Document status: AGREED, 2026-10-15.** Design session R5 of
> [`venue-visit-plan.md`](venue-visit-plan.md), on project structure and navigation. The other half
> of R5, re-attaching a show to a venue, is its own plan:
> [`venue-attach-plan.md`](venue-attach-plan.md). The questions and their answers are in §10 and in
> the design record, [`navigation-design/INDEX.md`](navigation-design/INDEX.md), beside the boards. A
> one-page copy of both plans for human readers is
> [`navigation-design/navigation.html`](navigation-design/navigation.html), with a live copy at
> <https://claude.ai/artifact/56QW1wW5M8HcB7yXCzqbhX>. The checked-in file is the authority.
>
> "Palette" is venue-visit D2's word for a template; S4 (`b954eee`) renamed the registry's label.
> Where this plan and the venue-visit plan disagree, this plan wins on how the desk is navigated.

## 1. Context

On the first evening in the hall, Chris found project structure hard to work with. The projects list
was hard to reach, a project's own screen showed little, and some actions could only be found in a
card's ⋮ menu. Venue-visit S2 (`f6b37b3`) gave the sidebar one state and added Remote access and
Updates to the install nav. This survey was read on `main` at `d35463c`, after S2, and the running
desk was walked with the TCH venue (v8), Sleeping Beauty and TCH Test (both on v7, behind on output)
and an empty Default.

- **Reaching the projects list.**
  - `/projects` has no registry entry (`navMatch.test.ts:51` pins that nothing lights on it).
  - It's reached three ways:
    - the breadcrumb's root crumb (`Breadcrumbs.tsx:61`);
    - ⌘K *View All Projects* (`CommandPalette.tsx:484`);
    - the redirect when no project is current (`ProjectOverview.tsx:215`).
  - The sidebar, the app bar and the user menu don't link to it.
  - `ProjectSwitcher.tsx` lists no projects and switches nothing. Its header names the **viewed**
    project and links to that project's overview (`:117`).
  - The app bar shows a hard-coded "Chris' DMX Controller v7" (`Layout.tsx:237`), although Install
    Settings › General already stores the desk's friendly name.
- **The top-level project screen**, `/projects/:id` (`routes/ProjectOverview.tsx`).
  - **What it shows:**
    - a name, an active badge and *Configure*;
    - Patch List, Scripts and FX Library cards;
    - seven more cards (Fixtures, Groups, Surfaces, Busk, Looks, Cues, Universes) only on the current
      project (`:133-189`).
  - **What it leaves out:**
    - Programmer, Prompt Book, Stage, Templates, Speed Masters, Regions, Rigging and Sync;
    - any live state;
    - a way to make the project current;
    - every ⋮ action;
    - the venue pin and whether it's behind.

    On the desk, Sleeping Beauty's overview says nothing of the hold that the banner above it
    announces.
  - **It ignores the project's kind.** A venue gets Scripts and FX Library cards, though the sidebar
    hides both rows for a venue (`hiddenInVenue`).
  - **Its labels differ from the registry's**: "Cues" against "Show", and "Universes" against
    "Channels".
- **Actions that exist only in a card's ⋮ menu** (`routes/Projects.tsx:350-413`):
  - **Activate**, **Clone**, **Export**, **Detach from venue…** and **Delete**.
  - ⌘K *Go to* only navigates, and `useSetCurrentProjectMutation` has one caller.
  - *Import* and *Add venue* are buttons on that page only.
  - *Extract a venue…* appears in the ⋮ menu and on that page's Venues tile.
  - *Update venue* also has the behind banner's *Review update*.
  - A venue's **History** and **Publish** appear only on the band across a venue's views
    (`components/venue/VenueBand.tsx`). They aren't in the nav, ⌘K or the ⋮ menu.
- **Install settings after S2.**
  - All six tabs have a nav row (`navigation.ts:339-404`).
  - **The Sync tab is shown to operators** (`InstallSettings.tsx:55`), but its nav row is admin-only.
    The same split recurs for Project Settings › Sync (`ProjectSettings.tsx:81`).
    - Every cloud-sync route is `adminOnly` on the backend (`routes/router.kt:83`), so the tab an
      operator can open only fails.
  - Telemetry is a card inside General.
  - A universe's address is set from a chip on the Patch List.
  - *Profile…* is in the user menu, which is right for an account setting.
- **The registry's grouping.**
  - `setup · program · live · monitor · settings · install` render as separators with no headings
    (`ProjectSwitcher.tsx:91,135`).
  - Stage is under *monitor* beside Channels, although it is a view used in both building and running
    a show.
  - **The rig is split.** Patch List, Fixtures and Groups are in *setup*, while Regions and Rigging,
    drawn in the same Stage editor, are Project Settings children.
  - **The nav row says *Regions* where its tab says *Stage*** (`navigation.ts:307`,
    `ProjectSettings.tsx:79`).
  - **Surfaces' predicates disagree.** The nav row is active-only, and the tab is unconditional.
  - **⌘K duplicates.** It has two rows whose `value` is "Sync", and *Project Settings* appears in both
    Navigation and Actions.
  - **Its project commands only navigate.** *New Cue Stack* and *Create New Project* go to a page
    without starting the create.
- **A project that isn't current loses nine rows.**
  - The `active-only` rows are Fixtures, Groups, the four live views, Stage, Channels and Surfaces.
    Only an "Inactive" badge explains it.
  - **A venue suffers most.** Opened beside the running show, the TCH venue offers Patch List and
    settings only, so drawing its stage or testing in its Programmer means taking the show off the
    rig.
- **Breadcrumbs.** Every trail is `Projects › Project › View`, except `routes/Channels.tsx:678-697`,
  whose own copy links its project crumb to `/projects` and always says "active".

## 2. Decisions

Agreed with Chris, 2026-10-15 (§10):

| # | Decision |
|---|---|
| D1 | **The app bar names the show on the rig, as a switcher.** Its menu has search, *On the rig*, *Shows* and *Venues*. Each row offers *Open* (its home) and *Make current*. The footer has *All projects…* and *New show…*. Projects also gets a registry row. |
| D2 | **`/projects/:id` is the project's home.** It leads with status: on the rig or *Make current*, the venue pin with *Behind*, *Review update* and *Open venue*, and sync. Every project action is in its header ⋮. Then come the registry's sections as cards with counts. A venue's home leads with its version, the draft's changes, *History*, *Publish* and the shows on it. |
| D3 | **The sidebar is named sections, by job.** **Run**: Programmer, Show, Prompt Book, Busk, Stage. **Rig**: Patch List, Fixtures, Groups, Regions, Rigging, Channels. **Library**: Looks, Palettes, Effects, Speed Masters, Scripts. **Project**: Settings, Surfaces, Sync. **Desk**: Projects, then the install pages. Paths don't change. |
| D4 | **On a project that isn't on the rig, every row stays.** A row that needs the rig is muted with a hollow dot at its right edge (tooltip *Needs the project on the rig*). Opening one shows a page naming the show that is on the rig, with *Make current*. |
| D5 | **The desk's friendly name sits small, before the switcher**, and is hidden below 640 px. |

The plan adds its own:

- **P1 — One PR per session**, in §8's order, per CLAUDE.md §"Git workflow".
  - Each session ends with `npm run check` green (and `./gradlew test`, should one touch the
    backend).
  - Its engineering-doc paragraphs are written.
  - It gets a done-marker in its heading, citing its change commit(s) and never the merge commit.
  - The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md))
    applies before each PR.
- **P2 — A row and its page share one predicate.** Who sees a nav row, its tab and its ⌘K item is
  decided once, beside the registry entry, and matches what the backend allows. The Sync tabs
  therefore go for operators: every cloud-sync route is admin-only.
- **P3 — Every project action is defined once.** `lib/projectActions.ts` holds each action's label,
  predicate and run. The Projects card, the home's ⋮, the switcher and ⌘K all render from it, so an
  action can't again exist in one place only.
- **P4 — "On the rig" is the server's `isCurrent`, and "viewed" is the URL's.**
  - The switcher always names the show on the rig.
  - The sidebar header and the breadcrumb name the project being viewed.
  - D4 is about the gap between the two.
  - The UI says "on the rig", never "active", because a venue held behind is current but not
    sending.
- **P5 — Nothing stored changes.** This plan has no table, column, route or `formatVersion` change.
  The counts the home shows come from queries the frontend already makes.
  Session 3 bends it for two read-only fields on the project list, by Chris's call (§4.3).

## 3. The sidebar (D3, D4)

### 3.1 Sections

`NavGroup` becomes `NavSection`. It is `run | rig | library | project | desk`, each with a label, and
the sidebar renders a heading per section. On the 64 px rail the headings become the rules between
sections, as today.

| Section | Rows, in order | Was |
|---|---|---|
| **Run** | Programmer · Show · Prompt Book · Busk · Stage | live; Stage from monitor |
| **Rig** | Patch List · Fixtures · Groups · Regions · Rigging · Channels | setup; Regions and Rigging from settings; Channels from monitor |
| **Library** | Looks · Palettes · Effects · Speed Masters · Scripts | program, reordered; *FX Library* relabelled *Effects* |
| **Project** | Settings · Surfaces · Sync | settings |
| **Desk** | Projects · Install Settings › Users · Sync · Remote Access · Updates · Diagnostics | install, with Projects added |

The cue model plan's all-cues table (R1 D13, its session 5) joins Library as **Cues**, after Looks,
and is hidden in a venue like the rest of Library. (`navigation.test.ts` pins Library's ids and the
venue's hidden ids, so the row adds `cues` to both.)

- **Run goes first.** Running a show is what the desk is most used for. The live-view group's order
  test (`navigation.test.ts:91-102`) becomes Run's: the four live views and then Stage.
- **Regions** and **Rigging** become top-level Rig rows, though their paths stay
  `/settings/stage` and `/settings/rigging`.
  - The Project Settings tab `stage` is relabelled **Regions**. It holds the stage size, which is the
    regions' frame, the seating and the regions, so the nav row and the tab say the same word.
  - Two rows can't both say *Stage*, and the Run row already does.
- **Project › Sync** and **Desk › Sync** stay "Sync" under their headings. ⌘K, which has no headings,
  reads `NavItem.commandLabel`: *Project sync* and *Desk sync*.
- **In a venue** (`hiddenInVenue`), Run keeps the Programmer and Stage, Library keeps nothing and its
  heading goes. The eight hidden ids are unchanged.
- **Projects** (`/projects`) is the Desk section's first row, lit by `mostSpecificActiveId` on the
  Projects page.

### 3.2 Off the rig (D4)

- `filterNavItems` stops dropping `active-only` rows and marks them `offRig: true` instead. The
  visibility value is renamed **`needsRig`**, because that is what it now means.
- **A `needsRig` row on a project that isn't current:**
  - the icon and label take the muted colour;
  - a hollow dot (`○`, 6 px) sits at the right edge, where the Sync row's attention dot sits;
  - the tooltip says *Needs the project on the rig*;
  - on the rail, the dot sits at the icon's corner.
- **The route.** A `needsRig` route renders `NotOnRig` in place of its view when the viewed project
  isn't current. The page says:

  > **TCH venue isn't on the rig.** The Stage view draws the rig that's live, and Sleeping Beauty is on
  > it now. [Make TCH venue current] [Open Sleeping Beauty's Stage]

  *Make current* opens the switch confirmation (§4.3).
- **The sidebar header's "Inactive" badge** becomes **Not on the rig**, in the same muted outline.

## 4. The app bar and the switcher (D1, D5)

### 4.1 The app bar

`Hall desk │ ● Sleeping Beauty ▾  TCH venue v7 · held  …  Connected  Blind·venue  ⋯  CS`

- **The desk's name** (`install.friendlyName`, falling back to "Lighting desk") is in small muted
  type, then a divider (D5). It is hidden below 640 px.
- **The switcher button** names the current project.
  - A green dot means it is sending.
  - An amber dot means it is held behind its venue (`venue.hold`).
  - Since venue-visit S6 a current show can also be dark by *Make rig safe*'s blackout, which is per
    project and runtime (`GlobalScalerStateHolder.safeBlackout`, `rigSafety.state`). The dot must not
    read green then; the session picks its look.
  - A venue's tag goes beside the name when the current project is a venue.
- **The pin chip.** A show on a venue gets one after the button: *TCH venue v7*, plus *held* or
  *v8 published* in the banner's colours. Clicking it opens the show's home.
- **`document.title`** becomes *Sleeping Beauty · Hall desk*.
- **Immersive mode** hides the app bar as today, so it hides the switcher too. ⌘K's *Make … current*
  covers immersive.

### 4.2 The menu

```
┌───────────────────────────────────────┐
│ 🔍 Find a show or venue                │
│ ON THE RIG                             │
│ ● Sleeping Beauty   TCH venue v7 · held│  Open
│ SHOWS                                  │
│   TCH Test          TCH venue v7       │  Open · Make current
│   Default           no venue           │  Open · Make current
│ VENUES                                 │
│   TCH venue  VENUE  v8 · 2 shows       │  Open · Make current
│ ───────────────────────────────────────│
│ All projects…                New show… │
└───────────────────────────────────────┘
```

- **Rows.** Shows and venues are sorted by name, with the current project first, under *On the rig*.
- **Search.** The search box filters by name. Typing and then Enter opens the first match's home,
  and ⌥Enter makes it current.
- **Make current** is shown on every project but the current one, to both roles, as Activate is
  today. A `VENUE_MISSING` show's row shows the reason instead (`ProjectDto.venue.id == null`).
- **The footer.**
  - *New show…* opens `CreateProjectDialog`.
  - *All projects…* goes to `/projects`.
  - An admin also gets *New venue…*.

### 4.3 Making a project current

- Every door uses one confirmation, `ProjectSwitchConfirmDialog`: the switcher, the `NotOnRig` page,
  the home, the card and ⌘K. It is reworded to say what a switch does
  (`docs/dmx-engineering.md` §"Handing the rig from one show to the next"):

  > **Put TCH Test on the rig?** Sleeping Beauty's cues, programmer and effects stop, and every
  > channel goes dark except those TCH Test parks.
- When the incoming show is behind its venue on output, a second line says it will open **held**.
- Since venue-visit S6 a switch also drops a bulk lamp run in flight, a staggered strike included
  (`RigSafetyService.attach`), and a show made safe and switched back to opens still blacked out, its
  *Undo* on the banner. The confirmation says each when it applies.
- **Two read-only fields, by Chris's call (2026-10-16, session 3).** Nothing the frontend read said
  how many channels another project parks or whether *Make rig safe*'s blackout still holds on it, so
  the project list gained `parks` and `safeBlackout`. P5 bends for them and no further: no table,
  column, route or `formatVersion`.

## 5. The project's home (D2)

`/projects/:id` stays the route. `ProjectOverview` is rewritten as `ProjectHome`.

### 5.1 A show's home

- **Header.**
  - The name and description.
  - **On the rig** (green) or a **Make current** button.
  - The **⋮** with every project action (§6).
  - *Configure* folds into the ⋮ as *Settings*, since the Project section links it too.
- **Status block**, one line per fact:
  - **Venue.**
    - *On TCH venue v7 · v8 published*, coloured as the behind banner is, with *Review update* and
      *Open venue*.
    - On the rig and held, it gains *Run against v7…*. It reuses `VenueBehindBanner`'s actions,
      not a copy.
    - A standalone show reads *No venue* with *Attach to venue…* (`venue-attach-plan.md`).
  - **Sync.** *Synced · cjcormack/lighting7-tch-2026-sleeping-beauty · 2 min ago*, or *Not synced*,
    with *Sync now* for an admin.
  - **Live.** It appears only on the rig: the live cue per stack, Blind, and the held state, read
    from `cueRunState` and the existing queries.
- **Sections.** The registry's sections, minus Desk, are rendered as cards from the same `navItems`,
  so a new row reaches the home without a hand-picked card.
  - Each card is its row's icon, label and an optional count, from `NavItem.homeCount?: (data) =>
    number | string`:
    - patches 60, fixtures 57, groups 24, *2 stacks · 35 cues*, looks 9, palettes 31, speed masters 4,
      surfaces 0.
    - Since R1.4 a cue can sit in several stacks or in none, so a count of cues per stack counts a
      shared cue twice and misses a loose one. Session 4 asks Chris whether Show counts stacks and
      entries, with R1.5's Cues row counting distinct cues.
  - Off the rig, the `needsRig` cards take D4's treatment.

### 5.2 A venue's home

- **Header.**
  - The name and the *Venue* tag.
  - **On the rig** or **Make current**.
  - The ⋮.
- **Status block:**
  - *v8 · 14 Oct 2026 · "re-patched LX2"*, with *History*.
  - *Draft: 3 changes (1 changes output)*, with *Publish v9…* and *Discard* for an admin, read from
    the history query the band already makes.
  - The venue's repo and sync state.
- **Shows on this venue.** `VenueShowsList`, with each show's pinned version, whether it's behind,
  and *Open* and *Make current*.
- **Sections.** As §5.1. The venue filter leaves Run (Programmer, Stage), Rig and Project.
- **The band stays.** `VenueBand` still crosses a venue's views. The home gives History and Publish
  a second, permanent door.

## 6. Project actions in one place (P3)

`lib/projectActions.ts` exports `projectActions(project, ctx) → ProjectAction[]`. Each action has an
`id`, `label`, `group` (`rig | copy | venue | danger`), `visible`, `enabled` with a reason, and `run`.

| Action | Visible when | Group |
|---|---|---|
| Make current | not current | rig |
| Open | always (switcher, ⌘K) | rig |
| Settings | always | rig |
| Clone | a show, or an admin on a venue | copy |
| Export | admin | copy |
| Update venue to vN… | a show on a venue that's behind | venue |
| Detach from venue… | a show on a venue held here | venue |
| Attach to venue… | a standalone show (`venue-attach-plan.md`) | venue |
| Extract a venue… | admin, a standalone show | venue |
| History · Publish vN… | a venue (Publish: admin, with draft changes) | venue |
| Delete | a show, or an admin on a venue; disabled when current | danger |

- **The four places render from it:**
  - the Projects card's ⋮;
  - the home's ⋮;
  - the switcher, which takes only the *rig* group;
  - ⌘K's **Project** group, for the viewed project.
- **The ⌘K Project group:**
  - Its *Projects* list gains *Make <name> current* beside *Go to <name>*.
  - *Create New Project* opens the dialog.
  - *Project Settings* appears once. (Nav.1 shipped this.)
- **Page buttons.** *Scripts* leaves the ⋮ menu, since the Library section has it. *Import* and
  *Add venue* stay as Projects-page buttons and also join ⌘K's Actions.

## 7. Smaller fixes

- **Sync tabs.** `InstallSettings`' and `ProjectSettings`' *Sync* tabs show for admins only (P2).
  `ProjectSyncRow`'s link stays admin-only with them.
- **Surfaces.** Its tab and its row share the registry's predicate. The tab is shown off the rig with
  D4's dot and the `NotOnRig` body, because its bindings editor reads the live device list.
- **Breadcrumbs.** `ChannelsBreadcrumbs` goes, and Channels uses `Breadcrumbs`.
- **Telemetry** gets a ⌘K item (*Telemetry…* → `/install#telemetry`, admin). It needs no nav row: it
  is one switch inside General.
- **The overview's kind blindness** goes with the overview (§5).

## 8. Implementation — five sessions

Each session is one PR (P1). All are frontend only (P5), but for session 3's two read-only list fields (§4.3).

### Session 1 — Sections and the registry — done, `a5b6c8c0`

- §3.1: `NavSection` and its headings, the regrouping, the Projects row, the *Regions* tab label,
  *FX Library* becomes *Effects*, and `commandLabel`.
- §7: Sync tabs (P2), Surfaces' predicate, and `ChannelsBreadcrumbs`.
- ⌘K's duplicate *Project Settings* goes.
- **Tests:** in `navigation.test.ts`:
  - Each section's ids are in order.
  - Run starts with the four live views.
  - Venue mode hides the same eight ids, and Library's heading goes.
  - `/projects` lights the Projects row.
  - An operator sees neither Sync row nor Sync tab.
  - ⌘K's labels are unique.
- **Docs:** the frontend CLAUDE.md's §Navigation Registry, which gains sections and P2.

### Session 2 — Off the rig — done, `1d1db07`

- §3.2: `needsRig`, the muted row with its dot, `NotOnRig` on every `needsRig` route, and the header's
  *Not on the rig*.
- **Tests:**
  - On a non-current project, every row renders and the nine carry the dot.
  - A `needsRig` route renders `NotOnRig`, naming the current show.
  - Its *Make current* opens the confirmation.
  - The current project's rows have no dot.
- **Docs:** the frontend CLAUDE.md beside §Navigation Registry.

### Session 3 — The switcher

- §4: the app bar's desk name, the switcher button and pin chip, the menu with search, and the
  reworded confirmation.
- `document.title`.
- ⌘K's *Make <name> current* and *Create New Project* opening the dialog.
- §7's *Telemetry…* ⌘K item, which no session held, and the `id="telemetry"` anchor on General's
  card that `/install#telemetry` needs (nothing carries it yet).
- **Tests:**
  - The button names the current project and its hold.
  - The menu orders *On the rig*, then Shows and Venues.
  - Search and Enter open, and ⌥Enter makes current through the confirmation.
  - A `VENUE_MISSING` show offers no *Make current*.
  - The desk name hides below 640 px.
  - The confirmation names the incoming show's parks and its hold.
- **Docs:** the frontend CLAUDE.md, with a new §"The app bar and the switcher".

### Session 4 — One set of project actions, and a show's home

- §6's `projectActions` with the Projects card, ⌘K and the switcher on it.
- §5.1's `ProjectHome` for a show: the status block and the sections with counts.
- **Tests:**
  - Each action's predicate is pinned per kind, role and current state (the ⋮ table above).
  - The card, the home's ⋮ and ⌘K offer the same set for one project.
  - The home's sections match the sidebar's for the same project.
  - A held show's home offers *Review update* and *Run against*.
- **Docs:** the frontend CLAUDE.md, replacing the overview's paragraph.

### Session 5 — A venue's home

- §5.2: the venue's status block, History and Publish from the home, the shows on it, and its
  sections.
- ⌘K's *History* and *Publish vN…* for a viewed venue.
- **Tests:**
  - An operator sees no Publish.
  - The draft count matches the band's.
  - The shows list opens and makes current.
  - A venue's home has no Library section.
- **Docs:** the frontend CLAUDE.md's venue paragraphs; `docs/fixtures-engineering.md` §"Shows on a
  venue" names the home beside the Projects page.

**Order and dependencies.**
- Sessions 1 and 2 are independent of everything else.
- Session 3 needs neither.
- Session 4 needs 3 (the switcher renders the rig group).
- Session 5 needs 4.
- Venue-visit S4 (palettes) may land before or after. Whichever lands second carries the label.
- [`venue-attach-plan.md`](venue-attach-plan.md) session 3 puts *Attach to venue…* in session 4's
  `projectActions`.

## 9. Out of scope

- **Opening Fixtures, Groups or the Stage editor on stored data** for a project that isn't on the
  rig. This was declined (§10, Q4) in favour of D4. It would need those views to read stored rows in
  place of the live rig.
- **Moving routes.** Paths stay where they are, and only the registry regroups (D3).
- **Install settings as their own section of pages.** Desk keeps one parent row with children, as
  S2 left it.

## 10. Questions

Answered by Chris, 2026-10-15, in three rounds. The re-attach questions are in
[`venue-attach-plan.md`](venue-attach-plan.md) §10.

1. **How should the operator reach the projects list and switch shows?** An app-bar switcher (D1).
   A switcher in the sidebar header, and a Projects row alone, were declined.
2. **What should `/projects/:id` be?** A project home (D2). Dropping the overview for the last view
   used, and keeping today's cards with the gaps filled, were declined.
3. **How should the sidebar be grouped?** Named sections by job (D3). Today's groups with headings,
   and live views first with the rest unchanged, were declined.
4. **What should a project that isn't on the rig show?** Every row stays, explained, and Chris added
   that the rows should look subtly different (D4). Hiding them with an explanation, and opening the
   views on stored data, were declined.
5. **Where does the desk's name go?** Small, before the switcher (D5). Inside the switcher's footer,
   and the browser tab only, were declined.
6. **How do the off-rig rows look?** Muted, with a hollow dot (D4). Muted only, and italic, were
   declined.
