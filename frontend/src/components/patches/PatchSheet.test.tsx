// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FixturePatch } from '@/api/patchApi'

/**
 * The patch list's own rules on the sheet kit (CLAUDE.md §Sheet kit):
 *
 *  - **Set over N addresses lands them consecutively by footprint from the typed one**, in
 *    visible-row order — one PUT per head whose channel moved, never N heads on one address.
 *  - **Spread on Address is From + Step** (blank step = footprint), in visible-row order.
 *  - **The overlap is on the cell**: a destructive ring, the other head on the title, a legend
 *    line under the sheet.
 *  - **Clear is refused on Address** — an address cannot be empty — and the refusal is the
 *    button's title.
 *
 * Everything store-connected is mocked away; the point is the gesture and the write it produces.
 */
const updatePatch = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const setHeadNumbers = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const setPatchHidden = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const setPatchColours = vi.fn((_args: unknown) => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/patches', () => ({
  useUpdatePatchMutation: () => [updatePatch],
  useSetHeadNumbersMutation: () => [setHeadNumbers],
  useSetPatchColoursMutation: () => [setPatchColours],
  useSetPatchHiddenMutation: () => [setPatchHidden],
  useDeletePatchMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve() }))],
}))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [] }) }))
// The served gel library, read from the resource the desk serves it from (`GET /gels`).
vi.mock('@/hooks/useGelIndex', async () => {
  const { indexGels } = await import('@/lib/gels')
  const gels = (await import('../../../../src/main/resources/gels.json')).default
  return { useGelIndex: () => indexGels(gels) }
})
vi.mock('@/store/locate', () => ({
  useLocateStateQuery: () => ({ data: { targets: [] } }),
  useToggleLocateMutation: () => [vi.fn()],
}))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
// The selection bar asks the viewport's height; jsdom has no `matchMedia`, and a desk is tall.
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@/components/fixtures-list/useHighlight', () => ({
  useHighlight: () => ({ press: () => {}, release: () => {}, isActive: false }),
}))
// jsdom lays nothing out, so the real virtualizer measures a zero-height scroller and renders no
// rows at all. Stubbed to render them all — this suite is about the gesture, not windowing.
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({
        index,
        key: index,
        start: index * estimateSize(),
        size: estimateSize(),
      })),
    scrollToIndex: () => {},
  }),
}))

import { toast } from 'sonner'
import { PatchSheet, patchRowId, type PatchSheetRow } from './PatchSheet'
import type { PatchGroup } from '@/api/patchApi'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'
import { indexLanterns, type Lantern } from '@/lib/lanterns'
import libraryJson from '../../../../src/main/resources/lanterns/library.json'

function patch(id: number, name: string, startChannel: number, channelCount = 6, universe = 1): FixturePatch {
  return {
    id,
    uuid: `uuid-${id}`,
    key: name.toLowerCase().replace(/\s+/g, '-'),
    displayName: name,
    fixtureTypeKey: 'par-hex',
    startChannel,
    channelCount,
    manufacturer: 'Chauvet',
    model: 'Freedom Par Hex',
    modeName: '6ch',
    universe,
    subnet: 0,
    sortOrder: id,
    groups: [],
    stageX: null,
    stageY: null,
    stageZ: null,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
  }
}

const RIG = [
  patch(1, 'PAR 1', 1),
  patch(2, 'PAR 2', 100),
  patch(3, 'PAR 3', 200),
  patch(4, 'Bar SL', 25, 18),
]

const rows: PatchSheetRow[] = RIG.map((p) => ({
  id: patchRowId(p.id),
  patch: p,
  riggingName: null,
  acceptsBeamAngle: false,
  acceptsGel: false,
}))

function draw(over: Partial<React.ComponentProps<typeof PatchSheet>> = {}) {
  return render(
    <PatchSheet
      projectId={1}
      rows={rows}
      allPatches={RIG}
      riggings={[]}
      visibleColumns={['address', 'type', 'key']}
      onEditPatch={() => {}}
      onEditGroup={() => {}}
      {...over}
    />,
  )
}

function row(name: string): HTMLElement {
  return screen.getByText(name).closest('[data-row-id]') as HTMLElement
}

function addressCell(name: string): HTMLElement {
  const row = screen.getByText(name).closest('[data-row-id]')!
  return within(row as HTMLElement).getByText(/^\d-\d{3}$/).closest('button')!
}

beforeEach(() => {
  stubFlatLayout()
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
  // `mockClear` keeps the implementation, and one test swaps in a PUT that never resolves — so the
  // default has to be put back, or every later test in this file inherits the hang.
  updatePatch.mockClear()
  updatePatch.mockImplementation(() => ({ unwrap: () => Promise.resolve() }))
  setHeadNumbers.mockClear()
  setPatchHidden.mockClear()
  setPatchColours.mockClear()
  setPatchColours.mockImplementation(() => ({ unwrap: () => Promise.resolve() }))
})

/**
 * Give the sheet a layout, so the marquee resolves to cells. jsdom reports every rect as zero, so
 * the first column's header spans x = 0..240, the Address column's 240..344, and the rest follow;
 * rows are 36px from a zero-height header, so row *i* is y = 36i..36i+36.
 */
function stubFlatLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  const bands: Record<string, [number, number]> = {
    // Only ever drawn alone, so it may share the Address column's band.
    head: [240, 312],
    colour: [240, 360],
    address: [240, 344],
    type: [344, 600],
    key: [600, 740],
    mount: [240, 358],
    gel: [358, 454],
    lantern: [358, 600],
  }
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 240)
    const col = this.getAttribute('data-column-header')
    if (col && bands[col]) return rect(...bands[col])
    return rect(0, 1000)
  })
}

