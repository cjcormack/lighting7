// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PaletteFx, PaletteSummary } from '@/api/palettesApi'
import type { SpeedMaster } from '@/api/speedMastersApi'

const savePalette = vi.fn((_args: unknown) => Promise.resolve({}))
const copyPalette = vi.fn((_args: unknown) => Promise.resolve({}))
const deletePalette = vi.fn((_args: unknown): Promise<unknown> => Promise.resolve(undefined))
const pickUp = vi.fn()
const toastError = vi.fn()
const toastInfo = vi.fn()

vi.mock('sonner', () => ({
  toast: {
    error: (...a: unknown[]) => toastError(...a),
    info: (...a: unknown[]) => toastInfo(...a),
    success: () => {},
  },
}))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/store/palettes', () => ({
  useSavePaletteMutation: () => [(args: unknown) => ({ unwrap: () => savePalette(args) })],
  useCopyPaletteMutation: () => [(args: unknown) => ({ unwrap: () => copyPalette(args) })],
  useDeletePaletteMutation: () => [(args: unknown) => ({ unwrap: () => deletePalette(args) })],
}))
vi.mock('@/store/hand', () => ({ handPickUp: (...a: unknown[]) => pickUp(...a) }))
vi.mock('@/store/projects', () => ({
  useProjectListQuery: () => ({
    data: [
      { id: 1, name: 'Hamlet', isCurrent: true },
      { id: 2, name: 'Rehearsal Room', isCurrent: false },
    ],
  }),
}))

import { PaletteSheet, pressedAgo, paletteRowId, type PaletteSheetRow } from './PaletteSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

const U = (n: number) => `bbbbbbbb-0000-0000-0000-00000000000${n}`
function master(n: number, name: string): SpeedMaster {
  return { id: n, uuid: U(n), masterIndex: n, name, bpm: 120, source: 'MANUAL', notes: null, referenceCount: 0 }
}
const MASTERS = [master(1, 'Global'), master(2, 'Colour'), master(3, 'Movement'), master(4, 'Strobe')]

function effect(over: Partial<PaletteFx> = {}): PaletteFx {
  return {
    fxType: 'Dimmer Pulse',
    family: 'intensity',
    cycle: 0.25,
    blendMode: 'OVERRIDE',
    order: 'NORMAL',
    parameters: {},
    timingSource: 'BEAT',
    ...over,
  }
}

function template(id: number, over: Partial<PaletteSummary>): PaletteSummary {
  return {
    id,
    uuid: `t${id}`,
    name: `T${id}`,
    notes: null,
    fadeDurationMs: null,
    family: 'INTENSITY',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    rows: [{ targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:100' }],
    fx: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

/** The board's library, less its dividers: two intensity values, a beat effect, a colour, a wall-clock effect. */
const FULL = template(1, { name: 'Full' })
const HALF = template(2, {
  name: 'Half',
  fadeDurationMs: 2000,
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:50' }],
})
// An effect template's empty `rows` is omitted on the wire: no `rows` field at all.
const PULSE = template(3, { name: 'Pulse', kind: 'fx', rows: undefined, fx: effect({ speedMasterUuid: U(4) }) })
const AMBER = template(4, {
  name: 'Amber',
  family: 'COLOUR',
  fadeDurationMs: 1000,
  rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A;policy=extract' }],
})
const RAINBOW = template(5, {
  name: 'Rainbow',
  family: 'COLOUR',
  kind: 'fx',
  rows: undefined,
  fx: effect({ fxType: 'Rainbow Cycle', family: 'colour', cycle: 4, timingSource: 'WALL_CLOCK' }),
})
const SPECIALS = template(6, {
  name: 'Band Specials',
  family: 'COLOUR',
  isGeneric: false,
  rows: [
    { targetType: 'fixture', targetKey: 'par-1', attribute: 'rgbColour', value: '#FF0000;policy=rgbonly' },
    { targetType: 'fixture', targetKey: 'par-2', attribute: 'rgbColour', value: '#00FF00;policy=rgbonly' },
  ],
})
const LIBRARY = [FULL, HALF, PULSE, AMBER, RAINBOW, SPECIALS]
const ROWS: PaletteSheetRow[] = LIBRARY.map((t) => ({ id: paletteRowId(t.id), palette: t }))

/** Stubbed column bands, left to right in `PaletteSheet`'s order — as the marquee measures them. */
const BANDS: Record<string, [number, number]> = {
  value: [244, 444],
  fade: [444, 516],
  master: [516, 636],
  notes: [636, 800],
}
const CENTRE = (col: string) => (BANDS[col][0] + BANDS[col][1]) / 2

function stubLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 180)
    const col = this.getAttribute('data-column-header')
    if (col && BANDS[col]) return rect(...BANDS[col])
    return rect(0, 1100)
  })
}

