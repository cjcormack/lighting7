// @vitest-environment jsdom
import { act, fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { useRef, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cue, CueAdHocEffect, CueLayer, CueLayerDetail, CueTarget } from '@/api/cuesApi'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { LayerHandlers } from '@/components/looks/LookStack'

/**
 * The cue editor's Looks and FX tabs (show-cue-editing plan session 6): each gesture one whole-list
 * write of the cue's `layers` or `adHocEffects`, every `sortOrder` restated, built from the cue as
 * the desk holds it — and **nothing sent to the stage** (P2). The store and the real `lightingApi`
 * are mounted over a socket that records every frame, so P2's test reads the wire itself.
 *
 * `LookStack` is a stand-in that hands its handlers out (its own rows are `LookStack.test.tsx`'s);
 * the FX tab draws the real `FxPicker`, in draft, and the real `FxEffectFields`.
 */

const socket = vi.hoisted(() => {
  const sent: { type?: string }[] = []
  return {
    sent,
    conn: {
      baseUrl: '/api/',
      readyState: () => 1,
      send: (payload: string) => {
        sent.push(JSON.parse(payload))
        return true
      },
      reconnect: () => {},
      subscribe: () => ({ unsubscribe: () => {} }),
    },
  }
})
vi.mock('@/api/internalApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/internalApi')>()),
  createInternalApiConnection: () => socket.conn,
}))

const data = vi.hoisted(() => ({
  cue: null as Cue | null,
  /** The cue as the desk holds it, where it has moved past the cache. */
  desk: null as Cue | null,
  patches: [] as Record<string, unknown>[],
  presses: [] as Record<string, unknown>[],
  /** What the desk answers a press with: the cue as stored after it. */
  pressed: null as Cue | null,
  /** While set, a PATCH after the first waits for it — a write in flight. */
  hold: null as Promise<void> | null,
  /** While set, the next PATCH is refused. */
  failNext: false,
  selection: [] as CueTarget[],
  library: [] as EffectLibraryEntry[],
  addFx: vi.fn(),
}))
vi.mock('@/store/cues', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/cues')>()),
  useProjectCueQuery: () => ({ data: data.cue ?? undefined, fulfilledTimeStamp: 1 }),
  useProjectCueCookedQuery: ({ cueId }: { cueId: number }) => ({ data: { cueId, rows: [] }, fulfilledTimeStamp: 1, isSuccess: true }),
  fetchLatestCue: () => Promise.resolve(data.desk ?? data.cue!),
  usePatchProjectCueMutation: () => [
    (arg: Record<string, unknown>) => {
      data.patches.push(arg)
      if (data.failNext) {
        data.failNext = false
        return { unwrap: () => Promise.reject(new Error('refused')) }
      }
      const wait = data.patches.length > 1 ? data.hold : null
      return { unwrap: () => (wait ?? Promise.resolve()).then(() => ({})) }
    },
  ],
  usePressCueLayerMutation: () => [
    (arg: Record<string, unknown>) => {
      data.presses.push(arg)
      return { unwrap: () => Promise.resolve({ action: 'applied', cue: data.pressed ?? data.cue! }) }
    },
  ],
}))
vi.mock('./CueValuesView', () => ({
  useCueValuesView: () => ({ selectionTargets: data.selection }),
  CueValuesViewProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
const stack = vi.hoisted(() => ({ handlers: null as LayerHandlers | null, props: null as Record<string, unknown> | null }))
vi.mock('@/components/looks/LookStack', () => ({
  LookStack: (props: { handlers: LayerHandlers; addActions?: ReactNode; layers: CueLayerDetail[]; targetsRefusal?: string | null }) => {
    stack.handlers = props.handlers
    stack.props = props as never
    return (
      <div data-testid="stack">
        {props.addActions}
        {props.layers.map((l, i) => (
          <span key={i} data-layer={l.source?.name} data-enabled={String(l.enabled !== false)} />
        ))}
      </div>
    )
  },
}))
// The one picker, standing in: it presses through the real `usePress`, so the cue arm is what runs.
const picker = vi.hoisted(() => ({ props: null as { library: string; pair: { targets: readonly CueTarget[] } } | null }))
vi.mock('@/components/press/ApplyPicker', async () => {
  const { usePress } = await import('@/hooks/usePress')
  return {
    ApplyPicker: (props: { projectId: number; library: string; pair: { targets: readonly CueTarget[]; families: null } }) => {
      picker.props = props
      const { press, into } = usePress(props.projectId, props.pair)
      return (
        <div data-testid="picker" data-into={into.kind === 'cue' ? into.cueLabel : into.kind}>
          <button type="button" onClick={() => void press({ kind: 'look', look: { id: 5, name: 'Cool' } }, 'press')}>
            press Cool
          </button>
        </div>
      )
    },
  }
})
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandCueLayerStrip: () => null }))
vi.mock('@/store/looks', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/looks')>()),
  useLookListQuery: () => ({ data: [{ id: 4, uuid: 'u4', name: 'Warm', families: [], rowCount: 1, effectCount: 1, targetCount: 1 }] }),
  useLookQuery: () => ({
    data: {
      id: 4,
      effects: [{ targetType: 'deferred', targetKey: '', effectType: 'Pulse', category: 'intensity', propertyName: 'dimmer', beatDivision: 1, blendMode: 'OVERRIDE', distribution: 'NORMAL', phaseOffset: 0, parameters: {} }],
    },
  }),
}))
vi.mock('@/store/templates', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/templates')>()),
  useTemplateListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/fixtureFx', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtureFx')>()),
  useEffectLibraryQuery: () => ({ data: data.library }),
  useActiveEffectsQuery: () => ({ data: [] }),
  useAddFixtureFxMutation: () => [data.addFx],
  useRemoveFxMutation: () => [data.addFx],
}))
vi.mock('@/store/groups', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/groups')>()),
  useGroupListQuery: () => ({ data: [] }),
  useApplyGroupFxMutation: () => [data.addFx],
  useRemoveGroupFxMutation: () => [data.addFx],
}))
vi.mock('@/store/speedMasters', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/speedMasters')>()),
  useSpeedMasterLiveQuery: () => ({ data: [], master: undefined }),
  useSpeedMasterDisplay: () => null,
  useSpeedMasterForCategory: () => () => null,
  useMaster1Uuid: () => 'm1',
}))
vi.mock('@/store/status', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/status')>()),
  useIsDeskConnected: () => true,
}))
const rig = vi.hoisted(() => ({ fixtures: [] as unknown[] }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: rig.fixtures, fixtureByKey: new Map((rig.fixtures as { key: string }[]).map((f) => [f.key, f])) }),
}))
vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof import('sonner')>()
  const toast = Object.assign(() => {}, { ...actual.toast, dismiss: () => {}, error: () => {} })
  return { ...actual, toast }
})

