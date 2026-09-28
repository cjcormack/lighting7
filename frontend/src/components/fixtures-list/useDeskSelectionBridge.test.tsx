// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueTarget } from '@/api/cuesApi'
import type { SelectionSource } from '@/api/selectionApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { Fixture } from '@/store/fixtures'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from './columns'
import { rowIdsForTargets, selectedRowTargets, type Row } from './rowModel'

/**
 * The programmer list ↔ desk selection bridge (`midi-surface-plan.md` §3.2, multi-screen plan
 * §3.2).
 *
 * The load-bearing case is the first one: a selected **group row** publishes one `group` entry, not
 * its eight members. Publishing the scope's `targetKeys` instead — the obvious wiring — would send
 * loose fixture keys carrying no discriminator, the strip's group select LED would stay dark, and
 * nothing would light the group row again on the way back. The rest are the quiet ones: no
 * publish on mount, no publish caused by the filter, no echo — and, since the mask joined the
 * selection, a marquee publishing its families, a frame with the same heads and a *new* mask
 * being applied rather than swallowed as an echo, and re-linking adopting the desk's fact rather
 * than publishing over it.
 */

let deskTargets: CueTarget[] = []
let deskFamilies: AttributeFamily[] | null = null
let deskSource: SelectionSource | null = null
const setDeskSelection = vi.fn()

vi.mock('@/store/selection', () => ({
  useDeskSelectionSnapshot: () => ({ targets: deskTargets, families: deskFamilies, source: deskSource }),
  setDeskSelection: (t: CueTarget[], f: AttributeFamily[] | null) => setDeskSelection(t, f),
}))

const { useDeskSelectionBridge } = await import('./useDeskSelectionBridge')

function fixture(key: string): Fixture {
  return { key, name: key, typeKey: 'par' } as Fixture
}

const rows: Row[] = [
  {
    kind: 'group',
    id: 'group:Front wash',
    name: 'Front wash',
    members: [fixture('par-1'), fixture('par-2')],
    memberCount: 2,
    expanded: false,
  } as unknown as Row,
  { kind: 'fixture', id: 'fixture:par-9', fixture: fixture('par-9') } as unknown as Row,
]

const NO_CELLS: CellRef<ColumnKey>[] = []
const COLOUR_ON_PAR_9: CellRef<ColumnKey>[] = [{ rowId: 'fixture:par-9', col: 'colour' }]
const POSITION_ON_PAR_9: CellRef<ColumnKey>[] = [{ rowId: 'fixture:par-9', col: 'position' }]

/**
 * Drives the hook the way `FixturesListContainer` does — ids and cells in, `setSelection` out —
 * with the container's own storage stubbed by `state`, so a change the hook makes is visible to
 * the next render exactly as a Redux dispatch would be. `setSelection` is the container's row door,
 * which clears the cells as the real one does.
 */
function drive(initial: string[] = [], enabled = true) {
  const state = { ids: new Set(initial), rows, cells: NO_CELLS, enabled }
  const setSelection = vi.fn((ids: readonly string[]) => {
    state.ids = new Set(ids)
    state.cells = NO_CELLS
  })
  const hook = renderHook(
    (p: { ids: Set<string>; rows: Row[]; cells: CellRef<ColumnKey>[]; enabled: boolean }) =>
      useDeskSelectionBridge(p.enabled, p.rows, p.ids, p.cells, setSelection),
    { initialProps: { ids: state.ids, rows, cells: NO_CELLS, enabled } },
  )
  const render = () =>
    act(() =>
      hook.rerender({ ids: state.ids, rows: state.rows, cells: state.cells, enabled: state.enabled }),
    )
  /** The operator moves the row selection. */
  const select = (ids: string[]) => {
    state.ids = new Set(ids)
    state.cells = NO_CELLS
    render()
  }
  /**
   * The operator draws a marquee: the cells' rows are the selection. The id set keeps its identity
   * while its members are unchanged, as the container's `cellRowIds` does — a drag mints a fresh
   * `cells` array per frame, not a fresh row set.
   */
  const marquee = (cells: CellRef<ColumnKey>[]) => {
    const ids = new Set(cells.map((c) => c.rowId))
    const same = ids.size === state.ids.size && [...ids].every((id) => state.ids.has(id))
    if (!same) state.ids = ids
    state.cells = cells
    render()
  }
  /**
   * The programmer's scope band is pressed while a marquee stands. The container converts the
   * marquee to its rows through the row door — same heads, no cells — so this mirrors that: the id
   * set is re-minted (it comes from the row selection now, not from `cellRowIds`) and the cells go.
   */
  const scopeSwitch = () => {
    state.ids = new Set(state.ids)
    state.cells = NO_CELLS
    render()
  }
  /**
   * A `selection.state` frame lands in the *same* render the scope switch's conversion does — the
   * race two reviewers asked about. Distinct from calling `frame()` and `scopeSwitch()` in turn,
   * which is the case where the frame is processed while the marquee still stands.
   */
  const frameDuringScopeSwitch = (
    targets: CueTarget[],
    families: AttributeFamily[] | null = null,
    source: SelectionSource | null = null,
  ) => {
    deskTargets = targets
    deskFamilies = families
    deskSource = source
    state.ids = new Set(state.ids)
    state.cells = NO_CELLS
    render()
  }
  /** The list's filter changes what is on screen; the selection does not move. */
  const filter = (next: Row[]) => {
    state.rows = next
    render()
  }
  /** The desk chip flips follow / local. */
  const follow = (next: boolean) => {
    state.enabled = next
    render()
  }
  /** A `selection.state` frame lands. */
  const frame = (targets: CueTarget[], families: AttributeFamily[] | null = null, source: SelectionSource | null = null) => {
    deskTargets = targets
    deskFamilies = families
    deskSource = source
    render()
  }
  return { hook, setSelection, state, render, select, marquee, scopeSwitch, frameDuringScopeSwitch, filter, follow, frame }
}

