import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Loader2 } from 'lucide-react'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { InlineEditField } from '@/components/InlineEditField'
import { SaveStatusIndicator } from '@/components/SaveStatusIndicator'
import { TruncateStart } from '@/components/TruncateStart'
import { SharedCueChip } from '@/components/runner/SharedCueChip'
import { CueEffectsReadout, CueLayersReadout } from '@/components/cues/CueDetailContent'
import { useExpandedCue } from '@/components/cues/CueRowParts'
import { CueSceneryEditorBody } from '@/components/scenery/CueSceneryEditor'
import { CueSceneryReadout } from '@/components/scenery/SceneryReadout'
import { CueEventsEditor, CueEventsReadout } from '@/components/effects/CueEventsEditor'
import { HandCueLayerStrip } from '@/components/hand/HandLayerTargets'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { CUE_CURVE_OPTIONS, formatFadeDuration, parseFadeDuration, parseFollowDelay } from '@/lib/cueUtils'
import { formatMs } from '@/lib/formatMs'
import type { CuePlace } from '@/lib/cuePlaces'
import { usePatchProjectCueMutation, useProjectCueCookedQuery } from '@/store/cues'
import { usePatchCueStackEntryMutation } from '@/store/cueStacks'
import { useSetCueEventsMutation } from '@/store/effects'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import type { Cue, CuePatchInput } from '@/api/cuesApi'
import { cueEditorTabStore, type CueEditorTab } from './cueEditorTab'
import { CueTab } from './CueTab'
import { CueRowStoreProvider, useCueRowSaveState } from './CueRowStore'
import { CueValuesGrid } from './CueValuesGrid'

/** What mounts the editor: the open card, the cue sheet's bottom pane, or a sheet over a view. */
export type CueEditorHost = 'card' | 'pane' | 'sheet'

/** A host asking the editor to show a tab — the cue sheet's Layers and FX read-outs do. */
export interface CueEditorTabRequest {
  tab: CueEditorTab
  /** Bumped per request, so asking again for the tab already asked for still lands. */
  seq: number
}

const LOCKED_TITLE = 'Locked — unlock the show to edit'
const NOT_CURRENT_TITLE = 'This cue is another project’s — make that project current to edit it'

const TAB_TRIGGER_CLASS = cn(
  'h-9 flex-none gap-1 rounded-none border-0 border-b-2 border-transparent px-2.5 text-xs text-muted-foreground shadow-none',
  'data-[state=active]:border-primary data-[state=active]:bg-transparent data-[state=active]:text-foreground data-[state=active]:shadow-none',
  'dark:data-[state=active]:border-primary dark:data-[state=active]:bg-transparent',
)

/**
 * One cue's editor (show-cue-editing plan D1, §4.1): a 40px header and the tabs *Values · Looks · FX
 * · Scenery · Events · Cue*. **Two hosts mount it** — the open card in the cards view and the cue
 * sheet's bottom pane — and a third, a right-hand sheet, is how the fixture sheet's *on Q12* and the
 * Stage view's *Moves with* open a cue in place (`stage/OwnerEditor`, D15).
 *
 * - **The header draws the entry apart from the cue** (P5): the stack and this place's number and
 *   follow are the entry's, written by its id (`PATCH cue-stacks/{sid}/entries/{entryId}`); the
 *   name, fade and curve after the rule are the cue's, shared by every stack it is in (P4), which the
 *   chip names. A cue read with no context entry — loose, or in several places and opened from none
 *   — has no number or follow to write, and says why.
 * - **The tab is the window's**: an open editor keeps its own, and a new one opens on the tab this
 *   window chose last (`cueEditorTab.ts`).
 * - **Values edits the cue's own rows** (`CueValuesGrid` over this editor's `CueRowStore`); Looks and
 *   FX are read-outs until their tables land. Scenery, Events and Cue edit the cue's scenery, its
 *   events, and its notes, hooks and busk pads.
 * - **Locked, it writes nothing** (§4.6): the header's fields and the Values grid are inert, and
 *   Scenery, Events and Cue draw their read-outs. So does a cue the desk will not edit (`canEdit`
 *   false), with that reason. A header field half-typed when its host goes (a GO collapsing a live
 *   card) commits, and so does a pending Values edit.
 * - **It is a key region** (`data-key-region`, §4.5): a key pressed in it is never the cue sheet's.
 *
 * It sends no output: every write here is a stored cue or entry write (P2).
 */
