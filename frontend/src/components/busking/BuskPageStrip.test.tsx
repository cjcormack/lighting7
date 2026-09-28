// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { DndContext } from '@dnd-kit/core'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { store } from '@/store'
import type { BuskPage } from '@/api/buskApi'
import { getLocalBuskPage, isFollowingBuskPage, resetBuskPageFollowStores, unlinkBuskPage } from '@/lib/buskPageFollow'
import { resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'
import {
  BuskPageStrip,
  PAD_CHIP_SUBJECT_CLASS,
  PAD_EDIT_WORD_CLASS,
  PAD_FOCUS_WORD_CLASS,
  PAD_LABEL_CLASS,
  PAD_PAGE_NAMES_CLASS,
  PAD_PAGE_SUBJECT_CLASS,
  PAD_ROW_FLOOR_PX,
  PAD_SECOND_ROW_CLASS,
  PAD_TWO_ROWS_CLASS,
  PAD_VERB_WORD_CLASS,
  FOLDED_PAGE_NAMES_CLASS,
  MERGED_PAGE_NAMES_CLASS,
  SPLIT_PAGE_NAMES_CLASS,
  SPLIT_PAGE_SUBJECT_CLASS,
  type BuskPageStripProps,
  type PadRowSelection,
} from './BuskPageStrip'
import { pagedWithDeskTitle } from './BuskPageChip'
import type { BuskingTarget } from './buskingTypes'
import type { SelectionVerbs } from './selectionVerbs'
import type { Fixture } from '@/store/fixtures'

const programmer = { blind: false }
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => programmer.blind }))

/** The other windows paged with the desk, as the registry would answer them. */
const coPaged = { names: [] as string[] }
vi.mock('@/store/windows', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/windows')>()),
  useCoPagedWindowNames: () => coPaged.names,
}))

/**
 * The pad row (busk-chrome plan session A.5, D17–D20): in Pads on the desk board the body's top
 * row, carrying the `PADS` label, the tabs at the rig row's control size, the three selection verbs
 * pressing the host's handlers, the summary in the gap, the family pill, then the host's controls;
 * in Split the tabs and the page mark only. The page mark (desk-follow D7) sits beside the tabs in
 * every shape: the link badge while paged with the desk, *Page: Own* while not. Its folds are its
 * own ladder of the rig row's shape.
 */
const pages: BuskPage[] = [
  { id: 4, uuid: 'p4', name: 'Ballads', sortOrder: 0, rows: [] },
  { id: 5, uuid: 'p5', name: 'Dance', sortOrder: 1, rows: [] },
]

const par: Fixture = {
  key: 'par-1',
  name: 'PAR 1',
  type: 'Generic PAR',
  groups: [],
  elements: [],
} as unknown as Fixture

/** The host's handlers as mocks: the verbs object, and each mock beside it for the assertions. */
function verbs() {
  const spread = vi.fn<() => void>()
  const locate = vi.fn<() => void>()
  const press = vi.fn<() => void>()
  const release = vi.fn<() => void>()
  const v: SelectionVerbs = {
    spread,
    locate: { press: locate, active: false, enabled: true, title: 'Locate the selection: white beam at centre', label: 'Locate' },
    highlight: { press, release, active: false, enabled: true },
  }
  return { v, spread, locate, press, release }
}

function padsOf(v: SelectionVerbs, families: PadRowSelection['families'] = null): PadRowSelection {
  const target: BuskingTarget = { type: 'fixture', key: 'par-1', fixture: par }
  return { selectedTargets: new Map([['fixture:par-1', target]]), families, verbs: v }
}

function draw(props: Partial<BuskPageStripProps> = {}) {
  return render(
    <Provider store={store}>
      <DndContext>
        <BuskPageStrip
          pages={pages}
          activePageId={4}
          editing={false}
          onSelect={() => {}}
          onCreate={() => Promise.resolve()}
          onRename={() => Promise.resolve()}
          onDelete={() => {}}
          onReorder={() => {}}
          {...props}
        />
      </DndContext>
    </Provider>,
  )
}

