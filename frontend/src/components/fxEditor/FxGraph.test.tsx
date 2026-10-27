// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SpeedMasterBeat } from '@/api/speedMastersWsApi'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { PixelCanvas } from '@/test/pixelCanvas'
import { attributeRow, colourSeries, levelSeries, positionSeries, previewAnswer, previewFx, row } from '@/test/fxPreviewAnswers'

/**
 * The FX editor's graph (FX editor plan §4.4): the toolbar's per-window choices, rows held in rig
 * order, Solo said in the toolbar, the hover's read-out, the playhead from the answer's clock and
 * its resync on a beat, held still under reduced motion, and the narrow arm.
 */
const wire = vi.hoisted(() => ({
  beats: new Map<string, (beat: SpeedMasterBeat) => void>(),
  requested: [] as string[],
  taps: [] as (string | null)[],
}))

vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: (_: unknown, { selectFromResult }: { selectFromResult: (r: { data: unknown }) => unknown }) =>
    selectFromResult({ data: [{ uuid: 'm1', index: 2, bpm: 120 }] }),
  subscribeToSpeedMasterBeat: (uuid: string, fn: (beat: SpeedMasterBeat) => void) => {
    wire.beats.set(uuid, fn)
    return { unsubscribe: () => wire.beats.delete(uuid) }
  },
  requestSpeedMasterBeat: (uuid: string) => wire.requested.push(uuid),
  tapSpeedMaster: (uuid: string | null) => wire.taps.push(uuid),
}))

const { FxGraph } = await import('./FxGraph')
const { draftOf } = await import('@/components/fx/fxEditorModel')
const { makeActiveFx } = await import('@/test/fixtureFactories')
const { GUTTER_DEFAULT_PX, resetFxEditorViewStores, setFxGraphOption, useFxGraphOptions } = await import('@/lib/fxEditorView')

let now = 0
let frames: FrameRequestCallback[] = []
let reduced = false
let coarse = false
let canvases: Map<string, PixelCanvas>

const contextOf = (canvas: HTMLCanvasElement) => {
  const ctx = new PixelCanvas(Math.max(1, canvas.width), Math.max(1, canvas.height))
  canvases.set(canvas.getAttribute('aria-label') ?? '', ctx)
  return ctx
}

beforeEach(() => {
  sessionStorage.clear()
  resetFxEditorViewStores()
  wire.beats.clear()
  wire.requested = []
  wire.taps = []
  canvases = new Map()
  now = 0
  frames = []
  reduced = false
  coarse = false
  vi.spyOn(performance, 'now').mockImplementation(() => now)
  vi.stubGlobal('requestAnimationFrame', (fn: FrameRequestCallback) => frames.push(fn))
  vi.stubGlobal('cancelAnimationFrame', () => {})
  vi.stubGlobal('devicePixelRatio', 1)
  window.matchMedia = ((query: string) => ({
    matches: (reduced && query.includes('reduced-motion')) || (coarse && query.includes('coarse')),
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
  // The lanes' column measures 180px and the window's names take 72 of it, so the plot starts at
  // x 72 and is 80px wide: a sample is 20px.
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 180, height: 400, left: 0, top: 0 }) as DOMRect)
  setFxGraphOption('gutter', 72)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const ROWS = [row('hex-1', 0, { label: 'Hex 1' }), row('hex-2', 1, { label: 'Hex 2' })]

function answerOf(over: Partial<FxPreviewAnswer> = {}): FxPreviewAnswer {
  return previewAnswer({
    rows: ROWS,
    lanes: [
      {
        family: 'INTENSITY',
        attributes: [
          {
            attribute: 'dimmer',
            kind: 'SLIDER',
            rows: [0, 1].map((r) => attributeRow(r, levelSeries([0, 128, 255, 64]), { fx: [{ fx: 0, output: levelSeries([0, 128, 255, 64]) }] })),
          },
        ],
      },
      { family: 'COLOUR', attributes: [{ attribute: 'rgbColour', kind: 'COLOUR', rows: [attributeRow(0, colourSeries([[255, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]]))] }] },
    ],
    fx: [previewFx(2, { starts: [[0], [0, 4]] })],
    ...over,
  })
}

function draw(answer: FxPreviewAnswer | null = answerOf(), props: Partial<Parameters<typeof FxGraph>[0]> = {}) {
  return render(
    <FxGraph
      answer={answer}
      error={null}
      empty={null}
      narrow={false}
      fxName={(t) => t}
      fixtureName={() => undefined}
      solo={null}
      onUnsolo={() => {}}
      contextOf={contextOf}
      {...props}
    />,
  )
}

/** The lanes' column measured [width] wide instead. */
const measuredAt = (width: number) =>
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width, height: 400, left: 0, top: 0 }) as DOMRect)

const labels = (lane: string) =>
  [...document.querySelectorAll<HTMLElement>(`[data-fx-graph-lane="${lane.toUpperCase()}"] [data-fx-graph-label]`)].map((l) => l.textContent)
