// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * The strip's three load-bearing behaviours: **the selection is the filter**, **the selection is
 * the target**, and **the gestures are the press's** — a tap the toggle route, ⌥click the apply
 * route, a hold or right-click the menu and never a press (apply-model plan D1, D8).
 *
 * Since session 2 of the space plan there is a fourth: **it renders nothing with no targets**
 * (D3), which reverses the rule it shipped with. That one is pinned because it is a reversal a
 * reader will otherwise take for a bug and "fix".
 *
 * The filter is what makes the strip usable without a picker — select colour cells and only colour
 * templates are offered, select RGB pars and no position template is — so a regression there turns
 * it back into a list of everything. The target rule is what makes a marquee mean something: three
 * colour cells land the press on those three heads. And the tap/⌥click split is the difference
 * between a dependency and a literal, which is invisible on screen: only the route called says
 * which happened.
 *
 * Targets and their families arrive as props from the container, which is the only thing that
 * knows which rows the cells sit on; the tests hand them in directly.
 */
const applyTemplate = vi.fn()
const toggleTemplate = vi.fn()
let templates: TemplateSummary[] = []
/** What `apply` resolves with — the two arms report in different fields, so the tests set it. */
let applyResult: unknown = { written: 1, skipped: [] }

const toastSuccess = vi.fn()
const toastWarning = vi.fn()
vi.mock('sonner', () => ({
  toast: {
    success: (...args: unknown[]) => toastSuccess(...args),
    warning: (...args: unknown[]) => toastWarning(...args),
    error: vi.fn(),
  },
}))

vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: templates }),
  useApplyTemplateMutation: () => [
    (args: unknown) => {
      applyTemplate(args)
      return { unwrap: () => Promise.resolve(applyResult) }
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
// The chips and the picker's pads read the desk's resolved applied state for their presence ring;
// empty unless a test sets it.
const applied = vi.hoisted(() => ({ current: [] as unknown[] }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: applied.current }) }))
// The press's other routes; a palette never reaches them, but `usePress` holds them.
// A refused absorb into a focused Look takes the effects it started off again.
vi.mock('@/store/fixtureFx', () => ({ useRemoveFxMutation: () => [vi.fn()], useEffectLibraryQuery: () => ({ data: [] }) }))
vi.mock('@/store/looks', () => ({
  useApplyLookMutation: () => [vi.fn()],
  useToggleLookMutation: () => [vi.fn()],
  useAbsorbLookEffectsMutation: () => [vi.fn()],
  // The picker reads the Look library when it is opened on Looks; the strip's never is.
  useLookListQuery: () => ({ data: [] }),
}))
// No programmer page above the strip here: Local, no focused Look, no published marquee.
vi.mock('@/components/programmer/LookRowStore', () => ({ useLookRowStore: () => null }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => null }))
// The hold menu's *Edit palette…* navigates; nothing here follows it.
vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useNavigate: () => vi.fn(),
}))
// The mask a press carries (multi-screen plan D4): the desk's while following, the marquee's own
// when unlinked. Null — no mask — unless a test says otherwise, so the presses below stay exact.
const pressFamilies = vi.hoisted(() => ({ current: null as AttributeFamily[] | null }))
vi.mock('@/store/selection', () => ({
  usePressFamilies: () => pressFamilies.current,
  useSelectionPair: () => ({ targets: [], families: null }),
}))

const newSheetProps = vi.fn()
const venue = { isVenue: false }
vi.mock('@/store/venues', () => ({ useVenueMode: () => ({ isVenue: venue.isVenue, canEdit: venue.isVenue }) }))
vi.mock('./NewTemplateFromSelectionSheet', () => ({
  NewTemplateFromSelectionSheet: (props: { open: boolean }) => {
    newSheetProps(props)
    return props.open ? <div data-testid="new-sheet" /> : null
  },
}))

const { TemplateStrip } = await import('./TemplateStrip')
import type { TemplateTarget } from '@/api/templatesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import { cellFamilies } from '@/components/fixtures-list/columns'

