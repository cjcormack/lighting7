# The live views' chrome — the busk Show tab, immersive on all four, the one-row band, the 40px header

> **Document status: APPROVED, 2026-09-21 — session A shipped the same day (lighting-react
> `aa4e4c54`); session A.5 was added that evening from Chris's review of A on the desk and is not
> started; session B is not started.** The visual design is settled and
> checked in beside this plan at [`busk-chrome-design/`](busk-chrome-design/INDEX.md) — five static
> artboards: the busk window with the ShowBar gone and the Show tab open, the same window immersive
> with the vertical budget, the Show tab at full size, the rig band's one row in its three shapes
> with the fold ladder, and the model with every suggestion's verdict. The live copy at
> <https://claude.ai/artifact/DCgs8coqVqkFoSTXRF6AMg> is a convenience, private to Chris; the
> checked-in files are the authority. This document is the engineering half: the model, the
> decisions and their reasons, and the session split. Where wording here and the artboards
> disagree, this plan wins on behaviour and the artboards win on layout and copy. Every call the
> boards carried was answered by Chris on 2026-09-21 except two, recorded in §11.

## 1. Context

The busk view shipped on 2026-09-21 (`lighting-react` `99e96914`) with 152px of chrome above the
rig band on Chris's 1122×768 desk window — the app header (48), the `ShowHeader` (48) and the
`ShowBar` (56) — and the band itself two rows deep before its first tile, so the first pad sat at
y≈450 of 768. Chris's brief: the view is too inefficient vertically. Take the ShowBar off it and put
a **Show tab** on the side sheet, based on the phone version of the Show view; let the view
**expand over the sidebar and the app header**; and suggest more. The review of the first boards
added three more decisions: the expand toggle is for **all four live views**, the rig band's label
row and controls row become **one row**, and the ShowHeader goes to **40px**.

What this plan is *not*: a change to any desk state, any wire frame, the busk page document, the
rig document, the press routes or the selection. Everything here is per-window and client-side;
lighting7 gains no session and no schema, and the only lighting7 work is this record.

## 2. Decisions taken

- **D1 — The ShowBar leaves the busk view on every board, and the phone runner is the sheet's
  fourth tab.** `RunMobile` — the always-locked layout Show swaps to below 600px — is mounted in
  the docked sheet: its strip (stack picker, cue list, the programmer chip, the tempo chip, DBO),
  the Current and Next cards, and BACK · GO as the tab's static footer, the way Colour and Spread
  keep their verbs. One component, not a copy, fed by the `useShowTransport` the route already
  holds through `useShowBarProps`. Show and the Prompt Book keep their bars; the programmer never
  had one, so the busk view is the second host without a bar and for the opposite reason — not
  too much chrome for the job, but the wrong shape of it. *(Chris's ask.)*
- **D2 — The Show tab's cards are collapsed by default.** The phone opens Current in Stage because
  there the card *is* the stage; on the busk view the rig band is, and two mini-stages a column
  apart would read as two answers. The toggle stays on the card. *(Open — §11; drawn collapsed.)*
- **D3 — The tab strip folds its words to glyphs below 400px of sheet**, so the sheet's 320 floor
  holds with four tabs, the mode toggle and the fold chevron. Each tab's glyph and only the open
  tab's word below 400 — the fold strip's own vocabulary — and every word at 400 and up.
- **D4 — The fold strip shows the live cue number** under the Show glyph, green, an em-dash with
  nothing on stage, from the server cursor the bar already read. A folded sheet still says what is
  on stage.
- **D5 — No transport keys on the busk view.** Space on a focused pad activates the pad; a key
  that also fired GO is two effects from one press on a live rig. GO is the footer, a MIDI `go`
  binding, or the Show view one pill away. *(Declined suggestion, recorded on `Model`.)*
- **D6 — The overlay sheet off the desk board carries Colour · Spread · Show and still no Speed
  tab.** Speed was withheld there because the bar had the tempo chip; the Show tab's strip carries
  it now, so the reason is met by the tab that replaces the bar.
- **D7 — Immersive is a per-window fact for all four live views.** `desk.immersive`
  (`off` | `on`) in `sessionStorage` beside the follow flag and full screen, default `off` on every
  surface; `Layout` hides the sidebar (hides, not collapses), the app header and the four overview
  panels while the window is on a live view with it on, and draws the app on every other route
  (the fixtures list is navigated *from* the sidebar). The expand glyph is the shared
  `ShowHeader`'s, after the host's `actions` and before the switcher, so all four views get it with
  no per-host wiring; the same glyph, inverted, brings the app back. *(Chris: all four, not busk
  only; the first draft was busk only.)*
- **D8 — Immersive is not full screen, and the two compose.** A windowed browser can be immersive
  and a full-screen one can show the app. Neither flips the other.
- **D9 — Immersive rides `viewOptions`, under every live view; nothing new on the wire.** The
  desk's Json is bare, so a top-level announce key would drop the frame (`windowsApi.test.ts` pins
  the key set). The Programmer, Show and Prompt Book entries in `WINDOW_VIEWS` gain an `options`
  descriptor of one entry, the Screens sheet draws a Chrome segment (*App · Immersive*) on every
  live-view row from it generically, `windows.viewOptions` sets it, and *Copy link* carries
  `immersive=` beside `page`, `focus` and `sheet`. `?immersive=on` is consumed at boot like
  `?window=` and stripped; it is not mirrored into the address, since it is not a view's fact.
