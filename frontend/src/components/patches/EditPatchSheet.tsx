import { forwardRef } from 'react'
import {
  Sheet,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet'
import { EditPatchForm, type EditPatchFormHandle } from './EditPatchForm'
import { isVenueOwned, type FixturePatch } from '@/api/patchApi'
import { VenuePatchPanel } from './VenuePatchPanel'

export type EditPatchSheetHandle = EditPatchFormHandle

interface EditPatchSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  patch: FixturePatch | null
  projectId: number
  existingPatches: FixturePatch[]
}

export const EditPatchSheet = forwardRef<EditPatchSheetHandle, EditPatchSheetProps>(function EditPatchSheet(
  { open, onOpenChange, patch, projectId, existingPatches },
  ref,
) {
  if (!patch) return null
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md gap-0">
        <SheetTitle className="sr-only">Edit Fixture</SheetTitle>
        {/* A venue's fixture in a show is read-only here and can only be hidden (venue plan session 3). */}
        {isVenueOwned(patch) ? (
          <VenuePatchPanel key={patch.id} patch={patch} projectId={projectId} onClose={() => onOpenChange(false)} />
        ) : (
          <EditPatchForm
            ref={ref}
            key={patch.id}
            patch={patch}
            projectId={projectId}
            existingPatches={existingPatches}
            onClose={() => onOpenChange(false)}
          />
        )}
      </SheetContent>
    </Sheet>
  )
})
