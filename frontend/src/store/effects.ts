import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { DISARMED, type EffectsArmState } from '../api/effectsApi'
import type { CueEvent, CueEventWriteItem } from '../api/cuesApi'

/**
 * One-shot effects over REST (stage-view plan session 9, P2) — see `api/effectsApi.ts`.
 *
 * Arm, fire and reload are the **current project's** and are REST, never socket frames, so the
 * socket gains no operation. Each answers the arm as `effects.armed` carries it, and the frame
 * that follows on every window is what the chip and the panels draw: a mutation's answer is only
 * there for the caller's error handling. On the desk's public listener they are refused unless an
 * admin has allowed arming and firing (`REMOTE_EFFECTS_DISABLED`).
 *
 * A cue's events are a **whole-list write** (`PUT cues/{id}/events`), every problem at once; the
 * cue's own read carries them, so a write invalidates the cue entries.
 *
 * **The live arm is form 3** (CLAUDE.md §"Where a WS bridge subscribes"): `effects.armed` is a
 * stream with nothing to refetch, so it lives in one cache entry fed by the WS layer, seeded from
 * its last frame.
 */
export interface EffectsStateResponse {
  armed: boolean
  armedUntil?: string | null
  remainingMs?: number | null
  rehearsal?: boolean
}

export interface FireResponse {
  fired: { fixture: string; fixtureName: string; trigger: string; label: string; at: string; rehearsed: boolean }
}

const effectsApiSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    armEffects: build.mutation<EffectsStateResponse, { projectId: number; on: boolean; seconds?: number }>({
      query: ({ projectId, on, seconds }) => ({
        url: `projects/${projectId}/effects/arm`,
        method: 'POST',
        body: seconds != null ? { on, seconds } : { on },
      }),
    }),
    fireTrigger: build.mutation<FireResponse, { projectId: number; patchId: number; trigger: string; rehearse?: boolean }>({
      query: ({ projectId, patchId, trigger, rehearse }) => ({
        url: `projects/${projectId}/patches/${patchId}/fire`,
        method: 'POST',
        body: rehearse ? { trigger, rehearse: true } : { trigger },
      }),
    }),
    reloadTrigger: build.mutation<EffectsStateResponse, { projectId: number; patchId: number; trigger?: string }>({
      query: ({ projectId, patchId, trigger }) => ({
        url: `projects/${projectId}/patches/${patchId}/reload`,
        method: 'POST',
        body: trigger != null ? { trigger } : {},
      }),
    }),
    setCueEvents: build.mutation<CueEvent[], { projectId: number; cueId: number; events: CueEventWriteItem[] }>({
      query: ({ projectId, cueId, events }) => ({
        url: `projects/${projectId}/cues/${cueId}/events`,
        method: 'PUT',
        body: { events },
      }),
      invalidatesTags: (_result, error, { projectId }) => (error ? [] : ['Cue', { type: 'CueList', id: projectId }]),
    }),
    effectsArm: build.query<EffectsArmState, void>({
      queryFn: () => ({ data: lightingApi.effects.getArmed() }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.effects.subscribeArmed((state) => {
          updateCachedData(() => state)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const {
  useArmEffectsMutation,
  useFireTriggerMutation,
  useReloadTriggerMutation,
  useSetCueEventsMutation,
  useEffectsArmQuery,
} = effectsApiSlice

/** The desk's arm and spent tubes, as the desk last said; disarmed before the first frame. */
export function useEffectsArm(): EffectsArmState {
  const { data } = useEffectsArmQuery()
  return data ?? DISARMED
}
