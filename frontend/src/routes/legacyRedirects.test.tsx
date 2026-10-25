// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LegacyAppliedRedirect, LegacyPalettesRedirect } from './legacyRedirects'

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
        <Route path="/projects/:projectId/templates" element={<LegacyPalettesRedirect />} />
        <Route path="/templates" element={<LegacyPalettesRedirect />} />
        <Route path="/projects/:projectId/palettes" element={<Landed />} />
        <Route path="/projects/:projectId/programmer/applied" element={<LegacyAppliedRedirect />} />
        <Route path="/projects/:projectId/programmer" element={<Landed />} />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(cleanup)

/** An old `/templates?family=` deep link must still land on the palettes, filter and all. */
describe('LegacyPalettesRedirect', () => {
  it('carries a project palette link, search included, to its palettes', () => {
    renderAt('/projects/3/templates?family=colour')
    expect(screen.getByTestId('landed')).toHaveTextContent('/projects/3/palettes?family=colour')
  })

  it('sends a bare /palettes link to the current project, search included', async () => {
    renderAt('/templates?family=position')
    expect(await screen.findByTestId('landed')).toHaveTextContent('/projects/7/palettes?family=position')
  })
})

/** The applied tables became the programmer's tabs (tables-colours plan D1). */
describe('LegacyAppliedRedirect', () => {
  it('lands Applied FX on the FX tab, its view intact', () => {
    renderAt('/projects/3/programmer/applied?table=fx&sort=name&dir=desc&q=wash&home=yours')
    expect(screen.getByTestId('landed')).toHaveTextContent('/projects/3/programmer?table=fx&sort=name&dir=desc&q=wash&home=yours')
  })

  it('lands Applied Looks and Applied Palettes on the Stack tab filtered to their kind', () => {
    renderAt('/projects/3/programmer/applied?table=looks&q=storm')
    expect(screen.getByTestId('landed')).toHaveTextContent(/^\/projects\/3\/programmer\?table=stack&kind=looks$/)
    cleanup()
    renderAt('/projects/3/programmer/applied?table=palettes&family=colour')
    expect(screen.getByTestId('landed')).toHaveTextContent(/^\/projects\/3\/programmer\?table=stack&kind=palettes$/)
  })

  it('lands a bare address on FX, the route’s default', () => {
    renderAt('/projects/3/programmer/applied')
    expect(screen.getByTestId('landed')).toHaveTextContent(/^\/projects\/3\/programmer\?table=fx$/)
  })
})
