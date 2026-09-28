import { useCallback, useMemo, useState } from 'react'
import { AlertCircle, ArrowRight, Check, Clock, Hammer, Loader2, Play, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CopyToProjectSheet } from '@/components/sheet/CopyToProjectSheet'
import { CountReadOut, ReadOnlyNote } from '@/components/sheet/LibraryReadOuts'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { libraryPermission } from '@/components/sheet/libraryScope'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable } from '@/components/sheet/SheetTable'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useSheet } from '@/components/sheet/useSheet'
import { useCopyScriptMutation, useSaveProjectScriptMutation } from '@/store/projects'
import type { ProjectScriptDetail } from '@/api/projectApi'
import { SCRIPT_TYPE_LABELS } from './scriptUtils'
import { checkFor, lineCount, type ScriptCheck } from './scriptCheck'
import { useScriptDelete } from './useScriptDelete'

export type ScriptColumnKey = 'type' | 'lines' | 'check' | 'usedBy'

export interface ScriptSheetRow extends SheetRow {
  /** Absent on a type divider. */
  script?: ProjectScriptDetail
}

type MemberRow = ScriptSheetRow & { script: ProjectScriptDetail }

export function scriptRowId(scriptId: number): string {
  return `script:${scriptId}`
}

function isMember(row: ScriptSheetRow): row is MemberRow {
  return row.script != null
}

export interface ScriptSheetProps {
  projectId: number
  /** The rows to draw, dividers included — the route groups them under *All* (`groupRows`). */
  rows: readonly ScriptSheetRow[]
  /** The scripts are the running project's. Off it the sheet is the read-only scope (D12). */
  isCurrentProject: boolean
  projectName: string
  /** This tab's last Compile of each script (D8) — the route's, never stored. */
  checks: ReadonlyMap<number, ScriptCheck>
  /** Compile these scripts, one at a time, each result landing in [checks] as it arrives. */
  onCompile: (scripts: readonly ProjectScriptDetail[]) => void
  compiling: boolean
  /** Run one script against the live show — the route shows the result. */
  onRun: (script: ProjectScriptDetail) => void
  running: boolean
  /** The effects an `FX_DEFINITION` script registers, by name — Used by. */
  registeredBy: (script: ProjectScriptDetail) => readonly string[]
  onOpenScript: (script: ProjectScriptDetail) => void
}

/**
 * **The Scripts library as a sheet** (library-sheets plan §3.2, session 4), replacing the type
 * sidebar (a bottom sheet on a phone) beside a list of buttons. The route draws the type chips and
 * groups the rows under type dividers; this draws the columns and the bar.
 *
 * **Every column is a read-out** — Type, Lines, Check, Used by — so nothing is in the marquee and
 * there are no cell verbs. The name renames in its popover, and the write is
 * `PUT {name, script, scriptType}` **from the list's own copy of the row**: the route replaces the
 * whole row and its `scriptType` defaults to `GENERAL`, so a rename that left the type out would
 * silently downgrade the script and stop it registering or hooking anything.
 *
 * - **Check** is this tab's last Compile (D8, `scriptCheck.ts`) — ✓, or the error count and line —
 *   and reads *not checked* again the moment the script's text is not the text it compiled.
 * - **Used by** is the effects an `FX_DEFINITION` script registers (the FX Library's discriminator,
 *   `effectsRegisteredBy`); anything else reads *—* until `FU-SCRIPT-USED-BY`, since cue hooks are
 *   only on full cue details and no list the client reads carries them.
 *
 * **The verbs are Compile · Run · Copy to… · Delete · Deselect** (D11). Run acts on one script and
 * is disabled over several. **Another project's scripts are the read-only scope** (D12): Compile,
 * Run, the rename and Delete are the running project's alone (the routes are
 * `withCurrentProject`), and *Copy to…* — which arrives with its type since session 0 — is the one
 * live verb. A row still opens there: `ScriptForm` has a read-only arm with its own copy.
 */
