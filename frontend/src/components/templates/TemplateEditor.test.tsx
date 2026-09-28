// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { TemplateInput, TemplateSummary } from '@/api/templatesApi'

/**
 * The editor's two identity choices and the write boundary's first rule.
 *
 * **Holds** is the session's whole point — a template holds a value *or* an effect, never both —
 * and three things about it are load-bearing rather than cosmetic:
 *
 *  - it is **locked after creation**, like the family, because the cook's template arm reads one
 *    half or the other and flipping it is a different template;
 *  - **Effect is refused under Beam**, and shown disabled with the reason rather than omitted,
 *    because the backend refuses `beam` *by name* and an operator has to learn where a beam chase
 *    lives instead;
 *  - a save sends **exactly one** of `rows` / `effect`, because a body naming the other half is a
 *    400 rather than a no-op.
 */
const library: EffectLibraryEntry[] = [
  {
    name: 'Colour Pulse',
    category: 'colour',
    outputType: 'COLOUR',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    description: 'Pulses between two colours on the beat.',
    parameters: [{ name: 'colourA', type: 'Colour', defaultValue: '#FF9D4A', description: '' }],
    compatibleProperties: ['rgbColour'],
  },
  {
    name: 'Rainbow Cycle',
    category: 'colour',
    outputType: 'COLOUR',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleProperties: ['rgbColour'],
  },
  {
    name: 'Sine Dim',
    category: 'dimmer',
    outputType: 'LEVEL',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleProperties: ['dimmer'],
  },
]

