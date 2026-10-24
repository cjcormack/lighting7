import { useEffect, useMemo, useState } from 'react'
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
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2 } from 'lucide-react'
import { AlsoAddToBuskRow } from '@/components/busking/AlsoAddToBuskRow'
import { useAddToBuskPage } from '@/components/busking/useAddToBuskPage'
import type { BuskAddTarget } from '@/lib/buskAdd'
import { effectSpeedLabel } from '@/components/fx/fxConstants'
import { isOnSeconds } from '@/lib/effectTiming'
import { SpeedMasterChip } from '@/components/fx/SpeedMasterChip'
import { findEffectEntry } from '@/components/busking/buskingTypes'
import { useCreateTemplateMutation, useTemplateListQuery } from '@/store/templates'
import { stepEffectPaletteShape } from '@/lib/stepEffects'
import { useEffectLibraryQuery } from '@/store/fixtureFx'
import { FAMILY_LABELS, familyForEffectCategory } from '@/lib/attributeFamily'
import { formatError } from '@/lib/formatError'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { TemplateEffect } from '@/api/templatesApi'

/**
 * *Save as template…* — the effect twin of the strip's *New from selection*.
 *
 * Both take something the operator has already made on the rig and give it a name in the library,
 * and both are why the library fills up without anyone opening the New template sheet. The
 * difference is only what is captured: a selection's *values* there, a running effect's *spec* here
 * — which the client already holds in full, so this posts to the ordinary create route with an
 * `effect` body rather than needing a from-programmer route of its own.
 *
 * **The running effect is left exactly where it is.** This is a copy into the library, not a move:
 * the instance stays programmer-owned and Record still writes it onto the cue as an ad-hoc effect.
 * Nothing links the two afterwards — retuning the template will not move the copy, and stopping the
 * copy will not touch the template.
 *
 * Deliberately a small sheet rather than the editor, on `NewTemplateFromSelectionSheet`'s reasoning:
 * every field the editor asks for is already decided — the family comes from the effect's category,
 * the effect and all its tuning come from the instance — so all that is left is a name.
 */
