import { describe, expect, it } from 'vitest'

// The sources themselves, as text. `?raw` rather than `node:fs` because this file is compiled by
// the app's tsconfig, which has no node types — and because the paths then go through the same
// resolver the app uses, so a file that is moved fails the build rather than the assertion.
import programmerPageSrc from './routes/ProgrammerPage.tsx?raw'
import layoutSrc from './Layout.tsx?raw'
import programmerGridSrc from './components/programmer/ProgrammerGrid.tsx?raw'
import selectionBarSrc from './components/sheet/SelectionBar.tsx?raw'
import cellEditorSurfaceSrc from './components/editor/EditorSurface.tsx?raw'
import buskWindowSrc from './lib/buskWindow.ts?raw'
import buskingViewSrc from './components/busking/BuskingView.tsx?raw'

/**
 * The short-viewport fold is one decision written in three places, and this is what keeps them one
 * number.
 *
 * Space plan D8 gives a landscape phone its own arm below 500px of viewport **height**: the app
 * header stops being sticky, the programmer's rows A and B become one row, its 22px ownership
 * footer goes and the key moves onto a button. (`ShowHeader` was a fourth site — it tightened to
 * `py-2` there — until the chrome tidy-up made it `py-2` at every height, so it carries no
 * threshold at all now and is deliberately not listed.) Because height
 * is the one thing a container query cannot ask, each surface says so with a media query of its
 * own — two as Tailwind arbitrary variants, one as the string `useMediaQuery` is handed. That
 * is not a choice: Tailwind scans source *text* for class names, so a threshold assembled from a
 * shared constant would generate no CSS at all. The number cannot be shared; it can only be
 * pinned.
 *
 * The failure this guards is quiet. A rig check that decides 500 is wrong and moves the JS
 * constant leaves the header un-sticking and the rows folding at two
 * different heights — no error, no type failure, no failing unit test, visible only on a real
 * short screen. Same reasoning as `navMatch.test.ts`, which pins two route prefixes apart for a
 * collision that also could not fail loudly.
 */
const SITES: ReadonlyArray<readonly [name: string, source: string]> = [
  // The `SHORT_VIEWPORT` constant `ProgrammerBody` hands to `useMediaQuery` — the one arm that is
  // JavaScript, because it moves components rather than restyling them.
  ['ProgrammerPage.tsx', programmerPageSrc],
  // The app header stops being sticky.
  ['Layout.tsx', layoutSrc],
  // Row B's key button arrives and the ownership footer goes.
  ['ProgrammerGrid.tsx', programmerGridSrc],
]

/** Every `max-height: NNNpx` in a file, whether it is a media query, a class name or a comment. */
function thresholds(source: string): number[] {
  return [...source.matchAll(/max-height:\s*(\d+)px/g)].map((m) => Number(m[1]))
}

describe('the short-viewport fold', () => {
  it('is the same height at every site that folds', () => {
    for (const [name, source] of SITES) {
      const found = thresholds(source)
      // Not merely "500 is in there": *every* threshold in the file must be 500, so a second one
      // added at a nearby number is caught too.
      expect(found, `${name} declares no max-height threshold`).not.toHaveLength(0)
      expect(new Set(found), `${name} folds at a different height`).toEqual(new Set([500]))
    }
  })

  it('spells the JS constant the way `matchMedia` needs it', () => {
    // `useMediaQuery` — or, in the third case, a `matchMedia` store of its own — passes this
    // straight to `window.matchMedia`, which answers `false` for a string it cannot parse rather
    // than throwing, so a typo here is a fold that silently never happens. Three sites hand it a
    // string: `ProgrammerBody`, which moves rows A and B; `SelectionBar` (the sheet kit's since the
    // desk-findings' group B), which decides whether row C is permanently in the flow
    // (`selectionBandState`); and `EditorSurface`, which swaps a cell editor's bottom sheet
    // for a right-hand one, because a short viewport has no vertical room to give a bottom sheet.
    // The test above already holds `ProgrammerGrid`'s class-name arms to one *number*; this holds
    // the three strings to one *spelling*, which the number check cannot see.
    //
    // Like `SelectionBar`, the third is pinned by spelling and not by `SITES`: it carries no
    // Tailwind variant, and `SITES` is also what the class-name test below slices. The fourth is
    // `lib/buskWindow.ts`, whose defaults ladder (busk-further plan §3.4) folds the busk view's
    // focus and sheet on the same height — its own copy, by the convention this file enforces. The
    // fifth is `BuskingView.tsx`, which draws the short board (the merged strip, the overlay sheet)
    // on the same height: short beats narrow, and the board and the defaults must fold together.
    for (const src of [programmerPageSrc, selectionBarSrc, cellEditorSurfaceSrc, buskWindowSrc, buskingViewSrc]) {
      expect(src).toContain("const SHORT_VIEWPORT = '(max-height: 500px)'")
    }
  })

  it('spells the cramped fold the same way at both sites that ask it', () => {
    // 750 is the colour editor's compact fold (`EditorSurface`), and the busk view's
    // two-rows-not-three default (`buskWindow`). Neither file can sit in `SITES` — each carries a
    // second `max-height` — so the two copies are held to one spelling here instead.
    for (const src of [cellEditorSurfaceSrc, buskWindowSrc]) {
      expect(src).toContain("const CRAMPED_VIEWPORT = '(max-height: 750px)'")
    }
  })

  it('keeps the other two sites as literal Tailwind class names', () => {
    // If one is ever "tidied" into an interpolation it will generate no CSS and that surface
    // will simply stop folding — silently, and only on a short screen.
    for (const [name, source] of SITES.slice(1)) {
      expect(source, `${name} no longer carries the literal variant`).toContain(
        '[@media(max-height:500px)]:',
      )
    }
  })
})
