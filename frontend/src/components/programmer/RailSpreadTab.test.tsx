// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import type { SpreadPanel } from '@/components/editor/SpreadPanel'
import { createMarqueeStore, MarqueeStoreContext, type MarqueeSnapshot } from '@/components/fixtures-list/marqueeContext'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import type { ProgrammerScope } from './ProgrammerScope'
import type { RailArm } from './ProgrammerWorkspace'
import { chan, colourProp, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The programmer rail's Spread tab (editor-kit plan session 4): its plans are the popover's —
 * built by the one `useMarqueeSpreadPlans`, run for real here over a stubbed store — and its scope
 * arms are the popover's too, drawn on the panel because a docked host has no trigger to disable.
 * The panel is a capture that draws one field, which is what a claimed Spread focuses.
 */
type PanelProps = ComponentProps<typeof SpreadPanel>
const panel = vi.hoisted(() => ({ props: null as PanelProps | null }))
vi.mock('@/components/editor/SpreadPanel', () => ({
  SpreadPanel: (props: PanelProps) => {
    panel.props = props
    return (
      <div data-spread-sheet-body inert={props.disabledReason != null}>
        {props.labelLine}
        <input aria-label="From" />
      </div>
    )
  },
}))
vi.mock('@/components/editor/ColourEditor', () => ({ ColourEditor: () => null }))

const scopeState = vi.hoisted(() => ({
  scope: { kind: 'local' } as ProgrammerScope | null,
  template: null as unknown,
  look: null as null | { lookName: string; setValue: () => void },
}))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => scopeState.scope }))
vi.mock('./FocusedTemplateLayer', () => ({ useFocusedTemplateLayer: () => scopeState.template }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => scopeState.look }))
vi.mock('@/components/fixtures-list/useCellWriters', () => ({ useCellWriters: () => ({}), applyPlannedWrite: () => {} }))
vi.mock('@/store/selection', () => ({ usePressFamilies: (local: unknown) => local }))
vi.mock('@/store/programmerOps', () => ({ useSpreadMutation: () => [vi.fn()] }))
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: [] }) }))

const arm = vi.hoisted(() => ({ value: {} as Partial<RailArm> }))
vi.mock('./ProgrammerWorkspace', () => ({ useRailArm: () => arm.value }))

const { RailSpreadTab } = await import('./RailSpreadTab')

const PAR_A = makeFixture('par-a', [sliderProp('dimmer', 'dimmer', chan(1)), colourProp('rgbColour', chan(2), chan(3), chan(4))])
const PAR_B = makeFixture('par-b', [sliderProp('dimmer', 'dimmer', chan(11)), colourProp('rgbColour', chan(12), chan(13), chan(14))])
const HEADS: readonly WriteTarget[] = [PAR_A, PAR_B]

function snapshot(over: Partial<MarqueeSnapshot> = {}): MarqueeSnapshot {
  return {
    batches: new Map(),
    columns: [{ col: 'dimmer', targets: HEADS }],
    rows: [],
    permission: { entry: true, clear: true },
    scopeLabel: 'Local',
    commit: vi.fn(),
    ...over,
  }
}

function draw(snap: MarqueeSnapshot) {
  const store = createMarqueeStore()
  store.set(snap)
  render(
    <MarqueeStoreContext.Provider value={store}>
      <RailSpreadTab projectId={1} />
    </MarqueeStoreContext.Provider>,
  )
}

beforeEach(() => {
  panel.props = null
  scopeState.scope = { kind: 'local' }
  scopeState.template = null
  scopeState.look = null
  arm.value = { focusRequest: null, consumeFocusRequest: vi.fn(), spreadSeed: null, consumeSpreadSeed: vi.fn() }
})
afterEach(cleanup)

describe('RailSpreadTab plans', () => {
  it('spreads the marquee’s column over its heads, desk-resolved, labelled like the popover', () => {
    draw(snapshot())
    const plan = panel.props!.plans[0]
    expect(plan.kind).toBe('intent')
    expect(plan.kind === 'intent' && plan.targets.map((t) => t.key)).toEqual(['par-a', 'par-b'])
    expect(plan.kind === 'intent' && plan.write).toBeUndefined()
    expect(panel.props!.host).toBe('docked')
    expect(screen.getByText('2 heads · Local')).toBeInTheDocument()
  })

  it('spreads a rows-only selection by the plain lists’ row rule — every family the heads take', () => {
    draw(snapshot({ columns: [], rows: HEADS }))
    const plan = panel.props!.plans.find((p) => p.kind === 'intent')
    expect(plan?.kind === 'intent' && plan.targets.map((t) => t.key)).toEqual(['par-a', 'par-b'])
    expect(plan?.kind === 'intent' && plan.offered).toEqual(expect.arrayContaining(['INTENSITY', 'COLOUR']))
  })

  it('draws its empty state, and stays open, with nothing selected', () => {
    draw(snapshot({ columns: [], rows: [] }))
    expect(panel.props).toBeNull()
    expect(screen.getByText('Nothing selected')).toBeInTheDocument()
  })
})

describe('RailSpreadTab scope arms', () => {
  it('a focused Look layer: write: false, the literals landing in the draft', () => {
    scopeState.scope = { kind: 'layer', layerId: 4 }
    scopeState.look = { lookName: 'Warm Wash', setValue: vi.fn() }
    draw(snapshot({ scopeLabel: 'Warm Wash' }))
    const plan = panel.props!.plans[0]
    expect(plan.kind === 'intent' && plan.write).toBe(false)
    expect(panel.props!.disabledReason).toBeNull()
  })

  it('Output: refused with the popover’s words, read-only on the label line', () => {
    scopeState.scope = { kind: 'output' }
    draw(snapshot({ permission: { entry: false, clear: false } }))
    expect(panel.props!.disabledReason).toBe('Output is a read of the cook — switch to Local to spread values onto these heads')
    expect(screen.getByText('Output · read-only')).toBeInTheDocument()
  })

  it('a focused template layer: refused with the popover’s words', () => {
    scopeState.scope = { kind: 'layer', layerId: 4 }
    scopeState.template = { id: 4 }
    draw(snapshot({ permission: { entry: false, clear: false } }))
    expect(panel.props!.disabledReason).toBe('This layer applies a template — switch to Local to spread values onto these heads')
  })
})

describe('RailSpreadTab claimed Spread', () => {
  it('focuses From, and drops the request', () => {
    const consume = vi.fn()
    arm.value = { ...arm.value, focusRequest: { tab: 'spread', seed: '', key: 1 }, consumeFocusRequest: consume }
    draw(snapshot())
    expect(consume).toHaveBeenCalled()
    expect(document.activeElement).toBe(screen.getByLabelText('From'))
  })

  it('drops the request without focusing where the scope refuses', () => {
    scopeState.scope = { kind: 'output' }
    const consume = vi.fn()
    arm.value = { ...arm.value, focusRequest: { tab: 'spread', seed: '', key: 1 }, consumeFocusRequest: consume }
    draw(snapshot({ permission: { entry: false, clear: false } }))
    expect(consume).toHaveBeenCalled()
    expect(document.activeElement).not.toBe(screen.getByLabelText('From'))
  })

  it('ignores a request meant for the Colour tab', () => {
    const consume = vi.fn()
    arm.value = { ...arm.value, focusRequest: { tab: 'colour', seed: '5', key: 1 }, consumeFocusRequest: consume }
    draw(snapshot())
    expect(consume).not.toHaveBeenCalled()
  })
})
