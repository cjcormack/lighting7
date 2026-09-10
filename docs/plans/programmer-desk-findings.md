# Programmer — desk findings, 2026-09-10

Everything the programmer space plan's desk pass turned up, in one place, to be **stepped through
in a session of its own**. It is a triage list, not a plan: each item is written so it can be
picked up cold, but none has been designed, sized or sequenced yet.

Raised during [`FU-MANUAL-DESK-SPACE`](manual-validation.md#fu-manual-desk-space), run at the desk
on 2026-09-10 against [`completed/programmer-space-plan.md`](completed/programmer-space-plan.md).
Checks 1–4 passed; check 5 failed on Blind. Six items come from the checks themselves; the rest are
what the operator noticed while running them, which is the point of a desk pass.

**These are not `FU-` items yet.** [`followups.md`](followups.md) is dormant work left behind by
*completed* plans, and each item there has had its shape decided. These have not. An item graduates
to `followups.md` — or straight into a plan — when this list is stepped through; anything rejected
stays here as its own decision record.

**The browser was Safari/WebKit**, on a desk and on a real iPhone. Where a number was measured in
Chromium it says so, because the two disagreed once already in this pass.

## Index

| Slug | Area | What |
|---|---|---|
| [`PD-BLIND-ON-PROGRAMMER`](#pd-blind-on-programmer) | Chrome | Blind can't be toggled on the page where you go blind — **the one failed check** |
| [`PD-SPEED-OVERLAY`](#pd-speed-overlay) | Chrome | the speed masters want an overlay rather than a band |
| [`PD-MARQUEE-TOUCH`](#pd-marquee-touch) | Touch | scrolling the table selects cells; text selects mid-drag |
| [`PD-TRACKING-GESTURE-TOUCH`](#pd-tracking-gesture-touch) | Touch | ⌥-press has no touch equivalent, so a phone can't add a tracking layer |
| [`PD-SELECTION-BAR-DENSITY`](#pd-selection-bar-density) | Touch | the bar spends its narrow width on detail rather than chips |
| [`PD-CLEAR-SELECTION-TOUCH`](#pd-clear-selection-touch) | Touch | no easy way to clear a selection on a phone |
| [`PD-SHEET-ICONS-OPEN`](#pd-sheet-icons-open) | Rail | the collapsed sheet's Layers / FX icons should open it at that band |
| [`PD-SHEET-CLOSE-ALIGN`](#pd-sheet-close-align) | Rail | the phone sheet's close X isn't vertically centred |
| [`PD-SELECTION-BAR-SHIFT`](#pd-selection-bar-shift) | Grid | the bar's arrival moves the grid under a live drag |
| [`PD-ENTER-FOCUS`](#pd-enter-focus) | Grid | Enter opens the cell editor without focusing it, and won't close it |
| [`PD-COLOUR-EDITOR-INPUTS`](#pd-colour-editor-inputs) | Grid | the colour editor asks for hex; it should offer a picker and per-emitter fields |
| [`PD-POPUP-AFTER-DRAG`](#pd-popup-after-drag) | Grid | a completed single-column drag should open its value popup |
| [`PD-TWO-RECORD-BUTTONS`](#pd-two-record-buttons) | Grid | two Record buttons, and one has a stray right margin |
| [`PD-TEMPLATE-MULTIHEAD-CELL`](#pd-template-multihead-cell) | Grid | a multi-head fixture's top-level colour cell takes no template — **diagnosed**; blocked behind `FU-LOOK-ELEMENT-ROWS`'s wall |
| [`PD-FILTER-PLACEHOLDER-CLIP`](#pd-filter-placeholder-clip) | Text | the filter's placeholder is clipped at every width |
| [`PD-SOURCE-TRUNCATION`](#pd-source-truncation) | Text | the source line truncates mid-word instead of dropping whole parts |
| [`PD-MOBILE-SAFARI-CHROME`](#pd-mobile-safari-chrome) | Global | mobile Safari's own chrome eats the landscape budget — app-wide, tracked elsewhere |

---

## Groups, and the order to take them

**Six groups and two standalones.** The groupings are not filing convenience: in each, the members
contend for the *same* resource — one gesture, one row's width, one component's layout — so taking
them apart means the second one redoes the first. Where that is the reason, it is said.

Model and effort follow the space plan's §9 rule: **judged by where the risk is, not by the size.**
Several of these are a few lines of code sitting on top of a decision, and the decision is the part
a smaller model drifts on.

### The order

1. **`PD-TEMPLATE-MULTIHEAD-CELL`** — first, and on its own. It is the only finding that silently
   does nothing on a rig, and it is independent of every other item on this list. **Diagnosed
   2026-09-10**: the cause is *not* key resolution in `rowModel.ts`, so it touches nothing the later
   groups build on — it is a server-side resolution gap, blocked behind the same missing cook
   capability as `FU-LOOK-ELEMENT-ROWS`. What comes first is now writing that plan, not writing the
   fix.
2. **Group E · Truncation** — cheap, self-contained, one commit, and it makes the page stop looking
   broken while the larger work is still being decided.
3. **Group C · The selection bar's geometry** — before Group B, because it settles *when* the bar is
   in the flow, and Group B then re-lays out *what is in it* against a row that has stopped moving.
4. **Group B · Touch and the phone** — the biggest of the buildable groups.
5. **Group D · The cell editors.**
6. **Group F · The rail's sheet**, and the **`PD-TWO-RECORD-BUTTONS`** standalone — both small,
   both fine to fold into whichever session has room.
7. **Group A · Show chrome on the programmer** — last, and **blocked on a decision that is yours**,
   not the implementer's. It is also the only one that could reverse a shipped session.

### The groups

| Group | Items | Model | Effort | Why |
|---|---|---|---|---|
| **A · Show chrome** | `PD-BLIND-ON-PROGRAMMER`, `PD-SPEED-OVERLAY` | Opus 5 | high | **One piece of work if the answer to both is an overlay** — Blind wants a way to be pressed here, the speed masters want to be summoned rather than resident, and a summoned show-chrome layer answers both at once. Solving them separately is how the programmer ends up with two bespoke summoning gestures. High because the rule in play (*one control, one place*) forbids the obvious fix and is refused **by name** in two files, so the failure mode is a plausible-looking change that breaks a documented invariant. **Blocked**: pick one of the three candidates in `PD-BLIND-ON-PROGRAMMER` first. |
| **B · Touch and the phone** | `PD-MARQUEE-TOUCH`, `PD-TRACKING-GESTURE-TOUCH`, `PD-SELECTION-BAR-DENSITY`, `PD-CLEAR-SELECTION-TOUCH` | Opus 5 | high | **They contend for two resources: the long-press gesture and the selection bar's width.** If the marquee claims long-press, the template chip cannot have it; if Deselect grows for touch, the chips shrink. Decide all four together or the third one undoes the first. High also because jsdom sees **no** touch behaviour at all — every one of these is verified in a browser on a real device or not at all, which is exactly how the marquee shipped with no `pointerType` check in the first place. |
| **C · The bar's geometry** | `PD-SELECTION-BAR-SHIFT`, `PD-POPUP-AFTER-DRAG` | Opus 5 | high | **Sequential, not merely related**: the popup anchors at the first selected cell, so it must open *after* whatever the bar does to the layout has settled, or it lands 34px off. The shift's shape is already decided at the desk, so the difficulty is not the design — it is that "is a drag in progress" must cross `ProgrammerBody`'s memo barrier at pointer rate, which is the hazard session 3 carved `RailGeometry` out for. Opus for that reason alone. |
| **D · The cell editors** | `PD-ENTER-FOCUS`, `PD-COLOUR-EDITOR-INPUTS` | Opus 5 | high | Both are *what happens when a cell editor opens*, in the same components with the same tests, so one browser pass covers both. High because the colour half touches the model's strongest rules: emitters come off the **colour descriptor**, and this side **never resolves an intent** — the client may serialise and parse only. A confident wrong answer here looks completely reasonable in review. |
| **E · Truncation** | `PD-FILTER-PLACEHOLDER-CLIP`, `PD-SOURCE-TRUNCATION` | Sonnet 5 | medium | One rule applied twice — **drop whole parts, don't ellipse a sentence** — which session 1 already wrote down and already implemented for the legend (`LEGEND_SHORT`, and the footer dropping items rather than slicing them). So there is a worked example in the tree to copy, and the only judgement is the drop *order*. Cheapest real improvement on the list. |
| **F · The rail's sheet** | `PD-SHEET-ICONS-OPEN`, `PD-SHEET-CLOSE-ALIGN` | Sonnet 5 | medium | Same surface, same file, one pass. Medium rather than low for one reason: the close X may be the **shared `SheetContent` primitive**, and a fix in the wrong layer moves every sheet in the app. The judgement is which layer, not the change. |

### The standalones

| Item | Model | Effort | Why |
|---|---|---|---|
| `PD-TEMPLATE-MULTIHEAD-CELL` | Opus 5 | high | The one confirmed **bug**, and the diagnosis is done (see the entry). It is not key resolution: a multi-element fixture's parent declares no properties of its own and `TemplateResolver` never looks below the head it is given, so the client offers a template the resolver refuses. The remaining work is a plan, not a commit: the fix is a parent-to-element fan-out whose cost is the cook's accumulator taking an element key — the same wall `FU-LOOK-ELEMENT-ROWS` is behind. Whether the two are filed as one plan or two sharing a first step is still open. |
| `PD-TWO-RECORD-BUTTONS` | Sonnet 5 | medium | Starts as an **investigation** — establish which two buttons and whether they do the same thing — and only then is it a change. If they differ, the fix is naming; if they don't, one goes. The stray margin rides along with whichever wins. |
| `PD-MOBILE-SAFARI-CHROME` | — | — | Not this list's work. App-wide, tracked by the operator separately. |

**Two rules carried over from the space plan's §9, because both earned their place here.** Run a
one-tier-down review (`/code-review-lite` or `/verified-ship`) after Groups **A**, **B** and **C** at
least: the failure class in this area is a rule stated in one place and not another, which a fresh
reader catches better than the author, and it is what caught two shipping defects in session 2. And
**keep the groups as separate commits** even where two are cheap — Group E in particular touches
copy that reads on `/fixtures/list` and `/groups/list` as well, and that diff should be readable on
its own.

---

## Chrome

### `PD-BLIND-ON-PROGRAMMER`

**Blind can't be toggled on the page you go blind for** — check 5.1, the pass's only failure.

Session 5 dropped `ShowBar` from the programmer, taking Blind, blackout, GO/BACK, the speed masters
and the transport keys with it. Blind is still *reported* — the app header's `ProgrammerIndicator`
draws its amber badge — but it cannot be pressed there, so going blind means switching to `/show`,
pressing Blind, switching back, editing, and switching out again.

The operator's verdict: **not liveable, and it should be possible to toggle Blind in the
Programmer.** Blackout is separately confirmed as *not* important (check 5.2), and GO/BACK's absence
is confirmed deliberate and fine (check 3, check 5.4).

**The constraint that makes this a design question rather than a change.**
[`FU-MANUAL-DESK-SPACE`](manual-validation.md#fu-manual-desk-space) names the only sanctioned
remedy: *the bar returning to this view whole* — **never a second Blind toggle in the action bar**,
which is the one-control-in-two-places split `useShowBarProps` was written to end, and which is
refused by name in `lighting-react/src/routes/ProgrammerPage.tsx` and in `lighting-react/CLAUDE.md`.
Session 5's own note says the same thing from the other side: *"anyone reopening this should reopen
the scope question, not the merge."*

So there are three candidate answers, and picking one is this item:

- **The bar comes back whole**, and session 5 is reversed on this view. Costs the ~60px that took
  the landscape phone from 4 whole fixture rows to 6 — measured, not guessed.
- **An overlay**, which is the shape [`PD-SPEED-OVERLAY`](#pd-speed-overlay) independently asks for.
  An overlay is not a second permanent control, so it may thread the rule rather than break it:
  one gesture summons the show's chrome over the grid, and it is the *same* `ShowBar` content from
  the same `useShowBarProps`. Read the two items together before designing either.
- **Blind stops being show chrome.** If Blind is really a *programmer* fact rather than a show one —
  it gates what the programmer puts on stage — then it may belong to the programmer and be
  *reported* on the other three views, which is the current arrangement exactly inverted. This is
  the largest of the three and the only one that changes what Blind *is*.

Do not reach for the fourth option the rule forbids. Whatever ships, one control, one place.

### `PD-SPEED-OVERLAY`

**The speed masters want an overlay, not a band** — check 5.3.

Asked whether the programmer needs a tempo readout while editing an effect's timing, the operator's
answer was that it should be solved *a different way*: a **speed-master overlay**, summoned rather
than resident. `ProgrammerFxList` naming each effect's master is not enough on its own, and the
`ShowBar`'s resident tile row is the thing session 5 removed for good reasons.

Today the bank lives in three places: `components/SpeedMasters.tsx` (the ShowBar's performance
surface, three arms by width and count), `/projects/:id/speed-masters` (manage), and
`BuskSpeedRail`'s cards on the busk page. A programmer overlay would be a fourth surface onto the
same masters — so the question to answer first is whether it *reuses* one of those or is a new one,
and this repo's history says a second near-copy of a speed surface drifts (`SpeedMasters.tsx`'s
docblock is the record of the last time).

Note the overlap with [`PD-BLIND-ON-PROGRAMMER`](#pd-blind-on-programmer): if the answer there is
also an overlay, these are one piece of work — a summoned show-chrome layer — not two.

---

## Touch and the phone

### `PD-MARQUEE-TOUCH`

**The cell marquee has no touch story, so a scroll selects cells** — check 3.2.

On a phone, scrolling the fixture table selects cells inconsistently, and text selects during a
drag. A long press then drag mostly works, which is the tell: the gesture is reachable, it just has
no priority over the browser's own.

The cause is three things at once, all in
`lighting-react/src/components/fixtures-list/FixturesTable.tsx` (`useCellMarquee` and the rows
wrapper it hangs off):

- **No `pointerType` check anywhere in `fixtures-list/`.** `onPointerDown` guards on
  `e.button !== 0`, which is 0 for touch too, so a finger arms the marquee exactly as a mouse does.
- **`DRAG_THRESHOLD_PX` is 5.** A mouse click never travels 5px; a scroll flick crosses it
  immediately, so the marquee arms before the browser has decided the gesture is a pan.
- **The rows wrapper declares no `touch-action`, and nothing sets `user-select: none` during a
  drag**, so the pan and the marquee both proceed and the text under them selects as well.

**The decision is a product call, not a guard.** Either touch **pans, and only a long press
marquees** — `touch-action: pan-y` on the wrapper plus the shared `hooks/useLongPress.ts`, which
already carries the `pointercancel` handling a scroller needs — or touch **never** marquees and
phone cell selection is taps only. Do not simply raise the threshold: a bigger number makes a scroll
*sometimes* select, which is today's complaint with a different constant.

If the answer is long-press, settle [`PD-TRACKING-GESTURE-TOUCH`](#pd-tracking-gesture-touch) at the
same time — long press cannot mean two things on one screen.

### `PD-TRACKING-GESTURE-TOUCH`

**⌥-press has no touch equivalent, so a phone can't add a tracking layer** — check 3.2.

The two apply gestures are click → `POST /templates/{id}/apply` (literals into Local) and ⌥click →
`POST /templates/{id}/toggle` (a layer that *tracks* the template). A phone has no Option key and
no gesture stands in for it, so the tracking half of the design is unreachable from the surface
session 4 built for it.

This is a **missing capability** rather than a rough edge, and it is compounded by the fact that the
difference between the two gestures is invisible on screen: it is stated only on the chip's `title`,
which a touch device never shows either.

Candidates: a long press on the chip (but see [`PD-MARQUEE-TOUCH`](#pd-marquee-touch) — if long
press arms the marquee, it is taken), or an explicit apply/track segmented control in the bar, which
is then also a [`PD-SELECTION-BAR-DENSITY`](#pd-selection-bar-density) question. These three are one
design problem seen from three sides.

### `PD-SELECTION-BAR-DENSITY`

**The selection bar spends its narrow width on detail rather than chips** — check 3.2, and again
unprompted for iPhone portrait.

At phone widths the bar's leading details — the marquee glyph, `4 fixtures · 8 cells`, the family
badge — take room the **template chips** need, and the chips are the only thing on that row an
operator presses. The operator's words: most of the bar is given to detail that is *"not that
useful, or could be more efficient in terms of space"*.

`SelectionBar` in `lighting-react/src/components/programmer/ProgrammerGrid.tsx` already drops the
keyboard hints and shortens Locate / Highlight by container query; the counts and the family badge
are what is left, and neither has a narrow arm. Note session 2's own finding that `@[1100px]` on
this bar **can never fire** — the container is the grid column — so these thresholds have never been
exercised at the wide end either.

Cheapest first: give the counts a narrow arm (`4 · 8`, or fold them into the marquee glyph's
`title`); drop the family badge below some width, since the chips are already filtered *by* that
family and the badge restates it; give the freed width to the chip scroller. Then weigh `New` and
Locate / Fan / Deselect against the chips rather than shrinking everything evenly.

Interacts with [`PD-SELECTION-BAR-SHIFT`](#pd-selection-bar-shift), which changes when this row is
in the flow at exactly these widths.

### `PD-CLEAR-SELECTION-TOUCH`

**No easy way to clear a selection on mobile.**

On a desktop, clicking off the selection or pressing the Deselect control does it. On a phone
neither is convenient — and with [`PD-SELECTION-BAR-DENSITY`](#pd-selection-bar-density) in play,
Deselect is one of the trailing controls competing for the width the chips want, so "make Deselect
bigger" and "give the chips more room" pull against each other. Settle them together.

Worth checking what a tap on empty grid space does today before designing anything.

---

## The rail and its sheet

### `PD-SHEET-ICONS-OPEN`

**The collapsed sheet's Layers and FX icons should open it at that band.**

In the collapsed layers-and-effects handle, pressing the Layers or FX icon does nothing; only the
chevron opens the sheet. Pressing the icon for a band should open the sheet — and, if it is cheap,
scrolled to that band, since the two are the sheet's two halves.

The 40px desktop strip has the same shape (two counts as badges under their glyphs, plus a `+`), so
check whether it has the same gap; session 3's rule was that *nothing is reachable only with the
rail open*, which is about the `+` menu but reads on the counts too.

### `PD-SHEET-CLOSE-ALIGN`

**The phone sheet's close X is not vertically centred in its header.** Cosmetic, small, and
`SheetContent`'s close button is a shared primitive — so check whether this is the sheet's header
layout or the primitive, because a fix in the wrong one moves every sheet in the app.

---

## The grid

### `PD-SELECTION-BAR-SHIFT`

**The selection bar's arrival moves the grid under a live drag** — found while busking for check 1.

`SelectionBar` returns `null` with no selection and sits above the table in the same column flow, so
the moment the first cell enters a marquee the bar mounts and pushes every row down ~34px, under a
pointer that is mid-drag. It is **not** a flicker — the bar does not oscillate, and the drag lands
on the cells it crossed — but it is worst when the drag **starts on a row that is not the top row**,
where the whole grid slides while the operator is still choosing.

**The operator's judgement on the saving decides the fix.** The 34px is only saved while nothing is
selected, which is exactly when the grid is not being used: to interact with the programmer you must
first select, so on a desktop the band is never absent at a moment its absence helps. On a
**landscape phone** it is different — 34px of 393 is worth keeping while reading the grid, and only
the drag is spoiled.

**So: a hybrid, decided at the desk.**

- **Short viewports** (the existing `[@media(max-height:500px)]` arm that `ScopedKeyPopover` and
  `ScopedLegend` already switch on): keep the bar out of the flow but **hold its mount until the
  drag ends** — it appears on pointer-up, not on the first cell.
- **Taller viewports**: **always reserve the height** — the band is in the flow unconditionally,
  empty until there is a selection.

`SelectionBar` already returns `null` *itself* rather than being conditionally mounted, so its hooks
run in a stable order; the change is what it returns, and that half is cheap. The short-viewport arm
is the work: "is a marquee drag in progress" lives in `useCellMarquee` inside `FixturesTable`
(`marquee.band`), a *sibling below* `renderToolbar`'s output and deeper in the tree, so it must be
lifted to `FixturesListContainer` and handed through the `renderToolbar` parts object beside
`cells`. Watch the memo barrier: a per-pointermove boolean crossing `ProgrammerBody` is the hazard
session 3 carved `RailGeometry` out for.

**This is not a reason to restore the always-on template strip.** Session 2 removed that because it
showed the whole library for a press that could only toast, cost ~90px permanently and wrapped to
four rows on a real rig. This item is about the band's *height being stable*, not its contents being
present.

### `PD-ENTER-FOCUS`

**Enter opens the cell editor without focusing it, and won't close it.**

Selecting dimmer cells and pressing Enter opens the value field, but the field does not take focus,
and a second Enter does not close it. The marquee keyboard is documented to do both — Enter applies
and closes, per `cellEntry.ts` and `CellEntryPopover` — so this is a defect against a stated
promise, not a gap.

Check `cellKeyboardPermission` and the scope in play when reproducing: entry is refused in Output
scope and on a focused template layer by design, and a refusal that *opens the popover anyway* would
look exactly like this.

### `PD-COLOUR-EDITOR-INPUTS`

**The colour editor asks for hex; it should offer a picker and per-emitter fields.**

People do not think in hex. The colour cell's editor should lead with the **colour picker**, and
carry text inputs for **R, G, B and the bundled emitters — W, A and UV** — beside it, so a value can
be typed per channel rather than converted by hand.

The emitter half is not cosmetic: white, amber and UV are rows of the template vocabulary in their
own right, and `dmx:` values on them are the one deliberate literal in a grammar that is otherwise
intents — so a per-emitter field is the direct expression of what the model already holds. Check
against the colour descriptor (`whiteChannel` / `amberChannel` / `uvChannel`) so a head without an
emitter shows no field for it.

**One thing to confirm at triage:** the operator's note says *"selecting dimmer cells and pressing
enter"*, but the hex complaint can only be about **colour** cells. Establish whether this is one
finding about colour cells or two findings that share an opening clause — [`PD-ENTER-FOCUS`](#pd-enter-focus)
is written as the dimmer half on the assumption that it is two.

### `PD-POPUP-AFTER-DRAG`

**A completed drag should open its value popup**, when the drag stayed within one column.

After a marquee drag the operator has said what they want to edit; making them then click a cell to
open an editor is a second gesture for a decision already made. The condition is the drag being
single-column — a mixed-column marquee has no one editor to open, which is exactly why
`commitMatchesResolution` exists.

Note the interaction with [`PD-SELECTION-BAR-SHIFT`](#pd-selection-bar-shift): the popover is
anchored at the first selected cell, so it must open *after* whatever the bar does to the layout has
settled, or it will be anchored 34px off.

### `PD-TWO-RECORD-BUTTONS`

**There are two Record buttons, which reads as confusing** — and the left one carries a right margin
to its containing box that looks unintentional.

Establish first *which two* — the action bar's Record with its destination menu, and whatever else
is presenting as Record — and then whether they do the same thing. If they do, one goes; if they do
not, the naming does. The margin is cosmetic and rides along with whichever answer wins.

### `PD-TEMPLATE-MULTIHEAD-CELL`

**A multi-head fixture's top-level colour cell takes no template**: applying one reports
`0 heads set · 1 could not take it`.

Selecting the *top-level* colour cell of a multi-head fixture — the fixture row rather than an
element row — and pressing a template lands on nothing.

**Reproduced 2026-09-10**, read-only against the running desk (project 6 *Experiment*, fixture
`led-lightbar-12-pixel-2`, type `led-lightbar-12-pixel-48ch`). `POST /projects/6/templates/resolve`
with the *Red* template's one row answers `{"entries":[]}` for that bar and `EXACT` for every other
head in the project, `led-lightbar-12-pixel` — the same model in its **12ch** mode — included.

**The client's key is right, and the hypothesis this entry was written on is wrong.**
`templateTargetsFor` sends `{type: "fixture", key: "led-lightbar-12-pixel-2"}`: the parent's own key,
a patched fixture, resolved without complaint. Nothing about element rows or key resolution is
involved on the way in.

**The cause is a shell parent.** In its 48-channel mode that bar declares **no `@FixtureProperty` at
all** — all twelve `RgbwPixel` elements carry the colour, and the parent carries nothing. The REST
row says so outright: `properties: []`, `elements: 12`, `elementGroupProperties: ['rgbColour']`.
`TemplateResolver` resolves against the head it is handed **and nothing below it**, so
`unmetColourRequirement` answers `NotACandidate("no colour")` and the whole template is refused for
that head. That is the skip, verbatim.

**Three other places fold elements in, which is why it looks offerable.** `capabilities` gains
`colour` from `elementGroupProperties` in `FixtureTypeRegistry`; the client's `targetFamilies` and
`targetEmitters` scan `target.elements`; and the **cell** comes from `buildRowCells`
(`fixtures-list/useRowValues.ts`) through `resolveTargetCells`, whose own docblock is the rule —
*"a target's own properties claim the column outright when they resolve it … only when they resolve
nothing do elements contribute one resolution each"*. So the patch advertises colour, the strip
offers the template and the grid draws the cell — and the resolver alone disagrees. It is the
failure class this list's own §Groups names: **a rule stated in one place and not another.**

Not `findGroupColourSource` / `elementGroupProperties`, which is the *stage* surfaces' colour
dispatch and reaches no cell in `fixtures-list/`. Read `resolveTargetCells`' docblock before
designing the fix for a second reason: it names the canonical multi-head shape as **a master
dimmer/strobe on the parent with colour/position on the heads**, so the general case is a parent
with *some* properties and not the shell below, and a fan-out that ignores which family is being
asked for would take the wrong heads on that shape.

**It is not a colour problem and not an apply problem.** Any family behaves the same way, and the
resolves-to panel is *silent* where apply is loud — `NotACandidate` is omitted there by design — so a
template editor pointed at a rig containing this bar says nothing about it whatsoever.

**A client-side fix is not available.** Element keys would be the obvious answer, and the three
consumers of `TemplateResolver` reach a head three different ways:

| consumer | lookup | takes an element key? |
|---|---|---|
| apply — `routes/templateApply.kt` | `untypedGroupableFixture` | **yes** |
| resolves-to panel — `routes/projectTemplates.kt` | `fixtures.fixtures.filter { it.key in keys }` | no — top-level register only |
| cook, i.e. ⌥click's layer — `fx/CueComposer.expandTargets` | `untypedFixture` | no — top-level register only |

So sending element keys would make **click** work and leave **⌥click** silently asserting nothing,
which is worse than today's honest skip. The cook's half is a type wall rather than an oversight:
`Expanded` and `Pending` hold a `Fixture`, and a `FixtureElement` does not extend one.

Correct `templateTargetsFor`'s docstring whatever ships — *"an element key is not a fixture key: the
template route resolves targets against the patch and would drop it silently"* is true of two of
those three and false of apply.

**So the fix is server-side, and it is a plan rather than a commit.** One expansion — a
multi-element parent fans out to its element heads inside the template path — placed once and used
by all three consumers. Its cost is where it stops being small: `Expanded.fixture` and
`Pending.fixture` widen from `Fixture` to `GroupableFixture`, and the cook then has to carry
**element-keyed** contributions.

**That is the same wall [`FU-LOOK-ELEMENT-ROWS`](followups.md#fu-look-element-rows) is behind**, and the cook says so in
one line: `if (row.elementKey != null) continue`, commented *"an element is not a (fixture,
property) key"*. Both items need the cook's accumulator to take an element, and neither can be
finished without building it. They are **not** the same item, though, and the entry's earlier guess
that they might be should not be read as settled: that follow-up is about rows **already authored**
with an `elementKey` being dropped, where this one is about a **parent target that must fan out**
before any such row exists. Whether they are filed as one plan or two that share a first step is a
call for whoever writes it — flagged here rather than answered, which is what this list is for.
(Note while you are in there: the cook's comment claims element rows are "handled by the caller-side
element path", and `FU-LOOK-ELEMENT-ROWS` says `buildCueAssignmentsForCue` has no such path. One of
the two is stale. Settle that first — it decides how much is left to build.)

Re-sized on that basis: still Opus 5 / high, still first in the order, but as a plan to write rather
than a defect to fix. The bug hunt is done.

**Reproduction, for the next session** — needs only a running desk, writes nothing to the rig:

```bash
curl -s -c c.txt -X POST localhost:8413/api/rest/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"…"}'
curl -s -b c.txt -X POST localhost:8413/api/rest/projects/6/templates/resolve \
  -H 'Content-Type: application/json' \
  -d '{"rows":[{"targetType":"deferred","targetKey":"","propertyName":"rgbColour","value":"#FF0000;policy=extract"}],"targets":[{"type":"fixture","key":"led-lightbar-12-pixel-2"}]}'
```

---

## Text and truncation

### `PD-FILTER-PLACEHOLDER-CLIP`

**`Filter fixtures by name, manufacturer, or type…` is clipped at every width**, and badly at narrow
ones.

The field's own `min-w-48` is already unpicked in row B (`[&>div]:min-w-0`) precisely because that
row cannot wrap, and below `@[600px]` the field becomes a search icon in a popover — so the
placeholder is being asked to fit a box that is deliberately allowed to shrink. The answer is a
shorter placeholder, or a placeholder with arms (`Filter fixtures…` / `Filter…`), not a wider field:
the field gives on purpose, and that decision is documented in `ProgrammerGrid`.

### `PD-SOURCE-TRUNCATION`

**`No source — 6 values, nothing to update` truncates mid-string and looks broken.**

The source box should **drop whole parts** as it narrows rather than ellipsing a sentence — the
pattern row A already uses for its verbs and the legend already uses for its glosses (`LEGEND_SHORT`,
and the footer *dropping* items at `@[420px]` / `@[520px]` rather than slicing one mid-word). Session
1 wrote that rule down for exactly this failure; the source line's own detail text did not get it.

Decide the drop order — the count, then the trailing clause, then the dash — so the shortest form is
still a true sentence.

---

## Global

### `PD-MOBILE-SAFARI-CHROME`

**Mobile Safari's own bar and tabs eat the landscape budget.** Raised as a caveat on check 4 (which
passed on its own terms: six whole fixture rows) and again for landscape generally.

**Tracked by the operator separately as an app-wide issue** — it is not a programmer problem and
should not be solved here. Recorded so this pass's numbers are read correctly: the plan's row counts
are of the *viewport*, and mobile Safari does not give the viewport it advertises.
