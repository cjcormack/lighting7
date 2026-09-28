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
import { EffectParameterForm } from '@/components/fx/EffectParameterForm'
import {
  useEffectLibraryQuery,
  useUpdateFxMutation,
  useRemoveFxMutation,
  usePauseFxMutation,
  useResumeFxMutation,
} from '@/store/fixtureFx'
import {
  useUpdateGroupFxMutation,
  useRemoveGroupFxMutation,
  usePauseGroupFxMutation,
  useResumeGroupFxMutation,
} from '@/store/groups'
import { useFixtureListQuery } from '@/store/fixtures'
import { detectExtendedChannels } from '@/components/fx/colourUtils'
import { groupMemberFixtures } from '@/lib/fxTargetProperties'
import type { ActiveEffectContext } from './buskingTypes'
import { normalizeEffectName } from './buskingTypes'
import type { ElementMode } from '@/api/groupsApi'

interface ActiveEffectSheetProps {
  context: ActiveEffectContext | null
  onClose: () => void
}

export function ActiveEffectSheet({ context, onClose }: ActiveEffectSheetProps) {
  const isOpen = context !== null
  const { data: library } = useEffectLibraryQuery(undefined, { skip: !isOpen })
  const { data: fixtureList } = useFixtureListQuery()

  const [updateFx] = useUpdateFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [updateGroupFx] = useUpdateGroupFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()

  const [beatDivision, setBeatDivision] = useState(1.0)
  const [blendMode, setBlendMode] = useState('OVERRIDE')
  const [phaseOffset, setPhaseOffset] = useState(0)
  const [parameters, setParameters] = useState<Record<string, string>>({})
  const [distributionStrategy, setDistributionStrategy] = useState('LINEAR')
  const [stepTiming, setStepTiming] = useState(false)
  const [speedMasterUuid, setSpeedMasterUuid] = useState<string | null>(null)
  const [rateSpeedMasterUuid, setRateSpeedMasterUuid] = useState<string | null>(null)
  const [elementMode, setElementMode] = useState<ElementMode>('PER_FIXTURE')

  const selectedEffect = useMemo(() => {
    if (!context || !library) return null
    const effectType = context.effect.effectType
    const normalized = normalizeEffectName(effectType)
    return library.find((e) => normalizeEffectName(e.name) === normalized) ?? null
  }, [context, library])

  const targetPropertyName = useMemo(() => {
    return context?.effect.propertyName ?? null
  }, [context])

  // Detect multi-head / multi-element for distribution options
  const showDistribution = useMemo(() => {
    if (!context) return false
    if (context.type === 'group') return true
    const fixture = context.type === 'fixture'
      ? fixtureList?.find((f) => f.key === context.fixtureKey)
      : null
    return (fixture?.elementGroupProperties?.length ?? 0) > 0
  }, [context, fixtureList])

  const showElementMode = useMemo(() => {
    if (!context || context.type !== 'group') return false
    if (!fixtureList) return false
    const groupName = context.groupName
    const members = groupMemberFixtures(fixtureList, groupName)
    return members.some((f) => f.elements && f.elements.length > 1)
  }, [context, fixtureList])

  // Extended colour channels (W/A/UV) available on the target
  const extendedChannels = useMemo(() => {
    if (!context || !fixtureList) return undefined
    const fixtures =
      context.type === 'fixture'
        ? fixtureList.filter((f) => f.key === context.fixtureKey)
        : groupMemberFixtures(fixtureList, context.groupName)
    return detectExtendedChannels(fixtures.map((f) => f.properties ?? []))
  }, [context, fixtureList])

  // Load state from context when it changes
  useEffect(() => {
    if (!context) return
    const effect = context.effect
    setBeatDivision(effect.beatDivision)
    setBlendMode(effect.blendMode)
    setPhaseOffset(effect.phaseOffset)
    setParameters({ ...effect.parameters })
    setStepTiming(context.effect.stepTiming ?? false)
    setSpeedMasterUuid(context.effect.speedMasterUuid ?? null)
    setRateSpeedMasterUuid(context.effect.rateSpeedMasterUuid ?? null)
    if (context.type === 'group') {
      setDistributionStrategy(context.effect.distribution)
      setElementMode(context.effect.elementMode ?? 'PER_FIXTURE')
    } else {
      setDistributionStrategy(context.effect.distributionStrategy ?? 'LINEAR')
    }
  }, [context])

  const handleUpdate = async () => {
    if (!context || !selectedEffect) return

    const baseBody = {
      effectType: selectedEffect.name,
      beatDivision,
      blendMode,
      phaseOffset,
      parameters,
      ...(speedMasterUuid != null ? { speedMasterUuid } : {}),
      ...(rateSpeedMasterUuid != null ? { rateSpeedMasterUuid } : {}),
    }

    if (context.type === 'group') {
      await updateGroupFx({
        id: context.effect.id,
        groupName: context.groupName,
        body: {
          ...baseBody,
          distributionStrategy,
          stepTiming,
          ...(showElementMode ? { elementMode } : {}),
        },
      })
    } else {
      await updateFx({
        id: context.effect.id,
        fixtureKey: context.fixtureKey,
        body: {
          ...baseBody,
          stepTiming,
          ...(showDistribution ? { distributionStrategy } : {}),
        },
      })
    }
    onClose()
  }

  const handleRemove = async () => {
    if (!context) return
    if (context.type === 'group') {
      await removeGroupFx({ id: context.effect.id, groupName: context.groupName })
    } else {
      await removeFx({ id: context.effect.id, fixtureKey: context.fixtureKey })
    }
    onClose()
  }

  const handleTogglePause = async () => {
    if (!context) return
    const isRunning = context.effect.isRunning
    if (context.type === 'group') {
      if (isRunning) {
        await pauseGroupFx({ id: context.effect.id, groupName: context.groupName })
      } else {
        await resumeGroupFx({ id: context.effect.id, groupName: context.groupName })
      }
    } else {
      if (isRunning) {
        await pauseFx({ id: context.effect.id, fixtureKey: context.fixtureKey })
      } else {
        await resumeFx({ id: context.effect.id, fixtureKey: context.fixtureKey })
      }
    }
  }

  const targetLabel = context
    ? context.type === 'group'
      ? context.groupName
      : context.fixtureKey
    : ''

  return (
    <Sheet open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Edit Effect</SheetTitle>
          <SheetDescription>{targetLabel}</SheetDescription>
        </SheetHeader>

        <SheetBody className="space-y-0 p-0">
          {selectedEffect && (
            <EffectParameterForm
              effect={selectedEffect}
              beatDivision={beatDivision}
              onBeatDivisionChange={setBeatDivision}
              blendMode={blendMode}
              onBlendModeChange={setBlendMode}
              phaseOffset={phaseOffset}
              onPhaseOffsetChange={setPhaseOffset}
              startOnBeat={false}
              onStartOnBeatChange={() => {}}
              parameters={parameters}
              onParametersChange={setParameters}
              targetPropertyName={targetPropertyName}
              isEdit={true}
              distributionStrategy={distributionStrategy}
              onDistributionStrategyChange={setDistributionStrategy}
              showDistribution={showDistribution}
              showStartOnBeat={false}
              elementMode={elementMode}
              onElementModeChange={(v) => setElementMode(v as ElementMode)}
              showElementMode={showElementMode}
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

        <SheetFooter className="flex-row gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handleTogglePause}
            className="flex-1"
          >
            {context?.effect.isRunning ? 'Pause' : 'Resume'}
          </Button>
          <Button
            variant="destructive"
            size="sm"
            onClick={handleRemove}
            className="flex-1"
          >
            Remove
          </Button>
          <Button onClick={handleUpdate} className="flex-1">
            Update
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
