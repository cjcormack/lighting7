// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { PaletteInput, PaletteSummary } from '@/api/palettesApi'

/**
 * The editor's two identity choices and the write boundary's first rule.
 *
 * **Holds** is the session's whole point — a template holds a value *or* an effect, never both —
 * and three things about it are load-bearing rather than cosmetic:
 *
 *  - it is **locked after creation**, like the family, because the cook's template arm reads one
 *    half or the other and flipping it is a different template;
 *  - under **Beam** the effect is one of the beam built-ins (effects plan D6), as under any family
 *    the effect is one of that family's own;
 *  - a save sends **exactly one** of `rows` / `effect`, because a body naming the other half is a
 *    400 rather than a no-op.
 */
const library: FxLibraryEntry[] = [
  {
    name: 'Colour Pulse',
    family: 'colour',
    outputType: 'COLOUR',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    description: 'Pulses between two colours on the beat.',
    parameters: [{ name: 'colourA', type: 'Colour', defaultValue: '#FF9D4A', description: '' }],
    compatibleAttributes: ['rgbColour'],
  },
  {
    name: 'Rainbow Cycle',
    family: 'colour',
    outputType: 'COLOUR',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleAttributes: ['rgbColour'],
  },
  {
    name: 'Circle',
    family: 'position',
    outputType: 'POSITION',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [
      { name: 'panCentre', type: 'UByte', defaultValue: '128', description: '' },
      { name: 'tiltCentre', type: 'UByte', defaultValue: '128', description: '' },
      { name: 'panRadius', type: 'UByte', defaultValue: '64', description: '' },
      { name: 'tiltRadius', type: 'UByte', defaultValue: '64', description: '' },
    ],
    compatibleAttributes: ['position'],
  },
  {
    name: 'Zoom Wave',
    family: 'beam',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '64', description: '' }],
    compatibleAttributes: ['zoom'],
  },
  {
    // Intensity's level waves, which a beam attribute makes Beam's (fx-settings plan D5).
    name: 'Wave',
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [
      { name: 'shape', type: 'choice', defaultValue: 'SINE', description: '' },
      { name: 'low', type: 'ubyte', defaultValue: '0', description: '' },
      { name: 'high', type: 'ubyte', defaultValue: '255', description: '' },
    ],
    compatibleAttributes: ['dimmer', 'uv', 'zoom', 'iris'],
    attributeDefaults: { zoom: { low: '64', high: '192' }, iris: { low: '0', high: '160' } },
  },
  {
    name: 'Sine Dim',
    family: 'intensity',
    outputType: 'LEVEL',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleAttributes: ['dimmer'],
  },
  {
    name: 'PaletteSteps',
    family: 'steps',
    outputType: 'STEPS',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [
      { name: 'palettes', type: 'paletteList', defaultValue: '', description: '' },
      { name: 'fadeRatio', type: 'double', defaultValue: '0.5', description: '' },
    ],
    compatibleAttributes: ['dimmer', 'rgbColour', 'position', 'zoom', 'focus', 'iris', 'frost'],
  },
  {
    name: 'SlotSteps',
    // The Steps family's, as the desk files it (terminology plan D23); a palette stores it as Beam.
    family: 'steps',
    outputType: 'STEPS',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [{ name: 'slots', type: 'slotList', defaultValue: '', description: '' }],
    compatibleAttributes: ['setting'],
  },
]

/** The palette library and the rig, which the step effects read (effects plan §3.6). */
const wire = vi.hoisted(() => ({ templates: [] as unknown[], fixtures: [] as unknown[] }))