const playheadX = () => document.querySelector<HTMLElement>('[data-fx-playhead]')!.style.transform
const runFrames = () =>
  act(() => {
    const due = frames
    frames = []
    due.forEach((fn) => fn(now))
  })

describe('the graph', () => {
  it('says it is asking before the desk answers, and why it has nothing to draw', () => {
    draw(null)
    expect(screen.getByText('Asking the desk…')).toBeTruthy()
    draw(null, { empty: 'nothing-reaches' })
    expect(screen.getByText('No FX reaches the selection.')).toBeTruthy()
    draw(null, { empty: 'nothing-selected' })
    expect(screen.getByText('Select heads to draw their FX.')).toBeTruthy()
  })

  it('draws one canvas per lane from the answer, each row labelled', () => {
    draw()
    expect([...document.querySelectorAll<HTMLElement>('[data-fx-graph-lane]')].map((l) => l.dataset.fxGraphLane)).toEqual(['INTENSITY', 'COLOUR'])
    expect(labels('Intensity')).toEqual(['Hex 1', 'Hex 2'])
    expect(canvases.get('Colour graph')!.pixel(82, 7)).toBe('rgb(255,0,0)')
  })

  it('holds the rows in rig order while the Order changes, and follows the Order without it', () => {
    const reversed = answerOf({ rows: [ROWS[1], ROWS[0]] })
    const { rerender } = draw()
    act(() => setFxGraphOption('rigOrder', true))
    expect(labels('Intensity')).toEqual(['Hex 1', 'Hex 2'])
    const props = { error: null, empty: null, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }
    rerender(<FxGraph answer={reversed} {...props} />)
    expect(labels('Intensity')).toEqual(['Hex 1', 'Hex 2'])
    act(() => setFxGraphOption('rigOrder', false))
    expect(labels('Intensity')).toEqual(['Hex 2', 'Hex 1'])
  })

  it('keeps the toolbar’s choices as the window’s view state', () => {
    let options: ReturnType<typeof useFxGraphOptions> | null = null
    function Probe() {
      options = useFxGraphOptions()
      return null
    }
    render(<Probe />)
    draw()
    fireEvent.click(screen.getByRole('button', { name: '8 bars' }))
    fireEvent.click(screen.getByRole('button', { name: 'This FX' }))
    fireEvent.click(screen.getByRole('button', { name: 'Rig order' }))
    fireEvent.click(screen.getByRole('button', { name: 'As seen' }))
    fireEvent.click(screen.getByRole('button', { name: 'Pause the playhead' }))
    expect(options).toEqual({ window: '8b', view: 'this', rigOrder: true, asSeen: true, paused: true, gutter: 72 })
    expect(JSON.parse(sessionStorage.getItem('programmer.fx.graph')!)).toMatchObject({ window: '8b' })
    expect(screen.getByRole('button', { name: 'Run the playhead' })).toBeTruthy()
  })

  it('names the master and taps it', () => {
    draw()
    expect(document.querySelector('[data-fx-graph-bpm]')).toHaveTextContent('M2 · 120')
    fireEvent.click(screen.getByRole('button', { name: 'Tap' }))
    expect(wire.taps).toEqual(['m1'])
  })

  it('says Seconds, and offers no Tap, for an FX on its own clock', () => {
    draw(answerOf({ clock: [{ seconds: true }], fx: [previewFx(2, { speedMasterUuid: null })] }))
    expect(screen.getByText('Seconds')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Tap' })).toBeNull()
  })

  it('says Solo in the toolbar, and its × lets it go', () => {
    const onUnsolo = vi.fn()
    draw(answerOf(), { solo: { name: 'Pulse' }, onUnsolo })
    expect(document.querySelector('[data-fx-graph-solo]')).toHaveTextContent('Solo: Pulse')
    fireEvent.click(screen.getByRole('button', { name: 'Unsolo' }))
    expect(onUnsolo).toHaveBeenCalledOnce()
  })

  it('reads a row’s value and phase under the pointer', () => {
    draw()
    const canvas = screen.getByLabelText('Intensity graph')
    // Sample 1 of Hex 2, a quarter of the way through the cycle starting at sample 0. The window
    // starts at tick 1000, beat 41⅔, so sample 1 is beat 42⅙: the 3rd of its bar.
    fireEvent.pointerMove(canvas, { clientX: 102, clientY: 40 })
    const readout = document.querySelector('[data-fx-graph-readout]')!
    expect(readout).toHaveTextContent('Beat 3.17')
    expect(readout).toHaveTextContent('Hex 2')
    expect(readout).toHaveTextContent('Level 50 %')
    expect(readout).toHaveTextContent('Phase 90°')
    fireEvent.pointerLeave(canvas)
    expect(readout).toHaveTextContent('Hover a row to read it')
  })

  it('says what the desk cut, and marks a stateful FX representative', () => {
    draw(answerOf({ truncated: ['ROWS'], fx: [previewFx(2, { fxType: 'CandleFlicker', representative: true })] }))
    expect(screen.getByText('The desk cut the rows, at 96.')).toBeTruthy()
    expect(document.querySelector('[data-fx-representative]')).toHaveTextContent('CandleFlicker · representative')
  })

  it('draws only the picked FX’s lane below the 760 arm, at most six rows, and says so', () => {
    const rows = Array.from({ length: 8 }, (_, i) => row(`h${i}`, i, { label: `H${i}` }))
    const answer = answerOf({
      rows,
      lanes: [
        { family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: rows.map((_, i) => attributeRow(i, levelSeries([0, 0, 0, 0]))) }] },
        { family: 'COLOUR', attributes: [{ attribute: 'rgbColour', kind: 'COLOUR', rows: rows.map((_, i) => attributeRow(i, colourSeries([[0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]]))) }] },
      ],
      fx: [previewFx(8, { family: 'COLOUR', attribute: 'rgbColour' })],
    })
    draw(answer, { narrow: true })
    expect([...document.querySelectorAll<HTMLElement>('[data-fx-graph-lane]')].map((l) => l.dataset.fxGraphLane)).toEqual(['COLOUR'])
    expect(labels('Colour')).toHaveLength(6)
    expect(screen.getByText('Narrow: the picked FX’s lane only, its first 6 rows.')).toBeTruthy()
  })

  it('lays every row out under the phone arm’s scrollRows, six of them shown and the rest a scroll away', () => {
    const rows = Array.from({ length: 8 }, (_, i) => row(`h${i}`, i, { label: `H${i}` }))
    const answer = answerOf({
      rows,
      lanes: [{ family: 'COLOUR', attributes: [{ attribute: 'rgbColour', kind: 'COLOUR', rows: rows.map((_, i) => attributeRow(i, colourSeries([[0, 0, 0], [0, 0, 0], [0, 0, 0], [0, 0, 0]]))) }] }],
      fx: [previewFx(8, { family: 'COLOUR', attribute: 'rgbColour' })],
    })
    draw(answer, { narrow: true, scrollRows: true })
    expect(labels('Colour')).toHaveLength(8)
    const scroll = document.querySelector<HTMLElement>('[data-fx-graph-scroll]')!
    const canvas = document.querySelector<HTMLElement>('[data-fx-graph-canvas="COLOUR"]')!
    expect(parseFloat(scroll.style.maxHeight)).toBeLessThan(parseFloat(canvas.style.height) + 28)
    expect(scroll.contains(document.querySelector('[data-fx-playhead]'))).toBe(true)
    expect(screen.getByText('Narrow: the picked FX’s lane only, 6 rows at a time — scroll for more.')).toBeTruthy()
  })
})

