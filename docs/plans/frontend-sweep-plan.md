# Frontend sweep — what the refactors left behind

> **Document status: findings catalogue, reconciled against the completed backend sweep
> (2026-08-29); awaiting triage.** Produced 2026-08-24 by a two-round
> multi-agent sweep of `lighting-react` (31 reviewer/verifier agents: nine audit dimensions plus a
> completeness critic, then five gap dimensions the critic identified; every finding adversarially
> verified against the code, S1s twice independently, and the S1/S2 band re-read by hand). Scope is
> the **frontend only** — backend facts appear solely in §14, which reconciles this catalogue
> against the sibling [backend-post-refactor-sweep.md](backend-post-refactor-sweep.md): frontend
> work that must land in step with its waves, plus the few backend findings this sweep hit that its
> backlog does not carry. This document departs from the house plan format in one declared way: each
> finding carries a severity / priority / complexity rating and a suggested model tier, defined in
> §2, because the intended consumer is a dispatching operator handing items to agents.
>
> Finding IDs use the slug grammar of `followups.md` but with an `FS-` prefix so they cannot be
> confused with real `FU-` items until deliberately promoted. **The sibling backend sweep is now
> complete** (all waves 0–6, `a4bf981`): no item here is gated on a backend wave any longer, five
> `FS-COORD-*` items landed with their backend halves, two became live defects, and three new
> coordination items and two new backend-half proposals came out of the landing. §14 carries all of
> it, and is the section to read before dispatching anything. Known-open `FU-` items were excluded
> from the sweep by construction; where a finding *corrects* one, it says so inline
> (`FS-DEAD-ORPHAN-FILES` vs `FU-FE-REBIND-INPLACE`, `FS-DEAD-DTO-FIELDS` vs
> `FU-FE-SHARED-LOOK-EDIT-GUARD`).

## 1. Context

`lighting-react` has just absorbed two long refactor arcs — looks-and-layers (five sessions) and
desk-simplification (four) — that rewrote cue compositing around Look layers, made the Programmer a
page with a scoped grid, split palettes/presets into Looks and Templates, folded Run into Show
behind the edit lock, and added speed masters. This sweep hunts what that much rewriting predictably
leaves behind: residue and gaps, performance regressions, dead code, unnecessary architecture, and
stale rationale. The deliverable is this catalogue; **no code was changed**.

The headline: the refactors themselves held up well — the load-bearing invariants (grid never
remounts, null scope ≠ Output, the two apply gestures, the lock semantics) are all intact and mostly
pinned. What the sweep found instead is two genuine live-desk bugs at the *edges* the refactors
didn't reach (`FS-BUG-CUESLOT-LIVENESS`, `FS-BUG-STALE-ROW-SNAPSHOT`), a band of real per-frame
waste in the WS fan-out and the fade path, roughly ten thousand lines of dead or misplaced code, and
a long tail of rationale comments that now describe deleted machinery — the most dangerous kind of
residue, because future agents obey comments.

## 2. How to read this

Each finding: one `### FS-<AREA>-<SLUG>` block with a rating line, the mechanism, and a fix at
agent-briefing altitude (what to do and what must not break — never step-by-step). Code is cited as
path + symbol, no line numbers. Ratings:

- **Severity** — `S1` incorrect behaviour or data loss on a live desk; `S2` real performance cost,
  or misleading code likely to breed bugs; `S3` debt (dead code, duplication, structure); `S4`
  cosmetic — naming, docs, hygiene.
- **Priority** — `P1` next working session; `P2` next cleanup round; `P3` opportunistic, or ride
  along with a neighbouring fix.
- **Complexity** — `C1` mechanical, no design; `C2` contained to one subsystem; `C3` cross-cutting
  or needs a design decision first.
- **Model** — cheapest tier an agent needs to fix it *safely*: `haiku` for mechanical batches,
  `sonnet` for contained changes, `opus` for cross-cutting or perf work, `fable` only where the
  change touches live-desk invariants (grid-remount rule, WS ordering, blind semantics, transport).

The backend sweep's scales map roughly onto these: its critical/high/medium/low ≈ S1/S2/S3/S4, its
P0 folds into P1 here, and its S/M/L ≈ C1/C2/C3 — the vocabularies differ because this doc's
consumer dispatches per-finding agents while that one executes in waves.

Verification status: every finding here survived an adversarial verifier that read the cited code;
where the verifier corrected the original claim, the dossier states the corrected version. Ratings
are post-verification (several were downgraded). One batch (`FS-DOCS-STALE-COMMENTS`, the doc-comment
items from the residue dimension) missed its verdict pass on a bookkeeping mismatch; its individual
claims are trivially checkable at fix time and are flagged there.

## 3. Index

124 findings: 2 × S1, 19 × S2, 68 × S3, 35 × S4. Sorted by severity then priority. The
`FS-COORD-*` rows were the backend seam (§14); all eleven have now landed. The `FS-BE-*` backend
halves still owed are listed in §14 only.

