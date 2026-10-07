import { useMemo, type RefObject } from 'react'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { SceneryEditor } from '@/components/scenery/SceneryEditor'
import { summariseSceneryState } from '@/lib/scenery'
import { useProjectCueQuery } from '@/store/cues'
import { useSetCueSceneryMutation } from '@/store/scenery'
import { useStageElementListQuery } from '@/store/stageElements'
import type { SceneryChange, TrackedScenery } from '@/api/sceneryApi'
import type { StageElementDto } from '@/api/stageElementApi'

const EMPTY_SCENERY: SceneryChange[] = []

/** The cue whose changes are open: a stack-list entry carries all of this (`CueStackCueEntry`). */
export interface CueSceneryEditorCue {
  id: number
  name: string
  cueNumber: string | null
  /** The cue's own changes, off the stack list — what the editor edits. */
  scenery?: SceneryChange[]
}

/**
 * A cue's scenery changes, edited where the cue is read rather than behind its card
 * (scenery-programmer plan D13, D14): the cue table's Scenery cell and the Prompt Book's rail card
 * open this, and both mean the same editor Cue properties hosts — `SceneryEditor` with times, each
 * row's `SceneryControl` writing on release and every gesture saving the cue's **whole** list.
 *
 * It opens through `EditorSurface` at [anchorRef] — the button that opened it, a popover on a desk
 * or an iPad, a bottom sheet on an upright phone and the **wide** side sheet on a short viewport,
 * since a row is an element select, a time field and the control. Open while [cue] is set; the host
 * clears it to close (and does so when the show locks under it — every host opens it only unlocked).
 *
 * Above the rows it says what the cue **tracks** into it (`CueDetails.trackedScenery`, the cue
 * card's own read, fetched only while open), so the rows can be read as changes rather than as the
 * whole stage: *Tracked into it: Moon out, Sofa shown (Q14) · Tabs drawn (Q2)*.
 */
export function CueSceneryEditor({
  projectId,
  cue,
  anchorRef,
  onClose,
}: {
  projectId: number
  cue: CueSceneryEditorCue | null
  anchorRef: RefObject<HTMLElement | null>
  onClose: () => void
}) {
  return (
    <EditorSurface
      open={cue != null}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={cue == null ? 'Scenery' : `${cueLabel(cue)} · Scenery`}
      align="start"
      anchorRef={anchorRef}
      wide
      contentClassName="w-[26rem] max-w-[calc(100vw-2rem)] max-h-[min(36rem,70vh)] overflow-y-auto p-3"
    >
      {cue != null && <CueSceneryEditorBody projectId={projectId} cue={cue} />}
    </EditorSurface>
  )
}

function CueSceneryEditorBody({ projectId, cue }: { projectId: number; cue: CueSceneryEditorCue }) {
  const { currentData: details } = useProjectCueQuery({ projectId, cueId: cue.id })
  const { data: elements } = useStageElementListQuery(projectId)
  const [setCueScenery] = useSetCueSceneryMutation()
  const tracked = useMemo(
    () => describeTracked(details?.trackedScenery ?? [], new Map((elements ?? []).map((e) => [e.uuid, e]))),
    [details?.trackedScenery, elements],
  )

  return (
    <div className="space-y-2" data-cue-scenery-editor={cue.id}>
      <div className="flex items-baseline gap-2">
        <span className="truncate text-sm font-semibold">
          {cueLabel(cue)}
          {cue.cueNumber ? ` · ${cue.name}` : ''}
        </span>
        <span className="shrink-0 text-xs text-muted-foreground">what changes here</span>
      </div>
      {tracked && <p className="text-[11px] text-muted-foreground">Tracked into it: {tracked}</p>}
      <SceneryEditor
        projectId={projectId}
        scenery={cue.scenery ?? EMPTY_SCENERY}
        withTime
        addLabel="Add change"
        idPrefix={`cue-${cue.id}-scenery-surface`}
        onSave={(scenery) => setCueScenery({ projectId, cueId: cue.id, scenery }).unwrap()}
      />
      <p className="text-[11px] text-muted-foreground">
        Saves as you go. A blank time moves with the cue&apos;s fade.
      </p>
    </div>
  )
}

/** A cue as its read-outs name it: *Q15*, or its name where it has no number. */
function cueLabel(cue: Pick<CueSceneryEditorCue, 'name' | 'cueNumber'>): string {
  return cue.cueNumber ? `Q${cue.cueNumber}` : cue.name
}

/**
 * What a cue tracks into it, grouped by where each piece was last moved, in the order the desk
 * lists them: `Moon out, Sofa shown (Q14) · Tabs drawn (Q2)`. Empty when it tracks nothing.
 */
function describeTracked(tracked: readonly TrackedScenery[], byUuid: ReadonlyMap<string, StageElementDto>): string {
  const groups = new Map<string, string[]>()
  for (const t of tracked) {
    const label = t.fromCueLabel
    const from = t.fromSet ? "the stack's set" : label == null ? 'an earlier cue' : /^\d/.test(label) ? `Q${label}` : label
    const element = byUuid.get(t.elementUuid)
    const words = summariseSceneryState(element, element?.name ?? t.elementName, t.state)
    groups.set(from, [...(groups.get(from) ?? []), words])
  }
  return [...groups].map(([from, items]) => `${items.join(', ')} (${from})`).join(' · ')
}
