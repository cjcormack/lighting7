// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OscSettings } from '@/store/osc'
import type { OscState } from '@/api/oscWsApi'

// Store-connected: mocking the store keeps this a component test and the import graph away from
// lightingApi's real WebSocket.
const mocks = vi.hoisted(() => ({
  save: vi.fn((args: unknown) => ({ unwrap: () => Promise.resolve(args) })),
  settings: undefined as unknown,
  state: null as unknown,
  role: 'ADMIN' as string,
  skip: [] as boolean[],
}))

vi.mock('@/store/osc', () => ({
  useOscSettingsQuery: (_: unknown, opts?: { skip?: boolean }) => {
    mocks.skip.push(opts?.skip ?? false)
    return { data: opts?.skip ? undefined : mocks.settings, error: undefined }
  },
  useSaveOscSettingsMutation: () => [mocks.save, { isLoading: false }],
  useOscState: () => mocks.state,
}))

vi.mock('./OscAddressList', () => ({
  OscAddressList: ({ state }: { state: OscState | null }) => <div data-testid="address-list">port {state?.port}</div>,
}))

vi.mock('@/store/auth', () => ({
  useAuthStatusQuery: () => ({ data: { user: { role: mocks.role } } }),
}))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { OscPanel } from './OscPanel'

function state(over: Partial<OscState> = {}): OscState {
  return {
    enabled: true,
    listening: true,
    port: 8415,
    error: null,
    received: 3,
    dropped: 0,
    refused: 2,
    refusedSenders: [{ address: '192.168.1.40', count: 2, lastAt: '2026-11-07 20:01:02.000Z' }],
    recent: [
      {
        at: '2026-11-07 20:01:00.000Z',
        sender: 'QLab',
        source: '192.168.1.20',
        address: '/lighting7/stack/act-1/cue/5/go',
        args: [],
        outcome: 'DONE',
        detail: 'GO TO 5 Storm in Act 1',
      },
      {
        at: '2026-11-07 20:00:58.000Z',
        sender: 'QLab',
        source: '192.168.1.20',
        address: '/lighting7/stack/act-3/go',
        args: [],
        outcome: 'NOT_FOUND',
        detail: 'No stack `act-3` in this show',
      },
    ],
    ...over,
  }
}

const settings = (over: Partial<OscSettings> = {}): OscSettings => ({
  enabled: true,
  bindAddress: null,
  port: 8415,
  senders: [{ id: 1, name: 'QLab', address: '192.168.1.20' }],
  ...over,
})

beforeEach(() => {
  mocks.settings = settings()
  mocks.state = state()
  mocks.role = 'ADMIN'
  mocks.skip = []
})

afterEach(() => {
  cleanup()
  mocks.save.mockClear()
})

