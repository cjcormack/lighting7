// @vitest-environment jsdom
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The applied tables (venue-visit plan S14): each lists exactly what the rail lists for the same
 * programmer state, every row action reaches the rail's own mutation, and sort, filter and chip
 * ride the query string.
 */
const mocks = vi.hoisted(() => ({
  effects: [] as ActiveEffect[],
  layers: [] as ProgrammerLayer[],
  looks: [] as LookSummary[],
  templates: [] as TemplateSummary[],
  blind: false,
  removeLayer: vi.fn(),
  moveLayer: vi.fn(),
  patchLayer: vi.fn(),
  detachLayer: vi.fn(),
  pickUp: vi.fn(),
  pauseFx: vi.fn(),
  resumeFx: vi.fn(),
  removeFx: vi.fn(),
  removeGroupFx: vi.fn(),
  pauseGroupFx: vi.fn(),
  resumeGroupFx: vi.fn(),
}))

const mutation = (spy: (arg: unknown) => void) => () => [
  (arg: unknown) => {
    spy(arg)
    return { unwrap: () => Promise.resolve() }
  },
]

vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: mocks.layers }),
  useProgrammerAppliedQuery: () => ({ data: undefined }),
  programmerRemoveLayer: mocks.removeLayer,
  programmerMoveLayer: mocks.moveLayer,
  programmerPatchLayer: mocks.patchLayer,
  programmerDetachLayer: mocks.detachLayer,
}))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: mocks.effects }),
  useEffectLibraryQuery: () => ({ data: [] }),
  usePauseFxMutation: mutation(mocks.pauseFx),
  useResumeFxMutation: mutation(mocks.resumeFx),
  useRemoveFxMutation: mutation(mocks.removeFx),
}))
vi.mock('@/store/groups', () => ({
  usePauseGroupFxMutation: mutation(mocks.pauseGroupFx),
  useResumeGroupFxMutation: mutation(mocks.resumeGroupFx),
  useRemoveGroupFxMutation: mutation(mocks.removeGroupFx),
}))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: mocks.looks }) }))
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: mocks.templates }) }))
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: (_: unknown, opts: { selectFromResult: (r: { data: unknown }) => unknown }) =>
    opts.selectFromResult({
      data: [
        { uuid: 'm1', index: 1, name: 'Master', bpm: 120 },
        { uuid: 'm2', index: 2, name: 'Wash', bpm: 96 },
      ],
    }),
}))
vi.mock('@/store/projects', () => ({ useProjectQuery: () => ({ data: { id: 1, name: 'Sleeping Beauty' } }) }))
vi.mock('@/store/hand', () => ({ handPickUp: (...a: unknown[]) => mocks.pickUp(...a) }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => mocks.blind }))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: [] }) }))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: ({ currentPage }: { currentPage: string }) => <span>{currentPage}</span> }))
vi.mock('@/components/fx/SpeedMasterChip', () => ({ SpeedMasterChip: () => null }))
vi.mock('@/components/fx/FxEditorPopover', () => ({
  FxEditorPopover: ({ effect }: { effect: ActiveEffect }) => <div data-testid="fx-editor">{effect.id}</div>,
}))
vi.mock('@/components/programmer/NewTemplateFromEffectSheet', () => ({
  NewTemplateFromEffectSheet: ({ open, effect }: { open: boolean; effect: ActiveEffect | null }) =>
    open ? <div data-testid="save-sheet">{effect?.id}</div> : null,
}))
vi.mock('@/components/looks/LookDetailSheet', () => ({
  LookDetailSheet: ({ open, look }: { open: boolean; look: LookSummary | null }) =>
    open ? <div data-testid="look-sheet">{look?.name}</div> : null,
}))

const { ProgrammerFxList } = await import('@/components/programmer/ProgrammerFxList')
const { ProgrammerLookStack } = await import('@/components/programmer/ProgrammerLookStack')
const { ProgrammerApplied } = await import('@/routes/ProgrammerApplied')
const { resetAppliedTableStore, setAppliedTable } = await import('@/lib/appliedTables')

