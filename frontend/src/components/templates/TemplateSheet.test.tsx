// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateEffect, TemplateSummary } from '@/api/templatesApi'
import type { SpeedMaster } from '@/api/speedMastersApi'

const saveTemplate = vi.fn((_args: unknown) => Promise.resolve({}))
const copyTemplate = vi.fn((_args: unknown) => Promise.resolve({}))
const deleteTemplate = vi.fn((_args: unknown): Promise<unknown> => Promise.resolve(undefined))
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
vi.mock('@/store/templates', () => ({
  useSaveTemplateMutation: () => [(args: unknown) => ({ unwrap: () => saveTemplate(args) })],
  useCopyTemplateMutation: () => [(args: unknown) => ({ unwrap: () => copyTemplate(args) })],
  useDeleteTemplateMutation: () => [(args: unknown) => ({ unwrap: () => deleteTemplate(args) })],
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

import { TemplateSheet, pressedAgo, templateRowId, type TemplateSheetRow } from './TemplateSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

const U = (n: number) => `bbbbbbbb-0000-0000-0000-00000000000${n}`
function master(n: number, name: string): SpeedMaster {
  return { id: n, uuid: U(n), masterIndex: n, name, bpm: 120, source: 'MANUAL', notes: null, referenceCount: 0 }
}
const MASTERS = [master(1, 'Global'), master(2, 'Colour'), master(3, 'Movement'), master(4, 'Strobe')]

function effect(over: Partial<TemplateEffect> = {}): TemplateEffect {
  return {
    effectType: 'Dimmer Pulse',
    category: 'dimmer',
    beatDivision: 0.25,
    blendMode: 'OVERRIDE',
    distribution: 'LINEAR',
    parameters: {},
    timingSource: 'BEAT',
    ...over,
  }
}

function template(id: number, over: Partial<TemplateSummary>): TemplateSummary {
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
    rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:100' }],
    effect: null,
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
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:50' }],
})
// An effect template's empty `rows` is omitted on the wire: no `rows` field at all.
const PULSE = template(3, { name: 'Pulse', kind: 'effect', rows: undefined, effect: effect({ speedMasterUuid: U(4) }) })
const AMBER = template(4, {
  name: 'Amber',
  family: 'COLOUR',
  fadeDurationMs: 1000,
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' }],
})
const RAINBOW = template(5, {
  name: 'Rainbow',
  family: 'COLOUR',
  kind: 'effect',
  rows: undefined,
  effect: effect({ effectType: 'Rainbow Cycle', category: 'colour', beatDivision: 4, timingSource: 'WALL_CLOCK' }),
})
const SPECIALS = template(6, {
  name: 'Band Specials',
  family: 'COLOUR',
  isGeneric: false,
  rows: [
    { targetType: 'fixture', targetKey: 'par-1', propertyName: 'rgbColour', value: '#FF0000;policy=rgbonly' },
    { targetType: 'fixture', targetKey: 'par-2', propertyName: 'rgbColour', value: '#00FF00;policy=rgbonly' },
  ],
})
const LIBRARY = [FULL, HALF, PULSE, AMBER, RAINBOW, SPECIALS]
const ROWS: TemplateSheetRow[] = LIBRARY.map((t) => ({ id: templateRowId(t.id), template: t }))

/** Stubbed column bands, left to right in `TemplateSheet`'s order — as the marquee measures them. */
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

function draw(over: Partial<React.ComponentProps<typeof TemplateSheet>> = {}) {
  const onOpenTemplate = vi.fn()
  render(
    <TemplateSheet
      projectId={1}
      rows={ROWS}
      library={LIBRARY}
      masters={MASTERS}
      isCurrentProject
      projectName="Hamlet"
      onOpenTemplate={onOpenTemplate}
      {...over}
    />,
  )
  return { onOpenTemplate }
}

function row(id: number): HTMLElement {
  return document.querySelector(`[data-row-id="tmpl:${id}"]`) as HTMLElement
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
  saveTemplate.mockClear()
  copyTemplate.mockClear()
  deleteTemplate.mockReset()
  deleteTemplate.mockImplementation(() => Promise.resolve(undefined))
  pickUp.mockClear()
  toastError.mockClear()
  toastInfo.mockClear()
})

describe('TemplateSheet — the columns', () => {
  it('reads Value in the template’s own grammar, from `rows ?? []`, and says which kind each holds', () => {
    draw()
    expect(row(2)).toHaveTextContent('50%')
    expect(row(4)).toHaveTextContent('#FF9D4A · ')
    expect(row(6)).toHaveTextContent('2 heads · per fixture')
    // An effect template holds no rows at all on the wire — its Value is the effect and its speed.
    expect(row(3)).toHaveTextContent('Dimmer Pulse · ')
    expect(row(5)).toHaveTextContent('Rainbow Cycle · 4s')
    expect(row(3)).toHaveTextContent('Effect')
    expect(row(1)).toHaveTextContent('Value')
  })

  it('draws a cell with nothing to set blank — an effect’s Fade, a value’s Master — and names the master', () => {
    draw()
    expect(row(3).querySelector('[data-cell="fade"] button')).toBeNull()
    expect(row(1).querySelector('[data-cell="master"] button')).toBeNull()
    expect(row(3)).toHaveTextContent('M4 Strobe')
    // A wall-clock effect's null rate master is unscaled, not master 1.
    expect(row(5)).toHaveTextContent('unscaled')
    // An empty but settable Fade is the em-dash; a set one its seconds.
    expect(within(row(1).querySelector('[data-cell="fade"]') as HTMLElement).getByText('—')).toBeInTheDocument()
    expect(row(2)).toHaveTextContent('2s')
  })
})

describe('TemplateSheet — Fade', () => {
  it('Set over three rows writes the value templates in ms with the presence flag, and skips the effect by name', async () => {
    draw()
    drag('fade', 1, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(await screen.findByText('Pulse runs an effect — no fade to time · skipped')).toBeInTheDocument()
    const field = screen.getByLabelText('Fade')
    fireEvent.change(field, { target: { value: '1.5' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(saveTemplate.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, templateId: 1, fadeDurationMs: 1500, fadeDurationMsPresent: true },
      { projectId: 1, templateId: 2, fadeDurationMs: 1500, fadeDurationMsPresent: true },
    ])
  })

  it('Enter on an untouched null fade writes nothing — it opens at 0, which is what the default lands at', async () => {
    draw()
    drag('fade', 1, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Fade')
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(saveTemplate).not.toHaveBeenCalled()
  })

  it('Clear writes the default (none) — a null with the presence flag — where a fade is set', async () => {
    draw()
    drag('fade', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    await waitFor(() => expect(saveTemplate).toHaveBeenCalledTimes(1))
    expect(saveTemplate).toHaveBeenCalledWith({ projectId: 1, templateId: 2, fadeDurationMs: null, fadeDurationMsPresent: true })
  })

  it('offers Spread as the kit’s duration plan over the fades', () => {
    draw()
    drag('fade', 1, 2)
    expect(screen.getByRole('button', { name: /Spread/ })).toBeEnabled()
  })
})

describe('TemplateSheet — Master', () => {
  it('writes the beat master on a BEAT effect — the whole effect, one field changed', async () => {
    draw()
    drag('master', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M2 · Colour' }))
    expect(saveTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 3,
      effect: { ...PULSE.effect, speedMasterUuid: U(2) },
    })
  })

  it('writes master 1 as null on a beat effect, the spelling every other writer uses', async () => {
    draw()
    drag('master', 3, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'M1 · Global' }))
    expect(saveTemplate).toHaveBeenCalledWith({ projectId: 1, templateId: 3, effect: { ...PULSE.effect, speedMasterUuid: null } })
  })

  it('a beat effect on master 1 still takes a Master before the bank has loaded', () => {
    draw({ masters: [] })
    // Not blank and not skipped: a null beat master is master 1 whether or not its uuid is known.
    expect(row(3).querySelector('[data-cell="master"] button')).not.toBeNull()
    const beatOnM1 = template(8, { name: 'Pulse M1', kind: 'effect', rows: undefined, effect: effect() })
    cleanup()
    draw({ masters: [], rows: [{ id: templateRowId(8), template: beatOnM1 }], library: [beatOnM1] })
    expect(row(8).querySelector('[data-cell="master"] button')).not.toBeNull()
    expect(row(8)).toHaveTextContent('M1')
  })

  it('writes the rate master on a WALL_CLOCK effect, and offers Unscaled there', async () => {
    draw()
    drag('master', 5, 5)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent)
    expect(options[0]).toBe('Unscaled')
    fireEvent.click(screen.getByRole('option', { name: 'M3 · Movement' }))
    expect(saveTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 5,
      effect: { ...RAINBOW.effect, rateSpeedMasterUuid: U(3) },
    })
    expect(saveTemplate.mock.calls[0][0]).not.toHaveProperty('effect.speedMasterUuid')
  })

  it('Clear writes M1 on a beat effect and unscaled on a wall-clock one — both the stored null', async () => {
    const running = template(7, {
      name: 'Rainbow Fast',
      family: 'COLOUR',
      kind: 'effect',
      rows: undefined,
      effect: effect({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: U(2) }),
    })
    const lib = [FULL, HALF, PULSE, running]
    draw({ rows: lib.map((t) => ({ id: templateRowId(t.id), template: t })), library: lib })
    const start = row(3)
    fireEvent.pointerDown(start, { button: 0, clientX: CENTRE('master'), clientY: 2 * 36 + 10 })
    fireEvent.pointerMove(start, { button: 0, buttons: 1, clientX: CENTRE('master') + 4, clientY: 3 * 36 + 26 })
    fireEvent.pointerUp(start, { button: 0, clientX: CENTRE('master') + 4, clientY: 3 * 36 + 26 })
    fireEvent.click(within(start).getAllByRole('button').find((b) => b.closest('[data-cell="master"]'))!)
    fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
    await waitFor(() => expect(saveTemplate).toHaveBeenCalledTimes(2))
    expect(saveTemplate.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, templateId: 3, effect: { ...PULSE.effect, speedMasterUuid: null } },
      { projectId: 1, templateId: 7, effect: { ...running.effect, rateSpeedMasterUuid: null } },
    ])
  })
})

