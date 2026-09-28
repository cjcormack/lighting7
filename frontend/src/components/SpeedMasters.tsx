import { memo, useRef, useSyncExternalStore } from 'react'
import { Link, useParams } from 'react-router'
import { ChevronDown, Settings2 } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { BeatIndicator } from './BeatIndicator'
import { formatBpm, useBpmDraft } from '../hooks/useBpmDraft'
import { createSyncStore } from '../lib/syncStore'
import { setSpeedMasterBpm, tapSpeedMaster, useSpeedMasterLiveQuery } from '../store/speedMasters'
import {
  followRatioOf,
  followerTempoLockedReason,
  followTargetOf,
  leaderNameOf,
  formatFollowRatio,
} from '../lib/speedMasterModel'
import { useIsDeskConnected } from '../store/status'
import { DESK_OFFLINE_LABEL } from '../api/wsGesture'
import type { SpeedMasterLiveState } from '../api/speedMastersWsApi'

/**
 * A master as this file draws it: the live shape, but with `bpm` widened so the pre-boot
 * placeholder can say "—" rather than a fabricated number.
 */
type TileMaster = Omit<SpeedMasterLiveState, 'bpm'> & { bpm: number | null }

/**
 * How much of its host's row is this component's to spend.
 *
 * `shared` is the ShowBar, where blackout, the programmer chip, BACK and GO are all `shrink-0`
 * and the live-state block is the only `flex-1` item — so every tile comes straight out of the
 * one thing an operator reads mid-show. `dedicated` is the overview panel, which owns its row
 * outright and is competing with nothing.
 *
 * This is **not** a per-host arm, which is the thing `SpeedMasters`' docblock refuses. The arms,
 * the components and the ladder's shape are identical in both; what differs is the width at which
 * each step is affordable, and that is a fact about the *row*, which only the host can state.
 */
export type SpeedMasterRoom = 'shared' | 'dedicated'

interface ArmLadder {
  /** Chip below this width, railed tile from it up. */
  rail: { show: string; hide: string }
  /** Tiled from this width up, by master count. Absent means never tile — see the ceiling below. */
  tiled: Record<number, { show: string; hide: string }>
  /**
   * The manage-page shortcut inside the railed arm, which only renders for a bank too big to tile
   * — the tiled arm carries its own. It is width-gated because on a shared row it is the least
   * important thing there; on a dedicated row almost nothing is competing, so it appears far
   * earlier rather than leaving a five-master panel with no route to the bank page at all.
   */
  manage: string
}

/**
 * The width ladders, per room.
 *
 * Width alone was the wrong test for the shared row. A named tile runs ~150px, so at 1000px a
 * four-master bank ate ~600px of the bar and left its cue numbers clipping. A container query
 * cannot see how many masters there are, so the count picks the threshold and the query applies it.
 *
 * The dedicated numbers are measured rather than scaled down from those: in a panel the railed arm
 * is a flat 168px whatever the width, and the tiled arm for four masters is 464px up to a ~700px
 * container and 533px from there to ~1100, where the tile's own queries let it grow to 711px. Each
 * threshold is therefore sized against the tile's width *at that threshold*, not against its widest
 * form — a flat per-tile figure would have been wrong at both ends — with roughly 15% headroom for
 * longer master names than this rig's.
 *
 * The four-master step is 620 and not, say, 800 because 788px is a real panel: that is what a
 * landscape phone at 852 leaves after the rail, and 533px of tiles were being sent to the pill rail
 * to save 255px of empty row.
 *
 * **The 5+ ceiling holds in both**, and it is not a width judgment: the rail reaches every master,
 * so consolidating loses nothing, and a bank that big is one you manage on its own page rather
 * than read off a strip. It is the only rule here that does not move with the room.
 *
 * **No dedicated threshold may equal its `rail` width.** The rail's show class and the chip's
 * hide class are a min-width pair at the same breakpoint on the same element, at equal
 * specificity, so which wins would be decided by Tailwind's own utility sort rather than by
 * anything stated here. The 1-master dedicated step is 300px against a 240px rail for that reason.
 *
 * (And note the classes above must be the only bracket-syntax spellings in this file: Tailwind
 * scans comments too, so a placeholder written in that shape in prose is emitted as a real rule —
 * `min-width:Npx` is not a length, and the build fails in `lightningcss` rather than here.)
 *
 * Both halves of each pair live on one line because they must stay exact complements, and Tailwind
 * only sees whole literal class strings, so neither can be computed from the other.
 */
