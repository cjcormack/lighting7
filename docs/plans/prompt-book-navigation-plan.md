# Prompt Book navigation: search, bookmarks and finding a place in a long script

> **Document status: PLANNED, 2026-11-28.** Written from the design page Chris and Claude went
> through that day, with his answers to its questions.
> Four sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`prompt-book-navigation-design/INDEX.md`](prompt-book-navigation-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-28: search for text in the script PDF; bookmarks for musical numbers and
scenes, which Claude can populate through the MCP tools, with an answer on how they overlap a cue
stack's dividers; and whatever else helps navigation in a long script, above all while programming
a show.

What the code says, read at `e03b8e9`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **Navigation is by cue.** The rail (`F/components/promptbook/CueStackPanel.tsx`) scrolls to a
  cue's anchor; *Jump to live* and `?cue=` do the same (`useBookAnchors.scrollToEntry`). A stretch
  of script with no cues has no handle.
- **Only a window of pages is drawn.** `ScriptViewer` keeps about one viewport of `<Page>`s mounted
  above and below (`RENDER_MARGIN`), because an 84-page script whole is 1.5 GB of canvas. The
  browser's find therefore reaches five or six pages.
- **The desk never reads the script's text.** It stores the PDF by content hash
  (`State.promptScriptPath`, `<appDataDir>/prompt-scripts/{projectUuid}/{hash}.pdf`) and serves the
  bytes; the browser counts the pages with pdf.js. `docs/mcp-engineering.md` says the backend has no
  PDF library.
- **Claude places things by estimating.** `scriptPlaceSchema` (`K/ai/SetupToolSchemas.kt`) is
  `pdfPage` and `y`, a fraction down the page, on `build_cue_stack`'s `at` and
  `mark_up_prompt_book`'s anchors and notes. `get_prompt_book` answers anchors and notes and no
  text.
- **Stack markers** are entries with no cue (`cue_stack_entries`), drawn in the rail between cards
  (`flattenShowRows`). The anchor routes refuse a marker's entry (`cueEntryOf`,
  `K/routes/projectPromptBooks.kt`), so a marker has no place on the script.
- **The book's records** are `prompt_books`, `prompt_book_anchors` and `prompt_book_annotations`
  (`K/models/promptBooks.kt`), all portable, places stored as rects normalised to the page
  (`PromptBookRectDto`, `checkPromptBookRegion`). Every write fires the payload-free
  `promptBookChanged`. `coverPages` is the offset from a PDF page to the script's printed number.
- **The follow scroll** (`useHeldFollow`, `frontend/CLAUDE.md` §"The Prompt Book's follow scroll")
  already treats a rail click, a warning's click, *Jump to live* and `?cue=` as the operator's own:
  they never wait, and each cancels a follow still waiting.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **The desk extracts the script's text.** One per-page index beside the PDF, named by its hash: each line of text with its box in the 0–1 page coordinates anchors use, enough within a line to box a hit, whether each page is a scan, and the PDF's own outline where it has one. Derived from the PDF's bytes: machine-local, never synced, built in the background and never while a request waits (§3 "The text index"). |
