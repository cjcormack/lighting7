// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Navigation plan P2: a settings tab is offered exactly where the nav row it opens is. The Sync
 * tabs go from an operator, whom every cloud-sync route refuses; Surfaces is offered off the rig as
 * its row is, with D4's dot over a `NotOnRig` body; and Project Settings' `stage` tab says Regions,
 * as its Rig row does.
 */

let role: 'ADMIN' | 'OPERATOR' = 'ADMIN'
let project = { id: 7, name: 'Show', description: null, isCurrent: true, kind: 'SHOW' as const }
/** The show on the rig while [project] is not. */
const onTheRig = { id: 1, name: 'Sleeping Beauty', description: null, isCurrent: true, kind: 'SHOW' as const }

vi.mock('@/store/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/auth')>()),
  useAuthStatusQuery: () => ({ data: { user: { role } } }),
}))
vi.mock('@/store/installs', () => ({
  useInstallQuery: () => ({ data: undefined, isLoading: true }),
  useUpdateInstallMutation: () => [vi.fn(), {}],
}))
vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectQuery: () => ({ data: project, isLoading: false }),
  useProjectListQuery: () => ({ data: project.isCurrent ? [project] : [project, onTheRig] }),
  useUpdateProjectMutation: () => [vi.fn(), {}],
  useSetCurrentProjectMutation: () => [vi.fn(), { isLoading: false }],
}))
let venueMode = { isVenue: false, canEdit: true }
vi.mock('@/store/venues', () => ({ useVenueMode: () => venueMode }))
vi.mock('./CloudSync', () => ({
  CloudSyncHubBody: () => <div>sync hub</div>,
  ProjectSyncContent: () => <div>project sync</div>,
}))
vi.mock('./Surfaces', () => ({ SurfacesContent: () => <div>surfaces body</div> }))
vi.mock('./Diagnostics', () => ({ DiagnosticsContent: () => <div>diagnostics body</div> }))
vi.mock('@/components/users/UsersTab', () => ({ UsersTab: () => <div>users body</div> }))
vi.mock('@/components/updates/UpdatePanel', () => ({ UpdatePanel: () => <div>updates body</div> }))
vi.mock('@/components/remoteAccess/RemoteAccessPanel', () => ({ RemoteAccessPanel: () => <div>remote body</div> }))
vi.mock('@/components/telemetry/TelemetryCard', () => ({ TelemetryCard: () => null }))
vi.mock('@/components/stage/StageRegionsContent', () => ({ StageRegionsContent: () => <div>regions body</div> }))
vi.mock('@/components/rigging/RiggingsContent', () => ({ RiggingsContent: () => <div>rigging body</div> }))
vi.mock('@/components/venue/SeatingPresetCard', () => ({ SeatingPresetCard: () => null }))

const { InstallSettings } = await import('./InstallSettings')
const { ProjectSettings } = await import('./ProjectSettings')

const tabs = () => screen.getAllByRole('tab').map((tab) => tab.textContent)

function renderInstall(path = '/install') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/install" element={<InstallSettings />} />
        <Route path="/install/:tab" element={<InstallSettings />} />
      </Routes>
    </MemoryRouter>,
  )
}

function renderProject(path = '/projects/7/settings') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/projects/:projectId/settings" element={<ProjectSettings />} />
        <Route path="/projects/:projectId/settings/:tab" element={<ProjectSettings />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  role = 'ADMIN'
  project = { id: 7, name: 'Show', description: null, isCurrent: true, kind: 'SHOW' }
  venueMode = { isVenue: false, canEdit: true }
})
afterEach(cleanup)

describe('Install Settings tabs', () => {
  it('offers an admin every tab', () => {
    renderInstall()
    expect(tabs()).toEqual(['General', 'Users', 'Sync', 'Remote access', 'Updates', 'Diagnostics'])
  })

  it('offers an operator no Sync, Users or Remote access, as the sidebar does', () => {
    role = 'OPERATOR'
    renderInstall()
    expect(tabs()).toEqual(['General', 'Updates', 'Diagnostics'])
  })

  it('opens General for an operator who reaches the Sync tab by URL', () => {
    role = 'OPERATOR'
    renderInstall('/install/sync')
    expect(screen.queryByText('sync hub')).toBeNull()
    expect(screen.getByRole('tab', { name: 'General' }).getAttribute('data-state')).toBe('active')
  })
})

describe('Project Settings tabs', () => {
  it('says Regions for the stage tab, and offers an admin Surfaces and Sync on the project on the rig', () => {
    renderProject()
    expect(tabs()).toEqual(['General', 'Surfaces', 'Regions', 'Rigging', 'Sync'])
  })

  it('offers an operator no Sync tab', () => {
    role = 'OPERATOR'
    renderProject()
    expect(tabs()).toEqual(['General', 'Surfaces', 'Regions', 'Rigging'])
  })

  it('opens General for an operator who reaches the Sync tab by URL', () => {
    role = 'OPERATOR'
    renderProject('/projects/7/settings/sync')
    expect(screen.queryByText('project sync')).toBeNull()
  })

  it('offers Surfaces off the rig with its dot, over a NotOnRig body naming the show on it', () => {
    project = { ...project, isCurrent: false }
    renderProject('/projects/7/settings/surfaces')
    expect(tabs()).toEqual(['General', 'Surfaces', 'Regions', 'Rigging', 'Sync'])
    const surfaces = screen.getByRole('tab', { name: 'Surfaces' })
    expect(surfaces.querySelector('[data-off-rig-dot]')).not.toBeNull()
    expect(surfaces.getAttribute('title')).toBe('Needs the project on the rig')
    expect(screen.queryByText('surfaces body')).toBeNull()
    expect(screen.getByText(/isn.t on the rig/).textContent).toContain('Show')
    expect(screen.getByText('Sleeping Beauty')).toBeTruthy()
  })

  it('leaves Make current pressable for an operator on a venue, whose other tabs are read-only', () => {
    role = 'OPERATOR'
    project = { ...project, isCurrent: false }
    venueMode = { isVenue: true, canEdit: false }
    renderProject('/projects/7/settings/surfaces')
    expect(screen.getByRole('button', { name: 'Make Show current' }).matches(':disabled')).toBe(false)
  })

  it('draws Surfaces with no dot on the project on the rig', () => {
    renderProject('/projects/7/settings/surfaces')
    expect(screen.getByRole('tab', { name: 'Surfaces' }).querySelector('[data-off-rig-dot]')).toBeNull()
    expect(screen.getByText('surfaces body')).toBeTruthy()
  })
})