const HEX_1: TemplateTarget[] = [{ type: 'fixture', key: 'hex-1' }]
const COLOUR_CELL: CellRef<ColumnKey>[] = [{ rowId: 'fixture:hex-1', col: 'colour' }]

/** The strip with the container's three answers: cells, where a press lands, what those heads have. */
function strip(
  cells: CellRef<ColumnKey>[],
  targets: TemplateTarget[] = HEX_1,
  targetFamilies: AttributeFamily[] = ['INTENSITY', 'COLOUR'],
  targetEmitters: string[] = ['white', 'amber', 'uv'],
) {
  return (
    <TemplateStrip
      projectId={1}
      cells={cells}
      // What the bar derives for its own badge and hands down, so the two answer from one
      // evaluation. `cellFamilies` is the real helper — a hand-written literal here would let the
      // strip's filter and the bar's badge drift apart in exactly the way sharing it prevents.
      askedFamilies={cells.length > 0 ? cellFamilies(cells) : null}
      targets={targets}
      targetFamilies={targetFamilies}
      targetEmitters={targetEmitters}
    />
  )
}

function template(over: Partial<TemplateSummary> = {}): TemplateSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    rows: [
      { targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#FF9D4A;policy=extract' },
    ],
    kind: 'value',
    requiredEmitters: [],
    effect: null,
    layerCount: 0,
    lastPressedAt: null,
    buskPageCount: 0,
    ...over,
  }
}

const AMBER = template()
const HALF_UP = template({
  id: 2,
  uuid: 'u2',
  name: 'Half Up',
  family: 'INTENSITY',
  rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'dimmer', value: 'pct:50' }],
})

