import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  compatibleEffectsFor,
  extraSliderPropertiesFor,
  groupMemberFixtures,
  propertyNamesFor,
  resolveEffectProperty,
  settingPropertiesFor,
  SETTING_SENTINEL,
  SLIDER_SENTINEL,
  type FxPropertyTarget,
} from '@/lib/fxTargetProperties'
import { fxCreateAddition, type FxCreateTarget } from '@/lib/fxCreateRequest'
import {
  useActiveEffectsQuery,
  useAddFixtureFxMutation,
  useEffectLibraryQuery,
  usePressProgrammerEffectMutation,
  useRemoveFxMutation,
  type EffectLibraryEntry,
} from '@/store/fixtureFx'
import { useApplyGroupFxMutation, useRemoveGroupFxMutation } from '@/store/groups'
import { useIsDeskConnected } from '@/store/status'
import { useSpeedMasterForCategory } from '@/store/speedMasters'
import { NEW_EFFECT_DIVISION, isOnSeconds } from '@/lib/effectTiming'
import { useTemplateListQuery } from '@/store/templates'
import {
  categoryForFamily,
  isPaletteSteps,
  isSlotSteps,
  paletteStepsOffers,
  seedSlots,
  serializeStepList,
  type PaletteStepsOffer,
} from '@/lib/stepEffects'
import type { BlendMode } from '@/api/groupsApi'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import type { CueTarget } from '@/api/cuesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { normalizeEffectName } from '../busking/buskingTypes'
import { getEffectDescription } from './fxConstants'
import {
  PICKER_FAMILIES,
  PICKER_FAMILY_LABELS,
  pickerFamilyOf,
  startingSpec,
  type PickerFamily,
} from './fxEditorModel'

/**
 * What an effect is started on: one fixture, a group, or a **selection** — several fixtures and
 * groups in order, one effect spread over them as a group's members are (effects plan D3). A
 * selection carries the fixtures it reaches for their properties, and its words for the sheet.
 */
export type FxTarget =
  | { type: 'fixture'; fixture: Fixture }
  | { type: 'group'; group: GroupSummary }
  | { type: 'selection'; targets: CueTarget[]; fixtures: Fixture[]; label: string }

/**
 * What a tap picks in **draft** (show-cue-editing plan D17): the effect with its starting spec, on
 * the property it lands on, for the host to store. Nothing runs.
 */
export interface DraftEffectPick {
  effectType: string
  category: string
  propertyName: string
  beatDivision: number
  /** The master tagged for the family's usage (D16); absent on a wall-clock effect, which starts on its own Seconds. */
  speedMasterUuid?: string | null
  blendMode: string
  parameters: Record<string, string>
  elementFilter?: string
}

/** The effect a picker session started and is auditioning — the one a second tap swaps. */
export interface AuditionedEffect {
  effectId: number
  effectType: string
  propertyName: string
}

/**
 * The FX tray's *adding* state (fixture-fx-sheets plan D9; Fx board, "Adding"): a family segment and
 * the effect rows. **A tap is the start** — the effect runs at once with its defaults, on the
 * property the sheet came from, as a programmer effect (D10: Clear sweeps it, Blind stages it,
 * Record captures it); **another tap swaps it** through `updateFx`, which keeps the phase (session 1
 * made a type swap carry the new type's timing source), so the operator auditions by tapping down
 * the list. *Edit <name>* goes on to `FxEditor`.
 *
 * A swap sends the new type's defaults with it — the old type's parameters mean nothing to the new
 * one — and its starting blend, so a Circle swapped for a Figure 8 is still *Around*. A tap on an
 * effect that lands on a **different property** (another family, or another setting) cannot be a
 * swap: an instance keeps its target. That one is a fresh start, and the auditioned effect it
 * replaces is stopped, so a picker session still holds one effect.
 *
 * The family opens on the row the sheet came from ([initialFamily]); a family the target cannot take
 * is disabled. An effect the library offers through a sentinel (`setting`, `slider`) on a target
 * with several such properties draws an *On* row that restarts it on the one chosen — the add
 * sheet's *Target setting* and *Target property* pickers, kept.
 */