function draw(over: Partial<React.ComponentProps<typeof PaletteSheet>> = {}) {
  const onOpenPalette = vi.fn()
  render(
    <PaletteSheet
      projectId={1}
      rows={ROWS}
      library={LIBRARY}
      masters={MASTERS}
      isCurrentProject
      projectName="Hamlet"
      onOpenPalette={onOpenPalette}
      {...over}
    />,
  )
  return { onOpenPalette }
}

function row(id: number): HTMLElement {
  return document.querySelector(`[data-row-id="pal:${id}"]`) as HTMLElement
}

/** A marquee down one column over the rows at 1-based positions `from`..`to` (36px rows). */
function drag(col: string, from: number, to: number) {
  const start = row(LIBRARY[from - 1].id)
  fireEvent.pointerDown(start, { button: 0, clientX: CENTRE(col), clientY: (from - 1) * 36 + 10 })
  fireEvent.pointerMove(start, { button: 0, buttons: 1, clientX: CENTRE(col) + 4, clientY: (to - 1) * 36 + 26 })
  fireEvent.pointerUp(start, { button: 0, clientX: CENTRE(col) + 4, clientY: (to - 1) * 36 + 26 })
  const cell = within(start).queryAllByRole('button').find((b) => b.closest(`[data-cell="${col}"]`))
  fireEvent.click(cell ?? start)
}

function selectRows(...ids: number[]) {
  ids.forEach((id, i) => fireEvent.click(row(id).querySelector('[data-first-column]')!, { metaKey: i > 0 }))
}

beforeEach(() => {
  stubLayout()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  resetEditorSurfaceMedia()
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  savePalette.mockClear()
  copyPalette.mockClear()
  deletePalette.mockReset()
  deletePalette.mockImplementation(() => Promise.resolve(undefined))
  pickUp.mockClear()
  toastError.mockClear()
  toastInfo.mockClear()
})

describe('PaletteSheet — the columns', () => {
  it('reads Value in the palette’s own grammar, from `rows ?? []`, and says which kind each holds', () => {
    draw()
    expect(row(2)).toHaveTextContent('50%')
    expect(row(4)).toHaveTextContent('#FF9D4A · ')
    expect(row(6)).toHaveTextContent('2 heads · per fixture')
    // An effect template holds no rows at all on the wire — its Value is the effect and its speed.
    expect(row(3)).toHaveTextContent('Dimmer Pulse · ')
    expect(row(5)).toHaveTextContent('Rainbow Cycle · 4 s')
    expect(row(3)).toHaveTextContent('FX')
    expect(row(1)).toHaveTextContent('Value')
  })

  it('draws a cell with nothing to set blank — an effect’s Fade, a value’s Master — and names the master', () => {
    draw()
    expect(row(3).querySelector('[data-cell="fade"] button')).toBeNull()
    expect(row(1).querySelector('[data-cell="master"] button')).toBeNull()
    expect(row(3)).toHaveTextContent('M4 Strobe')
    // A wall-clock effect's null rate master is Seconds (D7), not master 1.
    expect(row(5)).toHaveTextContent('Seconds')
    // An empty but settable Fade is the em-dash; a set one its seconds.
    expect(within(row(1).querySelector('[data-cell="fade"]') as HTMLElement).getByText('—')).toBeInTheDocument()
    expect(row(2)).toHaveTextContent('2s')
  })
})

