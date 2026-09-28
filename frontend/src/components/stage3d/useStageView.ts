import { useCallback } from 'react'
import { usePersistentState } from '../../hooks/usePersistentState'

export interface StageViewFlags {
  regions: boolean
  riggings: boolean
  fixtures: boolean
  labels: boolean
  beamCones: boolean
}

export const DEFAULT_VIEW_FLAGS: StageViewFlags = {
  regions: true,
  riggings: true,
  fixtures: true,
  labels: true,
  beamCones: true,
}

export function useStageView() {
  // merge: a preference stored by an older build won't carry flags added since,
  // so the parse is spread over the defaults rather than trusted wholesale.
  const [flags, setFlags] = usePersistentState<StageViewFlags>(
    'stageViewFlags',
    DEFAULT_VIEW_FLAGS,
    { merge: true },
  )

  const setFlag = useCallback(
    <K extends keyof StageViewFlags>(key: K, value: boolean) => {
      setFlags((prev) => ({ ...prev, [key]: value }))
    },
    [setFlags],
  )

  return { flags, setFlag }
}
