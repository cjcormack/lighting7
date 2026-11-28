# The app shell

Navigation, the app bar and switcher, a project's home, windows and full screen, desk accounts, the smaller views, and the cloud, update and Sentry surfaces. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### Scripts

Kotlin scripts for lighting automation. Editing uses the embedded `kotlin-playground`
widget (`src/kotlinScript/`), whose highlighting and autocomplete are served by **lighting7
itself**, from the same embedded Kotlin compiler that runs the scripts —
`/script-editor/*`, backed by `routes/scriptEditor.kt` + `scripts/ScriptEditorService.kt`.
There used to be a bundled `kotlin-compiler-server` fork behind `/kotlin-compiler-server`,
in a second JVM on port 8321; it is gone.

Two things to keep in step with the backend:

- **`wrapForEditor` in `components/scripts/ScriptEditor.tsx`** hands the widget the
  `//@lighting7-script-type=<TYPE>` marker, then the body between `//sampleStart` and
  `//sampleEnd`, and nothing else. Both halves are load-bearing, for different consumers, and
  neither is presentation:
  - The **fold markers are the widget's**. They fold the editor down to the body, make
    `onChange` hand back only the body, and offset every position it reports or asks about.
    The widget then *strips* them and posts `prefix + editorContents + suffix`, so the
    backend never sees them and `EditorDocument` always takes its no-marker path.
  - The **type marker is the backend's** — how it picks the template. Drop it and that editor
    silently falls back to GENERAL, losing every FX symbol. It cannot move into a query
    param or a per-type base URL: the widget owns the request shape, and its `server` is a
    module-level global that every `playground()` call overwrites, so two editors of
    different types would poison each other.

  There used to be a synthetic base class and import list per type here. Because the widget
  sends everything outside the fold markers verbatim, that stand-in was what the backend
  actually compiled — its constructor signature had to track the real base class by hand.
  With just the marker line, the real `.kts` template is what the body is compiled against.
- **The widget's own Run button is hidden** (`.kotlin-editor .run-button` in `index.css`).
  Every surface supplies its own Run wired to `/{projectId}/scripts/run`, which runs against
  the live show; the widget's button was a second, less correct path to the same thing.

The list is a sheet on the kit — type chips and dividers, a Check column filled by the bar's batch
Compile, Run over one row — and `ScriptForm` is what a row opens; see §Library sheets.

### Scenes & Chases
- **Scenes**: One-shot lighting configurations that run a script with specific settings
- **Chases**: Animated lighting sequences (same component, different mode)

Both use scripts as their base and allow configuring script settings per scene/chase.

### Fixtures
DMX fixture definitions - describes what channels a fixture uses and how to control it.

