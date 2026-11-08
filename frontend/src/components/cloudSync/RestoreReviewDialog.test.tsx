// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { CommitInfo, RestoreResult, RestoreReview } from '@/store/cloudSync'

let review: RestoreReview | undefined
let reviewError: unknown
const restoreCalls = vi.fn()
let restoreAnswers: Array<() => Promise<unknown>> = []

vi.mock('@/store/cloudSync', () => ({
  RESTORE_NOTE_MAX_LENGTH: 200,
  useCloudSyncRestoreReviewQuery: () => ({ data: review, error: reviewError, isFetching: false }),
  useCloudSyncRestoreMutation: () => [
    (arg: unknown) => {
      restoreCalls(arg)
      const answer = restoreAnswers.shift() ?? (() => Promise.reject(new Error('no answer queued')))
      return { unwrap: answer }
    },
    { isLoading: false },
  ],
}))

const navigate = vi.fn()
vi.mock('react-router', () => ({ useNavigate: () => navigate }))

const toastSuccess = vi.fn()
vi.mock('sonner', () => ({ toast: { success: (...args: unknown[]) => toastSuccess(...args), error: vi.fn() } }))

import { RestoreReviewDialog } from './RestoreReviewDialog'

const WHEN = Date.UTC(2026, 9, 29, 21, 14)

function commit(over: Partial<CommitInfo> = {}): CommitInfo {
  return {
    sha: '3f2a91c0000000000000000000000000000000aa',
    shortSha: '3f2a91c',
    authorName: 'lighting7',
    authorEmail: 'desk@lighting7',
    whenMs: WHEN,
    message: 'Tech run, end of night\n\nmore',
    installShortUuid: 'ab12',
    installFriendlyName: 'FOH desk',
    ...over,
  }
}

const REVIEW: RestoreReview = {
  target: commit(),
  head: commit({ sha: 'a41c09e0000000000000000000000000000000bb', shortSha: 'a41c09e', message: 'Snapshot' }),
  token: 'token-1',
  noChanges: false,
  counts: { back: 2, gone: 1, changed: 1 },
  kinds: [
    {
      folder: 'cues',
      noun: 'cue',
      plural: 'cues',
      records: [
        { uuid: 'c1', label: 'Cue “Rose garden”', change: 'BACK' },
        { uuid: 'c2', label: 'Cue “Spindle (new)”', change: 'GONE' },
        { uuid: 'c3', label: 'Cue “Curse”', change: 'CHANGED' },
      ],
    },
    { folder: 'looks', noun: 'Look', plural: 'Looks', records: [{ uuid: 'l1', label: 'Look “Dawn”', change: 'BACK' }] },
  ],
  project: null,
  venue: null,
  addresses: null,
  live: false,
  sync: { repo: 'https://github.com/x/y', lastSyncedAt: new Date(WHEN).toISOString(), push: true },
}

const RESULT: RestoreResult = {
  commit: commit({ sha: 'f00d000000000000000000000000000000000001', shortSha: 'f00d000', restoredFrom: REVIEW.target.sha }),
  before: commit({ sha: 'befe000000000000000000000000000000000002', shortSha: 'befe000' }),
  counts: { back: 2, gone: 1, changed: 1 },
  sync: { outcome: 'PUSHED', pushed: 1, pulled: 0, replaced: 0, headSha: 'f00d000000000000000000000000000000000001', message: '' },
  notes: [],
}

const onOpenChange = vi.fn()

function draw() {
  return render(<RestoreReviewDialog projectId={4} sha={REVIEW.target.sha} onOpenChange={onOpenChange} />)
}

/** The done toast's options, from the first `toast.success`. */
function doneToast() {
  return toastSuccess.mock.calls[0] as [
    string,
    { id: string; description: string; action: { label: string; onClick: () => void } },
  ]
}

function notes(): string[] {
  return [...document.querySelectorAll('[data-restore-note]')].map((n) => n.textContent ?? '')
}

function restoreButton(): HTMLButtonElement {
  return screen.getByRole('button', { name: 'Restore' }) as HTMLButtonElement
}