describe('reading the answer as the stage runs it', () => {
  const props = { error: null, empty: null, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }

  it('draws a cell’s colour as seen under its fixture’s one dimmer', () => {
    const rows = [row('bar', 0, { label: 'Bar 1' }), row('bar.0', 1, { fixtureKey: 'bar', label: 'Bar 1 · 1', element: 0 })]
    const answer = answerOf({
      rows,
      lanes: [
        { family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([0, 0, 0, 0]))] }] },
        { family: 'COLOUR', attributes: [{ attribute: 'rgbColour', kind: 'COLOUR', rows: [attributeRow(1, colourSeries([[255, 0, 0], [255, 0, 0], [255, 0, 0], [255, 0, 0]]))] }] },
      ],
      fx: [previewFx(2)],
    })
    act(() => setFxGraphOption('asSeen', true))
    draw(answer)
    expect(canvases.get('Colour graph')!.pixel(82, 7)).toBe('rgb(0,0,0)')
  })

  it('names the head the pointer rests on, by its key, across a re-ask that reorders the rows', () => {
    const { rerender } = draw()
    fireEvent.pointerMove(screen.getByLabelText('Intensity graph'), { clientX: 102, clientY: 10 })
    const readout = document.querySelector('[data-fx-graph-readout]')!
    expect(readout).toHaveTextContent('Hex 1')
    rerender(<FxGraph answer={answerOf({ rows: [ROWS[1], ROWS[0]] })} {...props} />)
    expect(readout).toHaveTextContent('Hex 1')
    // A window too short for the sample it rested on drops the read-out.
    rerender(<FxGraph answer={answerOf({ rows: [ROWS[1], ROWS[0]], window: { beats: 1, seconds: 0.5, samples: 1, sampleMs: 500 } })} {...props} />)
    expect(readout).toHaveTextContent('Hover a row to read it')
  })

  it('says the rows are in rig order while they are', () => {
    draw()
    const readout = document.querySelector('[data-fx-graph-readout]')!
    expect(readout).toHaveTextContent('Rows in Pulse’s Order')
    act(() => setFxGraphOption('rigOrder', true))
    expect(readout).toHaveTextContent('Rows in rig order')
  })

  const positionAnswer = () =>
    answerOf({
      lanes: [{ family: 'POSITION', attributes: [{ attribute: 'position', kind: 'POSITION', rows: [attributeRow(0, positionSeries([192, 192, 192, 192], [128, 128, 128, 128]))] }] }],
      fx: [previewFx(2, { family: 'POSITION', attribute: 'position' })],
    })

  it('puts the path panel under the lanes where beside them would leave no plot', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 300, height: 400, left: 0, top: 0 }) as DOMRect)
    draw(positionAnswer())
    const panel = document.querySelector('[data-fx-graph-path]')!
    expect(panel.parentElement!.className).toContain('flex-col')
    expect(screen.getByLabelText('Position graph').style.width).toBe('276px')
  })

  it('keeps the path panel’s dots drawn while the playhead is held and the drawing changes', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 600, height: 400, left: 0, top: 0 }) as DOMRect)
    draw(positionAnswer())
    act(() => setFxGraphOption('paused', true))
    act(() => setFxGraphOption('view', 'this'))
    const dots = [...canvases.entries()].filter(([label]) => label === '').map(([, ctx]) => ctx)
    const drawn = dots.some((ctx) => Array.from({ length: 132 * 132 }, (_, i) => ctx.pixel(i % 132, Math.floor(i / 132))).some((p) => p != null))
    expect(drawn).toBe(true)
  })

  it('asks for a beat when the playhead is let go, since it runs on behind the master', () => {
    draw()
    act(() => setFxGraphOption('paused', true))
    expect(wire.requested).toEqual([])
    act(() => setFxGraphOption('paused', false))
    expect(wire.requested).toEqual(['m1'])
  })
})