const ARMS: Record<SpeedMasterRoom, ArmLadder> = {
  shared: {
    rail: { show: 'hidden @[440px]:flex', hide: '@[440px]:hidden' },
    tiled: {
      1: { show: 'hidden @[1000px]:flex', hide: '@[1000px]:hidden' },
      2: { show: 'hidden @[1000px]:flex', hide: '@[1000px]:hidden' },
      3: { show: 'hidden @[1300px]:flex', hide: '@[1300px]:hidden' },
      4: { show: 'hidden @[1600px]:flex', hide: '@[1600px]:hidden' },
    },
    manage: 'hidden @[1000px]:flex',
  },
  dedicated: {
    rail: { show: 'hidden @[240px]:flex', hide: '@[240px]:hidden' },
    tiled: {
      1: { show: 'hidden @[300px]:flex', hide: '@[300px]:hidden' },
      2: { show: 'hidden @[360px]:flex', hide: '@[360px]:hidden' },
      3: { show: 'hidden @[500px]:flex', hide: '@[500px]:hidden' },
      4: { show: 'hidden @[620px]:flex', hide: '@[620px]:hidden' },
    },
    manage: 'hidden @[440px]:flex',
  },
}

/**
 * Which master the railed arm is showing.
 *
 * A `createSyncStore` singleton rather than `usePersistentState`, and the reason is the same one
 * `useVisSource` gives for the stage vis source: two surfaces read it — the ShowBar and the
 * globally-mounted overview panel — and `usePersistentState` reads its key once in a `useState`
 * initialiser with no storage listener, so two mounted instances hold two snapshots and drift the
 * moment one writes. Here that drift is not cosmetic: the selected master *is* the tile, so its
 * TAP and its click-to-edit BPM are the controls on screen. Two hosts disagreeing means a press in
 * one of them retunes a master the operator is reading in the other, with nothing saying so.
 *
 * **The key is unchanged**, so desks keep the master they were on: both paths decode with the same
 * `JSON.parse` over the same raw string. It was versioned away from `showbar.speedMaster.selected`
 * once, because existing desks had `2` stored — still a *valid* index — so reusing that key would
 * have silently landed them on M2 and defeated the rail now starting at M1. That reasoning applies
 * to the name, not to the storage mechanism, so the `.v2` name carries across the move.
 *
 * Exported for tests: the cached value is module-level, so it outlives `localStorage.clear()` in
 * a suite's `afterEach` and has to be `reset()` alongside it.
 */
export const selectedMasterStore = createSyncStore<number>({
  key: 'showbar.speedMaster.selected.v2',
  fallback: 1,
  // Narrowed rather than cast: an index has to be a positive integer, and a value written by a
  // later build (or typed into devtools) must not reach `masters.find` as a string or a float.
  parse: (parsed) =>
    typeof parsed === 'number' && Number.isInteger(parsed) && parsed >= 1 ? parsed : 1,
})

function useSelectedMasterIndex(): number {
  return useSyncExternalStore(
    selectedMasterStore.subscribe,
    selectedMasterStore.getSnapshot,
    selectedMasterStore.getServerSnapshot,
  )
}

interface SpeedMastersProps {
  /**
   * How much of the host's row belongs to this component. Defaults to `shared` so the ShowBar —
   * which had the only mount for this component's whole life — needs no change and behaves exactly
   * as before.
   */
  room?: SpeedMasterRoom
}

/**
 * What to draw before the first `speedMasters` frame arrives.
 *
 * The ShowBar used to read `fxState.bpm`, which defaults to a hardcoded 120 — so for a frame or
 * two at boot the desk stated a tempo nobody had set. `getState()` is honestly empty instead, and
 * a null bpm renders "—". TAP still works (a null uuid *is* master 1 on the wire); click-to-edit
 * does not, because there is no current value to seed the draft from.
 */
const PENDING_MASTER_1: TileMaster = {
  uuid: null,
  index: 1,
  name: '',
  bpm: null,
  isRunning: false,
  source: 'MANUAL',
}

