// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SyncConfig } from '@/store/cloudSync'

let config: SyncConfig
let cloudState: { data?: unknown; isError?: boolean } = {}
const syncToCloud = vi.fn()
const reconnect = vi.fn()
const move = vi.fn()
const toastSuccess = vi.fn()

vi.mock('@/store/cloudSync', () => ({
  useCloudSyncConfigQuery: () => ({ data: config, isLoading: false }),
  useCloudSyncToCloudMutation: () => [syncToCloud, { isLoading: false }],
  useCloudSyncReconnectMutation: () => [reconnect, { isLoading: false }],
  useCloudSyncRunMutation: () => [() => ({ unwrap: () => Promise.resolve({}) }), { isLoading: false }],
  useCloudMoveMutation: () => [move, { isLoading: false, error: undefined, reset: vi.fn() }],
  useCloudReposQuery: () => ({
    data: [{ repoId: 'r1', url: 'https://cloud.example/git/o/r1.git', name: 'Panto 2026', kind: 'SHOW', projectUuid: 'p', sizeBytes: 0, syncedHere: false }],
  }),
}))
vi.mock('@/store/cloud', () => ({ useCloudStateQuery: () => cloudState }))
vi.mock('sonner', () => ({ toast: { success: (...args: unknown[]) => toastSuccess(...args), error: vi.fn() } }))
vi.mock('@/components/cloudSync/AutoSyncForm', () => ({ AutoSyncForm: () => null }))
vi.mock('@/components/cloudSync/DisconnectConfirmDialog', () => ({ DisconnectConfirmDialog: () => null }))

import { ConfigPanel } from './ConfigPanel'

const UNSYNCED: SyncConfig = {
  branch: 'main',
  repoUrl: null,
  synced: false,
  autoSyncEnabled: false,
  autoSyncIntervalMs: null,
  lastSyncedSha: null,
  lastSyncedAt: null,
  linkedRepos: [],
}

const ADOPTED = { data: { configured: true, enabled: true, adopted: { installId: 'i', orgName: 'Org', adoptedAt: '', fingerprint: '' } } }

function renderPanel() {
  return render(
    <MemoryRouter>
      <ConfigPanel projectId={7} />
    </MemoryRouter>,
  )
}

