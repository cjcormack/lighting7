// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { BuskBank, BuskPage } from '@/api/buskApi'
import { lastBuskAddTargetStore } from '@/lib/buskAdd'

/**
 * The one route onto a busk page that does not go through the busk view's edit mode.
 *
 * What is worth pinning is the menu's *shape*: one page flattens because a submenu for a single
 * choice is friction, several nest, and every dead end is a disabled item saying why rather than an
 * empty menu or an inert button.
 */

const pages: BuskPage[] = []
const addPad = vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))

vi.mock('@/store/busk', () => ({
  useBuskPagesQuery: () => ({ data: pages, isLoading: false }),
  useAddBuskPadMutation: () => [addPad],
}))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

const { AddToBuskPageMenu } = await import('./AddToBuskPageMenu')

function bank(id: number, name: string): BuskBank {
  return { id, name, solo: false, flow: 'WRAP', pads: [] }
}

function page(id: number, name: string, banks: BuskBank[]): BuskPage {
  return { id, uuid: `u${id}`, name, sortOrder: id, rows: banks.length === 0 ? [] : [{ columns: [{ width: 12, banks }] }] }
}

function setPages(next: BuskPage[]) {
  pages.length = 0
  pages.push(...next)
}

/** Radix's trigger opens on `pointerdown`, not `click`. */
function openMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: /Add to busk page/ }), {
    button: 0,
    ctrlKey: false,
    pointerType: 'mouse',
  })
}

const record = { kind: 'CUE' as const, id: 55, name: 'Blackout' }

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.localStorage.clear()
  lastBuskAddTargetStore.reset()
})

it('renders nothing at all for a record that does not exist yet', () => {
  setPages([page(1, 'Act 1', [bank(20, 'Cues')])])
  const { container } = render(<AddToBuskPageMenu projectId={1} record={null} />)
  expect(container).toBeEmptyDOMElement()
})

describe('the shape of the menu', () => {
  it('flattens a single page to its banks', () => {
    setPages([page(1, 'Act 1', [bank(20, 'Cues'), bank(21, 'Keys')])])
    render(<AddToBuskPageMenu projectId={1} record={record} />)
    openMenu()

    expect(screen.getByRole('menuitem', { name: /Cues/ })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Keys/ })).toBeInTheDocument()
    // The page is a heading, not a step: with one page there is nothing to choose between.
    expect(screen.queryByRole('menuitem', { name: 'Act 1' })).not.toBeInTheDocument()
  })

  it('nests several pages, one submenu each', () => {
    setPages([page(1, 'Act 1', [bank(20, 'Cues')]), page(2, 'Act 2', [bank(30, 'Wash')])])
    render(<AddToBuskPageMenu projectId={1} record={record} />)
    openMenu()

    expect(screen.getByText('Act 1')).toBeInTheDocument()
    expect(screen.getByText('Act 2')).toBeInTheDocument()
    // A submenu's banks are behind its trigger, so neither page's banks are on screen yet.
    expect(screen.queryByText('Cues')).not.toBeInTheDocument()
  })

  it('says there are no pages rather than opening empty', () => {
    setPages([])
    render(<AddToBuskPageMenu projectId={1} record={record} />)
    openMenu()
    expect(screen.getByText(/No busk pages yet/)).toBeInTheDocument()
  })

  it('says a page has no banks rather than offering it as a dead end', () => {
    setPages([page(1, 'Act 1', [])])
    render(<AddToBuskPageMenu projectId={1} record={record} />)
    openMenu()
    expect(screen.getByText('No banks on this page')).toBeInTheDocument()
  })
})

it('appends to the bank chosen, naming the page it is on', async () => {
  setPages([page(1, 'Act 1', [bank(20, 'Cues')])])
  render(<AddToBuskPageMenu projectId={3} record={record} />)
  openMenu()
  fireEvent.click(screen.getByRole('menuitem', { name: /Cues/ }))

  await waitFor(() =>
    // `pageId` rides along for the echo bookkeeping; the *address* is the bank.
    expect(addPad).toHaveBeenCalledWith({ projectId: 3, pageId: 1, bankId: 20, cueId: 55 }),
  )
  // And it is remembered, so the create sheets offer the same bank next time.
  await waitFor(() =>
    expect(lastBuskAddTargetStore.getSnapshot()).toEqual({ projectId: 3, bankId: 20 }),
  )
})
