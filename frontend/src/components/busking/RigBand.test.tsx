// @vitest-environment jsdom
import { DndContext } from '@dnd-kit/core'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupSummary } from '@/api/groupsApi'
import type { DeskSelectionSnapshot } from '@/api/selectionApi'
import type { BuskRig, BuskRigPatch, BuskRigTile } from '@/api/buskRigApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { relinkToDesk, resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'
import { getBuskFocus, getBuskRigHeight, getBuskSheet, resetBuskWindowStores, setBuskRigHeight } from '@/lib/buskWindow'
import {
  CHIP_SUBJECT_CLASS,
  EDIT_WORD_CLASS,
  FIRST_ROW_CLASS,
  FOCUS_WORD_CLASS,
  RIG_LABEL_CLASS,
  RIG_ROW_FLOOR_PX,
  SECOND_ROW_CLASS,
  TWO_ROWS_CLASS,
  VERB_WORD_CLASS,
  snapRigHeight, stepRigHeight, COMPACT_FOCUS_WORD_CLASS, BLIND_WORD_CLASS } from './RigBand'
import { useSelectionVerbs } from './selectionVerbs'
import type { Fixture } from '@/store/fixtures'
import type { FixtureAppearance } from '@/components/fixtures/fixtureAppearance'
import { buskingTargetKey, type BuskingTarget } from './buskingTypes'

/**
 * The rig band (busk-further plan session 3), over `TargetBand.test.tsx`'s ground.
 *
 * The first test is the migration: an empty rig draws what the target band drew — every group then
 * every fixture, groups badged. The rest are the band's own: a built rig draws its rows; the three
 * cell modes expand; a tile press toggles the whole fixture (and a cell tile its cell) through the
 * one `{type, key}` shape; the rows handle — a drag that snaps to whole lines, stepped by the arrow
 * keys — clamps and snaps past both ends; the Cells menu presses one desk op per filter and the two
 * step buttons one per step; *Spread…* opens the side sheet's tab; and below `md` the band is one
 * row with a row chip. Session 7's
 * block is the pips: a tap toggles a cell, a mouse drag across them is a run, a finger runs only
 * after a hold, and a tile with every cell selected reads `all`. The busk-chrome plan's session A
 * block is the one row (D13, D15): the chip's DOM order the same in Split and Rig, the Cells
 * control reading its mode word when folded, and the floor as two rows by design; session A.5's
 * is D17–D20: no Pads arm, the desk chip only while unlinked, the re-expansion under the floor as
 * closed `@min-`/`@max-` ranges, and the label's fold.
 */

let groups: GroupSummary[] = []
let fixtures: Fixture[] = []
let patches: { id: number; key: string; displayName: string; stageHidden: boolean; groups: { id: number; name: string }[] }[] = []
let rigData: BuskRig | undefined = { rows: [] }
const commit = vi.fn()

vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: groups }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: fixtures }),
  useFixtureTypeListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/patches', () => ({ usePatchListQuery: () => ({ data: patches }) }))
vi.mock('@/store/busk', () => ({
  useBuskRigQuery: () => ({ data: rigData, isError: false }),
  useBuskRigCommit: () => commit,
}))
vi.mock('@/store/locate', () => ({
  useLocateStateQuery: () => ({ data: { targets: [] } }),
  useToggleLocateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve() }))],
}))
vi.mock('@/components/fixtures-list/useHighlight', () => ({
  useHighlight: () => ({ press: vi.fn(), release: vi.fn(), isActive: false }),
}))
vi.mock('@/store/hand', () => ({
  useHandPlace: () => vi.fn(),
  useDeskHandQuery: () => ({ offer: null }),
  heldName: () => 'held',
}))
// The stage's colour dispatch, frozen: red at full, with four per-cell segments.
vi.mock('@/components/fixtures/fixtureAppearance', () => ({
  FixtureAppearanceSource: ({ children }: { children: (a: FixtureAppearance) => React.ReactNode }) =>
    children({
      color: '#ff0000',
      intensity: 1,
      segments: [0, 1, 2, 3].map((i) => ({ css: `#00000${i}`, intensity: 0.5 })),
    }),
}))
// The desk chip's readers (the same three `TargetBand.test.tsx` mocked).
let snapshot: DeskSelectionSnapshot = { targets: [], families: null, source: null }
vi.mock('@/store/selection', async () => {
  const { unlinkFromDesk } = await import('@/lib/deskFollow')
  return {
    useDeskSelectionSnapshot: () => snapshot,
    // The badge's press (desk-follow D11): the desk's fact as this window's own.
    unlinkFromDeskNow: () => unlinkFromDesk({ targets: snapshot.targets, families: snapshot.families ?? null }),
  }
})
vi.mock('@/lib/windowIdentity', () => ({ useWindowName: () => 'Screen 2' }))
// The programmer's blind flag, behind the one seam the band's marks read it through: the band's
// tests render with no store, and the pill must be flippable per test.
const programmer = { blind: false }
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => programmer.blind }))
vi.mock('@/store/windows', () => ({ useDeskWindows: () => [], thisWindowRow: () => null }))

import { RigBand } from './RigBand'
import { RigStrip } from './RigStrip'

function group(name: string, memberCount: number): GroupSummary {
  return { name, memberCount, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'NONE', compatibleLookIds: [] }
}

const barCells = [0, 1, 2, 3].map((i) => ({ index: i, key: `bar-1.pixel-${i}`, displayName: `Cell ${i + 1}`, properties: [] }))
const barFixture = { key: 'bar-1', name: 'Bar L', typeKey: 'bar', elements: barCells, groups: [] } as unknown as Fixture
const parFixture = { key: 'par-1', name: 'PAR 1', typeKey: 'par', groups: [] } as unknown as Fixture
const barPatch: BuskRigPatch = { id: 11, key: 'bar-1', name: 'Bar L', elements: barCells.map((c) => ({ key: c.key, name: c.displayName })) }
const parPatch: BuskRigPatch = { id: 12, key: 'par-1', name: 'PAR 1' }

let nextId = 1
function tile(overrides: Partial<BuskRigTile> & Pick<BuskRigTile, 'kind'>): BuskRigTile {
  const id = nextId++
  return { id, uuid: `t${id}`, cellMode: 'PIPS', ...overrides }
}
function builtRig(): BuskRig {
  return {
    rows: [
      { id: 1, uuid: 'r1', name: 'Wash', tiles: [tile({ kind: 'GROUP', group: group('Front wash', 6) }), tile({ kind: 'FIXTURE', patch: barPatch })] },
      {
        id: 2,
        uuid: 'r2',
        name: 'Cells',
        tiles: [
          tile({ kind: 'FIXTURE', patch: barPatch, cellMode: 'PER_CELL' }),
          tile({ kind: 'FIXTURE', patch: barPatch, cellMode: 'HALVES', cellSplit: 2 }),
          tile({ kind: 'FIXTURE', patch: barPatch, elementKey: 'bar-1.pixel-2', cellMode: 'WHOLE' }),
        ],
      },
      { id: 3, uuid: 'r3', name: 'Three', tiles: [tile({ kind: 'FIXTURE', patch: parPatch })] },
      { id: 4, uuid: 'r4', name: 'Four', tiles: [tile({ kind: 'FIXTURE', patch: parPatch })] },
    ],
  }
}

/** The band as the view mounts it: the three selection verbs are the host's one hook instance. */
function Band(props: Omit<Parameters<typeof RigBand>[0], 'verbs'>) {
  const verbs = useSelectionVerbs(props.selectedTargets)
  return <RigBand {...props} verbs={verbs} />
}

function bandFor(
  selected: BuskingTarget[],
  handlers: Partial<Omit<Parameters<typeof RigBand>[0], 'verbs'>>,
  families: AttributeFamily[] | null,
) {
  const map = new Map(selected.map((t) => [buskingTargetKey(t), t]))
  const band = (
    <Band
      projectId={1}
      selectedTargets={map}
      families={families}
      onToggle={handlers.onToggle ?? (() => {})}
      onClear={handlers.onClear ?? (() => {})}
      onSubselect={handlers.onSubselect ?? (() => {})}
      editing={handlers.editing ?? false}
      compact={handlers.compact ?? false}
      stackRows={handlers.stackRows ?? false}
      focus={handlers.focus ?? 'split'}
      controls={handlers.controls}
    />
  )
  // The view's column: the band, the page strip and the page body, the boxes the band measures
  // the split's ceiling off.
  return (
    <DndContext>
      <div data-busk-column>
        {band}
        <div data-busk-page-strip="open" />
        <div data-busk-page-body />
      </div>
    </DndContext>
  )
}

function draw(
  selected: BuskingTarget[] = [],
  handlers: Partial<Omit<Parameters<typeof RigBand>[0], 'verbs'>> = {},
  families: AttributeFamily[] | null = null,
) {
  return render(bandFor(selected, handlers, families))
}

