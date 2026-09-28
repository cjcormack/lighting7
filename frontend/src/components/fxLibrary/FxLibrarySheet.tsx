import { useCallback, useMemo } from 'react'
import { AudioWaveform, Braces, Clock, GitFork, Lock, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CountReadOut, ReadOnlyNote } from '@/components/sheet/LibraryReadOuts'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { libraryPermission } from '@/components/sheet/libraryScope'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable } from '@/components/sheet/SheetTable'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useSheet } from '@/components/sheet/useSheet'
import { useUpdateFxDefinitionMutation, type FxDefinition } from '@/store/fxDefinitions'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import { cn } from '@/lib/utils'
import { EFFECT_MODE_LABELS, FX_SOURCE_LABELS, OUTPUT_TYPE_LABELS, type FxSource } from './fxLibraryModel'
import { useFxDefinitionDelete, type FxDeleteItem } from './useFxDefinitionDelete'

export type FxColumnKey = 'output' | 'mode' | 'timing' | 'params' | 'drives' | 'source'

export interface FxSheetRow extends SheetRow {
  /** Absent on a category divider. */
  entry?: EffectLibraryEntry
  source?: FxSource
  /** A custom row's definition — the discriminator, and what the rename and the delete write. */
  definition?: FxDefinition
  /** What the row is called — a custom definition's own name, else the id as words. */
  name?: string
}

/** A member row — every row but a divider carries its entry, source and name. */
export type FxMemberRow = FxSheetRow & { entry: EffectLibraryEntry; source: FxSource; name: string }

export function fxRowId(effectId: string): string {
  return `fx:${effectId}`
}

function isMember(row: FxSheetRow): row is FxMemberRow {
  return row.entry != null && row.source != null && row.name != null
}

export interface FxLibrarySheetProps {
  /** The rows to draw, dividers included — the route groups them under *All* (`groupRows`). */
  rows: readonly FxSheetRow[]
  /** The library is the running project's. Off it every verb is refused (D12). */
  isCurrentProject: boolean
  projectName: string
  /** Open a row's record: a custom row its definition, a script's effect its script, a built-in its detail. */
  onOpen: (row: FxMemberRow) => void
  /** Fork a built-in (D9) — the route mints the id, creates it and opens the editor. */
  onFork: (entry: EffectLibraryEntry) => void
  forking: boolean
}

/**
 * **The FX Library as a sheet** (library-sheets plan §3.2, session 4), replacing a shadcn `Table`
 * with collapsible category rows. The route draws the category chips and groups the rows under
 * category dividers; this draws the columns and the bar.
 *
 * **Every column is a read-out** — Output · Mode · Timing · Params · Drives · Source, straight off
 * the registry entry — so nothing here is in the marquee and there are no cell verbs. The one thing
 * a cell edits is a **custom** row's name (`PUT fx/definitions/{id} {name}`); a built-in's and a
 * script's effect's names are the desk's and the script's.
 *
 * **Source is the definition list read against the library** (`fxSourceOf`): custom where a
 * definition's `effectId` is the entry's id, *Script* for any other `USER` entry. That is also what
 * a row opens (§1's first bug): a custom row its definition, a script-registered row its **script**,
 * a built-in its read-only detail.
 *
 * **The verbs are Fork · Delete · Deselect** (D11). Fork takes one built-in and is refused over
 * anything else with the reason; Delete takes the batch and skips every row that is not custom by
 * name, before anything is sent (D13). **Off the running project nothing is live** (D12):
 * `/fx/definitions` always writes to the running show, and the FX Library has no copy route, so
 * the scope's reason stands on every verb and on the rename.
 */
