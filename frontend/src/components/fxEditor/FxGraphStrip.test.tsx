// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { PixelCanvas } from '@/test/pixelCanvas'
import { attributeRow, colourSeries, levelSeries, positionSeries, previewAnswer, previewFx, row } from '@/test/fxPreviewAnswers'

/**
 * The graph strip (FX editor plan D15, §4.6): the picked FX's lane alone, drawn from the answer —
 * *This FX* as the FX's own output with nothing underneath, *Result* over what is underneath — its
 * rows past the strip's count a scroll away.
 */
vi.mock('@/store/speedMasters', () => ({
  subscribeToSpeedMasterBeat: () => ({ unsubscribe: () => {} }),
  requestSpeedMasterBeat: () => {},
  tapSpeedMaster: () => {},
  useSpeedMasterLiveQuery: () => ({ master: null }),
}))

const { FxGraphStrip } = await import('./FxGraphStrip')

let canvases: Map<string, PixelCanvas>
const contextOf = (canvas: HTMLCanvasElement) => {
  const ctx = new PixelCanvas(Math.max(1, canvas.width), Math.max(1, canvas.height))
  canvases.set(canvas.getAttribute('aria-label') ?? '', ctx)
  return ctx
}

beforeEach(() => {
  canvases = new Map()
  vi.stubGlobal('requestAnimationFrame', () => 0)
  vi.stubGlobal('cancelAnimationFrame', () => {})
  vi.stubGlobal('devicePixelRatio', 1)
  window.matchMedia = ((query: string) => ({ matches: false, media: query, addEventListener: () => {}, removeEventListener: () => {} })) as unknown as typeof window.matchMedia
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 180, height: 400, left: 0, top: 0 }) as DOMRect)
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const ROWS = Array.from({ length: 10 }, (_, i) => row(`notional-${i + 1}`, i, { label: `Head ${i + 1}` }))

/** A Pulse on ten heads, over an Intensity lane and a Position lane another FX paints. */
function answerOf(over: Partial<FxPreviewAnswer> = {}): FxPreviewAnswer {
  const pulse = levelSeries([0, 255, 0, 255])
  return previewAnswer({
    rows: ROWS,
    lanes: [
      {
        family: 'INTENSITY',
        attributes: [
          {
            attribute: 'dimmer',
            kind: 'SLIDER',
            rows: ROWS.map((_, i) => attributeRow(i, levelSeries([200, 200, 200, 200]), { underneath: levelSeries([128]), fx: [{ fx: 0, output: pulse }] })),
          },
        ],
      },
      { family: 'POSITION', attributes: [{ attribute: 'position', kind: 'POSITION', rows: [attributeRow(0, positionSeries([128, 140, 128, 116], [128, 128, 128, 128]), { fx: [{ fx: 1, output: positionSeries([128, 140, 128, 116], [128, 128, 128, 128]) }] })] }] },
    ],
    fx: [previewFx(10), previewFx(1, { fxId: 9, entry: null, family: 'POSITION', attribute: 'position' })],
    ...over,
  })
}

const draw = (draw: 'this' | 'result', answer: FxPreviewAnswer | null = answerOf()) =>
  render(<FxGraphStrip answer={answer} error={null} waiting="Asking the desk…" draw={draw} fxName={(t) => t} caption="On eight heads" contextOf={contextOf} />)

describe('FxGraphStrip', () => {
  it('says it is asking before the desk answers', () => {
    draw('this', null)
    expect(screen.getByText('Asking the desk…')).toBeTruthy()
    expect(screen.getByText('On eight heads')).toBeTruthy()
  })

  it('draws the picked FX’s lane alone, never the others', () => {
    draw('result')
    expect([...document.querySelectorAll<HTMLElement>('[data-fx-graph-lane]')].map((l) => l.dataset.fxGraphLane)).toEqual(['INTENSITY'])
    expect(document.querySelector('[data-fx-graph-path]')).toBeNull()
    expect(document.querySelector('[data-fx-strip-window]')).toHaveTextContent('Pulse')
    expect(document.querySelector('[data-fx-strip-window]')).toHaveTextContent('10 heads')
  })

  it('draws This FX as the FX’s own output with nothing underneath, and Result over what is', () => {
    const colour = answerOf({
      rows: ROWS.slice(0, 2),
      lanes: [
        {
          family: 'COLOUR',
          attributes: [
            {
              attribute: 'rgbColour',
              kind: 'COLOUR',
              rows: [0, 1].map((i) => attributeRow(i, colourSeries([[0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]]), { fx: [{ fx: 0, output: colourSeries([[255, 0, 0], [255, 0, 0], [255, 0, 0], [255, 0, 0]]) }] })),
            },
          ],
        },
      ],
      fx: [previewFx(2, { family: 'COLOUR', attribute: 'rgbColour' })],
    })
    draw('this', colour)
    expect(canvases.get('Colour graph')!.pixel(82, 7)).toBe('rgb(255,0,0)')
    cleanup()
    canvases = new Map()
    draw('result', colour)
    expect(canvases.get('Colour graph')!.pixel(82, 7)).toBe('rgb(0,0,0)')
  })

  it('keys no Underneath where This FX draws none', () => {
    draw('this')
    const lane = document.querySelector('[data-fx-graph-lane="INTENSITY"]')!
    expect(lane).toHaveTextContent('This FX')
    expect(lane).not.toHaveTextContent('Underneath')
    cleanup()
    draw('result')
    expect(document.querySelector('[data-fx-graph-lane="INTENSITY"]')).toHaveTextContent('Underneath')
  })

  it('shows eight rows, the rest a scroll away', () => {
    draw('this')
    expect(canvases.get('Intensity graph')!.texts.map((t) => t.text)).toHaveLength(10)
    const scroll = document.querySelector<HTMLElement>('[data-fx-graph-scroll]')!
    expect(scroll).not.toBeNull()
    const canvas = document.querySelector<HTMLElement>('[data-fx-graph-canvas="INTENSITY"]')!
    expect(parseFloat(scroll.style.maxHeight)).toBeLessThan(parseFloat(canvas.style.height))
  })

  it('needs no scroller when every row fits', () => {
    draw('this', answerOf({ rows: ROWS.slice(0, 8) }))
    expect(document.querySelector('[data-fx-graph-scroll]')).toBeNull()
  })
})
