import { useCallback } from 'react'
import { toast } from 'sonner'
import { CuePropertiesSheet } from '@/components/cues/CuePropertiesSheet'
import { CueStackForm } from '@/components/cues/CueStackForm'
import { LookDetailSheet } from '@/components/looks/LookDetailSheet'
import { duplicateName } from '@/lib/duplicateName'
import { formatError } from '@/lib/formatError'
import type { MovesWithEntry } from '@/lib/scenery'
import { useProjectCueQuery } from '@/store/cues'
import { useProjectCueStackListQuery, useSaveProjectCueStackMutation } from '@/store/cueStacks'
import { useCopyLookMutation, useLookListQuery } from '@/store/looks'
import type { CueStackInput } from '@/api/cueStacksApi'
import type { LookSummary } from '@/api/looksApi'

/**
 * The editor a *Moves with* entry opens (scenery-programmer plan D11), in place over the Stage view:
 * a cue's **Cue properties**, a stack's **Stack settings** (its set) or the **Look sheet** — the
 * three places a scenery change is edited, since it is never edited on the element. Each is the
 * owner's own sheet, mounted here rather than reached by navigating away, so the operator keeps the
 * stage, the camera and the element form they came from.
 */
export function OwnerEditor({
  projectId,
  entry,
  onClose,
}: {
  projectId: number
  /** What is open, or null for nothing. */
  entry: Pick<MovesWithEntry, 'kind' | 'id'> | null
  onClose: () => void
}) {
  const onOpenChange = useCallback(
    (open: boolean) => {
      if (!open) onClose()
    },
    [onClose],
  )
  return (
    <>
      <CueEditor projectId={projectId} cueId={entry?.kind === 'cue' ? entry.id : null} onOpenChange={onOpenChange} />
      <StackEditor projectId={projectId} stackId={entry?.kind === 'set' ? entry.id : null} onOpenChange={onOpenChange} />
      <LookEditor projectId={projectId} lookId={entry?.kind === 'look' ? entry.id : null} onOpenChange={onOpenChange} />
    </>
  )
}

function CueEditor({ projectId, cueId, onOpenChange }: { projectId: number; cueId: number | null; onOpenChange: (open: boolean) => void }) {
  const { currentData: cue } = useProjectCueQuery({ projectId, cueId: cueId ?? 0 }, { skip: cueId == null })
  return <CuePropertiesSheet cue={cueId == null ? null : (cue ?? null)} projectId={projectId} open={cueId != null} onOpenChange={onOpenChange} />
}

function StackEditor({ projectId, stackId, onOpenChange }: { projectId: number; stackId: number | null; onOpenChange: (open: boolean) => void }) {
  const { data: stacks } = useProjectCueStackListQuery(projectId, { skip: stackId == null })
  const [saveStack, { isLoading }] = useSaveProjectCueStackMutation()
  const stack = stackId == null ? null : ((stacks ?? []).find((s) => s.id === stackId) ?? null)
  const onSave = useCallback(
    async (input: CueStackInput) => {
      if (stackId == null) return
      await saveStack({ projectId, stackId, ...input }).unwrap()
    },
    [projectId, saveStack, stackId],
  )
  return (
    <CueStackForm
      open={stack != null}
      onOpenChange={onOpenChange}
      projectId={projectId}
      stack={stack}
      // The set from the live list, as `ShowOverview` hands it: it saves itself.
      scenery={stack?.scenery}
      onSave={onSave}
      isSaving={isLoading}
    />
  )
}

function LookEditor({ projectId, lookId, onOpenChange }: { projectId: number; lookId: number | null; onOpenChange: (open: boolean) => void }) {
  const { data: looks } = useLookListQuery({ projectId }, { skip: lookId == null })
  const [copyLook] = useCopyLookMutation()
  const look = lookId == null ? null : ((looks ?? []).find((l) => l.id === lookId) ?? null)
  // The sheet's Duplicate, as the Looks route does it: one copy, the library's `(Copy n)` rule.
  const onDuplicate = useCallback(
    (source: LookSummary) => {
      const newName = duplicateName(source.name, new Set((looks ?? []).map((l) => l.name)))
      copyLook({ projectId, lookId: source.id, targetProjectId: projectId, newName })
        .unwrap()
        // `copyLook` is in `SILENT_ENDPOINTS`: a sheet reports its own copies.
        .catch((err) => toast.error(formatError(err), { id: 'sheet-write:looks:duplicate' }))
    },
    [copyLook, looks, projectId],
  )
  return <LookDetailSheet open={look != null} onOpenChange={onOpenChange} projectId={projectId} look={look} onDuplicate={onDuplicate} />
}
