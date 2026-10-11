import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Crosshair, EyeOff, Flashlight, Info, Landmark, Plug, Ticket, Trash2, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import {
  checkLanding,
  consecutiveLanding,
  findOverlaps,
  formatPatchAddress,
  lastChannel,
  type AddressedHead,
} from '@/lib/patchAddress'
import { checkKeyLanding, spreadKeys, type KeyedHead } from '@/lib/fixtureKey'
import {
  checkHeadNumberLanding,
  findHeadNumberClashes,
  headNumberDraftError,
  nextHeadNumber,
  parseHeadNumberDraft,
  type NumberedHead,
} from '@/lib/headNumber'
import { toast } from 'sonner'
import { useDeletePatchMutation, useSetHeadNumbersMutation, useSetPatchHiddenMutation, useUpdatePatchMutation } from '@/store/patches'
import { useFixtureListQuery, type Fixture } from '@/store/fixtures'
import { useLocateStateQuery, useToggleLocateMutation, type LocateTarget } from '@/store/locate'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { useHighlight } from '@/components/fixtures-list/useHighlight'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { SpreadPanel, type SpreadPlan } from '@/components/editor/SpreadPanel'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { LegendSwatch } from '@/components/sheet/SheetPage'

/** The clash ring — an Address that overlaps another head, a Head number another head shares —
 *  worn by the cell and by the legend's swatch alike. */
