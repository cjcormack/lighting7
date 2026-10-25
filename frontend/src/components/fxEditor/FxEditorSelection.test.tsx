// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveFx } from '@/store/fixtureFx'
import type { CueTarget } from '@/api/cuesApi'
import { chan, colourProp, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The FX editor (FX editor plan D1–D6, §4): the programmer FX tab's *Selection* view — the list in
 * family lanes in the desk's apply order, a Local card's drag as one `programmer.moveFx`, a cue's
 * card opening its cue, *+ FX* with *Replace · Stack*, and the widths the columns fold at.
 */
const HEX = [1, 2].map((n) =>
  makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n * 10)), colourProp('rgbColour', chan(n * 10 + 1), chan(n * 10 + 2), chan(n * 10 + 3)), sliderProp('uv', 'uv', chan(n * 10 + 4))], {
    name: `Hex ${n}`,
    groups: ['front'],
  }),
)

const mocks = vi.hoisted(() => ({
  targets: [] as CueTarget[],
  effects: [] as ActiveFx[],
  width: 1200,
  moveFx: vi.fn(),
  selectHeads: vi.fn(),
  stop: vi.fn(),
  pause: vi.fn(),
  scope: { kind: 'local' } as { kind: string },
  asks: [] as { fxIds: number[] }[],
  library: [] as { name: string; defaultDirection?: string }[],
}))

vi.mock('@/components/fx/selectionFxTarget', () => ({
  useWindowFxPair: () => ({ targets: mocks.targets, families: null }),
  fxTargetOf: (targets: CueTarget[]) => (targets.length === 0 ? null : { type: 'selection', targets, fixtures: [], label: 'Hex 1, Hex 2' }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: HEX }) }))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: mocks.effects }),
  useFxLibraryQuery: () => ({ data: mocks.library }),
  moveLocalFx: (...a: unknown[]) => mocks.moveFx(...a),
}))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: [{ layerId: 40, source: { kind: 'PALETTE', id: 3, uuid: 'u3', name: 'Night' } }] }),
}))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/components/fixtureSheet/fxLabels', () => ({
  useFxDetail: () => () => '1 bar · M1',
  useCueLabel: () => (id: number) => (id === 12 ? 'Q12' : undefined),
}))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => mocks.scope }))
vi.mock('@/components/programmer/ProgrammerAddFx', () => ({
  useProgrammerAddFx: () => ({ disabled: false, reason: 'Add an FX', pair: { targets: [], families: null } }),
  ProgrammerAddFxSheet: () => null,
}))
vi.mock('@/components/fx/useFxControls', () => ({
  useStopFx: () => mocks.stop,
  useToggleFxPause: () => mocks.pause,
}))
vi.mock('@/components/programmer/ProgrammerTableTabs', () => ({ ProgrammerTableTabs: () => null }))
vi.mock('@/components/desk/DeskChip', () => ({ DeskChip: () => null }))
vi.mock('@/components/programmer/RecordLookSheet', () => ({
  RecordLookSheet: ({ open, targets, initialMask, initialFxIds }: { open: boolean; targets: unknown; initialMask: unknown; initialFxIds: number[] }) =>
    open ? <div data-testid="record-look">{JSON.stringify({ targets, initialMask: initialMask === undefined ? 'window' : initialMask, initialFxIds })}</div> : null,
}))
vi.mock('@/components/stage/OwnerEditor', () => ({
  OwnerEditor: ({ entry }: { entry: { kind: string; id: number } }) => <div data-testid="cue-editor">{`${entry.kind}:${entry.id}`}</div>,
}))
vi.mock('@/components/fx/FxPicker', () => ({
  FxPicker: ({ initialFamily, press }: { initialFamily: string; press: { stack?: boolean } }) => (
    <div data-testid="picker" data-family={initialFamily} data-stack={String(press.stack === true)} />
  ),
}))
vi.mock('@/components/fx/SplitFxEditor', () => ({
  SplitFxEditor: ({ effect, layout }: { effect: ActiveFx; layout: string }) => (
    <div data-testid="inspector-editor" data-layout={layout}>
      {effect.id}
    </div>
  ),
}))
vi.mock('./useFxPreview', () => ({
  useFxPreview: (ask: { fxIds: number[] } | null) => {
    if (ask != null) mocks.asks.push(ask)
    return { answer: null, error: null }
  },
}))
vi.mock('./useUnderneath', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./useUnderneath')>()),
  useUnderneath: () => ({ kind: 'CUE', onStage: true, value: '153', cueId: 12 }),
}))
vi.mock('@/components/fixtures-list/programmerSelectRequest', () => ({
  selectWindowHeads: (...a: unknown[]) => mocks.selectHeads(...a),
}))