| ID | Finding | Sev | Pri | Cx | Model |
|---|---|---|---|---|---|
| ~~`FS-BUG-CUESLOT-LIVENESS`~~ done | "Is this cue/stack live?" is derived from the FX effect stream, so a rows-only cue reads… | S1 | P1 | C2 | fable |
| ~~`FS-BUG-STALE-ROW-SNAPSHOT`~~ **done** | Per-row programmer snapshot cache goes stale across an off→on subscription cycle | S1 | P1 | C2 | fable |
| ~~`FS-BUG-EDITOR-RESET-NOOP`~~ **done** | A changed `value` prop can never reach a live playground editor, so ScriptForm's Reset si… | S2 | P1 | C2 | sonnet |
| ~~`FS-BUG-FADE-KEY-SNAPSHOT`~~ **done** | `PROGRAMMER_FADE_KEY` is shared by key but not by value — ShowBar's Blind uses a mount-ti… | S2 | P1 | C2 | sonnet |
| ~~`FS-BUG-FXROUTE-REGEX`~~ **done** | `isFxRoute` is an unanchored prefix match and fires on `/fx-library`, locking the effects… | S2 | P1 | C1 | sonnet |
| ~~`FS-BUG-PROGRAMMER-ERROR-DROPPED`~~ **done** | `programmer.error` frames are delivered to zero subscribers, so a busk that lands nowhere… | S2 | P1 | C2 | sonnet |
| ~~`FS-BUG-RECONNECT-RESYNC`~~ **done** | Post-reconnect cache resync is a hand-maintained 15-tag list against 47 tagTypes; 20 tags… | S2 | P1 | C2 | sonnet |
| ~~`FS-BUG-TIMEDLAYERS-RENAME`~~ **done** | Record's "timed effect(s) kept" note is dead: backend renamed `timedPresetApplications` →… | S2 | P1 | C1 | sonnet |
| ~~`FS-DUP-AGGREGATION`~~ **done** | Two implementations of "aggregate a property across heads", already numerically divergent | S2 | P1 | C3 | opus |
| ~~`FS-PERF-FADE-IN-SHOWBAR`~~ **done** | Fade progress is prop-drilled into the ShowBar, re-rendering the chrome (and all of `Prog… | S2 | P1 | C3 | fable |
| `FS-PERF-MARQUEE-COUNT` | `batchCountFor` recomputes an O(rows × columns) marquee count for every rendered cell per… | S2 | P1 | C2 | sonnet |
| ~~`FS-PERF-PROGRAMMER-MEMO-BARRIER`~~ **done** | The programmer page has no memo barrier between chrome and body | S2 | P1 | C2 | sonnet |
| ~~`FS-PERF-SAVELOOK-INVALIDATION`~~ **done** | A layer-scope drag refetches the whole fixture list every 400 ms | S2 | P1 | C1 | sonnet |
| ~~`FS-PERF-WS-SINGLE-PARSE`~~ **done** | The channelState firehose is `JSON.parse`d 24 times per frame | S2 | P1 | C2 | sonnet |
| ~~`FS-TEST-LOOKONLY-GATE`~~ **done** | The LOOK-only gate — the guard against silently converting a generic template to per-fixt… | S2 | P1 | C1 | sonnet |
| ~~`FS-BUG-EDITOR-SILENT-READONLY`~~ **done** | A failed `/api/script-editor/versions` drops every editor to read-only and the frontend neith… | S2 | P2 | C2 | sonnet |
| `FS-BUG-PIXEL-CACHE-PERMUTATION` | `useGroupColourValues` never compares per-member colours, so a colour chase across a pixe… | S2 | P2 | C2 | sonnet |
| ~~`FS-PERF-CODE-SPLITTING`~~ **done** | The whole app ships as one 4 MB chunk — no route or vendor splitting anywhere | S2 | P2 | C2 | opus |
| ~~`FS-PERF-COLLAPSED-PANELS`~~ **done** | Collapsed overview panels keep doing full live work on every route | S2 | P2 | C2 | opus |
| ~~`FS-PERF-PROMPTBOOK-FADE-DRILL`~~ **done** | Prompt Book prop-drills `fadeProgress` to every cue card in the whole show | S2 | P2 | C2 | sonnet |
| `FS-TEST-PUBLICPATH` | The publicPath auth/boot-gate bypass predicate is untested and unexported | S2 | P2 | C2 | sonnet |
| ~~`FS-COORD-ADMIN-GATE`~~ **done** | Gate Export/Import in `Projects.tsx` — F6 landed, so both controls are live 403 generator… | S3 | P1 | C1 | sonnet |
| ~~`FS-COORD-CUEEDIT-RETIRE`~~ **done** | Delete the client's cueEdit remnants — D1 landed; the `force` senders must go before the ba… | S3 | P1 | C1 | sonnet |
| ~~`FS-COORD-WIRE-FIELD-DELETIONS`~~ **done** | The retired-concept wire fields are gone server-side under A2/D9 — every client site read… | S3 | P1 | C1 | haiku |
| ~~`FS-COORD-GROUPS-WS`~~ **done** | Delete the client groups WS layer — D3 landed, and the question it waited on is answered:… | S3 | P1 | C1 | sonnet |
| ~~`FS-COORD-LEGACY-TEMPO`~~ **done** | Migrated with backend D2 — see the item | S3 | P1 | C2 | sonnet |
| ~~`FS-EDITOR-DEBOUNCE-DIRTY`~~ **done** | onChange is debounced 500 ms with no flush, so the unsaved-changes guard and every Compil… | S3 | P1 | C2 | sonnet |
| `FS-PERF-BPM-INVALIDATION` | Any BPM change invalidates `FixtureEffects` + `GroupActiveEffects` — **tempo half gone with D2**; only the `FxBadge` consolidation is left | S3 | P2 | C2 | sonnet |
| `FS-TYPES-TEMPLATE-TOGGLE-MASK` | Template toggle discards the client's `propertyMask` and the server derives none, so ever… | S3 | P1 | C1 | sonnet |
| ~~`FS-WS-ERROR-ISOLATION`~~ **done** | `notifyEvent` has no per-subscriber error isolation, and the programmer bridge is registe… | S3 | P1 | C1 | sonnet |
| ~~`FS-ARCH-ALERTDIALOG-DEP`~~ **done** | `@radix-ui/react-alert-dialog` is an undeclared dependency, resolved only by hoisting fro… | S3 | P2 | C1 | haiku |
| ~~`FS-ARCH-CURSOR-OWNERSHIP`~~ **done** | Two stores own the live-cue/armed-next cursors; several of the resulting copies have no r… | S3 | P2 | C3 | fable |
| `FS-ARCH-GRID-IN-ROUTES` | `FixturesListContainer` — the shared value grid — lives in a route module a component imp… | S3 | P2 | C2 | sonnet |
| ~~`FS-ARCH-IMPORT-CYCLE`~~ **done** | The tree's only runtime import cycle: `CueSlotOverviewPanel` ↔ `CueSlotEditAssignPanel`,… | S3 | P2 | C1 | sonnet |
| ~~`FS-ARCH-LOCALSTORAGE-BOOT`~~ **done** | Unguarded `localStorage` on the boot path, against the policy the tree states explicitly | S3 | P2 | C1 | haiku |
| ~~`FS-BUG-CUE-TAG-STALE`~~ **done** | The `Cue` tag has no WS invalidation on any path, so an expanded cue's composed values go… | S3 | P2 | C2 | opus |
| ~~`FS-BUG-WS-SEND-DROPPED`~~ **done** | Every WS write is silently dropped while the socket is down — programmer sets, Blind, bla… | S3 | P2 | C2 | opus |
| ~~`FS-COORD-API-NORMALIZE`~~ **done** | Landed with backend F1–F5/F8 across five client commits — see the item | S3 | P2 | C2 | sonnet |
| ~~`FS-COORD-FXLIBRARY-PARAMS`~~ **done** | D7 ships real parameter types/defaults, so the FX sheet's never-exercised double-slider r… | S3 | P2 | C2 | sonnet |
| ~~`FS-COORD-PREVIEW-DEAD`~~ **done** | D4 deleted the Look preview routes and `installPreview`, so every client `isPreview` filt… | S3 | P2 | C1 | sonnet |
| `FS-DEAD-CUELAYER-HELPERS` | `reorderCueLayers` and `densifyCueLayerOrder` have no production caller | S3 | P2 | C2 | sonnet |
| ~~`FS-DEAD-CURRENTCUESTATE`~~ **done** | The `currentCueState` chain is dead, and its wire-compat comment protects a type nothing… | S3 | P2 | C1 | haiku |
| ~~`FS-DEAD-DEVDEPS`~~ **done** | Eight unused devDependencies, including a Prettier-in-ESLint wiring never made | S3 | P2 | C1 | sonnet |
| ~~`FS-DEAD-EXPORTS`~~ **done** | Sixteen exported symbols with zero references anywhere | S3 | P2 | C1 | haiku |
| ~~`FS-DEAD-ORPHAN-FILES`~~ **done** | Six files unreachable from `main.tsx` (and from any test) | S3 | P2 | C1 | haiku |
| `FS-DEAD-RTKQ-HOOKS` | Fourteen exported RTK Query hooks with zero importers; three FX-definition endpoints full… | S3 | P2 | C2 | sonnet |
| `FS-DUP-COLOUR-POPOVER` | `FxColourPicker` and `FxColourListPicker` duplicate the whole colour-popover body | S3 | P2 | C2 | sonnet |
| `FS-DUP-EFFECT-COMPAT` | Effect compatibility and sentinel-property resolution implemented twice | S3 | P2 | C2 | sonnet |
| ~~`FS-DUP-OVERVIEW-TOGGLES`~~ **done** | Four near-identical Overview toggles plus three alias hooks for one persistent toggle | S3 | P2 | C1 | haiku |
| `FS-DUP-REDIRECTS` | Seventeen byte-identical "redirect to the current project's equivalent" components | S3 | P2 | C1 | haiku |
| ~~`FS-DUP-ROW-SUBSCRIPTION`~~ **done** | `useRowOwnership` and `useLocalRowValues` duplicate the whole per-row programmer subscrip… | S3 | P2 | C2 | sonnet |
| ~~`FS-EDITOR-HIGHLIGHTONLY-PRESENCE`~~ **done** | Read-only works only because React omits an `undefined` attribute — `highlightOnly="false… | S3 | P2 | C1 | sonnet |
| ~~`FS-EDITOR-PROPTYPES-PHANTOM`~~ **done** | `prop-types` is a phantom dependency of the wrapper, and React 19 ignores what it declares | S3 | P2 | C1 | haiku |
| ~~`FS-PERF-CHANNEL-FANOUT`~~ **done** | One row's callback fires once per changed channel per batch, rebuilding its signature eac… | S3 | P2 | C2 | opus |
| ~~`FS-PERF-CHANNELSOURCE-REBUILD`~~ **done** | `createProgrammerChannelSource.rebuild` re-resolves every programmer entry on frames that… | S3 | P2 | C1 | sonnet |
| ~~`FS-PERF-FADE-DISPATCH`~~ **done** | Fade animation dispatches into Redux at 60 Hz; dev builds deep-scan four slices per frame | S3 | P2 | C3 | fable |
| ~~`FS-PERF-PROVENANCE-REFETCH`~~ **done** | Every cue crossfade tick drives a `programmer.state` request/response at up to 10 Hz per tab | S3 | P2 | C3 | fable |
| `FS-PERF-SIGNATURE-CACHE` | `changedKeys` recomputes both sides' JSON signatures on every diff | S3 | P2 | C2 | sonnet |
| `FS-RES-CUECARDEDITOR-DIR` | `runner/program/CueCardEditor/` is a directory owned by nobody | S3 | P2 | C1 | haiku |
| ~~`FS-RES-PALETTERESULT`~~ **done** | `UpdateDialog` renders an unreachable branch from a field the server deleted | S3 | P2 | C1 | haiku |
| ~~`FS-RES-PRESETPICKER`~~ **done** | `FxSection`'s `presetPicker` prop and doc describe a deleted synthetic-fixture preset bra… | S3 | P2 | C1 | haiku |
| `FS-TEST-COLOUR-TEMPLATES` | `FxColourTemplates` is untested, and its offerable filter is stricter than CLAUDE.md states | S3 | P2 | C2 | sonnet |
| ~~`FS-TEST-CUEUTILS-TRIGGERS`~~ **done** | `cueUtils.test.ts` pins fourteen layer fields (CLAUDE.md says thirteen) and none of the t… | S3 | P2 | C1 | haiku |
| ~~`FS-TEST-EDITOR-PINS`~~ **done** | The documented cross-type poisoning landmine is safe by construction today — and nothing… | S3 | P2 | C2 | sonnet |
| `FS-TEST-PROGRAMMER-SCOPE` | `focusLayer`'s membership guard and the removed-layer fallback are unpinned | S3 | P2 | C1 | sonnet |
| `FS-TEST-PROVENANCE-PIN` | The provenance-signature test pins field names that no longer exist, and the `layerSource… | S3 | P2 | C1 | sonnet |
| `FS-TYPES-ADDLAYER-MASK-DROP` | `ProgrammerLookStack.handleAdd` drops `propertyMask` when forwarding LayerPicker's layer | S3 | P2 | C1 | haiku |
| `FS-TYPES-CUE-STOMP` | Cue-level `stomp` is absent from the `Cue`/`CueInput` mirror, so duplicating a cue silent… | S3 | P2 | C1 | sonnet |
| `FS-TYPES-EFFECTTYPE-UNION` | `EffectType` is a closed 20-literal union with no backend counterpart, laundered by a cast | S3 | P2 | C2 | sonnet |
| `FS-TYPES-MASKGROUP-DUP` | `PropertyMaskGroup` is a second, free-standing copy of `AttributeFamily` | S3 | P2 | C2 | sonnet |
| `FS-TYPES-PALETTE-WIRE-ARMS` | Retired palette wire arms kept alive by "still on the wire" claims that are now false | S3 | P2 | C2 | sonnet |
| `FS-TYPES-PRESETCOUNT-RENAME` | `CueStackCueEntry.presetCount` mirrors a field renamed to `layerCount` — confirmed gone s… | S3 | P2 | C1 | haiku |
| `FS-TYPES-RIGGING-POSITION` | `FixturePatch.riggingPosition` is a phantom; the StageMarker badge it drives can never re… | S3 | P2 | C2 | sonnet |
| `FS-ARCH-BUSKING-GOD-HOOK` | `useBuskingState` is a 617-line hook mixing selection, derivation, presence rules and fou… | S3 | P3 | C2 | sonnet |
| `FS-BUG-3D-PLACEHOLDER` | The imperative 3D colour copy has no placeholder arm — an unmatched patch draws as a full… | S3 | P3 | C1 | sonnet |
| `FS-DEAD-DTO-FIELDS` | Wire-mirror fields never read by the client, several naming retired concepts | S3 | P3 | C2 | sonnet |
| `FS-DEAD-WS-METHODS` | Six WS API methods declared, implemented, never called | S3 | P3 | C2 | sonnet |
| ~~`FS-EDITOR-DEAD-BRANCH`~~ **done** | ScriptEditor's entire non-compact branch is unreachable and duplicates the widget mount v… | S3 | P3 | C2 | sonnet |
| ~~`FS-EDITOR-LIFECYCLE`~~ **done** | Playground instances are never destroyed on unmount, and `window.playgroundInstance` is a… | S3 | P3 | C1 | sonnet |
| `FS-PERF-CHANNEL-CACHE-DISPATCH` | /channels holds one RTK Query cache entry per channel, dispatching per changed channel pe… | S3 | P3 | C2 | sonnet |
| `FS-PERF-LITKEYS-ALLOC` | `useLitFixtureKeys` rebuilds a Set and spreads it on every snapshot read | S3 | P3 | C1 | haiku |
| ~~`FS-PERF-MOBILE-SHEET-FADE`~~ **done** | Phone cue-list sheet re-renders every row per fade frame with an O(n²) done-tick | S3 | P3 | C2 | sonnet |
| `FS-PERF-PALETTE-QUERIES` | CommandPalette subscribes six list queries while closed, on every route | S3 | P3 | C1 | haiku |
| `FS-PERF-STAGE-BUFFER-UPLOADS` | `StageEmitters` marks every instanced attribute dirty every frame, so a static rig re-upl… | S3 | P3 | C2 | sonnet |
| `FS-RES-CLOUDSYNC-SPLIT` | `routes/CloudSync.tsx` is a 1,306-line module holding two routes and twenty components —… | S3 | P3 | C1 | haiku |
| `FS-RES-FIXTUREMODEL-SPLIT` | `FixtureModel.tsx` mixes a 1,500-line R3F component with pure beam-cookie geometry, forci… | S3 | P3 | C2 | sonnet |
| `FS-RES-PROMPTBOOK-GODPAGE` | `PromptBookViewerPage` is ~1,000 lines with 54 hook calls and 15 hand-placed `noteEdit()`… | S3 | P3 | C3 | sonnet |
| ~~`FS-TYPES-GROUPFX-WS`~~ **done** | groupsApi's WS layer declares a frame the backend never emits and two methods nothing calls | S3 | P3 | C1 | haiku |
| `FS-TYPES-SURFACE-DESCRIPTORS` | Control-surface descriptors omit `touchCc` and `programChange`, and type `BankButtonContr… | S3 | P3 | C1 | haiku |
| `FS-ARCH-BRIDGE-EVAL` | ~23 module-scope WS-bridge subscriptions vs three documented deferred ones — the rule is… | S4 | P2 | C2 | sonnet |
| ~~`FS-COORD-NEW-BROADCASTS`~~ **done** | B5 shipped `scriptListChanged`/`fxDefinitionListChanged`; this repo has no listener for e… | S4 | P2 | C1 | sonnet |
| ~~`FS-DOCS-CLAUDEMD-CUE-ARM`~~ **done** | CLAUDE.md claims `EditorContextValue`'s `cue` arm is kept; the code removed it in 2b | S4 | P2 | C1 | haiku |
| ~~`FS-DOCS-COMPATIBLELOOKIDS`~~ **done** | `compatibleLookIds` is documented as type-gated and deferred-only in three places; it is… | S4 | P2 | C1 | haiku |
| ~~`FS-DOCS-ELEMENT-KEY-INVARIANT`~~ **done** | `LookRowStore` cites `syntheticFixture.ts` as the record of the element-key invariant; th… | S4 | P2 | C1 | sonnet |
| ~~`FS-DOCS-SPEEDMASTERS`~~ **done** | CLAUDE.md §Speed Masters and two code comments describe the deleted 2..N split and a `Spe… | S4 | P2 | C1 | haiku |
| ~~`FS-DOCS-STALE-COMMENTS`~~ **done** | Batch: ~14 rationale comments naming callers, renderers or files that no longer exist | S4 | P2 | C1 | haiku |
| `FS-RES-ROUTES-CONVENTION` | `routes/` mixes route modules, settings-tab bodies, orphan redirects and a pure helper —… | S4 | P2 | C2 | sonnet |
| `FS-RES-RUNNER-DIR` | `components/runner/`'s `program/` and `run/` subdirs are named for deleted routes | S4 | P2 | C2 | sonnet |
| `FS-ARCH-SURFACES-PATTERN` | `store/surfaces.ts` streams four WS states through `useState`+`useEffect` instead of the… | S4 | P3 | C2 | sonnet |
| `FS-CHROME-BEAT-MAP-PRUNE` | Per-master beat subscribables are never pruned, so reconnects re-request beats nothing wa… | S4 | P3 | C2 | sonnet |
| ~~`FS-CHROME-BEAT-RESUBSCRIBE`~~ **done** | Folded into `FS-COORD-LEGACY-TEMPO`, as that item said to | S4 | P3 | C2 | sonnet |
| ~~`FS-COORD-PING`~~ **done** | Landed with backend D5 as `c6ee984`; one flatten constraint survives it — see the item | S4 | P3 | C1 | haiku |
| ~~`FS-COORD-STRICT-ENUMS`~~ **done** | Confirm-only: E4/E10 made the effect-enum write sites strict, and B3 lets the FX sheet se… | S4 | P3 | C1 | haiku |
| ~~`FS-DEAD-CSS`~~ **done** | `.scrollbar-thin` and its three webkit child rules serve a deleted palette strip | S4 | P3 | C1 | haiku |
| ~~`FS-DEAD-EXPORT-KEYWORD`~~ **done** | Ten symbols exported but used only inside their own module | S4 | P3 | C1 | haiku |
| ~~`FS-DEAD-PROTOTYPES`~~ **done** | `src/prototypes/` is 2.4k lines of shipped-and-done design scratch inside the compiled tree | S4 | P3 | C1 | haiku |
| ~~`FS-DOCS-CLAUDEMD-PROVENANCE`~~ **done** | CLAUDE.md's provenance section names `lookId`/`lookName`, replaced by `layerSource` | S4 | P3 | C1 | haiku |
| ~~`FS-DOCS-OUTOFSCOPE-COMMENT`~~ **done** | `RecordSkipReason.OUT_OF_SCOPE`'s comment names "palette routes" and claims they are the… | S4 | P3 | C1 | haiku |
| ~~`FS-DOCS-REF-RATIONALE`~~ **done** | `programmerValue.ts` and `useCellWriters` still teach the retired `ref:` grammar as current | S4 | P3 | C1 | haiku |
| `FS-DUP-CHANNEL-SLIDER` | Four copies of the labelled 0–255 channel slider row | S4 | P3 | C1 | haiku |
| `FS-DUP-MARKER-ROW` | The same cue separator renders two different ways depending on surface | S4 | P3 | C1 | sonnet |
| `FS-DUP-MINISTAGE-GEL` | `MiniStage.pickColour` is a third, divergent copy of the gel arm of the colour dispatch | S4 | P3 | C1 | haiku |
| `FS-DUP-TARGETKEY` | Three spellings of the `type:key` target encoding; the named owner has no users | S4 | P3 | C1 | haiku |
| `FS-PERF-LAYER-SIGNATURE` | `programmerLayers` stringifies the whole layer stack on every programmer notification | S4 | P3 | C1 | haiku |
| `FS-PERF-TRANSPORT-ALLOC` | `useShowTransport` builds a whole-stack signature string per render | S4 | P3 | C1 | haiku |
| ~~`FS-RES-ANON-CATCH`~~ **done** | Three bare `.catch(() => {})` where the codebase has a named helper for exactly that | S4 | P3 | C1 | haiku |
| ~~`FS-RES-LIGHTING-EDITOR-DIR`~~ **done** | `components/lighting-editor/` is a one-file directory named for a pre-Programmer era | S4 | P3 | C1 | haiku |
| ~~`FS-RES-LOOKREFVALUE-NAME`~~ **done** | `lookRefValue.tsx` is named for the retired `ref:` grammar its own doc says it can never… | S4 | P3 | C1 | haiku |
| ~~`FS-RES-PANECHROME`~~ **done** | `components/cues/paneChrome.tsx` justifies its home with consumers deleted in 2a | S4 | P3 | C1 | haiku |
| ~~`FS-RES-STRAY-CAPTURES`~~ **refuted** | Four `capture *.json` DMX debug dumps sit at the repo root | S4 | P3 | C1 | haiku |
| `FS-TEST-INDICATOR-LINK` | `ProgrammerIndicator`'s link-vs-inert split is unpinned (the CLAUDE.md path trap itself i… | S4 | P3 | C1 | haiku |
| `FS-TYPES-CLONE-COUNTS` | `CloneProjectResponse` drops four of the server's content counts, and the dialog discards… | S4 | P3 | C1 | haiku |
| `FS-TYPES-ISBUILTIN` | `FxDefinition.isBuiltin` has no producer and no consumer | S4 | P3 | C1 | haiku |
| `FS-WS-DEBOUNCE-TICK` | `debounceMapUpdates` keeps its interval alive one no-op tick past idle | S4 | P3 | C1 | haiku |

## 4. Sequencing and collisions

Findings are deliberately not a queue — but several groups collide, and a few orderings are
load-bearing. The completeness critic mapped these; verified and consolidated:

1. **Fade root cause before fade patches.** `FS-PERF-FADE-DISPATCH` (stop distributing a frame-rate
   value) comes before the memo barriers and prop-drill removals
   (`FS-PERF-FADE-IN-SHOWBAR`, `FS-PERF-PROGRAMMER-MEMO-BARRIER`, `FS-PERF-PROMPTBOOK-FADE-DRILL`,
   `FS-PERF-MOBILE-SHEET-FADE`) — barriers added first look effective while the wake-up remains, and
   some become dead work.
2. **The S1 snapshot fix rides the hook consolidation.** Land `FS-BUG-STALE-ROW-SNAPSHOT` *through*
   `FS-DUP-ROW-SUBSCRIPTION`'s extraction, or the fix must be written twice.
3. ~~**Collapse the aggregation before fixing inside it.**~~ — **spent** (`de1959e`).
   `FS-DUP-AGGREGATION` has landed, so `FS-BUG-PIXEL-CACHE-PERMUTATION` now has exactly one host:
   the members-vs-aggregates compare gap sits in `useGroupColourValues`' snapshot cache, and its
   siblings' uncompared fields (`values` on slider/setting, `members` on position) are still
   unconsumed — the "delete them instead" half of that item's fix is still on offer.
4. **One Layout/overview-panel refactor, not five patches.** `FS-PERF-COLLAPSED-PANELS`,
   `FS-PERF-CHANNELSOURCE-REBUILD`'s `isVisible` gate, `FS-DUP-OVERVIEW-TOGGLES`,
   `FS-BUG-FXROUTE-REGEX`, `FS-ARCH-IMPORT-CYCLE` and `FS-ARCH-LOCALSTORAGE-BOOT` all converge on
   `Layout.tsx` and the panels. ~~**spent**~~ — landed as one batch 2026-08-30, six commits
   (lighting-react `52660a6`, `8d3e906`, `6cdd030`, `934025d`, `e773197`, `fbeddfa`, plus
   `6365894`, `0ed1b31`, `4d7946a` and `1a9ebdb` for the cycle guard and the two review passes).
   Nothing outstanding.
5. **The `components/runner/` tree: move first or last, never interleaved.**
   `FS-RES-RUNNER-DIR` + `FS-RES-CUECARDEDITOR-DIR` touch files carrying
   `FS-PERF-MOBILE-SHEET-FADE`, `FS-DUP-MARKER-ROW` and `FS-DUP-TARGETKEY`; pick an order and rebase
   the rest. `FS-DUP-TARGETKEY` supersedes `FS-DEAD-EXPORTS`' `targetEquals` line.
6. ~~**The script-editor cluster is one work package**~~ — **spent**. All eight have landed:
   `FS-EDITOR-PROPTYPES-PHANTOM` and `FS-EDITOR-HIGHLIGHTONLY-PRESENCE` with the manifest pass
   (`0146759`), the remaining six 2026-08-30 as `a402981` (code) + `8c429b6` (pins). Nothing
   outstanding; see §5's cluster header for why those six are two commits and not six.
7. ~~**One manifest pass**~~ — **spent** (`6398257`, `0146759`). `FS-DEAD-DEVDEPS` and
   `FS-ARCH-ALERTDIALOG-DEP` landed as one commit, `FS-EDITOR-PROPTYPES-PHANTOM` as another. Note
   for whatever touches `package.json` next: the agent sandbox blocks writes to npm's cache, so
   `npm install` can **remove** packages but not add them — a manifest change that adds a dependency
   cannot be split across two commits, and cannot be undone once installed.
8. **`FS-ARCH-CURSOR-OWNERSHIP` is a decision before a cleanup** — it absorbs three
   would-be deletions that are not automatically safe (the two `standbyCueId`s carry different
   facts).
9. **Any `cueUtils` edit keeps the field-by-field pin shape** — never tidy it into a deep-equal.
   `FS-TEST-CUEUTILS-TRIGGERS` has landed (`771ce87`), so the pin now covers `triggers` as well as
   `layers`; the shape rule still binds whatever touches it next.
10. **The backend seam is no longer a wait — it is a queue** (§14). The backend sweep is complete,
    so nothing is gated; what remains is an ordering constraint and a re-measure.
    `FS-COORD-CUEEDIT-RETIRE`'s `force` senders must be deleted here **before** the backend drops
    the inert fields (`FS-BE-FORCE-FIELDS`). `FS-COORD-GROUPS-WS`'s ordering is spent: the
    `groupListChanged` prerequisite was refuted rather than filed — `fixturesChanged` already
    carries that signal — so the deletion landed without a backend half.
    `FS-PERF-BPM-INVALIDATION`'s tempo half went with D2, and `FS-PERF-PROVENANCE-REFETCH`'s
    re-measure is **spent**: the mechanism held at HEAD and the item landed 2026-08-30 with the
    `programmerRevision` protocol change (lighting-react `90565f7`, lighting7 `86dedaa`).

A reasonable dispatch order for what's left after those constraints: **the two live defects the
backend landing created first** — `FS-COORD-ADMIN-GATE`'s `Projects.tsx` gating (an operator gets a
bare 403 from two visible controls today) and `FS-COORD-WIRE-FIELD-DELETIONS` (client sites reading
fields the server has deleted) — then the independent P1 bugs (`FS-BUG-STALE-ROW-SNAPSHOT` through
`FS-DUP-ROW-SUBSCRIPTION` per constraint 2, `FS-BUG-RECONNECT-RESYNC`, `FS-BUG-TIMEDLAYERS-RENAME`,
`FS-BUG-PROGRAMMER-ERROR-DROPPED`, `FS-BUG-FADE-KEY-SNAPSHOT`, `FS-TEST-LOOKONLY-GATE`), then the
rest of the coordination queue (`FS-COORD-CUEEDIT-RETIRE`, `FS-COORD-PREVIEW-DEAD`,
`FS-COORD-NEW-BROADCASTS`, `FS-COORD-FXLIBRARY-PARAMS`), then the fade cluster, then
`FS-PERF-WS-SINGLE-PARSE` + riders, then the haiku dead-code/docs batches (cheap, high
signal-to-noise for future agents), then the structural moves, with the C3-sized items
(`FS-DUP-AGGREGATION`, `FS-ARCH-CURSOR-OWNERSHIP`, `FS-PERF-CODE-SPLITTING`) scheduled as their own
sessions.

The coordination items are unusually good first work regardless of order: they are mostly deletions
against a server that has already moved, so they shrink the tree, and each one closes a place where
this repo's code currently describes a protocol that no longer exists.

## 5. Bugs

Things a desk can observe doing the wrong thing (or saying nothing when it must speak).

### ~~`FS-BUG-CUESLOT-LIVENESS`~~ — done, lighting-react `fa36988` + `1cdadb7`
**"Is this cue/stack live?" is derived from the FX effect stream, so a rows-only cue reads as never
running** · S1 · P1 · C2 · fable
`src/store/cues.ts`, `src/components/CueSlotOverviewPanel.tsx`

Done, with one correction to the fix sketch below: liveness reads `CueStack.activeCueId`, not
`projectProgramState.activeStackId` — the program state is only the show transport's playhead, and
a pad-activated stack is live without ever being the playhead. On-rig re-verify still owed.

`useActiveCueIds` / `useActiveCueStackIds` build their sets from `useFxStateQuery().activeEffects[]`
— but a cue made of property assignments and effect-free Look layers creates **no** `FxInstance`
(`applyCue` publishes cooked rows through `engine.setCueAssignments`, which never reaches the
`fxState` frame). `CueSlotOverviewPanel.handleSlotTap` branches on exactly those sets, and the pad's
`isActive` does too. So on a rows-only cue the slot never lights and every tap **re-fires** instead
of stopping; on a stack slot the tap calls `activateStack`, and `POST /cue-stacks/{id}/activate` has
no already-active short-circuit — a press meant to stop a running effect-free stack throws the
playhead back to cue 1 on a live rig. Static cues are the common case, not the exotic one. This is
the identical defect class CLAUDE.md records for the old Look pads (`FxInstance.presetId ===
lookId` "could never see a rows-only Look"), fixed there by reading the layer stack — the cue-slot
panel was left behind on the effect stream. `useStackActiveCueIds` is a third copy of the derivation
with no callers at all.

**Fix**: read liveness from the authoritative playhead — `projectProgramState.activeStackId` for
stack slots, the `cueRunStateChanged`-patched `CueStack.activeCueId` (or a cue-level equivalent) for
cue slots — and delete/re-point the `activeEffects`-derived hooks so no surface answers "is it on
stage" from the effect stream again. This changes what a tap does on a live rig: re-verify fire/stop
and activate/deactivate against a cue with no effects and one with effects. Note the backend half in
§14 (`FS-BE-STOP-ROWSONLY`): today `POST /cues/{cueId}/stop` cannot clear a rows-only cue fired
outside its stack, so the client fix alone makes the pad *look* right without making stop work.

### ~~`FS-BUG-STALE-ROW-SNAPSHOT`~~ — **landed**
**Per-row programmer snapshot cache goes stale across an off→on subscription cycle**,
lighting-react `0644a63` · S1 · P1 · C2 · fable
`src/components/fixtures-list/useRowOwnership.ts`, `src/components/fixtures-list/useScopedRowValues.ts`

Both `useRowOwnership.getSnapshot` and `useLocalRowValues.getSnapshot` cache on
`(cells identity, versionRef.current)`; `versionRef` only advances from notifications received
*while subscribed*, and both hooks are switched off by being handed `EMPTY_CELLS` (`ownershipCells`
empties on entering layer scope, `localCells` in any scope but Local). Nothing invalidates on
re-subscribe (`subscribeToKey` doesn't fire on registration), `cells` identity survives a scope
switch, and the grid-never-remounts rule guarantees the hook instance (and its stale ref) survives
too. **It does not self-heal**: `changedKeys` diffs against the api's own maps, which kept advancing
while the row was off, so the change that happened during the off window is never re-announced.
Reproduced with a live-subscription renderHook harness: Local → (template chip pressed from Output,
a second tab busking, a locate, an Include) → back to Local shows the pre-switch grid. It is S1
rather than S2 because the ownership snapshot carries `staged`, which `applyStagedValue` renders as
the cell's *value* under blind — and the cell editors seed from that value, so an operator nudging a
stale cell commits a value derived from a lie.

**Fix**: invalidate the memoised snapshot whenever the subscription set is (re)registered — bump the
version inside `subscribe`, or fold `subscribedKeys` into the cache key. Do it once by landing
`FS-DUP-ROW-SUBSCRIPTION` first (the bug exists identically in both copies of the mechanism). Must
not break: the cached snapshot identity (stops unrelated provenance pushes re-rendering the row),
the per-`(target,property)` subscription split, empty-cells-means-off, and the blind-transition
filter.

Landed as *both* remedies, not one: the subscription set's identity joined the cache key (heals
off→on during the re-entry render itself, no stale frame), and `subscribe` also bumps the version
on registration — review found the two close different windows, the latter covering a notification
landing between a row's render and its passive-effect subscription under a time-sliced render.

### ~~`FS-BUG-TIMEDLAYERS-RENAME`~~ — **landed**
**Record's "timed effect(s) kept" note is dead: backend renamed `timedPresetApplications` →
`timedLayers`**, lighting-react `d01f0b7` · S2 · P1 · C1 · sonnet
`src/store/programmerOps.ts`, `src/components/programmer/RecordSheet.tsx`

`ProgrammerPreservedCounts.timedPresetApplications` (non-optional) mirrors a field
`routes/programmerRecord.kt` renamed to `timedLayers`. The wire value is always `undefined`, so
`RecordSheet`'s sum is `NaN`, `NaN > 0` is false, and the reassurance "N timed effect(s) kept" can
**never render** — even when a real `timedAdHocEffects` count arrives. The whole point of that
panel ("a silent Record reads as everything went in") is silently defeated for this arm; tsc can't
see it and no test constructs a real response.

**Fix**: rename the field to `timedLayers` and fix the sum. kotlinx has defaults on all five counts,
so prefer `?? 0` at the read site over trusting non-optionality. Keep both counts in the one
sentence — that wording is what tells an operator a timed child was preserved rather than dropped.

Confirmed at backend HEAD after the sweep: `programmerRecord.kt:61-64` still sends `timedLayers` and
still carries the "Was `timedPresetApplications`" breadcrumb that made this findable. No backend
half; this is a one-repo fix.

### ~~`FS-BUG-FXROUTE-REGEX`~~ — **landed**
**`isFxRoute` is an unanchored prefix match and fires on `/fx-library`, locking the effects panel
open**, lighting-react `8d3e906` · S2 · P1 · C1 · sonnet
`src/Layout.tsx`, `src/hooks/useEffectsOverview.ts`

`/\/projects\/\d+\/fx/.test(pathname)` has no trailing boundary, so it matches
`projects/:id/fx-library` as well as the busking grid. Opening the FX Library force-opens the
effects overview panel and leaves its toolbar toggle dead with a tooltip blaming "the FX view" — a
page that isn't it. This is the `startsWith` trap CLAUDE.md documents for `pathMatch` and
`ProgrammerIndicator`, in a third place that never got the segment-aware treatment.

**Fix**: match the whole segment (reuse `lib/navMatch.ts`), retitle the tooltip, and confirm the
lock is still wanted at all now FX is a band of the programmer rather than a view. Coordinate with
the Layout cluster (§4).

Landed with the lock **kept**: `/projects/:id/fx` is still a real route with its own `active-only`
nav entry, so there is still a page that wants the panel open. Grew by one line: the locked tooltip
left ` effects overview` outside its ternary, so it read "…in FX view) effects overview" whenever it
fired.

Landed **twice**, which is the interesting part. The first attempt did what this item says — reuse
`navMatch.ts`, via a new exported `pathHasSegment` — and the review caught that a segment match
fixes `/fx-library` but then fires on `/projects/:id/programmer/fx`, a route the unanchored regex
never hit. So the commit that tightened the guard widened it elsewhere. The fix is to stop matching
the path here at all: `mostSpecificActiveId(navItems, pathname) === 'fx'` asks the nav the same
question the sidebar asks, and longest-match resolves both cases by construction (`4d7946a`). Three
cases are pinned in `navMatch.test.ts`. `pathHasSegment` stays — it is what `mostSpecificActiveId`
is built on, and `ProgrammerIndicator` now shares it.

### ~~`FS-BUG-PROGRAMMER-ERROR-DROPPED`~~ — **landed**
**`programmer.error` frames are delivered to zero subscribers, so a busk that lands nowhere is
silent**, `71fdb9a` · S2 · P1 · C2 · sonnet
`src/api/programmerWsApi.ts`, `src/store/errorToastMiddleware.ts`

`subscribeToErrors` exists, is documented ("so callers can surface a toast"), and has no production
caller — its only references are the declaration, the test mock, and its own test. The backend
really sends these: unknown fixture/group, unparseable value, `addLayer` with an unresolvable
source, and — most visibly — "Property X on Y resolves to no DMX channels". So the slider moves,
the rig doesn't, and nothing is said. This is the exact failure `errorToastMiddleware`'s deny-list
design exists to prevent for REST; the WS write path is the one place a failed operator action is
invisible by default.

**Fix**: one production subscriber (mounted once, beside the other bridges) raising
`toast.error(message)` with a stable sonner id so a drag's burst collapses to one toast. Don't route
it through `errorToastMiddleware` (that keys on RTK Query actions). First check whether the backend
unicasts the frame to the acting socket — a second tab must not toast for someone else's mistake.

### ~~`FS-BUG-FADE-KEY-SNAPSHOT`~~ — **landed**
**`PROGRAMMER_FADE_KEY` is shared by key but not by value — ShowBar's Blind uses a mount-time
snapshot of the fade**, `53561c0` · S2 · P1 · C2 · sonnet
`src/hooks/usePersistentState.ts`, `src/lib/programmerFade.ts`, `src/hooks/useShowBarProps.ts`,
`src/components/programmer/ProgrammerActionBar.tsx`

`usePersistentState` reads localStorage once in its `useState` initialiser, with no `storage`
listener and no cross-instance sync. The action bar's fade picker and `useShowBarProps`'s `onBlind`
are two independent instances of the same key, mounted simultaneously on `/programmer` — so moving
the picker never reaches the Blind button for the rest of that visit, and Blind fades with the
stale (typically 0 = snap) value. `lib/programmerFade.ts`'s written rationale ("the value had to
become addressable … or blinding would have started snapping") is currently false in practice.
Bounded to one page visit (any navigation re-reads), which is why this is S2 not S1 — but the stale
press is precisely mid-session, when the operator just chose a fade.

**Fix**: one shared source of truth rather than two snapshots — a module-level
`useSyncExternalStore` singleton (the shape `useVisSource.ts` already uses), or teach
`usePersistentState` to notify sibling instances of a key. Blind must still read the fade at press
time in ms, `Number(fadeMs) || 0` must keep answering 0 for junk, and the persisted key stays
`programmer.fadeMs`. Pin with a test: picker changed → Blind uses the new fade without a remount.

Grew in the landing: review found the new store was a second copy of `useVisSource.ts`'s
boilerplate — the same drift risk this item exists to remove — so both now build on a shared
`lib/syncStore.ts` `createSyncStore<T>` factory, and `usePersistentState`'s doc states its
one-mounted-instance-per-key rule and points at it.

### `FS-BUG-PIXEL-CACHE-PERMUTATION`
**`useGroupColourValues` never compares per-member colours, so a colour chase across a pixel bar
freezes its segments** · S2 · P2 · C2 · sonnet
`src/hooks/useGroupPropertyValues.ts`, `src/components/fixtures/fixtureAppearance.tsx`

The snapshot cache compares only aggregates (avg R/G/B/W/A/UV, `isUniform`, beam fields) — every
one permutation-invariant over `members` — while `members` is exactly what `MultiPixelAppearance`
maps into the `PixelSegment[]` that the 2D plot and mini-stage draw. A pattern that *permutes*
colour across a multi-pixel fixture (a chase — the canonical reason the fixture exists) leaves all
aggregates equal, hits the cache, and freezes the per-pixel segments while the overall swatch reads
correctly. Narrower than it sounds (the whole colour multiset must be preserved within one 33 ms
batch; the 3D path is unaffected because it calls `computeGroupColourValues` imperatively), but the
trigger is a real effect shape. The same compare gap sits latent in `useGroupSliderValues` /
`useGroupSettingValues` / `useGroupPositionValues`, whose uncompared fields have no consumer yet.

**Fix**: fold a cheap per-member signature into the existing members pass and compare it; do the
siblings too, or delete their uncompared result fields so the trap can't be armed later. Must stay a
value comparison — the stage views rely on identity-stable snapshots to avoid re-rendering on
equal-but-fresh data. **Sequencing**: this code is slated for consolidation by
`FS-DUP-AGGREGATION`; collapse first or fix inside the surviving implementation (§4). The gap round
independently re-verified this from the stage side and sharpened the observable: the 3D canvas
animates the chase correctly (its imperative copy has no such cache) while the 2D plot and the DOM
marker freeze — three surfaces disagreeing about one rig.

### ~~`FS-BUG-RECONNECT-RESYNC`~~ — **landed**
**Post-reconnect cache resync is a hand-maintained 15-tag list against 47 tagTypes; 20 tags have no
resync path at all**, lighting-react `928fc5c` · S2 · P1 · C2 · sonnet
`src/store/status.ts`, `src/store/restApi.ts`, and the per-bridge `open` branches in `src/api/*.ts`

Two independent reconnect mechanisms exist and neither is authoritative: `status.ts` invalidates a
literal 15-tag list on CLOSED→OPEN (under a comment claiming "all REST caches"), 22 tags are covered
by their own bridge's `open` branch, and **twenty have no path at all**: `Cue`, `Patch`,
`UniverseConfig`, `ProgramState`, `ControlSurfaceType`, `SurfaceBinding`, `PerfMidi`, `FxLibrary`,
the five `CloudSync*`, both `OAuth*`, `Update`, `AuthSessions`, both `ResetToken*`, `DeviceLogin`.
(Several of those have live WS invalidation paths — what they lack is the reconnect one.) Four
bridges have no `open` branch at all; `surfacesApi`'s re-sends state but never notifies
`bindingsChanged`; and there is no RTK Query safety net (`refetchOnReconnect` is opted into by two
hooks, and it keys off browser online/offline, which doesn't fire for a backend restart). The
observable is a real desk failure: after a laptop sleep or a lighting7 restart, `ProgramState` —
patched only by `updateQueryData` from `showChanged`, never invalidated — keeps its stale
`activeStackId`, so a show started elsewhere leaves this tab's transport greyed out (GO does
nothing) with no lock chrome, and a show stopped elsewhere leaves an armed-looking transport. Then:
stale patch list on the fixtures table and all three stage views, stale MIDI bindings, a stale
Updates tab.

**Fix**: make the resync one derived thing — export the tagTypes array from `restApi.ts` and have
the CLOSED→OPEN handler invalidate all of it minus a small commented exclusion set (`Auth` at
minimum: `authWsApi`'s `seenOpen` first-open guard deliberately stops the initial connect racing
AuthGate). Invalidation only refetches subscribed queries, and the role-gated families already pass
`skip: !isAdmin`, so the cost is bounded. Then delete the now-redundant pure-invalidation `open`
branches, keeping the ones that also re-send a socket state request; add a test that fails when tag
48 is in neither the reconnect list nor the exclusion set.

Grew in the landing: review objected that firing all 46 tags in one tick lands exactly when the
backend has just restarted, and lighting7 serves REST from a single pooled SQLite connection
(`maximumPoolSize = 1`), so the burst serialises behind a warming show. The dispatch is therefore
debounced 250 ms — a flapping link resyncs once, not once per transition — and goes out in waves of
eight 150 ms apart, operator-visible caches (`ProgramState`, `Patch`, the cue lists) first, with a
mid-sequence drop abandoning the rest. Coverage stays derived: the test pins that the waves
concatenate to exactly the resync set, so a new tag cannot fall out by landing in no wave. The
timings are code-read guesses, so the landing also files `FU-MANUAL-RECONNECT-RESYNC` in
`manual-validation.md`.

### ~~`FS-BUG-CUE-TAG-STALE`~~ — **landed**
**The `Cue` tag has no WS invalidation on any path, so an expanded cue's composed values go stale on
a healthy socket**, lighting-react `49fe1d3` (+ lighting7 `6525ad6`) · S3 · P2 · C2 · opus
`src/store/cues.ts`, `src/store/looks.ts`, `src/store/templates.ts`

`projectCueCooked` — the read behind `CueValueGrid` — is tagged `Cue`, and nothing invalidates `Cue`
from any socket frame (the cues bridge fires `CueList` only; the looks/templates bridges don't carry
it; it is absent from the reconnect list). The only writers are this client's own mutations, so the
doc comment "a Look edit that republishes it refetches this too" is true only in the tab that made
the edit — where it doesn't matter. Two tabs on `/show` with a cue expanded: one retunes a Look the
cue layers, the rig moves, the other tab's read-only grid shows the pre-edit values indefinitely,
and reconnect doesn't clear it either. **Fix** (layered): add `Cue` to the derived reconnect list
(`FS-BUG-RECONNECT-RESYNC`); have the looks/templates bridges also invalidate `Cue` (both are
CRUD-cadence, affordable); the full cross-client fix needs a backend frame — `republishForSourceEdit`
broadcasting its `cuesRepublished` id list (§14, `FS-BE-CUES-REPUBLISHED-FRAME` — still owed; the
field exists on the REST responses and nowhere on the bus) — and
the `projectCueCooked` doc comment corrected to say which edits actually refetch it.

Landed as all three layers, backend half included: the reconnect layer needed nothing (`Cue` reached
`RECONNECT_RESYNC_TAGS`, in the first wave, with `FS-BUG-RECONNECT-RESYNC`), the two CRUD bridges now
carry `Cue`, and `FS-BE-CUES-REPUBLISHED-FRAME` was written rather than left owed.

Landed wider than the **Fix** on two points, both deliberate. The frame carries **every cue layering
the edited record**, not the `cuesRepublished` list the item names: that list is the *live* cues whose
Layer 4 rows were replaced, but `/cues/{id}/cooked` composes on read, so a **dark** cue layering the
Look reads stale from the identical edit. Because the two sets differ, the frame is named
`cuesRecomposed` — during review the two names a line apart in `republishForSourceEdit` were read as
one set twice. Grew in the landing: the two bridges also send `CueList`, the pairing every
cue-affecting write in the client uses, since a cue's list entry carries `layers[].source.name` and a
rename elsewhere left the old one cached.

### ~~`FS-BUG-WS-SEND-DROPPED`~~ — **landed**
**Every WS write is silently dropped while the socket is down — programmer sets, Blind, blackout,
park included**, `8d83277` · S3 · P2 · C2 · opus
`src/api/internalApi.ts` and every WS write path

`send` is `if (ws.readyState === OPEN) ws.send(data)` — no return value, queue, log, or toast — and
the reconnect backoff reaches 30 s, so the drop window after a blip is seconds to half a minute.
Because programmer state is server-driven, the UI simply doesn't move; the same failed backend that
toasts a REST edit is total silence for a Blind press. No stale optimistic state results (the value
hooks read the live source), which is why this is S3 hardening rather than S2. **Fix**: `send`
returns false when not OPEN; operator-gesture call sites surface it through the same toast surface
REST uses, and controls that promise an immediate rig change (blackout, Blind, cell editors, park)
disable while disconnected. **Do not build a replay queue** — flushing a minute-old blackout on
reconnect moves the rig behind the operator's back; the bridges' idempotent state re-requests are
the correct catch-up.

Grew in the landing: the disable now covers three sibling surfaces this finding doesn't name but
that write to the same programmer over the same socket — the fixture-detail and group property
sliders, the busking property pads, and the speed-master BPM/TAP tiles. Leaving them live while the
grid was inert was incoherent. The review also found the grid's `pointer-events-none` was
mouse-only (the cell triggers are tabbable, so Tab-then-Enter walked past it), that the three
WS-backed RTK mutations (`parkChannel`, `unparkChannel`, `updateChannel`) fabricated success for a
frame that never left the browser, and that MIDI Learn spun forever on a dropped `beginLearn`.
`FU-MANUAL-WS-SEND-DROPPED` in [`manual-validation.md`](manual-validation.md) carries the rig check,
including the one thing no test covers: that nothing queued fires on reconnect.

### `FS-BUG-3D-PLACEHOLDER`
**The imperative 3D colour copy has no placeholder arm — an unmatched patch draws as a fully-lit
warm-white lamp** · S3 · P3 · C1 · sonnet
`src/components/stage3d/FixtureModel.tsx`, `src/components/fixtures/fixtureAppearance.tsx`

The shared dispatch renders a missing fixture record as a deliberate dim-grey placeholder; the 3D
`ColourSync` falls through to `FixedColourBeamSync '#fff8d5'` with dimmer factor 1 — so an unmatched
patch, and every patch during the window before the fixture list resolves, paints full warm white
while the 2D plot and markers correctly show unlit placeholders. Two default-colour literals are
also hard-coded where `DEFAULT_FIXTURE_COLOUR` is importable. **Fix**: add the placeholder arm (a
`PlaceholderBeamSync` with no channel subscriptions, respecting the fixed-hook-set-per-branch rule
this dispatch depends on), import the shared constant at both literals, extend
`FixtureModel.test.ts` with the fixture-undefined case.

### The script-editor cluster

The Kotlin editor subsystem (`src/kotlinScript/`, `src/components/scripts/`, plus the FxLibrary and
CueTriggerEditor mounts) had **zero findings and zero tests** before the gap round; it turned out to
hold two real bugs and a band of fragility. All eight items below touch the same two files —
dispatch them as **one work package** (suggested: one sonnet agent, `FS-EDITOR-LIFECYCLE` first,
since its imperative handle is what the two bugs' fixes need). **Spent** — all eight have landed.

The last six landed 2026-08-30 as **two** commits, not six: `a402981` for the five code items and
`8c429b6` for the pins. They interlock inside `component.mjs` — the echo suppression Reset needs
has nothing to suppress until the change channel is undebounced, and the handle it writes through
does not exist until `getInstance` stops clobbering the caller's — so a per-item split would have
meant committing intermediate states that never existed and were never checked. Same argument the
`0146759` pair below already carries.

Two of the eight left the cluster early, in the manifest pass:
`FS-EDITOR-PROPTYPES-PHANTOM` (which §4 constraint 7 always assigned there) and
`FS-EDITOR-HIGHLIGHTONLY-PRESENCE`, which it could not be separated from. The remaining six are
still one package, and `component.mjs` now has `component.d.mts` beside it — any prop the lifecycle
and debounce work adds to the wrapper needs declaring there too.

### ~~`FS-BUG-EDITOR-RESET-NOOP`~~ — **landed**
**A changed `value` prop can never reach a live playground editor, so ScriptForm's Reset silently
does nothing**, `a402981` (+ `8c429b6`) · S2 · P1 · C2 · sonnet
`src/kotlinScript/component.mjs`, `src/components/scripts/ScriptForm.tsx`

`componentDidUpdate` only re-runs `initPlayground()`, which early-returns once the widget has
stamped the node initialized; the push-new-value branch is commented out, and the React key contains
nothing that moves on a content change. So Reset sets `editCode` back while the editor keeps the
edited text — and worse, the next keystroke's debounced `onChange` hands back the whole current
body, re-syncing `editCode` to what Reset was supposed to discard. Same latent hazard for any future
revert in `EditFxDefinitionSheet`, and theme-flip remounts discard cursor/undo for the same reason.
**Fix**: a real controlled path in `componentDidUpdate` — write through the instance
(`instance.codemirror.setValue(body)` with the **folded body only**, or `instance.update({code})`),
compare before writing and suppress the echo `change`. Then pin Reset with a test.

Landed as a controlled path in `ScriptEditor`, not in `componentDidUpdate`: the compare has to be
exact, and the wrapper's `value` prop is the *wrapped* source while `onChange` reports the body, so
the marker arithmetic stayed where it already lives. The wrapper speaks the body on both sides.
Grew in the landing, from the review: `EditFxDefinitionSheet` stored `edits.script` only when it
trimmed unequal to the saved script and collapsed to `undefined` otherwise — harmless while the
editor was uncontrolled, but it reverted the operator's text and caret mid-edit once it wasn't.
The field now holds the editor's text verbatim and `hasChanged` asks the question instead. Also
fixed: a `script.script` that changed while the widget was still coming up was dropped for good,
since the effect does not re-run — the handle's arrival syncs too.

### ~~`FS-BUG-EDITOR-SILENT-READONLY`~~ — **landed**
**A failed `/api/script-editor/versions` drops every editor to read-only and the frontend neither
detects nor reports it**, `a402981` (+ `8c429b6`) · S2 · P2 · C2 · sonnet
`src/kotlinScript/component.mjs`, `src/components/scripts/ScriptEditor.tsx`

kotlin-playground's version probe resolves `undefined` on failure and falls back to
`highlightOnly`; the backend's own doc names this exact failure mode, but the wrapper discards the
promise `playground()` returns and no mount site passes `onError`. `/api/script-editor` sits behind the
auth gate and the desk restarts for new routes, so a 401 or a restart window is a live path — and it
bypasses the 401→Auth-invalidation mechanism because it's a raw fetch. The operator sees a
normal-looking editor that refuses every keystroke. **Fix**: keep the `playground()` promise, treat
zero-instances/rejection as fallback, render an inline "language service unreachable — editor is
read-only" message with a Retry that bumps a key nonce (the widget nulls its version cache on
failure, so a remount genuinely refetches). Wire `onError` while there.

One correction to the mechanism, from the widget's source: the failed probe does **not** resolve
with zero instances. It resolves with one, built `new ExecutableCode(node, {highlightOnly: true})`
— two arguments, no event functions — so the detection that actually works is "did `getInstance`
call back?", not "how long is the array?". Grew in the landing: `onError` raises the same banner
rather than only being wired, so the mid-session case (a desk restart under a live editor) reports
like the mount-time one.

### ~~`FS-EDITOR-LIFECYCLE`~~ — **landed**
**Playground instances are never destroyed on unmount, and `window.playgroundInstance` is a
write-only global that also clobbers the `getInstance` prop**, `a402981` (+ `8c429b6`) · S3 · P3 · C1 · sonnet
`src/kotlinScript/component.mjs`

The widget exposes `destroy()`; the wrapper never calls it, so every sheet close, tab switch and
theme flip abandons a live CodeMirror. `initPlayground` assigns `window.playgroundInstance`
(read by nothing) *and* overwrites any caller-passed `getInstance` — which is why no consumer can
reach the live editor value today. **Fix**: `componentWillUnmount` → `destroy()` (try/catch); replace
the global with a pass-through to `this.props.getInstance`. This unlocks the imperative handle the
two bugs above and the debounce item below need.

### ~~`FS-EDITOR-DEBOUNCE-DIRTY`~~ — **landed**
**onChange is debounced 500 ms with no flush, so the unsaved-changes guard and every
Compile/Run/Save read a copy that trails the editor**, `a402981` (+ `8c429b6`) · S3 · P1 · C2 · sonnet
`src/kotlinScript/component.mjs`, `src/components/scripts/ScriptForm.tsx`, `src/routes/FxLibrary.tsx`,
`src/components/cues/CueTriggerEditor.tsx`

Trailing-edge debounce, no maxWait, no blur flush, and no read path to the live value — so
type-then-Escape inside the window closes the sheet with no "Discard changes?" and loses the tail.
Downgraded from S2 because the window is ≤500 ms of idle and every consumer is a mouse click that
usually exceeds it — but the guard exists precisely for the hurried case. **Fix**: expose
`getValue()` through the un-clobbered `getInstance` handle; dirty checks and the send handlers read
through it (or at minimum flush on wrapper blur). Pin the Escape-after-typing case.

Landed by bypassing the debounce rather than reading around it: the wrapper no longer passes
`onChange` to the widget at all, and listens to CodeMirror directly, so every consumer's state is
never stale and no call site changed. The read-through-a-handle shape needed a second immediate
channel anyway — the Sheet reads `unsavedChanges` from its render closure, so a flush inside the
Escape keydown lands a render too late, and the guard can only work if the form already knows.
The widget's own debounced handler still drives highlighting and completion, untouched.

### ~~`FS-EDITOR-HIGHLIGHTONLY-PRESENCE`~~ — **landed**
**Read-only works only because React omits an `undefined` attribute — `highlightOnly="false"` would
make every editor read-only**, `0146759` · S3 · P2 · C1 · sonnet
`src/components/scripts/ScriptEditor.tsx`, `src/kotlinScript/component.mjs`

The widget tests attribute *presence*, not value; the natural tidy-up `String(readOnly)` flips every
editor to read-only with no type error and no failing test, and the whole prop surface is unchecked
(`@ts-expect-error`, `allowJs: false`). **Fix**: a small `component.d.ts` typing the props actually
used (drops the `@ts-expect-error`), `highlightOnly` typed so the falsy case can only be
`undefined`, and a call-site comment naming the presence semantics (same note for `autocomplete`
and `matchBrackets`).

Landed with `FS-EDITOR-PROPTYPES-PHANTOM` (see there for why they are inseparable), as
`component.d.mts` / `index.d.mts` — TypeScript resolves a `.mjs` import to `.d.mts`, not `.d.ts`.
One correction to the **Fix**, verified against `kotlin-playground`'s own source: only
`highlightOnly` is presence-tested (`hasAttribute`, special-casing the literal `"nocursor"`).
`autocomplete`, `matchBrackets`, `lines`, `highlightOnFly` and `autoIndent` are compared
`=== "true"`, so `"false"` genuinely turns *those* off — the call-site comment names which rule
applies to which rather than claiming presence semantics for all three. The landing also hoisted the
`<ReactKotlinPlayground>` element out of `ScriptEditor`'s compact/non-compact ternary, which had
mounted it byte-for-byte twice; `FS-EDITOR-DEAD-BRANCH` still owns deleting the dead arm.

### ~~`FS-EDITOR-DEAD-BRANCH`~~ — **landed**
**ScriptEditor's entire non-compact branch is unreachable and duplicates the widget mount verbatim**,
`a402981` · S3 · P3 · C2 · sonnet
`src/components/scripts/ScriptEditor.tsx`

All four mount sites pass `compact`, so the name Card, footer actions, `onCompile`/`onRun` props and
their state are unreachable — and the surviving ternary renders a byte-identical mount in both arms.
**Fix**: drop the prop and branch, collapse the ternary, note the component is deliberately
editor-only (the callers' own Compile/Run buttons differ in label, size and mutation and stay).

`ScriptEditorScript` keeps its `name`, now unread here: it is the component's public DTO across
five call sites and describes what is being edited. `LazyScriptEditor`'s fallback lost its
`compact` arm with it.

### ~~`FS-EDITOR-PROPTYPES-PHANTOM`~~ — **landed**
**`prop-types` is a phantom dependency of the wrapper, and React 19 ignores what it declares**,
`0146759` · S3 · P2 · C1 · haiku
`src/kotlinScript/component.mjs`, `package.json`

Resolved only by hoisting through `react-qr-code`/`eslint-plugin-react`; React 19 no longer
validates propTypes, so the ~40-line block plus `cloneProps` is inert (and one entry is actively
wrong per the presence finding). **Fix**: delete the propTypes machinery; keep the shape as the
`.d.ts` above instead. Do **not** add `prop-types` to package.json. Third item for the one manifest
pass (`FS-DEAD-DEVDEPS`, `FS-ARCH-ALERTDIALOG-DEP`).

Grew in the landing: it took `FS-EDITOR-HIGHLIGHTONLY-PRESENCE` with it, and could not have been
landed without it. Adding declarations makes `ScriptEditor`'s `@ts-expect-error` an
unused-directive error, so the `.d.ts` and the deletion are one change. Landed in its own commit,
separate from the manifest pass.

### ~~`FS-TEST-EDITOR-PINS`~~ — **landed**
**The documented cross-type poisoning landmine is safe by construction today — and nothing pins any
of what makes it safe**, `8c429b6` · S3 · P2 · C2 · sonnet
`src/components/scripts/`, `src/kotlinScript/`

Verified concretely: the module-global `server` is only ever assigned the one hardcoded
`"/api/script-editor"`, no page can mount two editor types at once (FxLibrary's three sheets are
exclusive branches of one Sheet), and the wrap→unwrap round-trip is exact rather than lucky. None of
it is pinned — no test asserts the marker line comes first, the body survives byte-for-byte, the
`server` assignment is unique, or that `.kotlin-editor` (which the Run-button-hiding CSS keys on) is
on the wrapper. **Fix**: `ScriptEditor.test.tsx` over a mocked widget pinning the value string per
type, the round-trip using the widget's own arithmetic, the wrapper class, the
`readOnly`-omits-attribute rule, and a grep-guard that `"/api/script-editor"` is assigned exactly once.

Landed with `src/test/kotlinPlaygroundFake.ts`, which fakes the widget rather than mocking it away
— the behaviours the wrapper reasons about are the widget's own, and are written down nowhere but
its source. It paid for itself at once: the fake resolves `getInstance` synchronously, stricter
than the real widget, and caught a teardown during a pending init discarding the instance that
init was about to produce (the StrictMode double-mount path). The grep-guard walks the tree
through `import.meta.glob(..., '?raw')` rather than `node:fs`, which the browser-targeted tsconfig
has no types for. `ScriptForm.test.tsx` carries the two end-to-end pins the sibling items asked
for: Reset, and Escape-after-typing.

## 6. Performance

### The fade-progress cluster

Five findings share one root cause: **fade progress travels by Redux dispatch and by prop**, against
the design rule CLAUDE.md already states ("the fade value is never a prop — each row reads its own
through `useCueFade`"). Fix the source first (`FS-PERF-FADE-DISPATCH`), then the distribution
(`FS-PERF-FADE-IN-SHOWBAR`, `FS-PERF-PROMPTBOOK-FADE-DRILL`, `FS-PERF-MOBILE-SHEET-FADE`), then the
memo barrier (`FS-PERF-PROGRAMMER-MEMO-BARRIER`) — barriers added first would look effective while
the frame-rate wake-up is still there, and some would become dead work.

### ~~`FS-PERF-FADE-DISPATCH`~~ — **landed**
**Fade animation dispatches into Redux at 60 Hz; dev builds deep-scan four slices per frame**,
lighting-react `055a0c0` · S3 · P2 · C3 · fable
`src/hooks/useRunnerAnimation.ts`, `src/store/runnerSlice.ts`, `src/store/index.ts`

`useRunnerAnimation` dispatches `setFadeProgress` once per rAF for the whole fade. In production the
per-dispatch cost is genuinely small (stable-null selectors, shallow-compared query hooks) — the
real cost is the re-render of everything subscribing to `selectStackRunner`, whose identity changes
per frame under Immer (see the cluster). In dev it is worse: `immutableCheck`/`serializableCheck`
exclude only `restApi.*`, so `runner`, `selection` (per-list row-id sets), `saveStatus` and
`editLock` are deep-traversed twice per dispatch, 60×/s — profiling in dev is dominated by
invariant middleware.

**Fix**: keep `(fadeStartMs, durationMs, cueId)` in the slice — written once per transition — and
let `useCueFade` run its own rAF for the single fading row, or throttle the dispatch to display
cadence (~10–15 Hz; the 0.1 s countdown can't tell). `startElapsedMs`/`serverTransition` mid-fade
join semantics, `markDone` firing exactly once, and `cancelAnimations` must behave identically;
`useShowTransport.test.tsx` and `ProgramView.test.tsx` are the guardrails. Also widen the dev-mode
`ignoredPaths`.

Grew in the landing: review found `markDone`'s kept-at-1.0 fade value was unreachable state (every
consumer gates on `activeCueId` first), so `markDone` now clears both descriptors; and `startFade`
clears a stale auto descriptor, closing a pre-existing hole where an unmount mid-countdown left the
countdown pinned at 100% across a remount. Rig check filed as `FU-MANUAL-FADE-DISPATCH`.

### ~~`FS-PERF-FADE-IN-SHOWBAR`~~ — **landed**
**Fade progress is prop-drilled into the ShowBar, re-rendering the chrome (and all of
`ProgrammerBody`) at frame rate**, lighting-react `8533c21` · S2 · P1 · C3 · fable
`src/hooks/useShowBarProps.ts`, `src/hooks/useShowTransport.ts`, `src/components/ShowBar.tsx`,
`src/routes/ProgrammerPage.tsx`

`useShowBarProps` subscribes to the runner slice via `useShowTransport` and returns `fadeRemainMs`,
so every host re-renders ~60×/s during any fade. On `/programmer` there is no memo boundary at all:
the page re-renders `ProgrammerBody` → scope providers → action bar → grid → virtualizer per frame,
while channel frames are also landing — precisely in the fix-it-during-a-running-show case the bar
was put on that page for. The chrome below `ProgramView`'s memo (`ShowBar` → `SpeedMasters`, all
arms → `ProgrammerIndicator`) reconciles every frame on `/show` too.

**Fix**: stop returning a frame-rate value from `useShowBarProps` — let the bar's FADING badge read
the runner through a leaf hook quantised to ~10 Hz, and memoize the bar and `SpeedMasters`. Must not
remount `ProgrammerGrid` (grid-never-remounts rule, pinned by `ProgrammerPage.test.tsx`'s
`gridMounts`), must not change what the two cursors mean to cue rows, must leave `ProgramView`'s
memo intact.

Grew in the landing: review found the memoized bar alone left the headline `/programmer` case
live — `useShowTransport`'s `useAnimatedProgress` calls re-render the host per rAF whether or not
it reads the frame-rate values. The transport now takes `frameRateProgress` (default true) and the
programmer page opts out, so it stops re-rendering per frame during fades entirely; the flag dies
once `FS-PERF-MOBILE-SHEET-FADE` and `FS-PERF-PROMPTBOOK-FADE-DRILL` move the remaining
`fadeProgress`/`autoProgress` consumers onto `useCueFade` and the transport can stop computing
them. `FS-PERF-PROGRAMMER-MEMO-BARRIER` is still owed for non-fade traffic. Rig check filed as
`FU-MANUAL-FADE-SHOWBAR`.

### ~~`FS-PERF-PROGRAMMER-MEMO-BARRIER`~~ — **landed**
**The programmer page has no memo barrier between chrome and body**, lighting-react `feece73` · S2
· P1 · C2 · sonnet
`src/routes/ProgrammerPage.tsx`

The page-level consequence of the above, worth its own item because it also bites on
non-fade traffic (`useProgrammerSummaryQuery` changes re-render the whole subtree). Wrap
`ProgrammerBody` in `React.memo` (its only prop is `projectId`) or pull `useShowBarProps` into a
small bar-owning child so the page stops subscribing to the runner slice. Same must-not-remount
constraint as above. Do after `FS-PERF-FADE-DISPATCH` so it protects against what remains, not
against what should be removed.

### ~~`FS-PERF-PROMPTBOOK-FADE-DRILL`~~ — **landed**
**Prompt Book prop-drills `fadeProgress` to every cue card in the whole show**, lighting-react
`80fc9cb` · S2 · P2 · C2 · sonnet
`src/routes/PromptBookPage.tsx`, `src/components/promptbook/CueStackPanel.tsx`,
`src/components/promptbook/PromptBookCueCard.tsx`

`railProps` (rebuilt per render, ~60×/s in a fade) spreads `fadeProgress`/`fadeRemainMs` into an
unmemoized `CueStackPanel`, which maps **every cue in every stack** to unmemoized
`PromptBookCueCard`s with ten fresh inline arrows each; all but one card re-render to display
nothing. The page even carries a comment admitting the render path runs every fade frame.

**Fix**: the live card reads its own fade via `useCueFade`; drop the two fields from `railProps`;
memoize the card with stable per-row callbacks. Keep `statusOf`/`modeOf` semantics and the live
card's viewMode-follows-GO behaviour.

Landed as specified: Prompt Book now opts out of `useShowTransport`'s frame-rate progress
(`frameRateProgress: false`) since the page never read `fadeProgress`/`fadeRemainMs` for anything
but the drill; `PromptBookCueCard` is `React.memo`'d with the page's own stable per-row callbacks
passed straight through (taking the cue/cueId themselves instead of being pre-bound per row).
Review also found — and this fixed — a pre-existing `warningsByCue.get(...) ?? []` that allocated
a fresh array every render for the common no-warnings case, which would have silently defeated the
new memo for almost every row, and a duplicated `row.cue.stackId === activeStackId` check factored
to one `isLiveStack` per row. Rig check filed as `FU-MANUAL-PROMPTBOOK-FADE`. The `frameRateProgress`
flag itself is still owed to die once `FS-PERF-MOBILE-SHEET-FADE` also lands (per the note on
`FS-PERF-FADE-IN-SHOWBAR` above).

Review of the working tree also caught a live defect in the already-landed
`FS-PERF-PROVENANCE-REFETCH` (unrelated to this item, but in scope for its own three
not-yet-pushed commits): the WS `Json` converter has `encodeDefaults = false`, so
`programmerRevision` (default 0) was silently omitted from the wire until the counter first
ticked past zero, reproducing the refetch storm that item was meant to fix. Fixed separately,
lighting7 `4eb3a4f` — see that item's own entry below for the note.

### ~~`FS-PERF-MOBILE-SHEET-FADE`~~ — **landed**
**Phone cue-list sheet re-renders every row per fade frame with an O(n²) done-tick**,
`0fa02f9` · S3 · P3 · C2 · sonnet
`src/components/runner/run/RunMobile.tsx`, `src/components/runner/MobileCueListSheet.tsx`,
`src/routes/ShowPage.tsx`

`ShowPage` builds a fresh `runnerDisplay` per render and the sheet maps every cue to an unmemoized
`MobileCueRow` with a fresh `onClick`; each row does `completedCueIds.includes(...)` — ~40k
comparisons per frame on a 200-cue stack, on a phone, while the sheet is open mid-show. Fix: rows
read their own fade via `useCueFade`, memoize the row, hoist a `Set` for the done-tick. Only the
active cue draws fade chrome; `onRequeueCue` stays inert off the playhead.

Grew in the landing: code review (high effort) found the desktop cue-stack view
(`StackDetail.tsx`) had the identical `completedCueIds.includes()` per-row cost this item fixed
on mobile. Not named in this item's file list; taken as part of this landing rather than filed
separately, since it's the same one-line-cause defect in the sibling view.

### `FS-PERF-TRANSPORT-ALLOC`
**`useShowTransport` builds a whole-stack signature string per render** · S4 · P3 · C1 · haiku
`src/hooks/useShowTransport.ts`, `src/routes/ShowPage.tsx`

`stackCueSig` (`cues.map(id).join(',')`) and `animCue = cues.find(...)` run unmemoized in a hook
that re-renders at frame rate; `ShowPage` adds an unconditional `runnerDisplay` object only the
narrow branch uses. Pure allocation reduction — nursery garbage, no measurable cost — so ride it
along with the cluster rather than scheduling it alone (this repo has cancelled perf follow-ups for
lack of a measurable gap before). Memoize on `[activeStack]`; the reset-gate semantics must not
change.

### The WS fan-out band

### ~~`FS-PERF-WS-SINGLE-PARSE`~~ — **landed**
**The channelState firehose is `JSON.parse`d 24 times per frame**, `61cbc01` · S2 · P1 · C2 · sonnet
`src/api/internalApi.ts`, `src/api/lightingApi.ts`, and every `src/api/*Api.ts` bridge

`notifyEvent` hands every WS event to all ~27 eagerly-built bridges; only `programmerWsApi` and
`speedMastersWsApi` substring-guard before parsing — 24 others `JSON.parse` the full frame
unconditionally and discard it. The dominant frame is `channelState` at up to ~40/s per universe
whenever anything moves, so a single running effect means ~40 × 24 redundant parses per second on
the main thread that also paints the grid. (The two existing fast paths call this stream "the
firehose" — the codebase already agrees.)

**Fix**: parse once in `internalApi` — hand bridges the already-parsed object (or a lazily-memoised
parse) and let them switch on `type`. Keep per-bridge `open` handling and notify-on-open semantics
(several bridges seed caches on reconnect); do not change which bridge sees which frame. Rider:
per-subscriber try/catch in `notifyEvent` (`FS-WS-ERROR-ISOLATION`).

Grew in the landing: review found that the twelve bridges which are nothing but "one payload-free
frame means this list changed" had all just been touched to take the new argument, and that the five
bridge test files each carried a byte-identical `fakeConnection()` edited in lockstep. Both were
extracted in the same commit — `createChangeSignalApi` in `wsSubscriptionFactory.ts`, and
`src/test/fakeWsConnection.ts` — because the extraction rewrote the very bodies this item edits, so
there was no separable intermediate. `parseWsFrame` is exported for the fake to mirror production,
and `internalApi` gained its first test file. Operator check filed as `FU-MANUAL-WS-SINGLE-PARSE`.

### ~~`FS-WS-ERROR-ISOLATION`~~ — **landed**
**`notifyEvent` has no per-subscriber error isolation, and the programmer bridge is registered
last**, `a046c50` · S3 · P1 · C1 · sonnet
`src/api/internalApi.ts`

A bare `forEach` — one throwing subscriber starves every later-registered bridge of that frame, and
the two most latency-sensitive bridges (programmer, speed masters) are registered last. No reachable
throw was constructed (the server sends only kotlinx text frames), so this is hardening, not a live
bug — take it as a rider on `FS-PERF-WS-SINGLE-PARSE`. Log failures; never swallow silently.

### ~~`FS-PERF-SAVELOOK-INVALIDATION`~~ — **landed**
**A layer-scope drag refetches the whole fixture list every 400 ms**, `f4053b4` · S2 · P1 · C1 ·
sonnet
`src/store/looks.ts`, `src/components/programmer/LookRowStore.tsx`

`saveLook` invalidates `['Look','LookList','Cue','CueList','Fixture','GroupList']` unconditionally,
and the layer-scope drag path saves rows-only bodies at 400 ms cadence. `Fixture` is provided by the
fixture list (48 consumers; server-side it runs `loadLookCompatibilityInfos` + `detectCapabilities`
per fixture), so every save refetches it and hands every consumer a new array identity mid-drag. A
rows-only PUT provably cannot move `compatibleLookIds` — `compatibleIdsFor` filters on effect
categories alone. The comment justifying the tags cites `editorFixtureType`, deleted in session 3;
fix the comment in the same change (the stale half is what makes the over-broad set look
justified).

**Fix**: invalidate by what the request wrote — rows-only saves skip `Fixture`/`GroupList`; saves
touching `effects` keep the full set (a Look gaining its first effect of a family must reappear in
`LookTogglePicker`/`LayerPicker`).

Follow-up, `b38f0c3`: review of the manifest pass found that `absorbLookEffects` — the `+ Effect`
path, which is unambiguously an effect write — never invalidated `Fixture`/`GroupList` at all, so
narrowing `saveLook` to the same rule left absorbing as the one effect write that stranded the
compatibility lists. Fixed there, along with collapsing `saveLook`'s nested ternary, whose two arms
repeated the same four base tags verbatim.

Landed as `effects === undefined`, not "rows-only": the same argument acquits the metadata-only save
`LookDetailSheet` sends, and presence of the `effects` key is the condition the wire actually
carries. Operator check filed as `FU-MANUAL-SAVELOOK-INVALIDATION`.

### `FS-PERF-BPM-INVALIDATION`
**Any BPM change invalidates `FixtureEffects` + `GroupActiveEffects`** · S3 · P1 · C2 · sonnet
`src/api/fxApi.ts`, `src/store/fixtureFx.ts`, `src/store/groups.ts`

`createFxApi` calls `notifyState` on a bpm-only `beatSync` (the backend sends one per tap), and two
module-level bridges answer with tag invalidations. Tempo cannot change which effects exist. The
worst case in the finding's original form (~60 GETs per tap) needs a second client parked on the
fixture-cards page; in the same tab a tap costs one redundant `fx/active` refetch — real but
smaller.

**The tempo half is gone**: D2 / `FS-COORD-LEGACY-TEMPO` retired `beatSync` and stripped `bpm`
from the `fxState` frame, so a tap no longer notifies the fx state at all and there is nothing to
gate. What remains of this finding is only the `FxBadge` consolidation — read the rig-wide
`useActiveEffectsQuery` rather than a per-fixture query, which removes the fan-out class for
genuine effect changes too. Don't break `useFxStateQuery` consumers.

### ~~`FS-PERF-PROVENANCE-REFETCH`~~ — **landed**
**Every cue crossfade tick drives a `programmer.state` request/response at up to 10 Hz per tab**,
lighting-react `90565f7` (+ lighting7 `86dedaa`) · S3 · P2 · C3 · fable
`src/api/programmerWsApi.ts`, backend `ProgrammerSocket`/`FxEngine`

`applyProvenance` schedules a state refetch on every `provenanceState` frame; a crossfade republishes
provenance at ~20 Hz (coalesced 50 ms), so every tab answers with ~10 state requests/s for the whole
fade — and a cue fade cannot have moved the programmer's own entries. Transient and small in the
common case (the reply carries no provenance and the programmer is usually near-empty during a run),
which is why this is S3/C3 rather than S2: the fix needs a protocol change. **Fix**: let provenance
say whether the programmer's value set could have changed (a monotonic programmer revision on the
frame, or a cue-fade-only flag) and skip the refetch when it couldn't. The refetch must survive for
every genuine off-connection write (MIDI, second tab, locate, template apply) — losing one strands
the grid, which is worse than the traffic. **Backend C3 is the server half** (it stops the
crossfade re-running the full resolver at ~62 fps): coordinate the protocol change there, and
re-measure this client cost after C3 lands before spending the effort here.

Grew in the landing: the re-measure confirmed the mechanism at HEAD (C3 removed the resolver cost
but the per-tick provenance emit and the client's unconditional refetch both survived). Landed as
the monotonic-revision option — `programmerRevision` on `provenanceState`, bumped by every
provenance trigger except the weight-only republish — after review refuted the fade-only-flag
option: the broadcast flow is replay-1 + DROP_OLDEST, so a slow tab can drop the single unflagged
frame a drained flag rides on, stranding an off-connection write. The review also added a second
fix in the same commit: a provenance frame that changed nothing no longer calls
`notifyState`/`notifyKeys` at all, ending the ~20 Hz snapshot-identity render churn on whole-state
subscribers. An adjacent verified defect in the landed `FS-PERF-PROGRAMMER-MEMO-BARRIER`
(`ProgrammerBody` held its own summary subscription, which `memo` cannot block) was fixed in its
own commit, lighting-react `090e008`.

A second defect surfaced later, during `FS-PERF-PROMPTBOOK-FADE-DRILL`'s review of the (at the
time still unpushed) working tree: the WS content converter's `Json` instance has
`encodeDefaults = false`, so `programmerRevision`'s `= 0` default was omitted from the wire
whenever the counter was still at 0 — server startup, and any frame before the first non-fade
trigger — and the client's field-missing branch (written for an *older* server) read that as
unsupported and refetched every frame, silently reproducing the storm this item shipped to fix.
Fixed with `@EncodeDefault(ALWAYS)`, lighting7 `4eb3a4f`.

### `FS-PERF-SIGNATURE-CACHE`
**`changedKeys` recomputes both sides' JSON signatures on every diff** · S3 · P2 · C2 · sonnet
`src/api/programmerWsApi.ts`

`signature(previous)` and `signature(value)` both run per key per frame (entry and provenance maps,
at 10–20 Hz under load), re-stringifying the previous map from scratch each time although it was
computed when installed. **Fix**: keep a parallel `Map<string,string>` of signatures built at
install, so a diff stringifies only the incoming side. Content-comparison semantics must stay
exactly as documented — identity `!==` would defeat the per-key channel entirely.

### ~~`FS-PERF-CHANNEL-FANOUT`~~ — **landed**
**One row's callback fires once per changed channel per batch, rebuilding its signature each time**,
`51b0abc` · S3 · P2 · C2 · opus
`src/hooks/usePropertyValues.ts`, `src/components/fixtures-list/useRowValues.ts`

`subscribeToChannels` registers the same callback once per channel, and each notification reruns
`getSnapshot` with its fresh signature array and per-read key-string allocations. No render churn
(the signature compare absorbs it) and ordinary rows are cheap (k≈8–13); the cost concentrates on
windows of collapsed multi-head bars/group rows. **Fix**: a set-level subscription that fires at
most once per debounced batch (or coalesce in `subscribeToChannels` on a microtask). Must not break
per-channel granularity for `createFanOut`/derived sources, snapshot-identity caching, or
`ChannelSource` threading.

Landed as the microtask coalesce, inside `subscribeToChannels`, so it covers every source rather
than each one — a single-channel set keeps the direct synchronous path, having nothing to coalesce.
**Grew in the landing**: the review found `FixtureModel`'s `useLiveColour` hand-rolling the same
per-channel registration, bypassing the helper entirely, and that turned out to be the larger half —
a seven-channel colour beam reapplied its whole colour seven times per batch, per fixture, across
the stage. Routed through the helper too. `FU-MANUAL-CHANNEL-FANOUT` carries the rig check.

### `FS-PERF-CHANNEL-CACHE-DISPATCH`
**/channels holds one RTK Query cache entry per channel, dispatching per changed channel per frame**
· S3 · P3 · C2 · sonnet
`src/store/channels.ts`, `src/routes/Channels.tsx`

Each `ChannelSlider` holds a `{universe, channelNo}`-keyed cache entry whose `updateCachedData` on a
`number` always emits a dispatch. Virtualization bounds mounted sliders to the low hundreds (not
512), and exposure is confined to the /channels debug view plus `FixtureContent` rows — hence S3.
**Fix**: read values through `lightingApi.channels.subscribeToChannel` via `useSyncExternalStore`,
keeping the per-channel split but taking Redux out of the 30 Hz path. Leave `channelsApi` batching
and the mutation write path alone.

### `FS-PERF-MARQUEE-COUNT`
**`batchCountFor` recomputes an O(rows × columns) marquee count for every rendered cell per pointer
move** · S2 · P1 · C2 · sonnet
`src/routes/FixturesList.tsx`, `src/components/fixtures-list/useCellSelection.ts`

The marquee branch ignores `(row, col)` — it returns the same number for every selected cell — yet
recomputes `byColumn()` over the whole selection plus `expandSelectionToTargets(rows, …)` per
column, per row, per marquee tick (the callback's identity changes each tick, so every visible row
re-renders too). Order 10⁵–10⁶ operations per pointer-move frame on a large list. **Fix**: hoist the
selection-wide count into one `useMemo` keyed on the cell selection and `rows`; keep the per-row
branches as they are. The count stays a documented *upper bound*, and collapsed multi-head bars must
keep their per-row "Applying to 12".

### `FS-PERF-LITKEYS-ALLOC`
**`useLitFixtureKeys` rebuilds a Set and spreads it on every snapshot read** · S3 · P3 · C1 · haiku
`src/components/fixtures-list/useLitFixtureKeys.ts`

O(fixtures) allocation per 33 ms batch *and* per render even when membership is unchanged. Count
matches against the cached set while iterating; allocate only on divergence. The membership-only
notification contract must hold — a fade that changes values but not who is lit must return the
cached identity, or every row memo churns mid-fade.

### `FS-PERF-LAYER-SIGNATURE`
**`programmerLayers` stringifies the whole layer stack on every programmer notification** · S4 · P3
· C1 · haiku
`src/store/programmer.ts`

Real but tiny (a handful of layers, usually `[]`; tens of microseconds at busk cadence), and the
whole-object compare is the *correct* part — a hand-rolled field list would be a maintenance hazard.
Note it; take it only if a cheap change-counter falls out of other programmer work. The
`layerState`-broadcast-reaches-every-tab invariant and `reset()` propagation must hold.

### Always-mounted chrome

### ~~`FS-PERF-COLLAPSED-PANELS`~~ — **landed**
**Collapsed overview panels keep doing full live work on every route**, lighting-react `52660a6`
(+ `6365894`) · S2 · P2 · C2 · opus
`src/Layout.tsx`, `src/components/StageOverviewPanel.tsx`, `src/components/EffectsOverviewPanel.tsx`,
`src/components/CueSlotOverviewPanel.tsx`

Layout renders all four panels always ("for animation"), with `isVisible` only switching grid rows.
A live rig therefore re-renders every mini-stage marker ~30×/s behind a zero-height container while
the operator is on /users; the effects panel keeps a `BeatIndicator` interval alive everywhere; the
cue-slot panel holds its queries and fxState-derived sets on every page.
(`docs/stage-vis-engineering.md`'s "a collapsed panel costs nothing" is scoped to the cue-preview
POST, not these subscriptions.) **Fix**: keep the animated wrapper; gate each panel's subscribing
body on `isVisible || wasRecentlyVisible` so collapse still animates out. The mini-stage keeps
sharing the Stage route's `ChannelSource` when open; reopening must not flash empty. Part of the
Layout cluster (§4). `FS-PERF-CHANNELSOURCE-REBUILD` below is the largest single cost hiding behind
this panel.

Landed as an unmount, not a `wasRecentlyVisible` render gate: a shared `CollapsiblePanel` keeps the
grid-rows wrapper and mounts the body while open plus one collapse's worth of time after, which is
the same thing said as a lifecycle rather than a flag. Reopening does not flash empty inside RTK
Query's one-minute retention of an unsubscribed entry; past it, it refetches like a fresh load, and
the two pieces of state an operator would miss (the stage group filter, the cue-slot page) are held
above the boundary. All **four** panels were done, not the three the file list names — the fixture
overview panel has the same shape and its cards subscribe per fixture.

Grew in the landing: `EditModeAssignPanel` was a fifth hand-rolled copy of the same collapse
holding a cue-stack query, so it goes through `CollapsiblePanel` too; two cue-slot timers (the
long-press stages, the drag edge-scroll) gained unmount cleanup, harmless before and not now; and
`docs/stage-vis-engineering.md`'s "a collapsed panel costs nothing" claim was restated, since its
stated *reason* — the panel renders whether or not it is expanded — is now false. A second review
pass added `holdMounted`, which keeps the cue-slot body through an in-flight drag (unmounting takes
every droppable with it, and the drop would be discarded in silence), and deleted the dead
`CueSlotDndContext.isSlotPanelVisible` — lighting-react `6365894`.

A second review pass over the whole cluster found one live regression and four consolidation gaps,
all taken (`4d7946a`, `1a9ebdb`): the FX lock, the index-coupled descriptor pairing, a falsy
`clientX` check that disabled drag edge-paging for a drag begun at x=0, the cue-slot page moved onto
`usePersistentState`, and `ProgrammerIndicator` migrated onto `pathHasSegment`. The one finding
left open is whether dnd-kit re-measures droppable rects after the wrapper collapses, which would
defeat `holdMounted`; that is a rig question and is `FU-MANUAL-COLLAPSED-PANELS`'s last step.

### ~~`FS-PERF-CHANNELSOURCE-REBUILD`~~ — **landed**
**`createProgrammerChannelSource.rebuild` re-resolves every programmer entry on frames that cannot
have changed a channel — app-wide, from the always-mounted overview panel**, lighting-react
`fbeddfa` · S3 · P2 · C1 · sonnet
`src/api/channelSource.ts`, `src/api/programmerWsApi.ts`, `src/components/StageOverviewPanel.tsx`

`rebuild` runs unconditionally on every `programmer.subscribe` notification — including
`includeTarget`, `layerState`, `blindState` and every `provenanceState` (up to 10/s) — allocating a
fresh channel map (parse + descriptor scan per entry) and walking old + new in `notifyChanged`.
`programmerWsApi` reassigns only the wrapper object on those frames, so `entries`/`channels` keep
identity and an identity guard distinguishes the cases exactly. `StageOverviewPanel` mounts the
provider whenever patches exist, on every page, gated only by CSS — so a persisted
Programmer/Output+Programmer vis choice keeps this running everywhere for a panel nobody can see.
**Fix**: early-return when both `state.entries` and `state.channels` are reference-identical to last
time (`refresh()` keeps forcing, it exists for descriptor changes); gate the provider on
`isVisible` as part of `FS-PERF-COLLAPSED-PANELS`. The drag path staying O(entries) per echo is
noted, not fixed — incremental resolution is a larger change.

Landed in two halves as written: the `isVisible` gate came free with
`FS-PERF-COLLAPSED-PANELS` (the provider sits inside the stage panel's body, which now unmounts),
and the identity guard is its own commit.

### `FS-PERF-STAGE-BUFFER-UPLOADS`
**`StageEmitters` marks every instanced attribute dirty every frame, so a static rig re-uploads all
of them — including both wash meshes on a show with no pixel bars** · S3 · P3 · C2 · sonnet
`src/components/stage3d/StageEmitters.tsx`

The priority-1 `useFrame` sets `needsUpdate = true` on ~50 instanced attributes plus five
`instanceMatrix` buffers unconditionally ("Cheap (just bit flips)" — the flip is cheap, the
consequent full-buffer GPU upload per flagged attribute is not). The wash pair is the clearest
waste: ~12,800 floats of `instanceMatrix` flagged per frame even when no fixture is a pixel strip
and the wash director never writes a byte. **Fix**: per-group dirty bits set by the writers; flag
and clear only dirty groups, wash groups first (biggest buffer, most often untouched). Correct the
comment.

### `FS-PERF-PALETTE-QUERIES`
**CommandPalette subscribes six list queries while closed, on every route** · S3 · P3 · C1 · haiku
`src/components/CommandPalette.tsx`

Fixture/group/park/channel-mapping invalidations refetch lists and re-render the palette on pages
that display none of them, and the entries can never be evicted. Skip the data queries until first
open (latch so reopens stay instant); check nothing relies on the palette keeping a list warm.

### ~~`FS-CHROME-BEAT-RESUBSCRIBE`~~ — **landed, folded into `FS-COORD-LEGACY-TEMPO`**
**`BeatIndicator` tears down and re-creates its WS subscription on every sync transition** · S4 ·
P3 · C2 · sonnet

`synced` now lives in a ref (`syncedRef`) alongside the state, so the subscribe effect keys on the
target master alone. `BeatIndicator.test.tsx` gained a case pinning it: delivering a beat must not
re-subscribe.

**The item's "one redundant `requestBeat`" was only true of an ordinary sync regain.** The
re-subscribe on the *visibility* transition was the mechanism that recovered a drifted local timer
after a tab switch — `setSynced(false)` flipped a dep, the effect re-ran, and `subscribeBeat` sent
the request as a side effect. Taking `synced` out of the deps removed it silently, leaving the dot
an empty ring for up to 16 beats. The recovery is now explicit: `requestSpeedMasterBeat` (a new
`requestBeat` on the WS api) asks for a frame without re-binding a subscription that was never the
problem. Pinned by a test.

### `FS-CHROME-BEAT-MAP-PRUNE`
**Per-master beat subscribables are never pruned, so reconnects re-request beats nothing watches** ·
S4 · P3 · C2 · sonnet
`src/api/speedMastersWsApi.ts`, `src/api/wsSubscriptionFactory.ts`

Bounded and tiny (masters-ever-displayed per tab; a few extra frames per reconnect only — the
re-request itself is deliberate and load-bearing for phase recovery). Give `createWsSubscribable` an
emptiness signal and drop empty entries; keep re-requests for keys with live subscribers and master
1's `''` convention.

### ~~`FS-PERF-CODE-SPLITTING`~~ — **landed**
**The whole app ships as one 4 MB chunk — no route or vendor splitting anywhere**, `656ff33` ·
S2 · P2 · C2 · opus
`vite.config.ts`, `src/App.tsx`

One 4,023 kB `index-*.js` (1,141 kB gzip); zero `React.lazy` or dynamic imports in src. Measured
per-island weight (throwaway `manualChunks` build): kotlin-playground 511 kB (script surfaces only),
pdfjs/react-pdf 416 kB (Prompt Book only), R3F/drei/postprocessing ~293 kB (Stage only),
react-markdown 123 kB (`AiChatPanel` only) — all parsed before the login screen paints, on every
cold boot and every post-update restart. **Fix**: route-level `React.lazy` for the four heavy
islands with layout-stable fallbacks. Two traps: `lib/stageCoords.ts` imports `three` and is
consumed by non-3D code, so `three` stays in the main chunk until the Three-typed helpers are
separated; and `AuthGate`/`BootGate` must not sit behind a lazy boundary.

Landed as four lazy boundaries, not four route boundaries: `CueTriggerEditor` mounts the Kotlin
editor in a sheet inside Show, so splitting only `/scripts` and `/fx-library` would have left all
511 kB in the entry chunk — that island's boundary is the widget's own component
(`LazyScriptEditor`), and `AiChatPanel`'s is a mount latch in `Layout`. `vite.config.ts` was not
touched; rolldown's default chunking did the rest. Boot payload (entry plus every `modulepreload`)
1,961 kB / 564 kB gzip, from 4,015 kB / 1,141 kB. Both traps held as written: `three` is still in
the boot set via `stageCoords` → `useProjectedPatches` → the Layout stage panel.

Grew in the landing: a `FeatureErrorBoundary` around each lazy boundary. The tree had no error
boundary anywhere, and four `React.lazy` sites make a stale-chunk 404 reachable for the first time
— acute here because the Windows updater rewrites the running install's statics in place — which
unguarded unmounts the whole desk. On-rig check filed as `FU-MANUAL-CODE-SPLITTING`.

### `FS-WS-DEBOUNCE-TICK`
**`debounceMapUpdates` keeps its interval alive one no-op tick past idle** · S4 · P3 · C1 · haiku
`src/api/channelsApi.ts`

Harmless (one empty callback per idle transition) but the function reads as a self-cancelling
debounce and isn't. Clear when nothing is pending, or switch to a trailing re-armed `setTimeout`;
all entries from one call must still fire together.

## 7. Contract drift — phantom fields and unpinned mirrors

The client's hand-mirrored types drifted in both directions across the refactors. Phantoms (client
declares, server no longer sends) are listed here; the one *omission* class the first round found is
`FS-TYPES-CUE-STOMP`; a dedicated gap-round pass then swept the omission direction backend-first —
its findings are folded in below.

### `FS-TYPES-CUE-STOMP`
**Cue-level `stomp` is absent from the `Cue`/`CueInput` mirror, so duplicating a cue silently clears
it** · S3 · P2 · C1 · sonnet
`src/api/cuesApi.ts`, `src/lib/cueUtils.ts`

`routes/projectCues.kt`'s `CueDetails` and `NewCue` both carry `stomp: Boolean = false` (the
cross-cue, effect-removing stomp — distinct from the per-layer one the client does model). Neither
client type declares it, so `buildCueInput` cannot round-trip it: `handleDuplicate` POSTs a copy that
takes the server default, and nothing on the desk can read or set the flag on a cue that arrives
with it (sync import, AI, another client). Latent rather than active — no producer sets it true
today, and the PATCH route preserves absent fields — but the exported-and-unused PUT (`saveProjectCue`)
overwrites it, so wiring that hook up would convert the loss to active. The field-by-field pin in
`cueUtils.test.ts` cannot catch a field the type never had.

