# The cue model — design record

Source: design session R1 of [`../venue-visit-plan.md`](../venue-visit-plan.md), 2026-10-14. On the
first evening in the hall the same blackout ended every act, and each act's stack held its own copy.
The venue-visit plan's D1 decided the model: one shared cue, stacks as ordered membership, cues
outside any stack, and delete as its own explicit operation. This session worked out the rest.

**Status: approved 2026-10-14.** The implementation plan is
[`../cue-model-plan.md`](../cue-model-plan.md). A one-page copy for human readers is
[`cue-model.html`](cue-model.html), with a live copy at
<https://claude.ai/artifact/396mjPfVFE8D6oi2Xh6EHH>, private to Chris and a convenience; the files
here are the authority. Where the page and the plan disagree, the plan wins.

There are no `.dc.html` boards. The model is data and runtime, not layout. The two surfaces it adds
(the Cues table and the pad's two looks) follow existing library views and pads, and R2 designs the
Show view's cue editing.

## Asked and answered

Chris answered these on 2026-10-14, in four rounds. The plan numbers them D2–D13 and D15–D18; D1
and D14 come from the venue-visit plan.

| Question | Answer |
| --- | --- |
| What belongs to a stack membership? | **Position, number (and auto) and follow.** Fade, curve and everything else stay on the cue: one fade per cue. Per-stack fade overrides and "all timing on the membership" were declined. |
| The same cue live in two stacks, and one moves on? | **Held until the last.** One contribution; it fades in on the first hold and out on the last release. Two independent instances, and "taken over", were declined. |
| Prompt-book anchors? | **Per membership.** A cue used in two acts is anchored at each place. |
| How does existing data reach the new model? | **Recover the migration seam.** One startup migration with a backup copy, then `cues` rebuilt. A hidden "pool" stack row, and a fresh database by re-import, were declined. |
| What do pads, slots, `FireCue` and the hand name? | **The cue, run loose.** No follow and no tracking; a loose cue can be placed. A stack entry, and "either", were declined. |
| The same cue twice in one stack? | **Yes.** Everything keys on the entry. |
| Deleting a cue in use? | **409 `CUE_IN_USE` and `?force=true`.** The UI always confirms with the list. |
| Copy from another show on the same venue: scope? | **Mapped by key**, across versions, venue records by uuid first. "Same version only" was declined. |
| Where does a loose cue compose? | **Above the stacks**, newest press on top. |
| A cue pad whose cue is live only in a stack? | **Two looks.** Solid when held loose, outline when live through a stack; a press toggles the loose hold. |
| Markers? | **A labelled entry**, not a cue. |
| Where is the all-cues table? | **Its own nav item**, a library view beside Looks and Templates. A tab in the Show view was declined. |
| A GO into a cue already live through another hold: do its events fire? | **Only when it comes on**, on its first hold. "On every GO" was declined. |
| Removing the entry that is live? | **Allowed, stage unchanged** until the stack's next GO, which fires the entry that followed. "Released now" and "refused" were declined. |
| A stack delete leaves cues loose: offer to delete them? | **Offered, unticked by default.** "Never" was declined. |
| Copy's source? | **Any project.** Venue records match by uuid when both are on the same venue. "Same venue only" was declined. |

## What was found in the code

Read from `main` at `258aa34`, 2026-10-14, by three surveys: every reference to a cue, sync and the
MCP tools, and the runtime and frontend. The plan's §1 holds the findings with file:line; in short:

- **A cue's place in its stack is stored on the cue**, under a NOT NULL `cue_stack_id`, with numbers
  unique through a raw-SQL partial index. SQLite can't relax the column without a rebuild.
- **Deleting a stack deletes its cues**, and the card's Remove deletes one outright. Nothing
  cascades (`PRAGMA foreign_keys` is off), so every sweep is by hand.
- **About twenty sites derive the stack from the cue**: `fireCue`, `/apply`, `/stop`, the busk press
  and pad LEDs, Record and Update, scenery tracking, numbering, and several MCP tools.
- **The engine is keyed by cue alone** (Layer 4 rows, fade weights, triggers, priority), so one cue
  live in two stacks collides today. Priority is stamped from the cue's home stack.
- **Nothing persisted names a stack with a cue**: pads, slots, anchors, events, scenery and
  `FireCue` name the cue alone. Only the include target carries a stack.
- **Sync stores membership in `CueJson`**, at `formatVersion` 29; merges are per record and know
  nothing about order. `FU-SYNC-ORDINAL-DOUBLE` asks to land with the next bump.
- **There is no all-cues view**; the old `/cues` routes redirect to the Show view.
- **The REST cross-project cue copy leaks** the source project's Look and template ids onto the
  copy's layers.

## Format

`cue-model.html` is a hand-written page (no generator): its `:root` tokens colour the model, amber for
the cue, blue for an entry and teal for a loose hold, in light and dark. Its three diagrams are
inline SVG: the model (two stacks naming one cue, a marker, a loose cue on a pad), the hold timeline,
and two desks inserting into one gap. The page carries example names (Act 1, Act 2, Blackout,
Storm flash); they are illustrative, not the desk's data.