- **D10 — What the header took with it comes back where it is read.** The blind report and value
  count (`ProgrammerIndicator`) are on the Show tab's strip; the connection pill becomes a red
  *Offline* chip on the `ShowHeader` drawn only while the socket is down — a pill that reads
  Connected all night is what immersive exists to remove; theme, full screen and Screens… are
  ⌘K's. Below `md` the mobile drawer's *button* moves to the ShowHeader's left edge while
  immersive, since the hamburger in the app header is the only navigation there.
- **D11 — The ShowHeader stays and is the way out.** Folding it too would take the switcher, Stop,
  the live dot and the way back, and the way back would have to be a floating button over a live
  view, which the full-screen work already refused. *(Declined suggestion.)*
- **D12 — The ShowHeader is a 40px chrome row.** The shell's height, with the same 32px controls on
  a 4px inset (`px-3 py-1`), on all four live views; the programmer chrome doc's "48 at every
  height" is revised by this. Chrome above the busk band goes 152 → 88 → 40. *(Chris: try 40.)*
- **D13 — The rig band's label row and controls row become one row.** `RIG`, the Cells menu and its
  two steps, the four verbs, the family pill and the desk chip **left-anchored after the verbs**,
  the gap, the Focus control and *Edit layout* right-anchored — the same order in every shape, so
  the chip sits at the same x in Split, Rig and Pads. The selection summary is drawn **only in
  Pads**, in the gap, `min-w-0` with an ellipsis and its full text on the title; in Split and Rig
  the lit tiles say it. The band is 32px shorter in all three shapes. *(Chris: merge them; the
  chip's drift between shapes was the first draft's and is fixed by the anchoring.)*
- **D14 — The family pill stays, and is drawn only while a mask is set.** It is the `families` half
  of the desk selection, which the tiles cannot show; a template outside it is refused by name and
  a Look's rows outside it are skipped. Absent for a plain selection, so most of the time the chip
  follows the verbs directly. *(Open — §11; one span to drop.)*
- **D15 — The folds are re-measured, Pads folds its verbs first, the Cells control keeps its mode
  word, and the floor is two rows by design.** Verbs to icons at ~1020px of band in Split and Rig
  and ~1180 in Pads (the band carries `data-focus`; the summary matters more than the verbs' words
  there); the *Cells:* prefix and the Focus words go at ~820 in every shape and the Cells control
  reads its mode — All · Odd · Even · 1st · 2nd · Invert · Masters — never a bare glyph; the
  summary truncates next; below ~600 the row becomes **two rows** — the selection verbs on the
  first, the pill, chip, summary and Focus on the second — not `flex-wrap`. The numbers are the
  board's and the app re-measures its own. *(Chris: two rows and the mode word.)*
