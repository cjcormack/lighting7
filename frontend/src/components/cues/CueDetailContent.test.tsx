// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Cue, CueLayerDetail } from '@/api/cuesApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'

vi.mock('@/store/fixtureFx', () => ({
  useEffectLibraryQuery: () => ({ data: [] }),
}))
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({ data: LOOKS }),
}))
// Both libraries, because a layer row names either — and `describeStackSource` only paints a layer
// as missing once *both* have landed, so a suite mocking one and not the other would show every
// Look layer as broken.
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: TEMPLATES }),
}))

// The Values section fetches the cue's cook. Stubbed out: what this suite is about is the read
// surface's *structure*, and `cueCookedRows.test.ts` covers the mapping behind that grid.
vi.mock('./CueValueGrid', () => ({ CueValueGrid: () => <div data-testid="cue-values" /> }))
// The stage map reads the projected patch and the fixture lookup, neither of which this suite is
// about — and both of which need the store.
vi.mock('./MiniStage', () => ({ MiniStage: () => <div data-testid="mini-stage" /> }))

import { CueDetailContent } from './CueDetailContent'

const LOOKS: LookSummary[] = [
  {
    id: 7,
    uuid: 'u7',
    name: 'Warm Wash',
    notes: null,
    families: ['COLOUR'],
    rowCount: 3,
    effectCount: 0,
    targetCount: 3,
    hasDeferredEffects: false,
    preview: [],
    layerCount: 1,
    buskPageCount: 0,
  },
]

const TEMPLATES: TemplateSummary[] = [
  {
    id: 4,
    uuid: 'ut4',
    name: 'Amber Breathe',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'effect',
    requiredEmitters: [],
    rows: [],
    effect: {
      effectType: 'ColourPulse',
      category: 'colour',
      beatDivision: 0.5,
      blendMode: 'OVERRIDE',
      distribution: 'LINEAR',
      parameters: {},
      timingSource: 'BEAT',
    },
    layerCount: 1,
    lastPressedAt: null,
    buskPageCount: 0,
  },
]

function layer(over: Partial<CueLayerDetail> = {}): CueLayerDetail {
  return {
    lookId: 7,
    source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' },
    sortOrder: 0,
    enabled: true,
    targets: [{ type: 'fixture', key: 'hex-1' }],
    propertyMask: null,
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
    ...over,
  }
}

function cue(over: Partial<Cue> = {}): Cue {
  return {
    id: 1,
    name: 'Act 1 Warm',
    cueNumber: '1',
    cueNumberAuto: false,
    sortOrder: 0,
    layers: [],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [],
    ...over,
  } as Cue
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

/**
 * The first test in this tree, and it covers more than one component's worth of surface.
 *
 * `CueDetailContent` **is** the cue read surface: the expanded cue card renders it directly,
 * and `RunMobileCueCard` and the Prompt Book rail through `CueCardBody`. None of those four render
 * cue content of their own, so what is asserted here is what all four show — which is why plan §4.5's
 * "four independent read renderers" collapsed to one file.
 */
describe('CueDetailContent', () => {
  it('renders each layer in order, named and with its targets', () => {
    render(
      <CueDetailContent
        cue={cue({ layers: [layer(), layer({ source: { kind: 'LOOK', id: 8, uuid: 'u8', name: 'Second' }, sortOrder: 1 })] })}
        projectId={1}
      />,
    )
    expect(screen.getByText('Warm Wash')).toBeInTheDocument()
    expect(screen.getByText('Second')).toBeInTheDocument()
    // Both layers name the same fixture, so this is deliberately getAll — one target chip per row.
    expect(screen.getAllByText('hex-1')).toHaveLength(2)
  })

  it('shows a masked layer’s families, so a colour-only layer explains itself', () => {
    // The migration sets a mask on every layer folded from a value-level reference, so this is the
    // common case. It reads the same here as in the editor because both draw `LayerRow`.
    render(<CueDetailContent cue={cue({ layers: [layer({ propertyMask: 'COLOUR' })] })} projectId={1} />)
    expect(screen.getByText('[Colour]')).toBeInTheDocument()
  })

  it('offers no editing controls at all', () => {
    // The property that makes sharing `LayerRow` with the authoring surface safe. `readOnly` strips
    // amount, enable, remove and the combine popover; a regression here would put live controls on
    // the Run card and the Prompt Book rail, where there is no mutation behind them.
    render(
      <CueDetailContent
        cue={cue({ layers: [layer({ propertyMask: 'COLOUR', blendMode: 'MAX', amount: 0.5 })] })}
        projectId={1}
      />,
    )
    expect(screen.queryByLabelText('Layer amount (%)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Disable layer')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Remove')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Reorder layer')).not.toBeInTheDocument()
    expect(screen.queryByTitle(/How this layer combines/)).not.toBeInTheDocument()
    // Read-only still *reports* both, or the operator cannot explain the cue — and words the mask
    // exactly as the editable trigger does.
    expect(screen.getByText('[Colour]')).toBeInTheDocument()
    expect(screen.getByText('MAX')).toBeInTheDocument()
  })

  it('says a layer with no targets takes them from the look', () => {
    render(<CueDetailContent cue={cue({ layers: [layer({ targets: [] })] })} projectId={1} />)
    expect(screen.getByText(/look’s own targets/)).toBeInTheDocument()
  })

  it('offers an empty state rather than a blank section', () => {
    render(<CueDetailContent cue={cue()} projectId={1} />)
    expect(screen.getByText('Layers')).toBeInTheDocument()
    expect(screen.getAllByText('None.').length).toBeGreaterThan(0)
  })
})
