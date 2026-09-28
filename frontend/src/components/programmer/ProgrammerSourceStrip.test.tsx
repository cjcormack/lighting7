// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IncludedTarget } from '@/api/programmerWsApi'

let summary = { blind: false, entryCount: 0, lastIncluded: null as IncludedTarget | null }
let dirty: number | null = null
let stacks: unknown[] = []
let looks: unknown[] = []

vi.mock('@/store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: summary }),
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: [] }) }))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: stacks }) }))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: looks }) }))
vi.mock('./useIncludeBaseline', () => ({ useIncludeBaseline: () => dirty }))

import { ProgrammerSourceStrip } from './ProgrammerSourceStrip'

const CUE: IncludedTarget = {
  kind: 'CUE',
  cueId: 5,
  cueStackId: 2,
  cueNumber: 'Q4',
  cueName: 'Warm Wash',
}
const STACK = { id: 2, name: 'Act 1', cues: [{ id: 5 }, { id: 6 }] }

function draw() {
  render(
    <ProgrammerSourceStrip
      projectId={1}
      onUpdate={() => {}}
      onRevert={() => {}}
    />,
  )
}

afterEach(() => {
  cleanup()
  summary = { blind: false, entryCount: 0, lastIncluded: null }
  dirty = null
  stacks = []
  looks = []
})