- **D16 — Declined: immersive folding the header, transport keys, denser pads.** Pad density stays
  `FU-BUSK-PAD-SIZE`.

The four below were taken on 2026-09-21 from Chris's review of session A on the desk (1118×820,
the sheet open on Show), and are session A.5's. They amend D13 for Pads and retire one of its
reasons; they do not touch the wire or any desk state.

- **D17 — In Pads the rig row is not drawn, and the pad row is the body's top row.** The rig row's
  controls act on tiles: the Cells menu, Prev and Next narrow or move a selection made on them,
  and Clear releases one — and in Pads there are none on screen, so in the shape that exists to
  give the page the height, a full row of chrome was doing nothing. The use this view is built
  for is a two-screen desk, the rig on one screen and the pads on the other, so a rig operation is
  reached on the rig screen: Cells, the steps and Clear are **not** on the pad row. Spread, Locate
  and Highlight **are** — they act on the rig and an operator expects them on both screens, and
  Spread is arguably the pads' own — as icon verbs. So the pad row is: the `PADS` label, the page
  tabs at the rig row's control size (28px, `text-xs`, the Focus control's own segmented look —
  they were larger and rounder than everything beside them), the three verbs, the selection
  summary in the gap (nothing else says it in Pads), the family pill while a mask is set, the
  chips of D18, then the Focus control and *Edit layout* / *Done* right-anchored. No chevron
  pill: the Focus control is on the row. In Split and Rig the rig row is as session A left it and
  the pad row under the band is the page tabs and the page chip (D18) and nothing else; the
  desk chip stays on the rig row. *(Chris.)*
- **D18 — A chip is drawn only while its window is unlinked.** Both the desk chip and the page
  chip: *Desk* is the resting state, and a pill saying so all night is noise. The *· from
  <window>* readout goes with it — an operator at a two-screen desk knows which screen they are
  selecting from. D13's "the chip sits at the same x in every shape" was the argument for the one
  row being identical in Pads, and it is retired with the chip; D14's family pill stays, being the
  mask and not a readout. *(Chris.)*
- **D19 — The folds re-expand in two-row mode, and a chip folds progressively.** Below the floor
  each row has its whole line, so the words the ladder took away come back while the line holds
  them — the verbs' and *Edit layout*'s, the *Cells:* prefix, the Focus words — and fold again
  below a second, measured rung; the app measures that ladder as it measured the first. A chip
  gives up *· from <window>* first, then its subject (*Targets:* / *Page:*), then truncates,
  each at a rung of the row it sits on, with its accessible name unchanged. *(Chris: the two-row
  rows have the room; the chip has three steps.)*
- **D20 — The `RIG` and `PADS` labels fold** to nothing at the narrow end of each row's ladder,
  before the floor, to gain the width; the row's `data-` attribute is what a test reaches it by.
  *(Chris.)*

## 3. The model

### 3.1 Per-window facts (client)

| Key | Storage | Values | Default | Set from |
| --- | --- | --- | --- | --- |
| `busk.sheet` | `sessionStorage` (exists) | `none` \| `speed` \| `colour` \| `spread` \| **`show`** | unchanged (Speed where the sheet docks) | the tab strip, the fold, `?sheet=`, ⌘K, Screens, MIDI `buskSheetToggle` |
| `busk.lastSheet` | (exists) | adds `show` | — | the fold's memory |
| **`desk.immersive`** | `sessionStorage`, new, `lib/immersive.ts` | `off` \| `on` | `off` | the ShowHeader glyph, ⌘K, the Screens row, `?immersive=` at boot |

