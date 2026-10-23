import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
import { AddToBuskPageMenu } from '@/components/busking/AddToBuskPageMenu'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { AudioWaveform, Loader2, SlidersHorizontal, Trash2 } from 'lucide-react'
import {
  ATTRIBUTE_FAMILIES,
  FAMILY_LABELS,
  familyForEffectCategory,
  type AttributeFamily,
} from '@/lib/attributeFamily'
import { describeTemplateIntent, type TemplateIntent } from '@/lib/templateIntent'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  findPanProperty,
  findTiltProperty,
  useVisibleFixtureListQuery,
} from '@/store/fixtures'
import { useTemplateListQuery } from '@/store/templates'
import {
  drivenCategoryOf,
  categoryForFamily,
  isPaletteSteps,
  isSlotSteps,
  paletteStepsOffers,
  rigSettingUnion,
  seedSlots,
  serializeStepList,
} from '@/lib/stepEffects'
import {
  type TemplateEffect,
  type TemplateInput,
  type TemplateRow,
  type TemplateSummary,
} from '@/api/templatesApi'
import { formatError } from '@/lib/formatError'
import { FxEffectFields } from '@/components/fx/FxEffectFields'
import { startingSpec, templateDraftOf, withTemplateDraft, type FxDraft, type FxScope } from '@/components/fx/fxEditorModel'
import { swappedTiming } from '@/lib/effectTiming'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { findEffectEntry } from '@/components/busking/buskingTypes'
import { useEffectLibraryQuery, type EffectLibraryEntry } from '@/store/fixtureFx'
import { useSpeedMasterForCategory } from '@/store/speedMasters'
import { TemplateResolvesTo } from './TemplateResolvesTo'
import { TemplateRunsOn } from './TemplateRunsOn'
import { FamilyControls } from './familyControls/FamilyControls'
import {
  seedValues,
  templateRowsFromValues,
  templateRowsKey,
  withIntent,
  type TemplateValues,
} from './familyControls/templateRows'

/**
 * Authoring a template: **family first, then Holds, then one control native to both.**
 *
 * The whole difference from the editor this replaces. `LookEditor` asked for an `editorFixtureType`
 * before anything else, built a synthetic fixture from it, and rendered a descriptor-driven property
 * grid — which is what made "Amber Key" a MAC Aura's colour rather than a colour. There is no fixture
 * type here and no grid: the family is the template's identity, the control is native to it (a colour
 * picker, one level, a centre-relative pan/tilt), and what gets stored is an **intent** resolved per
 * head at cook.
 *
 * **Holds** is the second identity choice, and it sits beside the family rather than under it: a
 * template holds a *value* or an *effect*, never both, and like the family it is fixed at creation
 * (fx-templates D1). Choosing Effect swaps the family-native control for `FxEffectFields` —
 * the live effect editor's fields, reused rather than redrawn, minus every row that needs a fixture —
 * and swaps the resolves-to panel for `TemplateRunsOn`.
 *
 * Three things this editor deliberately does not do:
 *
 *  - **It does not create a per-fixture template.** Eight heads aimed at one spot is a *recording* —
 *    the values come from where the heads actually are — so it comes from the programmer's strip.
 *    Opening one here edits its name, notes and fade, and lists its values read-only, which is the
 *    same stance `LookDetailSheet` takes and for the same reason: there is no authoring gesture that
 *    would improve on re-recording from the rig.
 *  - **It does not offer a slotted role.** Gobo and the wheel/macro channels are shown disabled with
 *    the reason, rather than omitted — an operator looking for gobo needs to learn *where* it lives.
 *  - **It does not offer a second effect, or a target for the one it has.** Both are Look-shaped
 *    (D2, D3): several effects together, or an effect that differs per head, is what a Look with
 *    deferred effects already is.
 */