function effect(id: number, over: Partial<ActiveEffect> = {}): ActiveEffect {
  return {
    id,
    effectType: `Effect ${id}`,
    targetKey: `par-${id}`,
    propertyName: 'dimmer',
    beatDivision: 1,
    blendMode: 'OVERRIDE',
    isRunning: true,
    phaseOffset: 0,
    currentPhase: 0,
    parameters: {},
    isGroupTarget: false,
    distributionStrategy: null,
    elementMode: null,
    elementFilter: null,
    stepTiming: false,
    lookId: null,
    templateId: null,
    sourceName: null,
    programmerLayerId: null,
    cueId: null,
    timingSource: 'BEAT',
    programmerOwned: true,
    intensityMultiplier: 1,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}

function layer(layerId: number, kind: 'LOOK' | 'TEMPLATE', id: number, name: string, over: Partial<ProgrammerLayer> = {}): ProgrammerLayer {
  return {
    layerId,
    source: { kind, id, uuid: `u-${kind}-${id}`, name },
    sortOrder: layerId,
    enabled: true,
    targets: [{ type: 'group', key: 'front' }],
    propertyMask: null,
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
    ...over,
  }
}

function look(id: number, name: string): LookSummary {
  return {
    id,
    uuid: `u-LOOK-${id}`,
    name,
    notes: null,
    families: ['COLOUR'],
    rowCount: 1,
    effectCount: 0,
    targetCount: 1,
    hasDeferredEffects: false,
    preview: [],
    layerCount: 0,
    buskPageCount: 0,
  }
}

function template(id: number, name: string, over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id,
    uuid: `u-TEMPLATE-${id}`,
    name,
    family: 'COLOUR',
    kind: 'value',
    isGeneric: true,
    rows: [{ targetType: null, targetKey: null, propertyName: 'colour', value: '#ff9d4a;policy=extract' }],
    ...over,
  } as TemplateSummary
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function drawRoute(search: string) {
  return render(
    <MemoryRouter initialEntries={[`/projects/1/programmer/applied${search}`]}>
      <Routes>
        <Route path="/projects/:projectId/programmer/applied" element={<ProgrammerApplied />} />
      </Routes>
      <Where />
    </MemoryRouter>,
  )
}

function drawRail(node: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={['/projects/1/programmer']}>
      <Routes>
        <Route path="/projects/:projectId/programmer" element={node} />
      </Routes>
    </MemoryRouter>,
  )
}

const where = () => screen.getByTestId('where').textContent ?? ''
const tableRows = () => [...document.querySelectorAll<HTMLElement>('[data-row-id]')]
const rowOf = (id: string) => document.querySelector<HTMLElement>(`[data-row-id="${id}"]`)!

/** Radix opens a menu on pointerdown. */
function openMenu(trigger: HTMLElement) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false })
}

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  sessionStorage.clear()
  resetAppliedTableStore()
  mocks.blind = false
  mocks.effects = [
    effect(1, { effectType: 'Pulse', targetKey: 'front', isGroupTarget: true, speedMasterUuid: 'm2' }),
    effect(2, { effectType: 'Circle', propertyName: 'position', size: 0.6, direction: 'BOUNCE' }),
    effect(3, { effectType: 'Lightning', programmerOwned: false, programmerLayerId: 12, nShot: 3, spent: true, seconds: true, timingSource: 'WALL_CLOCK', beatDivision: 4 }),
    effect(4, { effectType: 'Breathe', programmerOwned: false, cueId: 9, isRunning: false }),
  ]
  mocks.layers = [
    layer(10, 'LOOK', 1, 'Storm'),
    layer(11, 'TEMPLATE', 5, 'Amber'),
    layer(12, 'TEMPLATE', 6, 'Flash 3x'),
    layer(13, 'LOOK', 2, 'Thunder', { enabled: false }),
  ]
  mocks.looks = [look(1, 'Storm'), look(2, 'Thunder')]
  mocks.templates = [template(5, 'Amber'), template(6, 'Flash 3x', { kind: 'effect', rows: [] })]
  for (const spy of [mocks.removeLayer, mocks.patchLayer, mocks.detachLayer, mocks.pickUp, mocks.pauseFx, mocks.resumeFx, mocks.removeFx, mocks.removeGroupFx]) spy.mockClear()
})
afterEach(() => vi.unstubAllGlobals())

