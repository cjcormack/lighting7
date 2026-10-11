// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import type { VenueDiff, VenueHistory } from '@/api/venueApi'

let publishError: unknown
const publish = vi.fn()
const reset = vi.fn()
vi.mock('@/store/venues', () => ({
  usePublishVenueMutation: () => [publish, { isLoading: false, error: publishError, reset }],
  useVenueDiffQuery: () => ({ data: DIFF, isFetching: false }),
  useDiscardVenueDraftMutation: () => [vi.fn(), { isLoading: false }],
}))

import { PublishVenueSheet } from './PublishVenueSheet'

const DIFF: VenueDiff = {
  from: 2,
  to: null,
  output: [],
  drawing: [{ kind: 'RIGGING', table: 'riggings', name: 'FOH truss', change: 'CHANGED', output: false }],
}

const HISTORY: VenueHistory = {
  venueId: 9,
  name: 'Commemoration Hall',
  installed: 2,
  draft: { changes: 1, outputChanges: 0 },
  versions: [],
}

afterEach(() => {
  cleanup()
  publishError = undefined
})

describe('PublishVenueSheet (venue plan session 4)', () => {
  it('names a version another desk took, with the desk’s sentence, in the sheet', () => {
    publishError = {
      status: 409,
      data: { error: 'v3 was published on another desk while this draft was open', code: 'VENUE_VERSION_TAKEN' },
    }
    render(<PublishVenueSheet open onOpenChange={() => {}} history={HISTORY} />)
    expect(screen.getByText('That version was published on another desk')).toBeTruthy()
    expect(screen.getByText('v3 was published on another desk while this draft was open')).toBeTruthy()
  })

  it('names a pull-only desk’s refusal', () => {
    publishError = { status: 409, data: { error: 'This desk only pulls (sync.push = false)', code: 'VENUE_PUBLISH_PULL_ONLY' } }
    render(<PublishVenueSheet open onOpenChange={() => {}} history={HISTORY} />)
    expect(screen.getByText('This desk only pulls')).toBeTruthy()
  })

  it('draws any other refusal in the sheet too, under a plain title', () => {
    publishError = { status: 400, data: { error: 'A version needs a note saying what changed', code: 'VENUE_NOTE_REQUIRED' } }
    render(<PublishVenueSheet open onOpenChange={() => {}} history={HISTORY} />)
    expect(screen.getByText('Not published')).toBeTruthy()
    expect(screen.getByText('A version needs a note saying what changed')).toBeTruthy()
  })

  it('draws no alert before a refusal', () => {
    render(<PublishVenueSheet open onOpenChange={() => {}} history={HISTORY} />)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Publish v3' })).toBeTruthy()
  })
})