vi.mock('@/store/fixtureFx', () => ({ useEffectLibraryQuery: () => ({ data: library }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: [] }),
  useVisibleFixtureListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/speedMasters', () => ({
  // The D8 stamp: an effect authored under Colour picks up the master whose usage is `colour`.
  useSpeedMasterForCategory: () => (category: string | null) =>
    category === 'colour' ? 'master-2-uuid' : null,
  useSpeedMasterDisplay: () => null,
  useSpeedMasterBpm: () => 120,
  useMaster1Uuid: () => 'master-1-uuid',
  useSpeedMasterLiveQuery: () => ({ data: [] }),
}))
vi.mock('@/store/templates', () => ({
  useResolveTemplateMutation: () => [
    vi.fn(() => ({ unwrap: () => Promise.resolve({ entries: [] }) })),
    { isLoading: false },
  ],
  useTemplateListQuery: () => ({ data: [], isLoading: false }),
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

const { TemplateEditor } = await import('./TemplateEditor')

function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
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
      { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' },
    ],
    effect: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

function renderEditor(over: { template?: TemplateSummary | null; onSave?: (i: TemplateInput) => Promise<void> } = {}) {
  const onSave = over.onSave ?? vi.fn(async () => {})
  render(
    <TemplateEditor
      open
      onOpenChange={() => {}}
      projectId={1}
      template={over.template ?? null}
      onSave={onSave}
      isSaving={false}
    />,
  )
  return { onSave }
}

/** Radix's `Select` needs a real pointer API that jsdom does not ship. */
function chooseEffect(name: string) {
  fireEvent.click(screen.getByRole('combobox'))
  fireEvent.click(screen.getByRole('option', { name }))
}

// Radix's Slider (the beam and intensity controls) measures its thumb; jsdom has no
// ResizeObserver, so stub an inert one — the same stub `EffectParameterForm.test.tsx` uses.
beforeEach(() => {
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
  const body = onSave.mock.calls[onSave.mock.calls.length - 1][0] as TemplateInput
  return Object.fromEntries((body.rows ?? []).map((row) => [row.propertyName, row.value]))
}

describe('the colour control', () => {
  it('seeds the policy from the stored value, not from a UI default', () => {
    // `parseTemplateIntent` reads a colour with no policy token as `rgbonly`, matching every other
    // reader of that string. The control defaulted to `extract`, so a token-less row opened showing
    // Extract selected and was rewritten as Extract on the next unrelated edit.
    renderEditor({
      template: template({
        rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A' }],
      }),
    })
    expect(screen.getByRole('button', { name: 'RGB only' })).toHaveAttribute('data-variant', 'default')
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'outline')
  })

  it('starts a new colour template on Extract, the default a wash wants', () => {
    // Distinct from the stored-value case below: a draft with *no* colour row has not been authored
    // yet, so it takes the authoring default rather than the `rgbonly` a token-less stored row
    // parses as. Collapsing the two made every new colour template default to RGB only.
    renderEditor()
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'default')
  })

  it('locks the policy to RGB only once an emitter is set directly', async () => {
    // Extract and Additive drive the same emitter byte the explicit row names, so the desk refuses
    // the pair at the write boundary. Said here first, or a 400 is the first an operator hears of it.
    const { onSave } = renderEditor({ template: template() })
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
      template: template({
        rows: [
          { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' },
          { targetType: 'deferred', targetKey: '', propertyName: 'white', value: 'dmx:180' },
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
    const { onSave } = renderEditor({ template: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set White' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Clear White' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toHaveAttribute('data-variant', 'default')

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const rows = await savedRows(onSave as ReturnType<typeof vi.fn>)
    expect(rows.rgbColour).toBe('#FF9D4A;policy=extract')
  })

  it('leaves UV free to sit beside a policy — no policy has ever driven it', () => {
    renderEditor({ template: template() })
    fireEvent.click(screen.getByRole('button', { name: 'Set UV' }))
    expect(screen.getByRole('button', { name: 'Extract' })).toBeEnabled()
  })

  it('clears the colour row, so a UV-only template is authorable', async () => {
    // The reason this control needed a Clear at all: without one the colour row cannot be removed,
    // and a template of emitters alone cannot be made.
    const { onSave } = renderEditor({ template: template() })
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
    const { onSave } = renderEditor({ template: template() })
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
    expect(screen.getByRole('button', { name: 'Effect' })).toBeEnabled()
  })

  it('is locked once the template exists, as the family is', () => {
    // Both halves: the segment itself, and the sentence that says why — a disabled control with no
    // reason reads as breakage rather than as an identity.
    renderEditor({ template: template() })
    expect(screen.getByRole('button', { name: 'Value' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Effect' })).toBeDisabled()
    expect(screen.getByText(/cannot change afterwards/i)).toBeTruthy()
  })

  it('refuses Effect under Beam, disabled with the reason rather than omitted', () => {
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Beam' }))
    expect(screen.getByRole('button', { name: 'Effect' })).toBeDisabled()
    expect(screen.getByText(/no beam category/i)).toBeTruthy()
    // Where a beam chase *does* live, because "disabled" on its own teaches nobody.
    expect(screen.getByText(/recorded look/i)).toBeTruthy()
  })

  it('drops an effect draft when the family moves under it', () => {
    // The effect was chosen from the old family's category, so carrying it across would leave the
    // draft claiming a family its effect derives a different one from — which the server refuses.
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    chooseEffect('Colour Pulse')
    expect(screen.getByText(/pulses between two colours/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Intensity' }))
    expect(screen.queryByText(/pulses between two colours/i)).toBeNull()
  })
})

describe('the effect branch', () => {
  it('offers only the family’s own category', () => {
    // The family is the filter, which is why there is no category step to repeat.
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    fireEvent.click(screen.getByRole('combobox'))
    expect(screen.getByRole('option', { name: 'Colour Pulse' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'Rainbow Cycle' })).toBeTruthy()
    expect(screen.queryByRole('option', { name: 'Sine Dim' })).toBeNull()
  })

  it('hides Fade, which an effect has no arrival to time', () => {
    renderEditor()
    expect(screen.getByLabelText(/fade/i)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    expect(screen.queryByLabelText(/fade/i)).toBeNull()
  })
})

describe('validity and the save body', () => {
  it('needs a name and one of the two halves', () => {
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    const create = screen.getByRole('button', { name: /create template/i })
    // A name with no effect is not enough, and an effect with no name is not either.
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Amber Breathe' } })
    expect(create).toBeDisabled()
    chooseEffect('Colour Pulse')
    expect(create).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '  ' } })
    expect(create).toBeDisabled()
  })

  it('sends `effect` and no `rows`, stamped with the family’s usage master', async () => {
    // Never both halves: a body naming the other one is a 400 rather than a no-op. And the master
    // is stamped at authoring time — nothing resolves usage later, so an unstamped effect would
    // silently run on master 1 forever.
    const { onSave } = renderEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Effect' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Amber Breathe' } })
    chooseEffect('Colour Pulse')
    fireEvent.click(screen.getByRole('button', { name: /create template/i }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as TemplateInput
    expect(body.rows).toBeUndefined()
    expect(body.effect).toMatchObject({
      effectType: 'Colour Pulse',
      category: 'colour',
      propertyName: null,
      speedMasterUuid: 'master-2-uuid',
    })
    // Parameters seed from the effect's own declared defaults rather than arriving empty.
    expect(body.effect?.parameters).toEqual({ colourA: '#FF9D4A' })
    // An effect has no fade, and hiding the field has to clear the value rather than leave it.
    expect(body.fadeDurationMs).toBeNull()
  })

  it('sends `rows` and no `effect` for a value template', async () => {
    const { onSave } = renderEditor({ template: template() })
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Warm Amber' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onSave).toHaveBeenCalled())
    const body = (onSave as ReturnType<typeof vi.fn>).mock.calls[0][0] as TemplateInput
    expect(body.effect).toBeUndefined()
    expect(body.rows).toHaveLength(1)
  })
})

