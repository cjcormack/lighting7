import { type Ref } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EditPatchForm, type EditPatchFormHandle } from '@/components/patches/EditPatchForm'
import { EditStageRegionForm, type EditStageRegionFormHandle } from '@/components/stage/EditStageRegionForm'
import { EditRiggingForm, type EditRiggingFormHandle } from '@/components/rigging/EditRiggingForm'
import { EditSceneElementForm, type EditSceneElementFormHandle } from '@/components/stage/EditSceneElementForm'
import type { FixturePatch } from '@/api/patchApi'
import type { StageRegionDto } from '@/api/stageRegionApi'
import type { RiggingDto } from '@/api/riggingApi'
import type { StageElementDto } from '@/api/stageElementApi'

export type StageEditorTarget =
  | { kind: 'patch'; patch: FixturePatch }
  | { kind: 'region'; region: StageRegionDto | null }
  | { kind: 'rigging'; rigging: RiggingDto | null }
  /** A scene element: scenery or the venue (stage-view plan session 5). */
  | { kind: 'element'; element: StageElementDto }

interface StageEditorPanelProps {
  target: StageEditorTarget
  projectId: number
  existingPatches: FixturePatch[]
  onCollapse: () => void
  onDismiss: () => void
  patchRef?: Ref<EditPatchFormHandle>
  regionRef?: Ref<EditStageRegionFormHandle>
  riggingRef?: Ref<EditRiggingFormHandle>
  elementRef?: Ref<EditSceneElementFormHandle>
}

export function StageEditorPanel({
  target,
  projectId,
  existingPatches,
  onCollapse,
  onDismiss,
  patchRef,
  regionRef,
  riggingRef,
  elementRef,
}: StageEditorPanelProps) {
  return (
    <aside className="relative flex w-full flex-col border-l bg-background sm:w-[360px]">
      <button
        type="button"
        onClick={onCollapse}
        className="absolute right-3 top-3 z-10 rounded-sm p-1 text-muted-foreground opacity-70 transition-opacity hover:opacity-100"
        aria-label="Collapse editor panel"
      >
        <ChevronRight className="size-4" />
      </button>
      {target.kind === 'patch' && (
        <EditPatchForm
          autoFocusName={false}
          ref={patchRef}
          key={`patch-${target.patch.id}`}
          patch={target.patch}
          projectId={projectId}
          existingPatches={existingPatches}
          onClose={onDismiss}
        />
      )}
      {target.kind === 'region' && (
        <EditStageRegionForm
          autoFocusName={false}
          ref={regionRef}
          key={`region-${target.region?.uuid ?? 'new'}`}
          region={target.region}
          projectId={projectId}
          onClose={onDismiss}
        />
      )}
      {target.kind === 'rigging' && (
        <EditRiggingForm
          autoFocusName={false}
          ref={riggingRef}
          key={`rigging-${target.rigging?.uuid ?? 'new'}`}
          rigging={target.rigging}
          projectId={projectId}
          onClose={onDismiss}
        />
      )}
      {target.kind === 'element' && (
        <EditSceneElementForm
          ref={elementRef}
          key={`element-${target.element.uuid}`}
          element={target.element}
          projectId={projectId}
          onClose={onDismiss}
        />
      )}
    </aside>
  )
}

export function StageEditorPanelStub({ onExpand }: { onExpand: () => void }) {
  return (
    <aside className="flex w-10 flex-col items-center border-l bg-background py-2">
      <Button variant="ghost" size="sm" onClick={onExpand} aria-label="Show editor panel">
        <ChevronLeft className="size-4" />
      </Button>
    </aside>
  )
}
