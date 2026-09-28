// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// Store-connected, so the store module is mocked — same shape as
// components/auth/DeviceLoginSection.test.tsx, which also keeps the import graph away from
// lightingApi's real WebSocket. The whole `@/store/oauthGithub` surface has to be stubbed,
// not just the identity query: IdentityRow renders DeviceFlowModal, which pulls in three
// more hooks from the same module.
//
// What these tests are really about: a *rejected* identity must not render as a healthy
// one. The desk spent 25 days showing "Connected as @cjcormack" with a "refreshing soon"
// badge while every sync failed, because an access token whose expiry is in the past reads
// as "about to be refreshed" — so the badge's absence is as load-bearing as the alert's
// presence.
let identityResult: { data?: unknown; isLoading?: boolean } = {}

vi.mock('@/store/oauthGithub', () => ({
  useOauthGithubIdentityQuery: () => identityResult,
  useDisconnectOAuthGithubMutation: () => [
    () => ({ unwrap: () => Promise.resolve() }),
    { isLoading: false },
  ],
  useStartGithubDeviceFlowMutation: () => [
    () => ({ unwrap: () => Promise.resolve({}) }),
    { isLoading: false },
  ],
  usePollGithubDeviceFlowMutation: () => [
    () => ({ unwrap: () => Promise.resolve({}) }),
    { isLoading: false },
  ],
}))

import { IdentityRow } from './IdentityRow'

const HEALTHY = {
  connected: true,
  oauthConfigured: true,
  login: 'octocat',
  // Comfortably in the future, so the "refreshing soon" badge is not merely absent by luck.
  accessExpiresAt: new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString(),
  connectedAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
}

const REJECTED = {
  ...HEALTHY,
  // Expired a month ago: this is the shape that used to read as "refreshing soon".
  accessExpiresAt: new Date(Date.now() - 25 * 24 * 60 * 60 * 1000).toISOString(),
  reauthRequired: true,
  reauthReason: 'GitHub rejected the refresh token (bad_refresh_token); user must re-connect.',
  reauthRequiredAt: new Date(Date.now() - 25 * 24 * 60 * 60 * 1000).toISOString(),
}

describe('IdentityRow', () => {
  afterEach(() => {
    cleanup()
    identityResult = {}
  })

  it('shows the connected user and no alert when the identity is healthy', () => {
    identityResult = { data: HEALTHY }
    render(<IdentityRow projectId={1} />)

    expect(screen.getByText('@octocat')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/refreshing soon/i)).toBeNull()
    expect(screen.queryByRole('button', { name: /reconnect github/i })).toBeNull()
  })

  it('leads with a reconnect alert when GitHub has rejected the identity', () => {
    identityResult = { data: REJECTED }
    render(<IdentityRow projectId={1} />)

    const alert = screen.getByRole('alert')
    expect(alert.textContent).toContain('octocat')
    // GitHub's own reason is shown — "it broke" without "why" sends people to the logs.
    expect(alert.textContent).toContain('bad_refresh_token')
    expect(screen.getByRole('button', { name: /reconnect github/i })).toBeTruthy()
    // The regression that made this whole change necessary.
    expect(screen.queryByText(/refreshing soon/i)).toBeNull()
  })

  it('keeps the device-code fallback reachable while rejected', () => {
    // Whoever is standing at a desk that can't open a browser popup is exactly the person
    // most likely to be locked out, so this must not be dropped from the broken state.
    identityResult = { data: REJECTED }
    render(<IdentityRow projectId={1} />)

    expect(screen.getByRole('button', { name: /use a device code/i })).toBeTruthy()
    expect(screen.getByRole('button', { name: /disconnect/i })).toBeTruthy()
  })

  it('offers Connect when no identity is stored', () => {
    identityResult = { data: { connected: false, oauthConfigured: true } }
    render(<IdentityRow projectId={null} />)

    expect(screen.getByText(/not connected to github/i)).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('explains the PAT-only path when OAuth is not configured on the install', () => {
    identityResult = { data: { connected: false, oauthConfigured: false } }
    render(<IdentityRow projectId={1} />)

    expect(screen.getByText(/OAuth is not configured/i)).toBeTruthy()
  })
})