/** A single click on an address cell selects that one cell — the programmer's rule, kept. */
function clickAddress(name: string) {
  fireEvent.click(addressCell(name))
}

/** A marquee down the Key column over rows `from`..`to` (indices into the drawn rows). */
function dragKeys(from: number, to: number) {
  const cell = within(
    screen.getAllByText(/^(PAR \d|Bar SL)$/)[from].closest('[data-row-id]') as HTMLElement,
  ).getByText(/^(par|bar)-/).closest('button')!
  fireEvent.pointerDown(cell, { button: 0, clientX: 620, clientY: from * 36 + 10 })
  fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 700, clientY: to * 36 + 26 })
  fireEvent.pointerUp(cell, { button: 0, clientX: 700, clientY: to * 36 + 26 })
  fireEvent.click(cell)
}

/** A marquee down the Address column over rows `from`..`to` (indices into the drawn rows). */
function dragAddresses(from: number, to: number) {
  const cell = addressCell(screen.getAllByText(/^(PAR \d|Bar SL)$/)[from].textContent!)
  fireEvent.pointerDown(cell, { button: 0, clientX: 300, clientY: from * 36 + 10 })
  fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 320, clientY: to * 36 + 26 })
  fireEvent.pointerUp(cell, { button: 0, clientX: 320, clientY: to * 36 + 26 })
  // The click a real release generates, which the marquee swallows — without it the swallow would
  // eat the next click this test makes, on Set or Spread.
  fireEvent.click(cell)
}

