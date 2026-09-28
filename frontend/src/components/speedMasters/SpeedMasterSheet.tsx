import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { Trash2, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { BeatIndicator } from '@/components/BeatIndicator'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { ReadOutButton } from '@/components/sheet/ReadOutButton'
import { reportSheetWriteFailure } from '@/components/sheet/reportSheetWriteFailure'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable } from '@/components/sheet/SheetTable'
import { NumberCell } from '@/components/sheet/cells/NumberCell'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { listNames, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import { WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { useSheet } from '@/components/sheet/useSheet'
import { formatBpm } from '../../hooks/useBpmDraft'
import {
  DEFAULT_FOLLOW_RATIO,
  FOLLOW_RATIOS,
  SPEED_MASTER_USAGES,
  eligibleFollowTargets,
  followRatioOf,
  followTargetOf,
  followerTempoLockedReason,
  formatFollowRatio,
  leaderLabelOf,
  leaderNameOf,
  usageLabel,
} from '../../lib/speedMasterModel'
import { setSpeedMasterBpm, tapSpeedMaster, useSaveSpeedMasterMutation, useSpeedMasterLiveQuery } from '../../store/speedMasters'
import type { SpeedMaster, UpdateSpeedMasterRequest } from '../../api/speedMastersApi'
import type { SpeedMasterLiveState } from '../../api/speedMastersWsApi'
import { useSpeedMasterDelete } from './useSpeedMasterDelete'

export type SpeedMasterColumnKey =
  | 'beat'
  | 'bpm'
  | 'tap'
  | 'start'
  | 'follows'
  | 'ratio'
  | 'usage'
  | 'usedBy'
  | 'notes'

/** One master of the bank. `live` is its running state, joined by uuid — null off the current project. */
export interface SpeedMasterSheetRow extends SheetRow {
  master: SpeedMaster
  live: SpeedMasterLiveState | null
}

export function speedMasterRowId(masterId: number): string {
  return `sm:${masterId}`
}

/** The clocks' range — `MasterClock.MIN_BPM` / `MAX_BPM`, and the detail sheet's Starting BPM rule. */
const MIN_BPM = 20
const MAX_BPM = 300

/** The Follows column's first option — the value a manual master holds, and what Clear writes. */
const MANUAL = 'manual'
/** The Usage column's "routes nothing". Never on the wire: the PUT wants `usage: null`. */
const USAGE_NONE = 'none'

const USAGE_OPTIONS: SheetOption[] = [
  { value: USAGE_NONE, label: 'None' },
  ...SPEED_MASTER_USAGES.map((u) => ({ value: u, label: usageLabel(u) ?? u })),
]

const RATIO_OPTIONS: SheetOption[] = FOLLOW_RATIOS.map((r) => ({ value: `${r.num}/${r.den}`, label: r.label }))

/** Why a live-tempo cell is inert off the current project (library-sheets plan D12, §1's bug). */
const OFF_PROJECT_TEMPO = 'Another project — its masters are not the running ones, so the live tempo is read-only here'

/** `M2` — how the sheet names a master in a read-out. */
function masterShort(master: SpeedMaster): string {
  return `M${master.masterIndex}`
}

/** A null uuid is master 1 on every tempo write (`speedMastersWsApi`) — the one spelling the socket takes for it. */
function uuidForWrites(master: SpeedMaster): string | null {
  return master.masterIndex === 1 ? null : master.uuid
}

/** A live tempo as the board draws it: `128.0` — one decimal, which a tapped tempo carries. */
function bpmText(bpm: number): string {
  return formatBpm(bpm).toFixed(1)
}

/** A stored tempo as the board draws it: `120`, `96`, `92.5`. */
function formatStart(bpm: number): string {
  return Number.isInteger(bpm) ? String(bpm) : bpm.toFixed(1)
}

export interface SpeedMasterSheetProps {
  projectId: number
  /** The rows to draw — the bank, narrowed by the library row's filter. */
  masters: readonly SpeedMaster[]
  /** The whole bank, for the leader lookups and the Follows column's eligibility. */
  bank: readonly SpeedMaster[]
  /**
   * The page is the **current** project's. Off it, BPM and TAP are read-only (D12): they write the
   * running show's clocks, and the running show is some other project's. Everything else stays
   * editable — the REST routes are `withProject`.
   */
  isCurrentProject: boolean
  onOpenMaster: (master: SpeedMaster) => void
}

/**
 * **The Speed Masters sheet** (library-sheets plan §3.2) — the bank on the sheet kit, replacing the
 * row cards `SpeedMasterRow` drew. The name column is `libraryNameColumn`'s; the pencil (and ⏎ over
 * one row) opens `SpeedMasterDetailSheet`.
 *
 * **Two BPMs, two columns** (D7). *BPM* is the live tempo: a Set over two masters writes both
 * running clocks now, through `speedMasters.setBpm` — the WS write click-to-type uses. *Start* is
 * the stored row (`PUT {bpm}`), labelled as the boot tempo. The desk couples the two on the running
 * project — a live change is written back to the row after 750 ms, and a PUT of a stored tempo
 * retunes the running clock — so on the current project they converge; the columns stay apart
 * because they are two facts with two routes, and off the current project only Start is writable.
 *
 * **Skipped rows** (D12): a follower has no tempo of its own, so its BPM and Start are `undefined`,
 * as are master 1's Follows and a manual master's Ratio. The kit drops them before `write` and the
 * editor's read-out names them. Those cells are **blank** — no `display` — as the programmer draws a
 * column a row resolves nothing for, and a click there clears the selection as it does there; the
 * em-dash stays the mark of an empty but settable cell (Notes). Only BPM keeps a read-out for its
 * skipped rows, because a follower's derived tempo is worth reading. The Follows column adds one skip the kit cannot know in advance:
 * the leader chosen is the **origin's**, carried to every selected row, so `write` also drops a row
 * that may not follow it — itself, or one of its own followers (the desk's
 * `SPEED_MASTER_FOLLOW_CYCLE`) — and says so.
 *
 * **Refusals are this sheet's to say** (D14): `saveSpeedMaster` stays in `SILENT_ENDPOINTS`, and
 * a refused write is toasted by code, keyed per column, so a batch replaces rather than stacks.
 */
export function SpeedMasterSheet({ projectId, masters, bank, isCurrentProject, onOpenMaster }: SpeedMasterSheetProps) {
  const [saveMaster] = useSaveSpeedMasterMutation()
  const { data: live } = useSpeedMasterLiveQuery(undefined, { skip: !isCurrentProject })

  const rows = useMemo<SpeedMasterSheetRow[]>(() => {
    const liveByUuid = new Map((isCurrentProject ? (live ?? []) : []).map((m) => [m.uuid, m]))
    return masters.map((master) => ({
      id: speedMasterRowId(master.id),
      master,
      live: liveByUuid.get(master.uuid) ?? null,
    }))
  }, [isCurrentProject, live, masters])

  const master1Uuid = useMemo(() => bank.find((m) => m.masterIndex === 1)?.uuid ?? null, [bank])
  /** Master 1 has two spellings — its uuid, and the null that means it. Compare on this. */
  const canonical = useCallback((uuid: string | null) => (uuid === master1Uuid ? null : uuid), [master1Uuid])

  /** Each master's eligible leaders — memoised once per bank, since the option cell is `memo`. */
  const eligibleByUuid = useMemo(
    () => new Map(bank.map((m) => [m.uuid, eligibleFollowTargets(bank, m)])),
    [bank],
  )
  const followOptionsByUuid = useMemo(
    () =>
      new Map(
        bank.map((m) => [
          m.uuid,
          [
            { value: MANUAL, label: 'Manual' },
            ...(eligibleByUuid.get(m.uuid) ?? []).map((t) => ({ value: t.uuid, label: `M${t.masterIndex} · ${t.name}` })),
          ] satisfies SheetOption[],
        ]),
      ),
    [bank, eligibleByUuid],
  )

  /** One PUT per master, its refusal toasted by code under the column's key (D14). */
  const put = useCallback(
    (master: SpeedMaster, column: string, body: UpdateSpeedMasterRequest) => {
      saveMaster({ projectId, masterId: master.id, ...body })
        .unwrap()
        .catch((err: unknown) => reportSheetWriteFailure(err, { key: `speed-masters:${column}` }))
    },
    [projectId, saveMaster],
  )

  /** *M2 and M4 follow M1* — the one reason a tempo cell skips on the running project. */
  const followerSkipNote = useCallback(
    (skipped: readonly SpeedMasterSheetRow[]) => {
      const names = listNames(skipped.map((r) => masterShort(r.master)), 'master')
      const leaders = new Set(skipped.map((r) => leaderLabelOf(bank, followTargetOf(r.master))))
      const verb = skipped.length === 1 ? 'follows' : 'follow'
      return leaders.size === 1
        ? `${names} ${verb} ${[...leaders][0]} · skipped`
        : `${names} ${verb} other masters · skipped`
    },
    [bank],
  )

  const columns = useMemo<SheetColumn<SpeedMasterSheetRow, SpeedMasterColumnKey>[]>(
    () => [
      {
        key: 'beat',
        label: '',
        width: '28px',
        value: () => undefined,
        // Off the current project there is no clock of this master's to pulse — and master 1's
        // null uuid would pulse the running show's.
        display: (row) =>
          isCurrentProject ? (
            <BeatIndicator
              master={{ uuid: uuidForWrites(row.master), index: row.master.masterIndex }}
              className="mx-auto"
            />
          ) : null,
      },
      {
        key: 'bpm',
        label: 'BPM',
        kind: 'bpm',
        width: '96px',
        // Rounded to the tenth the face shows: a tapped tempo is a long float, and the editor's field
        // would otherwise open on `122.39122047244094`.
        value: (row) =>
          isCurrentProject && followRatioOf(row.master) == null ? formatBpm(row.live?.bpm ?? row.master.bpm) : undefined,
        cell: (row, props) => (
          <NumberCell
            {...(props as React.ComponentProps<typeof NumberCell>)}
            min={MIN_BPM}
            max={MAX_BPM}
            format={bpmText}
            note="The live tempo — written to the running clock now"
            face={
              <span className="mx-1.5 flex items-center gap-1.5">
                <span className={cn('font-mono text-sm font-bold tabular-nums', row.live == null && 'text-muted-foreground')}>
                  {bpmText(row.live?.bpm ?? row.master.bpm)}
                </span>
                {row.live?.source === 'TAP' && (
                  <span className="text-[9px] uppercase tracking-wide text-muted-foreground">tapped</span>
                )}
              </span>
            }
          />
        ),
        display: (row) => {
          const follow = followRatioOf(row.master)
          const bpm = isCurrentProject ? (row.live?.bpm ?? row.master.bpm) : row.master.bpm
          const title = !isCurrentProject
            ? OFF_PROJECT_TEMPO
            : follow
              ? followerTempoLockedReason(row.master.name, follow.num, follow.den, leaderNameOf(bank, followTargetOf(row.master)))
              : undefined
          return (
            <span className="mx-1.5 flex min-w-0 items-center gap-1.5" title={title}>
              <span className="font-mono text-sm font-bold tabular-nums text-muted-foreground">{bpmText(bpm)}</span>
              {follow && (
                <span className="truncate text-[10px] text-muted-foreground">
                  {formatFollowRatio(follow.num, follow.den)} of {leaderLabelOf(bank, followTargetOf(row.master))}
                </span>
              )}
            </span>
          )
        },
        write: (batch, value) => {
          // Belt and braces for D12: `value` is already undefined off the current project, so the
          // kit never hands this a row there — but this is the write that retunes a running clock.
          if (!isCurrentProject || typeof value !== 'number' || value < MIN_BPM || value > MAX_BPM) return false
          for (const row of batch) setSpeedMasterBpm(uuidForWrites(row.master), value)
          return true
        },
        clearRefusal: 'A master always runs at a tempo — type one instead',
        skipNote: (skipped) =>
          isCurrentProject ? followerSkipNote(skipped) : `${listNames(skipped.map((r) => masterShort(r.master)), 'master')} · another project’s tempo · skipped`,
      },
      {
        key: 'tap',
        label: 'Tap',
        width: '56px',
        value: () => undefined,
        display: (row) => {
          const follow = followRatioOf(row.master)
          const reason = !isCurrentProject
            ? OFF_PROJECT_TEMPO
            : follow
              ? followerTempoLockedReason(row.master.name, follow.num, follow.den, leaderNameOf(bank, followTargetOf(row.master)))
              : null
          return (
            <ReadOutButton
              onClick={() => tapSpeedMaster(uuidForWrites(row.master))}
              disabled={reason != null}
              title={reason ?? `Tap tempo for master ${row.master.masterIndex}`}
              aria-label={`Tap tempo for master ${row.master.masterIndex}`}
              className="border font-bold tracking-[0.08em] enabled:text-foreground"
            >
              TAP
            </ReadOutButton>
          )
        },
      },
      {
        key: 'start',
        label: 'Start',
        kind: 'start',
        width: '72px',
        value: (row) => (followRatioOf(row.master) == null ? formatBpm(row.master.bpm) : undefined),
        cell: (_row, props) => (
          <NumberCell
            {...(props as React.ComponentProps<typeof NumberCell>)}
            min={MIN_BPM}
            max={MAX_BPM}
            format={formatStart}
            note={
              isCurrentProject
                ? 'The boot tempo — on the running show it retunes the live clock too'
                : 'The boot tempo — what this master starts at when the project loads'
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'number' || value < MIN_BPM || value > MAX_BPM) return false
          for (const row of batch) if (row.master.bpm !== value) put(row.master, 'start', { bpm: value })
          return true
        },
        clearRefusal: 'A master always boots at a tempo — type one instead',
        skipNote: followerSkipNote,
      },
      {
        key: 'follows',
        label: 'Follows',
        kind: 'leader',
        width: 'minmax(120px, 0.8fr)',
        value: (row) => {
          if (row.master.masterIndex === 1) return undefined
          if (followRatioOf(row.master) == null) return MANUAL
          return followTargetOf(row.master) ?? master1Uuid ?? MANUAL
        },
        cell: (row, props) => {
          const target = followTargetOf(row.master)
          const following = followRatioOf(row.master) != null
          return (
            <OptionCell
              {...(props as React.ComponentProps<typeof OptionCell>)}
              options={followOptionsByUuid.get(row.master.uuid) ?? [{ value: MANUAL, label: 'Manual' }]}
              face={
                following ? (
                  <span className="mx-1.5 truncate text-xs">
                    <span className="font-mono font-bold">{leaderLabelOf(bank, target)}</span>{' '}
                    <span className="text-muted-foreground">{leaderNameOf(bank, target)}</span>
                  </span>
                ) : (
                  <span className="mx-1.5 text-xs text-muted-foreground">Manual</span>
                )
              }
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          if (value === MANUAL) {
            for (const row of batch) {
              if (followRatioOf(row.master) != null) put(row.master, 'follows', { followNum: null, followDen: null, followTargetUuid: null })
            }
            return true
          }
          if (!bank.some((m) => m.uuid === value)) return false
          // The leader is the origin's choice, carried to every selected row — so a row that may not
          // follow it (itself, or one of its own followers) is dropped here, before the desk's
          // SPEED_MASTER_FOLLOW_CYCLE would refuse it, and named.
          const refused: SpeedMaster[] = []
          for (const row of batch) {
            if (!(eligibleByUuid.get(row.master.uuid) ?? []).some((m) => m.uuid === value)) {
              refused.push(row.master)
              continue
            }
            const ratio = followRatioOf(row.master)
            if (ratio != null && canonical(followTargetOf(row.master)) === canonical(value)) continue
            const next = ratio ?? DEFAULT_FOLLOW_RATIO
            // Both halves of the pair with the target, never `bpm` beside them — the busk rail's
            // and the detail sheet's rule. A fresh link starts at the default ratio.
            put(row.master, 'follows', { followTargetUuid: canonical(value), followNum: next.num, followDen: next.den })
          }
          if (refused.length > 0) {
            const leader = bank.find((m) => m.uuid === value)
            toast.info(
              `${listNames(refused.map(masterShort), 'master')} skipped — ${refused.length === 1 ? 'it cannot' : 'they cannot'} follow ${leader ? masterShort(leader) : 'that master'}: a master may not follow itself or one of its own followers`,
              { id: 'sheet-write:speed-masters:follows-skip' },
            )
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch) {
            if (followRatioOf(row.master) != null) put(row.master, 'follows', { followNum: null, followDen: null, followTargetUuid: null })
          }
        },
        skipNote: (skipped) =>
          `${listNames(skipped.map((r) => masterShort(r.master)), 'master')} is the global tempo — it follows nothing · skipped`,
      },
      {
        key: 'ratio',
        label: 'Ratio',
        kind: 'ratio',
        width: '64px',
        value: (row) => {
          const ratio = followRatioOf(row.master)
          return ratio ? `${ratio.num}/${ratio.den}` : undefined
        },
        cell: (row, props) => {
          const ratio = followRatioOf(row.master)!
          return (
            <OptionCell
              {...(props as React.ComponentProps<typeof OptionCell>)}
              options={RATIO_OPTIONS}
              // A pair off the vocabulary still prints (`formatFollowRatio`), rather than a dash.
              face={<span className="mx-1.5 text-xs font-bold">{formatFollowRatio(ratio.num, ratio.den)}</span>}
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const [num, den] = value.split('/').map(Number)
          if (!Number.isInteger(num) || !Number.isInteger(den) || num <= 0 || den <= 0) return false
          for (const row of batch) {
            const ratio = followRatioOf(row.master)
            // Both halves, no target, no bpm: a ratio-only patch carries the stored leader forward
            // server-side — the busk rail's chips' rule.
            if (ratio != null && (ratio.num !== num || ratio.den !== den)) put(row.master, 'ratio', { followNum: num, followDen: den })
          }
          return true
        },
        clearRefusal: 'A link has a ratio — unlink it from Follows instead',
        skipNote: (skipped) =>
          `${listNames(skipped.map((r) => masterShort(r.master)), 'master')} ${skipped.length === 1 ? 'runs' : 'run'} manually · skipped`,
      },
      {
        key: 'usage',
        label: 'Usage',
        kind: 'usage',
        width: '96px',
        value: (row) => row.master.usage ?? USAGE_NONE,
        cell: (_row, props) => <OptionCell {...(props as React.ComponentProps<typeof OptionCell>)} options={USAGE_OPTIONS} />,
        write: (batch, value) => {
          if (typeof value !== 'string' || !USAGE_OPTIONS.some((o) => o.value === value)) return false
          // A usage belongs to one master per project, so claiming one over several is refused
          // before anything is sent — the desk would accept the first and 409 the rest.
          if (value !== USAGE_NONE && batch.length > 1) {
            toast.error(`A usage belongs to one master — set ${usageLabel(value)} on one row at a time`, {
              id: 'sheet-write:speed-masters:usage',
            })
            return false
          }
          const next = value === USAGE_NONE ? null : value
          for (const row of batch) if ((row.master.usage ?? null) !== next) put(row.master, 'usage', { usage: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.master.usage != null) put(row.master, 'usage', { usage: null })
        },
      },
      {
        key: 'usedBy',
        label: 'Used by',
        width: '64px',
        align: 'right',
        value: () => undefined,
        display: (row) => (
          <span
            className="mx-1.5 font-mono text-xs tabular-nums text-muted-foreground"
            title="Saved references — look effects, cue effects, per-layer overrides and followers"
          >
            {row.master.referenceCount > 0 ? row.master.referenceCount : '—'}
          </span>
        ),
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(120px, 1fr)',
        value: (row) => row.master.notes ?? '',
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              row.master.notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.master.notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value.trim()
          for (const row of batch) if (next !== (row.master.notes ?? null)) put(row.master, 'notes', { notes: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.master.notes) put(row.master, 'notes', { notes: null })
        },
      },
    ],
    [bank, canonical, eligibleByUuid, followOptionsByUuid, followerSkipNote, isCurrentProject, master1Uuid, put],
  )

  const copy = useCallback((cellCount: number) => {
    const cells = `${cellCount} selected cell${cellCount === 1 ? '' : 's'}`
    return { setTitle: `Set the ${cells} (Enter)`, clearTitle: `Clear the ${cells} (Backspace)` }
  }, [])
  const openRow = useCallback((row: SpeedMasterSheetRow) => onOpenMaster(row.master), [onOpenMaster])
  const sheet = useSheet<SpeedMasterSheetRow, SpeedMasterColumnKey>({
    rows,
    columns,
    permission: PERMISSION,
    copy,
    noun: 'master',
    rowName: speedMasterRowName,
    onOpenRow: openRow,
  })
  const { selectedRows, cellCount, clearByLadder, setRows } = sheet

  const { run: runDelete, busy: deleting, dialog: deleteDialog } = useSpeedMasterDelete({
    projectId,
    onDeleted: () => clearByLadder(),
    onKept: (kept) => setRows(kept.map((m) => speedMasterRowId(m.id))),
  })

  const selectedMasters = selectedRows.map((row) => row.master)
  const deletable = selectedMasters.filter((m) => m.masterIndex !== 1)
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
          />
        )}
        <Button
          variant="outline"
          size="sm"
          className="text-destructive hover:text-destructive"
          disabled={deleting || deletable.length === 0}
          onClick={() => void runDelete(selectedMasters)}
          title={
            deletable.length === 0
              ? 'Master 1 is the global tempo — every unassigned effect resolves to it'
              : `Delete ${deletable.length} master${deletable.length === 1 ? '' : 's'}${deletable.length < selectedMasters.length ? ' (master 1 is skipped)' : ''}`
          }
        >
          <Trash2 className="size-3.5" />
          <span className={WORD_CLASS}>Delete</span>
        </Button>
        <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const rowNoun = selectedRows.length === 1 ? 'master' : 'masters'
  const firstColumn = useMemo(
    () =>
      libraryNameColumn<SpeedMasterSheetRow>({
        label: 'Master',
        width: `${NAME_WIDTH}px`,
        noun: 'master',
        name: (row) => row.master.name,
        rename: (row, next) => put(row.master, 'name', { name: next }),
        prefix: (row) => masterShort(row.master),
        badges: (row) =>
          row.master.masterIndex === 1 ? (
            <Badge variant="secondary" className="text-[10px]">
              Global
            </Badge>
          ) : null,
        onOpen: openRow,
        openLabel: (row) => `Edit ${masterShort(row.master)} · ${row.master.name}`,
      }),
    [openRow, put],
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
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
      <SheetTable<SpeedMasterSheetRow, SpeedMasterColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + columns.reduce((n, c) => n + trackFloor(c.width), 0)}px`}
        firstColumn={firstColumn}
      />
      {deleteDialog}
    </div>
  )
}

/**
 * The name column's track. With the tracks' floors it sums to 912px, which is what the iPad frame
 * (1180×820) leaves the sheet with the app sidebar open — measured there at 940 on 2026-09-23; the
 * first cut summed to 1032 and scrolled Notes off the side.
 */
const NAME_WIDTH = 196

/** Both cell gestures, always: the sheet's refusals are per row (`value: undefined`) and per column. */
const PERMISSION = { entry: true, clear: true }

/** A master in a kit-drawn skip read-out — the sheet's columns all name their own, but a fallback. */
function speedMasterRowName(row: SpeedMasterSheetRow): string {
  return masterShort(row.master)
}

function trackFloor(width: string): number {
  const m = /^(?:minmax\()?\s*(\d+)px/.exec(width)
  return m ? Number(m[1]) : 96
}