const { FxEditorSelection } = await import('./FxEditorSelection')
const { getPickedFx, resetFxEditorViewStores, setPickedFx } = await import('@/lib/fxEditorView')
const { RemaskStoreContext, createRemaskStore } = await import('@/components/fixtures-list/remaskStore')

const FRONT = [{ type: 'group' as const, key: 'front' }]
const fx = (id: number, over: Partial<ActiveFx> = {}) =>
  makeActiveFx({ id, fxType: `FX ${id}`, targetKey: 'front', isGroupTarget: true, ...over })

function draw() {
  return render(<FxEditorSelection projectId={1} viewSwitch={null} />)
}

const card = (id: number) => document.querySelector<HTMLElement>(`[data-fx-card="${id}"]`)!
const lane = (family: string) => document.querySelector<HTMLElement>(`[data-fx-lane="${family}"]`)!
const laneIds = (family: string) => [...lane(family).querySelectorAll<HTMLElement>('[data-fx-card]')].map((c) => Number(c.dataset.fxCard))

beforeEach(() => {
  sessionStorage.clear()
  resetFxEditorViewStores()
  mocks.targets = FRONT
  mocks.width = 1200
  mocks.scope = { kind: 'local' }
  mocks.asks = []
  mocks.library = []
  mocks.effects = [
    fx(1, { programmerOwned: false, cueId: 12 }),
    fx(2, { programmerLayerId: 40, paletteId: 3 }),
    fx(3),
    fx(4),
    fx(5, { attribute: 'rgbColour', programmerOwned: false, cueId: 12 }),
  ]
  for (const spy of [mocks.moveFx, mocks.selectHeads, mocks.stop, mocks.pause]) spy.mockClear()
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: mocks.width, height: 600 }) as DOMRect)
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('the list', () => {
  it('draws a lane per family, top first in the desk’s apply order: Local, then layers, then cue FX', () => {
    draw()
    expect([...document.querySelectorAll<HTMLElement>('[data-fx-lane]')].map((l) => l.dataset.fxLane)).toEqual(['INTENSITY', 'COLOUR'])
    expect(laneIds('INTENSITY')).toEqual([4, 3, 2, 1])
    expect(laneIds('COLOUR')).toEqual([5])
    expect(card(2)).toHaveTextContent('Night · pressed')
    expect(card(1)).toHaveTextContent('on Q12')
  })

  it('says what each lane sits on', () => {
    draw()
    expect(within(lane('INTENSITY')).getByText(/^Underneath/)).toHaveTextContent('Underneath · Q12 60 %')
  })

  it('drags a Local card within its lane as exactly one programmer.moveFx, naming the card that ends above it', () => {
    mocks.effects = [fx(10), fx(20), fx(30)]
    draw()
    expect(laneIds('INTENSITY')).toEqual([30, 20, 10])
    fireEvent.dragStart(card(30))
    fireEvent.dragOver(card(10))
    fireEvent.drop(card(10))
    expect(mocks.moveFx.mock.calls).toEqual([[30, 10]])
  })

  it('moves a Local card one place with its grip’s ↑ and ↓', () => {
    mocks.effects = [fx(10), fx(20), fx(30)]
    draw()
    // 30 · 20 · 10, top first: 10 up one is directly below 30.
    fireEvent.keyDown(within(card(10)).getByRole('button', { name: /^Move FX 10/ }), { key: 'ArrowUp' })
    expect(mocks.moveFx.mock.calls).toEqual([[10, 30]])
    mocks.moveFx.mockClear()
    fireEvent.keyDown(within(card(30)).getByRole('button', { name: /^Move FX 30/ }), { key: 'ArrowDown' })
    expect(mocks.moveFx.mock.calls).toEqual([[30, 20]])
  })

  it('gives a layer’s and a cue’s card no grip, and refuses a drop on them', () => {
    draw()
    expect(within(card(2)).queryByRole('button', { name: /^Move/ })).toBeNull()
    expect(within(card(1)).queryByRole('button', { name: /^Move/ })).toBeNull()
    fireEvent.dragStart(card(4))
    fireEvent.drop(card(2))
    expect(mocks.moveFx).not.toHaveBeenCalled()
  })

  it('opens a cue FX card’s cue in the cue editor', () => {
    draw()
    fireEvent.click(within(card(1)).getByRole('button', { name: /on Q12 — open the cue/ }))
    expect(screen.getByTestId('cue-editor')).toHaveTextContent('cue:12')
  })

  it('picks a card into the inspector, the live editor in the inspector’s order', () => {
    draw()
    fireEvent.click(within(card(3)).getByRole('button', { name: 'Edit FX 3' }))
    expect(getPickedFx()).toBe(3)
    expect(screen.getByTestId('inspector-editor')).toHaveTextContent('3')
    expect(screen.getByTestId('inspector-editor')).toHaveAttribute('data-layout', 'inspector')
  })

  it('shows Direction only where it is not the FX type’s own, and names an attribute in words', () => {
    mocks.library = [{ name: 'Sweep', defaultDirection: 'BOUNCE' }]
    mocks.effects = [
      fx(1, { fxType: 'Sweep', direction: 'BOUNCE' }),
      fx(2, { fxType: 'Sweep', direction: 'FORWARD' }),
      fx(3, { fxType: 'Pulse', direction: 'BACKWARD', attribute: 'uv' }),
    ]
    draw()
    expect(within(card(1)).queryByTitle('Bounce')).toBeNull()
    expect(within(card(2)).getByTitle('Forward')).toHaveTextContent('→')
    expect(within(card(3)).getByTitle('Backward')).toHaveTextContent('←')
    expect(card(3)).toHaveTextContent('UV')
  })

  it('draws the family pill where the list publishes its write, and a read-out where it does not', () => {
    draw()
    expect(document.querySelector('[data-fx-subject]')).toHaveTextContent('All')
    expect(within(document.querySelector<HTMLElement>('[data-fx-subject]')!).queryByRole('button')).toBeNull()
    cleanup()
    const store = createRemaskStore()
    store.set(() => {})
    render(
      <RemaskStoreContext.Provider value={store}>
        <FxEditorSelection projectId={1} viewSwitch={null} />
      </RemaskStoreContext.Provider>,
    )
    expect(within(document.querySelector<HTMLElement>('[data-fx-subject]')!).getByRole('button', { name: /All/ })).toBeTruthy()
  })

  it('marks a solo’d card and changes nothing else', () => {
    draw()
    fireEvent.click(within(card(3)).getByRole('button', { name: 'Solo FX 3' }))
    expect(card(3).querySelector('[data-solo-mark]')).not.toBeNull()
    expect(mocks.pause).not.toHaveBeenCalled()
    expect(mocks.stop).not.toHaveBeenCalled()
  })

  it('counts the FX in the footer, and records a Look masked to the lanes’ families with the programmer’s FX ticked', () => {
    draw()
    expect(screen.getByText('5 FX')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Record as Look…' }))
    expect(JSON.parse(screen.getByTestId('record-look').textContent!)).toEqual({
      targets: FRONT,
      initialMask: ['INTENSITY', 'COLOUR'],
      initialFxIds: [4, 3],
    })
  })

  it('records over the window’s own mask when no FX reaches the selection', () => {
    mocks.effects = []
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Record as Look…' }))
    expect(JSON.parse(screen.getByTestId('record-look').textContent!)).toMatchObject({ targets: FRONT, initialMask: 'window' })
  })

  it('previews over the picked FX, else the list’s top card', () => {
    draw()
    expect(mocks.asks.at(-1)?.fxIds).toEqual([4])
    act(() => setPickedFx(2))
    expect(mocks.asks.at(-1)?.fxIds).toEqual([2])
  })

  it('leaves the inspector empty for a picked FX that is not on the selection', () => {
    mocks.effects = [...mocks.effects, fx(99, { targetKey: 'elsewhere', isGroupTarget: false })]
    setPickedFx(99)
    draw()
    expect(screen.queryByTestId('inspector-editor')).toBeNull()
    expect(document.querySelector('[data-fx-card="99"]')).toBeNull()
  })
})

describe('+ FX', () => {
  it('asks Replace · Stack on a lane holding a Local FX, starting on Replace; Stack presses with stack', () => {
    draw()
    fireEvent.click(within(lane('INTENSITY')).getByRole('button', { name: 'FX' }))
    expect(screen.getByTestId('picker')).toHaveAttribute('data-family', 'INTENSITY')
    expect(screen.getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('picker')).toHaveAttribute('data-stack', 'false')
    fireEvent.click(screen.getByRole('radio', { name: 'Stack' }))
    expect(screen.getByTestId('picker')).toHaveAttribute('data-stack', 'true')
  })

  it('asks nothing on a lane with no Local FX, and presses as Replace', () => {
    draw()
    fireEvent.click(within(lane('COLOUR')).getByRole('button', { name: 'FX' }))
    expect(screen.getByTestId('picker')).toHaveAttribute('data-family', 'COLOUR')
    expect(screen.queryByRole('radio', { name: 'Stack' })).toBeNull()
    expect(screen.getByTestId('picker')).toHaveAttribute('data-stack', 'false')
  })
})

describe('with nothing selected', () => {
  it('says so, and selects the heads of a running FX picked from the menu', () => {
    mocks.targets = []
    draw()
    expect(document.querySelector('[data-fx-nothing-selected]')).toHaveTextContent('Nothing is selected')
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Select the heads of a running FX…' }), { button: 0, ctrlKey: false })
    fireEvent.click(screen.getByRole('menuitem', { name: /FX 3/ }))
    expect(mocks.selectHeads.mock.calls).toEqual([[[{ type: 'group', key: 'front' }], HEX]])
    expect(getPickedFx()).toBe(3)
  })
})

describe('the widths', () => {
  const arm = () => document.querySelector<HTMLElement>('[data-fx-editor]')!.dataset.fxEditorBand

  it('draws three columns from 1100px, the inspector in its own', () => {
    mocks.width = 1100
    draw()
    expect(arm()).toBe('0')
    expect(document.querySelector('[data-fx-inspector="column"]')).not.toBeNull()
  })

  it('draws list · graph from 760px, the inspector over the graph’s right half only while something is open', () => {
    mocks.width = 1099
    draw()
    expect(arm()).toBe('1')
    expect(document.querySelector('[data-fx-inspector]')).toBeNull()
    act(() => setPickedFx(3))
    expect(document.querySelector('[data-fx-inspector="side"]')).not.toBeNull()
    cleanup()
    mocks.width = 760
    draw()
    expect(arm()).toBe('1')
  })

  it('opens no side inspector for + FX under a focused Look layer, whose own sheet takes the FX', () => {
    mocks.width = 1000
    mocks.scope = { kind: 'layer' }
    draw()
    fireEvent.click(within(lane('INTENSITY')).getByRole('button', { name: 'FX' }))
    expect(document.querySelector('[data-fx-inspector]')).toBeNull()
  })

  it('draws one column below 760px, the inspector under the list', () => {
    mocks.width = 759
    draw()
    expect(arm()).toBe('2')
    expect(document.querySelector('[data-fx-inspector="stacked"]')).not.toBeNull()
  })
})