describe('PatchSheet', () => {
  it('opens a fixture from its pencil — the kit’s now — and from ⏎ with one row selected (library-sheets D4)', () => {
    const onEditPatch = vi.fn()
    draw({ onEditPatch })
    fireEvent.click(screen.getByRole('button', { name: 'Edit PAR 2' }))
    expect(onEditPatch).toHaveBeenLastCalledWith(2)
    // A click on the name selects the row and focuses the rename's trigger; ⏎ still opens the row.
    const name = screen.getByText('PAR 3').closest('button')!
    fireEvent.click(name)
    name.focus()
    fireEvent.keyDown(name, { key: 'Enter' })
    expect(onEditPatch).toHaveBeenLastCalledWith(3)
    expect(onEditPatch).toHaveBeenCalledTimes(2)
    // Two rows: ⏎ opens nothing.
    fireEvent.click(row('PAR 1').querySelector('[data-first-column]')!, { metaKey: true })
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onEditPatch).toHaveBeenCalledTimes(2)
  })

  it('marks a venue draft change on its row, red-amber when it changes output (venue plan session 2)', () => {
    const marked = rows.map((r) =>
      r.patch.id === 2
        ? { ...r, draft: { kind: 'PATCH' as const, table: 'fixturePatches', uuid: 'uuid-2', name: 'PAR 2', change: 'CHANGED' as const, output: true, facets: ['ADDRESS' as const], fields: [{ field: 'address', from: '1-090', to: '1-100' }] } }
        : r.patch.id === 3
          ? { ...r, draft: { kind: 'PATCH' as const, table: 'fixturePatches', uuid: 'uuid-3', name: 'PAR 3', change: 'CHANGED' as const, output: false, facets: ['POSITION' as const], fields: [{ field: 'stageX', from: '1', to: '2' }] } }
          : r,
    )
    draw({ rows: marked, visibleColumns: ['draft', 'address'] })
    // The whole marker rides the title, so a narrow column clips nothing a hover cannot read.
    const address = within(row('PAR 2')).getByText('Address · 1-090 → 1-100').closest('[title]')
    expect(address).toHaveAttribute('title', 'Address · 1-090 → 1-100 — changes output')
    expect(within(row('PAR 3')).getByText('Position').closest('[title]')).toHaveAttribute('title', 'Position — drawing only')
    expect(within(row('PAR 1')).queryByTitle(/changes output|drawing only/)).toBeNull()
  })

  it('edits nothing when read-only — an operator looking at a venue (venue plan D3)', () => {
    const onEditPatch = vi.fn()
    draw({ onEditPatch, readOnly: true })
    expect(screen.queryByRole('button', { name: 'Edit PAR 2' })).toBeNull()
    // The name is a read-out, not the rename's trigger, and a cell is inert.
    expect(screen.getByText('PAR 3').closest('button')).toBeNull()
    fireEvent.click(addressCell('PAR 1'))
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onEditPatch).not.toHaveBeenCalled()
    expect(updatePatch).not.toHaveBeenCalled()
    // A selected row offers Locate and Highlight, never Unpatch.
    fireEvent.click(row('PAR 2').querySelector('[data-first-column]')!)
    expect(screen.getByRole('button', { name: /Locate/ })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Unpatch/ })).toBeNull()
  })

  describe('a show on a venue (venue plan session 3)', () => {
    // PAR 2 is the venue's, at the pinned version; the rest are the show's own.
    const VENUE_RIG = RIG.map((p) => (p.id === 2 ? { ...p, owner: 'VENUE' as const } : { ...p, owner: 'SHOW' as const }))
    const venueRows: PatchSheetRow[] = rows.map((r) => ({ ...r, patch: VENUE_RIG.find((p) => p.id === r.patch.id)! }))
    const drawVenue = (over: Partial<React.ComponentProps<typeof PatchSheet>> = {}) =>
      draw({ rows: venueRows, allPatches: VENUE_RIG, ...over })

    it('says whose each head is in the Source column', () => {
      drawVenue({ visibleColumns: ['source', 'address'] })
      expect(within(row('PAR 2')).getByText('Venue')).toBeInTheDocument()
      expect(within(row('PAR 1')).getByText('This show')).toBeInTheDocument()
    })

    it("drops a venue head from a Set and names it — the desk refuses a write to one (VENUE_OWNED)", async () => {
      drawVenue()
      dragAddresses(0, 1)
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const field = await screen.findByLabelText('Start channel')
      expect(screen.getByText(/PAR 2 is the venue's · skipped/)).toBeInTheDocument()
      fireEvent.change(field, { target: { value: '7' } })
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
      expect(updatePatch).toHaveBeenCalledTimes(1)
      expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, startChannel: 7 })
    })

    it("draws a venue head's identity read-only: no rename, no live cell", () => {
      drawVenue()
      // The name is plain text, not the rename's trigger; the address is drawn but inert.
      expect(screen.getByText('PAR 2').closest('button')).toBeNull()
      expect(addressCell('PAR 2')).toBeDisabled()
      expect(screen.getByText('PAR 1').closest('button')).not.toBeNull()
    })

    it('hides a selected venue head, and will not unpatch one', async () => {
      drawVenue()
      fireEvent.click(row('PAR 2').querySelector('[data-first-column]')!)
      expect(screen.getByRole('button', { name: /Unpatch/ })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: /Hide/ }))
      await act(async () => {})
      expect(setPatchHidden).toHaveBeenCalledWith({ projectId: 1, patchId: 2, hidden: true })
      // The show's own head offers Unpatch and no Hide.
      fireEvent.click(row('PAR 1').querySelector('[data-first-column]')!)
      expect(screen.getByRole('button', { name: /Unpatch/ })).not.toBeDisabled()
      expect(screen.queryByRole('button', { name: /^Hide/ })).toBeNull()
    })
  })

  it('lands a Set over N addresses consecutively by footprint from the typed one', async () => {
    draw()
    // PAR 1 (6ch) and PAR 2 (6ch), in visible order, set from 7: 7 and 13.
    dragAddresses(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Start channel')
    fireEvent.change(field, { target: { value: '7' } })
    // The preview names the landing before Apply.
    expect(screen.getByText('PAR 1 → 1-007')).toBeInTheDocument()
    expect(screen.getByText('PAR 2 → 1-013')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(updatePatch).toHaveBeenCalledTimes(2)
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, startChannel: 7 })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 2, startChannel: 13 })
  })

  it('names the collision before Apply and refuses it — the PUT has no overlap check of its own', async () => {
    draw()
    // PAR 1 → 19 (19–24) is clear; PAR 2 → 25 lands on Bar SL (25–42).
    dragAddresses(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Start channel')
    fireEvent.change(field, { target: { value: '19' } })
    expect(screen.getByText(/1-025 overlaps Bar SL/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(updatePatch).not.toHaveBeenCalled()
  })

  it('refuses Clear on Address, with the reason on the button', () => {
    draw()
    clickAddress('PAR 1')
    const clear = screen.getByRole('button', { name: 'Clear cells' })
    expect(clear).toBeDisabled()
    expect(clear).toHaveAttribute('title', 'An address cannot be empty')
    expect(screen.getByRole('button', { name: 'Set' })).not.toBeDisabled()
  })

  it('spreads addresses From + Step in visible-row order, blank step meaning each footprint', async () => {
    // Drawn in this order: PAR 1 (6), Bar SL (18), PAR 2 (6).
    draw({ rows: [rows[0], rows[3], rows[1], rows[2]] })
    dragAddresses(0, 2)
    expect(screen.getByText('3 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Spread' }))
    const from = await screen.findByLabelText('From')
    fireEvent.change(from, { target: { value: '300' } })
    // Visible order is PAR 1 (6), Bar SL (18), PAR 2 (6): 300, 306, 324.
    expect(screen.getByText(/1-300, 1-306, 1-324/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Step'), { target: { value: '20' } })
    expect(screen.getByText(/1-300, 1-320, 1-340/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, startChannel: 300 })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 4, startChannel: 320 })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 2, startChannel: 340 })
  })

  it('a commit reaches only its own column — a rigging picked over a Mount→Gel marquee is never a gel code', async () => {
    // Every patch column takes a string, so the kit's shape test cannot tell a rigging uuid from a
    // gel code. Mount and Gel are drawn side by side, and a marquee across both on one head, then
    // a mount picked from the Mount editor, must PUT riggingUuid alone.
    const gelRows = rows.map((r) => ({ ...r, acceptsGel: true }))
    draw({ rows: gelRows, riggings: [{ uuid: 'rig-1', name: 'FOH truss' }], visibleColumns: ['mount', 'gel'] })
    const mount = within(row('PAR 1')).getByText('Free').closest('button')!
    fireEvent.pointerDown(mount, { button: 0, clientX: 250, clientY: 10 })
    fireEvent.pointerMove(mount, { button: 0, buttons: 1, clientX: 400, clientY: 20 })
    fireEvent.pointerUp(mount, { button: 0, clientX: 400, clientY: 20 })
    fireEvent.click(mount)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'FOH truss' }))
    expect(updatePatch).toHaveBeenCalledTimes(1)
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, riggingUuid: 'rig-1' })
  })

  it('marks a head as infrastructure from the Role column, and badges it on its row', async () => {
    draw({ visibleColumns: ['role'] })
    const role = within(row('PAR 1')).getByText('Lighting').closest('button')!
    fireEvent.click(role)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.click(await screen.findByRole('option', { name: 'Infra' }))
    expect(updatePatch).toHaveBeenCalledTimes(1)
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, infrastructure: true })

    cleanup()
    const infra = { ...RIG[0], infrastructure: true }
    draw({ rows: [{ ...rows[0], patch: infra }], allPatches: [infra], visibleColumns: ['role'] })
    expect(within(row('PAR 1')).getByText('Infra')).toBeInTheDocument()
    expect(within(row('PAR 1')).getByRole('img', { name: /Infrastructure/ })).toBeInTheDocument()
  })

  it('types a Stage choice — a two-option cell has the filter Mount has', async () => {
    draw({ visibleColumns: ['stage'] })
    fireEvent.click(within(row('PAR 1')).getByText('Shown').closest('button')!)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const filter = await screen.findByRole('combobox', { name: 'Filter Stage options' })
    fireEvent.change(filter, { target: { value: 'hid' } })
    expect(screen.queryByRole('option', { name: 'Shown' })).toBeNull()
    fireEvent.keyDown(filter, { key: 'Enter' })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, stageHidden: true })
  })

  describe('the Mount and Lantern columns (stage-view plan session 7)', () => {
    const lanterns = indexLanterns(libraryJson as Lantern[])

    it('says a unit on a ledge stands, on its face and its title', () => {
      draw({
        rows: [{ ...rows[0], patch: { ...RIG[0], riggingUuid: 'rig-1' }, riggingName: 'Balcony', riggingKind: 'LEDGE' }],
        riggings: [{ uuid: 'rig-1', name: 'Balcony' }],
        visibleColumns: ['mount'],
      })
      expect(within(row('PAR 1')).getByText(/standing/)).toBeInTheDocument()
      expect(row('PAR 1').querySelector('[data-cell="mount"]')!.closest('[title]')?.getAttribute('title')).toMatch(
        /Balcony · ledge · standing/,
      )
    })

    it('names the lantern a dimmer is hung with, and picks another from the library', async () => {
      draw({
        rows: [{ ...rows[0], acceptsLantern: true, lanternLabel: 'House downlight', lanternIsDefault: true }],
        lanterns,
        visibleColumns: ['lantern'],
      })
      const cell = row('PAR 1').querySelector('[data-cell="lantern"] button') as HTMLElement
      expect(within(cell).getByText('House downlight')).toBeInTheDocument()
      fireEvent.click(cell)
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      fireEvent.click(await screen.findByRole('option', { name: 'Source Four 19° · 19°' }))
      expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, lanternType: 's4-19' })
    })

    it('reads out a DMX type’s declared body, which is not the operator’s to pick', () => {
      draw({ rows: [{ ...rows[0], bodyLabel: 'mover · spot' }], lanterns, visibleColumns: ['lantern'] })
      expect(within(row('PAR 1')).getByText('mover · spot')).toBeInTheDocument()
      expect(row('PAR 1').querySelector('[data-cell="lantern"] button')).toBeNull()
    })
  })

  describe('the Gel column', () => {
    const gelRows = rows.map((r) => ({ ...r, acceptsGel: true }))
    const gelCell = (name: string) => row(name).querySelector('[data-cell="gel"] button') as HTMLElement

    it('picks from the gel library — search, then click — as the patch editor does', async () => {
      draw({ rows: gelRows, visibleColumns: ['gel'] })
      fireEvent.click(gelCell('PAR 1'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const search = await screen.findByRole('combobox', { name: 'Search gels' })
      fireEvent.change(search, { target: { value: 'full ct blue' } })
      fireEvent.click(screen.getByRole('option', { name: /L201/ }))
      expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, gelCode: 'L201' })
    })

    it('takes the first match on Enter, and Open white clears a gel', async () => {
      const gelled = { ...RIG[0], gelCode: 'L201' }
      draw({ rows: [{ ...gelRows[0], patch: gelled }], allPatches: [gelled], visibleColumns: ['gel'] })
      fireEvent.click(gelCell('PAR 1'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const search = await screen.findByRole('combobox', { name: 'Search gels' })
      // With nothing typed, the highlight rests on the current gel.
      expect(screen.getByRole('option', { name: /L201/ })).toHaveAttribute('data-highlighted', 'true')
      fireEvent.change(search, { target: { value: 'L106' } })
      fireEvent.keyDown(search, { key: 'Enter' })
      expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, gelCode: 'L106' })

      updatePatch.mockClear()
      fireEvent.click(gelCell('PAR 1'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      fireEvent.click(await screen.findByRole('option', { name: /Open white/ }))
      expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, gelCode: null })
    })
  })

  it('rings an overlapping address, names the other head, and says so under the sheet', () => {
    // Bar SL at 25–42 and a par dropped onto 30.
    const clash = patch(5, 'PAR 5', 30)
    const all = [...RIG, clash]
    const clashRow: PatchSheetRow = { id: patchRowId(5), patch: clash, riggingName: null, acceptsBeamAngle: false, acceptsGel: false }
    draw({ rows: [...rows, clashRow], allPatches: all })
    const cell = addressCell('PAR 5').closest('[data-cell="address"]')!
    expect(cell.className).toContain('ring-destructive')
    expect(cell).toHaveAttribute('title', expect.stringContaining('Overlaps Bar SL'))
    expect(screen.getByText(/2 addresses overlap another fixture/)).toBeInTheDocument()
    expect(addressCell('PAR 1').closest('[data-cell="address"]')!.className).not.toContain('ring-destructive')
  })

  it('spreads one typed key over the selection, continuing the scheme it already carries', async () => {
    draw()
    // PAR 1 (`par-1`) and PAR 2 (`par-2`), re-keyed from `foh-3`.
    dragKeys(0, 1)
    expect(screen.getByText('2 cells')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Key')
    fireEvent.change(field, { target: { value: 'foh-3' } })
    // One head to a line, not a `·`-joined sentence.
    expect(screen.getByText('PAR 1 → foh-3')).toBeInTheDocument()
    expect(screen.getByText('PAR 2 → foh-4')).toBeInTheDocument()
    // Awaited: the PUTs go out one at a time, because each one checks key uniqueness on its own.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    })
    expect(updatePatch).toHaveBeenCalledTimes(2)
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 1, key: 'foh-3' })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 2, key: 'foh-4' })
  })

  it('names a key another head holds and refuses Apply — the PUT rejects a duplicate', async () => {
    draw()
    dragKeys(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Key')
    // `par-3` is PAR 3's, which is outside the selection.
    fireEvent.change(field, { target: { value: 'par-3' } })
    expect(screen.getByText(/“par-3” is already PAR 3's key/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(updatePatch).not.toHaveBeenCalled()
  })

  it('re-keys a batch onto keys its own members hold, one PUT at a time and in a safe order', async () => {
    draw()
    // PAR 1 · PAR 2 · PAR 3 are `par-1` · `par-2` · `par-3`; typing `par-2` walks them all up one.
    dragKeys(0, 2)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Key')
    fireEvent.change(field, { target: { value: 'par-2' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    })
    // Downwards: `par-4` is free, then the `par-3` it vacates, then `par-2`. The other order would
    // put `par-2` on PAR 1 while PAR 2 still held it, and each PUT checks uniqueness on its own.
    const bodies = (updatePatch.mock.calls as unknown as Record<string, unknown>[][]).map((c) => c[0])
    expect(bodies).toEqual([
      { projectId: 1, patchId: 3, key: 'par-4' },
      { projectId: 1, patchId: 2, key: 'par-3' },
      { projectId: 1, patchId: 1, key: 'par-2' },
    ])
  })

  it('refuses a second re-key while one batch is still going out', async () => {
    // `write` answers synchronously and the editor closes on top of the detached PUT loop, so two
    // overlapping batches would each plan against an `allPatches` the other has not landed in.
    let release: (() => void) | undefined
    updatePatch.mockImplementation(() => ({
      unwrap: () => new Promise<void>((resolve) => { release = () => resolve() }),
    }))
    draw()
    dragKeys(0, 1)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.change(await screen.findByLabelText('Key'), { target: { value: 'foh-3' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    })
    expect(updatePatch).toHaveBeenCalledTimes(1) // the first PUT is in flight, the second waits

    // A second batch, before the first has drained.
    dragKeys(2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    fireEvent.change(await screen.findByLabelText('Key'), { target: { value: 'spot-1' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    })
    expect(updatePatch).toHaveBeenCalledTimes(1) // refused: nothing new went out

    await act(async () => {
      release?.()
    })
  })

  it('renames a head from a double click on its name — the same popover as every value cell — and a single click still selects the row', async () => {
    draw()
    const name = screen.getByText('PAR 2')
    fireEvent.click(name)
    expect(row('PAR 2')).toHaveAttribute('data-state', 'selected')
    expect(screen.queryByLabelText('Fixture name')).not.toBeInTheDocument()
    fireEvent.doubleClick(name)
    const field = screen.getByLabelText('Fixture name') as HTMLInputElement
    expect(field.value).toBe('PAR 2')
    fireEvent.change(field, { target: { value: 'Front wash 2' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 2, displayName: 'Front wash 2' })
  })

  it('opens the address editor from Enter, seeded from a typed digit, and applies on Enter', async () => {
    draw()
    clickAddress('PAR 3')
    await act(async () => {
      fireEvent.keyDown(window, { key: '9' })
    })
    const field = await screen.findByLabelText('Start channel')
    expect(field).toHaveValue(9)
    fireEvent.change(field, { target: { value: '90' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(updatePatch).toHaveBeenCalledWith({ projectId: 1, patchId: 3, startChannel: 90 })
  })

  describe('the Colour column (tables-colours plan session 4)', () => {
    // PAR 1 pink, PAR 2 and PAR 3 none, Bar SL lime. Front wash (red) holds PAR 1–3, Side (cyan)
    // holds PAR 3 as well, and Bar SL is only in Plain, which has no colour.
    const GROUPS: PatchGroup[] = [
      { id: 10, name: 'Front wash', memberCount: 3, colour: 'red' },
      { id: 11, name: 'Side', memberCount: 1, colour: 'cyan' },
      { id: 12, name: 'Plain', memberCount: 1, colour: null },
    ]
    const COLOURED = RIG.map((p) => ({
      ...p,
      colour: p.id === 1 ? 'pink' : p.id === 4 ? 'lime' : null,
      groups:
        p.id === 3
          ? [{ id: 10, name: 'Front wash' }, { id: 11, name: 'Side' }]
          : p.id === 4
            ? [{ id: 12, name: 'Plain' }]
            : [{ id: 10, name: 'Front wash' }],
    }))
    const colouredRows: PatchSheetRow[] = rows.map((r) => ({ ...r, patch: COLOURED.find((p) => p.id === r.patch.id)! }))
    const drawColours = (over: Partial<React.ComponentProps<typeof PatchSheet>> = {}) =>
      draw({ rows: colouredRows, allPatches: COLOURED, groups: GROUPS, visibleColumns: ['colour'], ...over })

    const colourCell = (name: string) => row(name).querySelector('[data-cell="colour"] button') as HTMLElement

    /** A marquee down the Colour column over rows `from`..`to` (indices into the drawn rows). */
    function dragColours(from: number, to: number) {
      const cell = colourCell(screen.getAllByText(/^(PAR \d|Bar SL)$/)[from].textContent!)
      fireEvent.pointerDown(cell, { button: 0, clientX: 260, clientY: from * 36 + 10 })
      fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 300, clientY: to * 36 + 26 })
      fireEvent.pointerUp(cell, { button: 0, clientX: 300, clientY: to * 36 + 26 })
      fireEvent.click(cell)
    }

    const sent = () => (setPatchColours.mock.calls as unknown as [{ colours: Record<string, unknown>[] }][]).map(([a]) => a.colours)

    it('picks a colour from a digit over a marquee: one request, every entry its patch and colour alone', () => {
      drawColours()
      dragColours(0, 2)
      expect(screen.getByText('3 cells')).toBeInTheDocument()
      fireEvent.keyDown(window, { key: '3' })
      expect(setPatchColours).toHaveBeenCalledTimes(1)
      expect(setPatchColours).toHaveBeenCalledWith({
        projectId: 1,
        colours: [
          { patchId: 1, colour: 'yellow' },
          { patchId: 2, colour: 'yellow' },
          { patchId: 3, colour: 'yellow' },
        ],
      })
      for (const entry of sent()[0]) expect(Object.keys(entry).sort()).toEqual(['colour', 'patchId'])
      // A digit commits without opening the editor.
      expect(screen.queryByRole('group', { name: 'Colours' })).toBeNull()
      expect(updatePatch).not.toHaveBeenCalled()
    })

    it('keys 1–9 and 0 are the ten colours in order, and ⌫ clears only the heads that have one', () => {
      drawColours()
      dragColours(0, 1)
      fireEvent.keyDown(window, { key: '1' })
      fireEvent.keyDown(window, { key: '9' })
      fireEvent.keyDown(window, { key: '0' })
      expect(sent().map((colours) => colours.map((c) => c.colour))).toEqual([
        ['red', 'red'],
        ['white', 'white'],
        ['grey', 'grey'],
      ])
      setPatchColours.mockClear()
      fireEvent.keyDown(window, { key: 'Backspace' })
      expect(setPatchColours).toHaveBeenCalledWith({ projectId: 1, colours: [{ patchId: 1, colour: null }] })
    })

    it('opens the ten swatches on Set and picks one; the same colour again sends nothing', async () => {
      drawColours()
      fireEvent.click(colourCell('PAR 1'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const swatches = await screen.findByRole('group', { name: 'Colours' })
      expect(within(swatches).getAllByRole('button')).toHaveLength(10)
      expect(within(swatches).getByRole('button', { name: /Pink/ })).toHaveAttribute('aria-pressed', 'true')
      fireEvent.click(within(swatches).getByRole('button', { name: /Pink/ }))
      expect(setPatchColours).not.toHaveBeenCalled()
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      fireEvent.click(within(await screen.findByRole('group', { name: 'Colours' })).getByRole('button', { name: /Cyan/ }))
      expect(setPatchColours).toHaveBeenCalledWith({ projectId: 1, colours: [{ patchId: 1, colour: 'cyan' }] })
    })

    it('toasts a refused colour by its code (COLOUR_UNKNOWN), once per column', async () => {
      const error = vi.spyOn(toast, 'error')
      setPatchColours.mockImplementationOnce(() => ({
        unwrap: () =>
          Promise.reject({
            status: 400,
            data: { error: "patch 2: 'blue' is reserved (the desk draws your values in it)", code: 'COLOUR_UNKNOWN' },
          }),
      }))
      drawColours()
      fireEvent.click(colourCell('PAR 2'))
      fireEvent.keyDown(window, { key: '2' })
      await act(async () => {})
      expect(error).toHaveBeenCalledWith(
        expect.stringMatching(/^The desk refused the colour: .*reserved/),
        { id: 'sheet-write:patches:colour' },
      )
    })

    it('copies a group colour onto the selected heads with none, and onto all of them with ⌥ (D9)', async () => {
      const info = vi.spyOn(toast, 'info')
      drawColours()
      fireEvent.click(row('PAR 1').querySelector('[data-first-column]')!)
      fireEvent.click(row('PAR 2').querySelector('[data-first-column]')!, { metaKey: true })
      fireEvent.click(row('PAR 3').querySelector('[data-first-column]')!, { metaKey: true })
      fireEvent.click(row('Bar SL').querySelector('[data-first-column]')!, { metaKey: true })
      const press = screen.getByRole('button', { name: /Colour from group/ })
      fireEvent.click(press)
      await act(async () => {})
      // PAR 1 keeps its pink; PAR 3's two groups disagree; Bar SL's group has no colour.
      expect(sent()).toEqual([[{ patchId: 2, colour: 'red' }]])
      expect(info).toHaveBeenCalledWith('Coloured 1 of 4 fixtures from their groups', {
        id: 'patches:colour-from-group',
        description: 'PAR 1 already had a colour — ⌥ for all of them · PAR 3 in groups of different colours · Bar SL in no coloured group',
      })
      fireEvent.click(press, { altKey: true })
      expect(sent()[1]).toEqual([
        { patchId: 1, colour: 'red' },
        { patchId: 2, colour: 'red' },
      ])
    })

    it('says nothing of a copy the desk refused — the refusal is the sheet\'s own toast', async () => {
      const info = vi.spyOn(toast, 'info')
      const error = vi.spyOn(toast, 'error')
      setPatchColours.mockImplementationOnce(() => ({
        unwrap: () => Promise.reject({ status: 400, data: { error: 'At most 500 placements per request' } }),
      }))
      drawColours()
      fireEvent.click(row('PAR 2').querySelector('[data-first-column]')!)
      fireEvent.click(row('PAR 3').querySelector('[data-first-column]')!, { metaKey: true })
      fireEvent.click(screen.getByRole('button', { name: /Colour from group/ }))
      await act(async () => {})
      expect(setPatchColours).toHaveBeenCalledTimes(1)
      expect(error).toHaveBeenCalledWith('At most 500 placements per request', { id: 'sheet-write:patches:colour' })
      expect(info).not.toHaveBeenCalled()
    })

    it('offers no clear where nothing would change', async () => {
      drawColours()
      fireEvent.click(colourCell('PAR 2'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const popover = (await screen.findByRole('group', { name: 'Colours' })).parentElement!
      expect(within(popover).queryByRole('button', { name: /^(None|Clear|Take venue)/ })).toBeNull()
    })

    it('marks the name with the colour — a swatch, and the row class that washes the name cell', () => {
      drawColours()
      const par1 = row('PAR 1')
      expect(par1.className).toContain('fixture-tag-pink')
      expect(within(par1).getByRole('img', { name: 'Colour: Pink' })).toBeInTheDocument()
      expect(par1.querySelector(':scope > [data-name-cell]')).not.toBeNull()
      expect(row('PAR 2').className).not.toContain('fixture-tag')
      expect(within(row('PAR 2')).queryByRole('img', { name: /^Colour:/ })).toBeNull()
    })

    describe('a venue head in a show (D10)', () => {
      // PAR 2 is the venue's, recoloured green by this show; Bar SL is the venue's own lime.
      const VENUE_COLOURED = COLOURED.map((p) =>
        p.id === 2
          ? { ...p, owner: 'VENUE' as const, colour: 'green', colourOwner: 'SHOW' as const }
          : p.id === 4
            ? { ...p, owner: 'VENUE' as const, colourOwner: 'VENUE' as const }
            : { ...p, owner: 'SHOW' as const },
      )
      const venueRows: PatchSheetRow[] = rows.map((r) => ({ ...r, patch: VENUE_COLOURED.find((p) => p.id === r.patch.id)! }))
      const drawVenue = () => drawColours({ rows: venueRows, allPatches: VENUE_COLOURED })

      it("draws yours beside a show's recolour, and Take venue's gives the venue's back", async () => {
        drawVenue()
        expect(within(colourCell('PAR 2')).getByText('yours')).toBeInTheDocument()
        expect(within(colourCell('Bar SL')).queryByText('yours')).toBeNull()
        fireEvent.click(colourCell('PAR 2'))
        fireEvent.click(screen.getByRole('button', { name: 'Set' }))
        await screen.findByRole('group', { name: 'Colours' })
        expect(screen.queryByRole('button', { name: 'None' })).toBeNull()
        fireEvent.click(screen.getByRole('button', { name: 'Take venue’s' }))
        expect(setPatchColours).toHaveBeenCalledWith({ projectId: 1, colours: [{ patchId: 2, colour: null }] })
      })

      it("names a mixed clear Clear: the show's heads lose their colour, the recoloured venue head takes the venue's back", async () => {
        drawVenue()
        dragColours(0, 1)
        fireEvent.click(screen.getByRole('button', { name: 'Set' }))
        await screen.findByRole('group', { name: 'Colours' })
        const clear = screen.getByRole('button', { name: 'Clear' })
        expect(clear).toHaveAttribute('title', expect.stringMatching(/^Your fixtures lose their colour; the venue’s take/))
        fireEvent.click(clear)
        expect(setPatchColours).toHaveBeenCalledWith({
          projectId: 1,
          colours: [
            { patchId: 1, colour: null },
            { patchId: 2, colour: null },
          ],
        })
      })

      it("recolours a venue head, where every other column is the venue's — and ⌫ leaves its own colour", () => {
        drawVenue()
        expect(colourCell('Bar SL')).not.toBeDisabled()
        fireEvent.click(colourCell('Bar SL'))
        fireEvent.keyDown(window, { key: '6' })
        expect(setPatchColours).toHaveBeenLastCalledWith({ projectId: 1, colours: [{ patchId: 4, colour: 'cyan' }] })
        setPatchColours.mockClear()
        fireEvent.keyDown(window, { key: 'Backspace' })
        expect(setPatchColours).not.toHaveBeenCalled()
      })
    })
  })

  describe('the Head column', () => {
    // PAR 1..3 numbered 1..3 in address order; Bar SL unnumbered.
    const NUMBERED = RIG.map((p, i) => ({ ...p, headNumber: i < 3 ? i + 1 : null }))
    const numberedRows: PatchSheetRow[] = NUMBERED.map((p) => ({
      id: patchRowId(p.id),
      patch: p,
      riggingName: null,
      acceptsBeamAngle: false,
      acceptsGel: false,
    }))
    const drawHeads = (over: Partial<React.ComponentProps<typeof PatchSheet>> = {}) =>
      draw({ rows: numberedRows, allPatches: NUMBERED, visibleColumns: ['head'], ...over })

    /** The Head cell's trigger on a named row. */
    const headCell = (name: string) => row(name).querySelector('[data-cell="head"] button') as HTMLElement

    /** A marquee down the Head column over rows `from`..`to` (indices into the drawn rows). */
    function dragHeads(from: number, to: number) {
      const cell = headCell(screen.getAllByText(/^(PAR \d|Bar SL)$/)[from].textContent!)
      fireEvent.pointerDown(cell, { button: 0, clientX: 260, clientY: from * 36 + 10 })
      fireEvent.pointerMove(cell, { button: 0, buttons: 1, clientX: 280, clientY: to * 36 + 26 })
      fireEvent.pointerUp(cell, { button: 0, clientX: 280, clientY: to * 36 + 26 })
      fireEvent.click(cell)
    }

    it('numbers a selection consecutively from the typed number, in one request of the heads that moved', async () => {
      drawHeads()
      // PAR 3 (3) and Bar SL (unnumbered), from 3: PAR 3 keeps 3, Bar SL takes 4.
      dragHeads(2, 3)
      expect(screen.getByText('2 cells')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const field = await screen.findByLabelText('Head')
      fireEvent.change(field, { target: { value: '3' } })
      expect(screen.getByText('PAR 3 → 3')).toBeInTheDocument()
      expect(screen.getByText('Bar SL → 4')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
      expect(setHeadNumbers).toHaveBeenCalledTimes(1)
      expect(setHeadNumbers).toHaveBeenCalledWith({ projectId: 1, numbers: [{ patchId: 4, headNumber: 4 }] })
      expect(updatePatch).not.toHaveBeenCalled()
    })

    it('swaps numbers within the selection — the desk judges the batch as it will stand', async () => {
      // Drawn PAR 2 above PAR 1, numbered from 1: PAR 2 → 1, PAR 1 → 2, which each other holds.
      drawHeads({ rows: [numberedRows[1], numberedRows[0]] })
      dragHeads(0, 1)
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      fireEvent.change(await screen.findByLabelText('Head'), { target: { value: '1' } })
      expect(screen.getByRole('button', { name: 'Apply' })).not.toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
      expect(setHeadNumbers).toHaveBeenCalledWith({
        projectId: 1,
        numbers: [
          { patchId: 2, headNumber: 1 },
          { patchId: 1, headNumber: 2 },
        ],
      })
    })

    it('names a number another head holds, and refuses Apply', async () => {
      drawHeads()
      // PAR 1 and PAR 2 from 2: PAR 1 → 2 is PAR 2's (in the batch, fine), PAR 2 → 3 is PAR 3's.
      dragHeads(0, 1)
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const field = await screen.findByLabelText('Head')
      fireEvent.change(field, { target: { value: '2' } })
      expect(screen.getByText('Head 3 is already PAR 3')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
      fireEvent.keyDown(field, { key: 'Enter' })
      fireEvent.change(field, { target: { value: '1.5' } })
      expect(screen.getByText(/A head number is a whole number/)).toBeInTheDocument()
      expect(setHeadNumbers).not.toHaveBeenCalled()
    })

    it('opens an unnumbered head on the next free number, saying so, and applies it', async () => {
      // PAR 1..3 hold 1..3, so Bar SL is offered 4 — a real, selected draft, not a placeholder.
      drawHeads()
      fireEvent.click(headCell('Bar SL'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      const field = await screen.findByLabelText('Head')
      expect(field).toHaveValue('4')
      expect(screen.getByText(/Next free head number/)).toBeInTheDocument()
      fireEvent.change(field, { target: { value: '9' } })
      expect(screen.queryByText(/Next free head number/)).toBeNull()
      fireEvent.change(field, { target: { value: '4' } })
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
      expect(setHeadNumbers).toHaveBeenCalledWith({ projectId: 1, numbers: [{ patchId: 4, headNumber: 4 }] })
    })

    it('opens a numbered head on its own number, with no suggestion', async () => {
      drawHeads()
      fireEvent.click(headCell('PAR 2'))
      fireEvent.click(screen.getByRole('button', { name: 'Set' }))
      expect(await screen.findByLabelText('Head')).toHaveValue('2')
      expect(screen.queryByText(/Next free head number/)).toBeNull()
    })

    it('clears to unnumbered, sending only the heads that had a number', () => {
      drawHeads()
      dragHeads(2, 3)
      fireEvent.click(screen.getByRole('button', { name: 'Clear cells' }))
      expect(setHeadNumbers).toHaveBeenCalledWith({ projectId: 1, numbers: [{ patchId: 3, headNumber: null }] })
    })

    it('rings a head number two heads share, names the other, and says so under the sheet', () => {
      // A sync merge can import a shared number; the desk refuses one on every write.
      const shared = NUMBERED.map((p) => (p.id === 4 ? { ...p, headNumber: 2 } : p))
      drawHeads({
        allPatches: shared,
        rows: numberedRows.map((r) => (r.patch.id === 4 ? { ...r, patch: shared[3] } : r)),
      })
      const cell = row('Bar SL').querySelector('[data-cell="head"]')!
      expect(cell.className).toContain('ring-destructive')
      expect(cell).toHaveAttribute('title', 'Head 2 is also PAR 2')
      expect(screen.getByText(/2 fixtures share a head number/)).toBeInTheDocument()
      expect(row('PAR 1').querySelector('[data-cell="head"]')!.className).not.toContain('ring-destructive')
    })
  })
})