describe('the playhead', () => {
  it('advances from the answer’s clock, a frame at a time', () => {
    draw()
    expect(playheadX()).toBe('translateX(0px)')
    now = 500
    runFrames()
    // 500 ms of a 1000 ms window over 80px.
    expect(playheadX()).toBe('translateX(40px)')
  })

  it('resyncs on its master’s beat', () => {
    draw()
    expect(wire.beats.has('m1')).toBe(true)
    now = 100
    // Beat 42 is tick 1009: 9 ticks past the window's start at tick 1000, 187.5 ms at 120 BPM.
    act(() => wire.beats.get('m1')!({ masterUuid: 'm1', index: 2, beatNumber: 42, bpm: 120, timestampMs: 0 }))
    runFrames()
    expect(playheadX()).toBe('translateX(15px)')
  })

  it('asks for a beat when a fresh answer lands on the master it follows', () => {
    const { rerender } = draw()
    expect(wire.requested).toEqual([])
    const props = { error: null, empty: null, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }
    rerender(<FxGraph answer={answerOf()} {...props} />)
    expect(wire.requested).toEqual(['m1'])
  })

  it('holds still under reduced motion', () => {
    reduced = true
    draw()
    expect(frames).toHaveLength(0)
    now = 500
    act(() => wire.beats.get('m1')?.({ masterUuid: 'm1', index: 2, beatNumber: 42, bpm: 120, timestampMs: 0 }))
    runFrames()
    expect(playheadX()).toBe('translateX(0px)')
  })

  it('holds still while paused, and runs on from there', () => {
    draw()
    now = 250
    act(() => setFxGraphOption('paused', true))
    now = 900
    runFrames()
    expect(playheadX()).toBe('translateX(20px)')
    act(() => setFxGraphOption('paused', false))
    now = 1150
    runFrames()
    expect(playheadX()).toBe('translateX(40px)')
  })
})