vi.mock('@/store/fixtureFx', () => ({ useFxLibraryQuery: () => ({ data: library }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: [] }),
  useVisibleFixtureListQuery: () => ({ data: wire.fixtures }),
}))
vi.mock('@/store/speedMasters', () => ({
  // The D8 stamp: an effect authored under Colour picks up the master whose family is `colour`.
  useSpeedMasterForFamily: () => (category: string | null) =>
    category === 'colour' ? 'master-2-uuid' : category === 'beam' ? 'master-3-uuid' : null,
  useSpeedMasterDisplay: () => null,
  useSpeedMasterBpm: () => 120,
  useMaster1Uuid: () => 'master-1-uuid',
  useSpeedMasterLiveQuery: () => ({ data: [] }),
}))
vi.mock('@/store/palettes', () => ({
  useResolvePaletteMutation: () => [
    vi.fn(() => ({ unwrap: () => Promise.resolve({ entries: [] }) })),
    { isLoading: false },
  ],
  usePaletteListQuery: () => ({ data: wire.templates, isLoading: false }),
}))
vi.mock('react-router', () => ({ useParams: () => ({ projectId: '1' }) }))
// The footer's *Add to busk page* control subscribes to the page document. This suite is about the
// form, and renders with no Provider, so the store module is stubbed like every other one here.
vi.mock('@/store/busk', () => ({
  useBuskPagesQuery: () => ({ data: [], isLoading: false }),
  useAddBuskPadMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
}))
// The speed-master picker reaches for the live bank; the branch under test is the form around it.
vi.mock('@/components/fx/SpeedMasterSelect', () => ({ SpeedMasterSelect: () => null }))
// The FX branch's graph strip asks the desk over the window's selection; its asks are
// `PaletteRunsOn.test.tsx`'s, and here the strip only waits.
vi.mock('@/components/fx/selectionFxTarget', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/fx/selectionFxTarget')>()),
  useWindowSelectionTargets: () => [],
}))
vi.mock('@/components/fxEditor/useFxPreview', () => ({ useFxPreview: () => ({ answer: null, error: null }) }))

const { PaletteEditor } = await import('./PaletteEditor')