import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'
import { store } from '@/store'
import { resetKeyRegion } from '@/components/sheet/keyRegion'
import { CueRowStoreProvider, useCueRowSaveState, useCueRowStore, type CueRowStoreValue } from './CueRowStore'
import { usePressCue, type PressCue } from '@/components/press/pressCue'
import { CueLooksTab } from './CueLooksTab'
import { CueEffectsTab } from './CueEffectsTab'
import { moveItem } from './cueListEdits'
import { cueEffectDraftOf, withCueEffectDraft } from '@/components/fx/fxEditorModel'

const PULSE: EffectLibraryEntry = {
  name: 'Pulse',
  category: 'intensity',
  outputType: 'SLIDER',
  effectMode: 'STANDARD',
  timingSource: 'BEAT',
  compatibleProperties: ['dimmer'],
  parameters: [
    { name: 'min', type: 'ubyte', defaultValue: '0', description: '' },
    { name: 'max', type: 'ubyte', defaultValue: '255', description: '' },
  ],
}
const SINE: EffectLibraryEntry = { ...PULSE, name: 'SineWave' }

const WARM = { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Warm' } as const
const COOL = { kind: 'LOOK', id: 5, uuid: 'u5', name: 'Cool' } as const
const layer = (source: typeof WARM | typeof COOL, sortOrder: number): CueLayerDetail => ({
  lookId: source.id,
  sortOrder,
  enabled: true,
  targets: [],
  blendMode: 'OVERRIDE',
  amount: 1,
  source,
})
const pulse = (over: Partial<CueAdHocEffect> = {}): CueAdHocEffect => ({
  targetType: 'fixture',
  targetKey: 'orbit-sr',
  effectType: 'Pulse',
  category: 'intensity',
  propertyName: 'dimmer',
  beatDivision: 2,
  blendMode: 'OVERRIDE',
  distribution: 'NORMAL',
  phaseOffset: 0,
  elementMode: null,
  elementFilter: null,
  stepTiming: null,
  parameters: { min: '0', max: '255' },
  seconds: true,
  size: 0.5,
  direction: 'BOUNCE',
  nShot: 3,
  spread: 0.5,
  wings: 2,
  blocks: 2,
  groups: 3,
  delayMs: 500,
  sortOrder: 0,
  uuid: 'e-1',
  ...over,
})

function cueOf(over: Partial<Cue> = {}): Cue {
  return {
    id: 1,
    name: 'Cue 1',
    cueNumber: '1',
    cueNumberAuto: false,
    layers: [],
    adHocEffects: [],
    propertyAssignments: [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }],
    triggers: [],
    cueStackId: 7,
    cueStackName: 'Act 1',
    entryId: 101,
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
    ...over,
  } as Cue
}

