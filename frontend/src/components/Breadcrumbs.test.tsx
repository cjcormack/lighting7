// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'

const list: { data?: Array<{ id: number; name: string; isCurrent: boolean; kind: string }> } = {}
vi.mock('@/store/projects', () => ({ useProjectListQuery: () => list }))

import { Breadcrumbs } from './Breadcrumbs'

function draw(projectId: number, currentPage?: string) {
  return render(
    <MemoryRouter initialEntries={[`/projects/${projectId}/settings`]}>
      <Routes>
        <Route
          path="/projects/:projectId/settings"
          element={<Breadcrumbs projectName="Hamlet" currentPage={currentPage} />}
        />
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  cleanup()
  list.data = undefined
})

describe('Breadcrumbs', () => {
  const projects = [
    { id: 1, name: 'Hamlet', isCurrent: false, kind: 'SHOW' },
    { id: 2, name: 'Macbeth', isCurrent: true, kind: 'SHOW' },
  ]

  it.each([undefined, 'Settings'])('says "Not on the rig" for a project that is not current (page %s)', (page) => {
    list.data = projects
    draw(1, page)
    expect(screen.getByText('Not on the rig')).toBeTruthy()
    expect(screen.queryByText(/^active$|^inactive$/i)).toBeNull()
  })

  it.each([undefined, 'Settings'])('draws no badge on the project on the rig (page %s)', (page) => {
    list.data = projects
    draw(2, page)
    expect(screen.queryByText(/on the rig/i)).toBeNull()
    expect(screen.queryByText(/^active$/i)).toBeNull()
  })

  it('keeps the badge in the clipped, wrapping project segment, so it can never overlap the next crumb', () => {
    list.data = projects
    draw(1, 'Settings')
    const segment = screen.getByText('Not on the rig').parentElement!
    expect(segment.tagName).toBe('BUTTON')
    expect(segment.className).toContain('flex-wrap')
    expect(segment.className).toContain('overflow-hidden')
  })

  it('says nothing until the project list has loaded', () => {
    draw(1)
    expect(screen.queryByText(/on the rig/i)).toBeNull()
  })
})