| D2 | **Search runs in the browser over that index.** The view fetches the index once and searches as the operator types, across the script's text, cue names and notes, script notes and bookmark titles. It takes `⌘F` in this view, since the browser's find cannot see undrawn pages. Case, accents, quote style and runs of whitespace are ignored, and a hit may cross a line break. |
| D3 | **A bookmark is the book's own record:** a title, a kind (`ACT`, `SCENE`, `NUMBER`, `OTHER`), an optional short label (*I.3*, *#7*) and a point on the script (page and height). Its order is its place; nothing is reordered by hand. Kinds set colour and indent and are not a strict tree: a number may start in one scene and end in the next. Portable, synced with the show, journaled for undo. |
| D4 | **Bookmarks and stack markers stay two records, and the book joins them.** The rail groups cues under bookmark headings by where each cue is anchored. A stack marker still draws in the rail unless its name matches the heading directly above it. *Add as stack marker* copies a bookmark's title into a stack as a marker before the first cue anchored after it: a copy made once, with no link afterwards. Adding is idempotent: when a marker of that name already sits directly before that cue in that stack, nothing is added and the answer names the existing marker. A marker never becomes a bookmark. |
| D5 | **Contents lives in the toolbar.** A breadcrumb names where the top of the viewport is (*Act 1 › I.2 The Kitchen › #2*) and opens a filterable list of bookmarks, each with its page and the cues anchored inside it. `⌘K` offers *Go to bookmark…* over the same list. No new permanent panel. |
| D6 | **Claude places things by quoting the script.** Every `at` takes `text` (with an optional `pdfPage` and `occurrence`) beside today's `pdfPage` and `y`; the desk finds the line and uses its real box. A quote found nowhere, or in several places with nothing to choose by, is refused naming the candidates. `search_prompt_book` is new; `get_prompt_book` answers bookmarks, whether the script has text, and a page's lines on request; `mark_up_prompt_book` takes bookmarks. |
| D7 | **Navigation is never an edit.** Find, Contents, the strip, Go to page and Back work with the show locked and for both roles. Placing, moving, renaming and deleting a bookmark need the lock off, as an anchor does. |
| D8 | **No OCR.** A scanned page is named as unsearchable (*12 pages are scans and cannot be searched*), never answered as *0 results*. Bookmarks, by click, and Go to page still work on one. |
| D9 | **Three smaller aids.** *The strip*: a thin column between the script and the rail drawing the whole book, with a tick per bookmark, cue, search hit and warn or safety note, the live cue, and the viewport, clicked or dragged to move. *Go to page*: the toolbar's page readout is a field in the script's printed numbering. *Back*: after any jump a chip offers the place jumped from, one step deep. Its label always carries the page (*Back to p.12*), so it does not read as the transport's GO BACK, and it is a pointer target only (§4 "Keys"). |
| D10 | **Every jump is the operator's own.** A search hit, a bookmark, a page, the strip and Back never wait for the follow scroll and cancel one still waiting, as a rail click does today. The next GO onto a new sequence scrolls the book to the live cue as before. |

The plan adds four of its own:

- **P1 — The desk owns the reading.** Text extraction, the match rule and the resolving of a quote
  to a place are Kotlin. The view's search is a mirror of the match rule for typing speed, pinned to
  the desk's by one shared vector, `src/test/resources/promptBook/textSearch.fixture.json`, that
  both sides' tests read.
- **P2 — The index is a cache, not a record.** It has no table and no `SyncDispositions` row, is not
  exported, and may be deleted at any time. A desk that pulls a show builds it from the synced PDF.
  The index carries a version, so a later extractor replaces an older one's output. An index whose
  PDF is no longer in the store is removed when the project loads. Install backups leave the index
  out, and it is rebuilt after a restore.
- **P3 — No new socket frame.** Bookmark writes fire `promptBookChanged`, and bookmarks ride
  `PromptBookDetails`. `TraceSampling.WS_MESSAGES` and `FU-AUTH-WS-PER-MESSAGE` are untouched.
- **P4 — `at` stays backward compatible.** A place given as `pdfPage` and `y` means what it means
  today. `text` is the preferred form and the tools' descriptions say so.

## 3. The model

### The text index (D1, P2)

```
<appDataDir>/prompt-scripts/{projectUuid}/{hash}.text.json     (beside {hash}.pdf)

{ version, scriptHash, pageCount,
  pages:   [{ page, scanned, lines: [{ text, x, y, w, h, … }] }],
  outline: [{ title, level, page, y }] }
```

A PDF library joins the desk's runtime classpath for this (Apache PDFBox is the candidate:
Apache-2.0, which `licences/allowed.json` allows). Session 1 picks it, records it in
`licences/jvm.json` and measures what it adds to the installer.

**When it is built.** The desk builds the index in the background when a script is imported or
arrives by sync, and when the index is first asked for and absent. A request never waits for
extraction, and extraction never runs on a show thread. The route and the tools name three states:

- **Ready:** the index is on disk and is answered.
- **Building:** the view shows *Reading the script…* and retries. `search_prompt_book` and an
  `at.text` answer that the text is not ready yet and to try again shortly, and write nothing.
- **Script missing:** the PDF is not on this desk, as on a pulled show whose PDF has not arrived
  (`docs/sync-engineering.md` §"Prompt books"). The view shows its existing *Script PDF missing*
  state and the tools say so.

None of the three is ever answered as an empty index or as 0 results.

**Pages.** The index counts pages from 0, as the book's rects do. The MCP tools answer and take
`pdfPage` counted from 1, as today. The book's stored `pageCount` stays the authority: a PDF whose
page count the desk reads differently is logged, not rewritten.

A page is `scanned` when it carries too little text to be a typed page, the rule the viewer's
`MIN_TEXT_ITEMS` already applies per page. Session 2 moves the viewer onto the desk's answer.

Lines are in reading order. How much is stored within a line (per-glyph advances or per-word
boxes) is session 1's choice, bounded by D2's need to box a hit that starts mid-line.

### Matching (D2, P1)

One rule, used by the view, `search_prompt_book` and `at.text`: fold case, strip accents, read
curly quotes as straight, collapse whitespace, and let a query run from the end of one line into
the next. A hit is one rect per line it touches, in page coordinates.

### Bookmarks (D3)

```kotlin
// models/promptBooks.kt
object DaoPromptBookBookmarks : IntIdTable("prompt_book_bookmarks") {
    promptBook (cascade), kind, title, label?, page, y, uuid
}
```

Additive, so the schema reconciliation creates it and no migration step is needed. It is
**portable**: `promptBookBookmarks/{uuid}.json` in the archive, wired through `ProjectExporter`,
`ProjectImporter`, `SyncDispositions` and `RichProjectFixture`, at the next sync `formatVersion`
(57 at writing). A bookmark joins the show history wherever anchors and annotations are named:
`RESTORABLE_FOLDERS`, `history/RecordLabeler.kt`, `history/RecordRestorer.kt` and
`history/KindEffects.kt`. Session 3 decides `minReader` by whether an older importer
passes an unknown folder; a clone keeps bookmarks for free. `copy_to_project` and
`POST /cues/copy-from` do not copy them, as they do not copy anchors.

A section runs from its bookmark to the next of its kind or above (`ACT` above `SCENE` and
`NUMBER`; `OTHER` beside them). "The cues inside" a bookmark are the cue entries whose anchors fall
in that run, by the book's existing script order (`scriptPosition`).

### REST

| Route | Does |
|---|---|
| `GET /projects/{id}/prompt-book/text` | The index for the book's script (D1), cacheable by hash once ready. 404 with no book. It answers the index's state (ready, building or script missing) and never waits for extraction; asking for an absent index starts the build. Any project, both roles. |
| `POST /projects/{id}/prompt-book/bookmarks`, `PUT`/`DELETE …/{bid}` | Bookmark CRUD, checked like an anchor's place. |
| `PUT /projects/{id}/prompt-book/bookmarks` | The whole outline in one write: the PDF outline's import and Claude's `replace`. |
| `POST /projects/{id}/prompt-book/bookmarks/{bid}/marker` `{stackId?}` | *Add as stack marker* (D4): a marker of that title before the first cue anchored after the bookmark, in that stack, else the stack that cue is in. Idempotent as D4 says: a marker of that name already directly before that cue is answered, not added again. Refused when no cue is anchored after it. **Session 4's.** |

Bookmark writes follow the book's existing gate (`canEdit`, the current project) and each is a
show-history entry with a label of its own.

### MCP (D6)

The two readers, `get_prompt_book` and `search_prompt_book`, take a `projectId`. The writers keep
today's rule, the current project. An `at.text` is always resolved against the book of the project
the writing tool writes to.