beforeEach(() => {
  deskTargets = []
  deskFamilies = null
  deskSource = null
  setDeskSelection.mockClear()
})

describe('selectedRowTargets', () => {
  it('publishes a selected group row as one group entry, never as its members', () => {
    expect(selectedRowTargets(rows, new Set(['group:Front wash']))).toEqual([
      { type: 'group', key: 'Front wash' },
    ])
  })

  it('answers a fixture row by key', () => {
    expect(selectedRowTargets(rows, new Set(['fixture:par-9']))).toEqual([
      { type: 'fixture', key: 'par-9' },
    ])
  })
})

describe('rowIdsForTargets', () => {
  it('reads the same mapping backwards', () => {
    expect(rowIdsForTargets(rows, [{ type: 'group', key: 'Front wash' }])).toEqual([
      'group:Front wash',
    ])
  })

  it('ignores a target with no row rather than inventing one', () => {
    expect(rowIdsForTargets(rows, [{ type: 'group', key: 'Nowhere' }])).toEqual([])
  })
})

describe('useDeskSelectionBridge', () => {
  it('publishes nothing on mount', () => {
    // The slice starts empty — the container clears its scope on unmount — so a first-run publish
    // would wipe whatever the surface had selected the moment this page opened.
    drive()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  it('publishes an operator’s change, with no mask for a row selection', () => {
    const { select } = drive()
    select(['group:Front wash'])
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'group', key: 'Front wash' }], null)
  })

  it('publishes a marquee’s families beside its rows', () => {
    // A marquee is targets × families (D3): the desk's mask is what a pad press on the other
    // screen is masked to, so the rows alone would leave every press there unmasked.
    const { marquee } = drive()
    marquee(COLOUR_ON_PAR_9)
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], ['COLOUR'])
  })

  it('publishes again when the marquee moves to another column on the same rows', () => {
    const { marquee } = drive()
    marquee(COLOUR_ON_PAR_9)
    setDeskSelection.mockClear()
    marquee(POSITION_ON_PAR_9)
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], ['POSITION'])
  })

  it('does not republish for a fresh cells array naming the same families', () => {
    // A drag mints a new `cells` array every animation frame; only the families it names are the
    // desk's business.
    const { marquee } = drive()
    marquee(COLOUR_ON_PAR_9)
    setDeskSelection.mockClear()
    marquee([{ rowId: 'fixture:par-9', col: 'colour' }])
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  it('marks the rows a frame names, and does not echo it back', () => {
    const { setSelection, frame, render } = drive()
    frame([{ type: 'group', key: 'Front wash' }])
    expect(setSelection).toHaveBeenCalledWith(['group:Front wash'])

    // The dispatch has landed; the publish effect must recognise its own doing.
    render()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  /**
   * A scope switch under a marquee (multi-screen session 1 follow-up A). The container used to
   * clear the cells outright, and because the rows had been cleared when the cells were selected
   * that emptied the whole selection — so what reached the desk was `set([])`, and every other
   * screen's target band and family pill went dark. **Never publish an empty selection the
   * operator did not make**: the switch converts the marquee to its rows, so the heads are
   * republished unchanged and only the mask is dropped.
   */
  it('republishes the same heads with no mask when a scope switch drops a marquee to its rows', () => {
    const { marquee, scopeSwitch } = drive()
    marquee(COLOUR_ON_PAR_9)
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], ['COLOUR'])
    setDeskSelection.mockClear()

    scopeSwitch()

    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], null)
    // The regression itself, stated as what must never go out.
    for (const [targets] of setDeskSelection.mock.calls) expect(targets).not.toEqual([])
  })

  /**
   * The scope switch against an in-flight desk frame. Two reviewers raised this as a swallowed
   * republish; it is two different cases, and both land where the bridge's own rules say they
   * should, so what these pin is the outcome rather than a fix.
   */
  it('still republishes with no mask when a foreign frame lands in the same render as the switch', () => {
    // The conversion has already rendered, so `hasCells` is false by the time the desk→list effect
    // reads it: the "same heads" fast path returns without arming `pendingRef`, and the publish
    // effect is free. This is the ordinary race, and the scope switch's own publish survives it.
    const { marquee, frameDuringScopeSwitch } = drive()
    marquee(COLOUR_ON_PAR_9)
    setDeskSelection.mockClear()
    frameDuringScopeSwitch([{ type: 'fixture', key: 'par-9' }], ['POSITION'], {
      kind: 'window',
      name: 'Screen 2',
    })
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], null)
    for (const [targets] of setDeskSelection.mock.calls) expect(targets).not.toEqual([])
  })

  it('leaves another window’s mask standing when its frame is processed before the switch', () => {
    // The other order: the frame is read while the marquee still stands, so the bridge itself
    // drops it to rows and mutes the publish — `landed`. A scope switch arriving behind that must
    // NOT then publish `families: null` over the top, because the mask it would clear is not this
    // window's to clear. The heads are unchanged either way, which is the invariant that matters.
    const { setSelection, marquee, scopeSwitch, frame } = drive()
    marquee(COLOUR_ON_PAR_9)
    setDeskSelection.mockClear()
    frame([{ type: 'fixture', key: 'par-9' }], ['POSITION'], { kind: 'window', name: 'Screen 2' })
    expect(setSelection).toHaveBeenCalledWith(['fixture:par-9'])
    scopeSwitch()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  it('does not narrow the desk when the list filters a selected row out of view', () => {
    // `selectedRowTargets` narrows whenever `rows` narrows, and `rows` narrows on a filter
    // keystroke while `selectedIds` does not — an effect keyed on the target list would shrink the
    // desk's selection every time the operator typed.
    const { select, filter } = drive()
    select(['group:Front wash'])
    setDeskSelection.mockClear()
    filter([rows[1]!])
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  /**
   * The mute apply sets must lift on *either* outcome. A click that races an incoming frame leaves
   * `selectedIds` matching neither the applied ids nor the pre-dispatch ones — and a guard that
   * only cleared on an exact match would then stay armed for the rest of the mount, silently
   * disabling the publish direction with nothing to see.
   */
  it('keeps publishing after an operator click races an incoming frame', () => {
    const { setSelection, render, select, state, frame } = drive()
    frame([{ type: 'group', key: 'Front wash' }])
    expect(setSelection).toHaveBeenCalledWith(['group:Front wash'])

    // The operator gets somewhere else before apply's dispatch has rendered.
    state.ids = new Set(['fixture:par-9'])
    render()
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], null)

    // And the next click still gets through.
    setDeskSelection.mockClear()
    select(['group:Front wash'])
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'group', key: 'Front wash' }], null)
  })

  /**
   * The desk acknowledges a `set` with a `selection.state` frame, so after a publish the next frame
   * is usually our own words coming back. A marquee publishes at every row boundary, and the
   * echoes arrive in order but late — so without the bridge remembering what it sent, the echo of
   * an earlier frame reaches a selection that has moved on and is applied as though the desk had
   * moved it, which destroys the marquee that caused it.
   */
  it('drops the echo of its own publish rather than applying it', () => {
    const { setSelection, select, frame } = drive()
    select(['fixture:par-9'])
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], null)
    frame([{ type: 'fixture', key: 'par-9' }])
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('drops the echo of a marquee — same heads, same families', () => {
    const { setSelection, marquee, frame } = drive()
    marquee(COLOUR_ON_PAR_9)
    frame([{ type: 'fixture', key: 'par-9' }], ['COLOUR'])
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('drops a late echo of an earlier publish, and does not roll the selection back to it', () => {
    const { setSelection, select, frame } = drive()
    select(['fixture:par-9'])
    select(['fixture:par-9', 'group:Front wash'])
    expect(setDeskSelection).toHaveBeenCalledTimes(2)
    // The first frame's echo arrives after the second publish went out.
    frame([{ type: 'fixture', key: 'par-9' }])
    expect(setSelection).not.toHaveBeenCalled()
    // And the second's.
    frame([{ type: 'fixture', key: 'par-9' }, { type: 'group', key: 'Front wash' }])
    expect(setSelection).not.toHaveBeenCalled()
  })

  it('treats an echo the desk narrowed — a target it could not resolve — as an echo still', () => {
    const { setSelection, select, frame } = drive()
    select(['fixture:par-9', 'group:Front wash'])
    frame([{ type: 'fixture', key: 'par-9' }])
    expect(setSelection).not.toHaveBeenCalled()
  })

  /**
   * The FIFO key is targets **and** families. A frame with the same heads and a different mask is
   * exactly what a second window changing the mask under a standing marquee produces; a key of the
   * heads alone read it as our own echo and swallowed it, leaving this list drawing a Colour
   * marquee while the desk — and every press from here — said Position.
   */
  it('applies a frame with the same heads and a new mask instead of swallowing it as an echo', () => {
    const { setSelection, marquee, frame } = drive()
    marquee(COLOUR_ON_PAR_9)
    // Another window moves the mask before our echo has come back.
    frame([{ type: 'fixture', key: 'par-9' }], ['POSITION'], { kind: 'window', name: 'Screen 2' })
    // Applied as a row selection: this list cannot draw a mask it did not make as a marquee.
    expect(setSelection).toHaveBeenCalledWith(['fixture:par-9'])
  })

  it('applies a new mask on the same heads after its own echo has been acknowledged', () => {
    const { setSelection, marquee, frame, render } = drive()
    marquee(COLOUR_ON_PAR_9)
    frame([{ type: 'fixture', key: 'par-9' }], ['COLOUR'])
    expect(setSelection).not.toHaveBeenCalled()
    frame([{ type: 'fixture', key: 'par-9' }], ['POSITION'], { kind: 'window', name: 'Screen 2' })
    expect(setSelection).toHaveBeenCalledWith(['fixture:par-9'])
    // And the drop to rows is not published back over the desk's mask.
    setDeskSelection.mockClear()
    render()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  it('leaves a frame with the same heads, the same mask and a new source alone', () => {
    // Another window `set` the same heads: the chip moves (the store records the source before
    // this hook runs), the list does not, and nothing is published back.
    const { setSelection, marquee, frame } = drive()
    marquee(COLOUR_ON_PAR_9)
    frame([{ type: 'fixture', key: 'par-9' }], ['COLOUR'])
    setDeskSelection.mockClear()
    frame([{ type: 'fixture', key: 'par-9' }], ['COLOUR'], { kind: 'window', name: 'Screen 2' })
    expect(setSelection).not.toHaveBeenCalled()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  it('applies a deselect-all from the desk even while its own publish is unacknowledged', () => {
    // An empty frame is a subset of everything; read as an echo it would leave the list selected
    // against a desk that has cleared.
    const { setSelection, select, frame } = drive()
    select(['fixture:par-9'])
    frame([])
    expect(setSelection).toHaveBeenCalledWith([])
  })

  it('still applies a frame the desk originated after an echo has been acknowledged', () => {
    const { setSelection, select, frame } = drive()
    select(['fixture:par-9'])
    frame([{ type: 'fixture', key: 'par-9' }])
    // Now the surface moves it.
    frame([{ type: 'group', key: 'Front wash' }])
    expect(setSelection).toHaveBeenCalledWith(['group:Front wash'])
  })

  it('does nothing at all for a list that is not the programmer’s', () => {
    // `/fixtures/list` and `/groups/list` are browsing surfaces; two lists moving one desk-wide
    // fact would fight.
    const setSelection = vi.fn()
    deskTargets = [{ type: 'group', key: 'Front wash' }]
    const hook = renderHook(
      (p: { ids: Set<string> }) => useDeskSelectionBridge(false, rows, p.ids, NO_CELLS, setSelection),
      { initialProps: { ids: new Set<string>() } },
    )
    act(() => {
      hook.rerender({ ids: new Set(['fixture:par-9']) })
    })
    expect(setSelection).not.toHaveBeenCalled()
    expect(setDeskSelection).not.toHaveBeenCalled()
  })

  /**
   * Follow / local (D8). Unlinked, the bridge is off in both directions and the list keeps what it
   * had. Re-linking adopts the desk's fact and publishes nothing — the mount rule, for its reason:
   * a window joining must not clear what another screen has selected.
   */
  it('stops both directions while unlinked, and adopts the desk’s selection on re-link without publishing', () => {
    const { setSelection, select, follow, frame, state } = drive()
    select(['fixture:par-9'])
    setDeskSelection.mockClear()
    follow(false)
    // The desk moves; this list does not.
    frame([{ type: 'group', key: 'Front wash' }])
    expect(setSelection).not.toHaveBeenCalled()
    // The operator moves this list; the desk is not told.
    select(['fixture:par-9', 'group:Front wash'])
    expect(setDeskSelection).not.toHaveBeenCalled()

    follow(true)
    expect(setSelection).toHaveBeenCalledWith(['group:Front wash'])
    expect(setDeskSelection).not.toHaveBeenCalled()
    // And once adopted, the next operator change is published as usual.
    expect([...state.ids]).toEqual(['group:Front wash'])
    select(['fixture:par-9'])
    expect(setDeskSelection).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-9' }], null)
  })
})
