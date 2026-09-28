import { forwardRef, useImperativeHandle, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { SheetHeader, SheetBody, SheetFooter } from '@/components/ui/sheet'
import { Trash2 } from 'lucide-react'
import {
  useCreateStageRegionMutation,
  useUpdateStageRegionMutation,
  useDeleteStageRegionMutation,
} from '@/store/stageRegions'
import type { StageRegionDto, UpdateStageRegionRequest } from '@/api/stageRegionApi'
import { formatError } from '@/lib/formatError'
import { parseNullableNumber } from '@/lib/utils'
import { FieldGroup, NumberField } from '@/components/ui/form-fields'

interface EditStageRegionFormProps {
  region: StageRegionDto | null
  projectId: number
  onClose: () => void
  /**
   * Focus the name field on mount.
   *
   * True for the sheet, where the user deliberately opened the form to edit it.
   * **False for the docked stage panel**: there the form appears because the user
   * clicked an object on the canvas, and moving focus into a text field silently
   * disables every keyboard shortcut — arrow-key nudge, Delete, ⌘D — since they all
   * (correctly) stand down while the user is typing.
   */
  autoFocusName?: boolean
}

interface FormState {
  name: string
  centerX: number | null
  centerY: number | null
  centerZ: number | null
  widthM: number | null
  depthM: number | null
  heightM: number | null
  yawDeg: number | null
  sortOrder: number | null
}

const EMPTY_FORM: FormState = {
  name: '',
  centerX: null,
  centerY: null,
  centerZ: null,
  widthM: null,
  depthM: null,
  heightM: null,
  yawDeg: null,
  sortOrder: null,
}

function fromRegion(region: StageRegionDto): FormState {
  return {
    name: region.name,
    centerX: region.centerX,
    centerY: region.centerY,
    centerZ: region.centerZ,
    widthM: region.widthM,
    depthM: region.depthM,
    heightM: region.heightM,
    yawDeg: region.yawDeg,
    sortOrder: region.sortOrder,
  }
}

export interface EditStageRegionFormHandle {
  setPosition: (next: {
    centerX?: number | null
    centerY?: number | null
    centerZ?: number | null
    yawDeg?: number | null
    widthM?: number | null
    depthM?: number | null
    heightM?: number | null
  }) => void
}

export const EditStageRegionForm = forwardRef<EditStageRegionFormHandle, EditStageRegionFormProps>(function EditStageRegionForm(
  { region, projectId, onClose, autoFocusName = true },
  ref,
) {
  const isEdit = region != null
  // Callers re-key on region.uuid so this form mounts fresh per region.
  const [form, setForm] = useState<FormState>(() => (region ? fromRegion(region) : EMPTY_FORM))

  const [createRegion, { isLoading: isCreating }] = useCreateStageRegionMutation()
  const [updateRegion, { isLoading: isUpdating }] = useUpdateStageRegionMutation()
  const [deleteRegion, { isLoading: isDeleting }] = useDeleteStageRegionMutation()

  useImperativeHandle(ref, () => ({
    setPosition: (next) => setForm((prev) => ({ ...prev, ...next })),
  }), [])

  const isValid = form.name.trim().length > 0
  const isBusy = isCreating || isUpdating || isDeleting

  const handleSave = async () => {
    if (!isValid) return
    try {
      if (region) {
        const body: UpdateStageRegionRequest = {}
        const trimmed = form.name.trim()
        if (trimmed !== region.name) body.name = trimmed
        if (form.centerX !== region.centerX) body.centerX = form.centerX
        if (form.centerY !== region.centerY) body.centerY = form.centerY
        if (form.centerZ !== region.centerZ) body.centerZ = form.centerZ
        if (form.widthM !== region.widthM) body.widthM = form.widthM
        if (form.depthM !== region.depthM) body.depthM = form.depthM
        if (form.heightM !== region.heightM) body.heightM = form.heightM
        if (form.yawDeg !== region.yawDeg) body.yawDeg = form.yawDeg
        if (form.sortOrder != null && form.sortOrder !== region.sortOrder) {
          body.sortOrder = form.sortOrder
        }
        await updateRegion({ projectId, regionId: region.id, ...body }).unwrap()
        toast.success('Stage region saved')
      } else {
        await createRegion({
          projectId,
          name: form.name.trim(),
          centerX: form.centerX,
          centerY: form.centerY,
          centerZ: form.centerZ,
          widthM: form.widthM,
          depthM: form.depthM,
          heightM: form.heightM,
          yawDeg: form.yawDeg,
        }).unwrap()
        toast.success('Stage region created')
      }
      onClose()
    } catch (err) {
      toast.error(`Failed to save stage region: ${formatError(err)}`)
    }
  }

  const handleDelete = async () => {
    if (!region) return
    try {
      await deleteRegion({ projectId, regionId: region.id }).unwrap()
      toast.success('Stage region deleted')
      onClose()
    } catch (err) {
      toast.error(`Failed to delete stage region: ${formatError(err)}`)
    }
  }

  return (
    <div className="flex flex-1 min-h-0 flex-col">
      <SheetHeader>
        <h2 className="text-foreground font-semibold">{isEdit ? 'Edit Stage Region' : 'New Stage Region'}</h2>
        <p className="text-xs text-muted-foreground">
          Coordinates are in metres, FOH-relative. Z is up; leave blank to inherit defaults.
        </p>
      </SheetHeader>

      <SheetBody>
        <div className="space-y-1.5">
          <Label htmlFor="region-name">Name</Label>
          <Input
            id="region-name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            autoFocus={autoFocusName}
          />
        </div>

        <FieldGroup label="Centre (m)">
          <NumberField
            id="region-cx"
            label="X"
            value={form.centerX}
            onChange={(v) => setForm({ ...form, centerX: v })}
          />
          <NumberField
            id="region-cy"
            label="Y"
            value={form.centerY}
            onChange={(v) => setForm({ ...form, centerY: v })}
          />
          <NumberField
            id="region-cz"
            label="Z"
            value={form.centerZ}
            onChange={(v) => setForm({ ...form, centerZ: v })}
          />
        </FieldGroup>

        <FieldGroup label="Size (m)">
          <NumberField
            id="region-w"
            label="Width"
            value={form.widthM}
            onChange={(v) => setForm({ ...form, widthM: v })}
            min={0}
          />
          <NumberField
            id="region-d"
            label="Depth"
            value={form.depthM}
            onChange={(v) => setForm({ ...form, depthM: v })}
            min={0}
          />
          <NumberField
            id="region-h"
            label="Height"
            value={form.heightM}
            onChange={(v) => setForm({ ...form, heightM: v })}
            min={0}
          />
        </FieldGroup>

        <div className="space-y-1.5">
          <Label htmlFor="region-yaw">Yaw (deg)</Label>
          <Input
            id="region-yaw"
            type="number"
            value={form.yawDeg ?? ''}
            onChange={(e) => setForm({ ...form, yawDeg: parseNullableNumber(e.target.value) })}
            onFocus={(e) => e.target.select()}
          />
        </div>

        {isEdit && (
          <div className="space-y-1.5">
            <Label htmlFor="region-sort">Sort order</Label>
            <Input
              id="region-sort"
              type="number"
              value={form.sortOrder ?? ''}
              onChange={(e) => setForm({ ...form, sortOrder: parseNullableNumber(e.target.value) })}
              onFocus={(e) => e.target.select()}
            />
          </div>
        )}
      </SheetBody>

      {isEdit ? (
        <SheetFooter className="flex-row justify-between">
          <Button variant="destructive" size="sm" onClick={handleDelete} disabled={isBusy}>
            <Trash2 className="size-3.5 mr-1.5" />
            {isDeleting ? 'Deleting...' : 'Delete'}
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose} disabled={isBusy}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={!isValid || isBusy}>
              {isUpdating ? 'Saving...' : 'Save'}
            </Button>
          </div>
        </SheetFooter>
      ) : (
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={isBusy}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={!isValid || isBusy}>
            {isCreating ? 'Creating...' : 'Create'}
          </Button>
        </SheetFooter>
      )}
    </div>
  )
})
