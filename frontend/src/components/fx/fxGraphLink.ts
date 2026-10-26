import { createContext, useContext, useSyncExternalStore } from 'react'
import type { UpdateFxRequest } from '@/api/fxApi'
import type { ActiveFx } from '@/store/fixtureFx'
import type { FxPreviewFx, FxPreviewRow } from '@/store/fxPreview'
import type { FxDraft } from './fxEditorModel'

/**
 * **What ties the FX editor's inspector to its graph** (FX editor plan D9, D12, D13, §4.5): the
 * FX editor provides it around its columns — not the sheets it opens — so the live editor in the
 * inspector lights the graph's marks,
 * draws the offset ladder from the desk's answer, offers *Same as…* over the list's other FX, and
 * lends its draft and its write to the graph's handles. Every other host of the live editor has no
 * graph and provides nothing, so none of this is drawn there.
 */

/** A mark on the graph a control changes, lit while the pointer rests on it (§4.5). */
export type GraphMark = 'cycle' | 'starts' | 'mirror' | 'brackets' | 'holding' | 'amount' | 'levels'

/** The editor open in the inspector, lent to the graph's handles: they write through it, as its own fields do. */
export interface LinkedEditor {
  fxId: number
  draft: FxDraft
  /** An Intensity FX with a `min` and a `max`: the graph draws their lines as handles. */
  levels: boolean
  /** A wall-clock FX, whose cycle is seconds whatever its master. */
  wallClock: boolean
  readOnly: boolean
  /** A move (`release` false), held by the floor, or the release, which lands. */
  set: (patch: Partial<FxDraft>, release: boolean) => void
  setParam: (name: string, value: string, release: boolean) => void
}

/**
 * The row the graph's pointer rests on, by key: a store rather than state, so a pointer crossing
 * the rows redraws the ladder's dots and nothing else.
 */
export interface HoveredRowStore {
  get: () => string | null
  set: (key: string | null) => void
  subscribe: (onChange: () => void) => () => void
}

export function createHoveredRowStore(): HoveredRowStore {
  let key: string | null = null
  const listeners = new Set<() => void>()
  return {
    get: () => key,
    set: (next) => {
      if (next === key) return
      key = next
      listeners.forEach((fn) => fn())
    },
    subscribe: (fn) => {
      listeners.add(fn)
      return () => listeners.delete(fn)
    },
  }
}

const NO_ROW: HoveredRowStore = createHoveredRowStore()

export function useHoveredRow(store: HoveredRowStore | null): string | null {
  const from = store ?? NO_ROW
  return useSyncExternalStore(from.subscribe, from.get, from.get)
}

/** Another FX in the list, for *Same as…* (D13): its name, and whether its library entry runs on the wall clock. */
export interface MatchSource {
  effect: ActiveFx
  name: string
  wallClock: boolean
}

export interface FxGraphLink {
  /** The picked FX as the desk's preview answered it, and the answer's rows: what the ladder draws. */
  ladder: { fx: FxPreviewFx; rows: readonly FxPreviewRow[] } | null
  /** The row the graph's pointer rests on, which lights its dots. */
  hoveredRow: HoveredRowStore
  /** A dot the pointer rests on, linking it to its graph row. */
  setLadderRow: (key: string | null) => void
  setHighlight: (mark: GraphMark | null) => void
  others: readonly MatchSource[]
  /** Lend the handles an editor; [detach] takes back only the one [fxId] lent. */
  attach: (editor: LinkedEditor) => void
  detach: (fxId: number) => void
  /** The editor's unsent move on [fxId], so the preview follows a gesture before the floor sends it; null once the desk has it. */
  preview: (fxId: number, request: UpdateFxRequest | null) => void
}

export const FxGraphLinkContext = createContext<FxGraphLink | null>(null)

export function useFxGraphLink(): FxGraphLink | null {
  return useContext(FxGraphLinkContext)
}
