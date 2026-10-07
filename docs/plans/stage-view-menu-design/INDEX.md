# The Stage view's View menu — design record

Source: Chris's brief, 2026-10-06. `StageViewMenu.tsx` is one dropdown of about thirty rows —
Source, Show, Haze, Labels, Light budget, Gobos on surfaces, Box shadows and Test recovery — mixing
settings stored per window with settings stored per machine. Two additions were asked for:

- **Realistic / Readable** — the stage-light plan's §10 question 1. Decided in the brief: Readable
  *lifts the dark* — the room's ambient and the materials' fill rise so unlit and black surfaces show
  their shape, like a work light, while pools keep their realistic exposure. Left open: per window
  or per machine, and what `render_view` does with it.
- **A frame-rate readout** — frames per second and ms a frame while the demand canvas draws, *idle*
  otherwise, a small corner readout toggled from the menu.

The brief's starting proposal, to be tested rather than adopted: split per-window *what you see*
(source, show, haze, labels, the look) from per-machine *performance* (light budget, gobos, box
shadows, the readout, test recovery).

**Status: approved 2026-10-06; both sessions shipped the same day (`9e8cb5f7`, `f80902ec`).** Chris approved the boards and settled the four questions they left:
work lights are announced to other windows, `render_view` draws them off unless asked, the level is
*a*, and the group is called **Work lights · Off | On** rather than *Rendering* (the plan's §10).
The implementation plan is [`../completed/stage-view-menu-plan.md`](../completed/stage-view-menu-plan.md). A live copy of all four boards on one page is at
<https://claude.ai/artifact/BhD6p5tGwFKQ319wBYAc7g>, private to Chris, a convenience; the files here
are the authority. Where a board and the plan disagree, the plan wins on behaviour and the boards on
layout and copy, as with every record in this directory.

## What was measured

- **Today's menu is 1,535 px tall** — 27 items in a 256 px dropdown, measured in Chromium on the desk
  (project 15, 2026-10-06). At 1440 × 900 it shows 801 px; Labels, the three machine settings and
  Test recovery are all below the fold. The Source group alone is 330 px, most of it hints.
- **The two tabs proposed are 502 and 457 px**, measured on the board.
- **Work lights (then *Readable*) were tried on the real renderer**, not painted: the live shader
  uniforms of one window were written (nothing stored, nothing sent to the desk) and frames read
  back at DPR 1.5 from *Balcony · desk* and the Plan. At the chosen level (ambient 0.003 → 0.02, every surface's fill
  +0.04) the floor pool moved 119 → 121 and the pool on the black backcloth not at all, while an
  unlit seat went 1 → 17 and the riser 1 → 11. Two findings shaped the proposal: at brighter levels
  the pale ceiling outshines the floor pool (125 and 155 against 123 and 127), and on the Plan the
  matt black housings vanish against the newly lit floor until their own fill rises with the room
  (0.225 → 0.8). Black serge stays at 2–5 of 255 at every level tried.
- **Found while drawing: *Beam cones* is the light switch.** Off unmounts `StageEmitters`, which
  packs the light table every surface reads, so the rig goes dark — no beam and no pool. The
  frame is on the Model board. Haze *Off* is what hides the cones alone.
- **The starting proposal mostly holds.** Three things moved: the readout is per window, not per
  machine (each window draws its own canvas at its own rate, and the two desk screens are one
  browser profile); Fixtures, Beam cones, Rigging, Regions and Labels are per *browser* today
  (`localStorage stageViewFlags`), not per window, so the redesign moves them; and Venue, Set,
  Seating and Haze are per window but not announced — only the viewpoint and the source ride
  `windows.viewOptions`.

No frame rate is quoted as measured: the browser pane throttles `requestAnimationFrame`, so the
readout's numbers on the boards are illustrative.

## What the boards propose, in one paragraph each

**The View button opens a popover with two tabs, View and Performance.** Segments and toggles show
every value at once where radio rows spent a line each; only the chosen source's and haze's hint is
shown. The split is by purpose, and scope is written on every group — *this window* or *this
machine* — rather than implied by the tab. The trigger names a source that is not Output
(*View · Next GO*). Keep the dropdown with submenus, two header buttons, and one scroll with a
disclosure were considered and declined (Model).

**View is this window's:** Source as a 2 × 2 with Next GO's live status, Show as seven toggles in
two rows (Fixtures, *Light* — renamed from Beam cones — Rigging, Regions; Venue, Set, Seating), Haze,
Labels and **Work lights** (Off | On — the stage-light plan's *Realistic / Readable*, named for what
it imitates). The Show flags and Labels move to `sessionStorage`, a window with nothing stored reading
the old key once.

**Work lights lift the dark** (the Work lights board): one table,
`off {ambient 0.003, lift 0, housing 0.225}` and `on {ambient 0.02, lift 0.04, housing 0.8}`, all
uniforms. The lift is the materials' existing directional fill, so faces turned different ways read differently. Exposure, roll-off,
haze, lenses and background are untouched. For black finishes the lift sees a floor albedo (about
4 %, tuned in the cyc harness) while every light keeps the true one. Per window, default off, riding
`viewOptions` as `workLights` beside the source, with a Screens row segment and `?workLights=` on
*Copy link*. `render_view` gains an optional `workLights`, off by default.

**Performance leads with what the canvas is doing** — frames per second and ms a frame, the lights
packed against the lights lit, the haze tier — then the three machine settings and Test recovery.
**The readout** is a chip in the canvas's bottom-left corner (one row above the section editor's
cursor strip on a section in Edit), amber past 28 ms a frame where the haze governor steps down, and
*idle* after a second with no frame. It never asks for a frame, is per window and off by default,
and never appears in a capture.

## Format

Each `*.dc.html` is one artboard, generated by `gen.mjs` (`node gen.mjs`) from the Stage boards'
stylesheet (`../stage-view-design/Stage.dc.html`) plus what these boards add, so each renders
standalone; `canvas.json` is the layout manifest. Every board is dark-only.

| File | Draws |
| --- | --- |
| `Menu.dc.html` | The Stage view at 1440 wide on *Balcony · desk* (the work-lights-on frame), the popover open on View with its Performance tab beside it, the readout in the corner, and today's height against the two tabs to scale, with the fold at 1440 × 900. |
| `WorkLights.dc.html` | Work lights off beside on, from real frames; the three levels tried and their measurements; the Plan with and without the housings' fill; what work lights are, black finishes, whose they are; the Screens row's new segment; `render_view`. |
| `Readout.dc.html` | The four states (drawing, slow, idle, paused); placement in view mode and on a section in Edit; the Performance tab; the rules. |
| `Model.dc.html` | Every setting's storage today and proposed, its wire and its `render_view` treatment; the proposal tested; the Beam cones finding; wire, storage and code; the two sessions; what was declined. |
| `plates/` | Frames read off the desk's own canvas, 2026-10-06, project 15, DPR 1.5. `balcony-*` from *Balcony · desk*, `plan-*` from the Plan (cropped to the venue). `readable-a`/`b`/`c` are the three trial levels, `-housing` adds the housings' 0.8 fill, `beamcones-off` is the view with Beam cones off. |
