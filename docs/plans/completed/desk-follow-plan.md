# Following the desk — where a window's own selection means something, the paging group, and a mark while linked

> **Document status: DONE, 2026-09-23 — all three sessions shipped; D11 (the badge is the toggle)
> added the same day after Chris tried session 3 — done, lighting-react `3ae75831`.** The design is checked in beside this
> plan at [`desk-follow-design/`](desk-follow-design/INDEX.md): six static artboards covering an
> overview, a survey of six consoles, where a local selection means something, what following the
> page is for, the controls, and the model with every decision. The live copy at
> <https://claude.ai/artifact/5XfWNSgipBMMnb4GPBF2ph> is a convenience, private to Chris; the
> checked-in files are the authority. This document is the engineering half. Where it and the
> artboards disagree, this plan wins on behaviour and the artboards win on layout and copy. Every
> call the boards carried was answered by Chris on 2026-09-23 (§2).

## 1. Context

A window follows the desk twice over. Its **fixture selection** follows `DeskSelection` (multi-screen
plan D1, D8: `lib/deskFollow.ts`, per tab, default on), and on the busk view its **page** follows the
desk's showing page (`lib/buskPageFollow.ts`, 2026-09-16, tri-state for `?page=` arrival). The
busk-chrome plan's D18 then hid both chips while following, on the reasoning that a pill saying
*Desk* all night is noise.

Reviewing D18 (Chris, 2026-09-23) started from one case: a window in **Rig** focus with its own
selection selects for nobody. That generalises, and it surfaced two more problems:

1. **A local selection is only meaningful where the window can both select and act on it.** Rig
   focus selects and has no pads; Pads focus presses and has no tiles, so its presses land on a
   frozen snapshot the operator can neither see nor change from that screen.
2. **A touch-only window cannot leave following**, because the way out is ⌘K alone, and the
   Screens sheet — the place built to set another window up — only *reports* the flag.
3. **The page's setting is half-built.** The Screens row's page picker takes a window off the desk's
   page (`{page: n}` unlinks), but nothing puts it back: windows announce `pageFollows` and never
   apply it. The words (*follows the desk* / *own page* / *Page: This window*) name the mechanism,
   not the job.

The desk's showing page (`state/BuskPageState.kt`) is read by **following windows** and by the
**`BuskPageSet` LEDs**, and by nothing else — no binding presses a pad by page position
(`PressPad` names a uuid). So following the page is a **paging group**: the MIDI surface's
Next · Prev · Set and every window in the group page together. The survey (§2 D5) found that is
exactly what every console does.

## 2. Decisions taken

- **D1 — A window's own selection is offered only where it can both select and act.** Busk
  **Split** and the **Programmer**. Show and the Prompt Book have no selection; the Fixtures and
  Groups lists never bridge (multi-screen D1) and are unchanged. The survey agrees on the shape:
  every console shares one selection by default (by identity — Eos User ID, grandMA3 user profile,
  Titan user, or the console), and leaving it is a deliberate act; MagicQ's *Sync Programmers* is
  the nearest analogue to our local, and no desk lets a screen fall into it as a side effect of its
  layout.
- **D2 — Rig and Pads focus always follow the desk selection** — Rig included when the side
  sheet's Colour or Spread tab is open, though those could act. Rig focus exists to be the selector
  for another screen; a station that selects and colours on its own is what Split is for.
  *(Chris, decision 1.)* The effective rule is `follows || focus ∈ {rig, pads}` on the busk view,
  and the stored flag on the Programmer.
