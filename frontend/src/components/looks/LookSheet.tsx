import { useCallback, useMemo, useState } from 'react'
import { ArrowRight, AudioWaveform, Clapperboard, CopyPlus, Download, Hand, LayoutGrid, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { CopyToProjectSheet } from '@/components/sheet/CopyToProjectSheet'
import { CountReadOut, ReadOnlyNote } from '@/components/sheet/LibraryReadOuts'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { libraryPermission } from '@/components/sheet/libraryScope'
import { reportSheetWriteFailure } from '@/components/sheet/reportSheetWriteFailure'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable } from '@/components/sheet/SheetTable'
import { TextCell } from '@/components/sheet/cells/TextCell'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useSheet } from '@/components/sheet/useSheet'
import { useDuplicateBatch } from '@/components/sheet/useDuplicateBatch'
import { useInclude } from '@/components/programmer/useInclude'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import { handPickUp } from '@/store/hand'
import { useCopyLookMutation, useSaveLookMutation } from '@/store/looks'
import type { LookSummary } from '@/api/looksApi'
import { LookPreviewSwatches } from './lookValueChips'
import { useLookDelete } from './useLookDelete'

export type LookColumnKey = 'families' | 'preview' | 'contents' | 'notes' | 'layers' | 'pages'

export interface LookSheetRow extends SheetRow {
  look: LookSummary
}

export function lookRowId(lookId: number): string {
  return `look:${lookId}`
}

export interface LookSheetProps {
  projectId: number
  /** The rows to draw — the library, narrowed by the library row's filter. */
  looks: readonly LookSummary[]
  /** Every Look in the library, for Duplicate's `(Copy n)` names. */
  library: readonly LookSummary[]
  /** The library is the running project's. Off it the sheet is the read-only scope (D12). */
  isCurrentProject: boolean
  projectName: string
  onOpenLook: (look: LookSummary) => void
}

/**
 * **The Look library as a sheet** (library-sheets plan §3.2, session 2) — replacing the
 * `LookListRow`s and their hover `…` menus.
 *
 * **What a Look holds is recorded, and changed by Include** (D5, `LookDetailSheet`'s rule): the
 * sheet never grows a value grid for one. Families, Preview and Contents are read-outs derived
 * server-side, Cue layers and Busk pages are counts, and **Notes is the one value cell** — beside the
 * name's rename in the first column. Both write `PUT {name}` / `PUT {notes}` alone, metadata only:
 * a body without `rows` or `effects` is what tells the desk to leave the contents alone.
 *
 * **The row verbs are on the bar** (D11): Include and Pick up act on one Look and are disabled over
 * several with the reason; Duplicate, Copy to… and Delete take the batch. Pick up is not drawn off
 * the running project at all — the hand is project-scoped server-side, so a pick-up there resolves
 * nothing.
 *
 * **Another project's library is the read-only scope** (D12, `libraryPermission`): the marquee and
 * the selection still work, the rename, the Notes cell and every value verb are refused with the
 * reason, and *Copy to…* — which makes the Look yours — is the one live verb. No row opens there:
 * the detail sheet edits the name and notes, which the scope refuses.
 */
