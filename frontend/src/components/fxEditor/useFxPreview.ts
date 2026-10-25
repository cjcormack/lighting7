import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import type { UpdateFxRequest } from '@/api/fxApi'
import { lightingApi } from '@/api/lightingApi'
import { formatError } from '@/lib/formatError'
import {
  usePreviewFxMutation,
  type FxPreviewAnswer,
  type FxPreviewDraft,
  type FxPreviewEntry,
  type FxPreviewRequest,
  type FxPreviewWindowRequest,
} from '@/store/fxPreview'

/** The most FX one preview samples (`FxSampler.MAX_FX`); past it the desk refuses a `solo`. */
export const PREVIEW_MAX_FX = 8

export interface FxPreviewAsk {
  projectId: number
  /** The selection, in its order. */
  targets: readonly CueTarget[]
  /**
   * The FX to ask for, the picked one first: the window counts its cycles. Under `STACK` the desk
   * folds every running FX reaching the selection beside them, so the list needs to name only one —
   * and one named past the eighth is left out of the fold.
   */
  fxIds: readonly number[]
  /**
   * The inspector's unsent move on the first of [fxIds] — `{fxId, draft}`, the running FX with the
   * edit applied to a copy — so the graph follows a gesture before the 50 ms floor sends it.
   */
  draft?: UpdateFxRequest
  /**
   * FX not yet added — a palette's, a cue's — asked as `{draft}` after [fxIds], each built by the
   * add's own write boundary and never added. With no [fxIds] the first is the picked FX.
   */
  drafts?: readonly FxPreviewDraft[]
  /** `STACK`, the default: every running FX reaching the rows is folded too. `ONLY`: the FX asked for alone. */
  include?: 'STACK' | 'ONLY'
  /** Draw [drafts] on this many heads the rig does not have, with no [targets] (`include: ONLY`). */
  notional?: number
  /** Ask with no [targets]: the asked-for FX's own heads are the rows. */
  ownHeads?: boolean
  /** Every FX in the list: an `fxChanged` for any of them asks again. Absent, [fxIds]. */
  listed?: readonly number[]
  window?: FxPreviewWindowRequest
  /** An index into [fxIds]: its family's lane drawn as though it were alone over what is underneath (D14). */
  solo?: number
}

export interface FxPreviewState {
  answer: FxPreviewAnswer | null
  /** The desk's refusal of the latest ask, while the last answer stays drawn. */
  error: string | null
}

/**
 * **The FX editor's preview** (FX editor plan §3.3): `POST /fx/preview` over the selection with
 * the picked FX, `include: STACK` — the desk folds every FX reaching the selection beside it — and
 * the window and solo the graph chose. The strip's hosts (§4.6) ask the same way with drafts, over
 * an FX's own heads, or on notional heads. It is asked again — the latest ask wins, an answer to an
 * older one dropped — whenever what it drew may have moved:
 *
 * - the ask itself changes: the selection, the FX in the list, or the inspector's unsent move;
 * - `fxChanged` for an FX in the list — which is also how an `updateFx` this window sent is
 *   answered, and how a `programmer.moveFx` re-rank arrives;
 * - a tempo move on a master the answer's `clock` names;
 * - an entry or provenance move on a row's key for an attribute the answer drew — what is
 *   underneath changed.
 *
 * Moves arriving together coalesce into one ask a microtask later, and one ask is in flight at a
 * time with at most one waiting behind it. Nothing here decodes the samples: the graph does
 * (`fxPreviewDecode.ts`); the list reads the answer's apply order.
 */
