# After the rig session

> **Document status: DONE — sessions 1–9 merged 2026-11-09 to 2026-11-10.** Calls decided 2026-11-09
> (§3).

The design record is [`venue-rig-fixes-design/INDEX.md`](venue-rig-fixes-design/INDEX.md), and its
one-page copy [`venue-rig-fixes.html`](venue-rig-fixes-design/venue-rig-fixes.html) holds the
before-and-after of each item. Where the two differ, this plan wins.

## 1. Context

Fifteen notes from an evening programming a show on a venue's rig, 2026-11-09: thirteen written
down (items 1–13) and two added the same day (14, 15). Read from `main` at `29dd61c` (sync and step numbers rechecked at `a398af2`); line numbers
are that commit's and will drift.

1. **OSC senders can't be ranges.** A sender is one IP literal: `OscService.problems()`
   (`osc/OscService.kt:178–193`) runs each through `ipLiteral` (`:480`), which refuses
   `192.168.0.1/24`, and matching is an exact lookup in `Map<address, OscSender>` (`:302`) — UDP in
   `OscReceiver.kt:77–82`, TCP at accept (`OscTcpListener.kt:67–71`), per frame (`:160–164`), in
   `lingerForReplies` (`:150`) and in the removed-sender sweep (`:93–95`). The refused list is keyed
   by exact address and cleared with `removeAll(senders.keys)` (`OscService.kt:304, 380–390`).
   Replies go to the packet's source (`OscReceiver.kt:84, 94–96`) or back down the TCP connection,
   so a range changes nothing there. `osc_senders.address` is `varchar(64)`
   (`models/oscSettings.kt:34–40`), machine-local. No CIDR helper exists anywhere.
2. **The OSC cheat sheet is print-only.** `frontend/src/lib/oscCheatSheet.ts` writes a whole HTML
   document into a blank window and calls `print()`; `CheatSheetCard` in
   `components/osc/OscPanel.tsx` (~300–335) is its one button. The data is `GET
   /projects/{id}/osc/addresses`, typed in `lib/oscAddresses.ts`. No view shows it on screen.
3. **Include leaves multi-element fixtures expanded.** Include answers the keys it loaded
   (`routes/programmerInclude.kt:124, 155`), an element key for a head's row
   (`fx/CueApply.kt:438–441`). The programmer grid's landing
   (`fixtures-list/FixturesListContainer.tsx:1337–1394`, and `landTargets` at `:1101–1120`) adds the
   parent of every element key to `expandedFixtures` (`:1372`), which `lib/listExpansion.ts` keeps
   in `sessionStorage` per window, list and project. A Shehds' colour lives on its zones, so most
   cues open it, and it stays open.