function Editor({ tab, readOnly = false, onRequestUnlock }: { tab: 'looks' | 'fx' | 'both'; readOnly?: boolean; onRequestUnlock?: () => void }) {
  const ref = useRef<HTMLDivElement | null>(null)
  const cue = data.cue!
  const props = { cue, projectId: 1, readOnlyReason: readOnly ? 'Locked' : null, region: ref, onRequestUnlock }
  return (
    <CueRowStoreProvider projectId={1} cueId={1} readOnly={readOnly}>
      <div ref={ref} data-key-region data-testid="editor">
        {tab !== 'fx' && <CueLooksTab {...props} />}
        {tab !== 'looks' && <CueEffectsTab {...props} />}
      </div>
    </CueRowStoreProvider>
  )
}

function render(ui: ReactNode) {
  return rtlRender(
    <Provider store={store}>
      <MemoryRouter>{ui}</MemoryRouter>
    </Provider>,
  )
}

async function patched(count: number) {
  await waitFor(() => expect(data.patches).toHaveLength(count), { timeout: 3000 })
  return data.patches[count - 1]
}

window.matchMedia ??= ((query: string) =>
  ({ matches: false, media: query, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false }) as unknown as MediaQueryList) as typeof window.matchMedia
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

beforeEach(() => {
  rig.fixtures = [makeFixture('orbit-sr', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Orbit SR' })]
  data.cue = null
  data.desk = null
  data.patches.length = 0
  data.presses.length = 0
  data.pressed = null
  data.hold = null
  data.failNext = false
  data.selection = []
  data.library = [PULSE, SINE]
  data.addFx.mockReset()
  socket.sent.length = 0
  stack.handlers = null
  picker.props = null
  resetKeyRegion()
})
afterEach(() => vi.clearAllMocks())

describe('the Looks tab', () => {
  it('a reorder is one layers write restating every sortOrder, built on the cue as the desk holds it', async () => {
    data.cue = cueOf({ layers: [layer(WARM, 0), layer(COOL, 1)] })
    // Another client set Cool's amount since this window read the cue.
    data.desk = cueOf({ layers: [layer(WARM, 0), { ...layer(COOL, 1), amount: 0.4 }] })
    render(<Editor tab="looks" />)
    act(() => stack.handlers!.onMove(0, 1))
    const body = await patched(1)
    expect(Object.keys(body).sort()).toEqual(['cueId', 'layers', 'projectId'])
    expect(body.layers).toEqual([
      expect.objectContaining({ lookId: 5, sortOrder: 0, amount: 0.4 }),
      expect.objectContaining({ lookId: 4, sortOrder: 1 }),
    ])
    // The read-only `source` never goes back.
    expect((body.layers as Record<string, unknown>[]).some((l) => 'source' in l)).toBe(false)
    // Drawn moved before the desk answers.
    expect(screen.getByTestId('stack').querySelectorAll('[data-layer]')[0].getAttribute('data-layer')).toBe('Cool')
  })

  it('finds a gesture’s layer by its id when the desk’s list has moved', async () => {
    data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1 }, { ...layer(COOL, 1), id: 2 }] })
    // Another client put a layer in front.
    data.desk = cueOf({ layers: [{ ...layer(WARM, 0), lookId: 9, id: 3 }, { ...layer(WARM, 1), id: 1 }, { ...layer(COOL, 2), id: 2 }] })
    render(<Editor tab="looks" />)
    act(() => stack.handlers!.onSetEnabled(1, false))
    expect(((await patched(1)).layers as CueLayer[]).map((l) => [l.lookId, l.enabled !== false])).toEqual([
      [9, true],
      [4, true],
      [5, false],
    ])
    act(() => stack.handlers!.onRemove(1))
    expect(((await patched(2)).layers as CueLayer[]).map((l) => l.lookId)).toEqual([9, 4])
    // And the ids never go back.
    expect((data.patches[1].layers as Record<string, unknown>[]).some((l) => 'id' in l)).toBe(false)
  })

  it('keeps drawing a gesture while a write is in flight, through a refetch between two writes', async () => {
    const C = { kind: 'LOOK', id: 6, uuid: 'u6', name: 'Amber' } as const
    data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1 }, { ...layer(COOL, 1), id: 2 }, { ...layer(C as never, 2), id: 3 }] })
    let release: () => void = () => {}
    data.hold = new Promise<void>((r) => (release = r))
    const view = render(<Editor tab="looks" />)
    act(() => stack.handlers!.onRemove(1))
    await waitFor(() => expect(data.patches).toHaveLength(1))
    // The refetch after it lands while the next write is still on its way.
    act(() => stack.handlers!.onRemove(1))
    data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1 }, { ...layer(C as never, 1), id: 3 }] })
    view.rerender(
      <Provider store={store}>
        <MemoryRouter>
          <Editor tab="looks" />
        </MemoryRouter>
      </Provider>,
    )
    const drawn = () => [...screen.getByTestId('stack').querySelectorAll('[data-layer]')].map((e) => e.getAttribute('data-layer'))
    expect(drawn()).toEqual(['Warm'])
    release()
    await waitFor(() => expect(data.patches).toHaveLength(2))
  })

  it('stores a disabled layer', async () => {
    data.cue = cueOf({ layers: [layer(WARM, 0), layer(COOL, 1)] })
    render(<Editor tab="looks" />)
    act(() => stack.handlers!.onSetEnabled(1, false))
    const body = await patched(1)
    expect((body.layers as CueLayer[]).map((l) => [l.lookId, l.enabled, l.sortOrder])).toEqual([
      [4, true, 0],
      [5, false, 1],
    ])
  })

  it('Targets takes the Values selection; + Look opens the picker on it, whose press writes the cue', async () => {
    data.cue = cueOf({ layers: [layer(WARM, 0)] })
    const view = render(<Editor tab="looks" />)
    expect(stack.props!.targetsRefusal).toMatch(/Select fixtures or groups in Values/)
    data.selection = [{ type: 'group', key: 'Front' }, { type: 'fixture', key: 'orbit-sr' }]
    view.rerender(
      <Provider store={store}>
        <MemoryRouter>
          <Editor tab="looks" />
        </MemoryRouter>
      </Provider>,
    )
    expect(stack.props!.targetsRefusal).toBeNull()
    act(() => stack.handlers!.onSetTargets!(0))
    expect(((await patched(1)).layers as CueLayer[])[0].targets).toEqual(data.selection)

    fireEvent.click(screen.getByRole('button', { name: 'Look' }))
    expect(picker.props!.library).toBe('looks')
    expect(picker.props!.pair.targets).toEqual(data.selection)
    expect(screen.getByTestId('picker').getAttribute('data-into')).toBe('Q1')
    fireEvent.click(screen.getByRole('button', { name: 'press Cool' }))
    await waitFor(() => expect(data.presses).toHaveLength(1))
    // The desk applies the rule to the stored layers: the client sends the press, not a list.
    expect(data.presses[0]).toEqual({ projectId: 1, cueId: 1, lookId: 5, targets: data.selection })
    expect(data.patches).toHaveLength(1)
  })

  it('the layer settings write a layer’s timing and masters, an existing one and one the picker just put on', async () => {
    data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1 }] })
    data.selection = [{ type: 'fixture', key: 'orbit-sr' }]
    const view = render(<Editor tab="looks" />)
    act(() => stack.handlers!.onSetTiming!(0, { delayMs: 1500, intervalMs: null, randomWindowMs: null }))
    expect(((await patched(1)).layers as CueLayer[])[0]).toEqual(expect.objectContaining({ lookId: 4, delayMs: 1500 }))
    data.desk = cueOf({ layers: [{ ...layer(WARM, 0), id: 1, delayMs: 1500 }] })
    act(() => stack.handlers!.onSetTiming!(0, { speedMasterUuid: 'm2' }))
    expect(((await patched(2)).layers as CueLayer[])[0]).toEqual(expect.objectContaining({ delayMs: 1500, speedMasterUuid: 'm2' }))

    // + Look puts Cool on; the cue as the desk answers it has the new layer, untimed.
    data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1, delayMs: 1500, speedMasterUuid: 'm2' }] })
    data.desk = null
    data.pressed = cueOf({ layers: [...data.cue.layers, { ...layer(COOL, 1), id: 2 }] })
    fireEvent.click(screen.getByRole('button', { name: 'Look' }))
    fireEvent.click(screen.getByRole('button', { name: 'press Cool' }))
    await waitFor(() => expect(data.presses).toHaveLength(1))
    data.cue = data.pressed
    view.rerender(
      <Provider store={store}>
        <MemoryRouter>
          <Editor tab="looks" />
        </MemoryRouter>
      </Provider>,
    )
    act(() => stack.handlers!.onSetTiming!(1, { intervalMs: 2000 }))
    act(() => stack.handlers!.onSetTiming!(1, { rateSpeedMasterUuid: 'm3' }))
    const layers = (await patched(3)).layers as CueLayer[]
    expect(layers[1]).toEqual(expect.objectContaining({ lookId: 5, intervalMs: 2000, rateSpeedMasterUuid: 'm3' }))
    expect(layers[0]).toEqual(expect.objectContaining({ lookId: 4, delayMs: 1500, speedMasterUuid: 'm2' }))
  })

  it('locked, writes nothing and a ⌫ in the editor asks to unlock — the transport never sees it', async () => {
    data.cue = cueOf({ layers: [layer(WARM, 0)] })
    const unlock = vi.fn()
    const back = vi.fn()
    const transport = (e: KeyboardEvent) => {
      if (!e.defaultPrevented && e.key === 'Backspace') back()
    }
    window.addEventListener('keydown', transport)
    try {
      render(<Editor tab="both" readOnly onRequestUnlock={unlock} />)
      expect(stack.props!.readOnly).toBe(true)
      act(() => stack.handlers!.onSetEnabled(0, false))
      fireEvent.pointerDown(screen.getByTestId('editor'))
      fireEvent.keyDown(window, { key: 'Backspace' })
      expect(back).not.toHaveBeenCalled()
      fireEvent.click(await screen.findByRole('button', { name: /unlock/i }))
      expect(unlock).toHaveBeenCalledTimes(1)
      await new Promise((r) => setTimeout(r, 500))
      expect(data.patches).toHaveLength(0)
    } finally {
      window.removeEventListener('keydown', transport)
    }
  })
})