describe('Applied FX', () => {
  it('lists the rail’s rows, in the rail’s order', () => {
    drawRail(<ProgrammerFxList />)
    const rail = screen.getAllByRole('button', { name: /^Actions for / }).map((b) => b.getAttribute('aria-label'))
    act(() => document.body.replaceChildren())

    drawRoute('?table=fx')
    const table = tableRows().map((r) => within(r).getByRole('button', { name: /^Actions for / }).getAttribute('aria-label'))
    expect(table).toEqual(rail)
    expect(table).toEqual(['Actions for Pulse', 'Actions for Circle', 'Actions for Lightning', 'Actions for Breathe'])
  })

  it('reads the desk’s timing, size, direction, runs and spent, and marks held · Blind', () => {
    mocks.blind = true
    drawRoute('?table=fx')
    expect(rowOf('fx:1')).toHaveTextContent('M2 · 96')
    expect(rowOf('fx:2')).toHaveTextContent('M1 · 120')
    expect(rowOf('fx:2')).toHaveTextContent('60%')
    expect(rowOf('fx:2')).toHaveTextContent('↔')
    expect(rowOf('fx:3')).toHaveTextContent('Seconds')
    expect(rowOf('fx:3')).toHaveTextContent('3× · spent')
    expect(rowOf('fx:3')).toHaveTextContent('in Flash 3x')
    // Blind holds the programmer's own effects, and only those.
    expect(rowOf('fx:1').querySelector('[data-held-blind]')).not.toBeNull()
    expect(rowOf('fx:3').querySelector('[data-held-blind]')).toBeNull()
    expect(rowOf('fx:4')).toHaveTextContent('Paused')
  })

  it('stops through the rail’s mutation, a group’s by its group', () => {
    drawRail(<ProgrammerFxList />)
    openMenu(screen.getByRole('button', { name: 'Actions for Pulse' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Stop' }))
    const rail = mocks.removeGroupFx.mock.calls.slice()
    act(() => document.body.replaceChildren())
    mocks.removeGroupFx.mockClear()

    drawRoute('?table=fx')
    openMenu(within(rowOf('fx:1')).getByRole('button', { name: 'Actions for Pulse' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Stop' }))
    expect(mocks.removeGroupFx.mock.calls).toEqual(rail)
    expect(rail).toEqual([[{ id: 1, groupName: 'front' }]])
  })

  it('pauses and resumes through the editor’s own mutations', () => {
    drawRoute('?table=fx')
    openMenu(within(rowOf('fx:2')).getByRole('button', { name: 'Actions for Circle' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Pause' }))
    expect(mocks.pauseFx).toHaveBeenCalledWith({ id: 2, fixtureKey: 'par-2' })
    openMenu(within(rowOf('fx:4')).getByRole('button', { name: 'Actions for Breathe' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Resume' }))
    expect(mocks.resumeFx).toHaveBeenCalledWith({ id: 4, fixtureKey: 'par-4' })
  })

  it('opens the live editor from the row, ⏎ and Edit… alike', () => {
    drawRoute('?table=fx')
    fireEvent.click(rowOf('fx:2').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByTestId('fx-editor')).toHaveTextContent('2')
  })

  it('stops every selected effect from the bar', () => {
    drawRoute('?table=fx')
    fireEvent.click(rowOf('fx:2').querySelector('[data-first-column]')!)
    fireEvent.click(rowOf('fx:4').querySelector('[data-first-column]')!, { metaKey: true })
    expect(screen.getByRole('button', { name: 'Edit…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    expect(mocks.removeFx.mock.calls).toEqual([[{ id: 2, fixtureKey: 'par-2' }], [{ id: 4, fixtureKey: 'par-4' }]])
  })
})

describe('Applied Looks and Palettes', () => {
  it('list the rail’s layers of each kind, top wins, as the rail numbers them', () => {
    // Each row's name badge, by its title — `Look “Storm”`, `Palette “Amber”`.
    const badges = (root: ParentNode) =>
      [...root.querySelectorAll('[title^="Look “"], [title^="Palette “"], [title^="Effect palette “"]')].map((el) => el.getAttribute('title'))
    drawRail(<ProgrammerLookStack />)
    const rail = badges(document)
    act(() => document.body.replaceChildren())

    drawRoute('?table=looks')
    const looks = tableRows().flatMap((r) => badges(r))
    act(() => document.body.replaceChildren())
    drawRoute('?table=palettes')
    const palettes = tableRows().flatMap((r) => badges(r))

    expect(rail).toEqual(['Look “Thunder”', 'Effect palette “Flash 3x”', 'Palette “Amber”', 'Look “Storm”'])
    expect(looks).toEqual(rail.filter((t) => t!.startsWith('Look')))
    expect(palettes).toEqual(rail.filter((t) => !t!.startsWith('Look')))
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['layer:12', 'layer:11'])
  })

  it('marks a palette layer whose effect is spent', () => {
    drawRoute('?table=palettes')
    expect(rowOf('layer:12').querySelector('[data-spent]')).not.toBeNull()
    expect(rowOf('layer:11').querySelector('[data-spent]')).toBeNull()
  })

  it('detaches, disables, picks up and removes through the rail’s mutations', () => {
    drawRoute('?table=looks')
    const storm = rowOf('layer:10')
    const menu = () => openMenu(within(storm).getByRole('button', { name: 'Actions for Storm' }))
    menu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Detach' }))
    menu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Disable' }))
    menu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Pick up Storm' }))
    menu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Remove' }))
    expect(mocks.detachLayer).toHaveBeenCalledWith(10)
    expect(mocks.patchLayer).toHaveBeenCalledWith(10, { enabled: false })
    expect(mocks.pickUp).toHaveBeenCalledWith('LOOK', 1)
    expect(mocks.removeLayer).toHaveBeenCalledWith(10)
  })

  it('opens the rail’s settings popover on ⏎, whose Detach is the rail’s', () => {
    drawRoute('?table=looks')
    fireEvent.click(rowOf('layer:10').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Detach' }))
    expect(mocks.detachLayer).toHaveBeenCalledWith(10)
  })

  it('turns a layer back on from its toggle', () => {
    drawRoute('?table=looks')
    fireEvent.click(within(rowOf('layer:13')).getByRole('button', { name: 'Enable layer' }))
    expect(mocks.patchLayer).toHaveBeenCalledWith(13, { enabled: true })
  })

  it('opens the Look on Edit look…', () => {
    drawRoute('?table=looks')
    openMenu(within(rowOf('layer:13')).getByRole('button', { name: 'Actions for Thunder' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit look…' }))
    expect(screen.getByTestId('look-sheet')).toHaveTextContent('Thunder')
  })
})

describe('the query string', () => {
  it('takes sort, direction and filter from it', () => {
    drawRoute('?table=fx&sort=name&dir=desc&q=e')
    // `e` matches every row (Lightning by its property, dimmer), sorted by name Z→A.
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['fx:1', 'fx:3', 'fx:2', 'fx:4'])
  })

  it('narrows by the chip, and writes a header press and a chip back with replace', () => {
    drawRoute('?table=fx&home=yours')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['fx:1', 'fx:2'])
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    expect(where()).toBe('/projects/1/programmer/applied?table=fx&sort=name&home=yours')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['fx:2', 'fx:1'])
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    expect(where()).toBe('/projects/1/programmer/applied?table=fx&sort=name&dir=desc&home=yours')
    fireEvent.click(screen.getByRole('button', { name: /^All/ }))
    expect(where()).toBe('/projects/1/programmer/applied?table=fx&sort=name&dir=desc')
  })

  it('filters by what is typed, and remembers each table’s view for the next arrival', () => {
    const first = drawRoute('?table=palettes')
    fireEvent.change(screen.getByRole('textbox', { name: /Filter by palette/ }), { target: { value: 'amb' } })
    expect(where()).toBe('/projects/1/programmer/applied?table=palettes&q=amb')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['layer:11'])
    first.unmount()

    drawRoute('')
    expect(where()).toBe('/projects/1/programmer/applied?table=palettes&q=amb')
  })

  it('follows the window’s table when another window sets it', () => {
    drawRoute('?table=fx')
    act(() => setAppliedTable('looks'))
    expect(where()).toBe('/projects/1/programmer/applied?table=looks')
    expect(screen.getByText('Applied Looks')).toBeInTheDocument()
  })
})
