// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SyncConfig } from '@/store/cloudSync'

let config: SyncConfig
let cloudState: { data?: unknown; isError?: boolean } = {}
const syncToCloud = vi.fn()
const reconnect = vi.fn()

vi.mock('@/store/cloudSync', () => ({
  useCloudSyncConfigQuery: () => ({ data: config, isLoading: false }),
  useCloudSyncToCloudMutation: () => [syncToCloud, { isLoading: false }],
  useCloudSyncReconnectMutation: () => [reconnect, { isLoading: false }],
  useCloudSyncRunMutation: () => [() => ({ unwrap: () => Promise.resolve({}) }), { isLoading: false }],
  useUpdateCloudSyncConfigMutation: () => [vi.fn(), { isLoading: false }],
  useCloudReposQuery: () => ({
    data: [{ repoId: 'r1', url: 'https://cloud.example/git/o/r1.git', name: 'Panto 2026', kind: 'SHOW', projectUuid: 'p', sizeBytes: 0, syncedHere: false }],
  }),
}))
vi.mock('@/store/cloud', () => ({ useCloudStateQuery: () => cloudState }))
vi.mock('@/store/oauthGithub', () => ({ useOauthGithubIdentityQuery: () => ({ data: undefined }) }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/components/cloudSync/IdentityRow', () => ({ IdentityRow: () => <div>github identity</div> }))
vi.mock('@/components/cloudSync/CreateRepoDialog', () => ({ CreateRepoDialog: () => null }))
vi.mock('@/components/cloudSync/AutoSyncForm', () => ({ AutoSyncForm: () => null }))
vi.mock('@/components/cloudSync/PatPanel', () => ({ PatPanel: () => <div>pat panel</div> }))
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
  tokenPresent: false,
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
  })

  it('offers one button, Sync to the cloud, on an adopted desk', async () => {
    config = UNSYNCED
    cloudState = ADOPTED
    syncToCloud.mockReturnValue({ unwrap: () => Promise.resolve({ config: { ...UNSYNCED, repoName: 'Panto' }, existed: false }) })
    renderPanel()

    fireEvent.click(screen.getByRole('button', { name: /sync to the cloud/i }))
    await waitFor(() => expect(syncToCloud).toHaveBeenCalledWith(7))
    expect(screen.queryByText(/adopt this desk/i)).toBeNull()
    expect(screen.queryByText('github identity')).toBeNull()
  })

  it('sends an unadopted desk to the Cloud tab, and nothing else of the cloud', () => {
    config = UNSYNCED
    cloudState = { data: { configured: true, enabled: true, adopted: null } }
    renderPanel()

    expect(screen.getByText(/adopt this desk to sync/i)).toBeTruthy()
    expect(screen.getByRole('link', { name: /open the cloud tab/i }).getAttribute('href')).toBe('/install/cloud')
    expect(screen.queryByRole('button', { name: /sync to the cloud/i })).toBeNull()
  })

  it('keeps GitHub behind GitHub (legacy), closed', () => {
    config = UNSYNCED
    cloudState = ADOPTED
    renderPanel()

    expect(screen.queryByText('github identity')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /github \(legacy\)/i }))
    expect(screen.getByText('github identity')).toBeTruthy()
  })

  it('names a remembered cloud repo by its cloud name, never its URL', () => {
    config = { ...UNSYNCED, linkedRepos: [{ repoUrl: 'https://cloud.example/git/o/r1.git', lastLinkedAt: '', cloud: true }] }
    cloudState = ADOPTED
    renderPanel()

    expect(screen.getByText('Panto 2026')).toBeTruthy()
    expect(screen.queryByText(/cloud\.example/)).toBeNull()
  })

  it('shows a synced cloud repo by name, without GitHub identity or token', () => {
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
    expect(screen.queryByText('github identity')).toBeNull()
  })
})
