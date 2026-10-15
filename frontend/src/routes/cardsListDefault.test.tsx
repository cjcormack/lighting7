// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { RouterProvider } from 'react-router/dom'
import { createMemoryRouter } from 'react-router'

/**
 * Venue-visit plan S2: Fixtures and Groups land on their lists on a first visit. Driven as the
 * operator arrives — the sidebar's single entry is the bare cards path, with no link state — and
 * as they leave, through the switcher's own Cards segment, which must still reach the cards with
 * the default pointing the other way. `components/ViewSwitcher.test.ts` pins the rule for all four
 * pairs; this pins the two routes that read it with the new default.
 *
 * The project queries are held loading, so a cards route that does not redirect draws its spinner
 * and nothing else: the redirect is decided before the route waits on anything.
 */
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: undefined, isLoading: true }),
  useProjectQuery: () => ({ data: undefined, isLoading: true }),
}))

import { ProjectFixtures } from './Fixtures'
import { ProjectGroups } from './Groups'
import { FixturesViewSwitcher, GroupsViewSwitcher } from '../components/ViewSwitcher'

function draw(resource: 'fixtures' | 'groups') {
  const Cards = resource === 'fixtures' ? ProjectFixtures : ProjectGroups
  const Switcher = resource === 'fixtures' ? FixturesViewSwitcher : GroupsViewSwitcher
  const router = createMemoryRouter(
    [
      { path: `/projects/:projectId/${resource}`, element: <Cards /> },
      {
        path: `/projects/:projectId/${resource}/list`,
        element: (
          <div data-testid="list">
            <Switcher current="list" projectId={1} />
          </div>
        ),
      },
    ],
    { initialEntries: [`/projects/1/${resource}`] },
  )
  render(<RouterProvider router={router} />)
  return router
}

afterEach(() => {
  cleanup()
  localStorage.clear()
})

describe.each(['fixtures', 'groups'] as const)('/%s', (resource) => {
  it('lands a first visit on the list', () => {
    const router = draw(resource)
    expect(screen.getByTestId('list')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/projects/1/${resource}/list`)
  })

  it('reaches the cards from the list’s Cards segment, and stays there next visit', () => {
    const router = draw(resource)
    fireEvent.click(screen.getByRole('link', { name: /Cards/ }))
    expect(router.state.location.pathname).toBe(`/projects/1/${resource}`)
    expect(screen.queryByTestId('list')).toBeNull()
    cleanup()

    // The next arrival from the sidebar carries no link state; the stored choice holds it.
    const again = draw(resource)
    expect(again.state.location.pathname).toBe(`/projects/1/${resource}`)
  })

  it('keeps a desk that chose the cards before S2 on the cards', () => {
    localStorage.setItem(`${resource}.view`, '"cards"')
    const router = draw(resource)
    expect(router.state.location.pathname).toBe(`/projects/1/${resource}`)
    expect(screen.queryByTestId('list')).toBeNull()
  })
})