export function TemplateEditor({
  open,
  onOpenChange,
  projectId,
  template,
  onSave,
  isSaving,
  onDelete,
  isDeleting,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** Null for a create draft. */
  template: TemplateSummary | null
  onSave: (input: TemplateInput) => Promise<void>
  isSaving: boolean
  onDelete?: () => void
  isDeleting?: boolean
}) {
  const [name, setName] = useState('')
  const [notes, setNotes] = useState('')
  const [fadeSeconds, setFadeSeconds] = useState('')
  const [family, setFamily] = useState<AttributeFamily>('COLOUR')
  /**
   * The draft value per property, keyed by property name.
   *
   * A map rather than one value, because a family can hold more than one property: intensity is a
   * level *and* a strobe, and beam is four continuous roles plus prism. Only the entries present are
   * written, so an untouched property is absent from the template rather than stored at zero.
   */
  const [values, setValues] = useState<TemplateValues>({})
  /**
   * Which of the two this draft holds. Fixed after creation, like the family — an existing
   * template's segment is disabled and this only ever reflects what came back from the server.
   */
  const [holds, setHolds] = useState<'value' | 'effect'>('value')
  /** The effect draft, or null before one has been chosen. Only read when `holds === 'effect'`. */
  const [effectDraft, setEffectDraft] = useState<TemplateEffect | null>(null)
  const [saveError, setSaveError] = useState<unknown>(null)

  const { data: library } = useEffectLibraryQuery()
  const masterForCategory = useSpeedMasterForCategory()

  /**
   * Which template this session has been seeded from — `null` for a create draft, `undefined` while
   * closed.
   *
   * Keyed by id rather than by the object: the query hands back a fresh object whenever the
   * `Template` tag is invalidated, and re-seeding on that would silently replace whatever the
   * operator was halfway through typing while `isDirty` read false. Same trap `LookEditor` documented.
   */
  const seededRef = useRef<number | null | undefined>(undefined)

  useEffect(() => {
    if (!open) {
      seededRef.current = undefined
      return
    }
    const key = template?.id ?? null
    if (seededRef.current === key) return
    seededRef.current = key
    setSaveError(null)
    setName(template?.name ?? '')
    setNotes(template?.notes ?? '')
    setFadeSeconds(template?.fadeDurationMs != null ? String(template.fadeDurationMs / 1000) : '')
    setFamily(template?.family ?? 'COLOUR')
    setValues(seedValues(template))
    setHolds(template?.kind ?? 'value')
    setEffectDraft(template?.effect ?? null)
  }, [open, template])

  const isGeneric = template == null || template.isGeneric
  const holdsEffect = holds === 'effect'

  /**
   * The library entry behind the effect draft — the only thing that knows its `timingSource`.
   *
   * Matched through `findEffectEntry` rather than by `===`: a template captured from a running
   * effect carries that instance's spelling of `effectType`, which need not be the library entry's
   * `name` character for character. An exact miss would open a saved template with no parameter
   * form and the picker sitting on its placeholder.
   */
  const effectEntry = useMemo(
    () => (effectDraft == null ? undefined : findEffectEntry(library, effectDraft.effectType)),
    [library, effectDraft],
  )

  /** The rows this draft would save — also what the resolves-to panel asks about. */
  const rows = useMemo<TemplateRow[]>(() => {
    if (holdsEffect) return []
    if (!isGeneric) return template?.rows ?? []
    // The lifted rows builder, which applies the rgbonly rule through the same derivation the
    // policy buttons read — so what is shown and what is saved cannot disagree. The sheet's Value
    // cell builds its rows through the same function.
    return templateRowsFromValues(family, values)
  }, [holdsEffect, isGeneric, family, values, template])

  const fadeMs = useMemo(() => {
    const seconds = Number(fadeSeconds)
    if (fadeSeconds.trim() === '' || !Number.isFinite(seconds) || seconds < 0) return null
    return Math.round(seconds * 1000)
  }, [fadeSeconds])

  const isDirty = useMemo(() => {
    if (template == null) {
      return name.trim().length > 0 || rows.length > 0 || effectDraft != null
    }
    if (name !== template.name) return true
    if ((notes || null) !== (template.notes ?? null)) return true
    // Fade is not editable on an effect template — it has no arrival to time — so a hidden field
    // holding a stale value must not read as a change the operator made.
    if (!holdsEffect && fadeMs !== template.fadeDurationMs) return true
    if (holdsEffect) return effectKey(effectDraft) !== effectKey(template.effect)
    return templateRowsKey(rows) !== templateRowsKey(template.rows ?? [])
  }, [template, name, notes, fadeMs, rows, holdsEffect, effectDraft])

  /** Name, plus **exactly one** of the two halves — which is the write boundary's first rule. */
  const isValid = name.trim().length > 0 && (holdsEffect ? effectDraft != null : rows.length > 0)

  const setIntent = useCallback((propertyName: string, intent: TemplateIntent | null) => {
    setValues((prev) => withIntent(prev, propertyName, intent))
  }, [])

  /**
   * Choose (or swap) the effect.
   *
   * Parameters reset to the new effect's declared defaults rather than carrying over: they are named
   * per effect, so keeping a `colourA` from the last one would be luck rather than intent. The
   * timing fields *do* carry over — a division and a distribution mean the same thing whichever
   * effect is running, as do size, direction and runs — but for a division or Direction still at the
   * old effect's own default, which takes the new one's ([swappedTiming]); and the speed master is
   * re-stamped, because it is the family's default (D8) and the family has not moved.
   */
  const chooseEffect = useCallback(
    (entry: EffectLibraryEntry, extra?: EffectStart) => {
      setEffectDraft((prev) => {
        // The timing fields carry over only **within one timing source**: `beatDivision` is beats
        // on the grid and seconds on the clock, so a 10-second cycle carried into a beat effect
        // would silently become ten beats — the same units trap `effectSpeedLabel` exists for.
        const previousEntry = prev == null ? undefined : findEffectEntry(library, prev.effectType)
        const sameTiming = (previousEntry?.timingSource ?? 'BEAT') === (entry.timingSource ?? 'BEAT')
        // A new effect is its type's own cycle and Direction (venue-visit plan D16, terminology plan
        // D24) — four seconds for a wall-clock one.
        const timing = swappedTiming(prev ?? {}, previousEntry, entry, sameTiming && prev != null)
        // The blend and the parameters are the effect's own **starting spec** — the picker's rule
        // (fixture-fx-sheets plan D16): a movement effect starts *Around* (`ADDITIVE`, its centre
        // pinned at 128), so a Circle pad pressed after a position pad orbits it; anything else
        // starts on Override. The blend is not carried from the last effect — it was the hard-coded
        // Override that made every Circle template circle 128/128.
        const spec = startingSpec(entry)
        // Seconds (D7) carries within one timing source like the division it qualifies.
        const seconds = sameTiming && prev?.seconds === true
        // A step effect's start is the controls' (`EffectStart`): Palette Steps is filed under this
        // family with its palettes, Slot Steps names a setting and its first slots.
        const category = extra?.category ?? drivenCategoryOf(entry)
        return {
          effectType: entry.name,
          category,
          propertyName: extra?.propertyName ?? null,
          beatDivision: timing.beatDivision,
          blendMode: spec.blendMode,
          distribution: prev?.distribution ?? 'NORMAL',
          phaseOffset: prev?.phaseOffset ?? 0,
          elementMode: null,
          elementFilter: null,
          stepTiming: null,
          parameters: { ...spec.parameters, ...extra?.parameters },
          // Stamped at authoring time, not resolved later: `null` still means master 1 everywhere.
          // An effect on Seconds follows none.
          speedMasterUuid: seconds ? null : masterForCategory(category),
          rateSpeedMasterUuid: seconds || !sameTiming ? null : (prev?.rateSpeedMasterUuid ?? null),
          seconds,
          // Size and runs carry over like the phase: each means the same thing whichever effect is
          // running (effects plan D2, D9), and the live editor's swap keeps them too.
          ...(prev?.size != null ? { size: prev.size } : {}),
          direction: timing.direction,
          ...(prev?.nShot != null ? { nShot: prev.nShot } : {}),
          // So does the spread's shape (D4): it shapes the heads, whichever effect runs over them.
          ...(prev?.spread != null ? { spread: prev.spread } : {}),
          ...(prev?.wings != null ? { wings: prev.wings } : {}),
          ...(prev?.blocks != null ? { blocks: prev.blocks } : {}),
          ...(prev?.groups != null ? { groups: prev.groups } : {}),
          // The draft's own reading of its kind, until the desk answers with the registry's: what
          // tells a wall-clock effect's Seconds (no rate master) from a beat effect on a master.
          timingSource: entry.timingSource ?? 'BEAT',
        }
      })
    },
    [masterForCategory, library],
  )

  const patchEffect = useCallback((patch: Partial<FxDraft>) => {
    setEffectDraft((prev) => (prev == null ? prev : withTemplateDraft(prev, { ...templateDraftOf(prev), ...patch })))
  }, [])

  /** The setting a Slot Steps palette steps — the one property an effect palette names. */
  const setEffectProperty = useCallback((propertyName: string, parameters?: Record<string, string>) => {
    setEffectDraft((prev) =>
      prev == null ? prev : { ...prev, propertyName, parameters: { ...prev.parameters, ...parameters } },
    )
  }, [])

  const handleSave = async () => {
    setSaveError(null)
    try {
      await onSave({
        name: name.trim(),
        notes: notes.trim() === '' ? null : notes.trim(),
        notesPresent: true,
        // An effect has no arrival, so the field is hidden and the value cleared rather than left
        // to survive as a fade nothing can see or edit.
        fadeDurationMs: holdsEffect ? null : fadeMs,
        fadeDurationMsPresent: true,
        // **At most one of the two**, never both: which half a template holds is fixed at creation
        // and the write boundary refuses a body naming the other. A per-fixture template's rows are
        // not editable here either, so they are omitted rather than echoed — sending them back
        // would make a metadata edit a full rewrite of the recorded values.
        ...(holdsEffect
          ? effectDraft != null
            ? { effect: effectDraft }
            : {}
          : isGeneric
            ? { rows }
            : {}),
      })
      onOpenChange(false)
    } catch (err) {
      setSaveError(err)
    }
  }

  return (
    // `unsavedChanges` on the Sheet, **not** `useUnsavedChanges` here: that hook reads a context
    // `Sheet` itself provides, so calling it in the component that renders the `<Sheet>` looks up
    // the tree past the provider, finds nothing and silently no-ops (`register?.()`). The hook is
    // for a body component mounted *inside* `SheetContent`.
    <Sheet open={open} onOpenChange={onOpenChange} unsavedChanges={isDirty}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>{template == null ? 'New palette' : template.name}</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {saveError != null && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(saveError)}</AlertDescription>
            </Alert>
          )}

          {/* Family first, and disabled once a template exists: changing it would mean throwing
              every row away, which is a different template rather than an edit to this one. */}
          <div className="space-y-1.5">
            <Label>Attribute family — one only</Label>
            <div className="flex flex-wrap gap-1.5">
              {ATTRIBUTE_FAMILIES.map((f) => (
                <Button
                  key={f}
                  type="button"
                  size="sm"
                  variant={family === f ? 'default' : 'outline'}
                  disabled={template != null}
                  onClick={() => {
                    setFamily(f)
                    // Values are per-property and a property belongs to one family, so switching
                    // family cannot carry them across. Cleared explicitly rather than left to be
                    // filtered out later, so what the panel resolves is what the save will write.
                    setValues({})
                    // The effect goes with them, for the same reason: it is chosen from the old
                    // family's category, and carrying it across would leave the draft claiming a
                    // family its effect derives a different one from — which the server refuses.
                    setEffectDraft(null)
                  }}
                >
                  {FAMILY_LABELS[f].singular}
                </Button>
              ))}
            </div>
            {template != null && (
              <p className="text-[11px] text-muted-foreground">
                A palette&rsquo;s family is its identity — create a new one to hold a different
                attribute.
              </p>
            )}
          </div>

          {/* The second identity choice, and locked for the same reason the family is: the cook's
              template arm reads one half or the other, so flipping it is a different template. */}
          <div className="space-y-1.5 rounded-md border p-2.5">
            <Label>Holds — one only</Label>
            <div className="flex flex-wrap gap-1.5">
              <Button
                type="button"
                size="sm"
                variant={holds === 'value' ? 'default' : 'outline'}
                disabled={template != null}
                onClick={() => setHolds('value')}
              >
                <SlidersHorizontal className="size-3.5" />
                Value
              </Button>
              <Button
                type="button"
                size="sm"
                variant={holdsEffect ? 'default' : 'outline'}
                disabled={template != null}
                onClick={() => setHolds('effect')}
              >
                <AudioWaveform className="size-3.5" />
                FX
              </Button>
            </div>
            <p className="text-[11px] text-muted-foreground">
              A value is resolved per head; an FX runs on every head the layer names. For a value
              and an FX together, record a Look.
            </p>
            {template != null && (
              <p className="text-[11px] text-muted-foreground">
                Like the family, this is the palette&rsquo;s identity and cannot change afterwards.
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="template-name">Name</Label>
            <Input
              id="template-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Amber Key"
            />
          </div>

          {/* Fade is hidden for an effect — an effect has no arrival to time, so a fade field would
              be a control that does nothing. Notes takes the whole row in its place. */}
          <div className={holdsEffect ? 'space-y-1.5' : 'grid grid-cols-2 gap-3'}>
            {!holdsEffect && (
              <div className="space-y-1.5">
                <Label htmlFor="template-fade">Fade (seconds)</Label>
                <Input
                  id="template-fade"
                  value={fadeSeconds}
                  onChange={(e) => setFadeSeconds(e.target.value)}
                  placeholder="default"
                  inputMode="decimal"
                />
              </div>
            )}
            <div className="space-y-1.5">
              <Label htmlFor="template-notes">Notes</Label>
              <Input
                id="template-notes"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="optional"
              />
            </div>
          </div>

          {holdsEffect ? (
            <EffectControls
              projectId={projectId}
              family={family}
              library={library}
              draft={effectDraft}
              entry={effectEntry}
              onChoose={chooseEffect}
              onPatch={patchEffect}
              onProperty={setEffectProperty}
            />
          ) : isGeneric ? (
            <FamilyControls family={family} values={values} onChange={setIntent} />
          ) : (
            <PerFixtureValues template={template} />
          )}

          {/* Live against the real patch, so "usable on any fixture with colour" is something you
              can read *before* saving — including where it degrades. An effect has nothing per-head
              to resolve, so it gets the head count and the tempo instead. */}
          {holdsEffect ? (
            effectDraft != null && (
              <TemplateRunsOn family={family} effect={effectDraft} entry={effectEntry} />
            )
          ) : (
            <TemplateResolvesTo projectId={projectId} rows={rows} />
          )}
        </SheetBody>
        <SheetFooter className={onDelete ? 'flex-row justify-between' : 'flex-row justify-end gap-2'}>
          {onDelete && (
            <Button variant="destructive" onClick={onDelete} disabled={isDeleting === true}>
              {isDeleting === true ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
              Delete
            </Button>
          )}
          <div className="flex gap-2">
            {/* Only for a template that exists: `template == null` is a create draft, and a pad is a
                reference to an id there is not one of yet. The create sheets place a pad by holding
                the choice until their POST answers; this one has no POST of its own to wait for. */}
            <AddToBuskPageMenu
              projectId={projectId}
              record={template == null ? null : { kind: 'TEMPLATE', id: template.id, name: template.name }}
            />
            {/* Wrapped in SheetClose so Radix drives the close — which is what makes the unsaved
                changes guard fire. A plain onOpenChange(false) bypasses it. */}
            <SheetClose asChild>
              <Button variant="outline">Cancel</Button>
            </SheetClose>
            <Button onClick={handleSave} disabled={!isValid || isSaving}>
              {isSaving && <Loader2 className="size-4 animate-spin" />}
              {template == null ? 'Create palette' : 'Save'}
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/**
 * The effect branch: pick one effect from the family's category, then tune it.
 *
 * **The family is the filter**, so there is no category step to repeat — choosing Colour has already
 * said `colour`, and offering the picker's category pane again would ask the same question twice.
 * That is also why this is a plain `Select`: the add-effect wizard's full-pane type step (deleted
 * with that wizard, fixture-fx-sheets session 3) had a back button and was wrong for one field
 * inside a form.
 *
 * **The fields are the live editor's** (`FxEffectFields`, fixture-fx-sheets plan D16), over the
 * template's draft rather than a running instance: speed in beats a cycle with the master as a chip,
 * a movement effect's ***Centre: Around current position | Absolute*** and a level effect's
 * *Replace it | Within it*, the parameters in their roles, Shape and Advanced. It replaced
 * `EffectParameterForm`, which was this editor's draft form until session 5 and nothing else's.
 * What a template cannot answer is left out by its scope:
 *
 *  - no setting or slider *On* — a template effect names no property on no fixture;
 *  - no element mode, head filter or step timing — all three are questions about a *specific*
 *    multi-element head;
 *  - a size in DMX steps and a centre on the centre-relative scale, as the live editor has them —
 *    neither consults a head;
 *  - no extended colour channels, so the colour picker offers RGB only. That is correct rather than
 *    lazy, and it matches the backend: `resolveColourGeneric` resolves a template's colour without
 *    a head, so what is authored here is what any head will be asked for.
 *
 * `distribution` **is** offered (Advanced): how an effect's phase spreads across the heads it lands
 * on is a property of the effect, and the layer supplies the heads later. A wall-clock effect's rate
 * master offers *Unscaled*, which a draft can hold and a live frame cannot.
 */
function EffectControls({
  projectId,
  family,
  library,
  draft,
  entry,
  onChoose,
  onPatch,
  onProperty,
}: {
  projectId: number
  family: AttributeFamily
  library: EffectLibraryEntry[] | undefined
  draft: TemplateEffect | null
  entry: EffectLibraryEntry | undefined
  onChoose: (entry: EffectLibraryEntry, extra?: EffectStart) => void
  onPatch: (patch: Partial<FxDraft>) => void
  onProperty: (propertyName: string, parameters?: Record<string, string>) => void
}) {
  // Palette Steps (effects plan §4.4) is offered in this family where two generic value palettes of
  // it name one property; it starts on all of them, in library order.
  const { data: templates } = useTemplateListQuery({ projectId })
  const stepOffer = useMemo(
    () => paletteStepsOffers(templates ?? [], () => true).find((o) => o.family === family),
    [templates, family],
  )
  // Slot Steps names a setting, which a palette has no head to ask: the rig's settings, by name, each
  // offering every patched head's options of that name — two models' gobo wheels are one list.
  const { data: fixtures } = useVisibleFixtureListQuery()
  const rigSettings = useMemo(() => rigSettingUnion(fixtures ?? []), [fixtures])

  // Filtered through the same map the write boundary derives the family with, rather than by string
  // equality against one spelling: `familyForEffectCategory` is case-insensitive and takes `color`
  // as well as `colour`, so an exact match would hide from this picker a script-registered effect
  // that *Save as template…* would happily bank under this very family.
  const offered = useMemo(
    () =>
      (library ?? []).filter((e) =>
        isPaletteSteps(e.name) ? stepOffer != null : familyForEffectCategory(drivenCategoryOf(e)) === family,
      ),
    [library, family, stepOffer],
  )
  const fxDraft = useMemo(() => (draft == null ? null : templateDraftOf(draft)), [draft])
  const slotSetting = isSlotSteps(draft?.effectType) ? rigSettings.find((p) => p.name === draft?.propertyName) : undefined

  /** What a step effect starts on here: Palette Steps its family's palettes, Slot Steps a gobo wheel. */
  const startOf = (chosen: EffectLibraryEntry): EffectStart | undefined => {
    if (isPaletteSteps(chosen.name) && stepOffer != null) {
      return {
        category: categoryForFamily(family),
        parameters: { palettes: serializeStepList(stepOffer.palettes.map((p) => `pal:${p.uuid}`)) },
      }
    }
    if (isSlotSteps(chosen.name)) {
      const setting = rigSettings.find((p) => p.category === 'gobo') ?? rigSettings[0]
      if (setting != null) return { propertyName: setting.name, parameters: { slots: seedSlots(setting) } }
    }
    return undefined
  }

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label>FX</Label>
        <Select
          // The **entry's** name, not the draft's: the two can differ in spelling for a template
          // captured from a running effect, and every `SelectItem` is keyed by the library's own
          // name — so the draft's spelling would match no item and the trigger would fall back to
          // its placeholder on a template that has an effect perfectly well.
          value={entry?.name ?? draft?.effectType ?? ''}
          onValueChange={(next) => {
            const chosen = offered.find((e) => e.name === next)
            if (chosen) onChoose(chosen, startOf(chosen))
          }}
        >
          <SelectTrigger>
            <SelectValue placeholder="Choose an FX" />
          </SelectTrigger>
          <SelectContent>
            {offered.map((option) => (
              <SelectItem key={option.name} value={option.name}>
                {option.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-[11px] text-muted-foreground">
          {offered.length} {FAMILY_LABELS[family].singular.toLowerCase()} FX — the family is the filter.
        </p>
      </div>

      {draft != null && fxDraft != null && entry != null && (
        // The live editor's own column of fields, bordered so it reads as one block inside the
        // sheet's spacing. The description is said once, here, under the picker that chose it.
        <div className="flex flex-col gap-3 rounded-md border p-3" data-template-effect-fields>
          {entry.description && <EditorReadout>{entry.description}</EditorReadout>}
          {isSlotSteps(draft.effectType) && (
            <div className="flex flex-col gap-1">
              <Label>Setting</Label>
              <Select
                value={draft.propertyName ?? ''}
                onValueChange={(name) => {
                  // A different setting has different slots, so the list starts again on its own.
                  const setting = rigSettings.find((p) => p.name === name)
                  onProperty(name, { slots: seedSlots(setting) })
                }}
              >
                <SelectTrigger aria-label="Setting">
                  <SelectValue placeholder="Choose the setting it steps" />
                </SelectTrigger>
                <SelectContent>
                  {draft.propertyName != null && !rigSettings.some((p) => p.name === draft.propertyName) && (
                    <SelectItem value={draft.propertyName}>{draft.propertyName}</SelectItem>
                  )}
                  {rigSettings.map((p) => (
                    <SelectItem key={p.name} value={p.name}>
                      {p.displayName}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-[11px] text-muted-foreground">
                Each head steps this setting by the slots’ names — a head without one holds what is underneath.
              </p>
            </div>
          )}
          <FxEffectFields
            entry={entry}
            timingSource={draft.timingSource}
            draft={fxDraft}
            scope={TEMPLATE_SCOPE}
            extendedChannels={undefined}
            settingProperty={slotSetting}
            propertyName={draft.propertyName}
            paletteFamily={family}
            slotsFromRig
            readOnly={false}
            set={(patch) => onPatch(patch)}
            setParam={(name, value) => onPatch({ parameters: { ...fxDraft.parameters, [name]: value } })}
          />
          {/* The master's reason, which the old form said beside its picker (session 5 kept it). */}
          <EditorReadout>
            A new FX is one bar on the master whose usage matches this family, stored on the
            palette — every layer applying it follows the same tempo. On Seconds it follows none.
          </EditorReadout>
        </div>
      )}
    </div>
  )
}

/**
 * The Spread section (Order, Spread %, Wings, Blocks, Groups) over eight notional heads — no
 * `headCount`, since a palette has no target — and nothing else that needs a head.
 */
const TEMPLATE_SCOPE: FxScope = { heads: true, elementMode: false, elementFilter: false, stepTiming: false }

/**
 * Where a step effect starts on an effect palette (effects plan §3.6): Palette Steps under this
 * family's category, Slot Steps on a named setting, each with its list filled.
 */
interface EffectStart {
  category?: string
  propertyName?: string
  parameters?: Record<string, string>
}

/**
 * A per-fixture template's values, read-only.
 *
 * Read-only on the same reasoning `LookDetailSheet` is: these values came from where the heads
 * actually were, and the way to change them is to put the heads there again and re-record. A grid of
 * eight editable pan/tilt pairs would be a worse version of the rig.
 */
function PerFixtureValues({ template }: { template: TemplateSummary | null }) {
  const rows = template?.rows ?? []
  // A position recorded in degrees (before D3) reads on the centre scale through its own head.
  const { fixtureByKey } = useFixtureLookup()
  const axesOf = (key: string) => {
    const props = fixtureByKey.get(key)?.properties ?? []
    return { pan: findPanProperty(props), tilt: findTiltProperty(props) }
  }
  return (
    <div className="space-y-1.5">
      <Label>Recorded values</Label>
      <p className="text-[11px] text-muted-foreground">
        Recorded per head. Re-record from the programmer to change them &mdash; each head holds its
        own value, which is the point of a per-fixture palette.
      </p>
      <div className="rounded-md border divide-y">
        {rows.map((row) => (
          <div key={`${row.targetKey}:${row.propertyName}`} className="flex items-center gap-2 px-2 py-1.5">
            <span className="text-xs font-medium min-w-0 flex-1 truncate">{row.targetKey}</span>
            <Badge variant="outline" className="text-[10px] px-1.5 py-0 shrink-0">
              {row.propertyName}
            </Badge>
            <span className="font-mono text-[11px] tabular-nums text-muted-foreground shrink-0">
              {describeTemplateIntent(row.value, axesOf(row.targetKey))}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
/**
 * Effect identity for the dirty check — every field a save writes, in a fixed order.
 *
 * Field by field rather than `JSON.stringify(draft)`: key order differs between a draft this editor
 * built and one that came off the wire, so stringifying the objects would report a change on every
 * open. `parameters` is sorted for the same reason.
 */
function effectKey(effect: TemplateEffect | null): string {
  if (effect == null) return ''
  return JSON.stringify([
    effect.effectType,
    effect.category,
    effect.propertyName ?? null,
    effect.beatDivision,
    effect.blendMode,
    effect.distribution,
    effect.phaseOffset ?? 0,
    effect.speedMasterUuid ?? null,
    effect.rateSpeedMasterUuid ?? null,
    effect.seconds === true,
    // Size, direction and runs (effects plan D2, D5, D9), read at their defaults when unsaid.
    effect.size ?? 1,
    effect.direction ?? 'FORWARD',
    effect.nShot ?? null,
    // The spread's shape (D4), likewise.
    effect.spread ?? 1,
    effect.wings ?? 1,
    effect.blocks ?? 1,
    effect.groups ?? 0,
    Object.entries(effect.parameters).sort(([a], [b]) => a.localeCompare(b)),
  ])
}
