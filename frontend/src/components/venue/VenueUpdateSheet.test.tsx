// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { VenueUpdateReview } from '@/api/venueApi'

const update = vi.fn()
let review: VenueUpdateReview
vi.mock('@/store/venueHold', () => ({
  useVenueUpdateReviewQuery: () => ({ data: review, isFetching: false }),
  useUpdateVenueMutation: () => [
    (arg: unknown) => {
      update(arg)
      return { unwrap: () => Promise.resolve({}) }
    },
    { isLoading: false, error: undefined, reset: () => {} },
  ],
}))

import { VenueUpdateSheet } from './VenueUpdateSheet'

const REVIEW: VenueUpdateReview = {
  projectId: 4,
  venueId: 9,
  venueName: 'Commemoration Hall',
  from: 3,
  to: 5,
  fromNote: 'Re-patch: dimmers to 1–24',
  toNote: 'Added house lights on U4',
  output: [{ kind: 'PATCH', table: 'fixturePatches', uuid: 'h', name: 'HAZER', change: 'CHANGED', output: true, facets: ['ADDRESS'], fields: [{ field: 'address', from: '1-500', to: '1-512' }] }],
  drawing: [],
  carryOver: [{ kind: 'HIDE', name: 'FOS 7', detail: 'Your hide of fixture FOS 7' }],
  drift: [{ id: 'drift:x:MEDIA', recordUuid: 'x', patchName: 'LX2 C FRES', facet: 'MEDIA', value: { gelCode: 'L201' }, venue: { gelCode: 'L202' } }],
  dropped: [{ kind: 'OVERRIDE', name: 'FOS 7', detail: 'Your focus override on FOS 7: it is gone in v5' }],
  decisions: [{
    id: 'overlap:a:b',
    kind: 'ADDRESS_OVERLAP',
    message: 'Your SMOKE 2 at 1-512 clashes with HAZER (hazer) at 1-512: two fixtures can’t share an address.',
    mine: { uuid: 'a', patchId: 1, key: 'smoke-2', name: 'SMOKE 2', address: '1-512', kind: 'OWN' },
    other: { uuid: 'b', patchId: 2, key: 'hazer', name: 'HAZER', address: '1-512', kind: 'VENUE' },
    answers: ['KEEP_BOTH', 'READDRESS_MINE', 'HIDE_VENUES', 'REMOVE_MINE'],
    suggestedStartChannel: 480,
    suggestedAddress: '1-480',
  }],
  blockers: [],
  deadReferences: [{ kind: 'FIXTURE', key: 'fos-7', detail: "'fos-7' is gone in v5 and is named by Q12, Look Warm front." }],
}

afterEach(() => {
  cleanup()
  update.mockReset()
})

function draw(r: VenueUpdateReview = REVIEW) {
  review = r
  render(<VenueUpdateSheet open onOpenChange={() => {}} projectId={4} showName="Spring Concert 2026" />)
}

describe('VenueUpdateSheet (venue plan session 6)', () => {
  it('lays the hall’s changes beside what they do to the show', () => {
    draw()
    expect(screen.getByText('Update Spring Concert 2026 to Commemoration Hall v5')).toBeTruthy()
    expect(screen.getByText('HAZER · 1-500 → 1-512')).toBeTruthy()
    expect(screen.getByText(/carry over unchanged/)).toBeTruthy()
    expect(screen.getByText(/Your focus override on FOS 7/)).toBeTruthy()
    expect(screen.getByText(/'fos-7' is gone in v5/)).toBeTruthy()
    expect(screen.getByText(/yours \(L201\) is kept/)).toBeTruthy()
  })

  it('holds Update until every clash is answered, then sends the answers', async () => {
    draw()
    const go = screen.getByRole('button', { name: 'Update to v5' })
    expect(go).toBeDisabled()
    const group = screen.getByRole('radiogroup', { name: 'Answer: SMOKE 2' })
    expect(within(group).getByRole('radio', { name: 'Re-address mine → 1-480' })).toBeTruthy()
    fireEvent.click(within(group).getByRole('radio', { name: 'Keep both' }))
    fireEvent.click(screen.getByRole('button', { name: "Take venue's" }))
    expect(go).toBeEnabled()
    fireEvent.click(go)
    expect(update).toHaveBeenCalledWith({
      projectId: 4,
      to: 5,
      decisions: [
        { id: 'overlap:a:b', answer: 'KEEP_BOTH' },
        { id: 'drift:x:MEDIA', answer: 'TAKE_VENUES' },
      ],
    })
  })

  it('cannot update past a clash that cannot be kept', () => {
    draw({ ...REVIEW, decisions: [], blockers: [{ kind: 'KEY_CLASH', name: 'smoke-2', detail: "your SMOKE 2 has the key 'smoke-2', which v5's SMOKE takes" }] })
    expect(screen.getByText(/which v5's SMOKE takes/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Update to v5' })).toBeDisabled()
  })
})
