import type { AttributeFamily } from '@/lib/attributeFamily'
import type { FxPreviewAnswer, FxPreviewSeries } from '@/store/fxPreview'

/**
 * **The preview's planes, decoded** (FX editor plan §3.2): the desk sends each series as base64, a
 * byte a sample per plane — `level`; `red`, `green`, `blue` and any bundled `white`, `amber`, `uv`;
 * `pan`, `tilt` — and a Steps FX's own output adds the step each sample shows beside the steps'
 * labels. Decoded once per answer into typed arrays, per row, per attribute and per FX, so a redraw
 * reads bytes and never parses. Pure.
 */

export const PREVIEW_PLANES = ['level', 'red', 'green', 'blue', 'white', 'amber', 'uv', 'pan', 'tilt'] as const
export type PreviewPlane = (typeof PREVIEW_PLANES)[number]

export interface DecodedSeries {
  planes: Partial<Record<PreviewPlane, Uint8Array>>
  /** Per sample, the step shown — an index into [stepLabels]. Only a Steps FX's own output. */
  steps: Int32Array | null
  stepLabels: readonly string[] | null
}

export interface DecodedFxOutput {
  /** An index into the answer's `fx`. */
  fx: number
  output: DecodedSeries
  heldBackReason: 'STOMP' | 'PROGRAMMER' | 'BLIND' | null
}

export interface DecodedAttributeRow {
  /** An index into the answer's `rows`. */
  row: number
  parked: boolean
  /** One sample: what the attribute holds before the lane's FX, held across the window. */
  underneath: DecodedSeries
  result: DecodedSeries
  fx: DecodedFxOutput[]
}

export interface DecodedAttribute {
  attribute: string
  kind: string
  rows: DecodedAttributeRow[]
  /** [rows] by the answer's row index. */
  byRow: ReadonlyMap<number, DecodedAttributeRow>
}

export interface DecodedLane {
  family: AttributeFamily | null
  attributes: DecodedAttribute[]
}

export interface DecodedPreview {
  samples: number
  lanes: DecodedLane[]
}

const FAMILIES: readonly string[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM']

export function decodePlane(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function decodeSeries(series: FxPreviewSeries): DecodedSeries {
  const planes: Partial<Record<PreviewPlane, Uint8Array>> = {}
  for (const name of PREVIEW_PLANES) {
    const encoded = series.planes[name]
    if (encoded != null) planes[name] = decodePlane(encoded)
  }
  return {
    planes,
    steps: series.steps != null ? Int32Array.from(series.steps) : null,
    stepLabels: series.stepLabels ?? null,
  }
}

export function decodePreview(answer: FxPreviewAnswer): DecodedPreview {
  return {
    samples: answer.window.samples,
    lanes: answer.lanes.map((lane) => ({
      family: lane.family != null && FAMILIES.includes(lane.family) ? (lane.family as AttributeFamily) : null,
      attributes: lane.attributes.map((attribute) => {
        const rows = attribute.rows.map(
          (row): DecodedAttributeRow => ({
            row: row.row,
            parked: row.parked === true,
            underneath: decodeSeries(row.underneath),
            result: decodeSeries(row.result),
            fx: row.fx.map((output) => ({
              fx: output.fx,
              output: decodeSeries(output.output),
              heldBackReason: output.heldBackReason ?? null,
            })),
          }),
        )
        return { attribute: attribute.attribute, kind: attribute.kind, rows, byRow: new Map(rows.map((r) => [r.row, r])) }
      }),
    })),
  }
}
