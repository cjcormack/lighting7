import type { CueTarget } from '@/api/cuesApi'
import type { AddFixtureFxRequest } from './fixtureFx'
import type { UpdateFxRequest } from '@/api/fxApi'
import { restApi } from './restApi'

/**
 * **What an FX will paint over a window** — `POST /projects/{id}/fx/preview` (FX editor plan §3.3,
 * D7), the desk's sampler asked from the client. The types mirror `routes/fxPreview.kt`'s DTOs
 * field for field; `docs/fx-engineering.md` §"Previewing an FX" is the shape. Nothing is written:
 * no FX is added, no instance moves, no DMX is sent.
 */

/** An FX not yet added: `POST /fx/add`'s body, its targets the request's when it names none. */
export type FxPreviewDraft = Partial<AddFixtureFxRequest> & Pick<AddFixtureFxRequest, 'fxType' | 'attribute'>

/** A running FX ([fxId]), a draft (`POST /fx/add`'s body) or a running FX with an unsent edit (both). */
export type FxPreviewEntry =
  | { fxId: number; draft?: undefined }
  | { fxId?: undefined; draft: FxPreviewDraft }
  | { fxId: number; draft: UpdateFxRequest }

/** At most one of these; two cycles of the first FX when none. */
export interface FxPreviewWindowRequest {
  cycles?: number
  beats?: number
  seconds?: number
}

export interface FxPreviewRequest {
  /** The selection, in its order: the heads to draw. */
  targets: CueTarget[]
  /** At least one; the first is the picked FX, whose cycles the window counts. */
  fx: FxPreviewEntry[]
  /** `STACK`: every FX reaching the targets, plus [fx]; `ONLY`: [fx] alone. */
  include?: 'STACK' | 'ONLY'
  window?: FxPreviewWindowRequest
  /** An index into [fx]: its family's lane drawn as though it were alone over what is underneath. */
  solo?: number
  /**
   * Draw the drafts on this many heads the rig does not have (FX editor plan D15) — no [targets],
   * drafts only, `include: ONLY`. Their rows are `Head 1…n`.
   */
  notional?: number
}

export interface FxPreviewWindow {
  beats: number
  seconds: number
  samples: number
  sampleMs: number
}

/** A clock at the window's start: a master's tick and beat length, or `seconds` for FX on their own clock. */
export interface FxPreviewClock {
  speedMasterUuid?: string | null
  seconds?: boolean
  tickAtStart?: number | null
  msPerBeat?: number | null
}

/** A head, in graph order: its fixture, label, element index on a multi-element fixture, place in rig order. */
export interface FxPreviewRow {
  key: string
  fixtureKey: string
  label: string
  element?: number | null
  rigIndex: number
}

/**
 * Samples, each plane base64 — a byte a sample (`level`; `red`, `green`, `blue` and each bundled
 * `white`, `amber`, `uv`; `pan`, `tilt`). A Steps FX's own output carries its step indices.
 */
export interface FxPreviewSeries {
  planes: Record<string, string>
  steps?: number[] | null
  stepLabels?: string[] | null
}

/** One FX's output on a row, before Amount; [heldBackReason] when it is left out of the result. */
export interface FxPreviewOutput {
  /** An index into the answer's `fx`. */
  fx: number
  output: FxPreviewSeries
  heldBackReason?: 'STOMP' | 'PROGRAMMER' | 'BLIND' | null
}

export interface FxPreviewAttributeRow {
  /** An index into the answer's `rows`. */
  row: number
  parked?: boolean
  underneath: FxPreviewSeries
  result: FxPreviewSeries
  fx: FxPreviewOutput[]
}

export interface FxPreviewAttribute {
  attribute: string
  kind: 'SLIDER' | 'COLOUR' | 'POSITION' | string
  rows: FxPreviewAttributeRow[]
}

/** Intensity, Colour, Position, Beam, then any attribute no family claims (a null family). */
export interface FxPreviewLane {
  family: string | null
  attributes: FxPreviewAttribute[]
}

export interface FxPreviewRung {
  ranks: (number | null)[]
  counts: (number | null)[]
}

export interface FxPreviewLadder {
  order: FxPreviewRung
  segments: FxPreviewRung
  wings: FxPreviewRung
  parts: FxPreviewRung
  spread: (number | null)[]
}

/**
 * One FX sampled, in apply order (bottom first). [fxId] for a running FX, [entry] for one the request
 * named. The per-row lists line up with the answer's `rows`, null where it paints no such head.
 */
export interface FxPreviewFx {
  fxId?: number | null
  entry?: number | null
  fxType: string
  family: string | null
  attribute: string
  running: boolean
  speedMasterUuid?: string | null
  offsets: (number | null)[]
  starts: (number[] | null)[]
  holding: (number[][] | null)[]
  slots: number
  ladder: FxPreviewLadder
  representative?: boolean
}

export interface FxPreviewAnswer {
  window: FxPreviewWindow
  clock: FxPreviewClock[]
  rows: FxPreviewRow[]
  lanes: FxPreviewLane[]
  fx: FxPreviewFx[]
  /** `SAMPLES`, `ROWS`, `FX`: the caps the answer was cut at. */
  truncated?: string[]
}

export const fxPreviewApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /** Current project only (409 otherwise); 400 `FX_PREVIEW_INVALID` names every problem. */
    previewFx: build.mutation<FxPreviewAnswer, FxPreviewRequest & { projectId: number }>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/fx/preview`,
        method: 'POST',
        body,
      }),
    }),
  }),
  overrideExisting: false,
})

export const { usePreviewFxMutation } = fxPreviewApi
