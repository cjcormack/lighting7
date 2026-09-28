import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AudioWaveform, Layers, Lock, Play, X } from 'lucide-react'
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
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { formatFadeDuration, parseFadeDuration } from '@/lib/cueUtils'
import { formatMs } from '@/lib/formatMs'
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
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'

export type CueColumnKey = 'name' | 'fade' | 'curve' | 'follow' | 'book' | 'layers' | 'fx' | 'notes'

/** One cue of the stack; a marker is a divider row. */
export interface CueSheetRow extends SheetRow {
  cue: CueStackCueEntry
}

export function cueRowId(cueId: number): string {
  return `cue:${cueId}`
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
  activeCueId: number | null
  standbyCueId?: number | null
  completedCueIds?: number[]
  locationByCue?: Map<number, string>
  /** Arm a cue as the next GO. Absent where there is no transport, or off the playhead's stack. */
  onSetStandby?: (cueId: number) => void
  /** The show-editing lock — this sheet's read-only scope. */
  locked?: boolean
  /**
   * The cue named in the URL — `?cue=`, the external contract the Prompt Book mints. The cards
   * expand it; the sheet selects its row and scrolls to it, which is what "opened" means on a
   * sheet. Consumed on arrival and whenever it changes.
   */
  openedCueId?: number | null
  /** Open a cue's card on the cards view — Layers and FX are read-outs that do this. */
  onOpenCue: (cueId: number) => void
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
  onReorder?: (cueIds: number[]) => void
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
 * **Blank means unsettable; an em-dash means empty but settable.** Follow and Notes draw the dash
 * because a marquee can reach them and Set will write them; a read-out (Book, Layers, FX) and a
 * snap cue's Curve draw nothing at all, because there is no cell there to select. One glyph for
 * both made half the sheet's dashes look editable. Any column added here answers the same way.
 */
export function CueSheet({
  stack,
  projectId,
  activeCueId,
  standbyCueId,
  completedCueIds,
  locationByCue,
  onSetStandby,
  locked = false,
  openedCueId,
  onOpenCue,
  onOpenBook,
  onRequestUnlock,
  onReorder,
}: CueSheetProps) {
  const [patchCue] = usePatchProjectCueMutation()
  const patch = useCallback(
    (cueId: number, body: Record<string, unknown>) => {
      patchCue({ projectId, cueId, ...body }).unwrap().catch(ignoreReportedError)
    },
    [patchCue, projectId],
  )

  const rows = useMemo<CueSheetRow[]>(
    () =>
      stack.cues.map((cue) =>
        cue.cueType === 'MARKER' ? { id: cueRowId(cue.id), cue, divider: cue.name } : { id: cueRowId(cue.id), cue },
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
        value: (row) => row.cue.name,
        cell: (row, props) => {
          const active = row.cue.id === activeCueId
          const standby = row.cue.id === standbyCueId && !active
          return (
            <TextCell
              {...(props as React.ComponentProps<typeof TextCell>)}
              face={
                <span
                  className={cn(
                    'mx-1.5 truncate text-sm',
                    active ? 'font-semibold text-green-300' : standby ? 'font-semibold text-blue-300' : 'font-medium',
                  )}
                >
                  {row.cue.name}
                </span>
              }
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string' || value.trim() === '') return false
          for (const row of batch) if (value !== row.cue.name) patch(row.cue.id, { name: value })
          return true
        },
        clearRefusal: 'A cue has a name — it cannot be blank',
      },
      {
        key: 'fade',
        label: 'Fade',
        kind: 'fade',
        width: '88px',
        value: (row) => formatFadeDuration(row.cue.fadeDurationMs),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="2s"
            face={
              <span className="mx-1.5 font-mono text-xs font-medium tabular-nums">
                {formatFadeDuration(row.cue.fadeDurationMs) || 'SNAP'}
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
          for (const row of batch) if (parsed !== (row.cue.fadeDurationMs ?? null)) patch(row.cue.id, { fadeDurationMs: parsed })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.cue.fadeDurationMs != null) patch(row.cue.id, { fadeDurationMs: null })
        },
        spread: (batch): SpreadPlan | null =>
          batch.length === 0
            ? null
            : {
                kind: 'duration',
                col: 'fade',
                label: 'Fade',
                count: batch.length,
                names: batch.map((row) => `Q${row.cue.cueNumber ?? '—'}`),
                apply: (ms) => {
                  batch.forEach((row, i) => {
                    const next = ms[i] > 0 ? ms[i] : null
                    if (next !== (row.cue.fadeDurationMs ?? null)) patch(row.cue.id, { fadeDurationMs: next })
                  })
                },
              },
      },
      {
        key: 'curve',
        label: 'Curve',
        kind: 'curve',
        width: '118px',
        value: (row) => (row.cue.fadeDurationMs != null && row.cue.fadeDurationMs > 0 ? row.cue.fadeCurve : undefined),
        cell: (_row, props) => (
          <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={CURVE_OPTIONS} />
        ),
        // Nothing to set on a snap cue, so nothing drawn — see the docblock's blank-vs-em-dash rule.
        // A marquee that sweeps one up skips it (the kit drops it before `write`) and says why.
        display: () => null,
        skipNote: (skipped) => `${listNames(skipped.map(cueRowName), 'cue')} ${skipped.length === 1 ? 'snaps' : 'snap'} · skipped`,
        write: (batch, value) => {
          if (typeof value !== 'string' || !CURVE_OPTIONS.some((o) => o.value === value)) return false
          for (const row of batch) if (value !== row.cue.fadeCurve) patch(row.cue.id, { fadeCurve: value })
          return true
        },
        clearRefusal: 'A fade has a curve — pick one',
      },
      {
        key: 'follow',
        label: 'Follow',
        kind: 'follow',
        width: '96px',
        value: (row) => (row.cue.autoAdvance ? formatMs(row.cue.autoAdvanceDelayMs ?? 0) : ''),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="2s"
            face={
              row.cue.autoAdvance ? (
                <span className="mx-1.5 inline-flex items-center gap-1 text-xs">
                  <span className="text-[9px] uppercase tracking-wide text-blue-500">auto</span>
                  <span className="font-mono tabular-nums">{formatMs(row.cue.autoAdvanceDelayMs ?? 0)}</span>
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
              if (row.cue.autoAdvance) patch(row.cue.id, { autoAdvance: false })
            } else if (!row.cue.autoAdvance || parsed !== row.cue.autoAdvanceDelayMs) {
              patch(row.cue.id, { autoAdvance: true, autoAdvanceDelayMs: parsed })
            }
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.cue.autoAdvance) patch(row.cue.id, { autoAdvance: false })
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
          const location = locationByCue?.get(row.cue.id)
          // **The Book column opens the Prompt Book**, not the cue's card — it is the one read-out
          // that names a place in another document, and sending it to the card was answering a
          // different question from the one the column asks.
          return location ? (
            <ReadOutButton
              onClick={() => onOpenBook?.(row.cue.id)}
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
          row.cue.layerCount > 0 ? (
            <ReadOutButton onClick={() => onOpenCue(row.cue.id)} title="Open the cue's card">
              <CountBadge n={row.cue.layerCount} />
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
          row.cue.adHocEffectCount > 0 ? (
            <ReadOutButton onClick={() => onOpenCue(row.cue.id)} title="Open the cue's card" className="text-violet-400">
              <CountBadge n={row.cue.adHocEffectCount} className="border-violet-400/60" />
              <AudioWaveform className="size-3" />
            </ReadOutButton>
          ) : null,
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(200px, 1fr)',
        value: (row) => row.cue.notes ?? '',
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              row.cue.notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.cue.notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value
          for (const row of batch) if (next !== (row.cue.notes ?? null)) patch(row.cue.id, { notes: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.cue.notes) patch(row.cue.id, { notes: null })
        },
      },
    ],
    [activeCueId, locationByCue, onOpenBook, onOpenCue, patch, standbyCueId],
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
  const [unlockAsked, setUnlockAsked] = useState(false)
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
  // the stack list, and every GO moves `activeCueId` — and `?cue=` is never cleared on this view.
  // Keyed on `rows` alone the effect re-fired after every commit, wiping a live marquee and
  // re-scrolling to the linked cue (found by the fresh verifier). The ref remembers which id has
  // been acted on; a cue not yet in `rows` (the stack still loading) is consumed when it arrives.
  const consumedCueRef = useRef<number | null>(null)
  useEffect(() => {
    if (openedCueId == null) {
      consumedCueRef.current = null
      return
    }
    if (consumedCueRef.current === openedCueId) return
    const id = cueRowId(openedCueId)
    if (!rows.some((row) => row.id === id && row.divider == null)) return
    consumedCueRef.current = openedCueId
    setRows([id])
    scrollTo(id)
  }, [openedCueId, rows, scrollTo, setRows])

  /** Arming is what a click on the Cue column means while locked — the cards' body-click rule. */
  const onRowClick = useCallback(
    (id: string, e: React.MouseEvent) => {
      if (locked && onSetStandby) {
        const row = rows.find((r) => r.id === id)
        if (row && row.cue.id !== activeCueId) onSetStandby(row.cue.id)
        return
      }
      sheet.tableProps.onRowClick(id, e)
    },
    [activeCueId, locked, onSetStandby, rows, sheet.tableProps],
  )

  const armable = locked && onSetStandby && selectedRows.length === 1 && selectedRows[0].cue.id !== activeCueId
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
            onClick={() => armable && onSetStandby(selectedRows[0].cue.id)}
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
                  onReorder(ids.map((id) => Number(id.slice('cue:'.length))).filter(Number.isFinite)),
              }
            : undefined
        }
        minWidth={`${100 + columns.reduce((n, c) => n + trackFloor(c.width), 0)}px`}
        rowClass={(row) =>
          row.cue.id === activeCueId
            ? LIVE_ROW_CLASS
            : row.cue.id === standbyCueId
              ? STANDBY_ROW_CLASS
              : undefined
        }
        firstColumn={{
          label: 'Cue',
          width: '100px',
          render: (row) => {
            const active = row.cue.id === activeCueId
            const standby = row.cue.id === standbyCueId && !active
            return (
              <span className="relative flex min-w-0 items-center gap-2" data-cue-row={row.cue.id}>
                <CueStatePip isActive={active} isStandby={standby} />
                {completedSet.has(row.cue.id) && !active && (
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
                    row.cue.cueNumberAuto ? AUTO_CUE_NUMBER_CLASS : 'font-semibold',
                  )}
                >
                  {locked ? (
                    <span className="inline-block max-w-full px-0.5" title="Cue number">
                      <TruncateStart text={row.cue.cueNumber ? `Q${row.cue.cueNumber}` : '—'} />
                    </span>
                  ) : (
                    <TextCell
                      {...firstColumnCellProps<string>({
                        noun: 'cue',
                        value: row.cue.cueNumber ?? '',
                        label: 'Cue number',
                        onCommit: (next) => {
                          const trimmed = next || null
                          if (trimmed !== (row.cue.cueNumber ?? null)) patch(row.cue.id, { cueNumber: trimmed })
                        },
                      })}
                      mono
                      allowEmpty
                      placeholder="14A"
                      face={
                        <span
                          className="inline-block max-w-full px-0.5"
                          title={
                            row.cue.cueNumberAuto
                              ? 'Auto-numbered from position — double-click to set an explicit cue number'
                              : 'Double-click to edit the cue number'
                          }
                        >
                          <TruncateStart text={row.cue.cueNumber ? `Q${row.cue.cueNumber}` : '—'} />
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
  return row.cue.cueNumber ? `Q${row.cue.cueNumber}` : row.cue.name
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
