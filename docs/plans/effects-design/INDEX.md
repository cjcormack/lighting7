# Effects — design record

Source: design session R4 of [`../venue-visit-plan.md`](../venue-visit-plan.md), 2026-10-15. The first
evening in the hall wanted a breathing zoom, a colour cycle the other way round, and effects that did
not all start at one beat. R4 asked what MagicQ, grandMA3, Eos and Hog 4 offer, what our 24 built-ins
lack against them, and which of the gaps a theatre and busking desk should close.

**Status: draft, 2026-10-15, in review.** The implementation plan is
[`../effects-plan.md`](../effects-plan.md). A one-page copy for human readers is
[`effects.html`](effects.html), with a live copy at <https://claude.ai/artifact/LttskDxQVM8upsDYoBj9P5>,
private to Chris and a convenience; the files here are the authority. `node gen.mjs` regenerates the
page, and its figures are computed from the plan's §3 rules rather than drawn by hand.

## What is here

- [`survey.md`](survey.md) — the four desks from their own manuals, every claim cited to its page.
- [`effects.html`](effects.html) and [`gen.mjs`](gen.mjs) — the page: the survey as a comparison table
  with the gaps marked, the new parameters drawn (spread, blocks, groups, wings, direction, size, the
  fade), the decisions and the sessions.

There are no boards. The one new surface is a section of the effect editor (plan §4.1), drawn on the
page as its dial preview; the editor's layout is otherwise the fixture-fx-sheets record's.

## Asked and answered

Chris answered these on 2026-10-15, in five rounds. The plan numbers them D1–D13.

| Question | Answer |
| --- | --- |
| What does a cue's fade do to effects? | **Fade by size.** Per-effect entry/exit modes, and the snap, were declined. |
| Should an effect carry a Size? | **Yes, 0–100 % over what is underneath.** A 0–200 % amplitude and a runtime-only size were declined. |
| An effect on loose fixtures? | **One effect over the selection, in selection order.** Rig order and unison were declined. |
| Which shaping parameters? | **Spread %, Wings, Blocks and Groups.** |
| Where does direction live? | **On every running effect.** A parameter per effect was declined. |
| How do effects reach beam? | **Beam built-ins of their own.** Level effects on any beam slider, and the picker only, were declined. |
| Wheels and slots? | **A steps effect** — and, since a palette cannot hold a slot, **Slot Steps by option name** beside Palette Steps. |
| Which smaller features? | **Size on a fader and N-shot.** One Width/Softness and a rate scale were not taken. |
| The first beam set? | **Zoom Breathe, Iris Pulse, Frost Pulse, Focus Pull.** |
| A Beam speed master? | **Yes, Beam joins `usage`.** |
| Where do new parameters go in the editor? | **Size and Direction beside Speed, a Spread section.** All under Advanced was declined. |
| What does a Size fader scale? | **One cue stack's effects**, at runtime like the grand master. Per family and per speed master were declined. |
| This plan and R3's session 2? | **This plan's selection session first.** |
| Do programmer gestures fade effects? | **Yes, at the programmer fade.** |
