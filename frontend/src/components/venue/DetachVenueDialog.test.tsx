// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { DetachResult } from '@/api/venueApi'

const calls = vi.fn()
let dry: DetachResult | undefined
vi.mock('@/store/venues', () => ({
  useDetachVenueMutation: () => [
    (arg: unknown) => {
      calls(arg)
      return { unwrap: () => Promise.resolve(dry) }
    },
    { data: dry, isLoading: false, error: undefined, reset: () => {} },
  ],
}))

import { DetachVenueDialog } from './DetachVenueDialog'

const DRY: DetachResult = {
  dryRun: true,
  projectId: 4,
  venueId: 9,
  venueName: 'Commemoration Hall',
  version: 3,
  installed: 5,
  behind: 'OUTPUT',
  copied: {
    universes: ['0:0', '0:1'],
    patches: ['hex-1', 'hex-2', 'hazer'],
    riggings: ['FOH Truss'],
    regions: [],
    elements: ['Balcony'],
    groups: ['House'],
    viewpoints: [],
  },
  overridesApplied: ['hex-2: address, media'],
  hidesDropped: 1,
  relinked: { 'cue_events.patch_id': 2 },
  dropped: [],
  machineOverridesCopied: 1,
  imagesCopied: 0,
}

afterEach(() => {
  cleanup()
  calls.mockReset()
})

/** [result] as the dry run's answer; null for one that has not come back yet. */
function draw(result: DetachResult | null = DRY) {
  dry = result ?? undefined
  render(<DetachVenueDialog open onOpenChange={() => {}} projectId={4} showName="Spring Concert 2026" venueName="Commemoration Hall" />)
}

describe('DetachVenueDialog (venue plan session 7)', () => {
  it('reads the detach rolled back, and says what becomes the show’s own', () => {
    draw()
    expect(calls).toHaveBeenCalledWith({ projectId: 4, dryRun: true })
    expect(screen.getByText('Detach Spring Concert 2026 from Commemoration Hall?')).toBeTruthy()
    expect(screen.getByText(/3 fixtures · 2 universes · 1 rigging · 1 scene element · 1 group from v3/)).toBeTruthy()
    expect(screen.getByText(/hex-2: address, media/)).toBeTruthy()
    expect(screen.getByText(/1 hidden record stays the venue/)).toBeTruthy()
  })

  it('says a behind show detaches at its pin, and the hall’s later changes stay behind', () => {
    draw()
    expect(screen.getByText(/Commemoration Hall is at v5, which changes output\. It detaches at v3/)).toBeTruthy()
    cleanup()
    draw({ ...DRY, behind: 'NONE', installed: 3 })
    expect(screen.queryByText(/It detaches at v3/)).toBeNull()
  })

  it('detaches for real on the press, and waits for the preview first', async () => {
    draw(null)
    expect(screen.getByRole('button', { name: 'Detach' })).toBeDisabled()
    cleanup()
    calls.mockReset()
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Detach' }))
    await waitFor(() => expect(calls).toHaveBeenCalledWith({ projectId: 4 }))
  })
})
