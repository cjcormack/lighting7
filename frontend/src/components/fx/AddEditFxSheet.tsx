import { useState, useEffect, useMemo } from 'react'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
  SheetBody,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from '@/components/ui/sheet'
import {
  useEffectLibraryQuery,
  useAddFixtureFxMutation,
  useUpdateFxMutation,
  type EffectLibraryEntry,
  type FixtureDirectEffect,
} from '@/store/fixtureFx'
import {
  useApplyGroupFxMutation,
  useUpdateGroupFxMutation,
} from '@/store/groups'
import { useFixtureListQuery } from '@/store/fixtures'
import type { Fixture } from '@/store/fixtures'
import type { GroupSummary, GroupActiveEffect, BlendMode, DistributionStrategy, ElementMode } from '@/api/groupsApi'
import {
  compatibleEffectsFor,
  effectsByCategory as groupEffectsByCategory,
  extraSliderPropertiesFor,
  groupMemberFixtures,
  propertyNamesFor,
  resolveEffectProperty,
  settingPropertiesFor,
  SETTING_SENTINEL,
  SLIDER_SENTINEL,
  type FxPropertyTarget,
} from '@/lib/fxTargetProperties'
import { fxCreateAddition, type FxCreateRequest } from '@/lib/fxCreateRequest'
import { EffectCategoryPicker } from './EffectCategoryPicker'
import { EffectTypePicker } from './EffectTypePicker'
import { EffectParameterForm } from './EffectParameterForm'
import { detectExtendedChannels } from './colourUtils'

// ─── Target discriminated union ────────────────────────────────────────────

export type FxTarget =
  | { type: 'fixture'; fixture: Fixture }
  | { type: 'group'; group: GroupSummary }

type EditEffect = FixtureDirectEffect | GroupActiveEffect

export type SheetMode =
  | { mode: 'add' }
  | { mode: 'edit'; effectId: number; effect: EditEffect }

interface AddEditFxSheetProps {
  target: FxTarget
  mode: SheetMode | undefined
  onClose: () => void
  /**
   * Create the effect in the programmer's reserved priority band, so it composes on top of the
   * cues underneath rather than beside them. What the programmer's own `+ Effect` wants; the
   * fixture and group detail surfaces leave it off and write at base priority as they always have.
   */
  programmerOwned?: boolean
  /**
   * The instance that was just created, for a caller that needs to do something with it.
   *
   * Exists so the programmer can honour "an effect lands wherever the focused scope's values go":
   * with a layer focused it moves the new instance into that layer's Look. Reported rather than
   * inferred — diffing the running-effect list before and after would race with anything else
   * starting an effect, including another desk.
   *
   * Not called for an edit, and not called when the create failed.
   */
  onCreated?: (effectId: number) => void
}

type Step = 'category' | 'effect' | 'configure'

