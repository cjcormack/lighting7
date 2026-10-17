import { useEffect, useMemo, useRef, useState } from 'react'
import { lightingApi } from '../api/lightingApi'
import { createPushChannelSource, type PushChannelSource } from '../api/channelSource'
import type { LiveScenery } from '../api/sceneryApi'
import { previewScenery } from '../lib/scenery'
import { useCurrentProjectQuery } from '../store/projects'
import { useProjectCueListQuery } from '../store/cues'
import { useProjectCueStackListQuery, usePreviewCueLookQuery, usePreviewLooseCueQuery } from '../store/cueStacks'
import { useEditTarget, type EditTarget } from './useVisSource'

/**
 * The `edit` vis source (show-cue-editing plan §3.7, D13): the cue a cue editor's *Show on stage*
 * pointed this window at, composed by the desk's own preview and drawn — never output.
 *
 * - **An entry** is previewed in its stack's context (`GET cue-stacks/{sid}/preview?entryId=`), as a
 *   GO onto it would land — so Q7 draws as Q7 while Q1 is live in the same stack. Its stack is found
 *   in the current project's stack list: a window carries only the entry id.
 * - **A cue with no entry** is previewed as a loose press (`GET cues/{id}/preview`).
 *
 * Both are the **current** project's, the routes being `withCurrentProject`; a target the show on
 * the rig does not hold draws plain output. Layer 4 and scenery, as Next GO draws them; effects are
 * not drawn (the preview composes no effect).
 *
 * **It refetches after an edit**, which Next GO does not: the editor saves as it goes, so the stored
 * cue is the edit, at most one flush behind. Every `cueListChanged` (it names no cue) and every
 * `cuesRecomposed` naming the cue (a Look or palette under it changed) asks again, debounced at
 * [EDIT_REFETCH_DEBOUNCE_MS] so a flush's frames ask once.
 */
export const EDIT_REFETCH_DEBOUNCE_MS = 300

interface EditCueResolved {
  projectId: number | null
  /** The stack an entry target sits in; null for a cue target, or an entry the list lacks. */
  stackId: number | null
  entryId: number | null
  cueId: number | null
  /** *Q7 · Storm*, or the cue's name; null until known. */
  label: string | null
  /** The target names nothing in the show on the rig. */
  missing: boolean
  /** A read the target is resolved through failed — not the same as missing. */
  failed: boolean
  settled: boolean
}

const NONE: EditCueResolved = {
  projectId: null,
  stackId: null,
  entryId: null,
  cueId: null,
  label: null,
  missing: false,
  failed: false,
  settled: true,
}

function useEditCueResolved(enabled: boolean, target: EditTarget | null): EditCueResolved {
  const active = enabled && target != null
  const project = useCurrentProjectQuery(undefined, { skip: !active })
  const projectId = project.data?.id ?? null
  const stacks = useProjectCueStackListQuery(projectId ?? 0, { skip: !active || projectId == null || target?.kind !== 'entry' })
  const cues = useProjectCueListQuery(projectId ?? 0, { skip: !active || projectId == null || target?.kind !== 'cue' })

  return useMemo(() => {
    if (!active || target == null) return NONE
    // Settled once answered either way; missing only on an answer that lacks it, failed on none.
    const answered = (q: { isSuccess: boolean; isError: boolean }) => ({
      settled: q.isSuccess || q.isError,
      missing: q.isSuccess,
      failed: q.isError,
    })
    if (projectId == null) return { ...NONE, ...answered(project) }
    if (target.kind === 'entry') {
      if (stacks.data == null) return { ...NONE, projectId, ...answered(stacks), missing: false }
      for (const stack of stacks.data) {
        const cue = stack.cues.find((c) => c.entryId === target.entryId)
        if (cue != null) {
          const label = cue.cueNumber ? `Q${cue.cueNumber} · ${cue.name}` : cue.name
          return { ...NONE, projectId, stackId: stack.id, entryId: target.entryId, cueId: cue.id, label }
        }
      }
      return { ...NONE, projectId, missing: true }
    }
    if (cues.data == null) return { ...NONE, projectId, ...answered(cues), missing: false }
    const cue = cues.data.find((c) => c.id === target.cueId)
    if (cue == null) return { ...NONE, projectId, missing: true }
    return { ...NONE, projectId, cueId: cue.id, label: cue.name }
  }, [active, target, projectId, project, stacks, cues])
}