/** An effect template: one effect, no rows, always generic (fx-templates D1–D3). */
const BREATHE = template({
  id: 3,
  uuid: 'u3',
  name: 'Amber Breathe',
  family: 'COLOUR',
  kind: 'effect',
  requiredEmitters: [],
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

beforeEach(() => {
  venue.isVenue = false
  applyTemplate.mockClear()
  toggleTemplate.mockClear()
  pressFamilies.current = null
  applied.current = []
  toastSuccess.mockClear()
  toastWarning.mockClear()
  applyResult = { written: 1, skipped: [] }
  templates = [AMBER, HALF_UP]
  newSheetProps.mockClear()
})
afterEach(cleanup)

/** The `All · n` trigger, by its stable title rather than by its shifting label. */
function allButton(): HTMLElement {
  return screen.getByTitle('Every palette that fits the selection, searchable')
}

/** The chip labels, in the order the scroller draws them. */
function chipNames(): (string | null)[] {
  return screen
    .getAllByRole('button')
    .map((b) => b.textContent)
    .filter((t) => t !== 'New' && !(t ?? '').startsWith('All'))
}

describe('TemplateStrip', () => {
  it('offers no New palette in a venue, where Record is off (venue plan D11)', () => {
    render(strip(COLOUR_CELL))
    expect(screen.getByRole('button', { name: 'New palette' })).toBeTruthy()
    cleanup()
    venue.isVenue = true
    render(strip(COLOUR_CELL))
    expect(screen.queryByRole('button', { name: 'New palette' })).toBeNull()
  })

  it('offers only the families the selected cells name', () => {
    render(strip(COLOUR_CELL))
    expect(screen.getByText('Amber Key')).toBeInTheDocument()
    expect(screen.queryByText('Half Up')).not.toBeInTheDocument()
  })

  it('follows the selection when it moves to another column', () => {
    render(strip([{ rowId: 'fixture:hex-1', col: 'dimmer' }]))
    expect(screen.getByText('Half Up')).toBeInTheDocument()
    expect(screen.queryByText('Amber Key')).not.toBeInTheDocument()
  })

  it('offers what the selected fixtures can take when the gesture names no attribute', () => {
    // Rows selected but no cells: there is no attribute in the gesture, so the filter is what the
    // heads *have* — every family they could take, none they could not.
    render(strip([], HEX_1, ['INTENSITY', 'COLOUR']))
    expect(screen.getByText('Amber Key')).toBeInTheDocument()
    expect(screen.getByText('Half Up')).toBeInTheDocument()
  })

  it('withholds a family the selected fixtures do not have', () => {
    // A dimmer-only par selected: the colour template would land nowhere, so it is not offered.
    render(strip([], HEX_1, ['INTENSITY']))
    expect(screen.getByText('Half Up')).toBeInTheDocument()
    expect(screen.queryByText('Amber Key')).not.toBeInTheDocument()
  })

  it('withholds a palette naming an emitter the selection does not have', () => {
    // The family cannot draw this line — the hex, white, amber and UV are all COLOUR. A template
    // that names an emitter refuses on a head without it *whole*, not row by row, so offering it
    // to an RGB-only par would be offering a press that does nothing.
    templates = [template({ name: 'UV Blast', requiredEmitters: ['uv'] })]
    render(strip(COLOUR_CELL, HEX_1, ['COLOUR'], []))
    expect(screen.queryByText('UV Blast')).not.toBeInTheDocument()
  })

  it('offers it once one selected head has that emitter', () => {
    // A union over the selection, like `targetFamilies`: with a UV hex and a plain par selected
    // together the template is still offered, and the par reports a skip on the press. Requiring
    // every head to have it would hide most of the library from most mixed selections.
    templates = [template({ name: 'UV Blast', requiredEmitters: ['uv'] })]
    render(strip(COLOUR_CELL, HEX_1, ['COLOUR'], ['uv']))
    expect(screen.getByText('UV Blast')).toBeInTheDocument()
  })

  it('needs every emitter it names, not just one of them', () => {
    templates = [template({ name: 'Warm Wash', requiredEmitters: ['white', 'uv'] })]
    render(strip(COLOUR_CELL, HEX_1, ['COLOUR'], ['white']))
    expect(screen.queryByText('Warm Wash')).not.toBeInTheDocument()
  })

  it('says so when nothing fits, rather than showing an empty strip', () => {
    // Targets but no fitting template is a *statement*, not a blank bar — and `New` stays beside
    // it, because recording one is how the operator answers it.
    render(strip([], HEX_1, ['POSITION']))
    expect(screen.getByText('No palette fits what is selected.')).toBeInTheDocument()
    expect(screen.getByText('New')).toBeInTheDocument()
  })

  it('renders nothing with no targets — a press needs somewhere to land', () => {
    // Space plan D3, and a reversal: the strip used to show the *whole* library with nothing
    // selected, for a press that could only toast. That was the most expensive line on the page —
    // a row of chips wrapping to four rows on a real library — and the library is browsed on
    // `/palettes`. Nothing renders here at all now, `New` included.
    // `toBeEmptyDOMElement` and not merely "no chips": the whole band has to cost nothing, which
    // is the point of D3. The sheet below the guard renders null while closed, so it adds no node.
    const { container } = render(strip([], [], []))
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText('Amber Key')).not.toBeInTheDocument()
    expect(screen.queryByText('Half Up')).not.toBeInTheDocument()
    expect(screen.queryByText('New')).not.toBeInTheDocument()
  })

  it('offers both families when the marquee spans two columns', () => {
    render(
      strip([
        { rowId: 'fixture:hex-1', col: 'colour' },
        { rowId: 'fixture:hex-1', col: 'dimmer' },
      ]),
    )
    expect(screen.getByText('Amber Key')).toBeInTheDocument()
    expect(screen.getByText('Half Up')).toBeInTheDocument()
  })

  it('lands the press on the targets the container derived — the cells’ heads, not the checkboxes', () => {
    // Three colour cells on three heads: the container hands those three in, and every one of
    // them is sent, whatever the row selection names.
    const three: TemplateTarget[] = [
      { type: 'fixture', key: 'hex-1' },
      { type: 'fixture', key: 'hex-2' },
      { type: 'fixture', key: 'hex-3' },
    ]
    render(strip(COLOUR_CELL, three))
    fireEvent.click(screen.getByText('Amber Key'))
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: three,
      propertyMask: 'COLOUR',
    })
  })

  it('a tap puts on a layer that tracks the palette — the toggle route, never the apply', () => {
    // The server derives the applied mask from the template's rows; this send is the belief this
    // client is acting on, which is what lets a disagreement surface in the response rather than
    // silently on the rig. Pinned because sending nothing would make that check unavailable.
    render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('Amber Key'))
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: [{ type: 'fixture', key: 'hex-1' }],
      propertyMask: 'COLOUR',
    })
    expect(applyTemplate).not.toHaveBeenCalled()
  })

  it('⌥click applies its values — the apply route, never the toggle', () => {
    render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('Amber Key'), { altKey: true })
    expect(applyTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: [{ type: 'fixture', key: 'hex-1' }],
    })
    expect(toggleTemplate).not.toHaveBeenCalled()
  })

  it('sends the selection’s mask on both presses', () => {
    pressFamilies.current = ['COLOUR']
    render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('Amber Key'))
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: [{ type: 'fixture', key: 'hex-1' }],
      propertyMask: 'COLOUR',
      families: ['COLOUR'],
    })
    fireEvent.click(screen.getByText('Amber Key'), { altKey: true })
    expect(applyTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: [{ type: 'fixture', key: 'hex-1' }],
      families: ['COLOUR'],
    })
  })

  it('withholds a palette outside the selection’s mask (apply-model plan §4.2)', () => {
    // Another window's Position mask stands over this tab's colour cells: the desk would refuse a
    // Colour palette, so the strip does not offer one. An offer, not a refusal — nothing here stops
    // a press from going out.
    pressFamilies.current = ['POSITION']
    render(strip(COLOUR_CELL))
    expect(screen.queryByText('Amber Key')).not.toBeInTheDocument()
  })

  it('wears the pad’s ring from the desk’s applied state', () => {
    applied.current = [
      {
        source: { kind: 'TEMPLATE', id: 1, uuid: 'u1', name: 'Amber Key' },
        targets: [{ type: 'fixture', key: 'hex-1', state: 'all' }],
      },
    ]
    render(strip(COLOUR_CELL))
    expect(screen.getByText('Amber Key').closest('button')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Amber Key').closest('button')).toHaveAttribute('data-presence', 'all')
  })

  it('keeps the half-typed palette when the selection is pulled out from under it', () => {
    // The sheet is rendered OUTSIDE the D3 guard, and this is why. The desk selection is
    // server-owned and shared — another client, a MIDI select button, a group whose membership
    // changed — so `targets` can empty while the operator is mid-name. Unmounting the sheet with
    // the strip discarded that draft with no "Discard changes?" prompt, because `Sheet`'s guard
    // only intercepts the closes Radix drives, never a parent unmount.
    const { rerender } = render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('New'))
    expect(screen.getByTestId('new-sheet')).toBeInTheDocument()

    rerender(strip(COLOUR_CELL, []))

    // The chips are gone — D3 — but the sheet is still standing.
    expect(screen.queryByText('Amber Key')).not.toBeInTheDocument()
    expect(screen.getByTestId('new-sheet')).toBeInTheDocument()
  })

  /**
   * The busk column's split, sideways: values, a hairline, then effects. The chips are one gesture
   * either way — the order is what says which half you are in, since the tooltip is the only other
   * thing that differs.
   */
  it('names a per-fixture palette by its head count, never by one head\'s row', () => {
    // Each row is one head's own value, so describing the first would describe one head — and a
    // stored `deg:` row could only be read through a range that is not that head's (S11).
    templates = [
      template({
        name: 'Specials',
        family: 'POSITION',
        isGeneric: false,
        rows: [
          { targetType: 'fixture', targetKey: 'mover-1', propertyName: 'position', value: 'deg:12,-8' },
          { targetType: 'fixture', targetKey: 'mover-2', propertyName: 'position', value: 'pos:-14,-8' },
        ],
      }),
    ]
    render(strip([{ rowId: 'fixture:hex-1', col: 'position' }]))
    expect(screen.getByText('2 heads')).toBeInTheDocument()
  })

  it('puts effect chips after the values, in library order', () => {
    templates = [BREATHE, AMBER]
    render(strip(COLOUR_CELL))
    expect(chipNames()).toEqual(['Amber Key', 'Amber Breathe'])
  })

  // ─── Recents on the row ───────────────────────────────────────────────

  /**
   * The row is the eight templates this desk pressed most recently, most recent first — a **desk**
   * fact, stamped server-side, so it agrees with the busk page and the hardware. The library's own
   * name order is the fallback, not the rule.
   */
  it('draws the most recently pressed first, whatever the library order', () => {
    templates = [
      template({ id: 1, uuid: 'u1', name: 'Amber Key', lastPressedAt: '2026-09-14T10:00:00Z' }),
      template({ id: 2, uuid: 'u2', name: 'Blue Wash', lastPressedAt: '2026-09-14T10:00:02Z' }),
      template({ id: 3, uuid: 'u3', name: 'Cold Key', lastPressedAt: '2026-09-14T10:00:01Z' }),
    ]
    render(strip(COLOUR_CELL))
    expect(chipNames()).toEqual(['Blue Wash', 'Cold Key', 'Amber Key'])
  })

  it('falls back to the first eight by name when nothing has been pressed', () => {
    // A fresh project must not open on an empty row: the first press has to come off the row like
    // every one after it. Nine offerable, eight drawn, in the library's own order.
    templates = Array.from({ length: 9 }, (_, i) =>
      template({ id: i + 1, uuid: `u${i + 1}`, name: `Colour ${i + 1}` }),
    )
    render(strip(COLOUR_CELL))
    expect(chipNames()).toEqual([
      'Colour 1', 'Colour 2', 'Colour 3', 'Colour 4',
      'Colour 5', 'Colour 6', 'Colour 7', 'Colour 8',
    ])
  })

  it('shows only the recents once there are any, rather than padding them out by name', () => {
    // All or nothing: a padded row would move its own contents under the hand on the second press
    // — chip five becoming chip two — where a short row only grows.
    templates = [
      template({ id: 1, uuid: 'u1', name: 'Amber Key' }),
      template({ id: 2, uuid: 'u2', name: 'Blue Wash', lastPressedAt: '2026-09-14T10:00:00Z' }),
    ]
    render(strip(COLOUR_CELL))
    expect(chipNames()).toEqual(['Blue Wash'])
  })

  // ─── The way in to the rest of the library ────────────────────────────

  it('counts what fits the selection on the All button, not the whole library', () => {
    templates = [AMBER, HALF_UP]
    render(strip(COLOUR_CELL))
    // Two in the library, one of them a colour template — the badge is the strip's own filter.
    expect(allButton().textContent).toBe('All1')
    expect(allButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('drops the chip scroller below 600px of the row, keeping All and New', () => {
    // jsdom evaluates no container query, so what is pinned is that the scroller carries the fold
    // and the two buttons beside it do not: below 600 the library is reached through All alone.
    render(strip(COLOUR_CELL))
    const scroller = screen.getByText('Amber Key').closest('div')
    expect(scroller).toHaveClass('hidden')
    expect(scroller).toHaveClass('@[600px]:flex')
    expect(allButton()).not.toHaveClass('hidden')
  })

  it('presses the strip’s pair from a picker pad, as a chip does', async () => {
    // The picker presses the pair its door hands it: from `All · n`, the strip's own.
    render(strip(COLOUR_CELL))
    fireEvent.click(allButton())
    // Scoped to the panel: the chip in the scroller carries the same title, which is the point —
    // they are one gesture described once.
    const pad = within(await screen.findByRole('dialog')).getByTitle(/Click to put/)
    await act(async () => {
      fireEvent.click(pad)
    })
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: [{ type: 'fixture', key: 'hex-1' }],
      propertyMask: 'COLOUR',
    })
  })

  it('keeps the picker open across a press, and shuts it on a second All', async () => {
    render(strip(COLOUR_CELL))
    fireEvent.click(allButton())
    expect(allButton()).toHaveAttribute('aria-expanded', 'true')
    const panel = await screen.findByRole('dialog')
    await act(async () => {
      fireEvent.click(within(panel).getByTitle(/Click to put/))
    })
    // Auditioning three colours in a row is the normal case.
    expect(within(screen.getByRole('dialog')).getByTitle(/Click to put/)).toBeInTheDocument()

    fireEvent.click(allButton())
    expect(allButton()).toHaveAttribute('aria-expanded', 'false')
  })

  it('⌥click on an effect palette mints copies, and says how many started', async () => {
    // The effect arm writes no literals at all: `written` stays 0 and `effectIds` is the whole
    // result, so without this the one gesture that reaches the rig hardest would say nothing.
    templates = [BREATHE]
    applyResult = { written: 0, skipped: [], effectIds: [11, 12] }
    render(strip(COLOUR_CELL))

    fireEvent.click(screen.getByText('Amber Breathe'), { altKey: true })
    expect(applyTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 3,
      targets: [{ type: 'fixture', key: 'hex-1' }],
    })
    expect(toggleTemplate).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('2 FX started'))
  })

  it('does not read a value apply’s empty effectIds as a failed effect press', async () => {
    // The desk answers a value apply with `effectIds: []` too. That is not "nothing started" — no
    // effect was asked for — and warning about it put a red toast on every successful press.
    applyResult = { written: 3, skipped: [], effectIds: [] }
    render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('Amber Key'), { altKey: true })
    await vi.waitFor(() => expect(applyTemplate).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(toastWarning).not.toHaveBeenCalled()
    expect(toastSuccess).not.toHaveBeenCalled()
  })

  it('warns when an effect apply started nothing', async () => {
    // An empty list looks exactly like a press that started everything, and this arm has no
    // `skipped` to say otherwise — only the count.
    templates = [BREATHE]
    applyResult = { written: 0, skipped: [], effectIds: [] }
    render(strip(COLOUR_CELL))

    fireEvent.click(screen.getByText('Amber Breathe'), { altKey: true })
    await vi.waitFor(() =>
      expect(toastWarning).toHaveBeenCalledWith(
        'Nothing started — no selected head could take this FX',
      ),
    )
  })

  it('a tap on an effect palette puts on a tracking layer like any other', () => {
    templates = [BREATHE]
    render(strip(COLOUR_CELL))
    fireEvent.click(screen.getByText('Amber Breathe'))
    expect(toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 3,
      targets: [{ type: 'fixture', key: 'hex-1' }],
      propertyMask: 'COLOUR',
    })
    expect(applyTemplate).not.toHaveBeenCalled()
  })

  it('states every gesture in the chip’s tooltip, because only the route says which happened', () => {
    templates = [BREATHE]
    render(strip(COLOUR_CELL))
    expect(screen.getByText('Amber Breathe').closest('button')).toHaveAttribute(
      'title',
      'Click to run “Amber Breathe” as a layer that tracks it · ⌥click to run a copy · hold or right-click for more',
    )
  })

  it('opens the new-palette sheet from the pinned New chip', () => {
    // `New` is outside the chip scroller on purpose: the control that *fills* the library must not
    // be the one that scrolls off the end of it.
    render(strip([]))
    const chip = screen.getByText('New').closest('button')
    expect(chip).not.toBeDisabled()
    fireEvent.click(chip!)
    expect(screen.getByTestId('new-sheet')).toBeInTheDocument()
  })

  it('hands the sheet the same targets a press would use, and only the families the cells asked for', () => {
    // A marquee: the sheet records those heads and is told the family. Rows only: same heads, but
    // `families` is null — the capability list is the strip's filter, not the operator's choice,
    // and the sheet should ask rather than pre-pick one of several.
    render(strip(COLOUR_CELL))
    expect(newSheetProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ targets: HEX_1, families: ['COLOUR'] }),
    )
    cleanup()
    render(strip([], HEX_1, ['INTENSITY', 'COLOUR']))
    expect(newSheetProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ targets: HEX_1, families: null }),
    )
  })
})

