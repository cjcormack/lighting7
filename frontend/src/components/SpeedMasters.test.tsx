// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SpeedMasterLiveState } from '../api/speedMastersWsApi'

// Store-connected; mocking the store module keeps this a component test and — just as
// importantly — keeps the import graph away from lightingApi's real WebSocket.
const setSpeedMasterBpm = vi.fn()
const tapSpeedMaster = vi.fn()
const subscribeToSpeedMasterBeat = vi.fn(
  (_masterUuid: string | null, _fn: (beat: { bpm: number }) => void) => ({
    unsubscribe: () => {},
  }),
)
let liveMasters: SpeedMasterLiveState[] = []
vi.mock('../store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({ data: liveMasters }),
  setSpeedMasterBpm: (...args: unknown[]) => setSpeedMasterBpm(...args),
  tapSpeedMaster: (...args: unknown[]) => tapSpeedMaster(...args),
  // Each tile carries a BeatIndicator keyed to its own master.
  subscribeToSpeedMasterBeat: (masterUuid: string | null, fn: (beat: { bpm: number }) => void) =>
    subscribeToSpeedMasterBeat(masterUuid, fn),
  useMaster1Uuid: () => liveMasters.find((m) => m.index === 1)?.uuid ?? null,
  requestSpeedMasterBeat: () => {},
  // The dot re-seeds its interval off the live BPM, so it reads the same fake bank as the tiles.
  useSpeedMasterBpm: (uuid: string | null) =>
    liveMasters.find((m) => m.uuid === uuid)?.bpm ?? null,
}))
// The socket's readyState, driven directly: the real hook is an RTK Query subscription and this
// suite deliberately mounts the bar without a Provider.
const deskConnected = { current: true }
vi.mock('../store/status', () => ({
  useIsDeskConnected: () => deskConnected.current,
}))

import { SpeedMasters, SpeedMastersChip, selectedMasterStore } from './SpeedMasters'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  window.localStorage.clear()
  // The selected master is a `createSyncStore` singleton, so its value is cached at module level
  // and outlives `localStorage.clear()` — without this reset the first test to pick a master
  // would leak that choice into every test after it.
  selectedMasterStore.reset()
  deskConnected.current = true
})

function master(index: number, overrides: Partial<SpeedMasterLiveState> = {}): SpeedMasterLiveState {
  return {
    uuid: `aaaaaaaa-0000-0000-0000-00000000000${index}`,
    index,
    name: `Master ${index}`,
    bpm: 120,
    isRunning: true,
    source: 'MANUAL',
    ...overrides,
  }
}

/** Open the chip's popover and hand back its trigger. */
function openChip() {
  render(<SpeedMastersChip />)
  fireEvent.click(screen.getByLabelText('Speed masters'))
}

