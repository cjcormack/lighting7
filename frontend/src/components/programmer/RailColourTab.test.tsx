// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ColourEditorProps } from '@/components/editor/ColourEditor'
import { createMarqueeStore, MarqueeStoreContext, type MarqueeSnapshot } from '@/components/fixtures-list/marqueeContext'
import { batchForTargets, type CellBatch, type WriteTarget } from '@/components/fixtures-list/rowModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import type { ProgrammerScope } from './ProgrammerScope'
import type { RailArm } from './ProgrammerWorkspace'
import { chan, colourProp, makeFixture, makePixelBar, sliderProp } from '@/test/fixtureFactories'

/**
 * The programmer rail's Colour tab (editor-kit plan session 4). Two things are pinned here, the
 * two the plan names: **its targets are the colour cell's**, over one batch — the tab stands in for
 * the popover, so the two must not count or read heads two ways — and **its four scope arms**,
 * which are the cell's: Local writes, a focused Look layer writes into the draft, Output and a
 * focused template layer are read-only with the cell's words.
 *
 * The editor is a capture: the subject is what the tab hands it, not the picker.
 */
const editor = vi.hoisted(() => ({ props: null as ColourEditorProps | null, seeds: [] as (string | null | undefined)[] }))
vi.mock('@/components/editor/ColourEditor', () => ({
  ColourEditor: (props: ColourEditorProps) => {
    editor.props = props
    editor.seeds.push(props.keyboardOpen)
    return <div data-testid="editor">{props.labelLine}</div>
  },
}))
const cell = vi.hoisted(() => ({ targets: null as readonly WriteTarget[] | null }))
vi.mock('@/components/fixtures/ColourPickerPopover', () => ({
  ColourPickerPopover: (props: { targets?: readonly WriteTarget[] }) => {
    cell.targets = props.targets ?? null
    return null
  },
}))
vi.mock('./NewTemplateFromSelectionSheet', () => ({ NewTemplateFromSelectionSheet: () => null }))

const scopeState = vi.hoisted(() => ({
  scope: { kind: 'local' } as ProgrammerScope | null,
  template: null as unknown,
}))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => scopeState.scope }))
vi.mock('./FocusedTemplateLayer', () => ({ useFocusedTemplateLayer: () => scopeState.template }))

const arm = vi.hoisted(() => ({
  value: {
    focusRequest: null,
    consumeFocusRequest: () => {},
    spreadFrom: () => {},
  } as Partial<RailArm>,
}))
vi.mock('./ProgrammerWorkspace', () => ({ useRailArm: () => arm.value }))

const { RailColourTab } = await import('./RailColourTab')
const { ColourCell, colourTargetsOf } = await import('@/components/fixtures-list/cells/ColourCell')

/** One head with white, a dimmer-only par the marquee swept up, and a pixel bar whose colour lives on its cells. */
const RGBW = makeFixture('rgbw', [colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4) })])
const PAR = makeFixture('par', [sliderProp('dimmer', 'dimmer', chan(9))])
const BAR = makePixelBar('bar', 3)
const HEADS: readonly WriteTarget[] = [RGBW, PAR, BAR]

function snapshot(over: Partial<MarqueeSnapshot> = {}): MarqueeSnapshot {
  const batch = batchForTargets(HEADS, 'colour')
  return {
    batches: new Map<ColumnKey, CellBatch>([['colour', batch]]),
    columns: [{ col: 'colour', targets: HEADS }],
    rows: [],
    permission: { entry: true, clear: true },
    scopeLabel: 'Local',
    commit: vi.fn(),
    ...over,
  }
}

function draw(snap: MarqueeSnapshot | null) {
  const store = createMarqueeStore()
  store.set(snap)
  render(
    <MarqueeStoreContext.Provider value={store}>
      <RailColourTab projectId={1} />
    </MarqueeStoreContext.Provider>,
  )
  return store
}

beforeEach(() => {
  editor.props = null
  editor.seeds = []
  cell.targets = null
  scopeState.scope = { kind: 'local' }
  scopeState.template = null
  arm.value = { focusRequest: null, consumeFocusRequest: vi.fn(), spreadFrom: vi.fn() }
})
afterEach(cleanup)

