// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { CommitInfo } from '@/store/cloudSync'

function commit(n: number, over: Partial<CommitInfo> = {}): CommitInfo {
  const sha = String(n).repeat(40).slice(0, 40)
  return {
    sha,
    shortSha: sha.slice(0, 7),
    authorName: 'lighting7',
    authorEmail: 'desk@lighting7',
    whenMs: Date.UTC(2026, 10, n),
    message: `Snapshot ${n}`,
    installShortUuid: 'ab12',
    installFriendlyName: 'FOH desk',
    ...over,
  }
}

const RESTORED_FROM = '3f2a91c000000000000000000000000000000000'
const COMMITS = [
  commit(3, { message: 'FOH desk: Restored 3f2a91c from 2026-10-29 21:14 UTC', restoredFrom: RESTORED_FROM }),
  commit(2),
  commit(1),
]

vi.mock('@/store/cloudSync', () => ({
  useCloudSyncLogQuery: () => ({ data: COMMITS, isLoading: false }),
  useLazyCloudSyncLogQuery: () => [vi.fn(), { isFetching: false }],
}))

// The dialog has its own tests; here it only has to say which snapshot it was opened for.
vi.mock('./RestoreReviewDialog', () => ({
  RestoreReviewDialog: ({ sha }: { sha: string }) => <div data-testid="review">{sha}</div>,
}))

let search = new URLSearchParams()
const setSearchParams = vi.fn((update: (params: URLSearchParams) => URLSearchParams) => {
  search = update(new URLSearchParams(search))
})
vi.mock('react-router', () => ({ useSearchParams: () => [search, setSearchParams] }))

const toastSuccess = vi.fn()
vi.mock('sonner', () => ({ toast: { success: (...args: unknown[]) => toastSuccess(...args), error: vi.fn() } }))

import { HistoryPanel } from './HistoryPanel'

afterEach(() => {
  vi.clearAllMocks()
  search = new URLSearchParams()
})

/** Radix's DropdownMenuTrigger opens on `pointerdown`, not `click`. */
function openMenu(shortSha: string) {
  fireEvent.pointerDown(screen.getByRole('button', { name: `Actions for ${shortSha}` }), {
    button: 0,
    ctrlKey: false,
    pointerType: 'mouse',
  })
  return screen.getByRole('menu')
}

describe('HistoryPanel (sync-restore plan session 2)', () => {
  it('opens the review for an older snapshot from its row menu', () => {
    render(<HistoryPanel projectId={4} />)
    const menu = openMenu(COMMITS[1].shortSha)
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Review and restore…' }))
    expect(screen.getByTestId('review').textContent).toContain(COMMITS[1].sha)
  })

  it('disables restoring the newest snapshot, which is the show already', () => {
    render(<HistoryPanel projectId={4} />)
    const menu = openMenu(COMMITS[0].shortSha)
    const item = within(menu).getByRole('menuitem', { name: /^Review and restore…/ })
    expect(item).toHaveAttribute('data-disabled')
    expect(item.textContent).toContain('The show now')
  })

  it('offers no restore on a venue, whose history is its versions', () => {
    render(<HistoryPanel projectId={4} isVenue />)
    const menu = openMenu(COMMITS[1].shortSha)
    expect(within(menu).queryByRole('menuitem', { name: 'Review and restore…' })).toBeNull()
    expect(within(menu).getByRole('menuitem', { name: 'Copy commit id' })).toBeTruthy()
  })

  it('copies a commit id', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<HistoryPanel projectId={4} />)
    fireEvent.click(within(openMenu(COMMITS[2].shortSha)).getByRole('menuitem', { name: 'Copy commit id' }))
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Commit id copied'))
    expect(writeText).toHaveBeenCalledWith(COMMITS[2].sha)
  })

  it('marks a restore and names the snapshot it went back to', () => {
    render(<HistoryPanel projectId={4} />)
    const marker = screen.getByText('Restore of 3f2a91c')
    expect(marker).toHaveAttribute('title', `Restores ${RESTORED_FROM}`)
    expect(screen.getAllByText(/^Restore of /)).toHaveLength(1)
  })

  it("opens the review ?review= names, where a restore toast's Undo lands, and takes it off the URL", () => {
    search = new URLSearchParams({ review: 'before-sha', other: 'kept' })
    render(<HistoryPanel projectId={4} />)
    expect(screen.getByTestId('review').textContent).toBe('before-sha')
    expect(search.get('review')).toBeNull()
    expect(search.get('other')).toBe('kept')
  })

  it('ignores ?review= on a venue', () => {
    search = new URLSearchParams({ review: 'before-sha' })
    render(<HistoryPanel projectId={4} isVenue />)
    expect(screen.queryByTestId('review')).toBeNull()
  })
})
