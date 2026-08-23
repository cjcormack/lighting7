# Desk simplification — the Programmer as a place, Templates as a thing

> **Document status: ALL SESSIONS BUILT (2026-08-23).** Four sessions — Session 2 split, as §8
> anticipated. **1, 2a, 2b and 3 have all shipped.** No session has had a desk pass; §7 is the
> outstanding list.
>
> **Amended 2026-08-23 (fourth amendment) — 3 is built, and it took the entity split §Session 3
> declined.** `desk-simplification-design/TwoThings` argues "one backend table, two front doors —
> `hasDeferredRows` plus 'exactly one family' already separates them", and that does not survive
> contact with the design's own best example: a **per-fixture** template (eight heads aimed at one
> spot) has only *bound* rows, which is exactly a recorded Look's shape, so `/looks` and `/templates`
> would have listed the same record. Templates are their own tables (`templates`, `template_rows`),
> a layer's referent is polymorphic, and the split *removed* work rather than adding it:
> `editorFixtureType`, `LookEditor`'s synthetic-fixture grid, `LookDraftContext`, `LookLivePreview`,
> `syntheticFixture.ts`, `EditorContextValue`'s `look` arm and the type gate in `compatibleIdsFor`
> all went with it. All four families shipped in one session rather than the 3a/3b split §Sizing
> offered.
>
> **Nine departures from what §Session 3 says below.**
>
> 1. **Two entities, not one table with a flag** — above.
> 2. **A layer's `lookId`/`lookUuid`/`lookName` became one `LayerSource`**, rather than a `sourceKind`
>    added beside them. The smaller diff leaves a field called `lookName` holding a template's name,
>    which no compiler can find; collapsing the three made every reader visit exactly once.
> 3. **`hasDeferredRows` became `hasDeferredEffects`.** A Look *row* can no longer be deferred at all
>    (`validateLookRows` refuses it), but a deferred **effect** is still meaningful — it is what makes
>    a Look eligible for a busking pad — so the flag narrowed rather than disappeared.
> 4. **Strobe is a percentage, not Hz.** `BeamColour` calls Hz "the only unit two fixtures agree on",
>    and nothing in the fixture definitions declares a Hz range, so a `hz:` intent would have had
>    nothing to resolve against. Recorded as `FU-TMPL-STROBE-HZ`.
> 5. **An intensity template on a dimmerless head reports `Unsupported`.** The "existing
>    virtual-dimmer path" `BeamColour` promises does not exist server-side. `FU-TMPL-VIRTUAL-DIMMER`.
> 6. **No CMY.** There is no such head in this rig and no `WithCyan` trait; the third colour type here
>    is the *wheel*, which is the harder and more useful case, and it snaps by ΔE76 with the number
>    shown.
> 7. **A latent bug was found and fixed on the way**: `fixtureCategoryFor` canonicalised before
>    looking up, so `colour` became `rgbColour` and the MAC 250's colour *wheel* — a property literally
>    named `colour` — resolved to nothing and was dropped with a warn on every cook. That hit any
>    recorded Look holding a wheel colour, and it is what every colour template would have hit. Exact
>    name first, canonical second.
> 8. **The pre-v5 preset migration narrowed.** A preset's target-less *value* rows cannot become Look
>    rows any more, so `migratePresetsAndPalettesToLooks` brings its **effects** across and logs the
>    values. `LooksMigrationTest` asserts the loss explicitly rather than dropping the case.
> 9. **The busking pads take both kinds** and lost their create affordance: neither entity is authored
>    from a pad grid. `/fx-busking` is off-nav and URL-only, which is why this was cheap.
>
> Also fixed in passing: Cmd+K's "New FX Preset" pointed at `/presets?action=new`, a route retired two
> sessions ago. It is "New Template" now.
>
> **The design is committed alongside this plan** at
> [`desk-simplification-design/`](desk-simplification-design/INDEX.md) — sixteen artboards drawn
> against `lighting-react`'s real tokens, control heights and ownership colours. Cited below as
> *(design: `Stack`)*, meaning `desk-simplification-design/Stack.dc.html`; open it in a browser and
> it renders standalone. Read the INDEX first: it maps artboards to sessions and says what to ignore.
> Page 2 of the canvas is the structural answer, page 3 supersedes its layer and template thinking.
>
> A live pannable version exists at
> <https://claude.ai/code/artifact/d4f18b3f-87db-45c8-a0d1-22cdf9d51595>, but it is private — **the
> committed files are the authority**, and a session should never be blocked on reaching that URL.
>
> **Nothing here has left the dev environment.** No migrations, no rollback shims, no
> compatibility windows — data shapes change freely and hard, the same call
> [looks-and-layers-plan.md](completed/looks-and-layers-plan.md) made for the same reason. If a
> session finds itself writing a migration, it has misread this line.
>
> This plan is deliberately written as **outcomes**, not implementations. It says what the operator
> should see and which rules must hold; the session picks the files. Where it does name a symbol, it
> is to stop a wrong turn, not to prescribe a design.
>
> Supersedes the "programmer surfaces" half of
> [programmer-redesign-proposal.md](completed/programmer-redesign-proposal.md) and the §Navigation
> Registry note in `lighting-react`'s `CLAUDE.md` about there being no `programmer` nav entry.
>
> **Amended 2026-08-23 (third amendment) — 2b is built.** The Run/Show merge landed: `/run` is a
> redirect, there are three live views, and editability is the Prompt Book's lock rather than a
> route. Frontend only, as predicted — no backend change, no restart.
>
> **Seven departures from what is written below.** Six were findings that changed the plan; the
> seventh is a decision it explicitly deferred to this session.
>
> 1. **Run's tab click was destructive, and the hazard list here misses it.** `handleSwitchToStack`
>    ran `deactivate(old) → goToStack(target) → deactivate(target)`, so one unconfirmed press took
>    the live cue off stage and repositioned every other client. §"Rules that must hold" names
>    dragging and `cueEdit` as the show-critical hazards and then asks for this strip in the *locked*
>    view; this was worse than either. Browsing and arming were therefore split outright — the URL is
>    the browse cursor, and arming is `OffPlayheadBanner`'s confirm-gated control.
> 2. **The planned `goToStack` cache patch was wrong and was not made.** The plan proposed nulling
>    the target's `activeCueId` in `onQueryStarted`; the endpoint calls `activateAtFirstCue`, so that
>    would contradict what it does. The make-live action states the runner it intends instead, which
>    is what let `manualSwitchRef` go. Also found: the backend already deactivates the previous stack,
>    so the client-side call was a redundant round-trip — dropped.
> 3. **`PaneShell` and `TabButton` were not duplicated.** Only one copy survived, in `RunCueCard`;
>    2a deleted Show's. So that part was a decision, not a de-dupe — and Run's Output pane already
>    rendered `CueDetailContent`, so the two bodies were the same surface behind a tab shell.
>    `MiniStage` was folded into `CueDetailContent` as a Stage section so it survived the deletion.
> 4. **"Two active-cue cursors" was one added field, not a mode.** `useShowTransport` already
>    computed the optimistic-preferring value that Run hand-rolled; the divergence was at the
>    consumer. Both cursors are now returned and the row makes two comparisons. No mode switch, and
>    the marker/fade split needed no screenshot pass in the common case, because the server cursor is
>    itself patched optimistically.
> 5. **A cue row stays read-only.** The merge table says an unlocked row gains "its editable cells";
>    D2 says a cue is edited by Include. D2 won — giving a row cells would make a cue and the
>    programmer two places again. So `EditorContext`'s `cue` arm, its four session helpers,
>    `api/cueEditWsApi.ts` and fifteen branches across five hooks are gone. The
>    `409 CUE_EDIT_SESSION_OPEN` handling stays: the protocol is live and another client can hold a
>    session.
> 6. **Two latent defects surfaced and were fixed**, both found by the characterisation tests written
>    before anything moved. Run keyed `resetStack` on the stack id alone, so its own "Fix Order" never
>    recomputed the done/next cursors; and a cue reorder landing mid-fade nulled the optimistic cursor
>    and stopped the fade dead. A third was found in the keyboard handlers: Run guarded only dialogs,
>    so Space with the GO button focused fired GO *twice*.
> 7. **The expansion model beat both predecessors rather than picking one.** `?cue=` holds the
>    operator's card and the live one is derived on top, so a GO opens the new live card and cannot
>    take away the card being read — which Show's bare scalar could not express and Run's accumulating
>    `Set` got wrong in the other direction.
>
> **Four corrections from the first desk pass**, all of them consistency defects rather than the
> functional ones this plan worried about — and three of the four were places where §Session 2's own
> instructions produced the inconsistency:
>
> - **A locked cue row could not be expanded at all.** The row body arms the cue while locked, per
>   the plan's table, and the chevron was documented as always expanding — but it was a bare icon, not
>   a target. It is a button now.
> - **A locked expanded card offered Cue properties…, Edit in Programmer and Record.** All three were
>   kept on the reasoning that reading a cue and loading it are not edits to the stack. Wrong: Edit in
>   Programmer *Includes* the cue, and Include goes live (the desk does not auto-blind, by D2). An
>   expanded card in a running show is now a read surface and nothing else.
> - **Blind ended up in two different places.** The plan says put it in the ShowBar "only while
>   unlocked" and *not* on the Programmer, whose action bar already had it — which means the same
>   control moves location depending on which view you are on, and appears and disappears with the
>   lock. It now lives in the ShowBar alone, on every view in every state; the action-bar copy is
>   gone, and `useShowBarProps` reads the action bar's persisted fade key so blinding still fades
>   rather than snapping. This required ungating the ShowBar from `isShowActive` — which it should
>   never have been, since blackout, Blind and the speed masters all mean something with the show
>   down.
> - **The ShowBar had drifted into three near-copies.** Each host wired it by hand: the Prompt Book's
>   had no Blind and derived the stack name differently, Show suppressed the stack name beside the tab
>   strip. All three now spread `showBarProps`, overriding only `showShortcuts` — and adopting the hook
>   on the Prompt Book collapsed that page from **two** transport instances to one. The lock control
>   moved to `ShowHeader`'s `actions` slot on the Prompt Book too, so it is in the same position on
>   both surfaces that have a lock, and its transport shortcuts are now gated on `locked` like Show's.
>   (That last point supersedes the earlier decision to leave the Prompt Book's keyboard alone.)
>
> Two follow-ups to record: **DBO is inert** in every host (local state, no side effect), so a
> functional Blind tile now sits beside a cosmetic blackout; and **2a's shared-Look edit guard
> appears never to have shipped** — the rule "*Duplicate for this cue* is the primary action" has no
> implementation. Neither is 2b's to fix.
>
> **2b has not had a desk pass either.** The §7 S2b list stands, with one addition: confirm that
> *Make this stack live* asks before taking the current cue off stage, and that browsing a sibling
> stack leaves GO firing the live one.
>
> **Amended 2026-08-23 (second amendment).** **Session 2 split at the §8 seam.** 2a — the
> programmer stack — shipped as `lighting-react` `53be4fc` and `lighting7` `3110c21`. *(2b has since
> shipped too — see the third amendment above; the paragraph below is left as it stood.)* Everything
> in §Session 2 below about scopes, layers-in-place, Record-with-effects and the cue read surface is
> **built**; everything under §"Run and Show become one view" is **not**.
>
> Read the sections below as the record of *intent*. Six departures are what was actually built:
>
> 1. **The cue read surface is not `FixturesListContainer`.** `CueValueGrid` borrows the four cell
>    components instead. Mounting the real container in a cue card would bring a filter, checkboxes
>    and a drag-select marquee that mean nothing about a cue; its selection is Redux-scoped and there
>    are only three scopes, so a cue card would either clobber the programmer's or need a fourth
>    nothing acts on; and a stack of expanded cards would mount several copies of a
>    several-hundred-row virtualized table. A cue needs the value *language*, not the spreadsheet.
> 2. **`CuePropsPane` survived, relocated** into `CuePropertiesSheet` — it is the properties drawer.
>    It was never what was wrong with the three-pane editor; deleting a working per-field autosaving
>    form to retype the same dozen fields into a sheet would have been churn.
> 3. **A third backend route was needed**, unlisted here: `POST /looks/{id}/absorb-effects`, so
>    `+ Effect` with a layer focused can *move* a running band effect into that Look. Folding it into
>    `record-look` would rewrite the Look's values as a side effect of adding a chase to it.
> 4. **The client-side `cueEdit` session was left in place.** Nothing provides `EditorContextValue`'s
>    `cue` arm any more, but the backend protocol is live — so the `409 CUE_EDIT_SESSION_OPEN`
>    handling is *not* dead — and **2b is what decides whether an unlocked cue row gets editable
>    cells back**. Stripping fifteen branches across five hooks in the change that made them
>    unreachable, possibly to restore them one session later, was not worth the risk. Reasoning is in
>    `EditorContext.tsx`; if 2b settles on read-only for good, that arm and `api/cueEditWsApi.ts` are
>    the deletion.
> 5. **An un-busked Local cell shows an em-dash but its editor opens at the live value.** Asked and
>    settled with the operator. §3 says Local shows "what you set and nothing else", which is right
>    about the *display* and would have made busking start from zero; the split keeps both.
> 6. **`show-mode-engineering.md` was only half-rewritten**, behind a banner naming which half. Its
>    Run and transport sections are what 2b rewrites wholesale, and correcting them now would mean
>    writing them twice.
>
> Two follow-ups came out of 2a: [`FU-FE-CUEGRID-PER-CELL-LAYER`](followups.md#fu-fe-cuegrid-per-cell-layer)
> and [`FU-PROG-FOCUS-PREVIEW-LAYER`](followups.md#fu-prog-focus-preview-layer).
>
> **2a has not had a desk pass.** It was verified in a browser. The three checks that need a rig are
> in §7: phase survival on a layer drag while a chase runs, whether the layer-scope save cadence is
> tolerable, and a second tab seeing the stack change. Two defects in 2a were browser-only failures
> that a fully green suite reported as working — `pointer-events` inherits, and `fireEvent.click`
> does no hit-testing — so treat a green suite here as weaker evidence than usual.
>
> **Amended 2026-08-23 (first amendment).** Session 1 is implemented but **not yet committed and not
> yet desk-passed** — treat its section below as a record of intent, and the four departures listed
> here as the record of what was actually built. Session 2 absorbed the **Run/Show merge** — one
> view governed by the Prompt Book's lock — which §5 had deferred as a separate conversation. The
> reasoning is in Session 2 §"Run and Show become one view"; §5's entry is struck through rather
> than deleted, because the argument it makes is still the right one to answer. Session 1 took four
> decisions that differ from what is drawn below: `/program` became `/show` (a path rename, not
> just a label), the masters tile collapses on **count as well as width**, the source strip's change
> count is a per-tab baseline that degrades to *no badge* rather than a false "in sync", and the
> conflict state and Detach were cut as unreachable without a backend.

---

## 1. Context

Four surfaces — Program, Run, Programmer and Looks — grew into each other. The symptoms, from the
seat rather than from the code:

- **The programmer has no room.** It is a collapsed pane inside Program with Values / Layers / FX as
  tabs, so the three readings of one live object can never be seen together. Editing values while
  watching the layer stack that produced them is impossible by construction.
- **You cannot tell what you are editing.** The included cue is named only inside the Update
  button's tooltip. An operator four minutes into a busk has no on-screen answer to "will Record
  overwrite Q4?".
- **Seven identical buttons.** `Clear`, fade, `Blind`, `Record`, `Record look`, `Include`, `Update`
  sit in one row as peers, so nothing distinguishes what stages from what writes.
- **Composing a look means leaving.** Design the Look in one place, compose it into a cue in
  another, then bounce between the two to tune it. The programmer is where the rig actually is, and
  it is the one place you cannot do this.
- **A cue looks nothing like the programmer that made it.** The three-pane inline editor — targets,
  properties, layers — is a second, differently-shaped way to express the same state.
- **Selection is one cell at a time.** No drag across cells.
- **The show bar deletes information as it narrows.** Two thresholds fire at 560px in opposite
  directions, so between 560 and 900px roughly 470px of `shrink-0` tiles crush the live-state block
  and cue numbers clip while nominally visible.
- **A template is bound to a fixture type.** "Amber Key" authored against a MAC Aura is offered to
  Auras and refused to the LED bars beside them. One colour, one template per fixture type.

---

## 2. Decisions taken

Settled. A session should implement these, not re-open them.

**D1 — The programmer becomes its own view; Program becomes Show.** Values, layers and effects all
visible at once, no tabs. *(design: `DirectionC`, chosen over two alternatives kept on page 1.)*

**D2 — Opening a cue Includes it.** There is one place values are edited, so a cue and the
programmer cannot look different: they are the same screen. The three-pane cue editor goes.

**Include goes live, and Blind is the operator's deliberate pre-step** *(settled 2026-08-23)*. The
desk does not infer blind, auto-blind on open, or make reading a cue a special case — that is the
console convention, and it matches the busking-first stance the composition model already takes
elsewhere: predictability beats a desk quietly deciding what reaches the rig. The cost is a
placement requirement, not a behaviour one — see the rule in Session 2.

**D3 — Local values are the top of the stack.** Not a separate concept beside it. The engine already
works this way — `ProgrammerLookStack`'s precedence note says "the values you set yourself win over
all of them" — so this is a presentation change that makes an existing truth visible.

**D4 — The grid has a scope.** Output (read-only cook), Local, or one layer. Same grid, same cell
editors, same drag-select in all three; only the band above it and the dimmed columns change. That
sameness is the point — it is what makes editing a Look feel local.

**D5 — Look and Template are two things.** A **Look** composes cues: any families, its own
fixtures, applied as a layer with order, mask, amount, timing and stomp; always recorded, never
hand-authored. A **Template** composes values: exactly one family, no targets of its own, applied to
a selection. Two libraries, two verbs. *(design: `TwoThings`.)*

**D6 — Templates are not fixture-type-specific.** `editorFixtureType` stops being a required field
and stops gating compatibility. A template stores an **intent**, resolved per head at cook.
Compatibility becomes capability-only: does this head have colour at all.

**D7 — Effects live in a Look or on a cue, never on a layer.** `CueLayer` carries no effect list and
gains none. What a layer contributes is per-use override — tempo, amount, mask, stomp, enabled — all
of which it already has. *(design: `AddEffect`.)*

**D8 — Templates never nest inside a Look.** A Look built from templates flattens their values at
Record time. Templates are entries in a *stack*, and a Look is not a stack. This is what keeps
[`FU-LOOK-NESTED`](followups.md#fu-look-nested) closed; do not generalise past it.

**D9 — Manual cue and Look creation go away.** No "Add Cue", no "New Look". Cues and Looks are
recorded from the programmer or promoted from a selection. Separators, stacks and templates keep
their create buttons — none of them is a captured state.

**D10 — Dark-first.** These surfaces are read at a desk in a blacked-out room. Existing tokens
cover it; no new palette.

**D12 — One programmer, shared by every client.** *(Taken 2026-08-23, with D2 and D11 already in
hand.)* A second device is a **second window onto one desk, not a second seat**: whoever signs in
elsewhere sees exactly what the desk sees and can edit it. Concurrent authorship is not a scenario
this desk designs for, so there is no merge rule to invent and no per-user store — the rig shows one
value per property because DMX has one byte per channel, and "whose?" is a question worth never
having to answer. [`FU-PROG-PER-USER`](followups.md#fu-prog-per-user) is Rejected, with the two
places its cost estimate was wrong recorded there.

**D11 — Run and Show are one view, separated by a lock rather than a route.** *(Taken 2026-08-23,
after Session 1.)* The distinction between them was never a destination, it was whether a stray
click can change the show — which is a mode, and one the Prompt Book already models well. Three
views remain: Programmer · Show · Prompt Book. The lock is shared with the Prompt Book and stays
**client-local**: it is a stray-click guard, not access control, and the backend has no notion of
it. *(Reasoning in Session 2.)*

---

## 3. The model

### The stack, in two bands

One list, two bands, with the boundary drawn and labelled *(design: `Stack`)*:

```
VALUES    Local values        ← yours; beats every layer
          3  Amber Key        [Colour]     ← focused
          2  Storm Wash       [Colour]  (1 effect)
          1  Half Up          [Intensity]  ← a Template as a tracking layer
─────────  every value above beats every effect below  ─────────
EFFECTS   Colour Chase        in Storm Wash · layer 2      (stomped)
          Slow Sine           on this cue · ad-hoc
```

The boundary is the thing operators are most surprised by and it must be stated, not implied:
effects are Layer 3 and values Layer 4, so **dragging cannot make a value lose to an effect**. Per-layer
`stomp` is the only override, and it suppresses rather than removes.

Each effect row names where it lives — "in Storm Wash · layer 2" or "on this cue · ad-hoc" — because
that is the answer to "why can't I delete this?" and to "what will editing it break?".

### Where a new thing lands

The focused scope is the destination, for values and effects alike. One rule:

| Focused | A value edit goes to | `+ Effect` goes to |
|---|---|---|
| A layer | that Look's rows | that Look, as a `LookEffect` |
| Local | the programmer / the cue on Record | the cue, as a `CueAdHocEffect` |
| Output | nowhere — read-only | nowhere — the button is disabled and says why |

### Look vs Template

| | Template | Look |
|---|---|---|
| Composes | values | cues |
| Families | exactly one | any |
| Targets | none of its own | its own |
| Created by | **New template**, or new-from-selection | **Record**, or **Make layer** |
| Applied by | click → values · ⌥click → a tracking layer | added to a stack as a layer |
| Edited by | its own small editor | focusing its layer in the programmer |

A template may be **generic** (one value, any head) or **per fixture** (a focus position: eight heads
aimed at one spot hold eight different pan/tilts). The row says which. This is the existing
deferred/bound row split kept as an internal detail — it is not two library sections.

### What a colour template promises

Stored as intent plus a policy; resolved per head at cook *(design: `BeamColour`)*:

- **Intensity** — a level. A head with no dimmer takes it as a virtual dimmer.
- **Position** — degrees, not DMX. Clamped per head, and the clamp is reported.
- **Colour** — a colour plus a white/amber policy (**extract** / **additive** / **RGB only**).
  Additive, subtractive and wheel-only heads all resolve; a wheel snaps to nearest slot and the
  editor shows the ΔE so a bad match is visible *before* saving.
- **Beam** — only the **continuous** roles cross types: zoom, focus, iris, frost as a percentage of
  each head's own range, strobe in Hz, prism on/off. Gobo and colour-wheel **slots** do not — "gobo 3"
  is a different pattern on every model. Those stay in recorded Looks, which name a head and can
  therefore hold anything it has. The beam editor shows the excluded rows disabled with the reason
  rather than omitting them.

---

## 4. Implementation — three sessions

Each session ends with something an operator can use and a desk can verify. Session 1 touches no
backend at all.

### Session 1 — the Programmer becomes a place

> **Shipped** as `lighting-react` `66c99c3`. Still no desk pass. Four departures, in the first
> amendment above.

**Outcome.** There is a Programmer view. Program is Show. The tabs are gone, you can always see what
you are editing, the actions are legible, you can drag a selection across cells, and the show bar
survives a narrow window.

**What changes on screen** *(design: `Main`, `ProgrammerActions`, `ShowBar`)*

- A `programmer` nav entry and route, with `Show` where `Program` was. The old `pathMatch` collision
  between `/program` and `/programmer` is why the entry was dropped last time — this session is
  where that gets resolved properly rather than avoided.
- Values, layers and effects on one screen. No `Tabs`.
- A **source strip**, always present, naming what is loaded — cue number, name, stack, position —
  with a dirty count and Update / Revert / Detach. Its five states, empty included, are drawn on the
  canvas. Empty is a state, not an absence: "the programmer is empty, Include something" is the
  answer to a real question.
- The action bar in three labelled zones — **Stage** (Clear + fade, Blind), **Load** (Include),
  **Save** (Record ▾). Update moves out of here and onto the source strip, beside the thing it writes
  to. `Record look` stops being a sibling button and becomes a destination in Record's menu.
- **Drag-select across cells**, with a floating count chip naming the scope ("8 cells · 4 × Colour").
  Cell selection is a transient edit scope and is *orthogonal* to fixture selection, which keeps its
  checkboxes and keeps driving Record's scope. Both are on screen together; label them.
- **Run's own breakpoint defects go with it**, because they are the same class of bug and this is the
  session that owns narrow widths. A cue card in Run sizes its *header* on the viewport and its
  *panes* on its own container, so the two disagree by exactly the sidebar width — at some widths you
  get target chips visible above tabbed panes. Run's stack tabs scroll horizontally with no
  affordance, so a show with eight stacks hides them with nothing saying so. And a collapsed cue's
  note is indented by a hardcoded pixel value meant to line up under a column whose width is
  computed. Pick a single axis — container — and make these agree.
- The show bar **wraps rather than deletes**. Masters consolidate into one tile whose rail starts at
  M1 — which retires the split where the bar owned master 1 and the strip owned the rest, and frees
  the width the 560px collision was fighting over. Below that, two rows; below that, a meters chip
  with a popover. GO gets *wider* as the bar narrows, not narrower.

**Rules that must hold**

- The ownership colour vocabulary is already defined and documented in `ownership.ts`. Reuse it;
  do not mint a second one for cell selection — selection needs a visibly different affordance
  (marquee + fill), not another ring colour.
- `useNarrowContainer` defaults to `narrow = true`, so every mount paints the phone layout for one
  frame. Fix it here or the new show bar inherits a visible flash.
- `ViewSwitcher` uses a viewport `sm:` breakpoint while everything around it uses container queries.
  It gains a fourth pill this session; make it container-based at the same time.

**Non-goals.** No scope switching yet — the grid still shows the cook. No cue-editor changes. No
template work.

**Done when.** An operator can busk, see which cue they are editing, drag-select a block of colour
cells, set them in one gesture, and Record — without meeting a tab or a tooltip. At 380px the show
bar still offers every master.

---

### Session 2 — the stack is the editor

> **Split, and half shipped.** **2a** — everything in this section *except* §"Run and Show become
> one view" — is built (`lighting-react` `53be4fc`, `lighting7` `3110c21`). **2b** is that subsection
> alone, plus the rules under it that mention the lock, and is the next session. The split is the one
> §8 describes, and the order is fixed: 2b merges onto 2a's read-only cue surface.
>
> What 2a added beyond what is written below, because it turned out to be needed: `LayerRowNotices`
> (deferred and per-element Look rows are named rather than silently missing), `AddToTargetsButton`
> (the only way a layer widens — a marquee must never do it), and `UnsetCellMark` with the
> `placeholder` prop on all four cell components (departure 5 above).

**Outcome.** One programmer session is enough to compose a scene. You add looks, edit them in place,
reorder them, promote what you busked into a new one, and record a mixture of values and effects into
a Look — without leaving. The cue read surface becomes the same grid, and the three-pane cue editor
is deleted.

**And Run and Show become one view**, governed by the Prompt Book's lock. That was §5's
"separate conversation" until it became clear it is the *same* conversation: this session already
rebuilds Show's cue read surface as a read-only grid, and Run's three panes already *are* a
read-only cue surface. Doing both is building it twice and throwing one away. See
§"Run and Show become one view" below.

**What changes on screen** *(design: `Stack`, `StackScopes`, `RecordLook`, `AddEffect`, `Show`.
Note the artboards predate the merge — they draw Show alone, so read them for the cue surface and
not for the view count.)*

- **The scope switcher**: Output / Local / one layer. Focusing a stack entry points the grid at it.
  Clicking a tinted cell in Output jumps the scope to whichever entry won it — which is what finally
  makes the ownership colours navigational rather than decorative.
- **Local as the top stack entry**, always present, never removable, with its own count.
- **Focusing a layer** shows that Look's rows in the grid, editable. Columns outside the layer's mask
  go inert; fixtures outside its targets dim, with an "add to targets" affordance on the divider. The
  mask and the target list become things you see rather than popovers you open.
- **Make layer**: a selection in Local is promoted into a named Look, masked to the families you
  picked, saved to the library and applied here as a layer in one step. The rest stays local.
- **The two bands**, per §3, with each effect naming its home and stomp shown as suppression.
- **`+ Effect` follows the scope**, per the table in §3. `RecordLookSheet` currently has no notion of
  effects at all — recording a Look gains an **Effects** section beside the per-family value counts,
  each effect tickable. Three things to get right, because all three surprise: an unticked effect
  **keeps running** in the programmer band (leaving it out is not stopping it); delay/interval **do
  not travel** — `LookEffect` has no timing fields, so a busked effect's delay becomes the *layer's*;
  and the sheet counts what **Local** holds, not the cook, so busking on top of a layer that already
  asserted a colour records only your own value.
- **The record sheet asks about fixtures**: *keep these heads* (the default — a Look is a state over
  named heads, and that is what makes it reusable across cues) or *take from the layer*. The second
  is what would make it a template instead, so it greys out once more than one family is selected.
- **Two paths to a shared Look**, because the operator's confidence differs. **Busk first** is the
  default: set values, add the effect, then `Record → A new Look` gathers both, and the Look becomes
  the top layer immediately with what you ticked leaving Local. **Declare first** — `+ Look → New
  empty` puts an empty Look in the stack; focus it and everything you author goes straight in,
  because the scope *is* the Look, with no gathering step. Declare-first suits knowing up front that
  it is shared; busk-first suits still finding the look on the rig, which is most of the time. Ship
  both, default to busk-first.
- **The cue read surface is the grid**, plus the same `LayerRow`, drawn read-only. Cue metadata that
  has no home in a value grid — number, name, notes, fade, hooks — moves to a properties drawer.
- **Collapsed cue rows preview in the grid's language**: swatches for colour, bars for level. A Look
  library row does the same, because a Look and a cue are the same kind of thing at different scales.
- **"Add Cue" and "New Look" are gone.** Recording is the only way in. Show's stack header offers
  *Record into Act 1* instead.

**Run and Show become one view**

`/run` folds into `/show` and the pill count drops to three: Programmer · Show · Prompt Book.

The split was justified in `show-mode-engineering.md` on three grounds, two of which have already
lapsed — `ShowHeader` now renders identical Start/Stop and live-dot chrome on both views, which *is*
the conditional-header duplication the split existed to avoid, and **Show has had a fully working GO
transport for some time**, so "Run is where you fire cues" was already false. What remains is one
real distinction, and it is a *mode*, not a destination: whether a stray click can change the show.

That is exactly what the Prompt Book's lock already expresses, and it is derived rather than toggled:

```
locked = !canEdit || (isShowActive && lockRequested)
```

A stopped show is simply editable, with none of the editing chrome; starting the show re-arms the
lock so a session begun while stopped cannot carry into a running one; **GO re-locks immediately**;
and a two-minute idle re-lock gives ten seconds of visible countdown and a "Stay unlocked" escape.
`L` toggles it. Adopt that rule wholesale — the same hook, one shared lock across Show and the
Prompt Book, because "I am in a fix-it session" is one fact about the operator and one GO should end
it everywhere.

**Keep the lock client-local.** The backend has no notion of it and no route refuses a write on its
account, so a second client can edit a "locked" book today. It is a stray-click guard, not access
control, and dressing it as permission would be worse than not having it. The one thing ANDed in
front of it, `canEdit`, is not a role either — the backend computes it as `isCurrentProject`.

**The lock changes what you can do, not what things look like.** Two layouts under one switch would
be two views with extra steps. So both levels survive in both modes:

| | Locked (default while running) | Unlocked (default while stopped) |
|---|---|---|
| `/show` | the stack list, read-only | the same list, plus reorder / create / rename / delete |
| `/show/stacks/:id` | one stack's cues, **gaining Run's tab strip** for its siblings | the same, plus drag, inline edit, the editor |
| A cue row | Run's anatomy: state pip, fade chrome, click to arm standby | the same row, plus its editable cells |
| Transport | Space / Backspace live | shortcuts off — the operator is typing |
| Phone | `RunMobile` | — mobile is always locked |

Deleting the duplication is most of the work. The two sides render the same information twice in
fourteen places; `PaneShell`, `TabButton` and the lazy full-cue-fetch block are near-identical
copies, and the two `PaletteBar`s disagree (see §6).

**Two hard parts, neither of them about editing.** First, **two notions of the active stack**: Show
follows the server playhead, while Run owns a local browse cursor that fires `goToStack` +
`deactivateCueStack` behind a manual-switch guard — `useShowTransport` says outright that Run
"deliberately does NOT use this ... different code, not duplication". Reconciling those two is the
session's real risk, and it has nothing to do with the lock. Second, the two views hold **different
active-cue cursors on purpose** — Show reads the server's so the marker does not jitter during a
fade, Run reads the optimistic runner cursor so the fade animates. The merged row needs both,
chosen by mode.

**Rules that must hold**

- **Blind must be reachable wherever a cue can be opened.** D2 makes opening a cue Include it, and
  Include goes to the stage — so "enable Blind first" is the operator's move to make, and the desk's
  job is only to keep it one press away at the moment it is needed. It is not today: `Blind` toggles
  **only** in the programmer's action bar, while the ShowBar merely *displays* the state through
  `ProgrammerIndicator`. Left alone, the flow for "fix Q7 without it hitting the stage" becomes leave
  Show → Programmer → Blind → back → unlock → open, which is precisely the leaving-to-do-a-thing
  friction §1 opens with, reintroduced at the worst moment.

  Put the control in the **ShowBar**, beside DBO: they are the same class of thing (a gate on what
  reaches the rig), the bar is on every view, and the state is already drawn there. Showing it only
  while **unlocked** costs no width during a running show and ties it to D11 — locked, the badge
  stays a read-out; unlocked, it becomes the switch. Do not make the existing indicator itself the
  toggle: it is also the link to the programmer, and one control cannot be both without one of the
  two jobs becoming a surprise.
- **Locked means no `cueEdit` session, ever.** Today a session is opened per expanded card and
  defaults to `mode: 'live'`, and Run auto-expands the live cue on every stack switch. Combined
  naively that opens a live edit session on the cue that is on stage, mid-show, on every switch.
  This is the one show-critical hazard in the merge and it must be an explicit rule rather than an
  emergent property of which panes happen to mount.
- **Dragging must be impossible while locked, not merely discouraged.** The cue list wraps every row
  in a dnd context and the cue card calls `useSortable` unconditionally; a sortable row in a live cue
  list is the accident the lock exists to prevent.
- **Deep links survive.** `?cue=` is an external contract — the Prompt Book mints it, and the
  `/program*` redirects carry the search string precisely to keep it. Show addresses one cue by URL
  while Run tracks a *set* of expanded cues locally; pick one and say which, rather than letting the
  merge quietly drop the deep link.
- **Editing a shared Look edits it everywhere**, and this must be said at the moment of the first
  edit, not in a tooltip. **Duplicate for this cue** is the primary action, not "change all 9
  layers": retuning one cue is the common intent, and the other reading is the one an operator cannot
  undo across nine cues. A Look used by nothing else skips the question.
- **Reordering must not restart phase.** The programmer stack already gets this right by *not*
  renumbering `sortOrder` client-side — the server renumbers and re-ranks running effects in place.
  Every new reorder affordance (keyboard, the Output-cell jump, promote-into-position) routes through
  the same op. With composition now happening live, a drag is a normal gesture rather than a rare one.
- **The scope is not a filter on the cook.** Local shows what *you* set and nothing else, so "what
  will Record take?" becomes something to look at rather than a colour to trust.

**Non-goals.** No template work. Effects keep their current authoring UI — this session changes where
they *land*, not how they are configured. The Prompt Book's own layout is still untouched: it lends
its lock, it does not change.

**Done when.** An operator builds a scene from three looks and an effect, retunes one look mid-scene,
reorders the stack while the effect runs without it restarting, records the result, and never opens
a second editor. `CuePropsPane`, `TargetsPane` and `LayersPane`'s two arrangements are gone.

And: an operator runs the show, spots a wrong cue, presses `L`, fixes it in place, and presses GO —
which re-locks on its own. There is no `/run` to go to, and at no point did a locked list accept a
drag or open a `cueEdit` session.

---

### Session 3 — Templates

> **Shipped.** Nine departures, in the fourth amendment above. Still no desk pass — §7.4 stands.

**Outcome.** Templates are their own library and their own thing: one family each, no fixture type,
applied to a selection by click or as a tracking layer by ⌥click. A colour works on any head with
colour, resolved per head, with the resolution visible before you save.

**What changes on screen** *(design: `Templates`, `TemplateEditor`, `TwoThings`, `BeamColour`, `LookLibrary`)*

- **`/templates`**, with **New template** on it, and a family filter that is an exact partition. The
  `/looks` family filter goes — a Look spans families by nature, so filtering by one would hide most
  of them from most filters. `/looks` loses its New button entirely and says why.
- **Family first.** The editor asks for the family before anything else and gives a **family-native**
  control — a colour picker, one level, a pan/tilt pad, a short list of normalised beam sliders.
  There is no property list and no synthetic fixture, because there is no mode to resolve one against.
- **A "resolves to" panel**, live against the real patch, listing each affected head and what it will
  actually receive — including where it degrades. A head with no colour does not appear.
- **The template strip in the programmer**, filtered to the selection's family, ending in a
  *new-from-selection* chip. That chip is how the library fills up without anyone visiting it.
- **Two apply gestures**: click sets literal values in Local; ⌥click (or a drag onto the stack) adds a
  layer that tracks the template, targeted at the selection and masked to the template's family.
- **The beam editor shows its exclusions**, disabled with the reason on hover, rather than omitting
  them. An operator looking for gobo needs to learn *where* it lives, not conclude the desk cannot
  do it.

**Rules that must hold**

- **One family per template**, enforced at the write boundary — beside the check that already rejects
  a `ref:`-shaped value, which is the same kind of guarantee in the same place.
- `families` stays **derived**, not declared. A template reports one because its rows are one, not
  because a column says so.
- Compatibility becomes **capability-only**. Every consumer of the old type-based filtering needs a
  new answer, and one of them fails quietly: a picker that returns nothing when its compatible list
  is empty will simply not render.
- **The value grid disappears from the template editor.** Removing the fixture type removes the thing
  the grid was built out of; do not try to keep both.

**Non-goals.** No per-property blend ([`FU-LOOK-PERPROP-BLEND`](followups.md#fu-look-perprop-blend)).
No positional-list conversion
([`FU-PAL-POSITIONAL-CONVERSION`](followups.md#fu-pal-positional-conversion)). "Palette" continues to
mean exactly one thing — the positional ordered colour list FX parameters index as `P1`/`P2`.

**Done when.** One "Amber Key" is applied to an LED bar, a MAC Aura and a CMY profile in the same
cue and each looks amber; retuning it moves every layer that tracks it; and a beam template says out
loud that it cannot carry a gobo.

**Sizing.** This is the session most likely to want splitting, on the 3a/3b precedent: the entity,
the library, the editor and the apply gestures resolving **colour only**, then position degrees and
beam roles as a second pass. Split it if the first half is landing slowly rather than pushing to
finish all three families.

---

## 5. Explicitly out of scope

- **Per-user programmers — decided, not deferred.** This entry used to ask for a decision before
  Session 2. It was taken on 2026-08-23: **the programmer stays one shared state**, and that is the
  design rather than a tradeoff. See D12, and
  [`FU-PROG-PER-USER`](followups.md#fu-prog-per-user), now a Rejected decision record.
- ~~**Run and Prompt Book *layouts*.**~~ **Superseded for Run** — the merge is now Session 2's, and
  the reasoning that put it here has inverted. This entry argued that redesigning a Run card "would
  put show-critical layout in the same change as an authoring rework". True, and unavoidable: Session
  2 rebuilds Show's cue read surface as a read-only grid, which is the job Run's panes already do, so
  leaving Run out means building that surface twice. The risk the entry names is real and is answered
  by the rules above rather than by deferral.

  **The Prompt Book's layout is still out of scope.** It lends the lock and gains nothing. Its rail,
  its script pane and its annotation model are untouched.
- **The effect configuration form.** Sessions 2 and 3 change where effects live and which tempo they
  follow, not how a parameter is set.
- **Migrations of any kind.** See the status note.

---

## 6. Follow-ups to record

- [`FU-LOOK-ELEMENT-ROWS`](followups.md#fu-look-element-rows) (Ready) collides with the scoped grid:
  a Look's element rows compose nowhere, so per-head rows in a focused layer will render as empty.
  Either pick it up inside Session 2 or state in the UI that element rows are not composed.
- [`FU-CUE-APPLYDATA-ONE-BUILDER`](followups.md#fu-cue-applydata-one-builder) (Ready) sits in the
  cook path the Output scope reads. Cheap to absorb while that path is open.
- New, from Session 2: the shared-Look edit guard needs a **usage count** at the point of edit. If
  that is not already cheap to ask for, log it rather than fetching a Look's full detail per keystroke.
- New, from Session 3: the wheel-snap ΔE shown in the editor and the value actually written at cook
  must come from **one** implementation. Two would drift, and the editor's whole job here is to
  promise what the rig will do.
- **A live bug, found while scoping the Run/Show merge and worth fixing whatever happens to it.**
  There are two copies of `PaletteBar` and they disagree: Run's resolves gel names through
  `resolveColourToHex`, Show's assigns the stored string straight to `background`. So the same cue's
  palette swatches render differently in the two views, and a gel-named colour is simply wrong in
  one of them. The merge deletes one copy; until then, fix the duplication.
- **Stale documentation of an affordance that no longer exists.** Four places — `RunPropsPane`,
  `ShowHeader`, `App.tsx` and `show-mode-engineering.md` — still describe an "Edit Cue" button that
  jumps from Run to the cue editor. It is gone, so today there is *no* route from a cue in Run to
  editing it except the view switcher. The merge closes that gap; the stale comments should go with
  it, and `show-mode-engineering.md` needs a broader pass (it still describes `ProgramPage`,
  `ShowRunnerMobile`, `CueDetailSheet` and a Theatre/Band toggle, none of which exist).

---

## 7. Verification

Unit and integration coverage as usual, plus a desk pass per session — the looks-and-layers work
found two bugs on a desk that no test had, both in exactly this area (a provenance branch that never
named the winning layer, and a layer frame that reached only the acting tab). Assume that pattern
holds.

Per session, on a live desk. **Neither S1's nor S2a's desk pass has been done** — both shipped on
browser verification alone, and 2a specifically had two defects that a green suite called working.

1. **S1** — busk, read the source strip, drag-select a block, Record. Resize to 380px and confirm
   every master is still reachable.
2. **S2a** — three looks and an effect in one session; retune a look mid-scene and watch the stage;
   drag a layer while the effect runs and confirm the phase survives; record values + effects into
   one Look; confirm a second tab sees the stack change *and* that the scope falls back when a layer
   goes. Then the two that only a rig can answer: whether the layer-scope save cadence (~2 writes a
   second, so the rig **steps** rather than glides) is tolerable — if it is not, the answer is a
   bound-row preview channel, not a faster PUT, because `LookPreviewRequest` is deferred-only — and
   whether landing in Local rather than on the cook reads right at the desk.

3. **S2b** — the merge, which is the half that can hurt a running show. With the show **running**: try to
   drag a cue and fail; press `L`, edit, press GO, and confirm it re-locked itself; leave it unlocked
   and idle and watch the countdown re-lock it; confirm Blind appears beside DBO only while unlocked
   and actually gates the rig. With the show **stopped**: confirm it is simply editable, with no lock
   chrome anywhere. Then a phone: confirm it is locked and cannot be unlocked. Switch stacks from the
   tab strip while a cue fades, and confirm the fade animates *and* the marker does not jitter — that
   is the two-cursor rule, and it is the one that will be got wrong.

   Two more, from the browse/arm split that 2b introduced and this list predates: browse to a sibling
   stack and confirm **GO still fires the live one** (the guarantee that makes off-playhead browsing
   safe at all), and press *Make this stack live* with a cue on stage — it must ask first, and the
   blip it warns about (`go-to` fires the target's first cue before the desk darkens it) is worth
   watching for on the rig, since only a rig can show it.

   The `cueEdit` check is retired: no client-side session exists to open. That is a *structural*
   guarantee now rather than a behavioural one — the arm and its API module are deleted.
4. **S3** — the checks only a rig can answer, and this session has more of them than the others
   because resolution is per head:

   - **One colour template across three colour types at once** — an RGBWA hex, a white-only head and
     the MAC 250's *wheel* — and each should read as the same colour. The wheel is the one to judge by
     eye: the editor's ΔE says how close the desk believes it got, and only the rig says whether that
     belief is right (`FU-TMPL-WHEEL-PREVIEWS` is where a bad annotation would land).
   - **Retune it and watch every tracking layer move**, in a cue and in the programmer's own stack.
     That is `republishForTemplateEdit`, and it is the touring feature.
   - **The two gestures are visibly different**: click, then retune — the busked values must *not*
     move. ⌥click, then retune — they must.
   - **A position template in degrees across two heads with different ranges** (Shehds tilts 0–270°,
     MAC 250 0–257°): the same degrees must land on different DMX, and a clamp must be reported in the
     panel *and* true on stage.
   - **The white/amber policy on a head that has both**: extract should look brighter and cleaner than
     RGB-only at the same hue, which is the whole claim.
   - **A beam template says out loud that it cannot carry a gobo** — the disabled rows with their
     reasons, rather than the property simply being absent.
   - **New from selection** on heads that agree, then on heads that differ: the first must come out
     *Generic*, the second *Per fixture*, and the toast says which.

New routes, classes or fields need a **restart** — the backend hot-swaps changed handler bodies but
not new surface area, and the desk may be driving a live rig. Ask first. Anything that fails a desk
pass becomes a `FU-` item rather than an inline fix.

---

## 8. Scope honesty

Sessions 1 and 2 are the felt improvement and carry no backend risk worth naming. Session 3 is the
conceptually interesting one and also where the cost is: removing the fixture type removes the
machinery the template editor is built from, and the per-head resolution has to be right in the
engine and *visible* in the editor or it is worse than the constraint it replaces.

**Session 2 grew when the Run/Show merge folded in, and it is now the largest of the three.** That
is the right call on duplication grounds — the alternative builds one cue read surface twice — but
it should be said plainly that it also moves show-critical playback into an authoring change, which
§5 previously refused. What makes it acceptable is that the lock is a *pre-existing, proven*
mechanism rather than something invented here, and that the two hard parts are both nameable and
neither is about editing.

**Session 2 did split, on exactly this seam**: **2a the programmer stack** (scopes,
layers-in-place, Record with effects) and **2b the merge** (one view, the lock, Run's tab strip in
the drill-down). 2a shipped and stands alone, as predicted; 2b needs 2a's read-only grid to merge
*onto*, so the order was and is fixed.

One thing 2b should know that 2a learned: **there are still no tests for `ShowPage`, `StackDetail`,
`ShowOverview`, `ProgramView` or `CueCardEditor`**, so `?cue=` deep-link behaviour has no safety net
— and "Deep links survive" is one of 2b's own rules. 2a did not close that gap because it only
changed what an expanded card *renders*, not how a cue is addressed; 2b changes the addressing, so
the coverage is worth writing first rather than last.

If the three sessions have to become two, the honest cut is still **Session 3's second half** — ship
colour resolution and leave position and beam type-scoped, with the beam exclusions already written
down. Cutting Session 2 instead would leave the programmer roomier but still not able to compose,
which is the complaint that started this.
