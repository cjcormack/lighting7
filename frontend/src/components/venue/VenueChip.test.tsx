// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { VenueChip } from './VenueChip'
import { VenueShowsList } from './VenueShowsList'

afterEach(cleanup)

describe('VenueChip (venue plan session 3)', () => {
  it('names the venue and the pinned version, and opens the venue’s own Patch list', () => {
    render(
      <MemoryRouter>
        <VenueChip venue={{ uuid: 'aaaaaaaa-0000', id: 9, name: 'Commemoration Hall', version: 1, installed: 1 }} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Commemoration Hall')).toBeTruthy()
    expect(screen.getByText('· v1')).toBeTruthy()
    expect(screen.queryByText(/published/)).toBeNull()
    // Open venue goes to the venue's list — it never switches the desk's live project.
    expect(screen.getByRole('link', { name: 'Open venue' })).toHaveAttribute('href', '/projects/9/patches')
  })

  it('names a newer published version, and opens nothing for a venue this desk lacks', () => {
    render(
      <MemoryRouter>
        <VenueChip venue={{ uuid: 'bbbbbbbb-1111', id: null, name: null, version: 2, installed: 3 }} />
      </MemoryRouter>,
    )
    expect(screen.getByText('Venue bbbbbbbb')).toBeTruthy()
    expect(screen.getByText('· v3 published')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Open venue' })).toBeNull()
  })
})

describe('VenueShowsList (venue plan session 3)', () => {
  it('names each show on the venue with its pinned version, and says a publish changes none', () => {
    render(<VenueShowsList shows={[{ id: 4, name: 'Panto 2027', version: 1 }]} next={2} />)
    expect(screen.getByText('Panto 2027')).toBeTruthy()
    expect(screen.getByText('on v1')).toBeTruthy()
    expect(screen.getByText(/Publishing v2 changes none of them/)).toBeTruthy()
  })

  it('says so when no show is on the venue', () => {
    render(<VenueShowsList shows={[]} next={2} />)
    expect(screen.getByText('No show is on this venue yet, so publishing changes no show.')).toBeTruthy()
  })
})
