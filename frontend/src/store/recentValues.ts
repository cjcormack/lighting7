import { useCallback, useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import { lightingApi } from '@/api/lightingApi'
import type { RecentValuesState } from '@/api/recentValuesApi'

/**
 * The recent values (programmer-fixes plan D14–D18) — see `api/recentValuesApi.ts`. Read through
 * `useSyncExternalStore` over the WS layer's last frame rather than an RTK cache entry: the band is
 * mounted in the kit's editors, under hosts with no store of their own to provide.
 */

const NO_VALUES: readonly string[] = []

function subscribe(onChange: () => void): () => void {
  const subscription = lightingApi.recentValues.subscribe(onChange)
  return () => subscription.unsubscribe()
}

function snapshot(): RecentValuesState {
  return lightingApi.recentValues.get()
}

/**
 * Whether an editor of [projectId] may show and record the live project's lists: absent means
 * the host edits the live project. A frame not yet seen is not the project's.
 */
function isLive(state: RecentValuesState, projectId: number | undefined): boolean {
  return state.projectId != null && (projectId == null || projectId === state.projectId)
}

/** [key]'s list for the live project, newest first; empty for another project's editor. */
export function useRecentValues(key: string, projectId?: number): readonly string[] {
  const state = useSyncExternalStore(subscribe, snapshot, snapshot)
  return isLive(state, projectId) ? (state.lists[key] ?? NO_VALUES) : NO_VALUES
}

/** Put [value] at the front of [key]'s list, when [projectId] is the live project. */
export function recordRecentValue(key: string, value: string, projectId?: number): void {
  if (!isLive(snapshot(), projectId)) return
  lightingApi.recentValues.record(key, value)
}

/**
 * How long after a picker's release the recorder waits before it sends: react-colorful reports a
 * drag's last move from an effect, which can land after the window's release.
 */
export const RECENT_RELEASE_SETTLE_MS = 100

export interface RecentRecorder {
  /** A commit inside a gesture: held, so only the gesture's last is sent. */
  stage(value: string): void
  /** The gesture ended — a slider's commit, a field left: send what is staged now. */
  flush(): void
  /** A picker let go: send what is staged once its last report has landed. */
  release(): void
  /** A commit that is a gesture of its own — a tap on a recent: anything staged first, then this. */
  now(value: string): void
}

interface Staged {
  key: string
  projectId: number
  value: string
}

/**
 * The editor's recorder for [key] (D17): a gesture's end records, a frame does not. A staged value
 * keeps the key and the live project it was staged under, and is dropped if another project has
 * gone live before it is sent. Subscribes to nothing, so an editor that records re-renders for no
 * list change.
 */
export function useRecordRecent(key: string, projectId?: number): RecentRecorder {
  const target = useRef({ key, projectId })
  target.current = { key, projectId }
  const staged = useRef<Staged | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const flush = useCallback(() => {
    if (timer.current != null) clearTimeout(timer.current)
    timer.current = null
    const held = staged.current
    staged.current = null
    if (held != null && snapshot().projectId === held.projectId) lightingApi.recentValues.record(held.key, held.value)
  }, [])

  // The editor closing ends its gesture: Enter on a typed value, or a release still waiting.
  useEffect(() => flush, [flush])

  return useMemo(
    () => ({
      stage: (value) => {
        const state = snapshot()
        const live = isLive(state, target.current.projectId) ? state.projectId : null
        staged.current = live == null ? null : { key: target.current.key, projectId: live, value }
      },
      flush,
      release: () => {
        if (timer.current != null) clearTimeout(timer.current)
        timer.current = setTimeout(flush, RECENT_RELEASE_SETTLE_MS)
      },
      now: (value) => {
        flush()
        recordRecentValue(target.current.key, value, target.current.projectId)
      },
    }),
    [flush],
  )
}