**A unit's stored locate** (venue-visit plan D6, D17; lighting7 `docs/fixtures-engineering.md`
§"Stored locate") is where Locate points its pan and tilt: `FixturePatch.locate`, bytes per unit
(`head` absent for the fixture's own), shown on the centre-relative scale (`lib/storedLocate.ts` over
`positionScale.ts`). Two doors set it, both one REST call that reads **the desk's output** on the
current project (`useStoredLocateActions`, `store/locate.ts`'s `storeLocate` / `clearLocate`): row C's
Locate menu (*Set locate from current* · *Clear locate*, a chevron beside Locate that folds with it, so
the phone's row C keeps its budget) and the patch sheet's **Locate** box (`patches/LocateBox.tsx`,
in both `EditPatchForm` and `VenuePatchPanel`; nothing there waits for Save). On a venue patch in a
show the desk writes the show's Focus override, so `facetValueOf('FOCUS')` carries `locate` whole and
making Focus the show's keeps the venue's.

**Infrastructure fixtures.** A patch marked `infrastructure` (a dimmer on hard power, a relay) is
hidden from every view but **Patches** and **Channels**, the Stage included. The raw
`useFixtureListQuery` / `usePatchListQuery` still carry it, because a cue, group or binding that
already names it must keep resolving — so a view that **lists or offers** fixtures reads
`useVisibleFixtureListQuery` / `useVisiblePatchListQuery` (or `withoutInfrastructure` from
`lib/infrastructure.ts`), and only a view that resolves a key it already holds reads the raw list.
A new picker that reaches for the raw hook will offer the hazer's power dimmer — so ESLint refuses
the raw `useFixtureListQuery` / `usePatchListQuery` imports outside an allowlist of key-resolving
files in `eslint.config.js`; joining it is a claim reviewed there. (Enumerating
`useFixtureLookup().fixtures` is not covered: it is raw for the lookups it feeds.) A picker that
edits an existing reference keeps the one it already names, labelled, as the surface binding form's
attribute select does; the Groups views count and list members through `useVisibleGroupMembers`. The empty busk
rig's fallback (`effectiveRig`) drops it too, mirroring the desk's `BuskRigOrder`. Backend side:
lighting7 `docs/fixtures-engineering.md` §"Infrastructure fixtures".

### Channels
Raw DMX channel control per universe. Shows all 512 channels with current values.

**A park is the project's own or its venue's** (venue parks; lighting7
`docs/fixtures-engineering.md` §"Venue parks"). `parkState` carries each park's `owner` (`SHOW |
VENUE`), a show's re-park's `venueValue` and the `released` list; `api/parkApi.ts` keeps them
(`getEntries`, `getReleased`), `store/park.ts`'s `ParkState` carries them and `restoreChannelPark` is
the fourth WS-backed mutation. `lib/parkOwner.ts` is the one wording — `parkLabel`, `unparkLabel`,
`unparkConfirm` — for the Channels cards (a *V* badge, a dashed ring on a released channel, *Restore*
in the row's menu), the DMX sheet (the cell's hover, *Restore* on the bar, the footer's counts), the
fixture sheet's Park and both Unpark All confirms: unparking a venue park **releases** it for this show,
and unparking a show's re-park goes back to the venue's. A refused write arrives as `parkError` and is
toasted under one id. A desk that predates venue parks sends neither field, and every park reads as
the project's own.

### The MIDI surface view

`/projects/:id/settings/surfaces` draws the attached desk as **a picture built from profile data**
(`ControlSurfaceType.layout`) with an inspector per control (editor A), a region's job (editor B,
`SurfaceJobsPanel`), or the same bindings as a mapping sheet (editor C), the only view below tablet width (surface-setup plan session 3). Read
[`docs/midi-surface-engineering.md`](midi-surface-engineering.md) before touching it; the plan is
`../docs/plans/completed/surface-setup-plan.md`.

The things that bite, in one line each. **The desk is the only resolver** (P5): what every control
part does — its label with records by name, its colour, where it writes, what it shows, its health —
is the desk's `surfaceMap.state`, and the functions a form offers are `GET /surface-functions`; this
side keeps no copy of the resolve order, the kind tables or the label rules. **Every write is the slot
write** (`PUT` / `DELETE …/surface-bindings/slot`), and the map comes back on the socket. **Viewing
*Layer A* or *Layer B* switches nothing** (D13): the chips view, the desk's own key switches. **A touch
on the desk is captured without firing** (D14): `useIdentify` and the desk's `surfaceIdentify.*`.
**`SheetColumn.accepts`** is how the sheet's *On* skips, and names, rows that cannot take a subject. **A
job is a region's** (D11): every write is the whole job (`PUT` / `DELETE …/surface-jobs/{type}/{region}`),
and a control it derives is labelled from the map's `jobLabel`, never worked out here.

**Every record has a door onto a control** (editor D, session 6): *Assign to surface…* in a stack's,
a cue's, a busk pad's or page's and a rig tile's menu, and on the Look, palette, speed master, fixture
and group sheets, all opening one sheet (`AssignToSurfaceHost` in `Layout`) that waits for a touch, or
a click on the picture in remote mode, and offers only what fits the record and the part
(`assignOffer.ts`). *Bound on Button 36 · A* (`BoundOnChip`) is the desk's `surfaceUses.state`, read
off the socket without the store, so a door draws it wherever it is mounted.

`BindingForm` asks Does · On · How for any catalogue entry; `SurfaceInspector` mounts one per part;
`SurfaceSheet` mounts it in its *Does* cell; the assign sheet over a catalogue narrowed to its record. The fixtures pinned by lighting7's `SurfaceCatalogueTest`
(`src/test/resources/surfaces/`) are read by every surface test through `surfaceFixtures.ts`.

**One desk, one selection, server-owned** (`store/selection.ts`), and three surfaces move it: the
busk target band, the programmer's fixture list, and a select button on the desk itself. The list
half is `useDeskSelectionBridge`, whose load-bearing rule is that rows publish through
`rowLocateTarget` — never the `programmer` scope's `targetKeys`, which is already flattened to
member keys, so a marquee over *Front wash* would reach the desk as eight loose fixtures with the
group's select LED dark. Edit mode there is **local state**, deliberately not the busk view's Redux
slice: that one exists only because the cue-slot overlay is a sibling of the routed page.

### Windows, full screen and the hand

**A window is a socket carrying a client-minted identity** (multi-screen plan D9, D10; lighting7
d774fd9 is the wire, and where it and the plan's §3.4 sketch differ, the commit wins).
`lib/windowIdentity.ts` holds both halves in **`sessionStorage`, never `localStorage`** — the two
desk screens are two windows of one browser profile, and `localStorage` is one value per origin per
profile, so an identity kept there would be one identity for both screens. `windowId` is a uuid
kept for the tab's life once minted — a reload keeps it, and only a `?window=` boot mints a fresh
one, even over storage a cloned context inherited (its own bullet below); the name is
`?window=Screen%202` from the launch URL, read
once at boot before the router is created (`main.tsx`) and stripped, else *Window* plus a suffix,
and it can be renamed (`renameWindow`, a subscribable so the chip, the user menu and the announce
all move). Three things about the registry (`api/windowsApi.ts`, `store/windows.ts`):

- **The announce carries exactly `windowId`, `name`, `view`, `fullscreen`, `follows` — and
  `viewOptions` only while the view contributes any.** The desk's Json is bare — no
  `ignoreUnknownKeys` — so one extra key makes the whole frame undeserializable and it is dropped
  with a server-side log line only. The symptom is a window that never appears in `windows.state`;
  `windowsApi.test.ts` pins the key set — five keys plus `type`, or six with `viewOptions` — and
  `id` and `user` are the server's to say. It goes out on every `Status.OPEN` (the second legitimate
  `open` re-send, §Where a WS bridge subscribes) and on every change — route, full screen, follow,
  rename, a busk fact — from one effect in `useWindowsBridge`. It is **handled only once the show
  is warm**: the frame waits in the socket's incoming channel through boot, so `windows.state`
  arrives empty behind the boot overlay and fills itself when the show is ready. There is no retry
  timer; do not add one.
- **`viewOptions` is a free `string → string` map, and the registry never learns its vocabulary**
  (busk-further plan D13; lighting7 `6e2cc72`). On the busk view it carries `focus`,
  `sheet`, `pageFollows` and — only while the page is unlinked — `page` (the split's height is
  pixels on this window's screen and is not announced; the `rigRows` count it replaced was, and
  nothing ever read it); a following window
  announces no page, because the desk's showing page is the desk's to say and the Screens sheet
  reads it for every following row alike. It rides back on `windows.state`, which is what the
  Screens sheet draws a row's Focus · Sheet · Page controls from — generically, off the `options`
  descriptor on the row's *current* entry in `lib/windowViews.ts`, so a Prompt Book row draws only
  its Chrome segment (§Windows, full screen and the hand) and the sheet never learns the word busk. The fourth command,
  **`windows.viewOptions {targetId, view, options}`**, is rebroadcast like the other three; the
  named window applies it **for that view only** — a busk frame arriving at a window on the Prompt
  Book is ignored rather than stored for a later visit — through `applyImmersiveViewOption` on a
  view whose descriptor carries it, `applyBuskViewOptions` on the busk view and
  `applyStageViewOptions` on the Stage view, and re-announces. `{sheet: 'toggle'}` flips
  the fold and the last open tab (a MIDI `BuskSheetToggle`'s spelling); `{page: n}` **unlinks**
  that window onto the page exactly as arriving with `?page=` does; and **`{pageFollows}` is
  applied** since desk-follow session 3 (D6) — `'true'` pages the window with the desk again,
  `'false'` keeps the page it is showing as its own, and a frame carrying both resolves on
  `pageFollows` (§The busk layout). It is what the busk row's **Page segment** writes — *Page ·
  Paged with the desk | Own page*, *With desk · Own* below 390px of row (the descriptor's
  `shortValueLabels`, accessible name *Paging on <window>*), drawn as one group with the page
  picker, whose visible label it stands in for. **The picker follows the row's state**: on a
  paged-with row it pages **the group** (`busk.setPage`, as a tab click there does, so every
  window paged with the desk moves), on an own-page row that window alone (`{page}`); the *follows
  the desk* / *own page* caption beside it went with the segment. The current view is read at
  command time through a ref, because a ⌘K *Show Busk on X · Focus pads* is two frames in a row
  and the second must see the route the first moved the window to. *Copy link for <name>* on a row
  mints `?window=…&page=…&focus=…&sheet=…&immersive=on` (`windowSetupUrl`; the last only while it
  is on): the row's whole setup, which a
  fresh window latches on arrival — the page only while the row holds one of its own, since a
  following row's null desk page is not "the first page" and writing it would unlink the new
  window where this one follows. **A following row's link carries no page even with the desk on
  one**: the code wrote the desk's page whenever it was non-null until session 8, and
  `ScreensSheet.test.tsx` now pins a following row against a non-null desk page.
- **The fifth command, `windows.follow {targetId, on}`, sets a window's selection follow**
  (desk-follow plan D4; lighting7 3f4fb5b) — `windows.fullscreen`'s exact shape, rebroadcast like
  the other four, nothing written server-side. It is **not a view option**, because follow is the
  window's and not a view's and a Programmer row carries no busk options; the Screens row's
  Selection segment and ⌘K's per-window arm send it (`setWindowFollow` in `store/windows.ts`). The
  named window applies it in `useWindowsBridge`: `on` is `relinkToDesk`, `off` is `unlinkFromDesk`
  over the desk's fact as it stands — **refused** on the busk view in Rig or Pads focus
  (`followIsForced`, §One selection, two shapes), and a no-op for a window already local, which
  would otherwise re-snapshot the desk over the operator's own selection. **It re-announces either
  way**: a flag that moved goes out through the announce effect, and one that did not — a refusal,
  a no-op — re-sends the last announce, so a row a sender drew from a stale frame corrects itself.
  Like a show, a follow aimed at a disconnected window is lost (`FU-WINDOWS-SHOW-OFFLINE`).
- **The row's `id` is socket-minted and is what every command addresses**; the `windowId` is how a
  tab recognises its own row (`thisWindowRow`, first match). A duplicated tab copies its storage, so
  two rows can share a `windowId` and cannot be told apart from this side — D9 accepts that, the
  Screens sheet shows two rows, and `FU-WINDOWS-OWN-ROW-ID` is the exact fix.
- **`?window=` at boot mints a fresh `windowId`; a reload keeps the one it has** (session 2.5).
  `sessionStorage` is *cloned* into a top-level context created from an existing one — a
  `window.open` without `noopener`, a `target=_blank` link — so a second desk screen can wake up
  holding the first's id. A window is never told its own row, it *infers* it by matching `windowId`,
  so both screens would match both rows and the chip would read *Desk* — "I moved it" — when the
  twin moved it. The parameter means "a deliberately-named new window", which is exactly the signal
  that this context is not a continuation of the storage it woke up with; presence of the key is
  the signal, a blank value included, because a shared identity is the worse failure. The invariant
  on the other side is the regression to watch for: the parameter is **stripped** at boot, so a
  reload carries none and keeps its id — minting there would churn a registry row on every refresh.
  Both facts are read through one memoised `consumeLaunchParam`, because `main.tsx` calls only
  `windowName()` and the URL is rewritten by whichever of the two asks first. The **name does not
  follow**: a stored name still beats the parameter, so a `?window=` boot over cloned storage is a
  fresh id under the inherited name — two rows sharing a *name* is what D9 already accepts, and
  only the shared *id* was the misattribution. A window opened by the Screens sheet goes out with
  `noopener`, which per spec makes it a new browsing-context group rather than an auxiliary one and
  so should not clone at all — **still asserted from the spec, not observed**: the desktop app's
  Chromium preview pane creates no child context for any of the three routes (`window.open` plain,
  `window.open` with `noopener`, a `target=_blank` click), converting each into a navigation of the
  *current* tab, so neither route could be shown to clone there. What that pane did show is the
  same-tab half of the rule: each of those `?window=` navigations minted a fresh id and kept the
  stored name. The clone itself is the desk's to see, on a tray item, a Dock app or Safari.
- **Every socket receives every command, the sender included** (D11), so each handler's first act
  is comparing `targetId` to this window's row id, read at command time from the last state frame
  — which is also what makes this window's own `show` for another window a no-op when it comes back.
  `windows.show` is a `navigate(view)`: the show-editing lock is per tab and defaults locked, so
  `/show` lands locked; an open cell editor unmounts with its route and the desk bridge never
  publishes on unmount; and a **guarded sheet declines** — the toast names the view and carries a
  button that goes. `windows.rename` is **not applied server-side**: the target renames itself and
  re-announces, which is what makes the name survive that tab's reload, so a rename aimed at a
  disconnected window changes nothing, exactly as a show does (`FU-WINDOWS-SHOW-OFFLINE`; do not
  retry). `windows.fullscreen {on:false}` calls `document.exitFullscreen()`; `{on:true}` cannot call
  `requestFullscreen` without a gesture and raises the *Return to full screen* banner instead.

**Full screen** (`lib/fullscreen.ts`, plan §3.6): `requestFullscreen` on the document element from
a gesture — the user menu's item, ⇧F, the ⌘K command, the Screens sheet's button — then
`navigator.keyboard.lock(['Escape'])` **feature-detected**, so on Chrome at a secure origin Esc
reaches the sheet's clear-selection rather than the browser, and on Safari it quietly does not. A
`fullscreenchange` listener is the truth (Esc, a tab switch and another app all leave without
telling the requester), and it keeps a `sessionStorage` flag so a window that was full screen and is
not now — a reload, a crash, a `{on:true}` from another screen — draws the one-tap banner at the top
of `<main>` (`ReturnToFullscreenBanner`). In full screen the app draws a
small exit glyph on the user menu's label line and nowhere else — never a floating button on a live
view. **Safari on the Mac is a first-class desk browser (D13)**: every Chrome-only piece — Keyboard
Lock, `getScreenDetails`, the *Open a window on… Display N* row and the *Open <view> on another
display* commands — is feature-detected and its absence is *quiet*: no item, no gutter, no disabled
control saying "use Chrome".

**Immersive is a per-window fact for all four live views, and it is not full screen** (busk-chrome
plan D7–D10; `Immersive.dc.html` is the layout authority). `desk.immersive` (`off` | `on`, default
`off` on every surface) lives in `lib/immersive.ts` on `createSyncStore` over `sessionStorageArea` —
per tab, beside the follow flag and the full-screen flag, **never `localStorage` and never the
desk's**, because the flow it exists for is a busk window with no app around it beside a programmer
window with the app drawn. `Layout` reads one boolean, `useImmersive() && isLiveViewPath(pathname)`,
and while it holds skips the `<aside>` sidebar (**hidden, not collapsed**), the app `<header>`, the
five overview panels and the sidebar's `marginLeft`; the panels' stored visibility is untouched, so
they return on exit. On every other route the app is drawn whatever the fact says — the fixtures
list is navigated *from* the sidebar — and a window that leaves a live view for it gets the app back
and finds immersive waiting when it returns. Banners, `HandChip` and `DeskDndProvider`
are untouched. Full screen and immersive **compose** (D8): neither flips the other, and a windowed
browser can be immersive while a full-screen one shows the app.

**The sidebar has one state on every route, and immersive never writes it** (venue-visit plan D4,
`hooks/useSidebarOpen.ts`). One `localStorage` preference, `layout.sidebarOpen`, default open. The
live views used to start on the 64px rail with a preference of their own (space plan D7), which
read as a sidebar changing shape on every move between the programmer and the lists; immersive is
the deliberate way to hide chrome now, and because it hides the `<aside>` rather than collapsing
it, the sidebar comes back exactly as open or collapsed as it went. A desk upgraded from the split
starts from its old `layout.sidebarOpen.other`, read once; `.live` is not consulted, because
`Layout` wrote both keys with their defaults on every route, chosen or not. `isLiveViewPath` now
answers only where immersive applies, and the mobile drawer is untouched — its open state was never
persisted. Open costs the live views width: at a 768–943px window the Programmer's grid has under 704px;
an operator on a tablet collapses the sidebar once and it stays collapsed everywhere (`FU-MANUAL-VENUE-VISIT-S2`).

**The `ShowHeader` is the row immersive leaves standing, and it carries the way back** (D11, D12).
It is a 40px chrome row on all four live views now — it takes `CHROME_ROW_CLASS` itself, `h-10
px-3` with its 1px border inside the 40, the shell's 32px controls; it was 48, and the programmer
chrome doc's "48 at every height" is revised by this. The plan spelled the change `px-3 py-1`, which
measured **41** on the box in the browser because the border sits outside the padding; the class
every other chrome row uses is the height, so the header takes the class rather than restating the
number — with the
expand glyph (`ImmersiveToggle`) drawn by the header itself **after the host's `actions` and before
the switcher**, so every host gets it with no per-host wiring: outward arrows with the app drawn,
inward arrows lit while immersive, one control both ways. Folding the header too was declined
because it would take the switcher, Stop, the live dot and the way out, and the way back would be a
floating button over a live view, which the full-screen work already refused. Chrome above the busk
band went 152 → 88 → 40. What the app header took with it comes back **where it is read** (D10): a
red *Offline* chip on the `ShowHeader`, drawn only while immersive *and* the socket is down
(`useIsDeskConnected`) — a pill reading *Connected* all night is what immersive exists to remove —
and beside it an amber *Blind · venue* chip while the live show is held by its venue (`VenueHeldChip`,
venue plan session 6: the app header's `ProgrammerIndicator` carries that reason too, and the venue
banner under the header, `VenueBehindBanner`, offers *Review update* and *Run against*);
below `md` the mobile drawer's **button** at the header's left edge while immersive, since the
hamburger in the app header was the only navigation there (the drawer and its open state stay
`Layout`'s, and `components/mobileDrawerContext.ts` lends the opener); the blind report and value
count on the busk Show tab's strip — and, since that strip is invisible with the sheet folded or
another tab open, the band's amber `BLIND` pill and the Show glyph's dot (§The rig, §Focus and the
side sheet); theme, full screen and Screens… through ⌘K — the theme had no
command until this, so `lib/theme.ts` became a module-level store (bare string, not `syncStore`'s
JSON — `getInitialTheme` reads it before React exists) that the user menu's row and the new
*Switch to dark mode* / *Switch to light mode* command both read. The five overview panels' ⌘K
toggles are withheld with the panels, since a row reading *On* for a panel that is not on screen
is a control reporting a state it is not in. **Every live route's loading and not-found arms
draw `ImmersiveEscape`** — the header's own glyph on a chrome row, or bare inside
`SheetPage.Header` — because those arms render no `ShowHeader`, and an immersive window on a
missing project would otherwise show an error card with no control at all: ⌘K still worked, which
is why it survived a desk with a keyboard, but on a touch screen there was no way back and the
fact is per-tab and not in the URL. Not a floating button: the header's row, minus the header, so
D11 holds.

**Nothing new on the wire** (D9): `immersive` rides `viewOptions` under every live view — the
Show and Prompt Book entries in `WINDOW_VIEWS` carry a one-entry `options` descriptor
(`IMMERSIVE_OPTION`), the Programmer carries it after its table and Busk carries it fourth — because the desk's Json is bare and a sixth
top-level announce key would drop the frame (`windowsApi.test.ts` still pins the key set).
**The Stage view is a seventh `WINDOW_VIEWS` entry** (stage-view plan session 1), between Busk and
the libraries, carrying four options — `viewpoint` (`STAGE_VIEWPOINT_OPTION`, *Viewpoint · Orbit | Eye |
Plan | Front | Side*), since session 3 `source` (`STAGE_SOURCE_OPTION`, *Source · Output | Next
GO*, an `enum` segment, with *Cue in edit* while the window shows one) and since the stage-view menu plan's session 2 `workLights`
(`STAGE_WORK_LIGHTS_OPTION`, *Work lights · Off | On*), and since the tables-colours plan's session 5
`bodies` (`STAGE_BODIES_OPTION`, *Bodies · Plain | By colour*) — and **no immersive** — it is a window view,
not a live view, so it has no `ShowHeader` to leave standing. `announcedViewOptions` takes the Stage
options (`stageViewOptions`: the viewpoint, the source, the work lights, the bodies and the moves in dark) as its fourth argument
and sends them only under Stage; `applyViewOptionsFor` applies `immersive` only on a view whose
descriptor carries it and the Stage keys — the four and `movesInDark` — only on Stage (`lib/stageViewpoint.ts`'s `applyStageViewOptions`,
which ignores a value outside each vocabulary — a camera, a saved view's uuid since session 2 or an
unsaved seat since session 3; one of the five vis sources, `edit` only beside a positive `editEntry`
or `editCue`; `off | on`; `plain | colour`; `on | off` for `movesInDark`, which has no Screens segment). ⌘K gains *Show Stage on <window>* and *Open Stage on another display* from
the same list, and `windowSetupUrl` carries `viewpoint=`, `source=` (with `editEntry=` or `editCue=`
under `edit`), `workLights=`, `bodies=` and `movesInDark=off`, which the Stage route applies on arrival and strips
(`consumeLaunchStageOptions`). **Since
session 2 the option is a `picker`**, a third `WindowViewOption` kind whose control the *view*
supplies: `Layout` hands `ScreensSheet` a lazily-loaded control per option key (`controls`, today
`StageViewpointRowPicker` for `viewpoint`), so the sheet draws the cameras, saved views and seats
without importing the Stage view or learning its vocabulary, and the scene queries load only when a
Stage row is drawn. A key with no control reads its value as text.
**The Programmer carries its table** (tables-colours plan D1): `table` (`PROGRAMMER_TABLE_OPTION`,
*Table · Values | Stack | FX | Scenery*, an `enum`) beside `immersive`. It is announced from
`lib/programmerTables.ts`'s per-window store as `announcedViewOptions`' fifth argument, applied by
`applyProgrammerViewOptions`, and carried by `windowSetupUrl` as `table=`, which the page reads on
arrival like any `?table=`. The applied tables were an entry of their own, `applied` on
`/programmer/applied`, until they became the Programmer's tabs; the route redirects.
`announcedViewOptions` in `lib/windowViews.ts` is the one place that says which keys go out under
which view; the three libraries still send the five-key frame. **The Cues table is an eighth entry**
(cue model plan session 5), `cues` on `/cues` between Looks and Palettes, the sidebar's order: a
library, so no options, no `needsRig`, and a command that puts a window on it keeps the window's own
project. `windows.viewOptions {immersive}` is
applied on any of the four through the same per-view gate as the busk keys — a Show frame arriving
on the Prompt Book is still ignored, on purpose: the frame is a statement about the view the sender
was looking at. The Screens sheet draws a **Chrome** segment (*App · Immersive*, the descriptor's
`valueLabels` over `off` | `on`) on every live-view row from the descriptor, generically, and never
learns the word; `windowSetupUrl` carries `immersive=` beside `page`, `focus` and `sheet` **only
while it is `on`**, since `off` is what every window boots with. `?immersive=on` is consumed once at
boot through the same memoised `consumeLaunchParam` read `?window=` uses (`launchImmersive`) and
stripped in the same `replaceState` — one read, because whichever ran first would rewrite the URL
for the other — and it is **not** mirrored into the address the way the busk view mirrors `?focus=`,
because it is a window's fact and not a view's; a plain `?immersive=on` arrival is not a `?window=`
boot and mints no fresh `windowId`. ⌘K offers *Expand over the app* / *Show the app* for this window
only while it is on a live view, the label flipping with the state like the full-screen pair, and a
*Show <view> on <window> · Immersive* arm on every live view beside the busk focus arms. A MIDI
`ImmersiveSet` target is `FU-SURFACE-IMMERSIVE-SET`, not built.

**The one rule underneath all of it is the secure context** (`../docs/desk-screens.md`):
installation, Keyboard Lock and Window Management exist only on a potentially-trustworthy origin,
and the desk serves plain HTTP — so the two desk screens must be opened at `http://localhost:8413/`,
and the iPad at the `.local` name gets the Fullscreen API and nothing else. Nothing in the UI says
so; a desk screen whose Esc keeps leaving full screen has almost certainly been opened at the LAN
name. `public/manifest.webmanifest` (`display: fullscreen`, `display_override: ["fullscreen",
"standalone"]`, no service worker) plus `<link rel="manifest">` and `apple-mobile-web-app-capable`
in `index.html` are the whole of the install story; its icon is `public/icon.svg`, a placeholder
until `FU-DIST-ICONS` — the `/vite.svg` favicon link it replaced pointed at nothing.

**The Screens sheet** (`components/screens/ScreensSheet.tsx`, `Screens.dc.html` §2) is mounted once
in `Layout` and opened from the user menu and from ⌘K through `screensSheetState`, since neither
opener is an ancestor of the other. Every write on it is a `windows.*` command by row id — this
window's included, so a rename of this tab goes out and comes back like any other and there is one
path, not two. *Copy link for another device* mints `<origin>/?window=<name>` with the space as
`%20`, the launcher's spelling (`DeskScreens.screenUrl`); the origin is this tab's, because the desk
mints its LAN address server-side per request and exposes it on no GET route, so a tab at
`localhost` copies a link that names the desk to itself and says so under the button
(`FU-SCREENS-LAN-URL`). **Layouts** is `FU-SCREENS-LAYOUTS`, not built. **The hand** is session 3.

### Desk accounts

Login, roles, and user administration for a desk whose accounts live on the
**machine**, not in a project — see
`../docs/desk-accounts.md` for the backend contract and the break-glass
recovery. Frontend shape:

- `AuthGate` (wrapping `BootGate` in `App.tsx`) decides between `SetupScreen`,
  `LoginScreen`, and the app from `GET /auth/status`. A 401 from **any** endpoint
  invalidates the `Auth` tag, which is the entire logout mechanism; a WS close with
  code **4401** does the same, because the backend revokes live sockets.
- **`OnboardingGate`** (`components/onboarding/`, between `BootGate` and the router) shows a new
  desk's first admin two steps before the app while `GET /install` says `onboardingPending`:
  diagnostics (the telemetry switch, nothing picked until the admin picks) and projects — an
  unadopted desk adopts here (`CloudAdoptionFlow`), and an adopted one gets a checklist of the org's
  cloud repos imported through `/cloud-sync/import`,
  **venues first**, a taken name numbered (`Default (2)`), or the boot-time `Default` kept and named
  as a blank project. Finishing with an import opens it and the
  desk deletes the empty `Default`. An operator never sees it; the screen itself is a lazy chunk.
- `store/users.ts` is admin-only CRUD (`/api/rest/users`); `store/passwordReset.ts`
  and `store/deviceLogin.ts` are the **public** endpoint pairs the two phone pages
  use. They are separate slices because they are separate audiences —
  cookie-authenticated admin vs. no session at all — not merely separate paths. The
  *desk* side of the device-login QR lives in `store/auth.ts`, because it is
  authenticated and open to any role.
- `routes/ResetPasswordPage.tsx` and `routes/DeviceLoginPage.tsx` are **siblings of
  `Layout`**, not children, and are bypassed past both gates via the `publicPath`
  flag computed at module scope in `App.tsx`: no sidebar, no ShowBar, no project
  context, no session. Whoever opens `/reset/<token>` is by definition locked out;
  whoever opens `/device/<token>` has no session yet either.

  Two traps there. It matches the **routes**, not a bare prefix — `/device/` with no
  token would otherwise render blank with both gates off. And because the flag is
  read once per document, `DeviceLoginPage` finishes with
  `window.location.assign('/')`: a react-router `navigate('/')` from either page
  would render the whole app with no auth check and no boot check. A test pins that.
- `MIN_PASSWORD_LENGTH` lives in `lib/passwordPolicy.ts` and mirrors the backend's
  floor. Five surfaces ask for a password; a form that disagreed with the server
  would read as a bug in that form. `MAX_DISPLAY_NAME_LENGTH` in `lib/userPolicy.ts`
  mirrors the column width the same way, but makes the **weaker** claim: only
  `ProfileSheet` gates on it today, and `SetupScreen` / `CreateUserSheet` /
  `UserDetailSheet` still rely on the server's 400. Don't read it as "every
  display-name field is bounded".
- **`ProfileSheet` is the only self-service surface**, reached from the user menu,
  which holds Log out and the **theme toggle** — the latter since `PD-SPEED-OVERLAY`
  added a ninth icon button to the app header and the row stopped fitting a phone
  (nine controls came to 439px against a 375px viewport, and the avatar was what got
  pushed off). Theme went because it is the one thing on that row that is not a desk
  control but a per-*viewer* display preference, which is what the rest of this menu
  is. **On a bootstrap-open desk the menu still opens**, behind a generic glyph, with
  the per-viewer items — the theme, *Full screen*, *Screens…* — and no account items:
  it used to return a bare `ThemeToggle` there, which kept the theme reachable and
  quietly lost the other two the day they were added. That standalone button is
  deleted; `ThemeMenuItem` is the one theme control, and the theme is
  a `useState` seeded once from storage, so a second mount would drift. It is
  deliberately **not** on `syncStore`, which JSON-encodes: `theme` is stored
  as the bare string `dark` and read at module scope in `main.tsx` before React exists. Four tabs — **Profile / Password / Devices /
  Sign-in** — and **each tab owns its own action button**; the footer is just Close,
  because a footer Save would have to mean "save the display name" while you were
  looking at the devices list. Errors are per-tab state for the same reason: one
  shared alert would follow you to another tab and blame the wrong form.
  The name and the password are **separate saves and must stay that way**: the
  password submit needs `currentPassword` and a rename must not, and the two differ in
  consequence (a password change revokes every other session; a rename revokes
  nothing). Its route, `PUT /auth/profile`, is authenticated but **any role** and
  deliberately outside the admin-only `/api/rest/users` subtree — a self-exception
  inside a prefix-matched admin gate would mean that prefix list no longer describes
  its own subtree.

  There is no *Connected apps* list any more: Claude's connectors are granted and revoked in the
  Lighting7 Cloud portal, and the desk keeps no grants (lighting7 cloud-p1 plan D18).
- **Account changes self-heal across clients**, via two frames from one backend flow rather
  than the project-scoped `FixturesChangeListener` bus every other list rides (users belong to the
  machine; see `../docs/desk-accounts.md` → "Account edits reach other clients").
  `usersWsApi` → `store/users.ts` invalidates `UserList` / `User` on every socket;
  `authWsApi.subscribeOwnAccountChanged` → `store/auth.ts` invalidates `Auth` on **only** the
  affected user's sockets. Keep those two apart: folding the `Auth` invalidation into the
  user-list bridge would make every connected client re-read `auth/status` on any admin edit.
  The own-account subscriber lives on `authWsApi` for the same reason the 4401 one does — `Auth`
  is that module's tag.

  One reliance worth knowing before you add a call site: the backend does **not** role-filter
  `userListChanged`, which is safe only because `UsersTab` is the sole caller of
  `useUsersQuery` and passes `skip: !isAdmin`, so an operator has no subscriber and the dispatch
  is a no-op. A second unguarded call site would make every operator socket a 403 generator on
  every user edit. `store/installs.ts` has the same bridge shape for the install row.
- **"Manage users" is deliberately not in the user menu.** The `users` nav entry is
  `adminOnly`, so the sidebar and Cmd+K already carry that page; a second entry point
  only meant role-filtering the same destination in two places.
- 409 responses carrying `LAST_ADMIN` / `SELF_TARGET` are **ordinary flow steps**
  (you can't demote the last admin, and on your own account you can't disable,
  delete, re-role, or mint a reset QR), rendered inline in `UserDetailSheet` — which
  is why those endpoints are in `SILENT_ENDPOINTS`. The self cases are hidden rather
  than disabled in that sheet: a Password section made of three greyed-out controls
  reads as breakage.
- **The two QR surfaces make opposite calls on the way out, on purpose.**
  `ResetQrSheet` leaves its link alive and `ResetTokenHistory` makes it visible and
  revocable; `DeviceLoginSection` cancels its code, because that code *is* a way into
  the account rather than a way to re-password it. Don't factor them together — and
  don't turn the section back into a sheet, either: being the body of `ProfileSheet`'s
  Sign-in tab is what makes "left the tab", "parent closed" and "tree unmounted" one
  cancel mechanism (the teardown effect, keyed on `active` *and* firing on unmount —
  both are needed).
- **The Sign-in tab has no button: arriving mints, leaving cancels.** Radix mounts a
  tab's content only while it is active, and mounting is what mints — so navigating to
  a tab named for the thing replaces a press with a navigation, and the tab bar above
  the code is the way out. What must not regress is the other half: opening the sheet
  lands on **Profile**, so nothing is minted by opening it, and closing resets `tab` to
  `profile` via an effect on `open` rather than any close handler — because saving a
  name closes the sheet without going through one. Treat that reset as a security
  property, not tidiness.
- **Minting a device-login code must happen exactly once**, which is why the mint
  effect carries `mintedRef` and `onScreen()` reads *two* refs. Both exist because of
  StrictMode's development mount/teardown/remount: a flag only cleared in a teardown
  is left false while the section is on screen (every code then cancels itself on
  arrival), and a second POST is not merely wasteful — `AuthService.createDeviceLogin`
  retires the caller's previous code, so two mints race and resolving them backwards
  displays a QR the server has already cancelled. **The client cannot repair that
  afterwards**; it has no way to know which mint the server saw last, so don't reach
  for a "cancel the displaced code" fix. Two tests in `DeviceLoginSection.test.tsx`
  render under `StrictMode` for exactly this — plain `render` passes while all of it is
  broken. `mintedRef` is released again if the mint *fails*, so the "Try again" button
  is reachable: a failed mint leaves no `code`, so the EXPIRED/CANCELLED retry branch
  can't render and the tab would otherwise be an error with nothing to press.

### Cloud sync — a project on GitHub

Backend contract in `../docs/sync-engineering.md`. The desk syncs only with its cloud's repos, and
`SyncConfig` says which kind a project's repo is: `cloud`, or `github` for a GitHub repo, which the
desk does not sync with. The Sync tab's `ConfigPanel` draws *Move to the cloud* (`MoveToCloudPanel`,
`POST /cloud-sync/move` for the one project) only for `github`; a repo that is neither (another
cloud's, or this one's under a mistyped `cloud.baseUrl`, `NOT_A_CLOUD_REPO`) gets a notice and
*Disconnect repository*, never a move. `useSyncNow` gives *Sync now* the same reason.

### In-app updates

The **Updates** tab in `InstallSettings` (`components/updates/UpdatePanel.tsx`), backed by
`store/updates.ts` and `api/updateWsApi.ts`. Windows installer builds only; every other build
renders a one-line explanation of why it can't update itself. Backend contract and the MSI
mechanics live in `../docs/windows-updates.md`.

- **`updateStateChanged` is the one payload-carrying machine-socket frame**, so `updateWsApi` is
  modelled on `cloudSyncWsApi`, not `installWsApi`. For a several-hundred-megabyte download the
  frame *is* the progress; a payload-free "refetch" at 2 Hz would mean an HTTP round-trip per
  tick, which is the traffic the socket exists to avoid.
- The bridge in `store/updates.ts` splits deliberately: **`updateQueryData` for progress ticks**
  (zero network), **`invalidateTags` only on a terminal phase** — that's where `latest`, `error`,
  notes and `lastApplyOutcome` arrive. Invalidating per tick would defeat the whole reason the
  frame carries a payload. The panel also polls at 5 s **while busy** as a safety net, because
  `emitMachineEvent` uses `tryEmit` and drops frames when its buffer fills: a dropped progress
  tick is harmless, a dropped phase transition would strand the panel.
- The tab is visible to **everyone** with actions disabled for operators — the version, and that
  the desk is about to restart, are things anyone standing at it should read. **Never toast or
  modal an available update**: an operator mid-show must not be nagged.
- Release notes render as **plain text**. They are untrusted text fetched from the internet, and
  per §Dependencies a sanitising markdown renderer isn't worth adding for this.
- `ApplyUpdateDialog` requires **type-to-confirm only when the rig is live** (FX running or
  a stack active). The asymmetry is the point: making every routine update a typing chore trains
  people to type without reading, destroying the friction exactly when it matters. It also sends
  `confirmVersion`, so a tab left open across a newer check can't apply something its owner never
  saw — the backend 409s on a mismatch.

### Remote access

There is no Remote access tab: the desk's ngrok tunnel is gone (lighting7 cloud-p1 plan D18), and a
remote caller comes through Lighting7 Cloud — a remote session (**Remote mode**, below) or Claude through the
cloud's MCP relay. What may come through, and what any remote caller may do, is the Cloud tab's
*Remote access* section (below). `/install/remote` redirects to `/install/cloud`.

### Lighting7 Cloud

The **Cloud** tab in `InstallSettings` (`components/cloud/CloudPanel.tsx`), backed by
`store/cloud.ts` and `api/cloudWsApi.ts`: this desk's adoption by Lighting7 Cloud. Admin only —
hidden tab and nav row, `skip: !isAdmin`, `cloud.state` sent to admin sockets alone. Backend contract in `../docs/cloud-engineering.md` §"The Cloud tab".

- **The state is the desk's.** `GET /install/cloud`, every Cloud route's answer and the `cloud.state`
  frame are one document; the bridge replaces the cached state with each frame and each mutation
  writes its answer (`upsertQueryData`), so nothing refetches and a pending adoption survives a reload.
- **`remote` disables *Adopt*.** The desk refuses an adoption started over remote access and leaves a
  pending one's code and links out there; the tab reads the state's `remote` rather than guessing from
  the URL.
- **Remove asks, and offers the forced arm only on `CLOUD_UNREACHABLE`.** *Forget on this desk only*
  sends `?force=true`. The three mutations are in `SILENT_ENDPOINTS`; the tab renders each refusal.
- **A `notice` is dismissed per browser** (`usePersistentState`), keyed on its `noticeAt`: the desk
  repeats the same words for the next expiry or revoke, keeps both until the next adoption or a
  restart, and has no route to clear them.
- **The adoption link is `data-sentry-block`**, like the QR: its `href` carries the user code, and a
  replay masks text, not attributes.
- **Remote access** (`CloudRemoteAccess.tsx`, lighting7 cloud-p1 plan D6, D13): *Remote sessions*,
  *Approve each new session on this desk* and *Claude (MCP)* once adopted, the sidecar's status from
  `cloud.state`'s `link`, the scripts, effects and commands switches on any desk, and the open and
  recent sessions with *End*. Its own two queries (`remoteAccessSettings`, `remoteSessions`, the
  latter polled every 5 s while mounted: no frame announces a session); `setRemoteAccess` answers the
  switches and is silent, rendered inline. **`cloud.state`'s `remoteSwitches` patches the cached three
  cloud switches**, so another window's change and a new adoption (which starts them off) show at
  once; a change of install id invalidates both queries. **`remote` disables every switch with the reason** —
  the desk refuses them there (`CLOUD_SETTINGS_LOCAL_ONLY`) — and leaves *End*, which only reduces reach.
- **Approval is a toast on every admin window** (`RemoteSessionApprovals.tsx`, mounted once by
  `Layout`): `remoteSession.pending` is kept by `api/cloudWsApi.ts` (its latest list, read through
  `useSyncExternalStore`, cleared when the socket closes), each waiting session a `toast.custom` keyed
  by its sid with *Allow* and *Deny* and the seconds left, dismissed once the list drops it — answered
  anywhere, timed out or closed. The desk sends the frame only to admin sockets on its own listener.
  An *Allow* the desk refuses because the account's link moved (`CLOUD_SESSION_LINK_CHANGED`) is toasted
  by the error middleware, and the toast redraws with the new desk user from the next frame.

**Remote mode** (cloud-p1 plan D9): framed by the cloud's shell, with the desk's
`lighting7-remote-parent` meta in `index.html`, the SPA's socket is `api/remoteSocket.ts` over the
shell's `MessagePort`. `src/boot.ts` is the page's only script and decides it before `./main` loads,
because `lightingApi` builds its connection at module evaluation; read `isRemoteMode()`
(`lib/remoteMode.ts`) wherever a control is the LAN's. Backend contract in
`../docs/cloud-engineering.md` §"Remote mode".

### Sentry

`lib/sentry.ts` is the browser's Sentry and `main.tsx` its only importer (`sentryImports.test.ts`),
so no test can report; everything else calls `@sentry/react` directly, which does nothing until a
client exists. It asks `GET /install/telemetry` first and reports through the desk's `/api/sentry`
tunnel. `App` creates the router only once `lib/sentryReady.ts` says init has settled, because the
SDK instruments only a router made after `Sentry.init`; keep it that way, or page-load and
navigation transactions silently stop. Secrets are scrubbed by `lib/sentryScrub.ts` from the
backend's own pattern list. Replay and feedback are a dynamic `import()` of `lib/sentryLazy.ts`,
never a static one (the SDK's `lazyLoadIntegration` would fetch them from Sentry's CDN), and
*Report a problem…* in the user menu reads `lib/reportProblem.ts` rather than `lib/sentry.ts`. A
region that changes every frame carries `data-sentry-block`; text is unmasked in a replay, so
anything that shows a credential carries `data-sentry-mask` (or `-block`, for a QR code). The
whole contract, and what each event carries, is `../docs/observability-engineering.md` §"The
browser".

### Navigation Registry
- All navigation items are defined in `src/navigation.ts`
- When adding a new page/route, add an entry to the `navItems` array in `src/navigation.ts`
- This automatically registers the page in both the sidebar and the Cmd+K command palette
- Dynamic items (e.g. universes) are handled by the `useUniverseNavItems()` hook (`useNavItems()` just returns the static `navItems`)
- **The sidebar is five named sections, by job** (navigation plan D3, Nav.1): **Run** (Programmer ·
  Show · Prompt Book · Busk · Stage), **Rig** (Patch List · Fixtures · Groups · Regions · Rigging ·
  Channels), **Library** (Looks · Cues · Palettes · FX · Speed Masters · Scripts), **Project**
  (Settings · Surfaces · Sync) and **Desk** (Projects, then Install Settings and its tabs). A row's
  `section` is a `NavSection`, `NAV_SECTIONS` holds the labels, and the array is declared in section
  order — `navigation.test.ts` pins each section's ids whole, so the array *is* the sidebar. The
  sidebar draws a heading wherever `section` changes (`data-nav-section`) and the 64px rail a rule; a
  section left with no visible row draws neither, which is how a venue loses Library's heading. Paths
  never move with a row: Regions and Rigging are top-level Rig rows whose pages are still Project
  Settings tabs (the `stage` tab says *Regions* too, since Run's Stage row is the Stage view), and the
  FX Library row says *FX* over `/fx-library`. **Projects** is `/projects` with `pathExact`, which
  `mostSpecificActiveId` matches as the whole path only — `/projects` begins every project's path, so
  a segment match would light it everywhere. ⌘K has no headings, so it lists a row by
  `commandLabelOf(item)`: `commandLabel` names the rows whose label leans on its heading (*Project
  sync*, *Desk sync*, *Project Settings*), and cmdk keys selection on that value, so it must be
  unique — `navigation.test.ts` pins it. `keywords` are extra words ⌘K matches a row on, each
  scored whole (*fx library* finds FX). The Actions group's *Project Settings* and the Projects
  group's *View All Projects* went, being the Settings and Projects rows a second time.
- **The Programmer's tables are three ⌘K items and no sidebar row** (tables-colours plan D1,
  §The programmer's tables). ⌘K lists `programmerTableNavItems` beside the Programmer row —
  *Programmer › FX*, *Programmer › Stack*, *Programmer › Scenery* — as `?table=` on the one route,
  `pathMatch` the route's, the way the palette families are `?family=` on `/palettes`; the Programmer
  row itself lands on the window's own table. The *Applied* row that was the Programmer's child is
  gone with its route; a row's `parent` (drawn indented, `data-nav-child`, left off a project's home)
  is still honoured, and no row uses it now.
- **The ⌘K window commands are actions, not `NavItem`s.** `useWindowCommands()` in
  `src/navigation.ts` builds ⌘K's *Screens* group from the windows registry the way
  `usePaletteFamilyNavItems` builds its four from the family list, and `buildWindowCommands` is the
  pure half `navigation.test.ts` pins: *Go full screen* / *Exit full screen* (⇧F, absent where the
  browser has no Fullscreen API), *Screens…* with the window count, *Show <view> on <window>* for
  every **other** window × the eight views in `lib/windowViews.ts` (this window has the Navigation
  group already), *Open <view> on another display* (Chrome only, absent elsewhere — D13's rule that a
  missing feature is quiet, never a disabled row saying "use Chrome"), *<Window> · own selection* /
  *<Window> · follow the desk selection* for every other Busk or Programmer window (desk-follow D4,
  the unlink withheld where that row's focus forces following), and *Follow the desk selection in
  this window* with its state as the detail — its *Stop following…* arm withheld in Rig and Pads
  focus — and *<Window> · page with the desk* / *<Window> · own page* for every other **busk**
  window (desk-follow D6), offering only the arm that changes that row's announced `pageFollows`
  (a row that has not announced it counts as paged with), written as a `windows.viewOptions`
  frame under the row's view. They carry a `run`, not a `path`, because most of
  them move *another* window; the sidebar never lists them.
- **Exception — cards/list sibling routes**: list views that pair with a cards
  view (`/fixtures/list`, `/groups/list`, `/channels/:universe/table`,
  `/show/stacks/:stackId/table`, `/show/stacks/:stackId/timeline`) deliberately have **no** `navItems`
  entry. They're reached via the in-page Cards/List switcher
  (`src/components/ViewSwitcher.tsx`) and Cmd+K item deep links, and the
  sidebar keeps one entry per resource; the cards route redirects to the list
  when the sticky view preference says so. Follow that pattern for any new
  cards/list pair instead of adding a second sidebar row.
- **There are four live views: Programmer · Show · Prompt Book · Busk.** The programmer is
  `/projects/:id/programmer` (`ProgrammerPage`); `/show` (`ShowPage`) is *both* the
  cue/stack authoring surface and the runner. `/program*` and `/run*` both redirect to
  the `/show` equivalent, and `/program*` **carries the search string**, because
  `?cue=` deep links are how the Prompt Book's "Edit cue" reaches a cue.
  Show's own entry is not a constant: its `path` reads the window's remembered show position, so
  the sidebar and ⌘K return to the stack and card this window last had open (§"The Show
  position"); the redirects deliberately do not.

  **Busk is `/projects/:id/busk`** (`routes/Busk.tsx` → `components/busking/BuskingView`):
  the rig band, the page the operator built and the side sheet, under the same `ShowHeader` as
  the other three and — since the busk-chrome plan's session A — **no `ShowBar`**: the route still
  calls `useShowBarProps` and hands the transport to the sheet's Show tab (§Focus and the side
  sheet). The page itself is §The busk
  layout; this section is the route and the surface around it. It was `/fx`, which named
  the machinery rather than the job and sat one hyphen from `/fx-library` — the collision
  `lib/navMatch.ts` exists for. `LegacyFxRedirect` keeps both spellings of the old path
  alive; the nav entry keeps `id: "fx"` as its stable handle, the same call `program` made
  when Show was renamed.

  Four things about it that are decisions rather than detail:

  - **The pads are the *library*, and nothing else.** A busk pad presses a **named thing from the
    library** — a palette, a Look or a cue — and nothing on this page mints one. The view used to
    draw three pools of ad-hoc FX pads besides, a Controls pool of hold-to-slide property pads
    writing straight to the programmer, and a beat-division toggle to parameterise whatever those
    minted; all of it went when the view was brought back onto its design, because a grid minting
    anonymous FX instances with their own timing model was a different gesture wearing the same
    clothes. Three things follow, and none is a bug:
    - **Nothing on this page mints an FX instance.** An ad-hoc FX reaches the stage through a
      Look, a cue, or the Programmer's `+ FX`; a raw level through an
      intensity palette or the Programmer.
    - **`useSpeedMasterForFamily` lost its caller here.** The FX pads were the only surface
      doing the busking plan's D1 stamping, and it is client-side — the backend serves `family` but
      does not resolve it. The rail's caption was reworded off the promise it could no longer keep,
      and should stay reworded: nothing on *this* page stamps a master. The hook itself was kept
      rather than deleted, and `PaletteEditor` is its caller now (see §Speed Masters).
    - **`BuskingView` reads no target's running FX.** The eight fixed RTK Query slots that once
      fanned the selection out (and capped it at eight targets — `FU-BUSK-TARGET-CAP`, now retired)
      went with the pads. A palette or Look pad reads the programmer's **resolved applied state**
      (`useProgrammerAppliedQuery`; the view does not subscribe to the layer stack at all), which
      needs only `{type, key}` per target, so `lookLayerTarget` is the one place the group-name
      convention is applied and the selection has no ceiling. A **cue** pad reads `cueHolds.state`
      instead (`useCueRings`, `store/cueHolds.ts`) — what holds the cue, a stack or a press —
      which is what makes a cue pad a toggle rather than a playhead move.
  - **It does not pass `canOperate`, and the show-editing lock is not consulted.** GO must
    work from a busk pad: busking *is* the live use, and the lock is a stray-click guard for
    editing surfaces rather than a transport gate — the same reasoning that keeps `locked`
    away from `canOperate` on `/show`.
  - **The target band replaced a sidebar, and a tile is a plain toggle.** It was `TargetBand` —
    two rows of pads in one `grid-flow-col` container, groups then fixtures, scrolling sideways —
    and is the **rig band** now (§The rig), rows of tiles the operator built, with the same
    press. The list the band replaced was left-click-replace / right-click-toggle, which has no
    touchscreen gesture and no discoverable mouse one; `selectTarget`, the replace, survived it
    only for the narrow-width *Pick targets…* sheet, and went with that sheet (D15) — the
    selection hook has one write beside clear, `toggleTarget`, by `{type, key}`.
    `SelectedTargetSummary` went with the sidebar, and `Breadcrumbs`' `extra` / `onExtraClick`
    went with *it* — the busk view was their last consumer, so every breadcrumb trail is now
    `Projects > Project > <View>`.
  - **There is no empty-selection dim, and re-adding one would be a regression.** The pools used to
    grey themselves out with nothing selected. Three of the things a pad can now hold do not need a
    selection at all — a **per-fixture** palette names its own heads, a **Look** lands whole, and a
    **cue** has no targets — and the one case that genuinely needs one is refused *by name*
    server-side (`PALETTE_NEEDS_SELECTION`), which is a better answer than a grey page. A bank mixes kinds anyway, so the old per-section dim
    has nothing left to be per. **The band does not dim in edit mode either**, any more — it did,
    on the reasoning that pads do not press then and the selection they would press onto is doing
    nothing; but in edit mode the band is being *edited*, its tiles are drag handles and take
    drops, and a dim over a drop target reads as "not here". Only the show-all fallback is drawn
    dimmed while editing, because nothing on it can move (§The rig).

  **`look-groups-design/` in lighting7 is the layout authority** — `Main.dc.html` for play mode,
  `Edit.dc.html` for edit mode, `Layout.dc.html` for the rows/columns/banks structure and the three
  bank drop zones. It supersedes `busking-view-design/`, which drew the pools this page no longer
  has. Two conventions carried over and should hold for anything added here:

  - **One label, `EditorLabel`** (9px bold uppercase, wide-tracked, muted, **no icon**; it was
    the busk view's `BuskLabel` until the editor kit took it as the label over every control,
    `components/editor/EditorLabel.tsx`), on every
    region — band, library, rail. Regions once drew a larger icon-bearing heading, which made three
    parts of one instrument read as three surfaces. It renders a `<div>` deliberately, so a test can
    reach a region's body by walking up from its label.
  - **The target band lives inside the left column**, so the rail's border runs from under the
    ShowBar to the bottom of the page rather than starting below the band.

  **Holding a speed-master card turns it into a tempo fader**, which is the busk view's own
  hold-to-slide gesture — the one the property pads carried before they were deleted. It is the
  third way to set a tempo and they do not overlap: TAP finds one you can hear, the number sets one
  you know, and the drag *trims* one that is nearly right, which is what a busking operator does
  most and had no gesture for. Six things:

  - **The whole card is the fader**, armed after `SLIDE_HOLD_MS` and seeded from the point the press
    started at, so nothing jumps when the hold takes. `useLongPress` hands `onLongPress` that
    origin for exactly this. The rest of the drag lives on **window** listeners — a fader is
    followed past the edge of the thing that started it — keyed on the `sliding` *boolean* and never
    on the dragged value, or every `pointermove` would tear the listeners down and rebuild them.
    They must listen for **`pointercancel` as well as `pointerup`**: the rail is a scroller with no
    `touch-action` of its own, so on a touchscreen a drag the browser reclaims as a pan ends with no
    release at all, and a card left `sliding` writes a tempo on the next pointer movement anywhere
    on the page with nothing held down. `useLongPress` cancels on it too, or the armed hold fires on
    a finger that is already scrolling something else.
  - **The travel is `SLIDE_MIN_BPM`..`SLIDE_MAX_BPM` (60..180) in `lib/speedMasterModel.ts`**, not
    the clock's 20..300. Deliberately the same window as lighting7's `Does.Tempo` default
    and for its stated reason: a drag across the whole range spends most of its travel in tempos
    nobody plays at. It is a **control** range, not a limit — typing and TAP still reach the clock's
    ends. Don't "fix" it by widening it to the clock's range.
  - **It applies as it goes**, because that is what a fader is for: the tempo is judged by ear
    against a running show, and a control that only lands on release makes that guess-then-check.
    `useLivePush` (`components/editor/useLivePush.ts`, the editor kit's) is the traffic half of the
    same decision, not a softening of it — writes are
    deduplicated on the whole BPM and floored at `SLIDE_PUSH_MS` (50 ms), a deferred value is held
    and sent when the floor lifts rather than dropped, and the release bypasses both so the value
    let go on always lands. There is deliberately no optimistic pending value after the release,
    unlike the property pads: those waited on a REST refetch, and here the drag has been writing all
    along, so the desk is already at the value being released.
  - **`slideBpmRef` is written by the pointer handlers, never at render time.** A fast drag can
    dispatch a `pointermove` and the `pointerup` that ends it in one task, with no re-render
    between, so a ref assigned during render makes the release send the tempo from the move *before*
    last — silently undoing the operator's final movement. Found by a test, not by inspection.
  - **The click that ends a drag is swallowed** by an `onClickCapture` on the card calling
    `consumeLongPress()`. Capture runs root-to-child, so neither TAP, nor the bpm button, nor a ratio
    chip has to know the gesture exists — which is what lets the drag cross them freely.
  - **A follower cannot be dragged**, alongside its existing TAP and click-to-type refusals: the
    server refuses all three (`SPEED_MASTER_FOLLOWER`). Nor can any master while the desk is offline
    or its bpm field is open.

  **`SpeedMasterDetailSheet` is reached from the sliders glyph in the card's title row**, and only
  from there — the hold belongs to the fader, and a card cannot answer a hold two ways.
  `SlidersHorizontal`, not the footer link's `Settings2`: two identical glyphs a few rows apart read
  as one destination. The glyph is withheld until the REST row arrives, since the sheet edits that
  row rather than the live frame; the fader is not, because it needs only the uuid the live frame
  carries.

  The gesture itself is `hooks/useLongPress.ts`, which replaced two byte-identical hand-rolled
  copies; `PropertyPadButton` had a third (deleted with it), and `CueSlotOverviewPanel` adopted it
  too once its second stage — hold longer and the panel latched its own wiggle-and-cross edit mode —
  went. A slot's cross follows the **busk view's** edit mode now (`useBuskEditMode`), so what is
  left there is one stage: a hold opens the context menu, which is the only way to reach *View* and
  *Clear slot* on touch.

  **The cue column is gone, and a cue is a pad like any other.** There was a fourth region beside
  the Looks pool (`BuskCueStacks.tsx`): a card per runnable stack — name, live pip, current → next,
  Release, GO — and the **pinned cues** as pads below it. All of it went with the automatic layout,
  because a cue that wants a pad is now simply placed in a bank. What that decided, and what it
  costs:

  - **A cue pad is a loose hold, not a playhead move** (lighting7 cue model plan D6, D10, D11). It
    presses through `POST /busk/pads/{id}/press` like every other pad, which takes or lets go of a
    **loose hold** in the desk's hold registry (`fx/CueHolds.kt`) — so the cue goes live *without
    becoming any stack's playhead*, above every stack (the loose band, newest press on top), exactly
    as a cue slot behaves, and pressing it again lets go. Its ring has two looks, from
    `cueHolds.state` through `useCueRings`: **solid** (and `aria-pressed`) while a press holds it,
    the hold the next press lets go of; an **outline** while only a stack holds it, which a press
    takes loose. The stack's hold is never the pad's to drop. Its keys are every held cue, for a
    surface that asks only "is it on stage" (it replaced `useActiveCueIds`). `GoToStackRequest.cueId` lost its only caller and is
    gone: `/show/go-to` names a stack and lands on its first cue.
  - **A stack pad is a busk stack's own GO** (lighting7 busk-stack plan D9, D10): the pad names a
    stack out of the running order, and its press is that stack's GO — start, advance, release past
    the last cue — never the playhead's. Its face is `components/busking/stackPad.ts`'s reading of
    the stack as the stack list holds it (the list `cueRunStateChanged` patches, read once by the view
    and handed down as `PadBehaviour.stackOf`), so it needs no subscription of its own: the stack's
    name, the live cue and the next, solid green while running, *GO starts Q1 …* while stopped,
    *no cues* when empty, and a fade bar timed from the runner slice's last GO for the stack. Its menu
    is GO, Back (the stack's advance BACKWARD), Release (its stop, over its release time) and View;
    no *Pick up*, because the hand never carries a stack — `HeldKind` is every pad kind but
    `CUE_STACK`, and `canHandLand` takes one nowhere. The library's *Stacks* filter lists busk stacks
    only, and `CueStackForm` places a stored busk stack through *Add to busk page*. Putting a stack
    with pads back into the running order is refused `CUE_STACK_ON_PADS` (in `SILENT_CODES`); the form
    asks, naming the pages, and resends with `force`.
  - **GO and BACK are the transport's, in one place** — the ShowBar's until the busk-chrome plan's
    session A, the side sheet's Show tab's footer since (§Focus and the side sheet). The stack
    cards' GO was two requests behind one gesture — `transport.go()` on the live stack,
    `goToStack` on any other — and a busk page has no room for a transport that means different
    things depending on which card it is on.
  - **`pinnedToBusk` is gone entirely** — the column, the *Pin to Busk* toggle in `CuePropsPane`,
    and the `buildCueInput` round-trip. A cue that wants a pad is placed in a bank.
  - **The transport is not a prop any more either.** `routes/Busk.tsx` still holds the one
    `useShowTransport` through `useShowBarProps` — a second instance would mean a second rAF loop
    and a second reconcile effect on one runner slice — but nothing below it needs one.

  **The Effects Overview panel is gone, and there are five overview panels now** — Stage,
  Fixture, Speed Masters, Cue Slots. Effects Overview held a beat dot, master 1's bpm, a TAP, a
  running-FX count and a
  Kill All, and `/fx` used to force it open and its toggle inert for as long as that route was
  mounted, because the busk view had no tempo readout and no view of what was running. Both
  halves of that reason expired: the ShowBar carries the whole speed-master bank on every live
  view, each tile with its own beat dot and TAP, so the panel was a second and narrower answer
  to "what tempo is the desk at" — narrower because it only ever spoke for master 1 — and the
  busk view has a speed rail and pad presence rings besides. **The count and Kill All were not
  moved anywhere.** What is running is listed, FX by FX and removable by name, on the
  programmer's FX table and in its live editor; a "stop everything" gesture, if it is wanted
  again, belongs in the ShowBar rather than in a panel the operator has to open
  first. `store/fx.ts` (the `fxState` RTK Query wrapper) went with it — `api/fxApi` stays, since
  `store/groups.ts` still subscribes to the frame.

  **Speed Masters is the fourth, and it is not that panel returning** (`PD-SPEED-OVERLAY`). The
  difference is the whole of why it is allowed: Effects Overview drew a tempo readout *of its own*,
  narrower than the bar's; `SpeedMasterOverviewPanel` mounts `components/SpeedMasters.tsx` — the
  bar's own component, every master, unchanged — in a wider box, so it is the same answer reached
  from a view that has no bar rather than a second one. It hangs under the app header beside the
  other three, which is what makes it *every* view's and no view's: the programmer gains no chrome.
  Nothing came back with it — no count, no Kill All, no readout of its own — and no tempo is
  computed client-side; the live frame is still the readout, and master 1 and the follower rules
  are `SpeedMasters`' unchanged.

  It passes `room="dedicated"`, which is the only thing it tells the component — see §Speed
  Masters for what that picks and why the panel's thresholds are not the bar's.

  **Its visibility persists per panel and app-wide, which is a feature and a cost, and both are
  accepted.** Opened once it stays open on every view and across reloads: on the programmer that is
  a tempo band on screen, and on Show and the Prompt Book — and on Busk while its sheet's Speed
  tab is open — it is the bank drawn twice until dismissed. Neither is a defect and neither is session 5's band returning — the difference is a
  door the operator opened and can close. Do not "fix" it by making visibility per-view; that puts
  one surface in two states again.

  **Run is gone as a route, replaced by a mode.** Run and Show were never different
  destinations — the only real distinction was whether a stray click can change the
  show, which is a *mode*, and one the Prompt Book already modelled. So the lock came
  across instead of the route (see §The show-editing lock). Both levels of the view
  survive in both modes: locked, `/show` is the runner with a state pip, fade chrome
  and click-to-arm; unlocked (where the pip still arms), it is the same list plus drag, inline edit and the
  create/delete affordances. Two layouts under one switch would have been two views
  with extra steps.

  The programmer's own arrangement is the third it has had, and the reasoning for the
  second is what makes the third safe to state. The programmer was once its own page, then three tabs of a
  collapsed pane inside Program with no nav entry — the argument being that Values /
  Layers / FX are three readings of *one live object* rather than three destinations,
  and that a second sidebar row pointing at one page was the `"/program"` vs
  `"/programmer"` collision. The tabs premise held; the *pane* did not. Three readings
  of one object is an argument for showing them **together**, not for a switcher, and
  a collapsed pane could never do that. So: a page with room, its tables tabs of one column, and
  the FX lens and rings draw an FX on the Values grid, where the readings meet — §The
  programmer's tables. Renaming
  Program to Show removes the near-collision outright.

  Two traps that survive both the rename and the merge:

  - **`pathMatch` never uses `startsWith`.** `mostSpecificActiveId` now lives in
    `lib/navMatch.ts` and matches whole trailing segments (`endsWith(m) ||
    includes(m + '/')`), longest wins. `navMatch.test.ts` pins `/programmer` and
    `/show` apart so the collision cannot come back by accident.
  - **`ProgrammerIndicator` does the same test by hand** and must keep the
    segment-aware form. It is a trap in both directions: while it pointed at
    `/program`, the sibling `/projects/1/programmer` *did* start with it.

  `/programmer/fx` still redirects — FX was a route, then a band of the rail, and is the
  programmer's FX table now (§The programmer's tables). The per-fixture diagnostic
  (`FxSheet`, a second full fixture row model re-rendered on every programmer event) is
  gone: the Values grid's FX lens says per cell what it said, from one index.
- **Two libraries, two entries, and the filter is on the other one now.** `/looks`
  and `/palettes` are separate `navItems` entries and separate routes, because they
  are separate entities (see §Looks, palettes and layers). `/looks` has **no family
  filter at all**: a Look's families are *derived* from its rows, so one covering
  colour and position belongs to two banks at once and filtering by one would hide
  most of the library from most filters. `/palettes` has the sticky filter — the
  library row's `PartitionChips` since the library sheets, remembered under
  `looks.family`, kept under its old name as a private storage key nobody reads by
  name — and there a family **is** an exact partition: a palette holds exactly one.
  `usePaletteFamilyNavItems()` gives Cmd+K four deep links as `?family=` query params
  on the one route, with `pathMatch` the bare `/palettes` so the sidebar highlights its
  single row whichever family you arrived in — asserted in `navigation.test.ts`, which
  also pins the two `pathMatch`es apart.

  Note this is no longer the "where sibling routes do not apply" exception it was
  written as: on `/palettes` sibling routes *would* partition cleanly, and it is still
  one route because the filter is a **view** of a small library rather than a division
  of it. Reach for sibling routes when the sub-views partition the resource *and* the
  operator navigates between them (cards/list, an editor and its diagnostic); reach for
  a filter when the whole library is worth seeing at once.
- **Role filtering**: set `adminOnly: true` on any entry whose destination's API
  is admin only on the backend — `adminOnly {}` in lighting7's `auth/AuthGate.kt`,
  or `requireAdmin()` on every handler the page reads — currently `users`, `sync`,
  `project-sync` and `cloud`. Not on a page an operator
  can read whose *actions* are admin-only: **Updates** is shown to everyone, as its
  tab is, and the panel disables what an operator cannot do.
  `filterNavItems(items, isViewingActiveProject, isAdmin)` drops
  them for operators — role and kind are the only things that drop a row — so neither the sidebar nor Cmd+K offers a page that can only
  answer 403. `useIsNavAdmin()` supplies the flag and treats *anything but a
  resolved OPERATOR* as admin: during the `auth/status` round-trip, and on a
  bootstrap-open desk, the API really is reachable, and the backend refuses the
  call either way. This is presentation, never permission.
- **A row and its page share one predicate** (navigation plan P2): how a row, its ⌘K item and the
  settings tab it opens are offered is `navItemState` — `hidden`, `shown` or `offRig` — beside the
  registry, and nothing else. The settings pages map each tab to its row's id (`TAB_NAV_IDS` in
  `routes/InstallSettings.tsx` and `routes/ProjectSettings.tsx`) and draw a tab only where
  `useShownTabs` offers its row — so the two Sync tabs are an admin's alone, as every cloud-sync
  route is — and draw it off the rig where its row is (below). A tab its row hides, reached by URL,
  opens General, which is the row the sidebar lights there. A new tab gets its row's predicate by
  being in the map; a tab with no row is always drawn.
- **Off the rig, every row stays** (navigation plan D4, Nav.2). A row whose view reads or drives the
  live show carries `needsRig` — the nine are Programmer, Show, Prompt Book, Busk, Stage, Fixtures,
  Groups, Channels and Surfaces, which `navigation.test.ts` pins — and the field is the sentence
  `NotOnRig` says (*The Stage view draws the rig that's live*). On a project that is not on the rig
  `filterNavItems` keeps those rows and marks them `offRig`, and three places draw the mark, all
  from that one answer:
  - the **sidebar** row takes the muted colour and `OffRigDot` — a 6px hollow dot where the Sync
    row's attention dot sits, at the icon's corner on the rail — with *Needs the project on the
    rig* (`OFF_RIG_LABEL`) on its tooltip, expanded and collapsed alike; the header's badge says
    **Not on the rig**;
  - **⌘K** lists the row too, muted, with *Not on the rig* as its trailing hint, and opens it as
    the sidebar does — onto the `NotOnRig` page, not past it. A row that drew in one and not the
    other would be two answers to one question. The per-universe items are the exception, offered
    only on the project on the rig: they name the live rig's universes, not the viewed project's;
  - **Project Settings' Surfaces tab** is drawn with the dot over a `NotOnRig` body, since its
    bindings editor reads the live desk's devices — outside the tabs' disabled fieldset, because
    *Make current* is a runtime gesture an operator may make on a venue.

  **The route draws `NotOnRig` in place of the view** (`components/NotOnRig.tsx`). Every `needsRig`
  route in `App.tsx` is wrapped in `RigGate navId=…`, which draws the view only while the project
  in the URL is on the rig; the view never mounts otherwise, so none of its queries run for a
  project that is not current. It replaced the redirects to the current project that eight of the
  routes carried (Fixtures, Groups, both lists, Channels, the DMX sheet, Show and Busk) and gives
  the three that had none — Programmer, Prompt Book and Stage, which drew the live show's state
  under another project's name — the same answer. The page says *TCH venue isn't on the rig. The
  Stage view draws the rig that's live, and Sleeping Beauty is on it now.*, with **Make TCH venue
  current** (through `ProjectSwitchConfirmDialog`; withheld, with the reason, for a show whose
  venue version is not on this desk) and **Open Sleeping Beauty's Stage** (the row's `path` on the
  current project; withheld where that project is a venue the view is hidden in). **The URL is
  never touched**: a `?cue=` or `?page=` deep link stays where it was, and *Make current* lands on
  exactly the address that was asked for, since the gate draws the view the moment the project
  list says `isCurrent`. A route's `to` is its own address on another project — the lists keep
  `?select=`, Channels its universe and table — and is where *Open …* goes. On the rig is that
  server fact and nothing else (P4): the gate, the sidebar and ⌘K all read the project list's
  `isCurrent`, and nothing added here says *active*. The gate never draws the view for an id the
  list does not hold (a venue version's snapshot is never listed) or when the list cannot be read.

  **A view that was showing the project on the rig follows a switch made elsewhere** (the Projects
  page, another window, MCP's `switch_project`) to its `to` on the new project, with `replace` —
  the job the routes' redirects did for a desk screen, now for every routed view `RigGate` wraps —
  and draws `NotOnRig` only where the new project has no such view (a venue). Project Settings'
  Surfaces tab is the exception: a tab inside the settings page, never gated, it draws `NotOnRig`
  at once on a switch, as it fell back to General before. It waits while a sheet holds unsaved edits
  (`useHasUnsavedSheets`, the count `windows.show` declines on), keeping the view until the sheet
  is saved or let go. A view *opened* on a project that is not on the rig never follows; that is
  D4's page. And a command that puts another window on a view that needs the rig — the Screens
  row's view picker, ⌘K's *Show <view> on <window>* and *Open <view> on another display* — names
  the project on the rig (`WindowView.needsRig`, `windowViewProject`), whatever project that window
  was left on; a library keeps the window's own.
- **Not every nav path is its own route**: `users`' `/install/users` is served by
  `InstallSettings`' `:tab` route, like `sync`, `osc`, `cloud`, `updates` and
  `diagnostics`. Adding a tab means touching `TABS` + a `TabsTrigger` in
  `routes/InstallSettings.tsx`, not `App.tsx` — and a `navItems` entry under
  `install-settings` with the tab's role rule, in `TABS` order, and its id in
  `TAB_NAV_IDS`, so the sidebar, Cmd+K and the tab strip offer it alike
  (`navigation.test.ts`' install cases pin every tab but General, which is the parent
  row; `routes/settingsTabs.test.tsx` pins the tabs).

### The app bar and the switcher

**The app bar names the project on the rig, as a switcher** (navigation plan D1, D5, §4;
`components/appBar/`). Left to right: the desk's friendly name (`install.friendlyName`, else
*Lighting desk*, `lib/projectSwitch.ts`'s `deskNameOf`) small and muted with a divider, the switcher
button, and the pin chip; the chips on the right are unchanged and never shrink. It replaced the
hard-coded title. **On the rig is the server's `isCurrent` (P4)**: the switcher always names the
project the list says is current, whatever the URL is viewing — the sidebar header and the
breadcrumb name the viewed one. `document.title` is *Project · Desk name* from the same answer
(`useDocumentTitle`, mounted in `Layout`).

- **The dot** (`rigDotOf`): green while sending; amber while `venue.hold` holds the current show;
  a **hollow ring** while *Make rig safe*'s blackout holds it (`rigSafety.state`'s `blackout` — dark
  but nothing wrong, and it wins over the hold); grey while the desk is offline. The menu's *On the
  rig* row repeats it. A venue on the rig wears its tag in the button.
- **The pin chip** (`pinChipOf`), for a show on a venue: *TCH venue v7*, then *· held* (amber) while
  held, *· v8 published* while behind (amber on output, muted for drawing only), *· not on this
  desk* (red) for a missing version. It opens the show's home.
- **What folds where**, measured in the app (container widths of the header's content box): the
  desk name shows from **640**; the button's name from **440** — below it the button is the dot and
  the chevron, the name on its label and the menu's first row, because at 375 the chips leave it
  65px (246 of chips; 282 held, when *Blind · venue* joins them) and the name drew one letter; the
  pin chip from **920**, because the chips grow from 366 to 541 at 760 (`ConnectionStatus`'s label,
  the programmer chip's) and below 920 it truncated beside a project's name. Below 620
  `ConnectionStatus` already hides itself while Connected, as before.

**The menu** (`SwitcherMenu`, cmdk inside a popover): *Find a show or venue*, then **On the rig**,
**Shows** and **Venues**, each by name (`switcherGroupsOf` — the filter is the switcher's own, a
name substring, so cmdk's scoring cannot reorder the groups; `shouldFilter={false}`). A row reads
*name · detail* (`switcherDetailOf`: the pin, *no venue*, or a venue's *v8 · 2 shows*) with its
project's **rig** actions from `projectActions` (below): *Open*, *Make current* on every project but
the current one — or *Venue not here* for a show whose venue is not on this desk, *Version not here*
for one whose venue is but holds no version, or none as new as the pin (the action's
`refusalLabel`, its reason on the title; `venueMissing` in `lib/projectSwitch.ts` is the one rule,
the desk's `VenuePins.isMissing` as far as the list can tell). *Settings* is left off a row, which has room for
two buttons beside a venue's name on a phone; it is a press away on the home, the sidebar and ⌘K.
The row's own press is Open. **Enter opens the highlighted row's home** — the first match while typing, since a search
re-highlights the first row drawn — and **⌥Enter makes it current** through the confirmation (the
menu's `onKeyDown` runs before cmdk's and prevents its Enter). cmdk's root takes Enter for the
highlighted row whatever has focus, so the row and footer buttons stop their own Enter and Space
from reaching it (`keepOwnKeys`). The footer is *All projects…*, *New venue…* for an admin and *New
show…*, both through `CreateProjectDialog`, whose `onCreated` opens the new project's home.

**One confirmation for every door** (§4.3): `ProjectSwitchConfirmDialog` takes the project to put on
the rig and owns the switch — the switcher, ⌘K, `NotOnRig` and the Projects card all mount it with
`project` / `onClose`. Its body is mounted only while open, so a closed one subscribes to nothing,
and it refetches the project list as it opens. Its words are **frozen at the press**: the switch's
`projectChanged` refetches the list before `set-current` answers, and the live read would then
describe the incoming project replacing itself while *Switching…* and the close draw. `switchConfirmOf` is the copy: *Put TCH Test on the
rig?* — *Sleeping Beauty's cues, programmer and FX stop, and every channel goes dark except
those TCH Test parks (4).* — and a line for each consequence that applies: the incoming show is
behind its venue on output, so it **opens held**; a **bulk lamp run** on the outgoing project is cut
off where it is; the incoming project is **still blacked out** by *Make rig safe*, so it opens dark
with *Undo* on its banner. The park count and the blackout come from the project list's `parks` and
`safeBlackout`, two read-only fields added for this (Chris's call; nothing else the frontend read
knew another project's parks or blackout). A desk without them says the rule with no count.

**⌘K** lists *Go to <name>* and, for every project not on the rig that can be, *Make <name>
current* through the same confirmation — the only door while immersive, which hides the app bar. Its
**Project · <name>** group is the viewed project's actions (below), so the viewed project's *Make
current* is there and not in the list. *Create New Project* opens `CreateProjectDialog` and then the
new project's home, an admin's *Import project…* and *Add venue…* are the Projects page's two
buttons, and *Telemetry…* goes to `/install#telemetry`, which `TelemetryCard` scrolls to — keyed on
the location as well as the hash, so choosing it again while already there scrolls again — offered
to a signed-in admin only, as `InstallSettings` draws the card. The static `<title>` in `index.html`
is *Lighting desk*, what the tab says before `Layout` mounts (sign-in, boot).

**One set of project actions** (navigation plan §6, P3; `lib/projectActions.ts`).
`projectActions(project, ctx)` answers every action a project has — `id`, `label`, `group` (`rig |
copy | venue | danger`), `visible`, `enabled` with its `reason`, and `run` — and four places render
from it, none with a copy of a rule: the Projects card's ⋮ and the home's ⋮ (`ProjectActionsMenu`,
a group to a separator, a refused item disabled with its reason under its label, since a disabled
item takes no hover), the switcher's rows (the *rig* group less Settings) and ⌘K (the Project group,
and the list's *Go to* / *Make current*). ⌘K draws no *Project Settings* navigation row beside the
group's *Settings*, which takes its words. The table:

| Action | Offered | Group |
|---|---|---|
| Open | the switcher and ⌘K — a card is itself the way in, and a home is where it leads | rig |
| Make current | not on the rig; refused with the reason for a show whose venue version is not here | rig |
| Settings | always (it was the overview's *Configure*) | rig |
| Clone… | a show, or an admin on a venue; refused for a show whose venue version is not here | copy |
| Export… | an admin | copy |
| Update venue to vN… | a show on a venue it is behind | venue |
| Detach from venue… | a show on a venue this desk holds at its pinned version | venue |
| Attach to venue… | a standalone show; opens `AttachVenueSheet` | venue |
| Extract a venue… | an admin, a standalone show | venue |
| History · Publish vN… | an admin on a venue; Publish refused with nothing to publish | venue |
| Delete… | a show, or an admin on a venue; refused on the project on the rig | danger |

*Scripts* left the ⋮: the Library section has it. The rules are pure; an action's `run` goes through
the host a place hands in. `useProjectActions(project, place)` in `components/projectActions/` is the
host the card, the home and ⌘K share — the navigation, the dialog state, the venue history *Publish*
reads — and answers the actions and the dialogs to mount, each mounted only while asked for. The
switcher calls `projectActions` with its own host, whose one dialog is its confirmation. The
predicates are what is offered, never a permission: the desk refuses whatever it refuses.
`projectActions.test.ts` pins the table per kind, role and state; `ProjectActionPlaces.test.tsx`
renders the four places and pins that the card, the home's ⋮ and ⌘K offer one set — a venue's too,
to an admin and an operator — that a show whose venue is missing offers no working *Make current*
in any of them, that ⌘K's *History* and *Publish vN…* for a viewed venue carry their reasons and
open the band's sheets, and that *Attach to venue…* opens its sheet from all four doors.

**Attach to venue…** (venue-attach plan §5, D1–D4; `components/venue/AttachVenueSheet.tsx`, the rules
in `lib/venueAttach.ts`) is Detach in reverse, on Update's shell and rows (`VenueReviewParts.tsx`,
which `VenueUpdateSheet` draws from too). It opens on the venue the show was detached from — first in
the list and marked *Detached from here*, from the project list's `detachedFrom` — at its installed
version, offering the version the show left beside it while the desk holds it. One line says whether
matching is three-way (*Using v7, the version TCH Test left.*) or not: no origin, or the desk's own
reason from `origin.detail` for one it lacks or one of another venue. The review is the attach rolled
back on the desk: `GET …/venue/attach` with no answers, the same `POST` as a dry run with some
(`venueAttachReview`, a query so the last review stays drawn while the next reads), re-read 250 ms
after the last answer (a run of presses is one dry run, each a whole attach rolled back on the desk's
one connection) because answering one decision can retire another — an identity's *Take venue's*
retires the group decisions its members made — or take an answer off one. The desk refuses such an
answer by name (`ATTACH_INVALID`, two sentences `VenueAttachTest` pins), and the sheet drops it
(`staleAnswerIdOf`) and reads again; any other failed read holds *Attach* back, since the review on
screen is not the one for the answers given. The review is grouped
Output (*Attaching changes no output.*, else changes output then drawing only), From the venue (the
counts it inherits), Overrides (drift rows with *Keep mine* / *Take venue's*), Decisions (identity with
its rename's reach — who names the key, by record: *in 8 cues, 1 Look*; scripts said apart, never
rewritten — hall and groups, each with its default pressed, and P8's overlaps, which must be
answered: a dry run sends *Re-address mine* bare and the desk picks the free start, and the attach
sends the start the last review suggested, the address on the button), Comes in from the venue and
Hidden in this show (without an origin each incoming record has *Hide in this show*, the `HIDE`
decision, matched by kind and record; none on a hide the desk refused),
Yours, Will point at nothing, blockers and the desk's notes; an attach behind on output says it
opens held, or, on the rig, runs against its pin. *Attach* waits for every overlap and no blocker,
collapses to its progress — the sheet held open until the desk answers — and closes on the show's
home. `attachVenue` is a silent endpoint; the
sheet draws a refusal and keeps the answers. Detach's confirm says the version it leaves is
remembered.

### A project's home

**`/projects/:id` is a project's home** (navigation plan D2, §5.1, §5.2; `routes/ProjectHome.tsx`),
and it leads with status. A show's is below; a venue's follows it. The header (`ProjectHomeHeader`) is the name and description, then **On the rig**
(green) or **Make current** (refused for a missing venue version, with the reason under it), then the
⋮. The status block is one line per fact:

- **Venue** (`venueLineOf` in `lib/projectHome.ts`): *On TCH venue v7 · v8 published*, the pin
  chip's words, coloured as the behind banner is — red held, amber behind on output, neutral for
  drawing only or up to date, red for a version not on this desk — with the banner's own actions,
  `VenueBehindActions` (*Review update*, *Run against v7…* while held on the rig, *Hold output*
  while running against; the banner keeps *Later*), and *Open venue*. A standalone show reads *No
  venue* beside *Attach to venue…*, which is the ⋮'s action, so it opens the same sheet.
- **Sync**, for an admin only, since every cloud-sync route is an admin's: *Synced · owner/repo · 2
  min ago* or *Not synced*, and *Sync now* through `useSyncNow` (`components/cloudSync/`), the hook
  the Sync tab's Status panel presses too. Its toasts share the global toaster's id, so a run and
  its `cloudSyncDone` frame are one toast; the line re-reads the config on that frame, wherever the
  run started. Why *Sync now* is refused is written under the line, not left on a hover.
- **Live**, only on the rig: each runnable stack's live cue — its live **entry**'s row, so a cue
  placed twice reads the right number — from the stack list the `cueRunStateChanged` frames patch
  (`liveCuesOf`), then each cue a press holds loose (`looseCuesOf`, from `cueHolds.state`, *Loose
  Storm*), *Blind*, and *Held by its venue*. *No cue live* only when nothing holds a cue.

**The sections are the sidebar's**: `homeSectionsOf(filterNavItems(…))` in `navigation.ts`, the
sidebar's own rows for the same project less Desk, drawn as cards — never a hand-picked list, so a
new row reaches the home by being in the registry. A card is the row's icon and label and its
`NavItem.homeCount`, read from `useHomeCounts` (`components/projectHome/`), every count from a query
the frontend already made (P5). **Show counts its stacks, and the Cues card the project's cues once
each** — *2 stacks* and *35*, the cue list's length — because a cue can sit in several stacks or in
none, so a count per stack would count a shared cue twice and miss a loose one (Chris's call,
2026-10-16; the cues moved to their own row with the Cues table, cue model plan session 5). The cue
list and not `ProjectDetail.cueCount`, because the list's tag is the one a cue write invalidates; the
Cues row is Library, so the list is read on and off the rig, as the Looks' is. Off the rig the
`needsRig` cards take D4's treatment — muted, `OffRigDot`, *Needs the project on the rig* — and their
counts (Show's, fixtures, groups, universes, surface bindings) are not read. Patch List counts raw patches, infrastructure included,
because the list shows them. A venue's home draws no Show or Library card, so it reads none of
their lists. `ProjectHome.test.tsx` pins the sections against the rendered sidebar, the held show's
*Review update* and *Run against*, the counts and the off-rig cards; `projectHome.test.ts` the
lines. The status line, the Sync line and the sections are `components/projectHome/HomeParts.tsx`,
which both homes draw from.

**A venue's home** (§5.2, Nav.5; `components/projectHome/VenueHome.tsx`) has the same header — the
name and the *Venue* tag, *On the rig* or *Make current*, the ⋮ — and the same sections, which the
venue filter leaves as Run (Programmer, Stage), Rig and Project: there is no Library. Its status
block is:

- **Version**: *v8 · 14 Oct 2026 · “re-patched LX2”* (`venueVersionLineOf`), clamped to two lines
  with the whole of it on the hover, since a note can run to a paragraph. An admin's is read from
  the venue's history, which carries the note; an operator's — the history route is an admin's —
  from the project list's `venueSummary`, which has the version and its date and no note. *History*
  is the venue's own action from `projectActions`, opening the sheet the band and the ⋮ open.
- **Draft**, an admin's: *Draft: 3 changes (1 changes output)* (`draftLineOf`) in the band chip's
  colours, read through `useVenueHistory` — the band's own read, so the two count one draft. *Publish
  v9…* is the action too, refused with *Nothing to publish* (written under the line) as the band's
  button is, and *Discard* opens `DiscardDraftDialog` where History offers it: a version to go back to
  and changes to lose.
- **Sync**, an admin's: `SyncRow` with `useSyncNow`'s `isVenue` arm, so a run toasts the versions it
  fetched and pushed.

Then **Shows on this venue**: `VenueShowsList`, which History and the Publish sheet draw plain, here
with each show's pin and how it stands (`venueShowsOf` over the project list, by name: the pin chip's
*held*, *v8 published* or *not on this desk*, and the green dot on the one on the rig) and its *Open*
and *Make current* — the show's own **rig** actions, `rigRowActions` in `projectActions`, which the
switcher's rows read too — through the one `ProjectSwitchConfirmDialog`. While the project list is
being read the list and the Version line say *Reading…*, never that the venue has no shows. So a show whose pinned version is not here shows
*Version not here* with the reason on its title, and no button. An operator sees the version and the
shows and no Draft, Publish, Discard or Sync. **`VenueBand` stays** across a venue's views; the home
is History and Publish's second, permanent door. The band's sentence takes a basis rather than
`flex-1` and its chip and two buttons wrap, so on a phone the band no longer pushes the page 56px
wide and at 768 with the sidebar open it is two lines, not a word to a line.

`lib/projectSwitch.test.ts` pins the words and the order; `AppBarSwitcher.test.tsx` the button and
its hold, the menu's order, search with Enter and ⌥Enter through the confirmation (which names the
incoming parks and the hold), the missing-venue row, and the desk name's fold class.
