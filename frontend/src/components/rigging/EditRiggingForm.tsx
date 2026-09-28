import { forwardRef, useImperativeHandle, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { SheetHeader, SheetBody, SheetFooter } from '@/components/ui/sheet'
import { Trash2 } from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  useCreateRiggingMutation,
  useUpdateRiggingMutation,
  useDeleteRiggingMutation,
} from '@/store/riggings'
import type { RiggingDto, UpdateRiggingRequest } from '@/api/riggingApi'
import { formatError } from '@/lib/formatError'
import { parseNullableNumber } from '@/lib/utils'
import { FieldGroup, NumberField } from '@/components/ui/form-fields'

const KIND_OPTIONS = ['TRUSS', 'BAR', 'BOOM', 'PIPE', 'FLOOR_STAND', 'OTHER'] as const
type Kind = typeof KIND_OPTIONS[number]

interface EditRiggingFormProps {
  rigging: RiggingDto | null
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
  kind: Kind
  positionX: number | null
  positionY: number | null
  positionZ: number | null
  yawDeg: number | null
  pitchDeg: number | null
  rollDeg: number | null
  lengthM: number | null
  sortOrder: number | null
}

const EMPTY_FORM: FormState = {
  name: '',
  kind: 'TRUSS',
  positionX: 0,
  positionY: 0,
  positionZ: 4.5,
  yawDeg: 0,
  pitchDeg: 0,
  rollDeg: 0,
  lengthM: 3,
  sortOrder: null,
}

function fromRigging(rigging: RiggingDto): FormState {
  return {
    name: rigging.name,
    kind: KIND_OPTIONS.includes(rigging.kind as Kind) ? (rigging.kind as Kind) : 'OTHER',
    positionX: rigging.positionX,
    positionY: rigging.positionY,
    positionZ: rigging.positionZ,
    yawDeg: rigging.yawDeg,
    pitchDeg: rigging.pitchDeg,
    rollDeg: rigging.rollDeg,
    lengthM: rigging.lengthM,
    sortOrder: rigging.sortOrder,
  }
}

export interface EditRiggingFormHandle {
  setPosition: (next: {
    positionX?: number | null
    positionY?: number | null
    positionZ?: number | null
    yawDeg?: number | null
    pitchDeg?: number | null
    rollDeg?: number | null
    lengthM?: number | null
  }) => void
}

