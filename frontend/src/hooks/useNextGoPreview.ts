import { useEffect, useMemo, useState } from 'react'
import { createPushChannelSource, type PushChannelSource } from '../api/channelSource'
import type { LiveScenery } from '../api/sceneryApi'
import { previewScenery } from '../lib/scenery'
import { useCurrentProjectQuery } from '../store/projects'
import {
  useProjectCueStackListQuery,
  useProjectProgramStateQuery,
  usePreviewCueLookQuery,
} from '../store/cueStacks'

/** What the next GO would fire, as far as this session can see. */
interface NextGoTarget {
  projectId: number | null
  stackId: number | null
  cueId: number | null
  /** How the cue is named in the prompt book — for the View menu, not for the request. */
  cueLabel: string | null
}

const NOTHING: NextGoTarget = { projectId: null, stackId: null, cueId: null, cueLabel: null }

/**
 * Which cue the Next GO preview is of.
 *
 * The **current** project, not the viewed one: the preview route sits behind `withCurrentProject`
 * and answers 409 for anything else. The stack is the project playhead — the stack the Runner's
 * GO actually fires — so a show that isn't running has nothing on deck.
 *
 * `nextCueId` is the server's `effectiveNextCueId` (armed standby, else the positional next). The
 * global `cueRunStateChanged` subscriber in `store/cueStacks.ts` patches it straight into this
 * query's cache, so reading it here *is* following the run state — no second socket listener, and
 * no re-read on a standby-only or connect-snapshot frame that left the next cue where it was.
 */
function useNextGoTarget(enabled: boolean): NextGoTarget {
  return useNextGoTargetState(enabled).target
}

/**
 * [useNextGoTarget] and whether its three reads have settled — so a caller that must not draw
 * until it knows (`render_view`'s offscreen render) can tell "no cue on deck" from "not read yet".
 */
function useNextGoTargetState(enabled: boolean): { target: NextGoTarget; settled: boolean } {
  const project = useCurrentProjectQuery(undefined, { skip: !enabled })
  const projectId = project.data?.id ?? null
  const skip = !enabled || projectId == null

  const programState = useProjectProgramStateQuery(projectId ?? 0, { skip })
  const stacks = useProjectCueStackListQuery(projectId ?? 0, { skip })

  const stackId = programState.data?.activeStackId ?? null
  const stackList = stacks.data

  const target = useMemo(() => {
    if (skip || projectId == null || stackId == null) return NOTHING
    const stack = stackList?.find((s) => s.id === stackId)
    const cueId = stack?.nextCueId ?? null
    if (cueId == null) return { projectId, stackId, cueId: null, cueLabel: null }
    const cue = stack?.cues.find((c) => c.id === cueId)
    const cueLabel = cue ? (cue.cueNumber ? `${cue.cueNumber} · ${cue.name}` : cue.name) : null
    return { projectId, stackId, cueId, cueLabel }
  }, [skip, projectId, stackId, stackList])

  const read = (q: { isSuccess: boolean; isError: boolean }) => q.isSuccess || q.isError
  const settled =
    !enabled ||
    (read(project) && (projectId == null || (read(programState) && (stackId == null || read(stacks)))))
  return { target, settled }
}

/**
 * The preview request itself, shared by the source and the status line.
 *
 * Both call this with the same argument, and both the Stage canvas and the globally-mounted
 * overview panel may be asking at once — RTK Query collapses all of them into a single in-flight
 * request and a single cache entry, so a hidden panel costs nothing and every reader sees the same
 * outcome. `refetchOnMountOrArgChange` makes re-selecting the source recompose rather than replay
 * a cached look — the operator's only handle on the cue-contents staleness limit documented in
 * `docs/stage-vis-engineering.md`.
 */
function usePreviewOfTarget(target: NextGoTarget) {
  const { projectId, stackId, cueId } = target
  const ready = projectId != null && stackId != null && cueId != null
  return usePreviewCueLookQuery(
    { projectId: projectId!, stackId: stackId!, cueId: cueId! },
    { skip: !ready, refetchOnMountOrArgChange: true },
  )
}