4. **Record's existing-cue list ignores show order.** `programmer/RecordCuePicker.tsx` reads `GET
   /cues`, sorted by name (`routes/projectCues.kt:38`), and `cuePickerRows.ts:53–57` sorts by name
   then id. Each cue carries `entries[]` (`api/cuesApi.ts:320–330`); stacks carry `sortOrder` and
   `inRunningOrder`; `lib/runningOrder.ts:25` filters to the running-order stacks in the order
   given, sorting nothing. Nothing orders cues by the show.
5. **Path has no single size.** `src/main/resources/fx/position/Path.fx.kts:35–54` declares
   `panSize` and `tiltSize` (ubyte, default 64, `unit: dmx`, shown for Circle, Figure 8 and Random).
   The editor draws parameters from the desk's metadata (`FxFields.tsx` `paramItems` `:133–166`,
   `ParamControl` `:1862`); the only combined control is `pair` (`FxFields.tsx:155–160, 266, 2181`,
   checked in `fx/ParameterMetadata.kt:76–81`), a two-thumb low/high range, and `levelPairOf`
   (`fxEditorModel.ts:47–52`) reads any ubyte pair as a level.
6. **The FX colour pickers are not the programmer's.** `fx/FxColourPicker.tsx` and
   `fx/FxColourListPicker.tsx` (`:186, 269`) render `fx/ColourEditorBody.tsx`: a hex wheel, a hex
   field, presets, the palettes row (`FxColourPaletteRow`, `:266–275`), recent values (`:310`) and
   W/A/UV sliders (`:281–305`), but no R/G/B fields and no Pick. The programmer's
   `editor/ColourEditor.tsx` has R/G/B/W/A/UV fields and Pick beside its own palettes band and
   recent values. (The palette editor's `templates/familyControls/ColourControl.tsx` is a third, and
   out of scope.)
7. **Blinders read *Dark too briefly before a follow*.** `fixture/dmx/China2CellLedBlinderFixture.kt`
   has a parent dimmer, strobe, a SETTING program and a SPEED; each cell has warm and cold WHITE and a
   Warmth COLOUR mix. `MoveInDarkPlanner.moves` (`fx/MoveInDarkPlanner.kt:303–317`) moves every row
   whose family the stack names unless it is in `NEVER_MOVES` or the head's colour is its intensity —
   false here, since the parent has a dimmer (`fx/HeadIntensity.kt:96–101`). Stacks default to every
   movable family with 1 s delay and 2 s time (`models/moveInDark.kt:38–48`), and a head dark for
   less than both is `TOO_QUICK` (`MoveInDarkPlanner.kt:231–234`). Nothing asks whether a row has
   anything to travel.
8. **Shehds show no W.** In 24-channel mode its colour and white are on its `Zone` elements
   (`ShehdsLed19RgbwFixture.kt:82–99, 133–142`). The FX scope finds emitters with
   `detectExtendedChannels(targetFixtures.map(f => f.attributes))` (`fx/useFxScope.ts:65`,
   `fx/colourUtils.ts:154–176`), which never reads `elements[]`; a palette's FX passes
   `extendedChannels={undefined}` (`templates/PaletteEditor.tsx:723`). `emitterHeadCounts`
   (`editor/ColourEditor.tsx:311–326`) and `targetEmitters` (`fixtures-list/rowModel.ts:602–626`)
   already walk elements. The programmer's colour cell reads its resolutions' `whiteChannel`
   (`ColourCell.tsx:205`), which should be the heads'; not confirmed.
9. **A show can't rename a venue fixture.** `PatchFacet` is `ADDRESS, POSITION, FOCUS, MEDIA`
   (`models/showOverrides.kt:49`); `EffectivePatch` reads `displayName`, `key`, `headNumber` and
   `fixtureTypeKey` from the venue's row (`models/patchFacets.kt:255–261`); the patch PUT refuses a
   venue patch's name `VENUE_OWNED` (`routes/projectPatches.kt:358–366`); `VenueDiff` classes a name
   as `IDENTITY` (`venue/VenueDiff.kt:309`); the Patch sheet draws a venue row's name as text
   (`PatchSheet.tsx:1185–1191`) and `VenuePatchPanel.tsx:102–105` says it cannot be renamed. A
   display name is unique nowhere (only `(project, key)` is, `models/fixturePatches.kt:113`). A
   facet change reloads the live fixtures only for ADDRESS and MEDIA (`routes/patchFacets.kt:133`,
   `ai/VenueFixtureTools.kt:258`), though `DbFixtureLoader` names each fixture from
   `EffectivePatch.displayName` (`:103–107`). Readers take `displayName` straight off the row, past
   `RigScope.effective` — above all `DaoFixturePatch.toDto` (`routes/projectPatches.kt:1287`), which
   computes the effective patch and still sets the row's name on the `FixturePatchDto` every client
   reads; and `routes/projectBuskRig.kt:414`, `routes/projectPatchGroups.kt:262`,
   `models/cueEvents.kt:89, 229`, `state/VenueHoldService.kt:232`, `fixture/TriggerGuard.kt:126`,
   `fixture/CommandGuard.kt:155, 159`, `venue/VenueRigChecks.kt:249` (`keysOf`) and
   `ai/SetupTools.kt:487` (hidden fixtures). The list is a grep's, read at this commit; a session
   greps again.
   Sync writes `formatVersion` 53 with `minReader` 53 (`sync/dto/SyncDtos.kt`, bumped at `a398af2`
   by surface-setup session 1); the real gate is the importer's `SUPPORTED_FORMAT_VERSION` (53) and
   `MIN_SUPPORTED_FORMAT_VERSION` (`sync/ProjectImporter.kt:420–421`; the comment above them says
   the DTO's `minReader` is written, never read).
   `ShowOverrideJson.facet` is the enum (`sync/dto/SyncDtos.kt:503–511`).
10. **The show's list doesn't follow the show.** Only the Cards view scrolls to the live cue, on
    mount and on GO (`runner/StackDetail.tsx:161–179`, through a `listRef` only Cards attaches). The
    Table (`CueSheet.tsx:736–772`) and the Timeline (`StackTimeline.tsx:697–701`) scroll only for
    the `?cue=` link and the pane's row; arming never scrolls (`CueSheet.tsx:775–784`). The Table is
    virtualised (`sheet/SheetTable.tsx:231–245`), so only `sheet.scrollTo(rowId)`
    (`useSheet.ts:351`) can reach an off-screen row. Entering a running show drills to the live
    stack (`routes/ShowPage.tsx:356–359`).
11. **An auto number lags the order.** Auto numbers are stored. `renumberAutoEntries(stack,
    before)` (`routes/cueNumbering.kt:433–460`) relabels after every stack write, but an auto entry
    at the same index as in `before` **keeps** its number and becomes a fixed point
    (`AutoNumberInput.keep`, `:149`; venue-visit plan D11). So a typed or *Renumber…* number on the
    cue before an unmoved auto cue leaves it behind — Q30 typed, then an auto Q40 — and a typed 4.5
    reads 1, 4.5, 3, 4, 5 until *Fix Order*. Whether a typed number reads out of place is judged
    against the autos' **stored** numbers (`readsOutOfPlace`, `:234–245`). A leading run takes the
    highest free whole numbers below the next number (28, 29 before 30), then decimals
    (`runLabels`, `:264–279`). Three other paths run the same derivation without
    `renumberAutoEntries`: `numberOutOfOrder` (`cueNumberPlacement.kt:153`, which builds `keep`),
    `renumberAsArchived` (`history/RecordRestorer.kt:299`) and `heldAutoNumbers`
    (`sync/ProjectExporter.kt:427`), which writes to sync the auto numbers a reader could not derive.
    The importer reads every number an archive carries as typed (`sync/ProjectImporter.kt:2106`,
    KDoc `:2026–2029`), held autos included, and the v48 precedent (`sync/dto/SyncDtos.kt:52–57`)
    moved the writer and `minReader` when the derivation changed.
12. **Duplicate jars the page.** `CueActions.tsx:242–272` stays put only for `host === 'table'`
    (the Cues library); the cue sheet's bar passes `host="bar"` (`CueSheet.tsx:852–860`), so one copy
    `navigate()`s (a push) to the bare stack path with `?cue=&entry=` — every host but `table` does,
    the cue sheet's `popover` too (`CueSheet.tsx:819`), and the `timeline` host to
    `/timeline?cue=&entry=` (`CueActions.tsx:263–264`). The bare path is `/table`'s sibling
    (`App.tsx:373–387`), so `ShowPage` remounts and redirects (`ShowPage.tsx:422–426`), and the
    deep-link effect (`CueSheet.tsx:758–772`, the Timeline's `StackTimeline.tsx:703–717`) calls
    `cuePaneOpenStore.set(true)`.
13. **The Prompt Book drops a selection while the show plays.** Out of production the lock is
    `!canEdit` (`hooks/useEditLock.ts:17–37`), and `canEdit` is "the current project"
    (`routes/projectPromptBooks.kt:434`), so the lock is not the cause. Suspects, not confirmed: the
    follow scroll on every change of the live sequence (`routes/PromptBookPage.tsx:310–323`,
    `scrollToRegion` in `ScriptViewer.tsx:446–454`), and pages unmounting outside the render window
    (`ScriptViewer.tsx:378–400`), whose `selectionchange` handler then clears the toolbar
    (`:512–518`). `captureSelection` returns while `locked` (`:488`).
14. **Safari scrolls the whole page from the Show view.** The shell is `flex h-dvh`
    (`frontend/src/Layout.tsx:130`); nothing in the app sets `overscroll-behavior`, and `html` and
    `body` may scroll. A wheel over the header, which does not scroll, or past the end of the cue
    list goes to the root. The blank space says the document also overflows somewhere; not measured.
15. **Record's *A new cue after Q13* appends.** `ProgrammerActionBar.tsx:405–414` opens Record with
    only `defaultCueStackId`, its subtitle *`<stack>` · appended*. `ProgrammerRecordRequest`
    (`routes/programmerRoutes.kt:63`) takes `sortOrder`, which `programmerRecord.kt:146` passes to
    `placeCueInStack`; the menu sends none.

## 2. Decisions

### OSC

- **D1 — A sender's address is an IP or a range**: `a.b.c.d/n` (0–32) or an IPv6 `addr/n`
  (0–128). A range is stored as its network in canonical form (`192.168.0.1/24` saves as
  `192.168.0.0/24`); the form says so under the field, and an address and its `/32` (or `/128`) are
  one entry, saved as the bare address. Two entries naming the same address or the same network are
  refused as *listed twice*; overlapping ranges are allowed.
- **D2 — One matcher**, `osc/OscSenderMatcher.kt`, built when the settings load: an exact address
  wins, then the longest matching prefix, IPv4 and IPv6 apart (an IPv4-mapped IPv6 source matches as
  IPv4). Every lookup in §1.1 goes through it. The matched entry names the sender in `osc.state`'s
  log, and `OscLogRowDto` (`OscService.kt:553`, built at `:403–410`) gains the packet's source address, so a message
  through a range still says which machine sent it. History's source stays the entry. The refused
  list drops every address the new settings match, not only exact keys.
- **D3 — The settings form** takes a range in the same field (placeholder `192.168.1.20 or
  192.168.1.0/24`) and shows a chip *range · 256 addresses* on a range row. *Allow this sender* adds
  the exact address, as now. Machine-local, no sync, no migration (64 characters holds an IPv6
  `/128`).
- **D4 — The Addresses section** in the OSC tab (`components/osc/OscAddressList.tsx`), for the
  current project, under the listener settings: header *Addresses · `<project>`* with *port N · UDP
  and TCP*; a filter matching address, name or what it does; sections in the cheat sheet's order
  (Cue stacks, Loose cues, Looks, Busk pads and pages, Masters and tempo, Other), each a table of
  Address, Args, Reaches and a copy button (`navigator.clipboard`; where it is refused or absent,
  the address is selected in its cell and the toast says *Press ⌘C to copy*, which
  `copyOscAddress` in `CopyOscAddressMenu.tsx:99–108`, only toasting today, takes too); then
  *Ambiguous* (the message and its addresses) and *No
  address* (the record and why). Read each time the section mounts and on a *Refresh* button, as
  the Copy menus read it each time they open (`useOscAddressesQuery` with
  `refetchOnMountOrArgChange`, `CopyOscAddressMenu.tsx:79`; `store/osc.ts:51–52` says why: no frame
  says an address moved).
- **D5 — Print goes.** The section classifier (`SECTIONS`, `sectionOf`, `lib/oscCheatSheet.ts:4–18`)
  moves into `lib/oscAddresses.ts`, with new tests of its own (`oscCheatSheet.test.ts` only asserts
  the printed page); the rest of `oscCheatSheet.ts`, its test and the
  print button are deleted, and `OscPanel.test.tsx:34`'s mock of it goes.

### Auto numbers

- **D6 — Every auto number is derived from position on every stack write**, from the typed numbers
  alone. `AutoNumberInput.keep`, the kept-number pass and `renumberAutoEntries`' `before` go, at
  every path in §1.11 (`numberOutOfOrder` and `renumberAsArchived` included). Typed numbers are never
  touched. A run between two typed numbers takes the free whole numbers after the first, then
  decimals only where they run out, as today. No number fits, no number.
- **D7 — Out of place is judged against typed numbers and the run before it, never stored autos.**
  `readsOutOfPlace` becomes: a typed number reads out of place when it is not above the previous
  typed number that is in place, or, for the first typed number in place, when the auto cues before
  it fit neither the whole numbers from 1 below it nor those from 0. An out-of-place typed number
  neither anchors nor bounds a run; the run counts through it, taking its step but never its number
  (numbers are unique in a stack, `uq_entry_number_per_stack`), as today, and the stack offers *Fix
  Order*. So a **leading run counts up from 1** where it fits: two before 30 are 1, 2. Where it fits
  only from 0 it takes the highest whole numbers below, as today: a lone cue before a typed 1, or
  before 0.5, is 0. Where it fits neither, the typed number is flagged and the run counts up from 1
  through it, skipping the numbers typed elsewhere in the stack: 38 before a typed 30 are 1–29 and
  31–39, the 30 flagged, and the auto after it 41. Five autos with the second typed 4.5 read 1, 4.5,
  5, 6, 7.
- **D8 — Sync 54.** The derivation changes, so, as at v48, the writer and `minReader` go to 54 and
  so does the importer's `SUPPORTED_FORMAT_VERSION` (`sync/ProjectImporter.kt:420`): a v53 desk would
  derive 28, 29 where this one shows 1, 2. Every auto number is now derivable, so `heldAutoNumbers`
  goes and a v54 archive carries typed numbers only. A v53-or-older archive's carried numbers are
  typed or held autos, which nothing distinguishes, so they import as typed, as now; the operator
  clears a stray one with the Cue # cell's Clear.
- **D9 — Database step 16**, `state/AutoNumberTidyMigration.kt`: every stack's auto entries
  relabelled once by D6–D7, by its own `runIfNeeded` writing `PRAGMA user_version` and calling
  `MigrationSupport`'s `backUpDatabase`, as `SurfaceGrammarMigration` does, each change logged by stack
  and cue. A database changing none is only marked 16; one that changes some is backed up first to
  `<db>.pre-auto-numbers-<utc>.db`. It fixes the desk's own stacks, whose auto flags are stored; a
  number that arrived typed from an older archive (D8) stays typed. **The cost is stated, not
  designed around**: typing one number moves the auto numbers after it, and a typed number too low
  for the cues before it reads out of order rather than renumbering them.
  `docs/cue-stacks-engineering.md` §"Auto Numbering" loses venue-visit D11's paragraph and says a
  number the show calls should be typed.

### The Show view

- **D10 — `runner/useFollowShow.ts`**, one hook for Cards, Table and Timeline. Entering a stack
  view while the stack runs **centres its live entry**. Afterwards, each change of the stack's
  `activeEntryId` or `standbyEntryId` (GO, GO back, GO to, a follow, arming) brings that entry into
  view **only if it is outside the scroller**, centred. The Table and Timeline go through
  `sheet.scrollTo`, Cards through its list ref. **A `?cue=` link wins**: while one is being honoured
  (`StackDetail.tsx:173–179` on Cards, `CueSheet.tsx:758–772` on the Table,
  `StackTimeline.tsx:703–717` on the Timeline), entry centres nothing,
  and following starts from the linked row.
- **D11 — A hand scroll pauses following for 10 s.** A hand scroll is told by its **input**, never
  by a scroll event: a wheel, a touch move, a pointer down on the scrollbar or scroller, or a
  scrolling key while focus is in the list. The sheets' own scrolls (`keepPaneRowInView`, the
  `?cue=` link, the keyboard cursor's `scrollToIndex`, the virtualiser's corrections, D12's) do not
  pause it. A click selects a row already in view, so nothing moves under the pointer.
- **D12 — Duplicate never navigates from the Show view.** Every host in the Show view stays put.
  `CueActions` reports the copies' entry ids through an `onDuplicated(entryIds)` the view passes
  down; the Table and the Timeline select them in their own sheet (`useSheet`'s selection) and
  scroll to the first, and Cards scrolls to the first (it has no selection). The editor pane keeps
  its toggle and shows what the selection says. The toast says *Duplicated n cues*, with no Undo:
  each copy is its own history entry, so ⌘Z takes them back one at a time. The `?cue=` link keeps
  turning the pane on when it arrives from elsewhere.
- **D13 — The desk's shell is one fixed screen.** First measure, in Chromium on the Show view's
  three views, `document.scrollingElement.scrollHeight` against the window, and fix the element that
  overflows. Then the rule goes on the **shell only**: `Layout` sets a class on `html` while it is
  mounted (`overflow: hidden`, `overscroll-behavior: none`, full height on `html`, `body` and
  `#root`), so the pages outside it that scroll the document (`OnboardingScreen.tsx:35`,
  `ResetPasswordPage.tsx:83`, `DeviceLoginPage.tsx:82`, siblings of `Layout` in `App.tsx:475–482`)
  are untouched. The scrollers the kit owns (`SheetTable`, the Cards list, the Timeline,
  `EditorSurface`, the side sheets) take `overscroll-behavior: contain`. Safari is an operator check
  (§6).

