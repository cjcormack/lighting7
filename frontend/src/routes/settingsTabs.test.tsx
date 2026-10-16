// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Navigation plan P2: a settings tab is offered exactly where the nav row it opens is. The Sync
 * tabs go from an operator, whom every cloud-sync route refuses; Surfaces follows its row onto the
 * project on the rig; and Project Settings' `stage` tab says Regions, as its Rig row does.
 */

let role: 'ADMIN' | 'OPERATOR' = 'ADMIN'
let project = { id: 7, name: 'Show', description: null, isCurrent: true, kind: 'SHOW' as const }

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
  useProjectListQuery: () => ({ data: [project] }),
  useUpdateProjectMutation: () => [vi.fn(), {}],
}))
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: false, canEdit: true }) }))
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

  it('offers Surfaces only where its row is, on the project on the rig', () => {
    project = { ...project, isCurrent: false }
    renderProject('/projects/7/settings/surfaces')
    expect(tabs()).toEqual(['General', 'Regions', 'Rigging', 'Sync'])
    expect(screen.queryByText('surfaces body')).toBeNull()
  })
})
