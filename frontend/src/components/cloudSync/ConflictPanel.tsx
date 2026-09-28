import { useEffect, useMemo, useState } from "react"
import { useDispatch } from "react-redux"
import { toast } from "sonner"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Textarea } from "@/components/ui/textarea"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  Loader2,
  AlertTriangle,
  GitMerge,
  X,
  ChevronDown,
  ChevronRight,
  RotateCcw,
} from "lucide-react"
import { lightingApi } from "@/api/lightingApi"
import { restApi } from "@/store/restApi"
import {
  useCloudSyncConflictsQuery,
  useCloudSyncResolveMutation,
  useCloudSyncApplyMutation,
  useCloudSyncAbortMutation,
  type ConflictDto,
  type ConflictResolution,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"
import { cn } from "@/lib/utils"

/**
 * Conflict-resolution UI:
 *  - Per-row three-pane diff (mine / common ancestor / theirs) of canonical JSON.
 *  - LOCAL / REMOTE / MANUAL resolution toggle. MANUAL pops a JSON textarea editor
 *    that's validated client-side before save.
 *  - Resume/discard banner when the session is FAILED (e.g. crashed mid-apply) — only
 *    Abort is offered, since the recovery path is abort + re-run sync.
 *  - WS-driven cache invalidation so a sync started from another tab surfaces here.
 *
 * MANUAL is hidden for rows where the backend marks `manualEditAllowed === false`
 * (currently scripts, which span two files).
 */
export function ConflictPanel({ projectId }: { projectId: number }) {
  const { data, isLoading } = useCloudSyncConflictsQuery(projectId)
  const [resolve] = useCloudSyncResolveMutation()
  const [apply, { isLoading: isApplying }] = useCloudSyncApplyMutation()
  const [abort, { isLoading: isAborting }] = useCloudSyncAbortMutation()
  const dispatch = useDispatch()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  useEffect(() => {
    const sub = lightingApi.cloudSync.subscribeConflictsPending(() => {
      dispatch(restApi.util.invalidateTags(['CloudSyncConflicts', 'CloudSyncStatus']))
    })
    return () => sub.unsubscribe()
  }, [dispatch])

  if (isLoading || !data) return null
  if (!data.activeSession) return null

  const isFailed = data.state === "FAILED"
  const isApplyingState = data.state === "APPLYING"
  const sessionLabel = data.sessionId ? `#${data.sessionId}` : ""

  const handleChoose = async (
    conflict: ConflictDto,
    choice: ConflictResolution,
    manualValueJson?: string | null,
  ) => {
    try {
      await resolve({
        projectId,
        resolutions: [
          {
            tableName: conflict.tableName,
            recordUuid: conflict.recordUuid,
            resolution: choice,
            manualValueJson: choice === "MANUAL" ? (manualValueJson ?? null) : null,
          },
        ],
      }).unwrap()
    } catch (err) {
      toast.error(`Failed to record resolution: ${formatError(err)}`)
    }
  }

  const handleApply = async () => {
    try {
      const result = await apply(projectId).unwrap()
      toast.success(result.message || "Conflicts applied and pushed")
    } catch (err) {
      toast.error(`Apply failed: ${formatError(err)}`)
    }
  }

  const handleAbort = async () => {
    try {
      await abort(projectId).unwrap()
      toast("Session aborted; local working tree reset")
    } catch (err) {
      toast.error(`Abort failed: ${formatError(err)}`)
    }
  }

  // FAILED session — usually crash recovery. Show a focused recovery banner; only Abort.
  if (isFailed) {
    return (
      <Card className="p-4 space-y-3 border-destructive/50">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-sm font-semibold flex items-center gap-2">
              <RotateCcw className="size-4 text-destructive" />
              Sync session failed {sessionLabel}
            </h2>
            <p className="text-xs text-muted-foreground">
              Discard the session to reset the working tree and re-run sync.
            </p>
          </div>
          <Button variant="destructive" onClick={handleAbort} disabled={isAborting}>
            <X className="size-4 mr-1" />
            {isAborting ? "Discarding…" : "Discard session"}
          </Button>
        </div>
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertTitle>Apply did not complete</AlertTitle>
          <AlertDescription>
            {data.errorMessage ?? "The apply step was interrupted. Discard this session and run sync again."}
          </AlertDescription>
        </Alert>
      </Card>
    )
  }

  const allResolved = data.conflicts.every((c) => c.resolution != null)
  const unresolvedCount = data.conflicts.filter((c) => c.resolution == null).length

  const toggleExpanded = (key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  return (
    <Card className="p-4 space-y-4 border-amber-500/50">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-sm font-semibold flex items-center gap-2">
            <GitMerge className="size-4 text-amber-600" />
            Conflicts to resolve {sessionLabel}
          </h2>
          <p className="text-xs text-muted-foreground">
            Both sides changed {data.conflicts.length} record(s). Pick a resolution per
            row, then click <strong>Apply</strong> to commit and push. Manual edits replace
            both sides with your JSON payload.
          </p>
        </div>
        <div className="flex gap-2">
          <Button onClick={handleApply} disabled={!allResolved || isApplying || isAborting || isApplyingState}>
            {isApplying ? "Applying…" : "Apply"}
          </Button>
          <Button variant="outline" onClick={handleAbort} disabled={isApplying || isAborting}>
            <X className="size-4 mr-1" />
            {isAborting ? "Aborting…" : "Abort"}
          </Button>
        </div>
      </div>

      {!allResolved && (
        <div className="flex items-center gap-2 text-xs text-amber-600">
          <AlertTriangle className="size-3.5" />
          {unresolvedCount} of {data.conflicts.length} unresolved
        </div>
      )}

      <div className="space-y-2">
        {data.conflicts.map((c) => {
          const key = `${c.tableName}/${c.recordUuid}`
          return (
            <ConflictRow
              key={key}
              conflict={c}
              expanded={expanded.has(key)}
              onToggleExpanded={() => toggleExpanded(key)}
              onChoose={(choice, manual) => handleChoose(c, choice, manual)}
              busy={isApplying || isAborting}
            />
          )
        })}
      </div>

      {isApplying && (
        <div className="flex items-center justify-center py-2 text-xs text-muted-foreground">
          <Loader2 className="size-4 animate-spin mr-2" />
          Applying resolutions…
        </div>
      )}
    </Card>
  )
}

// ─── Per-row diff + picker ────────────────────────────────────────────

function ConflictRow({
  conflict,
  expanded,
  onToggleExpanded,
  onChoose,
  busy,
}: {
  conflict: ConflictDto
  expanded: boolean
  onToggleExpanded: () => void
  onChoose: (choice: ConflictResolution, manualValueJson?: string | null) => void | Promise<void>
  busy: boolean
}) {
  const manualAllowed = conflict.manualEditAllowed !== false
  const Chevron = expanded ? ChevronDown : ChevronRight

  return (
    <div className="border rounded-md">
      <div className="flex items-center justify-between gap-3 px-3 py-2">
        <button
          type="button"
          onClick={onToggleExpanded}
          className="flex items-center gap-2 text-left flex-1 min-w-0 hover:text-foreground"
        >
          <Chevron className="size-3.5 shrink-0 text-muted-foreground" />
          <span className="text-xs font-mono truncate">
            {conflict.tableName} / {conflict.recordUuid.slice(0, 8)}…
          </span>
          <Badge variant="outline" className="text-[10px] shrink-0">
            {conflict.conflictKind}
          </Badge>
        </button>
        <div className="flex items-center gap-2">
          {conflict.resolution ? (
            <Badge variant="secondary" className="text-[10px]">{conflict.resolution}</Badge>
          ) : (
            <Badge variant="outline" className="text-[10px]">Unresolved</Badge>
          )}
          <ToggleGroup
            type="single"
            size="sm"
            value={conflict.resolution ?? ""}
            onValueChange={(v) => {
              if (!v) return
              if (v === "MANUAL") {
                // Selecting MANUAL just expands the editor; the actual save happens
                // when the user clicks "Save manual" in the editor.
                if (!expanded) onToggleExpanded()
                return
              }
              void onChoose(v as ConflictResolution)
            }}
            disabled={busy}
          >
            <ToggleGroupItem value="LOCAL" aria-label="Use local">
              Local
            </ToggleGroupItem>
            <ToggleGroupItem value="REMOTE" aria-label="Use remote">
              Remote
            </ToggleGroupItem>
            {manualAllowed && (
              <ToggleGroupItem value="MANUAL" aria-label="Manual edit">
                Manual
              </ToggleGroupItem>
            )}
          </ToggleGroup>
        </div>
      </div>

      {expanded && (
        <div className="border-t p-3 space-y-3 bg-muted/20">
          <ThreePaneDiff conflict={conflict} />
          {manualAllowed && (
            <ManualEditor
              conflict={conflict}
              busy={busy}
              onSave={(json) => onChoose("MANUAL", json)}
            />
          )}
          {!manualAllowed && (
            <p className="text-[11px] text-muted-foreground italic">
              Manual editing is not yet supported for {conflict.tableName} records — pick
              Local or Remote.
            </p>
          )}
        </div>
      )}
    </div>
  )
}

// ─── Three-pane diff ──────────────────────────────────────────────────

function ThreePaneDiff({ conflict }: { conflict: ConflictDto }) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
      <DiffPane title="Mine (local)" json={conflict.localJson} accent="text-emerald-600" />
      <DiffPane title="Base (common ancestor)" json={conflict.baseJson} accent="text-muted-foreground" />
      <DiffPane title="Theirs (remote)" json={conflict.remoteJson} accent="text-sky-600" />
    </div>
  )
}