**Fix**: add `stomp` to `Cue`/`CueInput`, round-trip it in `buildCueInput`, extend the field-by-field
test. Decide separately whether `CuePropertiesSheet` exposes it. Do not conflate with per-layer
stomp (`lighting-composition-model.md` §Stomp).

### `FS-TYPES-PRESETCOUNT-RENAME`
**`CueStackCueEntry.presetCount` mirrors a field renamed to `layerCount`** · S3 · P2 · C1 · haiku
`src/api/cueStacksApi.ts` + four test fixtures

The backend renamed it in session 4 (KDoc: "No client reads it yet … treat it as available"), and
the sweep confirmed `presetCount` exists nowhere in the backend at HEAD — `layerCount` is the only
spelling. Declared non-optional, always `undefined`; the field actually sent — the per-cue layer count a
collapsed row wants — is invisible. Four test files write `presetCount: 0` purely for the compiler,
pinning the drift in place. **Fix**: mechanical rename + fixtures; optionally then use `layerCount`
where a layers-only cue currently reads as empty.

### `FS-TYPES-RIGGING-POSITION`
**`FixturePatch.riggingPosition` is a phantom; the StageMarker badge it drives can never render** ·
S3 · P2 · C2 · sonnet
`src/api/patchApi.ts`, `src/components/stage/StageMarker.tsx`