describe('OscPanel', () => {
  it('shows an operator the status and the log, and no settings or allow button', () => {
    mocks.role = 'OPERATOR'
    render(<OscPanel />)
    expect(screen.getByTestId('osc-status').textContent).toBe('Listening on UDP 8415')
    expect(screen.getByText('GO TO 5 Storm in Act 1')).toBeTruthy()
    expect(screen.getByText('Not found')).toBeTruthy()
    expect(screen.getByText('192.168.1.40')).toBeTruthy()
    expect(screen.queryByText('Senders')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Allow this sender' })).toBeNull()
    expect(mocks.skip.every(Boolean)).toBe(true)
  })

  it('says why it is not listening', () => {
    mocks.role = 'OPERATOR'
    mocks.state = state({ listening: false, error: "Couldn't listen on UDP 8415: Address already in use" })
    render(<OscPanel />)
    expect(screen.getByTestId('osc-status').textContent).toBe('Not listening')
    expect(screen.getByText(/Address already in use/)).toBeTruthy()
  })

  it('lets an admin turn it off, sending the whole document', async () => {
    render(<OscPanel />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Disable' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({
      enabled: false,
      port: 8415,
      bindAddress: null,
      senders: [{ id: 1, name: 'QLab', address: '192.168.1.20' }],
    })
  })

  it('sends the stored document on Enable, never an unsaved draft, and keeps reading what the desk holds', async () => {
    mocks.settings = settings({ enabled: false })
    mocks.save.mockImplementationOnce(() => ({ unwrap: () => Promise.reject({ status: 400, data: { error: 'no' } }) }))
    render(<OscPanel />)
    fireEvent.change(screen.getByLabelText('Port, UDP and TCP'), { target: { value: '9999' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Enable' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({
      enabled: true,
      port: 8415,
      bindAddress: null,
      senders: [{ id: 1, name: 'QLab', address: '192.168.1.20' }],
    })
    expect(screen.getByRole('button', { name: 'Enable' })).toBeTruthy()
  })

  it('keeps a sender typed but not saved through Enable, so Save still sends it', async () => {
    mocks.settings = settings({ enabled: false, senders: [] })
    const view = render(<OscPanel />)
    fireEvent.click(screen.getByRole('button', { name: /Add sender/ }))
    fireEvent.change(screen.getByLabelText('Sender name'), { target: { value: 'Sound Mac' } })
    fireEvent.change(screen.getByLabelText('Sender address'), { target: { value: '192.168.1.40' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Enable' }))
    })
    expect(mocks.save).toHaveBeenLastCalledWith({ enabled: true, port: 8415, bindAddress: null, senders: [] })

    // The desk answers with what it stores, and the cache moves under the draft.
    mocks.settings = settings({ enabled: true, senders: [] })
    view.rerender(<OscPanel />)
    expect((screen.getByLabelText('Sender name') as HTMLInputElement).value).toBe('Sound Mac')
    expect((screen.getByLabelText('Sender address') as HTMLInputElement).value).toBe('192.168.1.40')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    })
    expect(mocks.save).toHaveBeenLastCalledWith({
      enabled: true,
      port: 8415,
      bindAddress: null,
      senders: [{ id: undefined, name: 'Sound Mac', address: '192.168.1.40', replies: false }],
    })
  })

  it('follows the stored document where nothing was edited, and takes what a Save stored', async () => {
    const view = render(<OscPanel />)
    mocks.settings = settings({ port: 9000 })
    view.rerender(<OscPanel />)
    expect((screen.getByLabelText('Port, UDP and TCP') as HTMLInputElement).value).toBe('9000')

    fireEvent.change(screen.getAllByLabelText('Sender name')[0], { target: { value: 'QLab 2' } })
    mocks.save.mockImplementationOnce(() => ({
      unwrap: () => Promise.resolve(settings({ port: 9000, senders: [{ id: 1, name: 'QLab 2', address: '192.168.1.20' }] })),
    }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    })
    mocks.settings = settings({ port: 9000, senders: [{ id: 1, name: 'QLab 2', address: '192.168.1.20' }] })
    view.rerender(<OscPanel />)
    expect(screen.getByRole('button', { name: 'Save' })).toHaveProperty('disabled', true)
  })

  it('allows a refused sender with its address filled in, asking for a name', async () => {
    render(<OscPanel />)
    fireEvent.click(screen.getByRole('button', { name: 'Allow this sender' }))
    const addresses = screen.getAllByLabelText('Sender address') as HTMLInputElement[]
    expect(addresses.map((a) => a.value)).toEqual(['192.168.1.20', '192.168.1.40'])
    const names = screen.getAllByLabelText('Sender name')
    fireEvent.change(names[1], { target: { value: 'Sound Mac' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({
      enabled: true,
      port: 8415,
      bindAddress: null,
      senders: [
        { id: 1, name: 'QLab', address: '192.168.1.20', replies: false },
        { id: undefined, name: 'Sound Mac', address: '192.168.1.40', replies: false },
      ],
    })
  })

  it('says it listens on TCP too, and on TCP alone when UDP could not bind', () => {
    mocks.state = state({ tcpListening: true })
    render(<OscPanel />)
    expect(screen.getByTestId('osc-status').textContent).toBe('Listening on UDP and TCP 8415')
    cleanup()
    mocks.state = state({ listening: false, tcpListening: true, error: "Couldn't listen on UDP 8415: Address already in use" })
    render(<OscPanel />)
    expect(screen.getByTestId('osc-status').textContent).toBe('Listening on TCP 8415')
  })

  it('turns a sender’s replies on, and reads the stored switch back', async () => {
    mocks.settings = settings({ senders: [{ id: 1, name: 'QLab', address: '192.168.1.20', replies: false }] })
    render(<OscPanel />)
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement
    expect(save.disabled).toBe(true)
    const replies = screen.getByLabelText('Replies to QLab') as HTMLInputElement
    expect(replies.checked).toBe(false)
    fireEvent.click(replies)
    expect(save.disabled).toBe(false)
    await act(async () => {
      fireEvent.click(save)
    })
    expect(mocks.save).toHaveBeenCalledWith({
      enabled: true,
      port: 8415,
      bindAddress: null,
      senders: [{ id: 1, name: 'QLab', address: '192.168.1.20', replies: true }],
    })
  })

  it('draws a stored sender with replies on as ticked', () => {
    mocks.settings = settings({ senders: [{ id: 1, name: 'QLab', address: '192.168.1.20', replies: true }] })
    render(<OscPanel />)
    expect((screen.getByLabelText('Replies to QLab') as HTMLInputElement).checked).toBe(true)
  })

  it('shows the address list under the settings, for either role, on the listener’s port', () => {
    mocks.role = 'OPERATOR'
    render(<OscPanel />)
    expect(screen.getByTestId('address-list').textContent).toBe('port 8415')
    expect(screen.queryByRole('button', { name: 'Print cheat sheet' })).toBeNull()
  })

  it('marks a range sender with how many addresses it admits, and a single address with nothing', () => {
    mocks.settings = settings({
      senders: [
        { id: 1, name: 'QLab', address: '192.168.1.20' },
        { id: 2, name: 'Booth', address: '192.168.1.0/24' },
      ],
    })
    render(<OscPanel />)
    expect(screen.getAllByText(/^range · /).map((el) => el.textContent)).toEqual(['range · 256 addresses'])
    expect((screen.getAllByLabelText('Sender address')[0] as HTMLInputElement).placeholder).toBe('192.168.1.20 or 192.168.1.0/24')
  })

  it('marks a range as it is typed', () => {
    render(<OscPanel />)
    fireEvent.change(screen.getByLabelText('Sender address'), { target: { value: '10.0.0.0/16' } })
    expect(screen.getByText('range · 65,536 addresses')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Sender address'), { target: { value: '10.0.0.1/32' } })
    expect(screen.queryByText(/^range · /)).toBeNull()
  })

  it('says which machine sent a logged message', () => {
    mocks.role = 'OPERATOR'
    mocks.state = state({
      recent: [
        {
          at: '2026-11-07 20:01:00.000Z',
          sender: 'Booth',
          source: '192.168.1.44',
          address: '/lighting7/stack/act-1/go',
          args: [],
          outcome: 'DONE',
          detail: 'GO Act 1',
        },
      ],
    })
    render(<OscPanel />)
    expect(screen.getByText('· 192.168.1.44')).toBeTruthy()
  })

  it('lists the addresses a change moved, newest first, in words', () => {
    mocks.role = 'OPERATOR'
    mocks.state = state({
      warnings: [
        {
          id: 2,
          at: '2026-11-07 20:05:00.000Z',
          changes: [{ address: '/lighting7/busk/storm/1/1/press', was: 'Press Haze', now: 'Press Rain' }],
        },
        {
          id: 1,
          at: '2026-11-07 20:04:00.000Z',
          changes: [{ address: '/lighting7/stack/act-1/go', was: 'GO Act 1', now: null, reason: 'No stack `act-1` in this show' }],
        },
      ],
    })
    render(<OscPanel />)
    expect(screen.getByText('Addresses that moved')).toBeTruthy()
    const lines = screen.getAllByText(/^\/lighting7\//).map((el) => el.textContent)
    expect(lines).toContain('/lighting7/busk/storm/1/1/press now reaches Press Rain (was Press Haze)')
    expect(lines).toContain('/lighting7/stack/act-1/go no longer works (was GO Act 1) — No stack `act-1` in this show')
  })

  it('shows a refusal from the desk under the form', async () => {
    mocks.save.mockImplementationOnce(() => ({
      unwrap: () => Promise.reject({ status: 400, data: { error: 'The port must be from 1024 to 65535' } }),
    }))
    render(<OscPanel />)
    fireEvent.change(screen.getByLabelText('Port, UDP and TCP'), { target: { value: '80' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    })
    expect(screen.getByText('The port must be from 1024 to 65535')).toBeTruthy()
  })
})
