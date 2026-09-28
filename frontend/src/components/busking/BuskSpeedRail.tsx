import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { Link2, SlidersHorizontal } from 'lucide-react'
import { cn } from '@/lib/utils'
import { BeatIndicator } from '@/components/BeatIndicator'
import { ManageMastersLink } from '@/components/SpeedMasters'
import { SpeedMasterDetailSheet } from '@/components/speedMasters/SpeedMasterDetailSheet'
import { formatBpm, useBpmDraft } from '@/hooks/useBpmDraft'
import { useLivePush } from '@/components/editor/useLivePush'
import { useLongPress } from '@/hooks/useLongPress'
import { EDITOR_LABEL_CLASS } from '../editor/EditorLabel'
import {
  setSpeedMasterBpm,
  tapSpeedMaster,
  useSaveSpeedMasterMutation,
  useSpeedMasterListQuery,
  useSpeedMasterLiveQuery,
} from '@/store/speedMasters'
import {
  FOLLOW_RATIOS,
  SLIDE_HOLD_MS,
  SLIDE_PUSH_MS,
  bpmAtSlideFraction,
  bpmSlideFraction,
  describeFollow,
  followRatioOf,
  followerTempoLockedReason,
  followTargetOf,
  leaderLabelOf,
  leaderNameOf,
  usageLabel,
} from '@/lib/speedMasterModel'
import { useIsDeskConnected } from '@/store/status'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import type { SpeedMaster } from '@/api/speedMastersApi'
import type { SpeedMasterLiveState } from '@/api/speedMastersWsApi'

/**
 * The busk view's speed rail: the whole bank, one card each, down the right-hand side.
 *
 * The rail is what makes the routing sessions 1-2 built *visible*: every master says what it is
 * for, in a badge, on a performance surface. The rule that badge names — an effect with no explicit
 * master is stamped with the usage-matching one — has a caller again since fx-templates session 3:
 * `TemplateEditor` stamps an effect template when its effect is chosen. **Nothing on this page does
 * that**, though, so the caption still states what a badge *means* rather than promising what a
 * press here does; don't restore its old wording, which claimed the pads stamped.
 *
 * **Two queries, and each is load-bearing.** The live query supplies everything drawn — since the
 * busking-view plan's session 2 the WS state carries `usage` and the follow pair alongside the
 * running tempo, so nothing here needs the REST row to render. The list query supplies the REST row
 * itself, which live state is not: a ratio-chip PUT needs its numeric `id`, and the detail sheet
 * needs the whole thing. They are joined by uuid, the same join the Speed Masters page's
 * `SpeedMasterSheet` makes for the same reason.
 *
 * **A card is also the way into `SpeedMasterDetailSheet`**, through the sliders glyph in its title
 * row. Renaming a master, retagging its usage, setting the tempo it boots at and linking or
 * unlinking follow all live in that sheet, and until now `/speed-masters` was the only place to
 * reach it; a performance surface that shows a master's usage badge should be able to change it.
 * One sheet for the whole rail rather than one per card: only one can be open.
 *
 * **This is the fourth surface offering TAP and click-to-type**, after `MasterTile`, `MasterRow`
 * and the Speed Masters sheet's BPM cell and TAP read-out (`SpeedMasterSheet`, which replaced
 * `SpeedMasterRow` in that count), so it carries the same follower arm they do: a master deriving
 * its tempo from another is offered neither, because the server refuses both
 * (`SPEED_MASTER_FOLLOWER`) and nothing on a desk should be a button that cannot work.
 *
 * Mounted only on the desk board, by `SideSheet` — the overlay form off it carries no Speed tab. The
 * ShowBar above it already carries `SpeedMastersChip`, which reaches
 * every master in a popover; a 288px rail at phone width would be the pads' whole width.
 *
 * **It fills whatever width the sheet gives it** (2026-09-21), where it was a fixed 288px column
 * inside a sheet the operator drags to width — so the cards stopped at 288 in a 480px sheet. And
 * it draws **no heading and no caption**: the tab strip names the tab, and the sentence about
 * what a usage badge means is on each badge's own title instead. The caption's one rule — the
 * badge names the family whose effect templates default to this master, and nothing here stamps
 * one — still holds; it just is not printed at the top of every visit.
 */