/** The preview of whichever kind the target is, and a way to ask it again. */
function useEditCuePreviewQuery(resolved: EditCueResolved) {
  const { projectId, stackId, entryId, cueId } = resolved
  const entryReady = projectId != null && stackId != null && entryId != null
  const looseReady = projectId != null && stackId == null && entryId == null && cueId != null
  const entry = usePreviewCueLookQuery(
    { projectId: projectId!, stackId: stackId!, entryId: entryId! },
    { skip: !entryReady, refetchOnMountOrArgChange: true },
  )
  const loose = usePreviewLooseCueQuery(
    { projectId: projectId!, cueId: cueId! },
    { skip: !looseReady, refetchOnMountOrArgChange: true },
  )
  const query = entryReady ? entry : loose
  const ready = entryReady || looseReady
  return { ...query, ready }
}

/**
 * Ask [refetch] again, once, [EDIT_REFETCH_DEBOUNCE_MS] after the last `cueListChanged` or
 * `cuesRecomposed` naming [cueId] — while [active]. A timer that fires while the preview is
 * [fetching] waits for it to settle and asks then: RTK Query drops a refetch of a key whose request
 * is pending, and that request may have read the cue before the edit that asked.
 */
function useRefetchOnCueEdits(active: boolean, cueId: number | null, fetching: boolean, refetch: () => void) {
  const refetchRef = useRef(refetch)
  const cueIdRef = useRef(cueId)
  const fetchingRef = useRef(fetching)
  const owed = useRef(false)
  useEffect(() => {
    refetchRef.current = refetch
    cueIdRef.current = cueId
    fetchingRef.current = fetching
  })
  useEffect(() => {
    if (!active) return
    let timer: ReturnType<typeof setTimeout> | null = null
    const schedule = () => {
      if (timer != null) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        if (fetchingRef.current) owed.current = true
        else refetchRef.current()
      }, EDIT_REFETCH_DEBOUNCE_MS)
    }
    const listChanged = lightingApi.cues.subscribe(schedule)
    const recomposed = lightingApi.cues.subscribeRecomposed((cueIds) => {
      if (cueIdRef.current != null && cueIds.includes(cueIdRef.current)) schedule()
    })
    return () => {
      listChanged.unsubscribe()
      recomposed.unsubscribe()
      if (timer != null) clearTimeout(timer)
      owed.current = false
    }
  }, [active])
  useEffect(() => {
    if (!active || fetching || !owed.current) return
    owed.current = false
    refetchRef.current()
  }, [active, fetching])
}

/** One line for the View menu, so plain output is not mistaken for the cue in edit. */
export function useEditCueStatus(enabled: boolean): string | null {
  const target = useEditTarget()
  const resolved = useEditCueResolved(enabled, target)
  const { isError, isSuccess } = useEditCuePreviewQuery(resolved)
  if (!enabled) return null
  if (target == null) return 'No cue in edit — showing output.'
  if (resolved.failed) return 'Preview unavailable — showing output.'
  if (resolved.missing) return 'The cue in edit isn’t in the show on the rig — showing output.'
  if (!resolved.settled || resolved.label == null) return 'Composing the cue in edit…'
  if (isError) return 'Preview unavailable — showing output.'
  return isSuccess ? `Showing ${resolved.label} as stored — nothing is sent.` : `Composing ${resolved.label}…`
}

/**
 * The channels and scenery of the cue in edit, for `StageChannelSourceProvider`: a push source the
 * preview fills (empty — the wire shows through the overlay above — while there is nothing to draw),
 * and the scenery as the preview lands it, its moves starting when the answer arrived.
 */
export function useEditCueSourceState(enabled: boolean): {
  source: PushChannelSource | null
  settled: boolean
  scenery: LiveScenery | null
} {
  const target = useEditTarget()
  const resolved = useEditCueResolved(enabled, target)
  const { data, isError, isSuccess, isFetching, ready, refetch } = useEditCuePreviewQuery(resolved)
  const [source, setSource] = useState<PushChannelSource | null>(null)
  const [scenery, setScenery] = useState<LiveScenery | null>(null)

  useRefetchOnCueEdits(enabled && ready, resolved.cueId, isFetching, () => {
    if (ready) refetch()
  })

  useEffect(() => {
    if (!enabled) return
    setSource(createPushChannelSource())
    return () => setSource(null)
  }, [enabled])

  const channels = ready && !isError ? data?.channels : undefined
  useEffect(() => {
    source?.setChannels(channels ?? [])
  }, [source, channels])

  // Keyed on the answer's `scenery` alone, as Next GO's is: a refetch that recomposes only the
  // channels keeps the same array under RTK Query's structural sharing, so the tabs do not replay.
  const previewed = ready && !isError ? data?.scenery : undefined
  useEffect(() => {
    if (!enabled) return
    setScenery(previewed != null ? previewScenery(previewed, resolved.projectId, performance.now()) : null)
  }, [enabled, previewed, resolved.projectId])

  const previewSettled = !ready || (!isFetching && (isSuccess || isError))
  return {
    source,
    settled: !enabled || (source != null && resolved.settled && previewSettled),
    scenery: enabled ? scenery : null,
  }
}