export function AddEditFxSheet({ target, mode, onClose, programmerOwned, onCreated }: AddEditFxSheetProps) {
  const isOpen = mode !== undefined
  const isEdit = mode?.mode === 'edit'

  const { data: library } = useEffectLibraryQuery(undefined, { skip: !isOpen })
  const { data: fixtureList } = useFixtureListQuery(undefined, {
    skip: target.type !== 'group',
  })

  // Fixture mutations
  const [addFixtureFx] = useAddFixtureFxMutation()
  const [updateFixtureFx] = useUpdateFxMutation()

  // Group mutations
  const [applyGroupFx] = useApplyGroupFxMutation()
  const [updateGroupFx] = useUpdateGroupFxMutation()

  const [step, setStep] = useState<Step>('category')
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null)
  const [selectedEffect, setSelectedEffect] = useState<EffectLibraryEntry | null>(null)

  const [beatDivision, setBeatDivision] = useState(1.0)
  const [blendMode, setBlendMode] = useState('OVERRIDE')
  const [phaseOffset, setPhaseOffset] = useState(0)
  const [startOnBeat, setStartOnBeat] = useState(true)
  const [parameters, setParameters] = useState<Record<string, string>>({})
  const [distributionStrategy, setDistributionStrategy] = useState('LINEAR')
  const [elementMode, setElementMode] = useState<ElementMode>('PER_FIXTURE')
  const [elementFilter, setElementFilter] = useState('ALL')
  const [stepTiming, setStepTiming] = useState(false)
  const [speedMasterUuid, setSpeedMasterUuid] = useState<string | null>(null)
  const [rateSpeedMasterUuid, setRateSpeedMasterUuid] = useState<string | null>(null)
  const [selectedSettingProp, setSelectedSettingProp] = useState<string | null>(null)
  const [selectedSliderProp, setSelectedSliderProp] = useState<string | null>(null)

  // ─── Derived target info ───────────────────────────────────────────────

  const targetLabel = target.type === 'fixture' ? target.fixture.name : target.group.name

  // Member fixtures (groups only)
  const memberFixtures = useMemo(() => {
    if (target.type !== 'group') return []
    return groupMemberFixtures(fixtureList, target.group.name)
  }, [target, fixtureList])

  // The target as the shared property module addresses it.
  const propertyTarget = useMemo(
    (): FxPropertyTarget =>
      target.type === 'fixture'
        ? { type: 'fixture', fixture: target.fixture }
        : { type: 'group', capabilities: target.group.capabilities, members: memberFixtures },
    [target, memberFixtures],
  )

  // Multi-head detection
  const isMultiHead = useMemo(() => {
    if (target.type === 'fixture') {
      return (target.fixture.elementGroupProperties?.length ?? 0) > 0
    }
    // Group: always show distribution; check for multi-element members for elementMode
    return true
  }, [target])

  const hasMultiElementMembers = useMemo(() => {
    if (target.type === 'fixture') return false
    return memberFixtures.some((f) => f.elements && f.elements.length > 1)
  }, [target, memberFixtures])

  const showStartOnBeat = target.type === 'fixture'

  // Extended colour channels (W/A/UV) available on the target
  const extendedChannels = useMemo(() => {
    const fixtures =
      target.type === 'fixture' ? [target.fixture] : memberFixtures
    return detectExtendedChannels(fixtures.map((f) => f.properties ?? []))
  }, [target, memberFixtures])

  // ─── Property computation ──────────────────────────────────────────────

  const effectsByCategory = useMemo(
    () => groupEffectsByCategory(compatibleEffectsFor(library, propertyNamesFor([propertyTarget]))),
    [library, propertyTarget],
  )

  // Setting and slider properties, for the explicit pickers this sheet offers.
  const settingProperties = useMemo(() => settingPropertiesFor([propertyTarget]), [propertyTarget])
  const extraSliderProperties = useMemo(
    () => extraSliderPropertiesFor([propertyTarget]),
    [propertyTarget],
  )

  const targetPropertyName = useMemo((): string | null => {
    if (!selectedEffect) return null
    return resolveEffectProperty(propertyTarget, selectedEffect, {
      setting: selectedSettingProp,
      slider: selectedSliderProp,
    })
  }, [selectedEffect, propertyTarget, selectedSettingProp, selectedSliderProp])

  const settingOptions = useMemo(() => {
    if (!selectedEffect?.compatibleProperties.includes(SETTING_SENTINEL) || !targetPropertyName) return undefined
    const settingProp = settingProperties.find((sp) => sp.name === targetPropertyName)
    return settingProp?.options
  }, [selectedEffect, targetPropertyName, settingProperties])

  // ─── State management ──────────────────────────────────────────────────

  useEffect(() => {
    if (!isOpen) return

    if (isEdit && mode.mode === 'edit') {
      setStep('configure')
      const normalize = (s: string) => s.toLowerCase().replace(/[\s_]/g, '')
      const entry = library?.find(
        (e) => normalize(e.name) === normalize(mode.effect.effectType),
      )
      setSelectedEffect(entry ?? null)
      setSelectedCategory(entry?.category ?? null)
      setBeatDivision(mode.effect.beatDivision)
      setBlendMode(mode.effect.blendMode)
      setPhaseOffset(mode.effect.phaseOffset)
      setParameters({ ...mode.effect.parameters })
      setSelectedSettingProp(mode.effect.propertyName ?? null)
      setSelectedSliderProp(mode.effect.propertyName ?? null)

      setStepTiming(mode.effect.stepTiming ?? false)
      setSpeedMasterUuid(mode.effect.speedMasterUuid ?? null)
      setRateSpeedMasterUuid(mode.effect.rateSpeedMasterUuid ?? null)

      if (target.type === 'group' && 'distribution' in mode.effect) {
        setDistributionStrategy(mode.effect.distribution)
        setElementMode((mode.effect as GroupActiveEffect).elementMode ?? 'PER_FIXTURE')
        setElementFilter((mode.effect as GroupActiveEffect).elementFilter ?? 'ALL')
      } else {
        setDistributionStrategy((mode.effect as FixtureDirectEffect).distributionStrategy ?? 'LINEAR')
        setElementFilter((mode.effect as FixtureDirectEffect).elementFilter ?? 'ALL')
      }
    } else {
      setStep('category')
      setSelectedCategory(null)
      setSelectedEffect(null)
      setBeatDivision(1.0)
      setBlendMode('OVERRIDE')
      setPhaseOffset(0)
      setStartOnBeat(true)
      setParameters({})
      setDistributionStrategy('LINEAR')
      setElementMode('PER_FIXTURE')
      setElementFilter('ALL')
      setStepTiming(false)
      setSpeedMasterUuid(null)
      setRateSpeedMasterUuid(null)
      setSelectedSettingProp(null)
      setSelectedSliderProp(null)
    }
  }, [isOpen, mode, library, isEdit, target.type])

  const handleSelectCategory = (cat: string) => {
    setSelectedCategory(cat)
    setStep('effect')
  }

  const handleSelectEffect = (effect: EffectLibraryEntry) => {
    setSelectedEffect(effect)
    const defaults: Record<string, string> = {}
    effect.parameters.forEach((p) => {
      defaults[p.name] = p.defaultValue
    })
    setParameters(defaults)
    setStep('configure')
  }

  const handleApply = async () => {
    // A rate master only means anything to a WALL_CLOCK effect. The picker is already gated
    // on that, but the state survives stepping Back and choosing a different effect — so
    // gate the payload too, or a BEAT effect gets persisted with a rate master the engine
    // never reads and a summary chip claiming a scaling that does not happen.
    const ratePayload =
      selectedEffect?.timingSource === 'WALL_CLOCK' && rateSpeedMasterUuid != null
        ? { rateSpeedMasterUuid }
        : {}

    /**
     * Start the effect, on whichever of the two create routes this target needs.
     *
     * The route-shaped half lives in `fxCreateAddition`, shared with the busking pad; what each
     * branch below still decides is which of its own fields are *meaningful* — a single-head
     * fixture has nothing to distribute across, a group with no multi-element members has no
     * element mode.
     */
    const create = async (request: FxCreateRequest) => {
      const addition = fxCreateAddition(
        target.type === 'group'
          ? { type: 'group', groupName: target.group.name }
          : { type: 'fixture', fixtureKey: target.fixture.key },
        request,
      )
      const created = await (addition.kind === 'group'
        ? applyGroupFx({ groupName: addition.groupName, ...addition.payload })
        : addFixtureFx(addition.payload))
        .unwrap()
        // `.unwrap()` is here only so `onCreated` gets a real id — but it also makes a failed
        // create *throw*, which from an `onClick` is an unhandled rejection that skips `onClose`.
        // Swallowed rather than surfaced: `errorToastMiddleware` already reports it, and the
        // sheet's own behaviour on failure is unchanged from before the id was needed.
        .catch(() => null)
      if (created) onCreated?.(created.effectId)
    }

    if (target.type === 'fixture') {
      const fixture = target.fixture
      const fixtureIsMultiHead = (fixture.elementGroupProperties?.length ?? 0) > 0

      const filterPayload = fixtureIsMultiHead && elementFilter !== 'ALL' ? { elementFilter } : {}

      if (isEdit && mode?.mode === 'edit') {
        await updateFixtureFx({
          id: mode.effectId,
          fixtureKey: fixture.key,
          body: {
            effectType: selectedEffect?.name,
            beatDivision,
            blendMode,
            phaseOffset,
            parameters,
            stepTiming,
            ...(speedMasterUuid != null ? { speedMasterUuid } : {}),
            ...ratePayload,
            ...(fixtureIsMultiHead ? { distributionStrategy } : {}),
            ...filterPayload,
          },
        })
      } else if (selectedEffect && targetPropertyName) {
        await create({
          effectType: selectedEffect.name,
          propertyName: targetPropertyName,
          beatDivision,
          blendMode: blendMode as BlendMode,
          startOnBeat,
          phaseOffset,
          parameters,
          stepTiming,
          programmerOwned,
          speedMasterUuid,
          ...ratePayload,
          ...(fixtureIsMultiHead
            ? { distribution: distributionStrategy as DistributionStrategy }
            : {}),
          ...filterPayload,
        })
      }
    } else {
      const group = target.group
      const showFilter = isMultiHead || hasMultiElementMembers
      const filterPayload = showFilter && elementFilter !== 'ALL' ? { elementFilter } : {}

      if (isEdit && mode?.mode === 'edit') {
        await updateGroupFx({
          id: mode.effectId,
          groupName: group.name,
          body: {
            effectType: selectedEffect?.name,
            beatDivision,
            blendMode,
            phaseOffset,
            parameters,
            distributionStrategy,
            stepTiming,
            ...(speedMasterUuid != null ? { speedMasterUuid } : {}),
            ...ratePayload,
            ...(hasMultiElementMembers ? { elementMode } : {}),
            ...filterPayload,
          },
        })
      } else if (selectedEffect && targetPropertyName) {
        await create({
          effectType: selectedEffect.name,
          propertyName: targetPropertyName,
          beatDivision,
          blendMode: blendMode as BlendMode,
          distribution: distributionStrategy as DistributionStrategy,
          phaseOffset,
          parameters,
          stepTiming,
          programmerOwned,
          speedMasterUuid,
          ...ratePayload,
          ...(hasMultiElementMembers ? { elementMode } : {}),
          ...filterPayload,
        })
      }
    }
    onClose()
  }

  return (
    <Sheet open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{isEdit ? 'Edit Effect' : 'Add Effect'}</SheetTitle>
          <SheetDescription>{targetLabel}</SheetDescription>
        </SheetHeader>

        <SheetBody className="space-y-0 p-0">
          {step === 'category' && (
            <EffectCategoryPicker
              effectsByCategory={effectsByCategory}
              onSelect={handleSelectCategory}
            />
          )}

          {step === 'effect' && selectedCategory && (
            <EffectTypePicker
              category={selectedCategory}
              effects={effectsByCategory[selectedCategory] ?? []}
              onSelect={handleSelectEffect}
              onBack={() => setStep('category')}
            />
          )}

          {step === 'configure' && selectedEffect && (
            <EffectParameterForm
              effect={selectedEffect}
              beatDivision={beatDivision}
              onBeatDivisionChange={setBeatDivision}
              blendMode={blendMode}
              onBlendModeChange={setBlendMode}
              phaseOffset={phaseOffset}
              onPhaseOffsetChange={setPhaseOffset}
              startOnBeat={startOnBeat}
              onStartOnBeatChange={setStartOnBeat}
              parameters={parameters}
              onParametersChange={setParameters}
              targetPropertyName={targetPropertyName}
              isEdit={isEdit}
              onBack={isEdit ? undefined : () => setStep('effect')}
              distributionStrategy={distributionStrategy}
              onDistributionStrategyChange={setDistributionStrategy}
              showDistribution={isMultiHead}
              showStartOnBeat={showStartOnBeat}
              elementMode={elementMode}
              onElementModeChange={(v) => setElementMode(v as ElementMode)}
              showElementMode={hasMultiElementMembers}
              elementFilter={elementFilter}
              onElementFilterChange={setElementFilter}
              showElementFilter={isMultiHead}
              settingOptions={settingOptions}
              settingProperties={selectedEffect?.compatibleProperties.includes(SETTING_SENTINEL) ? settingProperties : undefined}
              onSettingPropertyChange={setSelectedSettingProp}
              sliderProperties={selectedEffect?.compatibleProperties.includes(SLIDER_SENTINEL) ? extraSliderProperties : undefined}
              onSliderPropertyChange={setSelectedSliderProp}
              extendedChannels={selectedEffect?.category === 'colour' ? extendedChannels : undefined}
              stepTiming={stepTiming}
              onStepTimingChange={setStepTiming}
              speedMasterUuid={speedMasterUuid}
              onSpeedMasterChange={setSpeedMasterUuid}
              rateSpeedMasterUuid={rateSpeedMasterUuid}
              onRateSpeedMasterChange={setRateSpeedMasterUuid}
            />
          )}
        </SheetBody>

        {step === 'configure' && (
          <SheetFooter>
            <Button onClick={handleApply} className="w-full">
              {isEdit ? 'Update Effect' : 'Apply Effect'}
            </Button>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  )
}