beforeEach(() => {
  review = REVIEW
  reviewError = undefined
  restoreAnswers = []
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('RestoreReviewDialog (sync-restore plan session 2)', () => {
  it('names the snapshot, counts the changes and groups them by kind', () => {
    draw()
    expect(screen.getByRole('heading').textContent).toMatch(/^Restore to .+\?$/)
    expect(screen.getByText(/3f2a91c/).closest('p')!.textContent).toBe('3f2a91c · FOH desk · “Tech run, end of night”')
    const counts = screen.getByLabelText('What the restore changes').textContent
    expect(counts).toContain('2come back')
    expect(counts).toContain('1go')
    expect(counts).toContain('1change back')

    const cues = document.querySelector('[data-restore-kind="cues"]') as HTMLDetailsElement
    expect(cues.open).toBe(true)
    expect(cues.querySelector('summary')!.textContent).toBe('Cues · 3')
    const marks = [...cues.querySelectorAll('li')].map((li) => li.textContent)
    expect(marks).toEqual([
      '+Cue “Rose garden” comes back',
      '−Cue “Spindle (new)” goes',
      '~Cue “Curse” changes back',
    ])
    const looks = document.querySelector('[data-restore-kind="looks"]') as HTMLDetailsElement
    expect(looks.open).toBe(false)
    expect(looks.querySelector('summary')!.textContent).toBe('Look · 1')
  })

  it('lists the show settings that change, and the name another project holds', () => {
    review = {
      ...REVIEW,
      project: {
        stageSize: { now: { widthM: 10, depthM: 8, heightM: 6 }, then: { widthM: 12, depthM: 8, heightM: 6 } },
        description: { now: 'New', then: 'Old' },
        nameKept: 'Sleeping Beauty',
      },
    }
    draw()
    const settings = document.querySelector('[data-restore-kind="project"]')!
    expect(settings.querySelector('summary')!.textContent).toBe('Show settings · 2')
    expect(settings.textContent).toContain('Description: back to “Old”')
    expect(settings.textContent).toContain('Stage size: 10 × 8 × 6 m → 12 × 8 × 6 m')
    expect(notes()).toContain('Another project is now called “Sleeping Beauty”, so this show keeps its current name.')
  })

  it('says a live show reloads, and one left behind on output runs against its pin', () => {
    review = {
      ...REVIEW,
      live: true,
      venue: {
        now: { uuid: 'v', name: 'Commemoration Hall', version: 5 },
        then: { uuid: 'v', name: 'Commemoration Hall', version: 3 },
        behind: 'OUTPUT',
        runAgainst: true,
      },
    }
    draw()
    const all = notes()
    expect(all[0]).toMatch(/^This show is live\. It reloads and crossfades into the snapshot/)
    expect(all[1]).toBe(
      'Venue: back to Commemoration Hall v3 (now v5). Later versions change output, so this show will be behind its venue. It keeps running against v3 now and opens held next time, until you update it.',
    )
  })

  it('says a show restored to before its venue comes off it', () => {
    review = {
      ...REVIEW,
      venue: { now: { uuid: 'v', name: 'Commemoration Hall', version: 2 }, then: null, behind: 'NONE', runAgainst: false },
    }
    draw()
    expect(notes()).toContain(
      'Venue: the snapshot is from before this show was on Commemoration Hall. It comes off the venue, with its rig as its own.',
    )
  })

  it('says which addresses come back and which universes come back without one', () => {
    review = { ...REVIEW, addresses: { copied: 2, missing: ['Universe 1', 'Universe 2:1'] } }
    draw()
    expect(notes()).toContain('2 addresses this desk keeps for the venue come back to the show.')
    expect(notes()).toContain('Universe 1, Universe 2:1 come back with no address on this desk. Set them in Universes after restoring.')
  })

  it('says the restore is pushed after, or stays here on a pull-only desk', () => {
    draw()
    expect(notes().at(-1)).toMatch(/^Last synced .+\. The desk syncs before restoring and pushes the restore after/)
    cleanup()
    review = { ...REVIEW, sync: { ...REVIEW.sync, push: false } }
    draw()
    expect(notes()).toContain("This desk doesn't push, so the restore stays on this desk.")
  })

  it('disables Restore when the snapshot is the show already, and says why', () => {
    review = { ...REVIEW, noChanges: true, counts: { back: 0, gone: 0, changed: 0 }, kinds: [] }
    draw()
    expect(restoreButton().disabled).toBe(true)
    expect(screen.getByText('This snapshot is the show as it is now: there is nothing to restore.')).toBeTruthy()
    expect(screen.queryByLabelText('Note (optional)')).toBeNull()
  })

  it('restores with the token and the note, toasts the counts, and Undo reviews the show as it stood', async () => {
    restoreAnswers.push(() => Promise.resolve({ ...RESULT, notes: ['A note from the desk.'] }))
    draw()
    fireEvent.change(screen.getByLabelText('Note (optional)'), { target: { value: '  Back to the tech  ' } })
    fireEvent.click(restoreButton())
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(restoreCalls).toHaveBeenCalledWith({
      projectId: 4,
      body: { sha: REVIEW.target.sha, token: 'token-1', note: 'Back to the tech' },
    })
    const [title, options] = doneToast()
    expect(title).toMatch(/^Restored to /)
    expect(options.id).toBe('cloud-sync-4')
    expect(options.description).toBe('4 records changed · pushed to the repo\nA note from the desk.')
    expect(options.action.label).toBe('Undo')
    options.action.onClick()
    expect(navigate).toHaveBeenCalledWith(`/projects/4/settings/sync?review=${RESULT.before.sha}`)
  })

  it('says a pin-only restore moved the venue, and a merge that pushed nothing is not pushed', async () => {
    review = {
      ...REVIEW,
      counts: { back: 0, gone: 0, changed: 0 },
      kinds: [],
      venue: { now: { uuid: 'v', version: 5 }, then: { uuid: 'v', version: 3 }, behind: 'NONE', runAgainst: false },
    }
    restoreAnswers.push(() =>
      Promise.resolve({
        ...RESULT,
        counts: { back: 0, gone: 0, changed: 0 },
        sync: { ...RESULT.sync!, outcome: 'MERGED', pushed: 0 },
      }),
    )
    draw()
    fireEvent.click(restoreButton())
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(doneToast()[1].description).toBe('Venue version changed')
  })

  it('puts a stale answer’s review in place, and restores with its token', async () => {
    const fresh: RestoreReview = { ...REVIEW, token: 'token-2', counts: { back: 5, gone: 0, changed: 0 } }
    restoreAnswers.push(() => Promise.reject({ status: 409, data: { error: 'moved', code: 'RESTORE_REVIEW_STALE', review: fresh } }))
    restoreAnswers.push(() => Promise.resolve(RESULT))
    draw()
    fireEvent.click(restoreButton())
    await screen.findByText('The show changed since this review was read. This is what restoring would do now.')
    expect(screen.getByLabelText('What the restore changes').textContent).toContain('5come back')
    expect(onOpenChange).not.toHaveBeenCalled()
    fireEvent.click(restoreButton())
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(restoreCalls.mock.calls[1][0].body.token).toBe('token-2')
  })

  it.each([
    ['SESSION_PENDING', 'A sync conflict is open. Resolve or abort it in the Conflicts panel, then restore.'],
    ['RESTORE_TOO_OLD', 'This snapshot was written in a format this desk no longer reads.'],
    ['RESTORE_COMMIT_UNKNOWN', "This snapshot isn't in the show's history any more."],
    ['RESTORE_UNREADABLE', 'cues/x.json is not JSON'],
  ])('says a refused restore (%s) in its own words', async (code, text) => {
    restoreAnswers.push(() => Promise.reject({ status: 409, data: { error: 'cues/x.json is not JSON', code } }))
    draw()
    fireEvent.click(restoreButton())
    expect(await screen.findByText(text)).toBeTruthy()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('says a refused review, and names the venue a missing pin needs', () => {
    review = undefined
    reviewError = { status: 409, data: { error: 'Commemoration Hall v3 (github.com/x/hall)', code: 'VENUE_MISSING' } }
    draw()
    expect(
      screen.getByText("The snapshot's venue version isn't on this desk. Import or sync the venue first. Commemoration Hall v3 (github.com/x/hall)"),
    ).toBeTruthy()
    expect(restoreButton().disabled).toBe(true)
  })
})
