// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'

/**
 * The picker: the whole library that fits the selection, as a searchable pad grid.
 *
 * What is pinned here is what the row above it cannot say — the four sections and their membership
 * rules, that search reaches all of them, that a press applies **and leaves the panel open**, that
 * ⌥ takes the other route, and that the three forms come from the cell editor's two media queries
 * rather than from anything of the picker's own.
 */
const applyTemplate = vi.fn()
const toggleTemplate = vi.fn()
let library: TemplateSummary[] = []

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: library }),
  useApplyTemplateMutation: () => [
    (args: unknown) => {
      applyTemplate(args)
      return { unwrap: () => Promise.resolve({ written: 1, skipped: [] }) }
    },
  ],
  useToggleTemplateMutation: () => [
    (args: unknown) => {
      toggleTemplate(args)
      return { unwrap: () => Promise.resolve({ action: 'applied', effectCount: 0 }) }
    },
  ],
  useCreateTemplateFromProgrammerMutation: () => [vi.fn(), { isLoading: false, reset: vi.fn() }],
}))

vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: [] }) }))
// The mask a press carries — none here; `TemplateStrip.test.tsx` pins the masked send.
vi.mock('@/store/selection', () => ({ usePressFamilies: () => null }))

// Counted, not stubbed: the real ordering still runs, but the picker must not call it while shut.
const recentTemplatesSpy = vi.hoisted(() => vi.fn())
vi.mock('@/lib/templateRecents', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/lib/templateRecents')>()
  return {
    ...real,
    recentTemplates: (...args: Parameters<typeof real.recentTemplates>) => {
      recentTemplatesSpy()
      return real.recentTemplates(...args)
    },
  }
})

// An effect pad's detail line names its live speed master, which is a subscription. Nothing here
// is about the tempo, so the bank is one unnamed master.
vi.mock('@/store/speedMasters', () => ({ useSpeedMasterDisplay: () => undefined }))

const newSheetProps = vi.fn()
vi.mock('./NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: (props: { open: boolean }) => {
    newSheetProps(props)
    return props.open ? <div data-testid="new-sheet" /> : null
  },
}))

/** The form the cell editor would choose. Swapped per test — the picker asks, it does not decide. */
const form = vi.hoisted(() => ({ current: 'popover' as 'popover' | 'bottom-sheet' | 'side-sheet' }))
vi.mock('@/components/editor/EditorSurface', () => ({
  useEditorForm: () => form.current,
}))

const { TemplatePicker } = await import('./TemplatePicker')