| Tool | Change |
|---|---|
| `get_prompt_book` | Adds `bookmarks` (id, kind, title, label, place, the entries inside), `hasText`, `scannedPages` and `pdfOutline`. With `pages: [n…]` it answers those pages' lines, each with its `y`. `hasText` and the lines follow the index's state: building and script missing are named, never answered as no text. |
| `search_prompt_book` (new) | `{query, pdfPage?, limit?, projectId?}`: each hit's page, box, line and the bookmark it falls under. Read-only. While the index is building, or the script is missing, it says so and answers no result count. |
| `mark_up_prompt_book` | Adds `bookmarks` (upserted by the key below, so a second run moves rather than doubles), `removeBookmarks` (by the same key), `replaceBookmarks: true` for a rebuild in one call, and `markers: true` to add the stack markers too, idempotent as D4 says: a second run adds none and names the markers already there. |
| every `at` | `{text, pdfPage?, occurrence?}` beside `{pdfPage, y, …}`: on `build_cue_stack`'s cues and `mark_up_prompt_book`'s anchors, notes and bookmarks. While the index is building, or the script is missing, a write naming `text` is refused saying so, and nothing is written. |

**A bookmark's key** is its `id` when given, else its kind and title, compared ignoring case and
surrounding whitespace. The label is data and never part of the key, so correcting a label doubles
nothing. Where two bookmarks share a kind and title (a reprise), a write naming them without an
`id` is refused, naming both with their ids and pages.

## 4. UX

On the page (`prompt-book-navigation-design/prompt-book-navigation.html`):

- **The toolbar** gains, left to right: the breadcrumb (D5), the page field (D9), the find field
  (D2) and, after a jump, the Back chip (D9). *Front matter*, warnings and *Cues* stay where they
  are.
- **Find**: a count (*3 of 14*), every hit washed on the page and the current one stronger, drawn
  as overlays from the index's boxes rather than from the text layer, so a hit's place is known
  before its page is drawn. `Enter` and `⇧Enter` step; `Esc` closes and offers Back to where the
  search began. Hits in cue names, cue notes, script notes and bookmark titles list above the
  script's.
- **A bookmark** is a flag in the left margin above the cue markers' lane. Unlocked: the selection
  toolbar gains *Bookmark* (title from the selection, kind guessed from it, both editable in a small
  sheet); the tool palette gains a Bookmark tool for a click on a scanned page; a flag drags
  vertically like an anchor and opens its sheet on a click (rename, kind, label, delete, and from
  session 4 *Add as stack marker*). A book with no bookmarks whose PDF has an outline offers *Import
  the PDF's outline* once.
- **Contents**: the list in §"Contents" of the page, filtered by kind, with each row's page and cue
  range, or *no cues*.
- **The rail**: sticky bookmark headings over the cues anchored inside them (D4); an unanchored cue
  sits where the book already places it by its neighbour.
- **The strip** and its legend as drawn; on a narrow layout it stays beside the script while the
  rail is a drawer.
- **Keys**: `⌘F` or `/` find, `G` the page field, `[` and `]` the previous and next bookmark, `⌥↑`
  and `⌥↓` the previous and next cue. None fires while a field has focus, and none is a transport
  key. The Back chip has no key of its own: Backspace stays the transport's GO BACK.

## 5. Implementation — four sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names written,
and its row marked *in review* with its branch. A session's PR is titled *Prompt Book navigation:
session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The script's text | in review | `claude/friendly-davinci-s636ia` | | |
| 2 | Find, pages and Back | planned | | | |
| 3 | Bookmarks | planned | | | |
| 4 | The rail's headings and the strip | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — The script's text

Backend and MCP only; Claude gains search and quoting before any UI exists.

- **The index (D1, P2):** the PDF library, the extractor, the index file and its version, the
  scanned-page rule, the PDF outline, and `GET …/prompt-book/text`. Built in the background by §3's
  rule, with its three states; a missing or older-version index rebuilt; an index whose PDF has left
  the store removed when the project loads; the index left out of install backups.