The backend folded the free-text field into first-class Rigging rows (`sync-engineering.md` records
the removal); the client still declares it on the patch DTO and both request types, and the 2D
marker's amber rigging badge is unreachable dead UI. **Fix**: delete the field from all three types
and the fixtures; then decide the badge's replacement — the rigging *name* resolved from
`riggingUuid` is the honest one, C2 because that half is a design choice.

### `FS-TYPES-EFFECTTYPE-UNION`
**`EffectType` is a closed 20-literal union with no backend counterpart, laundered by a cast** · S3
· P2 · C2 · sonnet
`src/api/groupsApi.ts`, `src/components/busking/useBuskingState.ts`

The real vocabulary is data-driven (`fx/index.txt` + each `.fx.kts` `id:` + user-defined
definitions, ~25 built-ins); the union is wrong in case, missing at least eight built-ins, and
structurally unable to name a user-defined effect. It only works because `FxRegistry` normalises
names, and the single production call site casts `effect.name as EffectType` — so the union
constrains nothing and misdescribes the vocabulary. **Fix**: widen to `string` (matching
`addFixtureFx`), delete the three unions and both casts; if narrowing is wanted, derive it from the
FX library query at runtime. Never canonicalise the sent name — the registry's normalisation is what
makes it work.

### `FS-TYPES-MASKGROUP-DUP`
**`PropertyMaskGroup` is a second, free-standing copy of `AttributeFamily`** · S3 · P2 · C2 · sonnet
`src/lib/attributeFamily.ts`, `src/store/programmerOps.ts`, `src/components/programmer/maskPicker.tsx`

