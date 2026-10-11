// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ProjectDetail } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'

let project: Partial<ProjectDetail> | undefined
let hold: VenueHoldState
vi.mock('@/store/projects', () => ({ useProjectQuery: () => ({ data: project }) }))
const runAgainst = vi.fn()
vi.mock('@/store/venueHold', () => ({
  useVenueHold: () => hold,
  useRunVenueAgainstMutation: () => [
    (arg: unknown) => {
      runAgainst(arg)
      return { unwrap: () => Promise.resolve({}) }
    },
    { isLoading: false },
  ],
}))
vi.mock('./VenueUpdateSheet', () => ({
  VenueUpdateSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="review" /> : null),
}))
vi.mock('./RunAgainstDialog', () => ({
  RunAgainstDialog: ({ open }: { open: boolean }) => (open ? <div data-testid="run-against" /> : null),
}))

import { VenueBehindBanner } from './VenueBehindBanner'

const SHOW = (behind: 'NONE' | 'DRAWING' | 'OUTPUT', version = 3): Partial<ProjectDetail> => ({
  id: 4,
  name: 'Spring Concert 2026',
  kind: 'SHOW',
  venue: { uuid: 'u', id: 9, name: 'Commemoration Hall', version, installed: 5, behind, drawingChanges: 2 },
})
const NOT_HELD: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }

afterEach(() => {
  cleanup()
  sessionStorage.clear()
  hold = NOT_HELD
})

describe('VenueBehindBanner (venue plan session 6)', () => {
  it('holds the live show: the board’s sentence, Review update and Run against', () => {
    project = SHOW('OUTPUT')
    hold = { projectId: 4, held: true, pinned: 3, installed: 5, runAgainst: false }
    render(<VenueBehindBanner projectId={4} />)
    expect(screen.getByText(/Output held — this show is in Blind\. It uses Commemoration Hall v3/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Run against v3…' }))
    expect(screen.getByTestId('run-against')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Review update' }))
    expect(screen.getByTestId('review')).toBeTruthy()
  })

  it('says it is running against the old pin, and offers the hold back', () => {
    project = SHOW('OUTPUT')
    hold = { projectId: 4, held: false, pinned: 3, installed: 5, runAgainst: true }
    render(<VenueBehindBanner projectId={4} />)
    expect(screen.getByText(/Running against v3\. Live output uses the old house patch\./)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Run against/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Hold output' }))
    expect(runAgainst).toHaveBeenCalledWith({ projectId: 4, on: false })
  })

  it('is a drawing-only banner with the count, and Later hides it for the session', () => {
    project = SHOW('DRAWING', 4)
    render(<VenueBehindBanner projectId={4} />)
    expect(screen.getByText(/Spring Concert 2026 uses v4; v5 is the hall as hung — 2 drawing changes, nothing that changes output\./)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Later' }))
    expect(screen.queryByRole('status')).toBeNull()
    cleanup()
    render(<VenueBehindBanner projectId={4} />)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('draws nothing for a show on its latest', () => {
    project = SHOW('NONE', 5)
    const { container } = render(<VenueBehindBanner projectId={4} />)
    expect(container.firstChild).toBeNull()
  })
})
