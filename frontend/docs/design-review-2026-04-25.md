# Critical Design Review — Lighting App

_Date: 2026-04-25_
_Scope: Full UI/UX audit of `src/` against the rules in `CLAUDE.md` and the most complex user flows (Cues, Runner, Busking, FxLibrary, Presets)._

Findings are ordered by user impact, not by area.

---

## 1. Live-show safety — the highest-stakes gaps

The runner is where mistakes are most expensive. Several places lack guardrails.

- **DBO state isn't loud enough on mobile.** [src/components/runner/ShowRunnerMobile.tsx:134](../src/components/runner/ShowRunnerMobile.tsx) toggles a destructive-variant button with a glow when DBO is on. On a small screen that's easy to miss while a show is running. Add a sticky banner mirroring the [OutOfOrderBanner](../src/components/runner/OutOfOrderBanner.tsx) pattern: "DBO ACTIVE — all output muted."
- **Stack activate/deactivate has no confirmation, no undo.** [src/routes/RunPage.tsx:161](../src/routes/RunPage.tsx) and the cue-advance path fire immediately. A misclick in the booth changes live state with no recovery. Either confirm destructive runner actions or give a 2-second "Undo" toast.
- **Blind-mode editing is visually indistinct from live.** [src/components/cues/editor/CueEditor.tsx:94](../src/components/cues/editor/CueEditor.tsx) tracks `editMode`, but the toggle is just a control — there's no full-width banner. Operators editing blind can mistakenly believe the rig will respond, or vice versa. Add a coloured banner across the editor when in blind.
- **Blind-mode edits are lost on sheet close** with no warning ([src/components/cues/editor/CueEditor.tsx:48](../src/components/cues/editor/CueEditor.tsx)). The unsaved-changes pattern from [ScriptForm](../src/components/scripts/ScriptForm.tsx) and [UnsavedChangesDialog](../src/UnsavedChangesDialog.tsx) exists — wire it through here.

## 2. Sheet vs Dialog rules in `CLAUDE.md` are violated in several places

- **`Dialog` used for forms.** [src/components/ChannelValueDialog.tsx](../src/components/ChannelValueDialog.tsx), [src/components/fixtures/GroupPropertiesDialog.tsx](../src/components/fixtures/GroupPropertiesDialog.tsx), and the duplicate-cue prompt at [src/routes/Cues.tsx:944](../src/routes/Cues.tsx) are all editing surfaces — by the project rule they should be Sheets. Worse, some of them already _implement_ Sheet markup but keep "Dialog" in the name, which is confusing for the next contributor.
- **`Modal` naming for read-only Sheets.** [src/components/fixtures/GroupDetailModal.tsx](../src/components/fixtures/GroupDetailModal.tsx) and [src/components/groups/FixtureDetailModal.tsx](../src/components/groups/FixtureDetailModal.tsx) are both Sheet-like surfaces. Rename to `*Sheet` or `*Drawer` so the convention is enforceable by ripgrep.
- **Ad-hoc overlay instead of `AlertDialog`.** Delete-cue confirm at [src/routes/Cues.tsx:864](../src/routes/Cues.tsx) is hand-rolled with `fixed inset-0`, while the stack delete just below uses a real Dialog. Replace with [`alert-dialog`](../src/components/ui/alert-dialog.tsx).
- **`size="sm"` in footers.** [src/components/patches/EditGroupSheet.tsx:184](../src/components/patches/EditGroupSheet.tsx) and inline destructive buttons in [src/components/cues/CueTriggerEditor.tsx:293](../src/components/cues/CueTriggerEditor.tsx), [src/components/cues/editor/EffectFlow.tsx:439](../src/components/cues/editor/EffectFlow.tsx) violate the no-`sm`-in-footers rule.

## 3. Picker proliferation — 11 pickers, 11 patterns

- [EffectTypePicker](../src/components/fx/EffectTypePicker.tsx)
- [fx/PresetPicker](../src/components/fx/PresetPicker.tsx)
- [cues/editor/PresetPicker](../src/components/cues/editor/PresetPicker.tsx) _(yes — same name, different file, different behaviour)_
- [FixtureTypePicker](../src/components/presets/FixtureTypePicker.tsx)
- [CueTargetPicker](../src/components/cues/CueTargetPicker.tsx)
- [BindingTargetPicker](../src/components/surfaces/BindingTargetPicker.tsx)
- [FxColourPicker](../src/components/fx/FxColourPicker.tsx)
- [FxColourListPicker](../src/components/fx/FxColourListPicker.tsx)
- [StackPickerSheet](../src/components/runner/StackPickerSheet.tsx)
- [GroupComboInput](../src/components/patches/GroupComboInput.tsx)
- [searchable-select](../src/components/ui/searchable-select.tsx)

