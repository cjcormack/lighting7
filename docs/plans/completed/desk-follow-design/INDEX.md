# Following the desk — design record

Source: six boards drawn 2026-09-23 against `lighting-react` as of `09960e8f`, in the busk records'
dark vocabulary. **Read them as the intended visual output, not as structure to copy**: there is no
React here.

The brief, from Chris on 2026-09-23: review the busk-chrome plan's D18 (chips drawn only while a
window is unlinked) as its own follow-up rather than inside that plan. His reason for D18 was that
a window in Rig focus with its own selection selects for nobody, but it is more nuanced than that.
Two questions to answer: what is the use case for following, or not following, the busk page? And
should follow be settable from the Screens (windows) sheet? Plus: how do other desks do it?

**Status: built 2026-09-23 — all eight calls answered by Chris the same day, all three sessions
shipped; a ninth call (the badge is the toggle, plan D11) was added after session 3.** The
implementation plan is [`../desk-follow-plan.md`](../desk-follow-plan.md). The live copy of these
boards is at <https://claude.ai/artifact/5XfWNSgipBMMnb4GPBF2ph> — private to Chris, a convenience;
the files here are the authority. Where a board and the plan disagree, the plan wins on behaviour and
these files on layout and copy.

## What the boards propose, in one paragraph each

**A window's own selection is offered only where it can both select and act** — busk Split and the
Programmer. Rig and Pads always follow; entering either while local drops the local selection and
toasts. Selection follow is set from the chip itself — a small link badge beside the family pill
while following, which presses to leave, and the dashed chip while local, which presses back — and,
for another window, from the Screens row (a new `windows.follow` command) and ⌘K.

**The desk page stays a paging group** — the consoles' universal shape — named *Paged with the
desk · Own page* and settable both ways from the Screens row by applying the `pageFollows` view
option windows already announce. While paged with the desk a window shows a link badge beside its
tabs, naming any other window paged with it; pressing it keeps the page on show as the window's own,
and the *Page: Own* chip that replaces it presses back.

## The boards

| File | What it draws |
|---|---|
| `Main.dc.html` | What we have, what the review found, what is proposed. |
| `Survey.dc.html` | Six consoles from their manuals — grandMA3, Eos, Hog 4, MagicQ, Titan, Onyx — on selection sharing, page sharing and where the switch lives, with sources. Items the manuals did not settle are marked. |
| `Selection.dc.html` | The select/act table per surface, the rule, the relink toast, what it does to D18. |
| `Pages.dc.html` | What the desk page is for, the paging group today and proposed, the link badge's three states, the scenarios. |
| `Screens.dc.html` | The Screens sheet with the Page and Selection segments, the selection mark in Split, ⌘K. |
| `Model.dc.html` | The wire, the client, the sessions, and the nine decisions as answered. |

## Superseded by the plan

- **The Model board's sessions** draw the client work as one session; the plan makes it two,
  because decisions 4 and 8 (both badges) were answered after the board was drawn.

## Regenerating

`python3 gen.py` rewrites every board and `canvas.json` from `boards.py` and `survey.py`. It writes
`canvas.json` from scratch, so merge by hand onto a copy re-saved by the canvas editor, which adds
keys of its own.