/**
 * The desk's tempo surface: every speed master, **including master 1**.
 *
 * Master 1 used to be the ShowBar's own BPM tile and this component rendered only 2..N. That split
 * was the width problem: two thresholds fired at 560px in opposite directions, so between 560 and
 * 900px the M1 tile, the strip's single tile and the transport together left the live-state block
 * nothing, and its cue numbers spilled. One tile that speaks for the whole bank frees that room and
 * removes the split brain, at the cost of nothing — master 1 is still what a null uuid means on a
 * tempo write, so TAP and setBpm are unchanged.
 *
 * **It has two hosts** since `PD-SPEED-OVERLAY`: the `ShowBar`, and `SpeedMasterOverviewPanel` —
 * the summoned panel that reaches the bank from a view with no bar, the programmer included. The
 * panel mounts this component whole rather than drawing a readout of its own, which is the entire
 * reason it is allowed to exist; see that file, and the Effects Overview paragraph in
 * `overviewPanels.tsx` for what the last near-copy of a speed surface cost.
 *
 * **A host states how much of its row is this component's, and nothing more** — `room`, which
 * picks a width ladder out of `ARMS`. That is the one thing a host is allowed to say, and it is
 * said because only the host can know it: the bar's row carries the transport and the live-state
 * block, the panel's row carries nothing else at all, so the same tile is affordable at very
 * different widths. What a host must never gain is an *arm* of its own — a readout, a tile or a
 * ladder shape only it has — because that is how the split brain comes back, and it is what
 * `overviewPanels.tsx` records the cost of.
 *
 * Reads its own state and takes no data props. Three arms, chosen by **the host's** `@container`
 * width *and* by how many masters there are — the bar declares that container on itself, the
 * panel declares one on its own body:
 *
 *  - **wide enough for this bank** — one tile per master, each named, plus the manage shortcut.
 *    "Wide enough" comes from `ARMS[room].tiled`, because it depends on the count; five or more
 *    never qualifies, in either room.
 *  - **narrower** — one railed tile: a pill per master picks which one it shows.
 *  - **narrowest** — `SpeedMastersChip`, a single readout that opens every master in a popover.
 *    Where those last two boundaries fall is `ARMS[room].rail`.
 *
 * Every arm that can render is in the DOM at once. That is deliberate and cheap: `BeatIndicator`
 * subscribables are shared per master, so the total is N however many arms are mounted, and an
 * unopened Radix popover mounts only its trigger.
 *
 * Memoized because its host may be the ShowBar, which re-renders ~10×/s while a cue fades to run
 * its FADING countdown — the masters have nothing to say about a fade, and this subtree is the
 * bar's biggest. `room` is a string literal at both call sites, so the memo still holds.
 */
export const SpeedMasters = memo(function SpeedMasters({ room = 'shared' }: SpeedMastersProps) {
  const { data: live } = useSpeedMasterLiveQuery()
  const selectedIndex = useSelectedMasterIndex()

  const masters: TileMaster[] = live?.length ? live : [PENDING_MASTER_1]
  const selected = masters.find((m) => m.index === selectedIndex) ?? masters[0]

  const ladder = ARMS[room]
  const tiledArm = ladder.tiled[masters.length]

  return (
    <>
      {/* A tile each — only while the bank is small enough to be worth the room. */}
      {tiledArm && (
        <div className={cn('items-stretch gap-2 shrink-0', tiledArm.show)}>
          {masters.map((m) => (
            <div
              key={m.uuid ?? m.index}
              className="flex items-stretch rounded-md border bg-card overflow-hidden"
            >
              <MasterTile master={m} bank={masters} />
            </div>
          ))}
          <ManageMastersLink />
        </div>
      )}

      {/* One tile with a rail to pick which master it shows. Takes over from the room's rail width
          up — and all the way up, on a bank too big to tile. Nothing is lost either way: the rail
          reaches every master, which is the whole point of consolidating rather than dropping. */}
      <div className={cn('items-stretch gap-2 shrink-0', ladder.rail.show, tiledArm?.hide)}>
        <div className="flex items-stretch rounded-md border bg-card overflow-hidden">
          <MasterRail
            masters={masters}
            selected={selected}
            onSelect={selectedMasterStore.set}
          />
          <MasterTile master={selected} bank={masters} />
        </div>
        {/* A bank too big to tile never renders the arm that carries this, and would otherwise
            lose its only route to the bank page. */}
        {!tiledArm && (
          <span className={ladder.manage}>
            <ManageMastersLink />
          </span>
        )}
      </div>

      {/* Below the room's rail width — one chip; the popover carries the whole bank. */}
      <SpeedMastersChip className={ladder.rail.hide} compact />
    </>
  )
})

/**
 * The phone-width tempo control: master 1's readout, a count of the masters it is standing in for,
 * and a popover holding every master with its own TAP.
 *
 * Exported because two surfaces have no `@container` ancestor to query and so must ask for this arm
 * by name — `RunMobile` and the Prompt Book's cue-stack drawer. Both previously hand-rolled a bpm
 * readout beside the strip, which meant neither could see any master but 1; this is how they gain
 * the rest of the bank.
 */