They each handle search, hierarchy, keyboard nav, and click-to-select differently. The hierarchical [FixtureTypePicker.tsx:186](../src/components/presets/FixtureTypePicker.tsx) has no breadcrumb and no keyboard nav at all. Two `PresetPicker` files with different behaviour is a smell on its own. Worth extracting a shared `<Picker>` primitive (or building on cmdk like [CommandPalette](../src/components/CommandPalette.tsx) already does) — this is the single largest consistency win available.

## 4. Selection and save state are opaque

- **Selection lost on tab switch** in [src/components/cues/editor/CueEditor.tsx:95](../src/components/cues/editor/CueEditor.tsx) — flipping between Groups/Fixtures clears `selection`. Either persist per-tab selection or scope to URL state.
- **Inline-edit save has no feedback.** [src/components/cues/InlineEditCell.tsx:91](../src/components/cues/InlineEditCell.tsx) and the debounced field paths in [src/routes/Cues.tsx:188](../src/routes/Cues.tsx) and [src/components/runner/EditorPanel.tsx:83](../src/components/runner/EditorPanel.tsx) save quietly. Add a transient "saving…" → ✓ next to the cell, or a global toast.
- **Mutations succeed silently across the app.** Only [ScriptForm](../src/components/scripts/ScriptForm.tsx) and [AddFixtureSheet](../src/components/patches/AddFixtureSheet.tsx) call `toast()`. Cue/preset/group/stack mutations don't. Standardise on a thin RTK Query `onQueryStarted` wrapper that surfaces success/error toasts unless explicitly suppressed.
- **Inline edit isn't discoverable.** No pencil affordance, no tooltip — only `hover:bg-accent`. Add a faint pencil on hover to [InlineEditCell](../src/components/cues/InlineEditCell.tsx)/[InlineTextCell](../src/components/cues/InlineTextCell.tsx).

## 5. Information density, especially in the Cue editor

- **[CueEditor.tsx](../src/components/cues/editor/CueEditor.tsx) (473 lines of layout)** has no collapsible sections — Targets, Properties, Effects, Triggers all stacked. On narrow widths it's a scroll marathon. Add chevron-collapsible sections with collapse state in localStorage.
- **[FxLibrary.tsx:165](../src/routes/FxLibrary.tsx) categories don't persist** their expansion state and reset on each visit. Either remember the last view or auto-expand on search.
- **[CueDetailContent](../src/components/cues/CueDetailContent.tsx) and [CueDetailSheet](../src/components/cues/CueDetailSheet.tsx)** have overlapping read-only rendering logic; one should fully delegate to the other.

## 6. Empty / loading / error states are not standardised

[Fixtures.tsx:111](../src/routes/Fixtures.tsx) shows "No fixtures available," [Cues.tsx:160](../src/routes/Cues.tsx) renders nothing for empty, [Diagnostics](../src/routes/Diagnostics.tsx) and [Patches](../src/routes/Patches.tsx) each handle this differently. Loading is inconsistently a spinner, "Loading…" text, or nothing. Build one `<EmptyState>` and one `<LoadingState>` and use them everywhere — a 30-minute task with outsized payoff.

## 7. Confirmation density is uneven

