import { describe, expect, it } from 'vitest'

// The source as text, the way `shortViewport.test.ts` reads its three sites. Everything this file
// pins is a Tailwind class, so there is nothing a rendered test could assert: jsdom performs no
// layout, so it cannot tell a row that wraps from one whose controls are drawn on top of the
// controls beside them.
import programmerGridSrc from './ProgrammerGrid.tsx?raw'
import sheetPageSrc from '../sheet/SheetPage.tsx?raw'
import programmerActionBarSrc from './ProgrammerActionBar.tsx?raw'
import { CONTROL_LABEL_CLASS } from '@/lib/utils'

/**
 * The folded row's layout contract — **the three classes that stop row A's half painting over row
 * B's tools**, pinned because each of them fails silently.
 *
 * Under `@media (max-height: 500px)` `ProgrammerBody` hands row A's two halves to this row as
 * `leading` (space plan D8), so one line carries the source box, five verbs and six tools. The
 * block that holds the first two declares an `@container`, which brings size containment — so its
 * min-content size is **zero** whatever it holds, while the action bar inside it is `shrink-0` and
 * 285px wide once iconic. On a 612×457 window the flex squeezed that block to 283px, the bar kept
 * its 285, and its Record button was drawn across the scope pills and the search icon to its
 * right. Nothing errored, no type failed, and no unit test could see it.
 *
 * The three together are one mechanism and are worth nothing apart: the floor is what makes the
 * block push back, the wrap is what turns that push into a second line rather than an overflow,
 * and the tool group is what makes the whole of row B's right half the thing that moves.
 */
describe("the folded row's layout contract", () => {
  it('gives the leading block an explicit floor rather than `min-w-0`', () => {
    // `min-w-0` was the spelling that let it be squeezed under its own contents. The number is
    // derived beside the class; what must not come back is a *zero* floor, which containment
    // then makes unrecoverable.
    expect(programmerGridSrc).toContain(
      '@container flex min-w-[min(410px,100%)] flex-initial items-center gap-2 has-[[data-fills]]:flex-1',
    )
  })

  it('lets the folded row wrap, and only the folded row', () => {
    // `h-10` in the unfolded arm, `min-h-10 flex-wrap` in the folded one. A fixed height on a
    // wrapping row would clip the second line instead of showing it, which is how this "fix"
    // would look fixed and not be. 40 is the chrome system's row height, the same as rows A and C.
    // Row B is the shell's `SheetPage.Row` since the list shell, so the contract is in two halves:
    // the grid asks for the minimum only when folded, and the shell's `minHeight` is what swaps
    // the fixed height for it.
    expect(programmerGridSrc).toContain(
      "<SheetPage.Row minHeight={!!leading} className={cn(leading && 'flex-wrap gap-y-1.5')}>",
    )
    expect(sheetPageSrc).toContain("minHeight && 'h-auto min-h-10'")
  })

  it("keeps row B's tools together, and out of the way when the row is not folded", () => {
    // `contents` is the half that is easy to lose: unfolded, the wrapper must generate no box at
    // all, or the `flex-1` spacer inside it stops being a sibling of `Groups` in the row itself
    // and the right-hand controls stop being right-aligned.
    expect(programmerGridSrc).toContain(
      "'flex shrink-0 flex-wrap items-center gap-2 @[560px]:@max-[739px]:[&_.control-label]:inline @[840px]:min-w-[384px] @[840px]:flex-1'",
    )
    expect(programmerGridSrc).toContain(": 'contents',")
  })

  it('keeps the label hook spelled the same at both ends', () => {
    // The reveal selector has to name the class as literal source text — Tailwind scans text, so
    // an arbitrary variant built from the constant would generate no CSS at all. That leaves the
    // producer (`labelUnlessCompact`, via the constant) and the consumer (the literal below) free
    // to drift: rename the constant and Tailwind simply stops emitting the override, the words
    // quietly stop coming back, and nothing else fails. This is the only thing that would.
    expect(CONTROL_LABEL_CLASS).toBe('control-label')
    expect(programmerGridSrc).toContain(`[&_.${CONTROL_LABEL_CLASS}]:inline`)
  })

  it('keeps the floor tied to the width it was derived from', () => {
    // The one number in this mechanism that lives in another file. The floor is the pair at its
    // legible minimum — the iconic action bar, the divider and two gaps, and the source box's own
    // 102px — so it is only right for as long as that bar is 285px wide, and the bar is measured
    // in `ProgrammerActionBar`'s doc comment rather than shared as a constant (it cannot be
    // shared: Tailwind scans source text, so a floor composed at runtime would generate no CSS).
    //
    // That coupling has already gone stale once in the other direction: the bar grew from ~230px
    // to 285px when Blind came back into it (`PD-BLIND-ON-PROGRAMMER`), and nothing pointed at the
    // arithmetic downstream of it. So pin the two together — re-measure the bar and this fails,
    // naming the floor that has to move with it.
    expect(programmerActionBarSrc).toContain('the bar is 285px iconic')
    expect(programmerGridSrc).toContain('285 + 17 + 102 = 404')
    expect(programmerGridSrc).toContain('min-w-[min(410px,100%)]')
  })

  it('gives the folded row\'s spare width to the filter field, not to a hole', () => {
    // The leading block stands at its floor and the tools take the rest, so the field inside them
    // is what the slack becomes — at 1024x457 that was a 204px hole between the verbs and the
    // tools. No `max-w` in this arm on purpose: here the field IS the spacer, so a ceiling would
    // only put the hole back beyond it — but it does carry a `min-w`, because with a cue included
    // the leading block grows too and an unclamped field would be starved to a clipped
    // placeholder, which is the regression the threshold exists to prevent.
    //
    // The folded arm keeps a plain `flex-1` — it shares the slack with the leading block by
    // design — where the unfolded arm is `flex-[999_1_0%]` so the field takes the slack before
    // the row's spacer does, and has its field from `@[360px]` (the chrome tidy-up).
    expect(programmerGridSrc).toContain("'flex-1 @[840px]:flex @[840px]:min-w-[132px]'")
    expect(programmerGridSrc).toContain("'max-w-[340px] flex-[999_1_0%] @[360px]:flex'")
    expect(programmerGridSrc).toContain("leading ? '@[840px]:hidden' : '@[360px]:hidden'")
  })

  it('keeps the `Groups` word off the folded row', () => {
    // The one control after the divider that was not keeping the row's icon rule. Its `@[800px]`
    // reads row B's own width, which when folded is the whole grid column — so the word appeared
    // exactly where the row has least to spare, and the 54px it cost was the difference between
    // the source box saying `Programmer is empty.` and drawing an empty bordered rectangle.
    expect(programmerGridSrc).toContain("labelUnlessCompact(!!leading, '@[800px]:inline')")
  })
})
