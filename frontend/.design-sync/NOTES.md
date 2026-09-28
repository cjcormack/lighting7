# design-sync notes for lighting-react

Repo-specific gotchas for syncing this design system to claude.ai/design. One bullet per
gotcha; append whenever something new is learned.

## Shape and build

- This is an **application** repo, not a component library. The design system is the
  shadcn-style primitives in `src/components/ui/` plus the app components that render without
  the Redux store or the router. `design-system/` is a small library package added for the sync:
  `index.ts` is the barrel, `vite.config.ts` the lib build (everything in `node_modules` stays
  external), `tsconfig.json` emits declarations into `dist/types/`. Build with `npm run build:ds`
  from the repo root. `design-system/dist/` is gitignored and ignored by ESLint.
- Store-bound components are deliberately **not** in the barrel (ShowHeader, ViewSwitcher,
  SpeedMasters, BeatIndicator, OwnershipLegend, EffectSummary, the fixtures-list cells,
  SaveStatusIndicator, Breadcrumbs). They read `react-redux` / `react-router` / `@/store` /
  `@/api` transitively and cannot render standalone. Re-check with a transitive import scan
  before adding one; a component that is "clean" at the top level can be dirty two hops down
  (`OwnershipLegend` → `ownership.ts` → `usePropertyValues`).
- Tailwind v4: the compiled stylesheet is `design-system/dist/index.css`, produced by
  `@tailwindcss/vite` from `src/index.css`. Tailwind scans the whole repo for class names, so the
  emitted CSS carries every utility the app uses (a superset of what the components need), plus
  the oklch token set and the `.dark` variant. There is no `@font-face`: the app uses Tailwind's
  default system-font stacks.

## Environment

- `npm i` under `.ds-sync/` fails with `EPERM` on `~/.npm/_cacache` (root-owned files from an
  old npm). Use a private cache: `npm i ... --cache "$TMPDIR/npm-cache"`.
- On macOS the Playwright browser cache is `~/Library/Caches/ms-playwright/`, not
  `~/.cache/ms-playwright/`. A `chromium-1234` build was already present before this sync.

## Rendering inside Claude Code's sandbox

- Playwright's Chromium cannot launch under the macOS sandbox (Mach bootstrap check-in refused;
  full Chrome additionally fails on its profile-lock socket). `.design-sync/chromium-sandboxed.sh`
  wraps the **headless shell** with `--single-process --no-zygote`; every validate/capture run
  needs `DS_CHROMIUM_PATH="$PWD/.design-sync/chromium-sandboxed.sh"`. Outside the sandbox the
  wrapper is unnecessary.

## Component list and grouping

- 145 components: 22 primitive modules expand to ~118 flat exports (shadcn style — `CardHeader`,
  `DialogTitle`, … are separate exports, not `Card.Header`). The converter's compound grouping
  keys off namespace/static members, which these exports never have, and forcing it would emit
  dotted `Card.Header` APIs that do not exist at runtime. So the list stays flat: **root
  components get authored previews; sub-part exports ship the floor card** and appear inside their
  parent's cells. Don't fork `dts.mjs` to "fix" this.
- The primitives all sit in the generic `ui/` directory, so the converter groups them as
  `general`. `design-system/docs/<Name>.md` frontmatter-only stubs assign categories (Actions /
  Forms / Overlays / Data display / Navigation / Utilities) without replacing the synthesized
  `.prompt.md`; `Sheet.md` is the one stub with a real body (the sheet-structure rules from
  CLAUDE.md). App components keep their source-directory group. `guidelinesGlob: []` in the
  config stops the default `docs/*.md` glob from shipping the stubs as guidelines.
- `cfg.provider` is `TooltipProvider`: Radix tooltips throw without it, and the app wraps the
  whole tree in one (`Layout.tsx`), so previews and the design agent's README both get it.