/**
 * jsdom lays nothing out, so the split's measurements are stubbed by attribute — installed before
 * a draw, since the band measures in a layout effect at mount: the column from y=0 `columnHeight`
 * tall, the band from y=0 to 16px under the grid (the handle's box), the rows grid `gridHeight`
 * tall from y=100 (100 of band chrome above it), each line `lineHeight` tall in turn, the page
 * strip 40. The ceiling is then `columnHeight − 100 − 16 − 40 − 120`: 440 by default.
 */
function stubLayout({ gridHeight = 160, lineHeight = 40, columnHeight = 716 } = {}) {
  const originalRect = Element.prototype.getBoundingClientRect
  const rect = (top: number, height: number) =>
    ({ top, bottom: top + height, left: 0, right: 0, width: 0, height, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-busk-column')) return rect(0, columnHeight)
    if (this.hasAttribute('data-rig-band')) return rect(0, 100 + gridHeight + 16)
    if (this.hasAttribute('data-rig-rows')) return rect(100, gridHeight)
    if (this.hasAttribute('data-busk-page-strip')) return rect(100 + gridHeight + 16, 40)
    const line = (this as HTMLElement).dataset?.rigLine
    if (line != null) return rect(100 + Number(line) * lineHeight, lineHeight)
    return originalRect.call(this)
  })
}

const rowsGrid = () => document.querySelector('[data-rig-rows]') as HTMLElement
const handle = () => screen.getByRole('separator', { name: 'Rig height' })

const tileButtons = () =>
  screen.getAllByRole('button', { pressed: false }).concat(screen.queryAllByRole('button', { pressed: true }))
    .filter(
      (b) =>
        b.getAttribute('aria-pressed') != null &&
        !b.hasAttribute('data-link-badge') &&
        !b.textContent?.startsWith('Targets') &&
        !b.textContent?.startsWith('Cells'),
    )

