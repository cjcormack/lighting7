import { describe, expect, it, vi } from 'vitest'
import type { CloudState, RemoteAccessSettings } from './cloud'

const stateSubscribers = vi.hoisted(() => [] as ((state: CloudState) => void)[])

vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const cloud = {
    subscribe: (fn: (state: CloudState) => void) => {
      stateSubscribers.push(fn)
      return { unsubscribe: () => {} }
    },
    subscribePending: () => ({ unsubscribe: () => {} }),
    pending: () => [],
  }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'cloud' ? cloud : target[prop]) }) }
})

import { store } from './index'
import { cloudApi } from './cloud'

const settings: RemoteAccessSettings = {
  remoteAccess: true,
  mcpAccess: false,
  approveSessions: true,
  allowScripts: true,
  allowEffects: false,
  allowCommands: false,
}

const adopted = (installId: string) => ({ installId, orgName: 'Org', adoptedAt: '2026-11-06T10:00:00.000Z', fingerprint: 'fp' })

/**
 * `cloud.state` carries the three cloud switches, so a change on another window, or an adoption
 * that starts them all off, reaches this window's Remote access section without a refetch.
 */
describe('the cloud.state bridge', () => {
  it('patches the cached switches from the frame, leaving the scripts, effects and commands three', async () => {
    await store.dispatch(cloudApi.util.upsertQueryData('remoteAccessSettings', undefined, settings))
    await store.dispatch(cloudApi.util.upsertQueryData('cloudState', undefined, { configured: true, enabled: true, adopted: adopted('inst-1') }))
    expect(stateSubscribers).toHaveLength(1)

    stateSubscribers[0]({
      configured: true,
      enabled: true,
      adopted: adopted('inst-1'),
      remoteSwitches: { remoteAccess: false, mcpAccess: true, approveSessions: false },
    })

    expect(cloudApi.endpoints.remoteAccessSettings.select()(store.getState()).data).toEqual({
      ...settings,
      remoteAccess: false,
      mcpAccess: true,
      approveSessions: false,
    })
  })

  it('a frame from a desk that does not carry them leaves the cache as it is', async () => {
    await store.dispatch(cloudApi.util.upsertQueryData('remoteAccessSettings', undefined, settings))
    stateSubscribers[0]({ configured: true, enabled: true, adopted: adopted('inst-1') })
    expect(cloudApi.endpoints.remoteAccessSettings.select()(store.getState()).data).toEqual(settings)
  })
})
