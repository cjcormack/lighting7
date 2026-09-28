import { useSyncExternalStore } from 'react'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { CueTarget } from '../api/cuesApi'
import type {
  IncludedTarget,
  ProgrammerAppliedSource,
  ProgrammerLayer,
  ProgrammerTargetType,
} from '../api/programmerWsApi'

export type {
  AppliedExtent,
  AppliedTarget,
  IncludedTarget,
  ProgrammerAppliedSource,
  ProgrammerEntry,
  ProgrammerKeyState,
  ProgrammerLayer,
  ProgrammerState,
  ProgrammerTargetType,
  ProvenanceEntry,
  ProvenanceSource,
} from '../api/programmerWsApi'
export { programmerKey } from '../api/programmerWsApi'

/**
 * Coarse programmer state for the always-visible indicator and the toolbar.
 *
 * Deliberately *just* the counters and the blind flag rather than the whole entry map:
 * this cache entry re-renders every consumer on each change, and the sheet's cells read
 * their own state through `lightingApi.programmer.subscribeToKey` instead. Mirrors how
 * `channels.ts` keeps per-channel queries separate from the whole-map subscription.
 */
export interface ProgrammerSummary {
  blind: boolean
  entryCount: number
  /** What Include last loaded — drives the Update button's target and label. */
  lastIncluded: IncludedTarget | null
}

function currentSummary(): ProgrammerSummary {
  const state = lightingApi.programmer.getState()
  return {
    blind: state.blind,
    entryCount: state.entries.size,
    lastIncluded: state.lastIncluded,
  }
}