/** One line for the View menu, so plain output isn't mistaken for a preview. */
export function useNextGoStatus(enabled: boolean): string | null {
  const target = useNextGoTarget(enabled)
  const { isError, isSuccess } = usePreviewOfTarget(target)
  if (!enabled) return null
  if (target.cueId == null) return 'No cue on deck — showing output.'
  // Derived from the request, not from the target: a failed preview leaves the stage showing
  // plain output, and saying "Previewing cue 12" over it is the exact confusion this line exists
  // to prevent. There is no toast either — a 400 at the end of a stack is an ordinary state.
  if (isError) return 'Preview unavailable — showing output.'
  const label = target.cueLabel ?? `cue ${target.cueId}`
  return isSuccess ? `Previewing ${label}.` : `Composing ${label}…`
}

/**
 * A channel source holding the look the next GO would produce.
 *
 * Created in an effect rather than a `useMemo`, like `useProgrammerSource`: StrictMode
 * double-invokes render, and a source built during a discarded render would be handed values
 * nobody is subscribed to.
 *
 * Keyed on *which* cue is on deck, not on what that cue contains: editing the previewed cue's
 * assignments leaves the last composed look up until the desk moves the next cue on. See
 * `docs/stage-vis-engineering.md` §"The fourth source: Next GO" for why there is no cheap
 * revision to key on.
 *
 * Anything short of a successful preview empties the source rather than blacking the stage out.
 * The overlay above it dispatches on `holds`, so an empty source shows plain output through —
 * which is the right answer for "no show running", "end of a non-looping stack" (the backend
 * answers 400 there, an ordinary state) and a failed request alike.
 */
export function useNextGoSource(enabled: boolean): PushChannelSource | null {
  return useNextGoSourceState(enabled).source
}

/**
 * [useNextGoSource], and whether what it holds is final: the cue on deck is known and its preview
 * has answered (or there is none to ask for). The Stage view draws whatever the source holds as it
 * arrives; `render_view`'s offscreen render waits for this, so its one frame is the preview and not
 * the wire it falls back to while composing.
 */
export function useNextGoSourceState(enabled: boolean): {
  source: PushChannelSource | null
  settled: boolean
  /**
   * The scenery the GO would land, its moves starting when the preview arrived — so selecting Next
   * GO draws the tabs moving as the GO would move them (stage-view plan session 8). Null where the
   * preview has nothing to say (no cue on deck, a refused request): the stage shows live scenery.
   */
  scenery: LiveScenery | null
} {
  const { target, settled: targetSettled } = useNextGoTargetState(enabled)
  const [source, setSource] = useState<PushChannelSource | null>(null)
  const [scenery, setScenery] = useState<LiveScenery | null>(null)
  const { data, isError, isSuccess, isFetching } = usePreviewOfTarget(target)

  useEffect(() => {
    if (!enabled) return
    setSource(createPushChannelSource())
    return () => setSource(null)
  }, [enabled])

  const channels = target.cueId != null && !isError ? data?.channels : undefined

  useEffect(() => {
    // `undefined` covers every not-a-look case at once — no show running, no cue on deck, or a
    // rejected request. All of them empty the source, and the overlay above shows the wire
    // through. Note this is RTK Query's `data`, not `currentData`: it holds the last successful
    // response *across* an arg change, so moving to the next cue keeps the outgoing look on stage
    // until the incoming one composes rather than flashing back to live output in between.
    source?.setChannels(channels ?? [])
  }, [source, channels])

  // Anchored here, in an effect, because the moves start when the answer lands — a `useMemo` would
  // stamp the clock on whichever render happened to compute it. Keyed on the answer's `scenery`
  // alone, not the whole answer: a refetch that recomposes the channels (a programmer move, a cue
  // edit) is a new `data`, while RTK Query's structural sharing keeps an unchanged `scenery` the
  // same array — so the tabs do not snap back and replay a move that has not changed.
  const previewedScenery = target.cueId != null && !isError ? data?.scenery : undefined
  useEffect(() => {
    if (!enabled) return
    setScenery(previewedScenery != null ? previewScenery(previewedScenery, target.projectId, performance.now()) : null)
  }, [enabled, previewedScenery, target.projectId])

  const previewSettled = target.cueId == null || (!isFetching && (isSuccess || isError))
  return { source, settled: !enabled || (source != null && targetSettled && previewSettled), scenery: enabled ? scenery : null }
}