export function NewTemplateFromEffectSheet({
  projectId: projectIdNum,
  open,
  onOpenChange,
  effect,
}: {
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The running effect being captured. Null closes the sheet. */
  effect: ActiveEffect | null
}) {
  const [create, { isLoading, error, reset }] = useCreateTemplateMutation()
  const { data: library } = useEffectLibraryQuery()

  const [name, setName] = useState('')
  // Held, not written: the pad names a template id that does not exist until `create` answers.
  const [buskTarget, setBuskTarget] = useState<BuskAddTarget | null>(null)
  const { placeAfterCreate } = useAddToBuskPage(projectIdNum, null)

  // Normalised, not `===`: a running instance spells its `effectType` as it was minted, which need
  // not match the library entry's `name` character for character — the divergence `findEffectEntry`
  // matches through. An exact lookup misses an effect the library really has, and every
  // gate below then reads that miss as "still loading" and disables Create for good.
  const entry = useMemo(() => findEffectEntry(library, effect?.effectType), [library, effect])
  const { data: templates } = useTemplateListQuery({ projectId: projectIdNum }, { skip: !Number.isFinite(projectIdNum) })
  // A step effect is banked by its palettes' family, or as Beam on its setting (effects plan §3.6):
  // its library category says neither, and Slot Steps names its setting where any other effect
  // names no property.
  const step = useMemo(() => (effect == null ? null : stepEffectPaletteShape(effect, templates)), [effect, templates])
  const stepShape = step != null && step.reason == null ? step : null
  const category = step != null ? (stepShape?.category ?? null) : (entry?.category ?? null)
  const family = stepShape != null ? stepShape.family : category == null ? null : familyForEffectCategory(category)
  // The **instance's** own answer, not the library entry's. They agree, but a running effect
  // reports its timing source directly and that is the nearer authority for describing this one.
  const isWallClock = effect?.timingSource === 'WALL_CLOCK'
  const speed = effect == null ? null : effectSpeedLabel(effect.beatDivision, effect.timingSource, effect.seconds)

  useEffect(() => {
    if (!open) return
    setName('')
    reset()
    setBuskTarget(null)
  }, [open, reset])


  const canSubmit =
    name.trim() !== '' &&
    effect != null &&
    category != null &&
    family != null &&
    Number.isFinite(projectIdNum) &&
    !isLoading

  const submit = async () => {
    if (effect == null || category == null) return
    const body: TemplateEffect = {
      effectType: effect.effectType,
      category,
      // Null deliberately: a template effect names no target (D3), so it names no property on one —
      // but for Slot Steps, which names the setting it steps (the write boundary refuses one without).
      propertyName: stepShape?.propertyName ?? null,
      elementMode: null,
      beatDivision: effect.beatDivision,
      blendMode: effect.blendMode,
      // The fixture DTO reports the spread as `distributionStrategy`, and absent means `NORMAL`, the
      // vocabulary the write boundary accepts.
      distribution: effect.distributionStrategy ?? 'NORMAL',
      phaseOffset: effect.phaseOffset,
      // Dropped with the two above, and for the same reason (D3): both are questions about a
      // *specific* multi-element head, and a template effect names no target. Carrying them would
      // also make a captured template differ from an authored one in two fields the editor can
      // neither show nor clear — `chooseEffect` hard-nulls both, and `effectKey` does not watch
      // them, so an element filter picked up here would be invisible and permanent.
      elementFilter: null,
      stepTiming: null,
      parameters: effect.parameters,
      speedMasterUuid: effect.speedMasterUuid,
      rateSpeedMasterUuid: effect.rateSpeedMasterUuid,
      // The running effect's timing (D7): a beat effect on Seconds keeps its cycle in seconds.
      seconds: isOnSeconds(effect, effect.timingSource),
      // Its size, direction and runs (effects plan D2, D5, D9), said only where not the default.
      ...(effect.size != null && effect.size !== 1 ? { size: effect.size } : {}),
      ...(effect.direction != null && effect.direction !== 'FORWARD' ? { direction: effect.direction } : {}),
      ...(effect.nShot != null ? { nShot: effect.nShot } : {}),
      // And the spread's shape (effects plan D4), likewise.
      ...(effect.spread != null && effect.spread !== 1 ? { spread: effect.spread } : {}),
      ...(effect.wings != null && effect.wings !== 1 ? { wings: effect.wings } : {}),
      ...(effect.blocks != null && effect.blocks !== 1 ? { blocks: effect.blocks } : {}),
      ...(effect.groups != null && effect.groups !== 0 ? { groups: effect.groups } : {}),
    }
    try {
      const created = await create({ projectId: projectIdNum, name: name.trim(), effect: body }).unwrap()
      await placeAfterCreate(
        buskTarget,
        { kind: 'TEMPLATE', id: created.id, name: created.name },
        `“${created.name}” saved to the palette library`,
      )
      onOpenChange(false)
    } catch {
      // Rendered inline below; `formatError` handles the shape.
    }
  }

  return (
    // `unsavedChanges` rather than `useUnsavedChanges`, and the difference is not stylistic: the
    // hook reads a context **`Sheet` itself provides**, so calling it in the component that renders
    // the `<Sheet>` looks up the tree *past* the provider and silently no-ops (`register?.()`). The
    // hook is for a body component mounted inside `SheetContent` — `FxLibrary`'s two sheets and the
    // primitive's own test are the working shape. Three sibling sheets still have the inert form.
    <Sheet open={open} onOpenChange={onOpenChange} unsavedChanges={name.trim() !== ''}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Save as palette</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {error != null && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}

          <p className="text-[11px] text-muted-foreground">
            Keeps this FX&rsquo;s settings under a name, so a busk pad or a layer can run it on
            whatever is selected. The FX running now is left exactly where it is — this is a
            copy into the library, so retuning the palette later will not move it.
          </p>

          {effect != null && (
            <div className="space-y-1.5">
              <Label>FX</Label>
              <div className="flex flex-wrap items-center gap-2 rounded-md border px-2.5 py-2 text-xs">
                <span className="font-medium">{effect.effectType}</span>
                {family != null && (
                  <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                    {FAMILY_LABELS[family].singular}
                  </Badge>
                )}
                <span className="flex-1" />
                <SpeedMasterChip
                  speedMasterUuid={isWallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid}
                  kind={isWallClock ? 'rate' : 'runsOn'}
                />
                {speed != null && (
                  <Badge variant="outline" className="font-mono text-[10px]">
                    {speed}
                  </Badge>
                )}
              </div>
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="new-effect-template-name">Name</Label>
            <Input
              id="new-effect-template-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Amber Wave"
              autoFocus
            />
          </div>

          {/* The library is still arriving, or the effect's category has no family. The gate on the
              menu item means the second case should be unreachable — said here anyway, because a
              disabled Create with no reason is the thing that teaches nobody. */}
          {effect != null && library == null && (
            <p className="text-[11px] text-muted-foreground">Reading the FX library…</p>
          )}
          {effect != null && library != null && entry == null && (
            <p className="text-[11px] text-destructive">
              &ldquo;{effect.effectType}&rdquo; is not in this desk&rsquo;s FX library, so its
              category — and so the palette&rsquo;s family — cannot be read.
            </p>
          )}
          {Number.isFinite(projectIdNum) && (
            <AlsoAddToBuskRow projectId={projectIdNum} target={buskTarget} onChange={setBuskTarget} />
          )}

          {step?.reason != null && <p className="text-[11px] text-muted-foreground">{step.reason}</p>}
          {entry != null && step == null && family == null && (
            <p className="text-[11px] text-destructive">
              A “{category}” FX cannot be a palette — an FX palette is banked by family,
              and that category has none. It can live in a recorded look instead.
            </p>
          )}
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
