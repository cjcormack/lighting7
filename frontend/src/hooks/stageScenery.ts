import { createContext, useContext } from 'react'
import { NO_SCENERY, type LiveScenery } from '../api/sceneryApi'

/**
 * The scenery a stage canvas draws (stage-view plan session 8), beside its channel source: the
 * desk's live `scenery.state`, or — for the Next GO vis source — the scenery the GO would land.
 * Provided by `StageChannelSourceProvider` with the channel source it matches; empty elsewhere, so a
 * canvas outside a provider draws every element at its base.
 *
 * Read by `Stage3D` itself, outside its canvas, so the capture root's context bridge (which carries
 * only `ChannelSourceContext`) has nothing to carry for it.
 */
export const StageSceneryContext = createContext<LiveScenery>(NO_SCENERY)

export function useStageScenery(): LiveScenery {
  return useContext(StageSceneryContext)
}