describe('the FX tab', () => {
  it('+ Effect adds in draft — no fx.add — and a second tap swaps it', async () => {
    data.cue = cueOf()
    data.selection = [{ type: 'fixture', key: 'orbit-sr' }]
    render(<Editor tab="fx" />)
    fireEvent.click(screen.getByRole('button', { name: /FX/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    const first = (await patched(1)).adHocEffects as CueAdHocEffect[]
    expect(first).toEqual([
      expect.objectContaining({ targetType: 'fixture', targetKey: 'orbit-sr', effectType: 'Pulse', propertyName: 'dimmer', sortOrder: 0 }),
    ])
    // The desk now holds the Pulse; the cache has not caught up.
    data.desk = cueOf({ adHocEffects: [{ ...first[0], uuid: 'e-new' }] })
    fireEvent.click(screen.getByRole('button', { name: 'SineWave' }))
    const second = (await patched(2)).adHocEffects as CueAdHocEffect[]
    // Swapped in place, not appended beside it.
    expect(second.map((e) => e.effectType)).toEqual(['SineWave'])
    expect(data.addFx).not.toHaveBeenCalled()
    expect(socket.sent.filter((f) => /fx/i.test(f.type ?? ''))).toEqual([])
  })

  it('an effect over two rows is stored as one selection effect', async () => {
    rig.fixtures = [
      makeFixture('orbit-sr', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Orbit SR' }),
      makeFixture('orbit-sl', [sliderProp('dimmer', 'dimmer', chan(11))], { name: 'Orbit SL' }),
    ]
    data.cue = cueOf()
    data.selection = [{ type: 'fixture', key: 'orbit-sr' }, { type: 'fixture', key: 'orbit-sl' }]
    render(<Editor tab="fx" />)
    fireEvent.click(screen.getByRole('button', { name: /FX/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    const [stored] = (await patched(1)).adHocEffects as CueAdHocEffect[]
    expect(stored.targetType).toBe('selection')
    expect(stored.targetKey).toBe('[{"key":"orbit-sr","type":"fixture"},{"key":"orbit-sl","type":"fixture"}]')
  })

  it('an edit keeps Seconds, size, direction, runs and fire timing, and sends the effect without its uuid', async () => {
    data.cue = cueOf({ adHocEffects: [pulse()] })
    render(<Editor tab="fx" />)
    fireEvent.click(screen.getByRole('button', { name: /Open Pulse/ }))
    const high = screen.getByRole('spinbutton', { name: /^High/ })
    fireEvent.change(high, { target: { value: '50' } })
    fireEvent.keyDown(high, { key: 'Enter' })
    const [edited] = (await patched(1)).adHocEffects as CueAdHocEffect[]
    expect(edited).toMatchObject({ seconds: true, size: 0.5, direction: 'BOUNCE', nShot: 3, spread: 0.5, wings: 2, blocks: 2, groups: 3, delayMs: 500, beatDivision: 2 })
    expect(Number(edited.parameters.max)).toBe(128)
    expect('uuid' in edited).toBe(false)
  })

  it('a reorder keeps every field of every effect, the spread shape included', () => {
    const list = [pulse(), pulse({ effectType: 'SineWave', uuid: 'e-2', sortOrder: 1, seconds: false, size: 1, direction: 'FORWARD', nShot: null })]
    const moved = moveItem(list, 0, 1)!
    expect(moved.map((e) => [e.effectType, e.sortOrder])).toEqual([
      ['SineWave', 0],
      ['Pulse', 1],
    ])
    expect(moved[1]).toEqual({ ...list[0], sortOrder: 1 })
    // And the draft round trip leaves an untouched effect as it was stored (an absent master is null: master 1).
    expect(withCueEffectDraft(list[0], cueEffectDraftOf(list[0], 'BEAT'))).toEqual({ ...list[0], speedMasterUuid: null, rateSpeedMasterUuid: null })
  })

  it('finds the effect a gesture was made on by its uuid when the desk’s list has moved, and leaves one it no longer holds', async () => {
    data.cue = cueOf({ adHocEffects: [pulse(), pulse({ effectType: 'SineWave', uuid: 'e-2', sortOrder: 1 })] })
    // Another client put a Strobe in front.
    data.desk = cueOf({
      adHocEffects: [pulse({ effectType: 'DimmerStrobe', uuid: 'e-0', sortOrder: 0 }), pulse({ sortOrder: 1 }), pulse({ effectType: 'SineWave', uuid: 'e-2', sortOrder: 2 })],
    })
    render(<Editor tab="fx" />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove SineWave' }))
    expect(((await patched(1)).adHocEffects as CueAdHocEffect[]).map((e) => e.effectType)).toEqual(['DimmerStrobe', 'Pulse'])
    // Another client deleted the Pulse: an edit of it writes nothing, and the header is not left Unsaved.
    data.desk = cueOf({ adHocEffects: [pulse({ effectType: 'DimmerStrobe', uuid: 'e-0', sortOrder: 0 })] })
    fireEvent.click(screen.getByRole('button', { name: /Open Pulse/ }))
    const high = screen.getByRole('spinbutton', { name: /^High/ })
    fireEvent.change(high, { target: { value: '50' } })
    fireEvent.keyDown(high, { key: 'Enter' })
    await new Promise((r) => setTimeout(r, 300))
    expect(data.patches).toHaveLength(1)
  })

  it('locked, a row is no focusable button, so a ⌫ after clicking in it still asks to unlock', async () => {
    data.cue = cueOf({ adHocEffects: [pulse()] })
    const unlock = vi.fn()
    render(<Editor tab="fx" readOnly onRequestUnlock={unlock} />)
    const row = document.querySelector('[data-cue-effect-row]') as HTMLElement
    expect(row.getAttribute('role')).toBeNull()
    expect(row.hasAttribute('tabindex')).toBe(false)
    fireEvent.pointerDown(row)
    fireEvent.keyDown(row, { key: 'Backspace' })
    fireEvent.click(await screen.findByRole('button', { name: /unlock/i }))
    expect(unlock).toHaveBeenCalledTimes(1)
  })

  it('opens a row’s editor only once the effect library has, so Seconds is read from it', () => {
    data.library = undefined as never
    data.cue = cueOf({ adHocEffects: [pulse()] })
    render(<Editor tab="fx" />)
    fireEvent.click(screen.getByRole('button', { name: /Open Pulse/ }))
    expect(screen.getByText('Reading the FX library…')).toBeTruthy()
    expect(document.querySelector('[data-cue-effect-editor]')).toBeNull()
  })

  it('lists a Look layer’s deferred effects under the Look, read-only', () => {
    data.cue = cueOf({ layers: [layer(WARM, 0)] })
    render(<Editor tab="fx" />)
    const section = screen.getByText(/From Warm/).parentElement!
    expect(within(section).getByText('Pulse')).toBeTruthy()
  })
})

it('a bare effect picked from a band appends after every effect, and a second pick swaps it and nothing else', async () => {
  const a = pulse({ effectType: 'DimmerStrobe', uuid: 'e-a', sortOrder: 0 })
  const b = pulse({ effectType: 'Flicker', uuid: 'e-b', sortOrder: 1 })
  data.cue = cueOf({ adHocEffects: [a, b] })
  const probe: { cue?: PressCue | null } = {}
  function Probe() {
    probe.cue = usePressCue()
    return null
  }
  render(
    <CueRowStoreProvider projectId={1} cueId={1} readOnly={false}>
      <Probe />
    </CueRowStoreProvider>,
  )
  const first = pulse()
  act(() => void probe.cue!.pickEffect(first, null))
  const appended = (await patched(1)).adHocEffects as CueAdHocEffect[]
  expect(appended.map((e) => [e.effectType, e.sortOrder])).toEqual([['DimmerStrobe', 0], ['Flicker', 1], ['Pulse', 2]])
  data.desk = cueOf({ adHocEffects: appended.map((e, i) => ({ ...e, uuid: e.uuid ?? `e-${i}` })) })
  act(() => void probe.cue!.pickEffect(pulse({ effectType: 'SineWave' }), first))
  const swapped = (await patched(2)).adHocEffects as CueAdHocEffect[]
  expect(swapped.map((e) => e.effectType)).toEqual(['DimmerStrobe', 'Flicker', 'SineWave'])
})

it('a press after a save that failed leaves Save failed standing', async () => {
  data.cue = cueOf()
  const probe: { cue?: PressCue | null; state?: string } = {}
  function Probe() {
    probe.cue = usePressCue()
    probe.state = useCueRowSaveState()
    return null
  }
  render(
    <CueRowStoreProvider projectId={1} cueId={1} readOnly={false}>
      <Probe />
    </CueRowStoreProvider>,
  )
  data.failNext = true
  void probe.cue!.pickEffect(pulse(), null)
  await act(async () => void (await probe.cue!.pressLayer({ templateId: 2 }, [{ type: 'fixture', key: 'hex-1' }], null)))
  expect(data.presses).toHaveLength(1)
  expect(probe.state).toBe('error')
})

it('a read-only cue stores no picked effect', async () => {
  data.cue = cueOf()
  const probe: { cue?: PressCue | null } = {}
  function Probe() {
    probe.cue = usePressCue()
    return null
  }
  render(
    <CueRowStoreProvider projectId={1} cueId={1} readOnly>
      <Probe />
    </CueRowStoreProvider>,
  )
  await expect(probe.cue!.pickEffect(pulse(), null)).resolves.toBe(false)
  expect(data.patches).toHaveLength(0)
})

it('one flush carries the rows and a list in one PATCH, and one Undo puts both back', async () => {
  data.cue = cueOf({ layers: [{ ...layer(WARM, 0), id: 1 }, { ...layer(COOL, 1), id: 2 }] })
  const probe: { store?: CueRowStoreValue | null } = {}
  function Probe() {
    probe.store = useCueRowStore()
    return null
  }
  render(
    <CueRowStoreProvider projectId={1} cueId={1} readOnly={false}>
      <Probe />
    </CueRowStoreProvider>,
  )
  act(() => {
    probe.store!.setValue('fixture', 'orbit-sr', 'dimmer', '200')
    void probe.store!.editLayers((list) => list.map((l, i) => ({ ...l, enabled: i !== 0 })))
  })
  const body = await patched(1)
  expect(Object.keys(body).sort()).toEqual(['cueId', 'layers', 'projectId', 'propertyAssignments'])
  // The desk now holds what that save wrote.
  data.desk = cueOf({
    layers: [{ ...layer(WARM, 0), id: 1, enabled: false }, { ...layer(COOL, 1), id: 2 }],
    propertyAssignments: body.propertyAssignments as Cue['propertyAssignments'],
  })
  act(() => probe.store!.undo())
  const undone = await patched(2)
  expect((undone.layers as CueLayer[]).map((l) => l.enabled)).toEqual([true, true])
  expect((undone.propertyAssignments as Cue['propertyAssignments']).map((r) => r.value)).toEqual(['10'])
})

it('sends no programmer, channel or effect frame over a full pass of both tabs (P2)', async () => {
  data.cue = cueOf({ layers: [layer(WARM, 0), layer(COOL, 1)], adHocEffects: [pulse()] })
  data.selection = [{ type: 'fixture', key: 'orbit-sr' }]
  render(<Editor tab="both" />)
  act(() => {
    stack.handlers!.onMove(0, 1)
    stack.handlers!.onSetEnabled(0, false)
    stack.handlers!.onSetAmount(1, 0.5)
    stack.handlers!.onSetBlendMode(1, 'MAX')
    stack.handlers!.onSetPropertyMask(1, 'COLOUR')
    stack.handlers!.onSetStomp(1, true)
    stack.handlers!.onSetTargets!(0)
  })
  fireEvent.click(screen.getByRole('button', { name: /Open Pulse/ }))
  const high = screen.getByRole('spinbutton', { name: /^High/ })
  fireEvent.change(high, { target: { value: '40' } })
  fireEvent.keyDown(high, { key: 'Enter' })
  fireEvent.click(screen.getByRole('button', { name: 'FX' }))
  fireEvent.click(screen.getByRole('button', { name: 'SineWave' }))
  act(() => stack.handlers!.onRemove(0))
  fireEvent.click(screen.getByRole('button', { name: 'Look' }))
  fireEvent.click(screen.getByRole('button', { name: 'press Cool' }))
  await waitFor(() => expect(data.presses).toHaveLength(1))
  await waitFor(() => expect(data.patches.length).toBeGreaterThanOrEqual(3), { timeout: 3000 })
  // Every write is the cue's layers or effects, never its own rows.
  expect(data.patches.every((p) => !('propertyAssignments' in p))).toBe(true)
  const banned = socket.sent.filter(
    (frame) =>
      /^(programmer|channels|fx)\./.test(frame.type ?? '') ||
      ['updateChannel', 'updateFx', 'removeFx', 'pauseFx', 'resumeFx', 'clearFx'].includes(frame.type ?? ''),
  )
  expect(banned).toEqual([])
  expect(data.addFx).not.toHaveBeenCalled()
})