export function CueEditor({
  projectId,
  cueId,
  entryId,
  locked,
  host,
  tabRequest,
  onRequestUnlock,
  className,
}: {
  projectId: number
  cueId: number
  /** The entry it was opened from — its number and follow are this place's. */
  entryId?: number | null
  locked: boolean
  host: CueEditorHost
  tabRequest?: CueEditorTabRequest | null
  /**
   * Lift the show's lock — offered where the lock is the operator's. A Values edit refused by the
   * lock then asks to unlock, as the cue action set's refused actions do.
   */
  onRequestUnlock?: () => void
  className?: string
}) {
  const { cueData: cue, isFetching } = useExpandedCue(projectId, cueId, true, entryId)
  const { data: cooked } = useProjectCueCookedQuery({ projectId, cueId })
  // A cue the desk will not take a write for — another project's — reads as locked does.
  const readOnlyReason = locked ? LOCKED_TITLE : cue?.canEdit === false ? NOT_CURRENT_TITLE : null
  const readOnly = readOnlyReason != null
  // Ids for this editor alone: two editors of one cue can be open at once (a cue twice in a stack,
  // a card under the fixture sheet's).
  const idBase = useId()

  const [tab, setTab] = useState<CueEditorTab>(() => tabRequest?.tab ?? cueEditorTabStore.getSnapshot())
  const choose = (next: CueEditorTab) => {
    setTab(next)
    cueEditorTabStore.set(next)
  }
  useEffect(() => {
    if (tabRequest != null) setTab(tabRequest.tab)
  }, [tabRequest])
  // The key region the Values grid's keys belong to (§4.5) — this editor's root, not another's.
  const regionRef = useRef<HTMLDivElement | null>(null)

  // Keyed by the cue: a host that moves one editor onto another cue (the right-hand sheet does)
  // mints a fresh store, so neither a pending edit nor Undo can reach the next cue.
  return (
    <CueRowStoreProvider key={cueId} projectId={projectId} cueId={cueId} entryId={entryId} readOnly={readOnly}>
    <div
      ref={regionRef}
      className={cn('flex min-h-0 flex-col', className)}
      data-cue-editor={cueId}
      data-cue-editor-host={host}
      data-key-region
    >
      {cue == null ? (
        <div className="flex items-center justify-center py-12 text-muted-foreground">
          {isFetching ? <Loader2 className="size-5 animate-spin" /> : <span className="text-sm">Loading cue…</span>}
        </div>
      ) : (
        <>
          <CueEditorHeader cue={cue} projectId={projectId} readOnlyReason={readOnlyReason} />
          <Tabs value={tab} onValueChange={(v) => choose(v as CueEditorTab)} className="min-h-0 flex-1 gap-0">
            <TabsList className="h-9 w-full shrink-0 justify-start gap-0.5 overflow-x-auto rounded-none border-b bg-transparent p-0 px-3">
              <EditorTab value="values" label="Values" count={cooked?.rows.length} />
              <EditorTab value="looks" label="Looks" count={cue.layers.length} />
              <EditorTab value="fx" label="FX" count={cue.adHocEffects.length} />
              <EditorTab value="scenery" label="Scenery" count={cue.scenery?.length ?? 0} />
              <EditorTab value="events" label="Events" count={cue.events?.length ?? 0} />
              <EditorTab value="cue" label="Cue" />
            </TabsList>
            <div className="min-h-0 flex-1 overflow-y-auto">
              <TabsContent
                value="values"
                className={cn('flex min-h-0 flex-col', host === 'card' ? 'max-h-[32rem]' : 'h-full')}
              >
                <CueValuesGrid region={regionRef} readOnlyReason={readOnlyReason} onRequestUnlock={locked ? onRequestUnlock : undefined} />
              </TabsContent>
              <TabsContent value="looks" className="space-y-2 p-3">
                <CueLayersReadout cue={cue} projectId={projectId} />
                {/* The hand's target for this cue (multi-screen plan §3.5), never while locked —
                    locked is the running state, and a cue must not gain a layer from a stray tap. */}
                {!readOnly && <HandCueLayerStrip projectId={projectId} cue={cue} />}
              </TabsContent>
              <TabsContent value="fx" className="space-y-2 p-3">
                <CueEffectsReadout cue={cue} />
              </TabsContent>
              <TabsContent value="scenery" className="p-3">
                <SceneryTab cue={cue} projectId={projectId} locked={readOnly} idBase={idBase} />
              </TabsContent>
              <TabsContent value="events" className="p-3">
                <EventsTab cue={cue} projectId={projectId} locked={readOnly} idBase={idBase} />
              </TabsContent>
              <TabsContent value="cue" className="p-3">
                <CueTab cue={cue} projectId={projectId} locked={readOnly} idBase={idBase} />
              </TabsContent>
            </div>
          </Tabs>
        </>
      )}
    </div>
    </CueRowStoreProvider>
  )
}