### The programmer

- **D14 — Include lands on the fixture.** A pure `foldLandingKeys(keys, fixtures)` in
  `fixtures-list/landing.ts`: a fixture **with heads**, every one of which is in the keys (or whose
  own key is), is answered as the fixture's key, collapsed; a fixture only some of whose heads are in
  them is answered as those head keys with the fixture to **open for this landing only**; a fixture
  with no heads is its own key, as now. `landTargets` and the Include landing both use it. A
  landing's openings — the fixture's, and the group's it opens in the grouped view
  (`FixturesListContainer.tsx:1112–1117, 1356`, `setExpandedGroups`) — are not written to
  `listExpansion`; the next mount draws both as the operator left them.
- **D15 — Record lists cues in show order.** `cuesInShowOrder(cues, stacks)` in
  `lib/runningOrder.ts`: running-order stacks in show order, then busk stacks by their order, each
  cue once at its first place in that order, under its stack's divider; loose cues last, by name.
  The row still lists every place. The filter matches as now and keeps a divider only over a match.
- **D16 — Record preselects** the included cue on Merge when the include target is a cue, else the
  playhead stack's live cue; no preselection otherwise.
- **D17 — *A new cue after Q13* lands after Q13.** `ProgrammerRecordRequest` gains `afterEntryId:
  Int?`, resolved inside Record's transaction to a position after that entry (400 *Cue stack entry
  not found* when it is not an entry of `cueStackId`); naming it with `sortOrder` is 400. The menu
  item passes the include target's entry; RecordSheet shows *After Q13 in Act 1* under the stack
  select with a *Put it at the end* switch, dropped when another stack is picked. The subtitle reads
  *`<stack>` · directly after Q13*. The new cue takes an auto number from its place (D6). MCP's
  `record_cue` is unchanged.

### FX

- **D18 — One colour editor.** The FX colour parameters (`FxColourPicker`, each entry of
  `FxColourListPicker`) mount `editor/ColourEditor`; `fx/ColourEditorBody.tsx` is deleted.
  `ColourEditor` gains an optional `references` prop: its palettes band then picks a `pal:{uuid}`
  value, drawn as the reference with its swatch, and offers *Save `<hex>` as palette…* — both from
  `FxColourPaletteRow` (`FxColourPalettes.tsx`), unchanged in meaning. Recent values record
  `colour`, as the programmer's do.
