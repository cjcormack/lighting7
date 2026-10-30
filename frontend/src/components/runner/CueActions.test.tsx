// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import type { CueUses } from '@/api/cuesApi'

/**
 * The cue action set on its own (show-cue-editing plan §5): what each action sends, the rule over
 * several rows, the D8 delete confirm and the lock without a way out. The card, the sheet and the
 * phone hosts are pinned where they mount it.
 */

const landed = (value: unknown = undefined) => ({ unwrap: () => Promise.resolve(value) })
const duplicateCue = vi.fn()
const deleteCues = vi.fn()
const removeEntry = vi.fn()
const addToStack = vi.fn()
const moveEntry = vi.fn()
const include = vi.fn()
const blindOn = vi.fn()
let preview: { uses: CueUses[] } | undefined
let running = false
let edited = false
const updateStage = vi.fn()

vi.mock('@/store/cues', () => ({
  useDuplicateCueMutation: () => [duplicateCue],
  useDeleteCuesMutation: () => [deleteCues, { isLoading: false }],
  useCueDeletePreviewQuery: (_arg: unknown, opts?: { skip?: boolean }) => ({
    data: opts?.skip ? undefined : preview,
    isFetching: false,
    isError: false,
  }),
  useUpdateCueStageMutation: () => [updateStage, { isLoading: false }],
}))
vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => edited }))
vi.mock('@/store/cueStacks', () => ({
  useAddCueToStackMutation: () => [addToStack],
  useMoveCueStackEntryMutation: () => [moveEntry],
  useRemoveCueStackEntryMutation: () => [removeEntry],
  useProjectProgramStateQuery: () => ({ data: { activeStackId: running ? 7 : null, canEdit: true } }),
  useProjectCueStackListQuery: () => ({
    data: [
      { id: 7, name: 'Act 1', type: 'STACK', cues: [] },
      { id: 8, name: 'Act 2', type: 'STACK', cues: [] },
      { id: 9, name: '—', type: 'SEPARATOR', cues: [] },
    ],
  }),
}))
vi.mock('@/store/programmer', () => ({
  programmerBlindOn: (fadeMs?: number) => blindOn(fadeMs),
  useProgrammerSummaryQuery: () => ({ data: { blind: false } }),
}))
vi.mock('@/components/programmer/useInclude', () => ({
  useInclude: () => ({ includeCue: include, isLoading: false }),
}))
vi.mock('@/components/programmer/RecordSheet', () => ({
  RecordSheet: (p: { targetCueId: number }) => <div data-testid="record-sheet">{p.targetCueId}</div>,
}))
const toastError = vi.fn()
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: (m: string) => toastError(m) } }))

import { CueActions, ONE_CUE_REASON, type CueActionRow } from './CueActions'
import { CueActionDialogsProvider } from './cueActionDialogs'
import { resetShowIncludeForTests } from '@/lib/showInclude'

const ROWS: CueActionRow[] = [
  { cueId: 1, name: 'Opener', cueNumber: '1', entryId: 101, stackId: 7 },
  { cueId: 2, name: 'Storm', cueNumber: '2', entryId: 102, stackId: 7 },
]

function draw(over: Partial<React.ComponentProps<typeof CueActions>> = {}) {
  return render(
    <MemoryRouter>
      <CueActions projectId={1} host="strip" rows={[ROWS[0]]} locked={false} {...over} />
    </MemoryRouter>,
  )
}

/** Open a Radix dropdown from its trigger — jsdom has no pointer for it. */
function openMenu(trigger: HTMLElement) {
  fireEvent.keyDown(trigger, { key: 'Enter' })
}