function EditorTab({ value, label, count }: { value: CueEditorTab; label: string; count?: number }) {
  return (
    <TabsTrigger value={value} className={TAB_TRIGGER_CLASS} data-cue-editor-tab={value}>
      {label}
      {count != null && <span className="font-mono text-[10px] tabular-nums opacity-80">{count}</span>}
    </TabsTrigger>
  )
}

/**
 * The header, one 40px chrome row: the entry's fields (the stack, this place's number and follow),
 * a rule, then the cue's (name, fade, curve), the shared chip and the save state.
 */
function CueEditorHeader({ cue, projectId, readOnlyReason }: { cue: Cue; projectId: number; readOnlyReason: string | null }) {
  const [patchCue] = usePatchProjectCueMutation()
  const [patchEntry] = usePatchCueStackEntryMutation()
  const stackId = cue.cueStackId
  const entryId = cue.entryId ?? null
  const locked = readOnlyReason != null
  const loose = stackId == null && (cue.entries?.length ?? 0) === 0
  const entryReason = locked
    ? readOnlyReason
    : stackId != null && entryId != null
      ? null
      : (cue.entries?.length ?? 0) > 1
        ? 'This cue is in several places — its number and follow are each place’s, set where it sits'
        : 'This cue is in no stack — it has no number or follow'

  const places = useMemo<CuePlace[] | undefined>(
    () =>
      cue.entries?.map((e) => ({ stackId: e.stackId, stackName: e.stackName, entryId: e.entryId, cueNumber: e.cueNumber })),
    [cue.entries],
  )

  const writeEntry = (body: { cueNumber?: string | null; autoAdvance?: boolean; autoAdvanceDelayMs?: number | null }) => {
    if (stackId == null || entryId == null) return
    patchEntry({ projectId, stackId, entryId, cueId: cue.id, ...body })
      .unwrap()
      .catch(ignoreReportedError)
  }
  const writeCue = (body: CuePatchInput) => {
    patchCue({ projectId, cueId: cue.id, ...body })
      .unwrap()
      .catch(ignoreReportedError)
  }

  const commitNumber = (next: string) => {
    const trimmed = next.trim() || null
    if (trimmed !== (cue.cueNumber ?? null)) writeEntry({ cueNumber: trimmed })
  }
  const commitFollow = (next: string) => {
    const parsed = parseFollowDelay(next)
    if (parsed === undefined) return false
    if (parsed === null) {
      if (cue.autoAdvance) writeEntry({ autoAdvance: false })
    } else if (!cue.autoAdvance || parsed !== cue.autoAdvanceDelayMs) {
      writeEntry({ autoAdvance: true, autoAdvanceDelayMs: parsed })
    }
  }
  const commitName = (next: string) => {
    const trimmed = next.trim()
    if (trimmed === '') return false
    if (trimmed !== cue.name) writeCue({ name: trimmed })
  }
  const commitFade = (next: string) => {
    const parsed = parseFadeDuration(next)
    if (parsed === undefined) return false
    if (parsed !== (cue.fadeDurationMs ?? null)) writeCue({ fadeDurationMs: parsed })
  }
  const hasFade = (cue.fadeDurationMs ?? 0) > 0

  return (
    <div className={cn(CHROME_ROW_CLASS, 'gap-2 overflow-hidden bg-muted/20 text-xs')} data-cue-editor-header>
      {/* ── The entry's ── */}
      {loose ? (
        <span className="shrink-0 text-muted-foreground" data-cue-editor-entry="loose" title="It is in no stack, so it has no place to number or follow from">
          In no stack · no number or follow
        </span>
      ) : (
      <div className="flex shrink-0 items-center gap-1.5" data-cue-editor-entry title={entryReason ?? undefined}>
        {cue.cueStackName && (
          <span className="max-w-[8rem] truncate text-muted-foreground" title={`In ${cue.cueStackName}`}>
            {cue.cueStackName}
          </span>
        )}
        <InlineEditField
          value={cue.cueNumber ?? ''}
          formatDisplay={(v) => <TruncateStart text={v ? `Q${v}` : '—'} />}
          onCommit={commitNumber}
          ariaLabel="entry cue number"
          commitOnUnmount
          disabled={entryReason != null}
          placeholder="14A"
          title={entryReason ?? (cue.cueNumberAuto ? 'Auto-numbered from position — click to set this entry’s number' : 'This entry’s number')}
          className={cn('max-w-[6rem] px-0.5 font-mono text-sm font-semibold', cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS)}
        />
        <span className="text-muted-foreground">follow</span>
        <InlineEditField
          value={cue.autoAdvance ? formatMs(cue.autoAdvanceDelayMs ?? 0) : ''}
          formatDisplay={(v) => (v ? `auto ${v}` : '—')}
          onCommit={commitFollow}
          ariaLabel="entry follow"
          commitOnUnmount
          disabled={entryReason != null}
          placeholder="2s"
          title={entryReason ?? 'This entry’s follow — a delay (0 fires at once), or blank for none'}
          className="w-16 font-mono"
        />
      </div>
      )}
      <span aria-hidden className="h-5 w-px shrink-0 bg-border" />
      {/* ── The cue's ── */}
      <InlineEditField
        value={cue.name}
        onCommit={commitName}
        ariaLabel="cue name"
        commitOnUnmount
        disabled={locked}
        title={locked ? readOnlyReason ?? undefined : 'The cue’s name — every stack it is in shows it'}
        className="min-w-0 max-w-full truncate text-sm font-medium"
      />
      <InlineEditField
        value={formatFadeDuration(cue.fadeDurationMs)}
        formatDisplay={(v) => v || 'SNAP'}
        onCommit={commitFade}
        ariaLabel="cue fade duration"
        commitOnUnmount
        placeholder="2s"
        disabled={locked}
        title={locked ? readOnlyReason ?? undefined : 'Fade duration — e.g. 2s, 500ms (a bare number is seconds)'}
        className="w-14 shrink-0 text-right font-mono"
      />
      <Select
        value={cue.fadeCurve}
        onValueChange={(curve) => curve !== cue.fadeCurve && writeCue({ fadeCurve: curve })}
        disabled={locked || !hasFade}
      >
        <SelectTrigger
          size="sm"
          aria-label="cue fade curve"
          className="h-7 w-[7.5rem] shrink-0 text-xs data-[size=sm]:h-7"
          title={locked ? readOnlyReason ?? undefined : hasFade ? 'The fade’s curve' : 'Set a fade duration to choose a curve'}
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {CUE_CURVE_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <SharedCueChip places={places} entryId={entryId ?? -1} />
      <span className="flex-1" />
      <CueValuesSaveState />
      <SaveStatusIndicator cueId={cue.id} />
    </div>
  )
}

function SceneryTab({ cue, projectId, locked, idBase }: { cue: Cue; projectId: number; locked: boolean; idBase: string }) {
  if (cue.cueType === 'MARKER') return null
  if (locked) {
    const scenery = cue.scenery ?? []
    const tracked = cue.trackedScenery ?? []
    return scenery.length === 0 && tracked.length === 0 ? (
      <p className="text-[11px] text-muted-foreground">Changes no scenery.</p>
    ) : (
      <CueSceneryReadout projectId={projectId} scenery={scenery} tracked={tracked} />
    )
  }
  return (
    <CueSceneryEditorBody
      projectId={projectId}
      cue={{ id: cue.id, name: cue.name, cueNumber: cue.cueNumber, entryId: cue.entryId ?? undefined, scenery: cue.scenery }}
      idPrefix={`${idBase}-scenery`}
    />
  )
}

const NO_EVENTS: NonNullable<Cue['events']> = []

function EventsTab({ cue, projectId, locked, idBase }: { cue: Cue; projectId: number; locked: boolean; idBase: string }) {
  const [setCueEvents] = useSetCueEventsMutation()
  const events = cue.events ?? NO_EVENTS
  if (cue.cueType === 'MARKER') return null
  if (locked) {
    return events.length === 0 ? <p className="text-[11px] text-muted-foreground">Fires nothing.</p> : <CueEventsReadout events={events} />
  }
  return (
    <div className="space-y-2">
      <CueEventsEditor
        projectId={projectId}
        events={events}
        idPrefix={`${idBase}-events`}
        onSave={(items) => setCueEvents({ projectId, cueId: cue.id, events: items }).unwrap()}
      />
      <p className="text-[11px] text-muted-foreground">
        Fires only while the desk is armed. Unarmed, the GO goes and the events are skipped and announced — never queued.
      </p>
    </div>
  )
}

/**
 * The Values grid's save state, beside the per-cue `SaveStatusIndicator` (show-cue-editing plan
 * D10): that pill already says *Saving…* and *Saved* for every PATCH of this cue, so this says the
 * two things it cannot — an edit still waiting for its flush, and a save the desk refused.
 */
function CueValuesSaveState() {
  const state = useCueRowSaveState()
  if (state === 'dirty') {
    return (
      <span className="shrink-0 text-xs text-muted-foreground" data-cue-values-save="dirty" title="Values waiting to save">
        Unsaved
      </span>
    )
  }
  if (state === 'error') {
    return (
      <span className="shrink-0 text-xs text-destructive" data-cue-values-save="error" title="The desk refused the last values save — edit again to retry">
        Save failed
      </span>
    )
  }
  return null
}