- **Removing a fixture from a group** is silent ([src/components/patches/EditGroupSheet.tsx:150](../src/components/patches/EditGroupSheet.tsx)).
- **Stack deactivate / cue advance** are silent (see #1).
- **Meanwhile, copy-cue and copy-preset** use full Dialogs even though they're never destructive.

The rule should be: destructive ⇒ confirm or undo; non-destructive ⇒ just do it and toast. The current bias is reversed in several places.

## 8. Naming inconsistencies in user-facing copy

- "Park" (channels) vs "Lock" (presets/fixtures) for read-only/hold state — [Channels.tsx:328](../src/routes/Channels.tsx) vs FX/preset surfaces. Pick one verb per concept.
- "Snapshot from live" callback name in [CueEditor.tsx:51](../src/components/cues/editor/CueEditor.tsx) — needs a clearer button label like "Capture from stage" with a tooltip on what gets overwritten.
- Two `PresetPicker.tsx` files with different behaviour (see #3) — same name, divergent UX.

## 9. Keyboard / discoverability

- [CommandPalette.tsx:152](../src/components/CommandPalette.tsx) is navigation-only. Adding context-aware verbs (Duplicate Cue, New Preset, Activate Stack X) would unlock a power-user workflow at almost zero UI cost.
- Pickers and target lists don't support arrow-key + Enter — a glaring gap given how often these are used.

---

## Suggested order of attack

### Quick wins (≈1 week)

1. **`<EmptyState>` + `<LoadingState>` primitives** rolled out across routes — kills inconsistency #6 in an afternoon.
2. **Toast wrapper around RTK mutations** — kills #4 (save feedback) globally.
3. **DBO + blind-mode banners** — addresses #1 with zero new architecture.
4. **Rename `*Dialog` → `*Sheet`** where they're forms; swap the ad-hoc Cues delete overlay for `AlertDialog` — gets #2 to a clean, ripgrep-enforceable state.

### Bigger lifts (worth scheduling)

5. **Unified `<Picker>` primitive** (#3) — highest single consistency payoff.
6. **Collapsible sections in CueEditor** (#5) — biggest density win on the most-used screen.
7. **Live-show "undo" toast** for runner state changes (#1) — non-trivial because it needs server-side reversibility, but the right long-term fix.

---

## Files referenced

| File | Issues |
| --- | --- |
| [src/components/runner/ShowRunnerMobile.tsx](../src/components/runner/ShowRunnerMobile.tsx) | #1 DBO banner |
| [src/routes/RunPage.tsx](../src/routes/RunPage.tsx) | #1 stack/cue confirmations |
| [src/components/cues/editor/CueEditor.tsx](../src/components/cues/editor/CueEditor.tsx) | #1 blind banner, #4 selection, #5 density |
| [src/components/ChannelValueDialog.tsx](../src/components/ChannelValueDialog.tsx) | #2 misnamed dialog |
| [src/components/fixtures/GroupPropertiesDialog.tsx](../src/components/fixtures/GroupPropertiesDialog.tsx) | #2 misnamed dialog |
| [src/components/fixtures/GroupDetailModal.tsx](../src/components/fixtures/GroupDetailModal.tsx) | #2 misnamed modal |
| [src/components/groups/FixtureDetailModal.tsx](../src/components/groups/FixtureDetailModal.tsx) | #2 misnamed modal |
| [src/routes/Cues.tsx](../src/routes/Cues.tsx) | #2 ad-hoc overlay + duplicate-cue dialog, #4 silent debounce, #6 empty state |
| [src/components/patches/EditGroupSheet.tsx](../src/components/patches/EditGroupSheet.tsx) | #2 size=sm in footer, #7 silent removal |
| [src/components/cues/CueTriggerEditor.tsx](../src/components/cues/CueTriggerEditor.tsx) | #2 size=sm |
| [src/components/cues/editor/EffectFlow.tsx](../src/components/cues/editor/EffectFlow.tsx) | #2 size=sm |
| [src/components/fx/PresetPicker.tsx](../src/components/fx/PresetPicker.tsx) + [src/components/cues/editor/PresetPicker.tsx](../src/components/cues/editor/PresetPicker.tsx) | #3 duplicate name |
| [src/components/presets/FixtureTypePicker.tsx](../src/components/presets/FixtureTypePicker.tsx) | #3 no breadcrumb, no keyboard nav |
| [src/components/cues/InlineEditCell.tsx](../src/components/cues/InlineEditCell.tsx), [src/components/cues/InlineTextCell.tsx](../src/components/cues/InlineTextCell.tsx) | #4 missing affordance |
| [src/components/runner/EditorPanel.tsx](../src/components/runner/EditorPanel.tsx) | #4 silent debounce |
| [src/routes/FxLibrary.tsx](../src/routes/FxLibrary.tsx) | #5 category state |
| [src/components/cues/CueDetailContent.tsx](../src/components/cues/CueDetailContent.tsx) + [src/components/cues/CueDetailSheet.tsx](../src/components/cues/CueDetailSheet.tsx) | #5 duplication |
| [src/routes/Fixtures.tsx](../src/routes/Fixtures.tsx), [src/routes/Diagnostics.tsx](../src/routes/Diagnostics.tsx), [src/routes/Patches.tsx](../src/routes/Patches.tsx) | #6 inconsistent empty/loading |
| [src/routes/Channels.tsx](../src/routes/Channels.tsx) | #8 park vs lock |
| [src/components/CommandPalette.tsx](../src/components/CommandPalette.tsx) | #9 nav-only |
