import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Breadcrumbs } from '@/components/Breadcrumbs'
import {
  useCompileProjectScriptMutation,
  useCurrentProjectQuery,
  useProjectQuery,
  useProjectScriptsQuery,
  useRunProjectScriptMutation,
} from '../store/projects'
import { useEffectLibraryQuery } from '../store/fixtureFx'
import { useFxDefinitionListQuery } from '../store/fxDefinitions'
import type { ProjectScriptDetail } from '../api/projectApi'
import type { ScriptType } from '../store/scripts'
import { ScriptForm } from '../components/scripts/ScriptForm'
import { ScriptRunDialog } from '../components/scripts/ScriptResultDialogs'
import { ScriptSheet, scriptRowId, type ScriptSheetRow } from '../components/scripts/ScriptSheet'
import { useScriptDelete } from '../components/scripts/useScriptDelete'
import { checkFromResult, type ScriptCheck } from '../components/scripts/scriptCheck'
import { ALL_SCRIPT_TYPES, SCRIPT_TYPE_LABELS } from '../components/scripts/scriptUtils'
import { definitionsByEffectId, displayName, effectsRegisteredBy } from '../components/fxLibrary/fxLibraryModel'
import { LibraryRow, PartitionChips } from '../components/sheet/LibraryRow'
import { SheetPage } from '../components/sheet/SheetPage'
import { groupRows } from '../components/sheet/groupRows'
import { usePartitionView } from '../components/sheet/usePartitionView'
import { formatError } from '../lib/formatError'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

// Redirect component for /scripts route
export function ScriptsRedirect() {
  const { scriptId } = useParams()
  return <CurrentProjectRedirect to={scriptId ? `scripts/${scriptId}` : 'scripts'} fullHeight />
}

/** The type chips' short labels — the sheet's Type column and dividers say the whole name. */
const SCRIPT_TYPE_SHORT_LABELS: Record<ScriptType, string> = {
  GENERAL: 'General',
  FX_DEFINITION: 'FX def',
  FX_APPLICATION: 'FX app',
  FX_CALC: 'FX calc',
  FX_CALC_STATEFUL: 'Stateful',
  FX_CALC_COMPOSITE: 'Composite',
}

/** `?type=fx-definition` ↔ `FX_DEFINITION`. */
function parseScriptType(raw: string): ScriptType | null {
  const upper = raw.toUpperCase().replaceAll('-', '_')
  return (ALL_SCRIPT_TYPES as readonly string[]).includes(upper) ? (upper as ScriptType) : null
}

function scriptTypeSlug(type: ScriptType): string {
  return type.toLowerCase().replaceAll('_', '-')
}

/**
 * The Scripts library as a **sheet** (library-sheets plan §3.2, session 4): the list shell's
 * header, the library row (filter · type chips · *New script*), the selection bar, `ScriptSheet`
 * and the footer. It replaced a type sidebar (a bottom sheet on a phone) beside a list of buttons;
 * `ScriptForm` stays, as what a row's pencil — or ⏎ over one row — opens (D1), and keeps its own
 * compile dialog.
 *
 * **Chips filter, dividers group** (D3): *All* plus one chip per type the project holds, in short
 * labels; `?type=` deep-links and the choice is remembered under `scripts.type`; under *All* the
 * rows are grouped by type divider in `ALL_SCRIPT_TYPES` order.
 *
 * **Check is this tab's, and lives here** (D8): a `Map<scriptId, ScriptCheck>` filled by the bar's
 * Compile one script at a time, each result written as it arrives, never stored. Each result
 * carries the text it compiled, so an edit makes the script read *not checked* again without this
 * route having to notice (`checkFor`).
 */
