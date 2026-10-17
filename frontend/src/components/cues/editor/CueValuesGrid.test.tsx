// @vitest-environment jsdom
import { act, fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import { Provider } from 'react-redux'
import { useRef, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cue, CookedRow, CuePropertyAssignment } from '@/api/cuesApi'
import type { ColourPropertyDescriptor, Fixture } from '@/store/fixtures'

/**
 * The cue editor's Values grid (show-cue-editing plan session 5): the programmer's table over a
 * cue's `CueRowStore`, writing its own rows and **sending nothing to the stage** (P2).
 *
 * Mounted for real — `FixturesTable`, the four cells, the store, the writers and the real
 * `lightingApi` — over a fake socket that records every frame, so P2's test reads the wire itself:
 * no `programmer.*`, `channels.*` or `fx.*` frame leaves the browser on a full editing pass. The
 * data hooks are stubbed, the cue's PATCH is a recorded mutation.
 */

// ── The socket: every frame the real `lightingApi` would send ───────────────────────────────────
const socket = vi.hoisted(() => {
  const handlers: ((...args: unknown[]) => void)[] = []
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
      subscribe: (fn: (...args: unknown[]) => void) => {
        handlers.push(fn)
        return { unsubscribe: () => handlers.splice(handlers.indexOf(fn), 1) }
      },
    },
  }
})
vi.mock('@/api/internalApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/internalApi')>()),
  createInternalApiConnection: () => socket.conn,
}))

// ── The data ─────────────────────────────────────────────────────────────────────────────────────
const data = vi.hoisted(() => ({
  cues: new Map<number, Cue>(),
  cooked: new Map<number, CookedRow[]>(),
  cookAt: 1,
  cueAt: 1,
  /** The cue as the desk holds it, where it has moved past the cache (another client's edit). */
  desk: new Map<number, Cue>(),
  failNext: false,
  patches: [] as { cueId: number; propertyAssignments: CuePropertyAssignment[] }[],
}))
vi.mock('@/store/cues', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/cues')>()),
  useProjectCueQuery: ({ cueId }: { cueId: number }) => ({ data: data.cues.get(cueId), fulfilledTimeStamp: data.cueAt }),
  useProjectCueCookedQuery: ({ cueId }: { cueId: number }) => ({
    data: { cueId, rows: data.cooked.get(cueId) ?? [] },
    fulfilledTimeStamp: data.cookAt,
    isSuccess: true,
  }),
  // The store builds every save on the cue as the desk holds it: here, the map as it stands.
  fetchLatestCue: ({ cueId }: { cueId: number }) => {
    const found = data.desk.get(cueId) ?? data.cues.get(cueId)
    return found ? Promise.resolve(found) : Promise.reject(new Error('no cue'))
  },
  usePatchProjectCueMutation: () => [
    (arg: { cueId: number; propertyAssignments: CuePropertyAssignment[] }) => {
      if (data.failNext) {
        data.failNext = false
        return { unwrap: () => Promise.reject(new Error('refused')) }
      }
      data.patches.push({ cueId: arg.cueId, propertyAssignments: arg.propertyAssignments })
      return { unwrap: () => Promise.resolve({}) }
    },
  ],
}))
const spread = vi.hoisted(() => ({ calls: [] as Record<string, unknown>[] }))
vi.mock('@/store/programmerOps', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/programmerOps')>()),
  useSpreadMutation: () => [
    (body: Record<string, unknown>) => {
      spread.calls.push(body)
      return {
        unwrap: () =>
          Promise.resolve({
            written: [
              { target: { type: 'fixture', key: 'orbit-sr' }, propertyName: 'dimmer', value: '0' },
              { target: { type: 'fixture', key: 'orbit-sl' }, propertyName: 'dimmer', value: '255' },
            ],
            skipped: [],
          }),
      }
    },
  ],
}))
vi.mock('@/store/templates', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/templates')>()),
  useTemplateListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/selection', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/selection')>()),
  usePressFamilies: (local: unknown) => local,
}))
vi.mock('@/store/status', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/status')>()),
  useIsDeskConnected: () => true,
}))
const rig = vi.hoisted(() => ({ fixtures: [] as unknown[], groups: null as unknown[] | null }))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: rig.fixtures }) }))
vi.mock('@/store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtures')>()),
  useVisibleFixtureListQuery: () => ({ data: rig.fixtures }),
}))
vi.mock('@/store/groups', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/groups')>()),
  useGroupListQuery: () => ({
    data: rig.groups ?? [{ name: 'Front', memberCount: 2, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', memberKeys: ['orbit-sr', 'orbit-sl'] }],
  }),
}))
const toasts = vi.hoisted(() => ({ last: null as null | { message: string; undo?: () => void } }))
vi.mock('sonner', async (importOriginal) => {
  const actual = await importOriginal<typeof import('sonner')>()
  const toast = Object.assign(
    (message: string, opts?: { action?: { onClick: () => void } }) => {
      toasts.last = { message, undo: opts?.action?.onClick }
    },
    { ...actual.toast, dismiss: () => {}, error: () => {} },
  )
  return { ...actual, toast }
})
// jsdom lays nothing out: render every row.
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () => Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { chan, colourProp, makeFixture, makePixelBar, positionProp, sliderProp } from '@/test/fixtureFactories'
import { store } from '@/store'
import { lightingApi } from '@/api/lightingApi'
import { CueRowStoreProvider } from './CueRowStore'
import { CueValuesGrid } from './CueValuesGrid'
import { useCellWriters } from '@/components/fixtures-list/useCellWriters'
import { useMarqueeSpreadPlans } from '@/components/fixtures-list/SpreadPopover'
import { EditorContextProvider } from '@/components/programmer/EditorContext'
import { useSheetKeyboard } from '@/components/sheet/useSheetKeyboard'
import { resetKeyRegion } from '@/components/sheet/keyRegion'
import type { IntentSpreadPlan } from '@/components/editor/SpreadPanel'

