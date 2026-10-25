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

const NO_SERIES: DecodedSeries = { planes: {}, steps: null, stepLabels: null }

/**
 * [preview] as [fx] alone paints it — each row's own output as its result, nothing underneath, never
 * parked or held back — where what the stage holds now is not the question: a cue's FX, whose GO
 * lands on whatever is there then, or a palette's on heads the rig lacks. Where the FX holds (its
 * Runs not begun or spent, `holding` per row, inclusive) the desk fills its output with what is
 * underneath now, so those samples keep the FX's own nearest value instead. Rows it does not paint go.
 */
export function ownOutputOnly(
  preview: DecodedPreview,
  fx: number,
  holding: (row: number) => readonly (readonly number[])[] | null | undefined = () => null,
): DecodedPreview {
  const lanes = preview.lanes.flatMap((lane) => {
    const attributes = lane.attributes.flatMap((attribute) => {
      const rows = attribute.rows.flatMap((row): DecodedAttributeRow[] => {
        const own = row.fx.find((o) => o.fx === fx)
        if (own == null) return []
        const output = heldOver(own.output, holding(row.row) ?? [])
        return [{ row: row.row, parked: false, underneath: NO_SERIES, result: output, fx: [{ ...own, output, heldBackReason: null }] }]
      })
      return rows.length === 0 ? [] : [{ ...attribute, rows, byRow: new Map(rows.map((r) => [r.row, r])) }]
    })
    return attributes.length === 0 ? [] : [{ ...lane, attributes }]
  })
  return { ...preview, lanes }
}

/** [series] with each [ranges] span (`[from, to]`, inclusive) holding the sample before it, else the one after. */
function heldOver(series: DecodedSeries, ranges: readonly (readonly number[])[]): DecodedSeries {
  if (ranges.length === 0) return series
  const hold = <T extends Uint8Array | Int32Array>(values: T): T => {
    const out = values.slice() as T
    for (const [from, to] of ranges) {
      const source = from > 0 ? from - 1 : to + 1
      if (source < 0 || source >= out.length) continue
      for (let s = Math.max(0, from); s <= Math.min(to, out.length - 1); s++) out[s] = out[source]
    }
    return out
  }
  const planes: DecodedSeries['planes'] = {}
  for (const [name, values] of Object.entries(series.planes) as [PreviewPlane, Uint8Array][]) planes[name] = hold(values)
  return { ...series, planes, steps: series.steps == null ? null : hold(series.steps) }
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