One Kotlin enum, two independent client unions, silently interchangeable and in fact mixed
(`useLocalFamilyCounts` returns one, `MaskPicker.counts` takes the other). The claim is deliberately
narrow: the *vocabulary* pin exists (`maskPicker.test.ts` equates `MASK_GROUPS` to
`ATTRIBUTE_FAMILIES`, and CLAUDE.md documents it) — what's missing is that the two *types* are
unrelated, so a literal added to one still typechecks. **Fix**: make `PropertyMaskGroup` an alias of
(or delete it for) `AttributeFamily`, and derive `MASK_GROUPS` rows from
`ATTRIBUTE_FAMILIES` + `FAMILY_LABELS`. Don't write a new vocabulary test — it exists. Wire form
(uppercase, `serializePropertyMask` null-normalisation, family order) must not change.

### `FS-TYPES-PALETTE-WIRE-ARMS`
**Retired palette wire arms kept alive by "still on the wire" claims that are now false** · S3 · P2
· C2 · sonnet
`src/store/programmerOps.ts`, `src/api/programmerWsApi.ts`, `src/lib/includedTarget.ts`,
`src/lib/programmerSource.ts`

Three mirrors keep a PALETTE arm on explicitly documented wire-compat grounds, and all three grounds
are gone: `ProgrammerUpdateResponse` has no `paletteResult`; `IncludeResponse.kind` is produced as
`CUE`/`LOOK` only; `IncludedTargetDto` dropped all `palette*` fields (the arm could only ever render
"Palette undefined"). `programmerWsApi.test.ts`'s "accepts a PALETTE include target" feeds a payload
the server can no longer emit — certifying the drift instead of catching it. **Fix**: remove the
PALETTE arm from all four modules and the pinning tests; `includedTargetKey`'s one-shape-per-kind
guarantee and the LOOK arm's `updateIncludedLook` write-back stay untouched. See also
`FS-BE-INCLUDEDTARGET-PALETTE` (§14) for the backend half, and `FS-RES-PALETTERESULT` for the
unreachable `UpdateDialog` branch.

### ~~`FS-TYPES-GROUPFX-WS`~~ — **landed with `FS-COORD-GROUPS-WS`**
**groupsApi's WS layer declares a frame the backend never emits and two methods nothing calls**,
lighting-react `c1a8c44` · S3 · P3 · C1 · haiku
`src/api/groupsApi.ts`

`groupFxAdded` is the only orphan in a full diff of client WS `type` literals against every backend
`@SerialName`; `addFx` sends a message the backend deliberately no-ops (group FX creation is
REST-only) and `clearFx` has no caller. The `groupsState` arm also types its payload as the 6-field
REST `GroupSummary` where the frame carries three fields — harmless only because the handler ignores
the payload. **Superseded in scope by backend D3**, which deletes `plugins/GroupSocket.kt` outright —
see `FS-COORD-GROUPS-WS` (§14) for the whole-module deletion and the GroupList-freshness question
that must be answered first. Do not do this finding's narrower fix separately.

### `FS-TYPES-ISBUILTIN`
**`FxDefinition.isBuiltin` has no producer and no consumer** · S4 · P3 · C1 · haiku
`src/store/fxDefinitions.ts`

A required boolean that is always `undefined` — the shape that later grows a guard that never
fires. The built-in/user distinction is real server-side but lives in *which endpoint served the
row*. Delete the field.

### `FS-TYPES-TEMPLATE-TOGGLE-MASK`
**Template toggle discards the client's `propertyMask` and the server derives none, so every ⌥click
and pad template layer is unmasked — against CLAUDE.md's stated gesture contract** · S3 · P1 · C1 ·
sonnet
`src/components/programmer/TemplateStrip.tsx`, `src/components/busking/BuskingView.tsx`, backend
`ProgrammerLayerStack.toggle` / `projectTemplates.kt`

Both client call sites send `propertyMask: template.family`, and the route's KDoc says the mask is
"echoed back rather than applied … the server derives" it. Nothing derives it:
`ProgrammerLayerStack.toggle` has no mask parameter (still true at backend HEAD — the sweep closed
without taking this), so the layer lands with `propertyMask: null`
and the response echo can never disagree with itself. CLAUDE.md's §two-apply-gestures claim ("masked
to the template's family — the client states the mask, so the layer row shows what it asserts") is
false in the layer data; the row still *displays* a family badge because `LookStack` derives
`info.families` from the library lookup, which is what keeps this S3 (rig output unaffected today —
a template's rows are all one family — and the display lies in the right direction). **Fix**: needs
the backend half — a `propertyMask` parameter on `toggle` with the family derived server-side
(§14, `FS-BE-TEMPLATE-TOGGLE-MASK` — still owed, and not carried by the completed backend sweep);
then fix the KDoc, and pin that the layer
emitted in `programmer.layerState` after a toggle carries the family.

### `FS-TYPES-ADDLAYER-MASK-DROP`
**`ProgrammerLookStack.handleAdd` drops `propertyMask` when forwarding LayerPicker's layer** · S3 ·
P2 · C1 · haiku
`src/components/programmer/ProgrammerLookStack.tsx`

`LayerPicker` sets the mask with a comment explaining exactly why ("an unmasked template layer would
read as 'this could touch anything'"); `handleAdd` rebuilds the `programmer.addLayer` payload field
by field and omits it — though the wire accepts it — so the identical picker produces a masked layer
in a cue and an unmasked one in the programmer. **Fix**: forward `propertyMask`, extend the comment
so the timing-only exclusion isn't read as "all extra fields deliberately dropped", pin with a test
on the outgoing frame.

### `FS-TYPES-SURFACE-DESCRIPTORS`
**Control-surface descriptors omit `touchCc` and `programChange`, and type `BankButtonControl.note`
non-nullable where the wire guarantees null for one legal shape** · S3 · P3 · C1 · haiku
`src/api/surfacesApi.ts`

The wire's Fader carries `touchCc` (populated by the shipped X-Touch profile) which the client
omits; the wire's BankButton makes `note`/`programChange` mutually exclusive while the client
declares `note: number` non-nullable and no `programChange`. Latent (nothing reads them today) —
but note this **corrects `FS-DEAD-DTO-FIELDS`' framing** of the surfaces feedback cluster as pure
over-declaration: the actual divergence runs in both directions, so that cluster wants
reconciliation against `ControlDescriptorDto`, not blanket deletion. **Fix**: add the two fields,
widen `note`, comment the mutual exclusion.

### `FS-TYPES-CLONE-COUNTS`
**`CloneProjectResponse` drops four of the server's content counts, and the dialog discards the
rest** · S4 · P3 · C1 · haiku
`src/api/projectApi.ts`, `src/CloneProjectDialog.tsx`

The server counts scripts, looks, cues, stacks and total records cloned; the client type declares
`scriptsCloned` only (with a stray blank line where the rest sat), and the dialog `.unwrap()`s and
discards even that, while its copy says a clone brings "scripts and settings". **Fix**: add the
fields and surface them as a one-line success toast — the only confirmation an operator gets that a
clone was complete — and fix the sheet wording.

## 8. Dead code

Roughly 4,300 lines deletable outright, plus endpoint/DTO surface. The house test gates make these
safe mechanical batches; the notes below are the non-obvious couplings.

### ~~`FS-DEAD-ORPHAN-FILES`~~ — **landed**
**Six files unreachable from `main.tsx` (and from any test)**, `a23b16c` · S3 · P2 · C1 · haiku
`src/UnsavedChangesDialog.tsx`, `src/components/cues/editor/DeadAssignmentsBanner.tsx`,
`src/components/looks/DeadLookRowsBanner.tsx`, `src/components/fixtures/GroupPropertiesDialog.tsx`,
`src/components/ui/searchable-select.tsx`, `src/react-app-env.d.ts`

An import-graph walk from `main.tsx` returns exactly this set. The banner pair is a closed loop
(each other's only importer); the live health-text path is `lib/healthDescriptor.describeHealth` via
`BindingMatrix`, which stays. `react-app-env.d.ts` references `react-scripts`, which isn't
installed. **Corrects a FU**: `FU-FE-REBIND-INPLACE`'s premise is already false —
`DeadPresetAssignmentsBanner` doesn't exist anywhere and `DeadAssignmentsBanner` renders nowhere, so
there is no live dead-assignment banner to add Rebind to. Restate that FU against whatever surface
would host it, or close it.

Grew in the landing: two follow-ups needed the premise repair, not one. `FU-FE-REBIND-INPLACE` was
closed as **Rejected** — there is no host to restate it against, since neither cues nor Looks have a
dead-row surface at all. `FU-FE-HEALTH-BADGE` named the same two banners as two of its three
`AssignmentHealth` renderers; one live renderer is left (`BindingMatrix.tsx`), so its trigger is now
"a second surface", not a fourth.

### `FS-DEAD-RTKQ-HOOKS`
**Fourteen exported RTK Query hooks with zero importers; three FX-definition endpoints fully dead**
· S3 · P2 · C2 · sonnet
`src/store/cues.ts`, `cueStacks.ts`, `templates.ts`, `fixtures.ts`, `fxDefinitions.ts`, `groups.ts`,
`projects.ts`, `ai.ts`

`useSaveProjectCueMutation` (dead since a cue became read-only), `useCopyCueMutation`,
`useProjectCueStackQuery`, `useAddCueToCueStackMutation`, `useGoToCueInStackMutation`,
`useTemplateQuery`, `useFixtureQuery`, `useFxDefinitionsQuery`, `useCompileFxScriptMutation`,
`useCompileFxDefinitionMutation`, `useTestFxDefinitionMutation`, `useDistributionStrategiesQuery`,
`useClearGroupFxMutation`, `useProjectScriptQuery`, `useAiConversationQuery`. Delete endpoint
definitions too where nothing else reaches them. **Coupled lists**: `NON_SAVE_ENDPOINTS`
(saveStatusSlice) and `SILENT_ENDPOINTS` (errorToastMiddleware) both have existence-pinning tests,
so an endpoint and its list entries move in one change. `copyCue`'s SILENT entry cites
`CopyCueDialog.tsx`, a file that does not exist — the one dead path among all paths that file
names. Verify per symbol, not per module.

### ~~`FS-DEAD-CURRENTCUESTATE`~~ — **landed**
**The `currentCueState` chain is dead, and its wire-compat comment protects a type nothing reads —
with a claim that is also false**, `c6533c9` · S3 · P2 · C1 · haiku
`src/store/cues.ts`, `src/api/cuesApi.ts`

Query, lazy hook and `CueCurrentState` have zero consumers; the type's "still `presetApplications`
on the wire" comment asserts the opposite of the wire (`CueCurrentStateResponse` now carries
`layers: List<CueLayerDto>`). Delete all three. The backend route stays; a future "what is on stage"
surface re-types against `layers` then.

### ~~`FS-DEAD-EXPORTS`~~ — **landed**
**Sixteen exported symbols with zero references anywhere**, `e88eb09` · S3 · P2 · C1 · haiku
across `src/api/`, `src/store/`, `src/hooks/`, `src/lib/`, `src/components/`

`resolveFixtureTypeLabel`, `isDeferred`, `LookPreviewResponse`/`LookPreviewRequest` (outlived their
endpoints), `CODE_SPEED_MASTER_PROTECTED`, `scopeIsEditable`, `targetEquals`, `SNAP_ANGLE_RAD`,
`XL_BREAKPOINT`, `useChannelParkStatus`, `alignEdgeLabel`, `arraysEqual`, `StageRegionsRedirect`,
`useStackActiveCueIds` (already deleted with `FS-BUG-CUESLOT-LIVENESS`), `buildEffectLibraryLookup`,
`programmerSetColour`/`programmerSetPosition` (thin wrappers the live call sites bypass). Cautions:
`isDeferred`'s deletion must not touch `validateLookRows`' inlined `ref:`/`tmpl:` shape checks;
keep the *meaning* of the `CODE_SPEED_MASTER_PROTECTED` prose in errorToastMiddleware's comment.

Landed as fourteen of the sixteen. `useStackActiveCueIds` was already gone with
`FS-BUG-CUESLOT-LIVENESS`, and `targetEquals` was deliberately left standing for
`FS-DUP-TARGETKEY` to take with its other half, per §4's supersession. Two private symbols followed
their last caller out as this item anticipated: `deepEqual` (whose only non-recursive caller was
`arraysEqual`) and `normaliseEffectName` (private to `buildEffectLibraryLookup`). Both cautions held
as written — errorToastMiddleware's prose already names the wire literal rather than the constant,
so nothing there needed changing.

### `FS-DEAD-CUELAYER-HELPERS`
**`reorderCueLayers` and `densifyCueLayerOrder` have no production caller** · S3 · P2 · C2 · sonnet
`src/lib/cueUtils.ts`, `src/lib/cueUtils.test.ts`

The cue-layer drag they served died with the three-pane editor; the only `LayerHandlers` host is the
programmer path, which deliberately does not renumber client-side. Their doc comments claimed
callers that don't exist, and so did CLAUDE.md and `ProgrammerLookStack.onMove`; all three were
corrected by `FS-DOCS-STALE-COMMENTS` (`afb2af8`), so what is left here is only the code. **Fix is
a decision**: delete both plus their describe block, or keep them as they now stand — annotated
"no production caller", the way `EditorContext.tsx` annotates its keeps. Do not let the deletion
tidy `cueUtils.test.ts`'s field-by-field pin into a deep-equal — that shape is deliberate, and
`FS-TEST-CUEUTILS-TRIGGERS` (`771ce87`) extended it to the trigger list.

### `FS-DEAD-DTO-FIELDS`
**Wire-mirror fields never read by the client, several naming retired concepts** · S3 · P3 · C2 ·
sonnet
`src/store/fixtureFx.ts`, `src/api/groupsApi.ts`, `src/api/fxApi.ts`,
`src/components/programmer/FxSheet.tsx`, `src/api/surfacesApi.ts`, `src/api/looksApi.ts`

Two jobs, not one — and the first is no longer coordination but repair: backend A2 (`ab0ff8b`)
**has** deleted `EffectDto.presetId` and `GroupEffectDto.presetId`, so these declarations describe a
wire that has moved (see `FS-COORD-WIRE-FIELD-DELETIONS`, now P1, which also picks up
`presetApplications` and `presetCount`). **Delete the retired-concept fields**: the three
`presetId` declarations plus
`FxSheet.toEffectContext`'s pass-through copy (nothing downstream reads it; sitting in a mapping
whose siblings are all deliberate makes the residue look load-bearing), `FxEffectState.speedMasterIndex`
(the chip resolves the index itself). **Leave or consciously collapse the pure wire mirrors**: the
surfaces feedback cluster (`hasMotor`/`ringCc`/`ledFeedback`/… and the types existing only for
them) is the one worth collapsing — but reconcile it against `ControlDescriptorDto` first rather
than deleting: the gap round found the divergence runs both ways there
(`FS-TYPES-SURFACE-DESCRIPTORS`). **Corrects a FU premise**: `LookDetails.usedByCueIds`/
`usedByCueNames` are already on the wire and unread — `FU-FE-SHARED-LOOK-EDIT-GUARD`'s missing usage
count needs no backend work.

### `FS-DEAD-WS-METHODS`
**Six WS API methods declared, implemented, never called** · S3 · P3 · C2 · sonnet
`src/api/cloudSyncWsApi.ts`, `src/api/surfacesApi.ts`, `src/api/groupsApi.ts`

`cloudSyncWsApi.subscribeStarted` (strands `CloudSyncStartedEvent`), three
`surfacesApi.request*State` senders, `groupsApi.addFx`/`clearFx` (see `FS-TYPES-GROUPFX-WS`). All
senders/subscribers, so removal can't drop an inbound frame; if any is a deliberate protocol
placeholder, comment it instead.

Two of the six are already gone: `groupsApi.addFx`/`clearFx` went with `FS-COORD-GROUPS-WS`
(lighting-react `c1a8c44`), which deleted the whole module. Four remain.

### ~~`FS-DEAD-DEVDEPS`~~ — **landed**
**Eight unused devDependencies, including a Prettier-in-ESLint wiring never made**, `6398257` · S3
· P2 · C1 · sonnet
`package.json`, `eslint.config.js`

`autoprefixer`, `postcss` (Tailwind v4 runs through `@tailwindcss/vite`), `tw-animate-css` (the
*other* animate package is the loaded one), `vite-tsconfig-paths` (alias is hand-rolled),
`@eslint/compat`, `@eslint/eslintrc`, `eslint-config-prettier`, `eslint-plugin-prettier` — the last
two mean Prettier is not part of `npm run lint` at all. **Fix**: remove, then full `npm run check`
plus `npm ci` from clean. The Prettier pair is a decision: wire `eslint-config-prettier` in (a
behaviour change to a real gate) or drop both and leave formatting to `npm run format`. Sibling
finding: `FS-ARCH-ALERTDIALOG-DEP` *adds* a missing declaration — do them together as one manifest
pass.

Landed as seven removals, not eight: `eslint-config-prettier` was kept and wired in last in the flat
config (the operator took the "wire it in" arm), so ESLint no longer holds formatting opinions,
while Prettier itself still runs only from `npm run format`. Landed in **one commit with
`FS-ARCH-ALERTDIALOG-DEP`** rather than two — `package.json` and `package-lock.json` cannot be split
across two commits without an intermediate `npm install` that *adds* packages back, and the agent
sandbox blocks writes to npm's cache, so only removals can be installed.

### ~~`FS-DEAD-CSS`~~ — **landed**
**`.scrollbar-thin` and its three webkit child rules serve a deleted palette strip**, `51276c2` · S4
· P3 · C1 · haiku
`src/index.css`

Zero class references; the comment names the "horizontal palette strip" deleted in session 4. The
whole of the dead-CSS surface — everything else in the file is live or carries documented reasons
(the kotlin-playground rules stay).

### ~~`FS-DEAD-EXPORT-KEYWORD`~~ — **landed**
**Ten symbols exported but used only inside their own module**, `2eb6854` · S4 · P3 · C1 · haiku
`src/hooks/`, `src/lib/`, `src/components/programmer/ProgrammerScope.tsx`,
`src/components/looks/lookRefValue.tsx`

Not dead code — dead *export surface* that reads as API: `getPropertyChannels`,
`makeFallbackSlider`, `useResolvedChannelSource`, `useNextGoTarget`, `deepEqual` (whose last live
caller is dead `arraysEqual` — check whether it follows), `DEFAULT_ARRAY_INSET_M`, `LOCAL_SCOPE`,
`scopesEqual`, `lookValueColourCss`, `describeLookValue`. Drop the `export` keyword.
`RecordPreset` looks similar but stays exported — it types the context's public `openRecord`.

### ~~`FS-DEAD-PROTOTYPES`~~ — **landed**
**`src/prototypes/` is 2.4k lines of shipped-and-done design scratch inside the compiled tree**,
`1fc26ac` · S4 · P3 · C1 · haiku
`src/prototypes/`, `eslint.config.js`

Zero importers; all three prototyped surfaces have shipped; `model.ts`'s `computeWarnings` was
ported to `lib/promptBook/desync.ts` with the original left behind. Downgraded to S4 because the
arrangement is partly deliberate (eslint.config.js documents ignoring the `.jsx` files; the README
frames them as reference implementations) — honour that by **relocating** to `docs/prototypes/` (or
deleting) rather than leaving a directory a third of which is type-checked and linted for nothing.
Drop the eslint ignore entry with it.

Landed as relocated, with the ignore entry **replaced** rather than dropped: `npm run lint` is
`eslint .`, not `eslint src`, so the `.jsx` files would have kept failing to parse in their new
home. `src/prototypes/**/*.jsx` became `docs/prototypes/**`. The tsc half of the finding is fully
answered — `tsconfig.json` includes only `src`, so `model.ts` leaves the type-check entirely.

## 9. Duplication

### ~~`FS-DUP-AGGREGATION`~~ — **landed**
**Two implementations of "aggregate a property across heads", already numerically divergent**,
lighting-react `de1959e` · S2 · P1 · C3 · opus
`src/components/fixtures-list/useRowValues.ts`, `src/hooks/useGroupPropertyValues.ts`,
`src/components/fixtures/PropertyVisualizers.tsx`, `src/components/fixtures/GroupPropertyVisualizers.tsx`

`aggregateCellValue` and the `useGroup*Values` family both compute min/max + uniformity, averaged
RGB(WAUV) + swatch, and normalised pan/tilt — and they already disagree: the grid averages white
over heads that *have* a white emitter, the group card divides by all members (two RGBW at W=255
beside two RGB heads: W=255 in the grid, W=128 on the card). `aggregateCellValue`'s own doc argues
against exactly this. On top, `PropertyVisualizers`/`GroupPropertyVisualizers` (~1,130 lines) are a
structurally parallel widget family. **Fix**: make `aggregateCellValue` the single aggregation and
have the group visualizers read through it; state the extended-emitter averaging rule once at the
surviving site. `computeGroupColourValues` also feeds the 3D beam colour — carry that derivation
across explicitly or keep it deliberately separate; do not let unifying the swatch silently change
what the stage paints. **Sequencing**: contains `FS-BUG-PIXEL-CACHE-PERMUTATION`'s host code —
collapse first or fix the bug in the survivor (§4).

Landed as the aggregation collapse only. The group hooks project their descriptors into
`CellResolution`s and read through `aggregateCellValue`; the beam derivation was kept deliberately
separate, with a comment saying why (it answers "what does the bar throw", where a mean muddies a
red+blue bar to grey and hides one bright pixel on a dark one) — the stage paints what it painted
before, pinned by test. The **`PropertyVisualizers` / `GroupPropertyVisualizers` widget family was
not merged**: the **Fix** named the aggregation, and the ~1,130-line parallel widget set is a
separate question nothing here forced. A second divergence surfaced alongside the first — the group
card's `combinedCss` was `rgb(avgR, avgG, avgB)` where a single fixture's had always been
`computeCombinedCss`, so the two paths opened the same `ColourPickerPopover` on different colours;
unified with the emitter rule. `FS-BUG-PIXEL-CACHE-PERMUTATION` is untouched and now has one host.

### ~~`FS-DUP-ROW-SUBSCRIPTION`~~ — **landed**
**`useRowOwnership` and `useLocalRowValues` duplicate the whole per-row programmer subscription
mechanism**, lighting-react `abb4266` · S3 · P2 · C2 · sonnet
`src/components/fixtures-list/useRowOwnership.ts`, `src/components/fixtures-list/useScopedRowValues.ts`

Same key-signature memo (same eslint-disable, same comment), same dedupe, same version-ref bump,
same blind-transition listener, same cache shape — so `FS-BUG-STALE-ROW-SNAPSHOT` exists identically
in both copies, and in Local scope each `(target, property)` carries two `subscribeToKey`
registrations plus two global listeners per mounted row (~2× the visible window; the table is
virtualised). **Fix**: extract one hook owning subscription mechanics; consumers layer their
aggregation on top. Land the S1 fix through this extraction. Preserve: `useRowOwnership`'s public
shape, empty-cells-means-off, Local's `entry.owner !== 'layers'` predicate, the per-key split.

### `FS-DUP-EFFECT-COMPAT`
**Effect compatibility and sentinel-property resolution implemented twice** · S3 · P2 · C2 · sonnet
`src/components/fx/AddEditFxSheet.tsx`, `src/components/busking/useBuskingState.ts`

`allPropertyNames` (with the `'setting'`/`'slider'` sentinels and the dimmer/uv exclusion
predicate), `compatibleEffects`, `effectsByCategory` and the sentinel→property resolution exist in
both, the predicate written out four times total. Adding an emitter category is a two-file edit with
no tying test. (`FU-FE-USE-TARGET-PROPERTIES` does not cover this — every anchor it names was
deleted; that FU needs restating regardless.) **Fix**: one module owning `propertyNamesFor(target)`,
`compatibleEffectsFor(library, target)`, `resolveEffectProperty(target, effect)`; pin the sentinel
rule with a unit test. Keep AddEditFxSheet's explicit setting/slider pickers — a UI affordance, not
part of the rule.

### ~~`FS-DUP-OVERVIEW-TOGGLES`~~ — **landed**
**Four near-identical Overview toggles plus three alias hooks for one persistent toggle**,
lighting-react `e773197` · S3 · P2 · C1 · haiku
`src/components/*OverviewToggle.tsx`, `src/Layout.tsx`

Same Tooltip + ghost icon button, differing in icon and noun; the command palette already declares
the same four panels as plain data — which is the shape one component would take, and reveals a live
inconsistency (Stage: hand-rolled SVG in the toolbar, lucide `Theater` in the palette). **Fix**: one
`OverviewToggle` driven by one panel-descriptor array feeding both the toolbar and the palette; drop
the three `usePersistentToggle` alias hooks; keep the effects panel's lock + tooltip; pick one Stage
icon. Part of the Layout cluster (§4).

### `FS-DUP-COLOUR-POPOVER`
**`FxColourPicker` and `FxColourListPicker` duplicate the whole colour-popover body** · S3 · P2 ·
C2 · sonnet
`src/components/fx/FxColourPicker.tsx`, `src/components/fx/FxColourListPicker.tsx`

Same five blocks in the same order (HexColorPicker, hex input with the same commit guard, swatch
row, `FxColourTemplateRow`, extended-channel block), same three handlers, same reseed-on-open
effect. Only dnd-kit ordering and list serialisation are genuinely list-specific. **Fix**: extract
one `ColourEditorBody`; each picker keeps its trigger and seeding. The
reference-opens-at-resolved-colour and touching-the-picker-replaces-the-reference rules are
documented behaviour and must survive; `FxColourListPicker.test.tsx` passes unchanged.

### `FS-DUP-REDIRECTS`
**Seventeen byte-identical "redirect to the current project's equivalent" components** · S3 · P2 ·
C1 · haiku
one per route file, mounted from `src/App.tsx`

Same `useCurrentProjectQuery` + navigate-with-replace + spinner body, seventeen times, so the
loading/not-found behaviour has to be edited seventeen times. **Fix**: one
`<CurrentProjectRedirect to="looks" />` with `preserveSearch` and sub-path options; fold the four
small variants in as options. `LegacyProgramRedirect` must keep carrying the search string (`?cue=`
deep links are an external contract), and the legacy `run`/`cues`/`cue-stacks` targets stay
byte-identical.

### `FS-DUP-CHANNEL-SLIDER`
**Four copies of the labelled 0–255 channel slider row** · S4 · P3 · C1 · haiku
`src/components/fixtures/ExtendedChannelSlider.tsx`, `src/components/fx/FxColourListPicker.tsx`,
`src/components/fixtures/PropertyVisualizers.tsx`, `src/components/fixtures/GroupPropertyVisualizers.tsx`

One shared component exists and one picker imports it; its list sibling redeclares it byte-identical,
and two more private copies differ only in label styling. Fold to one with the label style as a
prop; rendered markup must not change (popover widths are tuned to it).

### `FS-DUP-TARGETKEY`
**Three spellings of the `type:key` target encoding; the named owner has no users** · S4 · P3 · C1
· haiku
`src/components/runner/program/CueCardEditor/targetUtils.ts`, `src/components/busking/buskingTypes.ts`

`targetUtils.targetKey`/`targetEquals` are imported by nothing (only `collectCueTargets` is, and it
inlines the same concatenation); `buskingTypes` has its own `targetKey` over a different union;
call sites also hand-spell the template literal. **Fix**: one `lib/` helper taking the normalised
`{type, key}` pair (the busking union carries `name` where the cue union carries `key`); delete the
unused pair. Note: `components/surfaces/targetUtils.ts` is a different, live module — leave it.
Coordinate with `FS-RES-CUECARDEDITOR-DIR`, which moves the file (§4).

### `FS-DUP-MARKER-ROW`
**The same cue separator renders two different ways depending on surface** · S4 · P3 · C1 · sonnet
`src/components/runner/MarkerRow.tsx`, `src/components/runner/program/ProgramMarkerRow.tsx`

Of the four cue-row renderers flagged in exploration, only this pair truly overlaps (the others do
distinct jobs): a locked separator on desktop Show renders a different structure from the same
separator in the phone list and the Prompt Book rail. **Fix**: `ProgramMarkerRow`'s locked branch
renders `MarkerRow`, keeping the grip-column spacer so rows don't shift as the lock flips; the
unlocked branch (grip, rename, delete) is the genuinely different job and stays.

### `FS-DUP-MINISTAGE-GEL`
**`MiniStage.pickColour` is a third, divergent copy of the gel arm of the colour dispatch** · S4 ·
P3 · C1 · haiku
`src/components/cues/MiniStage.tsx`

It re-implements the gel/default tail of `FixtureAppearanceSource` minus the `acceptsGel` gate the
shared version calls out explicitly — so a stale gel code on a colour-mixing LED tints the
mini-stage dot while all three other surfaces ignore it — hard-codes a fourth copy of
`DEFAULT_FIXTURE_COLOUR`, and its comment cites a `DimmerOnlyMarker` that no longer exists. **Fix**:
apply the `acceptsGel` gate (the fixtureType is already looked up), import the constant, drop the
dead reference. Keep it static — this surface deliberately draws simulated cue targeting, not the
wire.

## 10. Architecture

### ~~`FS-ARCH-CURSOR-OWNERSHIP`~~ — **landed**
**Two stores own the live-cue/armed-next cursors; several of the resulting copies have no reader**,
`d368cb1` · S3 · P2 · C3 · fable
`src/store/cueStacks.ts`, `src/store/runnerSlice.ts`, `src/hooks/useShowTransport.ts`

One `cueRunStateChanged` frame is written into two stores, and which copy wins is arbitrary per
field: `useShowTransport` returns `serverActiveCueId` from the RTK cache but `standbyCueId` from the
slice. The hook then keeps a *second* tracker of the server transition (`prevServerActiveCueRef` +
reset effect) because the slice's own `serverActiveCueId` isn't exposed. Unread copies:
`ShowTransport.serverActiveCueId` itself has no production consumer (`ShowPage` hand-computes the
identical expression; `PromptBookPage` uses the optimistic cursor), and `CueStack.standbyCueId` is
written twice and read never. **This needs a design decision, not a cleanup pass**: CLAUDE.md's
two-cursor model is two *questions* and is not what's duplicated — but the two `standbyCueId`s
genuinely carry different facts (cache: explicitly-armed-only; slice: effective next), so "delete
the unread one" is not automatically safe, and swapping `PromptBookPage`'s `statusOf` onto the
server cursor is a live-desk behaviour change. **Fix**: pick one owner for each server fact (the
RTK cache is the natural home), reduce `runnerSlice` to the genuinely local animation state, expose
what `ShowPage` currently hand-computes, and write down which cursor each surface reads and why.
`useShowTransport.test.tsx` pins the reconciliation (deferred reset mid-fade, `serverNextCueId`
preference, done-tick); GO/BACK must not restart a fade.

Grew in the landing: the "second tracker" diagnosis was half wrong — `prevServerActiveCueRef` was
never a workaround for the slice's `serverActiveCueId` being unexposed, because the two answer
different questions (a first cut comparing the stores lost positional done ticks on multi-cue
snapshot jumps and misfired on optimistic mutation patches, caught in review). Landed as: cache
owns the server facts, `CueStack.standbyCueId` deleted, ShowPage's four hand-computed reads moved
onto the exposed `serverActiveCueId`, and the two reconcile effects merged into one with the same
change-detection semantics; the ownership map is written down in `useShowTransport`'s docblock and
CLAUDE.md. Rig check filed as `FU-MANUAL-CURSOR-OWNERSHIP` in `manual-validation.md`.

### `FS-ARCH-BUSKING-GOD-HOOK`
**`useBuskingState` is a 617-line hook mixing selection, derivation, presence rules and four
mutation paths** · S3 · P3 · C2 · sonnet
`src/components/busking/useBuskingState.ts`

Four `useState`s, five derived memos, eleven callbacks; the group-vs-fixture request builder written
twice; the property-name derivation written three times in one file. (The fresh-object-per-render
claim from exploration does *not* hold — every member is individually stable — so the cost is
comprehension, not renders.) **Fix**: split along its own comment boundaries — pad selection, effect
authoring (share the request builder), presence — and pull the property-name derivation from
`FS-DUP-EFFECT-COMPAT`'s shared module. `programmerEntryFor`'s `owner === 'web'` rule is
load-bearing: a pad must not light on, or clear, a Locate's or a layer's entry.

### ~~`FS-ARCH-IMPORT-CYCLE`~~ — **landed**
**The tree's only runtime import cycle: `CueSlotOverviewPanel` ↔ `CueSlotEditAssignPanel`, with no
lint rule to catch the next one**, lighting-react `934025d` (+ `0ed1b31`) · S3 · P2 · C1 · sonnet
`src/components/CueSlotOverviewPanel.tsx`, `src/components/CueSlotEditAssignPanel.tsx`, `eslint.config.js`

A genuine value-import cycle (the only one in the tree once type-only imports are stripped), in the
codebase whose CLAUDE.md documents what a cycle costs here (the `startOAuthIdentityBridge` TDZ
break that only shows up as a broken app in the browser). **Fix**: extract `SlotItemContent` +
`CueSlotAssignDragData` into a third module; then add `eslint-plugin-import` with `import/no-cycle`
as an error — it lands green immediately and guards the failure mode permanently.

Landed in two commits, because the agent sandbox cannot add npm packages (§4 constraint 7) and the
operator ran the install by hand. The rule needed more than turning on, and both halves of that
fail **silently**: without `settings['import/parsers']` mapping `.ts`/`.tsx` to the TypeScript
parser the plugin cannot read a dependency file at all and reports nothing whatsoever, and without
an alias-aware resolver it skips the ~70% of this tree's imports written `@/…`. The stock node
resolver understands neither, so the obvious configuration is a rule that passes forever and
catches nothing. `eslint-import-resolver-alias.cjs` teaches it the one prefix; it is a separate
CommonJS module because eslint-module-utils `require`s resolvers by name, and its path is computed
absolutely because a repo-relative name resolves against the *linted file's* directory. Verified by
restoring the pre-`934025d` cycle and watching the rule report it. `eslint-import-resolver-typescript`
would read the tsconfig `paths` directly and should replace the shim if it ever needs to do more.

### `FS-ARCH-GRID-IN-ROUTES`
**`FixturesListContainer` — the shared value grid — lives in a route module a component imports** ·
S3 · P2 · C2 · sonnet
`src/routes/FixturesList.tsx`, `src/components/programmer/ProgrammerGrid.tsx`

The only component→route import in the tree, for the single most-reused grid in the app; four
modules in `components/fixtures-list/` already document themselves against the container by name.
**Fix**: move it (plus its props type and `LIST_PAGE_CARD_CLASS`) into
`components/fixtures-list/`, leaving the route as the thin page. Must be a pure move preserving
component identity — `useListSelection` clears its Redux scope on unmount and
`ProgrammerPage.test.tsx` pins `gridMounts`; don't touch the null-scope-vs-Output distinction.

### ~~`FS-ARCH-LOCALSTORAGE-BOOT`~~ — **landed**
**Unguarded `localStorage` on the boot path, against the policy the tree states explicitly**,
lighting-react `6cdd030` · S3 · P2 · C1 · haiku
`src/lib/theme.ts`, `src/main.tsx`, `src/ThemeToggle.tsx`, `src/components/CueSlotOverviewPanel.tsx`

`usePersistentState`'s docblock states the policy (all storage access wrapped) and most sites follow
it; `getInitialTheme()` runs a bare `getItem` at module scope in `main.tsx` before React mounts — a
throw there (blocked site data, embedded view) is a blank page with no error boundary. Three lesser
bare accesses in ThemeToggle and the cue-slot panel. **Fix**: try/catch with a
what-degrades comment at all four; the boot-path one is the half that matters. Keep the raw string
encodings — both keys predate the helper and migrating loses stored preferences.

### ~~`FS-ARCH-ALERTDIALOG-DEP`~~ — **landed**
**`@radix-ui/react-alert-dialog` is an undeclared dependency, resolved only by hoisting from the
`radix-ui` umbrella**, `6398257` · S3 · P2 · C1 · haiku
`package.json`, `src/components/ui/alert-dialog.tsx`, `src/components/ui/context-menu.tsx`

The umbrella exists for one primitive (`context-menu`); removing or replacing it breaks the build at
`alert-dialog.tsx` — which `ui/sheet.tsx`'s unsaved-changes guard depends on. Fails loudly at build,
not runtime, hence S3. **Fix**: declare it at the locked version; decide the umbrella's fate
deliberately (move alert-dialog onto it, or context-menu off it). Don't change alert-dialog's
runtime version as a side effect — the sheet guard depends on its close semantics. Do with
`FS-DEAD-DEVDEPS` as one manifest pass.

Landed as: declare **both** primitives individually and drop the umbrella, rather than moving
alert-dialog onto it — that matches the other twelve `@radix-ui/react-*` declarations, and the
installed versions are byte-identical to what the umbrella pinned (alert-dialog 1.1.23,
context-menu 2.3.7), so the sheet guard's close semantics are unchanged. Takes 841 lines out of
`package-lock.json`, because the umbrella pulled in every Radix primitive.

### `FS-ARCH-SURFACES-PATTERN`
**`store/surfaces.ts` streams four WS states through `useState`+`useEffect` instead of the RTK
pattern nine siblings use** · S4 · P3 · C2 · sonnet
`src/store/surfaces.ts`

Real inconsistency, but the costs cited in exploration don't materialise (the two consumers are
sibling routes never mounted together; nothing wants to invalidate or select these four). Convert to
`queryFn` + `onCacheEntryAdded` mirroring `speedMasters.ts` when touching the file anyway; the
subscribe-replays-last-snapshot-synchronously property must stay true of the seed, and the pickup
Map reduction becomes a pure function.