describe('ConfigPanel', () => {
  afterEach(() => {
    cleanup()
    cloudState = {}
    syncToCloud.mockReset()
    reconnect.mockReset()
    move.mockReset()
    toastSuccess.mockReset()
  })

  it('offers one button, Sync to the cloud, on an adopted desk', async () => {
    config = UNSYNCED
    cloudState = ADOPTED
    syncToCloud.mockReturnValue({ unwrap: () => Promise.resolve({ config: { ...UNSYNCED, repoName: 'Panto' }, existed: false }) })
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /sync to the cloud/i }))
    await waitFor(() => expect(syncToCloud).toHaveBeenCalledWith(7))
    expect(screen.queryByText(/adopt this desk/i)).toBeNull()
    expect(screen.queryByText(/github/i)).toBeNull()
  })

  it('sends an unadopted desk to the Cloud tab, and nothing else of the cloud', () => {
    config = UNSYNCED
    cloudState = { data: { configured: true, enabled: true, adopted: null } }
    renderPanel()

    expect(screen.getByText(/adopt this desk to sync/i)).toBeTruthy()
    expect(screen.getByRole('link', { name: /open the cloud tab/i }).getAttribute('href')).toBe('/install/cloud')
    expect(screen.queryByRole('button', { name: /sync to the cloud/i })).toBeNull()
  })

  it('names a remembered cloud repo by its cloud name, never its URL, and offers no GitHub one', () => {
    config = {
      ...UNSYNCED,
      linkedRepos: [
        { repoUrl: 'https://cloud.example/git/o/r1.git', lastLinkedAt: '', cloud: true },
        { repoUrl: 'https://github.com/o/old.git', lastLinkedAt: '', cloud: false },
      ],
    }
    cloudState = ADOPTED
    renderPanel()

    expect(screen.getByText('Panto 2026')).toBeTruthy()
    expect(screen.queryByText(/cloud\.example/)).toBeNull()
    expect(screen.getAllByRole('button', { name: /reconnect/i })).toHaveLength(1)
    expect(screen.queryByText(/o\/old/)).toBeNull()
  })

  it('shows a synced cloud repo by name', () => {
    config = {
      ...UNSYNCED,
      synced: true,
      repoUrl: 'https://cloud.example/git/o/r1.git',
      repoName: 'Panto 2026',
      cloud: true,
      autoSyncEnabled: true,
    }
    cloudState = ADOPTED
    renderPanel()

    expect(screen.getByText('Panto 2026')).toBeTruthy()
    expect(screen.getByText(/synced to a lighting7 cloud repo/i)).toBeTruthy()
    expect(screen.queryByText(/cloud\.example/)).toBeNull()
    expect(screen.queryByText(/github/i)).toBeNull()
  })

  describe('a project still on GitHub', () => {
    const STRAGGLER: SyncConfig = { ...UNSYNCED, synced: true, repoUrl: 'https://github.com/o/panto.git', cloud: false, github: true }

    it('says so and moves it to the cloud', async () => {
      config = STRAGGLER
      cloudState = ADOPTED
      move.mockReturnValue({
        unwrap: () => Promise.resolve([{ projectId: 7, name: 'Panto', status: 'MOVED', repoName: 'Panto' }]),
      })
      renderPanel()

      expect(screen.getByText(/attached to github, which the desk does not sync with/i)).toBeTruthy()
      expect(screen.getByText('o/panto')).toBeTruthy()
      fireEvent.click(screen.getByRole('button', { name: /move to the cloud/i }))
      await waitFor(() => expect(move).toHaveBeenCalledWith([7]))
      await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Moved to Panto'))
    })

    it('says when it diverged on the cloud', async () => {
      config = STRAGGLER
      cloudState = ADOPTED
      move.mockReturnValue({
        unwrap: () => Promise.resolve([{ projectId: 7, name: 'Panto', status: 'MOVED_DIVERGED', repoName: 'Panto' }]),
      })
      renderPanel()

      fireEvent.click(screen.getByRole('button', { name: /move to the cloud/i }))
      await waitFor(() =>
        expect(toastSuccess).toHaveBeenCalledWith('Moved to Panto', {
          description: 'Another desk changed it there too: its next sync merges the two.',
        }),
      )
    })

    it('keeps a failure in place, with its message', async () => {
      config = STRAGGLER
      cloudState = ADOPTED
      move.mockReturnValue({
        unwrap: () => Promise.resolve([{ projectId: 7, name: 'Panto', status: 'FAILED', message: 'The org is out of storage' }]),
      })
      renderPanel()

      fireEvent.click(screen.getByRole('button', { name: /move to the cloud/i }))
      expect(await screen.findByText('Not moved: The org is out of storage')).toBeTruthy()
      expect(screen.getByRole('button', { name: /try it again/i })).toBeTruthy()
    })

    it('says so when the desk answers no row for the project', async () => {
      config = STRAGGLER
      cloudState = ADOPTED
      move.mockReturnValue({ unwrap: () => Promise.resolve([]) })
      renderPanel()

      fireEvent.click(screen.getByRole('button', { name: /move to the cloud/i }))
      expect(await screen.findByText(/did not say how the move went/i)).toBeTruthy()
    })

    it('sends an unadopted desk to the Cloud tab first', () => {
      config = STRAGGLER
      cloudState = { data: { configured: true, enabled: true, adopted: null } }
      renderPanel()

      expect(screen.getByText(/adopt this desk to move it to the cloud/i)).toBeTruthy()
      expect(screen.queryByRole('button', { name: /move to the cloud/i })).toBeNull()
    })

    it('moves nothing over remote access', () => {
      config = STRAGGLER
      cloudState = { data: { ...ADOPTED.data, remote: true } }
      renderPanel()

      expect(screen.getByText(/only be moved to the cloud from the desk itself/i)).toBeTruthy()
      expect(screen.queryByRole('button', { name: /move to the cloud/i })).toBeNull()
    })
  })

  it('tells a project on a repo neither GitHub nor this cloud only that, offering no move', () => {
    config = { ...UNSYNCED, synced: true, repoUrl: 'https://other-cloud.example/git/o/r.git', cloud: false, github: false }
    cloudState = ADOPTED
    renderPanel()

    expect(screen.getByText(/not one of this desk.s cloud.s, so the desk cannot sync with it/i)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /move to the cloud/i })).toBeNull()
    expect(screen.getByRole('button', { name: /disconnect repository/i })).toBeTruthy()
  })
})
