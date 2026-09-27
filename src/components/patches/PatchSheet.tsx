import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Crosshair, EyeOff, Flashlight, Info, Plug, Trash2, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { findGel, GELS } from '@/data/gels'
import {
  checkLanding,
  consecutiveLanding,
  findOverlaps,
  formatPatchAddress,
  lastChannel,
  type AddressedHead,
} from '@/lib/patchAddress'
import { checkKeyLanding, spreadKeys, type KeyedHead } from '@/lib/fixtureKey'
import { toast } from 'sonner'
import { useDeletePatchMutation, useUpdatePatchMutation } from '@/store/patches'
import { useFixtureListQuery, type Fixture } from '@/store/fixtures'
import { useLocateStateQuery, useToggleLocateMutation, type LocateTarget } from '@/store/locate'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { useHighlight } from '@/components/fixtures-list/useHighlight'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { SpreadPanel, type SpreadPlan } from '@/components/editor/SpreadPanel'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { LegendSwatch } from '@/components/sheet/SheetPage'

/** The overlap ring on an Address cell — worn by the cell and by the legend's swatch alike. */
const OVERLAP_CELL_CLASS = 'rounded-sm ring-1 ring-inset ring-destructive bg-destructive/10'
import { SheetTable } from '@/components/sheet/SheetTable'
import { useSheet } from '@/components/sheet/useSheet'
import { AddressCell, type CellAddress } from '@/components/sheet/cells/AddressCell'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { PHONE_FOLDED_CLASS, STRIP_MID_FOLDED_CLASS, WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { firstColumnCellProps, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import type { FixturePatch } from '@/api/patchApi'

export type PatchColumnKey =
  | 'address'
  | 'type'
  | 'mode'
  | 'ch'
  | 'key'
  | 'mount'
  | 'angle'
  | 'gel'
  | 'groups'
  | 'stage'
  | 'role'

export const PATCH_COLUMN_LABELS: Record<PatchColumnKey, string> = {
  address: 'Address',
  type: 'Type',
  mode: 'Mode',
  ch: 'Ch',
  key: 'Key',
  mount: 'Mount',
  angle: 'Angle',
  gel: 'Gel',
  groups: 'Groups',
  stage: 'Stage',
  role: 'Role',
}

export const PATCH_COLUMN_ORDER: PatchColumnKey[] = [
  'address',
  'type',
  'mode',
  'ch',
  'key',
  'mount',
  'angle',
  'gel',
  'groups',
  'stage',
  'role',
]

/** One head on the patch list. */
export interface PatchSheetRow extends SheetRow {
  patch: FixturePatch
  riggingName: string | null
  acceptsBeamAngle: boolean
  acceptsGel: boolean
}

export function patchRowId(patchId: number): string {
  return `patch:${patchId}`
}

/** The batch as the key arithmetic sees it. Pure, so it needs no hook and no identity. */
function keyedHeads(batch: readonly SheetRow[]): KeyedHead[] {
  return (batch as readonly PatchSheetRow[]).map((row) => ({
    id: row.patch.id,
    key: row.patch.key,
    name: row.patch.displayName,
  }))
}

function headOf(patch: FixturePatch): AddressedHead {
  return {
    id: patch.id,
    name: patch.displayName,
    universe: patch.universe,
    channel: patch.startChannel,
    footprint: patch.channelCount ?? 1,
  }
}

const NO_MOUNT = ''
const STAGE_OPTIONS: SheetOption[] = [
  { value: 'shown', label: 'Shown' },
  { value: 'hidden', label: 'Hidden' },
]
/** `FixturePatch.infrastructure`: a lighting fixture, or infrastructure hidden everywhere but here and Channels. */
const ROLE_OPTIONS: SheetOption[] = [
  { value: 'lighting', label: 'Lighting' },
  { value: 'infrastructure', label: 'Infra' },
]

/**
 * The patch list as a sheet (CLAUDE.md §Sheet kit): the patch list's rows on the
 * programmer's grid — 36px rows, a sticky name column, drag-select from the name column for rows
 * and from a value column for cells, one editor per column spread over the selection.
 *
 * Three things are this surface's rather than the kit's:
 *
 *  - **Set over N addresses lands them consecutively by footprint from the typed one**, and Spread on
 *    the Address column is From + Step in visible-row order (blank step = footprint). The
 *    arithmetic is `lib/patchAddress.ts`, pinned there; the write is N PUTs, one per head whose
 *    channel moved. There is no bulk route (a new lighting7 route is a restart, and Chris's call),
 *    so a batch can half-apply if a PUT fails mid-way — the editor refuses a landing that would
 *    collide *before* Apply, which is the only overlap check there is: the patch PUT has none.
 *  - **The overlap is on the cell**: a destructive ring on the Address cell with the other head on
 *    its title, and a legend line in the footer — found here, not later in a sheet.
 *  - **Clear is refused on Address** (an address cannot be empty), on Key (it cannot be blank), on
 *    Stage (a head is shown or hidden) and on Role (lighting or infrastructure), and offered on
 *    Mount, Angle and Gel, which each have a null. The Fixture column is the row head, not a cell.
 *  - **Every column is its own kind**, so a commit never crosses into a neighbour: Key, Mount,
 *    Angle, Gel and Stage all take a string, and without the kind a rigging picked over a
 *    Mount→Gel marquee would have landed as a gel code (`PatchSheet.test.tsx`).
 */
export function PatchSheet({
  projectId,
  rows,
  allPatches,
  riggings,
  visibleColumns,
  onEditPatch,
  onEditGroup,
  onCountsChange,
}: {
  projectId: number
  /** The rows to draw, already filtered by universe and text, in address order. */
  rows: readonly PatchSheetRow[]
  /** Every patch on the project, filtered or not — the overlap check needs the whole rig. */
  allPatches: readonly FixturePatch[]
  riggings: readonly { uuid: string; name: string }[]
  visibleColumns: readonly PatchColumnKey[]
  onEditPatch: (patchId: number) => void
  onEditGroup: (groupId: number, name: string) => void
  /** How many rows are selected, for the footer the route draws. */
  onCountsChange?: (selected: number) => void
}) {
  const [updatePatch] = useUpdatePatchMutation()
  const [deletePatch] = useDeletePatchMutation()
  const { data: fixtures } = useFixtureListQuery()
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()

  const allHeads = useMemo(() => allPatches.map(headOf), [allPatches])
  const overlaps = useMemo(() => findOverlaps(allHeads), [allHeads])

  const put = useCallback(
    (patchId: number, body: Record<string, unknown>) =>
      updatePatch({ projectId, patchId, ...body }).unwrap().catch(ignoreReportedError),
    [projectId, updatePatch],
  )

  /**
   * One key PUT, reporting whether it landed. The batch re-key has to know: the backend refuses a
   * duplicate key per PUT, so `planKeyWrites`' order only holds while every step succeeds — the
   * step after a failure would walk onto a key the failed one was supposed to have vacated. The
   * error itself is already on screen (the toast middleware reports it).
   */
  const putKey = useCallback(
    (patchId: number, key: string) =>
      updatePatch({ projectId, patchId, key })
        .unwrap()
        .then(
          () => true,
          () => false,
        ),
    [projectId, updatePatch],
  )

  /**
   * The batch's PUTs, **one at a time and stopped on the first failure**. Sequential because each
   * PUT checks key uniqueness on its own, so `planKeyWrites`' order only holds while every step
   * lands: the step after a failure would walk onto a key the failed one was supposed to have
   * vacated. A failure leaves the batch half-applied — the same caveat the Address column carries,
   * there being no bulk route — so it says how far it got rather than stopping silently.
   */
  const applyKeyWrites = useCallback(
    async (steps: readonly { id: number; key: string }[]) => {
      keyBatchInFlight.current = true
      try {
        for (let i = 0; i < steps.length; i++) {
          if (await putKey(steps[i].id, steps[i].key)) continue
          // The failure itself is already on screen (the toast middleware reports it); what only
          // this loop knows is that the rest of the batch never went out.
          if (i > 0) {
            toast.warning(`Re-keyed ${i} of ${steps.length} fixtures`, {
              description: 'The rest were left as they were — fix the one that failed and try again.',
            })
          }
          return
        }
      } finally {
        keyBatchInFlight.current = false
      }
    },
    [putKey],
  )

  /**
   * **One re-key batch at a time.** `write` has to answer synchronously, so the loop above runs
   * detached and the editor closes on top of it — which leaves a window in which a second batch
   * could be planned against an `allPatches` the first batch's PUTs have not landed in yet. Two
   * plans each assuming they are the only writer is how a batch walks onto a key the other one is
   * mid-way through vacating: the server refuses the duplicate, and both are left half-applied.
   * A wait-your-turn refusal is a second of patience against a rig nobody can re-key by hand.
   */
  const keyBatchInFlight = useRef(false)

  /** What a typed key would do to these rows, checked against every patch on the project. */
  const keyLanding = useCallback(
    (batch: readonly SheetRow[], draft: string) => {
      const heads = keyedHeads(batch)
      return checkKeyLanding(heads, spreadKeys(draft.trim(), heads.length), allPatches)
    },
    [allPatches],
  )

  /** The landing of a start over these rows, checked against the whole rig. */
  const landing = useCallback(
    (batch: readonly SheetRow[], start: number) =>
      checkLanding(
        allHeads,
        consecutiveLanding(
          (batch as readonly PatchSheetRow[]).map((row) => headOf(row.patch)),
          start,
        ),
      ),
    [allHeads],
  )

  const mountOptions = useMemo<SheetOption[]>(
    () => [{ value: NO_MOUNT, label: 'Free' }, ...riggings.map((r) => ({ value: r.uuid, label: r.name }))],
    [riggings],
  )

  const columns = useMemo<SheetColumn<PatchSheetRow, PatchColumnKey>[]>(() => {
    const all: SheetColumn<PatchSheetRow, PatchColumnKey>[] = [
      {
        key: 'address',
        label: 'Address',
        kind: 'address',
        width: '104px',
        value: (row): CellAddress => ({
          universe: row.patch.universe,
          channel: row.patch.startChannel,
          footprint: row.patch.channelCount ?? 1,
        }),
        cell: (row, props) => (
          <AddressCell
            {...(props as React.ComponentProps<typeof AddressCell>)}
            landing={landing}
            clash={overlaps.get(row.patch.id)?.name ?? null}
          />
        ),
        write: (batch, value) => {
          if (!isCellAddress(value)) return false
          const heads = batch.map((row) => headOf(row.patch))
          const plan = consecutiveLanding(heads, value.channel)
          // Refused, and said so: the editor blocks a colliding landing before Apply, and this is
          // the same refusal for a commit that reached the column any other way.
          if (checkLanding(allHeads, plan).error) return false
          for (const row of batch) {
            const next = plan.get(row.patch.id)
            if (next != null && next !== row.patch.startChannel) void put(row.patch.id, { startChannel: next })
          }
          return true
        },
        clearRefusal: 'An address cannot be empty',
        spread: (batch): SpreadPlan | null => {
          if (batch.length === 0) return null
          const universe = batch[0].patch.universe
          const mixed = batch.some((row) => row.patch.universe !== universe)
          return {
            kind: 'address',
            col: 'address',
            label: 'Address',
            count: batch.length,
            universe,
            footprints: batch.map((row) => row.patch.channelCount ?? 1),
            check: (channels) => {
              if (mixed) return 'Select heads on one universe to spread their addresses'
              return checkLanding(allHeads, new Map(batch.map((row, i) => [row.patch.id, channels[i]]))).error
            },
            apply: (channels) => {
              batch.forEach((row, i) => {
                if (channels[i] !== row.patch.startChannel) void put(row.patch.id, { startChannel: channels[i] })
              })
            },
          }
        },
        cellClass: (row) => (overlaps.has(row.patch.id) ? OVERLAP_CELL_CLASS : undefined),
        cellTitle: (row) => {
          const other = overlaps.get(row.patch.id)
          return other
            ? `Overlaps ${other.name} (${formatPatchAddress(other.universe, other.channel)} to ${formatPatchAddress(other.universe, lastChannel(other))})`
            : undefined
        },
      },
      {
        key: 'type',
        label: 'Type',
        width: 'minmax(180px, 1fr)',
        value: () => undefined,
        display: (row) => (
          <span className="mx-1.5 truncate text-xs text-muted-foreground">
            {[row.patch.manufacturer, row.patch.model].filter(Boolean).join(' ')}
          </span>
        ),
      },
      {
        key: 'mode',
        label: 'Mode',
        width: '118px',
        value: () => undefined,
        display: (row) => <span className="mx-1.5 truncate text-xs">{row.patch.modeName ?? ''}</span>,
      },
      {
        key: 'ch',
        label: 'Ch',
        width: '56px',
        align: 'right',
        value: () => undefined,
        display: (row) => (
          <span className="mx-1.5 font-mono text-xs tabular-nums">{row.patch.channelCount ?? 1}</span>
        ),
      },
      {
        key: 'key',
        label: 'Key',
        kind: 'key',
        width: '132px',
        value: (row) => row.patch.key,
        cell: (_row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            placeholder="par-1"
            plan={keyLanding}
          />
        ),
        // **A typed key spreads over the selection**, the way a typed address does: one head takes it
        // as it is, several count up from it in visible-row order, continuing the number, the
        // separator and the zero padding the typed key already carries (`lib/fixtureKey.ts`). The
        // landing is refused before Apply where it would take a key another head holds — and the
        // writes are **sequential, ordered and stopped on the first failure**, because unlike the
        // address PUT this one enforces uniqueness on every call.
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          if (keyBatchInFlight.current) {
            toast.warning('Still re-keying', { description: 'Wait for the last batch to land, then try again.' })
            return false
          }
          const typed = value.trim()
          const heads = keyedHeads(batch)
          // The landing carries the writes it had to plan to answer, so this does not re-plan them.
          const { error, writes } = checkKeyLanding(heads, spreadKeys(typed, heads.length), allPatches)
          if (error || !writes) return false
          void applyKeyWrites(writes)
          return true
        },
        clearRefusal: 'A key cannot be empty',
      },
      {
        key: 'mount',
        label: 'Mount',
        kind: 'mount',
        width: '118px',
        value: (row) => row.patch.riggingUuid ?? NO_MOUNT,
        cell: (row, props) => (
          <OptionCell
            {...(props as React.ComponentProps<typeof OptionCell>)}
            options={mountOptions}
            face={
              row.riggingName ? (
                <span className="mx-1.5 truncate text-xs">{row.riggingName}</span>
              ) : (
                <span className="mx-1.5 truncate text-xs text-muted-foreground/60">Free</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const uuid = value === NO_MOUNT ? null : value
          for (const row of batch) if (uuid !== row.patch.riggingUuid) void put(row.patch.id, { riggingUuid: uuid })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.patch.riggingUuid != null) void put(row.patch.id, { riggingUuid: null })
        },
      },
      {
        key: 'angle',
        label: 'Angle',
        kind: 'angle',
        width: '72px',
        value: (row) => (row.acceptsBeamAngle ? String(row.patch.beamAngleDeg ?? '') : undefined),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="25"
            face={
              row.patch.beamAngleDeg != null ? (
                <span className="mx-1.5 font-mono text-xs tabular-nums">{row.patch.beamAngleDeg}°</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
            validate={(draft) => {
              const text = draft.trim()
              if (text === '') return null
              const n = Number(text)
              return Number.isInteger(n) && n >= 0 && n <= 180 ? null : 'A beam angle is a whole number of degrees, 0–180'
            }}
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : Number(value)
          if (next != null && !Number.isInteger(next)) return false
          for (const row of batch) if (row.acceptsBeamAngle && next !== row.patch.beamAngleDeg) void put(row.patch.id, { beamAngleDeg: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.patch.beamAngleDeg != null) void put(row.patch.id, { beamAngleDeg: null })
        },
      },
      {
        key: 'gel',
        label: 'Gel',
        kind: 'gel',
        width: '96px',
        value: (row) => (row.acceptsGel ? (row.patch.gelCode ?? '') : undefined),
        cell: (row, props) => {
          const gel = findGel(row.patch.gelCode)
          return (
            <TextCell
              {...(props as React.ComponentProps<typeof TextCell>)}
              mono
              allowEmpty
              placeholder="L201"
              face={
                row.patch.gelCode ? (
                  <span className="mx-1.5 flex items-center gap-1.5">
                    <span
                      className="size-3 shrink-0 rounded-sm border border-border/60"
                      style={{ background: gel?.color ?? 'transparent' }}
                      aria-hidden
                    />
                    <span className="font-mono text-xs">{row.patch.gelCode}</span>
                  </span>
                ) : (
                  <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
                )
              }
              validate={(draft) => {
                const code = draft.trim().toUpperCase()
                return code === '' || GELS.some((g) => g.code === code) ? null : `“${code}” is not a gel in the library`
              }}
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const code = value.trim().toUpperCase() || null
          for (const row of batch) if (row.acceptsGel && code !== row.patch.gelCode) void put(row.patch.id, { gelCode: code })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.patch.gelCode != null) void put(row.patch.id, { gelCode: null })
        },
      },
      {
        key: 'groups',
        label: 'Groups',
        width: '150px',
        value: () => undefined,
        display: (row) => (
          <div className="mx-1 flex min-w-0 flex-wrap gap-1 overflow-hidden">
            {row.patch.groups.map((g) => (
              <Badge
                key={g.id}
                variant="secondary"
                className="cursor-pointer px-1.5 py-0 text-[10px] hover:bg-accent"
                onClick={(e) => {
                  e.stopPropagation()
                  onEditGroup(g.id, g.name)
                }}
              >
                {g.name}
              </Badge>
            ))}
          </div>
        ),
      },
      {
        key: 'stage',
        label: 'Stage',
        kind: 'stage',
        width: '64px',
        value: (row) => (row.patch.stageHidden ? 'hidden' : 'shown'),
        cell: (_row, props) => (
          <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={STAGE_OPTIONS} />
        ),
        write: (batch, value) => {
          if (value !== 'shown' && value !== 'hidden') return false
          const hidden = value === 'hidden'
          for (const row of batch) if (hidden !== row.patch.stageHidden) void put(row.patch.id, { stageHidden: hidden })
          return true
        },
        clearRefusal: 'A head is either shown on the stage or hidden — pick one',
      },
      {
        key: 'role',
        label: 'Role',
        kind: 'role',
        width: '84px',
        value: (row) => (row.patch.infrastructure ? 'infrastructure' : 'lighting'),
        cell: (_row, props) => (
          <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={ROLE_OPTIONS} />
        ),
        write: (batch, value) => {
          if (value !== 'lighting' && value !== 'infrastructure') return false
          const infrastructure = value === 'infrastructure'
          for (const row of batch) {
            if (infrastructure !== (row.patch.infrastructure ?? false)) void put(row.patch.id, { infrastructure })
          }
          return true
        },
        clearRefusal: 'A head is either a lighting fixture or infrastructure — pick one',
      },
    ]
    return visibleColumns.map((key) => all.find((c) => c.key === key)!).filter(Boolean)
  }, [allHeads, allPatches, applyKeyWrites, keyLanding, landing, mountOptions, onEditGroup, overlaps, put, visibleColumns])

  const copy = useCallback(
    (cellCount: number) => ({
      setTitle: `Set the ${cellCount} selected cell${cellCount === 1 ? '' : 's'} (Enter)`,
      clearTitle: `Clear the ${cellCount} selected cell${cellCount === 1 ? '' : 's'} (Backspace)`,
    }),
    [],
  )
  const openRow = useCallback((row: PatchSheetRow) => onEditPatch(row.patch.id), [onEditPatch])
  const sheet = useSheet<PatchSheetRow, PatchColumnKey>({
    rows,
    columns,
    permission: { entry: true, clear: true },
    copy,
    noun: 'fixture',
    rowName: patchRowName,
    onOpenRow: openRow,
  })
  const { selectedRows, cellCount, cellSelection } = sheet
  const selectedCount = selectedRows.length
  useEffect(() => {
    onCountsChange?.(selectedCount)
  }, [onCountsChange, selectedCount])

  // ── The surface's verbs: Locate · Highlight · Unpatch ──
  const locateTargets = useMemo<LocateTarget[]>(
    () => selectedRows.map((row) => ({ type: 'fixture', key: row.patch.key })),
    [selectedRows],
  )
  const isLocated = (t: LocateTarget) =>
    locateState?.targets.some((x) => x.type === t.type && x.key === t.key) ?? false
  const allLocated = locateTargets.length > 0 && locateTargets.every(isLocated)
  const locateSelection = () => {
    const toToggle = allLocated ? locateTargets : locateTargets.filter((t) => !isLocated(t))
    for (const target of toToggle) toggleLocate(target).unwrap().catch(ignoreReportedError)
  }
  const fixtureByKey = useMemo(() => new Map((fixtures ?? []).map((f) => [f.key, f])), [fixtures])
  const highlightTargets = useCallback(
    (): Fixture[] => selectedRows.flatMap((row) => fixtureByKey.get(row.patch.key) ?? []),
    [fixtureByKey, selectedRows],
  )
  const highlight = useHighlight(highlightTargets)
  const [unpatching, setUnpatching] = useState(false)
  const unpatchSelection = () => {
    const n = selectedRows.length
    if (n === 0) return
    if (!confirm(`Unpatch ${n} fixture${n === 1 ? '' : 's'}? This removes them from the rig.`)) return
    setUnpatching(true)
    Promise.all(
      selectedRows.map((row) => deletePatch({ projectId, patchId: row.patch.id }).unwrap().catch(ignoreReportedError)),
    ).finally(() => setUnpatching(false))
    sheet.clearByLadder()
  }

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
            spread={<SpreadPanel host="popover" plans={sheet.spreadPlans} drivableHint="address" className={PHONE_FOLDED_CLASS} />}
          />
        )}
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant={allLocated ? 'default' : 'outline'}
              size="sm"
              onClick={locateSelection}
              className={cn(STRIP_MID_FOLDED_CLASS, allLocated && 'bg-sky-500 text-white hover:bg-sky-600')}
            >
              <Crosshair className="size-3.5" />
              <span className={WORD_CLASS}>Locate</span>
            </Button>
          </TooltipTrigger>
          <TooltipContent>{allLocated ? 'Release locate on the selection' : 'Locate the selection: white beam at centre'}</TooltipContent>
        </Tooltip>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant={highlight.isActive ? 'default' : 'outline'}
              size="sm"
              className={STRIP_MID_FOLDED_CLASS}
              onPointerDown={highlight.press}
              onPointerUp={highlight.release}
              onPointerCancel={highlight.release}
              onPointerLeave={highlight.release}
            >
              <Flashlight className="size-3.5" />
              <span className={WORD_CLASS}>Highlight</span>
            </Button>
          </TooltipTrigger>
          <TooltipContent>Hold: full intensity on the selection, restored on release</TooltipContent>
        </Tooltip>
        <Button
          variant="outline"
          size="sm"
          className="text-destructive hover:text-destructive"
          disabled={unpatching}
          onClick={unpatchSelection}
          title="Remove the selected fixtures from the rig"
        >
          <Trash2 className="size-3.5" />
          <span className={WORD_CLASS}>Unpatch</span>
        </Button>
        <Button variant="ghost" size="sm" onClick={sheet.clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const rowNoun = selectedRows.length === 1 ? 'fixture' : 'fixtures'

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* Row C's own `@container`, with the queries on the child — see `ProgrammerGrid`. */}
      <div className="@container">
        <SelectionBar
          rowLabel={selectedRows.length > 0 ? `${selectedRows.length} ${rowNoun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${selectedRows.length} ${rowNoun} · ${sheet.family ?? ''} — edit once, applies to all`}
          family={sheet.family}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
        />
      </div>
      <SheetTable<PatchSheetRow, PatchColumnKey>
        {...sheet.tableProps}
        minWidth={`${240 + columns.reduce((n, c) => n + trackFloor(c.width), 0)}px`}
        firstColumn={{
          label: 'Fixture',
          width: '240px',
          render: (row, selected) => (
            <>
              {/* **A double click renames the head — in the same popover every value cell opens.**
                  A single click on this column is the row selection and a press on it starts the
                  row marquee, so the rename is the second gesture, the split the value cells make
                  (CLAUDE.md §The cell editor's three forms); the trigger's click bubbles to the
                  sticky cell's `onRowClick`, and the marquee arms on `pointerdown` regardless of
                  what it lands on. The pencil beside it — the kit's, `firstColumn.onOpen` — still
                  opens the full patch editor, and so does ⏎ with this one row selected. */}
              <span className="relative min-w-0 flex-1">
                <TextCell
                  {...firstColumnCellProps<string>({
                    noun: 'fixture',
                    value: row.patch.displayName,
                    label: 'Fixture name',
                    onCommit: (next) => {
                      if (next !== row.patch.displayName) void put(row.patch.id, { displayName: next })
                    },
                  })}
                  face={
                    <span
                      className={cn('mx-1 truncate text-sm', selected ? 'font-semibold' : 'font-medium')}
                      title="Double-click to rename"
                    >
                      {row.patch.displayName}
                    </span>
                  }
                />
              </span>
              <PatchVisibilityIcon patch={row.patch} />
            </>
          ),
          onOpen: openRow,
          openLabel: (row) => `Edit ${row.patch.displayName}`,
        }}
      />
      {overlaps.size > 0 && (
        <p className="flex items-center gap-2 border-t px-3 py-1 text-[10.5px] text-muted-foreground">
          <LegendSwatch className={OVERLAP_CELL_CLASS} />
          <Info className="size-3" />
          {overlaps.size} address{overlaps.size === 1 ? '' : 'es'} overlap another fixture — hover an address for which
        </p>
      )}
      {/* Keeps the selection's cells honest for the footer's count even when nothing is drawn. */}
      <span className="sr-only">{cellSelection.count} cells selected</span>
    </div>
  )
}

/** A head's name in a skip read-out — an Angle over a head with no beam angle, say. */
function patchRowName(row: PatchSheetRow): string {
  return row.patch.displayName
}

function isCellAddress(value: unknown): value is CellAddress {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as CellAddress).channel === 'number' &&
    typeof (value as CellAddress).universe === 'number'
  )
}

/** The floor of a grid track — the `minmax()` floor or the fixed width — for the table's minimum width. */
function trackFloor(width: string): number {
  const m = /^(?:minmax\()?\s*(\d+)px/.exec(width)
  return m ? Number(m[1]) : 96
}

/**
 * The row head's one visibility mark: infrastructure (hidden everywhere but here and Channels)
 * outranks stage-hidden, which it implies — so a head shows at most one.
 */
function PatchVisibilityIcon({ patch }: { patch: FixturePatch }) {
  const className = 'relative size-3 shrink-0 text-muted-foreground'
  if (patch.infrastructure) {
    return <Plug className={className} role="img" aria-label="Infrastructure — hidden everywhere but Patches and Channels" />
  }
  if (patch.stageHidden) return <EyeOff className={className} role="img" aria-label="Hidden from Stage view" />
  return null
}
