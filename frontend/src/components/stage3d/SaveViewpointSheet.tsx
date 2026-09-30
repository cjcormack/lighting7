import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import type { CreateStageViewpointRequest, StageViewpointDto } from '@/api/stageViewpointApi'
import { useCreateStageViewpointMutation } from '@/store/stageViewpoints'
import { formatError } from '@/lib/formatError'

/** What the sheet is about to save, in words, from the request it will send. */
function describe(request: CreateStageViewpointRequest, seatingName: string | null): string {
  const at = (x?: number | null, y?: number | null, z?: number | null) =>
    [x, y, z].map((n) => (n == null ? '?' : n.toFixed(1))).join(', ')
  if (request.kind === 'SEAT') {
    return `Sitting in seat ${request.seatId} of ${seatingName ?? 'the seating'}, looking where you are looking now, ${request.fovDeg}° lens.`
  }
  if (request.kind === 'ORBIT') {
    return `An orbit view from ${at(request.eyeX, request.eyeY, request.eyeZ)} m, circling ${at(request.targetX, request.targetY, request.targetZ)} m.`
  }
  return `Standing at ${at(request.eyeX, request.eyeY, request.eyeZ)} m, looking where you are looking now, ${request.fovDeg}° lens.`
}

/**
 * *Save this view…* (`Stage.dc.html` §4): name where this window's camera is now and keep it as a
 * `stage_viewpoints` row, portable with the show. The request is built by the caller from the live
 * pose (`viewpointFromCamera`), so what is saved is exactly what is on screen; saving moves this
 * window onto the new view, which is what announces it to the Screens sheet.
 *
 * A duplicate name is an ordinary step in naming a view, shown beside the field
 * (`createStageViewpoint` is in `SILENT_ENDPOINTS`).
 */
export function SaveViewpointSheet({
  open,
  onOpenChange,
  projectId,
  request,
  seatingName,
  onSaved,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** The view to save, without its name — built when the sheet opens. */
  request: Omit<CreateStageViewpointRequest, 'name'> | null
  seatingName: string | null
  onSaved: (row: StageViewpointDto) => void
}) {
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [create, { isLoading }] = useCreateStageViewpointMutation()

  useEffect(() => {
    if (!open) return
    setName('')
    setError(null)
  }, [open])

  const trimmed = name.trim()
  const save = async () => {
    if (request == null || trimmed === '') return
    try {
      const row = await create({ projectId, ...request, name: trimmed }).unwrap()
      onSaved(row)
      onOpenChange(false)
      toast.success(`Saved “${row.name}”`)
    } catch (err) {
      setError(formatError(err))
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange} unsavedChanges={trimmed !== ''}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Save this view</SheetTitle>
          <SheetDescription>
            {request == null ? '' : describe({ ...request, name: trimmed }, seatingName)}
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          <form
            id="save-viewpoint"
            onSubmit={(e) => {
              e.preventDefault()
              void save()
            }}
            className="space-y-2"
          >
            <Label htmlFor="viewpoint-name">Name</Label>
            <Input
              id="viewpoint-name"
              value={name}
              maxLength={100}
              autoFocus
              placeholder="Row F centre"
              onChange={(e) => {
                setName(e.target.value)
                setError(null)
              }}
            />
          </form>
          {error != null && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <SheetClose asChild>
            <Button variant="outline">Cancel</Button>
          </SheetClose>
          <Button type="submit" form="save-viewpoint" disabled={request == null || trimmed === '' || isLoading}>
            Save
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
