# The busk view's chrome — design record

Source: four boards drawn 2026-09-21 against `lighting-react`'s shipped busk view (`99e96914`) and
the busk-further record beside it (`completed/busk-further-design/`), in that record's own dark
vocabulary. **Read them as the intended visual output, not as structure to copy**: there is no
React here.

The brief, from Chris on 2026-09-21: the busk view is too inefficient vertically. Two things were
asked for — take the ShowBar off the view and put a **Show tab** on the side sheet, based on the
phone version of the Show view; and let the view **expand over the sidebar and the app header** —
and a third: other suggestions. Mid-review Chris added that the expand toggle should be built
for all four live views, not only Busk; the boards say so. The screenshot that prompted it was a 1122×768 window with 152px of
chrome above the rig band (app header 48, ShowHeader 48, ShowBar 56) and the first pad at y≈450.

**Status: approved 2026-09-21; all three sessions built the same day (lighting-react `aa4e4c54`, `37ba7197`, `aa4f2c24`).** The implementation plan is
[`../busk-chrome-plan.md`](../busk-chrome-plan.md), and the live copy of these boards is at
<https://claude.ai/artifact/DCgs8coqVqkFoSTXRF6AMg> — private to Chris, a convenience; the files
here are the authority. Where a board and the plan will disagree, the plan wins on behaviour and these files on
layout and copy, as with every record in this directory.

## What the boards propose, in one paragraph each

**The ShowBar leaves the busk view on every board, and the phone runner becomes the sheet's fourth
tab.** `RunMobile` — the always-locked layout Show swaps to below 600px — is mounted in the docked
sheet at its 320px floor: its strip (stack picker, cue list, the programmer chip, the tempo chip,
DBO), the Current and Next cards collapsed by default (the rig band is the stage here), and
BACK · GO as the tab's static footer, the way Colour and Spread keep their verbs. One component, not
a copy, fed by the `useShowTransport` the route already holds. The tab strip folds its words to
glyphs below 400px of sheet, so the floor stays 320. The fold strip gains the live cue number under
the Show glyph. The overlay sheet off the desk board carries Colour · Spread · Show. No transport
keys are bound.

**Immersive is a per-window fact, on all four live views.** `desk.immersive` (`off` | `on`) in
`sessionStorage` beside the follow flag and full screen, announced under `viewOptions` of whichever
live view the window is on, consumed at boot from `?immersive=`, set from an expand glyph on the
shared ShowHeader (so the Programmer, Show and the Prompt Book carry it too — Chris's call on
2026-09-21, after a busk-only first draft), ⌘K, or the Screens sheet's row, and carried by
*Copy link*. `Layout` hides the sidebar, the app header and the four overview panels while the
window is on a live view with it on. The ShowHeader stays, at **40px** (Chris, 2026-09-21: the shell's chrome-row height, on all four
live views): it is the row that carries the switcher, Stop, the dot and the way back. It is not
full screen and the two compose. Chrome above the busk band goes 152 → 88 → 40.

**The rig band's label row and controls row become one row** (Chris, 2026-09-21, after seeing the
first draft): `RIG`, the Cells menu and steps, the verbs, the family pill and the desk chip, the
Focus control and *Edit layout*, in that order in every shape. The selection summary is drawn only
in Pads, in the gap before the Focus control, since in Split and Rig the lit tiles say it. The band
is 32px shorter in all three shapes; the fold thresholds move up by the width the pill and chip add.
The family pill and the desk chip are left-anchored after the verbs so the chip never moves between
shapes; the Cells control folds to its mode word, never a bare glyph; and below ~600px of band the
row becomes two rows by design.

**Three more things proposed, three decided by Chris, three declined, one open** — on `Model`.

## Superseded

- `Band.dc.html`'s **Pads rows** — the one row drawn in Pads with the Cells menu, the steps, Clear
  and the desk chip — and the ladder's Pads rungs are superseded by the plan's **D17–D20**
  (session A.5, from Chris's review of session A on the desk): in Pads the rig row is not drawn,
  the pad row (`PADS`, the tabs at the rig controls' size, Spread · Locate · Highlight, the
  summary, the pill, the Focus control) is the top row, and a chip is drawn only while unlinked.
  The Split and Rig rows stand. Not redrawn here; the plan is the record.
- `Band.dc.html`'s fold numbers (1020 · 1180 · 820 · 600) were the board's; the app's are
  1100 · 1260 · 820 · 700 on the band's content box (session A's `RigBand.tsx` docblock).
- `ShowTab.dc.html`'s phone overlay at 288: session A widened the right-hand form to the sheet's
  320 floor so the runner's strip fits.
- **Session B shipped four things the boards do not draw** (lighting-react `aa4f2c24`). The
  `ShowHeader` takes the shell's `CHROME_ROW_CLASS` (`h-10 px-3`, 40 on the box with its border,
  as `Immersive.dc.html` draws it) rather than the plan's literal `px-3 py-1`, which measured 41
  in the browser. The four live routes' loading and not-found arms draw `ImmersiveEscape` — the
  header's glyph on a chrome row — because they render no header and an immersive touch-only
  window had no way back. `Model.dc.html`'s "theme, full screen and Screens… are ⌘K's" was not
  true of theme: no command existed, so `lib/theme.ts` became a store and ⌘K gained *Switch to
  dark/light mode*. And the palette withholds the four overview-panel toggles while the panels
  are unmounted. The breadcrumb trail is single-line with a truncating project name.

## Format

Each `*.dc.html` is one artboard; all four are static mockups with the busk-further `Main` board's
stylesheet inlined plus what these boards add, so each renders standalone. `canvas.json` is the layout manifest. Every
board is dark-only on purpose.

| File | Draws |
| --- | --- |
| `Main.dc.html` | The whole window at 1440×1000, immersive **off**: the 64px rail, the app header, the ShowHeader with the expand glyph, no ShowBar, the band and page as shipped, and the side sheet with four glyph tabs and the Show tab open. Three callouts and the budget line. |
| `Immersive.dc.html` | The same window with immersive **on**: the ShowHeader as the top row, the band at three lines, the sheet folded with the live cue on the fold; the same glyph on the other three views' headers; and the vertical budget — one 1122×768 window in three states, to scale. |
| `ShowTab.dc.html` | The Show tab at 320 (glyph tabs, cards collapsed) and at 400 (worded tabs, Current expanded to Stage); the fold at 40px; the phone's overlay sheet with its third tab; the six rules. |
| `Band.dc.html` | The rig band's one row in Split, Rig, Pads and edit mode at 1440, and the fold ladder at 1040 · 1000 · 800 · 640 · 520 with Pads at each (the one shape with the summary). Four rules. |
| `Model.dc.html` | What is stored in the window, what rides the wire (nothing new), where the code goes, what leaves and what stays, the two sessions, and the other suggestions with their verdicts. |
