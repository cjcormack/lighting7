// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  data: undefined as unknown,
  calls: 0,
}))

vi.mock('@/store/cloud', () => ({
  useCloudHeartbeatQuery: () => {
    mocks.calls++
    return { data: mocks.data, isLoading: false, error: undefined }
  },
}))

import { CloudHeartbeat } from './CloudHeartbeat'

function open() {
  const details = screen.getByTestId('cloud-heartbeat') as HTMLDetailsElement
  details.open = true
  fireEvent(details, new Event('toggle'))
}

describe('CloudHeartbeat', () => {
  afterEach(() => {
    cleanup()
    mocks.data = undefined
    mocks.calls = 0
  })

  it('reads nothing until opened', () => {
    render(<CloudHeartbeat />)
    expect(mocks.calls).toBe(0)
  })

  it('shows the last heartbeat as JSON once opened', () => {
    mocks.data = {
      sentAt: '2026-11-16T10:00:00.000Z',
      health: { uptimeS: 42, deskVersion: '1.2.3', project: { uuid: 'u-1', name: 'Hamlet' } },
    }
    render(<CloudHeartbeat />)
    open()
    const json = screen.getByTestId('cloud-heartbeat-json').textContent ?? ''
    expect(JSON.parse(json)).toEqual({ uptimeS: 42, deskVersion: '1.2.3', project: { uuid: 'u-1', name: 'Hamlet' } })
  })

  it('says when none has gone yet', () => {
    mocks.data = {}
    render(<CloudHeartbeat />)
    open()
    expect(screen.getByText(/No heartbeat sent yet/)).toBeTruthy()
  })
})
