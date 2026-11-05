import { useEffect, useMemo, useRef, useState } from 'react'
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
import { Loader2 } from 'lucide-react'
import { AlsoAddToBuskRow } from '@/components/busking/AlsoAddToBuskRow'
import { useAddToBuskPage } from '@/components/busking/useAddToBuskPage'
import type { BuskAddTarget } from '@/lib/buskAdd'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { useCreatePaletteFromProgrammerMutation } from '@/store/palettes'
import { formatError } from '@/lib/formatError'
import type { PaletteTarget } from '@/api/palettesApi'

/**
 * Record the selection as a new palette — Record ▾'s *A new palette* (programmer-toolbar plan D2).
 *
 * Deliberately a small sheet, not the editor. Everything the editor asks is either already decided
 * (the family comes from the selection, the values come from the rig) or does not apply yet, so all
 * that is left is a name. Reusing `PaletteEditor` here would mean showing a family-native control
 * that the recorded values are about to overwrite.
 *
 * **One family, chosen not asked** where the selection says so. A marquee across the Colour column
 * means colour; with several families in the selection the operator picks, because a template holds
 * exactly one and guessing would silently drop the rest.
 */
export function NewPaletteFromSelectionSheet({
  open,
  onOpenChange,
  projectId,
  families,
  targets,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** The families the selection is asking about, or null when the gesture named none. */
  families: readonly AttributeFamily[] | null
  /**
   * The heads to record from — a press's own targets (the marquee store's), so a marquee over three
   * colour cells records those three and not every selected row. A press and *A new palette* must
   * agree on this or they would name different heads.
   */
  targets: readonly PaletteTarget[]
}) {
  const [record, { isLoading, error, reset }] = useCreatePaletteFromProgrammerMutation()

  const [name, setName] = useState('')
  const [family, setFamily] = useState<AttributeFamily | null>(null)
  // *Use it on these heads* (palette-references plan D15, call C): on by default, every open.
  const [asReference, setAsReference] = useState(true)

  const offered = useMemo(
    () => (families != null && families.length > 0 ? families : [...ATTRIBUTE_FAMILIES]),
    [families],
  )

  // Reset as the sheet opens, and only then: a host that hands over a fresh `families` array while it
  // is open must not undo what the operator chose here (an unticked *Use it on these heads* above all).
  const offeredRef = useRef(offered)
  useEffect(() => {
    offeredRef.current = offered
  }, [offered])
  useEffect(() => {
    if (!open) return
    setName('')
    setAsReference(true)
    setBuskTarget(null)
    // Pre-chosen when the selection names exactly one — the common case.
    setFamily(offeredRef.current.length === 1 ? offeredRef.current[0] : null)
    reset()
  }, [open, reset])

  // Held, not written: the pad names a template id that does not exist until `record` answers.
  const [buskTarget, setBuskTarget] = useState<BuskAddTarget | null>(null)
  const { placeAfterCreate } = useAddToBuskPage(projectId, null)

  const canSubmit = name.trim() !== '' && family != null && targets.length > 0 && !isLoading

  const submit = async () => {
    if (family == null) return
    try {
      const result = await record({
        projectId,
        name: name.trim(),
        mask: [family],
        targets: [...targets],
        asReference,
      }).unwrap()
      // The shape it came out as is worth saying: an operator who meant "one amber" and got a
      // per-fixture template has selected heads that disagree, and will want to know now rather
      // than when they apply it somewhere else.
      const shape = result.isGeneric
        ? 'one value for any head'
        : `per fixture, ${result.palette.rows?.length ?? 0} heads`
      const referenced = result.referenced ?? 0
      const linked =
        referenced > 0
          ? ` · ${referenced} value${referenced === 1 ? ' is a reference' : 's are references'} to it now; nothing moved on stage`
          : ''
      await placeAfterCreate(
        buskTarget,
        { kind: 'PALETTE', id: result.palette.id, name: result.palette.name },
        `“${result.palette.name}” — ${shape}${linked}`,
      )
      onOpenChange(false)
    } catch {
      // Rendered inline below; `formatError` handles the shape.
    }
  }

  return (
    // `unsavedChanges` on the Sheet, **not** `useUnsavedChanges` here: that hook reads a context
    // `Sheet` itself provides, so calling it in the component that renders the `<Sheet>` looks up
    // the tree past the provider, finds nothing and silently no-ops (`register?.()`). The hook is
    // for a body component mounted *inside* `SheetContent`.
    <Sheet open={open} onOpenChange={onOpenChange} unsavedChanges={name.trim() !== ''}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>New palette from selection</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {error != null && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}

          <p className="text-[11px] text-muted-foreground">
            Keeps what {targets.length} selected fixture{targets.length === 1 ? '' : 's'}{' '}
            {targets.length === 1 ? 'is' : 'are'} holding now. If they all agree it becomes one value
            for any head; if they differ it is kept per fixture, which is what a focus position is.
          </p>

          <div className="space-y-1.5">
            <Label htmlFor="new-template-name">Name</Label>
            <Input
              id="new-template-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Amber Key"
              autoFocus
            />
          </div>

          {offered.length > 1 && (
            <div className="space-y-1.5">
              <Label>Attribute family — one only</Label>
              <div className="flex flex-wrap gap-1.5">
                {offered.map((f) => (
                  <Button
                    key={f}
                    type="button"
                    size="sm"
                    variant={family === f ? 'default' : 'outline'}
                    onClick={() => setFamily(f)}
                  >
                    {FAMILY_LABELS[f].singular}
                  </Button>
                ))}
              </div>
              <p className="text-[11px] text-muted-foreground">
                Your selection covers more than one, and a palette holds exactly one.
              </p>
            </div>
          )}

          <label
            className="flex items-start gap-2.5 rounded-lg border border-teal-400/45 bg-teal-400/[0.06] p-3"
          >
            <input
              type="checkbox"
              checked={asReference}
              onChange={(e) => setAsReference(e.target.checked)}
              className="mt-0.5 size-4 shrink-0 accent-teal-400"
            />
            <span className="space-y-1">
              <span className="block text-sm font-medium">Use it on these heads</span>
              <span className="block text-[11px] leading-snug text-muted-foreground">
                {referenceSentence(targets, family, name.trim())}
              </span>
            </span>
          </label>

          {targets.length === 0 && (
            <p className="text-[11px] text-destructive">
              Nothing selected — there is nothing to record.
            </p>
          )}

          <AlsoAddToBuskRow projectId={projectId} target={buskTarget} onChange={setBuskTarget} />
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <SheetClose asChild>
            <Button variant="outline">Cancel</Button>
          </SheetClose>
          <Button onClick={submit} disabled={!canSubmit}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            Create palette
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/** *The 4 selected heads’ position values become references to DSR Focus. …* — a group's members, *the selection’s*. */
function referenceSentence(targets: readonly PaletteTarget[], family: AttributeFamily | null, name: string): string {
  const whose = targets.some((t) => t.type === 'group')
    ? 'The selection’s'
    : targets.length === 1
      ? 'The selected head’s'
      : `The ${targets.length} selected heads’`
  const values = family != null ? `${FAMILY_LABELS[family].singular.toLowerCase()} values` : 'values'
  return `${whose} ${values} become references to ${name !== '' ? name : 'it'}. Nothing moves on stage, and whatever you record from them keeps the reference.`
}
