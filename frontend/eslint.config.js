import path from "node:path"
import { fileURLToPath } from "node:url"

import globals from "globals"
import pluginJs from "@eslint/js"
import tseslint from "typescript-eslint"
import pluginReact from "eslint-plugin-react"
import pluginReactHooks from "eslint-plugin-react-hooks"
import pluginImport from "eslint-plugin-import"
import configPrettier from "eslint-config-prettier"

// Absolute, and computed rather than written down: eslint-module-utils `require`s the resolver by
// name, and a repo-relative name is resolved against the *linted file's* base directory — so
// "./eslint-import-resolver-alias.cjs" loads for a file in `src/` and throws for one in
// `src/components/`. See that file for what it does and why it has to exist.
const ALIAS_RESOLVER = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "eslint-import-resolver-alias.cjs",
)

export default [
  {
    ignores: [
      "dist/**",
      // The design-sync library build (see .design-sync/); generated, like dist/.
      "design-system/dist/**",
      // The rest of the design-sync working set (see .design-sync/NOTES.md).
      // `ds-bundle/` is generated output and `.ds-sync/` is a vendored copy of the
      // converter, neither of which is this repo's code. `.design-sync/` holds the
      // sync inputs: its preview cards import the bare package name the converter
      // maps onto the shipped bundle ("lighting-desk-ui"), which nothing in
      // node_modules resolves, so the import rules can only report false errors
      // there. Those files are verified by actually rendering, which is stronger.
      "ds-bundle/**",
      ".ds-sync/**",
      ".design-sync/**",
      "node_modules/**",
      "coverage/**",
      // Agent worktrees are full second checkouts of this repo, build output and
      // all, so linting them reports every issue twice over.
      ".claude/**",
      // Design-session scratch, kept as reference implementations next to the
      // rest of the docs. Outside `src/`, so tsc never sees it; the `.jsx`
      // files are TypeScript inside and the parser can't read them either.
      "docs/prototypes/**",
    ],
  },
  { files: ["**/*.{js,mjs,cjs,ts,jsx,tsx}"] },
  { languageOptions: { globals: globals.browser } },
  pluginJs.configs.recommended,
  ...tseslint.configs.recommended,
  pluginReact.configs.flat.recommended,
  // React 19's automatic JSX runtime — no `import React` needed in scope.
  pluginReact.configs.flat["jsx-runtime"],
  {
    settings: {
      react: {
        version: "detect",
      },
    },
    plugins: { "react-hooks": pluginReactHooks },
    rules: {
      // The classic pair. v7's `recommended` preset also pulls in the React
      // Compiler rules, which this codebase hasn't been written against yet.
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "warn",
      // TypeScript checks prop shapes; propTypes would be a second, weaker copy.
      "react/prop-types": "off",
      // A leading underscore is the project's marker for a deliberately unused
      // binding (required positional args, destructure-to-omit, ignored catches).
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrorsIgnorePattern: "^_",
          destructuredArrayIgnorePattern: "^_",
        },
      ],
    },
  },
  {
    // Cycle detection only — not `plugin:import/recommended`, which brings a pile of rules this
    // tree has never been written against and which TypeScript already answers better.
    //
    // A value-import cycle here is not a style question: `startOAuthIdentityBridge` produced a
    // TDZ break that compiled green and showed up only as a broken app in the browser, and the
    // one this rule was added alongside was `CueSlotOverviewPanel` ↔ `CueSlotEditAssignPanel`.
    // `allowUnsafeDynamicCyclicDependency` stays off; `maxDepth` stays unbounded, because the
    // cycles that hurt are rarely the two-module ones.
    files: ["src/**/*.{ts,tsx}"],
    plugins: { import: pluginImport },
    settings: {
      // Both entries are load-bearing, and both fail *silently* when wrong — the rule skips any
      // import it cannot parse or resolve, so a misconfiguration reads as "no cycles". Without
      // `import/parsers` it cannot read a `.ts` dependency at all and reports nothing whatsoever;
      // without the alias resolver it misses the ~70% of imports written `@/…`. Verified by
      // reintroducing a cycle in each import style and watching both be reported.
      "import/parsers": { "@typescript-eslint/parser": [".ts", ".tsx"] },
      "import/resolver": { [ALIAS_RESOLVER]: {} },
    },
    rules: { "import/no-cycle": ["error", { ignoreExternal: true }] },
  },
  {
    // Infrastructure fixtures (CLAUDE.md §Fixtures): the raw fixture and patch lists carry them, so a
    // view that reaches for a raw hook to *list or offer* fixtures offers the hazer's power dimmer —
    // and fails open, silently. The raw hooks are therefore allowed only in the files below, each of
    // which either resolves keys it already holds or is the Patches view. Anything else reads
    // `useVisibleFixtureListQuery` / `useVisiblePatchListQuery`. Adding a file here is a claim that it
    // never enumerates the rig for an operator — make it in the review, not in passing. (Not
    // covered: enumerating `useFixtureLookup().fixtures`, which is raw for the lookups it feeds.)
    files: ["src/**/*.{ts,tsx}"],
    ignores: [
      "src/**/*.test.{ts,tsx}",
      "src/store/fixtures.ts",
      "src/store/patches.ts",
      "src/hooks/useFixtureLookup.ts",
      "src/hooks/useTargetProperties.ts",
      "src/components/busking/RigBand.tsx",
      "src/components/busking/RigPalette.tsx",
      "src/components/busking/SideSheetFold.tsx",
      "src/components/busking/useBuskingSelection.ts",
      "src/components/cues/CueValueGrid.tsx",
      "src/components/editor/ColourEditor.tsx",
      "src/components/fixtures-list/useClearCellEffects.ts",
      "src/components/groups/CompactFixtureCard.tsx",
      "src/components/groups/FixtureDetailModal.tsx",
      "src/components/groups/GroupMembersSection.tsx",
      "src/components/groups/useVisibleGroupMembers.ts",
      "src/components/patches/PatchSheet.tsx",
      "src/components/programmer/FocusedTemplateLayer.tsx",
      "src/components/programmer/LayerPicker.tsx",
      "src/components/programmer/LookRowStore.tsx",
      "src/components/programmer/ProgrammerAddEffect.tsx",
      "src/components/programmer/useLocalFamilyCounts.ts",
      "src/components/surfaces/BindingTargetPicker.tsx",
      "src/components/surfaces/SurfaceInspector.tsx",
      "src/routes/Patches.tsx",
      "src/routes/ProjectOverview.tsx",
      "src/routes/Stage.tsx",
    ],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              regex: "(^|/)store/fixtures$",
              importNames: ["useFixtureListQuery"],
              message:
                "Lists infrastructure fixtures. A view that lists or offers fixtures reads useVisibleFixtureListQuery (CLAUDE.md §Fixtures); a key-resolving file joins the allowlist in eslint.config.js.",
            },
            {
              regex: "(^|/)store/patches$",
              importNames: ["usePatchListQuery"],
              message:
                "Lists infrastructure patches. A view that lists or offers patches reads useVisiblePatchListQuery (CLAUDE.md §Fixtures); a key-resolving file joins the allowlist in eslint.config.js.",
            },
          ],
        },
      ],
    },
  },
  {
    // react-three-fiber props (position, intensity, args, …) are not DOM
    // attributes; @react-three/fiber's own JSX types are what validate them.
    files: ["src/components/stage3d/**"],
    rules: { "react/no-unknown-property": "off" },
  },
  {
    files: ["*.config.{js,ts}", "*.config.*.{js,ts}"],
    languageOptions: { globals: globals.node },
  },
  {
    // The import resolver runs inside ESLint, in Node, and has to be CommonJS: eslint-module-utils
    // loads it with `require`.
    files: ["*.cjs"],
    languageOptions: { globals: globals.node, sourceType: "commonjs" },
    rules: { "@typescript-eslint/no-require-imports": "off" },
  },
  // Last, so it wins: turns off every core/plugin rule that would fight
  // `npm run format`. Prettier itself is not run from the lint gate — this only
  // stops ESLint from having an opinion about formatting.
  configPrettier,
]
