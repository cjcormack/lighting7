// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LegacyTemplatesRedirect } from './legacyRedirects'

vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 7 }, isLoading: false }),
}))

function Landed() {
  const { pathname, search } = useLocation()
  return <div data-testid="landed">{`${pathname}${search}`}</div>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/projects/:projectId/templates" element={<LegacyTemplatesRedirect />} />
        <Route path="/templates" element={<LegacyTemplatesRedirect />} />
        <Route path="/projects/:projectId/palettes" element={<Landed />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(cleanup)

/** An old `/templates?family=` deep link must still land on the palettes, filter and all. */
describe('LegacyTemplatesRedirect', () => {
  it('carries a project palette link, search included, to its palettes', () => {
    renderAt('/projects/3/templates?family=colour')
    expect(screen.getByTestId('landed')).toHaveTextContent('/projects/3/palettes?family=colour')
  })

  it('sends a bare /palettes link to the current project, search included', async () => {
    renderAt('/templates?family=position')
    expect(await screen.findByTestId('landed')).toHaveTextContent('/projects/7/palettes?family=position')
  })
})