export function FxLibrarySheet({ rows, isCurrentProject, projectName, onOpen, onFork, forking }: FxLibrarySheetProps) {
  const [updateDefinition] = useUpdateFxDefinitionMutation()
  // Its own sentence: the FX Library has no copy route, so *copy it here* would name a verb it lacks.
  const scope = useMemo(
    () => libraryPermission(isCurrentProject, projectName, `Not the running project — ${FX_READ_ONLY_REASON}`),
    [isCurrentProject, projectName],
  )

  const columns = useMemo<SheetColumn<FxSheetRow, FxColumnKey>[]>(
    () => [
      {
        key: 'output',
        label: 'Output',
        width: '100px',
        value: () => undefined,
        display: (row) => row.entry && <ReadText>{OUTPUT_TYPE_LABELS[row.entry.outputType] ?? row.entry.outputType}</ReadText>,
      },
      {
        key: 'mode',
        label: 'Mode',
        width: '96px',
        value: () => undefined,
        display: (row) => row.entry && <ReadText>{EFFECT_MODE_LABELS[row.entry.effectMode] ?? row.entry.effectMode}</ReadText>,
      },
      {
        key: 'timing',
        label: 'Timing',
        width: '86px',
        value: () => undefined,
        display: (row) =>
          row.entry &&
          (row.entry.timingSource === 'WALL_CLOCK' ? (
            <ReadText title="Wall clock — a fixed cycle length, scaled by a rate master">
              <Clock className="size-3" />
              Clock
            </ReadText>
          ) : (
            <ReadText title="Beat — follows a speed master">
              <AudioWaveform className="size-3" />
              Beat
            </ReadText>
          )),
      },
      {
        key: 'params',
        label: 'Params',
        width: '70px',
        value: () => undefined,
        display: (row) =>
          row.entry && (
            <CountReadOut
              count={row.entry.parameters.length}
              title={row.entry.parameters.map((p) => p.name).join(', ')}
            />
          ),
      },
      {
        key: 'drives',
        label: 'Drives',
        width: 'minmax(150px, 1fr)',
        value: () => undefined,
        display: (row) =>
          row.entry && (
            <span className="mx-1.5 flex min-w-0 gap-1 overflow-hidden" title={row.entry.compatibleProperties.join(', ')}>
              {row.entry.compatibleProperties.map((prop) => (
                <span
                  key={prop}
                  className="inline-flex h-[18px] shrink-0 items-center rounded-full bg-muted px-1.5 font-mono text-[10px]"
                >
                  {prop}
                </span>
              ))}
            </span>
          ),
      },
      {
        key: 'source',
        label: 'Source',
        width: '110px',
        value: () => undefined,
        display: (row) => row.source && <SourceBadge source={row.source} />,
      },
    ],
    [],
  )

  const openRow = useCallback((row: FxSheetRow) => {
    if (isMember(row)) onOpen(row)
  }, [onOpen])

  const sheet = useSheet<FxSheetRow, FxColumnKey>({
    rows,
    columns,
    permission: scope.permission,
    copy: scope.copy,
    noun: 'effect',
    rowName: (row) => row.name ?? row.id,
    onOpenRow: openRow,
  })
  const { selectedRows, clearByLadder } = sheet
  const selected = useMemo(() => selectedRows.filter(isMember), [selectedRows])

  const { run: runDelete, busy: deleting } = useFxDefinitionDelete({ onDeleted: () => clearByLadder() })

  const rename = useCallback(
    (row: FxSheetRow, next: string) => {
      const name = next.trim()
      if (row.definition == null || name === '' || name === row.definition.name) return
      // A refusal reaches the operator through the middleware — `updateFxDefinition` is not silent,
      // and the editor relies on that too — so the sheet swallows only the rejection itself.
      updateDefinition({ id: row.definition.id, name })
        .unwrap()
        .catch(() => {})
    },
    [updateDefinition],
  )

  const count = selected.length
  const noun = count === 1 ? 'effect' : 'effects'
  const one = selected[0]
  const forkReason =
    scope.reason ??
    (forking
      ? 'Forking…'
      : (oneRecordReason('Fork', 'effect', count) ??
        (one.source !== 'builtIn'
          ? `Only a built-in forks — ${one.source === 'custom' ? 'edit this custom effect directly' : 'edit the script that registers this effect'}`
          : one.entry.script
            ? null
            : `${one.name} publishes no script to fork`)))
  const deletable = selected.some((row) => row.source === 'custom')
  const deleteReason =
    scope.reason ??
    (deleting
      ? 'Deleting…'
      : deletable
        ? null
        : 'Only a custom effect deletes here — built-ins are the desk’s, and a script’s effects are its script’s')

  const verbs =
    count > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
        <LibraryVerb
          icon={GitFork}
          label="Fork"
          title={`Make a custom copy of ${one?.name ?? 'this effect'} you can edit`}
          disabledReason={forkReason}
          onClick={() => onFork(one.entry)}
        />
        <LibraryVerb
          icon={Trash2}
          label="Delete"
          destructive
          title={`Delete ${count} ${noun} — only custom effects are sent`}
          disabledReason={deleteReason}
          onClick={() => void runDelete(selected.map(toDeleteItem))}
        />
        <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const firstColumn = useMemo(
    () =>
      libraryNameColumn<FxSheetRow>({
        label: 'Effect',
        width: `${NAME_WIDTH}px`,
        noun: 'effect',
        name: (row) => row.name ?? '',
        rename,
        renameDisabled: (row) => scope.readOnly || row.source !== 'custom',
        badges: (row) =>
          row.source === 'builtIn' ? (
            <Lock className="size-3 text-muted-foreground/70" aria-label="Built-in — read-only" />
          ) : null,
        onOpen: openRow,
        openLabel: (row) => (row.source === 'script' && isCurrentProject ? `Open ${row.name}’s script` : `Open ${row.name}`),
      }),
    [isCurrentProject, openRow, rename, scope.readOnly],
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${noun}` : null}
          cellLabel={null}
          family={null}
          hints={{ entry: false, clear: false }}
          strip={scope.reason != null ? <ReadOnlyNote reason={scope.reason} /> : undefined}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          foldForStrip={false}
        />
      </div>
      <SheetTable<FxSheetRow, FxColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + MIN_TRACKS}px`}
        firstColumn={firstColumn}
      />
    </div>
  )
}

