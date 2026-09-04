# Busk layout — design exploration

Source: a Claude Design canvas authored 2026-09-04 against `lighting-react`'s shipped busk and
library components (the dark oklch tokens, the 56px `LookPadButton` presence ladder, the busk
group cluster, the 44px list rows and 40px `TemplateGroupRow`). **Read them as the intended
visual output, not as structure to copy**: there is no React here.

This is a proposal, not a plan: nothing here has landed. The brief began as "template groups and
ordering, but for Looks", and asked whether grouping is really a busk-layout thing with Looks and
templates side by side. Three directions were drawn; **Alternative C — a busk layout of its own —
was chosen** on 2026-09-04, and the canvas's first page is that direction built out: the busk
view in play and edit mode, the operator's build flow, and the model. It drops template groups
and template/Look ordering, replaces pinned cues and the cue-stack column on the busk view, and
keeps the FX cue slots overlay as it is coded except that its members — cues and fixture-bound
Looks — are dragged in from the busk view's edit-mode library. None of
those features has left the dev desk, so there is no migration.

The implementation plan lives at [`../busk-layout-plan.md`](../busk-layout-plan.md). Its §4 is a
grep-able summary of what these files draw; where wording disagrees, the plan wins on behaviour
and these files win on layout and copy.

The live, pannable version is at
<https://claude.ai/code/artifact/4eb72004-4860-4297-b6e3-27b3f1f39dd9> — private to Chris, so
**treat these files as the authority** and the URL as a convenience if you happen to have access.

## Format

Each `*.dc.html` is one artboard. All eleven are **static mockups**: no `{{ hole }}` bindings and no
logic script, so each renders standalone in a browser. `canvas.json` is the layout manifest, two
pages, plus sticky notes carrying the open questions (how much placement; the library pages afterwards;
save per gesture vs on Done; which Looks a slot may hold), and one recording how taking the
cue slot's press settled cues in a solo bank.

Every artboard is **dark-only on purpose**.

## Page 1 · Busk layout (chosen)

| File | Draws |
| --- | --- |
| `Main.dc.html` | The busk view in play mode: a page strip, two rows of columns of banks holding mixed pads (templates, Looks, cues), two solo banks, one bank stacked under another, one bank flowing as a column. No family columns, no Looks pool, no cue-stack cards. Ballyhoo just pressed. |
| `Edit.dc.html` | The same page in edit mode: bank name fields, Solo switches, pad crosses, drop slots, *+ Bank*, *+ Page*, and the draggable library palette in the speed rail's place. |
| `Flows.dc.html` | Six moments of building a page: first open, edit layout, drag from the library, arrange, solo, and adding from the Show view / editors / *Save as template*. |
| `Slots.dc.html` | The FX cue slots overlay as coded, above the busk view in edit mode: slots as drop targets for cues and bound Looks dragged from the library palette; no banks, no solo, no selection. |
| `Layout.dc.html` | Rows → columns → banks: the structure, the three drop zones a lifted bank sees, column width shares and a bank's wrap-or-column flow. Solo never decides shape. |
| `Model.dc.html` | Page → bank → pad; the press route by kind and the solo rules; what goes, what comes, sync, the one-shot migration; the busking-view decision restated. |
| `Survey.dc.html` | QLC+, MagicQ, Daslight, Lightkey, grandMA3, Hog 4, Eos and clip-launchers: where exclusivity lives on each; the two camps. |

## Page 2 · Earlier directions (for the record)

| File | Draws |
| --- | --- |
| `DirectionA.dc.html` | Alternative A — a separate `look_groups` mirroring template groups. Why it cannot express the brief's example. |
| `DirectionB.dc.html`, `Library.dc.html` | Alternative B — one library group holding both kinds, with a derived home column; the busk view and the two library pages. The original recommendation, superseded by C. |
| `DirectionC.dc.html` | Alternative C as first sketched, with its for/against notes. |