### `FS-ARCH-BRIDGE-EVAL`
**~23 module-scope WS-bridge subscriptions vs three documented deferred ones — the rule is implicit**
· S4 · P2 · C2 · sonnet
`src/store/*.ts`, `src/main.tsx`

Most store slices subscribe at module scope; `looks`/`templates`/`oauthGithub` defer via
`start*Bridge()` from `main.tsx` because they sit on the earliest render path (TDZ hazard,
documented). The exploration framing ("twelve slices do the forbidden thing") misread the doc —
`store/oauthGithub.ts` treats module-scope as the accepted default and deferral as the exception —
and today no module in `lightingApi`'s import closure value-imports a store slice, so there is no
live cycle. What's missing is the **rule written down**: which pattern a new slice uses and what
makes a slice "early-path". **Fix**: state it once (CLAUDE.md or a comment at `createLightingApi`),
with the full current census (the deferred trio and the ~20 module-scope sites), rather than
migrating everything. Migrate only if `import/no-cycle` (see `FS-ARCH-IMPORT-CYCLE`) proves
insufficient.

## 11. Structure and naming

Where things live and what they're called. Individually small; collectively they are why a new agent
gets lost. Most are best done as one coordinated pass (§4).

### `FS-RES-CUECARDEDITOR-DIR`
**`runner/program/CueCardEditor/` is a directory owned by nobody** · S3 · P2 · C1 · haiku
`src/components/runner/program/CueCardEditor/`, `src/components/cues/`

Leftover of the deleted three-pane editor: `CuePropsPane`'s sole consumer is
`components/cues/CuePropertiesSheet`; `targetUtils.collectCueTargets`' consumers are three
`components/cues/` modules plus `CueCardBody` — none is `CueCardEditor`; `ProgramCueRow` is a 16-line
pass-through existing purely to hide the nesting. So newer code reaches four levels into a directory
named for a component that doesn't use these modules. The sibling `components/cues/editor/` has the
same orphaned shape (its live pair `AddLayerSheet`/`LayerPicker` is consumed only from
`components/programmer/`; its third file is dead — `FS-DEAD-ORPHAN-FILES`) and should be dissolved
in the same pass. **Fix**: move `CuePropsPane` + `targetUtils` into `components/cues/`, hoist
`CueCardEditor.tsx` one level, delete `ProgramCueRow`, relocate `AddLayerSheet`/`LayerPicker` beside
their consumer. Pure moves; the two test files' import/mock paths move with them. Do in the same
pass as `FS-RES-RUNNER-DIR`.

### `FS-RES-RUNNER-DIR`
**`components/runner/`'s `program/` and `run/` subdirs are named for deleted routes** · S4 · P2 ·
C2 · sonnet
`src/components/runner/program/`, `src/components/runner/run/`, `src/routes/ShowPage.tsx`

The verifier cut this down from "rename the whole tree": `runner` itself is legitimate (the docs
call `/show` "the runner", and `runnerSlice`/`useRunnerAnimation` are squarely playback) — the
residue is the two subdirectory names and the `Program*` component names, which point at routes that
now only redirect. **Fix**: flatten `program/` into `runner/`, rename `run/` → `mobile/` (it is the
phone takeover its own note describes), rename `ProgramView`/`ProgramMarkerRow` to Show-era names.
`ShowPage.test.tsx` mocks five of these by path string — update in the same commit. Do together
with `FS-RES-CUECARDEDITOR-DIR` and after the perf items touching these files, or before all of
them — not interleaved (§4).

### `FS-RES-ROUTES-CONVENTION`
**`routes/` mixes route modules, settings-tab bodies, orphan redirects and a pure helper — the
convention is real but undocumented, with three genuine strays** · S4 · P2 · C2 · sonnet
`src/routes/Riggings.tsx`, `src/routes/StageRegions.tsx`, `src/routes/RunPage.tsx`,
`src/routes/CloudSync.tsx`, `src/routes/ProgrammerPage.tsx`

The verifier reframed this: most of it is one uniform pattern (a former route file keeps its
resource identity and hosts its legacy redirect plus the tab body that replaced it), not drift. The
genuine strays: `Riggings.tsx`/`StageRegions.tsx` are routed nowhere at all (pure tab bodies
mis-filed as routes, one carrying a dead redirect export); `RunPage.tsx` is 63 lines of two
redirects rendering no page; `formatRepoUrl` is a pure helper exported from a route module; and
`LegacyProgramRedirect` lives in `ProgrammerPage.tsx` rather than `ShowPage.tsx` — the exact
`/program` vs `/programmer` confusion CLAUDE.md warns about, reproduced in file layout. **Fix**:
move the two tab bodies to `components/`, `formatRepoUrl` to `lib/`, collect the legacy redirects
into one `routes/legacyRedirects.tsx` (absorbing `RunPage.tsx`), and write the convention down.
Every URL and redirect target stays byte-identical — `?cue=` deep links are an external contract.

### `FS-RES-CLOUDSYNC-SPLIT`
**`routes/CloudSync.tsx` is a 1,306-line module holding two routes and twenty components — beside
the populated `components/cloudSync/` directory it already imports from** · S3 · P3 · C1 · haiku
`src/routes/CloudSync.tsx`, `src/components/cloudSync/`

(The exploration claim that the directory is *unused* was wrong — it holds `ConflictPanel`,
`RepoPicker`, `DeviceFlowModal`, `SyncReauthBanner`, all live. The defensible finding is the
inverse: the split was started and abandoned for the bulk of the feature.) **Fix**: move the eight
panels, dialog, row/badge components and pure helpers across, leaving the two route bodies plus
redirect. Mechanical — but the five `connected === true && reauthRequired !== true` gates CLAUDE.md
names (three live in this file) must survive verbatim; collapsing either half reintroduces the
25-day silent-failure bug.

### `FS-RES-PROMPTBOOK-GODPAGE`
**`PromptBookViewerPage` is ~1,000 lines with 54 hook calls and 15 hand-placed `noteEdit()` sites**
· S3 · P3 · C3 · sonnet
`src/routes/PromptBookPage.tsx`

764 lines of hook body before any JSX; anchor CRUD, annotation dialog, region resolution, desync
panel, undo snapshots and tool palette interleaved in one scope. The concrete consequence: the
auto-relock idle timer is reset by 15 scattered `noteEdit()` calls where `ShowPage` does the same
with two capture handlers — a new edit affordance here silently fails to reset the countdown.
**Fix**: lift cohesive slices into named hooks under `lib/promptBook/` / `components/promptbook/`
(the boundary already exists), and replace the 15 call sites with the capture-handler approach.
`useEditLock`'s shared-slice semantics and transition-only re-arm are pinned by
`useEditLock.test.tsx` and must hold. `routes/Stage.tsx` has the same shape at smaller scale — same
treatment when touched.

### `FS-RES-FIXTUREMODEL-SPLIT`
**`FixtureModel.tsx` mixes a 1,500-line R3F component with pure beam-cookie geometry, forcing its
unit test into jsdom** · S3 · P3 · C2 · sonnet
`src/components/stage3d/FixtureModel.tsx`, `src/components/stage3d/beamOptics.ts`

Four exported pure geometry functions live inside the component module, so their node-runnable test
must open with `@vitest-environment jsdom` just to survive the drei/fiber imports — against the
documented pattern (`beamOptics.test.ts` runs in default node). **Fix**: move the cookie/lobe maths
into a peer module beside `beamOptics.ts` taking `three` types only. Must not break the imperative
per-frame write path — these functions mutate vectors/materials in place from `useFrame`; making
them allocate would cost per-frame garbage on the canvas.

### ~~`FS-RES-PRESETPICKER`~~ — **landed**
**`FxSection`'s `presetPicker` prop and doc describe a deleted synthetic-fixture preset branch — and
claim a suppression the code doesn't perform**, `d2138c0` · S3 · P2 · C1 · haiku
`src/components/fx/FxSection.tsx`

The header says "Suppress this whole panel" for a preset mode that no longer exists; the code
suppresses nothing. The slot is filled by `LookTogglePicker` at both call sites. **Fix**: delete the
stale paragraph, rename the prop to `lookPicker` (three sites, one file). Value: the file stops
advertising a third rendering mode a reader will hunt for.

### ~~`FS-RES-PALETTERESULT`~~ — **landed**
**`UpdateDialog` renders an unreachable branch from a field the server deleted**, `8a8437a` · S3 ·
P2 · C1 · haiku
`src/store/programmerOps.ts`, `src/components/programmer/UpdateDialog.tsx`

The render half of `FS-TYPES-PALETTE-WIRE-ARMS`: a full `result.paletteResult &&` block with a
four-line explanatory comment, plus the `invalidatesTags` arm keyed on it. Delete branch, comment,
arm, and `PaletteUpdateResult`; `lookResult` is the live successor rendering the same shape. The two
deliberate `ref:`-era keeps CLAUDE.md names (`validateLookRows`' rejection, `StateMigrations`'
upgrade path) are backend-side and unaffected.

Noted in the landing, not fixed: that `invalidatesTags` arm was the *only* place `updateProgrammer`
invalidated `Look`/`LookList`, and it was keyed on a field the server can no longer send — so a Mode
A Update that writes a **Look** has never refreshed the Look caches, and still doesn't. Deleting the
arm is behaviour-preserving (it could never fire). `recordLook` invalidates correctly, so the gap is
Update-only; whoever takes `FS-TYPES-PALETTE-WIRE-ARMS` should decide whether `lookResult` earns the
tags the palette arm was holding.

### ~~`FS-RES-LIGHTING-EDITOR-DIR`~~ — **landed**
**`components/lighting-editor/` is a one-file directory named for a pre-Programmer era**, `6ded2cc`
· S4 · P3 · C1 · haiku

Holds only `EditorContext.tsx`; all four consumers are programmer-side. Move it into
`components/programmer/` (one consumer is a `vi.mock` path string). Keep the file's doc comment
intact — it is the authoritative record of why there is no `cue` arm.

### ~~`FS-RES-LOOKREFVALUE-NAME`~~ — **landed**
**`lookRefValue.tsx` is named for the retired `ref:` grammar its own doc says it can never handle**,
`25b181e` · S4 · P3 · C1 · haiku
`src/components/looks/lookRefValue.tsx`

A reader grepping for surviving `ref:` machinery hits it first. Rename to `lookValueChips.tsx`
(three importers). Don't touch the deliberate `ref:` survivors.

### ~~`FS-RES-PANECHROME`~~ — **landed**
**`components/cues/paneChrome.tsx` justifies its home with consumers deleted in 2a**, `32ff4fa` ·
S4 · P3 · C1 · haiku
`src/components/cues/paneChrome.tsx`

Its only direct importer is `LookStack` — though `LookStack` is itself shared back to the cue side,
so the doc is stale in its specifics rather than wholly false. Move beside `LookStack` (or inline)
and drop the obsolete rationale; hand any caller-less export to the dead-code pass.