export const EditRiggingForm = forwardRef<EditRiggingFormHandle, EditRiggingFormProps>(function EditRiggingForm(
  { rigging, projectId, onClose, autoFocusName = true },
  ref,
) {
  const isEdit = rigging != null
  // Callers re-key on rigging.uuid so this form mounts fresh per rigging.
  const [form, setForm] = useState<FormState>(() => (rigging ? fromRigging(rigging) : EMPTY_FORM))

  const [createRigging, { isLoading: isCreating }] = useCreateRiggingMutation()
  const [updateRigging, { isLoading: isUpdating }] = useUpdateRiggingMutation()
  const [deleteRigging, { isLoading: isDeleting }] = useDeleteRiggingMutation()

  useImperativeHandle(ref, () => ({
    setPosition: (next) => setForm((prev) => ({ ...prev, ...next })),
  }), [])

  const isValid = form.name.trim().length > 0
  const isBusy = isCreating || isUpdating || isDeleting

  const handleSave = async () => {
    if (!isValid) return
    const trimmedName = form.name.trim()
    try {
      if (rigging) {
        const body: UpdateRiggingRequest = {}
        if (trimmedName !== rigging.name) body.name = trimmedName
        if (form.kind !== rigging.kind) body.kind = form.kind
        if (form.positionX !== rigging.positionX) body.positionX = form.positionX
        if (form.positionY !== rigging.positionY) body.positionY = form.positionY
        if (form.positionZ !== rigging.positionZ) body.positionZ = form.positionZ
        if (form.yawDeg !== rigging.yawDeg) body.yawDeg = form.yawDeg
        if (form.pitchDeg !== rigging.pitchDeg) body.pitchDeg = form.pitchDeg
        if (form.rollDeg !== rigging.rollDeg) body.rollDeg = form.rollDeg
        if (form.lengthM !== rigging.lengthM) body.lengthM = form.lengthM
        if (form.sortOrder != null && form.sortOrder !== rigging.sortOrder) {
          body.sortOrder = form.sortOrder
        }
        await updateRigging({ projectId, riggingId: rigging.id, ...body }).unwrap()
        toast.success('Rigging saved')
      } else {
        await createRigging({
          projectId,
          name: trimmedName,
          kind: form.kind,
          positionX: form.positionX,
          positionY: form.positionY,
          positionZ: form.positionZ,
          yawDeg: form.yawDeg,
          pitchDeg: form.pitchDeg,
          rollDeg: form.rollDeg,
          lengthM: form.lengthM,
        }).unwrap()
        toast.success('Rigging created')
      }
      onClose()
    } catch (err) {
      toast.error(`Failed to save rigging: ${formatError(err)}`)
    }
  }

  const handleDelete = async () => {
    if (!rigging) return
    try {
      await deleteRigging({ projectId, riggingId: rigging.id }).unwrap()
      toast.success('Rigging deleted')
      onClose()
    } catch (err) {
      toast.error(`Failed to delete rigging: ${formatError(err)}`)
    }
  }

  return (
    <div className="flex flex-1 min-h-0 flex-col">
      <SheetHeader>
        <h2 className="text-foreground font-semibold">{isEdit ? 'Edit Rigging' : 'New Rigging'}</h2>
        <p className="text-xs text-muted-foreground">
          Position is in metres, FOH-relative (Z is up). Rotation is yaw/pitch/roll in degrees.
        </p>
      </SheetHeader>

      <SheetBody>
        <div className="space-y-1.5">
          <Label htmlFor="rigging-name">Name</Label>
          <Input
            id="rigging-name"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            autoFocus={autoFocusName}
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="rigging-kind">Kind</Label>
          <Select
            value={form.kind}
            onValueChange={(v) => setForm({ ...form, kind: v as Kind })}
          >
            <SelectTrigger id="rigging-kind" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {KIND_OPTIONS.map((opt) => (
                <SelectItem key={opt} value={opt}>{opt}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <FieldGroup label="Position (m)">
          <NumberField
            id="rigging-px"
            label="X"
            value={form.positionX}
            onChange={(v) => setForm({ ...form, positionX: v })}
          />
          <NumberField
            id="rigging-py"
            label="Y"
            value={form.positionY}
            onChange={(v) => setForm({ ...form, positionY: v })}
          />
          <NumberField
            id="rigging-pz"
            label="Z"
            value={form.positionZ}
            onChange={(v) => setForm({ ...form, positionZ: v })}
          />
        </FieldGroup>

        <FieldGroup label="Rotation (deg)">
          <NumberField
            id="rigging-yaw"
            label="Yaw"
            value={form.yawDeg}
            onChange={(v) => setForm({ ...form, yawDeg: v })}
          />
          <NumberField
            id="rigging-pitch"
            label="Pitch"
            value={form.pitchDeg}
            onChange={(v) => setForm({ ...form, pitchDeg: v })}
          />
          <NumberField
            id="rigging-roll"
            label="Roll"
            value={form.rollDeg}
            onChange={(v) => setForm({ ...form, rollDeg: v })}
          />
        </FieldGroup>

        <div className="space-y-1.5">
          <Label htmlFor="rigging-length">Length (m)</Label>
          <Input
            id="rigging-length"
            type="number"
            step="0.1"
            min={0}
            value={form.lengthM ?? ''}
            onChange={(e) => setForm({ ...form, lengthM: parseNullableNumber(e.target.value) })}
            onFocus={(e) => e.target.select()}
            placeholder="3.0"
          />
          <p className="text-xs text-muted-foreground">
            Length along the rig&rsquo;s local X axis. Used for the 3D bar render.
          </p>
        </div>

        {isEdit && (
          <div className="space-y-1.5">
            <Label htmlFor="rigging-sort">Sort order</Label>
            <Input
              id="rigging-sort"
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