export function LookSheet({ projectId, looks, library, isCurrentProject, projectName, onOpenLook }: LookSheetProps) {
  const [saveLook] = useSaveLookMutation()
  const [copyLook] = useCopyLookMutation()
  const { include, isLoading: isIncluding } = useInclude(projectId)
  const scope = useMemo(() => libraryPermission(isCurrentProject, projectName), [isCurrentProject, projectName])

  const rows = useMemo<LookSheetRow[]>(() => looks.map((look) => ({ id: lookRowId(look.id), look })), [looks])

  /** One metadata PUT, its refusal toasted by code under the column's key (D14). */
  const put = useCallback(
    (look: LookSummary, column: string, body: { name?: string; notes?: string | null }) => {
      saveLook({ projectId, lookId: look.id, ...body })
        .unwrap()
        // The desk's own sentence names a clash (`A look named '…' already exists`), so no
        // per-code phrasing is needed — only the key, so a batch's refusals replace.
        .catch((err: unknown) => reportSheetWriteFailure(err, { key: `looks:${column}` }))
    },
    [projectId, saveLook],
  )

  const columns = useMemo<SheetColumn<LookSheetRow, LookColumnKey>[]>(
    () => [
      {
        key: 'families',
        label: 'Families',
        width: 'minmax(120px, 170px)',
        value: () => undefined,
        // Derived server-side from the rows, and several is normal — the reason a Look has no type.
        display: (row) => (
          <span className="mx-1.5 flex min-w-0 gap-1 overflow-hidden">
            {row.look.families.map((family) => (
              <span
                key={family}
                className="inline-flex h-[18px] shrink-0 items-center rounded-full bg-muted px-1.5 text-[10px] font-medium"
              >
                {FAMILY_LABELS[family].singular}
              </span>
            ))}
          </span>
        ),
      },
      {
        key: 'preview',
        label: 'Preview',
        width: '120px',
        value: () => undefined,
        display: (row) =>
          row.look.preview.length > 0 ? (
            <span className="mx-1.5 flex min-w-0 overflow-hidden">
              <LookPreviewSwatches preview={row.look.preview} />
            </span>
          ) : row.look.effectCount > 0 ? (
            // No literal to show: a Look that only runs effects says so with the desk's FX glyph.
            <AudioWaveform className="mx-2 size-3.5 text-violet-400" aria-label="Effects only" />
          ) : null,
      },
      {
        key: 'contents',
        label: 'Contents',
        width: 'minmax(140px, 190px)',
        value: () => undefined,
        display: (row) => (
          <span className="mx-1.5 truncate text-xs text-muted-foreground" title={describeLookContents(row.look)}>
            {describeLookContents(row.look)}
          </span>
        ),
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(140px, 1fr)',
        value: (row) => row.look.notes ?? '',
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              row.look.notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.look.notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value.trim()
          for (const row of batch) if (next !== (row.look.notes ?? null)) put(row.look, 'notes', { notes: next })
          return true
        },
        clear: (batch) => {
          for (const row of batch) if (row.look.notes) put(row.look, 'notes', { notes: null })
        },
      },
      {
        key: 'layers',
        label: 'Cue layers',
        width: '88px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut
            count={row.look.layerCount}
            icon={<Clapperboard className="size-3" />}
            title={`Used by ${row.look.layerCount} cue layer${row.look.layerCount === 1 ? '' : 's'} — these gate the delete`}
          />
        ),
      },
      {
        key: 'pages',
        label: 'Busk pages',
        width: '88px',
        value: () => undefined,
        display: (row) => (
          <CountReadOut
            count={row.look.buskPageCount}
            icon={<LayoutGrid className="size-3" />}
            title={`On ${row.look.buskPageCount} busk page${row.look.buskPageCount === 1 ? '' : 's'} — a hint, not a use`}
          />
        ),
      },
    ],
    [put],
  )

  const openRow = useCallback((row: LookSheetRow) => onOpenLook(row.look), [onOpenLook])
  const sheet = useSheet<LookSheetRow, LookColumnKey>({
    rows,
    columns,
    permission: scope.permission,
    copy: scope.copy,
    cellDisabled: scope.readOnly ? cellsInert : undefined,
    noun: 'look',
    rowName: lookRowName,
    onOpenRow: isCurrentProject ? openRow : undefined,
  })
  const { selectedRows, cellCount, clearByLadder, setRows } = sheet
  const selected = useMemo(() => selectedRows.map((row) => row.look), [selectedRows])

  const { run: runDelete, busy: deleting, dialog: deleteDialog } = useLookDelete({
    projectId,
    onDeleted: () => clearByLadder(),
    onKept: (kept) => setRows(kept.map((look) => lookRowId(look.id))),
  })

  const [copyOpen, setCopyOpen] = useState(false)
  const [copying, setCopying] = useState<readonly LookSummary[]>([])

  const copyHere = useCallback(
    (look: LookSummary, newName: string) =>
      copyLook({ projectId, lookId: look.id, targetProjectId: projectId, newName }).unwrap(),
    [copyLook, projectId],
  )
  const { duplicate, duplicating } = useDuplicateBatch({ library, name: lookName, copy: copyHere, toastKey: 'looks' })

  const count = selected.length
  const noun = count === 1 ? 'look' : 'looks'
  const verbs =
    count > 0 ? (
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
        <LibraryVerb
          icon={Download}
          label="Include"
          title={`Include ${selected[0]?.name ?? 'this look'} into the programmer — edit it on the rig, then Update`}
          disabledReason={scope.reason ?? (isIncluding ? 'Including…' : oneRecordReason('Include', 'look', count))}
          onClick={() => void include({ kind: 'LOOK', lookId: selected[0].id })}
        />
        {/* Only on the running project: the hand is project-scoped server-side, and a pick-up from
            another project's library would resolve nothing and be dropped with no reply. */}
        {isCurrentProject && (
          <LibraryVerb
            icon={Hand}
            label="Pick up"
            title={`Pick up ${selected[0]?.name ?? 'this look'} — place it on another screen`}
            disabledReason={oneRecordReason('Pick up', 'look', count)}
            onClick={() => handPickUp('LOOK', selected[0].id)}
          />
        )}
        <LibraryVerb
          icon={CopyPlus}
          label="Duplicate"
          title={`Duplicate ${count} ${noun} in this project`}
          disabledReason={scope.reason ?? (duplicating ? 'Duplicating…' : null)}
          onClick={() => void duplicate(selected)}
        />
        <LibraryVerb
          icon={ArrowRight}
          label="Copy to…"
          title={`Copy ${count} ${noun} to another project`}
          onClick={() => {
            setCopying(selected)
            setCopyOpen(true)
          }}
        />
        <LibraryVerb
          icon={Trash2}
          label="Delete"
          destructive
          title={`Delete ${count} ${noun}`}
          disabledReason={scope.reason ?? (deleting ? 'Deleting…' : null)}
          onClick={() => void runDelete(selected)}
        />
        <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const firstColumn = useMemo(
    () =>
      libraryNameColumn<LookSheetRow>({
        label: 'Look',
        width: `${NAME_WIDTH}px`,
        noun: 'look',
        name: (row) => row.look.name,
        rename: (row, next) => put(row.look, 'name', { name: next }),
        renameDisabled: scope.readOnly ? () => true : undefined,
        onOpen: isCurrentProject ? openRow : undefined,
        openLabel: (row) => `Open ${row.look.name}`,
      }),
    [isCurrentProject, openRow, put, scope.readOnly],
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${noun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${count} ${noun} · ${sheet.family ?? ''} — edit once, applies to all`}
          family={sheet.family}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          strip={scope.reason != null ? <ReadOnlyNote reason={scope.reason} /> : undefined}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          foldForStrip={false}
        />
      </div>
      <SheetTable<LookSheetRow, LookColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + MIN_TRACKS}px`}
        firstColumn={firstColumn}
      />
      {deleteDialog}
      <CopyToProjectSheet<LookSummary>
        open={copyOpen}
        onOpenChange={setCopyOpen}
        items={copying}
        noun="look"
        name={(look) => look.name}
        sourceProjectId={projectId}
        copy={(look, targetProjectId, newName) =>
          copyLook({ projectId, lookId: look.id, targetProjectId, newName }).unwrap()
        }
      />
    </div>
  )
}

