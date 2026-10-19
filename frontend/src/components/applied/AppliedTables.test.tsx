// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import type { StageElementDto } from '@/api/stageElementApi'
import type { LiveScenery, ProgrammerScenery } from '@/api/sceneryApi'

/**
 * The programmer's Stack, FX and Scenery tables (tables-colours plan D1, the applied tables of
 * venue-visit plan S14 moved onto the programmer's tabs). Since session 2 they are the only place
 * the programmer's layers, effects and scenery are listed — the rail is the Colour · Spread dock
 * (D4) — so this suite pins everything the rail used to offer that the tables now carry alone: a
 * layer's settings, Detach, the replacement notes, + Look / + Palette, + Effect, *held · Blind*,
 * *Edit…* on an effect, the values-beat-effects rule, the hand's place on the stack and ⌘K's
 * *+ Effect* door. Sort, filter and chip ride the query string beside `?table=`.
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
  openMakeLayer: vi.fn(),
  localValues: 0,
  elements: [] as StageElementDto[],
  programmerScenery: { projectId: 1, elements: [] } as ProgrammerScenery,
  liveScenery: { projectId: 1, entries: {} } as LiveScenery,
  setScenery: vi.fn(),
  clearScenery: vi.fn(),
  notes: new Map<string, string>(),
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
  useProgrammerScenery: () => mocks.programmerScenery,
  programmerRemoveLayer: mocks.removeLayer,
  programmerMoveLayer: mocks.moveLayer,
  programmerPatchLayer: mocks.patchLayer,
  programmerDetachLayer: mocks.detachLayer,
  programmerSetScenery: (...a: unknown[]) => mocks.setScenery(...a),
  programmerClearScenery: (...a: unknown[]) => mocks.clearScenery(...a),
}))
vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: mocks.elements }) }))
vi.mock('@/store/scenery', () => ({
  useLiveScenery: () => mocks.liveScenery,
  useSetLookSceneryMutation: () => [vi.fn()],
}))
vi.mock('@/components/programmer/ProgrammerSheets', () => ({ useProgrammerSheets: () => ({ openMakeLayer: mocks.openMakeLayer }) }))
vi.mock('@/components/programmer/useLocalFamilyCounts', () => ({ useLocalValueCount: () => mocks.localValues }))
// The pickers behind the create verbs have their own suites; here they only have to be there.
vi.mock('@/components/programmer/ApplyLayerPicker', () => ({
  ApplyLayerPicker: ({ library }: { library: string }) => <div data-testid="apply-picker">{library}</div>,
}))
vi.mock('@/components/programmer/ProgrammerAddEffect', async () => {
  const { useEffect, useState } = await import('react')
  const { registerAddEffectDoor } = await import('@/components/programmer/addEffectDoor')
  return {
    useProgrammerAddEffect: () => ({ disabled: false, reason: 'Add an effect to the programmer.', pair: { targets: [], families: null } }),
    ProgrammerAddEffectSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="add-effect" /> : null),
    ProgrammerAddEffectDoor: () => {
      const [asked, setAsked] = useState(false)
      useEffect(() => registerAddEffectDoor(() => setAsked(true)), [])
      return asked ? <div data-testid="add-effect-door" /> : null
    },
  }
})
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandProgrammerLayerStrip: () => <div data-testid="hand-layer-strip" /> }))
vi.mock('@/components/programmer/useReplacementNotes', () => ({
  ReplacementNotesProvider: ({ children }: { children: ReactNode }) => children,
  useReplacementNotes: () => mocks.notes,
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
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: mocks.looks }), useLookQuery: () => ({ currentData: undefined }) }))
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

const { ProgrammerTables } = await import('@/components/programmer/ProgrammerTables')
const { ProgrammerScopeProvider, useProgrammerScope } = await import('@/components/programmer/ProgrammerScope')
const { resetProgrammerTableStore, setProgrammerTable } = await import('@/lib/programmerTables')
const { DEFAULT_SCENERY_FILTERS, SCENERY_FILTERS_KEY, resetSceneryFiltersStore } = await import(
  '@/components/programmer/ProgrammerSceneryList'
)
const { FxSelectionStoreContext, createFxSelectionStore } = await import('@/components/fixtures-list/fxSelection')
const { openAddEffectDoor } = await import('@/components/programmer/addEffectDoor')

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

function element(uuid: string, name: string, layer: 'VENUE' | 'SET', kind: 'DRAPE' | 'OBJECT', params: Record<string, unknown>): StageElementDto {
  return {
    id: uuid.length,
    uuid,
    name,
    kind,
    layer,
    positionX: 0,
    positionY: 0,
    positionZ: 0,
    yawDeg: 0,
    widthM: 4,
    depthM: 0.1,
    heightM: 6,
    finishColour: null,
    finishPattern: null,
    emissive: false,
    params,
    hidden: false,
    sortOrder: 0,
  } as unknown as StageElementDto
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

/** The Values grid's stand-in: what its scope is pointed at. */
function ScopeProbe() {
  const scope = useProgrammerScope()
  return <div data-testid="scope">{scope == null ? '' : scope.kind === 'layer' ? `layer:${scope.layerId}` : scope.kind}</div>
}

