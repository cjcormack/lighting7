// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FixturePatch } from '@/api/patchApi'
import { EMPTY_LANTERNS } from '@/lib/lanterns'
import { indexGels } from '@/lib/gels'

const setFacet = vi.fn(() => ({ unwrap: () => Promise.resolve({ patch: {}, changed: true, warnings: [] }) }))
vi.mock('@/store/patches', () => ({
  useSetPatchFacetMutation: () => [setFacet, { isLoading: false }],
  useKeepPatchFacetMutation: () => [vi.fn(), { isLoading: false }],
  useSetPatchHiddenMutation: () => [vi.fn(), { isLoading: false }],
  useUniverseConfigListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/projects', () => ({ useProjectQuery: () => ({ data: { venue: { id: 3, name: 'Commemoration Hall', version: 2 } } }) }))
vi.mock('@/store/fixtures', async (orig) => ({
  ...(await orig<typeof import('@/store/fixtures')>()),
  useFixtureTypeListQuery: () => ({ data: [{ typeKey: 'hex', acceptsGel: true, acceptsBeamAngle: true, attributes: [] }] }),
}))
vi.mock('@/store/riggings', () => ({ useRiggingListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useLanternIndex', () => ({ useLanternIndex: () => EMPTY_LANTERNS }))
vi.mock('@/hooks/useGelIndex', () => ({ useGelIndex: () => indexGels([]) }))
vi.mock('./LocateBox', () => ({ LocateBox: () => null }))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } }))

const { VenuePatchPanel } = await import('./VenuePatchPanel')

/**
 * A venue fixture's panel in a show (venue-rig-fixes plan D25): the Identity block holds a Name card,
 * so the show may rename the fixture for itself while its key and type stay the venue's; the other
 * four facets follow below it.
 */
afterEach(() => {
  cleanup()
  setFacet.mockClear()
})

function patch(over: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id: 12, uuid: 'p-12', key: 'hex-1', displayName: 'Hex 1', fixtureTypeKey: 'hex', startChannel: 10, channelCount: 12,
    manufacturer: null, model: null, modeName: null, universe: 0, subnet: 0, sortOrder: 0, groups: [],
    stageX: null, stageY: null, stageZ: null, baseYawDeg: null, basePitchDeg: null, riggingUuid: null,
    beamAngleDeg: null, gelCode: null, kindOverride: null, stageHidden: false, owner: 'VENUE',
    ...over,
  }
}

function draw(p = patch()) {
  render(
    <MemoryRouter>
      <VenuePatchPanel patch={p} projectId={5} onClose={() => {}} />
    </MemoryRouter>,
  )
}

describe('VenuePatchPanel', () => {
  it('draws the Name card in the Identity block, the other facets after it, and says a show may rename', () => {
    draw()
    const identity = screen.getByRole('region', { name: 'Identity' })
    expect(within(identity).getByRole('region', { name: 'Name' })).toBeInTheDocument()
    expect(within(identity).getByText(/rename it for this show, not rekey or retype/)).toBeInTheDocument()
    expect(within(identity).getByText('hex-1')).toBeInTheDocument()
    const facets = Array.from(document.querySelectorAll('[data-facet]')).map((e) => e.getAttribute('data-facet'))
    expect(facets).toEqual(['NAME', 'ADDRESS', 'POSITION', 'FOCUS', 'MEDIA'])
  })

  it('renames the fixture for this show through the Name facet', () => {
    draw()
    const card = screen.getByRole('region', { name: 'Name' })
    fireEvent.click(within(card).getByRole('radio', { name: 'This show' }))
    fireEvent.change(within(card).getByLabelText('Name in this show'), { target: { value: 'Stage left wash' } })
    fireEvent.click(within(card).getByRole('button', { name: "Make name this show's" }))
    expect(setFacet).toHaveBeenCalledWith({ projectId: 5, patchId: 12, facet: 'NAME', value: { displayName: 'Stage left wash' } })
  })

  it('marks the Name card overridden while the show names the fixture its own way', () => {
    draw(patch({ displayName: 'Stage left wash', facets: [{ facet: 'NAME', value: { displayName: 'Stage left wash' }, venue: { displayName: 'Hex 1' } }] }))
    const card = screen.getByRole('region', { name: 'Name' })
    expect(card).toHaveAttribute('data-overridden', 'true')
    expect(within(card).getByText('Venue: Hex 1')).toBeInTheDocument()
  })
})
