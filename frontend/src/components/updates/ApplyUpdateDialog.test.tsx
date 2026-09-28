// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UpdateStatus } from '@/store/updates'

// Store-connected. Mocking the two store modules keeps this a component test and keeps the
// import graph away from lightingApi's real WebSocket, as UserDetailSheet's test does.
const mocks = vi.hoisted(() => ({
  apply: vi.fn((args: unknown) => ({ unwrap: () => Promise.resolve(args) })),
  install: { data: undefined } as { data: unknown },
}))

vi.mock('@/store/updates', () => ({
  useApplyUpdateMutation: () => [mocks.apply, { isLoading: false, error: undefined, reset: () => {} }],
}))

vi.mock('@/store/installs', () => ({
  useInstallQuery: () => mocks.install,
}))

const LOADED_INSTALL = { uuid: 'i-1', friendlyName: 'Front of House', createdAt: new Date(1).toISOString() }

import { ApplyUpdateDialog } from './ApplyUpdateDialog'

function status(live: Partial<UpdateStatus['live']> = {}): UpdateStatus {
  return {
    channel: 'PACKAGED_WINDOWS',
    currentVersion: '1.1.0',
    phase: 'READY_TO_APPLY',
    availability: 'UPDATE_AVAILABLE',
    stagedVersion: '1.2.0',
    latest: { tag: 'v1.2.0', version: '1.2.0', htmlUrl: 'https://example.invalid/r' },
    autoCheckEnabled: true,
    downloadedBytes: 100,
    totalBytes: 100,
    live: { showReady: true, activeEffectCount: 0, ...live },
    throttled: false,
  }
}

const confirmButton = () => screen.getByRole('button', { name: /install and restart/i })

beforeEach(() => {
  mocks.install = { data: LOADED_INSTALL }
})

afterEach(() => {
  cleanup()
  mocks.apply.mockClear()
})

describe('ApplyUpdateDialog', () => {
  it('says what stopping the desk costs', () => {
    render(<ApplyUpdateDialog open onOpenChange={() => {}} status={status()} />)

    expect(screen.getByText(/install lighting7 1\.2\.0 and restart\?/i)).toBeTruthy()
    expect(screen.getByText(/DMX output stops/i)).toBeTruthy()
    expect(screen.getByText(/administrator permission/i)).toBeTruthy()
  })

  /**
   * The deliberate asymmetry: an idle desk gets a plain click. Requiring the typing ritual for
   * every routine update is how you train people to type without reading.
   */
  it('needs only a click when nothing is running', () => {
    render(<ApplyUpdateDialog open onOpenChange={() => {}} status={status()} />)

    expect(screen.queryByLabelText(/to confirm/i)).toBeNull()
    expect(confirmButton().hasAttribute('disabled')).toBe(false)

    fireEvent.click(confirmButton())
    expect(mocks.apply).toHaveBeenCalledWith({ confirmVersion: '1.2.0' })
  })

  it('demands the desk name when effects are running', () => {
    render(
      <ApplyUpdateDialog open onOpenChange={() => {}} status={status({ activeEffectCount: 3 })} />,
    )

    expect(screen.getByText(/3 effects are running right now/i)).toBeTruthy()
    expect(confirmButton().hasAttribute('disabled')).toBe(true)

    fireEvent.change(screen.getByLabelText(/to confirm/i), { target: { value: 'Front of House' } })
    expect(confirmButton().hasAttribute('disabled')).toBe(false)

    fireEvent.click(confirmButton())
    expect(mocks.apply).toHaveBeenCalledWith({ confirmVersion: '1.2.0' })
  })

  it('demands the desk name when a cue stack is active even with no effects', () => {
    render(
      <ApplyUpdateDialog
        open
        onOpenChange={() => {}}
        status={status({ activeEffectCount: 0, activeStackName: 'Act One' })}
      />,
    )

    expect(screen.getByText(/Act One/)).toBeTruthy()
    expect(confirmButton().hasAttribute('disabled')).toBe(true)
  })

  it('keeps the button disabled while the typed name is wrong', () => {
    render(
      <ApplyUpdateDialog open onOpenChange={() => {}} status={status({ activeEffectCount: 1 })} />,
    )

    fireEvent.change(screen.getByLabelText(/to confirm/i), { target: { value: 'Front of Hous' } })
    expect(confirmButton().hasAttribute('disabled')).toBe(true)
    expect(mocks.apply).not.toHaveBeenCalled()
  })

  /**
   * `confirmVersion` is what stops a tab left open across a newer check from applying something
   * its owner never read the notes for — the backend 409s on a mismatch.
   */
  it('sends the staged version, not the latest advertised one', () => {
    const stale = {
      ...status(),
      stagedVersion: '1.2.0',
      latest: { tag: 'v1.3.0', version: '1.3.0', htmlUrl: 'https://example.invalid/r' },
    }
    render(<ApplyUpdateDialog open onOpenChange={() => {}} status={stale} />)

    fireEvent.click(confirmButton())
    expect(mocks.apply).toHaveBeenCalledWith({ confirmVersion: '1.2.0' })
  })

  /**
   * Regression: `deskName` and `typed` both start as '', so a bare `typed === deskName` check was
   * satisfied by typing nothing while the install query was in flight — or permanently, if it
   * errored. That turned the live-rig gate into a single click on a running desk.
   */
  it('does not let an unloaded desk name satisfy the gate', () => {
    mocks.install = { data: undefined }
    render(
      <ApplyUpdateDialog open onOpenChange={() => {}} status={status({ activeEffectCount: 2 })} />,
    )

    expect(confirmButton().hasAttribute('disabled')).toBe(true)
    // And it says why, rather than leaving a dead button.
    expect(screen.getByText(/needed to confirm an update/i)).toBeTruthy()

    fireEvent.click(confirmButton())
    expect(mocks.apply).not.toHaveBeenCalled()
  })

  it('still allows an idle desk through when the name has not loaded', () => {
    mocks.install = { data: undefined }
    render(<ApplyUpdateDialog open onOpenChange={() => {}} status={status()} />)

    // Nothing is running, so the name was never part of the gate.
    expect(confirmButton().hasAttribute('disabled')).toBe(false)
  })

  it('singularises the effect count', () => {
    render(
      <ApplyUpdateDialog open onOpenChange={() => {}} status={status({ activeEffectCount: 1 })} />,
    )
    expect(screen.getByText(/1 effect is running right now/i)).toBeTruthy()
  })
})
