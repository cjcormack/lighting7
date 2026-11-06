// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** Settings › General's *In production* switch (show-view-fixes plan D21): a show's, saved on the switch. */

let project: { id: number; name: string; description: null; isCurrent: boolean; kind: 'SHOW' | 'VENUE'; inProduction?: boolean }
const update = vi.fn((_arg: unknown) => ({ unwrap: () => Promise.resolve({}) }))

vi.mock('@/store/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/auth')>()),
  useAuthStatusQuery: () => ({ data: { user: { role: 'ADMIN' } } }),
}))
vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectQuery: () => ({ data: project, isLoading: false }),
  useProjectListQuery: () => ({ data: [project] }),
  useUpdateProjectMutation: () => [update, { isLoading: false }],
  useSetCurrentProjectMutation: () => [vi.fn(), { isLoading: false }],
}))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: project.kind === 'VENUE', canEdit: true }) }))
vi.mock('./CloudSync', () => ({ ProjectSyncContent: () => null }))
vi.mock('./Surfaces', () => ({ SurfacesContent: () => null }))
vi.mock('@/components/stage/StageRegionsContent', () => ({ StageRegionsContent: () => null }))
vi.mock('@/components/rigging/RiggingsContent', () => ({ RiggingsContent: () => null }))
vi.mock('@/components/venue/SeatingPresetCard', () => ({ SeatingPresetCard: () => null }))

const { ProjectSettings } = await import('./ProjectSettings')

function draw() {
  render(
    <MemoryRouter initialEntries={['/projects/7/settings']}>
      <Routes>
        <Route path="/projects/:projectId/settings" element={<ProjectSettings />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  project = { id: 7, name: 'Show', description: null, isCurrent: true, kind: 'SHOW', inProduction: true }
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('In production', () => {
  it('reads the show and turns it off in one update', async () => {
    draw()
    const toggle = screen.getByRole('switch', { name: 'In production' })
    expect(toggle.getAttribute('aria-checked')).toBe('true')
    await act(async () => {
      fireEvent.click(toggle)
    })
    expect(update).toHaveBeenCalledWith({ id: 7, inProduction: false })
  })

  it('reads an older desk, which sends no field, as on', () => {
    project = { ...project, inProduction: undefined }
    draw()
    expect(screen.getByRole('switch', { name: 'In production' }).getAttribute('aria-checked')).toBe('true')
  })

  it('is not offered on a venue, which has no lock', () => {
    project = { ...project, kind: 'VENUE' }
    draw()
    expect(screen.queryByRole('switch', { name: 'In production' })).toBeNull()
  })
})
