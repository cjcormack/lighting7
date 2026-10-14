// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const sdk = vi.hoisted(() => ({
  enabled: false,
  count: vi.fn(),
  distribution: vi.fn(),
}))
vi.mock('@sentry/react', () => ({
  isEnabled: () => sdk.enabled,
  metrics: { count: sdk.count, distribution: sdk.distribution },
}))

import {
  STAGE_FPS_INTERVAL_MS,
  countWsReconnect,
  markBootReady,
  markSignInShown,
  resetSentryMetrics,
  sampleStageFps,
  sendBootReady,
  settleBootReady,
} from './sentryMetrics'

describe('sentryMetrics', () => {
  beforeEach(() => {
    sdk.enabled = false
    sdk.count.mockClear()
    sdk.distribution.mockClear()
    resetSentryMetrics()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('holds boot.ready until Sentry is running, then sends it once', () => {
    vi.spyOn(performance, 'now').mockReturnValue(1234.4)
    markBootReady()
    expect(sdk.distribution).not.toHaveBeenCalled()

    sdk.enabled = true
    sendBootReady()
    sendBootReady()
    markBootReady()
    expect(sdk.distribution).toHaveBeenCalledTimes(1)
    expect(sdk.distribution).toHaveBeenCalledWith('boot.ready', 1234, { unit: 'millisecond' })
  })

  it('drops a boot that finished with Sentry off, rather than sending it on a later switch-on', () => {
    settleBootReady()
    markBootReady()
    sdk.enabled = true
    sendBootReady()
    expect(sdk.distribution).not.toHaveBeenCalled()
  })

  it('sends a boot that finished before the telemetry fetch settled, once Sentry starts', () => {
    markBootReady()
    sdk.enabled = true
    sendBootReady()
    settleBootReady()
    expect(sdk.distribution).toHaveBeenCalledTimes(1)
  })

  it('sends no boot.ready for a page load that showed the sign-in screen', () => {
    sdk.enabled = true
    markSignInShown()
    markBootReady()
    sendBootReady()
    expect(sdk.distribution).not.toHaveBeenCalled()
  })

  it('counts ws.reconnect with its cause', () => {
    countWsReconnect('forced')
    expect(sdk.count).toHaveBeenCalledWith('ws.reconnect', 1, { attributes: { cause: 'forced' } })
  })

  it('samples stage.fps every 10 s while the page is visible and the canvas is drawing', () => {
    vi.useFakeTimers()
    let visibility: DocumentVisibilityState = 'visible'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    let fps: number | null = 58
    const stop = sampleStageFps(() => fps)

    vi.advanceTimersByTime(STAGE_FPS_INTERVAL_MS - 1)
    expect(sdk.distribution).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(sdk.distribution).toHaveBeenLastCalledWith('stage.fps', 58)

    fps = null
    vi.advanceTimersByTime(STAGE_FPS_INTERVAL_MS)
    fps = 30
    visibility = 'hidden'
    vi.advanceTimersByTime(STAGE_FPS_INTERVAL_MS)
    expect(sdk.distribution).toHaveBeenCalledTimes(1)

    visibility = 'visible'
    stop()
    vi.advanceTimersByTime(STAGE_FPS_INTERVAL_MS)
    expect(sdk.distribution).toHaveBeenCalledTimes(1)
  })
})
