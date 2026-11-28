# Prompt Book

The Prompt Book binds a show to its script: one PDF per project, cue anchors and notes placed on
it, and the desk's own reading of its text. This document covers the backend. The view is
`frontend/CLAUDE.md`'s Prompt Book sections and `frontend/docs/show-mode-engineering.md`; the
records and their sync are `docs/sync-engineering.md` §"Prompt books".

## The store

A script PDF is stored by content, outside the database and outside the JSON sync machinery:
`<appDataDir>/prompt-scripts/{projectUuid}/{sha256}.pdf` (`State.promptScriptPath`;
`promptBooks.scriptStoreRoot` in `local.conf` moves the root, which the tests use). The hash is the
script's identity, so the bytes for a name never change and `GET …/prompt-book/scripts/{hash}` is
cached forever. The view's import uploads the bytes (`POST …/prompt-book/scripts`, 100 MB at most,
`%PDF-` checked), counts the pages with pdf.js and binds the book to the hash
(`PUT …/prompt-book`). Sync moves the PDFs as raw bytes (`sync/PromptScriptRepoSync.kt`).

The book's rects (anchors and notes) are fractions of the page, `{page, x, y, w, h}`, with `page`
counted from 0 and `y` from the top. `coverPages` is the offset from a PDF page to the script's
printed number. The book's `pageCount` is the authority on how many pages there are.

## The text index

The desk reads each script's text once and keeps it beside the PDF as
`{projectUuid}/{hash}.text.json` (prompt-book navigation plan D1). The view's find and Claude's
search and quoting all read this one index.

```
{ version, scriptHash, pageCount,
  pages:   [{ page, scanned, lines: [{ text, x, y, w, h, cx }] }],
  outline: [{ title, level, page, y? }] }
```

- **Lines** come in reading order, PDFBox's position-sorted order, each trimmed of surrounding
  whitespace, with its box in the book's page fractions. `cx` holds the left edge of each character
  and then the right edge of the last (`cx.size == text.length + 1`), so a hit that starts mid-line
  is boxed on its own characters. A space between two words spans the gap between them. A line's
  top is its glyphs' tallest top, and its bottom reaches a quarter of their height below the baseline.
  Every value is rounded to four places, a tenth of a point on a letter page.
- **A page is `scanned`** when it has fewer than five lines (`ScriptTextPage.MIN_TEXT_LINES`, the
  viewer's own `MIN_TEXT_ITEMS` floor). There is no OCR (D8): such a page has little or no
  text, likely a scan, and is still searched, but a search names it beside its results, so a quote it
  may hold unread is never answered as plain no results.
- **The outline** is the PDF's own bookmarks, flattened depth first, `level` 0 at the top; `y` is
  the destination's top where it names one.
- Pages are counted from 0, as the book's rects are; the MCP tools convert to `pdfPage` counted
  from 1.

`promptbook/ScriptTextExtractor.kt` does the reading with **Apache PDFBox** (Apache-2.0, its three
modules in `licences/jvm.json`). It loads the PDF with a temp-file stream cache, so a long script
does not sit in the heap.

### A cache, never a record (P2)

The index has no table and no `SyncDispositions` row, is never exported, and may be deleted at any
time: it is rebuilt from the PDF. Install backups leave it out (`InstallBackup`'s prompt-scripts
tree skips `ScriptTextStore.isIndexFile`), and a restored desk rebuilds it on first ask. When a
project loads (`ProjectManager.createShow`), every index whose PDF has left the store is removed.
`ScriptTextIndex.VERSION` is moved whenever the extractor's output changes: an index of another
version reads as absent and is rebuilt.

### When it is built, and its states

`promptbook/ScriptTextStore.kt` (`State.scriptText`) owns the files. A build runs on the store's
own thread, `script-text-N`, a daemon at the lowest priority: never a show thread, and never inside
a request. It is asked for when a script is uploaded, when a book is bound to a hash, when sync or
an import copies a PDF into the store (`PromptScriptRepoSync.hydrateStore`), and when the index is
asked for and absent. A second ask while one is queued queues nothing.

`state(projectUuid, hash)` never waits, and answers one of:

| State | Means | `GET …/prompt-book/text` | Tools |
|---|---|---|---|
| `READY` | The index is on disk. | 200 `{state, scriptHash, index}`, `ETag` on version and hash, `Cache-Control: no-cache` (304 on a match) | Answered |
| `BUILDING` | Being read. | 202, `Retry-After: 2` | `textState`, no result count; a quote is refused and nothing is written |
| `SCRIPT_MISSING` | The PDF is not on this desk, as on a pulled show whose PDF has not arrived. | 200 `{state, scriptHash, message}` | As building, saying so |
| `UNREADABLE` | PDFBox could not read the PDF. Remembered until the desk restarts, so it is not read again and again. | 200 `{state, scriptHash, message}` | As building, saying so |

None is ever answered as an empty index or as zero results. The route answers any project for both
roles, and 404 for a project with no book. A ready answer is the stored file spliced into its
envelope, never decoded and re-encoded. The store keeps the last index it read in memory, keyed by
file and modification time, so a search per keystroke reads the file once. A PDF the desk counts
differently from the book's `pageCount` is logged once and never rewritten.

## Matching

`promptbook/ScriptTextMatcher.kt` is the one match rule (P1), used by `search_prompt_book` and a quoted
`at`; from session 2 the view's find mirrors it in TypeScript, for typing speed:

- each character is decomposed (NFKD, so a ligature reads as its letters), its combining marks
  dropped and the rest lower-cased;
- curly single quotes, primes and backticks read as `'`, curly double quotes and guillemets as `"`;
- every run of whitespace, a non-breaking space included, is one space;
- a page's lines are joined by one space, so a query may run from the end of one line into the
  next, but never from one page into the next.

Hits are found left to right and never overlap. A hit is a list of spans, one per line it touches,
each `[start, end)` in that line's own characters, which `rects` turns into one box per line. The
rule is pinned by `src/test/resources/promptBook/textSearch.fixture.json`, read by
`ScriptTextMatcherTest` and, once session 2 adds the mirror, by the view's tests: change the rule there
first.

## Claude's places

The MCP side is `docs/mcp-engineering.md` §"Show-setup tools": `search_prompt_book`,
`get_prompt_book`'s `textState`, `hasText`, `scannedPages`, `pdfOutline` and `pages`, and the `at`
every prompt-book writer takes, a quote or a page and height (`ai/ScriptPlaces.kt`). A quote is
resolved against the book of the project the tool writes to, from one read of the index per call.

## Tests

- `promptbook/ScriptTextExtractorTest` over `typed-script.pdf` and `scanned-script.pdf` under
  `src/test/resources/promptBook/`, hand-written PDFs whose text sits at known places (the test's
  KDoc says where): reading order, boxes within a tolerance, the outline, a scan.
- `promptbook/ScriptTextMatcherTest`, the shared vector and a hit's boxes.
- `promptbook/ScriptTextStoreTest`, the states, a rebuild after deletion and after a version
  change, an unreadable PDF and the prune, with builds held by `testsupport/HeldExecutor`.
- `routes/PromptBookTextRouteTest` and `mcp/McpPromptBookTextTest`, each state from the route and
  the tools, a quote resolved, missing and ambiguous with and without `pdfPage`, and the prune at
  project load; `backup/InstallBackupTest` holds the index out of a backup.