export function FxPicker({
  target,
  initialFamily,
  preferredProperty,
  current,
  onCurrent,
  onEdit,
  elementFilter,
  press,
  draft,
}: {
  target: FxTarget
  /**
   * Start through the **bare effect press** (`POST /programmer/effects`, apply-model plan §3.4, D5)
   * under the door's mask: one Local effect over the target, which first takes those heads off every
   * other Local effect of its family. Absent — a focused Look, which a press must leave as the stage
   * was — the effect starts on `/fx/add` alone, for its host to absorb.
   */
  press?: { projectId: number; families: readonly AttributeFamily[] | null }
  /**
   * Start on some of a multi-head fixture's heads — the sheet's head strip picking exactly the odd,
   * even or a half of them (`elementFilterFor`). Absent: every head.
   */
  elementFilter?: string
  /** The row the sheet came from, as a picker family. */
  initialFamily?: PickerFamily | null
  /** The property that row writes, preferred over the effect's first compatible one. */
  preferredProperty?: string | null
  /** The effect this session has started, if any. Unused in [draft]. */
  current?: AuditionedEffect | null
  onCurrent?: (next: AuditionedEffect | null) => void
  /** *Edit <name>* — the host opens the editor on what this session started or picked. */
  onEdit: () => void
  /**
   * **Draft**: a tap starts nothing (D17). It hands [DraftEffectPick] to the host, which stores the
   * effect — the cue editor's FX tab — and a second tap hands the next, which the host swaps in for
   * [draft.current], the one this session picked. No socket is needed.
   */
  draft?: {
    current: { effectType: string; propertyName: string } | null
    onPick: (pick: DraftEffectPick) => void
  }
}) {
  const { data: library } = useEffectLibraryQuery()
  // In draft nothing runs, so nothing running is read.
  const { data: active } = useActiveEffectsQuery(undefined, { skip: draft != null })
  const { fixtures } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const [addFixtureFx] = useAddFixtureFxMutation()
  const [pressEffect] = usePressProgrammerEffectMutation()
  const [applyGroupFx] = useApplyGroupFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const propertyTarget = useMemo(
    (): FxPropertyTarget =>
      target.type === 'fixture'
        ? { type: 'fixture', fixture: target.fixture }
        : target.type === 'selection'
          ? { type: 'group', capabilities: [], members: target.fixtures }
          : { type: 'group', capabilities: target.group.capabilities, members: groupMemberFixtures(fixtures, target.group.name) },
    [target, fixtures],
  )
  const targetNames = useMemo(() => propertyNamesFor([propertyTarget]), [propertyTarget])
  // Palette Steps names no property of its own — it steps whichever its palettes name — so it is
  // never offered through the library's property match, only through [stepOffers] below.
  const compatible = useMemo(
    () => compatibleEffectsFor(library, targetNames).filter((e) => !isPaletteSteps(e.name)),
    [library, targetNames],
  )
  // Palette Steps (effects plan §4.4): offered in every family with two or more generic value
  // palettes naming a property this target has, stepping those palettes on that property.
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = projectId ? Number(projectId) : NaN
  const { data: templates } = useTemplateListQuery({ projectId: projectIdNum }, { skip: !Number.isFinite(projectIdNum) })
  const paletteStepsEntry = useMemo(() => library?.find((e) => isPaletteSteps(e.name)), [library])
  const stepOffers = useMemo(() => {
    if (paletteStepsEntry == null) return new Map<PickerFamily, PaletteStepsOffer>()
    const has = (name: string) => targetNames.has(name) || (name === 'rgbColour' && targetNames.has('colour'))
    return new Map(paletteStepsOffers(templates ?? [], has, preferredProperty).map((o): [PickerFamily, PaletteStepsOffer] => [o.family, o]))
  }, [paletteStepsEntry, templates, targetNames, preferredProperty])
  const byFamily = useMemo(() => {
    const grouped = new Map<PickerFamily, EffectLibraryEntry[]>()
    for (const entry of compatible) {
      const family = pickerFamilyOf(entry.category)
      grouped.set(family, [...(grouped.get(family) ?? []), entry])
    }
    if (paletteStepsEntry != null) {
      for (const family of stepOffers.keys()) grouped.set(family, [...(grouped.get(family) ?? []), paletteStepsEntry])
    }
    return grouped
  }, [compatible, paletteStepsEntry, stepOffers])

  const settingProps = settingPropertiesFor([propertyTarget])
  const isSettingRow = preferredProperty != null && settingProps.some((p) => p.name === preferredProperty)
  const firstFamily = PICKER_FAMILIES.find((f) => (byFamily.get(f)?.length ?? 0) > 0) ?? 'INTENSITY'
  // A Beam row opens on Beam where a beam effect drives its property — a beam built-in's own, Slot
  // Steps' on a setting, Palette Steps' over that property's palettes — and otherwise (prism without
  // slots or palettes) on Controls, where the effects that can take it are.
  const openingFamily =
    initialFamily === 'BEAM' &&
    preferredProperty != null &&
    !(byFamily.get('BEAM') ?? []).some((e) =>
      isPaletteSteps(e.name)
        ? stepOffers.get('BEAM')?.propertyName === preferredProperty
        : e.compatibleProperties.includes(preferredProperty) ||
          (isSettingRow && e.compatibleProperties.includes(SETTING_SENTINEL)),
    )
      ? 'CONTROLS'
      : initialFamily
  const [chosenFamily, setChosenFamily] = useState<PickerFamily | null>(null)
  const family =
    chosenFamily ?? (openingFamily != null && (byFamily.get(openingFamily)?.length ?? 0) > 0 ? openingFamily : firstFamily)

  // The auditioned effect is only a swap target while it is still running — stopped elsewhere, the
  // next tap starts afresh. One this session has just started counts as running until the effect
  // list has caught up with it: a second tap inside that refetch would otherwise start a second
  // effect and orphan the first.
  const [justStarted, setJustStarted] = useState<number | null>(null)
  const runningEffect = current != null ? (active ?? []).find((e) => e.id === current?.effectId) : undefined
  const masterForCategory = useSpeedMasterForCategory()
  const running =
    current != null && (runningEffect != null || justStarted === current.effectId) ? current : null
  /** What the rows mark as picked: the auditioned effect, or in draft the one the host stored. */
  const shown = draft != null ? draft.current : running
  useEffect(() => {
    if (justStarted != null && (active ?? []).some((e) => e.id === justStarted)) setJustStarted(null)
  }, [active, justStarted])

  const [chosen, setChosen] = useState<{ setting?: string; slider?: string }>({})
  const busy = useRef(false)
  const [pending, setPending] = useState(false)

  const propertyFor = (entry: EffectLibraryEntry, pick = chosen): string | null => {
    if (isPaletteSteps(entry.name)) return stepOffers.get(family)?.propertyName ?? null
    if (
      preferredProperty != null &&
      entry.compatibleProperties.includes(preferredProperty) &&
      targetNames.has(preferredProperty)
    ) {
      return preferredProperty
    }
    // A setting row takes an effect offered through the setting sentinel (Slot Steps) itself.
    if (isSettingRow && pick.setting == null && entry.compatibleProperties.includes(SETTING_SENTINEL)) {
      return preferredProperty
    }
    // Slot Steps from anywhere else starts on a gobo wheel where the head has one (Chris's call,
    // the palette editor's own default), else the first setting; the *On* row moves it.
    if (isSlotSteps(entry.name) && pick.setting == null) {
      const gobo = settingProps.find((p) => p.category === 'gobo')
      if (gobo != null) return gobo.name
    }
    return resolveEffectProperty(propertyTarget, entry, pick)
  }

  /**
   * The effect's starting spec, with the step lists filled so a tap shows it stepping: Palette
   * Steps through the family's palettes, Slot Steps through the setting's first slots past its open.
   */
  const specFor = (entry: EffectLibraryEntry, propertyName: string) => {
    const spec = startingSpec(entry)
    if (isPaletteSteps(entry.name)) {
      const offer = stepOffers.get(family)
      if (offer != null) {
        spec.parameters = { ...spec.parameters, palettes: serializeStepList(offer.palettes.map((p) => `tmpl:${p.uuid}`)) }
      }
    } else if (isSlotSteps(entry.name)) {
      spec.parameters = { ...spec.parameters, slots: seedSlots(settingProps.find((p) => p.name === propertyName)) }
    }
    return spec
  }

  /** The category a master is stamped by: Palette Steps' is its palettes' family's. */
  const categoryOf = (entry: EffectLibraryEntry) => {
    const offer = isPaletteSteps(entry.name) ? stepOffers.get(family) : undefined
    return offer != null ? categoryForFamily(offer.family) : entry.category
  }

  const start = async (entry: EffectLibraryEntry, pick = chosen) => {
    if (busy.current) return
    const propertyName = propertyFor(entry, pick)
    if (propertyName == null) return
    const spec = specFor(entry, propertyName)

    if (draft != null) {
      if (
        draft.current != null &&
        draft.current.propertyName === propertyName &&
        normalizeEffectName(draft.current.effectType) === normalizeEffectName(entry.name)
      ) {
        return
      }
      draft.onPick({
        effectType: entry.name,
        category: entry.category,
        propertyName,
        // A new effect is one bar on the master tagged for its family's usage (D16), as below.
        beatDivision: NEW_EFFECT_DIVISION,
        ...(entry.timingSource === 'WALL_CLOCK' ? {} : { speedMasterUuid: masterForCategory(categoryOf(entry)) }),
        blendMode: spec.blendMode,
        parameters: spec.parameters,
        ...(elementFilter != null && target.type === 'fixture' ? { elementFilter } : {}),
      })
      return
    }

    if (running != null && running.propertyName === propertyName) {
      if (normalizeEffectName(running.effectType) === normalizeEffectName(entry.name)) return
      // A swap across units (a beat effect for a wall-clock flicker) would carry the number across
      // them — 16 beats becoming 16 seconds — so it lands on a new effect's one bar; a swap within
      // one unit keeps the operator's speed. The desk keeps the swapped effect's timing choice
      // (D7), so a beat effect on Seconds stays in seconds whichever type it becomes, and the
      // running effect's loop already reads `WALL_CLOCK` for one.
      const runningOnSeconds = runningEffect != null && isOnSeconds(runningEffect, runningEffect.timingSource)
      const fromUnit = (runningEffect?.timingSource ?? 'BEAT') === 'WALL_CLOCK' ? 'seconds' : 'beats'
      const toUnit = entry.timingSource === 'WALL_CLOCK' || runningOnSeconds ? 'seconds' : 'beats'
      const speed = fromUnit === toUnit ? {} : { beatDivision: NEW_EFFECT_DIVISION }
      if (lightingApi.fx.updateFx(running.effectId, { effectType: entry.name, ...spec, ...speed })) {
        onCurrent?.({ ...running, effectType: entry.name })
      }
      return
    }

    busy.current = true
    setPending(true)
    try {
      if (running != null) {
        const request =
          target.type === 'group'
            ? removeGroupFx({ id: running.effectId, groupName: target.group.name })
            : removeFx({ id: running.effectId, fixtureKey: target.type === 'fixture' ? target.fixture.key : '' })
        await request.unwrap().catch(() => {
          // Reported by errorToastMiddleware; the start below goes ahead.
        })
      }
      const request = {
        effectType: entry.name,
        propertyName,
        // A new effect is one bar on the master tagged for its family's usage, else master 1
        // (venue-visit plan D16) — stamped here, as the palette editor stamps one, so a null
        // still means master 1 everywhere. A wall-clock effect starts on its own Seconds.
        beatDivision: NEW_EFFECT_DIVISION,
        ...(entry.timingSource === 'WALL_CLOCK' ? {} : { speedMasterUuid: masterForCategory(categoryOf(entry)) }),
        blendMode: spec.blendMode as BlendMode,
        phaseOffset: 0,
        parameters: spec.parameters,
        ...(elementFilter != null && target.type === 'fixture' ? { elementFilter } : {}),
      }
      const created = await (
        press != null
          ? pressEffect({
              ...request,
              speedMasterUuid: request.speedMasterUuid ?? undefined,
              projectId: press.projectId,
              targets: pressTargetsOf(target),
              ...(press.families != null && press.families.length > 0 ? { families: [...press.families] } : {}),
            })
          : startOn(fxCreateAddition(fxCreateTargetOf(target), { ...request, programmerOwned: true }))
      )
        .unwrap()
        // Reported by errorToastMiddleware — a mask refusal in the desk's own words; a failed start
        // leaves nothing auditioned.
        .catch(() => null)
      if (created) setJustStarted(created.effectId)
      onCurrent?.(created ? { effectId: created.effectId, effectType: entry.name, propertyName } : null)
    } finally {
      busy.current = false
      setPending(false)
    }
  }

  /** `/fx/add`, or the group route for a group. */
  const startOn = (addition: ReturnType<typeof fxCreateAddition>) =>
    addition.kind === 'group' ? applyGroupFx({ groupName: addition.groupName, ...addition.payload }) : addFixtureFx(addition.payload)

  const effects = byFamily.get(family) ?? []
  const currentEntry =
    shown != null
      ? [...compatible, ...(paletteStepsEntry != null ? [paletteStepsEntry] : [])].find(
          (e) => normalizeEffectName(e.name) === normalizeEffectName(shown.effectType),
        )
      : undefined
  // The *On* row: an effect offered through a sentinel names no property of its own, so where the
  // target has several it could land on, the operator picks — and the pick restarts it there.
  const sliderProps = extraSliderPropertiesFor([propertyTarget])
  const sentinel =
    currentEntry == null || shown == null
      ? null
      : currentEntry.compatibleProperties.includes(SETTING_SENTINEL) && settingProps.some((p) => p.name === shown.propertyName)
        ? { kind: 'setting' as const, options: settingProps }
        : currentEntry.compatibleProperties.includes(SLIDER_SENTINEL) && sliderProps.some((p) => p.name === shown.propertyName)
          ? { kind: 'slider' as const, options: sliderProps }
          : null

  return (
    <div data-fx-picker className="flex min-h-0 flex-col gap-2">
      <ToggleGroup
        type="single"
        size="sm"
        value={family}
        aria-label="Effect family"
        onValueChange={(v) => v && setChosenFamily(v as PickerFamily)}
        className="flex w-full"
      >
        {PICKER_FAMILIES.map((f) => (
          <ToggleGroupItem
            key={f}
            value={f}
            disabled={(byFamily.get(f)?.length ?? 0) === 0}
            className="min-w-0 flex-1 truncate px-1.5 text-xs"
          >
            {PICKER_FAMILY_LABELS[f]}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>

      {library == null ? (
        <EditorReadout>Reading the effect library…</EditorReadout>
      ) : effects.length === 0 ? (
        <EditorReadout>Nothing in the library fits this {target.type}.</EditorReadout>
      ) : (
        <ul className="flex flex-col gap-0.5" aria-label={`${PICKER_FAMILY_LABELS[family]} effects`}>
          {effects.map((entry) => {
            const isCurrent = currentEntry != null && currentEntry.name === entry.name
            return (
              <li key={entry.name}>
                <button
                  type="button"
                  data-effect-pick={entry.name}
                  disabled={(draft == null && !connected) || pending}
                  aria-label={entry.name}
                  aria-pressed={isCurrent}
                  title={getEffectDescription(entry.name, entry.description)}
                  onClick={() => void start(entry)}
                  className={cn(
                    'flex w-full min-w-0 items-center gap-2 rounded-md border border-transparent px-2.5 py-1.5 text-left text-[12.5px] hover:bg-accent disabled:opacity-50',
                    isCurrent && 'border-violet-500/55 bg-violet-500/[0.08]',
                  )}
                >
                  <span className={cn('size-[7px] shrink-0 rounded-full', isCurrent ? 'bg-violet-500' : 'bg-muted-foreground/40')} />
                  <span className="min-w-0 flex-1 truncate font-medium">{entry.name}</span>
                  {isCurrent && (
                    <span className="shrink-0 text-[10px] text-violet-600 dark:text-violet-300">{draft != null ? 'in the cue' : 'running'}</span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {sentinel != null && shown != null && currentEntry != null && sentinel.options.length > 1 && (
        <div className="flex flex-col gap-1">
          <EditorLabel>On</EditorLabel>
          <Select
            value={shown.propertyName}
            disabled={(draft == null && !connected) || pending}
            onValueChange={(name) => {
              const pick = sentinel.kind === 'setting' ? { ...chosen, setting: name } : { ...chosen, slider: name }
              setChosen(pick)
              void start(currentEntry, pick)
            }}
          >
            <SelectTrigger aria-label="Property" className="h-7 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {sentinel.options.map((p) => (
                <SelectItem key={p.name} value={p.name} className="text-xs">
                  {p.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      <div className="flex items-center gap-2 pt-1">
        <p className="min-w-0 flex-1 text-[10.5px] text-muted-foreground">
          {draft != null ? 'A tap adds it to the cue, running nothing; another tap swaps it.' : 'A tap starts it; another tap swaps it, phase kept.'}
        </p>
        {shown != null && (
          <Button variant="outline" size="sm" className="h-7 shrink-0 gap-1 px-2 text-xs" onClick={onEdit}>
            <Pencil className="size-3" />
            Edit {currentEntry?.name ?? shown.effectType}
          </Button>
        )}
      </div>
    </div>
  )
}

/** The create route's address for a target. */
function fxCreateTargetOf(target: FxTarget): FxCreateTarget {
  return target.type === 'group'
    ? { type: 'group', groupName: target.group.name }
    : target.type === 'selection'
      ? { type: 'selection', targets: target.targets }
      : { type: 'fixture', fixtureKey: target.fixture.key }
}

/** A target as the press names it: a fixture or a cell by its key, a group by its name, a selection's items in order. */
export function pressTargetsOf(target: FxTarget): CueTarget[] {
  return target.type === 'group'
    ? [{ type: 'group', key: target.group.name }]
    : target.type === 'selection'
      ? [...target.targets]
      : [{ type: 'fixture', key: target.fixture.key }]
}