export const programmerApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    programmerSummary: build.query<ProgrammerSummary, void>({
      queryFn: () => ({ data: currentSummary() }),
      async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
        const subscription = lightingApi.programmer.subscribe((state) => {
          updateCachedData((draft) => {
            // Assign fields rather than replacing the object: RTK Query's Immer draft
            // only notices structural writes, and this keeps the identity stable when
            // neither counter moved (a provenance-only push).
            draft.blind = state.blind
            draft.entryCount = state.entries.size
            draft.lastIncluded = state.lastIncluded
          })
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useProgrammerSummaryQuery } = programmerApi

/**
 * The programmer's Look-layer stack.
 *
 * A **separate** cache entry from [ProgrammerSummary] rather than a field on it, and the reason is
 * the summary's own: it is read by the always-visible `ProgrammerIndicator`, so folding the stack in
 * would re-render that on every layer event. This entry is subscribed only by the surfaces that
 * draw layers — the Program pane's Layers tab and the busking pads' active ring.
 */
export const programmerLayersApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    programmerLayers: build.query<ProgrammerLayer[], void>({
      queryFn: () => ({ data: [...lightingApi.programmer.layers()] }),
      async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
        // `subscribe` fires on every provenance push too, so compare before writing: an
        // untouched stack must not wake the pads on each 50 Hz-adjacent layer event.
        let seen = lightingApi.programmer.layers()
        let signature = layerSignature(seen)
        const subscription = lightingApi.programmer.subscribe((state) => {
          // The WS api only reassigns `layers` on a frame that carries them, so the dominant
          // caller here — a provenance push — hands back the identical array and settles for
          // free. The stringify stays as the backstop for the frames that do rebuild it (a
          // `programmer.state` snapshot allocates a new array whatever its contents), because
          // the whole-object compare is the part that must not drift into a field list.
          if (state.layers === seen) return
          seen = state.layers
          const next = layerSignature(state.layers)
          if (next === signature) return
          signature = next
          updateCachedData(() => [...state.layers])
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

function layerSignature(layers: readonly ProgrammerLayer[]): string {
  return JSON.stringify(layers)
}

export const { useProgrammerLayersQuery } = programmerLayersApi

/**
 * The layer stack **resolved**: which Looks and templates are applied to which targets.
 *
 * A third cache entry rather than a field on the layer stack, by the same argument that split the
 * stack off the summary — the two are read by different surfaces. The Layers pane edits layers and
 * never asks about coverage; the busk pads ask only about coverage and never draw a layer. Folding
 * them together would wake each on the other's frames, and they arrive in the same frame anyway.
 *
 * The resolution itself is entirely the desk's (`ProgrammerLayerStack.appliedState`): group
 * expansion, coverage and the `all`/`some` extent all arrive decided. See `lookPresence.ts` for
 * the one thing left to do with it — folding a multi-target selection into one ring.
 */
export const programmerAppliedApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    programmerApplied: build.query<ProgrammerAppliedSource[], void>({
      queryFn: () => ({ data: [...lightingApi.programmer.applied()] }),
      async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
        // Same guard as the layer stack's, and needed for the same reason: `subscribe` fires on
        // every provenance push, and an untouched stack must not wake the pads 20×/s through a
        // fade. The identity check settles those for free — only a frame carrying layers
        // reassigns this — and the stringify catches a connect snapshot that rebuilt an
        // equal list.
        let seen = lightingApi.programmer.applied()
        let signature = JSON.stringify(seen)
        const subscription = lightingApi.programmer.subscribe((state) => {
          if (state.applied === seen) return
          seen = state.applied
          const next = JSON.stringify(state.applied)
          if (next === signature) return
          signature = next
          updateCachedData(() => [...state.applied])
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const { useProgrammerAppliedQuery } = programmerAppliedApi

/**
 * Re-render on *any* programmer change, including a value edit that leaves the entry count
 * untouched. [useProgrammerSummaryQuery] deliberately only tracks the counters, so a view
 * that reads entry *values* through `lightingApi.programmer.getKeyState` needs this to know
 * when to look again.
 *
 * A blunt instrument by design — it is for small surfaces like the busking pad. The
 * programmer sheet subscribes per (target, property) instead, because waking every cell on
 * every layer event is exactly what that split exists to avoid.
 */
export function useProgrammerRevision(): number {
  return useSyncExternalStore(subscribeToProgrammer, getProgrammerRevision)
}

let revision = 0
const revisionListeners = new Set<() => void>()
let revisionSubscription: { unsubscribe: () => void } | null = null

function subscribeToProgrammer(onStoreChange: () => void): () => void {
  revisionListeners.add(onStoreChange)
  revisionSubscription ??= lightingApi.programmer.subscribe(() => {
    revision += 1
    revisionListeners.forEach((fn) => fn())
  })
  return () => {
    revisionListeners.delete(onStoreChange)
    if (revisionListeners.size === 0) {
      revisionSubscription?.unsubscribe()
      revisionSubscription = null
    }
  }
}

function getProgrammerRevision(): number {
  return revision
}

// ── Imperative writers ──────────────────────────────────────────────────────
// Plain functions, not mutations: these are fire-and-forget WS ops on the busking hot path
// (a slider drag emits them at input rate), and an RTK Query mutation per frame would both
// churn the cache and light up the save-status indicator. Same rationale as `fx.ts`.

export function programmerSet(
  targetType: ProgrammerTargetType,
  targetKey: string,
  propertyName: string,
  value: string,
  fadeMs?: number,
) {
  lightingApi.programmer.set(targetType, targetKey, propertyName, value, fadeMs)
}

/** Releases **every** owner on the property — locate, presets and surface faders included. */
export function programmerClearEntry(
  targetType: ProgrammerTargetType,
  targetKey: string,
  propertyName: string,
  fadeMs?: number,
) {
  lightingApi.programmer.clearEntry(targetType, targetKey, propertyName, fadeMs)
}

export function programmerClearAll(fadeMs?: number) {
  lightingApi.programmer.clearAll(fadeMs)
}

export function programmerSetBlind(blind: boolean, fadeMs?: number) {
  lightingApi.programmer.setBlind(blind, fadeMs)
}

// ── Layer ops ───────────────────────────────────────────────────────────────
// Plain functions for the same reason as the writers above, plus one of their own: the server
// answers every one of these with the whole `programmer.layerState` broadcast, so there is no
// per-call reply for a mutation to await. `patchLayer` is also on a drag/typing path.

export function programmerAddLayer(input: {
  /** Exactly one of these — a layer applies a Look or a template. */
  lookId?: number
  templateId?: number
  targets?: CueTarget[]
  propertyMask?: string
  blendMode?: string
  amount?: number
  speedMasterUuid?: string
  rateSpeedMasterUuid?: string
  fadeMs?: number
}): boolean {
  // The verdict is the **send's**, not the desk's: true means the frame went out, never that a
  // layer exists. That is all a caller can know here — the op is answered by a broadcast — but it
  // is enough to tell "the socket was closed and `sendGesture` already said so" from "it landed",
  // which is the difference between the hand letting go of a record and keeping it.
  return lightingApi.programmer.addLayer(input)
}

export function programmerRemoveLayer(layerId: number, fadeMs?: number) {
  lightingApi.programmer.removeLayer(layerId, fadeMs)
}

/** [toIndex] counts non-preview layers — the preview always sorts last, server-side. */
export function programmerMoveLayer(layerId: number, toIndex: number) {
  lightingApi.programmer.moveLayer(layerId, toIndex)
}

export function programmerPatchLayer(
  layerId: number,
  patch: {
    enabled?: boolean
    amount?: number
    propertyMask?: string
    blendMode?: string
    targets?: CueTarget[]
    stomp?: boolean
    fadeMs?: number
  },
) {
  lightingApi.programmer.patchLayer(layerId, patch)
}
