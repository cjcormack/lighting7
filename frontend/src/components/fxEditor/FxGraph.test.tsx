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
const { resetFxEditorViewStores, setFxGraphOption, useFxGraphOptions } = await import('@/lib/fxEditorView')

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
  // The lanes' column measures 180px, so the plot starts at x 72 and is 80px wide: a sample is 20px.
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width: 180, height: 400, left: 0, top: 0 }) as DOMRect)
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
      empty={false}
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

const labels = (lane: string) => canvases.get(`${lane} graph`)!.texts.map((t) => t.text)
const playheadX = () => document.querySelector<HTMLElement>('[data-fx-playhead]')!.style.transform
const runFrames = () =>
  act(() => {
    const due = frames
    frames = []
    due.forEach((fn) => fn(now))
  })

describe('the graph', () => {
  it('says it is asking before the desk answers, and what it lacks with no FX', () => {
    draw(null)
    expect(screen.getByText('Asking the desk…')).toBeTruthy()
    draw(null, { empty: true })
    expect(screen.getByText('No FX reaches the selection.')).toBeTruthy()
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
    const props = { error: null, empty: false, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }
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
    expect(options).toEqual({ window: '8b', view: 'this', rigOrder: true, asSeen: true, paused: true })
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
})

describe('reading the answer as the stage runs it', () => {
  const props = { error: null, empty: false, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }

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
    const props = { error: null, empty: false, narrow: false, fxName: (t: string) => t, fixtureName: () => undefined, solo: null, onUnsolo: () => {}, contextOf }
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
    const edge = handle('cycle')!
    expect(edge.style.left).toBe('108px')
    fireEvent.pointerDown(edge, { button: 0, clientX: 112, clientY: 10 })
    // 40px further is twice the cycle; 44px is still twice it, on the step.
    fireEvent.pointerMove(window, { clientX: 152, clientY: 10 })
    fireEvent.pointerMove(window, { clientX: 156, clientY: 10 })
    expect(editor.set.mock.calls).toEqual([[{ cycle: 8 }, false]])
    expect(document.querySelector('[data-fx-handle-label]')).toHaveTextContent('2 bars')
    fireEvent.pointerUp(window, { clientX: 156, clientY: 10 })
    expect(editor.set).toHaveBeenLastCalledWith({ cycle: 8 }, true)
    expect(document.querySelector('[data-fx-handle-label]')).toBeNull()
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

  it('drags the chase line for Spread, the grabbed row’s start following the finger', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    // Hex 2 is rank 1 of 2: half a cycle of offset for each unit of Spread. 20px is 24 samples, a
    // half-cycle, so Spread rises by one.
    drag(handle('spread')!, { x: 92, y: 30 }, { x: 112, y: 30 })
    expect(editor.set).toHaveBeenLastCalledWith({ spread: 2 }, true)
  })

  it('drags a row block sideways for Phase', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    drag(screen.getByLabelText('Intensity graph'), { x: 100, y: 15 }, { x: 120, y: 15 })
    // 24 samples right is half a cycle less Phase, wrapped.
    expect(editor.set).toHaveBeenLastCalledWith({ phaseOffset: 0.5 }, true)
  })

  it('drags an Intensity FX’s max and min lines, each row on its own scale', () => {
    const editor = editorOf()
    draw(handleAnswer(), { editor })
    const max = document.querySelector('[data-fx-handle="max"][data-row="hex-1"]')!
    // Hex 1's full is y 2 and its baseline y 27: 12.5px down is half way.
    drag(max, { x: 100, y: 2 }, { x: 100, y: 14.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('max', '128', true)
    const min = document.querySelector('[data-fx-handle="min"][data-row="hex-2"]')!
    drag(min, { x: 100, y: 55 }, { x: 100, y: 42.5 })
    expect(editor.setParam).toHaveBeenLastCalledWith('min', '128', true)
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

  it('lights the mark the inspector’s hovered control changes', () => {
    draw(handleAnswer(), { highlight: 'starts' })
    expect(canvases.get('Intensity graph')!.pixel(72, 1)).toBe('#c026d3')
  })
})
