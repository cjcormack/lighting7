import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AudioWaveform, Blinds, Layers, Lock, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { cn } from '@/lib/utils'
import { usePatchProjectCueMutation } from '@/store/cues'
import { usePatchCueStackEntryMutation } from '@/store/cueStacks'
import type { CueEntryPatch } from '@/api/cueStacksApi'
import type { CuePlace } from '@/lib/cuePlaces'
import { SharedCueChip } from './SharedCueChip'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { formatFadeDuration, parseFadeDuration } from '@/lib/cueUtils'
import { formatMs } from '@/lib/formatMs'
import { cueSceneryLines } from '@/lib/scenery'
import { useStageElementListQuery } from '@/store/stageElements'
import { CueSceneryEditor } from '@/components/scenery/CueSceneryEditor'
import { TRACKED_HATCH_CLASS } from '@/components/scenery/trackedHatch'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { TruncateStart } from '@/components/TruncateStart'
import { CueStatePip } from '@/components/cues/CueRowParts'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { SpreadPanel, type SpreadPlan } from '@/components/editor/SpreadPanel'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { ReadOutButton } from '@/components/sheet/ReadOutButton'
import { LegendSwatch, SheetPage } from '@/components/sheet/SheetPage'

/**
 * The live and standby rows' tints — one constant each, worn by the row *and* by its legend
 * swatch, so retuning a row moves the key with it (`LegendSwatch`'s contract).
 */
