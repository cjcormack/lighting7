# Two screens and an iPad — design exploration

Source: a Claude Design canvas authored 2026-09-15, generated from `lighting-react`'s real values —
the oklch tokens from `index.css`, the `button.tsx` sizes, the row system from
`programmer-chrome-design/`, the sheet anatomy from `sheet-views-design/` and `list-shell-design/`,
and the busk view from `BuskPad.tsx` / `padFace.ts` / `TargetBand.tsx`. **Read them as the intended
visual output, not as structure to copy**: static HTML mockups with no React and no state.

The brief: the theatre desk runs two touch screens, and with MagicQ both are the app full screen.
The equivalent here is a full-screen browser window per screen, plus an iPad. Three asks — controls
to go full screen; select on one window and act on another; drag and drop between windows — plus
what the consoles do and the architecture (selection server-side, optional within a session). This
is a **proposal**, not a plan; nothing here has landed.

What the record found: the architecture is mostly there. The fixture selection is already a desk
fact (`selection.state`, `store/selection.ts`, `useDeskSelectionBridge`), and so are the busk page,
blind and the template recents. The work is three more facts of the same shape — the attribute mask
on the selection, a registry of windows, and *the hand* (a desk-owned held item) — one window fact
(follow / local), and one nameable gesture per ask.

The live, pannable version is at <https://claude.ai/artifact/CrvaTqN1Jun3HaPpMAy9ow> — private to Chris,
so **treat these files as the authority** and the URL as a convenience.

## Format

Each `*.dc.html` is one artboard. Ignore the `<script src="./support.js">` line and the `<x-dc>` /
`<helmet>` wrappers — canvas scaffolding. Open a file in a browser and it renders standalone.

**The artboards are generated.** [`gen.mjs`](gen.mjs) is the source: `node gen.mjs` in this
directory rewrites every `.dc.html` and `canvas.json`. Change a value there rather than in an
artboard, or they drift. The seeded canvas (`two-screens-and-an-ipad.html`, the editor plus the
artboards, ~2.5 MB) is **not committed** — regenerate it with the design skill's `seed-canvas.mjs`
from these files, never by hand.

Every artboard is **dark-only on purpose**.

## Artboards

| File | Draws |
| --- | --- |
| `Survey.dc.html` | Five consoles (grandMA3, MagicQ, Eos, Hog 4, Titan) read for three things: how a second screen is treated, what a remote is, whether the programmer is shared. Three takeaways. |
| `Model.dc.html` | Desk facts vs window facts, what is new on the wire, the two states of the desk chip, the four-step sequence of a press on Screen 2 landing on Screen 1's cells, why the iPad is not a special case, and the "optional within a session" answer. |
| `Main.dc.html` | Screen 1 — the programmer full screen, a Colour marquee over three heads, the **desk chip** on the selection bar. |
| `Screen2.dc.html` | Screen 2 — Busk at the same moment: the band lights the three heads, carries the Colour pill, says *from Screen 1*; Lavender just pressed. |
| `Tablet.dc.html` | The iPad — Warm Wash in the hand, picked up on Screen 2; the Looks bank shows the drop slot. |
| `Screens.dc.html` | Ask 1 — Full screen and Screens… in the user menu and ⌘K; the Screens sheet (windows registry, view picker per window, open-on-display, saved layouts); Esc; the four ways out of the browser chrome. |
| `Hand.dc.html` | Ask 3 — the hand in three frames (pick up · in hand · place), then the same-machine edge drag. |
| `Options.dc.html` | Three ways to move a thing across windows (the hand · native same-browser DnD · Send to…), A recommended. |

## The calls this record makes, for Chris to confirm or overturn

1. **Follow the desk by default, unlink per window.** Every console makes a second screen a view
   on one programmer; a remote with its own programmer (MA, Titan) is a second-*person* feature.
2. **The attribute mask joins `selection.state`.** A marquee publishes its columns; a pad press
   elsewhere lands on those cells.
3. **The hand, not a pointer drag, is the cross-window move.** The edge drag between the two desk
   screens is a shortcut over it (Window Management API), never the only route.
4. **Installed (PWA, `display: fullscreen`) for the desk screens**, because a plain tab gives Esc
   to the browser. The in-app Full screen item is for ad-hoc use and the iPad.
5. **Nothing new in the app header row.** Full screen and Screens… live in the user menu and ⌘K.
