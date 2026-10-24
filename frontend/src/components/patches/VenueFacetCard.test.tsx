// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FixturePatch } from '@/api/patchApi'
import { EMPTY_LANTERNS } from '@/lib/lanterns'
import { indexGels } from '@/lib/gels'
import { VenueFacetCard, type FacetCardContext } from './VenueFacetCard'
import { FacetChips } from './PatchSheet'

const setFacet = vi.fn()
const keepFacet = vi.fn()

vi.mock('@/store/patches', async (orig) => ({
  ...(await orig<typeof import('@/store/patches')>()),
  useSetPatchFacetMutation: () => [setFacet, { isLoading: false }],
  useKeepPatchFacetMutation: () => [keepFacet, { isLoading: false }],
}))
// The gel field reads the served library; none is needed to draw it.
vi.mock('@/hooks/useGelIndex', () => ({ useGelIndex: () => indexGels([]) }))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } }))

/**
 * The fixture sheet's facet card (venue plan session 5, `Show.dc.html`): *Venue* reads the venue's
 * value, *This show* opens the facet's fields and saves it whole, a drifted facet draws the note with
 * *Keep mine* and *Take venue's*, and the Patch list's chips say which facets are the show's.
 */
afterEach(cleanup)
beforeEach(() => {
  setFacet.mockReset().mockReturnValue({ unwrap: () => Promise.resolve({ patch: {}, changed: true, warnings: [] }) })
  keepFacet.mockReset().mockReturnValue({ unwrap: () => Promise.resolve({ patch: {}, changed: true }) })
})

function patch(over: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id: 12, uuid: 'p-12', key: 'hex-1', displayName: 'Hex 1', fixtureTypeKey: 'hex', startChannel: 10, channelCount: 12,
    manufacturer: null, model: null, modeName: null, universe: 0, subnet: 0, sortOrder: 0, groups: [],
    stageX: null, stageY: null, stageZ: null, baseYawDeg: null, basePitchDeg: null, riggingUuid: null,
    beamAngleDeg: null, gelCode: 'L201', kindOverride: null, stageHidden: false, owner: 'VENUE',
    ...over,
  }
}

const ctx: FacetCardContext = {
  projectId: 5,
  fixtureType: { typeKey: 'hex', acceptsGel: true, acceptsBeamAngle: true, attributes: [] } as unknown as FacetCardContext['fixtureType'],
  universes: [
    { id: 1, subnet: 0, universe: 0, controllerType: 'ARTNET', address: null, refreshIntervalMs: 25, refreshIntervalOverridden: false, patchCount: 4, owner: 'VENUE', uuid: 'u-0' },
    { id: 2, subnet: 0, universe: 1, controllerType: 'ARTNET', address: null, refreshIntervalMs: 25, refreshIntervalOverridden: false, patchCount: 2, owner: 'VENUE', uuid: 'u-1' },
  ],
  lanterns: EMPTY_LANTERNS,
  gels: indexGels([]),
  names: { universeOf: (uuid) => (uuid === 'u-1' ? 1 : uuid === 'u-0' ? 0 : undefined) },
}

describe('VenueFacetCard', () => {
  it('reads the venue’s value, and This show opens the fields and saves the facet whole', async () => {
    render(<VenueFacetCard facet="ADDRESS" patch={patch()} ctx={ctx} />)
    expect(screen.getByText('0-010')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: 'This show' }))
    const start = screen.getByLabelText('Start channel')
    fireEvent.change(start, { target: { value: '200' } })
    fireEvent.click(screen.getByRole('button', { name: "Make address this show's" }))
    await waitFor(() => expect(setFacet).toHaveBeenCalled())
    expect(setFacet).toHaveBeenCalledWith({ projectId: 5, patchId: 12, facet: 'ADDRESS', value: { universe: 0, subnet: 0, startChannel: 200 } })
  })

  it('draws the drift note, and Keep mine and Take venue’s answer it', async () => {
    const drifted = patch({
      gelCode: 'L203',
      facets: [{
        facet: 'MEDIA', value: { gelCode: 'L203' }, venue: { gelCode: 'L202' }, drifted: true, base: { gelCode: 'L201' },
      }],
    })
    render(<VenueFacetCard facet="MEDIA" patch={drifted} ctx={ctx} />)
    expect(screen.getByText(/The venue changed this under you/)).toBeInTheDocument()
    expect(screen.getByText(/Gel L201 → Gel L202/)).toBeInTheDocument()
    expect(screen.getByText('Venue: Gel L202')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Keep mine' }))
    expect(keepFacet).toHaveBeenCalledWith({ projectId: 5, patchId: 12, facet: 'MEDIA' })
    fireEvent.click(screen.getByRole('button', { name: /Take venue/ }))
    expect(setFacet).toHaveBeenCalledWith({ projectId: 5, patchId: 12, facet: 'MEDIA', value: null })
  })

  it('reverts an override to the venue’s', () => {
    render(<VenueFacetCard facet="MEDIA" patch={patch({ facets: [{ facet: 'MEDIA', value: { gelCode: 'L201' }, venue: { gelCode: 'L106' } }] })} ctx={ctx} />)
    fireEvent.click(screen.getByRole('button', { name: /Revert to venue/ }))
    expect(setFacet).toHaveBeenCalledWith({ projectId: 5, patchId: 12, facet: 'MEDIA', value: null })
  })

  it('offers no This show for a facet the type has nothing in, and says why', () => {
    const noOptics = { ...ctx, fixtureType: { typeKey: 'hex', acceptsGel: false, acceptsBeamAngle: false, attributes: [] } as unknown as FacetCardContext['fixtureType'] }
    render(<VenueFacetCard facet="FOCUS" patch={patch()} ctx={noOptics} />)
    const item = screen.getByRole('radio', { name: 'This show' })
    expect(item).toBeDisabled()
    expect(item).toHaveAttribute('title', expect.stringMatching(/own optics/))
  })
})

describe('FacetChips', () => {
  it('lights the overridden facets and ambers a drifted one', () => {
    render(<FacetChips patch={patch({ facets: [
      { facet: 'POSITION', value: {}, venue: {} },
      { facet: 'MEDIA', value: {}, venue: {}, drifted: true },
    ] })} />)
    const chip = (facet: string) => document.querySelector(`[data-facet-chips] [data-facet="${facet}"]`)
    expect(chip('ADDRESS')).toHaveAttribute('data-state', 'venue')
    expect(chip('POSITION')).toHaveAttribute('data-state', 'show')
    expect(chip('MEDIA')).toHaveAttribute('data-state', 'drifted')
  })
})