### ~~`FS-RES-ANON-CATCH`~~ — **landed**
**Three bare `.catch(() => {})` where the codebase has a named helper for exactly that**, `e50c6e6`
· S4 · P3 · C1 · haiku
`src/hooks/useShowBarProps.ts`, `src/components/EffectsOverviewPanel.tsx`

`ignoreReportedError` exists precisely to mark the deliberate sink (eight sites use it), and the
middleware file itself warns about the anonymous form. Replace the three; verify each endpoint is
genuinely absent from `SILENT_ENDPOINTS` (they are) — a silenced endpoint plus an anonymous catch
would be truly invisible.

### ~~`FS-RES-STRAY-CAPTURES`~~ — **refuted at HEAD**
**Four `capture *.json` DMX debug dumps sit at the repo root** · S4 · P3 · C1 · haiku
repo root

Committed debug artefacts. Delete (or move under a gitignored scratch dir) and add a `.gitignore`
pattern so the next capture doesn't land in the tree.

**Refuted at HEAD**: the four dumps are untracked working-tree scratch, not committed —
`.gitignore:27` already carries `capture*.json` (added with the Stage 3D `profileHarness` work) and
`git check-ignore` matches all four. There is nothing in the tree to delete and no pattern to add;
what is left is a local file the operator can remove whenever they like.

## 12. Docs and stale rationale

The refactors' most insidious residue: rationale comments and CLAUDE.md paragraphs that now describe
deleted machinery, in a codebase whose agents demonstrably obey written rationale. Each item below
names text that would make a competent agent do the wrong thing.

### ~~`FS-DOCS-SPEEDMASTERS`~~ — **landed**
**CLAUDE.md §Speed Masters and two code comments describe the deleted 2..N split and a
`SpeedMastersStrip` that no longer exists**, lighting-react `a69e07d` · S4 · P2 · C1 · haiku
`CLAUDE.md`, `src/routes/SpeedMasters.tsx`, `src/routes/ResetPasswordPage.test.tsx`

No `SpeedMastersStrip` symbol exists; `components/SpeedMasters.tsx` renders **every** master
including M1 and its docblock explains why the split was removed (the ShowBar BPM tile no longer
exists either). A reviewer following CLAUDE.md would reintroduce the split brain the component was
rewritten to remove. **Fix**: rewrite CLAUDE.md §Speed Masters to match the component's docblock;
fix the two stale mentions. Keep the still-true halves: stored vs live BPM, the two per-effect uuid
references, master 1 undeletable / what a null uuid means.

Grew in the landing: the same section's `BeatIndicator` paragraph was stale for a different reason
— it described a fallback to the legacy unkeyed `beatSync`, which went with backend D2. Rewritten
alongside, including the `useMaster1Uuid` correction `FS-COORD-LEGACY-TEMPO` established (null means
master 1 on the tempo *write* messages only).

### ~~`FS-DOCS-CLAUDEMD-CUE-ARM`~~ — **landed**
**CLAUDE.md claims `EditorContextValue`'s `cue` arm is kept; the code removed it in 2b**,
lighting-react `62b64eb` · S4 · P2 · C1 · haiku
`CLAUDE.md`, `src/components/lighting-editor/EditorContext.tsx`

The file's own doc says the opposite of CLAUDE.md ("There is no `cue` arm … removed in 2b"). An
agent reading CLAUDE.md will hunt for branches that don't exist or reintroduce the arm believing it
deliberate. **Fix**: rewrite the paragraph — the surviving piece is the `409 CUE_EDIT_SESSION_OPEN`
handling (true only until backend D1: the decision to retire `cueEdit.*` is taken, and
`FS-COORD-CUEEDIT-RETIRE` then deletes the handling too — write the rewrite so it doesn't enshrine
a keep that is about to evaporate).

### ~~`FS-DOCS-CLAUDEMD-PROVENANCE`~~ — **landed**
**CLAUDE.md's provenance section names `lookId`/`lookName`, replaced by `layerSource`**,
lighting-react `4eebd54` · S4 · P3 · C1 · haiku
`CLAUDE.md`, `src/api/programmerWsApi.ts`

The doc's "must stay in `provenanceSignature`" invariant is right but names two fields that no
longer exist and misses the polymorphism (a Look and a template can share an int PK) that is the
whole reason the signature reads a source object. Update to `layerId` + `layerSource` with the why.
Same drift that let the stale test in `FS-TEST-PROVENANCE-PIN` survive.

### ~~`FS-DOCS-COMPATIBLELOOKIDS`~~ — **landed**
**`compatibleLookIds` is documented as type-gated and deferred-only in three places; it is neither**,
lighting-react `0bcda19` · S4 · P2 · C1 · haiku
`src/api/groupsApi.ts`, `src/store/fixtures.ts`, `src/components/fx/LookTogglePicker.tsx`

The producer is capability-only (D6) and never excludes bound rows; "deferred Looks" describes
nothing since session 3 made every Look row bound. `LayerPicker.tsx` already carries the corrected
wording, so the codebase contradicts itself on one field. **Fix**: align all four comments with
`LayerPicker`'s; say what a toggle onto one target does for a Look whose rows name other fixtures.
Do not restore a client-side filter — compatibility belongs to the backend (and see
`FS-BE-COMPATIBLEIDS`, §14, for the rows-only hole itself — still owed, so the corrected wording
here has to describe the hole rather than promise it is closed).

Landed as scoped: all three comments now match `LayerPicker`'s, the rows-only hole is stated as the
backend's to close, and no client-side filter was added. `FS-BE-COMPATIBLEIDS` is still owed.

### ~~`FS-DOCS-OUTOFSCOPE-COMMENT`~~ — **landed**
**`RecordSkipReason.OUT_OF_SCOPE`'s comment names "palette routes" and claims they are the only
scoped ones — both halves wrong**, lighting-react `5462720` · S4 · P3 · C1 · haiku
`src/store/programmerOps.ts`

The backend arm says the *Look* routes; a targeted cue Record passes a scope too; "palette" is
retired vocabulary. Reword; sweep the file's other retired-vocabulary comments in the same pass
(`RecordRequest.targets` also says "unlike a palette").

### ~~`FS-DOCS-ELEMENT-KEY-INVARIANT`~~ — **landed**
**`LookRowStore` cites `syntheticFixture.ts` as the record of the element-key invariant; that file
was deleted**, lighting-react `8924b9e` · S4 · P2 · C1 · sonnet
`src/components/programmer/LookRowStore.tsx`

The invariant (element keys are element-local suffixes, never parsed or synthesised client-side) is
real and now documented nowhere. State it inline or relocate it beside the other value-grammar rules
in `src/lib/` and cite that. `FU-LOOK-ELEMENT-ROWS` tracks the behaviour gap; this is only about the
rule having a live home.

Landed as the inline option, not the relocation: `src/lib/`'s value-grammar modules are about
*values*, and an element key is target addressing, so a home there would have needed a new exported
symbol with no caller. `LookRowStore` is the only code the rule constrains, so the rule lives beside
it.

### ~~`FS-DOCS-REF-RATIONALE`~~ — **landed**
**`programmerValue.ts` and `useCellWriters` still teach the retired `ref:` grammar as current**,
lighting-react `0caf6aa` · S4 · P3 · C1 · haiku
`src/lib/programmerValue.ts`, `src/components/fixtures-list/useCellWriters.ts`

One doc block says `ref:{uuid}` "can now appear" as an entry's value forty lines above the note
recording its retirement; `writePosition`'s comment justifies a call-site choice by a distinction
that no longer exists. The gap round added the same module's other half: `parseProgrammerValue`'s
doc still claims the positional `P1` grammar "survives" in `colourUtils.ts` (it holds only `tmpl:`
now), and `programmerValue.test.ts` justifies its `'P1'` case with the same dead claim — the
assertion is now just another unparseable string. **Fix**: rewrite both files' doc blocks to one
rule — this parser reads literals only; `ref:` and positional `P*` are both retired, `tmpl:` is
legal only in an effect parameter — fold the `'P1'` test case into the junk-strings case, and
repoint the `parseProgrammerEntryValue` tombstone at `colourUtils.test.ts`'s `tmpl:` coverage.
Leave the live `ref:` rejections alone.

Grew in the landing, slightly: `parseProgrammerEntryValue`'s docblock claimed "its two callers" and
there are three (`useScopedRowValues`, `useCellWriters`, `programmerChannels`), and its stated reason
for existing was the `ref:` branch it no longer has — restated as keeping one entry-shaped reader.
The `describe('parseProgrammerEntryValue')` tombstone's claim that the positional form "did not"
retire went too.

### ~~`FS-DOCS-STALE-COMMENTS`~~ — **landed**
**Batch: ~14 rationale comments naming callers, renderers or files that no longer exist**,
lighting-react `afb2af8` · S4 · P2 · C1 · haiku
`CLAUDE.md`, `src/components/ShowBar.tsx`,
`src/components/runner/program/CueCardEditor/CueCardEditor.tsx`, `src/store/saveStatusSlice.ts`,
`src/components/cues/TimingBadge.tsx`, `src/api/fxApi.ts`, `src/api/cuesApi.ts`,
`src/lib/cueUtils.ts`, `src/store/errorToastMiddleware.ts`, `src/components/looks/LookStack.test.tsx`,
`src/api/fixtureTypeHierarchy.ts`, `src/api/cueStacksApi.ts`, `src/components/runner/run/RunMobile.tsx`

The collected one-liners, each verified against the tree (the last four sat in a batch that missed
its verdict pass on a bookkeeping mismatch — re-verify each at fix time, it's a grep apiece):
`ShowBar`'s Blind comment claims host-conditional rendering that 2b deliberately removed (the most
dangerous one — acting on it reintroduces the drift `useShowBarProps` exists to prevent);
`CueCardEditor`'s `@container` comment names `bodyRef`/`tabsBreakpoint` (deleted with the tabs) and
its closing comment recommends "the programmer-wide Make hard", deleted in session 4 (and the
honest replacement wording depends on backend D5, which proposes deleting the caller-less
`/flatten` route — don't cite flatten as live if that stands);
`saveStatusSlice` describes an entry no longer in the list; `TimingBadge` says "preset/effect
summary cards"; `fxApi.speedMasterIndex` claims the FX-sheet chip renders it (the chip resolves the
index itself); `cuesApi`'s presetApplications keep-note (goes with `FS-DEAD-CURRENTCUESTATE`);
`cueUtils`' claimed reorder/densify callers (goes with `FS-DEAD-CUELAYER-HELPERS`, and CLAUDE.md
repeats the claim); `errorToastMiddleware`'s `CopyCueDialog.tsx` path (goes with
`FS-DEAD-RTKQ-HOOKS`); `LookStack.test.tsx` citing a deleted `LayersPane.test.tsx` as the cue-side
coverage; `fixtureTypeHierarchy` naming the deleted Look editor among its consumers;
`cueStacksApi` citing `docs/cue-stacks-engineering.md` without the `lighting7/` qualifier (reads as
a dead local path); `RunMobile`'s summary still framing itself against the Run view. **Fix**: fix
each in the same change that resolves the code it describes where one exists; the rest as one
mechanical docs pass, verifying each replacement against the tree rather than the surrounding prose.

Grew in the landing, by two sites the list did not name and one it did that was already fixed:

- **CLAUDE.md's flatten paragraph** (§"All three Make Hard routes are gone") presented
  `POST /{projectId}/cues/{cueId}/flatten` as the live replacement, which is exactly what
  `FS-COORD-PING`'s surviving constraint says it must not do — so it landed here, with the two
  design constraints kept as notes for a reimplementation rather than as a live route's contract.
  That constraint is now spent.
- **`ProgrammerLookStack.onMove`** justified not renumbering by naming "the cue path's
  `reorderCueLayers`", asserting a live cue path that does not exist. Same false claim as the
  `cueUtils` docblocks, fixed in the same commit.
- `cuesApi`'s `presetApplications` keep-note was already gone with `FS-DEAD-CURRENTCUESTATE`;
  nothing to do.

`FS-DEAD-CUELAYER-HELPERS` still owns the decision on whether the two helpers go; this only made
their doc comments, their one referring call site and CLAUDE.md stop claiming callers.

## 13. Tests