const LIVE_ROW_CLASS = 'bg-green-500/[0.08] shadow-[inset_3px_0_0_rgb(34,197,94)]'
const STANDBY_ROW_CLASS = 'bg-blue-500/[0.06] shadow-[inset_3px_0_0_rgb(59,130,246)]'
import { SheetTable } from '@/components/sheet/SheetTable'
import { useSheet } from '@/components/sheet/useSheet'
import type { SheetKeyRefusal } from '@/components/sheet/useSheetKeyboard'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { PHONE_FOLDED_CLASS, WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { firstColumnCellProps, listNames, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import { isCueRow, type CueStack, type CueStackCueEntry, type CueStackCueRow } from '@/api/cueStacksApi'

export type CueColumnKey = 'name' | 'fade' | 'curve' | 'follow' | 'book' | 'layers' | 'fx' | 'scenery' | 'notes'

/**
 * One row of the stack's list — an **entry**: a cue, or a marker drawn as a divider. Keyed by the
 * entry id, since a marker has no cue id and the two id spaces overlap.
 */
export interface CueSheetRow extends SheetRow {
  entry: CueStackCueEntry
}

export function entryRowId(entryId: number): string {
  return `entry:${entryId}`
}

/**
 * The cue a row places. The kit never asks a column about a divider (`takesValue`, `SheetTable`'s
 * divider arm, the selectable-row filter), so every column reads its row through this, and a
 * marker reaching one is a broken invariant rather than a value to draw.
 */
function cueOf(row: CueSheetRow): CueStackCueRow {
  if (!isCueRow(row.entry)) throw new Error(`marker row ${row.id} reached a cue column`)
  return row.entry
}

/** The curve vocabulary `CuePropsPane`'s select offers, in its order. */
const CURVE_OPTIONS: SheetOption[] = [
  { value: 'LINEAR', label: 'Linear' },
  { value: 'SINE_IN_OUT', label: 'Sine In/Out' },
  { value: 'CUBIC_IN_OUT', label: 'Cubic In/Out' },
  { value: 'EASE_IN', label: 'Ease In' },
  { value: 'EASE_OUT', label: 'Ease Out' },
  { value: 'EASE_IN_OUT', label: 'Ease In/Out' },
]

/** The lock's reason, said once — the verbs' titles and the bar's strip read it. */
const LOCKED_REASON = 'Locked — cells are read-only · L to edit'

export interface CueSheetProps {
  stack: CueStack
  projectId: number
  /** The live and armed entries — a row is an entry, and a cue may sit twice in one stack. */
  activeEntryId: number | null
  standbyEntryId?: number | null
  completedCueIds?: number[]
  locationByCue?: Map<number, string>
  /** Arm a cue as the next GO. Absent where there is no transport, or off the playhead's stack. */
  onSetStandby?: (cueId: number, entryId: number) => void
  /** The show-editing lock — this sheet's read-only scope. */
  locked?: boolean
  /**
   * The cue named in the URL — `?cue=`, the external contract the Prompt Book mints. The cards
   * expand it; the sheet selects its row and scrolls to it, which is what "opened" means on a
   * sheet. Consumed on arrival and whenever it changes.
   */
  openedEntryId?: number | null
  /** Open a cue's card on the cards view — Layers and FX are read-outs that do this. */
  onOpenCue: (cueId: number, entryId: number) => void
  /** Open the Prompt Book at this cue's anchor — the Book column's read-out. */
  onOpenBook?: (cueId: number) => void
  /**
   * The show is locked and the operator reached for an edit anyway. Given, the refused gestures
   * stop being dead controls and ask to unlock instead; absent, they stay disabled with the reason.
   */
  onRequestUnlock?: () => void
  /**
   * Reorder the stack — every row id in its new order, separators included, which is the same
   * `reorderCues` the cards view's drag calls. Absent leaves the rows static.
   */
  /** Every row's entry id in its new order, markers included. */
  onReorder?: (entryIds: number[]) => void
  /** Every cue's places across the show — the shared chip on a cue in more than one. */
  placesByCue?: ReadonlyMap<number, readonly CuePlace[]>
}

/**
 * The cue sheet — one row per cue, a marker as a divider row, on the sheet kit
 * (CLAUDE.md §Sheet kit). Name · Fade · Curve · Follow · Notes are cells; the cue number keeps its
 * inline edit on the Cue column; Book · Layers · FX are read-outs that open the card. Live row
 * green, next row blue, as the cards.
 *
 * **The lock is this sheet's read-only scope**, the way Output scope is the programmer's. Locked,
 * every value cell is inert — `pointer-events-none` on the wrapper, `disabled` on the trigger,
 * the keyboard refused through the permission, Set · Clear · Spread disabled with the reason — the
 * marquee still works, and a click on the Cue column arms the cue as next, exactly as a card
 * click does. Unlocked, cells edit under the amber wash the chrome above already wears. GO, BACK,
 * Space and ⌫ are untouched: `useTransportKeys` is the page's, enabled exactly while locked as it
 * always was, and `canOperate` is never handed `locked` (CLAUDE.md §The show-editing lock).
 *
 * **A commit reaches only the columns of its own kind.** Name, Fade, Curve, Follow and Notes all
 * take a string, so the kit's shape test cannot tell a fade from a follow delay; each declares its
 * own `kind`, and a `3s` typed into Fade over a Fade→Follow→Notes marquee sets three fades and
 * nothing else — `CueSheet.test.tsx` pins it.
 *
 * Writes are one PATCH per cue carrying the field — the same auto-saving contract the cards'
 * inline fields keep. Spread on Fade spreads first→last along a curve — the panel's curve row,
 * where the one-option Spread select was (`FU-SPREAD-DURATION-CURVES`).
 *
 * No Hooks column: `CueStackCueEntry` carries no trigger count, and adding one is a backend field.
 *
 * **Scenery is a read-out that opens an editor** (scenery-programmer plan D13): the cue's own
 * changes as `Tabs → closed · 4 s`, off the stack list's `CueStackCueEntry.scenery`, or the tracked
 * hatch where it changes nothing but something is tracked into it; a press opens `CueSceneryEditor`
 * (Cue properties' own `SceneryEditor`, with times) at the cell, so a cue's scenery is edited without
 * opening its card. It is no `cell` — one cue's list is not a value a marquee spreads — and it
 * follows the lock as the value cells do: locked, a press asks to unlock (or is disabled with the
 * reason where the lock is not the operator's), and an editor open when the show locks closes.
 *
 * **Blank means unsettable; an em-dash means empty but settable.** Follow and Notes draw the dash
 * because a marquee can reach them and Set will write them; a read-out (Book, Layers, FX) and a
 * snap cue's Curve draw nothing at all, because there is no cell there to select. One glyph for
 * both made half the sheet's dashes look editable. Any column added here answers the same way.
 */
export function CueSheet({
  stack,
  projectId,
  activeEntryId,
  standbyEntryId,
  completedCueIds,
  locationByCue,
  onSetStandby,
  locked = false,
  openedEntryId,
  onOpenCue,
  onOpenBook,
  onRequestUnlock,
  onReorder,
  placesByCue,
}: CueSheetProps) {
  const [patchCue] = usePatchProjectCueMutation()
  const [patchEntryMutation] = usePatchCueStackEntryMutation()
  const { data: elements } = useStageElementListQuery(projectId)
  const elementsByUuid = useMemo(() => (elements == null ? null : new Map(elements.map((e) => [e.uuid, e]))), [elements])
  /**
   * The cues something is tracked *into* — the stack's set, or an earlier cue's own change — for the
   * Scenery column's hatch: a cue that changes nothing reads *tracked* only where there is something
   * to track, and an em-dash (empty, but the editor will set it) where the stack moves nothing above
   * it. Scenery tracks from the top of the list, as the desk's `trackedSceneryAt` folds it.
   */
  const tracksScenery = useMemo(() => {
    const out = new Set<number>()
    let above = (stack.scenery?.length ?? 0) > 0
    for (const c of stack.cues) {
      if (!isCueRow(c)) continue
      if (above) out.add(c.id)
      if ((c.scenery?.length ?? 0) > 0) above = true
    }
    return out
  }, [stack.cues, stack.scenery])
  /** The entry whose scenery editor is open — an entry, so a shared cue tracks from its place here. */
  const [sceneryEntryId, setSceneryEntryId] = useState<number | null>(null)
  const sceneryAnchorRef = useRef<HTMLElement | null>(null)
  // The GO that re-locks the show forgets the open editor rather than holding it for the unlock.
  useEffect(() => {
    if (locked) setSceneryEntryId(null)
  }, [locked])
  const patch = useCallback(
    (cueId: number, body: Record<string, unknown>) => {
      patchCue({ projectId, cueId, ...body }).unwrap().catch(ignoreReportedError)
    },
    [patchCue, projectId],
  )

  /** The number and the follow are the entry's, written on the entry. */
  const patchEntry = useCallback(
    (row: CueStackCueRow, body: CueEntryPatch) => {
      patchEntryMutation({ projectId, stackId: stack.id, entryId: row.entryId, cueId: row.id, ...body })
        .unwrap()
        .catch(ignoreReportedError)
    },
    [patchEntryMutation, projectId, stack.id],
  )

  const [unlockAsked, setUnlockAsked] = useState(false)

  const rows = useMemo<CueSheetRow[]>(
    () =>
      stack.cues.map((entry) =>
        isCueRow(entry)
          ? { id: entryRowId(entry.entryId), entry }
          : { id: entryRowId(entry.entryId), entry, divider: entry.name },
      ),
    [stack.cues],
  )
  const completedSet = useMemo(() => new Set(completedCueIds), [completedCueIds])

  const columns = useMemo<SheetColumn<CueSheetRow, CueColumnKey>[]>(
    () => [
      {
        key: 'name',
        label: 'Name',
        kind: 'name',
        width: 'minmax(200px, 1fr)',
        value: (row) => cueOf(row).name,
        cell: (row, props) => {
          const active = row.entry.entryId === activeEntryId
          const standby = row.entry.entryId === standbyEntryId && !active
          return (
            <TextCell
              {...(props as React.ComponentProps<typeof TextCell>)}
              face={
                <span className="mx-1.5 flex min-w-0 items-center gap-1.5">
                  <span
                    className={cn(
                      'truncate text-sm',
                      active ? 'font-semibold text-green-300' : standby ? 'font-semibold text-blue-300' : 'font-medium',
                    )}
                  >
                    {cueOf(row).name}
                  </span>
                  <SharedCueChip places={placesByCue?.get(cueOf(row).id)} entryId={cueOf(row).entryId} interactive={false} />
                </span>
              }
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string' || value.trim() === '') return false
          for (const row of batch) if (value !== cueOf(row).name) patch(cueOf(row).id, { name: value })
          return true
        },
        clearRefusal: 'A cue has a name — it cannot be blank',
      },
      {
        key: 'fade',
        label: 'Fade',
        kind: 'fade',
        width: '88px',
        value: (row) => formatFadeDuration(cueOf(row).fadeDurationMs),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="2s"
            face={
              <span className="mx-1.5 font-mono text-xs font-medium tabular-nums">
                {formatFadeDuration(cueOf(row).fadeDurationMs) || 'SNAP'}
              </span>
            }
            validate={(draft) =>
              parseFadeDuration(draft) === undefined ? 'A fade is seconds, or a number with ms, s or m — or SNAP' : null
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const parsed = parseFadeDuration(value)
          if (parsed === undefined) return false
          for (const row of batch) if (parsed !== (cueOf(row).fadeDurationMs ?? null)) patch(cueOf(row).id, { fadeDurationMs: parsed })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (cueOf(row).fadeDurationMs != null) patch(cueOf(row).id, { fadeDurationMs: null })
        },
        spread: (batch): SpreadPlan | null =>
          batch.length === 0
            ? null
            : {
                kind: 'duration',
                col: 'fade',
                label: 'Fade',
                count: batch.length,
                names: batch.map((row) => `Q${cueOf(row).cueNumber ?? '—'}`),
                apply: (ms) => {
                  batch.forEach((row, i) => {
                    const next = ms[i] > 0 ? ms[i] : null
                    if (next !== (cueOf(row).fadeDurationMs ?? null)) patch(cueOf(row).id, { fadeDurationMs: next })
                  })
                },
              },
      },
      {
        key: 'curve',
        label: 'Curve',
        kind: 'curve',
        width: '118px',
        value: (row) => {
          const cue = cueOf(row)
          return cue.fadeDurationMs != null && cue.fadeDurationMs > 0 ? cue.fadeCurve : undefined
        },
        cell: (_row, props) => (
          <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={CURVE_OPTIONS} />
        ),
        // Nothing to set on a snap cue, so nothing drawn — see the docblock's blank-vs-em-dash rule.
        // A marquee that sweeps one up skips it (the kit drops it before `write`) and says why.
        display: () => null,
        skipNote: (skipped) => `${listNames(skipped.map(cueRowName), 'cue')} ${skipped.length === 1 ? 'snaps' : 'snap'} · skipped`,
        write: (batch, value) => {
          if (typeof value !== 'string' || !CURVE_OPTIONS.some((o) => o.value === value)) return false
          for (const row of batch) if (value !== cueOf(row).fadeCurve) patch(cueOf(row).id, { fadeCurve: value })
          return true
        },
        clearRefusal: 'A fade has a curve — pick one',
      },
      {
        key: 'follow',
        label: 'Follow',
        kind: 'follow',
        width: '96px',
        value: (row) => (cueOf(row).autoAdvance ? formatMs(cueOf(row).autoAdvanceDelayMs ?? 0) : ''),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="2s"
            face={
              cueOf(row).autoAdvance ? (
                <span className="mx-1.5 inline-flex items-center gap-1 text-xs">
                  <span className="text-[9px] uppercase tracking-wide text-blue-500">auto</span>
                  <span className="font-mono tabular-nums">{formatMs(cueOf(row).autoAdvanceDelayMs ?? 0)}</span>
                </span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
            validate={(draft) =>
              parseFadeDuration(draft) === undefined ? 'A follow is a delay in seconds, or blank for none' : null
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const parsed = parseFadeDuration(value)
          if (parsed === undefined) return false
          for (const row of batch) {
            if (parsed === null) {
              if (cueOf(row).autoAdvance) patchEntry(cueOf(row), { autoAdvance: false })
            } else if (!cueOf(row).autoAdvance || parsed !== cueOf(row).autoAdvanceDelayMs) {
              patchEntry(cueOf(row), { autoAdvance: true, autoAdvanceDelayMs: parsed })
            }
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (cueOf(row).autoAdvance) patchEntry(cueOf(row), { autoAdvance: false })
        },
      },
      {
        key: 'book',
        // Wider than the design's 76px, and not in the mono face: the mockup's value was `p1`,
        // and a real one is a sentence — `bottom of p. 12`, up to 85px of proportional 11px text.
        // At 76px mono it wrapped to two lines and pushed the cell past the row's fixed height,
        // painting over the row below. The kit clips a read-out now (`SheetTable`), so this is
        // about the label being *readable* rather than about the overflow.
        label: 'Book',
        width: '108px',
        value: () => undefined,
        display: (row) => {
          const location = locationByCue?.get(cueOf(row).id)
          // **The Book column opens the Prompt Book**, not the cue's card — it is the one read-out
          // that names a place in another document, and sending it to the card was answering a
          // different question from the one the column asks.
          return location ? (
            <ReadOutButton
              onClick={() => onOpenBook?.(cueOf(row).id)}
              disabled={onOpenBook == null}
              title={`${location} — open the Prompt Book here`}
            >
              <span className="truncate text-[11px]">{location}</span>
            </ReadOutButton>
          ) : null
        },
      },
      {
        key: 'layers',
        label: 'Layers',
        width: '76px',
        value: () => undefined,
        display: (row) =>
          cueOf(row).layerCount > 0 ? (
            <ReadOutButton onClick={() => onOpenCue(cueOf(row).id, row.entry.entryId)} title="Open the cue's card">
              <CountBadge n={cueOf(row).layerCount} />
              <Layers className="size-3" />
            </ReadOutButton>
          ) : null,
      },
      {
        key: 'fx',
        label: 'FX',
        width: '56px',
        value: () => undefined,
        display: (row) =>
          cueOf(row).adHocEffectCount > 0 ? (
            <ReadOutButton onClick={() => onOpenCue(cueOf(row).id, row.entry.entryId)} title="Open the cue's card" className="text-violet-400">
              <CountBadge n={cueOf(row).adHocEffectCount} className="border-violet-400/60" />
              <AudioWaveform className="size-3" />
            </ReadOutButton>
          ) : null,
      },
      {
        key: 'scenery',
        label: 'Scenery',
        width: '196px',
        value: () => undefined,
        display: (row) => {
          const lines = cueSceneryLines(cueOf(row).scenery, elementsByUuid)
          const said = lines.join(' · ')
          const face =
            lines.length > 0 ? (
              <span className="truncate text-[11px] text-foreground">{said}</span>
            ) : tracksScenery.has(cueOf(row).id) ? (
              <span className={cn('rounded px-1.5 text-[10px] leading-4 text-muted-foreground', TRACKED_HATCH_CLASS)}>tracked</span>
            ) : (
              <span className="text-xs text-muted-foreground/60">—</span>
            )
          const what = lines.length > 0 ? said : tracksScenery.has(cueOf(row).id) ? 'Changes nothing — its scenery is tracked' : 'Changes no scenery'
          return (
            <ReadOutButton
              data-scenery-cue={cueOf(row).id}
              // Locked and not the operator's to lift: legible but inert, with the reason.
              disabled={locked && onRequestUnlock == null}
              title={locked ? `${what} — ${LOCKED_REASON}` : `${what} — edit the scenery this cue changes`}
              aria-label={`Scenery of ${cueRowName(row)}: ${what}`}
              onClick={(e) => {
                // **A refused edit asks to unlock**, the sheet's rule for every gesture the lock refuses.
                if (locked) {
                  setUnlockAsked(true)
                  return
                }
                sceneryAnchorRef.current = e.currentTarget
                setSceneryEntryId(row.entry.entryId)
              }}
            >
              {lines.length > 0 && <Blinds className="size-3 shrink-0" />}
              {face}
            </ReadOutButton>
          )
        },
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(200px, 1fr)',
        value: (row) => cueOf(row).notes ?? '',
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              cueOf(row).notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{cueOf(row).notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value
          for (const row of batch) if (next !== (cueOf(row).notes ?? null)) patch(cueOf(row).id, { notes: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (cueOf(row).notes) patch(cueOf(row).id, { notes: null })
        },
      },
    ],
    [activeEntryId, elementsByUuid, locationByCue, locked, onOpenBook, onOpenCue, onRequestUnlock, patch, patchEntry, placesByCue, standbyEntryId, tracksScenery],
  )

  const copy = useCallback(
    (cellCount: number) => {
      if (locked) return { setTitle: LOCKED_REASON, clearTitle: LOCKED_REASON }
      const cells = `${cellCount} selected cell${cellCount === 1 ? '' : 's'}`
      return { setTitle: `Set the ${cells} (Enter)`, clearTitle: `Clear the ${cells} (Backspace)` }
    },
    [locked],
  )
  const permission = useMemo(() => ({ entry: !locked, clear: !locked }), [locked])
  const cellDisabled = useCallback(() => locked, [locked])

  /**
   * **A refused edit asks to unlock rather than doing nothing.** Locked, Set · Clear · Spread were
   * greyed out and ⏎ was swallowed — which reads as a broken sheet rather than as a mode, since
   * the marquee that put the operator there still works. Now each of those opens this dialog, and
   * the way out is one press.
   *
   * Offered only when the lock is the operator's to lift (`onRequestUnlock` is withheld where the
   * backend would refuse the write anyway — see `ShowPage`), so the inert case keeps the disabled
   * buttons and their reason, which is the honest answer there.
   */
  const askToUnlock = useCallback(() => setUnlockAsked(true), [])
  /** The verbs' half: a press, so there is no key to weigh. */
  const refuseVerb = locked && onRequestUnlock ? askToUnlock : undefined

  /**
   * The keyboard's half — **Enter alone**, which is this surface's call to make rather than the
   * kit's. While a show is locked, `useTransportKeys` owns Backspace (it is BACK) and a typed `l`
   * (the lock toggle, and the keyboard's own way back to editing); both stand aside on
   * `defaultPrevented`, so claiming either here would take them away in the state they matter
   * most. The transport binds no Enter.
   *
   * Memoised, not an inline ternary: `useSheetKeyboard` has this in its effect's deps, so an
   * identity that moved per render would tear down and rebuild a window keydown listener at
   * render rate.
   */
  const refuseKey = useMemo(() => {
    if (!locked || !onRequestUnlock) return undefined
    return ({ key }: SheetKeyRefusal) => {
      if (key !== 'Enter') return false
      setUnlockAsked(true)
      return true
    }
  }, [locked, onRequestUnlock])

  const sheet = useSheet<CueSheetRow, CueColumnKey>({
    rows,
    columns,
    permission,
    copy,
    cellDisabled,
    onRefused: refuseKey,
    noun: 'cue',
    rowName: cueRowName,
  })
  const { selectedRows, cellCount, setRows, scrollTo } = sheet

  // The deep link: select the addressed cue's row and bring it into view — **once per id**. Not
  // an expansion — a sheet has no card to open — but the row is what the Prompt Book's "Edit cue"
  // was pointing at, and a link that lands on the right stack with the wrong cue nowhere in sight
  // would have dropped the contract on the floor.
  //
  // Once, because `rows` is re-minted on every refetch of the stack — each cell commit invalidates
  // the stack list, and every GO moves `activeEntryId` — and `?cue=` is never cleared on this view.
  // Keyed on `rows` alone the effect re-fired after every commit, wiping a live marquee and
  // re-scrolling to the linked cue (found by the fresh verifier). The ref remembers which id has
  // been acted on; a cue not yet in `rows` (the stack still loading) is consumed when it arrives.
  const consumedCueRef = useRef<number | null>(null)
  useEffect(() => {
    if (openedEntryId == null) {
      consumedCueRef.current = null
      return
    }
    if (consumedCueRef.current === openedEntryId) return
    const row = rows.find((r) => r.divider == null && r.entry.entryId === openedEntryId)
    if (row == null) return
    const id = row.id
    consumedCueRef.current = openedEntryId
    setRows([id])
    scrollTo(id)
  }, [openedEntryId, rows, scrollTo, setRows])

  /** Arming is what a click on the Cue column means while locked — the cards' body-click rule. */
  const onRowClick = useCallback(
    (id: string, e: React.MouseEvent) => {
      if (locked && onSetStandby) {
        const row = rows.find((r) => r.id === id && r.divider == null)
        if (row && row.entry.entryId !== activeEntryId) onSetStandby(cueOf(row).id, row.entry.entryId)
        return
      }
      sheet.tableProps.onRowClick(id, e)
    },
    [activeEntryId, locked, onSetStandby, rows, sheet.tableProps],
  )

  const armable = locked && onSetStandby && selectedRows.length === 1 && selectedRows[0].entry.entryId !== activeEntryId
  const verbs =
    selectedRows.length > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
        {cellCount > 0 && (
          <CellSelectionActions
            copy={sheet.copy}
            permission={sheet.permission}
            setRef={sheet.setButtonRef}
            onSet={sheet.toggleCellEditor}
            onClear={sheet.clearSelectedCells}
            onRefused={refuseVerb}
            spread={
              <SpreadPanel
                host="popover"
                plans={sheet.spreadPlans}
                disabledReason={locked ? LOCKED_REASON : null}
                onRefused={refuseVerb}
                drivableHint="fade"
                className={PHONE_FOLDED_CLASS}
              />
            }
          />
        )}
        {locked && onSetStandby && (
          <Button
            variant="outline"
            size="sm"
            disabled={!armable}
            onClick={() => armable && onSetStandby(cueOf(selectedRows[0]).id, selectedRows[0].entry.entryId)}
            title={armable ? 'Arm this cue as the next GO' : 'Select one cue that is not on stage to arm it'}
          >
            <Play className="size-3.5" />
            <span className={WORD_CLASS}>Arm as next</span>
          </Button>
        )}
        <Button variant="ghost" size="sm" onClick={sheet.clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const rowNoun = selectedRows.length === 1 ? 'cue' : 'cues'
  const standardCount = rows.filter((row) => row.divider == null).length
  const markerCount = rows.length - standardCount

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="@container">
        <SelectionBar
          rowLabel={selectedRows.length > 0 ? `${selectedRows.length} ${rowNoun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${selectedRows.length} ${rowNoun} · ${sheet.family ?? ''} — edit once, applies to all`}
          family={sheet.family}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          strip={
            locked ? (
              <span className="inline-flex shrink-0 items-center gap-1.5 border-l pl-3 text-xs text-muted-foreground">
                <Lock className="size-3" />
                {LOCKED_REASON}
              </span>
            ) : undefined
          }
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          // The lock note is a short label, not a scroller: it needs no room, so the verbs keep
          // their words as on a bar with nothing riding it.
          foldForStrip={false}
        />
      </div>
      <SheetTable<CueSheetRow, CueColumnKey>
        {...sheet.tableProps}
        onRowClick={onRowClick}
        // **Reordering is an unlocked gesture**, like every other edit on this sheet — the grip is
        // drawn only then, and the rows the cards view can drag are the rows this can. The ids come
        // back as row ids (`cue:<n>`), which is the sheet's vocabulary, so they are unwrapped here
        // rather than making the kit know what a cue is.
        rowDrag={
          onReorder
            ? {
                enabled: !locked,
                onReorder: (ids) =>
                  onReorder(ids.map((id) => Number(id.slice('entry:'.length))).filter(Number.isFinite)),
              }
            : undefined
        }
        minWidth={`${100 + columns.reduce((n, c) => n + trackFloor(c.width), 0)}px`}
        // Asked of every row, dividers included — a marker is never live or next.
        rowClass={(row) =>
          !isCueRow(row.entry)
            ? undefined
            : row.entry.entryId === activeEntryId
              ? LIVE_ROW_CLASS
              : row.entry.entryId === standbyEntryId
                ? STANDBY_ROW_CLASS
                : undefined
        }
        firstColumn={{
          label: 'Cue',
          width: '100px',
          render: (row) => {
            const active = row.entry.entryId === activeEntryId
            const standby = row.entry.entryId === standbyEntryId && !active
            return (
              <span
                className="relative flex min-w-0 items-center gap-2"
                data-cue-row={cueOf(row).id}
                data-entry-row={row.entry.entryId}
              >
                <CueStatePip isActive={active} isStandby={standby} />
                {completedSet.has(cueOf(row).id) && !active && (
                  <span className="sr-only">Played</span>
                )}
                {/* **A double click opens it, in the same popover as every value cell on this
                    sheet.** It was a single click, which had to swallow the press so the row
                    underneath did not select — so the Cue column was the one column where a click
                    meant something different. Now the single click selects the row and the second
                    gesture edits, which is the grid's rule everywhere else.

                    **Locked, it is plain text, not a disabled trigger.** A click on the number
                    while locked arms the cue, by bubbling to the column — and a browser dispatches
                    no click at all for a press inside a disabled button, so a disabled editor would
                    have made the number the one dead spot in the column. The face is
                    `inline-block` in both arms: left inline, `TruncateStart`'s blocks made the line
                    box 59px tall for a 20px number, and as the row's tallest min-content that
                    became the track. */}
                <span
                  className={cn(
                    'min-w-0 flex-1 font-mono text-sm',
                    cueOf(row).cueNumberAuto ? AUTO_CUE_NUMBER_CLASS : 'font-semibold',
                  )}
                >
                  {locked ? (
                    <span className="inline-block max-w-full px-0.5" title="Cue number">
                      <TruncateStart text={cueOf(row).cueNumber ? `Q${cueOf(row).cueNumber}` : '—'} />
                    </span>
                  ) : (
                    <TextCell
                      {...firstColumnCellProps<string>({
                        noun: 'cue',
                        value: cueOf(row).cueNumber ?? '',
                        label: 'Cue number',
                        onCommit: (next) => {
                          const trimmed = next || null
                          if (trimmed !== (cueOf(row).cueNumber ?? null)) patchEntry(cueOf(row), { cueNumber: trimmed })
                        },
                      })}
                      mono
                      allowEmpty
                      placeholder="14A"
                      face={
                        <span
                          className="inline-block max-w-full px-0.5"
                          title={
                            cueOf(row).cueNumberAuto
                              ? 'Auto-numbered from position — double-click to set an explicit cue number'
                              : 'Double-click to edit the cue number'
                          }
                        >
                          <TruncateStart text={cueOf(row).cueNumber ? `Q${cueOf(row).cueNumber}` : '—'} />
                        </span>
                      }
                    />
                  )}
                </span>
              </span>
            )
          },
        }}
      />
      <CueSceneryEditor
        projectId={projectId}
        // Closed by the lock as well as by its own dismissal: every edit on this sheet is an
        // unlocked gesture, and the GO that re-locks the show must not leave one open.
        cue={locked || sceneryEntryId == null ? null : (stack.cues.filter(isCueRow).find((c) => c.entryId === sceneryEntryId) ?? null)}
        anchorRef={sceneryAnchorRef}
        onClose={() => setSceneryEntryId(null)}
      />
      <AlertDialog open={unlockAsked} onOpenChange={setUnlockAsked}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unlock the show to edit?</AlertDialogTitle>
            <AlertDialogDescription>
              The show is running and locked, so a stray click cannot change it. Unlocking leaves it
              running and re-locks itself on the next GO — your selection is kept either way.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Stay locked</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setUnlockAsked(false)
                onRequestUnlock?.()
              }}
            >
              Unlock
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {/* The shell's footer, and its one swatch (CLAUDE.md §List shell): each wears the tint and
          the 3px edge its row wears above, so the key teaches the mark and not a stand-in. */}
      <SheetPage.Footer>
        <span>
          {standardCount} cue{standardCount === 1 ? '' : 's'}
          {markerCount > 0 ? ` · ${markerCount} marker${markerCount === 1 ? '' : 's'}` : ''}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <LegendSwatch className={cn(LIVE_ROW_CLASS, 'ring-1 ring-inset ring-green-500/40')} />
          Live
        </span>
        <span className="inline-flex items-center gap-1.5">
          <LegendSwatch className={cn(STANDBY_ROW_CLASS, 'ring-1 ring-inset ring-blue-500/40')} />
          Next
        </span>
      </SheetPage.Footer>
    </div>
  )
}

/** A cue in a skip read-out — `Q14`, the number the operator reads the sheet by. */
function cueRowName(row: CueSheetRow): string {
  return cueOf(row).cueNumber ? `Q${cueOf(row).cueNumber}` : cueOf(row).name
}

function CountBadge({ n, className }: { n: number; className?: string }) {
  return (
    <span className={cn('rounded border px-1 font-mono text-[10px] tabular-nums', className)}>{n}</span>
  )
}

function trackFloor(width: string): number {
  const m = /^(?:minmax\()?\s*(\d+)px/.exec(width)
  return m ? Number(m[1]) : 96
}