- **D3 — Entering Rig or Pads while local drops the local selection, and says so.** The window
  relinks (`relinkToDesk`: adopts the desk's, publishes nothing), and a toast on that window reads
  *<Window> follows the desk selection again — <Focus> focus presses onto the desk's selection.
  Your own was dropped.* Whichever door moved the focus — the Focus control, the Screens row, ⌘K,
  a MIDI `BuskFocusSet`, `?focus=` on arrival — because three of those act from elsewhere and the
  drop must never be silent. Holding the selection until Split was declined: it needs a chip, in a
  focus that has none, to explain a selection nothing is using. *(Chris, decision 2.)*
- **D4 — Selection follow is set where windows are managed.** A **Selection · Desk | This window**
  segment on every Busk and Programmer row of the Screens sheet, **disabled with its reason** on a
  busk row in Rig or Pads (*Pads focus follows*), never hidden. A new **`windows.follow {targetId,
  on}`** command carries it — the fifth windows command, rebroadcast like the other four; the named
  window applies it and re-announces. It is not a view option, because follow is the window's and
  not a view's, and a Programmer row carries no busk options. The target **refuses** an `on: false`
  its effective rule forbids (a stale row), and re-announces so the row corrects itself. ⌘K gains
  the per-window arm (*<Window> · follow the desk selection* / *· own selection*), mirroring
  *Show <view> on <window>*, and withholds *Stop following…* for this window in Rig and Pads.
- **D5 — The desk page stays a paging group.** Per-window pages everywhere (no desk page, hardware
  bindings naming a window) was drafted first and dropped: every surveyed console shares a page by
  group with a per-surface opt-out — Eos paging groups, Titan's *Follow World Page Change*, Onyx
  Wing IDs, MagicQ bank ties, grandMA3's page per user profile — and MIDI surfaces paging several
  screens together is the reason it exists. *(Chris, decision 3.)* `BuskPageState`, `busk.pageState`
  / `busk.setPage` and the three page targets are unchanged.
- **D6 — Page follow is settable both ways from the Screens row.** A **Page · Paged with the desk |
  Own page** segment beside the page picker. The row writes `windows.viewOptions {pageFollows}`;
  `applyBuskViewOptions` **applies** it — `true` relinks the page (the chip's own press), `false`
  keeps the page the window is showing as its own. No new frame: `pageFollows` already rides the
  announce. The picker itself follows the row's state: on a paged-with row it pages **the group**
  (`busk.setPage`, as a tab click there does); on an own-page row it pages that window
  (`{page: n}`, as today).
- **D7 — A window paged with the desk always says so: a link badge beside the page tabs.** A small
  glyph badge, drawn wherever the page chip is drawn today (the pad row in Split and Pads, the short
  board's merged row) and on Rig focus's folded page strip. While another **open** busk window pages
  with this one it names it (*⛓ Screen 2*; several are listed on the hover), folding to the glyph
  alone on the row's ladder; the hover says a tab click pages them too. On an own-page window the
  badge gives way to the chip. So the page is always marked one way or the other — D18 revisited
  for the page, with a badge rather than the old full chip. *(Chris, decision 4: "always show when
  we're linked, even if it is just a small badge".)* The co-paged windows come from `windows.state`:
  rows on `busk` whose `viewOptions.pageFollows` is not `'false'`, other than this window's own.
  *The badge is also a press — see D11.*
- **D8 — A window following the desk selection always says so: a link badge beside the family
  pill.** Glyph only at every width, hover *Following the desk selection*, on the rig row (Split,
  Rig), the pad row (Pads — which always follows now, so always the badge), the compact rig strip
  and the short board's merged row, and the Programmer's row C. While local, the dashed *Targets:
  This window* chip takes its place and presses back, as today. D18 revisited for the selection as
  D7 does for the page. *(Chris, decision 8.)* `DeskChip` and `BuskPageChip` render the badge while
  linked instead of nothing; one `LinkBadge` component draws both so they cannot drift. *The badge
  is also a press — see D11.*
- **D9 — The words.** Page: *Paged with the desk · Own page* on the Screens segment, *Page: Own* on
  the chip (it was *Page: This window*). Short forms on narrow rows — *With desk · Own* on the
  segment, *Own* on the chip — each at a rung measured into its row's ladder, like every other word
  there. Selection keeps *Desk · This window* and *Targets: This window*. *(Chris, decision 7.)*
- **D10 — Not now.** **Named selections** for a second operator (Eos's User ID model:
  *Desk | B | This window*) — the survey's universal model and the right one for two operators;
  nothing here blocks it. A MIDI **`SelectionFollowSet(windowName, on)`** target — `BuskFocusSet`'s
  twin; the Screens sheet on another window covers the touch-only case. Both recorded as follow-ups
  (§8). *(Chris, decisions 5 and 6.)*
- **D11 — The badge is the toggle, both ways.** Revises D7 and D8's "a mark, never a control".
  Trying session 3 at the desk, the chip on the row was what Chris reached for, and the Screens
  sheet was the wrong primary door: it exists to set *another* window. So each badge is a button
  while linked. The selection's press takes the desk's selection as this window's own
  (`unlinkFromDeskNow`, ⌘K's gesture). The page's press keeps the page on show as this window's own
  (`unlinkBuskPage` over the strip's active page). The dashed chip that replaces it presses back, as
  before, so one control flips the two modes. It is `aria-pressed` (pressed = linked, the pill's
  convention) and the mark's exact box, so no ladder moves. **Where the window cannot leave, it
  stays a mark:** the selection in Rig and Pads focus always follows (D2), so there the badge is
  `role="img"` with the reason on its hover (*… — Pads focus always follows*), not a press that D3
  would undo at once. The Screens row and ⌘K are unchanged, for other windows. *(Chris, decision 9,
  2026-09-23.)*

## 3. The model

### 3.1 Per-window facts (client) — unchanged keys

| Fact | Home | Values | Default | Written by |
|---|---|---|---|---|
| `desk.follow` | `sessionStorage` (exists) | `true` \| `false` | `true` | the chip, ⌘K, **`windows.follow`**, and **entering Rig or Pads** (D3) |
| `desk.localSelection` | `sessionStorage` (exists) | targets + families | empty | unchanged |
| `busk.pageFollows` | `sessionStorage` (exists, tri-state) | `null` \| `true` \| `false` | `null` → follow | the chip, a tab click that never reached the desk, `?page=`, **`windows.viewOptions {pageFollows}`** (D6), `{page}` |

Nothing migrates: every key keeps its spelling and default.

### 3.2 The wire — one frame

- **`windows.follow {targetId: String, on: Boolean}`**, inbound and rebroadcast verbatim as
  outbound — `windows.fullscreen`'s exact shape (`WindowsSocket.kt`,
  `WindowRegistry.Command.Follow`, `toOutMessage`). Nothing is written server-side: the target
  applies it and re-announces `follows`, which is how the registry learns it, as for a rename.
- `windows.viewOptions {pageFollows: 'true' | 'false'}` under `busk` — already legal; the registry
  never learns the vocabulary. The only change is that the client applies it (D6).

### 3.3 Where the code goes

**lighting7**
- `plugins/WindowsSocket.kt`: `WindowsFollowInMessage` / `WindowsFollowOutMessage`, the handler
  arm, `toOutMessage`. `state/WindowRegistry.kt`: `Command.Follow`.
- `WindowsSocketTest.kt`: the round trip, and that a follow for a disconnected row is lost the way
  a show is (`FU-WINDOWS-SHOW-OFFLINE`'s behaviour, not a new one).
- `docs/websocket-engineering.md`: both frame tables and "the four commands" → five.

**lighting-react**
- `api/windowsApi.ts`: `windows.follow` send and subscribe. `store/windows.ts`: `setWindowFollow`.
- `components/screens/useWindowsBridge.ts`: the handler — `on` → `relinkToDesk()`; `off` →
  `unlinkFromDesk(snapshot)` unless this window is on `busk` in Rig or Pads (D4's refusal).
- `lib/deskFollow.ts`: `followIsForced(view, focus)` — the one statement of D1/D2, read by the
  bridge, `BuskingView`, the Screens row and ⌘K.
- `components/busking/BuskingView.tsx`: the D3 effect — on a focus change into Rig or Pads while
  `!useDeskFollow()`, `relinkToDesk()` and the toast. Keyed on the focus, not on mount: a window
  that *arrives* in Pads while local (a reload with stale `sessionStorage`) relinks the same way.
- `lib/buskWindow.ts`: `applyBuskViewOptions` applies `pageFollows` (D6), with a seam beside
  `unlinkPage`.
- `lib/windowViews.ts`: the busk descriptor gains a `pageFollows` enum option (`true` | `false`,
  `valueLabels` *Paged with the desk* / *Own page*); a selection-follow descriptor entry is **not**
  a view option — the Screens row draws the Selection segment itself from `row.follows`, for Busk
  and Programmer rows, and writes `windows.follow`.
- `components/screens/ScreensSheet.tsx`: the two segments; the page picker's two arms (D6); the
  *follows the desk / own selection / own page* captions go.
- `components/desk/LinkBadge.tsx` (new): the glyph badge, with an optional list of names and the
  row's fold class. `DeskChip` and `BuskPageChip` render it while linked.
- `components/busking/BuskPageStrip.tsx`, `RigBand.tsx`, `RigStrip.tsx`,
  `components/programmer/SelectionBar.tsx`: the badges' placement, and the ladders re-measured —
  the rig row's and the pad row's (`RigBand.tsx`'s and `BuskPageStrip.tsx`'s docblocks own the
  numbers) and row C's. Row C is budgeted to the pixel, so the glyph must be measured there, not
  assumed.
- `navigation.ts`: `buildWindowCommands` — the per-window follow arm, the withheld *Stop following*,
  and the page pair per busk window (*<Window> · page with the desk* / *· own page*).
- `CLAUDE.md`: §The busk layout (the page's paging group, the words, the badge), §The rig (the
  chips' D18 paragraph), §One selection, two shapes (the desk chip, the rule, the drop),
  §Windows, full screen and the hand (the fifth command).

## 4. UX — what the design draws

`desk-follow-design/`: **Main** (what we have, what the review found, what is proposed), **Survey**
(six consoles, from their manuals, with sources), **Selection** (the select/act table and the rule),
**Pages** (the paging group, the badge's three states), **Screens** (the Screens sheet's rows, the
selection mark in Split, ⌘K) and **Model** (wire, code, sessions, the eight decisions).

## 5. Implementation — three sessions

Each session ends with its checks green, its CLAUDE.md or engineering-doc paragraphs written, and
its done-marker here.

### ~~Session 1 — `windows.follow` (lighting7)~~ — done, lighting7 `3f4fb5b`

- The frame, the command, the handler, the test (§3.3).
- `docs/websocket-engineering.md`.
- **Restart the desk** after it lands: a new route class does not hot-swap. Until then a client
  sending `windows.follow` gets its frame dropped (the Json is bare), so session 2 must not ship
  before a restarted desk.

### ~~Session 2 — the selection (lighting-react)~~ — done, lighting-react `f01eda67`

- `followIsForced`, the D3 relink and toast, the `windows.follow` handler and its refusal.
- The Screens row's Selection segment; ⌘K's per-window arm and the withheld *Stop following*.
- `LinkBadge`, and the selection badge on the rig row, pad row, compact strip, merged row and
  row C (D8); the ladders re-measured.
- CLAUDE.md §One selection, two shapes, §The rig, §Windows.

### ~~Session 3 — the page (lighting-react)~~ — done, lighting-react `7526b262`

- `pageFollows` applied from `windows.viewOptions`; the Screens row's Page segment and the picker's
  two arms (D6).
- The page badge by the tabs with the co-paged windows' names (D7), on the pad row, merged row and
  the folded strip; the chip's *Page: Own*; the short forms (D9); the ladders re-measured.
- ⌘K's page pair.
- CLAUDE.md §The busk layout.

Sessions 2 and 3 touch the same rows (the pad row carries both badges in Pads) and should run in
that order, not in parallel.

## 6. Migration

None. Every per-tab key keeps its spelling and default; `windows.follow` is additive, and a
desk that predates it drops the frame (the only reason session 1 goes first).

## 7. Explicitly out of scope

- Named selections and a MIDI follow target (D10).
- Any change to the desk page, `BuskPageState` or the page binding targets (D5).
- The Fixtures and Groups lists, which never bridge.

## 8. Follow-ups to record

- `FU-SELECTION-NAMED` — named selections for a second operator (D10).
- `FU-SURFACE-SELECTION-FOLLOW-SET` — a MIDI `SelectionFollowSet(windowName, on)` target (D10).

## 9. Verification

Beyond the unit suites, at the desk after sessions 2 and 3:

- **Selection.** Screen 1 in Split, Screen 2 in Split. Unlink Screen 2 from Screen 1's Screens row;
  its *Targets: This window* chip appears and Screen 1 still shows the badge. Set Screen 2 to Pads
  from the Screens row: Screen 2 relinks and toasts, its pad row shows the badge. The Selection
  segment on Screen 2's row is disabled with *Pads focus follows*. Do it again with a MIDI
  `BuskFocusSet` and with `?focus=pads` on a reload. The Programmer row's segment unlinks and relinks
  an iPad.
- **Page.** Both screens paged with the desk: each shows *⛓ <the other>*; the MIDI page buttons page
  both. Put Screen 2 on its own page from Screen 1's Screens row, and put it back from the same
  row — nobody walks to Screen 2. Narrow each row until the badge folds to its glyph and the words
  to their short forms.
- **Rows C and the rig row** at the widths their ladders were measured at: the badge never moves a
  control.
- **The badge toggles (D11).** On Screen 1 in Split, press the selection badge: *Targets: This
  window* appears, and its press relinks. Press the page badge: *Page: Own* appears on the page
  that was showing, and its press pages with the desk again. In Pads and Rig focus the selection
  badge does not press, and its hover says why. The Programmer's row C badge unlinks and relinks the
  same way.

## 10. Scope honesty

The Model board drew the client work as one session; decisions 4 and 8 — both badges, taken after
the board was drawn — add two re-measured ladders and row C, so it is two here. The fold rungs for
the badges and the short words are not known until measured in the app; the rule (a glyph at every
width; the words shorten before anything else moves) is the decision, the numbers are not.

## 11. Open questions

None. Chris answered all eight calls on 2026-09-23.
