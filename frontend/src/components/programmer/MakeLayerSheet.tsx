import { useEffect, useRef, useState } from 'react'
import { useSelector } from 'react-redux'
import { Loader2 } from 'lucide-react'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatError } from '@/lib/formatError'
import { serializePropertyMask } from '@/lib/attributeFamily'
import { useRecordLookMutation } from '@/store/programmerOps'
import { programmerAddLayer, programmerClearEntry } from '@/store/programmer'
import { selectTargetKeys } from '@/store/selectionSlice'
import { MaskPicker } from './maskPicker'
import { useLocalFamilyCounts } from './useLocalFamilyCounts'
import type { PropertyMaskGroup } from '@/store/programmerOps'

/**
 * Promote what you busked into a shared Look, applied here as a layer, without leaving.
 *
 * The gesture the whole session exists for: composing a scene used to mean designing a Look in the
 * library, applying it to a cue somewhere else, then bouncing between the two to tune it. This is
 * three existing operations in the order the operator means them —
 *
 * 1. `POST /programmer/record-look` (CREATE), masked to the families picked and scoped to the
 *    selection, which returns the Look it wrote;
 * 2. `programmer.addLayer`, so it is immediately in the stack and on the rig;
 * 3. `programmer.clearEntry` per row it took, so **what you promoted leaves Local and the rest
 *    stays yours** — the level you busked is still a local value if you masked to Colour.
 *
 * A sequence, not one call, which has a consequence worth stating rather than hiding: a failure
 * after step 1 leaves the Look in the library. That is reported, because the alternative is an
 * operator who thinks nothing happened and records a second copy under a slightly different name.
 * Rolling it back would mean deleting a Look on the strength of a failed WS send, which is worse.
 *
 * The keys to release come from the response's own `look.rows` rather than being guessed from the
 * selection: group collapsing happens server-side, so the rows are the only account of what was
 * actually taken.
 */
export function MakeLayerSheet({
  open,
  onOpenChange,
  projectId,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
}) {
  const [record, { isLoading, error, reset }] = useRecordLookMutation()
  const counts = useLocalFamilyCounts()
  const selectedKeys = useSelector((s: Parameters<typeof selectTargetKeys>[0]) =>
    selectTargetKeys(s, 'programmer'),
  )

  const [name, setName] = useState('')
  const [mask, setMask] = useState<PropertyMaskGroup[]>([])
  const [partial, setPartial] = useState<string | null>(null)

  const openedRef = useRef(false)
  useEffect(() => {
    if (!open) {
      openedRef.current = false
      return
    }
    if (openedRef.current) return
    openedRef.current = true
    setName('')
    setMask([])
    setPartial(null)
    reset()
  }, [open, reset])

  // The selection is what makes a Look reusable — "Warm Amber" over four named heads rather than
  // over whatever the programmer happened to be holding. Without one there is nothing to promote.
  const targets = selectedKeys.map((key) => ({ type: 'fixture' as const, key }))
  const canSubmit = name.trim() !== '' && targets.length > 0 && !isLoading

  const submit = async () => {
    setPartial(null)
    try {
      const response = await record({
        projectId,
        mode: 'CREATE',
        source: 'TOUCHED',
        mask: mask.length > 0 ? mask : undefined,
        name: name.trim(),
        targets,
      }).unwrap()

      try {
        programmerAddLayer({
          lookId: response.look.id,
          targets,
          // The layer asserts exactly what was promoted, so the families left behind in Local
          // still win — they are above every layer.
          propertyMask: serializePropertyMask(mask) ?? undefined,
        })
        for (const row of response.look.rows) {
          if (row.targetType === 'deferred') continue
          programmerClearEntry(row.targetType, row.targetKey, row.propertyName)
        }
      } catch (e) {
        setPartial(
          `“${response.look.name}” was saved to the library, but applying it here failed: ${formatError(e)}. Add it from + Look.`,
        )
        return
      }
      onOpenChange(false)
    } catch {
      // Rendered inline from the mutation's own `error` — `recordLook` is in SILENT_ENDPOINTS.
    }
  }

  return (
    // `unsavedChanges` on the Sheet, **not** `useUnsavedChanges` here: that hook reads a context
    // `Sheet` itself provides, so calling it in the component that renders the `<Sheet>` looks up
    // the tree past the provider, finds nothing and silently no-ops (`register?.()`). The hook is
    // for a body component mounted *inside* `SheetContent`.
    <Sheet
      open={open}
      onOpenChange={(next) => !isLoading && onOpenChange(next)}
      unsavedChanges={name.trim() !== '' || mask.length > 0}
    >
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Make a layer from your values</SheetTitle>
        </SheetHeader>
        <SheetBody>
          <div className="space-y-1.5">
            <Label htmlFor="make-layer-name">Name</Label>
            <Input
              id="make-layer-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Warm Key"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label>Keep</Label>
            <MaskPicker value={mask} onChange={setMask} counts={counts} />
            <p className="text-xs text-muted-foreground">
              Masked to what you pick, so anything you leave out stays a local value — and still
              beats every layer.
            </p>
          </div>

          <p className="text-xs text-muted-foreground">
            {targets.length === 0
              ? 'Select the heads this look is about first. A look over named heads is what makes it reusable across cues.'
              : `Over ${targets.length} selected head${targets.length === 1 ? '' : 's'}. Saved to the library and applied here as the top layer.`}
          </p>

          {partial && (
            <Alert variant="destructive">
              <AlertDescription>{partial}</AlertDescription>
            </Alert>
          )}
          {error && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <SheetClose asChild>
            <Button variant="outline" disabled={isLoading}>
              Cancel
            </Button>
          </SheetClose>
          <Button onClick={submit} disabled={!canSubmit}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            Make layer
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