`show` joins `LIVE_SHEET_TABS` in `lib/buskWindow.ts`, which lights it in the strip, the fold's
glyph row, the Screens sheet's Sheet segment and the toggle's memory at once. `desk.immersive`
takes `createSyncStore` over `sessionStorageArea` like every other per-tab fact; `?immersive=` is
read once through the same memoised launch-param path `?window=` uses (`lib/windowIdentity.ts`'s
`consumeLaunchParam`), and stripped.

### 3.2 The wire — nothing new

`windows.announce` carries `viewOptions.immersive` under every live view (`'on'` | `'off'`), beside
the busk-only keys; the key set of the announce itself is unchanged and `windowsApi.test.ts` keeps
pinning it. `windows.viewOptions {targetId, view, options: {immersive}}` applies on any live view
through the existing per-view apply, which now has an arm for the three views that had none.
`windowSetupUrl` gains `immersive` in its key list. The Sheet segment's values come from
`LIVE_SHEET_TABS` already. No lighting7 change; MIDI gains nothing (`FU-SURFACE-IMMERSIVE-SET`,
§8).

### 3.3 Where the code goes

- `routes/Busk.tsx` — drops `<ShowBar>`; keeps `useShowBarProps` and hands `showBarProps` and the
  transport to the view for the tab.
- `components/busking/ShowTab.tsx` — mounts `RunMobile` with `ProgrammerIndicator` in its strip
  through a new `strip` slot on `RunMobile`, over a lifted `useRunnerDisplay(projectId, transport)`
  that `ShowPage` switches to as well (its `RunnerDisplayState` is built inline there today).
- `components/busking/SideSheet.tsx` / `SideSheetFold.tsx` — the fourth `TabSpec`; the strip folds
  tab words below 400px of sheet (`@container` on the strip); the fold draws the live cue under the
  Show glyph; the overlay sheet lists three tabs.
- `components/busking/RigBand.tsx` / `RigStrip.tsx` — the one row (D13), the summary in the Pads
  gap, `data-focus` on the band, the re-measured folds and the two-row floor (D15); the Cells
  control's mode-word fold. Session A.5: no Pads arm on the desk board (D17), the two-row
  re-expansion (D19), the label fold (D20).
- `components/busking/BuskPageStrip.tsx` — session A.5's **pad row** (D17): the label, the
  resized tabs, the three verbs, the summary, the pill, the page chip when unlinked, the host's
  Focus control and edit toggle in Pads; `BuskingView` hands the verbs' handlers and the selection
  down as it hands the band's. `components/desk/FollowPill.tsx` / `DeskChip.tsx` /
  `busking/BuskPageChip.tsx` — drawn only while unlinked (D18), and the progressive fold (D19)
  through per-part classes the host supplies, the accessible name kept whole.
- `components/ShowHeader.tsx` — 40px (`py-1`), the `ImmersiveToggle` after `actions`, the *Offline*
  chip, the drawer button below `md` while immersive.
- `Layout.tsx` — one boolean, `immersive && isLiveViewPath(pathname)`, that skips the `<aside>`,
  the `<header>`, the four overview panels and the sidebar's `marginLeft`. Banners, `HandChip`, the
  AI panel and `DeskDndProvider` are untouched.
- `lib/immersive.ts` (new), `lib/windowViews.ts`, `lib/screens.ts`, `navigation.ts`,
  `components/screens/ScreensSheet.tsx`, `useWindowsBridge.ts` — the store, the one-entry
  descriptor on the three other live views, the setup-URL key, two ⌘K commands (*Expand over the
  app* / *Show the app* for this window; the per-window arm beside *Focus…*), the Chrome segment.
- `hooks/useShowBarProps.ts` docblock — "three live views that have one" becomes two.

## 4. UX — what the design draws

