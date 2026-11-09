// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { OscAddress, OscAddressList as AddressList } from '@/lib/oscAddresses'
import type { OscState } from '@/api/oscWsApi'

const mocks = vi.hoisted(() => ({
  list: undefined as AddressList | undefined,
  readError: undefined as unknown,
  calls: [] as unknown[],
  refetch: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  message: vi.fn(),
}))

vi.mock('@/store/osc', () => ({
  useOscAddressesQuery: (projectId: number, opts?: unknown) => {
    mocks.calls.push({ projectId, opts })
    return { data: mocks.list, isFetching: false, isError: mocks.readError != null, error: mocks.readError, refetch: mocks.refetch }
  },
}))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 7, name: 'Tempest' } }),
}))
vi.mock('sonner', () => ({ toast: { success: mocks.success, error: mocks.error, message: mocks.message } }))

const { OscAddressList } = await import('./OscAddressList')

const address = (address: string, pattern: string, reaches: string, does = ''): OscAddress => ({
  address,
  pattern,
  args: '–',
  does,
  reaches,
  binding: { does: { type: 'blackout' } },
})

const list: AddressList = {
  prefix: '/lighting7',
  patterns: [],
  addresses: [
    { ...address('/lighting7/tempo/1', '/lighting7/tempo/{master}', 'Tempo of M1 Main'), args: 'f BPM' },
    address('/lighting7/stack/act-1/go', '/lighting7/stack/{stack}/go', 'GO Act 1', 'The next cue in the stack'),
    address('/lighting7/look/haze-fill/on', '/lighting7/look/{look}/on', 'Look Haze fill on'),
    address('/lighting7/busk/storm/1/2/press', '/lighting7/busk/{page}/{bank}/{pad}/press', 'Press Rain'),
  ],
  ambiguous: [{ message: '`storm` names 2 cues: Storm!, storm', records: [], addresses: ['/lighting7/cue/storm/on'] }],
  unaddressed: [{ kind: 'STACK', id: 1, uuid: 'u', name: '???', reason: 'Its name has no letter or digit, so it has no address' }],
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.calls = []
  mocks.readError = undefined
})

const osc = (over: Partial<OscState> = {}): OscState => ({
  enabled: true,
  listening: true,
  tcpListening: true,
  port: 8415,
  received: 0,
  dropped: 0,
  refused: 0,
  refusedSenders: [],
  recent: [],
  ...over,
})

const sectionTitles = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)

describe('OscAddressList', () => {
  it('lists the current show’s addresses by kind, read afresh on mount, with the ambiguous and the unaddressed after', () => {
    mocks.list = list
    render(<OscAddressList state={osc()} />)
    expect(screen.getByText('Tempest')).toBeTruthy()
    expect(screen.getByText('port 8415 · UDP and TCP')).toBeTruthy()
    expect(mocks.calls[0]).toEqual({ projectId: 7, opts: { refetchOnMountOrArgChange: true } })
    expect(sectionTitles()).toEqual(['Cue stacks', 'Looks', 'Busk pads and pages', 'Masters and tempo', 'Ambiguous', 'No address'])

    const tempo = within(screen.getByRole('region', { name: 'Masters and tempo' }))
    expect(tempo.getByText('/lighting7/tempo/1')).toBeTruthy()
    expect(tempo.getByText('f BPM')).toBeTruthy()
    expect(tempo.getByText('Tempo of M1 Main')).toBeTruthy()
    expect(screen.getByText(/`storm` names 2 cues/)).toBeTruthy()
    expect(screen.getByText('???: Its name has no letter or digit, so it has no address')).toBeTruthy()
  })

  it('filters by address, what it reaches or what it does, and keeps only the sections with a match', () => {
    mocks.list = list
    render(<OscAddressList state={osc()} />)
    const filter = screen.getByLabelText('Filter addresses')
    fireEvent.change(filter, { target: { value: 'HAZE' } })
    expect(sectionTitles()).toEqual(['Looks'])
    fireEvent.change(filter, { target: { value: 'next cue' } })
    expect(sectionTitles()).toEqual(['Cue stacks'])
    fireEvent.change(filter, { target: { value: 'storm' } })
    expect(sectionTitles()).toEqual(['Busk pads and pages', 'Ambiguous'])
    fireEvent.change(filter, { target: { value: 'names 2' } })
    expect(sectionTitles()).toEqual(['Ambiguous'])
    fireEvent.change(filter, { target: { value: 'nothing at all' } })
    expect(screen.getByText('No address matches “nothing at all”.')).toBeTruthy()
  })

  it('reads the list again on Refresh', () => {
    mocks.list = list
    render(<OscAddressList state={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(mocks.refetch).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/UDP and TCP/)).toBeNull()
  })

  it('copies an address from its row', async () => {
    mocks.list = list
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<OscAddressList state={osc()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Copy /lighting7/look/haze-fill/on' }))
    await waitFor(() => expect(mocks.success).toHaveBeenCalledWith('Copied /lighting7/look/haze-fill/on'))
    expect(writeText).toHaveBeenCalledWith('/lighting7/look/haze-fill/on')
  })

  it('selects the address in its cell for ⌘C where no copy works', async () => {
    mocks.list = list
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    render(<OscAddressList state={osc()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Copy /lighting7/tempo/1' }))
    await waitFor(() => expect(mocks.message).toHaveBeenCalledWith('Press ⌘C to copy', { description: '/lighting7/tempo/1' }))
    expect(window.getSelection()?.toString()).toBe('/lighting7/tempo/1')
  })

  it('says in the header what the desk listens on', () => {
    const header = (state: OscState) => {
      render(<OscAddressList state={state} />)
      const text = screen.queryByText(/port 8415|OSC is off/)?.textContent ?? null
      cleanup()
      return text
    }
    mocks.list = list
    expect(header(osc({ tcpListening: false }))).toBe('port 8415 · UDP')
    expect(header(osc({ listening: false }))).toBe('port 8415 · TCP')
    expect(header(osc({ listening: false, tcpListening: false }))).toBeNull()
    expect(header(osc({ enabled: false, listening: false, tcpListening: false }))).toBe('OSC is off')
  })

  it('says a failed Refresh failed, and that the list still drawn may be out of date', () => {
    mocks.list = list
    mocks.readError = { status: 'FETCH_ERROR', error: 'down' }
    render(<OscAddressList state={osc()} />)
    expect(screen.getByText(/Couldn’t read the addresses: Could not reach the server — the list below may be out of date\./)).toBeTruthy()
    expect(screen.getByText('/lighting7/tempo/1')).toBeTruthy()
  })

  it('says why when the first read fails', () => {
    mocks.list = undefined
    mocks.readError = { status: 'FETCH_ERROR', error: 'down' }
    render(<OscAddressList state={osc()} />)
    expect(screen.getByText('Couldn’t read the addresses: Could not reach the server')).toBeTruthy()
    expect(screen.queryByText('Reading the addresses…')).toBeNull()
  })

  it('says so when the show answers to no address', () => {
    mocks.list = { ...list, addresses: [], ambiguous: [], unaddressed: [] }
    render(<OscAddressList state={osc()} />)
    expect(screen.getByText('This show answers to no addresses yet.')).toBeTruthy()
  })
})