The pattern across these: CLAUDE.md declares an invariant load-bearing, and either the pin rotted
across the refactors or it never existed. Most fixes are one focused test file. (The script-editor
subsystem's zero-test state is `FS-TEST-EDITOR-PINS`, filed with its cluster in §5.)

### ~~`FS-TEST-LOOKONLY-GATE`~~ — **landed**
**The LOOK-only gate — the guard against silently converting a generic template to per-fixture — has
no test**, lighting-react `3a67b93` · S2 · P1 · C1 · sonnet
`src/components/programmer/LookRowStore.tsx`, `src/components/programmer/LookRowStore.test.tsx`

CLAUDE.md's rule that `LookRowStore` engages only for a LOOK layer is one expression
(`layer?.source.kind === 'LOOK' ? … : undefined`), and the suite never constructs a TEMPLATE-source
layer — the obvious "simplification" to `layer?.source.id` leaves everything green while making a
focused template's rows editable, which is precisely the silent-conversion CLAUDE.md names.
`LayerRowNotices` explains, it does not gate. **Fix**: a case focusing a TEMPLATE-source layer,
asserting on the store's own output (query skipped, empty `serverRows`, `setValue` a no-op) so the
pin survives notice rewording.

### `FS-TEST-PUBLICPATH`
**The publicPath auth/boot-gate bypass predicate is untested and unexported** · S2 · P2 · C2 ·
sonnet
`src/App.tsx`

The predicate that switches *both* gates off carries two documented traps (matches routes not
prefixes; the `i` flag is load-bearing against auto-capitalising phone keyboards), and no test
touches it — the test CLAUDE.md cites pins a consequence, not the matching. **Fix**: extract
`isPublicPath(pathname)` into `src/lib/publicPath.ts` with its comments, and pin the named cases:
`/reset/abc` and `/device/abc` true (± trailing slash), `/Device/abc` true, `/device/` and `/device`
false, `/device/abc/def` false.

### `FS-TEST-PROGRAMMER-SCOPE`
**`focusLayer`'s membership guard and the removed-layer fallback are unpinned** · S3 · P2 · C1 ·
sonnet
`src/components/programmer/ProgrammerScope.tsx`

CLAUDE.md calls the membership guard the one that bites (a cue's `layerId` must be refused), and no
test asserts it — `FixturesTable.test.tsx` stubs the scope hooks, and while `ProgrammerPage.test.tsx`
does mount the real provider (pinning landing scope, the Output/Local switch and no-remount), it
never exercises `focusLayer` refusal or the focused-layer-removed fallback to Output. A regression
partly self-heals via that fallback, hence S3. **Fix**: `ProgrammerScope.test.tsx` over a mocked
layers query pinning refusal-returns-false, accept-switches, removal-falls-back, and the actions
context identity staying stable across a scope change (the file's own doc says that stability is the
point of the split contexts).

### ~~`FS-TEST-CUEUTILS-TRIGGERS`~~ — **landed**
**`cueUtils.test.ts` pins fourteen layer fields (CLAUDE.md says thirteen) and none of the trigger
fields its own docstring claims**, lighting-react `771ce87` · S3 · P2 · C1 · haiku
`src/lib/cueUtils.test.ts`, `CLAUDE.md`

`templateId` made it fourteen; CLAUDE.md's count drifted (say "every field" instead of a number so
it can't re-rot). The docstring claims triggers are pinned field-by-field too, but the fixture's
`triggers: []` exercises none of the six — the identical silent-drop failure mode, unguarded behind
a docstring that says it's guarded. **Fix**: a trigger with six non-default fields, pinned
individually in the layer test's style, plus the `scriptName`-is-stripped assertion.

### `FS-TEST-COLOUR-TEMPLATES`
**`FxColourTemplates` is untested, and its offerable filter is stricter than CLAUDE.md states** ·
S3 · P2 · C2 · sonnet
`src/components/fx/FxColourTemplates.tsx`, `CLAUDE.md`

The only suite touching `useColourTemplates` runs the no-project path. And `isOfferable` requires
`rows.length === 1` on top of the documented `family === 'COLOUR' && isGeneric`, so a two-row
generic colour template is silently unofferable and nothing says so. **Fix**: a test file over a
mocked template list pinning all three exclusions and the `labelFor` loading/resolved/dangling
split; decide whether the `rows.length === 1` clause is intended and state it in CLAUDE.md either
way.

### `FS-TEST-INDICATOR-LINK`
**`ProgrammerIndicator`'s link-vs-inert split is unpinned (the CLAUDE.md path trap itself is now
historical)** · S4 · P3 · C1 · haiku
`src/components/ProgrammerIndicator.test.tsx`

All seven cases mount at `/projects/1/show`; none asserts the badge is an inert div (not a Link) on
the programmer itself. The bare-`startsWith` trap CLAUDE.md warns about cannot actually bite at the
current path value — it diverges only on non-existent siblings — so what's worth pinning is just the
on/off-programmer rendering split. **Fix**: parameterise the mount route; assert link+tooltip at
`/show`, inert at `/programmer` and `/programmer/fx`.

### `FS-TEST-PROVENANCE-PIN`
**The provenance-signature test pins field names that no longer exist, and the `layerSource` arm of
the signature has no test at all** · S3 · P2 · C1 · sonnet
`src/api/programmerWsApi.test.ts`

The "wakes a cell when only the winning layer changed" test sends `lookId`/`lookName` — fields
`ProvenanceEntry` no longer has — through an `unknown`-typed frame helper, so the stale shape passes
untyped. The core invariant is still pinned (the frames differ in `layerId`, so dropping that from
the signature would fail the test) — what is *unpinned* is the arm added for a documented hazard:
`layerSource.kind/.id/.name`, guarding a Look and a template sharing an int PK. **Fix**: retype the
frame helper so stale field names fail to compile; rewrite the fixture to the current shape; add the
missing case (two frames differing only in `layerSource`, `layerId` and `source` held fixed).

## 14. The backend seam

The sibling [backend-post-refactor-sweep.md](backend-post-refactor-sweep.md) is **complete** — all
seven waves (0–6) landed, the last on 2026-08-29 (`a4bf981`). Nothing in this section is gated on a
backend wave any more: every `FS-COORD-*` item is either already landed or unblocked and ready to
dispatch. All four of its standing decisions shipped as written (retire `cueEdit.*`; retire the
legacy tempo surface on both sides; route-tree auth gating; normalize the API hard, no aliases).

This section was reconciled against what actually landed on 2026-08-29, and three things changed
shape in the process:

1. **Five coordination items are already done.** Their client halves shipped in the same run as the
   backend change, because "no aliases" makes a split a broken desk. Struck through below with both
   SHAs.
2. **Two items stopped being cleanup and became live defects.** The backend deleted the fields and
   gated the routes while the client still reads and offers them —
   `FS-COORD-WIRE-FIELD-DELETIONS` and the surviving half of `FS-COORD-ADMIN-GATE` are **P1** now,
   not P2, and the second is a live 403 generator on a desk.
3. **The backend sweep closed without picking up this section's `FS-BE-*` proposals.** They are
   restated at the end as backend halves still owed. Two have since been written by the frontend
   item that needed them (`FS-BE-ACTIVATE-SHORTCIRCUIT`, `FS-BE-CUES-REPUBLISHED-FRAME`), which is
   the pattern to follow when one blocks an item: write the half, in its own commit here.

One ordering constraint outranks everything else here, because getting it backwards breaks Record
and Update on a live desk: **`FS-COORD-CUEEDIT-RETIRE`'s `force` senders must be deleted from this
repo before the backend deletes the inert `force` fields**, not after. The backend deliberately kept
those two fields for exactly this reason (D1 note 1).

### Frontend work that lands in step with backend waves

*(The waves are all landed; "in step" now means "these are the items the backend seam created".)*

### ~~`FS-COORD-CUEEDIT-RETIRE`~~ — **landed**
**Delete the client's cueEdit remnants — backend D1 landed `26cc782`**, lighting-react `62b64eb`
· S3 · P1 · C1 · sonnet
`src/components/lighting-editor/EditorContext.tsx`, `src/components/programmer/RecordSheet.tsx`,
`src/components/programmer/UpdateDialog.tsx`, `src/lib/programmerSource.ts`,
`src/store/programmerOps.ts`, `src/store/perf.ts`, `src/routes/Diagnostics.tsx`,
`src/components/runner/program/CueCardEditor/CueCardEditor.tsx`,
`src/components/fixtures-list/useCellWriters.ts`

D1 deleted the whole `cueEdit.*` family: fifteen socket messages, all four 409 guards, and
`GET /perf/cueedit-histogram`. Verified still present in this repo at HEAD, and what to do with
each:

- **The `force` senders go first, and they are the only part with an ordering constraint.**
  `RecordSheet.submit(force)` and `UpdateDialog.commit(force)` put `force` in *every* request body,
  not just a conflict retry, and the backend's `Json` is strict on unknown keys. The backend is
  holding two now-inert fields open until this lands; deleting them server-side first would 400
  every Record and Update. Land this, then promote `FS-BE-FORCE-FIELDS` below.
- **The 409 handling** — `RecordSheet.tsx:178`, `UpdateDialog.tsx:73,161`, `programmerOps.ts:302`'s
  union arm, `programmerSource.ts:18`'s comment. Its justification ("another client can hold a
  session") is now false: nothing can open one. Keep `INCLUDE_TARGET_GONE`, which is live and
  shares the same union and the same dialog.
- **The Diagnostics panel** — `store/perf.ts:62` queries `perf/cueedit-histogram`, which now 404s
  rather than returning an empty histogram, so `Diagnostics.tsx:107-145` shows a fetch error where
  it used to show an honest empty state. Delete the query and the panel together.
- **Stale doc comments** naming the family in `EditorContext.tsx:11-14`, `CueCardEditor.tsx:87,365`
  and `useCellWriters.ts:53`. Write `FS-DOCS-CLAUDEMD-CUE-ARM`'s CLAUDE.md rewrite in the same
  change so it doesn't enshrine a keep that has already evaporated — and see `FS-COORD-PING` for
  the constraint on what that rewrite may say about flatten.

Grew in the landing: `RecordSheet` lost its conflict path entirely rather than just its `force`
sender — `INCLUDE_TARGET_GONE` is Update-only server-side, so Record had no surviving 409 to catch
and `submit` now reports through the mutation's own `error`. Deleting the Diagnostics panel also
orphaned `HistogramView` and its `Stat` helper, which had no other consumer. `FS-DOCS-CLAUDEMD-CUE-ARM`
landed in the same commit, as this item asked. `FS-BE-FORCE-FIELDS` is now unblocked — §14 updated.

### ~~`FS-COORD-LEGACY-TEMPO`~~ — **landed with backend D2**
**Migrate the legacy-tempo consumers to `speedMasters.*`** · S3 · P1 · C2 · sonnet

Landed as the client half of backend sweep D2 (`db937f6` + `lighting-react` `179280e`), in one
change across both repos. Two corrections worth keeping, because both were premises of this item as
written:

- It was **ten files, not three**. Substantive: `api/fxApi.ts` (the transport — the `beatSync`
  message type and the `requestBeatSync`/`setFxBpm`/`tapTempo` senders), `store/fx.ts`,
  `store/speedMasters.ts`, `BeatIndicator.tsx`, `EffectsOverviewPanel.tsx`, plus their three
  tests. Doc-comment-only: `speedMastersApi.ts`, `speedMastersWsApi.ts`, `components/SpeedMasters.tsx`.
- **"Master 1's `''` key (null uuid already means master 1)" was wrong.** Null means master 1 on
  the *write* messages only. `speedMasters.beat` tags each frame from the bank entry, and after
  the first `load()` master 1's entry holds its real row uuid — so an `''`-keyed subscriber
  never matches a frame, and a `requestBeat` with an omitted uuid never satisfies the throttle.
  Invisible until this change, because master 1 was the one master that never used the keyed
  stream. Fixed client-side (`useMaster1Uuid` in `store/speedMasters.ts`) rather than by
  normalizing the wire, so a uuid keeps naming exactly one master across `state`/`changed`/`beat`.
  `speedMastersWsApi.test.ts` had encoded the wrong convention and was corrected.

Also resolved the `beatSync` half of `FS-PERF-BPM-INVALIDATION` by deletion, and folded in
`FS-CHROME-BEAT-RESUBSCRIBE` as that item asked.

### ~~`FS-COORD-GROUPS-WS`~~ — **landed**
**Delete the client groups WS layer — backend D3 landed `de2e1d5`, and the question it was waiting
on now has an answer**, lighting-react `c1a8c44` · S3 · P1 · C1 · sonnet
`src/api/groupsApi.ts`, `src/store/groups.ts`

D3 deleted `plugins/GroupSocket.kt` outright, superseding `FS-TYPES-GROUPFX-WS`'s scope. This item
said not to delete the client half until one question was answered — *what keeps `GroupList` fresh
across clients, given the `groupsState → invalidateTags(['GroupList'])` bridge never fires?* It has
been answered by grep at backend HEAD: **nothing does.** There is no `groupListChanged` anywhere in
the backend, and B5 added the two missing `*ListChanged` broadcasts without adding this one.

So this is two changes, not one, and the deletion is the *second*: file `FS-BE-GROUPLIST-CHANGED`
(below) as the functional gap, then delete `GroupsInMessage` (`groupsApi.ts:209-211`), the
`groupsState`/`groupFxAdded` arms in `handleOnMessage` (`:239-247`) and the dead `addFx`/`clearFx`
senders — and re-point the invalidation at whatever frame the backend half adds, rather than
leaving the list refreshed only by this client's own mutations.

Landed as one change, not two: the freshness question has a better answer than this item found.
`GET /groups` reads `state.show.fixtures.groups`, and that register is only ever rebuilt inside
`Fixtures.register {}` (`show/Fixtures.kt`), whose tail already fires `fixturesChanged()` — already
broadcast as `FixturesChangedOutMessage`. Every path that can change the group list (patch CRUD,
patch-group `PUT`/`DELETE`, riggings, universe configs, a project switch) reaches that block through
`DbFixtureLoader.loadFixtures`, so a `groupListChanged` frame would be a duplicate emitted from the
same line — `FS-BE-GROUPLIST-CHANGED` is struck below rather than filed. The invalidation was
re-pointed at the frame that already exists instead: `store/fixtures.ts` now invalidates `GroupList`
alongside `Fixture`, which covers the id-scoped `group` / `groupProperties` entries too. Deleting
the whole module rather than the named arms also closes `FS-TYPES-GROUPFX-WS` and spends the
`groupsApi.addFx`/`clearFx` half of `FS-DEAD-WS-METHODS`.

### ~~`FS-COORD-WIRE-FIELD-DELETIONS`~~ — **landed**
**Retired-concept wire fields: the server has now deleted them, so these are live phantoms**,
`39b2375` *(was: "coordinate, don't unilaterally delete")* · S3 · **P1** · C1 · haiku
`src/components/programmer/FxSheet.tsx`, `src/store/fixtureFx.ts`, `src/api/groupsApi.ts`,
`src/api/cuesApi.ts`, `src/api/cueStacksApi.ts`

Backend A2 (`ab0ff8b`) deleted `presetId` from `EffectDto` **and** `GroupEffectDto`; D9 (`10c51ae`)
took `LookRepublishOutcome.programmerKeysUncovered`; `presetApplications` and `presetCount` went
with the layer rewrite. Nothing here is coordination any more — the fields are gone, and every
client site reading one now reads `undefined`:

- `FxSheet.tsx:358` copies `presetId: effect.presetId` out of a DTO that no longer carries it.
- `store/fixtureFx.ts:28,165` and `groupsApi.ts:120` declare it; `fixtureFx.ts:174`'s doc still
  reasons about how it differs from its siblings.
- `cuesApi.ts:265-269`'s `presetApplications` block asserts "**Still** `presetApplications` on the
  wire" — false as of the cue rewrite (`projectCues.kt:640` records the removal). `cueUtils.test.ts`
  pins the same claim from the other side.
- `cueStacksApi.ts:16`'s `presetCount` mirrors a field renamed to `layerCount` — that is
  `FS-TYPES-PRESETCOUNT-RENAME`, and it is confirmed rather than suspected now. It is never
  rendered, only carried and pinned by six test fixtures, so it is a safe deletion.

The B4 half of this item is **resolved and needs no change**: the backend asked (F8) whether the
client was missing `rateSpeedMasterIndex`, and the answer from this side is no — the FX-sheet chip
resolves display via `useSpeedMasterDisplay(uuid)`, so the two `*Index` fields in `api/fxApi.ts`
are display debris. B4 shipped with the `*Index` pair left unconditional deliberately and the
`*Uuid` pair nulled per `timingSource`, which is what this client actually reads.
`FS-DOCS-STALE-COMMENTS` covered the stale `speedMasterIndex` claim (`afb2af8`): the field is now
documented as read by nothing, with the chip resolving the index itself. Deleting it is
`FS-DEAD-DTO-FIELDS`' call.

Landed as scoped: `cueStacksApi.ts`'s `presetCount` was left alone, per this item's own note that
its deletion is `FS-TYPES-PRESETCOUNT-RENAME`'s job. `CueCurrentState.presetApplications` was
deleted outright rather than replaced with the backend's new `layers` shape — that type is already
flagged dead in full by `FS-DEAD-CURRENTCUESTATE`, so wiring up a replacement would be waste.

### ~~`FS-COORD-API-NORMALIZE`~~ — **landed with backend F1–F5, F8**
**Every hard rename in the F wave was a same-change client edit** · S3 · P2 · C2 · sonnet

Done, in five commits that shipped alongside their backend halves — no aliases meant a split would
have left the desk 404ing on nearly every call:

- **F1** (`1af6ef8` + `85229a7`) — the whole REST path rename: `/controlSurfaceTypes` →
  `/control-surface-types`, `/project/{id}/stageRegions` → `/projects/{id}/stage-regions`,
  `/project/{id}/surfaceBindings` → `/projects/{id}/surface-bindings`, `GET /project/list` →
  `GET /projects`, `GET /fixture/list` → `GET /fixtures`, and everything under the renamed subtrees.
  The conventions are now written down in the backend's `docs/api-conventions.md`.
- **F2** (`05fb674` + `481e453`) — AI conversations moved under `/projects/{projectId}/…`; the four
  callers went to `projects/current/…`.
- **F3** (`5c59124`) — delete routes answer `204`; `deactivateProgram` gained a body it doesn't
  read. Nothing was outstanding: `fetchBaseQuery` already no-ops an empty body.
- **F4** (`411363b` + `a5d1853`) — `previewCueLook` is `GET` + `?cueId=`.
- **F5** (`27323a3` + `c19aa12`) — `speedMasterListChanged` → `speedMasters.listChanged`,
  `surfaceBindingsChanged` → `surfaceBank.bindingsChanged`, and the ten request-on-open sends
  deleted now that every stateful family pushes its snapshot per connection.
- **F8** (`974e6c0` + `d6c26c4`) — `fxState` is the same `EffectDto` REST returns: `phase` →
  `currentPhase`, `targetKey` is the bare key with a sibling `propertyName`, `effectType` is the
  registry id. It fixed a real bug on the way: Kill All's `removeFx({ fixtureKey })` cache tag was
  built from the composite key and could never match.

One consequence worth carrying to a neighbouring item: F5's connect burst narrows
`FS-BUG-RECONNECT-RESYNC` without closing it. Eleven families now arrive unasked on every
reconnect, so the hand-maintained tag list no longer has to cover them — but everything RTK Query
caches over plain REST still does.

### ~~`FS-COORD-ADMIN-GATE`~~ — **landed**
**Gate Export/Import in `Projects.tsx` — backend F6 landed `60cc3b3` and this is now a live 403**,
`a695e6c` · S3 · **P1** · C1 · sonnet
`src/routes/Projects.tsx`, `src/navigation.ts`, `src/store/restApi.ts`

F6 landed narrower than this item assumed, and the narrowing is good news: the code-execution
endpoints stayed **operator-reachable** (`scripts/run`, definition test, script-editor compile, AI
`run_lighting_script`) — an operator is trusted local crew who can already do anything the desk
process can — so no surface becomes a 403 generator on that account and the script editor needs no
`skip: !isAdmin` treatment. Only `POST /project/{id}/export` and `POST /project/import` were newly
gated, on their filesystem-path argument.

The `/script-editor` → `/api/script-editor` half **landed** in this repo as `0d2081d`, with the
backend, because a mismatch is silent (a failed `/versions` probe drops every editor on the page to
read-only — `FS-BUG-EDITOR-SILENT-READONLY`).

What is left is the live defect: `Projects.tsx` renders the Import button (`:83-85`) and the Export
menu item (`:256-257`) unconditionally, so an operator sees two controls that can now only answer
403. The component already computes `isAdmin` (`:47`) and already uses it to skip an admin-only
query (`:48`) — hide or disable both controls the same way. Then the `adminOnly` nav ids (pinned by
`navigation.test.ts` against nothing backend-side) and the role-prefix list in `restApi.ts:15`,
which the backend doc cites as a hand-mirror worth re-verifying now that the real gating exists.

Landed as scoped: both controls are hidden (not disabled), matching this codebase's documented
"hidden rather than disabled" convention for permission-gated affordances. The `restApi.ts:15`
role-prefix list is drift — no such list exists at HEAD (`restApi.ts` has a `NOT_A_SESSION_LOSS`
endpoint set, not a role/path mirror); the actual mechanism is `navigation.ts`'s per-item
`adminOnly` flags plus each component's own `isAdmin` check, both already correct and pinned.

### ~~`FS-COORD-NEW-BROADCASTS`~~ — **landed**
**Add the client bridges for `scriptListChanged` / `fxDefinitionListChanged` — backend B5 landed
`47dcb83`**, lighting-react `f59feeb` · S4 · P2 · C1 · sonnet

Both frames are broadcast today; grep finds **no** listener in this repo, so the staleness B5 fixed
server-side is still fully present here — a second client's script list and effect library never
refresh. Two bridges plus tag invalidations, using whichever pattern `FS-ARCH-BRIDGE-EVAL`'s
written-down rule prescribes. `FS-DEAD-RTKQ-HOOKS` deletes three unused FX-definition endpoints;
decide which tags survive that before wiring the second bridge.

Landed as two module-scope bridges, not deferred `start…Bridge()` ones: `FS-ARCH-BRIDGE-EVAL` has
not landed, so there is still no written-down rule, and the existing precedent is that the deferred
form exists only for slices on the earliest render path. `scriptListChanged` sits in
`store/projects.ts`, which owns the script endpoints and already touches `lightingApi` at module
scope. `fxDefinitionListChanged` sits in `store/fixtureFx.ts` rather than beside the definition CRUD
in `store/fxDefinitions.ts`: a module-scope bridge only runs once something imports its module, and
`effectLibrary` is the `FxLibrary` consumer that mounts everywhere while that slice is reached only
from `routes/FxLibrary.tsx`. `FxLibrary` is the only tag either bridge needs, so
`FS-DEAD-RTKQ-HOOKS` cannot strand one.

### ~~`FS-COORD-PING`~~ — **landed with backend D5**
**The WS `ping` keepalive** · S4 · P3 · C1 · haiku

Done in `c6ee984`, alongside backend D5 (`98ec1a2`): the `ws.send({type: "ping"})` keepalive went
(Ktor's `pingPeriod` already covers it), together with flatten's id field and `clearAll`'s stale
reply field name. Verified gone at HEAD.

One constraint survived this item, and is now **spent**: D5 also deleted
`POST /cues/{cueId}/flatten`, so `FS-DOCS-STALE-COMMENTS`' `CueCardEditor` rewrite and CLAUDE.md's
flatten paragraph must not present flatten as the live replacement for Make Hard. Both landed
saying nothing replaced it (`afb2af8`).

### ~~`FS-COORD-PREVIEW-DEAD`~~ — **landed** *(new — created by backend D4)*
**The Look live-preview machinery is dead code now, not merely unreachable**, lighting-react
`05d5d65` · S3 · P2 · C1 · sonnet
`src/api/programmerWsApi.ts`, `src/components/programmer/ProgrammerLookStack.tsx`,
`src/components/programmer/ProgrammerFxList.tsx`, `src/components/busking/lookPresence.ts`,
`src/store/looks.test.ts`, `ProgrammerScopeBand`, `LookRowStoreProvider`

Backend D4 (`17c5dac`) deleted `POST`/`DELETE /project/{id}/looks/preview` **and**
`ProgrammerLayerStack.installPreview`, so `programmer.layerState` can no longer carry an
`isPreview` layer — verified: neither symbol exists backend-side at HEAD. Every client filter on it
is therefore unreachable rather than merely unused:
`programmerWsApi.ts:122`'s `isPreview?: boolean`, `ProgrammerLookStack.tsx:59-60`'s filter and
`preview` lookup, `ProgrammerFxList.tsx:35`'s filter, `lookPresence.ts:29,64`'s two guards, and the
unfiltered-stack special-casing in `ProgrammerScopeBand` / `LookRowStoreProvider` that
`FU-PROG-FOCUS-PREVIEW-LAYER` flagged. `store/looks.test.ts:36` still fixtures a
`projects/1/looks/preview` write count, and `ProgrammerLookStack.test.tsx` builds three preview
layers to assert behaviour the server can no longer produce.

Read `FU-PROG-FOCUS-PREVIEW-LAYER` before deleting — the backend item checked it didn't want the
hook kept, but the *frontend* special-casing is what that follow-up describes, so this may close it
outright. Deleting the filters without deleting the tests leaves three green tests asserting a
deleted protocol.

Grew in the landing: the follow-up needed no action — it was already retired in `followups.md` when
D4 landed, and `ProgrammerScopeBand` / `LookRowStoreProvider` turned out to carry no `isPreview`
reference at all, only an unfiltered lookup with nothing left to admit. Three of the four preview
tests were deleted; the fourth pinned index→`layerId` addressing *through* the filter and was
rewritten against two ordinary layers instead, since blend and mask reach the store from a popover
rather than from the row. Three stale doc claims the deletion falsified went too —
`store/looks.ts`'s "the route and `installPreview` remain and still work" (citing a follow-up id
that exists nowhere), and `LookRowStore` + CLAUDE.md both offering `LookPreviewRequest` as the
smooth-preview escape hatch a layer-scope drag doesn't have.

### ~~`FS-COORD-FXLIBRARY-PARAMS`~~ — **landed** *(new — created by backend D7)*
**`GET /fx/library` now returns real parameter types and defaults, and the FX sheet has a heuristic
that was never exercised against them**, `1e3c33a` · S3 · P2 · C2 · sonnet
`src/components/fx/EffectParameterForm.tsx`

D7 (`84885df`) replaced the placeholder `"string"` / `""` / `""` triple with each parameter's real
`type`, `defaultValue` and `description`. Same payload shape, so nothing breaks — but this form
consumes all three, so typed controls and prefilled defaults start working on their own, for the
first time, across all 28 built-in effects.

The one place that needs eyes rather than a green build: the `double`/`float` branch
(`EffectParameterForm.tsx:474-478`) picks its slider range from a heuristic — *"if default <= 1.0,
treat as 0–1 ratio; otherwise 0–10"* — which until D7 always saw `""`. Walk the built-ins'
declared doubles and confirm the two buckets are right; a parameter whose sensible range exceeds 10
(or whose default is 0 but whose range is not 0–1) now renders an unusable slider. Fix by declaring
the range rather than by widening the heuristic, if the backend registrations can carry one.

**Landed as an `int` fix and a confirmation, not the double fix the item expected.** The walk found
the double heuristic *correct for every built-in*: all ten declared doubles are 0–1 ratios with
defaults in [0.1, 1.0] (`ColourCycle.fadeRatio`, `ColourStrobe.onRatio`, `RainbowCycle.saturation`
and `brightness`, `CandleFlicker.smoothing`, `FluorescentFlicker.flickerSpeed`, `Pulse.attackRatio`
and `holdRatio`, `SquareWave.dutyCycle`, `Strobe.onRatio`), so the 0–10 arm is unreachable from the
built-in library and no unusable double slider exists at HEAD.

The branch D7 *did* break is the neighbouring `int` one, which derived its max from the live value
(`Math.max(255, numVal * 2)`). While `defaultValue` was `""` that always read 0 and the range was a
stable 0–255; with real defaults, `FluorescentFlicker.flickerDurationMs` opens at 0–1600 and then
ratchets to 0–3200 the moment the handle reaches the right edge, which it therefore never can.

**Grew in the landing: the int control gained a typable value.** The first fix derived the max from
the declared default and widened it by the live value; review showed that merely relocates the
defect, because `max === numVal` for anything above the derived range pins the thumb at 100% — the
value can then fall but never rise — while dropping the ratchet caps a 0–5000 parameter with a small
default at 255 with no way to reach the rest. Both failures are the same one: with no declared
bounds on the wire, a guessed range was the branch's *only* input. So the max is now a pure function
of the declared default (`Math.max(255, default * 2)`, never moves), the slider clamps for display
without writing back, and the value readout in the header row became an `Input` — the pattern the
wall-clock `Cycle length` control already uses — so anything outside the guess is still reachable.

The plan's preferred fix — declare the range on the wire — was **not** taken, because it is a
backend change and the built-ins gave it nothing to fix. It is filed as `FU-FE-FX-PARAM-RANGE` in
`followups.md`, triggered by a script-defined effect whose numeric range the heuristic guesses
wrong; that is the only case left where it can.

### ~~`FS-COORD-STRICT-ENUMS`~~ — **confirmed at HEAD, no change** *(new — confirm-only, from backend E4/E10)*
**The effect-enum write sites now 400 an unrecognised value**, no commit · S4 · P3 · C1 · haiku

E4 (`0ce10d9`) and E10 (`dc1e7ea`) made `blendMode` / `distribution` / `elementMode` /
`elementFilter` strict where they enter the desk: the REST apply endpoints and look/cue write routes
answer 400, `programmer.addLayer` / `programmer.patchLayer` answer a `programmer.error` frame, and
the AI tool call fails. Casing and whitespace are still tolerated, so a client already sending a
real value cannot break.

The backend verified this client only sends canonical names (`BlendMode` is a string-union;
`distribution` / `elementFilter` are literals), so this is a **confirm, not a change** — the entry
exists so a future reader doesn't rediscover it as a bug. Two things ride on it, though: a
`programmer.error` frame is now reachable from a bad enum, which is exactly the frame
`FS-BUG-PROGRAMMER-ERROR-DROPPED` found delivered to zero subscribers — it now raises a toast
(`71fdb9a`); and
`FS-TYPES-EFFECTTYPE-UNION`'s cast is the shape that could launder a non-canonical value past the
type system.

Also confirm-only from the same waves: **B3** (`39f4d7c`) added `elementMode` to `POST /fx/add`, so
`AddEditFxSheet` can set FLAT mode at creation instead of add-then-`PUT` — worth taking if the sheet
does the two-step today. **E10** and **B3** are both cheap riders on any FX-sheet work.

Confirmed at both HEADs, and nothing needed changing. `BLEND_MODE_OPTIONS`,
`DISTRIBUTION_STRATEGY_OPTIONS`, `ELEMENT_MODE_OPTIONS` and `ELEMENT_FILTER_OPTIONS`
(`components/fx/fxConstants.ts`) are canonical against `BlendMode`, `DistributionStrategy.byName`
and `ElementFilter`, and the only enum literals written outside those tables are
`useBuskingState`'s `'OVERRIDE'` / `'LINEAR'`. The B3 rider is already taken: `AddEditFxSheet`
sends `elementMode` in the create payload on both the fixture and group branches, so there is no
add-then-`PUT` two-step to remove.

### Backend halves still owed

The backend sweep closed (all waves, `a4bf981`) **without** picking up the proposals this section
raised. They are restated here as the backlog they now are; the right home is
`../plans/followups.md` as `FU-` items, and promoting them is a decision for the desk owner rather
than something an agent should do while landing a frontend item.

Three of the six are settled:

- ~~`FS-BE-STOP-ROWSONLY`~~ — refuted at HEAD: `removeEffectsForCue` clears Layer 4
  unconditionally (`b11d66a`), and `/apply` routes through `activateCueInStack` (`dcc511f`) so the
  stack is active and stop takes the deactivate branch anyway. Pinned by `CueSlotLivenessRouteTest`.
- ~~`FS-BE-GROUPLIST-CHANGED`~~ — refuted at HEAD: the signal it proposed already exists under
  another name. `GET /groups` reads `state.show.fixtures.groups`, that register is only rebuilt
  inside `Fixtures.register {}`, and that block's last act is `fixturesChanged()` — already
  broadcast. A `groupListChanged` frame would be a second frame from one emit point, so
  `FS-COORD-GROUPS-WS` pointed the client's `GroupList` invalidation at `fixturesChanged` instead
  (lighting-react `c1a8c44`).
- ~~`FS-BE-ACTIVATE-SHORTCIRCUIT`~~ — done, `1cdadb7`, with `FS-BUG-CUESLOT-LIVENESS`.
- ~~`FS-BE-CUES-REPUBLISHED-FRAME`~~ — done, `6525ad6`, with `FS-BUG-CUE-TAG-STALE`. Landed as
  `cuesRecomposed`, carrying every cue layering the edited record rather than the REST responses'
  narrower `cuesRepublished` list — see that item for why the two are different questions.

Still owed, and each blocking a frontend item from being finished honestly:

- `FS-BE-COMPATIBLEIDS` — `compatibleIdsFor` (`routes/lightFixtures.kt`) filters on inferred effect
  capabilities only, so a rows-only Look (empty capability set) is reported compatible with every
  target and `LookTogglePicker` offers pads that assert nothing. Decide whether compatibility should
  also require row coverage. `FS-DOCS-COMPATIBLELOOKIDS` landed without it (`0bcda19`) by
  documenting the hole rather than claiming it closed, as that item required — so this is no longer
  blocking anything, only still true.
- `FS-BE-TEMPLATE-TOGGLE-MASK` — `ProgrammerLayerStack.toggle` has no `propertyMask` parameter and
  `projectTemplates.kt`'s toggle route derives none, while its KDoc claims the server derives the
  family and cross-checks the echo (the echo can never disagree with itself). Backend half of
  `FS-TYPES-TEMPLATE-TOGGLE-MASK`; that item is a client-side no-op without it.
- `FS-BE-FORCE-FIELDS` *(new)* — **now unblocked.** `force` survives on the Record and Update
  request bodies, inert, solely because this repo used to send it on every submit (backend D1
  note 1). `FS-COORD-CUEEDIT-RETIRE` landed as lighting-react `62b64eb`, so no client sends it and
  the two fields can be deleted server-side whenever the desk owner wants them gone. The ordering
  constraint is spent; nothing 400s either way now.

And one remark, not an item: `programmerRecord.kt`'s rename-breadcrumb KDoc pattern ("Was
`timedPresetApplications`", still there at `:61-64`) is what let `FS-BUG-TIMEDLAYERS-RENAME` be
found quickly; the rename that *didn't* leave one (`layerCount`) took longer, and left this repo
carrying `presetCount` to this day. The API normalization wave kept the convention — worth keeping.

## 15. Explicitly not findings

Recorded so the next sweep doesn't re-flag them. Each was examined and is deliberate (documented at
the site or in CLAUDE.md), or was checked and holds:

- **`CueValueGrid` vs `ProgrammerGrid`**, **`LookStack` vs `ProgrammerLookStack`** (wrapper),
  **`AddLayerSheet` vs `MakeLayerSheet`**, **`lookLayerPresence` vs `templateLayerPresence`** —
  documented non-duplicates; the reasons at each site still hold.
- **`SpeedMasters` mounting all responsive arms simultaneously** — documented at the site as
  deliberate (CSS-only switching so the arms can't drift); the *costs* that ride on it are covered
  by `FS-PERF-FADE-IN-SHOWBAR` and (landed) `FS-CHROME-BEAT-RESUBSCRIBE`, not by un-mounting arms.
- **`ProgrammerGrid`'s per-render `editorContext` literal** — absorbed by `EditorContext`'s
  field-wise memo; benign.
- **Release notes rendering as plain text** — the sweep noted `react-markdown` is now in the tree
  anyway (via `AiChatPanel`), but the plain-text choice is a documented security posture for
  untrusted internet text, not a dependency-cost artefact; it stands either way.
- **`reconnect()` leaving the replaced socket's `onmessage` live** — examined and refuted as
  unreachable in practice.
- **`prototypes/computeWarnings` as a duplication risk** — the port is one-way and documented; the
  residual risk is covered by relocating the directory (`FS-DEAD-PROTOTYPES`).
- **In-app updates and desk accounts subsystems** — spot-checked clean by the completeness critic:
  the tick-vs-terminal cache split, the 401/4401 auth invalidation, and the
  `NOT_A_SESSION_LOSS` exemptions are all implemented exactly as documented.
- **`useShowTransport`'s two-cursor model** — the two *questions* (stable marker vs fade chrome) are
  a documented design and are not the duplication `FS-ARCH-CURSOR-OWNERSHIP` describes; don't
  collapse the cursors while fixing the ownership.

## 16. Scope honesty

What this sweep did and did not do, so silence is never read as a clean bill:

- **Everything the refactors touched was examined hard** — the programmer grid, layers, cue
  transport, templates, speed masters, WS fan-out — and the first round's completeness critic then
  named the blind spots, all five of which the gap round swept: reconnect/offline resync, the
  script-editor subsystem, the stage read path, server→client contract omissions, and stale test
  assertions. Those five went from zero findings to twenty-seven, so "no findings" in the first
  round meant *unexamined*, not clean — treat any future subsystem silence the same way.
- **Perf findings are code-read, not profiled.** Mechanisms and cadences were verified in source
  (and cost claims were adversarially checked — several were downgraded), but nothing was measured
  on a desk. The fade cluster and `FS-PERF-WS-SINGLE-PARSE` deserve a before/after on real hardware;
  anything operator-perceivable (crossfade smoothness, drag responsiveness) belongs as
  `manual-validation.md` rows when fixed, per house rule.
- **Still thin**: Surfaces/MIDI beyond `store/surfaces.ts` and the descriptor types (learn-mode
  flows, `BindingMatrix` interaction paths); Prompt Book annotation/region geometry (only size and
  prop-drill findings); accessibility (not examined at all); the in-app updates and desk-accounts
  subsystems were spot-checked clean on correctness but never examined under the perf/fan-out lens.
- **The contract-omission pass was entity-driven, not exhaustive**: it diffed the main round-trip
  builders and big DTOs backend-first; smaller response types may still hide omissions of the
  `CloneProjectResponse` kind.
- **Counting**: 131 raw findings from 31 agents; 4 refuted outright, ~10 merged as duplicates or
  absorbed into clusters; what remains is every finding that survived adversarial verification, with
  downgrades applied. Two S1s, both independently re-verified twice and re-read by hand.
- This catalogue cites symbols, not line numbers, against `lighting-react` `b5067e5`-era `main`;
  expect drift as items land. Eight commits have landed since (`c126d62` through `d6c26c4`, mostly
  the backend-coordinated halves in §14), and the §14 claims — but only those — were re-verified
  against both repos' HEAD on 2026-08-29. Everything outside §14 is still as first written. When an item lands, strike it through here with the commit SHA, the
  way `backend-post-refactor-sweep.md` does.



