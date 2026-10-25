// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Cue, CueLayerDetail } from '@/api/cuesApi'
import type { LookSummary } from '@/api/looksApi'
import type { PaletteSummary } from '@/api/palettesApi'

vi.mock('@/store/fixtureFx', () => ({
  useFxLibraryQuery: () => ({ data: [] }),
}))
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({ data: LOOKS }),
}))
// Both libraries, because a layer row names either — and `describeStackSource` only paints a layer
// as missing once *both* have landed, so a suite mocking one and not the other would show every
// Look layer as broken.
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({ data: TEMPLATES }),
}))

// The Values section fetches the cue's cook. Stubbed out: what this suite is about is the read
// surface's *structure*, and `cueCookedRows.test.ts` covers the mapping behind that grid.
vi.mock('./CueValueGrid', () => ({ CueValueGrid: () => <div data-testid="cue-values" /> }))

import { CueDetailContent } from './CueDetailContent'

const LOOKS: LookSummary[] = [
  {
    id: 7,
    uuid: 'u7',
    name: 'Warm Wash',
    notes: null,
    families: ['COLOUR'],
    rowCount: 3,
    fxCount: 0,
    targetCount: 3,
    preview: [],
    layerCount: 1,
    buskPageCount: 0,
  },
]

const TEMPLATES: PaletteSummary[] = [
  {
    id: 4,
    uuid: 'ut4',
    name: 'Amber Breathe',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'fx',
    requiredEmitters: [],
    rows: [],
    fx: {
      fxType: 'ColourWave',
      family: 'colour',
      cycle: 0.5,
      blendMode: 'OVERRIDE',
      order: 'NORMAL',
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
    attributeMask: null,
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
    adHocFx: [],
    attributeAssignments: [],
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
  it('renders each layer in order, named, with a palette’s targets and none on a Look’s', () => {
    render(
      <CueDetailContent
        cue={cue({
          layers: [
            layer(),
            layer({ source: { kind: 'PALETTE', id: 8, uuid: 'p8', name: 'Second' }, lookId: undefined, paletteId: 8, sortOrder: 1 }),
          ],
        })}
        projectId={1}
      />,
    )
    expect(screen.getByText('Warm Wash')).toBeInTheDocument()
    expect(screen.getByText('Second')).toBeInTheDocument()
    // A Look lands whole, so only the palette's row draws a target chip.
    expect(screen.getAllByText('hex-1')).toHaveLength(1)
  })

  it('shows a masked layer’s families, so a colour-only layer explains itself', () => {
    // The migration sets a mask on every layer folded from a value-level reference, so this is the
    // common case. It reads the same here as in the editor because both draw `LayerRow`.
    render(<CueDetailContent cue={cue({ layers: [layer({ attributeMask: 'COLOUR' })] })} projectId={1} />)
    expect(screen.getByText('[Colour]')).toBeInTheDocument()
  })

  it('offers no editing controls at all', () => {
    // The property that makes sharing `LayerRow` with the authoring surface safe. `readOnly` strips
    // amount, enable, remove and the combine popover; a regression here would put live controls on
    // the Run card and the Prompt Book rail, where there is no mutation behind them.
    render(
      <CueDetailContent
        cue={cue({ layers: [layer({ attributeMask: 'COLOUR', blendMode: 'MAX', amount: 0.5 })] })}
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

  it('says a palette layer with no targets lands on its own heads', () => {
    render(
      <CueDetailContent
        cue={cue({ layers: [layer({ source: { kind: 'PALETTE', id: 8, uuid: 'p8', name: 'Throne' }, lookId: undefined, paletteId: 8, targets: [] })] })}
        projectId={1}
      />,
    )
    expect(screen.getByText('its own heads')).toBeInTheDocument()
  })

  it('draws no stage map, however many heads the cue touches (venue-visit S3)', () => {
    render(
      <CueDetailContent cue={cue({ layers: [layer({ targets: [{ type: 'fixture', key: 'hex-1' }, { type: 'group', key: 'front' }] })] })} projectId={1} />,
    )
    expect(screen.queryByText('Stage')).not.toBeInTheDocument()
  })

  it('offers an empty state rather than a blank section', () => {
    render(<CueDetailContent cue={cue()} projectId={1} />)
    expect(screen.getByText('Layers')).toBeInTheDocument()
    expect(screen.getAllByText('None.').length).toBeGreaterThan(0)
  })
})