function DiffPane({
  title,
  json,
  accent,
}: {
  title: string
  json: string | null
  accent: string
}) {
  const formatted = useMemo(() => formatJson(json), [json])
  return (
    <div className="border rounded-md bg-background flex flex-col min-h-0">
      <div className={cn("px-2 py-1 text-[10px] font-semibold uppercase tracking-wide border-b", accent)}>
        {title}
      </div>
      {json == null ? (
        <div className="px-2 py-3 text-[11px] text-muted-foreground italic">
          (deleted on this side)
        </div>
      ) : (
        <pre className="px-2 py-2 text-[11px] font-mono leading-snug whitespace-pre-wrap break-all overflow-auto max-h-72">
          {formatted}
        </pre>
      )}
    </div>
  )
}

// ─── MANUAL JSON editor ───────────────────────────────────────────────

function ManualEditor({
  conflict,
  busy,
  onSave,
}: {
  conflict: ConflictDto
  busy: boolean
  onSave: (json: string) => void | Promise<void>
}) {
  // Seed the textarea with whatever the user previously saved (if any), otherwise
  // the local side as a sensible starting point. Seeded once on mount — we don't
  // re-seed on refetch because that would stomp in-progress edits whenever the
  // parent query invalidates (e.g. after another row's resolve).
  const [draft, setDraft] = useState(
    () => conflict.manualValueJson ?? formatJson(conflict.localJson) ?? "",
  )
  const [saving, setSaving] = useState(false)

  const validation = useMemo(() => validateJson(draft), [draft])
  // Pre-canonicalise the saved value once per server update; the per-keystroke
  // `dirty` check then only canonicalises `draft`.
  const savedCanonical = useMemo(
    () => (conflict.manualValueJson ? canonicalJson(conflict.manualValueJson) : null),
    [conflict.manualValueJson],
  )
  const dirty = useMemo(() => {
    if (conflict.resolution !== "MANUAL") return true
    if (!validation.valid) return true
    return canonicalJson(draft) !== savedCanonical
  }, [draft, conflict.resolution, savedCanonical, validation.valid])

  const handleSave = async () => {
    if (!validation.valid || saving) return
    setSaving(true)
    try {
      await onSave(draft)
    } finally {
      setSaving(false)
    }
  }

  const handleResetToLocal = () => {
    setDraft(formatJson(conflict.localJson) ?? "")
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          Manual edit
        </div>
        <div className="flex gap-1">
          <Button
            size="sm"
            variant="ghost"
            onClick={handleResetToLocal}
            disabled={busy || saving}
            type="button"
          >
            Reset to local
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={busy || saving || !validation.valid || !dirty}
            type="button"
          >
            {saving ? "Saving…" : conflict.resolution === "MANUAL" ? "Update manual" : "Save manual"}
          </Button>
        </div>
      </div>
      <Textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        rows={10}
        className="font-mono text-[11px] leading-snug min-h-40"
        placeholder="Paste or edit canonical JSON for this record"
        spellCheck={false}
        aria-invalid={!validation.valid || undefined}
      />
      {!validation.valid && (
        <p className="text-[11px] text-destructive">
          Invalid JSON: {validation.error}
        </p>
      )}
      {validation.valid && conflict.resolution !== "MANUAL" && (
        <p className="text-[11px] text-muted-foreground">
          Click <strong>Save manual</strong> to record this as the resolution.
        </p>
      )}
    </div>
  )
}

// ─── Helpers ──────────────────────────────────────────────────────────

function formatJson(raw: string | null | undefined): string | null {
  if (raw == null) return null
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function validateJson(raw: string): { valid: true } | { valid: false; error: string } {
  const trimmed = raw.trim()
  if (!trimmed) return { valid: false, error: "must not be empty" }
  try {
    JSON.parse(trimmed)
    return { valid: true }
  } catch (e) {
    return { valid: false, error: e instanceof Error ? e.message : String(e) }
  }
}

/** JSON-parsed string ⇒ a single canonical form for whitespace-insensitive equality. */
function canonicalJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw))
  } catch {
    return raw
  }
}