describe('ProgrammerSourceStrip', () => {
  it('reports that there is no source, rather than teaching what Include does', () => {
    // The register the whole box is now written in: it names what Record will write back to, and
    // with nothing included that answer is two words. It used to be an eleven-word instruction
    // sitting permanently beside the `Include` button it described — the only thing on this desk
    // that taught rather than reported, and the reason this arm needed an eight-rung ladder.
    draw()
    const visible = screen.getByText('No source')
    expect(visible).toBeTruthy()
    // The instruction is off the SCREEN, which is the decision — `aria-hidden` on the visible
    // words and the sentence in an `sr-only` span beside them is how it stays off the screen
    // without going out of the document. Assert the split, not the absence: a plain
    // `queryByText(...)` for the sentence would pass only while assistive tech had nothing.
    expect(visible.getAttribute('aria-hidden')).toBe('true')
    const sentence = screen.getByText(
      'Programmer is empty. Include a cue or a Look, or start busking.',
    )
    expect(sentence.className).toContain('sr-only')
  })

  it('keeps the sentence it no longer says, on the box', () => {
    // Nothing was deleted, only moved — the same rule every width-driven cut in this plan follows.
    // The `title` is on the BOX and not the text, and may be *only* in the two arms with no
    // interactive descendant: a native title is inherited by any descendant without one, so in the
    // cue and Look arms it would be answered by `Update`, which is already inside a Radix tooltip.
    draw()
    const box = screen.getByTitle('Programmer is empty. Include a cue or a Look, or start busking.')
    expect(box.querySelector('button')).toBeNull()
  })

  it('marks the states that want the row\'s width, and only those', () => {
    // The producer half of a contract whose consumer is a CSS selector in another component:
    // `ProgrammerGrid`'s leading block is `has-[[data-fills]]:flex-1`, so this attribute is the
    // only thing telling it whether to grow. Nothing type-checks that link — rename the `fill`
    // prop or respell the attribute and both files still compile, both test files still pass, and
    // the folded row silently lays out wrong. `foldedRow.test.ts` pins the consumer; this pins the
    // producer, so the two ends are asserted against the same spelling.
    const box = () =>
      // `getAll`: since the phone arm the cue's name is also in the box's `sr-only` sentence.
      screen.getAllByText(/No source|Warm Wash|has been deleted/)[0].closest('[class*="rounded-md"]')

    // Sourceless: as wide as its words, so it must NOT ask for the row.
    draw()
    expect(box()?.hasAttribute('data-fills')).toBe(false)
    cleanup()

    // A cue: the name truncates and the badges and verbs need the room.
    summary = { ...summary, lastIncluded: CUE }
    stacks = [STACK]
    draw()
    expect(box()?.hasAttribute('data-fills')).toBe(true)
    cleanup()

    // And the deleted-source arm, which is the one an "exactly two and two" reading forgets — its
    // sentence is the longest thing this box ever says, so it needs the width most of all.
    stacks = []
    draw()
    expect(box()?.hasAttribute('data-fills')).toBe(true)
  })

  it('is as wide as its words, not as wide as the row', () => {
    // `PD-SOURCE-BOX-WIDTH`. `flex-1` was how the rungs got a definite width to be measured
    // against — a container under `inline-size` containment cannot be sized by its contents — so
    // the box took every spare pixel of the row and drew a bordered rectangle around a short
    // sentence, 660px of it on a 772x457 window. With no rungs there is nothing to size, so the
    // box is `flex-initial` and the slack goes to the verbs through the divider's `ml-auto`.
    draw()
    const box = screen.getByTitle('Programmer is empty. Include a cue or a Look, or start busking.')
    expect(box.className).toContain('flex-initial')
    expect(box.className).not.toContain('flex-1')
  })

  it('names the busking state, and offers no verb of its own', () => {
    summary = { ...summary, entryCount: 12 }
    draw()
    expect(screen.getByText('Busking')).toBeTruthy()
    expect(screen.getByText('12 values')).toBeTruthy()
    // The whole state, for the widths where the count is not drawn — on the box's `title` for a
    // pointer, and in an `sr-only` span for everyone who has not got one.
    expect(screen.getByTitle('Busking — 12 values, with no source to update')).toBeTruthy()
    expect(
      screen.getByText('Busking — 12 values, with no source to update').className,
    ).toContain('sr-only')
    // `PD-TWO-RECORD-BUTTONS`: this box used to carry its own Record here, calling the identical
    // `openRecord()` the action bar's primary does. One act, one control — the bar's, which is
    // also the only one with the destination menu. The box names the source and stops.
    expect(screen.queryByRole('button', { name: /Record/ })).toBeNull()
  })

  it('drops the busking count whole, and never the label', () => {
    // What survives of `PD-SOURCE-TRUNCATION`: one rung per arm, in the place each ladder always
    // ended. The count goes first because it is the part said twice — it is on the rail's Local
    // values row and in the programmer tile — and the label is the only thing naming the state.
    summary = { ...summary, entryCount: 12 }
    draw()
    expect(screen.getByText('12 values').className).toContain('@[450px]:block')
    expect(screen.getByText('Busking').className).toContain('shrink-0')
    expect(screen.getByText('Busking').className).not.toContain('hidden')
  })

  it('drops the empty state\'s words whole rather than slicing them', () => {
    // The same single rung on the other arm: below the width where `No source` fits beside the
    // 285px iconic action bar, the box is its glyph alone. A word sliced mid-letter is the fault
    // this file has been written against since session 1.
    //
    // 410 and not a rounder 420: this rung has a CEILING as well as a floor — the block is 419px
    // on the 852x393 phone the fold exists for, so slack chosen upwards takes the words off that
    // screen instead of protecting them. See the constant's doc comment.
    draw()
    expect(screen.getByText('No source').className).toContain('@[410px]:block')
    expect(screen.getByText('No source').className).toContain('hidden')
  })

  it('names the cue, its stack and its position, and labels Update with it', () => {
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 3
    stacks = [STACK]
    draw()
    // Two `Q4`s on screen since session 1: the cue number in the box, and the one appended to
    // Update at `@[800px]`. Both are the same fact, so the assertion is that at least one is there.
    expect(screen.getAllByText('Q4').length).toBeGreaterThan(0)
    expect(screen.getByText('Warm Wash')).toBeTruthy()
    expect(screen.getByText('Act 1 · cue 1 of 2')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Update Q4' })).not.toBeDisabled()
  })

  it('carries every sentence a narrow width hides on a title or an aria-label', () => {
    // The promise of session 1: rows A and B lost their labels and their explanatory sentences to
    // gain the page back, and *nothing they said was deleted*. Each one is on hover instead, so
    // these assertions are what stops a later shrink quietly taking the words with it.
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 3
    stacks = [STACK]
    draw()
    // The long dirty wording — visible only at `@[1100px]`.
    expect(screen.getByTitle('3 changes not written back')).toBeTruthy()
    // Revert is an icon below `@[1100px]`; the word is its accessible name at every width.
    expect(screen.getByRole('button', { name: 'Revert' })).toBeTruthy()
    // The whole state as one sentence, for the widths where the location line is gone — on the
    // *text*, never on the box: a native `title` on an ancestor is what the browser shows for a
    // descendant that has none, and the Update button below is already inside a Radix tooltip.
    const sentence = screen.getAllByTitle('Editing · Q4 · Warm Wash · Act 1 · cue 1 of 2')
    expect(sentence.length).toBeGreaterThan(0)
    for (const el of sentence) expect(el.querySelector('button')).toBeNull()
  })

  it('keeps the cue number on Update\'s accessible name when the visible text drops it', () => {
    // Below `@[800px]` the button reads just "Update". Which cue it writes to is the one thing an
    // operator must not have to guess, so it stays on the label rather than only in the box.
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 2
    stacks = [STACK]
    draw()
    expect(screen.getByRole('button', { name: 'Update Q4' })).toBeTruthy()
  })

  it('disables Update and says "in sync" only when a baseline proves it', () => {
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 0
    stacks = [STACK]
    draw()
    expect(screen.getByText('in sync')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Update Q4' })).toBeDisabled()
  })

  it('NEVER claims "in sync" without a baseline, and leaves Update enabled', () => {
    // The rule of this band. A reloaded tab, or one opened after the Include, has no baseline. A
    // false "in sync" tells an operator their work is written when it is not, and costs the cue —
    // so the badge is omitted entirely and Update stays pressable.
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = null
    stacks = [STACK]
    draw()
    expect(screen.queryByText('in sync')).toBeNull()
    expect(screen.queryByText(/not written back/)).toBeNull()
    expect(screen.getByRole('button', { name: 'Update Q4' })).not.toBeDisabled()
  })

  it('reports a deleted cue instead of a conflict it cannot detect', () => {
    // "Q4 changed on another desk" is not reachable — no version on `Cue`, no frame announcing it.
    // A cue that has left the stack list IS, and it reuses the same amber slot.
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    stacks = []
    draw()
    expect(screen.getByText(/has been deleted/)).toBeTruthy()
    // `PD-TWO-RECORD-BUTTONS` again: this arm carried the second Record too, in outline. The
    // warning is the whole row now — the verb is the action bar's, at every entry count.
    expect(screen.queryByRole('button', { name: /Record/ })).toBeNull()
  })

  it('still reports a deleted source with nothing busked behind it', () => {
    // The one state the deletion actually changed the reach of: `missing` at zero entries, where
    // the box's old Record was unconditionally enabled and the action bar's is `disabled` on
    // `!hasContent`. Losing it is correct rather than a gap — Record reads the programmer, and
    // an enabled Record over an empty one contradicts the rule the action bar already keeps —
    // but it was untested on both sides of the change, so it is pinned here.
    summary = { ...summary, entryCount: 0, lastIncluded: CUE }
    stacks = []
    draw()
    expect(screen.getByText(/has been deleted/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Record/ })).toBeNull()
  })

  it('folds to `Q4 · Update · Revert` below 600, with the change count as a dot on Update', () => {
    // The phone arm (`programmer-chrome-design`): with a cue included the box had 67px on a
    // portrait phone for ~200px of content and Update was clipped to a sliver. Below `@[600px]`
    // the name and the badge are hidden, the dirty state is an amber dot on Update, and the count
    // moves to Update's tooltip; the whole state stays in an `sr-only` sentence.
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 3
    stacks = [STACK]
    draw()
    const name = screen.getByText('Warm Wash')
    expect(name.className).toContain('hidden')
    expect(name.className).toContain('@[600px]:inline')
    const badge = screen.getByTitle('3 changes not written back')
    expect(badge.className).toContain('hidden')
    expect(badge.className).toContain('@[600px]:flex')
    const update = screen.getByRole('button', { name: 'Update Q4' })
    const dot = update.parentElement!.querySelector('span[aria-hidden]')!
    expect(dot).not.toBeNull()
    expect(dot.className).toContain('bg-amber-400')
    expect(dot.className).toContain('@[600px]:hidden')
    // The number itself never leaves the box: `Q4` is the one thing the fold keeps on screen.
    const numbers = screen.getAllByText('Q4').filter((el) => !el.className.includes('hidden'))
    expect(numbers.length).toBeGreaterThan(0)
    expect(
      screen.getByText('Editing · Q4 · Warm Wash · Act 1 · cue 1 of 2', { selector: '.sr-only' }),
    ).toBeTruthy()
  })

  it('keeps the name on the phone where nothing else names the source', () => {
    // `includedTargetParts` falls back to the *name* for a cue with no number, so hiding the name
    // below `@[600px]` unconditionally left an Upload glyph and a Revert glyph and nothing at all
    // saying what Update would overwrite — the one question this box exists to answer.
    summary = {
      ...summary,
      entryCount: 9,
      lastIncluded: { ...CUE, cueNumber: '', cueName: 'Warm Wash' },
    }
    dirty = 3
    stacks = [STACK]
    draw()
    const name = screen.getByText('Warm Wash', { selector: '[aria-hidden]' })
    expect(name.className).not.toContain('hidden')
    expect(name.className).not.toContain('@[600px]:inline')
  })

  it('drops it again for a Look once its families badge has arrived to carry it', () => {
    // The Look arm's own identity below 600 is the badge, so the name goes when there is one —
    // and stays while `families` is still in flight, which is every first paint after an Include.
    summary = {
      ...summary,
      entryCount: 9,
      lastIncluded: { kind: 'LOOK', lookId: 3, lookName: 'Warm Amber' },
    }
    dirty = 1
    draw()
    expect(
      screen.getByText('Warm Amber', { selector: '[aria-hidden]' }).className,
    ).not.toContain('hidden')
    cleanup()

    looks = [{ id: 3, families: ['COLOUR'] }]
    draw()
    const named = screen.getByText('Warm Amber', { selector: '[aria-hidden]' })
    expect(named.className).toContain('hidden')
    expect(named.className).toContain('@[600px]:inline')
  })

  it('draws no dot on Update with nothing to write back', () => {
    summary = { ...summary, entryCount: 9, lastIncluded: CUE }
    dirty = 0
    stacks = [STACK]
    draw()
    const update = screen.getByRole('button', { name: 'Update Q4' })
    expect(update.parentElement!.querySelector('span[aria-hidden]')).toBeNull()
  })
})