`Main`: the window at 1440×1000 with the app drawn; 40px ShowHeader with the expand glyph; no bar;
the one-row band; the sheet with four glyph tabs and Show open (strip, two collapsed cards, BACK ·
GO footer). `Immersive`: the same window with the app hidden, three rig lines, the sheet folded
with the live cue on the fold, the glyph on the other three headers, the budget in three states.
`ShowTab`: 320 and 400, the fold, the phone overlay, six rules. `Band`: Split, Rig, Pads, Pads with
a mask, edit mode, and the ladder at 1260 · 1100 (Pads and Split) · 800 · 700 · <600 (two rows,
Pads and Split). `Model`: facts, wire, code map, leaves/stays, sessions, every suggestion's verdict.

## 5. Implementation — two sessions

All lighting-react. **A, then A.5, then B**: A.5 reshapes the rows B's `Layout` budget is
measured against, and B's Screens-sheet segment reads the descriptor neither touches.
Each session ends with `npm run check` green, its CLAUDE.md paragraph written, and its done-marker
here (strikethrough plus commit hash, nothing more — the detail belongs in the commit message).
Each runs under `/verified-ship`.

### ~~Session A — the Show tab and the one-row band (lighting-react) — Fable 5.1, high~~ — done, lighting-react `aa4e4c54`

- Lift `useRunnerDisplay` out of `ShowPage` (the `activeCue` / `standbyCue` / `nextStack` /
  `completedCueIds` derivation) into `hooks/useRunnerDisplay.ts`; `ShowPage` calls it.
