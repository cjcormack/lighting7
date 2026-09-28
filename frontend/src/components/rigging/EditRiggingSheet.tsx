import { forwardRef } from 'react'
import {
  Sheet,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet'
import { EditRiggingForm, type EditRiggingFormHandle } from './EditRiggingForm'
import type { RiggingDto } from '@/api/riggingApi'

export type EditRiggingSheetHandle = EditRiggingFormHandle

interface EditRiggingSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  rigging: RiggingDto | null
  projectId: number
}

export const EditRiggingSheet = forwardRef<EditRiggingSheetHandle, EditRiggingSheetProps>(function EditRiggingSheet(
  { open, onOpenChange, rigging, projectId },
  ref,
) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md gap-0">
        <SheetTitle className="sr-only">{rigging ? 'Edit Rigging' : 'New Rigging'}</SheetTitle>
        {open && (
          <EditRiggingForm
            ref={ref}
            key={rigging?.uuid ?? 'new'}
            rigging={rigging}
            projectId={projectId}
            onClose={() => onOpenChange(false)}
          />
        )}
      </SheetContent>
    </Sheet>
  )
})
