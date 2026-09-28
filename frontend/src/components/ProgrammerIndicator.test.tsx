// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

/**
 * One mode: the badge is the blind signal wherever it is mounted — the app header and the
 * `ShowBar` alike. It briefly had a second (session 2b to `PD-BLIND-ON-PROGRAMMER`), when the bar
 * drew its own BLIND tile and told the badge to report only the count; the tile is the programmer's
 * action bar's now, and the `blindShownSeparately` arm went with it.
 */

const summary = { entryCount: 0, blind: false }
vi.mock('../store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: summary }),
}))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1 } }),
}))

import { ProgrammerIndicator } from './ProgrammerIndicator'

function draw({ at = '/projects/1/show' }: { at?: string } = {}) {
  return render(
    <MemoryRouter initialEntries={[at]}>
      <ProgrammerIndicator />
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  summary.entryCount = 0
  summary.blind = false
})

describe('ProgrammerIndicator', () => {
  it('says nothing when the programmer is empty and not blind', () => {
    const { container } = draw()
    expect(container.innerHTML).toBe('')
  })

  it('reports blind on its own, because a blind programmer looks like a working one', () => {
    summary.blind = true
    draw()
    expect(screen.getByText('Blind')).toBeTruthy()
  })

  it('reports the value count', () => {
    summary.entryCount = 5
    draw()
    expect(screen.getByText('5')).toBeTruthy()
  })

  it('reports blind and the count together, and explains both in the tooltip', () => {
    // "5 values, and none of them reaching the stage" is the useful sentence; the badge itself
    // spells out the word only above 760px, so the label carries it at every width.
    summary.blind = true
    summary.entryCount = 5
    draw()

    expect(screen.getByText('Blind')).toBeTruthy()
    expect(screen.getByText('5')).toBeTruthy()
    expect(screen.getByLabelText(/holds 5 values · Blind — the programmer is gated out/)).toBeTruthy()
  })

  it('has no way to be told to stay quiet about blind', () => {
    // The `blindShownSeparately` arm went with the ShowBar's tile. A badge that can be silenced is
    // a badge a host can silence with nothing else saying it, and the app header's mount on the
    // programmer is the case that must stay loud — being blind and not knowing it is the hazard.
    summary.blind = true
    render(
      <MemoryRouter initialEntries={['/projects/1/programmer']}>
        {/* @ts-expect-error — the prop is gone; a caller passing it must not compile. */}
        <ProgrammerIndicator blindShownSeparately />
      </MemoryRouter>,
    )
    expect(screen.getByText('Blind')).toBeTruthy()
  })

  it('offers the trip to the programmer from anywhere else', () => {
    summary.entryCount = 5
    draw({ at: '/projects/1/show' })

    const link = screen.getByRole('link')
    expect(link.getAttribute('href')).toBe('/projects/1/programmer')
    expect(link.getAttribute('aria-label')).toContain('Go to the programmer')
  })

  it('is inert on the programmer itself — a link to where you already are is noise', () => {
    summary.entryCount = 5
    draw({ at: '/projects/1/programmer' })

    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText('5')).toBeTruthy()
    expect(screen.getByLabelText(/Programmer holds 5 values$/)).toBeTruthy()
  })

  it('counts a programmer subroute as being here', () => {
    // Segment-aware, not a bare `startsWith`: `/programmer/fx` is still the programmer, and the
    // badge must not offer a trip to the page it is already sitting on.
    summary.entryCount = 5
    draw({ at: '/projects/1/programmer/fx' })

    expect(screen.queryByRole('link')).toBeNull()
  })

  it('washes amber only when blind', () => {
    summary.entryCount = 5
    const live = draw()
    expect(live.container.innerHTML).not.toContain('amber')
    cleanup()

    summary.blind = true
    const blind = draw()
    expect(blind.container.innerHTML).toContain('amber')
  })
})