- **D19 — Emitters from the heads.** The FX scope's extended channels come from the targets'
  heads, elements included (`emitterHeadCounts`' walk, shared rather than copied); a palette's FX
  offers the emitters any patched head has. A test pins the programmer's colour cell and popover on
  a collapsed Shehds 24-channel row offering W; if it does not, the session fixes it there too.
- **D20 — `controls` in the frontmatter.** Beside `parameters:`, an FX file may declare
  `controls:`, each `{name, label, scales: [panSize, tiltSize], shownWhen?}`, parsed by
  `fx/FxFileLoader.kt` (whose `parseParameter` would read a typeless entry as a `string` parameter,
  `:565`, so a control never goes in `parameters:`). The checks sit with the parameter checks in
  `fx/ParameterMetadata.kt`: two or more parameters of one numeric type, each in at most one control
  and in no `pair`, every name known, `shownWhen` as a parameter's. A control is not a parameter:
  nothing stores it. It rides beside the parameter list — an optional `controls` list, empty by
  default, on `FxRegistration` (`fx/FxRegistry.kt:52`), filled by the frontmatter loader, and on the
  library DTO `FxTypeInfo` (`:102`) — so the FX routes, MCP and scripts see the same parameters as
  before. A script's own `registerFx` may pass one, checked the same way.
- **D21 — The Size slider** reads the largest of its members. A gesture (a drag, a key, a typed
  value) takes the members' values **at its start** as its base and, at *v*, sets every member to
  `round(base × v / baseLargest)`, clamped to its own range, so a drag through small values or 0
  and back keeps the ratio (a base all zero sets every member to *v*). Its range is the members'
  widest. The members fold under More. Path declares it for Circle, Figure 8 and Random.

### Fixtures

- **D22 — Instant rows.** A row is instant when nothing in the head travels to make it, judged by
  its **value and attribute**, never by family or category alone:
  - **instant**: a mixed colour value (`AttributeValue.Colour` on an emitter mix), `WHITE`, `AMBER`;
    a `SETTING` or `OTHER` that is not a wheel, a media slot or a framing control (declares no colour
    previews, no slot images and no `media`); and a `SPEED` on a head with no travelling row, such as
    a blinder's program speed.
  - **travels**: every setting that is a wheel, whatever its category — the colour wheels declared as
    COLOUR settings (`MartinMac250Fixture.kt:255`, `RobeColorSpot575Fixture.kt:346, 351`,
    `Fusion100SpotMkIIFixture.kt:281`) included; `GOBO`, `GOBO_ROTATION`, `GOBO_ROTATION_MODE`,
    `PRISM`, `PRISM_ROTATION`, `FOCUS`, `ZOOM`, `IRIS`, `FROST`, `SHUTTER`, `SHUTTER_ROTATION`,
    `MOVEMENT_MACRO` and the position categories; and a `SPEED` on a head that has travelling rows —
    a mover's vector speed (`MartinMac250Fixture.kt:300–307`, Robe `:341`, Fusion `:358`) or a timing
    channel (`TimingRole`, the Revolution's) — which moves with them, at their time.
  The session writes the table into `docs/cue-stacks-engineering.md` §"Move in dark".
- **D23 — A head whose rows are all instant is set at the moment it goes dark**, with no delay and
  no move time, so it is never `TOO_QUICK`; lit straight through it is still `LIT_BEFORE`, and a cue
  set to *Late* sets it at the dark too. A head with any travelling row moves **all** its rows as
  now, instant ones included, with the stack's delay and time, so `MoveInDarkService`'s one
  `HeadMove` per head (`:114–123`) and `moveInDark.state`'s one state per head are unchanged. One
  predicate, `isInstant(row, head)`, is read by `MoveInDarkPlanner` and `MoveInDarkService`.

### Patching

- **D24 — The NAME facet.** `PatchFacet.NAME`, `NameFacet(displayName)` in `models/patchFacets.kt`,
  laid over the version's patch by `RigScope.effective` (`EffectivePatch.displayName`). Base, drift,
  *Keep mine* and *Take venue's* as every facet. Blank is refused (`FACET_INVALID`); no uniqueness
  check, as for a show's own patch. The key, head and type stay the venue's. **Every reader of a
  patch's name in a show reads the effective one**: the row readers in §1.9, the patch DTO first,
  move onto `RigScope.effective` (or a name lookup it answers), the session grepping for any it
  missed, and a test covers each. A name change
  **reloads the live rig**, as Address and Media do: `announceFacetChange`
  (`routes/patchFacets.kt:133`) and `override_venue_fixtures`' `reloads` (`VenueFixtureTools.kt:258`)
  take NAME, since `DbFixtureLoader` names each fixture from the effective patch.
- **D25 — Where it is set.** The Patch sheet's name cell on a venue row edits it, through `PUT
  …/facets/NAME`, not the patch PUT; the venue panel's Identity block gains a Name card
  (`VenueFacetCard`) and its "not rename" line says *rename for this show, not rekey or retype*.
  `FacetChips` shows it. MCP's `override_venue_fixtures` takes `NAME` with a `name` field;
  `VENUE_FIXTURE_HINT` and the tool description say the name may be the show's.
- **D26 — What it touches.** `VenueDiff` moves `displayName` out of `IDENTITY` into a new
  `VenueFacet.NAME` (`venue/VenueDiff.kt:352`, a wire enum, with its frontend label); a venue rename
  under a show's Name override is drift, else it reaches the show as today. Update's review lists it
  with facet drift. Detach writes the effective name into the copy. Attach pairs a show patch that
  matches a venue patch but for its name as that patch plus a Name override (`VenueAttachPairing`).
- **D27 — Sync 56.** The DTO's `formatVersion` and `minReader` and the importer's
  `SUPPORTED_FORMAT_VERSION` to 56 (session 2 took 54, surface-setup session 5 55): an older reader would
  fail on the enum value in `showOverrides/`, and a clean refusal is what the gate is for.
  `seedShowOnVenue` (`testsupport/VenueTestSupport.kt`) carries a Name override — the rich fixture is a
  standalone show, which holds no overrides; `ProjectRoundTripTest` and the clone test cover it.

### Prompt Book

- **D28 — Reproduce first.** A Playwright run against a cloud-session desk (`scripts/cloud-session.sh`):
  a stack with follows, *In production* off, the show running, a text selection made in the book
  across a GO and across a follow. Record what clears it in the session's *As built*.
- **D29 — The expected fix**: while a text selection or the anchor toolbar is open, or the pointer
  is down in the script, the follow scroll **waits**, and runs once the selection is used or
  dropped; a page holding the selection stays mounted whatever the render window says. If the run
  shows another cause, fix that and record it here.

## 3. Calls

Decided by Chris on 2026-11-09, in three rounds (`venue-rig-fixes-design/INDEX.md`).

1. **Auto numbers always from position, with a startup tidy-up** (D6–D9). Without the tidy-up, and
   closing gaps only after a typed number, were declined.
2. **A show renames a venue fixture's display name only** (D24). Name and key was declined.
3. **Instant rows snap in the dark** (D22–D23). Never moving them, and hiding the warning only, were
   declined.
4. **The OSC address list lives in the OSC tab** (D4). Its own page and a sheet were declined.
5. **Include selects the fixture**, opening it for the landing only when some heads came in (D14).
6. **Record lists in show order, one row a cue** (D15).
7. **Path's Size scales both**, the two under More (D20–D21). Size and Aspect, and a link toggle,
   were declined.
8. **The list follows entry, GO and arm**, pausing 10 s after a hand scroll (D10–D11).
9. **Duplicate stays put and selects the copies** (D12).
10. **One colour editor, the programmer's** (D18).

## 4. Risks

- **D6 reverses venue-visit plan D11 on purpose.** A prompt book or a QLab cue that calls an auto
  number will see it move when a number before it is typed. Session 2's docs say so, and the
  startup step logs every number it changes. A number that reached a desk from an older archive
  stays typed (D8), so a stray Q40 there is cleared by hand.
- **The startup step rewrites numbers on the one deployed desk.** It backs up first; its test runs
  over a frozen database (a step-15 database holding a Q30 / Q40 stack), never regenerated.
- **A follow scroll that fights the operator** is the failure D11 guards; session 3's tests pin
  that a hand scroll holds it off and a click never scrolls.
- **`ColourEditor` grows a second mode.** Its programmer hosts must not change: session 5 keeps the
  existing `ColourEditor` tests green unmodified.
- **Two sync bumps.** Session 2's 54 makes a desk at 53 refuse every show this desk pushes, since
  it would derive other auto numbers; session 8's 56 makes a desk at 55 refuse one. Desks update
  together; that is the rule the importer's gate encodes.

## 5. Implementation — nine sessions

Each session is one PR, run by `/next-session`, titled *Rig session fixes: session N — title*. A
session records what it found or changed from this plan as an *As built* bullet at the end of its
own section, as the earlier plans do. **Step and version numbers are as of `a398af2`**:
surface-setup session 1 took step 15 and sync 53 while this plan was written, and its later
sessions may take more, so a session takes the next free database step and sync version when it
runs and corrects this plan in its commit.
They are stacked in order; never merge a later session's PR before an earlier one's. Sessions 2 and
8 run the full `./gradlew test` and `npm run check` locally (a data step; a sync bump); the others
run `./gradlew compileTestKotlin` and the touched packages' tests where they touch the backend, and
`npm run check:quick` in `frontend/` (root `CLAUDE.md` §"Pre-commit checks").

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | OSC sender ranges and the address list | merged | `claude/magical-ritchie-84882h` | [#410](https://github.com/cjcormack/lighting7/pull/410) | `7296801` |
| 2 | Auto numbers from position | merged | `claude/gallant-meitner-7xk6a8` | [#411](https://github.com/cjcormack/lighting7/pull/411) | `210c26d` |
| 3 | The Show view follows the show | merged | `claude/zen-dirac-kc1wi2` | [#413](https://github.com/cjcormack/lighting7/pull/413) | `5a7f5cc` |
| 4 | Include, and Record's cue list and placement | merged | `claude/sharp-lamport-6erbz0` | [#415](https://github.com/cjcormack/lighting7/pull/415) | `0015e0e` |
| 5 | One colour editor for FX | merged | `claude/venue-rig-fixes-1fe92b` | [#417](https://github.com/cjcormack/lighting7/pull/417) | `35394c6` |
| 6 | Path's Size | merged | `claude/lucid-keller-q7h8l3` | [#418](https://github.com/cjcormack/lighting7/pull/418) | `9f0b797` |
| 7 | Move in dark's instant rows | merged | `claude/brave-darwin-jeducv` | [#420](https://github.com/cjcormack/lighting7/pull/420) | `4b5ccf3` |
| 8 | A show's name for a venue fixture | merged | `claude/eloquent-planck-9djcze` | [#421](https://github.com/cjcormack/lighting7/pull/421) | `afb9b04` |
| 9 | Selecting in the Prompt Book while the show plays | merged | `claude/focused-gates-d819o7` | [#422](https://github.com/cjcormack/lighting7/pull/422) | `30f55d6` |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — OSC sender ranges and the address list

D1–D5.

- `osc/OscSenderMatcher.kt` (new): parse (`addressOrRange`, the canonical network), match (exact, then
  longest prefix, IPv4-mapped as IPv4). `OscService.problems()` through it, *listed twice* on the
  canonical form; the senders map replaced by the matcher in `OscService`, `OscReceiver` and
  `OscTcpListener` (every site in §1.1); `refusedSenders` cleared by match; `OscLogRowDto` gains
  the source address (D2).
- `frontend/src/components/osc/OscPanel.tsx`: the field's placeholder and hint, the range chip
  (D3); `CheatSheetCard` replaced by `OscAddressList.tsx` (new, D4); the section classifier moved
  into `lib/oscAddresses.ts` and the rest of `lib/oscCheatSheet.ts` deleted (D5);
  `CopyOscAddressMenu.tsx`'s copy fallback (D4); the log row's source address in the Received log.
- Tests: `OscSenderMatcherTest` (new: canonicalisation, /32 and /128, exact over range, longer prefix
  first, IPv4-mapped, bad prefix and text refused); an `OscService` settings test (a range saved
  canonical, *listed twice* across spellings, the refused list cleared by a range); a UDP and a TCP
  receive test from an address only a range admits, its log row carrying the source.
  `OscAddressList.test.tsx` (new: sections, filter, the copy button and its fallback, ambiguous and
  unaddressed rows); `oscAddresses.test.ts` gains the classifier's cases (new: each address kind to
  its section), and `oscCheatSheet.test.ts` is deleted; `OscPanel.test.tsx`'s mock of the cheat sheet removed and range-chip cases added.
- Docs: `docs/osc-engineering.md` (the allowlist and the address list); root `CLAUDE.md`'s
  `/install/osc` and OSC lines (ranges, *What *Copy OSC address* … the OSC tab's cheat sheet read*
  becomes the address list).
- Local: `compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.osc.*'`, `check:quick`.
- *As built*: the matcher takes an `InetAddress` (and an IP literal, for the refused list and a
  TCP connection's re-check), and `OscInbound` carries the packet's `source` beside the entry that
  admitted it. A refused clipboard is not always a cell to select: the address list's copy button
  selects its cell for ⌘C, but a *Copy OSC address* menu closes on select and hands focus back to its
  trigger, so both first try the browser's own `execCommand('copy')` — which a plain-http LAN origin,
  where `navigator.clipboard` is absent, still allows inside a gesture — and a menu with neither says
  it could not copy. That copy sets the address on its `copy` event and counts only when the event
  ran: a selected offscreen field alone copied nothing under a dropdown's focus trap, which takes
  focus back to the menu item, while `execCommand` still answered true. The matcher keys exact
  entries by address bytes and compares a prefix bit by bit, and unmaps an IPv4-mapped source with
  `InetAddress.getByAddress`, as `canonicalIp` does. The chip counts above 2²⁰ as a power of two (`range · 2^24 addresses`). The
  `--tests` filter needs `:test`, since `:launcher:test` finds none; in a cloud container
  `compileKotlin` also wanted `-Pkotlin.daemon.jvmargs=-Xmx5g`.

### Session 2 — Auto numbers from position

D6–D9.

- `routes/cueNumbering.kt`: `AutoNumberInput.keep` and the kept-number pass go; `readsOutOfPlace`
  as D7; `renumberAutoEntries(stack)` with no `before`. Every path that derives numbers, found by
  grepping `computeAutoCueNumbers` as well as `renumberAutoEntries` and the `cueEntryIds(…)`
  snapshots taken only for it: the routes, `ai/SetupTools.kt`, `sync/ProjectImporter.kt`,
  `state/CueNumberMigration.kt`, `cueNumberPlacement.kt`'s `numberOutOfOrder` (the `placement:
  ASK` check behind `CUE_NUMBER_OUT_OF_ORDER`, *Move to its place / Keep it here*) and
  `history/RecordRestorer.kt`'s `renumberAsArchived`. The *Fix Order* offer is the frontend's
  `detectOutOfOrder` (`lib/cueNumber.ts:123`, read at `StackDetail.tsx:155`), which reads the stored
  numbers and needs no change.
- Sync 54 (D8): `heldAutoNumbers` and its use in `sync/ProjectExporter.kt:427` go; the DTO's
  `formatVersion` and `minReader` and `ProjectImporter.SUPPORTED_FORMAT_VERSION` to 54, with a v54
  comment in `sync/dto/SyncDtos.kt` beside v48's.
- `state/AutoNumberTidyMigration.kt` (new), step 16, after `SurfaceGrammarMigration` in
  `State.initDatabase` (`State.kt:1527`): its own `runIfNeeded` records 16, backing up through
  `MigrationSupport`'s `backUpDatabase` first, as `SurfaceGrammarMigration.kt` does.
- Frontend: nothing derives auto numbers, but check `StackDetail.tsx`'s out-of-order offer
  (`:139–156`) and its tests against the new rule.
- Tests: `CueNumberingTest`, `EntryNumberingTest` and `CueNumberingRouteTest` for D6–D7 (the rows of
  the design's table, an insert, a delete, a move, a typed number between autos, 38 autos before a
  typed 30 reading 1–29, 31–39 with the 30 flagged and the next auto 41, a lone cue before a typed 1
  or 0.5 reading 0, no room in four places); `AutoNumberTidyMigrationTest` (new) over
  `src/test/resources/db/pre-auto-numbers.sql` (new), a database at 15 holding a Q30 / Q40 stack and
  an all-auto stack (made through the model at this commit, then frozen; never regenerate it),
  asserting the numbers, the log and the backup, and that a tidy database is only marked; a sync
  test that a v54 archive carries no auto number and a v53 one's carried numbers import as typed.
  Every test that asserted D11's kept numbers is changed to the new result, named in the commit.
- Docs: `docs/cue-stacks-engineering.md` §"Auto Numbering" and §"Cue Number Model";
  `docs/sync-engineering.md` (version 54); root `CLAUDE.md` §Database (step 16) and §"Cue Stack Run Endpoints"'
  *Cue numbers are numbers* line (auto numbers).
- Local: the full `./gradlew test` and `npm run check`.
- *As built*: step 16 and sync 54 were still free. `AutoNumberInput` went with `keep`:
  `computeAutoCueNumbers` takes each entry's typed number or null, and `cueEntryIds` went with the
  `before` snapshots. `renumberAsArchived` and `RecordRestorer.renumberAsWritten` went too: the restore's
  `removeEntries` and `ProjectImporter.restore` already run `renumberAutoEntries` on every stack an
  entry joins or leaves, and the images carry typed numbers only. An auto entry given the number it
  shows becomes typed at it (`planEntryNumberEdit`), since an auto number moves with later inserts. Step 11
  (`CueNumberMigration`) now derives the stacks it touches from scratch as well; step 16 would anyway.
  Where the first typed number reads out of place and a later one is in place, the run before the
  later one counts up from 1 or down from it by that one's fit, through the first (38 autos, a typed
  30, an auto, a typed 50 read 1–29, 31–39, 30, 41, 50). A run between a typed number and the next is
  bounded by it even with no room, so a cue there stays blank and the cue after the next counts on
  from it. The tests that pinned D11's kept numbers now pin D6–D7's: `CueNumberingTest`,
  `CueNumberingRouteTest`, `EntryNumberingTest`, `CueStackEntrySyncTest` and `CueModelMigrationTest`
  (whose blank cue step 16 now numbers); `ExportGoldenTest`'s hashes moved with `formatVersion.json`
  alone. The frontend needed no change: `detectOutOfOrder` reads the stored numbers.

### Session 3 — The Show view follows the show

D10–D13.

- `components/runner/useFollowShow.ts` (new; D10, D11), mounted by `StackDetail` for Cards and
  passed a `scrollTo` by `CueSheet` and `StackTimeline`. It replaces `StackDetail.tsx:161–168`'s
  live-entry effect; the `?cue=` effects (`StackDetail.tsx:173–179`, `CueSheet.tsx:758–772`,
  `StackTimeline.tsx:703–717`) stay, and the hook stands down while one is being honoured.
- `runner/CueActions.tsx`: no `navigate()` from a Show view host; `onDuplicated(entryIds)`, which
  `CueSheet` and `StackTimeline` turn into a selection in their sheet and a scroll, and Cards into a
  scroll (D12).
- D13: the measurement first (a Playwright run over the Show view at 1440×900, the three views, a
  long stack), the overflowing element fixed, then the shell class set by `Layout` and the
  scrollers. Record what overflowed in *As built*.
- Tests: `useFollowShow.test.tsx` (new: entry centres the live row; a GO to an off-screen row
  scrolls, to an on-screen one does not; arming does the same; a wheel within 10 s holds it off; a
  programmatic scroll such as the pane row's does not; a click never scrolls; a `?cue=` link wins
  over the entry centring); `CueSheet.test.tsx` and `StackTimeline.test.tsx` through
  `sheet.scrollTo`; `CueActions.test.tsx` (no navigation from the bar, `onDuplicated` given the
  copies, the pane store untouched); a `Layout` test that the shell class is on `html` while it is
  mounted and gone after, and that the reset-password route has none.
- Docs: frontend `CLAUDE.md`'s cue sheet entry in §Sheet kit (following, Duplicate) and
  §"Patterns and Conventions" › Components (the fixed shell, a new paragraph);
  `frontend/docs/show-mode-engineering.md` §"Auto-drill and deep links" (following, and the link
  winning).
- Local: `check:quick`.
- *As built*: measured against this desk run by `scripts/cloud-session.sh` with a 60-cue stack, in
  Chromium at 1440×900 through Vite dev servers on the base and the branch. **Cards overflowed**:
  `document.scrollingElement.scrollHeight` was 3098 against 900, from each cue number's `sr-only`
  copy, absolutely positioned with `body` as its containing block because the Cards scroller was not
  positioned, so its `overflow-y-auto` never clipped them; the Table and the Timeline read 900, inside
  `SheetTable`'s `relative` wrapper. The list is `relative` now and reads 900 with the shell class
  taken off. A GO on the live desk to Q50 centred its row on the Table and on Cards, a wheel then held
  a GO to Q30 off, and the document stayed at 0. `useFollowShow` takes `enabled`, so Cards counts as
  entering when its view mounts (`StackDetail` stays mounted across a view switch), and the hook
  finds the sheet's scroller by a new `data-sheet-scroller` on `SheetTable`. Duplicate's copies wait in
  `useLandCopies` until the stack's refetch lists them, then the sheet selects those it draws
  (`useSheetFollow`, the Table's and the Timeline's one wiring), since its selection drops ids it has
  no row for; they are forgotten on leaving the stack or after 5 s, so a copy a filter hides never
  lands later. Cards centres by the list's own `scrollTop`, since `scrollIntoView` scrolls every
  ancestor too. The phone runner's ⋯ menu is a Show view host too, so its Duplicate stays put, with no
  `onDuplicated`; the Prompt Book's still opens the copy. Scrolling keys pause following only when no
  handler took them (`defaultPrevented`, read once the handlers have run), so the sheet cursor's
  arrows are the sheet's own scroll. The overscroll rule is on `SHEET_SCROLLER_CLASS` (which
  `FixturesTable` shares), `SheetBody`, `EditorSurface`'s popover and the busk side sheets' bodies.
  Every other page in the shell was measured the same way, with the shell class taken off, and none
  overflowed in either direction: at 1440×900 and 390×844 the programmer, the Show overview and all
  three stack views, the Prompt Book, Busk, Stage, Patches, Fixtures, Groups, Looks, Cues, Palettes,
  Speed masters, Settings, Channels, Scripts, the FX library, Install and Projects, and at 1440×900
  also Settings' Surfaces and Sync and Install's Diagnostics and Sync. Cards was the only overflow.

### Session 4 — Include, and Record's cue list and placement

D14–D17.

- `fixtures-list/landing.ts` (new, `foldLandingKeys`), used by `FixturesListContainer.tsx`'s
  Include landing and `landTargets`; the landing's fixture and group openings kept out of
  `listExpansion`.
- `lib/runningOrder.ts` `cuesInShowOrder`; `cuePickerRows.ts` and `RecordCuePicker.tsx` drawing
  dividers and preselecting (D16).
- `routes/programmerRoutes.kt` `afterEntryId`, resolved in `programmerRecord.kt` (with `sortOrder`
  refused beside it); `ProgrammerActionBar.tsx`'s item and subtitle; `RecordSheet.tsx`'s *After Q13
  in Act 1* line and *Put it at the end* switch; `store/programmerOps.ts`'s `RecordRequest` (`:61`) and
  `recordProgrammer` (`:571`).
- Tests: `landing.test.ts` (new: all heads → the fixture, some → the heads and an open, a headless
  fixture kept, a parent key kept, a group's members); a `FixturesListContainer` test that an Include
  of a Shehds' heads selects the fixture collapsed and writes no fixture or group expansion;
  `runningOrder.test.ts` (show order, busk after, loose last, a shared cue at its first place);
  `RecordCuePicker.test.tsx` (new: dividers, filter,
  preselect); a Record route test (`afterEntryId` lands after the entry, a foreign entry 400, with
  `sortOrder` 400); `ProgrammerActionBar.test.tsx` and `RecordSheet.test.tsx` for the request.
- Docs: frontend `CLAUDE.md`'s Record paragraph (*A row names every place…*) and the Include
  landing; `docs/cues-engineering.md` if it lists Record's fields.
- Local: `compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.routes.*'`, `check:quick`.
- *As built*: `landing.ts` holds `useLandingOpenings` beside `foldLandingKeys`: the landing's openings
  laid over the stored set while the list stays mounted, each landing replacing the last's; the
  container's chevrons, Expand all and Collapse all go through its setters, so a row they close is
  closed whichever opened it, a row they open is stored, and a landing's row they leave open stays the
  landing's — unless the gesture means to open it (Expand all, a ⌥-click level expand, a deep link's
  group), which stores it. `foldLandingKeys` also answers each key's fixture (`fixtureOf`), which both
  landings read. `landTargets` is the heads chip's landing (*Odd*, *Select by colour*), so it folds too.
  The Include landing now selects on the commit after its openings, as `landTargets` does: the row door
  keeps only rows that are shown, so an Include of some heads opened the fixture and selected nothing
  (and a member row in a closed group likewise), which predates this session. The
  picker's rows became `cuePickerSections` (one per divider) with `filterCuePickerSections`, and a
  row's places are sorted into the same order (`showOrderStacks`, which `cuesInShowOrder` shares);
  `preselectedRecordCue` is D16's rule, held apart from the cue picked so it is offered on Merge alone
  and never carried into Replace or Remove, filled in when the summary, the playhead or the stack list
  arrives after the open, and the picker brings a picked row into view once. *Put it at the end* is a
  checkbox, the sheet's idiom (the kit has no switch), and *After Q13 in Act 1* is drawn while the
  entry's stack is the choice rather than dropped for good. The menu keeps the bare number the source
  strip shows (*A new cue after 13*, *Act 1 · directly after 13*), and passes the include's entry only
  when the stack list holds it, saying *· appended* otherwise (never a row guessed by cue); the sheet's
  line takes the picker's `Q`. `afterEntryId` is resolved in `routes/programmerSurface.kt`
  (`performProgrammerRecord`'s transaction), not `programmerRecord.kt`; `recordShapeProblem`, which the
  AI surface shares (its `record_cue` sends neither), refuses it beside `sortOrder` and outside CREATE;
  a bad entry is 400 where the core's other in-transaction failures stay 404. `cuePickerRows.test.ts`'s
  by-name cases and `RecordSheet.test.tsx`'s picker order now pin show order; `RecordSceneryRow.test.tsx`
  and `RecordTempoRow.test.tsx` mock the two hooks the sheet now reads.

### Session 5 — One colour editor for FX

D18, D19.

- `editor/ColourEditor.tsx`: the `references` mode; `fx/FxColourPicker.tsx` and
  `fx/FxColourListPicker.tsx` onto it; `fx/ColourEditorBody.tsx` deleted.
- `fx/useFxScope.ts` and `fx/colourUtils.ts` (`detectExtendedChannels` reading elements, or
  replaced by the shared walk); `templates/PaletteEditor.tsx:723` passing the rig's emitters.
- Tests: the FX colour picker draws R/G/B fields and commits a typed R; a `pal:` value reads as the
  reference and *Save as palette…* still works; a Colour Cycle on a Shehds 24-channel offers W; a
  palette's FX offers W when a patched head has it; the programmer's colour popover on a collapsed
  Shehds row offers W. The existing `ColourEditor` tests pass unmodified.
- Docs: frontend `CLAUDE.md`'s FX colour paragraphs (§Looks, palettes and layers' `pal:` section,
  `FxColourPalettes.tsx`).
- Local: `check:quick`.
- *As built*: the FX pickers mount `editor/ColourEditorCore.tsx` through one adapter,
  `fx/FxColourEditor.tsx`, the editor's body moved out of
  `ColourEditor.tsx`, which is now the core plus its two press bands: `PaletteBand` and `FxBand` reach
  `ApplyPicker` and through it `FxEditor`, where the FX colour parameters are drawn, so mounting
  `ColourEditor` there was an import cycle. Its programmer hosts and tests are unchanged. The core
  gained `onRelease` beside `references`: the FX editor's live push and the
  cue FX tab's saves read a release, and the programmer's hosts write every move, so each edit is a
  frame and `onRelease` the gesture's end — the picker let go a frame after its last move, a picker
  key's key-up, a field left, a slider's commit, a recent tapped, the editor closed. A typed channel
  therefore lands when its field is left, where the old hex field released each valid hex. The old
  body's hex field and colour presets went with it, since the programmer's editor has neither (the
  read-out shows the hex); `COLOUR_PRESETS` and `isValidHexColour`, used only there, were deleted.
  The references band is `FxColourPaletteRow` with a frame of its own, and the read-out names the
  palette a value references. `detectExtendedChannels` was deleted for `emitterFlagsOf`, beside
  `emitterHeadCounts` and over its walk in `lib/emitterHeads.ts` (re-exported by both editor modules),
  which `useFxScope` and the palette editor (over the visible fixtures) read. An emitter the targets
  lack is never written, so a stored one is kept. The programmer's picker turns pure white into the
  white LED alone (`#000000;w255`); an FX colour reaches every head and an RGB-only one would go
  black, so the core's `pickerWhiteLed` is off in the FX hosts, white stays `#ffffff` and the
  white-LED hints are not drawn. The
  programmer's colour cell reads W on a collapsed Shehds row from the zones' colour descriptors, as
  its test pins, but the desk built an element's colour descriptor without its bundled emitters, so
  no zone carried `whiteChannel` and neither the cell nor an FX on a Shehds offered W:
  `DmxFixture.extractElementAttributes` now bundles them as the fixture's descriptor does
  (`ShehdsLed19RgbwFixtureTest`). Both
  popovers are the cell's 352px, since the editor's picker is fluid.

### Session 6 — Path's Size

D20, D21.

- `fx/FxFileLoader.kt`: the `controls:` key parsed; `fx/ParameterMetadata.kt`: its checks;
  `fx/FxRegistry.kt`: a `controls` list on `FxRegistration` and on `FxTypeInfo` (declared at `:97`, built at
  `:259` by `getLibrary`, `:255`), which `routes/lightFx.kt:117` serves as the FX library; `Path.fx.kts`
  declares `size`. (`routes/fxDefinitions.kt` is the CRUD of user FX definitions. Their source
  is a script body with no frontmatter, so they carry no `controls`; corrected in session 6.)
- `fxEditorModel.ts` `paramView` and `FxFields.tsx`: the Size slider, scaled from the gesture's
  start, its members under More.
- Tests: `ParameterMetadataTest` (new: a valid control; refused: one member, mixed types, a member
  in a pair, a member twice, an unknown name); an `FxFileLoader` test that `controls:` never lands in
  the parameters; a library route test that Path's parameters are unchanged and its `controls` holds
  `size`; `fxEditorModel.test.ts` (Size reads the largest, scales keeping the ratio, a drag to 0 and
  back keeps it, clamps, all-zero); `FxFields.test.tsx` drawing Size for Circle and not for Line.
- Docs: `docs/fx-engineering.md` §"Path, Colour Cycle and the renamed parameters" (Size) and the
  frontmatter reference; frontend `CLAUDE.md`'s FX fields paragraph (*Each parameter is drawn from
  the desk's metadata*).
- Local: `compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.fx.*'`, `check:quick`.

### Session 7 — Move in dark's instant rows

D22, D23.

- `fx/MoveInDarkPlanner.kt`: `isInstant(row, head)` (D22); a head of instant rows only planned at
  the dark with zero delay and time, never `TOO_QUICK` (D23); `fx/MoveInDarkService.kt` running
  such a head's one `HeadMove` with zero delay and time.
- Tests: `MoveInDarkPlannerTest` — the blinder (a cell's Warmth and the parent's program and speed,
  dark for 500 ms before a follow: moved, not `TOO_QUICK`); a mover's position dark for 500 ms still
  `TOO_QUICK`; a mover with position, a vector speed and an LED colour: all three wait for the delay
  and move together; a MAC 250's colour wheel (a COLOUR setting) and a gobo wheel travel; an
  instant-only head on a *Late* cue set at the dark. `MoveInDarkServiceTest`: an instant-only head
  landing at the dark and reported `SET`. The static plan route's `live` list for the blinder stack
  is empty.
- Docs: `docs/cue-stacks-engineering.md` §"Move in dark" (the instant table, D22).
- Local: `compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.fx.*'` and the move-in-dark route
  tests.
- *As built:* `HeadIntensity` carries what `isInstant` reads: `wheels` (a setting whose options
  declare a colour preview or a gobo, or that declares `media`) and `travels` (the head declares a
  wheel or a travelling category). "A head that has travelling rows" for a SPEED is read from the
  head's declared attributes, not the cue's rows, so a mover's vector speed travels even in a cue that
  sets nothing else. The service's catch-up at a GO gives an instant head the same zero timing, and a
  head lit early that goes dark again is set at once too. The route test is in
  `CueMoveInDarkRoutesTest`, beside the other static-plan tests.

### Session 8 — A show's name for a venue fixture

D24–D27.

- `models/showOverrides.kt`, `models/patchFacets.kt` (`NameFacet`, codec, `EffectivePatch`),
  `venue/ShowOverrides.kt`, `routes/patchFacets.kt`, `venue/VenueDiff.kt`, `venue/VenueUpdate.kt`'s
  review, `venue/VenueDetach.kt`, `venue/VenueAttachPairing.kt`, `ai/VenueFixtureTools.kt` and
  `ai/SetupToolSchemas.kt`, `venue/VenueRigGuard.kt`'s hint; NAME in `announceFacetChange`'s and
  the tool's reload (D24); the row readers of §1.9 onto the effective name; sync 56 (D27), the DTO
  and `ProjectImporter.SUPPORTED_FORMAT_VERSION`.
- Frontend: `api/patchApi.ts` (`PatchFacet`), `lib/patchFacets.ts` (label), `VenuePatchPanel.tsx`
  (the Name card and its line), `PatchSheet.tsx` (the venue row's name cell through the facet).
- Tests: a facet route test (set, blank refused, drift on a venue rename, *Keep mine*, *Take
  venue's*); a NAME change reloading the live rig, the fixture's live name the show's; the busk
  rig, the group members, a cue event's patch, the venue hold and the trigger guard naming it so;
  `RigScope` effective name; Detach's copy named; Attach pairing a renamed patch as an override; an
  Update review listing the drift; `ProjectRoundTripTest` / clone with `RichProjectFixture`'s Name
  override; a 54 archive still reads; an MCP tool test. `VenueFacetCard.test.tsx`,
  `VenuePatchPanel.test.tsx` (new) and `PatchSheet.test.tsx` for the write.
- Docs: `docs/fixtures-engineering.md` §"Facet overrides"; `docs/sync-engineering.md` (56); root
  `CLAUDE.md`'s facet lines (*identity (name, key, type, head) still the venue's*, the facet list,
  the `PUT …/facets/{facet}` row) and the sync history.
- Local: the full `./gradlew test` and `npm run check`.
- *As built:* sync is **56**, not 55: surface-setup session 5 took 55 first. The Name facet's value is
  `{displayName}`, trimmed, at most 255 characters; `RigScope.nameOf(patch)` answers it where a reader
  holds the row, and `PatchFacet.reloadsRig` (Address, Media, Name) is the one reload rule the route and
  `override_venue_fixtures` share; undo's `KindEffects` rebuilds on a Name override too. Of §1.9's
  readers, `state/VenueHoldService.kt:232` was left as it is: it names an internal fixture the hold
  builds to guard trigger and command channels, which no person reads. Readers the grep found beyond
  §1.9: `SurfaceLabels`, the Update review's names. Attach needed no pairing code of its own:
  `venueFacetOf` reads the name as a facet and the pairing loops every facet, so dropping
  `displayName` from a patch row's `attributes` was enough. The Name override sits in
  `seedShowOnVenue`, not `RichProjectFixture`, which is a standalone show; the export golden hashes
  moved with the version and that file. The Patch sheet's venue-row name cell is the own row's rename
  popover, writing `PUT …/facets/NAME`, and the venue panel's Name card sits inside its Identity block.

### Session 9 — Selecting in the Prompt Book while the show plays

D28, D29.

- The reproduction first (D28), its finding in *As built*; then `routes/PromptBookPage.tsx` (the
  follow scroll held while a selection is open) and `components/promptbook/ScriptViewer.tsx` (the
  selection's page kept mounted; the selection's state exposed to the page), or what the run shows.
- Tests: `PromptBookPage.test.tsx` (new) — a selection open across a change of the live entry is kept and
  the follow scroll runs after it is dropped; `ScriptViewer.test.tsx` — a page holding the selection
  stays mounted outside the render window; the locked behaviour unchanged.
- Docs: a new paragraph on the Prompt Book's follow scroll and a held selection in frontend
  `CLAUDE.md` §"Cues, Stacks & Triggers" (neither it nor `frontend/docs/show-mode-engineering.md`
  describes the follow scroll today).
- Then `docs/plans/manual-validation.md` gains `FU-MANUAL-RIG-SESSION-FIXES` (§6), and this plan's
  status line says DONE.
- Local: `check:quick`.
- *As built:* the reproduction ran in Chromium (Playwright) against a cloud-session desk, an 8-page
  PDF with cues anchored on pages 1, 2, 4, 6 and 8 and a follow out of the third. A selection
  **survived** a GO and a follow whose scroll stayed within a viewport of it, though the scroll carried
  it out of sight and left the toolbar floating where the selection had been; a GO TO onto a cue five
  pages on scrolled its page out of the render window, the page unmounted, and the selection and its
  toolbar went (§1.13's second suspect, set off by the first); and a GO landing mid-drag scrolled the
  book under the pointer, so the selection ran on from 221 characters to 1 958. So D29's fix as
  written: `ScriptViewer` reports `onHoldChange` — a pointer down in the script (overlays included,
  released wherever it comes up), a live selection in it or its toolbar open, unlocked only, and
  `(false, true)` on unmount — and `lib/promptBook/useHeldFollow.ts` holds the page's follow scroll
  meanwhile, running it once when the hold ends and not at all when no GO came, the viewer unmounted
  or the operator moved the book themselves (a rail or warning click, *Jump to live*, a `?cue=` link
  cancel it); the first and last pages of a selection stay mounted, not every page between. The same run after the fix: nothing moved under a held selection or drag, a click
  that dropped it or a *Mark cut* that used it then scrolled to the live cue, a hold with no GO
  scrolled nothing, and a selection on page 1 survived a hand scroll to page 7 with page 1 kept mounted.

## 6. Operator checks

Recorded by session 9 as `FU-MANUAL-RIG-SESSION-FIXES` in
[`manual-validation.md`](../manual-validation.md), at the desk: QLab sending from an address inside a
range; the OSC tab's address list beside QLab; Safari on the Show view, wheel over the header and
past the end of a long list; a blinder stack with follows reading no *too quick*; a Shehds' FX
colour offering W; a venue fixture renamed in the show; text anchored in the Prompt Book mid-show.

## 7. Explicitly out of scope

- Renaming a venue fixture's key in a show (call 2).
- An Aspect control for Path, or linked sizes on any other FX (Size is declared per FX).
- A print or PDF of the OSC address list.
- Keeping an auto number still while the cues before it change (call 1 reverses it).
- Following the show in the Prompt Book's cue rail or the phone runner.