beforeEach(() => {
  running = false
  edited = false
  updateStage.mockReturnValue(landed({ rows: 3, fx: { edited: 1, stopped: 1, started: 0 }, fadeMs: 2000, curve: 'LINEAR' }))
  preview = undefined
  duplicateCue.mockImplementation(({ cueId, stackId }) =>
    landed({ cueId: cueId + 50, name: 'copy', stackId, entryId: cueId + 150 }),
  )
  deleteCues.mockReturnValue(landed({ deleted: [], uses: [] }))
  removeEntry.mockReturnValue(landed())
  addToStack.mockReturnValue(landed())
  moveEntry.mockReturnValue(landed())
  include.mockResolvedValue({ fixtureKeys: [], groupKeys: [], warnings: [] })
  blindOn.mockResolvedValue(true)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  resetShowIncludeForTests()
})

describe('CueActions', () => {
  it('over several rows disables the one-cue actions with the reason, and keeps the rest', () => {
    draw({ host: 'bar', rows: ROWS })
    for (const name of ['Edit in programmer', 'Load into programmer', 'Record…']) {
      const button = screen.getByRole('button', { name })
      expect(button).toHaveProperty('disabled', true)
      expect(button.getAttribute('title')).toBe(ONE_CUE_REASON)
    }
    for (const name of ['Duplicate', 'Remove from stack', 'Delete…']) {
      expect(screen.getByRole('button', { name })).toHaveProperty('disabled', false)
    }
  })

  it('duplicates each row in row order, to the end of its stack', async () => {
    draw({ host: 'bar', rows: ROWS })
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(duplicateCue).toHaveBeenCalledTimes(2))
    expect(duplicateCue.mock.calls.map(([arg]) => arg)).toEqual([
      { projectId: 1, cueId: 1, stackId: 7 },
      { projectId: 1, cueId: 2, stackId: 7 },
    ])
  })

  it('opens one copy on the Timeline from the Timeline, and on the stack’s sticky view elsewhere', async () => {
    const Where = () => <span data-testid="where">{useLocation().pathname + useLocation().search}</span>
    for (const [host, path] of [
      ['timeline', '/projects/1/show/stacks/7/timeline?cue=51&entry=151'],
      ['bar', '/projects/1/show/stacks/7?cue=51&entry=151'],
    ] as const) {
      render(
        <MemoryRouter>
          <CueActions projectId={1} host={host} rows={[ROWS[0]]} locked={false} />
          <Where />
        </MemoryRouter>,
      )
      fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
      await waitFor(() => expect(screen.getByTestId('where').textContent).toBe(path))
      cleanup()
    }
  })

  it('removes each entry from its stack — never the cue — and asks nothing', () => {
    draw({ host: 'bar', rows: ROWS })
    fireEvent.click(screen.getByRole('button', { name: 'Remove from stack' }))
    expect(removeEntry.mock.calls.map(([arg]) => arg)).toEqual([
      { projectId: 1, stackId: 7, entryId: 101, cueId: 1 },
      { projectId: 1, stackId: 7, entryId: 102, cueId: 2 },
    ])
    expect(deleteCues).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('Delete… lists every use from the dry run in one confirm, then deletes them all with force', async () => {
    preview = {
      uses: [
        {
          cueId: 1,
          name: 'Opener',
          entries: [
            { stackId: 7, stackName: 'Act 1', entryId: 101, cueNumber: '1' },
            { stackId: 8, stackName: 'Act 2', entryId: 205, cueNumber: '14' },
          ],
          pads: [{ padId: 3, pageId: 1, pageName: 'Colours' }],
          slots: [{ slotId: 4, label: 'Page 1, slot 2' }],
          anchors: [{ entryId: 101, label: 'LX 1' }],
          bindings: [{ bindingId: 9, label: 'xtouch · fader-1 · bank 2' }],
        },
      ],
    }
    draw({ host: 'bar', rows: ROWS })
    fireEvent.click(screen.getByRole('button', { name: 'Delete…' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Delete 2 cues?')).toBeTruthy()
    const lines = [...dialog.querySelectorAll('[data-cue-use]')].map((el) => el.textContent)
    expect(lines).toEqual([
      'In Act 1 as Q1 · Act 2 as Q14',
      'A busk pad on Colours',
      'Cue slot Page 1, slot 2',
      '1 prompt-book anchor (LX 1)',
      'Surface binding xtouch · fader-1 · bank 2 — stays, naming no cue',
    ])
    // The cue nothing names says so; and the binding's fate is said once, out loud.
    expect(within(dialog).getByText('Nothing else names it.')).toBeTruthy()
    expect(dialog.querySelector('[data-binding-note]')?.textContent).toMatch(/report a missing cue/)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(deleteCues).toHaveBeenCalledWith({ projectId: 1, cueIds: [1, 2] })
  })

  it('moves an entry whole through the move route — not add then remove', async () => {
    draw()
    openMenu(screen.getByRole('button', { name: 'More cue actions' }))
    const sub = await screen.findByRole('menuitem', { name: /Move to stack/ })
    fireEvent.keyDown(sub, { key: 'ArrowRight' })
    // Its own stack is not a destination.
    expect(screen.queryByRole('menuitem', { name: 'Act 1' })).toBeNull()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Act 2' }))
    await waitFor(() =>
      expect(moveEntry).toHaveBeenCalledWith({ projectId: 1, stackId: 7, entryId: 101, cueId: 1, toStackId: 8 }),
    )
    expect(addToStack).not.toHaveBeenCalled()
    expect(removeEntry).not.toHaveBeenCalled()
  })

  it('locked with no way to unlock here: every action but Blind edit is disabled with the reason', () => {
    running = true
    draw({ locked: true, lockedReason: 'Locked — unlock the show on the Show view to edit' })
    expect(screen.getByRole('button', { name: 'Blind edit in programmer' })).toHaveProperty('disabled', false)
    for (const name of ['Load into programmer', 'Record…', 'Duplicate', 'Remove from stack', 'Delete…']) {
      const button = screen.getByRole('button', { name })
      expect(button).toHaveProperty('disabled', true)
      expect(button.getAttribute('title')).toBe('Locked — unlock the show on the Show view to edit')
    }
  })

  it('Blind edit loads nothing when the desk does not say Blind is on', async () => {
    running = true
    blindOn.mockResolvedValueOnce(false)
    draw({ locked: true, onRequestUnlock: vi.fn() })
    fireEvent.click(screen.getByRole('button', { name: 'Blind edit in programmer' }))
    await waitFor(() => expect(toastError).toHaveBeenCalled())
    expect(include).not.toHaveBeenCalled()
  })

  it('records the way back for ← Show only from the Show view (D16)', async () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Load into programmer' }))
    await waitFor(() => expect(include).toHaveBeenCalledTimes(1))
    expect(sessionStorage.getItem('show.include')).toBeNull()
    cleanup()
    draw({ fromShowView: true })
    fireEvent.click(screen.getByRole('button', { name: 'Load into programmer' }))
    await waitFor(() =>
      expect(JSON.parse(sessionStorage.getItem('show.include') ?? 'null')).toEqual({
        projectId: 1,
        cueId: 1,
        entryId: 101,
      }),
    )
  })

  it('a click inside its dialogs does not reach the host the menu sits in', async () => {
    const hostClick = vi.fn()
    render(
      <MemoryRouter>
        <div onClick={hostClick}>
          <CueActions projectId={1} host="menu" rows={[ROWS[0]]} locked={false} />
        </div>
      </MemoryRouter>,
    )
    openMenu(screen.getByRole('button', { name: 'Cue actions' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete…' }))
    hostClick.mockClear()
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }))
    expect(hostClick).not.toHaveBeenCalled()
  })

  describe('the dialogs outlive their host', () => {
    /** A live card: its strip is drawn only while it is open, as the cards view's is. */
    function LiveCard({ open }: { open: boolean }) {
      return open ? <CueActions projectId={1} host="strip" rows={[ROWS[0]]} locked={false} /> : null
    }
    function drawCard(open: boolean) {
      return (
        <MemoryRouter>
          <CueActionDialogsProvider>
            <LiveCard open={open} />
          </CueActionDialogsProvider>
        </MemoryRouter>
      )
    }

    it('a GO from elsewhere collapsing the live card does not close its Record sheet', () => {
      const view = render(drawCard(true))
      fireEvent.click(screen.getByRole('button', { name: 'Record…' }))
      expect(screen.getByTestId('record-sheet').textContent).toBe('1')
      // The GO: the card was open only because it was live, and it closes.
      view.rerender(drawCard(false))
      expect(screen.queryByRole('button', { name: 'Record…' })).toBeNull()
      expect(screen.getByTestId('record-sheet').textContent).toBe('1')
    })

    it('nor its delete confirm', async () => {
      preview = { uses: [] }
      const view = render(drawCard(true))
      fireEvent.click(screen.getByRole('button', { name: 'Delete…' }))
      await screen.findByRole('dialog')
      view.rerender(drawCard(false))
      const dialog = screen.getByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
      expect(deleteCues).toHaveBeenCalledWith({ projectId: 1, cueIds: [1] })
    })
  })

  it('Record… opens the Record sheet on the cue', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Record…' }))
    expect(screen.getByTestId('record-sheet').textContent).toBe('1')
  })

  it('draws the phone card’s ⋯ menu in §5’s order, the face first', async () => {
    running = true
    draw({ host: 'menu' })
    openMenu(screen.getByRole('button', { name: 'Cue actions' }))
    const items = (await screen.findAllByRole('menuitem')).map((el) => el.textContent)
    expect(items).toEqual([
      'Blind edit in programmer',
      'Edit in programmer',
      'Load into programmer',
      'Record…',
      'Duplicate',
      'Remove from stack',
      'Delete…',
      'Add to stack…',
      'Move to stack…',
      'Copy link',
    ])
  })

  describe('Update stage… (show-cue-editing plan §4.4)', () => {
    it('follows Record… in the menu while the cue is edited since GO, and not otherwise', async () => {
      draw({ host: 'menu' })
      openMenu(screen.getByRole('button', { name: 'Cue actions' }))
      expect((await screen.findAllByRole('menuitem')).map((el) => el.textContent)).not.toContain('Update stage…')
      cleanup()

      edited = true
      draw({ host: 'menu' })
      openMenu(screen.getByRole('button', { name: 'Cue actions' }))
      const items = (await screen.findAllByRole('menuitem')).map((el) => el.textContent)
      expect(items.slice(items.indexOf('Record…'), items.indexOf('Record…') + 2)).toEqual(['Record…', 'Update stage…'])
    })

    it('opens one confirm naming the cue and the dry run’s counts, which sends one update-stage', async () => {
      edited = true
      draw({ host: 'menu' })
      openMenu(screen.getByRole('button', { name: 'Cue actions' }))
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Update stage…' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText('Update stage to Q1 Opener?')).toBeTruthy()
      await waitFor(() => expect(dialog.querySelector('[data-update-stage-counts]')?.textContent).toContain('3 values change, over its 2.0s fade'))
      expect(dialog.querySelector('[data-update-stage-counts]')?.textContent).toContain('1 FX edited in place · 1 stopped')
      expect(updateStage.mock.calls.map(([arg]) => arg)).toEqual([{ projectId: 1, cueId: 1, dryRun: true }])

      fireEvent.click(within(dialog).getByRole('button', { name: 'Update stage' }))
      await waitFor(() => expect(updateStage).toHaveBeenCalledTimes(2))
      expect(updateStage.mock.calls[1][0]).toEqual({ projectId: 1, cueId: 1, dryRun: false })
    })

    it('locked, asks to unlock and sends nothing', async () => {
      edited = true
      const unlock = vi.fn()
      draw({ host: 'menu', locked: true, onRequestUnlock: unlock })
      openMenu(screen.getByRole('button', { name: 'Cue actions' }))
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Update stage…' }))
      expect(await screen.findByText('Unlock the show to edit?')).toBeTruthy()
      expect(updateStage).not.toHaveBeenCalled()
    })
  })
})
