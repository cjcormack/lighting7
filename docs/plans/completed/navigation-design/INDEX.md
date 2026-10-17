# Navigation and re-attach — design record

Source: design session R5 of [`../venue-visit-plan.md`](../venue-visit-plan.md), 2026-10-15. On the
first evening in the hall, Chris found project structure hard to work with:

- The projects list was hard to reach.
- A project's own screen showed little.
- Some actions could be found only in a card's ⋮ menu.
- A detached show couldn't be put back on its venue.

R5 asked for a survey of how the operator reaches the projects list, what the top-level project screen
shows and leaves out, which actions exist only in the ⋮ menu, which install settings the nav lacks after
S2 (`f6b37b3`), and whether the registry's grouping holds up. It also asked for a design for
re-attach, Detach in reverse.

**Status: approved 2026-10-15.** R5 produced two implementation plans:
- [`../navigation-plan.md`](../navigation-plan.md)
- [`../venue-attach-plan.md`](../venue-attach-plan.md)

A one-page copy of both, for human readers, is [`navigation.html`](navigation.html), with a live copy
at <https://claude.ai/artifact/56QW1wW5M8HcB7yXCzqbhX>. That copy is private to Chris and a
convenience; the files here are the authority. Where a board and a plan disagree, the plan wins on
behaviour and the boards on layout and copy, as with every record in this directory.

## Asked and answered

Chris answered these on 2026-10-15, in three rounds. The navigation plan numbers its decisions D1–D5,
and the attach plan its own D1–D4.

| Question | Answer | Plan |
| --- | --- | --- |
| How does the operator reach the projects list and switch shows? | **An app-bar switcher** naming the show on the rig. Its menu has search, Shows and Venues, each with Open and Make current, then All projects and New show. Projects also gets a registry row. A sidebar-header switcher, and a Projects row alone, were declined. | Nav D1 |
| What should `/projects/:id` be? | **A project home**: status first (on the rig, venue pin, sync), every action in one ⋮, then the registry's sections as cards with counts. A venue's home leads with its version, draft, History, Publish and shows. Dropping the overview, and keeping its cards with the gaps filled, were declined. | Nav D2 |
| How should the sidebar be grouped? | **Named sections by job**: Run, Rig, Library, Project, Desk. Today's groups with headings, and live views first with the rest unchanged, were declined. | Nav D3 |
| What does a project that isn't on the rig show? | **Every row stays, explained**, and Chris added "they should be subtly different in the sidebar". Hiding them with an explanation, and opening the views on stored data, were declined. | Nav D4 |
| Where does the desk's name go? | **Small, before the switcher.** Inside the switcher's footer, and the browser tab only, were declined. | Nav D5 |
| How do the off-rig rows look? | **Muted, with a hollow dot.** Muted only, and italic, were declined. | Nav D4 |
| How does re-attach know where a show came from? | **Detach records it** (`detachedFrom`). Matching is three-way where there's an origin. Pinning the origin version and then running Update, and asking about every unmatched record, were declined. | Attach D1 |
| A paired patch whose identity differs? | **Keep mine by default**, with *Take venue's* renaming references per row. Always taking the venue's, and blocking until fixed, were declined. | Attach D2 |
| Records with no facets that differ? | **Hall structure takes the venue's; groups keep the show's.** All venue's, and all show's, were declined. | Attach D3 |
| Who attaches, and from where? | **Both roles, like Detach**, from the project actions, with an MCP tool. Admins only was declined. | Attach D4 |

## What was found

Read on `main` at `d35463c` by three code surveys (the frontend's navigation, the Projects page and
install settings, and the venue backend). The desk was then walked with the TCH venue (v8), Sleeping
Beauty and TCH Test (both on v7, behind on output) and an empty Default. The plans' §1 hold the
findings with file:line; in short:

- **The Projects page has no nav entry.** Only the breadcrumb's root crumb and ⌘K reach it.
- **The sidebar's "ProjectSwitcher" switches nothing.** It names the viewed project and links to its
  overview.
- **Activate is only in a card's ⋮.** So are Clone, Export, Detach and Delete. Import and Add venue
  are buttons on that page only, and History and Publish exist only on the venue band.
- **The overview is ten shortcut cards.** It has no Make current, no actions and no venue state, and
  it ignores the project's kind.
- **The install nav is complete since S2**, but the Sync tabs show to operators while every
  cloud-sync route is admin-only.
- **The grouping is six unlabelled groups.** Stage sits under *monitor*, the rig is split across
  *setup* and *settings*, and a row says *Regions* where its tab says *Stage*.
- **A project that isn't current loses nine rows.** The venue, opened beside the running show,
  offers only Patch List and settings.
- **Detach keeps no record of its origin.** It keeps every uuid, though, and uuids are stable across a
  venue's versions, so a detached show can still be paired with any later version.

## What the boards propose, in one paragraph each

**Navigation.** The app bar reads *Hall desk │ ● Sleeping Beauty ▾ · TCH venue v7 · held*. The
switcher's menu lists the show on the rig, the shows and the venues, each with Open and Make current,
and every Make current goes through one confirmation. That confirmation says what a switch does: it
blacks out everything except the incoming show's parks, and warns when the show will open held. The
sidebar has five named sections. Viewing the venue while the show is on the rig keeps every row, mutes
those that need the rig with a hollow dot, and opens a *not on the rig* page in place of the view.

**Home.** A show's home leads with its status: the venue pin and the behind banner's actions, sync,
and what's live. Its ⋮ is the same action set as the card, the switcher and ⌘K. Its sections are the
sidebar's, as cards with counts. A venue's home leads with its version, the draft with Publish and
Discard, its repo, and the shows on it.

**Attach.** A standalone show's *Attach to venue…* opens a sheet:
- **Pick.** The venue (the one it was detached from comes first) and the version (installed by
  default).
- **Three-way or not.** The sheet says whether matching is three-way.
- **The review**, grouped as Update's is: output, what's inherited, what becomes an override,
  decisions (identity, groups, hall structure), hides, the show's own additions and address overlaps.
  Then *Attach to TCH venue v8*.

## Format

The files follow the apply-model record's format:
- **Boards.** Each `*.dc.html` is one dark artboard, generated by `gen.mjs` (`node gen.mjs`) from the
  apply-model boards' stylesheet, restated in the generator with this record's classes appended, so
  each board renders standalone.
- **Layout.** `canvas.json` is the layout manifest.
- **Markers.** Numbered amber markers are keyed in each board's notes strip.
- **The one-page copy.** `navigation.html` is the one-page copy published as the live artifact. It is
  hand-written and theme-aware, and its diagram is inline SVG.

| File | Draws |
| --- | --- |
| `Navigation.dc.html` | The app bar with the desk name, the switcher and the pin chip; the switcher's menu; the one confirmation; the sidebar's five sections; the venue viewed off the rig, with dotted rows and the *not on the rig* page. |
| `Home.dc.html` | Sleeping Beauty's home, held behind its venue, with its ⋮ open; the TCH venue's home with its draft, History, Publish and shows. |
| `Attach.dc.html` | TCH Test's attach sheet against TCH venue v8, three-way from v7: output, inherited, overrides, decisions, hides, additions and an overlap. |

## Names on the boards

The project, venue and show names, their versions and the desk's pin states are the desk's as
walked. The counts are the desk's where it showed them (60 patches, 57 fixtures, 24 groups, 9 Looks,
35 cues in 2 stacks, 58 venue patches). Everything else is illustrative:
- *Hall desk*;
- the cue numbers;
- the palette and viewpoint counts;
- the version note;
- every record named in the attach review (hex-7, Front wash, Boom SL, the Robe Pointes).