beforeEach(() => {
  // A desk screen: wide, tall, so the split defaults to three rows (`lib/buskWindow.ts`'s ladder).
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.startsWith('(min-width'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
  snapshot = { targets: [], families: null, source: null }
  groups = [group('Front wash', 6)]
  fixtures = [barFixture, parFixture]
  patches = [
    { id: 11, key: 'bar-1', displayName: 'Bar L', stageHidden: false, groups: [{ id: 1, name: 'Front wash' }] },
    { id: 12, key: 'par-1', displayName: 'PAR 1', stageHidden: false, groups: [] },
  ]
  rigData = { rows: [] }
  commit.mockClear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.clearAllMocks()
  window.sessionStorage.clear()
  resetDeskFollowStores()
  resetBuskWindowStores()
  vi.unstubAllGlobals()
})

describe('the rig band', () => {
  it('draws an empty rig as today’s band: every group then every fixture, groups badged', () => {
    draw()
    expect(screen.getByText('Groups')).toBeInTheDocument()
    expect(screen.getByText('Fixtures')).toBeInTheDocument()
    const names = tileButtons().map((b) => b.textContent)
    expect(names).toEqual(['Front wash6', 'Bar L4', 'PAR 1'])
  })

  it('explains an empty rig rather than drawing an empty band', () => {
    groups = []
    fixtures = []
    draw()
    expect(screen.getByText('No fixtures or groups configured')).toBeInTheDocument()
  })

  it('draws a built rig as its rows, and never the fallback beside them', () => {
    rigData = builtRig()
    draw()
    expect(screen.getByText('Wash')).toBeInTheDocument()
    expect(screen.getByText('Cells')).toBeInTheDocument()
    expect(screen.queryByText('Groups')).not.toBeInTheDocument()
    expect(screen.queryByText('Fixtures')).not.toBeInTheDocument()
  })

  it('expands a PER_CELL tile per cell, a HALVES tile into runs, and a cell tile into its cell', () => {
    rigData = builtRig()
    draw()
    for (let i = 1; i <= 4; i += 1) expect(screen.getAllByText(`Bar L · Cell ${i}`).length).toBeGreaterThan(0)
    expect(screen.getByText('Bar L 1–2')).toBeInTheDocument()
    expect(screen.getByText('Bar L 3–4')).toBeInTheDocument()
    // The cell tile and the PER_CELL expansion both name cell 3 — two tiles, one cell.
    expect(screen.getAllByText('Bar L · Cell 3')).toHaveLength(2)
  })

  it('toggles the whole fixture on a tile press, and a cell on a cell tile — as `{type, key}`', () => {
    rigData = builtRig()
    const onToggle = vi.fn()
    draw([], { onToggle })
    fireEvent.click(screen.getByRole('button', { name: 'Bar L' }))
    expect(onToggle).toHaveBeenLastCalledWith({ type: 'fixture', key: 'bar-1' })
    fireEvent.click(screen.getByRole('button', { name: 'Front wash' }))
    expect(onToggle).toHaveBeenLastCalledWith({ type: 'group', key: 'Front wash' })
    fireEvent.click(screen.getAllByRole('button', { name: 'Bar L · Cell 3' })[0])
    expect(onToggle).toHaveBeenLastCalledWith({ type: 'fixture', key: 'bar-1.pixel-2' })
    onToggle.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Bar L 3–4' }))
    expect(onToggle.mock.calls.map((c) => c[0])).toEqual([
      { type: 'fixture', key: 'bar-1.pixel-2' },
      { type: 'fixture', key: 'bar-1.pixel-3' },
    ])
  })

  it('presses a run as one pad: on from anything but all, off from all', () => {
    rigData = builtRig()
    const onToggle = vi.fn()
    // Cell 3 (pixel-2) is already selected, so the run 3–4 is half lit.
    draw([{ type: 'fixture', key: 'bar-1.pixel-2', fixture: barFixture, element: barCells[2] }], { onToggle })
    const run = screen.getByRole('button', { name: 'Bar L 3–4' })
    expect(run).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(run)
    // Only the unlit cell is toggled — on — so the run reaches `all` rather than its complement.
    expect(onToggle.mock.calls.map((c) => c[0])).toEqual([{ type: 'fixture', key: 'bar-1.pixel-3' }])
  })

  it('reads a cell or run tile `all` while its whole fixture is selected — a parent covers its cells', () => {
    rigData = builtRig()
    draw([{ type: 'fixture', key: 'bar-1', fixture: barFixture }])
    expect(screen.getByRole('button', { name: 'Bar L 1–2' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getAllByRole('button', { name: 'Bar L · Cell 3' })[0]).toHaveAttribute('aria-pressed', 'true')
    // Every line is mounted in Split, so both PAR 1 tiles (rows Three and Four) are on screen.
    for (const par of screen.getAllByRole('button', { name: 'PAR 1' })) expect(par).toHaveAttribute('aria-pressed', 'false')
  })

  it('reads a fixture tile `some` with a count when one of its cells is selected elsewhere', () => {
    rigData = builtRig()
    draw([{ type: 'fixture', key: 'bar-1.pixel-1', fixture: barFixture, element: barCells[1] }])
    const bar = screen.getByRole('button', { name: 'Bar L' })
    expect(bar).toHaveAttribute('aria-pressed', 'true')
    expect(within(bar).getByText('1 of 4')).toBeInTheDocument()
  })

  it('draws the live bar and the pips through the stage’s colour dispatch, the pips beside the tile’s button', () => {
    rigData = builtRig()
    draw()
    const pips = screen.getByRole('group', { name: 'Bar L cells' })
    expect(pips.children).toHaveLength(4)
    expect((pips.children[2] as HTMLElement).style.background).toBe('rgb(0, 0, 2)')
    // A button cannot hold buttons: the row is a sibling of the tile's press, over the same box.
    expect(screen.getByRole('button', { name: 'Bar L' }).contains(pips)).toBe(false)
  })

  describe('the pips (session 7)', () => {
    const pip = (n: number) => screen.getByRole('checkbox', { name: `Bar L · Cell ${n}` })
    const mouse = { button: 0, pointerId: 1, pointerType: 'mouse' }

    it('toggles a cell on a tap, as `{type: \'fixture\', key}` through the one handler — once for a mouse', () => {
      rigData = builtRig()
      const onToggle = vi.fn()
      draw([], { onToggle })
      fireEvent.pointerDown(pip(2), mouse)
      fireEvent.pointerUp(pip(2), mouse)
      fireEvent.click(pip(2))
      expect(onToggle.mock.calls.map((c) => c[0])).toEqual([{ type: 'fixture', key: 'bar-1.pixel-1' }])
      // The keyboard's activation is a click with no run before it, and toggles.
      fireEvent.click(pip(3))
      expect(onToggle).toHaveBeenLastCalledWith({ type: 'fixture', key: 'bar-1.pixel-2' })
    })

    it('runs across the pips a mouse crosses, each toggled once', () => {
      rigData = builtRig()
      const onToggle = vi.fn()
      draw([], { onToggle })
      fireEvent.pointerDown(pip(1), mouse)
      fireEvent.pointerMove(pip(2), mouse)
      fireEvent.pointerMove(pip(3), mouse)
      fireEvent.pointerMove(pip(2), mouse)
      fireEvent.pointerUp(pip(2), mouse)
      expect(onToggle.mock.calls.map((c) => c[0].key)).toEqual(['bar-1.pixel-0', 'bar-1.pixel-1', 'bar-1.pixel-2'])
      // A second pointer's moves are not this run's.
      fireEvent.pointerDown(pip(4), mouse)
      fireEvent.pointerMove(pip(1), { ...mouse, pointerId: 2 })
      expect(onToggle.mock.calls.map((c) => c[0].key)).toEqual(['bar-1.pixel-0', 'bar-1.pixel-1', 'bar-1.pixel-2', 'bar-1.pixel-3'])
    })

    it('runs for a finger only after a hold — a moving finger is the browser’s pan — and grows the pip under it to 44px', () => {
      vi.useFakeTimers()
      try {
        rigData = builtRig()
        const onToggle = vi.fn()
        draw([], { onToggle })
        const touch = { button: 0, pointerId: 7, pointerType: 'touch', clientX: 10, clientY: 10 }
        // Moved before the hold: nothing, and the hold is off.
        fireEvent.pointerDown(pip(1), touch)
        fireEvent.pointerMove(pip(2), { ...touch, clientX: 40 })
        act(() => vi.advanceTimersByTime(600))
        expect(onToggle).not.toHaveBeenCalled()
        fireEvent.pointerUp(pip(2), touch)
        // Held still: the run starts on the pip under the finger, and follows it.
        fireEvent.pointerDown(pip(1), touch)
        act(() => vi.advanceTimersByTime(500))
        expect(onToggle.mock.calls.map((c) => c[0].key)).toEqual(['bar-1.pixel-0'])
        expect(pip(1).className).toContain('h-11')
        // The rest of the row rests at the board's 8px.
        expect(pip(2).className).toContain('h-2')
        fireEvent.pointerMove(pip(2), { ...touch, clientX: 12 })
        expect(onToggle.mock.calls.map((c) => c[0].key)).toEqual(['bar-1.pixel-0', 'bar-1.pixel-1'])
        expect(pip(2).className).toContain('h-11')
        expect(pip(1).className).not.toContain('h-11')
        fireEvent.pointerUp(pip(2), touch)
        expect(pip(2).className).not.toContain('h-11')
        // The click the release generates is the run's, not a second toggle.
        fireEvent.click(pip(2))
        expect(onToggle).toHaveBeenCalledTimes(2)
      } finally {
        vi.useRealTimers()
      }
    })

    it('reads a fixture tile `all` when every cell is selected, badged `4 of 4`, and its press releases the cells', () => {
      rigData = builtRig()
      const onToggle = vi.fn()
      draw(barCells.map((cell) => ({ type: 'fixture', key: cell.key, fixture: barFixture, element: cell })), { onToggle })
      const bar = screen.getByRole('button', { name: 'Bar L' })
      expect(bar).toHaveAttribute('aria-pressed', 'true')
      expect(bar.className).toContain('ring-primary/50')
      // Not `4`: cells-all and parent-selected are different selections with different presses.
      expect(within(bar).getByText('4 of 4')).toBeInTheDocument()
      for (let n = 1; n <= 4; n += 1) expect(pip(n)).toBeChecked()
      // From `all` the tile goes off — cell by cell, since toggling the parent would only add it.
      fireEvent.click(bar)
      expect(onToggle.mock.calls.map((c) => c[0].key)).toEqual(barCells.map((c) => c.key))
    })

    it('reads every pip checked while the parent is selected, badged `4`, and the press is the parent', () => {
      // The desk narrows the parent on a pip press under it, so a pip there must not read dark.
      rigData = builtRig()
      const onToggle = vi.fn()
      draw([{ type: 'fixture', key: 'bar-1', fixture: barFixture }], { onToggle })
      const bar = screen.getByRole('button', { name: 'Bar L' })
      expect(within(bar).getByText('4')).toBeInTheDocument()
      for (let n = 1; n <= 4; n += 1) expect(pip(n)).toBeChecked()
      fireEvent.click(bar)
      expect(onToggle).toHaveBeenCalledWith({ type: 'fixture', key: 'bar-1' })
    })

    it('is inert in Edit layout, where the tile’s whole face is the drag handle', () => {
      rigData = builtRig()
      const onToggle = vi.fn()
      draw([], { onToggle, editing: true })
      expect(screen.getByRole('group', { name: 'Bar L cells' }).className).toContain('pointer-events-none')
      fireEvent.pointerDown(pip(1), mouse)
      expect(onToggle).not.toHaveBeenCalled()
    })
  })

  it('clears through the handler, and draws no summary of its own — the lit tiles say it, and in Pads the pad row does (D17)', () => {
    const onClear = vi.fn()
    const selection = [
      { type: 'group', name: 'Front wash', group: groups[0] },
      { type: 'fixture', key: 'par-1', fixture: parFixture },
    ] as BuskingTarget[]
    draw(selection, { onClear })
    expect(document.querySelector('[data-rig-summary]')).toBeNull()
    expect(screen.queryByText('Front wash, PAR 1 · 7 heads')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onClear).toHaveBeenCalledTimes(1)
    cleanup()
    draw(selection, { focus: 'rig' })
    expect(document.querySelector('[data-rig-summary]')).toBeNull()
  })

  it('is one row: the label, Cells and its steps, the verbs, then the pill left-anchored, the gap, the host’s controls last (D13)', () => {
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls: <button type="button">Focus here</button> }, ['COLOUR'])
    const row = document.querySelector('[data-rig-row="desk"]') as HTMLElement
    expect(row).not.toBeNull()
    expect(document.querySelector('[data-rig-label-row]')).toBeNull()
    expect(document.querySelector('[data-rig-controls-row]')).toBeNull()
    // The `@container` is the band, an ancestor of the row and never the row itself: a query
    // container is the nearest ancestor container, so a floor class on the container element has
    // nothing to match (`BuskPageStrip.test.tsx` pins the same structure for the pad row).
    expect(row.parentElement!.className).toContain('@container')
    expect(row.className).not.toContain('@container')
    // The order, read off the DOM: verbs group then state group, the host's controls last.
    const order = [...row.querySelectorAll('button, [data-rig-family]')].map(
      (el) => el.getAttribute('aria-label') ?? el.textContent,
    )
    expect(order.slice(0, 3)).toEqual(['Cells: All', 'Previous along the rig', 'Next along the rig'])
    expect(order.slice(3, 7)).toEqual(['Spread…', 'Locate', 'Highlight', 'Clear'])
    // The selection's link badge is a button in Split — the toggle's linked face (desk-follow D11).
    expect(order.slice(7)).toEqual(['Colour', 'Following the desk selection', 'Focus here'])
    const state = row.querySelector('[data-rig-row-state]') as HTMLElement
    expect(within(state).getByText('Colour')).toBeInTheDocument()
    expect(state.lastElementChild).toHaveTextContent('Focus here')
    expect(state.className).toContain('flex-1')
    // Above the floor the row does not wrap on its own: the folds decide, not `flex-wrap`.
    expect(row.className).not.toMatch(/(^| )flex-wrap( |$)/)
  })

  it('draws an amber BLIND pill in the state group, right after the family pill, only while the programmer is blind — in Split and in Rig', () => {
    const controls = <button type="button">Focus here</button>
    // Not blind: nothing — absent means nothing to say, the family pill's own rule (D14).
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls }, ['COLOUR'])
    expect(document.querySelector('[data-busk-blind]')).toBeNull()
    cleanup()

    programmer.blind = true
    try {
      for (const focus of ['split', 'rig'] as const) {
        draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls, focus }, ['COLOUR'])
        const state = document.querySelector('[data-rig-row-state]') as HTMLElement
        const pill = state.querySelector('[data-busk-blind]') as HTMLElement
        expect(pill, focus).not.toBeNull()
        expect(pill).toHaveTextContent('Blind')
        expect(pill.title).toMatch(/not reaching the stage/)
        // A reporter, never a control.
        expect(pill.tagName).not.toBe('BUTTON')
        expect(pill.className).toContain('amber')
        // The glyph stays and the word folds on the pill's own rung, above every other (the row's
        // ladder has no room for it beside a mask pill); the pill itself may give
        // (`min-w-0 shrink`, never `shrink-0`) so at the extreme it truncates rather than pushing
        // the Focus control under the sheet.
        expect(pill.querySelector('svg')).not.toBeNull()
        expect(pill.querySelector('[data-busk-blind-word]')!.className).toContain(BLIND_WORD_CLASS)
        expect(pill.className).toMatch(/(^| )shrink( |$)/)
        expect(pill.className).not.toMatch(/(^| )shrink-0( |$)/)
        expect(pill.className).toContain('min-w-0')
        // After the family pill, before the host's controls.
        const order = [...state.querySelectorAll('button, [data-rig-family], [data-busk-blind]')].map(
          (el) => el.getAttribute('aria-label') ?? el.textContent,
        )
        // In Split the selection badge is the toggle; in Rig, which always follows, it is a mark (D11).
        expect(order, focus).toEqual(
          focus === 'split' ? ['Colour', 'Blind', 'Following the desk selection', 'Focus here'] : ['Colour', 'Blind', 'Focus here'],
        )
        cleanup()
      }
      // With no mask the pill stands alone at the front of the group.
      draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls }, null)
      const state = document.querySelector('[data-rig-row-state]') as HTMLElement
      expect(state.firstElementChild).toHaveAttribute('data-busk-blind')
      cleanup()
      // The compact board's row carries it too, its word on the compact rung.
      draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls, compact: true }, ['COLOUR'])
      const compactPill = document.querySelector('[data-rig-row="compact"] [data-busk-blind]') as HTMLElement
      expect(compactPill).not.toBeNull()
      expect(compactPill.querySelector('[data-busk-blind-word]')!.className).toContain(COMPACT_FOCUS_WORD_CLASS)
    } finally {
      programmer.blind = false
    }
  })

  it('draws the link badge while following and the desk chip once unlinked, right after the pill, the chip truncating first (desk-follow D8)', () => {
    const controls = <button type="button">Focus here</button>
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls }, ['COLOUR'])
    // Following: the badge, a glyph that never gives — not the pill, and not nothing.
    expect(screen.queryByRole('button', { name: /^Targets:/ })).toBeNull()
    const linked = document.querySelector('[data-rig-row-state]') as HTMLElement
    const badge = within(linked).getByRole('button', { name: 'Following the desk selection' })
    expect(badge.className).toContain('shrink-0')
    expect([...linked.querySelectorAll('button, [data-rig-family], [data-link-badge]')].map((el) => el.getAttribute('aria-label') ?? el.textContent)).toEqual([
      'Colour',
      'Following the desk selection',
      'Focus here',
    ])
    cleanup()
    unlinkFromDesk({ targets: [], families: null })
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls }, ['COLOUR'])
    const state = document.querySelector('[data-rig-row-state]') as HTMLElement
    const chip = within(state).getByRole('button', { name: 'Targets: This window' })
    expect(chip).toHaveAttribute('aria-pressed', 'false')
    // Right after the pill, before the gap and the host's controls.
    const order = [...state.querySelectorAll('button, [data-rig-family]')].map((el) => el.getAttribute('aria-label') ?? el.textContent)
    expect(order).toEqual(['Colour', 'Targets: This window', 'Focus here'])
    // The chip is the one control that may give: `min-w-0 shrink`, both words, since the pill's
    // own base is `shrink-0` and a bare `min-w-0` would leave it unshrinkable.
    expect(chip.className).toMatch(/(^| )shrink( |$)/)
    expect(chip.className).not.toMatch(/(^| )shrink-0( |$)/)
    expect(chip.className).toContain('min-w-0')
    // Its subject folds at the row's rung (D19) while the name stays whole.
    expect(chip.querySelector('[data-pill-subject]')!.className).toContain(CHIP_SUBJECT_CLASS)
    // The compact row's chip too, and its badge once linked again.
    cleanup()
    draw([], { compact: true })
    expect(screen.getByRole('button', { name: 'Targets: This window' })).toBeInTheDocument()
    cleanup()
    relinkToDesk()
    draw([], { compact: true })
    expect(screen.getByRole('button', { name: 'Following the desk selection' })).toBeInTheDocument()
  })

  it('draws Split and Rig with one DOM order, ending with the host’s controls, and has no Pads arm (D17)', () => {
    rigData = builtRig()
    const controls = <button type="button">Focus here</button>
    const positions: Record<string, string[]> = {}
    for (const focus of ['split', 'rig'] as const) {
      cleanup()
      draw([], { focus, controls })
      const row = document.querySelector('[data-rig-row="desk"]') as HTMLElement
      // The badge counted as itself whatever it is drawn as: a button in Split, a mark in Rig (D11).
      positions[focus] = [...row.querySelectorAll('button, [data-link-badge]')].map((el) => el.getAttribute('aria-label') ?? el.textContent ?? '')
      expect(row.querySelector('[data-rig-row-state]')!.lastElementChild).toHaveTextContent('Focus here')
      expect(document.querySelector(`[data-rig-band="${focus}"]`)).toHaveAttribute('data-focus', focus)
    }
    expect(positions.rig).toEqual(positions.split)
    // Rig always follows the desk selection (D2), so there the badge is a mark that says why, not a
    // press that would be undone at once; in Split it is the toggle (desk-follow D11).
    const rigBadge = screen.getByRole('img', { name: 'Following the desk selection' })
    expect(rigBadge).toHaveAttribute('title', 'Following the desk selection — Rig focus always follows')

    // Rig focus: no handle and no pill under the rows — the way back is the folded page strip's
    // chevron (`BuskPageStrip`), which the host draws, and the Focus control; there is no Pads
    // chevron either, since in Pads the band is not drawn and the pad row's Focus control is the way back.
    expect(screen.queryByRole('separator', { name: 'Rig height' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Show the rig rows again: Split' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Show the page again: Split' })).toBeNull()
    cleanup()
    draw([], { focus: 'split', controls })
    expect(screen.getByRole('button', { name: 'Following the desk selection' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('folds the verbs to their icons first, the Cells prefix and Focus words after, and the label last before the floor (D15, D20)', () => {
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }])
    for (const name of ['Spread…', 'Locate', 'Highlight', 'Clear']) {
      const button = screen.getByRole('button', { name })
      expect(button.querySelector('svg')).not.toBeNull()
      const word = [...button.querySelectorAll('span')].find((el) => el.textContent === name)!
      expect(word.className).toBe(VERB_WORD_CLASS)
    }
    const rung = (cls: string) => Number(cls.match(/@\[(\d+)px\]/)![1])
    // Verbs' words go first, the Focus words (and the Cells prefix, the chip's subject) after,
    // the label last, and the floor under all of them. *Edit layout*'s word goes with the verbs'.
    expect(rung(VERB_WORD_CLASS)).toBeGreaterThan(rung(FOCUS_WORD_CLASS))
    expect(rung(EDIT_WORD_CLASS)).toBe(rung(VERB_WORD_CLASS))
    expect(rung(CHIP_SUBJECT_CLASS)).toBe(rung(FOCUS_WORD_CLASS))
    expect(rung(FOCUS_WORD_CLASS)).toBeGreaterThan(rung(RIG_LABEL_CLASS))
    expect(rung(RIG_LABEL_CLASS)).toBeGreaterThan(RIG_ROW_FLOOR_PX)
    // The label folds to nothing — `hidden` below its rung — and the row keeps its handle.
    expect(RIG_LABEL_CLASS).toMatch(/^hidden @\[\d+px\]:block$/)
    const label = screen.getByText('Rig', { selector: 'div' })
    expect(label.className).toContain(RIG_LABEL_CLASS)
    expect(label.closest('[data-rig-row="desk"]')).not.toBeNull()
  })

  it('brings the words back under the floor as closed @min/@max ranges, so no rung depends on rule order (D19)', () => {
    const range = (cls: string) => {
      const m = cls.match(/@min-\[(\d+)px\]:@max-\[(\d+)px\]:(\S+)/)
      expect(m, cls).not.toBeNull()
      return { from: Number(m![1]), to: Number(m![2]), utility: m![3] }
    }
    for (const cls of [VERB_WORD_CLASS, EDIT_WORD_CLASS, FOCUS_WORD_CLASS, CHIP_SUBJECT_CLASS]) {
      const { from, to, utility } = range(cls)
      // A closed range under the floor: it overlaps nothing above it, and it ends at the floor.
      expect(to).toBe(RIG_ROW_FLOOR_PX)
      expect(from).toBeLessThan(to)
      expect(utility).toBe('inline')
      // …beside the plain rung above the floor, which is `hidden` below it.
      expect(cls).toMatch(/^hidden @\[\d+px\]:inline @min-/)
      expect(Number(cls.match(/@\[(\d+)px\]/)![1])).toBeGreaterThan(RIG_ROW_FLOOR_PX)
    }
    // On the verbs line the words return before the prefix does; on the state line *Edit layout*'s
    // word before the Focus words — the same order as above the floor, and each rung below it.
    expect(range(VERB_WORD_CLASS).from).toBeGreaterThan(range(FOCUS_WORD_CLASS).from)
    expect(range(EDIT_WORD_CLASS).from).toBeGreaterThan(range(FOCUS_WORD_CLASS).from)
    // The Cells prefix and the mode's short form swap on one rung, above and below the floor alike
    // — and under the floor the prefix's range ends exactly where the verbs' words return: the
    // fully worded line is wider than the floor, so the two never share it and neither wins by
    // rule order.
    draw([{ type: 'group', name: 'Front wash', group: groups[0] }])
    const trigger = screen.getByRole('button', { name: 'Cells: All' })
    const prefix = [...trigger.querySelectorAll('span')].find((el) => el.textContent === 'Cells: ')!
    const short = trigger.querySelector('[data-cells-mode]') as HTMLElement
    const prefixRange = range(prefix.className)
    expect(prefixRange.utility).toBe('inline')
    expect(prefixRange.from).toBeLessThan(prefixRange.to)
    expect(prefixRange.to).toBe(range(VERB_WORD_CLASS).from)
    expect(range(short.className)).toEqual({ ...prefixRange, utility: 'hidden' })
    // The label does not come back: under the floor the row is plainly the rig's.
    expect(RIG_LABEL_CLASS).not.toContain('@min-')
  })

  it('reads its mode word when the Cells control folds — never a bare glyph (D15)', () => {
    const onSubselect = vi.fn()
    draw([{ type: 'group', name: 'Front wash', group: groups[0] }], { onSubselect })
    const trigger = screen.getByRole('button', { name: 'Cells: All' })
    // Three spans: the prefix and the full word fold together, the short word is drawn in their place.
    const spans = [...trigger.querySelectorAll('span')]
    const prefix = spans.find((el) => el.textContent === 'Cells: ')!
    const full = spans.find((el) => el.textContent === 'All' && !el.hasAttribute('data-cells-mode'))!
    const short = trigger.querySelector('[data-cells-mode]') as HTMLElement
    const [focusRung] = FOCUS_WORD_CLASS.match(/@\[\d+px\]/)!
    expect(prefix.className).toContain('hidden')
    expect(prefix.className).toContain(`${focusRung}:inline`)
    expect(full.className).toBe(prefix.className)
    expect(short.className).toContain(`${focusRung}:hidden`)
    expect(short.className).not.toMatch(/(^| )hidden( |$)/)
    // The short form of every filter: the mode in one word.
    for (const [item, mode, word] of [
      ['Odd', 'ODD', 'Odd'],
      ['1st half', 'FIRST_HALF', '1st'],
      ['2nd half', 'SECOND_HALF', '2nd'],
      ['Masters only', 'MASTERS', 'Masters'],
      ['Invert', 'INVERT', 'Invert'],
    ] as const) {
      fireEvent.pointerDown(screen.getByRole('button', { name: /^Cells:/ }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitemradio', { name: item }))
      expect(onSubselect).toHaveBeenLastCalledWith(mode)
      expect(screen.getByRole('button', { name: `Cells: ${item}` }).querySelector('[data-cells-mode]')).toHaveTextContent(word)
    }
  })

  it('becomes two rows by design at the floor — the verbs on the first, the pill, chip and Focus on the second — not flex-wrap (D15)', () => {
    unlinkFromDesk({ targets: [], families: null })
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { controls: <button type="button">Focus here</button> }, ['COLOUR'])
    const row = document.querySelector('[data-rig-row="desk"]') as HTMLElement
    const verbs = row.querySelector('[data-rig-row-verbs]') as HTMLElement
    const state = row.querySelector('[data-rig-row-state]') as HTMLElement
    // The row wraps only below the floor, and the state group is what takes the second row whole.
    expect(TWO_ROWS_CLASS).toMatch(/^@max-\[\d+px\]:flex-wrap$/)
    expect(SECOND_ROW_CLASS).toMatch(/^@max-\[\d+px\]:basis-full$/)
    expect(TWO_ROWS_CLASS.match(/\d+/)![0]).toBe(SECOND_ROW_CLASS.match(/\d+/)![0])
    expect(Number(TWO_ROWS_CLASS.match(/\d+/)![0])).toBe(RIG_ROW_FLOOR_PX)
    expect(row.className).toContain(TWO_ROWS_CLASS)
    expect(state.className).toContain(SECOND_ROW_CLASS)
    // Under the floor the verbs group may wrap within its own line — the last resort for a band
    // narrower than the icons — and never above it.
    expect(verbs.className).toContain(FIRST_ROW_CLASS)
    expect(FIRST_ROW_CLASS.match(/\d+/)![0]).toBe(TWO_ROWS_CLASS.match(/\d+/)![0])
    // The floor sits under the last fold: the Cells prefix and Focus words go before the row breaks.
    expect(Number(TWO_ROWS_CLASS.match(/\d+/)![0])).toBeLessThan(Number(FOCUS_WORD_CLASS.match(/\d+/)![0]))
    expect(verbs.className).toContain('shrink-0')
    // What sits on each row.
    expect(within(verbs).getByRole('button', { name: 'Cells: All' })).toBeInTheDocument()
    expect(within(verbs).getByRole('button', { name: 'Clear' })).toBeInTheDocument()
    expect(within(state).getByRole('button', { name: 'Targets: This window' })).toBeInTheDocument()
    expect(within(state).getByText('Colour')).toBeInTheDocument()
    expect(within(state).getByText('Focus here')).toBeInTheDocument()
    expect(within(verbs).queryByText('Focus here')).toBeNull()
  })

  it('presses the Cells menu as one op per filter — all seven in one menu — and labels only the last press', () => {
    const onSubselect = vi.fn()
    draw([{ type: 'group', name: 'Front wash', group: groups[0] }], { onSubselect })
    const trigger = screen.getByRole('button', { name: 'Cells: All' })
    expect(trigger).toHaveTextContent('Cells: All')
    for (const [item, mode] of [
      ['Odd', 'ODD'],
      ['Even', 'EVEN'],
      ['1st half', 'FIRST_HALF'],
      ['2nd half', 'SECOND_HALF'],
      ['Invert', 'INVERT'],
      ['Masters only', 'MASTERS'],
      ['All', 'ALL'],
    ] as const) {
      fireEvent.pointerDown(screen.getByRole('button', { name: /^Cells:/ }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      // A radio item, since the seven are one choice; a step is not among them.
      expect(screen.queryByRole('menuitemradio', { name: /Next|Prev/ })).toBeNull()
      fireEvent.click(screen.getByRole('menuitemradio', { name: item }))
      expect(onSubselect).toHaveBeenLastCalledWith(mode)
      expect(screen.getByRole('button', { name: `Cells: ${item}` })).toBeInTheDocument()
    }
    expect(onSubselect).toHaveBeenCalledTimes(7)
    // Nothing on the face is derived from the selection: the desk keeps no sub-selection state.
    expect(screen.getByRole('button', { name: 'Cells: All' })).not.toHaveAttribute('aria-pressed')
  })

  it('steps the selection along the rig from two buttons beside the menu, which never become its label', () => {
    const onSubselect = vi.fn()
    draw([{ type: 'group', name: 'Front wash', group: groups[0] }], { onSubselect })
    fireEvent.click(screen.getByRole('button', { name: 'Next along the rig' }))
    expect(onSubselect).toHaveBeenLastCalledWith('NEXT')
    fireEvent.click(screen.getByRole('button', { name: 'Previous along the rig' }))
    expect(onSubselect).toHaveBeenLastCalledWith('PREV')
    // A step moves the selection; it is not a mode the menu remembers.
    expect(screen.getByRole('button', { name: 'Cells: All' })).toBeInTheDocument()
  })

  it('draws the family pill, and Spread… opens the side sheet’s Spread tab', () => {
    draw([{ type: 'group', name: 'Front wash', group: groups[0] }], {}, ['COLOUR'])
    expect(screen.getByText('Colour')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Targets:/ })).toBeNull()
    expect(getBuskSheet()).not.toBe('spread')
    fireEvent.click(screen.getByRole('button', { name: 'Spread…' }))
    // The verb writes the sheet fact and nothing else: the selection is untouched.
    expect(getBuskSheet()).toBe('spread')
  })

  it('offers Spread… in the compact verbs menu too, opening the same tab', () => {
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { compact: true })
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Selection verbs' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    fireEvent.click(screen.getByRole('menuitem', { name: 'Spread…' }))
    expect(getBuskSheet()).toBe('spread')
  })

  it('folds the Cells menu’s seven filters and the two steps into the compact verbs menu below md', () => {
    const onSubselect = vi.fn()
    draw([{ type: 'fixture', key: 'par-1', fixture: parFixture }], { compact: true, onSubselect })
    expect(screen.queryByRole('button', { name: /^Cells:/ })).not.toBeInTheDocument()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Selection verbs' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'All', 'Odd', 'Even', '1st half', '2nd half', 'Invert', 'Masters only', 'Previous along the rig', 'Next along the rig', 'Spread…', 'Locate', 'Clear',
    ])
    fireEvent.click(screen.getByRole('menuitem', { name: 'Invert' }))
    expect(onSubselect).toHaveBeenCalledWith('INVERT')
  })

  it('draws the desk board’s Split as a scroller at the window’s height, every line mounted, the handle reporting it', () => {
    rigData = builtRig()
    stubLayout()
    setBuskRigHeight(100)
    draw()
    // Every line is in the DOM — the rows scroll under the bar — and the grid is the scroller.
    expect(screen.getByText('Four')).toBeInTheDocument()
    expect(rowsGrid().className).toContain('overflow-y-auto')
    expect(rowsGrid().style.height).toBe('100px')
    // The old ± buttons and the "3 of 4 rows" caption are gone: the height is the handle's own value.
    expect(handle()).toHaveAttribute('aria-valuenow', '100')
    expect(handle()).toHaveAttribute('aria-valuemin', '56')
    // The ceiling: the column less its fixed chrome, less the page's 120 — never the page body's
    // remainder, which a rig taller than the column has already collapsed.
    expect(handle()).toHaveAttribute('aria-valuemax', '440')
    expect(screen.queryByRole('button', { name: 'Show one row more' })).not.toBeInTheDocument()
    expect(screen.queryByText(/of 4 rows/)).not.toBeInTheDocument()
  })

  it('draws the surface’s default whole lines while the window has not chosen — three on a desk screen — measured, not stored', () => {
    rigData = builtRig()
    stubLayout()
    draw()
    // Three lines of 40 from the grid's top: the region's bottom sits on the third line's.
    expect(rowsGrid().style.height).toBe('120px')
    expect(handle()).toHaveAttribute('aria-valuenow', '120')
    expect(getBuskRigHeight()).toBeNull()
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBeNull()
  })

  it('steps the keys between line edges, to the ceiling past the last and the floor above the first — never changing focus', () => {
    rigData = builtRig()
    stubLayout()
    setBuskRigHeight(100)
    draw()
    fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    expect(handle()).toHaveAttribute('aria-valuenow', '120')
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBe('120')
    fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    expect(handle()).toHaveAttribute('aria-valuenow', '160')
    // Past the last line: the ceiling, which is how the page is made smaller. Not Rig focus.
    fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    expect(handle()).toHaveAttribute('aria-valuenow', '440')
    expect(getBuskFocus()).toBe('split')
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    expect(handle()).toHaveAttribute('aria-valuenow', '160')
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    // The first line's edge (40) is under the floor, so the floor it is — and not Pads focus.
    expect(handle()).toHaveAttribute('aria-valuenow', '56')
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    expect(handle()).toHaveAttribute('aria-valuenow', '56')
    expect(getBuskFocus()).toBe('split')
  })

  it('drags live — snapping to a line’s edge within 10px as it passes one, following the pointer otherwise — and writes the fact on release', () => {
    rigData = builtRig()
    stubLayout()
    draw()
    // The stub reports the grid 160 tall, so that is where the drag starts from.
    fireEvent.pointerDown(handle(), { button: 0, pointerId: 1, clientY: 300 })
    expect(handle()).toHaveAttribute('data-rig-rows-dragging', 'true')
    // 45 up is 115: within 10 of the third line's edge at 120, so it snaps there — while held.
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 255 })
    expect(rowsGrid().style.height).toBe('120px')
    // 60 up is 100: no edge within reach, so the bar rests between two lines.
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 240 })
    expect(rowsGrid().style.height).toBe('100px')
    // Far above the first line is the floor, far below the last is the ceiling — and neither is a
    // change of focus.
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 0 })
    expect(rowsGrid().style.height).toBe('56px')
    expect(getBuskFocus()).toBe('split')
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 900 })
    expect(rowsGrid().style.height).toBe('440px')
    expect(getBuskFocus()).toBe('split')
    // Nothing is written until the release, which writes what the drag left.
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBeNull()
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 240 })
    fireEvent.pointerUp(window, { pointerId: 1, clientY: 240 })
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBe('100')
    expect(handle()).toHaveAttribute('aria-valuenow', '100')
    expect(handle()).not.toHaveAttribute('data-rig-rows-dragging')
    expect(getBuskFocus()).toBe('split')
  })

  it('writes nothing for a press that moved nothing, and two still presses on the grip restore the default', () => {
    rigData = builtRig()
    stubLayout()
    setBuskRigHeight(100)
    draw()
    // The clock the double press is read against — an event's `timeStamp` cannot be set from a test.
    const clock = vi.spyOn(Date, 'now')
    const press = (at: number, moveTo?: number) => {
      fireEvent.pointerDown(handle(), { button: 0, pointerId: 1, clientY: 300 })
      if (moveTo != null) fireEvent.pointerMove(window, { pointerId: 1, clientY: moveTo })
      clock.mockReturnValue(at)
      fireEvent.pointerUp(window, { pointerId: 1, clientY: moveTo ?? 300 })
    }
    // One still press: the wish stands, and nothing is written for it — not even a `pointermove`
    // at the press's own point, which would otherwise snap the grip onto an edge within reach.
    press(1000, 300)
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBe('100')
    expect(getBuskRigHeight()).toBe(100)
    expect(rowsGrid().style.height).toBe('100px')
    // A press that moved is not half of a double press: the next still press starts the count.
    press(1100, 240)
    expect(getBuskRigHeight()).toBe(100)
    press(1200)
    expect(getBuskRigHeight()).toBe(100)
    // Two still presses within 400ms — read off the presses, not off `dblclick`, since the press
    // cancels `pointerdown` and whether a browser still synthesises the click pair is its call.
    press(1500)
    expect(getBuskRigHeight()).toBeNull()
    expect(handle()).toHaveAttribute('aria-valuenow', '120')
    expect(rowsGrid().style.height).toBe('120px')
    // Consumed: the next still press is a first again, and one 800ms after it is too.
    setBuskRigHeight(100)
    press(1700)
    expect(getBuskRigHeight()).toBe(100)
    press(2500)
    expect(getBuskRigHeight()).toBe(100)
  })

  it('clamps a stored height taller than the body to the ceiling on read, keeping the wish', () => {
    rigData = builtRig()
    stubLayout({ columnHeight: 516 })
    setBuskRigHeight(1000)
    draw()
    // 516 − 100 − 16 − 40 − 120: the page keeps its minimum, and the window shows its wish again when it grows.
    expect(handle()).toHaveAttribute('aria-valuenow', '240')
    expect(rowsGrid().style.height).toBe('240px')
    expect(getBuskRigHeight()).toBe(1000)
  })

  it('cancels a drag when the shape changes under it — another window’s focus write mid-drag — clearing the height and re-arming nothing', () => {
    rigData = builtRig()
    stubLayout()
    setBuskRigHeight(100)
    const { rerender } = draw()
    fireEvent.pointerDown(handle(), { button: 0, pointerId: 1, clientY: 300 })
    fireEvent.pointerMove(window, { pointerId: 1, clientY: 240 })
    expect(rowsGrid().style.height).toBe('100px')
    // Another window sends `focus: 'rig'`: the band re-renders in Rig with the grid free to flex
    // — no fixed height left on it — and there is no handle, so the drag in flight is cancelled.
    rerender(bandFor([], { focus: 'rig' }, null))
    expect(rowsGrid().style.height).toBe('')
    expect(screen.queryByRole('separator', { name: 'Rig height' })).toBeNull()
    // Back in Split with the pointer released elsewhere: nothing writes the height.
    rerender(bandFor([], { focus: 'split' }, null))
    fireEvent.pointerUp(window, { pointerId: 1, clientY: 105 })
    expect(window.sessionStorage.getItem('busk.rigHeight')).toBe('100')
    expect(getBuskFocus()).toBe('split')
  })

  it('snaps a height to the nearest line edge within reach and clamps it, and steps between edges — the pure rules', () => {
    const edges = [40, 80, 120, 160]
    expect(snapRigHeight(115, edges, 56, 440)).toBe(120)
    expect(snapRigHeight(100, edges, 56, 440)).toBe(100)
    expect(snapRigHeight(70, edges, 56, 440)).toBe(80)
    expect(snapRigHeight(45, edges, 56, 440)).toBe(56)
    expect(snapRigHeight(900, edges, 56, 440)).toBe(440)
    // A ceiling under the floor is the floor: a window too short for both keeps the rig readable.
    expect(snapRigHeight(100, edges, 56, 20)).toBe(56)
    expect(snapRigHeight(100, [], 56, 440)).toBe(100)
    expect(stepRigHeight(100, edges, 1, 56, 440)).toBe(120)
    expect(stepRigHeight(120, edges, 1, 56, 440)).toBe(160)
    expect(stepRigHeight(160, edges, 1, 56, 440)).toBe(440)
    expect(stepRigHeight(100, edges, -1, 56, 440)).toBe(80)
    expect(stepRigHeight(80, edges, -1, 56, 440)).toBe(56)
    expect(stepRigHeight(56, edges, -1, 56, 440)).toBe(56)
  })

  it('draws the handle for a one-line rig too, and no handle in edit mode', () => {
    rigData = { rows: [{ id: 1, uuid: 'r1', name: 'Wash', tiles: [tile({ kind: 'FIXTURE', patch: parPatch })] }] }
    stubLayout()
    draw()
    expect(handle()).toBeInTheDocument()
    cleanup()
    draw([], { editing: true })
    expect(screen.queryByRole('separator', { name: 'Rig height' })).toBeNull()
    expect(rowsGrid().style.height).toBe('')
  })

  it('lays rows out on a twelve-track grid by their width, two half-width rows sharing a line, and flows a row’s tiles by its flow', () => {
    const rig = builtRig()
    rig.rows![0].width = 6
    rig.rows![1].width = 6
    rig.rows![1].flow = 'WRAP'
    rig.rows![2].flow = 'COLUMN'
    rigData = rig
    stubLayout()
    draw()
    // Wash and Cells share the first line, Three is the second, Four the third — every line mounted.
    expect(screen.getByText('Wash')).toBeInTheDocument()
    expect(screen.getByText('Cells')).toBeInTheDocument()
    expect(screen.getByText('Three')).toBeInTheDocument()
    expect(screen.getByText('Four')).toBeInTheDocument()
    const lines = [...document.querySelectorAll('[data-rig-line]')]
    expect(lines.map((el) => el.getAttribute('data-rig-line'))).toEqual(['0', '0', '1', '2'])
    expect(lines.map((el) => (el as HTMLElement).style.gridColumn)).toEqual(['span 6 / span 6', 'span 6 / span 6', 'span 12 / span 12', 'span 12 / span 12'])
    expect(document.querySelector('[data-rig-rows]')!.className).toContain('grid-cols-12')
    const bodies = [...document.querySelectorAll('[data-rig-row-body]')].map((b) => b.getAttribute('data-rig-row-body'))
    expect(bodies).toEqual(['scroll', 'wrap', 'column', 'scroll'])
    expect(document.querySelector('[data-rig-row-body="wrap"]')!.className).toContain('flex-wrap')
    expect(document.querySelector('[data-rig-row-body="column"]')!.className).toContain('flex-col')
    expect(document.querySelector('[data-rig-row-body="scroll"]')!.className).toContain('overflow-x-auto')
    // Two rows share a line, so the unit the handle snaps to is the line: the default three lines
    // of the stub's 40 put the region's bottom on the third line's edge.
    expect(handle()).toHaveAttribute('aria-valuenow', '120')
  })

  it('stacks every row two tiles across, scrolling with the band, in Rig focus below md — never a sideways row', () => {
    rigData = builtRig()
    draw([], { focus: 'rig', compact: true, stackRows: true })
    const bodies = [...document.querySelectorAll('[data-rig-row-body]')]
    expect(bodies).toHaveLength(4)
    expect(bodies.every((body) => body.getAttribute('data-rig-row-body') === 'stacked')).toBe(true)
    expect(bodies[0].className).toContain('grid-cols-2')
    expect(bodies[0].className).not.toContain('overflow-x-auto')
    // The rows are the scroller, vertically — not the band, whose one row carries the way back.
    expect(document.querySelector('[data-rig-rows]')!.className).toContain('overflow-y-auto')
    expect(document.querySelector('[data-rig-band="rig"]')!.className).not.toContain('overflow-y-auto')
    cleanup()
    // Split below md keeps the one sideways row, and the desk board's Rig focus keeps its rows.
    draw([], { compact: true, stackRows: true })
    expect(document.querySelector('[data-rig-row-body]')).toHaveAttribute('data-rig-row-body', 'scroll')
    cleanup()
    // The short board is compact too — 48px tiles, the row chip — but wider than `md`, so its Rig
    // focus keeps the sideways rows: stacking is the narrow board's alone.
    draw([], { focus: 'rig', compact: true })
    expect([...document.querySelectorAll('[data-rig-row-body]')].every((b) => b.getAttribute('data-rig-row-body') === 'scroll')).toBe(true)
    cleanup()
    draw([], { focus: 'rig' })
    expect([...document.querySelectorAll('[data-rig-row-body]')].every((b) => b.getAttribute('data-rig-row-body') === 'scroll')).toBe(true)
  })

  it('shows every row with no handle in Rig focus, filling the body — below md too', () => {
    rigData = builtRig()
    draw([], { focus: 'rig' })
    expect(screen.queryByRole('separator', { name: 'Rig height' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Show the page again: Split' })).toBeNull()
    expect(screen.getByText('Four')).toBeInTheDocument()
    expect(screen.queryByText(/of 4 rows/)).not.toBeInTheDocument()
    expect(document.querySelector('[data-rig-band="rig"]')!.className).toContain('flex-1')
    expect(document.querySelector('[data-rig-rows]')!.className).toContain('overflow-y-auto')

    cleanup()
    draw([], { focus: 'rig', compact: true })
    expect(screen.getByText('Four')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Row:/ })).not.toBeInTheDocument()
  })

  it('is one row with a row chip below md, the verbs in a menu', () => {
    rigData = builtRig()
    draw([], { compact: true })
    expect(screen.getByRole('button', { name: 'Row: Wash' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Front wash' })).toBeInTheDocument()
    expect(screen.queryByText('Cells')).not.toBeInTheDocument()
    expect(screen.queryByText('Bar L 1–2')).not.toBeInTheDocument()
    expect(screen.queryByText(/of 4 rows/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Selection verbs' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Locate' })).not.toBeInTheDocument()
  })

  describe('in Edit layout', () => {
    it('offers a name field, a cross per tile and a menu on every tile, with the cell modes on a multi-head tile only', () => {
      rigData = builtRig()
      draw([], { editing: true })
      expect(screen.getAllByLabelText('Row name')).toHaveLength(4)
      expect(screen.getByLabelText('Remove Front wash from the rig')).toBeInTheDocument()
      // Both controls sit inside the tile's corner — nothing hangs past the row body's clip or into
      // the next tile — the cross last, so it reads as the tile's own.
      const controls = screen.getByLabelText('Remove Front wash from the rig').closest('[data-rig-tile-controls]')!
      expect(controls.className).toContain('top-1')
      expect(controls.className).toContain('right-1')
      expect(controls.lastElementChild).toBe(screen.getByLabelText('Remove Front wash from the rig'))
      expect(screen.getByLabelText('Options for Bar L')).toBeInTheDocument()
      expect(screen.getAllByLabelText('Options for PAR 1')).toHaveLength(2)
      fireEvent.pointerDown(screen.getAllByLabelText('Options for PAR 1')[0], { button: 0, ctrlKey: false, pointerType: 'mouse' })
      expect(screen.queryByRole('menuitemradio', { name: 'Whole fixture only' })).not.toBeInTheDocument()
      expect(screen.getByRole('menuitem', { name: 'Rename tile…' })).toBeInTheDocument()
      fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
      fireEvent.pointerDown(screen.getByLabelText('Options for Bar L'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      expect(screen.getByRole('menuitemradio', { name: 'Whole fixture only' })).toBeInTheDocument()
      expect(screen.getByRole('menuitem', { name: 'Rename tile…' })).toBeInTheDocument()
      fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
      expect(screen.getByRole('button', { name: 'Show every target' })).toBeEnabled()
      // The play verbs step aside while editing: tiles are drag handles, not toggles.
      expect(screen.queryByRole('button', { name: 'Cells: All' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument()
    })

    it('sets a row’s width and flow from its menu — the bank’s two facts, on the row — and removes the row from it', () => {
      rigData = builtRig()
      draw([], { editing: true })
      fireEvent.pointerDown(screen.getByLabelText('Options for row Wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('button', { name: '½' }))
      let op = commit.mock.calls.at(-1)![0] as (rig: BuskRig) => BuskRig
      expect(op(builtRig()).rows![0].width).toBe(6)
      fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
      fireEvent.pointerDown(screen.getByLabelText('Options for row Wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      expect(screen.getByRole('menuitemradio', { name: 'Flow: Scroll' })).toHaveAttribute('aria-checked', 'true')
      fireEvent.click(screen.getByRole('menuitemradio', { name: 'Flow: Wrap' }))
      op = commit.mock.calls.at(-1)![0] as (rig: BuskRig) => BuskRig
      expect(op(builtRig()).rows![0].flow).toBe('WRAP')
      fireEvent.pointerDown(screen.getByLabelText('Options for row Wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Remove row' }))
      op = commit.mock.calls.at(-1)![0] as (rig: BuskRig) => BuskRig
      expect(op(builtRig()).rows!.map((row) => row.name)).toEqual(['Cells', 'Three', 'Four'])
    })

    it('renames a tile in place — Rename tile… writes the tile’s label, and the record’s own name clears it', () => {
      rigData = builtRig()
      draw([], { editing: true })
      fireEvent.pointerDown(screen.getByLabelText('Options for Front wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rename tile…' }))
      const field = screen.getByLabelText('Tile name') as HTMLInputElement
      // Seeded with the name shown, so a rename edits rather than retypes.
      expect(field.value).toBe('Front wash')
      expect(document.activeElement).toBe(field)
      fireEvent.change(field, { target: { value: 'Wash' } })
      fireEvent.keyDown(field, { key: 'Enter' })
      expect(commit).toHaveBeenCalledTimes(1)
      const op = commit.mock.calls[0][0] as (rig: BuskRig) => BuskRig
      expect(op(builtRig()).rows![0].tiles![0].label).toBe('Wash')
      // The field is gone with the gesture, and the tile is a tile again.
      expect(screen.queryByLabelText('Tile name')).not.toBeInTheDocument()
      expect(screen.getByLabelText('Options for Front wash')).toBeInTheDocument()

      // A labelled tile shows its label, and saving the record's own name back clears it.
      cleanup()
      commit.mockClear()
      const labelled = builtRig()
      labelled.rows![0].tiles![0].label = 'Wash'
      rigData = labelled
      draw([], { editing: true })
      expect(screen.getByRole('button', { name: 'Wash' })).toBeInTheDocument()
      fireEvent.pointerDown(screen.getByLabelText('Options for Wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rename tile…' }))
      const again = screen.getByLabelText('Tile name') as HTMLInputElement
      expect(again.value).toBe('Wash')
      expect(again.placeholder).toBe('Front wash')
      fireEvent.change(again, { target: { value: 'Front wash' } })
      fireEvent.keyDown(again, { key: 'Enter' })
      const clear = commit.mock.calls[0][0] as (rig: BuskRig) => BuskRig
      expect(clear(labelled).rows![0].tiles![0].label).toBeNull()
    })

    it('seeds a cell tile’s rename with the name shown, and a per-cell tile’s with the fixture’s name the cells compose on', () => {
      rigData = builtRig()
      draw([], { editing: true })
      // The single-cell tile: its label replaces `Bar L · Cell 3`, so that is what the field edits.
      // (The PER_CELL tile draws a `Bar L · Cell 3` of its own, earlier in the row; the cell tile is last.)
      fireEvent.pointerDown(screen.getAllByLabelText('Options for Bar L · Cell 3').at(-1)!, { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rename tile…' }))
      let field = screen.getByLabelText('Tile name') as HTMLInputElement
      expect(field.value).toBe('Bar L · Cell 3')
      expect(field.placeholder).toBe('Bar L · Cell 3')
      fireEvent.change(field, { target: { value: 'Centre' } })
      fireEvent.keyDown(field, { key: 'Enter' })
      let op = commit.mock.calls[0][0] as (rig: BuskRig) => BuskRig
      expect(op(builtRig()).rows![1].tiles![2].label).toBe('Centre')
      commit.mockClear()

      // A PER_CELL tile draws four tiles from one stored tile; the label composes under each cell
      // name, so every one of the four seeds `Bar L` and writes the one stored label.
      fireEvent.pointerDown(screen.getAllByLabelText('Options for Bar L · Cell 2')[0], { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rename tile…' }))
      field = screen.getByLabelText('Tile name') as HTMLInputElement
      expect(field.value).toBe('Bar L')
      expect(field.placeholder).toBe('Bar L')
      fireEvent.change(field, { target: { value: 'Left' } })
      fireEvent.keyDown(field, { key: 'Enter' })
      op = commit.mock.calls[0][0] as (rig: BuskRig) => BuskRig
      const next = op(builtRig())
      expect(next.rows![1].tiles![0].label).toBe('Left')
      expect(next.rows![1].tiles![1].label).toBeUndefined()
    })

    it('reverts a rename on Escape and writes nothing — the field is focused, so its blur is real', () => {
      rigData = builtRig()
      draw([], { editing: true })
      fireEvent.pointerDown(screen.getByLabelText('Options for Front wash'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
      fireEvent.click(screen.getByRole('menuitem', { name: 'Rename tile…' }))
      const field = screen.getByLabelText('Tile name') as HTMLInputElement
      expect(document.activeElement).toBe(field)
      fireEvent.change(field, { target: { value: 'Nope' } })
      fireEvent.keyDown(field, { key: 'Escape' })
      expect(commit).not.toHaveBeenCalled()
      expect(screen.queryByLabelText('Tile name')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Front wash' })).toBeInTheDocument()
    })

    it('registers every drawn cell of one stored tile under its own drag id', () => {
      rigData = builtRig()
      draw([], { editing: true })
      // dnd-kit stamps `aria-describedby="DndDescribedBy-<n>"` per draggable; the four PER_CELL
      // cells, the two runs and the cell tile must be seven registrations, not one shared id.
      const cells = screen.getAllByRole('button', { name: /^Bar L · Cell \d$/ })
      const runs = screen.getAllByRole('button', { name: /^Bar L \d–\d$/ })
      const ids = new Set([...cells, ...runs].map((b) => b.closest('[data-rig-tile-id]')?.getAttribute('data-rig-tile-id')))
      expect(ids.size).toBe(cells.length + runs.length)
    })

    it('commits a tile removal as an op over the rig', () => {
      rigData = builtRig()
      draw([], { editing: true })
      fireEvent.click(screen.getByLabelText('Remove Front wash from the rig'))
      expect(commit).toHaveBeenCalledTimes(1)
      const op = commit.mock.calls[0][0] as (rig: BuskRig) => BuskRig
      const next = op(builtRig())
      expect(next.rows![0].tiles!.map((t) => t.group?.name ?? t.patch?.name)).toEqual(['Bar L'])
    })

    it('draws the fallback dimmed with nothing to move, and only the new-row zone to drop on', () => {
      draw([], { editing: true })
      expect(screen.getByText(/Showing every target/)).toBeInTheDocument()
      expect(screen.queryAllByLabelText('Row name')).toHaveLength(0)
      expect(screen.queryByLabelText(/Remove .* from the rig/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Show every target' })).toBeDisabled()
      expect(document.querySelector('[data-rig-new-row]')).not.toBeNull()
    })
  })
})

describe('the folded rig strip', () => {
  it('keeps the summary, the pill and the desk chip — the chip only while unlinked — labelled Pads, with no chevron, on the band’s own row', () => {
    const map = new Map<string, BuskingTarget>([
      ['group:Front wash', { type: 'group', name: 'Front wash', group: group('Front wash', 6) }],
    ])
    render(<RigStrip selectedTargets={map} families={['COLOUR']} controls={<span>Focus here</span>} />)
    expect(screen.getByText('Front wash · 6 heads')).toBeInTheDocument()
    // In Pads this is the body's top row, as the desk board's pad row is: it says so.
    expect(screen.getByText('Pads')).toBeInTheDocument()
    expect(screen.queryByText('Rig')).toBeNull()
    // The host's controls — the Focus control — sit at the strip's end, as on the band's one row,
    // with no chevron before them: the Focus control is the one way between the shapes, and a
    // chevron here pushed it along on this strip only.
    expect(screen.getByText('Focus here')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Unfold the rig' })).toBeNull()
    // The band's own box and row, so the row sits where the band's does.
    const strip = document.querySelector('[data-rig-strip]') as HTMLElement
    expect(strip.className).toContain('pt-2.5')
    expect(strip.className).toContain('pb-2')
    expect(strip.firstElementChild!.className).toContain('min-h-7')
    expect(screen.getByText('Colour')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Targets:/ })).toBeNull()
    // No blind pill while the programmer is not blind…
    expect(document.querySelector('[data-busk-blind]')).toBeNull()
    cleanup()
    // …and one after the family pill while it is: off the desk board this strip (and the short
    // board's merged row, which mounts the same pieces) is the only rig chrome in Pads.
    programmer.blind = true
    try {
      render(<RigStrip selectedTargets={map} families={['COLOUR']} />)
      const pill = document.querySelector('[data-busk-blind]') as HTMLElement
      expect(pill).toHaveTextContent('Blind')
      expect(pill.previousElementSibling).toHaveTextContent('Colour')
      expect(pill.querySelector('[data-busk-blind-word]')!.className).toContain(COMPACT_FOCUS_WORD_CLASS)
    } finally {
      programmer.blind = false
    }
    // Following, the badge (desk-follow D8); unlinked, the chip.
    expect(screen.getByRole('img', { name: 'Following the desk selection' })).toBeInTheDocument()
    cleanup()
    unlinkFromDesk({ targets: [], families: null })
    render(<RigStrip selectedTargets={map} families={['COLOUR']} />)
    expect(screen.getByRole('button', { name: 'Targets: This window' })).toBeInTheDocument()
  })
})
