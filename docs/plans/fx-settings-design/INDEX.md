# FX settings: design record

Source: a design session on 2026-10-26. Chris set up Colour Cycle over four three-zone Shehds at
Spread 100 % and saw the colour edges stagger between fixtures. The graph was right: four heads start
a quarter of a cycle apart, and three colours change every third. At 133 % each fixture starts one
colour behind the last. He asked for a review of every FX setting and type, against use-cases and
other desks, to make the editor simpler without losing the engine.

The engineering half is [`../fx-settings-plan.md`](../fx-settings-plan.md). Where the plan and the
page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| Group the 29 built-ins into looks in the editor over unchanged types, or consolidate them on the desk? | **Consolidate on the desk.** Looks in the client only were declined. |
| How are stored FX naming an old type handled? | **Rewrite once, and read old names** wherever they can still arrive (an archive, MCP, a script). Keeping old ids as aliases forever was declined. |
| Is "one step behind" stored, so it survives a change of selection? | **Yes, a stored spread unit** the desk resolves at expansion. A button that writes 133 % once was declined. |
| Does a new step FX start one step behind? | **Yes.** A stored FX keeps what it has. |
| Words for Wings, Segments and Parts? | **Keep MagicQ's words**, with an example line. *Move together in · Repeat every · Mirror* and grandMA3's *Blocks · Groups · Wings* were declined. |
| *Width: One head* (one head on at a time) in this plan? | **Yes.** |
| Cycle in the inspector? | **A chip** in a Timing strip. Keeping the eight-button grid was declined. |
| Comment: custom percentages beside the Width presets? | **Every preset segment ends in a typed value.** |
| Comment: do the pattern chips lose their drawings? | **No**, and they are now drawn on the selection's own heads. |

## What other desks do

Researched from search summaries of the vendors' manuals and forums (the session's network policy
blocked the vendor sites themselves).

- **MagicQ:** Spread is a share of the cycle (100 % = even), with no alignment to an FX's steps; the
  operator types 133 %. Segments, Parts and Wings are its words, and ours. One to Four Shot. Dup,
  Include and Use Elements are our Over.
- **grandMA3:** phasers of steps with width and transition, MAtricks *Block · Group · Wings*. 2.4
  added **Adaptive Width** (a two-step phaser's first step sized to one fixture) and Adaptive Measure,
  the nearest any desk comes to aligning offsets to the selection. Its phase calculator's exclusive end
  is the four-heads-on-three-steps misalignment. Forum users find a basic chase slow to build.
- **Eos:** relative, absolute and step effects; Trail *Even · Solo*; Grouping; Duration in cycles;
  entry and exit modes.
- **Hog 4:** one effect with the waveform as a parameter (*Table*); Size, Rate, Offset and Length on
  the first wheel page; fanning is endpoint-inclusive, which happens to fit 4 heads on 3 steps. 4.0
  removing the stock colour effects drew complaints: keep presets in the picker.
- **Titan:** Spread shown as phase, where 180° reads as "every 2 fixtures"; Overlap in fixtures.
- **Onyx:** **Step per X** beside **Wave per X**, the offset by step as a first-class choice. This is
  the idea the plan's *1 step* takes.

## The page

[`fx-settings.html`](fx-settings.html) is one theme-aware page with the desk drawn dark inside it:
the case, an audit of every setting, the six desks' vocabulary, the proposed inspector with notes, a
live model of *Across the heads* on the desk's spread rules (Random shuffled differently), the FX
types consolidated, twelve recipes with previews, what changes where, and the seven answers.

It is published at <https://claude.ai/artifact/LDGAQvTygH5DwrJCUJ3LDk>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

The four Shehds, their names and every value are illustrative. The page's "Mix" and "Across the
heads" are section headings, not glossary words.
