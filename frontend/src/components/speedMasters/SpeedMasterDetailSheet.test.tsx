// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SpeedMaster } from '../../api/speedMastersApi'

const saveMaster = vi.fn()
const deleteMaster = vi.fn()

const U2 = 'aaaaaaaa-0000-0000-0000-000000000002'

/**
 * The bank the sheet's leader picker and follow preview read.
 *
 * `vi.hoisted` because the mock factory below is hoisted above module scope and may not close
 * over ordinary consts. M6 follows M5 (the master these tests edit), so it is the descendant
 * whose exclusion from the picker keeps a chain from looping.
 */
const bank = vi.hoisted(() => [
  { id: 1, uuid: 'aaaaaaaa-0000-0000-0000-000000000001', masterIndex: 1, name: 'Master 1', bpm: 120, source: 'MANUAL', notes: null, referenceCount: 0 },
  { id: 2, uuid: 'aaaaaaaa-0000-0000-0000-000000000002', masterIndex: 2, name: 'Movement', bpm: 90, source: 'MANUAL', notes: null, referenceCount: 0 },
  { id: 5, uuid: 'aaaaaaaa-0000-0000-0000-000000000005', masterIndex: 5, name: 'Slow Wash', bpm: 64, source: 'MANUAL', notes: null, referenceCount: 0 },
  {
    id: 6, uuid: 'aaaaaaaa-0000-0000-0000-000000000006', masterIndex: 6, name: 'Chase',
    bpm: 32, source: 'MANUAL', notes: null, referenceCount: 0,
    followNum: 1, followDen: 2, followTargetUuid: 'aaaaaaaa-0000-0000-0000-000000000005',
  },
])

vi.mock('../../store/speedMasters', () => ({
  useSpeedMasterListQuery: () => ({ data: bank }),
  useSaveSpeedMasterMutation: () => [
    (args: unknown) => ({ unwrap: () => saveMaster(args) }),
    { isLoading: false, error: undefined },
  ],
  useDeleteSpeedMasterMutation: () => [
    (args: unknown) => ({ unwrap: () => deleteMaster(args) }),
    { isLoading: false },
  ],
  // The follow preview reads the *leader's* live tempo through selectFromResult, keyed by
  // uuid — which is why these carry one, unlike the index-only stub this replaced.
  useSpeedMasterLiveQuery: (
    _arg: unknown,
    opts?: {
      selectFromResult: (r: {
        data: { uuid: string; index: number; bpm: number }[]
      }) => unknown
    },
  ) =>
    opts?.selectFromResult({
      data: [
        { uuid: 'aaaaaaaa-0000-0000-0000-000000000001', index: 1, bpm: 120 },
        { uuid: 'aaaaaaaa-0000-0000-0000-000000000002', index: 2, bpm: 90 },
      ],
    }) ?? {},
}))

import { SpeedMasterDetailSheet } from './SpeedMasterDetailSheet'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function master(overrides: Partial<SpeedMaster> = {}): SpeedMaster {
  return {
    id: 5,
    uuid: 'aaaaaaaa-0000-0000-0000-000000000005',
    masterIndex: 5,
    name: 'Slow Wash',
    bpm: 64,
    source: 'MANUAL',
    notes: null,
    referenceCount: 0,
    ...overrides,
  }
}

function renderSheet(m: SpeedMaster) {
  return render(
    <SpeedMasterDetailSheet open onOpenChange={() => {}} projectId={1} master={m} />,
  )
}

