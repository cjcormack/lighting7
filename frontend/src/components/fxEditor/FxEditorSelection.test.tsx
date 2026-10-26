// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useEffect } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveFx, FxLibraryEntry } from '@/store/fixtureFx'
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
  asks: [] as { fxIds: number[]; solo?: number; window?: unknown; draft?: unknown }[],
  library: [] as Partial<FxLibraryEntry>[],
  /** Draw the real `FxPicker`, for a lane's whole session. */
  realPicker: false,
  pressFx: vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ fxId: 50, stopped: [], narrowed: [] }) })),
  updateFx: vi.fn((_id: number, _req: unknown) => true),
  selectTable: vi.fn(),
  /** `fxTargetOf` answers a group by its name, for the subject's group form. */
  namedGroup: false,
}))

vi.mock('@/components/fx/selectionFxTarget', () => ({
  useWindowFxPair: () => ({ targets: mocks.targets, families: null }),
  fxTargetOf: (targets: CueTarget[]) =>
    targets.length === 0
      ? null
      : mocks.namedGroup
        ? { type: 'group', group: { name: 'Front wash' } }
        : { type: 'selection', targets, fixtures: HEX, label: 'Hex 1, Hex 2' },
}))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: HEX }) }))
vi.mock('@/store/groups', () => ({
  useGroupListQuery: () => ({ data: [] }),
  useApplyGroupFxMutation: () => [vi.fn()],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: mocks.effects }),
  useFxLibraryQuery: () => ({ data: mocks.library }),
  moveLocalFx: (...a: unknown[]) => mocks.moveFx(...a),
  useAddFixtureFxMutation: () => [vi.fn()],
  usePressProgrammerFxMutation: () => [mocks.pressFx],
  useRemoveFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/palettes', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/palettes')>()),
  usePaletteListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/speedMasters', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/speedMasters')>()),
  useSpeedMasterForFamily: () => () => null,
}))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = { updateFx: mocks.updateFx, subscribe: () => ({ unsubscribe: () => {} }), subscribeToErrors: () => ({ unsubscribe: () => {} }) }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})
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
vi.mock('@/components/programmer/ProgrammerTableTabs', () => ({
  ProgrammerTableTabs: () => null,
  useProgrammerTables: () => ({ table: 'fx', select: mocks.selectTable, counts: {} }),
}))
vi.mock('@/hooks/useFixtureColourIndex', () => ({ useFixtureColourIndex: () => new Map([['hex-1', 'red']]) }))
vi.mock('@/components/desk/DeskChip', () => ({ DeskChip: () => null }))
vi.mock('@/components/programmer/RecordLookSheet', () => ({
  RecordLookSheet: ({ open, targets, initialMask, initialFxIds }: { open: boolean; targets: unknown; initialMask: unknown; initialFxIds: number[] }) =>
    open ? <div data-testid="record-look">{JSON.stringify({ targets, initialMask: initialMask === undefined ? 'window' : initialMask, initialFxIds })}</div> : null,
}))
vi.mock('@/components/stage/OwnerEditor', () => ({
  OwnerEditor: ({ entry }: { entry: { kind: string; id: number } }) => <div data-testid="cue-editor">{`${entry.kind}:${entry.id}`}</div>,
}))
/** The attribute a stub picker's taps land on, by the family it opens on. */
const STUB_LANDS: Record<string, string> = { INTENSITY: 'dimmer', COLOUR: 'rgbColour' }
vi.mock('@/components/fx/FxPicker', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/components/fx/FxPicker')>()
  type Props = Parameters<typeof real.FxPicker>[0]
  function Stub({ initialFamily, press, onLanding }: Props) {
    const lands = STUB_LANDS[initialFamily ?? ''] ?? 'dimmer'
    useEffect(() => onLanding?.([lands]), [lands, onLanding])
    return <div data-testid="picker" data-family={initialFamily} data-stack={String(press?.stackOn?.(lands) === true)} />
  }
  return { ...real, FxPicker: (props: Props) => (mocks.realPicker ? <real.FxPicker {...props} /> : <Stub {...props} />) }
})
vi.mock('@/components/fx/SplitFxEditor', async () => {
  const { useFxGraphLink } = await import('@/components/fx/fxGraphLink')
  return {
    SplitFxEditor: ({ effect, layout }: { effect: ActiveFx; layout: string }) => {
      // The live editor's side of the link: an unsent move, and the FX Match offers.
      const link = useFxGraphLink()
      return (
        <>
          <div data-testid="inspector-editor" data-layout={layout}>
            {effect.id}
          </div>
          <button type="button" onClick={() => link?.preview(effect.id, { cycle: 8, parameters: {}, blendMode: 'OVERRIDE', phaseOffset: 0 })}>
            Move
          </button>
          <button type="button" onClick={() => link?.preview(effect.id, null)}>
            Landed
          </button>
          <span data-testid="match-others">{link?.others.map((o) => o.effect.id).join(',')}</span>
        </>
      )
    },
  }
})
vi.mock('./useFxPreview', () => ({
  useFxPreview: (ask: { fxIds: number[]; solo?: number; window?: unknown; draft?: unknown } | null) => {
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
const { getPickedFx, resetFxEditorViewStores, setFxGraphOption, setPickedFx } = await import('@/lib/fxEditorView')
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
  mocks.realPicker = false
  mocks.namedGroup = false
  mocks.pressFx.mockClear()
  mocks.updateFx.mockClear()
  mocks.effects = [
    fx(1, { programmerOwned: false, cueId: 12 }),
    fx(2, { programmerLayerId: 40, paletteId: 3 }),
    fx(3),
    fx(4),
    fx(5, { attribute: 'rgbColour', programmerOwned: false, cueId: 12 }),
  ]
  for (const spy of [mocks.moveFx, mocks.selectHeads, mocks.stop, mocks.pause, mocks.selectTable]) spy.mockClear()
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

  it('asks with the inspector’s unsent move on the picked FX until the desk has it, and offers Match the list’s other FX', () => {
    draw()
    fireEvent.click(within(card(3)).getByRole('button', { name: 'Edit FX 3' }))
    expect(mocks.asks.at(-1)?.draft).toBeUndefined()
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))
    expect(mocks.asks.at(-1)).toMatchObject({ fxIds: [3], draft: { cycle: 8 } })
    fireEvent.click(screen.getByRole('button', { name: 'Landed' }))
    expect(mocks.asks.at(-1)?.draft).toBeUndefined()
    expect(screen.getByTestId('match-others').textContent!.split(',').map(Number).sort()).toEqual([1, 2, 4, 5])
  })

  it('asks for a solo’d FX beside the picked one, solo naming it, and sends the graph’s window', () => {
    draw()
    expect(mocks.asks.at(-1)).toMatchObject({ fxIds: [4], window: { cycles: 2 } })
    expect(mocks.asks.at(-1)?.solo).toBeUndefined()
    fireEvent.click(within(card(3)).getByRole('button', { name: 'Solo FX 3' }))
    expect(mocks.asks.at(-1)).toMatchObject({ fxIds: [4, 3], solo: 1 })
    fireEvent.click(within(card(3)).getByRole('button', { name: 'Unsolo FX 3' }))
    fireEvent.click(within(card(4)).getByRole('button', { name: 'Solo FX 4' }))
    expect(mocks.asks.at(-1)).toMatchObject({ fxIds: [4], solo: 0 })
    act(() => setFxGraphOption('window', '2b'))
    expect(mocks.asks.at(-1)).toMatchObject({ window: { beats: 8 } })
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

  it('never asks about the FX its own session started, and a swap keeps its blend', async () => {
    const colour = (name: string): Partial<FxLibraryEntry> => ({
      name,
      family: 'colour',
      outputType: 'COLOUR',
      fxMode: 'STANDARD',
      timingSource: 'BEAT',
      compatibleAttributes: ['rgbColour'],
      parameters: [],
    })
    mocks.library = [colour('Rainbow Cycle'), colour('Colour Cycle')]
    mocks.realPicker = true
    const { rerender } = draw()
    fireEvent.click(within(lane('COLOUR')).getByRole('button', { name: 'FX' }))
    expect(screen.queryByRole('radio', { name: 'Stack' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Rainbow Cycle' }))
    await waitFor(() => expect(mocks.pressFx).toHaveBeenCalledTimes(1))
    expect(mocks.pressFx.mock.calls[0][0]).not.toHaveProperty('stack')
    // The lanes refetch and now hold the session's own FX: still nothing to ask.
    mocks.effects = [...mocks.effects, fx(50, { fxType: 'RainbowCycle', attribute: 'rgbColour' })]
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    expect(card(50)).not.toBeNull()
    expect(screen.queryByRole('radio', { name: 'Stack' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Colour Cycle' }))
    expect(mocks.updateFx).toHaveBeenCalledWith(50, expect.objectContaining({ fxType: 'Colour Cycle', blendMode: 'OVERRIDE' }))
    expect(mocks.pressFx).toHaveBeenCalledTimes(1)
  })

  it('starts afresh once the selection moves, asking where it applies and swapping nothing on the old heads', async () => {
    const colour = (name: string): Partial<FxLibraryEntry> => ({
      name,
      family: 'colour',
      outputType: 'COLOUR',
      fxMode: 'STANDARD',
      timingSource: 'BEAT',
      compatibleAttributes: ['rgbColour'],
      parameters: [],
    })
    mocks.library = [colour('Rainbow Cycle'), colour('Colour Cycle')]
    mocks.realPicker = true
    const { rerender } = draw()
    fireEvent.click(within(lane('COLOUR')).getByRole('button', { name: 'FX' }))
    fireEvent.click(screen.getByRole('button', { name: 'Rainbow Cycle' }))
    await waitFor(() => expect(mocks.pressFx).toHaveBeenCalledTimes(1))
    mocks.effects = [...mocks.effects, fx(50, { fxType: 'RainbowCycle', attribute: 'rgbColour' })]
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)

    // Hex 2 alone, the picker still open: FX 50 reaches it, so the fresh start asks.
    mocks.targets = [{ type: 'fixture', key: 'hex-2' }]
    mocks.pressFx.mockImplementationOnce(() => ({ unwrap: () => Promise.resolve({ fxId: 51, stopped: [], narrowed: [] }) }))
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    expect(screen.getByRole('radio', { name: 'Replace' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Colour Cycle' }))
    await waitFor(() => expect(mocks.pressFx).toHaveBeenCalledTimes(2))
    expect(mocks.pressFx.mock.calls[1][0]).toEqual(expect.objectContaining({ fxType: 'Colour Cycle', targets: [{ type: 'fixture', key: 'hex-2' }] }))
    expect(mocks.updateFx).not.toHaveBeenCalled()

    // Back on the heads it was started on, a tap swaps FX 50 again.
    mocks.targets = FRONT
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Colour Cycle' }))
    expect(mocks.updateFx).toHaveBeenCalledWith(50, expect.objectContaining({ fxType: 'Colour Cycle' }))
    expect(mocks.pressFx).toHaveBeenCalledTimes(2)
  })
})

describe('with nothing selected', () => {
  it('says so in one line and lists the running FX as cards, by family then name, with what each runs on', () => {
    mocks.targets = []
    mocks.effects = [fx(3, { fxType: 'Pulse' }), fx(5, { fxType: 'Colour Cycle', attribute: 'rgbColour' }), fx(4, { fxType: 'Breath' }), fx(6, { leaving: true })]
    draw()
    const empty = document.querySelector<HTMLElement>('[data-fx-nothing-selected]')!
    expect(empty).toHaveTextContent('No heads selected. Pick a running FX to edit it on its heads.')
    const cards = [...empty.querySelectorAll<HTMLElement>('[data-fx-running-card]')]
    expect(cards.map((c) => c.dataset.fxRunningCard)).toEqual(['4', '3', '5'])
    expect(cards[2]).toHaveTextContent(/^Colour CycleColour/)
    expect(cards[2]).toHaveTextContent('front · 2 fixtures · 1 bar · M1')
  })

  it('selects a card’s heads and picks its FX with one press', () => {
    mocks.targets = []
    draw()
    fireEvent.click(document.querySelector<HTMLElement>('[data-fx-running-card="3"]')!)
    expect(mocks.selectHeads.mock.calls).toEqual([[[{ type: 'group', key: 'front' }], HEX]])
    expect(getPickedFx()).toBe(3)
  })

  it('puts the card’s FX in the inspector in place of a + FX picker left open', () => {
    const { rerender } = draw()
    fireEvent.click(within(lane('INTENSITY')).getByRole('button', { name: 'FX' }))
    expect(screen.getByTestId('picker')).toBeTruthy()
    mocks.targets = []
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    fireEvent.click(document.querySelector<HTMLElement>('[data-fx-running-card="3"]')!)
    mocks.targets = FRONT
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    expect(screen.queryByTestId('picker')).toBeNull()
    expect(screen.getByTestId('inspector-editor')).toHaveTextContent('3')
  })

  it('offers the Values table, where heads are selected, and only that with nothing running', () => {
    mocks.targets = []
    mocks.effects = []
    draw()
    expect(document.querySelector('[data-fx-nothing-selected]')).toHaveTextContent(/^No heads selected\.Select heads in Values$/)
    fireEvent.click(screen.getByRole('button', { name: 'Select heads in Values' }))
    expect(mocks.selectTable).toHaveBeenCalledWith('values')
  })

  it('tells the graph why it is empty: nothing selected, or nothing reaching the selection', () => {
    mocks.targets = []
    draw()
    expect(within(document.querySelector<HTMLElement>('[data-fx-graph]')!).getByText('Select heads to draw their FX.')).toBeTruthy()
    cleanup()
    mocks.targets = FRONT
    mocks.effects = []
    draw()
    expect(within(document.querySelector<HTMLElement>('[data-fx-graph]')!).getByText('No FX reaches the selection.')).toBeTruthy()
  })
})

describe('the subject', () => {
  const subject = () => document.querySelector<HTMLElement>('[data-fx-subject]')!

  it('reads a group as its name and how many fixtures, with no list to open', () => {
    mocks.namedGroup = true
    draw()
    expect(subject()).toHaveTextContent(/^Front wash· 2 fixtures/)
    expect(subject().querySelector('[data-fx-subject-fixtures]')).toBeNull()
  })

  it('reads several fixtures as a count that opens their list, the mask pill beside it', () => {
    mocks.targets = HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))
    draw()
    const chip = subject().querySelector<HTMLElement>('[data-fx-subject-fixtures]')!
    expect(chip).toHaveTextContent('2 fixtures')
    expect(subject()).toHaveTextContent('All')
    fireEvent.pointerDown(chip, { button: 0, ctrlKey: false })
    const rows = screen.getAllByRole('menuitem')
    expect(rows.map((r) => r.textContent)).toEqual(['Hex 1Only', 'Hex 2Only'])
    expect(rows[0].querySelector('[aria-hidden]')!.getAttribute('style')).toContain('--tag-red')
    expect(rows[1].querySelector('[aria-hidden]')!.getAttribute('style')).toBeNull()
  })

  it('narrows the selection to one fixture with Only', () => {
    mocks.targets = HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))
    draw()
    fireEvent.pointerDown(subject().querySelector<HTMLElement>('[data-fx-subject-fixtures]')!, { button: 0, ctrlKey: false })
    fireEvent.click(screen.getByRole('menuitem', { name: /Hex 2/ }))
    expect(mocks.selectHeads.mock.calls).toEqual([[[{ type: 'fixture', key: 'hex-2' }], HEX]])
  })

  it('hands focus to the mask once Only has taken the chip away', () => {
    mocks.targets = HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))
    const { rerender } = draw()
    fireEvent.pointerDown(subject().querySelector<HTMLElement>('[data-fx-subject-fixtures]')!, { button: 0, ctrlKey: false })
    fireEvent.click(screen.getByRole('menuitem', { name: /Hex 2/ }))
    act(() => (document.activeElement as HTMLElement | null)?.blur())
    mocks.targets = [{ type: 'fixture', key: 'hex-2' }]
    rerender(<FxEditorSelection projectId={1} viewSwitch={null} />)
    expect(subject().querySelector('[data-fx-subject-fixtures]')).toBeNull()
    expect(document.activeElement).toBe(subject().querySelector('[data-fx-subject-mask]'))
  })

  it('opens the list as a bottom sheet below the 760 arm', () => {
    mocks.width = 390
    mocks.targets = HEX.map((f) => ({ type: 'fixture' as const, key: f.key }))
    draw()
    fireEvent.click(subject().querySelector<HTMLElement>('[data-fx-subject-fixtures]')!)
    const sheet = screen.getByRole('menu', { name: 'Selected fixtures' })
    expect(within(sheet).getAllByRole('menuitem').map((r) => r.className)).toEqual([expect.stringContaining('min-h-11'), expect.stringContaining('min-h-11')])
    // A finger has no hover, so every row says Only.
    expect(within(sheet).getAllByText('Only').map((o) => o.className)).toEqual([expect.not.stringContaining('opacity-0'), expect.not.stringContaining('opacity-0')])
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
    // Each row sizes to its content there, so the graph under the list never draws over it.
    expect(document.querySelector('[data-fx-list]')!.parentElement!.className).toContain('auto-rows-max')
  })
})

describe('the phone arm (§4.2)', () => {
  it('folds the list to a row of lane chips, one lane opened under them, the graph then the inspector below', () => {
    mocks.width = 390
    draw()
    const chips = [...document.querySelectorAll<HTMLElement>('[data-fx-lane-chip]')]
    expect(chips.map((c) => [c.dataset.fxLaneChip, c.textContent])).toEqual([
      ['INTENSITY', 'Intensity4'],
      ['COLOUR', 'Colour1'],
    ])
    expect(document.querySelector('[data-fx-lane]')).toBeNull()
    fireEvent.click(chips[1])
    expect([...document.querySelectorAll<HTMLElement>('[data-fx-lane]')].map((l) => l.dataset.fxLane)).toEqual(['COLOUR'])
    expect(laneIds('COLOUR')).toEqual([5])
    expect(chips[1]).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(chips[1])
    expect(document.querySelector('[data-fx-lane]')).toBeNull()
    // List, graph, inspector: one column, in that order.
    const list = document.querySelector('[data-fx-list]')!
    const graph = document.querySelector('[data-fx-graph]')!
    const inspector = document.querySelector('[data-fx-inspector="stacked"]')!
    expect(list.compareDocumentPosition(graph) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(graph.compareDocumentPosition(inspector) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('lights the chip of the lane holding the picked FX', () => {
    mocks.width = 390
    act(() => setPickedFx(5))
    draw()
    expect(document.querySelector('[data-fx-lane-chip="COLOUR"]')!.className).toContain('border-violet')
    expect(document.querySelector('[data-fx-lane-chip="INTENSITY"]')!.className).not.toContain('border-violet')
  })

  it('draws no lane chips from 760px', () => {
    mocks.width = 760
    draw()
    expect(document.querySelector('[data-fx-lane-chips]')).toBeNull()
  })
})