describe('the handles (FX editor plan D12, §4.4)', () => {
  // Two cycles of 48 samples over the 80px plot: the first cycle ends at x 112, a sample is ⅚ px.
  const SAMPLES = 96
  const flat = (v: number) => levelSeries(Array.from({ length: SAMPLES }, () => v))
  const handleAnswer = (over: Partial<FxPreviewAnswer> = {}) =>
    answerOf({
      window: { beats: 8, seconds: 4, samples: SAMPLES, sampleMs: 41.7 },
      lanes: [
        {
          family: 'INTENSITY',
          attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [0, 1].map((r) => attributeRow(r, flat(0), { fx: [{ fx: 0, output: flat(255) }] })) }],
        },
      ],
      fx: [previewFx(2, { starts: [[0, 48], [24, 72]] })],
      ...over,
    })
  const editorOf = (over: Partial<Parameters<typeof draftOf>[0]> = {}) => ({
    fxId: 7,
    draft: draftOf(makeActiveFx({ id: 7, fxType: 'Pulse', cycle: 4, order: 'NORMAL', parameters: { min: '0', max: '255' }, ...over })),
    levels: true,
    wallClock: false,
    readOnly: false,
    set: vi.fn(),
    setParam: vi.fn(),
  })
  const handle = (name: string) => document.querySelector<HTMLElement>(`[data-fx-handle="${name}"]`)
  const drag = (el: Element, from: { x: number; y: number }, to: { x: number; y: number }) => {
    fireEvent.pointerDown(el, { button: 0, clientX: from.x, clientY: from.y })
    fireEvent.pointerMove(window, { clientX: to.x, clientY: to.y })
    fireEvent.pointerUp(window, { clientX: to.x, clientY: to.y })
  }

  it('drags the first cycle’s edge for Cycle, snapping to a step, the release landing', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    // The Cycle tab sits on the ruler at the first cycle's end, labelled with the cycle.
    const tab = handle('cycle')!
    expect(tab.closest('[data-fx-graph-ruler]')).not.toBeNull()
    expect(tab.style.left).toBe('112px')
    expect(tab).toHaveTextContent('1 bar')
    fireEvent.pointerDown(tab, { button: 0, clientX: 112, clientY: 10 })
    // 40px further is twice the cycle; 44px is still twice it, on the step.
    fireEvent.pointerMove(window, { clientX: 152, clientY: 10 })
    fireEvent.pointerMove(window, { clientX: 156, clientY: 10 })
    expect(editor.set.mock.calls).toEqual([[{ cycle: 8 }, false]])
    expect(document.querySelector('[data-fx-handle-label]')).toHaveTextContent('2 bars')
    expect(tab.style.left).toBe('152px')
    fireEvent.pointerUp(window, { clientX: 156, clientY: 10 })
    expect(editor.set).toHaveBeenLastCalledWith({ cycle: 8 }, true)
    expect(document.querySelector('[data-fx-handle-label]')).toHaveTextContent('1 bar')
  })

  it('lands nothing for a drag that never leaves its step', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    drag(handle('cycle')!, { x: 112, y: 10 }, { x: 114, y: 10 })
    expect(editor.set).not.toHaveBeenCalled()
  })

  it('takes a press that moves less than the slop, or another pointer’s moves, for no drag', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    const canvas = screen.getByLabelText('Intensity graph')
    fireEvent.pointerDown(canvas, { button: 0, clientX: 100, clientY: 15, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 102, clientY: 15, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 140, clientY: 15, pointerId: 2 })
    fireEvent.pointerUp(window, { clientX: 140, clientY: 15, pointerId: 2 })
    fireEvent.pointerUp(window, { clientX: 102, clientY: 15, pointerId: 1 })
    expect(editor.set).not.toHaveBeenCalled()
  })

  it('drags the first cycle’s edge of a Seconds FX in seconds, not onto the beat steps', () => {
    const editor = editorOf({ seconds: true, cycle: 30, timingSource: 'WALL_CLOCK' })
    draw(handleAnswer(), { editor })
    drag(handle('cycle')!, { x: 112, y: 10 }, { x: 116, y: 10 })
    expect(editor.set).toHaveBeenLastCalledWith({ cycle: 33 }, true)
  })

  it('drags Spread’s knob, at the last row’s start, the row’s start following the finger', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    const knob = handle('spread')!
    // Hex 2's first start is sample 24, x 92, at the top of its row.
    expect([knob.style.left, knob.style.top]).toEqual(['92px', '30px'])
    // Hex 2 is rank 1 of 2: half a cycle of offset for each unit of Spread. 20px is 24 samples, a
    // half-cycle, so Spread rises by one.
    drag(knob, { x: 92, y: 30 }, { x: 112, y: 30 })
    expect(editor.set).toHaveBeenLastCalledWith({ spread: 2 }, true)
    // The line is a mark: it takes no press.
    expect(document.querySelector('[data-fx-spread-line]')?.getAttribute('data-fx-handle')).toBeNull()
  })

  it('puts Spread’s knob on a row Spread moves, so it follows the finger', () => {
    const editor = editorOf()
    // Hex 2 is rank 0 of its Part: its start never moves with Spread, so the knob rides Hex 1's.
    const ladder = { ...previewFx(2).ladder, parts: { ranks: [1, 0], counts: [2, 2] } }
    draw(handleAnswer({ fx: [previewFx(2, { starts: [[0, 48], [24, 72]], ladder })] }), { editor })
    const knob = handle('spread')!
    expect([knob.style.left, knob.style.top]).toEqual(['72px', '2px'])
    drag(knob, { x: 72, y: 2 }, { x: 92, y: 2 })
    expect(editor.set).toHaveBeenLastCalledWith({ spread: 2 }, true)
  })

  it('names a knob on hover and lights what it changes', () => {
    const onHighlight = vi.fn()
    const ladder = { ...previewFx(2).ladder, spread: [0, 0.25] }
    draw(handleAnswer({ fx: [previewFx(2, { starts: [[0, 48], [12, 60]], ladder })] }), { editor: editorOf({ spread: 0.5 }), onHighlight })
    fireEvent.pointerEnter(handle('spread')!)
    // Spread 50 % over Hex 1 and Hex 2: Hex 2 a quarter of a cycle after Hex 1 (fx-settings plan §4).
    expect(document.querySelector('[data-fx-knob-tip]')).toHaveTextContent('Later by ¼ cycle')
    expect(onHighlight).toHaveBeenLastCalledWith('starts')
    fireEvent.pointerLeave(handle('spread')!)
    expect(onHighlight).toHaveBeenLastCalledWith(null)
    fireEvent.pointerEnter(document.querySelector('[data-fx-handle="max"][data-row="hex-1"]')!)
    expect(document.querySelector('[data-fx-knob-tip]')).toHaveTextContent('High 100%')
    expect(onHighlight).toHaveBeenLastCalledWith('levels')
    fireEvent.pointerEnter(handle('cycle')!)
    expect(onHighlight).toHaveBeenLastCalledWith('cycle')
  })

  it('says Spread’s knob in the FX’s own steps under STEP, and drags it in steps', () => {
    const editor = { ...editorOf({ fxType: 'Colour Cycle', spread: 1, spreadUnit: 'STEP' }), steps: 3 }
    draw(handleAnswer({ fx: [previewFx(2, { starts: [[0, 48], [16, 64]], ladder: { ...previewFx(2).ladder, spread: [0, 1 / 3] } })] }), { editor })
    fireEvent.pointerEnter(handle('spread')!)
    expect(document.querySelector('[data-fx-knob-tip]')).toHaveTextContent('Later by ⅓ cycle · 1 step')
    fireEvent.pointerLeave(handle('spread')!)
    // Hex 2 moves a third of a cycle a step: 20px is 24 samples, half a cycle, so 1½ steps more.
    const knob = handle('spread')!
    drag(knob, { x: Number.parseFloat(knob.style.left), y: 2 }, { x: Number.parseFloat(knob.style.left) + 20, y: 2 })
    expect(editor.set).toHaveBeenLastCalledWith({ spread: 2.5 }, true)
  })

  it('says every head starts together when the desk’s offsets are all one', () => {
    draw(handleAnswer(), { editor: editorOf({ spread: 0 }) })
    fireEvent.pointerEnter(handle('spread')!)
    expect(document.querySelector('[data-fx-knob-tip]')).toHaveTextContent('Every head together')
  })

  it('drags a row block sideways for Phase', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    drag(screen.getByLabelText('Intensity graph'), { x: 100, y: 15 }, { x: 120, y: 15 })
    // 24 samples right is half a cycle less Phase, wrapped.
    expect(editor.set).toHaveBeenLastCalledWith({ phaseOffset: 0.5 }, true)
  })

  it('drags an Intensity FX’s max and min by one pair of knobs, on the first row’s scale, every row keeping its lines', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    // High and Low are the FX's: one pair of knobs, the lines drawn on every row as marks.
    expect(document.querySelectorAll('[data-fx-handle="max"], [data-fx-handle="min"]')).toHaveLength(2)
    expect(document.querySelectorAll('[data-fx-level-line]')).toHaveLength(4)
    const max = document.querySelector('[data-fx-handle="max"][data-row="hex-1"]')!
    // Hex 1's full is y 2 and its baseline y 27: 12.5px down is half way.
    drag(max, { x: 100, y: 2 }, { x: 100, y: 14.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('max', '128', true)
    const min = document.querySelector('[data-fx-handle="min"][data-row="hex-1"]')!
    drag(min, { x: 100, y: 27 }, { x: 100, y: 14.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('min', '128', true)
  })

  it('drags a Wave’s low and high by the same knobs, writing the parameters the FX pairs', () => {
    const editor = {
      ...editorOf({ fxType: 'Wave', parameters: { shape: 'SINE', low: '0', high: '255' } }),
      levelParams: { low: 'low', high: 'high' },
    }
    draw(handleAnswer(), { editor })
    drag(document.querySelector('[data-fx-handle="max"][data-row="hex-1"]')!, { x: 100, y: 2 }, { x: 100, y: 14.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('high', '128', true)
    drag(document.querySelector('[data-fx-handle="min"][data-row="hex-1"]')!, { x: 100, y: 27 }, { x: 100, y: 14.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('low', '128', true)
  })

  it('keeps a knob’s light when the pointer moves straight onto another knob', () => {
    const onHighlight = vi.fn()
    draw(handleAnswer(), { editor: editorOf(), onHighlight })
    fireEvent.pointerEnter(document.querySelector('[data-fx-handle="max"]')!)
    fireEvent.pointerLeave(document.querySelector('[data-fx-handle="max"]')!)
    fireEvent.pointerEnter(handle('spread')!)
    expect(onHighlight).toHaveBeenLastCalledWith('starts')
  })

  it('draws no max and min lines on an FX without them', () => {
    draw(handleAnswer(), { editor: { ...editorOf(), levels: false } })
    expect(handle('max')).toBeNull()
    expect(handle('cycle')).not.toBeNull()
  })

  it('draws no handle under a coarse pointer, read-only, or for an FX the inspector is not editing', () => {
    coarse = true
    const editor = editorOf()
    const { unmount } = draw(handleAnswer(), { editor })
    expect(document.querySelector('[data-fx-handle]')).toBeNull()
    fireEvent.pointerDown(screen.getByLabelText('Intensity graph'), { button: 0, clientX: 100, clientY: 15 })
    fireEvent.pointerMove(window, { clientX: 120, clientY: 15 })
    expect(editor.set).not.toHaveBeenCalled()
    unmount()
    coarse = false
    draw(handleAnswer(), { editor: { ...editor, readOnly: true } })
    expect(document.querySelector('[data-fx-handle]')).toBeNull()
    cleanup()
    draw(handleAnswer(), { editor: { ...editor, fxId: 99 } })
    expect(document.querySelector('[data-fx-handle]')).toBeNull()
  })

  it('tells the ladder the row the pointer rests on, and marks the row a dot links to', () => {
    const onHoverRow = vi.fn()
    draw(handleAnswer(), { onHoverRow, linkedRow: 'hex-2' })
    fireEvent.pointerMove(screen.getByLabelText('Intensity graph'), { clientX: 100, clientY: 40 })
    expect(onHoverRow).toHaveBeenLastCalledWith('hex-2')
    fireEvent.pointerLeave(screen.getByLabelText('Intensity graph'))
    expect(onHoverRow).toHaveBeenLastCalledWith(null)
    expect(canvases.get('Intensity graph')!.pixel(1, 33)).toBe('#c026d3')
    expect(canvases.get('Intensity graph')!.pixel(1, 5)).toBeNull()
  })

  it('lights the max and min lines while their knob is hovered', () => {
    draw(handleAnswer(), { editor: editorOf(), highlight: 'levels' })
    expect(document.querySelector('[data-fx-level-line="max"]')!.className).toContain('fuchsia')
  })

  it('lights the mark the inspector’s hovered control changes', () => {
    draw(handleAnswer(), { highlight: 'starts' })
    expect(canvases.get('Intensity graph')!.pixel(72, 1)).toBe('#c026d3')
  })
})

describe('the toolbar and the ruler (follow-ups D4)', () => {
  it('labels its segments, and puts the window’s length beside Window', () => {
    draw()
    const toolbar = document.querySelector('[data-fx-graph-toolbar]')!
    expect(screen.getByRole('group', { name: 'Window' })).toHaveTextContent('Window')
    expect(screen.getByRole('group', { name: 'Draw' })).toHaveTextContent('Draw')
    expect(toolbar.querySelector('[data-fx-graph-length]')).toHaveTextContent('2 beats · 1 s')
    // The line under the toolbar is the hover's: it no longer reads like a control.
    expect(document.querySelector('[data-fx-graph-readout]')).not.toHaveTextContent('Window')
  })

  it('numbers the beats on the ruler from the tick the window started on', () => {
    // Tick 1000 is beat 41⅔: the window holds beats 42 and 43, the third and fourth of its bar.
    draw()
    expect(canvases.get('Beat ruler')!.texts.map((t) => t.text)).toEqual(['1.3', '1.4'])
  })

  it('reads seconds on an FX’s own clock', () => {
    draw(answerOf({ clock: [{ seconds: true }], fx: [previewFx(2, { speedMasterUuid: null })] }))
    expect(canvases.get('Beat ruler')!.texts.map((t) => t.text)).toEqual(['0 s'])
  })
})

describe('a fixture’s own row (follow-ups D5)', () => {
  const BAR = [
    row('bar.0', 0, { fixtureKey: 'bar', label: 'Bar Element 1', element: 0 }),
    row('bar.1', 1, { fixtureKey: 'bar', label: 'Bar Element 2', element: 1 }),
  ]
  const barAnswer = () =>
    answerOf({
      rows: BAR,
      lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [attributeRow(0, levelSeries([255, 255, 255, 255])), attributeRow(1, levelSeries([0, 0, 0, 0]))] }] }],
    })

  it('draws a fixture row over its elements, which its chevron folds and unfolds', () => {
    measuredAt(600)
    act(() => setFxGraphOption('gutter', GUTTER_DEFAULT_PX))
    draw(barAnswer(), { fixtureName: (k) => (k === 'bar' ? 'Bar' : undefined) })
    expect(labels('Intensity')).toEqual(['Bar', 'Element 1', 'Element 2'])
    const fold = screen.getByRole('button', { name: 'Bar — fold its elements' })
    expect(fold).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(fold)
    expect(labels('Intensity')).toEqual(['Bar'])
    expect(JSON.parse(sessionStorage.getItem('programmer.fx.foldedFixtures')!)).toEqual(['bar'])
    fireEvent.click(screen.getByRole('button', { name: 'Bar — show its elements' }))
    expect(labels('Intensity')).toEqual(['Bar', 'Element 1', 'Element 2'])
  })

  it('reads an element’s number where its own words do not fit the name column', () => {
    // 180px of column leaves the names their narrowest, 72px.
    draw(barAnswer(), { fixtureName: (k) => (k === 'bar' ? 'Bar' : undefined) })
    expect(labels('Intensity')).toEqual(['Bar', '1', '2'])
  })

  it('reads a mixed row under This FX as the picked FX’s own output, as it is drawn', () => {
    const answer = answerOf({
      rows: BAR,
      lanes: [
        {
          family: 'INTENSITY',
          attributes: [
            {
              attribute: 'dimmer',
              kind: 'SLIDER',
              rows: [0, 1].map((r) => attributeRow(r, levelSeries([0, 0, 0, 0]), { fx: [{ fx: 0, output: levelSeries([255, 255, 255, 255]) }] })),
            },
          ],
        },
      ],
    })
    act(() => setFxGraphOption('view', 'this'))
    draw(answer, { fixtureName: (k) => (k === 'bar' ? 'Bar' : undefined) })
    fireEvent.pointerMove(screen.getByLabelText('Intensity graph'), { clientX: 82, clientY: 10 })
    expect(document.querySelector('[data-fx-graph-readout]')).toHaveTextContent('Level 100 %')
  })

  it('folds every fixture from the lane’s header, its way out of a heat map', () => {
    const many = Array.from({ length: 27 }, (_, i) => row(`b${Math.floor(i / 9)}.${i % 9}`, i, { fixtureKey: `b${Math.floor(i / 9)}`, label: `Cell ${i % 9}`, element: i % 9 }))
    const answer = answerOf({
      rows: many,
      lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: many.map((_, r) => attributeRow(r, levelSeries([0, 0, 0, 0]))) }] }],
      fx: [previewFx(27)],
    })
    draw(answer)
    // 27 elements is a heat map: no names, so no chevrons.
    expect(labels('Intensity')).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: 'Fold elements' }))
    expect(labels('Intensity')).toEqual(['b0', 'b1', 'b2'])
    fireEvent.click(screen.getByRole('button', { name: 'Show elements' }))
    expect(labels('Intensity')).toEqual([])
  })

  it('reads a mixed row on hover as the mix of its elements, with no phase', () => {
    draw(barAnswer(), { fixtureName: (k) => (k === 'bar' ? 'Bar' : undefined) })
    fireEvent.pointerMove(screen.getByLabelText('Intensity graph'), { clientX: 82, clientY: 10 })
    const readout = document.querySelector('[data-fx-graph-readout]')!
    expect(readout).toHaveTextContent('Bar · mix of 2')
    expect(readout).toHaveTextContent('Level 50 %')
    expect(readout).not.toHaveTextContent('Phase')
  })

  it('cuts a long name in its middle, the whole name on hover', () => {
    measuredAt(600)
    act(() => setFxGraphOption('gutter', 96))
    draw(barAnswer(), { fixtureName: (k) => (k === 'bar' ? 'LX2 Shehds 19x15 Stage Right' : undefined) })
    const label = document.querySelector<HTMLElement>('[data-fx-graph-label^="mix"]')!
    expect(label.textContent).toContain('…')
    expect(label.textContent?.startsWith('LX2')).toBe(true)
    expect(label.title).toBe('LX2 Shehds 19x15 Stage Right · mix of 2')
  })
})