function drawRoute(search: string, fxSelection = createFxSelectionStore()) {
  return render(
    <MemoryRouter initialEntries={[`/projects/1/programmer${search}`]}>
      <Routes>
        <Route
          path="/projects/:projectId/programmer"
          element={
            <FxSelectionStoreContext.Provider value={fxSelection}>
              <ProgrammerScopeProvider>
                <ProgrammerTables projectId={1} values={<ScopeProbe />} />
              </ProgrammerScopeProvider>
            </FxSelectionStoreContext.Provider>
          }
        />
      </Routes>
      <Where />
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
  resetProgrammerTableStore()
  resetSceneryFiltersStore()
  mocks.blind = false
  mocks.localValues = 0
  mocks.notes = new Map()
  mocks.programmerScenery = { projectId: 1, elements: [] }
  mocks.liveScenery = { projectId: 1, entries: {} }
  mocks.elements = [
    element('e-tabs', 'Tabs', 'VENUE', 'DRAPE', { role: 'TABS', operation: 'DRAW' }),
    element('e-cloth', 'Backcloth', 'VENUE', 'DRAPE', { role: 'BACKCLOTH', operation: 'FLY' }),
    element('e-sofa', 'Sofa', 'SET', 'OBJECT', {}),
  ]
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
  for (const spy of [mocks.removeLayer, mocks.moveLayer, mocks.patchLayer, mocks.detachLayer, mocks.pickUp, mocks.pauseFx, mocks.resumeFx, mocks.removeFx, mocks.removeGroupFx, mocks.openMakeLayer, mocks.setScenery, mocks.clearScenery]) spy.mockClear()
})
afterEach(() => vi.unstubAllGlobals())

