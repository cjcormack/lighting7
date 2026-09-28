// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import type { LookSummary } from '@/api/looksApi'

/**
 * The programmer's stack at the rail's dense density: two lines per row, the amount, blend, mask,
 * stomp and remove behind one popover. What is pinned is the index → `layerId` translation
 * through every one of those controls — the popover moved them off the row, and a handler that
 * resolved the wrong row would do so silently. The picker's forwarding, which used to live here
 * behind the stack's own Add button, is `ProgrammerAddLayerSheet.test.tsx`'s now.
 */
const mocks = vi.hoisted(() => ({
  layers: [] as ProgrammerLayer[],
  removeLayer: vi.fn(),
  moveLayer: vi.fn(),
  patchLayer: vi.fn(),
}))

vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: mocks.layers }),
  programmerRemoveLayer: mocks.removeLayer,
  programmerMoveLayer: mocks.moveLayer,
  programmerPatchLayer: mocks.patchLayer,
}))
vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({ data: LOOKS }),
}))
// The stack loads both libraries now, because a layer can apply either. Mocked as loaded-and-empty:
// these tests are about layer *addressing*, and an unmocked query has no Provider to read.
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: [] }),
}))
vi.mock('react-router', () => ({ useParams: () => ({ projectId: '1' }) }))

import { ProgrammerLookStack } from './ProgrammerLookStack'
import { ProgrammerScopeProvider, useProgrammerScope } from './ProgrammerScope'

function look(id: number, name: string, effectCount = 0): LookSummary {
  return {
    id,
    uuid: `u${id}`,
    name,
    notes: null,
    families: ['COLOUR'],
    rowCount: 1,
    effectCount,
    targetCount: 1,
    hasDeferredEffects: false,
    preview: [],
    layerCount: 1,
    buskPageCount: 0,
  }
}

const LOOKS = [look(7, 'Warm Wash'), look(8, 'Slow Pulse', 2)]

function layer(overrides: Partial<ProgrammerLayer> = {}): ProgrammerLayer {
  return {
    layerId: 1,
    source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' },
    sortOrder: 0,
    enabled: true,
    targets: [{ type: 'group', key: 'front-wash' }],
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
    ...overrides,
  }
}

const SLOW_PULSE = { kind: 'LOOK', id: 8, uuid: 'u8', name: 'Slow Pulse' } as const

/**
 * Open the popover of the row drawn at `row` — every control but enable and focus lives behind it.
 * The dense list is drawn **top wins**, so row 0 is the LAST layer in the array and the highest
 * precedence; the tests below pin that by reaching for the second-drawn row and expecting the
 * first layer's id.
 */
function openSettings(row: number) {
  fireEvent.click(screen.getAllByLabelText('Layer settings')[row])
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.layers = []
})

