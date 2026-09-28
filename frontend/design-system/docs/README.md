# Component doc stubs for design-sync

One file per component, named `<Name>.md`. The converter (`.ds-sync/`, see `.design-sync/`)
binds these by filename and reads the frontmatter `category` to group the component in the
Claude Design pane; the primitives otherwise all land in one "general" group because `ui/` is a
generic directory name. App components keep the group their source directory gives them
(`runner`, `looks`, `busking`, …) and need no stub.

A stub with **only frontmatter** keeps the converter's synthesized `.prompt.md` (props from the
`.d.ts`, examples from the authored preview, related components). A stub with a **body**
replaces that synthesized text with the body, so write one only when the hand-written guidance
is worth losing the auto-generated examples for.
