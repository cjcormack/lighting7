import type { FxPreviewAnswer, FxPreviewAttributeRow, FxPreviewFx, FxPreviewRow, FxPreviewSeries } from '@/store/fxPreview'

/** Base64 of one plane's bytes, as the desk sends it. */
export const plane = (bytes: readonly number[]): string => btoa(String.fromCharCode(...bytes))

export const levelSeries = (bytes: readonly number[]): FxPreviewSeries => ({ planes: { level: plane(bytes) } })

export const colourSeries = (samples: readonly [number, number, number][], extra: Partial<Record<'white' | 'amber' | 'uv', number[]>> = {}): FxPreviewSeries => ({
  planes: {
    red: plane(samples.map((s) => s[0])),
    green: plane(samples.map((s) => s[1])),
    blue: plane(samples.map((s) => s[2])),
    ...Object.fromEntries(Object.entries(extra).map(([k, v]) => [k, plane(v)])),
  },
})

export const positionSeries = (pan: readonly number[], tilt: readonly number[]): FxPreviewSeries => ({
  planes: { pan: plane(pan), tilt: plane(tilt) },
})

export const row = (key: string, rigIndex: number, over: Partial<FxPreviewRow> = {}): FxPreviewRow => ({
  key,
  fixtureKey: key,
  label: key,
  rigIndex,
  ...over,
})

export const attributeRow = (r: number, result: FxPreviewSeries, over: Partial<FxPreviewAttributeRow> = {}): FxPreviewAttributeRow => ({
  row: r,
  underneath: result,
  result,
  fx: [],
  ...over,
})

const rung = (n: number) => ({ ranks: Array.from({ length: n }, (_, i) => i), counts: Array.from({ length: n }, () => n) })

/** An FX painting [rows] rows, its ladder plain (every step leaving the Order's ranks alone). */
export function previewFx(rows: number, over: Partial<FxPreviewFx> = {}): FxPreviewFx {
  return {
    fxId: 7,
    entry: 0,
    fxType: 'Pulse',
    family: 'INTENSITY',
    attribute: 'dimmer',
    running: true,
    speedMasterUuid: 'm1',
    offsets: Array.from({ length: rows }, () => 0),
    starts: Array.from({ length: rows }, () => [0]),
    holding: Array.from({ length: rows }, () => null),
    slots: 1,
    ladder: { order: rung(rows), segments: rung(rows), wings: rung(rows), parts: rung(rows), spread: Array.from({ length: rows }, () => 0) },
    ...over,
  }
}

export function previewAnswer(over: Partial<FxPreviewAnswer> & Pick<FxPreviewAnswer, 'rows' | 'lanes'>): FxPreviewAnswer {
  return {
    window: { beats: 2, seconds: 1, samples: 4, sampleMs: 250 },
    clock: [{ speedMasterUuid: 'm1', tickAtStart: 1000, msPerBeat: 500 }],
    fx: [previewFx(over.rows.length)],
    ...over,
  }
}