describe('SpeedMasterDetailSheet', () => {
  it('seeds the form from the master', () => {
    renderSheet(master({ notes: 'half-time position waves' }))

    expect(screen.getByLabelText('Name')).toHaveValue('Slow Wash')
    expect(screen.getByLabelText('Starting BPM')).toHaveValue('64')
    expect(screen.getByLabelText('Notes')).toHaveValue('half-time position waves')
  })

  it('will not let master 1 be deleted', () => {
    // Master 1 is the global tempo — the server refuses (409 SPEED_MASTER_PROTECTED), so
    // the button says so up front rather than round-tripping a doomed request.
    renderSheet(master({ id: 1, masterIndex: 1, name: 'Master 1' }))

    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
  })

  it('keeps an in-progress edit when the master refetches', async () => {
    // The list refetches on every speedMasters.listChanged. Re-seeding on those would wipe
    // whatever the operator is halfway through typing.
    const initial = master()
    const { rerender } = renderSheet(initial)

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chorus wash' } })
    rerender(
      <SpeedMasterDetailSheet
        open
        onOpenChange={() => {}}
        projectId={1}
        // Same id, fresh object — exactly what a refetch produces.
        master={master({ name: 'Slow Wash' })}
      />,
    )

    expect(screen.getByLabelText('Name')).toHaveValue('Chorus wash')
  })

  it('rejects a starting BPM outside the clock range before sending it', () => {
    renderSheet(master())

    fireEvent.change(screen.getByLabelText('Starting BPM'), { target: { value: '900' } })

    expect(screen.getByText('Must be between 20 and 300.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })

  it('asks about an in-use master in the batch-delete dialog, naming its followers, and forces on request', async () => {
    deleteMaster.mockRejectedValueOnce({
      data: {
        error: 'in use',
        code: 'SPEED_MASTER_IN_USE',
        referenceCount: 4,
        lookEffectCount: 1,
        cueAdHocEffectCount: 2,
        cueLayerCount: 0,
        cueIds: [84, 91],
        followerNames: ['Chase'],
      },
    })
    const onOpenChange = vi.fn()
    render(<SpeedMasterDetailSheet open onOpenChange={onOpenChange} projectId={1} master={master({ referenceCount: 4 })} />)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    // One dialog — the sheet's batch delete over one master (library-sheets plan D13).
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Delete 1 master?')
    expect(dialog).toHaveTextContent('M5 · Slow Wash')
    expect(dialog).toHaveTextContent('1 look effect')
    expect(dialog).toHaveTextContent('2 cue effects')
    expect(dialog).toHaveTextContent('cues 84, 91')
    expect(dialog).toHaveTextContent('followed by Chase')

    deleteMaster.mockResolvedValueOnce(undefined)
    fireEvent.click(screen.getByRole('button', { name: 'Delete anyway' }))

    await waitFor(() => expect(deleteMaster).toHaveBeenCalledTimes(2))
    expect(deleteMaster.mock.calls[0][0]).toMatchObject({ masterId: 5, force: false })
    expect(deleteMaster.mock.calls[1][0]).toMatchObject({ masterId: 5, force: true })
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('omits bpm from a name-only save so a live tempo is not clobbered', async () => {
    // The PUT retunes the running clock whenever it carries a bpm, and the field is seeded
    // once per master — so resending the seed after someone tapped the master would snap
    // the live tempo back to the stored value mid-show.
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master())

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chorus' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).not.toHaveProperty('bpm')
  })

  it('saves name, starting bpm and notes together', async () => {
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master())

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chorus' } })
    fireEvent.change(screen.getByLabelText('Starting BPM'), { target: { value: '96' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).toMatchObject({
      projectId: 1,
      masterId: 5,
      name: 'Chorus',
      bpm: 96,
      notes: null,
    })
  })

  it('offers master 1 no tempo mode at all', () => {
    // Master 1 is what followers derive from — the server 400s a ratio on it
    // (SPEED_MASTER_CANNOT_FOLLOW). The control is hidden rather than disabled: a segmented
    // switch with both halves dead reads as breakage.
    renderSheet(master({ id: 1, masterIndex: 1, name: 'Master 1' }))

    expect(screen.queryByRole('radio', { name: 'Follow' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Starting BPM')).toBeInTheDocument()
  })

  it('hides the starting BPM while following, and shows what the ratio resolves to', () => {
    // A follower's tempo is derived from master 1, so its stored default is meaningless while
    // linked — and the server refuses a PUT carrying both.
    renderSheet(master({ followNum: 1, followDen: 2 }))

    expect(screen.queryByLabelText('Starting BPM')).not.toBeInTheDocument()
    // Master 1 is mocked at 120, so half time is 60.
    expect(screen.getByText('60')).toBeInTheDocument()
  })

  it('sends the ratio pair and no bpm when linking a manual master', async () => {
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master())

    fireEvent.click(screen.getByRole('radio', { name: 'Follow' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Follow Master 1 at 1/3' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    // Master 1 travels as null, not as its uuid: null *is* master 1 on every wire here, and a
    // fresh link defaults to it.
    expect(saveMaster.mock.calls[0][0]).toMatchObject({
      followNum: 1,
      followDen: 3,
      followTargetUuid: null,
    })
    // The pair and a bpm together is a 400 (SPEED_MASTER_FOLLOWER) — a follower has no stored
    // tempo to set.
    expect(saveMaster.mock.calls[0][0]).not.toHaveProperty('bpm')
  })

  it('unlinks by sending both halves as null', async () => {
    // A patch only changes the keys it carries, so "manual again" has to be said explicitly;
    // omitting them would leave the master following.
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master({ followNum: 1, followDen: 2 }))

    fireEvent.click(screen.getByRole('radio', { name: 'Manual' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).toMatchObject({
      followNum: null,
      followDen: null,
      followTargetUuid: null,
    })
  })

  it('names the followed master in the picker, the preview and the prose', () => {
    // The whole point of follow targets: a follower of M2 must not be described — anywhere —
    // as following master 1.
    renderSheet(master({ followNum: 1, followDen: 2, followTargetUuid: U2 }))

    expect(screen.getByRole('combobox', { name: 'Follows' })).toHaveTextContent('M2 · Movement')
    // M2 is mocked at 90 live, so half time is 45 — read off the leader, not off master 1.
    expect(screen.getByText('45')).toBeInTheDocument()
    expect(screen.getByText(/Movement drives this master/)).toBeInTheDocument()
  })

  it('carries the stored leader through a ratio-only edit', async () => {
    // The target rides with the pair. Sending the ratio alone would let the server's
    // carry-forward do the right thing, but this sheet knows which master it means and says so.
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master({ followNum: 1, followDen: 2, followTargetUuid: U2 }))

    fireEvent.click(screen.getByRole('radio', { name: 'Follow Movement at 1/4' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).toMatchObject({
      followNum: 1,
      followDen: 4,
      followTargetUuid: U2,
    })
  })

  it('treats a re-save of a master-1 follower as unchanged', async () => {
    // Master 1 has two spellings — its uuid and the null that means it. A picker seeded with
    // the uuid must not make a rename look like a re-link, or every save would re-send one.
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master({ followNum: 1, followDen: 2 }))

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chorus' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).not.toHaveProperty('followTargetUuid')
  })

  it('leaves the follow pair out of a save that did not touch it', async () => {
    // Same reasoning as bpm: the PUT is a patch, and restating a key the operator did not
    // touch is how another tab's edit gets quietly reverted.
    saveMaster.mockResolvedValueOnce(undefined)
    renderSheet(master({ followNum: 1, followDen: 2 }))

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chorus' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveMaster).toHaveBeenCalledTimes(1))
    expect(saveMaster.mock.calls[0][0]).not.toHaveProperty('followNum')
    expect(saveMaster.mock.calls[0][0]).not.toHaveProperty('usage')
  })

  it('seeds the usage select from the master', () => {
    // Routing is a fact about the bank, so the sheet has to show the one already set before it
    // can be changed — and "Not routed" is a real value, not an empty state.
    renderSheet(master({ usage: 'position' }))
    expect(screen.getByRole('combobox', { name: 'Default usage' })).toHaveTextContent(
      /movement/i,
    )

    cleanup()
    renderSheet(master())
    expect(screen.getByRole('combobox', { name: 'Default usage' })).toHaveTextContent(
      'Not routed',
    )
  })
})