/**
 * A hold or a right-click opens the chip's menu — never a press, on any pointer (apply-model plan
 * D8) — and the click the hold's release generates reaches nothing.
 */
describe('TemplateStrip hold', () => {
  const TOUCH = { pointerType: 'touch' }

  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('a hold opens the menu, never a layer, and the release’s click presses nothing', () => {
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    fireEvent.pointerDown(chip, { ...TOUCH, clientX: 10, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(501)
    })
    expect(screen.getByRole('menu', { name: 'Amber Key' })).toBeInTheDocument()
    fireEvent.pointerUp(chip, TOUCH)
    fireEvent.click(chip)
    expect(toggleTemplate).not.toHaveBeenCalled()
    expect(applyTemplate).not.toHaveBeenCalled()
  })

  it('a mouse hold opens the menu too', () => {
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    fireEvent.pointerDown(chip, { pointerType: 'mouse', clientX: 10, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(800)
    })
    fireEvent.pointerUp(chip, { pointerType: 'mouse' })
    fireEvent.click(chip)
    expect(screen.getByRole('menu', { name: 'Amber Key' })).toBeInTheDocument()
    expect(toggleTemplate).not.toHaveBeenCalled()
  })

  it('a right-click opens the menu, and its items name the gestures', () => {
    render(strip(COLOUR_CELL))
    fireEvent.contextMenu(screen.getByText('Amber Key').closest('button')!)
    const menu = screen.getByRole('menu', { name: 'Amber Key' })
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'Put on',
      'Apply as values',
      'Pick up',
      'Edit palette…',
    ])
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Apply as values' }))
    expect(applyTemplate).toHaveBeenCalledTimes(1)
    expect(toggleTemplate).not.toHaveBeenCalled()
  })

  it('names *Take off these heads* when the palette covers them all', () => {
    applied.current = [
      {
        source: { kind: 'TEMPLATE', id: 1, uuid: 'u1', name: 'Amber Key' },
        targets: [{ type: 'fixture', key: 'hex-1', state: 'all' }],
      },
    ]
    render(strip(COLOUR_CELL))
    fireEvent.contextMenu(screen.getByText('Amber Key').closest('button')!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Take off these heads' }))
    expect(toggleTemplate).toHaveBeenCalledTimes(1)
  })

  it('a tap is still the press — the hold takes nothing from it', () => {
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    fireEvent.pointerDown(chip, { ...TOUCH, clientX: 10, clientY: 10 })
    fireEvent.pointerUp(chip, TOUCH)
    fireEvent.click(chip)
    expect(toggleTemplate).toHaveBeenCalledTimes(1)
    expect(applyTemplate).not.toHaveBeenCalled()
  })

  it('a finger scrolling the chips past one opens nothing', () => {
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    fireEvent.pointerDown(chip, { ...TOUCH, clientX: 10, clientY: 10 })
    fireEvent.pointerMove(chip, { ...TOUCH, clientX: 40, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('a hold released off the chip does not eat the next keyboard press of it', () => {
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    fireEvent.pointerDown(chip, { ...TOUCH, clientX: 10, clientY: 10 })
    act(() => {
      vi.advanceTimersByTime(501)
    })
    // Implicit capture delivers the release here even off the element, and no click follows.
    fireEvent.pointerUp(chip, TOUCH)
    act(() => {
      vi.advanceTimersByTime(400)
    })
    // Enter on the focused chip is a click with no pointer sequence before it.
    fireEvent.click(chip)
    expect(toggleTemplate).toHaveBeenCalledTimes(1)
  })

  it('draws its chips and New at the 28px nested tier', () => {
    // A chip is a control inside the selection bar's 40px row, so it is `h-7` where the row's own
    // verbs are 32 — the same tier as Update and Revert inside the source box. It was 26.
    render(strip(COLOUR_CELL))
    const chip = screen.getByText('Amber Key').closest('button')!
    expect(chip.className).toContain('h-7')
    expect(chip.className).not.toContain('h-[26px]')
    const fresh = screen.getByRole('button', { name: /New/ })
    expect(fresh.className).toContain('h-7')
  })
})