/**
 * The name column's track. With the other tracks' floors (120 + 120 + 140 + 140 + 88 + 88 = 696)
 * the sheet needs 896px, inside the ~940 the iPad frame (1180×820) leaves it with the sidebar open.
 */
const NAME_WIDTH = 200
const MIN_TRACKS = 696

function lookRowName(row: LookSheetRow): string {
  return row.look.name
}

function lookName(look: LookSummary): string {
  return look.name
}

/** Every value cell is inert in the read-only scope — the rename and Notes alike. */
function cellsInert(): boolean {
  return true
}

/**
 * What a Look covers, in one line — `12 fixtures · 24 rows · 1 fx`. The row's subtitle before the
 * sheet (`LookListRow`'s `describeContents`), with the effect count folded in now that it has no
 * badge of its own. A Look with deferred effects says so: that is what makes it pad-eligible.
 */
export function describeLookContents(look: LookSummary): string {
  if (look.targetCount === 0 && look.effectCount === 0) return 'Empty'
  const parts: string[] = []
  if (look.targetCount > 0) parts.push(`${look.targetCount} fixture${look.targetCount === 1 ? '' : 's'}`)
  if (look.rowCount > 0) parts.push(`${look.rowCount} row${look.rowCount === 1 ? '' : 's'}`)
  if (look.effectCount > 0) parts.push(`${look.effectCount} fx`)
  if (look.hasDeferredEffects) parts.push('effects follow the layer')
  return parts.join(' · ')
}