describe('SpeedMasters — the tile arms', () => {
  it('renders master 1 as a tile of its own', () => {
    // This inverts the old assertion. The strip used to render *nothing* for an M1-only bank,
    // because M1 was the ShowBar's separate BPM tile — the split this consolidation removed.
    liveMasters = [master(1)]
    render(<SpeedMasters />)
    expect(screen.getAllByText(/M1/).length).toBeGreaterThan(0)
    expect(screen.getAllByLabelText('Tap tempo for master 1').length).toBeGreaterThan(0)
  })

  it('gives a three-master bank a higher bar to clear, rather than never tiling', () => {
    // The threshold is about affordability, not a magic cap: three tiles are fine on a wide desk,
    // so the count picks the width at which they appear.
    liveMasters = [master(1), master(2), master(3)]
    const { container } = render(<SpeedMasters />)
    expect(container.innerHTML).toContain('@[1300px]:flex')
    expect(container.innerHTML).toContain('@[1300px]:hidden')

    cleanup()
    liveMasters = [master(1), master(2), master(3), master(4)]
    const four = render(<SpeedMasters />).container
    expect(four.innerHTML).toContain('@[1600px]:flex')
  })

  it('renders a tile per master while the bank is small enough to tile', () => {
    liveMasters = [master(1), master(2, { bpm: 60 })]
    render(<SpeedMasters />)
    // jsdom applies no CSS, so every arm is queryable; the names only *display* at ≥1000px.
    expect(screen.getAllByText(/Master 1/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Master 2/).length).toBeGreaterThan(0)
    expect(screen.getAllByText('60').length).toBeGreaterThan(0)
  })

  it('stops tiling a bank too big to afford it, at any width', () => {
    // Width alone was the wrong test. A named tile is ~150px, so at 1000px a four-master bank ate
    // ~600px and the live-state block — the one `flex-1` item, and the one an operator reads
    // mid-show — was left with nothing and clipped its cue numbers.
    liveMasters = [master(1), master(2), master(3), master(4), master(5)]
    render(<SpeedMasters />)

    // Exactly one tile — the rail's selected master. Five tiles would carry five TAPs. (The chip's
    // own TAPs live behind its popover, which is closed.)
    expect(screen.getAllByLabelText(/^Tap tempo for master/)).toHaveLength(1)
    // No tiled arm in the DOM at all, not merely hidden — jsdom applies no CSS, so a hidden arm
    // would still be queryable and this assertion would not hold.
    expect(screen.queryByText(/Master 5/)).toBeNull()
    // …but every master is still REACHABLE, which is what consolidating rather than dropping means.
    for (const index of [1, 2, 3, 4]) {
      expect(screen.getByRole('button', { name: `M${index}` })).toBeTruthy()
    }
  })

  it('keeps two mounted hosts on the same selected master', () => {
    // The regression this guards is not cosmetic. `PD-SPEED-OVERLAY` gave this component a second
    // host — the overview panel, mounted by `Layout` on every route — so on Show, the Prompt Book
    // and Busk both hosts are on screen together. The selected master IS the tile, so it is that
    // master's TAP and click-to-edit BPM that render: two hosts disagreeing means a press in one
    // retunes a master the operator is reading in the other, with nothing on screen saying so.
    //
    // It was `usePersistentState`, which reads its key once in a `useState` initialiser and has no
    // storage listener — so the second host kept its mount-time snapshot forever. A
    // `createSyncStore` singleton is what makes both hosts one reader.
    // Five masters, so neither host renders a tiled arm and the only tile on screen in each is
    // the rail's. With a small bank both arms are in the DOM at once (deliberately), and every
    // master would have a tile regardless of what the rail is pointing at — which would make the
    // assertions below pass without saying anything.
    liveMasters = [1, 2, 3, 4, 5].map((i) => master(i))
    render(
      <>
        <SpeedMasters />
        <SpeedMasters room="dedicated" />
      </>,
    )

    // Both hosts start on M1 and both offer the rail.
    expect(screen.getAllByRole('button', { name: 'M1', pressed: true })).toHaveLength(2)

    // Move one host's rail to M2; the other must follow.
    fireEvent.click(screen.getAllByRole('button', { name: 'M2' })[0])

    expect(screen.getAllByRole('button', { name: 'M2', pressed: true })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'M1', pressed: true })).toBeNull()
    // The tile that renders is the selected one, in both — which is the half that makes the
    // disagreement dangerous rather than untidy.
    expect(screen.getAllByLabelText('Tap tempo for master 2')).toHaveLength(2)
    expect(screen.queryByLabelText('Tap tempo for master 1')).toBeNull()
    expect(screen.getAllByLabelText(/^Tap tempo for master/)).toHaveLength(2)
  })

  it('tiles a four-master bank far earlier in a dedicated row than in a shared one', () => {
    // The thresholds are not about whether the tiles fit: measured in the panel, four masters are
    // 464px of tiles at a narrow container and 711px at a wide one, and the shared ladder made
    // them clear 1600px. That 1600 is what they would cost the ShowBar's live-state block, which
    // a panel owning its own row does not have. Same arms, same components, different width.
    //
    // Asserted on the class strings because jsdom applies no CSS and resolves no container query,
    // so the width at which an arm turns on is only observable as the literal Tailwind class —
    // which is also the thing that has to stay a whole literal for Tailwind to emit it at all.
    liveMasters = [master(1), master(2), master(3), master(4)]

    const dedicated = render(<SpeedMasters room="dedicated" />).container.innerHTML
    expect(dedicated).toContain('@[620px]:flex')
    expect(dedicated).not.toContain('@[1600px]:flex')

    cleanup()
    const shared = render(<SpeedMasters />).container.innerHTML
    expect(shared).toContain('@[1600px]:flex')
    expect(shared).not.toContain('@[620px]:flex')
  })

  it('keeps the 5+ ceiling in a dedicated row, because that one is not about width', () => {
    // Every other threshold moves with the room. This one does not: the rail reaches every master,
    // so consolidating loses nothing, and a bank that big is one you manage on its own page.
    liveMasters = [master(1), master(2), master(3), master(4), master(5)]
    render(<SpeedMasters room="dedicated" />)

    expect(screen.getAllByLabelText(/^Tap tempo for master/)).toHaveLength(1)
    expect(screen.queryByText(/Master 5/)).toBeNull()
  })

  it('the railed tile defaults to master 1', () => {
    // The persisted key was versioned precisely so desks holding the old default of `2` — still a
    // valid index — land on M1 rather than quietly defeating the rail starting there.
    liveMasters = [master(1, { bpm: 120 }), master(2, { bpm: 60 })]
    window.localStorage.setItem('showbar.speedMaster.selected', '2')
    render(<SpeedMasters />)
    expect(screen.getByRole('button', { name: 'M1', pressed: true })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'M2', pressed: false })).toBeTruthy()
  })

  it('master 1 is click-to-edit, like every other master', () => {
    // It used to be a read-only span in the ShowBar, which made the global tempo the one master
    // you could not type at while standing at the desk.
    liveMasters = [master(1, { bpm: 120 })]
    render(<SpeedMasters />)

    fireEvent.click(screen.getAllByText('120')[0])
    const input = screen.getByLabelText('Master 1 BPM')
    fireEvent.change(input, { target: { value: '128' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    // A null uuid *is* master 1 on the wire — the same message the old read-only TAP sent.
    expect(setSpeedMasterBpm).toHaveBeenCalledWith(liveMasters[0].uuid, 128)
  })

  it('shows an em dash rather than a fabricated tempo before the first frame', () => {
    // The ShowBar used to read `fxState.bpm`, which defaulted to a hardcoded 120, so for a frame
    // or two at boot the desk stated a tempo nobody had set. (That field is gone now — the FX
    // panel's readout was the last consumer and moved here too.)
    liveMasters = []
    render(<SpeedMasters />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)

    // TAP still works with no uuid yet; typing does not, because there is nothing to seed a draft.
    fireEvent.click(screen.getAllByLabelText('Tap tempo for master 1')[0])
    expect(tapSpeedMaster).toHaveBeenCalledWith(null)
  })
})

describe('SpeedMastersChip — the phone arm', () => {
  it('counts the masters it is standing in for', () => {
    // The one thing the old phone ladder could not say: masters exist and you cannot see them.
    liveMasters = [master(1), master(2), master(3)]
    render(<SpeedMastersChip />)
    expect(screen.getByText('+2')).toBeTruthy()
  })

  it('taps the right master from the popover', () => {
    liveMasters = [master(1), master(2)]
    openChip()
    fireEvent.click(screen.getByLabelText('Tap tempo for master 2'))
    expect(tapSpeedMaster).toHaveBeenCalledWith(liveMasters[1].uuid)
  })

  it('stops taking taps while the desk is unreachable, rather than dropping them', () => {
    // TAP is a `speedMasters.tap` frame and the number beside it is the server's answer, so
    // against a dead socket the operator taps out a bar and nothing moves.
    liveMasters = [master(1), master(2)]
    deskConnected.current = false
    openChip()
    const tap = screen.getByLabelText('Tap tempo for master 2')
    expect(tap).toBeDisabled()
    fireEvent.click(tap)
    expect(tapSpeedMaster).not.toHaveBeenCalled()
  })

  it('commits a typed tempo on Enter and sends it to the right master', () => {
    liveMasters = [master(1), master(2, { bpm: 60 })]
    openChip()

    fireEvent.click(screen.getByText('60'))
    const input = screen.getByLabelText('Master 2 BPM')
    fireEvent.change(input, { target: { value: '96' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(setSpeedMasterBpm).toHaveBeenCalledWith(liveMasters[1].uuid, 96)
  })

  it('ignores a server push while the operator is typing', () => {
    liveMasters = [master(1), master(2, { bpm: 60 })]
    openChip()

    fireEvent.click(screen.getByText('60'))
    fireEvent.change(screen.getByLabelText('Master 2 BPM'), { target: { value: '9' } })

    // Another surface taps the master mid-edit; the field must keep the draft.
    liveMasters = [master(1), master(2, { bpm: 87, source: 'TAP' })]
    fireEvent.click(screen.getByLabelText('Tap tempo for master 1'))
    expect((screen.getByLabelText('Master 2 BPM') as HTMLInputElement).value).toBe('9')
  })

  it('escape reverts to the live value without sending anything', () => {
    liveMasters = [master(1), master(2, { bpm: 60 })]
    openChip()

    fireEvent.click(screen.getByText('60'))
    const input = screen.getByLabelText('Master 2 BPM')
    fireEvent.change(input, { target: { value: '999' } })
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
    // Still open, still showing the live value: Escape undoes the typo, it does not also throw
    // away the popover the operator is working in.
    expect(screen.getByText('60')).toBeTruthy()
  })

  it('a garbage draft commits nothing', () => {
    liveMasters = [master(1), master(2, { bpm: 60 })]
    openChip()

    fireEvent.click(screen.getByText('60'))
    const input = screen.getByLabelText('Master 2 BPM')
    fireEvent.change(input, { target: { value: 'fast' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })
})

describe('a master that follows master 1', () => {
  it('trades TAP for its ratio and stops offering the tempo draft', () => {
    // The server refuses both writes on a follower (SPEED_MASTER_FOLLOWER) because its tempo is
    // derived from master 1. The refusal stays as a backstop for writers with no affordance to
    // remove — a MIDI surface, a stale tab — but nothing here should be a button that can't work.
    liveMasters = [master(1), master(2, { bpm: 60, followNum: 1, followDen: 2 })]
    render(<SpeedMasters />)

    expect(screen.queryByLabelText('Tap tempo for master 2')).not.toBeInTheDocument()
    expect(
      screen.getByLabelText('Master 2 follows Master 1 at 1/2'),
    ).toHaveTextContent('½')

    // Master 1 keeps both — it is what the follower derives from. (Every width arm is mounted
    // at once, and master 1 is the railed arm's default selection, so it appears more than once.)
    expect(screen.getAllByLabelText('Tap tempo for master 1').length).toBeGreaterThan(0)
  })

  it('does not open a BPM draft when its readout is clicked', () => {
    liveMasters = [master(1), master(2, { bpm: 60, followNum: 1, followDen: 2 })]
    render(<SpeedMasters />)

    fireEvent.click(screen.getByText('60'))

    expect(screen.queryByLabelText('Master 2 BPM')).not.toBeInTheDocument()
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  it('names the master it actually follows, in the label as well as the text', () => {
    // The accessible name is the half that kept saying "Master 1" after the visible badge
    // learned about follow targets — so a screen reader was told the wrong leader on every
    // surface an operator can reach a follower from.
    liveMasters = [
      master(1),
      master(2, { name: 'Movement', bpm: 90 }),
      master(3, {
        name: 'Crawl',
        bpm: 45,
        followNum: 1,
        followDen: 2,
        followTargetUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
      }),
    ]
    render(<SpeedMasters />)

    expect(screen.getByLabelText('Master 3 follows Movement at 1/2')).toHaveTextContent('½')
  })

  it('applies the same rule in the phone popover', () => {
    // Three surfaces offer tap + click-to-type; a follower that is inert on the ShowBar and
    // tappable on a phone is the same bug in a narrower window.
    liveMasters = [master(1), master(2, { bpm: 60, followNum: 1, followDen: 2 })]
    openChip()

    expect(screen.queryByLabelText('Tap tempo for master 2')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Master 2 follows Master 1 at 1/2')).toBeInTheDocument()
  })
})