describe('ProgrammerLookStack', () => {
  it('addresses a layer by its id, not by the row it was drawn at', () => {
    // The whole point of the index → `layerId` translation: `layerId`s are not positions and are
    // not dense, so acting on the array index would hit the wrong layer.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE })]
    render(<ProgrammerLookStack />)

    // Drawn top wins: the second row on screen is the FIRST layer in the array.
    fireEvent.click(screen.getAllByLabelText('Disable layer')[1])
    expect(mocks.patchLayer).toHaveBeenCalledWith(40, { enabled: false })

    openSettings(0)
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    expect(mocks.removeLayer).toHaveBeenCalledWith(12)
  })

  it('draws the stack top wins: the last layer first, badged with its array position', () => {
    // The desk's array is sortOrder ascending and later wins, so the row at the top of a list
    // headed "top wins" has to be the last one. Only the rendering reverses — the badge and every
    // index a handler receives are the array's — so the FX band's "layer 2" still names badge 2.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE })]
    render(<ProgrammerLookStack />)
    const names = screen.getAllByTitle('Show this look in the grid').map((b) => b.textContent)
    expect(names).toEqual(['Slow Pulse', 'Warm Wash'])
    const badges = screen.getAllByLabelText('Reorder layer').map((h) => h.nextElementSibling?.textContent)
    expect(badges).toEqual(['2', '1'])
  })

  it('toggles stomp against the layer id', () => {
    // Stomp is the programmer's escape hatch from the Layer 3/4 boundary: a busking effect below
    // fighting a value a Look above sets. It goes through `patchLayer` like every other field —
    // deliberately *not* through the pads' `looks/{id}/toggle`, which owns add/remove only.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE })]
    render(<ProgrammerLookStack />)

    openSettings(1)
    fireEvent.click(screen.getByLabelText('Stomp lower layers'))
    expect(mocks.patchLayer).toHaveBeenCalledWith(40, { stomp: true })
  })

  it('says on the row itself that a layer is stomping', () => {
    // The one setting that changes what the rows *below* do, so it does not hide in the popover.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE, stomp: true })]
    render(<ProgrammerLookStack />)
    expect(screen.getAllByText('stomp')).toHaveLength(1)
  })

  it('commits an amount against the layer id', () => {
    mocks.layers = [layer({ layerId: 40, amount: 1 })]
    render(<ProgrammerLookStack />)

    openSettings(0)
    const input = screen.getByLabelText('Layer amount (%)')
    fireEvent.change(input, { target: { value: '60' } })
    fireEvent.blur(input)

    expect(mocks.patchLayer).toHaveBeenCalledWith(40, { amount: 0.6 })
  })

  it('addresses a blend or mask change by layerId, not by index', async () => {
    // Same index→id translation the other ops get, and worth its own case because blend and mask
    // arrive from a popover rather than from the row: the second row must still reach layer 41.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 41, source: SLOW_PULSE })]
    render(<ProgrammerLookStack />)

    openSettings(1)
    fireEvent.click(await screen.findByLabelText('Colour'))
    expect(mocks.patchLayer).toHaveBeenCalledWith(40, { propertyMask: 'COLOUR' })
  })

  it('clears a mask with an empty string, because an omitted field means leave alone', async () => {
    // `programmer.patchLayer` treats a missing field as "don't touch". Sending `undefined` for a
    // cleared mask would make un-masking a silent no-op, so null has to travel as ''.
    mocks.layers = [layer({ layerId: 40, propertyMask: 'COLOUR' })]
    render(<ProgrammerLookStack />)

    openSettings(0)
    fireEvent.click(await screen.findByLabelText('Colour'))
    expect(mocks.patchLayer).toHaveBeenCalledWith(40, { propertyMask: '' })
  })

  it('reads kind, targets, amount, mask and effects off the second line', () => {
    // The wide row spreads these across chips; at 300px they are one truncating line, so every
    // fact that line carries is asserted, and the full target list rides its title.
    mocks.layers = [
      layer({ layerId: 40, amount: 0.6, propertyMask: 'COLOUR', blendMode: 'ADD' }),
      layer({
        layerId: 41,
        source: SLOW_PULSE,
        targets: [
          { type: 'fixture', key: 'hex-1' },
          { type: 'fixture', key: 'hex-2' },
        ],
      }),
      layer({ layerId: 42, targets: [] }),
    ]
    render(<ProgrammerLookStack />)
    expect(screen.getByText('Look · front-wash · 60% · [Colour] · ADD')).toBeInTheDocument()
    expect(screen.getByText('Look · 2 targets · 100% · 2 effects')).toHaveAttribute(
      'title',
      expect.stringContaining('On hex-1, hex-2'),
    )
    expect(screen.getByText('Look · own targets · 100%')).toBeInTheDocument()
  })

  it('points the grid at a layer from its name badge, by layer id', () => {
    // Focus is this client's only, so it never reaches the wire — it sets the scope the grid
    // reads, keyed on `layerId` because two rows may apply one Look.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE })]
    const seen: unknown[] = []
    function Probe() {
      seen.push(useProgrammerScope())
      return null
    }
    render(
      <ProgrammerScopeProvider>
        <ProgrammerLookStack />
        <Probe />
      </ProgrammerScopeProvider>,
    )

    fireEvent.click(screen.getAllByTitle('Show this look in the grid')[1])
    expect(seen.at(-1)).toEqual({ kind: 'layer', layerId: 40 })
    expect(screen.getByTitle('The grid is showing this look')).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('gives every row a drag handle', () => {
    // dnd-kit's pointer sequence isn't drivable with fireEvent; what is pinned is that the dense
    // row kept the affordance the wide row has, once per row.
    mocks.layers = [layer({ layerId: 40 }), layer({ layerId: 12, source: SLOW_PULSE })]
    render(<ProgrammerLookStack />)
    expect(screen.getAllByLabelText('Reorder layer')).toHaveLength(2)
  })

  it('offers an empty state rather than a blank pane', () => {
    render(<ProgrammerLookStack />)
    expect(screen.getByText(/No layers\./)).toBeInTheDocument()
  })
})