describe('the FX table', () => {
  it('lists every running effect, one row per instance in the desk’s order', () => {
    drawRoute('?table=fx')
    const table = tableRows().map((r) => within(r).getByRole('button', { name: /^Actions for / }).getAttribute('aria-label'))
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

  it('stops a group’s effect by its group, and a head’s by its head', () => {
    drawRoute('?table=fx')
    openMenu(within(rowOf('fx:1')).getByRole('button', { name: 'Actions for Pulse' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Stop' }))
    expect(mocks.removeGroupFx.mock.calls).toEqual([[{ id: 1, groupName: 'front' }]])
    openMenu(within(rowOf('fx:2')).getByRole('button', { name: 'Actions for Circle' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Stop' }))
    expect(mocks.removeFx.mock.calls).toEqual([[{ id: 2, fixtureKey: 'par-2' }]])
  })

  it('opens the live editor from Edit… in the row’s menu', () => {
    drawRoute('?table=fx')
    openMenu(within(rowOf('fx:3')).getByRole('button', { name: 'Actions for Lightning' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit…' }))
    expect(screen.getByTestId('fx-editor')).toHaveTextContent('3')
  })

  it('publishes its selection for the Values grid’s rings, and takes it back when it mounts again', () => {
    const fxSelection = createFxSelectionStore()
    const first = drawRoute('?table=fx', fxSelection)
    fireEvent.click(rowOf('fx:2').querySelector('[data-first-column]')!)
    fireEvent.click(rowOf('fx:4').querySelector('[data-first-column]')!, { metaKey: true })
    expect([...fxSelection.get()]).toEqual([2, 4])
    // The table mounts on demand: leaving it keeps what the rings read.
    first.unmount()
    expect([...fxSelection.get()]).toEqual([2, 4])

    drawRoute('?table=fx', fxSelection)
    expect(rowOf('fx:2')).toHaveAttribute('data-state', 'selected')
    expect(rowOf('fx:4')).toHaveAttribute('data-state', 'selected')
    fireEvent.click(screen.getByRole('button', { name: /Deselect/ }))
    expect(fxSelection.get().size).toBe(0)
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

describe('the Stack table', () => {
  // Each row's name badge, by its title — `Look “Storm”`, `Palette “Amber”`.
  const badges = (root: ParentNode) =>
    [...root.querySelectorAll('[title^="Look “"], [title^="Palette “"], [title^="Effect palette “"]')].map((el) => el.getAttribute('title'))

  it('lists every layer top wins, Looks and palettes in one order, under your values pinned on top', () => {
    drawRoute('?table=stack')
    expect(tableRows().flatMap((r) => badges(r))).toEqual(['Look “Thunder”', 'Effect palette “Flash 3x”', 'Palette “Amber”', 'Look “Storm”'])
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local', 'layer:13', 'layer:12', 'layer:11', 'layer:10'])
    expect(rowOf('local')).toHaveTextContent('Your values')
  })

  it('names what a later press took from a layer in its Note', () => {
    mocks.notes = new Map([['LOOK:1', 'lost Hex 3–4 to Amber']])
    drawRoute('?table=stack')
    expect(rowOf('layer:10').querySelector('[data-layer-note]')).toHaveTextContent('lost Hex 3–4 to Amber')
    expect(rowOf('layer:11').querySelector('[data-layer-note]')).toBeNull()
  })

  it('opens the Look and palette pickers from + Look or palette', () => {
    drawRoute('?table=stack')
    openMenu(screen.getByRole('button', { name: /Look or palette/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Palette…' }))
    expect(screen.getByTestId('apply-picker')).toHaveTextContent('palettes')
  })

  it('draws the values-beat-effects rule with the effects under the stack, a press from the FX tab', () => {
    drawRoute('?table=stack')
    const rule = document.querySelector('[data-effects-rule]')!
    expect(rule).toHaveTextContent('Values above beat effects below')
    expect(rule).toHaveTextContent('4 effects run under this stack')
    fireEvent.click(within(rule as HTMLElement).getByRole('button', { name: 'on the FX tab' }))
    expect(where()).toBe('/projects/1/programmer?table=fx')
  })

  it('says when the filter matches no layer, your values still listed', () => {
    drawRoute('?table=stack&q=nothing-like-this')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local'])
    expect(document.querySelector('[data-stack-no-match]')).toHaveTextContent('No layers match the filter.')
    cleanup()
    drawRoute('?table=stack&q=storm')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local', 'layer:10'])
    expect(document.querySelector('[data-stack-no-match]')).toBeNull()
  })

  it('narrows by kind, your values staying pinned', () => {
    drawRoute('?table=stack&kind=palettes')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local', 'layer:12', 'layer:11'])
    fireEvent.click(screen.getByRole('button', { name: /^Looks/ }))
    expect(where()).toBe('/projects/1/programmer?table=stack&kind=looks')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local', 'layer:13', 'layer:10'])
  })

  it('arms the drag on the layers in stack order, and refuses it while a chip, a filter or a sort narrows the rows', () => {
    const grips = () => tableRows().filter((r) => within(r).queryByRole('button', { name: 'Reorder row' }) != null).map((r) => r.dataset.rowId)
    const first = drawRoute('?table=stack')
    // Your values is pinned: no grip.
    expect(grips()).toEqual(['layer:13', 'layer:12', 'layer:11', 'layer:10'])
    expect(screen.getByText(/drag ⋮⋮ to reorder/)).toBeInTheDocument()
    first.unmount()

    for (const search of ['?table=stack&kind=looks', '?table=stack&q=storm', '?table=stack&sort=name']) {
      const view = drawRoute(search)
      expect(grips(), search).toEqual([])
      view.unmount()
    }
  })

  it('marks a palette layer whose effect is spent', () => {
    drawRoute('?table=stack')
    expect(rowOf('layer:12').querySelector('[data-spent]')).not.toBeNull()
    expect(rowOf('layer:11').querySelector('[data-spent]')).toBeNull()
  })

  it('detaches, disables, picks up and removes through the stack’s mutations', () => {
    drawRoute('?table=stack')
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

  it('points the Values grid at a layer on ⏎ and switches to Values', () => {
    drawRoute('?table=stack')
    expect(screen.getByTestId('scope')).toHaveTextContent('local')
    fireEvent.click(rowOf('layer:11').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByTestId('scope')).toHaveTextContent('layer:11')
    expect(where()).toBe('/projects/1/programmer?table=values')
  })

  it('marks the layer the Values grid is pointed at', () => {
    drawRoute('?table=stack')
    expect(rowOf('layer:11').className).not.toContain('bg-teal-500/10')
    fireEvent.click(rowOf('layer:11').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    act(() => setProgrammerTable('stack'))
    expect(rowOf('layer:11').className).toContain('bg-teal-500/10')
    expect(rowOf('layer:12').className).not.toContain('bg-teal-500/10')
  })

  it('points it at Local from your values, and from Edit in grid on the bar', () => {
    drawRoute('?table=stack')
    fireEvent.click(rowOf('layer:11').querySelector('[data-first-column]')!)
    fireEvent.click(screen.getByRole('button', { name: 'Edit in grid' }))
    expect(screen.getByTestId('scope')).toHaveTextContent('layer:11')
    cleanup()

    drawRoute('?table=stack')
    fireEvent.click(rowOf('local').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(screen.getByTestId('scope')).toHaveTextContent('local')
    expect(where()).toBe('/projects/1/programmer?table=values')
  })

  it('makes a layer from your values’ row, once there are values to promote', () => {
    drawRoute('?table=stack')
    const make = within(rowOf('local')).getByRole('button', { name: 'Make layer' })
    expect(make).toBeDisabled()
    cleanup()

    mocks.localValues = 3
    drawRoute('?table=stack')
    expect(rowOf('local')).toHaveTextContent('3 values')
    fireEvent.click(within(rowOf('local')).getByRole('button', { name: 'Make layer' }))
    expect(mocks.openMakeLayer).toHaveBeenCalledTimes(1)
  })

  it('opens the settings popover from the row’s menu, with Detach in it', () => {
    drawRoute('?table=stack')
    openMenu(within(rowOf('layer:10')).getByRole('button', { name: 'Actions for Storm' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Settings…' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Detach' }))
    expect(mocks.detachLayer).toHaveBeenCalledWith(10)
  })

  it('turns a layer back on from its toggle', () => {
    drawRoute('?table=stack')
    fireEvent.click(within(rowOf('layer:13')).getByRole('button', { name: 'Enable layer' }))
    expect(mocks.patchLayer).toHaveBeenCalledWith(13, { enabled: true })
  })

  it('opens the Look on Edit look…', () => {
    drawRoute('?table=stack')
    openMenu(within(rowOf('layer:13')).getByRole('button', { name: 'Actions for Thunder' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Edit look…' }))
    expect(screen.getByTestId('look-sheet')).toHaveTextContent('Thunder')
  })
})

describe('the Scenery table', () => {
  it('lists every element Venue then Set by name, the filters as chips', () => {
    drawRoute('?table=scenery')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['divider:VENUE', 'e-cloth', 'e-tabs', 'divider:SET', 'e-sofa'])
    fireEvent.click(screen.getByRole('button', { name: 'Venue' }))
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['divider:SET', 'e-sofa'])
    // The full list's own filters, per window: the two cannot disagree.
    expect(JSON.parse(sessionStorage.getItem(SCENERY_FILTERS_KEY)!)).toEqual({ ...DEFAULT_SCENERY_FILTERS, venue: false })
    fireEvent.click(screen.getByRole('button', { name: /^Moving/ }))
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(tableRows()).toHaveLength(5)
  })

  it('keeps under Changed only what something on stage has moved', () => {
    mocks.liveScenery = {
      projectId: 1,
      entries: {
        'e-cloth': { elementUuid: 'e-cloth', state: { visible: false }, from: { visible: true }, startedAtMs: 0, durationMs: 0, source: { kind: 'cue', cueId: 3, label: '3' } },
      },
    }
    drawRoute('?table=scenery')
    fireEvent.click(screen.getByRole('button', { name: 'Changed' }))
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['divider:VENUE', 'e-cloth'])
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('keeps a held piece whatever the filters say, and releases it through the programmer', () => {
    mocks.programmerScenery = { projectId: 1, elements: [{ elementUuid: 'e-tabs', state: { open: 0.5 } }] }
    drawRoute('?table=scenery')
    fireEvent.click(screen.getByRole('button', { name: 'Venue' }))
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['divider:VENUE', 'e-tabs', 'divider:SET', 'e-sofa'])
    fireEvent.click(within(rowOf('e-tabs')).getByRole('button', { name: 'Release Tabs' }))
    expect(mocks.clearScenery).toHaveBeenCalledWith('e-tabs', 0)
  })
})

describe('the FX table’s create verb and rule', () => {
  it('draws + Effect on the library row and the values-beat-effects rule over the sheet', () => {
    drawRoute('?table=fx')
    fireEvent.click(screen.getByRole('button', { name: 'Add an effect' }))
    expect(screen.getByTestId('add-effect')).toBeInTheDocument()
    expect(document.querySelector('[data-effects-rule]')).toHaveTextContent('Values above beat effects below')
  })
})

describe('what the tables’ frame carries for every table', () => {
  it('places the hand on the stack under whichever table shows, Values included', () => {
    for (const search of ['', '?table=stack', '?table=fx', '?table=scenery']) {
      const view = drawRoute(search)
      expect(screen.getAllByTestId('hand-layer-strip'), search).toHaveLength(1)
      view.unmount()
    }
  })

  it('holds ⌘K’s + Effect door while it is mounted, on any table', () => {
    const view = drawRoute('')
    expect(screen.queryByTestId('add-effect-door')).toBeNull()
    act(() => {
      expect(openAddEffectDoor()).toBe(true)
    })
    expect(screen.getByTestId('add-effect-door')).toBeInTheDocument()
    view.unmount()
    expect(openAddEffectDoor()).toBe(false)
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
    expect(where()).toBe('/projects/1/programmer?table=fx&sort=name&home=yours')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['fx:2', 'fx:1'])
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    expect(where()).toBe('/projects/1/programmer?table=fx&sort=name&dir=desc&home=yours')
    fireEvent.click(screen.getByRole('button', { name: /^All/ }))
    expect(where()).toBe('/projects/1/programmer?table=fx&sort=name&dir=desc')
  })

  it('filters by what is typed, and remembers each table’s view for the next arrival', () => {
    const first = drawRoute('?table=stack')
    fireEvent.change(screen.getByRole('textbox', { name: /Filter by look, palette/ }), { target: { value: 'amb' } })
    expect(where()).toBe('/projects/1/programmer?table=stack&q=amb')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['local', 'layer:11'])
    first.unmount()

    drawRoute('')
    expect(where()).toBe('/projects/1/programmer?table=stack&q=amb')
  })

  it('lands a tab on the view the window left it on, replacing the address', () => {
    sessionStorage.setItem('programmer.view.fx', JSON.stringify({ sort: 'name', dir: 'asc', q: '', chip: 'yours' }))
    drawRoute('?table=stack')
    fireEvent.click(within(screen.getByRole('tablist', { name: 'Programmer tables' })).getByRole('tab', { name: /^FX/ }))
    expect(where()).toBe('/projects/1/programmer?table=fx&sort=name&home=yours')
    expect(tableRows().map((r) => r.dataset.rowId)).toEqual(['fx:2', 'fx:1'])
  })

  it('leaves a bare address on Values bare', () => {
    drawRoute('')
    expect(where()).toBe('/projects/1/programmer')
    expect(screen.getByTestId('scope')).toBeInTheDocument()
  })

  it('follows the window’s table when another window sets it', () => {
    drawRoute('?table=fx')
    act(() => setProgrammerTable('scenery'))
    expect(where()).toBe('/projects/1/programmer?table=scenery')
    expect(screen.getByRole('group', { name: 'Scenery filters' })).toBeInTheDocument()
  })
})
