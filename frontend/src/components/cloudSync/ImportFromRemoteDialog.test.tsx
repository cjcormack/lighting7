// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'

let cloudState: { data?: unknown } = {}
const importRepo = vi.fn()

vi.mock('@/store/cloudSync', () => ({
  useCloudSyncImportMutation: () => [importRepo, { isLoading: false }],
  useCloudReposQuery: () => ({
    data: [
      { repoId: 'v', url: 'https://cloud.example/git/o/v.git', name: 'Commemoration Hall', kind: 'VENUE', projectUuid: 'v', sizeBytes: 0, syncedHere: false },
      { repoId: 's', url: 'https://cloud.example/git/o/s.git', name: 'Panto', kind: 'SHOW', projectUuid: 's', sizeBytes: 0, syncedHere: true, projectId: 3 },
    ],
  }),
}))
vi.mock('@/store/cloud', () => ({ useCloudStateQuery: () => cloudState }))
vi.mock('@/store/projects', () => ({ useProjectListQuery: () => ({ data: [] }) }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { AddRemoteProjectDialog } from './ImportFromRemoteDialog'

function renderDialog() {
  return render(
    <MemoryRouter>
      <AddRemoteProjectDialog open onOpenChange={() => {}} />
    </MemoryRouter>,
  )
}

describe('Import from the cloud', () => {
  afterEach(() => {
    cleanup()
    importRepo.mockReset()
  })

  it("lists the org's repos, badges a venue, and imports the one picked under its name", async () => {
    cloudState = { data: { configured: true, enabled: true, adopted: { installId: 'i' } } }
    importRepo.mockReturnValue({ unwrap: () => Promise.resolve({ projectId: 9, projectUuid: 'v', name: 'Commemoration Hall' }) })
    renderDialog()

    expect(screen.getByText('Venue')).toBeTruthy()
    const here = screen.getByRole('option', { name: /panto/i }) as HTMLButtonElement
    expect(here.disabled).toBe(true)
    expect(here.textContent).toContain('Already on this desk')

    fireEvent.click(screen.getByRole('option', { name: /commemoration hall/i }))
    expect((screen.getByLabelText(/project name/i) as HTMLInputElement).value).toBe('Commemoration Hall')
    fireEvent.click(screen.getByRole('button', { name: /^import$/i }))
    await waitFor(() =>
      expect(importRepo).toHaveBeenCalledWith({
        repoUrl: 'https://cloud.example/git/o/v.git',
        branch: 'main',
        projectName: 'Commemoration Hall',
      }),
    )
  })

  it('asks an unadopted desk to adopt', () => {
    cloudState = { data: { configured: true, enabled: true, adopted: null } }
    renderDialog()

    expect(screen.getByText(/adopt this desk to import/i)).toBeTruthy()
    expect(screen.queryByRole('option')).toBeNull()
  })
})
