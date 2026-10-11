// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import type { VenueHistory } from '@/api/venueApi'

const mode = { isVenue: true, canEdit: true }
let history: VenueHistory | undefined
vi.mock('@/store/venues', () => ({
  useVenueMode: () => mode,
  useVenueHistory: () => ({ data: mode.canEdit ? history : undefined }),
}))
// The sheets are their own; here, whether the band offers them.
vi.mock('./PublishVenueSheet', () => ({ PublishVenueSheet: () => null }))
vi.mock('./VenueHistorySheet', () => ({ VenueHistorySheet: () => null }))

import { VenueBand } from './VenueBand'

const HISTORY: VenueHistory = {
  venueId: 9,
  name: 'Commemoration Hall',
  installed: 5,
  draft: { changes: 6, outputChanges: 4 },
  versions: [],
}

afterEach(() => {
  cleanup()
  mode.isVenue = true
  mode.canEdit = true
  history = HISTORY
})

describe('VenueBand (venue plan session 2)', () => {
  it('tells an admin they are editing a draft, with its size, History and Publish', () => {
    history = HISTORY
    render(<VenueBand projectId={9} />)
    expect(screen.getByText('Editing the venue.')).toBeTruthy()
    expect(screen.getByText('Draft · 6 changes since v5')).toBeTruthy()
    expect(screen.getByRole('button', { name: /History/ })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Publish v6…/ })).toBeEnabled()
  })

  it('offers no Publish when the draft is the installed version', () => {
    history = { ...HISTORY, draft: { changes: 0, outputChanges: 0 } }
    render(<VenueBand projectId={9} />)
    expect(screen.getByText('Draft · no changes since v5')).toBeTruthy()
    expect(screen.getByRole('button', { name: /Publish v6…/ })).toBeDisabled()
  })

  it('shows an operator the venue read-only: no draft, no History, no Publish', () => {
    mode.canEdit = false
    render(<VenueBand projectId={9} />)
    expect(screen.getByText('Viewing a venue.')).toBeTruthy()
    expect(screen.queryByText(/Draft ·/)).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('draws nothing on a show', () => {
    mode.isVenue = false
    mode.canEdit = false
    const { container } = render(<VenueBand projectId={1} />)
    expect(container.firstChild).toBeNull()
  })
})
