# After the rig session — design record

Source: Chris's notes from an evening programming a show on a venue's rig, 2026-11-09. Thirteen
were written down: two on OSC, two on the programmer, two on FX, two on fixtures, one on patching,
three on the Show view and one on the Prompt Book. Two more followed the same day: Safari scrolling
the whole page from the Show view, and Record's *A new cue after Q13* appending to the stack.

**Status: agreed 2026-11-09.** The implementation plan is
[`../venue-rig-fixes-plan.md`](../venue-rig-fixes-plan.md). The one-page copy is
[`venue-rig-fixes.html`](venue-rig-fixes.html), hand-written and theme-aware, with a live copy at
<https://claude.ai/artifact/WkajZ8H5EJMCNUsw8ZkJjY> (the checked-in file is the authority). Its
§Decisions holds Q1–Q11.

## Asked and answered

Chris answered these on 2026-11-09, in three rounds.

| Question | Answer |
| --- | --- |
| What went wrong with auto numbers? | A typed Q30 followed by an auto Q40, perhaps on a stack from before the recent changes. |
| The fix? | **Always derive auto numbers from position**, with a startup tidy-up. Without the tidy-up, and closing gaps only after a typed number, were declined. |
| What may a show change of a venue fixture? | **Its display name only**, as a facet. Name and key was declined. |
| Blinders' *Dark too briefly*? | **Instant rows snap in the dark.** Never moving them, and hiding the warning only, were declined. |
| Where does the OSC address list live? | **In the OSC settings tab.** Its own page, and a sheet from anywhere, were declined. |
| Include and multi-head fixtures? | **Select the fixture** when every head came in; open it for that landing only when some did. |
| Record's existing-cue order? | **Show order, one row a cue.** A shared cue under every place was declined. |
| Path's single size? | **Size scales both**, the separate sizes under More. Size and Aspect, and a link toggle, were declined. |
| When does the show's list scroll? | **On entry, GO and arm**, pausing 10 s after a hand scroll. Always centring, and only on entry, were declined. |
| After Duplicate? | **Stay put and select the copies.** |
| FX colour pickers? | **One editor, the programmer's.** R/G/B fields added to the FX body was declined. |

## What was found in the code

Read from `main` at `29dd61c`, 2026-11-09; each item's files are in the plan's §1.

- **OSC senders match by exact IP string**, and nothing in the codebase parses CIDR. Replies go to
  the packet's source, so a range needs no reply change.
- **The cheat sheet is a document written into a blank window and printed**; no view shows the
  addresses.
- **An auto cue that does not move keeps its number** (`AutoNumberInput.keep`, venue-visit plan
  D11), so a typed number before it leaves it behind; out of order is judged against the autos'
  stored numbers; and an archive's carried numbers, held autos included, import as typed. Changing
  the derivation needs a sync bump, as v48's did.
- **Only the Cards view scrolls to the live cue**; the Table and Timeline (virtualised) never do,
  and arming never scrolls anywhere.
- **Duplicate from the cue sheet's bar navigates**, remounting `ShowPage`; the `?cue=` deep link
  then turns the editor pane on.
- **Include's landing expands every parent of an element key it selects**, and the expansion is kept
  in `sessionStorage`.
- **Record's cue picker sorts by name**, as `GET /cues` does.
- **Record's *A new cue after Q13* sends only the stack**, though the route takes a position.
- **Path's sizes are two parameters**; the only combined control is a low/high `pair`.
- **The FX colour body has no R/G/B fields** and detects emitters from fixture-level attributes
  only, missing a Shehds' zones.
- **Move in dark treats every non-intensity row as a mechanical move**, whatever the head.
- **A venue patch's display name is read from the venue's row**, and no facet can reach it; names
  are unique nowhere.
- **The Prompt Book's selection is not refused by the lock out of production**; the follow scroll
  and page unmounting are the suspects.
- **Nothing sets `overscroll-behavior`**, and the shell's root may scroll.

## Format

`venue-rig-fixes.html` is the record. Names in its mock-ups (Cinderella, Act 1, QLab Mac) are
illustrative.