- `RunMobile` gains a `strip` slot (leading, before the spacer) and a `defaultExpansion` prop
  (`null` from the tab, the phone's current default otherwise) — D2.
- `ShowTab.tsx`; the fourth `TabSpec`; `show` in `LIVE_SHEET_TABS`; the strip's `@container`
  glyph fold below 400 with the open tab's word kept; the fold's cue number (D4); the overlay
  sheet's third tab (D6). `routes/Busk.tsx` drops the bar and passes what the tab needs.
- `RigBand` / `RigStrip`: D13 and D15 — the one row, the summary in the Pads gap with `title`,
  `data-focus`, the Pads-first verb fold, the mode-word Cells control, the two-row floor. The
  folded Pads arm (`focus="pads"`) is the one row plus the chevron pill.
- Tests: `SideSheet.test.tsx` (four tabs; the fold's cue number reads the server cursor; the
  overlay lists three), `buskWindow.test.ts` (`show` is live; `?sheet=show` arrives),
  `ScreensSheet.test.tsx` (the Sheet segment offers Show), `RigBand.test.tsx` (the summary only in
  Pads; the chip's DOM order is the same in all three shapes; Cells reads its mode when folded;
  the floor renders two rows), `Busk.test.tsx` (no `ShowBar` mounted), `ShowPage.test.tsx`
  (unchanged behaviour through the lifted hook), `windowsApi.test.ts` (key set unchanged).
- Docs: CLAUDE.md §Focus and the side sheet (the fourth tab, the fold's cue, the strip's fold),
  §The rig (the one row, the folds), §The show-editing lock (the bar's hosts are two).
- Done-marker here.

### Session A.5 — the pad row, the chips, the two-row re-expansion (lighting-react) — Fable 5.1, high

Chris's desk review of session A, 2026-09-21 (D17–D20). Amends session A; nothing on the wire.

- **The pad row** (D17): `BuskPageStrip` gains a `PADS` label, the tabs at the rig row's 28px
  control size (the segmented look of `BuskFocusControl`: an `h-7 p-0.5` group of `h-6 px-2
  text-xs` items), Spread · Locate · Highlight as icon verbs (the band's three, same handlers, same
  `aria-label`s, handed down by `BuskingView`), the selection summary in the gap with its text on
  the title, the family pill while a mask is set, and — in Pads only — the host's Focus control and
  *Edit layout* / *Done* at its end. `RigBand` draws **no** `focus="pads"` arm on the desk board
  any more: in Pads the body is the pad row, then the page. The chevron pill back to Split goes
  with it. `RigStrip` / the short board's merged row are unchanged.
- **Chips only while unlinked** (D18): `DeskChip` and `BuskPageChip` render nothing while
  following; unlinked they draw the dashed *This window* pill as now. The desk chip stays on the
  rig row (Split, Rig); the page chip on the pad row (every shape).
- **Two-row re-expansion** (D19): the rig row's ladder gains a second, measured set of rungs for
  below the 700 floor — the words return while the line holds them (measured in the app: the
  verbs' worded group is 573px, the state group's worded set ~486) and fold again below that;
  stacked `@min-[…]:@max-[…]:` container variants, so no rung depends on rule order. The pad row
  has its own ladder, measured, with the same shape.
- **Progressive chip** (D19): `FollowPill` takes a class per part — the *from* suffix, the subject
  — and the hosts supply the rungs; `aria-label` carries the whole reading so the name a test or a
  screen reader gets never changes with the width.
- **Labels fold** (D20): `RIG` on the rig row and `PADS` on the pad row hide at a rung before the
  floor; `data-rig-row` / `data-pad-row` are the handles.
- Tests: `RigBand.test.tsx` (no Pads arm on the desk board; the desk chip absent while following,
  present unlinked; the re-expansion classes are `@min-`/`@max-` pairs whose numbers order
  correctly; the label's fold class), `BuskPageStrip.test.tsx` (new: the row's order in Pads and
  in Split; the three verbs press the handlers; the summary only in Pads; the page chip only
  unlinked; the tab sizing), `BuskingView.test.tsx` (Pads draws the pad row and no band; the
  Focus control is on the pad row in Pads and on the band otherwise), `FollowPill.test.tsx` /
  `DeskChip` cases (the accessible name is whole whatever the classes hide).
- Docs: CLAUDE.md §Focus and the side sheet (Pads' shape), §The rig (the pad row, D18, D19, D20),
  §One selection, two shapes (the chip's presence rule); `busk-chrome-design/INDEX.md`'s
  *Superseded* line for `Band.dc.html`'s Pads rows.
- Done-marker here.

### Session B — immersive on all four live views, the 40px header (lighting-react) — Fable 5.1, high

- `lib/immersive.ts`: the store, `useImmersive` / `setImmersive` / `toggleImmersive`, the boot
  read of `?immersive=` beside `?window=`, stripped.
- `ShowHeader`: `py-1` (D12), `ImmersiveToggle` after `actions`, the *Offline* chip
  (`useIsDeskConnected`, drawn only while immersive and disconnected), the drawer button below
  `md` while immersive (D10).
- `Layout`: the one boolean (D7); the mobile drawer's open state is lifted so the header's button
  can open it from below.
- `lib/windowViews.ts`: `immersive` option on all four live views; `useWindowsBridge`: the announce
  carries it under every live view, `windows.viewOptions` applies it on any of the four;
  `lib/screens.ts`: the setup-URL key; `ScreensSheet`: the Chrome segment draws from the
  descriptor; `navigation.ts`: the two ⌘K commands and the per-window arm.
- Tests: `Layout.test.tsx` (hidden on each of the four live paths with the fact on; drawn on
  `/fixtures` with it on; drawn on `/busk` with it off), `immersive.test.ts` (boot read, strip, a
  reload keeps the fact), `windowsApi.test.ts` (announce key set unchanged; `viewOptions.immersive`
  present under every live view), `ScreensSheet.test.tsx` (the segment on a Programmer row; *Copy
  link* carries `immersive=`), `navigation.test.ts` (the commands), `ShowHeader.test.tsx` (40px
  row; the glyph on all four; the chip only while offline).
- Docs: CLAUDE.md §Windows, full screen and the hand (immersive, D7–D10), the programmer chrome
  paragraph (48 → 40), `docs/plans/programmer-chrome-design/INDEX.md` a revised line; this
  record's INDEX gains the same *Superseded* discipline the busk-further one has if anything
  shipped differently.
- Done-marker here; then the busk-further design INDEX's *Superseded* pointer is checked against
  what shipped.

## 6. Migration

None. `desk.immersive` defaults off; `busk.sheet` keeps its default; a v-anything busk page, rig
and selection are untouched. A window announcing `viewOptions.immersive` to a desk that predates
this reads as any unknown key does — the registry never learns the vocabulary.

## 7. Explicitly out of scope

- Any lighting7 change: no MIDI target, no announce key, no schema.
- Folding the `ShowHeader` in immersive (D11); transport keys on the busk view (D5); pad density
  (`FU-BUSK-PAD-SIZE`).
- Immersive as anything but per-window: no desk-wide immersive, no `localStorage`.
- A ShowBar change on Show or the Prompt Book beyond the docblock's count.

## 8. Follow-ups to record

- `FU-SURFACE-IMMERSIVE-SET` — a MIDI `ImmersiveSet(windowName, on)` target, `BuskFocusSet`'s
  twin, addressed by registry name and judged by window health. Not built; the Screens sheet and
  ⌘K reach it without lighting7.
- `FU-BUSK-SUMMARY-SPLIT` — if the Pads-only summary turns out to be missed in Split with the rows
  scrolled out of view, the fold strip's head count is the first place to look, and the gap is
  the second.

## 9. Verification

Beyond the unit suites, at the desk after each session:

- **A.** On `/busk` at 1122×768: no bar; the sheet's Show tab shows the live cue and GO fires it;
  the fold shows the cue number; the band is one row and 32px shorter; Pads shows the summary in
  the gap and Split does not; the chip does not move between the three shapes; narrow the window
  until the Cells control folds and confirm it reads its mode; below 700 (measured; the board
  said 600) the band is two rows. On a phone the overlay sheet has Colour · Spread · Show.
  *Still owed at the desk after `aa4e4c54`: the green cue number on the fold was pinned by test
  only, the preview could not drive it without GO on the dev rig.*
- **A.5.** On the two-screen desk, one window in Pads and one in Split: the Pads window shows the
  pad row and no rig row, with Spread · Locate · Highlight acting on the selection made on the
  other screen; neither chip is drawn while both windows follow; unlink the page on one and its
  page chip appears; narrow a Split window under the floor and the verbs' words come back on
  their own line, then go again; the `RIG` label folds last before the floor.
- **B.** Press the glyph on each of the four live views: the sidebar and header go and come back;
  navigate to the fixtures list and the app is drawn; return and it is not. Open a second window
  from the Screens sheet with *Copy link* from an immersive row and it arrives immersive. Set it
  from the Screens row and from ⌘K. Pull the desk's socket and the *Offline* chip appears on the
  header. ⇧F and immersive together on Safari and Chrome.

## 10. Scope honesty

Session A re-measured D15's thresholds in the app at 1100 / 1260 / 820 / 700 on the band's
content box, widened the overlay's right-hand form to the sheet's 320 floor so the runner's strip
fits, and lifted `useMakeStackLive` beside `useRunnerDisplay` because the tab's picker browses;
its commit message and lighting-react's CLAUDE.md are the record. D17 then removed the Pads row
D13 and D15 were partly written for, so `Band.dc.html`'s Pads rows and its ladder's Pads rungs are
superseded by session A.5 rather than redrawn.

The fold thresholds in D15 are measured on the boards' mockup and will be re-measured in the app;
the rule (Pads folds its verbs first; Cells keeps its mode; two rows by design) is the decision,
the numbers are not. `RunMobile` in a 320px column has not been built before; if the cards or
the cue-list sheet need a variant there, that is session A's to find and record as an amendment,
not a reason to copy the component.

## 11. Open questions

- **The family pill on the one row** (D14) — drawn kept, beside the chip, only while a mask is set.
  One span to drop. *Shipped kept in session A; D18 keeps it while dropping the chip.*
- **The Show tab's cards** (D2) — drawn collapsed by default; the phone opens Current in Stage.
  One default either way. *Shipped collapsed in session A.*

Answered 2026-09-21 by Chris: immersive on all four live views (D7); the band's rows merged (D13);
the ShowHeader at 40 (D12); two rows by design below 600 and the Cells control keeping its mode
word (D15).
