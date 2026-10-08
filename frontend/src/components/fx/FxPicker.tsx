import { useEffect, useMemo, useRef, useState } from 'react'
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
import { fxCreateAddition } from '@/lib/fxCreateRequest'
import {
  useActiveEffectsQuery,
  useAddFixtureFxMutation,
  useEffectLibraryQuery,
  useRemoveFxMutation,
  type EffectLibraryEntry,
} from '@/store/fixtureFx'
import { useApplyGroupFxMutation, useRemoveGroupFxMutation } from '@/store/groups'
import { useIsDeskConnected } from '@/store/status'
import type { BlendMode } from '@/api/groupsApi'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
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

/** What an effect is started on: one fixture, or a group. */
export type FxTarget = { type: 'fixture'; fixture: Fixture } | { type: 'group'; group: GroupSummary }

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
}: {
  target: FxTarget
  /**
   * Start on some of a multi-head fixture's heads — the sheet's head strip picking exactly the odd,
   * even or a half of them (`elementFilterFor`). Absent: every head.
   */
  elementFilter?: string
  /** The row the sheet came from, as a picker family. */
  initialFamily?: PickerFamily | null
  /** The property that row writes, preferred over the effect's first compatible one. */
  preferredProperty?: string | null
  /** The effect this session has started, if any. */
  current: AuditionedEffect | null
  onCurrent: (next: AuditionedEffect | null) => void
  /** *Edit <name>* — the host opens `FxEditor` on [current]. */
  onEdit: () => void
}) {
  const { data: library } = useEffectLibraryQuery()
  const { data: active } = useActiveEffectsQuery()
  const { fixtures } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const [addFixtureFx] = useAddFixtureFxMutation()
  const [applyGroupFx] = useApplyGroupFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const propertyTarget = useMemo(
    (): FxPropertyTarget =>
      target.type === 'fixture'
        ? { type: 'fixture', fixture: target.fixture }
        : { type: 'group', capabilities: target.group.capabilities, members: groupMemberFixtures(fixtures, target.group.name) },
    [target, fixtures],
  )
  const compatible = useMemo(
    () => compatibleEffectsFor(library, propertyNamesFor([propertyTarget])),
    [library, propertyTarget],
  )
  const byFamily = useMemo(() => {
    const grouped = new Map<PickerFamily, EffectLibraryEntry[]>()
    for (const entry of compatible) {
      const family = pickerFamilyOf(entry.category)
      grouped.set(family, [...(grouped.get(family) ?? []), entry])
    }
    return grouped
  }, [compatible])

  const firstFamily = PICKER_FAMILIES.find((f) => (byFamily.get(f)?.length ?? 0) > 0) ?? 'INTENSITY'
  const [chosenFamily, setChosenFamily] = useState<PickerFamily | null>(null)
  const family =
    chosenFamily ?? (initialFamily != null && (byFamily.get(initialFamily)?.length ?? 0) > 0 ? initialFamily : firstFamily)

  // The auditioned effect is only a swap target while it is still running — stopped elsewhere, the
  // next tap starts afresh. One this session has just started counts as running until the effect
  // list has caught up with it: a second tap inside that refetch would otherwise start a second
  // effect and orphan the first.
  const [justStarted, setJustStarted] = useState<number | null>(null)
  const runningEffect = current != null ? (active ?? []).find((e) => e.id === current.effectId) : undefined
  const running = current != null && (runningEffect != null || justStarted === current.effectId) ? current : null
  useEffect(() => {
    if (justStarted != null && (active ?? []).some((e) => e.id === justStarted)) setJustStarted(null)
  }, [active, justStarted])

  const [chosen, setChosen] = useState<{ setting?: string; slider?: string }>({})
  const busy = useRef(false)
  const [pending, setPending] = useState(false)

  const propertyFor = (entry: EffectLibraryEntry, pick = chosen): string | null => {
    if (
      preferredProperty != null &&
      entry.compatibleProperties.includes(preferredProperty) &&
      propertyNamesFor([propertyTarget]).has(preferredProperty)
    ) {
      return preferredProperty
    }
    return resolveEffectProperty(propertyTarget, entry, pick)
  }

  const start = async (entry: EffectLibraryEntry, pick = chosen) => {
    if (busy.current) return
    const propertyName = propertyFor(entry, pick)
    if (propertyName == null) return
    const spec = startingSpec(entry)

    if (running != null && running.propertyName === propertyName) {
      if (normalizeEffectName(running.effectType) === normalizeEffectName(entry.name)) return
      // A swap across timing sources (a beat effect for a wall-clock flicker) would carry the number
      // across units — 16 beats becoming 16 seconds — so it lands on the start's 1, as a fresh
      // effect would; a swap within one source keeps the operator's speed.
      const fromSource = runningEffect?.timingSource ?? 'BEAT'
      const toSource = entry.timingSource ?? 'BEAT'
      const speed = fromSource === toSource ? {} : { beatDivision: 1 }
      if (lightingApi.fx.updateFx(running.effectId, { effectType: entry.name, ...spec, ...speed })) {
        onCurrent({ ...running, effectType: entry.name })
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
            : removeFx({ id: running.effectId, fixtureKey: target.fixture.key })
        await request.unwrap().catch(() => {
          // Reported by errorToastMiddleware; the start below goes ahead.
        })
      }
      const addition = fxCreateAddition(
        target.type === 'group'
          ? { type: 'group', groupName: target.group.name }
          : { type: 'fixture', fixtureKey: target.fixture.key },
        {
          effectType: entry.name,
          propertyName,
          beatDivision: 1,
          blendMode: spec.blendMode as BlendMode,
          phaseOffset: 0,
          parameters: spec.parameters,
          programmerOwned: true,
          ...(elementFilter != null && target.type === 'fixture' ? { elementFilter } : {}),
        },
      )
      const created = await (addition.kind === 'group'
        ? applyGroupFx({ groupName: addition.groupName, ...addition.payload })
        : addFixtureFx(addition.payload)
      )
        .unwrap()
        // Reported by errorToastMiddleware; a failed start leaves nothing auditioned.
        .catch(() => null)
      if (created) setJustStarted(created.effectId)
      onCurrent(created ? { effectId: created.effectId, effectType: entry.name, propertyName } : null)
    } finally {
      busy.current = false
      setPending(false)
    }
  }

  const effects = byFamily.get(family) ?? []
  const currentEntry = running != null ? compatible.find((e) => normalizeEffectName(e.name) === normalizeEffectName(running.effectType)) : undefined
  // The *On* row: an effect offered through a sentinel names no property of its own, so where the
  // target has several it could land on, the operator picks — and the pick restarts it there.
  const settingProps = settingPropertiesFor([propertyTarget])
  const sliderProps = extraSliderPropertiesFor([propertyTarget])
  const sentinel =
    currentEntry == null || running == null
      ? null
      : currentEntry.compatibleProperties.includes(SETTING_SENTINEL) && settingProps.some((p) => p.name === running.propertyName)
        ? { kind: 'setting' as const, options: settingProps }
        : currentEntry.compatibleProperties.includes(SLIDER_SENTINEL) && sliderProps.some((p) => p.name === running.propertyName)
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
        <EditorReadout>Nothing in the library fits this {target.type === 'group' ? 'group' : 'fixture'}.</EditorReadout>
      ) : (
        <ul className="flex flex-col gap-0.5" aria-label={`${PICKER_FAMILY_LABELS[family]} effects`}>
          {effects.map((entry) => {
            const isCurrent = currentEntry != null && currentEntry.name === entry.name
            return (
              <li key={entry.name}>
                <button
                  type="button"
                  data-effect-pick={entry.name}
                  disabled={!connected || pending}
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
                  {isCurrent && <span className="shrink-0 text-[10px] text-violet-600 dark:text-violet-300">running</span>}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      {sentinel != null && running != null && currentEntry != null && sentinel.options.length > 1 && (
        <div className="flex flex-col gap-1">
          <EditorLabel>On</EditorLabel>
          <Select
            value={running.propertyName}
            disabled={!connected || pending}
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
        <p className="min-w-0 flex-1 text-[10.5px] text-muted-foreground">A tap starts it; another tap swaps it, phase kept.</p>
        {running != null && (
          <Button variant="outline" size="sm" className="h-7 shrink-0 gap-1 px-2 text-xs" onClick={onEdit}>
            <Pencil className="size-3" />
            Edit {currentEntry?.name ?? running.effectType}
          </Button>
        )}
      </div>
    </div>
  )
}
