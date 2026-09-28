import { forwardRef } from 'react'
import {
  Sheet,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet'
import { EditStageRegionForm, type EditStageRegionFormHandle } from './EditStageRegionForm'
import type { StageRegionDto } from '@/api/stageRegionApi'

export type EditStageRegionSheetHandle = EditStageRegionFormHandle

interface EditStageRegionSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  region: StageRegionDto | null
  projectId: number
}

export const EditStageRegionSheet = forwardRef<EditStageRegionSheetHandle, EditStageRegionSheetProps>(function EditStageRegionSheet(
  { open, onOpenChange, region, projectId },
  ref,
) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md gap-0">
        <SheetTitle className="sr-only">{region ? 'Edit Stage Region' : 'New Stage Region'}</SheetTitle>
        {open && (
          <EditStageRegionForm
            ref={ref}
            key={region?.uuid ?? 'new'}
            region={region}
            projectId={projectId}
            onClose={() => onOpenChange(false)}
          />
        )}
      </SheetContent>
    </Sheet>
  )
})
