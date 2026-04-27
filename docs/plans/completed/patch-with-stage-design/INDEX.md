# Patch with Stage — design reference

Source: Claude Design (claude.ai/design) handoff bundle exported by Chris on
2026-04-26 (`Lighting Project-handoff.zip`). The bundle's original README is
here as [`README.md`](README.md) — it explains the handoff format and the
intent (recreate the visual output, not copy the prototype's structure).

The implementation plan lives at
[`../patch-with-stage-plan.md`](../patch-with-stage-plan.md). Files we are
**not** implementing from the bundle (Busking View, Program Mode, mobile
overlays) were left out to keep this directory focused — see the original ZIP
if you need them.

## Entry point

[`Patch with Stage.html`](Patch%20with%20Stage.html) — the design canvas. It
mounts every variation (A/B/C of the patch sheet, A/B/C of the stage toggle,
mobile equivalents). The `<DCArtboard>` wrappers are scaffolding, ignore them;
read the components they host.

## What to read for each phase

### Phase 1 — Backend
- [`patch-data.jsx`](patch-data.jsx) — canonical shape of the per-fixture
  patch record in the design (`fixtures[]`: `id, x, y, pos, beam, gel,
  fxKind, isGeneric, ...`). Our backend column names are listed in the plan;
  this file shows which fields the design assumes exist.

### Phase 2 — Patch list + fixture sheet (Variation A)
- [`patch-desktop.jsx`](patch-desktop.jsx) — `PvVariationA` is the layout we
  are implementing. `PvBackdrop` shows the patch list with the new Position
  column. `PvIdentityRows` is the existing identity/address block.
- [`patch-bits.jsx`](patch-bits.jsx) — shared form bits we are reproducing in
  React: `PvPositionInput`, `PvBeamAngle`, `PvGelPicker`, `PvSection`. Also
  `PvPatchList` shows the patch table column order including the new
  Position column.
- [`patch.css`](patch.css) — visual specs for the inline mini-stage
  (`.pv-stage*`), patch sheet rhythm, beam cone (`.pv-beam-cone`), gel
  picker, and patch table.

### Phase 3 — Global stage toggle (Variation B · slide-down panel)
- [`stage-toggle.jsx`](stage-toggle.jsx) — `StageToggleHost` with
  `mode="panel"` plus `StagePanel` is the variation we're implementing.
  **Ignore `StageFixtureBar`** — per user direction, marker clicks open the
  existing `FixtureDetailModal` instead.
- [`stage-toggle.css`](stage-toggle.css) — `.stage-panel*` rules for the
  half-height drop, header strip, and slide-down animation.
- [`stage-view.jsx`](stage-view.jsx) — `MarkerLive`, `StageBackdrop`,
  `deriveLive` are the marker visuals (live colour glow, beam cone) we want
  to reproduce against real channel-state data. The `StageView` page itself
  is *not* what we're building; we're using its parts inside a slide-down
  panel.
- [`stage-view.css`](stage-view.css) — marker / backdrop styling
  (`.sv-mk-live`, `.sv-cone`, `.sv-glow`, `.sv-stage-grid`).

### Shared
- [`styles.css`](styles.css) — base CSS variables (`--accent`, `--text-*`,
  `--border-*`, `--mono`, `--sans`). Translate by name to our Tailwind v4
  tokens; don't copy values literally — our dark theme is OKLch-based.
- [`design-canvas.jsx`](design-canvas.jsx),
  [`ios-frame.jsx`](ios-frame.jsx) — design-canvas chrome only, ignore.
- [`patch-mobile.*`](patch-mobile.jsx),
  [`stage-mobile*`](stage-mobile.jsx) — mobile variants are out of scope for
  the initial cut (see plan's "Out of scope" section).

## How to view

The HTML file expects React + Babel-standalone CDNs and same-directory access
to all the JSX/CSS. To preview locally:

```sh
cd docs/plans/patch-with-stage-design
python3 -m http.server 8080
# then open http://localhost:8080/Patch%20with%20Stage.html
```

The bundle README discourages screenshotting — read the source directly for
dimensions, colours, and layout rules.
