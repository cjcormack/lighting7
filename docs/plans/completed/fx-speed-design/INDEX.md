# FX speed: design record

Source: a design session on 2026-11-06. Chris asked to reconsider how speed works in FX: tying it
to a bar or a beat is clunky and hard to understand, when a speed slider with snaps is what an
operator wants; *Seconds* instead of a speed master is not much use, when tapping the value is; and a
cue's speed masters are a useful idea that is unnatural to programme, when you would rather set the
tempo in the FX editor and have Record take it from the programmer.

The engineering half is [`../fx-speed-plan.md`](../fx-speed-plan.md). Where the plan and the page
disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| What does Speed ×1 mean? | **One cycle a bar**, today's default for a new FX. One cycle a beat (Hog's reading) and a step FX counting steps were declined. |
| What replaces Seconds? | **Own tempo, tapped**, with the same slider on it. Masters only, and keeping Seconds with a Tap, were declined. |
| Which taps go into the programmer? | **The FX editor's, plus *Capture*** on a rail card (drawn first as *Hold*, renamed in review because the glossary's Hold is a Pulse's). The rail and surfaces stay live. Every tap being held was declined: a busk tap would then block the next cue's tempo. |
| Extras? | **×½ and ×2 on masters**, and **an FX speed fader or encoder**. |
| A master's word? | **Tempo**, so Speed is the FX's. The glossary gave Speed to the master. |
| A rail or surface tap on a held master? | **It moves the hold**, rather than going live underneath where nothing would show it. |

## What other desks do

Researched from search summaries of the vendors' manuals and forums. The session's network policy
blocked the vendor sites themselves.

- **grandMA3:** phaser Speed per attribute in BPM, Hz or seconds a beat, with Measure for the beats a
  phaser takes; a phaser attribute names its speed master; a master's **Speed Scale** multiplies or
  divides, and **Learn** taps it (by default ignoring the scale). Speed is a layer value Record stores.
- **MagicQ:** FX speed in BPM; up to 100 speed masters, per cue stack or per FX, an FX's own winning;
  **Tap to time** on a playback sets the current cue's FX speed; a master on a stack overrides the
  recorded speed.
- **Eos:** an effect's cycle time or BPM; **Learn Time** averages taps; BPM is a cue-level override,
  starred once recorded.
- **Hog 4:** Rate in BPM beside Size, Offset and Length; rate masters scale a cuelist by percent and
  batch faders give 0× to 4×; tap sync on a master; rates stack at cue, cuelist and fader.
- **Titan:** shape speed in BPM; BPM masters replace and Rate masters scale; tap or a typed number on
  a master's key.
- **Onyx:** a non-linear FX speed value with no BPM link and no native tap; users script it.

Every desk but Onyx shows a rate, relates masters to FX by scaling and makes tap the main way in;
grandMA3, MagicQ, Eos and Hog record the live speed into the cue.

## The page

[`fx-speed.html`](fx-speed.html) is one theme-aware page with the desk drawn dark inside it: today's
three problems, the six desks, the model (Tempo × Speed = the cycle) and its words, a live model of the
Speed control (Tap, the snaps, the glide back onto the grid), the editor's Speed section with
notes and its labels table, tempo in the programmer with its rules, the two extras, what changes
underneath, the four sessions and the six answers.

It is published at <https://claude.ai/artifact/MmKL1MQSdmeFzXsav5H8cU>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

M1 *Main*, M2 *Chase*, M3 *Moves*, cue 12, the fixtures and every tempo are illustrative.
