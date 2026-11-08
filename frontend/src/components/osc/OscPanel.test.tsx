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
  readAddresses: vi.fn((projectId: number) => ({ unwrap: () => Promise.resolve({ projectId }) })),
  openWindow: vi.fn(() => ({ close: vi.fn() }) as unknown as Window),
  printSheet: vi.fn((_win: unknown, _list: unknown, _name: string) => {}),
}))

vi.mock('@/store/osc', () => ({
  useOscSettingsQuery: (_: unknown, opts?: { skip?: boolean }) => {
    mocks.skip.push(opts?.skip ?? false)
    return { data: opts?.skip ? undefined : mocks.settings, error: undefined }
  },
  useSaveOscSettingsMutation: () => [mocks.save, { isLoading: false }],
  useOscState: () => mocks.state,
  useLazyOscAddressesQuery: () => [mocks.readAddresses, { isFetching: false }],
}))

vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 7, name: 'Tempest' } }),
}))

vi.mock('@/lib/oscCheatSheet', () => ({
  openCheatSheetWindow: () => mocks.openWindow(),
  printOscCheatSheet: (win: unknown, list: unknown, name: string) => mocks.printSheet(win, list, name),
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
        address: '/lighting7/stack/act-1/cue/5/go',
        args: [],
        outcome: 'DONE',
        detail: 'GO TO 5 Storm in Act 1',
      },
      {
        at: '2026-11-07 20:00:58.000Z',
        sender: 'QLab',
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

  it('prints the current show’s cheat sheet from the listing, for either role', async () => {
    mocks.role = 'OPERATOR'
    render(<OscPanel />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Print cheat sheet' }))
    })
    // The window opens in the click, before the listing's round trip, which a browser would block.
    expect(mocks.openWindow.mock.invocationCallOrder[0]).toBeLessThan(mocks.readAddresses.mock.invocationCallOrder[0])
    expect(mocks.readAddresses).toHaveBeenCalledWith(7)
    expect(mocks.printSheet).toHaveBeenCalledWith(expect.anything(), { projectId: 7 }, 'Tempest')
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