describe('the name column (follow-ups D6)', () => {
  it('starts at 152px, and its grip steps 8px by the arrow keys within 96–280, kept per window', () => {
    measuredAt(600)
    act(() => setFxGraphOption('gutter', GUTTER_DEFAULT_PX))
    draw()
    const grip = screen.getByRole('separator', { name: 'Name column width' })
    expect(grip).toHaveAttribute('aria-valuenow', '152')
    fireEvent.keyDown(grip, { key: 'ArrowRight' })
    expect(JSON.parse(sessionStorage.getItem('programmer.fx.graph')!)).toMatchObject({ gutter: 160 })
    fireEvent.pointerDown(grip, { button: 0, clientX: 160, clientY: 10 })
    fireEvent.pointerMove(window, { clientX: 460, clientY: 10 })
    expect(grip).toHaveAttribute('aria-valuenow', '280')
    fireEvent.pointerUp(window, { clientX: 460, clientY: 10 })
    expect(JSON.parse(sessionStorage.getItem('programmer.fx.graph')!)).toMatchObject({ gutter: 280 })
  })

  it('gives way down to 72px where the window’s width would leave the plot under 160px', () => {
    // 180px of column is 156 of lanes: 152 of names would leave no plot.
    act(() => setFxGraphOption('gutter', GUTTER_DEFAULT_PX))
    draw()
    expect(document.querySelector<HTMLElement>('[data-fx-playhead]')!.style.left).toBe('72px')
    cleanup()
    // 400px is 376 of lanes: the names take 212 and leave the plot its 160.
    measuredAt(400)
    act(() => setFxGraphOption('gutter', 280))
    draw()
    expect(document.querySelector<HTMLElement>('[data-fx-playhead]')!.style.left).toBe('212px')
    // The grip reports and steps the window's width, never the narrowed one, so a key press keeps it.
    const grip = screen.getByRole('separator', { name: 'Name column width' })
    expect(grip).toHaveAttribute('aria-valuenow', '280')
    act(() => setFxGraphOption('gutter', GUTTER_DEFAULT_PX))
    cleanup()
    measuredAt(180)
    draw()
    fireEvent.keyDown(screen.getByRole('separator', { name: 'Name column width' }), { key: 'ArrowRight' })
    expect(JSON.parse(sessionStorage.getItem('programmer.fx.graph')!)).toMatchObject({ gutter: 160 })
  })

  it('draws no grip under a coarse pointer, where the width is the default', () => {
    coarse = true
    measuredAt(600)
    draw()
    expect(screen.queryByRole('separator', { name: 'Name column width' })).toBeNull()
    expect(document.querySelector<HTMLElement>('[data-fx-playhead]')!.style.left).toBe(`${GUTTER_DEFAULT_PX}px`)
  })
})