const orbit = (key: string, first: number) =>
  makeFixture(
    key,
    [
      sliderProp('dimmer', 'dimmer', chan(first)),
      colourProp('rgbColour', chan(first + 1), chan(first + 2), chan(first + 3)),
      positionProp('position', chan(first + 4), chan(first + 5)),
    ],
    { name: key === 'orbit-sr' ? 'Orbit SR' : 'Orbit SL', groups: ['Front'] },
  )

function cue(id: number, propertyAssignments: CuePropertyAssignment[]): Cue {
  return {
    id,
    name: `Cue ${id}`,
    cueNumber: String(id),
    cueNumberAuto: false,
    layers: [],
    adHocEffects: [],
    propertyAssignments,
    triggers: [],
    cueStackId: 7,
    cueStackName: 'Act 1',
    entryId: 100 + id,
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
  } as Cue
}

const SCRIM = { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Scrim · opaque' } as const
const layerRow = (key: string, propertyName: string, value: string): CookedRow => ({
  targetType: 'fixture',
  targetKey: key,
  propertyName,
  value,
  layerId: 4,
  layerSource: SCRIM,
  sourceTargetType: 'fixture',
  sourceTargetKey: key,
})

/** One editor: its key region, its store, its grid. */
function Editor({
  cueId,
  children,
  readOnly = false,
  onRequestUnlock,
}: {
  cueId: number
  children?: ReactNode
  readOnly?: boolean
  onRequestUnlock?: () => void
}) {
  const ref = useRef<HTMLDivElement | null>(null)
  return (
    <CueRowStoreProvider projectId={1} cueId={cueId} readOnly={readOnly}>
      <div ref={ref} data-key-region data-testid={`editor-${cueId}`} style={{ height: 600 }}>
        <CueValuesGrid region={ref} readOnlyReason={readOnly ? 'Locked' : null} onRequestUnlock={onRequestUnlock} />
        {children}
      </div>
    </CueRowStoreProvider>
  )
}

function render(ui: ReactNode) {
  return rtlRender(<Provider store={store}>{ui}</Provider>)
}

function editor(cueId: number) {
  return screen.getByTestId(`editor-${cueId}`)
}
function cell(cueId: number, rowId: string, col: string) {
  const row = editor(cueId).querySelector(`[data-row-id="${rowId}"]`)
  const found = row?.querySelector(`[data-cell="${col}"]`)
  if (!(found instanceof HTMLElement)) throw new Error(`no ${col} cell on ${rowId}`)
  return found
}
/** A press in the editor (the region takes the keys) and a click on the cell (selects it). */
function selectCell(cueId: number, rowId: string, col: string) {
  fireEvent.pointerDown(editor(cueId))
  fireEvent.click(within(cell(cueId, rowId, col)).getByRole('button'))
}
async function typeLevel(percent: string) {
  fireEvent.keyDown(window, { key: 'Enter' })
  fireEvent.change(await screen.findByRole('spinbutton', { name: 'Dimmer' }), { target: { value: percent } })
}
async function saved(count: number) {
  await waitFor(() => expect(data.patches).toHaveLength(count), { timeout: 3000 })
  return data.patches[count - 1].propertyAssignments
}

// jsdom has no `matchMedia`; a desk is tall and wide, so every query answers false (the popover form).
window.matchMedia ??= ((query: string) =>
  ({ matches: false, media: query, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, onchange: null, dispatchEvent: () => false }) as unknown as MediaQueryList) as typeof window.matchMedia
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

beforeEach(() => {
  rig.fixtures = [orbit('orbit-sr', 1), orbit('orbit-sl', 11)]
  rig.groups = null
  data.cues.clear()
  data.cooked.clear()
  data.desk.clear()
  data.failNext = false
  data.patches.length = 0
  data.cookAt = 1
  data.cueAt = 1
  socket.sent.length = 0
  spread.calls.length = 0
  toasts.last = null
  resetKeyRegion()
})
afterEach(() => {
  vi.clearAllMocks()
})

describe('CueValuesGrid', () => {
  it('a typed value over a Look-won cell becomes own and wins; ⌫ shows the layer again', async () => {
    data.cues.set(1, cue(1, []))
    data.cooked.set(1, [layerRow('orbit-sr', 'dimmer', '255'), layerRow('orbit-sl', 'dimmer', '255')])
    render(<Editor cueId={1} />)

    expect(cell(1, 'fixture:orbit-sr', 'dimmer')).toHaveAttribute('title', 'From Scrim · opaque')
    expect(cell(1, 'fixture:orbit-sr', 'dimmer').className).toContain('teal')

    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('80')
    // Own at once, over the Look it beats — and saved as an own row of 80 %.
    expect(cell(1, 'fixture:orbit-sr', 'dimmer')).toHaveAttribute('title', 'Own · overrides Scrim · opaque')
    expect(cell(1, 'fixture:orbit-sr', 'dimmer').className).toContain('blue')
    expect(await saved(1)).toEqual([{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '204', sortOrder: 0 }])

    // The save read back: the cue holds the row, the cook says own.
    data.cues.set(1, cue(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '204', sortOrder: 0 }]))
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(window, { key: 'Backspace' })
    // The own row is gone from the draft, so the Look under it shows again …
    expect(cell(1, 'fixture:orbit-sr', 'dimmer')).toHaveAttribute('title', 'From Scrim · opaque')
    // … and the save takes the row away.
    expect(await saved(2)).toEqual([])
  })

  it('keeps every row’s fade, move-in-dark and order through an edit', async () => {
    data.cues.set(
      1,
      cue(1, [
        { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'position', value: '100,150', fadeDurationMs: 1500, moveInDark: true, sortOrder: 3 },
        { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', fadeDurationMs: 800, sortOrder: 1 },
      ]),
    )
    data.cooked.set(1, [
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'position', value: '100,150', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' },
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' },
    ])
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('80')
    expect(await saved(1)).toEqual([
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'position', value: '100,150', fadeDurationMs: 1500, moveInDark: true, sortOrder: 3 },
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '204', fadeDurationMs: 800, sortOrder: 1 },
    ])
  })

  it('draws a group’s own row on the group’s row and edits it there', async () => {
    data.cues.set(1, cue(1, [{ targetType: 'group', targetKey: 'Front', propertyName: 'dimmer', value: '115', sortOrder: 0 }]))
    data.cooked.set(1, [
      { targetType: 'group', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '115', sourceTargetType: 'group', sourceTargetKey: 'Front' },
      { targetType: 'group', targetKey: 'orbit-sl', propertyName: 'dimmer', value: '115', sourceTargetType: 'group', sourceTargetKey: 'Front' },
    ])
    render(<Editor cueId={1} />)
    expect(cell(1, 'group:Front', 'dimmer')).toHaveAttribute('title', 'Own · Front’s row')
    // Its members read it through the group.
    expect(cell(1, 'fixture:orbit-sl', 'dimmer')).toHaveAttribute('title', 'Own · Front’s row')

    selectCell(1, 'group:Front', 'dimmer')
    await typeLevel('80')
    // One group row, rewritten — not a row per member.
    expect(await saved(1)).toEqual([{ targetType: 'group', targetKey: 'Front', propertyName: 'dimmer', value: '204', sortOrder: 0 }])
  })

  it('draws a group row only the cells its own row can take — not a colour its members hold on their cells', () => {
    rig.fixtures = [makePixelBar('bar-1', 2, [sliderProp('dimmer', 'dimmer', chan(90))], { name: 'Bar 1', groups: ['Bars'] })]
    rig.groups = [{ name: 'Bars', memberCount: 1, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', memberKeys: ['bar-1'] }]
    data.cues.set(1, cue(1, [{ targetType: 'group', targetKey: 'Bars', propertyName: 'dimmer', value: '115', sortOrder: 0 }]))
    data.cooked.set(1, [
      { targetType: 'group', targetKey: 'bar-1', propertyName: 'dimmer', value: '115', sourceTargetType: 'group', sourceTargetKey: 'Bars' },
      layerRow('bar-1.pixel-0', 'rgbColour', '#ff0000'),
    ])
    render(<Editor cueId={1} />)
    expect(cell(1, 'group:Bars', 'dimmer')).toHaveAttribute('title', 'Own · Bars’s row')
    // The bar's colour lives on its cells: its own row draws it, the group row draws no cell a
    // commit would land nowhere from.
    expect(cell(1, 'fixture:bar-1', 'colour')).toBeInTheDocument()
    expect(editor(1).querySelector('[data-row-id="group:Bars"] [data-cell="colour"]')).toBeNull()
  })

  it('Undo puts the list back as it was before the last save', async () => {
    const before: CuePropertyAssignment[] = [
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', fadeDurationMs: 800, sortOrder: 1 },
    ]
    data.cues.set(1, cue(1, before))
    data.cooked.set(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' }])
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('80')
    await saved(1)
    await waitFor(() => expect(toasts.last?.undo).toBeDefined())
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    expect(await saved(2)).toEqual(before)
  })

  it('sends no programmer, channel or effect frame over a full editing pass (P2)', async () => {
    data.cues.set(1, cue(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }]))
    data.cooked.set(1, [
      { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' },
      layerRow('orbit-sl', 'position', '100,150'),
    ])
    const probe: { writers?: ReturnType<typeof useCellWriters>; plans?: ReturnType<typeof useMarqueeSpreadPlans> } = {}
    function Probe() {
      probe.writers = useCellWriters()
      probe.plans = useMarqueeSpreadPlans({ columns: [{ col: 'dimmer', targets: rig.fixtures as never }], projectId: 1 })
      return null
    }
    render(
      <Editor cueId={1}>
        <EditorContextProvider value={{ kind: 'cue', cueId: 1 }}>
          <Probe />
        </EditorContextProvider>
      </Editor>,
    )
    // Set, through the grid.
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('80')
    // Colour and a single-axis position, through the writers the grid writes with.
    act(() => {
      probe.writers!.writeColour('orbit-sr', (rig.fixtures[0] as Fixture).properties[1] as ColourPropertyDescriptor, 255, 128, 0)
      probe.writers!.writePosition('orbit-sl', chan(15), chan(16), 40, undefined)
    })
    // Spread: the desk resolves with `write: false`, and the literals land in the cue.
    const plan = probe.plans!.plans.find((p): p is IntentSpreadPlan => p.kind === 'intent')!
    expect(plan.write).toBe(false)
    await act(async () => {
      const answer = await plan.send({} as never)
      plan.onAnswer?.(answer as never, {} as never)
    })
    const assignments = await saved(1)
    expect(spread.calls).toHaveLength(1)
    // The position took its tilt from the composed cell (the Look's 150), never the wire.
    expect(assignments).toEqual(
      expect.arrayContaining([
        { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '0', sortOrder: 0 },
        { targetType: 'fixture', targetKey: 'orbit-sl', propertyName: 'dimmer', value: '255', sortOrder: expect.any(Number) },
        { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'rgbColour', value: '#FF8000', sortOrder: expect.any(Number) },
        { targetType: 'fixture', targetKey: 'orbit-sl', propertyName: 'position', value: '40,150', sortOrder: expect.any(Number) },
      ]),
    )
    // ⌫ and Undo.
    data.cues.set(1, cue(1, assignments))
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(window, { key: 'Backspace' })
    await saved(2)
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await saved(3)

    const banned = socket.sent.filter((frame) => /^(programmer|channels|fx)\./.test(frame.type ?? '') || frame.type === 'updateChannel')
    expect(banned).toEqual([])
    // And the socket is the one `lightingApi` writes to: a live write does reach it.
    lightingApi.programmer.set('fixture', 'orbit-sr', 'dimmer', '255')
    expect(socket.sent.some((frame) => frame.type?.startsWith('programmer.'))).toBe(true)
  })

  it('takes keys only in its own region, and dims the other grid’s selection', async () => {
    const own = (key: string): CuePropertyAssignment => ({ targetType: 'fixture', targetKey: key, propertyName: 'dimmer', value: '10', sortOrder: 0 })
    const ownCook = (key: string): CookedRow => ({ targetType: 'fixture', targetKey: key, propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: key })
    data.cues.set(1, cue(1, [own('orbit-sr')]))
    data.cooked.set(1, [ownCook('orbit-sr')])
    data.cues.set(2, cue(2, [own('orbit-sr')]))
    data.cooked.set(2, [ownCook('orbit-sr')])
    // A sheet outside both regions — the cue sheet behind the pane — whose ⌫ must not fire either.
    const sheetClear = vi.fn()
    function Sheet() {
      useSheetKeyboard({
        cellCount: 1,
        permission: { entry: true, clear: true },
        isCellSelected: () => true,
        onEscape: () => {},
        onOpen: () => {},
        onClear: sheetClear,
      })
      return null
    }
    render(
      <>
        <Sheet />
        <Editor cueId={1} />
        <Editor cueId={2} />
      </>,
    )
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    selectCell(2, 'fixture:orbit-sr', 'dimmer')
    // Cue 2 has the keys; cue 1 keeps its selection, drawn dimmed.
    expect(editor(2).querySelector('[data-cue-values-grid]')).toHaveAttribute('data-key-active')
    expect(editor(1).querySelector('[data-cue-values-grid]')).not.toHaveAttribute('data-key-active')
    expect(cell(1, 'fixture:orbit-sr', 'dimmer').className).toContain('after:bg-primary/10')
    fireEvent.keyDown(window, { key: 'Backspace' })
    expect(await saved(1)).toEqual([])
    expect(data.patches[0].cueId).toBe(2)
    expect(sheetClear).not.toHaveBeenCalled()

    // A press back in cue 1 hands it the keys.
    fireEvent.pointerDown(editor(1))
    fireEvent.keyDown(window, { key: 'Backspace' })
    await saved(2)
    expect(data.patches[1].cueId).toBe(1)
    expect(sheetClear).not.toHaveBeenCalled()

    // Outside every region, the sheet has its keys back.
    fireEvent.pointerDown(document.body)
    fireEvent.keyDown(window, { key: 'Backspace' })
    expect(sheetClear).toHaveBeenCalledTimes(1)
  })

  it('builds a save on the cue as the desk holds it, not the cache', async () => {
    const mine: CuePropertyAssignment = { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }
    const theirs: CuePropertyAssignment = { targetType: 'fixture', targetKey: 'orbit-sl', propertyName: 'dimmer', value: '50', sortOrder: 1 }
    data.cues.set(1, cue(1, [mine]))
    data.cooked.set(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' }])
    // Another client added a row; `cueListChanged` does not reach this cache.
    data.desk.set(1, cue(1, [mine, theirs]))
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('80')
    expect(await saved(1)).toEqual([{ ...mine, value: '204' }, theirs])

    // Undo puts back only the row the save changed: their row, edited again since, stays.
    const theirsAgain = { ...theirs, value: '60' }
    data.desk.set(1, cue(1, [{ ...mine, value: '204' }, theirsAgain]))
    await waitFor(() => expect(toasts.last?.undo).toBeDefined())
    act(() => toasts.last!.undo!())
    expect(await saved(2)).toEqual([mine, theirsAgain])
  })

  it('leaves the last Undo standing when a save fails, and sends the edit again', async () => {
    const before: CuePropertyAssignment = { targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }
    data.cues.set(1, cue(1, [before]))
    data.cooked.set(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' }])
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    data.failNext = true
    await typeLevel('80')
    await new Promise((r) => setTimeout(r, 600))
    expect(data.patches).toHaveLength(0)
    expect(toasts.last).toBeNull()
    // ⌘Z has nothing to undo: the refused save set none.
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(window, { key: 'z', metaKey: true })
    await new Promise((r) => setTimeout(r, 50))
    expect(data.patches).toHaveLength(0)
    // The next edit carries the refused one with it.
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    await typeLevel('90')
    expect(await saved(1)).toEqual([{ ...before, value: '230' }])
  })

  it('writes nothing for ⌫ over a cell the cue holds no own row on', async () => {
    data.cues.set(1, cue(1, []))
    data.cooked.set(1, [layerRow('orbit-sr', 'dimmer', '255')])
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    fireEvent.keyDown(window, { key: 'Backspace' })
    await new Promise((r) => setTimeout(r, 600))
    expect(data.patches).toHaveLength(0)
    expect(toasts.last).toBeNull()
  })

  it('folds a white component into RGB on a head with no white channel', async () => {
    data.cues.set(1, cue(1, []))
    data.cooked.set(1, [layerRow('orbit-sr', 'dimmer', '255')])
    const probe: { writers?: ReturnType<typeof useCellWriters> } = {}
    function Probe() {
      probe.writers = useCellWriters()
      return null
    }
    render(
      <Editor cueId={1}>
        <EditorContextProvider value={{ kind: 'cue', cueId: 1 }}>
          <Probe />
        </EditorContextProvider>
      </Editor>,
    )
    act(() => {
      probe.writers!.writeColour('orbit-sr', (rig.fixtures[0] as Fixture).properties[1] as ColourPropertyDescriptor, 0, 0, 0, 255)
    })
    expect(await saved(1)).toEqual([{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'rgbColour', value: '#FFFFFF', sortOrder: 0 }])
  })

  it('claims an Escape that clears its selection, so the sheet it opens in stays open', () => {
    data.cues.set(1, cue(1, []))
    data.cooked.set(1, [layerRow('orbit-sr', 'dimmer', '255')])
    render(<Editor cueId={1} />)
    selectCell(1, 'fixture:orbit-sr', 'dimmer')
    const first = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => {
      window.dispatchEvent(first)
    })
    expect(first.defaultPrevented).toBe(true)
    // Nothing left to clear: the next Escape is the sheet's.
    const second = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => {
      window.dispatchEvent(second)
    })
    expect(second.defaultPrevented).toBe(false)
  })

  it('locked, claims ⌫ in the editor and asks to unlock — the transport never sees it', async () => {
    data.cues.set(1, cue(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }]))
    data.cooked.set(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' }])
    const unlock = vi.fn()
    // The transport's BACK: a bubble listener standing aside from a claimed key, as `useTransportKeys` does.
    const back = vi.fn()
    const transport = (e: KeyboardEvent) => {
      if (!e.defaultPrevented && e.key === 'Backspace') back()
    }
    window.addEventListener('keydown', transport)
    try {
      // A marquee drawn before the show locked (a GO from elsewhere locks it under the operator).
      const { rerender } = render(<Editor cueId={1} onRequestUnlock={unlock} />)
      selectCell(1, 'fixture:orbit-sr', 'dimmer')
      rerender(
        <Provider store={store}>
          <Editor cueId={1} readOnly onRequestUnlock={unlock} />
        </Provider>,
      )
      fireEvent.keyDown(window, { key: 'Backspace' })
      expect(back).not.toHaveBeenCalled()
      fireEvent.click(await screen.findByRole('button', { name: /unlock/i }))
      expect(unlock).toHaveBeenCalledTimes(1)
      expect(data.patches).toHaveLength(0)
    } finally {
      window.removeEventListener('keydown', transport)
    }
  })

  it('reads and writes nothing while read-only', async () => {
    data.cues.set(1, cue(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sortOrder: 0 }]))
    data.cooked.set(1, [{ targetType: 'fixture', targetKey: 'orbit-sr', propertyName: 'dimmer', value: '10', sourceTargetType: 'fixture', sourceTargetKey: 'orbit-sr' }])
    render(<Editor cueId={1} readOnly />)
    expect(within(cell(1, 'fixture:orbit-sr', 'dimmer')).getByRole('button')).toBeDisabled()
    fireEvent.pointerDown(editor(1))
    fireEvent.keyDown(window, { key: 'Backspace' })
    await new Promise((r) => setTimeout(r, 500))
    expect(data.patches).toHaveLength(0)
  })
})
