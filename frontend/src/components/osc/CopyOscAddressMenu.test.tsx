// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { OscAddressList } from '@/lib/oscAddresses'

const mocks = vi.hoisted(() => ({
  list: undefined as OscAddressList | undefined,
  refetch: [] as unknown[],
  success: vi.fn(),
  error: vi.fn(),
}))

vi.mock('@/store/osc', () => ({
  useOscAddressesQuery: (projectId: number, opts?: unknown) => {
    mocks.refetch.push({ projectId, opts })
    return { data: mocks.list, isLoading: false, isError: false }
  },
}))
vi.mock('sonner', () => ({ toast: { success: mocks.success, error: mocks.error } }))

const { CopyOscAddressButton } = await import('./CopyOscAddressMenu')
const { setRemoteLinkForTests } = await import('@/lib/remoteMode')

const list: OscAddressList = {
  prefix: '/lighting7',
  patterns: [],
  addresses: [
    { address: '/lighting7/look/haze-fill/on', pattern: '/lighting7/look/{look}/on', args: '–', does: 'The Look on whole', reaches: 'Look Haze fill on', target: { type: 'lookSet', lookUuid: 'l', on: true }, record: { kind: 'LOOK', id: 3, uuid: 'l' } },
    { address: '/lighting7/look/haze-fill/off', pattern: '/lighting7/look/{look}/off', args: '–', does: '', reaches: 'Look Haze fill off', target: { type: 'lookSet', lookUuid: 'l', on: false }, record: { kind: 'LOOK', id: 3, uuid: 'l' } },
  ],
  ambiguous: [],
  unaddressed: [{ kind: 'LOOK', id: 4, uuid: 'x', name: '★', reason: 'Its name has no letter or digit, so it has no address' }],
}

/** Radix's trigger opens on `pointerdown`, not `click`. */
function openMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: /OSC address/ }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.refetch = []
})

describe('Copy OSC address', () => {
  it('lists the record’s addresses, read afresh as it opens, and copies the one chosen', async () => {
    mocks.list = list
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<CopyOscAddressButton projectId={7} records={[{ kind: 'LOOK', id: 3 }]} />)
    expect(mocks.refetch).toEqual([])
    openMenu()

    const items = screen.getAllByRole('menuitem')
    expect(items.map((i) => i.textContent)).toEqual(['on/lighting7/look/haze-fill/on', 'off/lighting7/look/haze-fill/off'])
    expect(mocks.refetch[0]).toEqual({ projectId: 7, opts: { refetchOnMountOrArgChange: true } })
    fireEvent.click(items[1])
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('/lighting7/look/haze-fill/off'))
    await waitFor(() => expect(mocks.success).toHaveBeenCalledWith('Copied /lighting7/look/haze-fill/off'))
  })

  it('says why a record has no address, in the desk’s words', () => {
    mocks.list = list
    render(<CopyOscAddressButton projectId={7} records={[{ kind: 'LOOK', id: 4 }]} />)
    openMenu()
    const item = screen.getByRole('menuitem')
    expect(item.textContent).toBe('Its name has no letter or digit, so it has no address')
    expect(item.getAttribute('aria-disabled')).toBe('true')
  })

  it('is absent in remote mode, where the desk refuses the listing', () => {
    mocks.list = list
    setRemoteLinkForTests({} as Parameters<typeof setRemoteLinkForTests>[0])
    try {
      const { container } = render(<CopyOscAddressButton projectId={7} records={[{ kind: 'LOOK', id: 3 }]} />)
      expect(container).toBeEmptyDOMElement()
    } finally {
      setRemoteLinkForTests(null)
    }
  })
})