describe('PaletteSheet — Fade', () => {
  it('Set over three rows writes the value palettes in ms with the presence flag, and skips the effect by name', async () => {
    draw()
    drag('fade', 1, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(await screen.findByText('Pulse runs an FX — no fade to time · skipped')).toBeInTheDocument()
    const field = screen.getByLabelText('Fade')
    fireEvent.change(field, { target: { value: '1.5' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(savePalette.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, paletteId: 1, fadeDurationMs: 1500, fadeDurationMsPresent: true },
      { projectId: 1, paletteId: 2, fadeDurationMs: 1500, fadeDurationMsPresent: true },
    ])
  })

  it('Enter on an untouched null fade writes nothing — it opens at 0, which is what the default lands at', async () => {
    draw()
    drag('fade', 1, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Fade')
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(savePalette).not.toHaveBeenCalled()
  })

  it('Clear writes the default (none) — a null with the presence flag — where a fade is set', async () => {
    draw()
    drag('fade', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    await waitFor(() => expect(savePalette).toHaveBeenCalledTimes(1))
    expect(savePalette).toHaveBeenCalledWith({ projectId: 1, paletteId: 2, fadeDurationMs: null, fadeDurationMsPresent: true })
  })

  it('offers Fan as the kit’s duration plan over the fades', () => {
    draw()
    drag('fade', 1, 2)
    expect(screen.getByRole('button', { name: /Fan/ })).toBeEnabled()
  })
})

describe('PaletteSheet — Master', () => {
  it('writes the beat master on a BEAT effect — the whole effect, one field changed', async () => {
    draw()
    drag('master', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M2 · Colour' }))
    expect(savePalette).toHaveBeenCalledWith({
      projectId: 1,
      paletteId: 3,
      fx: { ...PULSE.fx, speedMasterUuid: U(2) },
    })
  })

  it('writes master 1 as null on a beat effect, the spelling every other writer uses', async () => {
    draw()
    drag('master', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M1 · Global' }))
    expect(savePalette).toHaveBeenCalledWith({ projectId: 1, paletteId: 3, fx: { ...PULSE.fx, speedMasterUuid: null } })
  })

  it('puts a beat effect on Seconds keeping its speed, and offers it before every master (D7)', async () => {
    draw()
    drag('master', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(options[0]).toBe('Seconds')
    fireEvent.click(screen.getByRole('option', { name: 'Seconds' }))
    // The beats at the master's stored tempo (every master here is 120 BPM): half a second a beat.
    const sent = savePalette.mock.calls[0][0] as { fx: { cycle: number; seconds: boolean; speedMasterUuid: null } }
    expect(sent.fx.seconds).toBe(true)
    expect(sent.fx.speedMasterUuid).toBeNull()
    expect(sent.fx.cycle).toBe(PULSE.fx!.cycle / 2)
  })

  it('a beat effect on master 1 still takes a Master before the bank has loaded', () => {
    draw({ masters: [] })
    // Not blank and not skipped: a null beat master is master 1 whether or not its uuid is known.
    expect(row(3).querySelector('[data-cell="master"] button')).not.toBeNull()
    const beatOnM1 = template(8, { name: 'Pulse M1', kind: 'fx', rows: undefined, fx: effect() })
    cleanup()
    draw({ masters: [], rows: [{ id: paletteRowId(8), palette: beatOnM1 }], library: [beatOnM1] })
    expect(row(8).querySelector('[data-cell="master"] button')).not.toBeNull()
    expect(row(8)).toHaveTextContent('M1')
  })

  it('writes the rate master on a WALL_CLOCK effect, and offers Seconds first', async () => {
    draw()
    drag('master', 5, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(options[0]).toBe('Seconds')
    fireEvent.click(screen.getByRole('option', { name: 'M3 · Movement' }))
    expect(savePalette).toHaveBeenCalledWith({
      projectId: 1,
      paletteId: 5,
      fx: { ...RAINBOW.fx, rateSpeedMasterUuid: U(3) },
    })
    expect(savePalette.mock.calls[0][0]).not.toHaveProperty('effect.speedMasterUuid')
  })

  it('Clear writes M1 on a beat effect and Seconds on a wall-clock one — both the stored null', async () => {
    const running = template(7, {
      name: 'Rainbow Fast',
      family: 'COLOUR',
      kind: 'fx',
      rows: undefined,
      fx: effect({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: U(2) }),
    })
    const lib = [FULL, HALF, PULSE, running]
    draw({ rows: lib.map((t) => ({ id: paletteRowId(t.id), palette: t })), library: lib })
    const start = row(3)
    fireEvent.pointerDown(start, { button: 0, clientX: CENTRE('master'), clientY: 2 * 36 + 10 })
    fireEvent.pointerMove(start, { button: 0, buttons: 1, clientX: CENTRE('master') + 4, clientY: 3 * 36 + 26 })
    fireEvent.pointerUp(start, { button: 0, clientX: CENTRE('master') + 4, clientY: 3 * 36 + 26 })
    fireEvent.click(within(start).getAllByRole('button').find((b) => b.closest('[data-cell="master"]'))!)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    await waitFor(() => expect(savePalette).toHaveBeenCalledTimes(2))
    expect(savePalette.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, paletteId: 3, fx: { ...PULSE.fx, speedMasterUuid: null } },
      { projectId: 1, paletteId: 7, fx: { ...running.fx, rateSpeedMasterUuid: null } },
    ])
  })
})

/** The rows each saved `PUT {rows}` carried, as `property=value` in order — the grammar the desk stores. */
function sentRows(call: number): string[] {
  const body = savePalette.mock.calls[call][0] as { rows: { attribute: string; value: string }[] }
  return body.rows.map((r) => `${r.attribute}=${r.value}`)
}

describe('PaletteSheet — Value (session 3)', () => {
  it('edits a colour palette in the editor’s own control, and a white row forces the policy to RGB only', async () => {
    draw()
    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    // The editor's control, lifted: the hex field, the policy buttons, the emitters.
    expect(await screen.findByLabelText('Hex colour')).toHaveValue('#FF9D4A')
    expect(screen.getByRole('button', { name: 'Extract' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Set White' }))
    // Shown first, with the reason: the policy would drive the same byte.
    expect(screen.getByRole('button', { name: 'Extract' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(savePalette).toHaveBeenCalledTimes(1)
    expect(savePalette.mock.calls[0][0]).toMatchObject({ projectId: 1, paletteId: 4 })
    // The stored `policy=extract` is written back as `rgbonly`: the white/extract pair is what the
    // write boundary refuses by name, and the lifted rows builder is what keeps the cell from sending it.
    expect(sentRows(0)).toEqual(['rgbColour=#FF9D4A;policy=rgbonly', 'white=dmx:255'])
    expect(savePalette.mock.calls[0][0]).toEqual({
      projectId: 1,
      paletteId: 4,
      rows: [
        { targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A;policy=rgbonly', sortOrder: 0 },
        { targetType: 'deferred', targetKey: '', attribute: 'white', value: 'dmx:255', sortOrder: 1 },
      ],
    })
  })

  it('writes a typed hex from Enter in its field, and nothing at all from Enter on an untouched editor', async () => {
    draw()
    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const hex = await screen.findByLabelText('Hex colour')
    fireEvent.keyDown(hex, { key: 'Enter' })
    expect(savePalette).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Hex colour')).not.toBeInTheDocument()

    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const again = await screen.findByLabelText('Hex colour')
    fireEvent.change(again, { target: { value: '#1030FF' } })
    fireEvent.keyDown(again, { key: 'Enter' })
    expect(sentRows(0)).toEqual(['rgbColour=#1030FF;policy=extract'])
  })

  it('Enter on an untouched slider editor writes nothing either — and a stored row the builder reads the same is no change', async () => {
    // A lower-case hex and no policy order the builder would write: the same intent, so no change.
    const lower = template(4, { ...AMBER, rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#ff9d4a;policy=extract' }] })
    draw({ rows: [...ROWS.slice(0, 3), { id: paletteRowId(4), palette: lower }] })
    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.keyDown(await screen.findByLabelText('Hex colour'), { key: 'Enter' })
    expect(savePalette).not.toHaveBeenCalled()

    drag('value', 1, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const [level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(savePalette).not.toHaveBeenCalled()
  })

  it('writes only the origin’s family over a mixed marquee, and names the rest as skipped', async () => {
    draw()
    // Full and Half are Intensity, Pulse is an effect, Amber is Colour.
    drag('value', 1, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const editor = (await screen.findByText('The intent — the desk resolves it per head')).closest(
      '[data-template-value-editor]',
    ) as HTMLElement
    expect(within(editor).getByText('Pulse runs an FX — open to change · skipped')).toBeInTheDocument()
    expect(within(editor).getByText('Amber is Colour · skipped')).toBeInTheDocument()
    // The label line counts what the commit reaches, not what the marquee covers.
    expect(within(editor).getByText('2 palettes')).toBeInTheDocument()
    const [level] = within(editor).getAllByRole('slider')
    fireEvent.keyDown(level, { key: 'ArrowLeft' })
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(savePalette.mock.calls.map(([a]) => (a as { paletteId: number }).paletteId)).toEqual([1, 2])
    expect(sentRows(0)).toEqual(['dimmer=pct:99'])
    expect(sentRows(1)).toEqual(['dimmer=pct:99'])
  })

  it('lands what changed over each palette’s own values — a sibling keeps the properties the operator did not touch', async () => {
    // Full holds a level; Strobed holds a level and a strobe. Replacing Strobed's rows with Full's
    // would delete its strobe — on an untouched Enter as much as on an edit.
    const strobed = template(7, {
      name: 'Strobed',
      rows: [
        { targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:50' },
        { targetType: 'deferred', targetKey: '', attribute: 'strobe', value: 'pct:25' },
      ],
    })
    draw({ rows: [ROWS[0], { id: paletteRowId(7), palette: strobed }] })
    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    let [level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(savePalette).not.toHaveBeenCalled()

    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    ;[level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'ArrowLeft' })
    fireEvent.keyDown(level, { key: 'Enter' })
    await waitFor(() => expect(savePalette).toHaveBeenCalledTimes(2))
    expect(savePalette.mock.calls.map(([a]) => (a as { paletteId: number }).paletteId)).toEqual([1, 7])
    expect(sentRows(0)).toEqual(['dimmer=pct:99'])
    expect(sentRows(1)).toEqual(['dimmer=pct:99', 'strobe=pct:25'])
  })

  it('skips, by name, a sibling a removal would leave with no value', async () => {
    const both = template(8, {
      name: 'Both',
      rows: [
        { targetType: 'deferred', targetKey: '', attribute: 'dimmer', value: 'pct:40' },
        { targetType: 'deferred', targetKey: '', attribute: 'strobe', value: 'pct:10' },
      ],
    })
    // Clearing Both's level leaves its strobe; the same removal would empty Full.
    draw({ rows: [{ id: paletteRowId(8), palette: both }, ROWS[0]] })
    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    await screen.findAllByRole('slider')
    fireEvent.click(screen.getAllByRole('button', { name: 'Clear' })[0])
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(savePalette.mock.calls.map(([a]) => (a as { paletteId: number }).paletteId)).toEqual([8])
    expect(sentRows(0)).toEqual(['strobe=pct:10'])
    expect(toastInfo).toHaveBeenCalledWith('Full would hold no value — skipped', { id: 'sheet-write:templates:value-skip' })
  })

  it('keeps a per-fixture and an effect palette as read-outs — no editor, the read-out drawn', () => {
    draw()
    for (const id of [3, 5, 6]) {
      expect(row(id).querySelector('[data-cell="value"]')).toBeNull()
    }
    expect(row(6)).toHaveTextContent('2 heads · per fixture')
    expect(row(5)).toHaveTextContent('Rainbow Cycle · 4 s')
    expect(row(4).querySelector('[data-cell="value"] button')).not.toBeNull()
  })

  it('refuses Clear — a palette holds a value — and offers no Fan', () => {
    draw()
    drag('value', 1, 2)
    const clear = screen.getByRole('button', { name: 'Clear cells' })
    expect(clear).toBeDisabled()
    expect(clear).toHaveAttribute('title', 'A palette holds a value — set another one instead')
    expect(screen.getByRole('button', { name: /Fan/ })).toBeDisabled()
  })

  it('is inert in another project’s library — palette PUTs are the running project’s', () => {
    draw({ isCurrentProject: false, projectName: 'Rehearsal Room' })
    expect(row(4).querySelector('[data-cell="value"] button')).toBeDisabled()
  })
})

describe('PaletteSheet — the row verbs', () => {
  it('Pick up acts on one palette: disabled over two with the reason, the hand over one', () => {
    draw()
    selectRows(1, 2)
    const pick = screen.getByRole('button', { name: 'Pick up' })
    expect(pick).toBeDisabled()
    expect(pick).toHaveAttribute('title', 'Pick up takes one palette — select just one')
    selectRows(4)
    fireEvent.click(screen.getByRole('button', { name: 'Pick up' }))
    expect(pickUp).toHaveBeenCalledWith('PALETTE', 4)
  })

  it('Duplicate names each copy (Copy n) against the library and the batch, through the copy route', async () => {
    draw({ library: [...LIBRARY, template(9, { name: 'Full (Copy)' })] })
    selectRows(1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(copyPalette).toHaveBeenCalledTimes(2))
    expect(copyPalette.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, paletteId: 1, targetProjectId: 1, newName: 'Full (Copy 2)' },
      { projectId: 1, paletteId: 2, targetProjectId: 1, newName: 'Half (Copy)' },
    ])
  })

  it('Delete sends each plain, holds a padded one unsent, and asks once over every kind of use', async () => {
    deletePalette.mockImplementation((args) => {
      const { paletteId, force } = args as { paletteId: number; force: boolean }
      if (paletteId === 1 && !force) {
        return Promise.reject({
          status: 409,
          data: {
            error: 'in use',
            code: 'PALETTE_IN_USE',
            layerCount: 1,
            cueIds: [4],
            cueNames: ['Q4'],
            fxReferenceCount: 2,
            runningCount: 1,
          },
        })
      }
      return Promise.resolve(undefined)
    })
    const padded = { ...AMBER, buskPageCount: 2 }
    const lib = [FULL, HALF, PULSE, padded]
    draw({ rows: lib.map((t) => ({ id: paletteRowId(t.id), palette: t })), library: lib })
    selectRows(1, 2, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog')
    // Amber was never sent: its pads go silently with it, so it is asked about first.
    expect(deletePalette.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, paletteId: 1, force: false },
      { projectId: 1, paletteId: 2, force: false },
    ])
    expect(within(dialog).getByText('Half was deleted.', { exact: false })).toBeInTheDocument()
    expect(dialog).toHaveTextContent(
      '1 cue layer — Q4 · named by 2 FX parameters, which would run as white · 1 programmer layer applying it now, stopped at once',
    )
    expect(dialog).toHaveTextContent('on 2 busk pages')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deletePalette).toHaveBeenCalledTimes(4))
    expect(deletePalette.mock.calls.slice(2).map(([a]) => a)).toEqual([
      // Full's uses were named by the desk, so it is forced; Amber's pads were the only use the
      // dialog could name, so it is sent plain — and nothing else uses it, so plain deletes it.
      { projectId: 1, paletteId: 1, force: true },
      { projectId: 1, paletteId: 4, force: false },
    ])
  })
})

describe('PaletteSheet — a padded palette that is also in use', () => {
  it('is held for its pads, sent plain on Delete anyway, and forced only once the desk has named its uses', async () => {
    deletePalette.mockImplementation((args) => {
      const { force } = args as { force: boolean }
      return force
        ? Promise.resolve(undefined)
        : Promise.reject({
            status: 409,
            data: { error: 'in use', code: 'PALETTE_IN_USE', layerCount: 0, cueIds: [], cueNames: [], fxReferenceCount: 1, runningCount: 0 },
          })
    })
    const padded = { ...AMBER, buskPageCount: 1 }
    draw({ rows: [{ id: paletteRowId(padded.id), palette: padded }], library: [padded] })
    selectRows(4)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    let dialog = await screen.findByRole('alertdialog')
    expect(deletePalette).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deletePalette).toHaveBeenCalledTimes(1))
    expect(deletePalette.mock.calls[0][0]).toEqual({ projectId: 1, paletteId: 4, force: false })
    dialog = await screen.findByRole('alertdialog')
    await waitFor(() =>
      expect(dialog).toHaveTextContent('named by 1 FX parameter, which would run as white · on 1 busk page'),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deletePalette).toHaveBeenCalledTimes(2))
    expect(deletePalette.mock.calls[1][0]).toEqual({ projectId: 1, paletteId: 4, force: true })
  })
})

describe('PaletteSheet — another project’s library (D12)', () => {
  it('disables every value verb with the reason, keeps Copy to… live, and draws no Pick up', () => {
    const { onOpenPalette } = draw({ isCurrentProject: false, projectName: 'Rehearsal Room' })
    selectRows(1, 2)
    const reason = 'Rehearsal Room’s library — copy it here to edit'
    expect(screen.getByText(reason)).toBeInTheDocument()
    for (const name of ['Duplicate', 'Delete']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
      expect(screen.getByRole('button', { name })).toHaveAttribute('title', reason)
    }
    expect(screen.queryByRole('button', { name: 'Pick up' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy to…' })).toBeEnabled()
    // No row opens there: the editor edits what the scope refuses.
    selectRows(1)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpenPalette).not.toHaveBeenCalled()
  })

  it('refuses Set and Clear over cells, but the marquee still selects', () => {
    draw({ isCurrentProject: false, projectName: 'Rehearsal Room' })
    drag('notes', 1, 2)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Clear cells' })).toBeDisabled()
  })

  it('Copy to… offers the running project first and copies each record there', async () => {
    draw({ projectId: 2, isCurrentProject: false, projectName: 'Rehearsal Room' })
    selectRows(1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Copy to…' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Copy' }))
    await waitFor(() => expect(copyPalette).toHaveBeenCalledTimes(2))
    expect(copyPalette.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 2, paletteId: 1, targetProjectId: 1, newName: undefined },
      { projectId: 2, paletteId: 2, targetProjectId: 1, newName: undefined },
    ])
  })
})

describe('pressedAgo', () => {
  it('reads a pressed stamp by parsing it, in the coarsest unit that fits', () => {
    const now = Date.parse('2026-09-24T12:00:00Z')
    expect(pressedAgo(null, now)).toBeNull()
    expect(pressedAgo(undefined, now)).toBeNull()
    expect(pressedAgo('2026-09-24T11:59:48Z', now)).toBe('12s ago')
    // `Instant.toString()` drops the fraction on an exact second; both spellings parse.
    expect(pressedAgo('2026-09-24T11:57:30.500Z', now)).toBe('2m ago')
    expect(pressedAgo('2026-09-24T11:00:00Z', now)).toBe('1h ago')
    expect(pressedAgo('2026-09-22T12:00:00Z', now)).toBe('2d ago')
    expect(pressedAgo('garbage', now)).toBeNull()
  })
})