afterEach(() => {
  cleanup()
  coPaged.names = []
  window.sessionStorage.clear()
  resetBuskPageFollowStores()
  resetDeskFollowStores()
})

describe('the pad row', () => {
  it('in Pads is the label, the tabs and the page mark, the three verbs, the summary, the pill, the desk chip, then the host’s controls (D17)', () => {
    unlinkBuskPage(4)
    unlinkFromDesk({ targets: [], families: null })
    draw({ pads: padsOf(verbs().v, ['COLOUR']), controls: <button type="button">Focus here</button> })
    const row = document.querySelector('[data-pad-row="pads"]') as HTMLElement
    expect(row).not.toBeNull()
    const order = [...row.querySelectorAll('button, [data-pad-summary], [data-pad-family]')].map(
      (el) => el.getAttribute('aria-label') ?? el.textContent,
    )
    expect(order).toEqual([
      'Ballads', 'Dance', 'Page: Own',
      'Spread…', 'Locate', 'Highlight',
      'PAR 1 · 1 head', 'Colour', 'Targets: This window', 'Focus here',
    ])
    // The label is the row's first thing, and folds by its rung.
    const label = screen.getByText('Pads', { selector: 'div' })
    expect(label.className).toContain(PAD_LABEL_CLASS)
    expect(row.querySelector('[data-pad-row-tabs]')!.firstElementChild).toBe(label)
    // No Cells menu, no steps, no Clear: those act on tiles.
    expect(within(row).queryByRole('button', { name: /^Cells:/ })).toBeNull()
    expect(within(row).queryByRole('button', { name: 'Clear' })).toBeNull()
    expect(within(row).queryByRole('button', { name: /along the rig/ })).toBeNull()
    // The controls end the state group.
    const state = row.querySelector('[data-pad-row-state]') as HTMLElement
    expect(state.lastElementChild).toHaveTextContent('Focus here')
    expect(state.className).toContain('flex-1')
  })

  it('draws the amber BLIND pill after the family pill in Pads only while the programmer is blind, and never in Split', () => {
    unlinkBuskPage(4)
    unlinkFromDesk({ targets: [], families: null })
    draw({ pads: padsOf(verbs().v, ['COLOUR']), controls: <button type="button">Focus here</button> })
    expect(document.querySelector('[data-busk-blind]')).toBeNull()
    cleanup()

    programmer.blind = true
    try {
      draw({ pads: padsOf(verbs().v, ['COLOUR']), controls: <button type="button">Focus here</button> })
      const row = document.querySelector('[data-pad-row="pads"]') as HTMLElement
      const pill = row.querySelector('[data-busk-blind]') as HTMLElement
      expect(pill).toHaveTextContent('Blind')
      expect(pill.tagName).not.toBe('BUTTON')
      // Its word folds on the pad row's Focus-words rung, and it may give before a control moves.
      expect(pill.querySelector('[data-busk-blind-word]')!.className).toContain(PAD_FOCUS_WORD_CLASS)
      expect(pill.className).toMatch(/(^| )shrink( |$)/)
      const order = [...row.querySelectorAll('button, [data-pad-summary], [data-pad-family], [data-busk-blind]')].map(
        (el) => el.getAttribute('aria-label') ?? el.textContent,
      )
      expect(order).toEqual([
        'Ballads', 'Dance', 'Page: Own',
        'Spread…', 'Locate', 'Highlight',
        'PAR 1 · 1 head', 'Colour', 'Blind', 'Targets: This window', 'Focus here',
      ])
      cleanup()
      // In Split and Rig the rig row carries it; the pad row does not repeat it.
      draw({ controls: <button type="button">Focus here</button> })
      expect(document.querySelector('[data-pad-row="split"]')).not.toBeNull()
      expect(document.querySelector('[data-busk-blind]')).toBeNull()
    } finally {
      programmer.blind = false
    }
  })

  it('in Split is the tabs and the page mark only — no label, no verbs, no summary, no desk chip, and the host’s controls still at the end', () => {
    unlinkBuskPage(4)
    unlinkFromDesk({ targets: [], families: null })
    draw({ controls: <button type="button">Sheet</button> })
    const row = document.querySelector('[data-pad-row="split"]') as HTMLElement
    expect(row).not.toBeNull()
    const order = [...row.querySelectorAll('button, [data-pad-summary], [data-pad-family]')].map(
      (el) => el.getAttribute('aria-label') ?? el.textContent,
    )
    expect(order).toEqual(['Ballads', 'Dance', 'Page: Own', 'Sheet'])
    expect(row.querySelector('[data-pad-summary]')).toBeNull()
    // The label is Pads' — a `div`, which the order above cannot see, so it is asked for by name.
    expect(screen.queryByText('Pads', { selector: 'div' })).toBeNull()
    // The desk chip is the rig row's here.
    expect(screen.queryByRole('button', { name: /^Targets:/ })).toBeNull()
    // Split wraps as it always did, for edit mode's name field; the Pads ladder is not applied,
    // and the tabs group is capped to the row and wraps within it, so the name field can break.
    expect(row.className).toMatch(/(^| )flex-wrap( |$)/)
    expect(row.className).not.toContain(PAD_TWO_ROWS_CLASS)
    const tabs = row.querySelector('[data-pad-row-tabs]') as HTMLElement
    expect(tabs.className).toContain('max-w-full')
    expect(tabs.className).toMatch(/(^| )flex-wrap( |$)/)
  })

  it('presses the host’s handlers from the three verbs, with the band’s aria-labels', () => {
    const mocks = verbs()
    draw({ pads: padsOf(mocks.v) })
    fireEvent.click(screen.getByRole('button', { name: 'Spread…' }))
    expect(mocks.spread).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Locate' }))
    expect(mocks.locate).toHaveBeenCalledTimes(1)
    const highlight = screen.getByRole('button', { name: 'Highlight' })
    fireEvent.pointerDown(highlight)
    expect(mocks.press).toHaveBeenCalledTimes(1)
    fireEvent.pointerUp(highlight)
    expect(mocks.release).toHaveBeenCalledTimes(1)
    // Each verb's word folds by the pad row's own rung, not the rig row's.
    for (const name of ['Spread…', 'Locate', 'Highlight']) {
      const word = [...screen.getByRole('button', { name }).querySelectorAll('span')].find((el) => el.textContent === name)!
      expect(word.className).toBe(PAD_VERB_WORD_CLASS)
    }
  })

  it('draws the summary only in Pads, in the gap, truncating with the whole text on its title', () => {
    draw({ pads: padsOf(verbs().v) })
    const summary = document.querySelector('[data-pad-summary]') as HTMLElement
    expect(summary).toHaveTextContent('PAR 1 · 1 head')
    expect(summary).toHaveAttribute('title', 'PAR 1 · 1 head')
    expect(summary.className).toContain('min-w-0')
    expect(summary.className).toContain('truncate')
    expect(summary.className).toContain('flex-1')
    cleanup()
    draw()
    expect(document.querySelector('[data-pad-summary]')).toBeNull()
  })

  it('draws the family pill only while a mask is set, and only in Pads', () => {
    draw({ pads: padsOf(verbs().v, ['COLOUR', 'POSITION']) })
    expect(document.querySelector('[data-pad-family]')).toHaveTextContent('Colour · Position')
    cleanup()
    draw({ pads: padsOf(verbs().v, null) })
    expect(document.querySelector('[data-pad-family]')).toBeNull()
    cleanup()
    draw()
    expect(document.querySelector('[data-pad-family]')).toBeNull()
  })

  it('marks the page in every shape — the link badge while paged with the desk, *Page: Own* while not — beside the tabs (desk-follow D7, D9)', () => {
    // Paged with the desk: the badge — the toggle's linked face (D11) — in the tabs' group.
    draw({ pads: padsOf(verbs().v) })
    expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
    const badge = screen.getByRole('button', { name: 'Paged with the desk' })
    expect(badge).toHaveAttribute('data-link-badge')
    expect(badge.closest('[data-pad-row-tabs]')).not.toBeNull()
    // With nobody else paged with it, the glyph alone.
    expect(badge.querySelector('[data-link-badge-names]')).toBeNull()
    cleanup()
    draw()
    expect(screen.getByRole('button', { name: 'Paged with the desk' })).toBeInTheDocument()
    cleanup()
    // Own page: the dashed chip, whose subject folds at the row's rung (D9's *Own*).
    unlinkBuskPage(4)
    draw({ pads: padsOf(verbs().v) })
    expect(screen.queryByRole('button', { name: /^Paged with the desk/ })).toBeNull()
    const chip = screen.getByRole('button', { name: 'Page: Own' })
    expect(chip.className).toContain('border-dashed')
    expect(chip.closest('[data-pad-row-tabs]')).not.toBeNull()
    expect(chip.className).toMatch(/(^| )shrink( |$)/)
    expect(chip.className).toContain('min-w-0')
    expect(chip.querySelector('[data-pill-subject]')!.className).toContain(PAD_PAGE_SUBJECT_CLASS)
    cleanup()
    draw()
    expect(screen.getByRole('button', { name: 'Page: Own' }).querySelector('[data-pill-subject]')!.className).toContain(
      SPLIT_PAGE_SUBJECT_CLASS,
    )
    // Not with no pages: a click there would spend the arrival decision on nothing.
    cleanup()
    draw({ pages: [], activePageId: null })
    expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Paged with the desk/ })).toBeNull()
  })

  it('is the toggle both ways: the badge keeps the page on show as this window’s own, the chip pages with the desk again (D11)', () => {
    draw({ activePageId: 5 })
    const badge = screen.getByRole('button', { name: 'Paged with the desk' })
    expect(badge).toHaveAttribute('aria-pressed', 'true')
    expect(badge).toHaveAttribute('title', "Paged with the desk. Click to keep this page as this window's own")
    fireEvent.click(badge)
    expect(isFollowingBuskPage()).toBe(false)
    expect(getLocalBuskPage()).toBe(5)
    fireEvent.click(screen.getByRole('button', { name: 'Page: Own' }))
    expect(isFollowingBuskPage()).toBe(true)
    expect(screen.getByRole('button', { name: 'Paged with the desk' })).toBeInTheDocument()
    cleanup()
    // Folded (Rig focus pages with the group too), the same toggle.
    draw({ folded: true, activePageId: 4 })
    fireEvent.click(screen.getByRole('button', { name: 'Paged with the desk' }))
    expect(getLocalBuskPage()).toBe(4)
  })

  it('draws the selection badge in Pads as a mark, not a press — Pads always follows (D2)', () => {
    draw({ pads: padsOf(verbs().v) })
    const row = document.querySelector('[data-pad-row-state]') as HTMLElement
    expect(within(row).getByRole('img', { name: 'Following the desk selection' })).toHaveAttribute(
      'title',
      'Following the desk selection — Pads focus always follows',
    )
    expect(within(row).queryByRole('button', { name: 'Following the desk selection' })).toBeNull()
  })

  it('names the other windows paged with the desk on the badge, folding them on each row’s own rung (D7)', () => {
    coPaged.names = ['Screen 2', 'Screen 3']
    const title = 'Paged with the desk, with Screen 2 and Screen 3 — a tab click here pages them too'
    const namesOf = () => screen.getByRole('button', { name: title }).querySelector('[data-link-badge-names]') as HTMLElement
    draw({ pads: padsOf(verbs().v) })
    expect(namesOf()).toHaveTextContent('Screen 2 +1')
    expect(namesOf().className).toBe(PAD_PAGE_NAMES_CLASS)
    cleanup()
    draw()
    expect(namesOf().className).toBe(SPLIT_PAGE_NAMES_CLASS)
    cleanup()
    draw({ dense: true })
    expect(namesOf().className).toBe(MERGED_PAGE_NAMES_CLASS)
    cleanup()
    draw({ folded: true })
    expect(namesOf().className).toBe(FOLDED_PAGE_NAMES_CLASS)
    // One name reads *it*, and nobody else reads the bare sentence.
    expect(pagedWithDeskTitle(['Screen 2'])).toBe('Paged with the desk, with Screen 2 — a tab click here pages it too')
    expect(pagedWithDeskTitle([])).toBe('Paged with the desk')
  })

  it('sizes the tabs to the rig row’s controls: a 28px group of 24px text-xs items', () => {
    draw({ pads: padsOf(verbs().v) })
    const group = document.querySelector('[data-busk-page-tabs]') as HTMLElement
    expect(group.className).toContain('h-7')
    expect(group.className).toContain('p-0.5')
    for (const name of ['Ballads', 'Dance']) {
      const tab = screen.getByRole('button', { name })
      expect(tab.className).toContain('h-6')
      expect(tab.className).toContain('px-2')
      expect(tab.className).toContain('text-xs')
    }
    expect(screen.getByRole('button', { name: 'Ballads' })).toHaveAttribute('aria-current', 'page')
    // The merged row keeps its own, smaller, form.
    cleanup()
    draw({ dense: true })
    expect((document.querySelector('[data-busk-page-tabs]') as HTMLElement).className).toContain('p-px')
    expect(screen.getByRole('button', { name: 'Ballads' }).className).toContain('py-0.5')
    // And it is 32px with its border inside, not a 32px row under a bordered wrapper (33).
    const wrapper = document.querySelector('[data-busk-page-strip-dense]') as HTMLElement
    expect(wrapper.className).toContain('h-8')
    expect(wrapper.className).toContain('border-b')
    const row = wrapper.querySelector('[data-pad-row="merged"]') as HTMLElement
    expect(row.className).toContain('h-full')
    expect(row.className).not.toContain('h-8')
  })

  it('folds in the rig row’s order — verbs’ and Edit layout’s words, then the Focus words and the chip’s subject, then the label — and is two rows under its own floor, with the words back as closed ranges (D15, D19, D20)', () => {
    const rung = (cls: string) => Number(cls.match(/@\[(\d+)px\]/)![1])
    const range = (cls: string) => {
      const m = cls.match(/@min-\[(\d+)px\]:@max-\[(\d+)px\]:(\S+)/)
      expect(m, cls).not.toBeNull()
      return { from: Number(m![1]), to: Number(m![2]), utility: m![3] }
    }
    // The page badge's names go first (desk-follow D7), and the page chip's *Page:* with them.
    expect(rung(PAD_PAGE_NAMES_CLASS)).toBeGreaterThan(rung(PAD_VERB_WORD_CLASS))
    expect(rung(PAD_PAGE_SUBJECT_CLASS)).toBe(rung(PAD_PAGE_NAMES_CLASS))
    expect(rung(PAD_VERB_WORD_CLASS)).toBeGreaterThan(rung(PAD_FOCUS_WORD_CLASS))
    expect(rung(PAD_EDIT_WORD_CLASS)).toBe(rung(PAD_VERB_WORD_CLASS))
    expect(rung(PAD_CHIP_SUBJECT_CLASS)).toBe(rung(PAD_FOCUS_WORD_CLASS))
    expect(rung(PAD_FOCUS_WORD_CLASS)).toBeGreaterThan(rung(PAD_LABEL_CLASS))
    expect(rung(PAD_LABEL_CLASS)).toBeGreaterThan(PAD_ROW_FLOOR_PX)
    expect(PAD_LABEL_CLASS).toMatch(/^hidden @\[\d+px\]:block$/)
    for (const cls of [
      PAD_PAGE_NAMES_CLASS,
      PAD_PAGE_SUBJECT_CLASS,
      PAD_VERB_WORD_CLASS,
      PAD_EDIT_WORD_CLASS,
      PAD_FOCUS_WORD_CLASS,
      PAD_CHIP_SUBJECT_CLASS,
    ]) {
      const { from, to, utility } = range(cls)
      expect(to).toBe(PAD_ROW_FLOOR_PX)
      expect(from).toBeLessThan(to)
      expect(utility).toBe('inline')
      expect(cls).toMatch(/^hidden @\[\d+px\]:inline @min-/)
    }
    // Under the floor the first line holds the tabs, the mark and the verbs: the verbs' words come
    // back before the chip's subject, and that before the badge's names.
    expect(range(PAD_PAGE_NAMES_CLASS).from).toBeGreaterThan(range(PAD_PAGE_SUBJECT_CLASS).from)
    expect(range(PAD_PAGE_SUBJECT_CLASS).from).toBeGreaterThan(range(PAD_VERB_WORD_CLASS).from)
    // The floor, on the row: two groups, the second taking its whole line under it.
    draw({ pads: padsOf(verbs().v), controls: <button type="button">Focus here</button> })
    const row = document.querySelector('[data-pad-row="pads"]') as HTMLElement
    // **The `@container` is an ancestor of the row, never the row itself**: a query container is
    // the nearest ancestor container, so a floor class on the container element has nothing to
    // match and never fires — which jsdom cannot show, so the structure is pinned here.
    expect(row.className).not.toContain('@container')
    const container = row.parentElement as HTMLElement
    expect(container.className).toContain('@container')
    expect(container).toHaveAttribute('data-busk-page-strip', 'open')
    // The gutter is the container's, so its content box is the row's and the rungs read it.
    expect(container.className).toContain('px-4')
    expect(row.className).not.toContain('px-4')
    expect(PAD_TWO_ROWS_CLASS).toMatch(/^@max-\[\d+px\]:flex-wrap$/)
    expect(PAD_SECOND_ROW_CLASS).toMatch(/^@max-\[\d+px\]:basis-full$/)
    expect(Number(PAD_TWO_ROWS_CLASS.match(/\d+/)![0])).toBe(PAD_ROW_FLOOR_PX)
    expect(row.className).toContain(PAD_TWO_ROWS_CLASS)
    expect(row.className).not.toMatch(/(^| )flex-wrap( |$)/)
    const tabs = row.querySelector('[data-pad-row-tabs]') as HTMLElement
    const state = row.querySelector('[data-pad-row-state]') as HTMLElement
    expect(state.className).toContain(PAD_SECOND_ROW_CLASS)
    expect(tabs.className).toContain('shrink-0')
    expect(within(tabs).getByRole('button', { name: 'Highlight' })).toBeInTheDocument()
    expect(within(state).getByText('Focus here')).toBeInTheDocument()
    expect(state.querySelector('[data-pad-summary]')).not.toBeNull()
  })

  it('keeps the merged row and the folded strip as they were — no label, no verbs, no summary — beside the page mark', () => {
    draw({ dense: true, leading: <span>leading</span>, controls: <span>ctl</span> })
    const merged = document.querySelector('[data-pad-row="merged"]') as HTMLElement
    expect(merged.className).toContain('h-full')
    expect(merged.parentElement!.className).toContain('h-8')
    expect(merged.querySelector('[data-pad-row-tabs]')).toBeNull()
    expect(screen.queryByText('Pads', { selector: 'div' })).toBeNull()
    expect(screen.getByText('leading').compareDocumentPosition(screen.getByText('ctl')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    cleanup()
    draw({ folded: true, controls: <span>ctl</span> })
    const folded = document.querySelector('[data-busk-page-strip="folded"]') as HTMLElement
    expect(folded.className).toContain('h-10')
    expect(folded).toHaveTextContent('Ballads')
    // Rig focus still pages with the group, so the fold carries the page mark (D7).
    expect(within(folded).getByRole('button', { name: 'Paged with the desk' })).toBeInTheDocument()
    expect(within(folded).queryByRole('button', { name: 'Ballads' })).toBeNull()
  })

  it('folded, carries the chevron back to Split before the controls, and only when given a press', () => {
    const onUnfold = vi.fn()
    draw({ folded: true, controls: <span>ctl</span>, onUnfold })
    const folded = document.querySelector('[data-busk-page-strip="folded"]') as HTMLElement
    const chevron = within(folded).getByRole('button', { name: 'Show the page again: Split' })
    expect(chevron.compareDocumentPosition(screen.getByText('ctl')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(chevron)
    expect(onUnfold).toHaveBeenCalledTimes(1)
    cleanup()
    draw({ folded: true })
    expect(screen.queryByRole('button', { name: 'Show the page again: Split' })).toBeNull()
  })
})