const HEX_1: TemplateTarget[] = [{ type: 'fixture', key: 'hex-1' }]
const COLOUR_CELL: CellRef<ColumnKey>[] = [{ rowId: 'fixture:hex-1', col: 'colour' }]

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
    lastPressedAt: null,
    rows: [
      { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' },
    ],
    effect: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

const PRESSED = template({ id: 1, uuid: 'u1', name: 'Amber Key', lastPressedAt: '2026-09-14T10:00:00.000Z' })
const GENERIC = template({ id: 2, uuid: 'u2', name: 'Blue Wash' })
const PER_FIXTURE = template({
  id: 3,
  uuid: 'u3',
  name: 'Front Focus',
  family: 'POSITION',
  isGeneric: false,
  rows: [{ targetType: 'fixture', targetKey: 'hex-1', propertyName: 'position', value: 'deg:12,-8' }],
})
const EFFECT = template({
  id: 4,
  uuid: 'u4',
  name: 'Amber Breathe',
  kind: 'effect',
  rows: [],
  effect: {
    effectType: 'ColourPulse',
    category: 'colour',
    beatDivision: 0.5,
    blendMode: 'OVERRIDE',
    distribution: 'LINEAR',
    parameters: {},
    timingSource: 'BEAT',
  },
})

const OFFERABLE = [PRESSED, GENERIC, PER_FIXTURE, EFFECT]

function picker(over: Partial<React.ComponentProps<typeof TemplatePicker>> = {}) {
  const anchor = { current: document.createElement('button') }
  return (
    <TemplatePicker
      projectId={1}
      open
      onOpenChange={() => {}}
      anchorRef={anchor}
      cells={COLOUR_CELL}
      askedFamilies={['COLOUR']}
      targets={HEX_1}
      offerable={OFFERABLE}
      {...over}
    />
  )
}

/** The panel, whichever of the three shapes it is in — all three are Radix dialogs. */
const panel = () => screen.getByRole('dialog')

/** A section's pads, by the section's own label. */
function sectionPads(label: string): string[] {
  const heading = within(panel()).getByText(label)
  const grid = heading.parentElement?.nextElementSibling
  return [...(grid?.children ?? [])].map((pad) => pad.querySelector('span')?.textContent ?? '')
}

beforeEach(() => {
  applyTemplate.mockClear()
  toggleTemplate.mockClear()
  newSheetProps.mockClear()
  form.current = 'popover'
  recentTemplatesSpy.mockClear()
  library = [...OFFERABLE, template({ id: 9, uuid: 'u9', name: 'Not offered', family: 'BEAM' })]
})
afterEach(cleanup)

describe('TemplatePicker', () => {
  it('sorts the library into Recent, All, Per fixture and Effects', () => {
    render(picker())
    expect(sectionPads('Recent')).toEqual(['Amber Key'])
    // `All` is the generic **value** templates: a per-fixture one holds a value per head and an
    // effect template holds no rows at all, so each gets a section that says what it is.
    expect(sectionPads('All')).toEqual(['Amber Key', 'Blue Wash'])
    expect(sectionPads('Per fixture')).toEqual(['Front Focus'])
    expect(sectionPads('Effects')).toEqual(['Amber Breathe'])
  })

  it('hides a section with nothing in it rather than drawing an empty grid', () => {
    render(picker({ offerable: [GENERIC] }))
    expect(within(panel()).queryByText('Recent')).not.toBeInTheDocument()
    expect(within(panel()).queryByText('Effects')).not.toBeInTheDocument()
    expect(sectionPads('All')).toEqual(['Blue Wash'])
  })

  it('filters every section from the one search field', () => {
    render(picker())
    fireEvent.change(within(panel()).getByLabelText('Search templates'), {
      target: { value: 'amber' },
    })
    expect(sectionPads('Recent')).toEqual(['Amber Key'])
    expect(sectionPads('All')).toEqual(['Amber Key'])
    expect(sectionPads('Effects')).toEqual(['Amber Breathe'])
    expect(within(panel()).queryByText('Per fixture')).not.toBeInTheDocument()
  })

  it('says how much of the library fits the selection', () => {
    render(picker())
    expect(within(panel()).getByText('4 of 5 fit the selection')).toBeInTheDocument()
  })

  it('applies on a click and stays open, so three colours can be auditioned in a row', async () => {
    render(picker())
    const pads = within(panel()).getAllByTitle(/Click to set these values/)
    await act(async () => {
      fireEvent.click(pads[0])
    })
    expect(applyTemplate).toHaveBeenCalledWith({ projectId: 1, templateId: 1, targets: HEX_1 })
    expect(toggleTemplate).not.toHaveBeenCalled()
    expect(panel()).toBeInTheDocument()
  })

  it('⌥click adds a tracking layer instead, masked to the template’s family', async () => {
    render(picker())
    await act(async () => {
      fireEvent.click(within(panel()).getAllByTitle(/Click to set these values/)[0], { altKey: true })
    })
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: HEX_1,
      propertyMask: 'COLOUR',
    })
    expect(applyTemplate).not.toHaveBeenCalled()
  })

  it('names the family and the cell count where cells fix the scope', () => {
    render(picker())
    // By the badge rather than by the word: "Colour" is also every colour pad's detail line.
    expect(panel().querySelector('[data-slot="badge"]')).toHaveTextContent('Colour')
    expect(within(panel()).getByText(/for 1 cell$/)).toBeInTheDocument()
  })

  it('offers the family segments instead where only rows are selected', () => {
    render(picker({ cells: [], askedFamilies: null }))
    // The segments are the library's `PartitionChips`, and picking one narrows every section.
    fireEvent.click(within(panel()).getByText('Position'))
    expect(sectionPads('Per fixture')).toEqual(['Front Focus'])
    expect(within(panel()).queryByText('Effects')).not.toBeInTheDocument()
  })

  it('is a sheet on a phone and a popover at a desk', () => {
    form.current = 'bottom-sheet'
    const { unmount } = render(picker())
    expect(panel()).toHaveAttribute('data-slot', 'sheet-content')
    // The gesture hint is the popover's alone: ⌥ is a key a phone has not got, and the hold it
    // would describe is the press the operator has just made.
    expect(within(panel()).queryByText(/⌥click/)).not.toBeInTheDocument()
    unmount()

    form.current = 'popover'
    render(picker())
    expect(panel()).toHaveAttribute('data-slot', 'popover-content')
    expect(within(panel()).getByText(/⌥click/)).toBeInTheDocument()
  })

  it('renders nothing to press with no targets, and still keeps the New sheet mounted', () => {
    // D3, the strip's own rule — and the sheet sits outside it because the desk selection is
    // shared, so `targets` can empty while a template name is half typed.
    render(picker({ targets: [] }))
    expect(screen.queryByLabelText('Search templates')).not.toBeInTheDocument()
    expect(newSheetProps).toHaveBeenCalled()
  })

  it('computes nothing while it is shut, however often its inputs change identity', () => {
    // It is mounted unconditionally beside the strip, and a marquee drag mints a fresh `offerable`
    // array every animation frame. Ungated, every one of those frames re-filtered and re-sorted the
    // whole library and rebuilt the pad grid, all discarded because the panel was closed.
    const { rerender } = render(picker({ open: false }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    for (let frame = 0; frame < 5; frame++) {
      rerender(picker({ open: false, offerable: [...OFFERABLE] }))
    }
    expect(recentTemplatesSpy).not.toHaveBeenCalled()

    rerender(picker({ open: true }))
    expect(recentTemplatesSpy).toHaveBeenCalled()
    expect(sectionPads('Recent')).toEqual(['Amber Key'])
  })

  it('refuses a mouse hold, because a mouse has ⌥', () => {
    // The pad shares `useTemplatePressHandlers` with the chip. A mouse hold would be a second,
    // silent door to the tracking mutation: a paused pointer would add a layer where literals were
    // meant, with nothing on screen saying which happened.
    vi.useFakeTimers()
    try {
      render(picker())
      const pad = within(panel()).getAllByTitle(/Click to set these values/)[0]
      fireEvent.pointerDown(pad, { pointerType: 'mouse' })
      vi.advanceTimersByTime(1200)
      expect(toggleTemplate).not.toHaveBeenCalled()

      fireEvent.pointerDown(pad, { pointerType: 'touch' })
      vi.advanceTimersByTime(1200)
      expect(toggleTemplate).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('hands New from selection the same targets a press would use', () => {
    render(picker())
    fireEvent.click(within(panel()).getByText('New from selection'))
    expect(newSheetProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ open: true, targets: HEX_1, families: ['COLOUR'] }),
    )
  })
})