- **The match rule (P1):** one Kotlin matcher and `textSearch.fixture.json`.
- **MCP (D6, P4):** `search_prompt_book`; `get_prompt_book`'s `hasText`, `scannedPages`,
  `pdfOutline` and `pages`; `at.text` on `build_cue_stack` and `mark_up_prompt_book`'s anchors and
  notes, with its refusals. Bookmarks are session 3's.
- **Tests:** extraction over a small typed PDF and a scanned one kept under `src/test/resources`
  (boxes within a tolerance of known positions, reading order, the outline); the matcher's vector;
  a quote resolved, missing, and ambiguous with and without `pdfPage`; the route on a project with
  no book; the index rebuilt after deletion; each state's answer (ready, building, script missing)
  from the route, `search_prompt_book` and an `at.text`, none an empty index or 0 results; an
  orphaned index removed at project load; a backup holding no index.
- **Docs:** start `docs/prompt-book-engineering.md` (the store, the index, the match rule), linked
  from the root `CLAUDE.md`; `docs/mcp-engineering.md`'s tool table and its line saying the backend
  has no PDF library; `docs/licensing.md`'s entry and `THIRD-PARTY-NOTICES.md`.
- **Checks:** the full `./gradlew check shadowJar` locally, since the dependency set and the fat
  jar change; report the installer's growth in the PR.

*As built.* PDFBox 3.0.8 (`pdfbox`, `pdfbox-io`, `fontbox`, all Apache-2.0) adds 1,185 entries and
3.5 MB compressed to `lighting7.jar` as CI builds it (`-PnativePayloadOs=windows`, 145.8 MB), and so
about 3.5 MB to the installer, whose payload is already deflated. Where the session departed from
the text above:

- **A fourth state, `UNREADABLE`**, for a PDF PDFBox cannot read; otherwise it would read as
  building for ever. The store remembers it until the desk restarts.
- **The scanned rule is fewer than five lines.** PDFBox has no counterpart to pdf.js's text items,
  so the viewer's `MIN_TEXT_ITEMS` floor is applied to lines.
- **Within a line, the index stores each character's left edge** and the line's right edge (`cx`).
- **The route's states:** ready is 200 with the index inside `{state, scriptHash, index}`, building
  is 202 with `Retry-After`, script missing and unreadable are 200 with their `state`.
- **`docs/licensing.md` lists no dependencies;** a new one's entry is `licences/jvm.json`.
- **`search_prompt_book` names no bookmark yet**, since bookmarks are session 3's; session 3 adds
  the bookmark each hit falls under.

### Session 2 — Find, pages and Back

Frontend, over session 1's route.

- **Find (D2, D8):** the toolbar's field, the client matcher against `textSearch.fixture.json`, hit
  overlays and stepping, hits in cues, notes and annotations, the scanned-pages message, `⌘F` and
  `/`. The viewer reads `scanned` from the index in place of its own count.
- **Go to page and Back (D9, D10):** the page field in printed numbering (`coverPages`), `G`, the
  Back chip and when it clears. Every jump goes through one function that cancels a waiting follow.
- **Tests:** the matcher's vector; a hit on an undrawn page scrolls to it and draws once mounted;
  `⌘F` is taken only in this view; a jump while a follow waits cancels it; Back after a search, a
  page jump and a rail click; nothing here is disabled while locked (D7).
- **Docs:** `frontend/CLAUDE.md`'s Prompt Book sections and
  `frontend/docs/show-mode-engineering.md`.
- **Checks:** `npm run check:quick` in `frontend/`.

### Session 3 — Bookmarks

The record across the wire, in one PR.

- **Backend (D3):** the table, its DTO on `PromptBookDetails`, the routes in §3 except the marker
  route, sync at the next `formatVersion` with the round-trip and `SyncCoverageTest` rows, undo (the
  history wiring §3 "Bookmarks" names, a label per write), and `promptBookChanged` on every write
  (P3).
- **MCP (D6):** bookmarks in `get_prompt_book` and `mark_up_prompt_book`, `at.text` on them, the
  key in §3 "MCP", `replaceBookmarks` and `removeBookmarks`, and the bookmark each
  `search_prompt_book` hit falls under. `markers` waits for session 4.