export function BuskSpeedRail() {
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = Number(projectId)
  const { data: live } = useSpeedMasterLiveQuery()
  const { data: rows } = useSpeedMasterListQuery(
    { projectId: projectIdNum },
    { skip: !Number.isFinite(projectIdNum) },
  )

  // The REST row is carried for the two writes — a ratio chip's PUT needs its `id`, the detail
  // sheet needs all of it. Nothing *displayed* comes from it: every value drawn is from the live
  // frame, so a stale list cannot make the rail state a tempo, a usage or a ratio the desk is not
  // running.
  const rowByUuid = useMemo(
    () => new Map((rows ?? []).map((row) => [row.uuid, row] as const)),
    [rows],
  )

  // The open sheet is held by **id and re-resolved**, never as a captured row — the same call
  // `routes/SpeedMasters.tsx` makes, for the same two reasons. The list refetches on every
  // `speedMasters.listChanged`, so a snapshot would keep showing the pre-edit name after a rename
  // in another tab (and would compare its dirty check against it); and a master deleted elsewhere
  // would leave the sheet open on a row that no longer exists, whose Save and Delete both 404.
  const [sheetMasterId, setSheetMasterId] = useState<number | null>(null)
  const sheetMaster =
    sheetMasterId == null ? null : ((rows ?? []).find((r) => r.id === sheetMasterId) ?? null)

  return (
    <div data-busk-speed-rail className="flex min-h-0 min-w-0 flex-1 flex-col gap-2.5 overflow-y-auto border-l p-3">
      {(live ?? []).map((master) => {
        const row = master.uuid == null ? undefined : rowByUuid.get(master.uuid)
        return (
          <MasterCard
            key={master.uuid ?? master.index}
            master={master}
            bank={live ?? []}
            projectId={projectIdNum}
            row={row}
            onOpenSettings={row == null ? undefined : () => setSheetMasterId(row.id)}
          />
        )
      })}

      <div className="flex-1" />
      <ManageMastersLink variant="row" />

      <SpeedMasterDetailSheet
        open={sheetMaster != null}
        onOpenChange={(next) => !next && setSheetMasterId(null)}
        projectId={projectIdNum}
        master={sheetMaster}
      />
    </div>
  )
}

/**
 * Tempo writes for a running drag: {@link useLivePush} over the master's bpm — deduplicated on the
 * whole BPM (the travel is about 0.4 BPM a pixel, so most moves land on the last one), floored at
 * {@link SLIDE_PUSH_MS}, a deferred value held and sent when the floor lifts, and `flush` as the
 * release that bypasses both. The discipline itself lives in the hook, since the Colour tab's drag
 * needs exactly it over six bytes; what is left here is the master and one rule about it.
 *
 * **A drag on one master must not leave state that a drag on the next one reads**: the dedupe is
 * against *this* drag's own moves, and the master's tempo moves by every other route between
 * drags, so the last-sent value is forgotten whenever the card's master changes as well as at the
 * start of each drag.
 */
function useLiveTempoPush(uuid: string | null) {
  const { push, flush, reset } = useLivePush<number>((bpm) => setSpeedMasterBpm(uuid, bpm), {
    floorMs: SLIDE_PUSH_MS,
  })
  useEffect(() => {
    reset()
  }, [uuid, reset])
  return { push, flush, reset }
}

/**
 * One master, in one of three arms.
 *
 * - **Master 1** is the global tempo: the big number, a full-width TAP, and no usage badge — it is
 *   where every unmatched category lands by definition, so a badge saying so would be noise.
 * - **A follower** shows what it derives and the ratio it derives at, and trades TAP for the five
 *   ratio chips. Its tempo is not typeable either: the same refusal covers both writes.
 * - **A manual master** keeps its own TAP inline, and carries its usage badge.
 *
 * **Hold the card and it becomes a tempo fader** — the busk view's own hold-to-slide gesture, the
 * one the property pads used to carry, and the third way to set a tempo beside typing it and
 * tapping it. Each answers a different question: TAP finds a tempo you can hear, the number sets one
 * you know, and the drag *trims* one that is nearly right, which is the thing a busking operator
 * does most and had no gesture for.
 *
 * Five things about it:
 *
 * - **The whole card is the fader.** It arms after {@link SLIDE_HOLD_MS} and seeds from the point
 *   the press started at, so the tempo does not jump the moment the hold takes. The click that ends
 *   the drag is swallowed in the capture phase, before TAP, the bpm button or a ratio chip can see
 *   it — that is why the drag may cross them freely.
 * - **The travel is 60..180**, not the clock's 20..300 — `SLIDE_MIN_BPM`'s note has the reasoning,
 *   which is lighting7's own for the MIDI tempo fader. It is a control range, not a limit: the
 *   other two gestures still reach the whole clock.
 * - **It applies as it goes.** The rig moves under the drag rather than on the release, because
 *   that is what a fader is for: the tempo is judged by ear, against a show that is running. The
 *   writes are deduplicated and floored at {@link SLIDE_PUSH_MS} so a `pointermove` per frame does
 *   not become a broadcast per frame — see `useLiveTempoPush` — and the release always sends the
 *   value let go on.
 * - **A follower cannot be dragged**, for the same reason it has no TAP and no typing: its tempo
 *   comes from master 1, and the server refuses all three writes. Nor can any master while the
 *   desk is offline or its bpm field is open.
 * - **The detail sheet moved to the glyph alone.** The hold used to open it; a card cannot answer a
 *   hold two ways, and trimming a tempo is the gesture that belongs on a performance surface.
 */
