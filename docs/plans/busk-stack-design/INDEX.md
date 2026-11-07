# Stack pads: design record

Source: a design session on 2026-11-07. Chris asked how to run a pantomime from one cue stack while
busking the musical numbers, whose backing tracks are fixed, without complicating that stack, then
whether a busk button could step through a separate stack of cues. A surface's `CueStackGo` already
can; a busk pad cannot, and every stack sits in the show's running order, so the show's own GO would
step into a song stack. He asked for a design to trigger a separate stack from a busk button, then
added a second use: a short stack that runs itself when Carabossy enters, replacing the state the
main stack's cue is giving, and hands it back when it ends. The OSC plan landed on main during the
session, and the design was checked against it.

**Status: agreed 2026-11-07.** The implementation plan is [`../busk-stack-plan.md`](../busk-stack-plan.md).
Where the plan and the page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

Chris answered these on 2026-11-07, in three rounds, taking the recommended answer each time. They
are the page's Decisions, Q1–Q12.

| Question | Answer |
| --- | --- |
| The words on screen? | **Busk stack**, set by the switch *In the running order*; its pad a *stack pad*. *Side stack* and *Run from pads only* were declined. |
| May a pad name a stack in the running order? | **No**, busk stacks only. |
| After the last cue? | **Stay, Loop or Release**, a busk stack set to Release unless Loop is on. |
| Solo banks? | **A start takes siblings off, and a sibling's on press releases the stack**; an advance takes nothing off. |
| A busk stack with pads put back into the running order? | **409 naming the pages**, `?force=true` taking the pads off. |
| A button that stops a stack? | **Withdrawn**: the OSC plan's `CueStackStop`. |
| Busk stacks on the Show view? | **After the show's tabs, past a divider**, never made the playhead. |
| Does a release drop the effects arm? | **Yes**, as every stop does. |
| How do stacks rank for LTP? | **Latest GO wins**, busk stacks in a band above the show's stacks. Ranking busk stacks by when they started, one band for all, and recency for busk stacks only were declined. |
| A new busk stack, and FX under Replaces? | **Adds**, Replaces per stack; FX underneath **held back and resumed**. |
| Release time and Replaces: which stacks? | **Release time on every stack, Replaces on busk stacks only.** |
| Beside the OSC plan? | **Use its `CueStackStop`**, and the stack-pad and surfaces sessions wait for OSC session 1. |

## What was found in the code

Read from `main` at `6a5ffcf`, the OSC plan at `9bc4001`:

- **A pad names a palette, a Look or a cue** (`models/buskLayout.kt:160`), with a CHECK that
  exactly one is set. A cue pad toggles a loose hold (`routes/BuskPressService.kt:209`); nothing
  presses a stack.
- **The operator's database already holds that CHECK**: the terminology step rebuilt `busk_pads`
  from the model (`state/TerminologyMigration.kt:146`), and SQLite cannot alter a CHECK, so a fourth
  arm needs the table rebuilt again.
- **A surface can GO any stack** (`midi/SurfaceActions.kt:341`, `CueStackManager.go`), but no
  binding stops one; the OSC plan adds `CueStackStop`.
- **The running order is every `STACK` row** (`routes/projectShow.kt:285`), and the Show view's GO
  with nothing on deck advances the playhead to the next one, releasing the stack it leaves
  (`hooks/useShowTransport.ts:306`, `routes/projectShow.kt:113`).
- **A stack cannot take light away from another**: intensity is highest-takes-precedence across
  every live cue (`fx/CueAssignmentResolver.kt:367`), and the rest is LTP by a priority derived from
  the stack's id (`fx/CueApply.kt:233`), so the stack made later wins, not the one started later.
- **The main stack's FX paint over another cue's values** (Tier 3 over Tier 4), and cue stomp
  removes them for good rather than holding them back (`FxEngine.stompForCue`, `fx/FxEngine.kt:2011`).
- **A stack's release snaps** (`Departure.SNAP`, `fx/CueStackManager.kt:472`).

## The page

[`busk-stack.html`](busk-stack.html) is one theme-aware page in the design-record style, its
mock-ups drawn dark in both themes as the desk is: today, busk stacks and the running order, how a
busk stack adds to or replaces the main stack, worked through Carabossy's entrance, the stack pad and
its gestures, solo banks, surfaces and OSC, storage and sync, edges, the decisions and the
four-session outline. Names in its mock-ups (Cinderella, *Song 3 · Ugly Sisters*, cue numbers such as Q2
and Q14) are illustrative; Carabossy is the operator's example.

It is published at <https://claude.ai/artifact/MXqQkJKb3rK1PXzwTcSM5g>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.