- Dropped from the barrel on purpose: `ScriptUploadCard` (imports `pdfjs` from `react-pdf`, which
  would pull the PDF engine into every design's runtime for one upload card).

## Conventions header

- `.design-sync/conventions.md` names utility classes; Tailwind v4 emits **only the classes the app
  uses**, so every class named there must be re-verified against `ds-bundle/_ds_bundle.css` after
  a rebuild: `node .design-sync/check-classes.mjs ds-bundle/_ds_bundle.css <class...>` prints
  ok/NO per class (it escapes `:` and `/` the way the compiled CSS does). Responsive variants are
  sparse — `md:grid-cols-2` exists, `sm:grid-cols-2` does not.

## Authoring previews here

- **Composing an open overlay.** Radix roots take `defaultOpen`, so `Dialog`, `AlertDialog`,
  `Popover`, `DropdownMenu`, `Select` and `Sheet` all render open in a card. `Sheet` needs
  `modal={false}` (plus `onInteractOutside` prevented); `Dialog`/`AlertDialog` do not — their
  `fixed inset-0` overlay is contained by the single-card transform and the dimmed backdrop
  reads correctly. `ContextMenu` has no `defaultOpen`: dispatch a `contextmenu` `MouseEvent`
  on the trigger from a `useEffect` about 50 ms after mount (a synchronous dispatch is too
  early for React's root listener) and aim the point at the trigger's lower-right corner so the
  menu drops clear of its own label.
- **App components that own their popover state** (`ColourPickerPopover`, `GelPickerField`,
  `StageShortcutsPopover`) expose no open prop. A mount effect that `.click()`s the trigger
  opens them before the screenshot, because the capture waits for `networkidle`.
- Per-story captures are viewport-sized and one story per page load, so `position: fixed`
  toolbars and portalled content are captured cleanly and never collide with other cells.
  Anchor them by measuring a real element with `getBoundingClientRect` in a `useLayoutEffect`
  rather than hard-coding coordinates.
- **`bg-muted` is near-white in the light theme.** A component whose only surface is muted
  (`Avatar`'s fallback) reads as floating text on a white card. Put those compositions on a
  `rounded-lg border p-3` surface, which is also where they sit in the app.
- The emitted `.d.ts` drops `| null` from several props (`Stage2DHud.cursor`, `snapStepM`,
  `PromptBookToolbar.scriptFileName`, `BeamAngleField.value`, `GelPickerField.value`). The
  bundle is esbuild-compiled with no type check and the source does accept null, so passing it
  is correct; cast (`null as unknown as …`) rather than inventing a non-null value.

## Known render warns

These are triaged and expected. A warn **not** in this list is new — look at it before shipping.

- `[GRID_OVERFLOW]` was resolved for all five components it named by the overrides in
  `cfg.overrides`: `AuthScreenLayout` and `FloatingSelectionToolbar` are `cardMode: single`
  (their content is fixed/portalled and escapes any grid cell); `ToggleGroup`, `BeamAngleField`
  and `Stage2DHud` are `cardMode: column` (wider than a grid cell).
- `PromptBookToolbar`'s live-cue chip is a dark green pill (`emerald-950/60` on `emerald-800`),
  tuned for the dark theme. On the light review sheet that is the component's real behaviour,
  not a render fault.
- 90 of the 145 components show the typographic floor card. They are the shadcn sub-part
  exports (`CardHeader`, `DialogTitle`, `TableRow`, …), which only render inside their parent
  and are shown there. This is the deliberate baseline, not a failure.

## Re-sync risks

- **The Tailwind safelist is hand-maintained.** `design-system/design-system.css` lists the
  utility vocabulary the design agent is promised in `.design-sync/conventions.md`. Nothing
  checks the two against each other automatically — after any edit to either, re-run
  `node .design-sync/check-classes.mjs ds-bundle/_ds_bundle.css <class...>` over the header's
  table. A class that stops resolving silently produces unstyled designs.
- **Previews are pinned to the app's real components.** They import from `lighting-desk-ui`, so
  a prop rename or a component deleted from `design-system/index.ts` breaks that preview's
  compile (`! preview build failed: <Name>` in the build log) and drops it to the floor card.
  The bundle is unaffected, so this is easy to miss — grep the build log.
- **`design-system/index.ts` is a hand-maintained barrel.** New primitives in
  `src/components/ui/` do not appear until they are exported there. Before adding an app
  component, re-check transitively that it pulls in no `react-redux`, `react-router`, `@/store`,
  `@/api` or `@/hooks` — the check that matters is the whole import closure, not the top file.
- Verified against Node 24.12, npm, Playwright 1.62.1 with the cached `chromium-1234` build.
  Nothing is fetched from the network at build time.