export function ScriptSheet({
  projectId,
  rows,
  isCurrentProject,
  projectName,
  checks,
  onCompile,
  compiling,
  onRun,
  running,
  registeredBy,
  onOpenScript,
}: ScriptSheetProps) {
  const [saveScript] = useSaveProjectScriptMutation()
  const [copyScript] = useCopyScriptMutation()
  const scope = useMemo(() => libraryPermission(isCurrentProject, projectName), [isCurrentProject, projectName])

  const columns = useMemo<SheetColumn<ScriptSheetRow, ScriptColumnKey>[]>(
    () => [
      {
        key: 'type',
        label: 'Type',
        width: '150px',
        value: () => undefined,
        display: (row) =>
          row.script && (
            <span className="mx-2 inline-flex h-[18px] items-center truncate rounded-full border px-1.5 text-[10px] font-medium text-muted-foreground">
              {SCRIPT_TYPE_LABELS[row.script.scriptType] ?? row.script.scriptType}
            </span>
          ),
      },
      {
        key: 'lines',
        label: 'Lines',
        width: '70px',
        value: () => undefined,
        display: (row) => row.script && <CountReadOut count={lineCount(row.script.script)} />,
      },
      {
        key: 'check',
        label: 'Check',
        width: 'minmax(170px, 220px)',
        value: () => undefined,
        display: (row) => row.script && <CheckReadOut check={checkFor(row.script, checks)} />,
      },
      {
        key: 'usedBy',
        label: 'Used by',
        width: 'minmax(130px, 1fr)',
        value: () => undefined,
        display: (row) => {
          if (!row.script) return null
          const registers = registeredBy(row.script)
          if (registers.length === 0) return <span className="mx-2 text-xs text-muted-foreground/45">—</span>
          const text = `registers ${registers.join(', ')}`
          return (
            <span className="mx-2 truncate text-xs text-muted-foreground" title={text}>
              {text}
            </span>
          )
        },
      },
    ],
    [checks, registeredBy],
  )

  const openRow = useCallback((row: ScriptSheetRow) => {
    if (row.script) onOpenScript(row.script)
  }, [onOpenScript])

  const sheet = useSheet<ScriptSheetRow, ScriptColumnKey>({
    rows,
    columns,
    permission: scope.permission,
    copy: scope.copy,
    noun: 'script',
    rowName: (row) => row.script?.name ?? row.id,
    onOpenRow: openRow,
  })
  const { selectedRows, clearByLadder, setRows } = sheet
  const selected = useMemo(() => selectedRows.filter(isMember).map((row) => row.script), [selectedRows])

  const { run: runDelete, busy: deleting, dialog: deleteDialog } = useScriptDelete({
    projectId,
    registeredBy,
    onDeleted: () => clearByLadder(),
    onKept: (kept) => setRows(kept.map((script) => scriptRowId(script.id))),
  })

  const rename = useCallback(
    (row: ScriptSheetRow, next: string) => {
      const script = row.script
      const name = next.trim()
      if (script == null || name === '' || name === script.name) return
      // The whole row, from the list's copy: the PUT replaces all three and defaults the type.
      // A refusal reaches the operator through the middleware (`saveProjectScript` is not silent,
      // and `ScriptForm` relies on that too), so only the rejection itself is swallowed here.
      saveScript({ projectId, scriptId: script.id, name, script: script.script, scriptType: script.scriptType })
        .unwrap()
        .catch(() => {})
    },
    [projectId, saveScript],
  )

  const [copyOpen, setCopyOpen] = useState(false)
  const [copying, setCopying] = useState<readonly ProjectScriptDetail[]>([])

  const count = selected.length
  const noun = count === 1 ? 'script' : 'scripts'
  const verbs =
    count > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
        <LibraryVerb
          icon={Hammer}
          label="Compile"
          title={`Compile ${count} ${noun} — the result lands in Check; nothing runs`}
          disabledReason={scope.reason ?? (compiling ? 'Compiling…' : null)}
          onClick={() => onCompile(selected)}
        />
        <LibraryVerb
          icon={Play}
          label="Run"
          title={`Run ${selected[0]?.name ?? 'this script'} against the live show`}
          disabledReason={scope.reason ?? (running ? 'Running…' : oneRecordReason('Run', 'script', count))}
          onClick={() => onRun(selected[0])}
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
      libraryNameColumn<ScriptSheetRow>({
        label: 'Script',
        width: `${NAME_WIDTH}px`,
        noun: 'script',
        name: (row) => row.script?.name ?? '',
        rename,
        renameDisabled: (row) => scope.readOnly || row.script?.canEdit === false,
        onOpen: openRow,
        openLabel: (row) => `Open ${row.script?.name ?? 'script'}`,
      }),
    [openRow, rename, scope.readOnly],
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
      <SheetTable<ScriptSheetRow, ScriptColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + MIN_TRACKS}px`}
        firstColumn={firstColumn}
      />
      {deleteDialog}
      <CopyToProjectSheet<ProjectScriptDetail>
        open={copyOpen}
        onOpenChange={setCopyOpen}
        items={copying}
        noun="script"
        name={(script) => script.name}
        sourceProjectId={projectId}
        copy={(script, targetProjectId, newName) =>
          copyScript({ projectId, scriptId: script.id, targetProjectId, newName }).unwrap()
        }
      />
    </div>
  )
}

/**
 * The name column's track. With the other tracks' floors (150 + 70 + 170 + 130 = 520) the sheet
 * needs 760px, inside the ~940 the iPad frame (1180×820) leaves it with the sidebar open.
 */
const NAME_WIDTH = 240
const MIN_TRACKS = 520

/** ✓, the error count and its line, or *not checked* — the board's four states, and a queue. */
export function CheckReadOut({ check }: { check: ScriptCheck | null }) {
  const base = 'mx-2 inline-flex min-w-0 items-center gap-1.5 text-xs'
  if (check == null) return <span className={`${base} text-muted-foreground/45`}>not checked</span>
  switch (check.status) {
    case 'queued':
      return (
        <span className={`${base} text-muted-foreground`}>
          <Clock className="size-3.5" />
          Queued
        </span>
      )
    case 'busy':
      return (
        <span className={`${base} text-muted-foreground`}>
          <Loader2 className="size-3.5 animate-spin" />
          Compiling…
        </span>
      )
    case 'ok':
      return (
        <span className={`${base} text-green-600 dark:text-green-400`}>
          <Check className="size-3.5" />
          Compiles
          {check.warnings > 0 && (
            <span className="text-muted-foreground">
              · {check.warnings} warning{check.warnings === 1 ? '' : 's'}
            </span>
          )}
        </span>
      )
    case 'error':
      return (
        <span className={`${base} text-destructive`} title={check.message}>
          <AlertCircle className="size-3.5 shrink-0" />
          <span className="truncate">
            {check.errors} error{check.errors === 1 ? '' : 's'}
            {check.line != null ? ` · line ${check.line}` : ''}
          </span>
        </span>
      )
    case 'failed':
      return (
        <span className={`${base} text-destructive`} title={check.reason}>
          <AlertCircle className="size-3.5 shrink-0" />
          <span className="truncate">Not compiled — {check.reason}</span>
        </span>
      )
  }
}