export function SpeedMastersChip({
  className,
  compact = false,
}: {
  className?: string
  /**
   * The ShowBar's bottom rung, where the whole bar is one 56px row (space plan D8): tighter
   * padding, no `+n`, and no chevron.
   *
   * Both of the things it drops are *hints* rather than information — that there are other
   * masters, and that this opens something — and between them they are 30px of a live block that
   * has about 70 to say `Q4 → Q5` in. The popover still lists every master and the whole chip is
   * still the button, so nothing here becomes unreachable; it becomes unadvertised, at the one
   * width where nothing else fits either. Default false, so the three other callers
   * (`RunMobile`, `CueStackPanel`, and this file's own `@[440px]:hidden` arm) are unchanged.
   */
  compact?: boolean
}) {
  const { data: live } = useSpeedMasterLiveQuery()
  const masters: TileMaster[] = live?.length ? live : [PENDING_MASTER_1]
  const primary = masters.find((m) => m.index === 1) ?? masters[0]
  const extra = masters.length - 1
  const contentRef = useRef<HTMLDivElement>(null)

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Speed masters"
          className={cn(
            'flex shrink-0 items-center gap-1.5 rounded-md border bg-card py-1 transition-colors hover:bg-muted/40',
            compact ? 'px-1.5' : 'px-2',
            className,
          )}
        >
          <BeatIndicator master={asLive(primary)} className="size-1.5 shrink-0" />
          <span className="font-mono text-[13px] font-bold leading-none tabular-nums">
            {primary.bpm == null ? '—' : formatBpm(primary.bpm)}
          </span>
          {/* The one thing today's phone ladder cannot say: there ARE other masters. */}
          {!compact && extra > 0 && (
            <span className="text-[10px] text-muted-foreground">+{extra}</span>
          )}
          {!compact && <ChevronDown className="size-3 shrink-0 text-muted-foreground" />}
        </button>
      </PopoverTrigger>
      <PopoverContent
        ref={contentRef}
        align="start"
        className="w-[230px] p-2"
        onEscapeKeyDown={(e) => {
          // Escape inside a BPM field reverts that draft (`useBpmDraft`), and must not ALSO throw
          // away the popover the operator is working in. Radix listens on `document` in the capture
          // phase, so stopping propagation from the input cannot reach it — preventing the default
          // here is the documented way, and the event still goes on to revert the draft.
          const active = document.activeElement
          if (active instanceof HTMLInputElement && contentRef.current?.contains(active)) {
            e.preventDefault()
          }
        }}
      >
        <p className="px-1 pb-1.5 text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
          Speed masters
        </p>
        <div className="flex flex-col gap-px">
          {masters.map((m) => (
            <MasterRow key={m.uuid ?? m.index} master={m} bank={masters} />
          ))}
        </div>
        <ManageMastersLink variant="row" />
      </PopoverContent>
    </Popover>
  )
}