function template(over: Partial<PaletteSummary> = {}): PaletteSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    rows: [
      { targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A;policy=extract' },
    ],
    fx: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

function renderEditor(over: { palette?: PaletteSummary | null; onSave?: (i: PaletteInput) => Promise<void> } = {}) {
  const onSave = over.onSave ?? vi.fn(async () => {})
  render(
    <PaletteEditor
      open
      onOpenChange={() => {}}
      projectId={1}
      palette={over.palette ?? null}
      onSave={onSave}
      isSaving={false}
    />,
  )
  return { onSave }
}

/** Radix's `Select` needs a real pointer API that jsdom does not ship. */
function chooseFx(name: string) {
  fireEvent.click(screen.getAllByRole('combobox')[0])
  fireEvent.click(screen.getByRole('option', { name }))
}

// Radix's Slider (the beam and intensity controls) measures its thumb; jsdom has no
// ResizeObserver, so stub an inert one — the same stub the effect editor's own tests use.
beforeEach(() => {
  wire.templates = []
  wire.fixtures = []
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

/** The rows a save produced, keyed by property — the shape every colour assertion below reads. */
async function savedRows(onSave: ReturnType<typeof vi.fn>) {
  await waitFor(() => expect(onSave).toHaveBeenCalled())
  const body = onSave.mock.calls[onSave.mock.calls.length - 1][0] as PaletteInput
  return Object.fromEntries((body.rows ?? []).map((row) => [row.attribute, row.value]))
}

describe('the colour control', () => {
  it('seeds the policy from the stored value, not from a UI default', () => {
    // `parsePaletteIntent` reads a colour with no policy token as `rgbonly`, matching every other
    // reader of that string. The control defaulted to `extract`, so a token-less row opened showing
    // Extract selected and was rewritten as Extract on the next unrelated edit.
    renderEditor({
      palette: template({
        rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A' }],
      }),
    })
    expect(screen.getByRole('button', { name: 'RGB only' })).toHaveAttribute('data-variant', 'default')
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'outline')
  })

  it('starts a new colour palette on Extract, the default a wash wants', () => {
    // Distinct from the stored-value case below: a draft with *no* colour row has not been authored
    // yet, so it takes the authoring default rather than the `rgbonly` a token-less stored row
    // parses as. Collapsing the two made every new colour template default to RGB only.
    renderEditor()
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'default')
  })

  it('locks the policy to RGB only once an emitter is set directly', async () => {
    // Extract and Additive drive the same emitter byte the explicit row names, so the desk refuses
    // the pair at the write boundary. Said here first, or a 400 is the first an operator hears of it.
    const { onSave } = renderEditor({ palette: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set White' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Additive' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'RGB only' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows.rgbColour).toBe('#FF9D4A;policy=rgbonly')
    expect(rows.white).toBe('dmx:255')
  })

  it('collapses a stored Extract-beside-white to RGB only on open, not just on edit', async () => {
    // The seed path is the editor's *second* writer: `seedValues` parses stored rows straight into
    // the draft without going through `setIntent`. While the rule lived in that setter, a template
    // carrying both would open showing Extract selected *and* disabled — and save the same refused
    // combination straight back into a 400. Deriving it at every read is what closes that.
    const { onSave } = renderEditor({
      palette: template({
        rows: [
          { targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#FF9D4A;policy=extract' },
          { targetType: 'deferred', targetKey: '', attribute: 'white', value: 'dmx:180' },
        ],
      }),
    })
    expect(screen.getByRole('button', { name: 'RGB only' })).toHaveAttribute('data-variant', 'default')
    expect(screen.getByRole('button', { name: 'Extract' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows.rgbColour).toBe('#FF9D4A;policy=rgbonly')
    expect(rows.white).toBe('dmx:180')
  })

  it('restores the stored policy when the emitter is cleared again', async () => {
    // The other half of deriving rather than mutating: nothing overwrote `extract`, so removing the
    // emitter puts the operator back where they were instead of silently leaving them on RGB only.
    const { onSave } = renderEditor({ palette: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set White' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Clear White' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'default')

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows.rgbColour).toBe('#FF9D4A;policy=extract')
  })

  it('leaves UV free to sit beside a policy — no policy has ever driven it', () => {
    renderEditor({ palette: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set UV' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toBeEnabled()
  })

  it('clears the colour row, so a UV-only palette is authorable', async () => {
    // The reason this control needed a Clear at all: without one the colour row cannot be removed,
    // and a template of emitters alone cannot be made.
    const { onSave } = renderEditor({ palette: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set UV' }))
    fireEvent.click(screen.getByRole('button', { name: 'Clear colour' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows).toEqual({ uv: 'dmx:255' })
  })

  it('distinguishes an emitter left off from one set to zero', async () => {
    // Absent is not zero: no row means the template says nothing about that emitter and leaves what
    // is under it alone, which is what lets a UV template sit over an amber wash. A row at 0 drives
    // it to 0. Setting then clearing must land back at "no row", not at `dmx:0`.
    const { onSave } = renderEditor({ palette: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set Amber' }))
    fireEvent.click(screen.getByRole('button', { name: 'Clear Amber' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows.amber).toBeUndefined()
    expect(Object.keys(rows)).toEqual(['rgbColour'])
  })
})

describe('the Holds choice', () => {
  it('starts on Value and offers Effect', () => {
    renderEditor()
    expect(screen.getByRole('button', { name: 'Value' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'FX' })).toBeEnabled()
  })

  it('is locked once the palette exists, as the family is', () => {
    // Both halves: the segment itself, and the sentence that says why — a disabled control with no
    // reason reads as breakage rather than as an identity.
    renderEditor({ palette: template() })
    expect(screen.getByRole('button', { name: 'Value' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'FX' })).toBeDisabled()
    expect(screen.getByText(/cannot change afterwards/i)).toBeTruthy()
  })

  it('offers Effect under Beam, with the beam built-ins', () => {
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Beam' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.click(screen.getByRole('combobox'))
    expect(screen.getByRole('option', { name: 'Zoom Wave' })).toBeTruthy()
    expect(screen.queryByRole('option', { name: 'Colour Pulse' })).toBeNull()
  })

  it('drops an effect draft when the family moves under it', () => {
    // The effect was chosen from the old family's category, so carrying it across would leave the
    // draft claiming a family its effect derives a different one from — which the server refuses.
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    chooseFx('Colour Pulse')
    expect(screen.getByText(/pulses between two colours/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Intensity' }))
    expect(screen.queryByText(/pulses between two colours/i)).toBeNull()
  })
})

describe('the effect branch', () => {
  it('offers only the family’s own category', () => {
    // The family is the filter, which is why there is no category step to repeat.
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.click(screen.getByRole('combobox'))
    expect(screen.getByRole('option', { name: 'Colour Pulse' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'Rainbow Cycle' })).toBeTruthy()
    expect(screen.queryByRole('option', { name: 'Sine Dim' })).toBeNull()
  })

  it('hides Fade, which an effect has no arrival to time', () => {
    renderEditor()
    expect(screen.getByLabelText(/fade/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    expect(screen.queryByLabelText(/fade/i)).toBeNull()
  })
})

describe('validity and the save body', () => {
  it('needs a name and one of the two halves', () => {
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    const create = screen.getByRole('button', { name: /create palette/i })
    // A name with no effect is not enough, and an effect with no name is not either.
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Amber Breathe' } })
    expect(create).toBeDisabled()
    chooseFx('Colour Pulse')
    expect(create).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '  ' } })
    expect(create).toBeDisabled()
  })

  it('sends `effect` and no `rows`, stamped with the family’s master', async () => {
    // Never both halves: a body naming the other one is a 400 rather than a no-op. And the master
    // is stamped at authoring time — nothing resolves the family later, so an unstamped effect would
    // silently run on master 1 forever.
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Amber Breathe' } })
    chooseFx('Colour Pulse')
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.rows).toBeUndefined()
    expect(body.fx).toMatchObject({
      fxType: 'Colour Pulse',
      family: 'colour',
      attribute: null,
      speedMasterUuid: 'master-2-uuid',
    })
    // Parameters seed from the effect's own declared defaults rather than arriving empty.
    expect(body.fx?.parameters).toEqual({ colourA: '#FF9D4A' })
    // An effect has no fade, and hiding the field has to clear the value rather than leave it.
    expect(body.fadeDurationMs).toBeNull()
  })

  it('saves a beam effect palette on the Beam master', async () => {
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Beam' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Zoom Wave' } })
    chooseFx('Zoom Wave')
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({
      fxType: 'Zoom Wave',
      family: 'beam',
      attribute: null,
      speedMasterUuid: 'master-3-uuid',
    })
  })

  it('saves a Wave in a Beam palette on the beam attribute it drives, at its defaults there, on the Beam master', async () => {
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Beam' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Iris breathe' } })
    chooseFx('Wave')
    fireEvent.click(screen.getByRole('combobox', { name: 'Attribute' }))
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Zoom', 'Iris'])
    fireEvent.click(screen.getByRole('option', { name: 'Iris' }))
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({
      fxType: 'Wave',
      family: 'intensity',
      attribute: 'iris',
      speedMasterUuid: 'master-3-uuid',
      parameters: { shape: 'SINE', low: '0', high: '160' },
    })
  })

  it('sends `rows` and no `effect` for a value palette', async () => {
    const { onSave } = renderEditor({ palette: template() })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Warm Amber' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toBeUndefined()
    expect(body.rows).toHaveLength(1)
  })
})

describe('Around in palettes (fixture-fx-sheets plan D16)', () => {
  it('saves a new movement palette as ADDITIVE with its centre pinned at 128, the centre hidden', async () => {
    // The bug that started the plan, in the busk view: a template stored Override, so a Circle pad
    // pressed after a position pad circled 128/128. A new one starts Around.
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Position' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Big circle' } })
    chooseFx('Circle')

    expect(screen.getByRole('radio', { name: 'Around current position' })).toHaveAttribute('data-state', 'on')
    // Around hides the centre: it is pinned, and a knob there would turn the orbit into an offset.
    expect(screen.queryByRole('slider', { name: 'Pan' })).toBeNull()
    expect(screen.getByRole('slider', { name: 'Pan size' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    // A new FX palette starts on Fixtures (FX editor follow-ups D8).
    expect(body.fx).toMatchObject({ fxType: 'Circle', blendMode: 'ADDITIVE', elementMode: 'WHOLE' })
    expect(body.fx?.parameters).toMatchObject({ panCentre: '128', tiltCentre: '128' })
  })

  it('reads a stored Override movement palette as Absolute, with its centre shown (call 6)', async () => {
    const { onSave } = renderEditor({
      palette: template({
        name: 'Old circle',
        family: 'POSITION',
        kind: 'fx',
        rows: undefined,
        fx: {
          fxType: 'Circle',
          family: 'position',
          attribute: null,
          cycle: 1,
          blendMode: 'OVERRIDE',
          order: 'NORMAL',
          phaseOffset: 0,
          parameters: { panCentre: '90', tiltCentre: '100', panRadius: '64', tiltRadius: '64' },
          speedMasterUuid: null,
          rateSpeedMasterUuid: null,
          timingSource: 'BEAT',
        },
      }),
    })
    expect(screen.getByRole('radio', { name: 'Absolute' })).toHaveAttribute('data-state', 'on')
    expect(screen.getByRole('slider', { name: 'Pan' })).toBeTruthy()

    // Choosing Around is an edit like any other, and saves the D12 spelling.
    fireEvent.click(screen.getByRole('radio', { name: 'Around current position' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({ blendMode: 'ADDITIVE' })
    expect(body.fx?.parameters).toMatchObject({ panCentre: '128', tiltCentre: '128' })
  })
})

describe('Over in a palette', () => {
  it.each([null, 'FLAT'])('keeps a stored palette’s element mode (%s) when its FX type is swapped', async (elementMode) => {
    const { onSave } = renderEditor({
      palette: template({
        name: 'Pulse',
        family: 'COLOUR',
        kind: 'fx',
        rows: undefined,
        fx: {
          fxType: 'Colour Pulse',
          family: 'colour',
          attribute: null,
          cycle: 4,
          blendMode: 'OVERRIDE',
          order: 'NORMAL',
          phaseOffset: 0,
          elementMode,
          parameters: { colourA: '#FF9D4A' },
          speedMasterUuid: null,
          rateSpeedMasterUuid: null,
          timingSource: 'BEAT',
        },
      }),
    })
    chooseFx('Rainbow Cycle')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({ fxType: 'Rainbow Cycle', elementMode })
  })

  it('draws Over on an FX palette and saves the choice', async () => {
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Colour' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Chase' } })
    chooseFx('Rainbow Cycle')
    expect(screen.getByRole('radio', { name: 'Fixtures' })).toHaveAttribute('data-state', 'on')
    fireEvent.click(screen.getByRole('radio', { name: 'Elements' }))
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    expect(((onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput).fx).toMatchObject({ elementMode: 'FLAT' })
  })
})

describe('size, direction and runs in a palette (effects plan session 1)', () => {
  const pulse = (over: object = {}) =>
    template({
      name: 'Warm pulse',
      family: 'COLOUR',
      kind: 'fx',
      rows: undefined,
      fx: {
        fxType: 'Colour Pulse',
        family: 'colour',
        attribute: null,
        cycle: 1,
        blendMode: 'OVERRIDE',
        order: 'NORMAL',
        phaseOffset: 0,
        parameters: { colourA: '#FF9D4A' },
        speedMasterUuid: null,
        rateSpeedMasterUuid: null,
        timingSource: 'BEAT',
        ...over,
      },
    })

  it('reads an edit of direction alone as unsaved, so closing asks first', async () => {
    renderEditor({ palette: pulse() })
    fireEvent.click(screen.getByRole('radio', { name: 'Bounce' }))
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(await screen.findByText('Discard changes?')).toBeTruthy()
  })

  it('carries size, direction and runs across a swap of the effect, as it carries the phase', async () => {
    const { onSave } = renderEditor({ palette: pulse({ amount: 0.4, direction: 'BACKWARD', runs: 2 }) })
    chooseFx('Rainbow Cycle')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({ fxType: 'Rainbow Cycle', amount: 0.4, direction: 'BACKWARD', runs: 2 })
  })
})


describe('the step effects (effects plan session 7)', () => {
  const position = (name: string, value: string): PaletteSummary =>
    template({
      id: name.length,
      uuid: `uuid-${name}`,
      name,
      family: 'POSITION',
      rows: [{ targetType: 'deferred', targetKey: '', attribute: 'position', value }],
    })

  it('offers Palette Steps where the family has two generic value palettes, filed under that family', async () => {
    wire.templates = [position('Centre', 'pos:0,0'), position('Downstage', 'pos:-64,32')]
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Position' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Walk' } })
    chooseFx('PaletteSteps')
    expect(screen.getByRole('list', { name: 'Palettes' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({
      fxType: 'PaletteSteps',
      family: 'position',
      attribute: null,
      parameters: { palettes: 'pal:uuid-Centre,pal:uuid-Downstage', fadeRatio: '0.5' },
    })
  })

  it('does not offer Palette Steps in a family with fewer than two', () => {
    wire.templates = [position('Centre', 'pos:0,0')]
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Position' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.click(screen.getByRole('combobox'))
    expect(screen.getByRole('option', { name: 'Circle' })).toBeTruthy()
    expect(screen.queryByRole('option', { name: 'PaletteSteps' })).toBeNull()
  })

  it('saves Slot Steps naming the setting it steps, from the rig, with its first slots', async () => {
    wire.fixtures = [
      {
        key: 'spot-1',
        attributes: [
          {
            type: 'setting',
            name: 'gobo',
            displayName: 'Gobo wheel',
            category: 'gobo',
            options: [
              { name: 'OPEN', level: 0, displayName: 'Open' },
              { name: 'GOBO_1', level: 10, displayName: 'Gobo 1' },
              { name: 'GOBO_2', level: 20, displayName: 'Gobo 2' },
            ],
          },
        ],
      },
    ]
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Beam' }))
    fireEvent.click(screen.getByRole('button', { name: 'FX' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Gobos' } })
    chooseFx('SlotSteps')
    expect(screen.getByRole('combobox', { name: 'Setting' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /create palette/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as PaletteInput
    expect(body.fx).toMatchObject({
      fxType: 'SlotSteps',
      family: 'beam',
      attribute: 'gobo',
      parameters: { slots: 'GOBO_1,GOBO_2' },
    })
  })
})
