// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import type { BuskBank, BuskPage } from '@/api/buskApi'
import { lastBuskAddTargetStore } from '@/lib/buskAdd'

/**
 * The create sheets' *Also add to \<bank\>* row.
 *
 * The first assertion here is the one that matters: this row asks for its choices **without a
 * record**, because the template it is placing does not exist until the sheet's Create call
 * answers. An earlier version returned no targets at all in that case, which made the whole control
 * render nothing and its placement branch unreachable — with `tsc` and every other suite green.
 */

const pages: BuskPage[] = []

vi.mock('@/store/busk', () => ({
  useBuskPagesQuery: () => ({ data: pages, isLoading: false }),
  useAddBuskPadMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
}))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

const { AlsoAddToBuskRow } = await import('./AlsoAddToBuskRow')

function bank(id: number, name: string): BuskBank {
  return { id, name, solo: false, flow: 'WRAP', pads: [] }
}

function page(id: number, name: string, banks: BuskBank[]): BuskPage {
  return {
    id,
    uuid: `u${id}`,
    name,
    sortOrder: id,
    rows: banks.length === 0 ? [] : [{ columns: [{ width: 12, banks }] }],
  }
}

function setPages(next: BuskPage[]) {
  pages.length = 0
  pages.push(...next)
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.localStorage.clear()
  lastBuskAddTargetStore.reset()
})

it('offers a bank even though the record being placed does not exist yet', async () => {
  setPages([page(1, 'Act 1', [bank(20, 'Movement')])])
  const onChange = vi.fn()
  render(<AlsoAddToBuskRow projectId={1} target={null} onChange={onChange} />)

  // Seeded from the first bank, and reported up so the sheet can place the pad after it creates.
  await waitFor(() =>
    expect(onChange).toHaveBeenCalledWith({
      pageId: 1,
      pageName: 'Act 1',
      bankId: 20,
      bankLabel: 'Movement',
    }),
  )
})

it('offers the bank a placement last landed in', async () => {
  setPages([page(1, 'Act 1', [bank(20, 'Movement'), bank(21, 'Colour')])])
  lastBuskAddTargetStore.set({ projectId: 1, bankId: 21 })
  const onChange = vi.fn()
  render(<AlsoAddToBuskRow projectId={1} target={null} onChange={onChange} />)

  await waitFor(() => expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ bankId: 21 })))
})

it('renders nothing when there is nowhere to put a pad', () => {
  // A page with no banks is a legal page, and `[].every(...)` is vacuously true — so this case has
  // to be asked as "are there any choices", not "do all pages lack banks".
  setPages([page(1, 'Act 1', [])])
  const onChange = vi.fn()
  const { container } = render(<AlsoAddToBuskRow projectId={1} target={null} onChange={onChange} />)

  expect(container).toBeEmptyDOMElement()
  expect(onChange).not.toHaveBeenCalled()
})

it('shows the chosen bank once one is held', () => {
  setPages([page(1, 'Act 1', [bank(20, 'Movement')])])
  render(
    <AlsoAddToBuskRow
      projectId={1}
      target={{ pageId: 1, pageName: 'Act 1', bankId: 20, bankLabel: 'Movement' }}
      onChange={vi.fn()}
    />,
  )
  expect(screen.getByText('Also add a pad to')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Movement · Act 1/ })).toBeInTheDocument()
})