/** Which master the single tile shows. Pointless with a one-master bank, so it hides itself. */
function MasterRail({
  masters,
  selected,
  onSelect,
}: {
  masters: TileMaster[]
  selected: TileMaster
  onSelect: (index: number) => void
}) {
  if (masters.length < 2) return null

  return (
    // Two rows, flowing into columns: a vertical list of pills outgrows the tile's height at four
    // masters and pushes the whole bar taller. This caps the height and spends width instead, which
    // is what the bar has more of.
    <div className="grid grid-flow-col grid-rows-2 content-center gap-px border-r bg-muted/30 px-1">
      {masters.map((m) => (
        <button
          key={m.uuid ?? m.index}
          type="button"
          onClick={() => onSelect(m.index)}
          aria-pressed={m.index === selected.index}
          className={cn(
            'rounded px-1 text-[9px] font-bold leading-tight',
            m.index === selected.index
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          M{m.index}
        </button>
      ))}
    </div>
  )
}

/**
 * Shortcut to the Speed Masters page, for adding or renaming a master mid-show without hunting
 * through the sidebar.
 *
 * Renders nothing off a project-scoped route. `variant="row"` is the popover's full-width footer —
 * at phone widths there is otherwise no route to the bank page at all — and is exported for the
 * busk view's speed rail, whose footer is the same bordered row saying the same thing.
 */
export function ManageMastersLink({ variant = 'icon' }: { variant?: 'icon' | 'row' }) {
  const { projectId } = useParams<{ projectId: string }>()
  if (projectId == null) return null

  const to = `/projects/${projectId}/speed-masters`
  if (variant === 'row') {
    return (
      <Link
        to={to}
        className="mt-1.5 flex items-center gap-1.5 rounded border-t px-1 pt-1.5 text-[11px] text-muted-foreground transition-colors hover:text-foreground"
      >
        <Settings2 className="size-3" />
        Manage speed masters
      </Link>
    )
  }

  return (
    <Link
      to={to}
      title="Manage speed masters"
      aria-label="Manage speed masters"
      className="flex items-center rounded-md border bg-card px-2 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
    >
      <Settings2 className="size-3.5" />
    </Link>
  )
}

/**
 * One master's readout + TAP. The BPM value is a click-to-edit input with dirty tracking: while the
 * operator is typing, server pushes are ignored so a tap from another surface can't yank the field
 * out from under them; Enter or blur commits, Escape reverts.
 *
 * This edits the **live** tempo (`speedMasters.setBpm`), never the stored default — that stays
 * editable only in the detail sheet, where it can be labelled as such. Master 1 is edited here on
 * exactly the same terms as the rest: it used to be a read-only span in the ShowBar, which made the
 * global tempo the one master you could not type at while standing at the desk.
 */
function MasterTile({ master, bank }: { master: TileMaster; bank: readonly TileMaster[] }) {
  const { editing, draft, start, change, commit, onKeyDown } = useBpmDraft(master.uuid, (bpm) =>
    setSpeedMasterBpm(master.uuid, bpm),
  )
  // Both a typed tempo and a TAP are `speedMasters.*` WS writes, and the readout is the server's
  // — so against a dead socket the operator taps a bar's worth of beats and the number never
  // moves. The tile keeps *showing* the tempo; only the two writes stop.
  const connected = useIsDeskConnected()
  // A follower's tempo is derived from its leader, and the server refuses both writes on it
  // (SPEED_MASTER_FOLLOWER). So the tile trades TAP for the ratio and stops offering the draft
  // — the refusal still exists as a backstop for writers with no affordance to remove (a MIDI
  // surface, a stale tab), but nothing here should be a button that cannot work.
  const follow = followRatioOf(master)
  // Named once and used by both the tooltip and the badge's accessible name: a screen reader
  // reading "follows Master 1" off a follower of M2 is the same wrong answer the visible label
  // stopped giving when the bank arrived here.
  const leaderName = leaderNameOf(bank, followTargetOf(master))
  const lockedReason = follow
    ? followerTempoLockedReason(
        master.name || `Master ${master.index}`,
        follow.num,
        follow.den,
        leaderName,
      )
    : null

  return (
    <>
      <div className="flex flex-col justify-start gap-px px-3 py-1.5">
        <span className="flex items-center gap-1 truncate max-w-[18ch] text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
          <BeatIndicator master={asLive(master)} className="size-1.5 shrink-0" />
          M{master.index}
          {/* Only the ≥1000px arm is on screen above 1000, so this one span serves both the wide
              tile's "M1 · BPM" and the railed tile's bare "M1" without a prop or a branch. */}
          {master.name && <span className="hidden @[1000px]:inline"> · {master.name}</span>}
          {master.source === 'TAP' && !editing && ' · tap'}
        </span>
        {editing ? (
          <input
            autoFocus
            inputMode="decimal"
            value={draft}
            onChange={(e) => change(e.target.value)}
            onBlur={commit}
            onKeyDown={onKeyDown}
            aria-label={`Master ${master.index} BPM`}
            className="w-[5ch] border-b border-primary bg-transparent font-mono text-lg font-bold leading-none text-foreground outline-none @max-[700px]:text-[15px]"
          />
        ) : (
          <button
            type="button"
            disabled={master.bpm == null || !connected || lockedReason != null}
            onClick={() => master.bpm != null && start(master.bpm)}
            title={
              lockedReason ??
              (connected ? `Master ${master.index} — click to type a tempo` : DESK_OFFLINE_LABEL)
            }
            className="text-left font-mono text-lg font-bold leading-none tabular-nums text-foreground transition-colors hover:text-primary disabled:hover:text-foreground @max-[700px]:text-[15px]"
          >
            {master.bpm == null ? '—' : formatBpm(master.bpm)}
          </button>
        )}
      </div>
      {/* Same cell, same borders — a follower swaps TAP for what it is following at, so linking
          a master never reflows the bar. */}
      {follow ? (
        <span
          title={lockedReason ?? undefined}
          aria-label={`Master ${master.index} follows ${leaderName} at ${follow.num}/${follow.den}`}
          className="flex items-center justify-center border-l px-3 text-xs font-bold tabular-nums text-muted-foreground @max-[700px]:px-2 @max-[700px]:text-[11px]"
        >
          {formatFollowRatio(follow.num, follow.den)}
        </span>
      ) : (
        <button
          type="button"
          onClick={() => tapSpeedMaster(master.uuid)}
          disabled={!connected}
          title={connected ? undefined : DESK_OFFLINE_LABEL}
          aria-label={`Tap tempo for master ${master.index}`}
          className="flex items-center justify-center border-l px-3 text-xs font-bold uppercase tracking-[0.08em] transition-colors hover:bg-primary hover:text-primary-foreground active:bg-primary active:text-primary-foreground @max-[700px]:px-2 @max-[700px]:text-[11px]"
        >
          TAP
        </button>
      )}
    </>
  )
}

/**
 * One master inside the phone popover. A row rather than a reused `MasterTile` because the layouts
 * genuinely differ — horizontal, name-first — but nothing is duplicated: both go through
 * `useBpmDraft`, `tapSpeedMaster` and `BeatIndicator`, which is where the behaviour lives.
 */
function MasterRow({ master, bank }: { master: TileMaster; bank: readonly TileMaster[] }) {
  const { editing, draft, start, change, commit, onKeyDown } = useBpmDraft(master.uuid, (bpm) =>
    setSpeedMasterBpm(master.uuid, bpm),
  )
  /** Same two writes as `MasterTile` above, in the narrow arm's popover. */
  const connected = useIsDeskConnected()
  /** And the same follower rule — the phone arm must not offer a write the server refuses. */
  const follow = followRatioOf(master)
  /** Same leader lookup as the tile, feeding the same tooltip and the same accessible name. */
  const leaderName = leaderNameOf(bank, followTargetOf(master))
  const lockedReason = follow
    ? followerTempoLockedReason(
        master.name || `Master ${master.index}`,
        follow.num,
        follow.den,
        leaderName,
      )
    : null

  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded px-1.5 py-1',
        master.index === 1 && 'bg-muted',
      )}
    >
      <BeatIndicator master={asLive(master)} className="size-1.5 shrink-0" />
      <span className="min-w-0 flex-1 truncate text-[11px] font-medium">
        M{master.index}
        {master.name && ` · ${master.name}`}
      </span>
      {editing ? (
        <input
          autoFocus
          inputMode="decimal"
          value={draft}
          onChange={(e) => change(e.target.value)}
          onBlur={commit}
          onKeyDown={onKeyDown}
          aria-label={`Master ${master.index} BPM`}
          className="w-[5ch] border-b border-primary bg-transparent text-right font-mono text-sm font-bold leading-none outline-none"
        />
      ) : (
        <button
          type="button"
          disabled={master.bpm == null || !connected || lockedReason != null}
          onClick={() => master.bpm != null && start(master.bpm)}
          title={
            lockedReason ??
            (connected ? `Master ${master.index} — click to type a tempo` : DESK_OFFLINE_LABEL)
          }
          className="font-mono text-sm font-bold tabular-nums transition-colors hover:text-primary disabled:hover:text-foreground"
        >
          {master.bpm == null ? '—' : formatBpm(master.bpm)}
        </button>
      )}
      {follow ? (
        <span
          title={lockedReason ?? undefined}
          aria-label={`Master ${master.index} follows ${leaderName} at ${follow.num}/${follow.den}`}
          className="rounded border px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-muted-foreground"
        >
          {formatFollowRatio(follow.num, follow.den)}
        </span>
      ) : (
        <button
          type="button"
          onClick={() => tapSpeedMaster(master.uuid)}
          disabled={!connected}
          title={connected ? undefined : DESK_OFFLINE_LABEL}
          aria-label={`Tap tempo for master ${master.index}`}
          className="rounded border px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-[0.08em] transition-colors hover:bg-primary hover:text-primary-foreground"
        >
          TAP
        </button>
      )}
    </div>
  )
}

/**
 * `BeatIndicator` wants the live shape. The only difference is the pre-boot null bpm, which it
 * never reads — it resolves its stream from `index`/`uuid` alone.
 */
function asLive(master: TileMaster): SpeedMasterLiveState {
  return { ...master, bpm: master.bpm ?? 0 }
}