/** Why nothing is live off the running project — the library and its writes are the running show's. */
export const FX_READ_ONLY_REASON = 'the FX Library shows and edits the running show’s effects'

/**
 * The name column's track. With the other tracks' floors (100 + 96 + 86 + 70 + 150 + 110 = 612)
 * the sheet needs 812px, inside the ~940 the iPad frame (1180×820) leaves it with the sidebar open.
 */
const NAME_WIDTH = 200
const MIN_TRACKS = 612

function toDeleteItem(row: FxMemberRow): FxDeleteItem {
  return { name: row.name, source: row.source, definition: row.definition }
}

function ReadText({ children, title }: { children: React.ReactNode; title?: string }) {
  return (
    <span className="mx-2 inline-flex min-w-0 items-center gap-1.5 truncate text-xs text-muted-foreground" title={title}>
      {children}
    </span>
  )
}

/** Built-in · Custom · Script — the boards' three chips. */
export function SourceBadge({ source }: { source: FxSource }) {
  return (
    <span
      className={cn(
        'mx-2 inline-flex h-[18px] items-center gap-1 rounded-full px-1.5 text-[10px] font-medium',
        source === 'builtIn' && 'border text-muted-foreground',
        source === 'custom' && 'bg-blue-500/20 text-blue-700 dark:text-blue-300',
        source === 'script' && 'bg-sky-500/20 text-sky-700 dark:text-sky-300',
      )}
      data-source={source}
    >
      {source === 'builtIn' && <Lock className="size-2.5" />}
      {source === 'script' && <Braces className="size-2.5" />}
      {FX_SOURCE_LABELS[source]}
    </span>
  )
}