const OVERLAP_CELL_CLASS = 'rounded-sm ring-1 ring-inset ring-destructive bg-destructive/10'
import { SheetTable } from '@/components/sheet/SheetTable'
import { useSheet } from '@/components/sheet/useSheet'
import { AddressCell, type CellAddress } from '@/components/sheet/cells/AddressCell'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { GelCell } from './GelCell'
import { mountFor } from '@/components/stage3d/bodies/mount'
import { EMPTY_LANTERNS, lanternFieldLabel, type LanternIndex } from '@/lib/lanterns'
import { PHONE_FOLDED_CLASS, STRIP_MID_FOLDED_CLASS, WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { firstColumnCellProps, listNames, skippedNote, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import { isVenueOwned, type FixturePatch } from '@/api/patchApi'
import type { VenueChange } from '@/api/venueApi'
import { draftMarker } from '@/lib/venue'
import { FACET_LABEL, facetChips } from '@/lib/patchFacets'

export type PatchColumnKey =
  | 'head'
  | 'address'
  | 'type'
  | 'mode'
  | 'ch'
  | 'key'
  | 'mount'
  | 'lantern'
  | 'angle'
  | 'gel'
  | 'groups'
  | 'stage'
  | 'role'
  /** A venue's draft marker (venue plan session 2) — appended by the route in venue mode, never in the Columns menu. */
  | 'draft'
  /** Whose the head is in a show on a venue (session 3) — led by the route there, never in the Columns menu. */
  | 'source'
  /** A venue head's facet chips in a show (session 5) — closed by the route there, never in the Columns menu. */
  | 'facets'

export const PATCH_COLUMN_LABELS: Record<PatchColumnKey, string> = {
  head: 'Head',
  address: 'Address',
  type: 'Type',
  mode: 'Mode',
  ch: 'Ch',
  key: 'Key',
  mount: 'Mount',
  lantern: 'Lantern',
  angle: 'Angle',
  gel: 'Gel',
  groups: 'Groups',
  stage: 'Stage',
  role: 'Role',
  draft: 'Draft',
  source: 'Source',
  facets: 'This show',
}

export const PATCH_COLUMN_ORDER: PatchColumnKey[] = [
  'head',
  'address',
  'type',
  'mode',
  'ch',
  'key',
  'mount',
  'lantern',
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
  /** The rigging's kind — which says whether the unit hangs from it or stands on it. */
  riggingKind?: string | null
  acceptsBeamAngle: boolean
  acceptsGel: boolean
  /** Hung with a lantern from the library (a generic dimmer): the Lantern column edits it. */
  acceptsLantern?: boolean
  /** The lanterns it is drawn as, in a phrase (`lanternListLabel`); null for a type with none. */
  lanternLabel?: string | null
  /** Whether that phrase is the kind's default rather than a lantern the patch names. */
  lanternIsDefault?: boolean
  /** A DMX type's declared body, for the Lantern column's read-out — *mover · spot*. */
  bodyLabel?: string | null
  /** In a venue, what the draft changed on this head since the installed version (the Draft column). */
  draft?: VenueChange | null
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

/** A patch as the head-number arithmetic sees it. */
function numberedHead(patch: FixturePatch): NumberedHead {
  return { id: patch.id, name: patch.displayName, headNumber: patch.headNumber ?? null }
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
/** The Lantern column's "names none" option: a dimmer drawn as its kind's default. */
const DEFAULT_LANTERN = '__default__'
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
 *  - **Set over N head numbers counts up from the typed one** in visible-row order, and goes out
 *    as **one** atomic request on the bulk route, which judges uniqueness against the batch's final
 *    state — so a renumber that swaps two heads needs none of the Key column's write ordering. A
 *    number another head holds is named before Apply (`lib/headNumber.ts`); a number two heads share
 *    anyway (a sync merge) is ringed like an overlapping address. Clear unnumbers.
 *  - **Clear is refused on Address** (an address cannot be empty), on Key (it cannot be blank), on
 *    Stage (a head is shown or hidden) and on Role (lighting or infrastructure), and offered on
 *    Mount, Angle and Gel, which each have a null. The Fixture column is the row head, not a cell.
 *  - **Mount names how the unit is carried** — a rigging a unit stands on (a ledge, a floor stand)
 *    says *standing* beside its name — and **Lantern** is the library's lantern a conventional is
 *    hung with (stage-view plan session 7): a pick lands on every selected dimmer, the desk derives
 *    each one's kind from it and drops a zoom the new lantern cannot take; a DMX type reads out the
 *    body it declares, and Clear returns a dimmer to its kind's default.
 *  - **Every column is its own kind**, so a commit never crosses into a neighbour: Key, Mount,
 *    Angle, Gel and Stage all take a string, and without the kind a rigging picked over a
 *    Mount→Gel marquee would have landed as a gel code (`PatchSheet.test.tsx`).
 */
export function PatchSheet({
  projectId,
  rows,
  allPatches,
  riggings,
  lanterns = EMPTY_LANTERNS,
  visibleColumns,
  onEditPatch,
  onEditGroup,
  onCountsChange,
  readOnly = false,
}: {
  projectId: number
  /** The rows to draw, already filtered by universe and text, in address order. */
  rows: readonly PatchSheetRow[]
  /** Every patch on the project, filtered or not — the overlap check needs the whole rig. */
  allPatches: readonly FixturePatch[]
  riggings: readonly { uuid: string; name: string }[]
  /** The lantern library — the Lantern column's options. */
  lanterns?: LanternIndex
  visibleColumns: readonly PatchColumnKey[]
  onEditPatch: (patchId: number) => void
  onEditGroup: (groupId: number, name: string) => void
  /** How many rows are selected, for the footer the route draws. */
  onCountsChange?: (selected: number) => void
  /**
   * Nothing edits: no cell takes a value, the name does not rename and no row opens its editor — a
   * venue an operator is looking at (venue plan D3). The desk refuses the write either way
   * (`VENUE_ADMIN_ONLY`); this keeps the sheet from offering one.
   */
  readOnly?: boolean
}) {
  const [updatePatch] = useUpdatePatchMutation()
  const [setHeadNumbers] = useSetHeadNumbersMutation()
  const [deletePatch] = useDeletePatchMutation()
  const { data: fixtures } = useFixtureListQuery()
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()

  const allHeads = useMemo(() => allPatches.map(headOf), [allPatches])
  const hasVenueRows = useMemo(() => allPatches.some(isVenueOwned), [allPatches])
  const [setPatchHidden] = useSetPatchHiddenMutation()
  const overlaps = useMemo(() => findOverlaps(allHeads), [allHeads])
  const allNumbered = useMemo(() => allPatches.map(numberedHead), [allPatches])
  const headClashes = useMemo(() => findHeadNumberClashes(allNumbered), [allNumbered])
  /**
   * What an unnumbered Head cell opens with: the next free number, selected, rather than an empty
   * field under a `1` placeholder that read as the value about to be applied.
   */
  const headSeed = useMemo(() => {
    const next = nextHeadNumber(allNumbered)
    return next == null ? null : { value: String(next), note: `Next free head number — type to change` }
  }, [allNumbered])

  /**
   * One request for the whole renumber — the desk's bulk route, atomic, checked against the
   * batch's final state. Only the heads whose number moves are sent.
   */
  const renumber = useCallback(
    (numbers: { patchId: number; headNumber: number | null }[]) => {
      if (numbers.length === 0) return
      void setHeadNumbers({ projectId, numbers }).unwrap().catch(ignoreReportedError)
    },
    [projectId, setHeadNumbers],
  )

  /** What a typed start would do to these rows, checked against every head on the project. */
  const headLanding = useCallback(
    (batch: readonly SheetRow[], draft: string) => {
      const start = parseHeadNumberDraft(draft)
      if (start === null || start === 'invalid') return null
      const landing = checkHeadNumberLanding(
        (batch as readonly PatchSheetRow[]).map((row) => numberedHead(row.patch)),
        start,
        allNumbered,
      )
      return { lines: landing.lines, error: landing.error }
    },
    [allNumbered],
  )

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
  const lanternOptions = useMemo<SheetOption[]>(
    () => [
      { value: DEFAULT_LANTERN, label: 'Default for the kind' },
      ...lanterns.all.map((l) => ({ value: l.id, label: `${l.name} · ${lanternFieldLabel(l)}` })),
    ],
    [lanterns],
  )

  const columns = useMemo<SheetColumn<PatchSheetRow, PatchColumnKey>[]>(() => {
    const all: SheetColumn<PatchSheetRow, PatchColumnKey>[] = [
      {
        key: 'head',
        label: 'Head',
        kind: 'head',
        width: '64px',
        value: (row) => (row.patch.headNumber == null ? '' : String(row.patch.headNumber)),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="Blank to unnumber"
            emptySeed={headSeed}
            face={
              row.patch.headNumber != null ? (
                <span className="mx-1.5 font-mono text-xs tabular-nums">{row.patch.headNumber}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
            validate={headNumberDraftError}
            plan={headLanding}
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const start = parseHeadNumberDraft(value)
          if (start === 'invalid') return false
          const heads = batch.map((row) => numberedHead(row.patch))
          if (start === null) {
            renumber(heads.filter((h) => h.headNumber != null).map((h) => ({ patchId: h.id, headNumber: null })))
            return true
          }
          // Refused, and said so: the editor names a taken number before Apply, and this is the
          // same refusal for a commit that reached the column any other way.
          const { error, numbers } = checkHeadNumberLanding(heads, start, allNumbered)
          if (error || !numbers) return false
          renumber(
            heads
              .filter((h) => numbers.get(h.id) !== h.headNumber)
              .map((h) => ({ patchId: h.id, headNumber: numbers.get(h.id)! })),
          )
          return true
        },
        clear: (batch) => {
          renumber(
            batch.filter((row) => row.patch.headNumber != null).map((row) => ({ patchId: row.patch.id, headNumber: null })),
          )
        },
        cellClass: (row) => (headClashes.has(row.patch.id) ? OVERLAP_CELL_CLASS : undefined),
        cellTitle: (row) => {
          const other = headClashes.get(row.patch.id)
          return other ? `Head ${row.patch.headNumber} is also ${other.name}` : undefined
        },
      },
      {
        key: 'address',
        label: 'Address',
        kind: 'address',
        width: '96px',
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
        width: 'minmax(128px, 1fr)',
        value: () => undefined,
        display: (row) => {
          const type = [row.patch.manufacturer, row.patch.model].filter(Boolean).join(' ')
          return (
            <span className="mx-1.5 truncate text-xs text-muted-foreground" title={type}>
              {type}
            </span>
          )
        },
      },
      {
        key: 'mode',
        label: 'Mode',
        width: '96px',
        value: () => undefined,
        display: (row) => <span className="mx-1.5 truncate text-xs">{row.patch.modeName ?? ''}</span>,
      },
      {
        key: 'ch',
        label: 'Ch',
        width: '48px',
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
        width: '112px',
        value: (row) => row.patch.key,
        cell: (_row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            placeholder="A key, e.g. par-1"
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
        width: '104px',
        value: (row) => row.patch.riggingUuid ?? NO_MOUNT,
        cell: (row, props) => (
          <OptionCell
            {...(props as React.ComponentProps<typeof OptionCell>)}
            options={mountOptions}
            face={
              row.riggingName ? (
                <span className="mx-1.5 truncate text-xs">
                  {row.riggingName}
                  {mountFor({ kind: row.riggingKind ?? null }) === 'stand' && (
                    <span className="text-muted-foreground"> · standing</span>
                  )}
                </span>
              ) : (
                <span className="mx-1.5 truncate text-xs text-muted-foreground/60">Free</span>
              )
            }
          />
        ),
        cellTitle: (row) =>
          row.riggingName
            ? [
                row.riggingName,
                row.riggingKind?.toLowerCase().replace('_', ' '),
                mountFor({ kind: row.riggingKind ?? null }) === 'stand' ? 'standing' : 'hung',
              ]
                .filter(Boolean)
                .join(' · ')
            : undefined,
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
        key: 'lantern',
        label: 'Lantern',
        kind: 'lantern',
        width: 'minmax(140px, 1fr)',
        value: (row) => (row.acceptsLantern ? (row.patch.lanternType ?? DEFAULT_LANTERN) : undefined),
        cell: (row, props) => (
          <OptionCell
            {...(props as React.ComponentProps<typeof OptionCell>)}
            options={lanternOptions}
            face={
              <span className={cn('mx-1.5 truncate text-xs', row.lanternIsDefault && 'text-muted-foreground')}>
                {row.lanternLabel || '—'}
              </span>
            }
          />
        ),
        // A DMX type's body, which it declares: it is not the operator's to pick.
        display: (row) =>
          row.bodyLabel ? <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.bodyLabel}</span> : null,
        cellTitle: (row) =>
          row.acceptsLantern
            ? `${row.lanternLabel ?? ''}${row.lanternIsDefault ? ' — the default for its kind' : ''}`
            : row.bodyLabel
              ? `Its type's body: ${row.bodyLabel}`
              : undefined,
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const id = value === DEFAULT_LANTERN ? null : value
          for (const row of batch) {
            if (id !== (row.patch.lanternType ?? null)) void put(row.patch.id, { lanternType: id })
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.patch.lanternType != null) void put(row.patch.id, { lanternType: null })
        },
      },
      {
        key: 'angle',
        label: 'Angle',
        kind: 'angle',
        width: '64px',
        value: (row) => (row.acceptsBeamAngle ? String(row.patch.beamAngleDeg ?? '') : undefined),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            mono
            allowEmpty
            placeholder="Blank for the default"
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
        width: '88px',
        value: (row) => (row.acceptsGel ? (row.patch.gelCode ?? '') : undefined),
        // The patch editor's gel picker, not a typed code: search by name or number, filter by
        // brand, pick from swatches (`GelCell`). Its value is the code, `''` for open white.
        cell: (_row, props) => <GelCell {...(props as React.ComponentProps<typeof GelCell>)} />,
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          // `GelCell` commits a library code as it stands, or `''` for open white.
          const code = value || null
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
        // Takes the most of the slack: a head in three groups needs ~200px of badges, where
        // Type is one string that truncates with its whole text on the hover.
        width: 'minmax(160px, 1.5fr)',
        value: () => undefined,
        display: (row) => (
          <div
            className="mx-1 flex min-w-0 gap-1 overflow-hidden"
            title={row.patch.groups.map((g) => g.name).join(', ') || undefined}
          >
            {row.patch.groups.map((g) => (
              <Badge
                key={g.id}
                variant="secondary"
                className={cn('shrink-0 px-1.5 py-0 text-[10px]', !readOnly && 'cursor-pointer hover:bg-accent')}
                onClick={readOnly ? undefined : (e) => {
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
        width: '84px',
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
      {
        // A read-out: what the venue's draft changed on this head (venue plan session 2,
        // `Venue.dc.html` callout 2) — red-amber when it changes output.
        key: 'draft',
        label: 'Draft',
        width: 'minmax(200px,1fr)',
        gutter: false,
        value: (row) => (row.draft ? draftMarker(row.draft) : undefined),
        display: (row) => (row.draft ? <DraftMarker change={row.draft} /> : null),
      },
      {
        // A read-out: whose the head is in a show on a venue (venue plan session 3, `Show.dc.html`
        // callout 1) — the hall for the venue's, the ticket for the show's own.
        key: 'source',
        label: 'Source',
        width: '112px',
        gutter: false,
        value: (row) => (isVenueOwned(row.patch) ? 'VENUE' : 'SHOW'),
        display: (row) => <SourceMark venue={isVenueOwned(row.patch)} />,
      },
      {
        // A read-out: which of a venue head's four facets this show makes its own (venue plan
        // session 5, `Show.dc.html`'s *This show* column) — lit when overridden, amber when the
        // venue has changed one under the override. Set from the fixture sheet.
        key: 'facets',
        label: 'This show',
        width: '248px',
        gutter: false,
        value: (row) => (isVenueOwned(row.patch) ? facetChips(row.patch).map((c) => c.state).join(',') : undefined),
        display: (row) => (isVenueOwned(row.patch) ? <FacetChips patch={row.patch} /> : null),
      },
    ]
    // **A venue's head is the venue's in every column** (venue plan session 3): the desk refuses a
    // patch write to one through a show (`VENUE_OWNED`); what a show can make its own — a facet, whole
    // (session 5) — is set from the fixture sheet, never a cell. So in a rig holding any, each editable
    // column draws a venue head's cell inert and drops it from a commit, naming it on the editor's
    // read-out, as the kit drops a row with nothing to set. A standalone show's columns are untouched.
    const locked = hasVenueRows ? all.map(lockVenueRows) : all
    return visibleColumns.map((key) => locked.find((c) => c.key === key)!).filter(Boolean)
  }, [
    allHeads,
    allNumbered,
    allPatches,
    applyKeyWrites,
    hasVenueRows,
    headClashes,
    headLanding,
    headSeed,
    keyLanding,
    landing,
    mountOptions,
    lanternOptions,
    onEditGroup,
    overlaps,
    put,
    readOnly,
    renumber,
    visibleColumns,
  ])

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
    permission: { entry: !readOnly, clear: !readOnly },
    cellDisabled: readOnly ? () => true : undefined,
    copy,
    noun: 'fixture',
    rowName: patchRowName,
    onOpenRow: readOnly ? undefined : openRow,
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
  // A venue's head is hidden in a show, never unpatched (venue plan session 3): Unpatch takes the
  // show's own heads alone and says why when the selection holds the venue's.
  const venueSelected = selectedRows.filter((row) => isVenueOwned(row.patch))
  const ownSelected = selectedRows.filter((row) => !isVenueOwned(row.patch))
  const unpatchSelection = () => {
    const n = ownSelected.length
    if (n === 0 || venueSelected.length > 0) return
    if (!confirm(`Unpatch ${n} fixture${n === 1 ? '' : 's'}? This removes them from the rig.`)) return
    setUnpatching(true)
    Promise.all(
      ownSelected.map((row) => deletePatch({ projectId, patchId: row.patch.id }).unwrap().catch(ignoreReportedError)),
    ).finally(() => setUnpatching(false))
    sheet.clearByLadder()
  }
  const [hiding, setHiding] = useState(false)
  const hideSelection = () => {
    if (venueSelected.length === 0) return
    setHiding(true)
    // One at a time: a hide the desk refuses (`VENUE_HIDE_IN_USE` — the cannon fires on a cue) is
    // toasted by the middleware, and the rest still go.
    void venueSelected
      .reduce<Promise<unknown>>(
        (chain, row) =>
          chain.then(() =>
            setPatchHidden({ projectId, patchId: row.patch.id, hidden: true }).unwrap().catch(ignoreReportedError),
          ),
        Promise.resolve(),
      )
      .finally(() => setHiding(false))
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
        {!readOnly && venueSelected.length > 0 && (
          <Button
            variant="outline"
            size="sm"
            disabled={hiding}
            onClick={hideSelection}
            title={`Hide the ${venueSelected.length} venue fixture${venueSelected.length === 1 ? '' : 's'} in this show — Hidden brings them back`}
          >
            <EyeOff className="size-3.5" />
            <span className={WORD_CLASS}>Hide</span>
          </Button>
        )}
        {!readOnly && (
          <Button
            variant="outline"
            size="sm"
            className="text-destructive hover:text-destructive"
            disabled={unpatching || ownSelected.length === 0 || venueSelected.length > 0}
            onClick={unpatchSelection}
            title={
              venueSelected.length > 0
                ? "A venue's fixture is hidden in a show, not unpatched — deselect the venue's to unpatch your own"
                : 'Remove the selected fixtures from the rig'
            }
          >
            <Trash2 className="size-3.5" />
            <span className={WORD_CLASS}>Unpatch</span>
          </Button>
        )}
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
        minWidth={`${220 + columns.reduce((n, c) => n + trackFloor(c.width), 0)}px`}
        firstColumn={{
          label: 'Fixture',
          width: '220px',
          render: (row, selected) => readOnly || isVenueOwned(row.patch) ? (
            <>
              <span className={cn('mx-1 min-w-0 flex-1 truncate text-sm', selected ? 'font-semibold' : 'font-medium')}>
                {row.patch.displayName}
              </span>
              <PatchVisibilityIcon patch={row.patch} />
            </>
          ) : (
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
          onOpen: readOnly ? undefined : openRow,
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
      {headClashes.size > 0 && (
        <p className="flex items-center gap-2 border-t px-3 py-1 text-[10.5px] text-muted-foreground">
          <LegendSwatch className={OVERLAP_CELL_CLASS} />
          <Info className="size-3" />
          {headClashes.size} fixture{headClashes.size === 1 ? ' shares its' : 's share a'} head number with another — hover a head for which
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

/**
 * A venue head's draft change as a pill (venue plan session 2): `Added`, `Removed`, or the facet that
 * changed — red-amber when the change reaches output, neutral when it is drawing only.
 */
export function DraftMarker({ change, className }: { change: VenueChange; className?: string }) {
  return (
    <span
      className={cn(
        'mx-1.5 inline-flex h-5 max-w-full items-center truncate rounded-full border px-2 text-[11px] font-medium',
        change.output
          ? 'border-orange-500/50 bg-orange-500/10 text-orange-700 dark:text-orange-300'
          : 'border-border bg-muted/50 text-foreground',
        className,
      )}
      title={`${draftMarker(change)} — ${change.output ? 'changes output' : 'drawing only'}`}
    >
      <span className="truncate">{draftMarker(change)}</span>
    </span>
  )
}

// ─── A show on a venue (venue plan session 3) ─────────────────────────

/**
 * A venue head's four facet chips — Address · Position · Focus · Media — lit where this show
 * overrides the facet and amber where the venue changed it under the override (drift).
 */
export function FacetChips({ patch }: { patch: FixturePatch }) {
  return (
    <span className="mx-1.5 inline-flex items-center gap-1" data-facet-chips>
      {facetChips(patch).map(({ facet, state }) => (
        <span
          key={facet}
          data-facet={facet}
          data-state={state}
          className={cn(
            'rounded px-1 py-px text-[10px] leading-4',
            state === 'venue' && 'text-muted-foreground/60',
            state === 'show' && 'bg-primary/15 font-medium text-primary',
            state === 'drifted' && 'bg-amber-500/20 font-medium text-amber-700 dark:text-amber-300',
          )}
          title={
            state === 'venue'
              ? `${FACET_LABEL[facet]}: the venue's`
              : state === 'drifted'
                ? `${FACET_LABEL[facet]}: this show's — the venue changed it since`
                : `${FACET_LABEL[facet]}: this show's`
          }
        >
          {FACET_LABEL[facet]}
        </span>
      ))}
    </span>
  )
}

/**
 * Whose a head is, as the Source column draws it — the hall for the venue's at the pinned version,
 * the ticket for the show's own (`Show.dc.html` callout 1).
 */
export function SourceMark({ venue }: { venue: boolean }) {
  const Icon = venue ? Landmark : Ticket
  return (
    <span
      className={cn('mx-1.5 inline-flex items-center gap-1.5 text-xs', venue ? 'text-muted-foreground' : 'font-medium')}
      title={venue ? "The venue's, at the version this show is pinned to" : "This show's own"}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      {venue ? 'Venue' : 'This show'}
    </span>
  )
}

const noopCommit = () => {}

/**
 * [column] with a venue's heads made inert: drawn through the column's own cell, disabled, and
 * dropped from every commit, Clear and Spread by the kit (a row whose `value` is undefined is never
 * written), and named on the editor's read-out as the venue's.
 */
function lockVenueRows(column: SheetColumn<PatchSheetRow, PatchColumnKey>): SheetColumn<PatchSheetRow, PatchColumnKey> {
  const cell = column.cell
  if (cell == null) return column
  return {
    ...column,
    value: (row) => (isVenueOwned(row.patch) ? undefined : column.value(row)),
    display: (row) => {
      if (!isVenueOwned(row.patch)) return column.display?.(row)
      const value = column.value(row)
      if (value === undefined) return null
      return (
        <div className="relative h-full min-w-0 flex-1 py-0.5 pr-[18px]" title="The venue's — hide it in this show, or change it in the venue">
          {cell(row, firstColumnCellProps<unknown>({ value, label: column.label, noun: 'fixture', disabled: true, onCommit: noopCommit }))}
        </div>
      )
    },
    skipNote: (rows) => {
      const venue = rows.filter((row) => isVenueOwned(row.patch))
      const own = rows.filter((row) => !isVenueOwned(row.patch))
      const parts: string[] = []
      if (venue.length > 0) {
        const names = listNames(venue.map(patchRowName), 'fixture')
        parts.push(`${names} ${venue.length === 1 ? 'is' : 'are'} the venue's · skipped`)
      }
      if (own.length > 0) parts.push(column.skipNote?.(own) ?? skippedNote(own.map(patchRowName)))
      return parts.join(' · ')
    },
  }
}
