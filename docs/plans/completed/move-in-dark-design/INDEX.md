# Move in dark: design record

Source: a design session on 2026-10-31. Chris asked what support the desk has for moving heads in
the dark in cue stacks. It has a per-row `moveInDark` flag that snaps a position at GO when the
outgoing cue stores dimmer 0, which no screen, Record or MCP tool can set. He chose to build a real
look-ahead: dark heads moved to the cue that lights them next, before its GO.

**Status: agreed 2026-10-31.** The implementation plan is [`../move-in-dark-plan.md`](../move-in-dark-plan.md).
Where the plan and the page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

Chris answered these on 2026-10-31, taking the recommended answer each time. They are the page's
§7 Q1–Q8.

| Question | Answer |
| --- | --- |
| The word on screen? | **Move in dark**, as the code and docs already say. *Move in black* (MA) and *Mark* (Eos, Hog) were declined. |
| On for stacks that already exist? | **New stacks on; existing stacks off** until turned on. On everywhere and off everywhere were declined. |
| Late as well as Early? | **Both, Early the default.** Early only, and MA3's UponGo and named target cue, were declined. |
| Which families move by default? | **Position, Colour and Beam.** Position only and Position with Beam were declined. |
| A moved head something lights before its GO? | **Let go**: it returns to the live cue's values over the move time, shown as *lit early*, and moves again once dark. Holding it at the target was declined. |
| The per-row flag? | **Retire it**: lifted into its cue's setting, the column and sync field dropped. Keeping both was declined. |
| A deliberate fly-in? | **Per head in the Cue tab**, beside the cue-wide Off. A per-patch *never moves in dark* was declined for now. |
| Defaults? | **Delay 1 s, move time 2 s.** |

## What other desks do

Researched from search extracts of the vendors' manuals and forums (the session's network policy
blocked the vendor sites themselves).

- **Eos:** AutoMark (show-wide, one cue ahead, after intensity reaches 0) or Referenced Marks; a per-cue
  D flag on the cue marked for; mark time is the cue's or a global Mark Time, added to the cue's
  duration; M, R, x flags in the cue list; out of order, marked cues fade to marks at once.
- **grandMA3:** Move In Black on the sequence (Never, Force Early/UponGo/Late) and on the cue part where
  the dimmer opens (None, Defined target, Early, UponGo, Late); MIB Delay and Fade, global, per cue
  part and per attribute; *Hold* on the dimmer opts a fixture out. A dimmer phaser blocks MIB.
- **MagicQ:** Move When Dark per stack (Position, Beam, Colour of heads at 0 % used next), one global
  time; Mark Cues as explicit cues. Users hold heads at 1 % to keep a live move.
- **Titan:** Move In Dark on list and cue (Early, Late, cue n, offset), Inhibit per cue, delay and
  fade per list or cue, fallback to the nearest usable cue.
- **Hog 4:** explicit Mark cues only; fade mark or time mark.
- **Onyx:** Auto Mark per cuelist or cue, non-destructive; a null intensity is not marked.

## What was found in the code

Read from `main` at `ade1846`. The page's §1 holds the findings with file:line: the row flag and its
arming (`fx/CueAssignmentResolver.kt:474`, `:617`); Record's MERGE overwriting it
(`routes/programmerRecord.kt:203`); the cue tier keyed by cue id (`fx/CueAssignmentLayer.kt:31`);
`effectiveNext` (`fx/CueRunStateTracker.kt:190`); the preview's row builder (`fx/CueApply.kt:523`);
the intensity classification (`midi/GlobalScalerState.kt:118`).

## The page

[`move-in-dark.html`](move-in-dark.html) is one theme-aware page with the desk drawn dark inside it:
today, the six desks, twelve rules (D1–D12), an interactive timing model of one head through three
cues, seven screens (stack settings, the cue table's Dark column, the Cue tab, the stack row and phone
runner, the Stage view overlay, the Timeline, the key stack), what changes where, eight questions
(Q1–Q8) and a five-session outline.

It is published at <https://claude.ai/artifact/PMc1Z7ZuNJ7pWBU7rMkETa>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

The cues, heads and timings are illustrative.