/** The rows each saved `PUT {rows}` carried, as `property=value` in order — the grammar the desk stores. */
function sentRows(call: number): string[] {
  const body = saveTemplate.mock.calls[call][0] as { rows: { propertyName: string; value: string }[] }
  return body.rows.map((r) => `${r.propertyName}=${r.value}`)
}

describe('TemplateSheet — Value (session 3)', () => {
  it('edits a colour template in the editor’s own control, and a white row forces the policy to RGB only', async () => {
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
    expect(saveTemplate).toHaveBeenCalledTimes(1)
    expect(saveTemplate.mock.calls[0][0]).toMatchObject({ projectId: 1, templateId: 4 })
    // The stored `policy=extract` is written back as `rgbonly`: the white/extract pair is what the
    // write boundary refuses by name, and the lifted rows builder is what keeps the cell from sending it.
    expect(sentRows(0)).toEqual(['rgbColour=#FF9D4A;policy=rgbonly', 'white=dmx:255'])
    expect(saveTemplate.mock.calls[0][0]).toEqual({
      projectId: 1,
      templateId: 4,
      rows: [
        { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=rgbonly', sortOrder: 0 },
        { targetType: 'deferred', targetKey: '', propertyName: 'white', value: 'dmx:255', sortOrder: 1 },
      ],
    })
  })

  it('writes a typed hex from Enter in its field, and nothing at all from Enter on an untouched editor', async () => {
    draw()
    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const hex = await screen.findByLabelText('Hex colour')
    fireEvent.keyDown(hex, { key: 'Enter' })
    expect(saveTemplate).not.toHaveBeenCalled()
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
    const lower = template(4, { ...AMBER, rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#ff9d4a;policy=extract' }] })
    draw({ rows: [...ROWS.slice(0, 3), { id: templateRowId(4), template: lower }] })
    drag('value', 4, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.keyDown(await screen.findByLabelText('Hex colour'), { key: 'Enter' })
    expect(saveTemplate).not.toHaveBeenCalled()

    drag('value', 1, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const [level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(saveTemplate).not.toHaveBeenCalled()
  })

  it('writes only the origin’s family over a mixed marquee, and names the rest as skipped', async () => {
    draw()
    // Full and Half are Intensity, Pulse is an effect, Amber is Colour.
    drag('value', 1, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const editor = (await screen.findByText('The intent — the desk resolves it per head')).closest(
      '[data-template-value-editor]',
    ) as HTMLElement
    expect(within(editor).getByText('Pulse runs an effect — open to change · skipped')).toBeInTheDocument()
    expect(within(editor).getByText('Amber is Colour · skipped')).toBeInTheDocument()
    // The label line counts what the commit reaches, not what the marquee covers.
    expect(within(editor).getByText('2 templates')).toBeInTheDocument()
    const [level] = within(editor).getAllByRole('slider')
    fireEvent.keyDown(level, { key: 'ArrowLeft' })
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(saveTemplate.mock.calls.map(([a]) => (a as { templateId: number }).templateId)).toEqual([1, 2])
    expect(sentRows(0)).toEqual(['dimmer=pct:99'])
    expect(sentRows(1)).toEqual(['dimmer=pct:99'])
  })

  it('lands what changed over each template’s own values — a sibling keeps the properties the operator did not touch', async () => {
    // Full holds a level; Strobed holds a level and a strobe. Replacing Strobed's rows with Full's
    // would delete its strobe — on an untouched Enter as much as on an edit.
    const strobed = template(7, {
      name: 'Strobed',
      rows: [
        { targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:50' },
        { targetType: 'deferred', targetKey: '', propertyName: 'strobe', value: 'pct:25' },
      ],
    })
    draw({ rows: [ROWS[0], { id: templateRowId(7), template: strobed }] })
    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    let [level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'Enter' })
    expect(saveTemplate).not.toHaveBeenCalled()

    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    ;[level] = await screen.findAllByRole('slider')
    fireEvent.keyDown(level, { key: 'ArrowLeft' })
    fireEvent.keyDown(level, { key: 'Enter' })
    await waitFor(() => expect(saveTemplate).toHaveBeenCalledTimes(2))
    expect(saveTemplate.mock.calls.map(([a]) => (a as { templateId: number }).templateId)).toEqual([1, 7])
    expect(sentRows(0)).toEqual(['dimmer=pct:99'])
    expect(sentRows(1)).toEqual(['dimmer=pct:99', 'strobe=pct:25'])
  })

  it('skips, by name, a sibling a removal would leave with no value', async () => {
    const both = template(8, {
      name: 'Both',
      rows: [
        { targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:40' },
        { targetType: 'deferred', targetKey: '', propertyName: 'strobe', value: 'pct:10' },
      ],
    })
    // Clearing Both's level leaves its strobe; the same removal would empty Full.
    draw({ rows: [{ id: templateRowId(8), template: both }, ROWS[0]] })
    drag('value', 1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    await screen.findAllByRole('slider')
    fireEvent.click(screen.getAllByRole('button', { name: 'Clear' })[0])
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(saveTemplate.mock.calls.map(([a]) => (a as { templateId: number }).templateId)).toEqual([8])
    expect(sentRows(0)).toEqual(['strobe=pct:10'])
    expect(toastInfo).toHaveBeenCalledWith('Full would hold no value — skipped', { id: 'sheet-write:templates:value-skip' })
  })

  it('keeps a per-fixture and an effect template as read-outs — no editor, the read-out drawn', () => {
    draw()
    for (const id of [3, 5, 6]) {
      expect(row(id).querySelector('[data-cell="value"]')).toBeNull()
    }
    expect(row(6)).toHaveTextContent('2 heads · per fixture')
    expect(row(5)).toHaveTextContent('Rainbow Cycle · 4s')
    expect(row(4).querySelector('[data-cell="value"] button')).not.toBeNull()
  })

  it('refuses Clear — a template holds a value — and offers no Spread', () => {
    draw()
    drag('value', 1, 2)
    const clear = screen.getByRole('button', { name: 'Clear cells' })
    expect(clear).toBeDisabled()
    expect(clear).toHaveAttribute('title', 'A template holds a value — set another one instead')
    expect(screen.getByRole('button', { name: /Spread/ })).toBeDisabled()
  })

  it('is inert in another project’s library — template PUTs are the running project’s', () => {
    draw({ isCurrentProject: false, projectName: 'Rehearsal Room' })
    expect(row(4).querySelector('[data-cell="value"] button')).toBeDisabled()
  })
})

describe('TemplateSheet — the row verbs', () => {
  it('Pick up acts on one template: disabled over two with the reason, the hand over one', () => {
    draw()
    selectRows(1, 2)
    const pick = screen.getByRole('button', { name: 'Pick up' })
    expect(pick).toBeDisabled()
    expect(pick).toHaveAttribute('title', 'Pick up takes one template — select just one')
    selectRows(4)
    fireEvent.click(screen.getByRole('button', { name: 'Pick up' }))
    expect(pickUp).toHaveBeenCalledWith('TEMPLATE', 4)
  })

  it('Duplicate names each copy (Copy n) against the library and the batch, through the copy route', async () => {
    draw({ library: [...LIBRARY, template(9, { name: 'Full (Copy)' })] })
    selectRows(1, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(copyTemplate).toHaveBeenCalledTimes(2))
    expect(copyTemplate.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, templateId: 1, targetProjectId: 1, newName: 'Full (Copy 2)' },
      { projectId: 1, templateId: 2, targetProjectId: 1, newName: 'Half (Copy)' },
    ])
  })

  it('Delete sends each plain, holds a padded one unsent, and asks once over every kind of use', async () => {
    deleteTemplate.mockImplementation((args) => {
      const { templateId, force } = args as { templateId: number; force: boolean }
      if (templateId === 1 && !force) {
        return Promise.reject({
          status: 409,
          data: {
            error: 'in use',
            code: 'TEMPLATE_IN_USE',
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
    draw({ rows: lib.map((t) => ({ id: templateRowId(t.id), template: t })), library: lib })
    selectRows(1, 2, 4)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog')
    // Amber was never sent: its pads go silently with it, so it is asked about first.
    expect(deleteTemplate.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, templateId: 1, force: false },
      { projectId: 1, templateId: 2, force: false },
    ])
    expect(within(dialog).getByText('Half was deleted.', { exact: false })).toBeInTheDocument()
    expect(dialog).toHaveTextContent(
      '1 cue layer — Q4 · named by 2 effect parameters, which would run as white · 1 programmer layer applying it now, stopped at once',
    )
    expect(dialog).toHaveTextContent('on 2 busk pages')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deleteTemplate).toHaveBeenCalledTimes(4))
    expect(deleteTemplate.mock.calls.slice(2).map(([a]) => a)).toEqual([
      // Full's uses were named by the desk, so it is forced; Amber's pads were the only use the
      // dialog could name, so it is sent plain — and nothing else uses it, so plain deletes it.
      { projectId: 1, templateId: 1, force: true },
      { projectId: 1, templateId: 4, force: false },
    ])
  })
})

describe('TemplateSheet — a padded template that is also in use', () => {
  it('is held for its pads, sent plain on Delete anyway, and forced only once the desk has named its uses', async () => {
    deleteTemplate.mockImplementation((args) => {
      const { force } = args as { force: boolean }
      return force
        ? Promise.resolve(undefined)
        : Promise.reject({
            status: 409,
            data: { error: 'in use', code: 'TEMPLATE_IN_USE', layerCount: 0, cueIds: [], cueNames: [], fxReferenceCount: 1, runningCount: 0 },
          })
    })
    const padded = { ...AMBER, buskPageCount: 1 }
    draw({ rows: [{ id: templateRowId(padded.id), template: padded }], library: [padded] })
    selectRows(4)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    let dialog = await screen.findByRole('alertdialog')
    expect(deleteTemplate).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deleteTemplate).toHaveBeenCalledTimes(1))
    expect(deleteTemplate.mock.calls[0][0]).toEqual({ projectId: 1, templateId: 4, force: false })
    dialog = await screen.findByRole('alertdialog')
    await waitFor(() =>
      expect(dialog).toHaveTextContent('named by 1 effect parameter, which would run as white · on 1 busk page'),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deleteTemplate).toHaveBeenCalledTimes(2))
    expect(deleteTemplate.mock.calls[1][0]).toEqual({ projectId: 1, templateId: 4, force: true })
  })
})

describe('TemplateSheet — another project’s library (D12)', () => {
  it('disables every value verb with the reason, keeps Copy to… live, and draws no Pick up', () => {
    const { onOpenTemplate } = draw({ isCurrentProject: false, projectName: 'Rehearsal Room' })
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
    expect(onOpenTemplate).not.toHaveBeenCalled()
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
    await waitFor(() => expect(copyTemplate).toHaveBeenCalledTimes(2))
    expect(copyTemplate.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 2, templateId: 1, targetProjectId: 1, newName: undefined },
      { projectId: 2, templateId: 2, targetProjectId: 1, newName: undefined },
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
