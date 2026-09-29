import { useCallback, useMemo } from 'react'
import { usePersistentState } from '../../hooks/usePersistentState'
import { toStageLabelMode, type StageLabelMode } from './stageLabels'

export interface StageViewFlags {
  regions: boolean
  riggings: boolean
  fixtures: boolean
  /** Which labels the 3D layer draws; the 2D plot reads anything but `none` as "labels on". */
  labels: StageLabelMode
  beamCones: boolean
}

/** The flags that are plain on/off switches — everything but the label mode. */
export type StageViewToggle = Exclude<keyof StageViewFlags, 'labels'>

export const DEFAULT_VIEW_FLAGS: StageViewFlags = {
  regions: true,
  riggings: true,
  fixtures: true,
  labels: 'positions',
  beamCones: true,
}

export function useStageView() {
  // merge: a preference stored by an older build won't carry flags added since,
  // so the parse is spread over the defaults rather than trusted wholesale.
  const [stored, setFlags] = usePersistentState<StageViewFlags>(
    'stageViewFlags',
    DEFAULT_VIEW_FLAGS,
    { merge: true },
  )
  // `labels` was a boolean before the label layer, and a desk's storage still holds one.
  const flags = useMemo(
    () => ({ ...stored, labels: toStageLabelMode(stored.labels) }),
    [stored],
  )

  const setFlag = useCallback(
    (key: StageViewToggle, value: boolean) => {
      setFlags((prev) => ({ ...prev, [key]: value }))
    },
    [setFlags],
  )
  const setLabelMode = useCallback(
    (labels: StageLabelMode) => {
      setFlags((prev) => ({ ...prev, labels }))
    },
    [setFlags],
  )

  return { flags, setFlag, setLabelMode }
}