- **The view (D5, D7):** flags, the selection toolbar's *Bookmark* and the palette's tool, the sheet
  (without *Add as stack marker*, which is session 4's), the drag, the outline import, the
  breadcrumb, Contents, `⌘K`'s *Go to bookmark…*, and bookmark titles in find.
- **Tests:** CRUD and its refusals; the whole-outline write; export, import, clone and a v56 archive
  read; an undo of a create, a move and a delete; the MCP upsert moving rather than doubling, a
  corrected label doubling nothing, and a reprise named without an `id` refused; placing from a
  selection and from a click; the breadcrumb at section edges; locked, no bookmark affordance
  writes.
- **Docs:** `docs/prompt-book-engineering.md`, `docs/sync-engineering.md` (the folder and the
  version), `docs/undo-engineering.md`, `docs/mcp-engineering.md`,
  the root `CLAUDE.md`'s Database and API sections, `frontend/CLAUDE.md`.
- **Checks:** the full `./gradlew test` locally (a `formatVersion` bump and a new table);
  `npm run check:quick`.

### Session 4 — The rail's headings and the strip

- **The rail (D4):** headings derived from bookmarks and anchors, the matching-marker rule,
  *Add as stack marker* (the route, the sheet's button, Contents' per-kind action) and
  `mark_up_prompt_book`'s `markers`.
- **The strip (D9):** every tick kind, the viewport box, click and drag, its place on the narrow
  layout. It reads positions from data it already has (anchors, bookmarks, annotations, hits) and
  mounts no page.
- **Keys:** `[`, `]`, `⌥↑`, `⌥↓`.
- **Tests:** a cue moving between headings when its anchor moves; a marker hidden only under a
  matching heading; the marker's place, and its refusal with no cue after the bookmark; a second
  *Add as stack marker* and a second `markers: true` run adding nothing and naming the existing
  marker; the strip's ticks against a fixture book; a drag scrolling without mounting the pages
  between.
- **Docs:** as session 3's, and `docs/cue-stacks-engineering.md` where it lists what writes a
  marker.
- **Checks:** `./gradlew test --tests` for the routes and `ai` packages; `npm run check:quick`.

## 6. Migration

None. The bookmark table is additive and empty. Existing anchors, notes and stack markers are
untouched, and a show from before this plan opens with no bookmarks and its rail as it is today.
The text index is built in the background by §3's rule.

## 7. Explicitly out of scope

- OCR of a scanned script (D8); `FU-PROMPT-BOOK-OCR`.
- *What's left*: marking or filtering cues with nothing programmed. Declined on the page.
- A marker linked live to a bookmark, and a marker placed on the script (declined in answer to how
  bookmarks relate to a stack's markers).
- A Contents tab in the rail or a left panel (declined in answer to where Contents lives).
- Page thumbnails. The strip is the overview.
- Regular expressions, whole-word or case-sensitive search.
- Search across projects, or of a script that is not the book's current one.
- Moving another window's book to a bookmark through `windows.viewOptions`.
- A fly department's own markers (`FU-SCENERY-FLY-DEPT`).

## 8. Follow-ups

No session touches an FX tick, the output path, the auth gate's prefixes or adds a WS command, so
none of `followups.md`'s gates is expected to fire; each session still reads the index for one that
does. Session 1 adds `FU-PROMPT-BOOK-OCR` (Trigger: a show arrives with a scanned script that needs
searching).

## 9. Verification

- **Session 1:** on a real show's script, Claude is asked to anchor five cues by quoting their
  lines, and each lands on its line in the book without a `y` being given.
- **Session 2:** Chris finds a line he half remembers in an 80-page script from the top of the book
  in under five seconds, with the show running and locked, and Back returns him.
- **Session 3:** Claude populates acts, scenes and numbers for a whole script in one conversation,
  a second run doubles nothing, and the outline reaches a second desk through sync.
- **Session 4:** the rail reads as the script's own contents page with cues under each scene, and
  the strip shows at a glance which scenes have no cues yet.