function MasterCard({
  master,
  bank,
  projectId,
  row,
  onOpenSettings,
}: {
  master: SpeedMasterLiveState
  /** The live bank, so a follower's chip can name the master it actually follows. */
  bank: readonly SpeedMasterLiveState[]
  projectId: number
  /** The REST row behind this live frame, absent until the list arrives. */
  row: SpeedMaster | undefined
  /** Absent with no `row`: there would be nothing for the sheet to edit. */
  onOpenSettings: (() => void) | undefined
}) {
  const connected = useIsDeskConnected()
  const { editing, draft, start, change, commit, onKeyDown } = useBpmDraft(master.uuid, (bpm) =>
    setSpeedMasterBpm(master.uuid, bpm),
  )
  const isMaster1 = master.index === 1
  const follow = followRatioOf(master)
  const leaderTarget = followTargetOf(master)
  const lockedReason = follow
    ? followerTempoLockedReason(
        master.name || `Master ${master.index}`,
        follow.num,
        follow.den,
        leaderNameOf(bank, leaderTarget),
      )
    : null
  const usage = usageLabel(master.usage)

  const cardRef = useRef<HTMLDivElement>(null)
  /** The tempo under the finger while a drag runs; null when one is not. */
  const [slideBpm, setSlideBpm] = useState<number | null>(null)
  /**
   * The same value, written **synchronously by the pointer handlers** rather than during render.
   *
   * It must not be `slideBpmRef.current = slideBpm` at render time. A fast drag can dispatch a
   * `pointermove` and the `pointerup` that ends it inside one task, and React has not re-rendered
   * in between — so the release would read the tempo from the move *before* last and send that,
   * silently undoing the operator's final movement. Caught by a test, not by inspection.
   */
  const slideBpmRef = useRef<number | null>(null)
  const sliding = slideBpm != null

  const setSlide = (bpm: number | null) => {
    slideBpmRef.current = bpm
    setSlideBpm(bpm)
  }

  const bpmAtClientX = (clientX: number): number | null => {
    const rect = cardRef.current?.getBoundingClientRect()
    // A zero-width card means the layout has not settled; a drag from there would read as 0% and
    // slam the tempo to the bottom of the range.
    if (!rect || rect.width <= 0) return null
    return bpmAtSlideFraction((clientX - rect.left) / rect.width)
  }

  const { push, flush, reset } = useLiveTempoPush(master.uuid)

  const { handlers, consumeLongPress } = useLongPress({
    delayMs: SLIDE_HOLD_MS,
    disabled: !connected || lockedReason != null || editing,
    onLongPress: (origin) => {
      const bpm = bpmAtClientX(origin.x)
      if (bpm == null) return
      // A fresh drag dedupes against itself only — see `reset`. The tempo may have moved by TAP,
      // by the bpm field or by another tab since the last one, so nothing about that drag's last
      // write says anything about where the desk is now.
      reset()
      setSlide(bpm)
      // The arming point is already a value the operator chose by putting their finger there, so
      // it goes to the rig like any other. Waiting for the first move would make the hold feel
      // like it had done nothing.
      push(bpm)
    },
  })

  // The rest of the drag lives on the window: a fader is followed past the edge of the thing that
  // started it, and the release very often happens outside the card.
  //
  // There is no optimistic "pending" value after the release, unlike the property pads this gesture
  // is borrowed from. Those waited on a REST refetch; this is a socket write answered by a
  // `speedMasters.changed` push, and the drag has been writing all along, so by the release the
  // desk is already at (or one step from) the value being let go on.
  useEffect(() => {
    if (!sliding) return
    const onMove = (e: PointerEvent) => {
      const next = bpmAtClientX(e.clientX)
      if (next == null) return
      setSlide(next)
      push(next)
    }
    const onUp = () => {
      const value = slideBpmRef.current
      setSlide(null)
      if (value != null) flush(value)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    // `pointercancel` ends the drag as surely as a release does, and on a touchscreen desk it is
    // the *likely* ending: the rail is a scroller with no `touch-action` of its own, so a drag the
    // browser decides is a pan is taken away mid-gesture and no `pointerup` ever arrives. Without
    // this the card is left `sliding` for ever — the fill and the ring stay, the window listeners
    // stay installed, and the next pointer movement anywhere on the page writes a tempo to the desk
    // with nothing held down. Treated as a release rather than an abort: the drag has been applying
    // all along, so the last value shown is already the one the rig is at.
    window.addEventListener('pointercancel', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
    }
    // Keyed on `sliding`, the boolean — never on `slideBpm`. `bpmAtClientX` reads refs and module
    // constants only and `push`/`flush` are stable, so re-installing on every pointermove would buy
    // nothing and would tear the listeners down and back up in the middle of the drag.
  }, [sliding, push, flush])

  const shownBpm = slideBpm ?? master.bpm

  return (
    <div
      ref={cardRef}
      {...handlers}
      // Capture runs root-to-child, so stopping here is what keeps the click that ended a drag from
      // also tapping a tempo or picking a ratio. No child has to know the gesture exists.
      onClickCapture={(e) => {
        if (consumeLongPress()) e.stopPropagation()
      }}
      className={cn(
        'relative overflow-hidden rounded-lg border bg-card',
        sliding ? 'border-primary ring-1 ring-primary/50' : 'border-border',
      )}
    >
      {/* The fader's travel, drawn behind the card's own content rather than as a control of its
          own: there is no track to aim at until the hold takes, which is the point of a gesture
          that costs no width. */}
      {sliding && (
        <div
          aria-hidden
          className="absolute inset-y-0 left-0 bg-primary/25"
          style={{ width: `${bpmSlideFraction(shownBpm) * 100}%` }}
        />
      )}
      <div className="relative flex flex-col gap-2 px-3 py-2.5">
        <span className={cn('flex items-center gap-1.5', EDITOR_LABEL_CLASS)}>
          <BeatIndicator master={master} className="size-1.5 shrink-0" />
          <span className="truncate">
            M{master.index}
            {master.name && ` · ${master.name}`}
          </span>
          <span className="flex-1" />
          {/* Master 1 never carries one: it is the fallback for every category no other master
            claims, so "routes dimmer" would understate it and any single label would be wrong. */}
          {!isMaster1 && usage && (
            <span
              className="shrink-0 rounded-full bg-muted px-1.5 py-px text-[9px] font-bold tracking-[0.06em]"
              // The rail's old caption, on the badge it was about: what a usage *means*, not what a
              // press here does — nothing on this page stamps a master; `TemplateEditor` does.
              title="Usage: effect templates of this family default to this master"
            >
              {usage}
            </span>
          )}
          {onOpenSettings && (
            <button
              type="button"
              onClick={onOpenSettings}
              aria-label={`Master ${master.index} settings`}
              title="Name, usage, starting tempo and follow"
              className="shrink-0 transition-colors hover:text-foreground"
            >
              <SlidersHorizontal className="size-3" />
            </button>
          )}
        </span>

        <div className="flex items-center gap-2">
          <div className="flex flex-1 items-baseline gap-1.5">
            {editing ? (
              <input
                autoFocus
                inputMode="decimal"
                value={draft}
                onChange={(e) => change(e.target.value)}
                onBlur={commit}
                onKeyDown={onKeyDown}
                aria-label={`Master ${master.index} BPM`}
                className={cn(
                  'w-[5ch] border-b border-primary bg-transparent font-mono font-bold leading-none tabular-nums outline-none',
                  isMaster1 ? 'text-3xl' : 'text-lg',
                )}
              />
            ) : (
              <button
                type="button"
                disabled={!connected || lockedReason != null}
                onClick={() => start(master.bpm)}
                title={
                  lockedReason ??
                  (connected
                    ? `Master ${master.index} — click to type a tempo, or hold the card and drag`
                    : DESK_OFFLINE_LABEL)
                }
                className={cn(
                  'font-mono font-bold leading-none tabular-nums transition-colors hover:text-primary disabled:hover:text-foreground',
                  isMaster1 ? 'text-3xl' : 'text-lg',
                )}
              >
                {formatBpm(shownBpm)}
              </button>
            )}
            {isMaster1 ? (
              <span className="text-[10px] text-muted-foreground">BPM</span>
            ) : follow ? (
              <span
                className="flex items-center gap-1 text-[10px] text-muted-foreground"
                title={lockedReason ?? undefined}
              >
                <Link2 className="size-2.5 shrink-0" />
                {describeFollow(follow.num, follow.den, leaderLabelOf(bank, leaderTarget))}
              </span>
            ) : (
              <span className="text-[10px] text-muted-foreground">manual</span>
            )}
          </div>

          {/* A follower's TAP is not disabled, it is absent — the chips take the cell instead, so
            linking a master swaps one control for another rather than leaving a dead button. */}
          {!follow && !isMaster1 && (
            <TapButton master={master} connected={connected} className="h-8 px-3.5" />
          )}
        </div>

        {/* Master 1 can never follow (the server refuses it a ratio), so its TAP needs no guard. */}
        {isMaster1 && <TapButton master={master} connected={connected} className="h-11 w-full" />}

        {follow && (
          <RatioChips
            index={master.index}
            projectId={projectId}
            masterId={row?.id}
            current={follow}
            leaderName={leaderNameOf(bank, leaderTarget)}
          />
        )}
      </div>
    </div>
  )
}

function TapButton({
  master,
  connected,
  className,
}: {
  master: SpeedMasterLiveState
  connected: boolean
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={() => tapSpeedMaster(master.uuid)}
      disabled={!connected}
      title={connected ? undefined : DESK_OFFLINE_LABEL}
      aria-label={`Tap tempo for master ${master.index}`}
      className={cn(
        'flex items-center justify-center rounded-md border text-xs font-bold uppercase tracking-[0.08em] transition-colors',
        'hover:bg-primary hover:text-primary-foreground active:bg-primary active:text-primary-foreground',
        'disabled:pointer-events-none disabled:opacity-50',
        className,
      )}
    >
      TAP
    </button>
  )
}

/**
 * The five time signatures, in the cell TAP would have had.
 *
 * This is the **second** surface that can write a follow ratio — until now only
 * `SpeedMasterDetailSheet` could — and it writes the same way that one does, for the same reasons:
 * both halves of the pair or neither (a half-patch is a 400), and **never alongside `bpm`**, which
 * the server refuses on a follower because its tempo comes from master 1 rather than from a stored
 * default. The response's `bpm` is deliberately not read: it is the pre-link stored value beside
 * the ratio it just accepted (`FU-SPEED-LINK-PUT-STALE-BPM`), and the state frame corrects it.
 *
 * Only reachable on a master that is already following: linking and unlinking stay in the sheet,
 * where the choice can be labelled. Retuning a link mid-show is the gesture that belongs on a
 * performance surface; deciding whether to have one is not.
 */
function RatioChips({
  index,
  projectId,
  masterId,
  current,
  leaderName,
}: {
  index: number
  projectId: number
  masterId: number | undefined
  current: { num: number; den: number }
  /** The master being followed — the chips retune the ratio, never the leader. */
  leaderName: string
}) {
  const [saveMaster, { isLoading }] = useSaveSpeedMasterMutation()

  return (
    <div className="flex gap-1" role="group" aria-label={`Master ${index} time signature`}>
      {FOLLOW_RATIOS.map((ratio) => {
        const active = ratio.num === current.num && ratio.den === current.den
        return (
          <button
            key={ratio.label}
            type="button"
            aria-pressed={active}
            aria-label={`Follow ${leaderName} at ${ratio.num}/${ratio.den}`}
            // No id means the REST list has not arrived (or this is the pre-boot synthetic master
            // 1, which never follows anyway); the chip has nothing to address until it does.
            disabled={masterId == null || isLoading || active}
            onClick={() => {
              if (masterId == null) return
              // No `followTargetUuid`: these chips change the *ratio* of an existing link, and
              // the PUT carries the stored leader forward when a ratio-only patch omits it.
              // Sending one would make a chip press capable of re-pointing the link.
              void saveMaster({
                projectId,
                masterId,
                followNum: ratio.num,
                followDen: ratio.den,
              })
                .unwrap()
                .catch(() => {
                  // Reported by `errorToastMiddleware`; nothing moved, so nothing to undo.
                })
            }}
            className={cn(
              'inline-flex h-[30px] min-w-[34px] items-center justify-center rounded-md border px-1.5 text-xs font-bold transition-all',
              'disabled:pointer-events-none',
              active
                ? 'border-primary bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground',
            )}
          >
            {ratio.label}
          </button>
        )
      })}
    </div>
  )
}