describe('RailColourTab targets', () => {
  it('edits exactly the heads the colour cell edits, over one batch', () => {
    // The cell's rule (`colourTargetsOf`): the RGBW head as itself, the bar as one target per cell,
    // the dimmer-only par dropped. Rendered through the real cell so a change to either side's
    // derivation fails here rather than drifting.
    const batch = batchForTargets(HEADS, 'colour')
    draw(snapshot())
    render(
      <ColourCell
        value={{ kind: 'colour', r: 0, g: 0, b: 0, combinedCss: '#000', isUniform: true }}
        resolutions={[...batch.resolutions]}
        batch={batch}
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(cell.targets).not.toBeNull()
    expect(editor.props?.targets).toEqual(cell.targets)
    expect(editor.props?.targets).toEqual(colourTargetsOf(HEADS))
    expect(editor.props?.targets?.map((t) => t.key)).toEqual(['rgbw', 'bar.pixel-0', 'bar.pixel-1', 'bar.pixel-2'])
    // The emitter rows are the batch's union, as the cell's are.
    expect(editor.props?.hasWhiteChannel).toBe(true)
    expect(editor.props?.hasAmberChannel).toBe(false)
  })

  it('takes the rows’ colour heads when rows are selected and no cells', () => {
    draw(snapshot({ batches: new Map(), columns: [], rows: HEADS }))
    expect(editor.props?.targets).toEqual(colourTargetsOf(HEADS))
  })

  it('draws its empty state, and stays open, with nothing selected', () => {
    draw(snapshot({ batches: new Map(), columns: [], rows: [] }))
    expect(screen.queryByTestId('editor')).toBeNull()
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
  })

  it('re-targets as the marquee moves', () => {
    const store = draw(snapshot())
    act(() => store.set(snapshot({ batches: new Map([['colour', batchForTargets([RGBW], 'colour')]]), columns: [{ col: 'colour', targets: [RGBW] }] })))
    expect(editor.props?.targets?.map((t) => t.key)).toEqual(['rgbw'])
  })
})

describe('RailColourTab scope arms', () => {
  it('Local: writes the Colour column through the marquee’s commit, and says so on the label line', () => {
    const snap = snapshot()
    draw(snap)
    expect(screen.getByText('4 heads · Local')).toBeInTheDocument()
    expect(editor.props?.readOnly).toBe(false)
    act(() => editor.props?.onColourChange(10, 20, 30, 40))
    expect(snap.commit).toHaveBeenCalledWith('colour', { kind: 'colour', r: 10, g: 20, b: 30, w: 40, a: undefined, uv: undefined })
  })

  it('a focused Look layer: writes through the same commit, named by the Look', () => {
    // The commit is the container's, whose writers are the grid's `lookLayer` arm in this scope —
    // the tab never picks an arm of its own.
    scopeState.scope = { kind: 'layer', layerId: 7 }
    const snap = snapshot({ scopeLabel: 'Warm Wash', permission: { entry: true, clear: false } })
    draw(snap)
    expect(screen.getByText('4 heads · Warm Wash')).toBeInTheDocument()
    act(() => editor.props?.onColourChange(1, 2, 3))
    expect(snap.commit).toHaveBeenCalledTimes(1)
  })

  it('Output: read-only with the cell’s words, Pick left to the editor, and nothing written', () => {
    scopeState.scope = { kind: 'output' }
    const snap = snapshot({ permission: { entry: false, clear: false } })
    draw(snap)
    expect(screen.getByText('Output · read-only')).toBeInTheDocument()
    expect(screen.getByText('Output is a read of the cook — switch to Local to set these cells')).toBeInTheDocument()
    // Read-only is the editor's whole guarantee — Spread… and Recent included (`ColourEditor.test`).
    expect(editor.props?.readOnly).toBe(true)
    act(() => editor.props?.onColourChange(1, 2, 3))
    expect(snap.commit).not.toHaveBeenCalled()
  })

  it('a focused template layer: refused with the cell’s words, the tab open and disabled', () => {
    scopeState.scope = { kind: 'layer', layerId: 3 }
    scopeState.template = { id: 3 }
    const snap = snapshot({ permission: { entry: false, clear: false } })
    draw(snap)
    expect(screen.getByText('This layer applies a template — switch to Local to set these cells')).toBeInTheDocument()
    expect(editor.props?.readOnly).toBe(true)
    act(() => editor.props?.onColourChange(1, 2, 3))
    expect(snap.commit).not.toHaveBeenCalled()
  })
})

describe('RailColourTab claimed opens', () => {
  it('seeds the character a claimed open carried, and drops the request', () => {
    const consume = vi.fn()
    arm.value = { focusRequest: { tab: 'colour', seed: '5', key: 1 }, consumeFocusRequest: consume, spreadFrom: vi.fn() }
    draw(snapshot())
    expect(consume).toHaveBeenCalled()
    // Set for one commit — which is when the R field takes it — and dropped after, so the same key
    // pressed twice seeds twice. By the time the render settles it is gone again.
    expect(editor.seeds).toContain('5')
    expect(editor.props?.keyboardOpen).toBeNull()
  })

  it('hands Spread… to the rail with the RGB alone', () => {
    const spreadFrom = vi.fn()
    arm.value = { focusRequest: null, consumeFocusRequest: vi.fn(), spreadFrom }
    draw(snapshot())
    act(() => editor.props?.onSpread?.({ r: 1, g: 2, b: 3, w: 4, a: 5, uv: 6 }))
    expect(spreadFrom).toHaveBeenCalledWith({ r: 1, g: 2, b: 3 })
  })
})