export default function ProjectScripts() {
  const { projectId, scriptId } = useParams()
  const navigate = useNavigate()

  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: scriptList, isLoading: scriptsLoading } = useProjectScriptsQuery(projectIdNum)
  // Used by: the running show's library, read against its definitions (the FX Library's rule).
  const { data: fxLibrary } = useEffectLibraryQuery()
  const { data: fxDefinitions } = useFxDefinitionListQuery()
  const [compileScript] = useCompileProjectScriptMutation()
  const [runScript, { data: runResult, isUninitialized: hasNotRun, isLoading: isRunning, reset: resetRun }] =
    useRunProjectScriptMutation()

  const isCurrentProject = currentProject?.id === projectIdNum

  const [type, changeType] = usePartitionView<ScriptType>({
    param: 'type',
    storageKey: 'scripts.type',
    parse: parseScriptType,
    slug: scriptTypeSlug,
  })
  const [filter, setFilter] = useState('')

  // Form state
  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)

  const library = useMemo(() => scriptList ?? [], [scriptList])
  // Re-read from the list rather than remembered, so a rename on the sheet reaches an open form's
  // title and a save is measured against what the desk now holds.
  const editingScript = useMemo(
    () => (editingId == null ? null : (library.find((s) => s.id === editingId) ?? null)),
    [editingId, library],
  )

  // A script that leaves the list under an open form (deleted from another client) closes it for
  // real — left open, `script={null}` would turn the form into a *create* form mid-edit.
  useEffect(() => {
    if (editingId != null && scriptList != null && editingScript == null) {
      setEditingId(null)
      setFormOpen(false)
    }
  }, [editingId, scriptList, editingScript])

  // Deep link: `/scripts/:scriptId` opens that script — the FX Library's way into the script that
  // registers an effect — then drops the id from the URL.
  useEffect(() => {
    if (scriptId && scriptList && scriptList.length > 0) {
      const script = scriptList.find((s) => s.id === Number(scriptId))
      if (script) {
        setEditingId(script.id)
        setFormOpen(true)
        navigate(`/projects/${projectId}/scripts`, { replace: true })
      }
    }
  }, [scriptId, scriptList, navigate, projectId])

  // ── Check (D8) ──
  const [checks, setChecks] = useState<ReadonlyMap<number, ScriptCheck>>(() => new Map())
  const [compiling, setCompiling] = useState(false)
  const compilingRef = useRef(false)
  const setCheck = useCallback((script: ProjectScriptDetail, state: ScriptCheck) => {
    setChecks((prev) => new Map(prev).set(script.id, state))
  }, [])

  const compile = useCallback(
    async (scripts: readonly ProjectScriptDetail[]) => {
      if (compilingRef.current || scripts.length === 0) return
      compilingRef.current = true
      setCompiling(true)
      // Each carries the text and type it will compile — what `checkFor` matches against later.
      const snapshots = scripts.map((s) => ({ script: s, text: s.script, scriptType: s.scriptType }))
      setChecks((prev) => {
        const next = new Map(prev)
        for (const { script, text, scriptType } of snapshots) {
          next.set(script.id, { script: text, scriptType, status: 'queued' })
        }
        return next
      })
      try {
        // One at a time: the desk has one compiler, and the rows fill in the order they are drawn.
        for (const { script, text, scriptType } of snapshots) {
          setCheck(script, { script: text, scriptType, status: 'busy' })
          try {
            const result = await compileScript({ projectId: projectIdNum, script: text, scriptType }).unwrap()
            setCheck(script, { script: text, scriptType, ...checkFromResult(result) })
          } catch (err) {
            setCheck(script, { script: text, scriptType, status: 'failed', reason: formatError(err) })
          }
        }
      } finally {
        compilingRef.current = false
        setCompiling(false)
      }
    },
    [compileScript, projectIdNum, setCheck],
  )

  const run = useCallback(
    (script: ProjectScriptDetail) => {
      runScript({ projectId: projectIdNum, script: script.script, scriptType: script.scriptType, scriptId: script.id })
    },
    [projectIdNum, runScript],
  )

  // ── Used by ──
  const registeredBy = useMemo(() => {
    const byEffectId = definitionsByEffectId(fxDefinitions ?? [])
    const entries = fxLibrary ?? []
    // Another project's script ids name nothing in the running show's library.
    return (script: ProjectScriptDetail): readonly string[] =>
      isCurrentProject && script.scriptType === 'FX_DEFINITION'
        ? effectsRegisteredBy(script.id, entries, byEffectId).map((e) => displayName(e.name))
        : []
  }, [fxDefinitions, fxLibrary, isCurrentProject])

  const { run: runDelete, busy: isDeleting, dialog: deleteDialog } = useScriptDelete({
    projectId: projectIdNum,
    registeredBy,
    onDeleted: () => {
      setFormOpen(false)
      setEditingId(null)
    },
  })

  const typeCounts = useMemo(() => {
    const counts = new Map<ScriptType, number>()
    for (const s of library) counts.set(s.scriptType, (counts.get(s.scriptType) ?? 0) + 1)
    return counts
  }, [library])

  /** The rows the sheet draws: the type and the text filter, then — under *All* — the dividers. */
  const sheetRows = useMemo<ScriptSheetRow[]>(() => {
    const needle = filter.trim().toLowerCase()
    const shown = library
      .filter((s) => (type === 'ALL' || s.scriptType === type) && (needle === '' || s.name.toLowerCase().includes(needle)))
      .sort((a, b) => a.name.localeCompare(b.name))
    const members: ScriptSheetRow[] = shown.map((script) => ({ id: scriptRowId(script.id), script }))
    if (type !== 'ALL') return members
    return groupRows<ScriptSheetRow, ScriptType>(members, {
      partition: (row) => row.script?.scriptType ?? '',
      order: ALL_SCRIPT_TYPES,
      divider: (key) => ({
        id: `type:${key || 'other'}`,
        divider: `${key in SCRIPT_TYPE_LABELS ? SCRIPT_TYPE_LABELS[key as ScriptType] : 'Other'} · ${shown.filter((s) => s.scriptType === key).length}`,
      }),
      unlisted: 'other',
    })
  }, [filter, library, type])

  const handleCreate = () => {
    setEditingId(null)
    setFormOpen(true)
  }

  if (projectLoading || currentLoading) {
    return (
      <SheetPage>
        <SheetPage.Header />
        <SheetPage.Empty loading />
      </SheetPage>
    )
  }
  if (!project) {
    return (
      <SheetPage>
        <SheetPage.Header />
        <SheetPage.Empty className="text-destructive">Project not found</SheetPage.Empty>
      </SheetPage>
    )
  }

  const memberCount = sheetRows.filter((row) => row.divider == null).length
  const shownChecks = library.flatMap((s) => {
    const check = checks.get(s.id)
    return check != null && check.script === s.script && check.scriptType === s.scriptType ? [check] : []
  })
  const checked = shownChecks.filter((c) => c.status === 'ok' || c.status === 'error' || c.status === 'failed').length
  const failed = shownChecks.filter((c) => c.status === 'error' || c.status === 'failed').length

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Scripts" />
      </SheetPage.Header>
      <LibraryRow
        filter={filter}
        onFilterChange={setFilter}
        chips={
          <PartitionChips<ScriptType>
            label="Script type"
            fold={560}
            value={type}
            onChange={changeType}
            allCount={library.length}
            options={ALL_SCRIPT_TYPES.filter((t) => (typeCounts.get(t) ?? 0) > 0 || t === type).map((t) => ({
              value: t,
              label: SCRIPT_TYPE_SHORT_LABELS[t],
              count: typeCounts.get(t) ?? 0,
            }))}
          />
        }
        create={
          isCurrentProject ? (
            <Button size="sm" className="shrink-0 gap-1.5" onClick={handleCreate}>
              <Plus className="size-3.5" />
              <span className="hidden sm:inline">New script</span>
            </Button>
          ) : undefined
        }
      />
      {scriptsLoading ? (
        <SheetPage.Empty loading />
      ) : library.length === 0 ? (
        <SheetPage.Empty>
          {isCurrentProject ? 'No scripts yet. Create one with New script.' : 'No scripts in this project.'}
        </SheetPage.Empty>
      ) : memberCount === 0 ? (
        <SheetPage.Empty>No scripts match.</SheetPage.Empty>
      ) : (
        <ScriptSheet
          projectId={projectIdNum}
          rows={sheetRows}
          isCurrentProject={isCurrentProject}
          projectName={project.name}
          checks={checks}
          onCompile={(scripts) => void compile(scripts)}
          compiling={compiling}
          onRun={run}
          running={isRunning}
          registeredBy={registeredBy}
          onOpenScript={(script) => {
            setEditingId(script.id)
            setFormOpen(true)
          }}
        />
      )}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {library.length} script{library.length === 1 ? '' : 's'}
          {checked > 0 ? ` · ${checked} checked` : ''}
          {failed > 0 ? ` · ${failed} failed` : ''}
        </span>
        <span className="ml-auto">
          {isCurrentProject
            ? 'Check is this tab’s last compile — not stored'
            : 'Not the running project · copy a script here to use it'}
        </span>
      </SheetPage.Footer>

      <ScriptRunDialog runResult={runResult} hasNotRun={hasNotRun} isRunning={isRunning} resetRun={resetRun} />
      <ScriptForm
        open={formOpen}
        onOpenChange={(next) => {
          setFormOpen(next)
          if (!next) setEditingId(null)
        }}
        script={editingScript}
        projectId={projectIdNum}
        isCurrentProject={isCurrentProject}
        onDelete={(script) => void runDelete([script])}
        isDeleting={isDeleting}
      />
      {deleteDialog}
    </SheetPage>
  )
}
