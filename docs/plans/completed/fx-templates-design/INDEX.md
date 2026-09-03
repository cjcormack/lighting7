# Effects on templates — design reference

Source: a Claude Design canvas authored 2026-09-02, drawn against `lighting-react`'s shipped
components rather than the older design canvases — the dark oklch tokens from `index.css`, the
44px `TemplateListRow`, the 56px `LookPadButton` with its none/some/all presence ladder, the
`LookStack` layer row with `LookNameBadge`, and the `ProgrammerFxList` effect row. **Read them as
the intended visual output, not as structure to copy**: there is no React here.

The implementation plan lives at [`../fx-templates-plan.md`](../fx-templates-plan.md). Its §4 is
a grep-able summary of what these files draw; where wording disagrees, the plan wins on behaviour
and these files win on layout and copy.

The live, pannable version is at
<https://claude.ai/code/artifact/5f04a67c-3c6d-4d74-9e4b-f4bd09f4111a> — private to Chris, so
**treat these files as the authority** and the URL as a convenience if you happen to have access.

## Format

Each `*.dc.html` is one artboard. All six are **static mockups**: no `{{ hole }}` bindings and no
logic script, so each renders standalone in a browser. `canvas.json` is the layout manifest plus
four sticky notes carrying the open questions (one effect per template; the default speed master;
what a plain click on an effect chip does; why Beam is excluded).

Every artboard is **dark-only on purpose**.

## The artboards

| File | Draws |
| --- | --- |
| `Main.dc.html` | The model: value / effect / "both is a Look", what stays the same, what is new, and one template rendered in its five places. |
| `NewTemplate.dc.html` | The New template sheet with **Holds: Effect** chosen — the added segment, the reused effect editor, the *Runs on* panel. |
| `Library.dc.html` | The Templates view with effect rows interleaved; the wave tile in the swatch slot; the subtitle grammar. |
| `Busk.dc.html` | The busk view: effect pads under an *Effects* hairline in each family column; Beam without one; the Looks pool unchanged. |
| `Programmer.dc.html` | The template strip with effect chips and the click / ⌥click tooltip; the layer row; *FX running* with the home badge and *Save as template…*. |
| `Elsewhere.dc.html` | Cue editor layer panel, add-layer picker, the effect colour picker rule, the delete guard, command palette and AI, the Looks cross-link. |
