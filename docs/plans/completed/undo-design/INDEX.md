# Undo and history — design record

Source: a design session on 2026-10-20, after asking what MagicQ, grandMA3, Eos and Hog 4 undo and
how deep. lighting7 has about ten one-step Undos, each with its own inverse (the hand, the cue
editor, prompt-book anchors, Make rig safe), and nothing that reaches a delete, a Record, a Clear or
an edit Claude made. This design proposes two histories behind one key: programmer snapshots, and a
journal of every stored record that changed, which can be put back in place, ids kept, with no
reload.

**Status: agreed 2026-10-20.** The implementation plan is [`../undo-plan.md`](../undo-plan.md).
The one-page copy is [`undo.html`](undo.html), hand-written, theme-aware, with its mock-ups drawn dark
in both themes as the desk is. There are no `*.dc.html` boards yet; they can follow if the design
moves to a canvas.

## Asked and answered

Chris answered these on 2026-10-20, in two rounds, taking the recommended answer each time. They are
`undo.html` §4's P1–P7.

| Question | Answer |
| --- | --- |
| Whose changes does ⌘Z take back? | **Your account's show changes, plus programmer steps from anyone** (there is one programmer). One desk-wide stack and one stack per window were declined. |
| Programmer and show: one key or two? | **One key, newest first across both**; the programmer bar keeps a programmer-only Undo. MagicQ's programmer-first order and two keys were declined. |
| An older entry something changed since? | ***Undo back to here*, listing what it collides with, or Cancel.** Refusing and overwriting were declined. |
| Parks, tempo, selection, machine-local? | **Parks only.** Tempo, the selection and machine-local settings stay out. |
| Depth and lifetime? | **50 programmer steps; 500 show entries per project, kept while the desk runs**, across a switch away and back, cleared by a pull or import, a venue Update, Attach, Detach, Extract or Discard, or a restart. Clearing on a switch and persisting across a restart were declined. |
| Undo while running or locked? | **Confirm when output would move; locked asks to unlock.** No confirms, and show undo off while a stack runs, were declined. |
| Claude? | **Each MCP grant is its own source, with `list_history` and `undo_changes` for its own entries.** No tool, and counting as you, were declined. |

## What was found in the code

Read from `main` at `59ebdb7`, 2026-10-20, by three surveys (existing undos and write paths; the
programmer's state, records and importer; the frontend's chrome, toasts and keys). `undo.html` §2
holds the findings with file:line; in short:

- **Writes have no single path.** About 206 mutating routes each open their own transaction, MCP
  tools skip the HTTP write guard, and parks, tempo and surface learn are WS frames.
- **A whole-project restore re-mints every integer id** (`ProjectImporter.replaceFromWorkingTree`),
  so snapshot-and-reload cannot be the undo mechanism: URLs, editors, standby and holds name ids.
- **Every portable record has a canonical JSON form and a hash** (the sync exporter,
  `RecordHasher`), which is the before and after image. The exporter only writes files today.
- **The importer is per kind but over a folder**, threading uuid→DAO maps in foreign-key order;
  restoring a few records needs it to read records and update rows in place.
- **The programmer is several holders** (`ProgrammerStore`, the engine's Local effects,
  `LocateManager`, `ProgrammerScenery`), with no snapshot API; ShowCarry hands them over by reference.
- **Each record kind already has one list-changed frame and one live hook** (republish, `markEdited`,
  `loadFixtures`, `reloadSpeedMasters`), so an undo can make the same calls.

## Format

`undo.html` is the record. Names in its mock-ups (Sleeping Beauty, Act 1, Q11–Q13, "Warm wash",
Hex 1–6, Spot 1–4, the X-Touch) are illustrative.