export function useFxPreview(ask: FxPreviewAsk | null): FxPreviewState {
  const [preview] = usePreviewFxMutation()
  // Read through a ref: an ask is decided by the request alone, never by the trigger's identity.
  const previewRef = useRef(preview)
  previewRef.current = preview
  const [state, setState] = useState<FxPreviewState>({ answer: null, error: null })
  const key = askKey(ask)
  // What an answer must still match to be drawn: everything but the unsent move, whose answer is
  // newer than the one drawn even after the finger has moved on — the inspector's `draft`, and a
  // draft's settings, of which only what it is and where it runs must still match.
  const baseKey = askKey(
    ask == null
      ? null
      : { ...ask, draft: undefined, drafts: ask.drafts?.map((d) => ({ fxType: d.fxType, attribute: d.attribute, targets: d.targets })) },
  )

  const request = useMemo((): (FxPreviewRequest & { projectId: number }) | null => {
    if (ask == null) return null
    const fx: FxPreviewEntry[] = [
      ...ask.fxIds.map((fxId, i): FxPreviewEntry => (i === 0 && ask.draft != null ? { fxId, draft: ask.draft } : { fxId })),
      ...(ask.drafts ?? []).map((draft): FxPreviewEntry => ({ draft })),
    ].slice(0, PREVIEW_MAX_FX)
    const notional = ask.notional != null && ask.notional > 0 ? ask.notional : null
    if (fx.length === 0 || (notional == null && ask.targets.length === 0 && ask.ownHeads !== true)) return null
    return {
      projectId: ask.projectId,
      targets: notional != null ? [] : ask.targets.map((t) => ({ type: t.type, key: t.key })),
      fx,
      include: ask.include ?? 'STACK',
      ...(notional != null ? { notional } : {}),
      ...(ask.window != null ? { window: ask.window } : {}),
      ...(ask.solo != null && ask.solo >= 0 && ask.solo < fx.length ? { solo: ask.solo } : {}),
    }
    // Keyed by content — the listed FX included, so one joining the list asks again — and a
    // re-render that rebuilt the same arrays asks nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  // One ask in flight and at most one waiting behind it, which goes with whatever is asked by
  // then: a fader dragged under a drawn row moves its key on every frame, and the desk samples
  // the whole stack each time.
  const live = useRef({ request, key, baseKey, inFlight: false, trailing: false, alive: true })
  live.current.request = request
  live.current.key = key
  live.current.baseKey = baseKey

  const askNow = useCallback(() => {
    const now = live.current
    if (now.request == null) {
      setState({ answer: null, error: null })
      return
    }
    if (now.inFlight) {
      now.trailing = true
      return
    }
    now.inFlight = true
    const sentKey = now.baseKey
    const done = (apply: () => void) => {
      now.inFlight = false
      // An answer to another selection, list, window or solo is an older ask's: dropped. One to an
      // earlier move of the same drag is drawn — it is newer than what is — and the ask behind it
      // brings the latest.
      if (now.alive && sentKey === now.baseKey) apply()
      if (now.alive && now.trailing) {
        now.trailing = false
        askNow()
      }
    }
    previewRef
      .current(now.request)
      .unwrap()
      .then(
        (answer) => done(() => setState({ answer, error: null })),
        (e: unknown) => done(() => setState((prev) => ({ answer: prev.answer, error: formatError(e) }))),
      )
  }, [])

  useEffect(() => {
    askNow()
  }, [askNow, key])

  // An answer still on its way when the hook goes is nobody's.
  useEffect(() => {
    const now = live.current
    now.alive = true
    return () => {
      now.alive = false
    }
  }, [])

  const fxKey =
    request == null ? '' : [...new Set([...(ask?.listed ?? []), ...request.fx.flatMap((e) => (e.fxId != null ? [e.fxId] : []))])].join(',')
  const answer = state.answer
  const clockKey = (answer?.clock ?? []).flatMap((c) => (c.speedMasterUuid != null ? [c.speedMasterUuid] : [])).join(',')
  // Notional heads hold nothing on the desk, so nothing underneath them moves.
  const onRig = request?.notional == null
  const rowKeys = useMemo(() => (answer == null || !onRig ? [] : previewKeys(answer)), [answer, onRig])
  const rowKeysKey = rowKeys.map(([k, a]) => `${k}|${a}`).join('\u0000')

  const asking = request != null
  useEffect(() => {
    if (!asking) return
    let queued = false
    let live = true
    const askSoon = () => {
      if (queued) return
      queued = true
      queueMicrotask(() => {
        queued = false
        if (live) askNow()
      })
    }
    const inList = new Set(fxKey === '' ? [] : fxKey.split(',').map(Number))
    // A removal changes the list itself, which asks again once the running list has dropped it.
    const changes = lightingApi.fx.subscribeToChanges((change) => {
      if (change.fxId != null && inList.has(change.fxId) && change.changeType !== 'removed') askSoon()
    })
    const masters = new Set(clockKey === '' ? [] : clockKey.split(','))
    const bpm = new Map<string, number>()
    for (const m of lightingApi.speedMasters.getState()) if (m.uuid != null) bpm.set(m.uuid, m.bpm)
    const tempo = lightingApi.speedMasters.subscribe((bank) => {
      let moved = false
      for (const m of bank) {
        if (m.uuid == null || !masters.has(m.uuid)) continue
        if (bpm.get(m.uuid) !== m.bpm) moved = true
        bpm.set(m.uuid, m.bpm)
      }
      if (moved) askSoon()
    })
    const keys =
      rowKeysKey === ''
        ? []
        : rowKeysKey.split('\u0000').map((pair) => {
            const at = pair.lastIndexOf('|')
            return lightingApi.programmer.subscribeToKey(pair.slice(0, at), pair.slice(at + 1), askSoon)
          })
    return () => {
      live = false
      changes.unsubscribe()
      tempo.unsubscribe()
      keys.forEach((s) => s.unsubscribe())
    }
  }, [askNow, asking, clockKey, fxKey, rowKeysKey])

  return request == null ? { answer: null, error: null } : state
}

function askKey(ask: FxPreviewAsk | null): string {
  if (ask == null) return ''
  const listed = [...(ask.listed ?? [])].sort((a, b) => a - b)
  return JSON.stringify([
    ask.projectId,
    ask.targets.map((t) => [t.type, t.key]),
    ask.fxIds,
    listed,
    ask.window ?? null,
    ask.solo ?? null,
    ask.draft ?? null,
    ask.drafts ?? null,
    ask.include ?? 'STACK',
    ask.notional ?? null,
    ask.ownHeads === true,
  ])
}

/** Every (row key, attribute) the answer drew: what is underneath each of them is a reason to ask again. */
function previewKeys(answer: FxPreviewAnswer): [string, string][] {
  const out: [string, string][] = []
  for (const lane of answer.lanes) {
    for (const attribute of lane.attributes) {
      for (const row of attribute.rows) {
        const key = answer.rows[row.row]?.key
        if (key != null) out.push([key, attribute.attribute])
      }
    }
  }
  return out
}
