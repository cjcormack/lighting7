import { describe, expect, it } from 'vitest'
import type { ClientRect } from '@dnd-kit/core'
import type { BuskPage } from '@/api/buskApi'
import {
  applyDrop,
  buskBankBodyId,
  buskBankId,
  buskBankUnderId,
  buskGutterId,
  buskPadId,
  BUSK_NEW_ROW_ID,
} from '@/lib/buskLayout'
import type { DragSource, DropTarget } from '@/lib/buskLayout'
import { canLandForTest, insertionSide, resolveDropTarget, resolveRigDropTarget, sameTarget, TARGET_HYSTERESIS_PX } from './buskDnd'
import { applyDrop as applyRigDrop, parseRigDragId, rigRowBodyId, rigTileId } from '@/lib/buskRig'
import type { BuskRig } from '@/api/buskRigApi'
import { slotAssignmentFor } from '@/components/dnd/slotDrop'

/**
 * Where a hover lands.
 *
 * This is the whole of the drag *decision*; `BuskEditProvider` only feeds it what dnd-kit reports
 * and hands the answer to `applyDrop`. Driving a real `DndContext` in jsdom would test dnd-kit's
 * collision detection against rects that are all zero, which is why the decision lives out here.
 */

const page: BuskPage = {
  id: 1,
  uuid: 'p1',
  name: 'Ballads',
  sortOrder: 0,
  rows: [
    {
      columns: [
        {
          id: 1,
          uuid: 'c1',
          width: 12,
          banks: [
            {
              id: 1,
              uuid: 'b1',
              name: 'Movement',
              solo: false,
              flow: 'WRAP',
              pads: [
                { id: 1, uuid: 'pa', kind: 'TEMPLATE', template: { id: 1 } as never },
                { id: 2, uuid: 'pb', kind: 'TEMPLATE', template: { id: 2 } as never },
              ],
            },
          ],
        },
      ],
    },
  ],
}

function rect(left: number, top: number): ClientRect {
  return { top, left, right: left + 100, bottom: top + 50, width: 100, height: 50 } as ClientRect
}

const PAD_1 = buskPadId({ row: 0, column: 0, bank: 0, pad: 1 })
const BANK_0 = { row: 0, column: 0, bank: 0 }

function hover(
  overId: string,
  opts: {
    source?: DragSource['kind']
    activeId?: string
    activeRect?: ClientRect | null
    current?: DropTarget | null
  } = {},
) {
  return resolveDropTarget({
    page,
    source: opts.source ?? 'palette',
    activeId: opts.activeId ?? 'palette:look:9',
    overId,
    collisionIds: [overId],
    activeRect: opts.activeRect ?? null,
    overRect: rect(100, 100),
    current: opts.current ?? null,
  })
}

describe('a stationary pointer cannot move the slot', () => {
  /**
   * The desk crash this exists for, reproduced from its own trace.
   *
   * Dragging a pad near the boundary between two banks in one column, the dashed slot flipped
   * between them every two or three frames while the pointer moved **one pixel** — because opening
   * the slot displaces the pads after it, so the pad *under* a stationary pointer changes, which
   * re-answers "what are you over", which moves the slot back. `BuskEditProvider` forces a
   * re-measure on every target change, so React counted the nested updates and threw *Maximum
   * update depth exceeded* rather than flickering.
   *
   * The `sameBank` stickiness below could not catch it: this oscillation is precisely *between*
   * banks, which that rule is written to allow. The axis that works is pointer movement, because it
   * is the one input the slot cannot change.
   */
  const OTHER_BANK_PAD = buskPadId({ row: 0, column: 0, bank: 0, pad: 0 })
  const showing: DropTarget = { kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 1 } }

  it('keeps the slot where it is while the pointer has barely moved', () => {
    // The geometry has shifted a different pad under the pointer — dnd-kit now reports a different
    // `over` — but the operator has not moved, so the answer must not change.
    expect(
      resolveDropTarget({
        page,
        source: 'pad',
        activeId: 'busk-pad-x',
        overId: OTHER_BANK_PAD,
        collisionIds: [OTHER_BANK_PAD],
        activeRect: rect(60, 100),
        overRect: rect(100, 100),
        current: showing,
        movedSinceTarget: 1,
      }),
    ).toEqual(showing)
  })

  it('holds even when the displacement carries the pointer off every droppable', () => {
    // The slot is not a droppable, so the shift can leave `over` null outright. Closing the slot
    // there would be the same loop with an extra frame in it.
    expect(
      resolveDropTarget({
        page,
        source: 'pad',
        activeId: 'busk-pad-x',
        overId: null,
        collisionIds: [],
        activeRect: rect(60, 100),
        overRect: null,
        current: showing,
        movedSinceTarget: 0,
      }),
    ).toEqual(showing)
  })

  it('lets go once the operator has genuinely moved', () => {
    const moved = resolveDropTarget({
      page,
      source: 'pad',
      activeId: 'busk-pad-x',
      overId: OTHER_BANK_PAD,
      collisionIds: [OTHER_BANK_PAD],
      activeRect: rect(60, 100),
      overRect: rect(100, 100),
      current: showing,
      movedSinceTarget: TARGET_HYSTERESIS_PX,
    })
    expect(moved).not.toEqual(showing)
  })

  it('never holds on the first resolve of a drag, which has no anchor', () => {
    expect(
      resolveDropTarget({
        page,
        source: 'pad',
        activeId: 'busk-pad-x',
        overId: OTHER_BANK_PAD,
        collisionIds: [OTHER_BANK_PAD],
        activeRect: rect(60, 100),
        overRect: rect(100, 100),
        current: null,
        movedSinceTarget: 0,
      }),
    ).not.toBeNull()
  })
})

describe('resolving a hover', () => {
  it('lands before the pad when the pointer is on its leading half', () => {
    expect(hover(PAD_1, { activeRect: rect(60, 100) })).toEqual({
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 1 },
    })
  })

  it('lands after the pad when the pointer has crossed its centre', () => {
    expect(hover(PAD_1, { activeRect: rect(140, 100) })).toEqual({
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 2 },
    })
  })

  it('reads the axis the drag is actually moving along', () => {
    // Barely right but well below: the vertical difference is what decides it.
    expect(hover(PAD_1, { activeRect: rect(105, 200) })).toEqual({
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 2 },
    })
  })

  it('appends at the end of the bank over its body', () => {
    expect(hover(buskBankBodyId({ row: 0, column: 0, bank: 0 }))).toEqual({
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 2 },
    })
  })

  it('reads the three bank zones for a lifted bank', () => {
    const lifted = { source: 'bank' as const, activeId: 'bbank:1.0.0' }
    expect(hover(buskBankUnderId(BANK_0), lifted)).toEqual({ kind: 'bank-under', at: BANK_0 })
    expect(hover(buskGutterId(0, 1), lifted)).toEqual({ kind: 'new-column', row: 0, column: 1 })
    expect(hover(BUSK_NEW_ROW_ID, lifted)).toEqual({ kind: 'new-row' })
  })

  it('takes the deepest thing the pointer is inside, whatever order they arrive in', () => {
    const target = resolveDropTarget({
      page,
      source: 'palette',
      activeId: 'palette:look:9',
      // The bank body contains the pad; the pad must win however dnd-kit sorted them.
      collisionIds: [buskBankBodyId({ row: 0, column: 0, bank: 0 }), PAD_1],
      overId: buskBankBodyId({ row: 0, column: 0, bank: 0 }),
      activeRect: null,
      overRect: rect(100, 100),
    })
    expect(target).toEqual({ kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 1 } })
  })

  it('answers null over nothing, over itself, and over a foreign droppable', () => {
    expect(hover(PAD_1, { activeId: PAD_1 })).toBeNull()
    expect(
      resolveDropTarget({
        page,
        source: 'palette',
        activeId: 'palette:look:9',
        overId: null,
        collisionIds: [],
        activeRect: null,
        overRect: null,
      }),
    ).toBeNull()
    expect(hover('slot-0-3')).toBeNull()
  })
})

describe('what each source may land on', () => {
  /**
   * The regression: a lifted bank over a pad resolved to a *pad* target, so a dashed slot opened
   * inside the bank and the drop then went nowhere — `dropBank` refuses a pad target, and the
   * monitor returned before committing. No request, no toast, a highlight that lied.
   */
  const lifted = { source: 'bank' as const, activeId: 'bbank:1.0.0' }

  it('lets a bank land on no pad and no bank body, however deep they are', () => {
    expect(hover(PAD_1, lifted)).toBeNull()
    expect(hover(buskBankBodyId(BANK_0), lifted)).toBeNull()
    // The pad and the body are the deepest things there; the strip must still win for a bank.
    expect(
      resolveDropTarget({
        page,
        source: 'bank',
        activeId: 'bbank:1.0.0',
        overId: buskBankUnderId(BANK_0),
        collisionIds: [PAD_1, buskBankBodyId(BANK_0), buskBankUnderId(BANK_0)],
        activeRect: null,
        overRect: rect(100, 100),
      }),
    ).toEqual({ kind: 'bank-under', at: BANK_0 })
  })

  it('lets a pad and a palette row land on none of the bank zones', () => {
    for (const source of ['pad', 'palette'] as const) {
      const opts = { source, activeId: source === 'pad' ? buskPadId({ ...BANK_0, pad: 0 }) : 'palette:look:9' }
      expect(hover(buskBankUnderId(BANK_0), opts)).toBeNull()
      expect(hover(buskGutterId(0, 1), opts)).toBeNull()
      expect(hover(BUSK_NEW_ROW_ID, opts)).toBeNull()
    }
  })
})

describe('the open slot stays put', () => {
  /**
   * Opening the slot shifts the pad the pointer was over along by one cell, leaving the pointer
   * on the slot — which is no droppable — inside the bank's body. Collapsing that to the body's
   * append would throw the slot to the end of the bank the instant it opened.
   */
  const current: DropTarget = { kind: 'pad', at: { ...BANK_0, pad: 1 } }

  it('keeps the slot where it is over the body of the bank it is open in', () => {
    expect(hover(buskBankBodyId(BANK_0), { current })).toEqual(current)
  })

  it('still appends when entering another bank, and when no slot is open', () => {
    const elsewhere: DropTarget = { kind: 'pad', at: { row: 1, column: 0, bank: 0, pad: 0 } }
    expect(hover(buskBankBodyId(BANK_0), { current: elsewhere })).toEqual({
      kind: 'pad',
      at: { ...BANK_0, pad: 2 },
    })
    expect(hover(buskBankBodyId(BANK_0))).toEqual({ kind: 'pad', at: { ...BANK_0, pad: 2 } })
  })

  it('moves on when the pointer reaches a pad', () => {
    expect(hover(PAD_1, { current, activeRect: rect(140, 100) })).toEqual({
      kind: 'pad',
      at: { ...BANK_0, pad: 2 },
    })
  })
})

describe('the hover guards', () => {
  it('treats the same landing place as no change', () => {
    const at = { row: 0, column: 0, bank: 0, pad: 1 }
    expect(sameTarget({ kind: 'pad', at }, { kind: 'pad', at: { ...at } })).toBe(true)
    expect(sameTarget({ kind: 'pad', at }, { kind: 'new-row' })).toBe(false)
    expect(sameTarget(null, null)).toBe(true)
    expect(sameTarget(null, { kind: 'new-row' })).toBe(false)
  })

  it('lands before the target when it has no rect to compare', () => {
    expect(insertionSide(null, rect(0, 0))).toBe(0)
  })
})

describe('the slot and the landing place agree', () => {
  /**
   * The regression this exists for: `resolveDropTarget` hands back the gap the dashed slot is drawn
   * in, and `applyDrop` has to put the pad in *that* gap. Testing either half alone missed a
   * downward drag landing one place further on than the operator was shown — each half was
   * self-consistent, and they disagreed about what the index meant.
   */
  function drop(fromPad: number, overPad: number, side: 'leading' | 'trailing') {
    const target = resolveDropTarget({
      page,
      source: 'pad',
      activeId: buskPadId({ row: 0, column: 0, bank: 0, pad: fromPad }),
      overId: buskPadId({ row: 0, column: 0, bank: 0, pad: overPad }),
      collisionIds: [buskPadId({ row: 0, column: 0, bank: 0, pad: overPad })],
      activeRect: rect(side === 'trailing' ? 140 : 60, 100),
      overRect: rect(100, 100),
    })
    const next = applyDrop(page, { kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: fromPad } }, target!)
    return {
      slotBefore: target?.kind === 'pad' ? target.at.pad : null,
      pads: (next ?? page).rows[0].columns[0].banks[0].pads.map((p) => p.template!.id),
    }
  }

  it('lands a pad dragged forwards in the gap the slot showed', () => {
    // [1,2] with 1 lifted, dropped past 2's centre: the slot sits after 2, and so does the pad.
    expect(drop(0, 1, 'trailing')).toEqual({ slotBefore: 2, pads: [2, 1] })
  })

  it('lands a pad dragged backwards in the gap the slot showed', () => {
    expect(drop(1, 0, 'leading')).toEqual({ slotBefore: 0, pads: [2, 1] })
  })

  it('treats the gap either side of the dragged pad as no move', () => {
    expect(drop(0, 1, 'leading').pads).toEqual([1, 2])
  })

  /**
   * The same composition for a bank, which the pad tests never covered: the resolver and the
   * mutator were each tested alone, and a bank drag resolving to a target the mutator refuses is
   * exactly what "the drop looked live and nothing happened" was.
   */
  function twoColumns(): BuskPage {
    const bank = (id: number, name: string) => ({
      id,
      uuid: `b${id}`,
      name,
      solo: false,
      flow: 'WRAP' as const,
      pads: [{ id: id * 10, uuid: `p${id}`, kind: 'TEMPLATE' as const, template: { id } as never }],
    })
    return {
      ...page,
      rows: [
        {
          columns: [
            { id: 1, uuid: 'c1', width: 6, banks: [bank(1, 'Movement')] },
            { id: 2, uuid: 'c2', width: 6, banks: [bank(2, 'Colour')] },
          ],
        },
      ],
    }
  }

  function dropBank(overId: string) {
    const doc = twoColumns()
    const target = resolveDropTarget({
      page: doc,
      source: 'bank',
      activeId: buskBankId(BANK_0),
      overId,
      collisionIds: [overId],
      activeRect: null,
      overRect: rect(100, 100),
    })
    expect(target).not.toBeNull()
    const next = applyDrop(doc, { kind: 'bank', at: BANK_0 }, target!)
    expect(next).not.toBeNull()
    return next!.rows.map((row) => row.columns.map((column) => column.banks.map((b) => b.name)))
  }

  it('stacks a bank under the one it was shown under', () => {
    expect(dropBank(buskBankUnderId({ row: 0, column: 1, bank: 0 }))).toEqual([[['Colour', 'Movement']]])
  })

  it('opens the column at the gutter it was shown in', () => {
    expect(dropBank(buskGutterId(0, 2))).toEqual([[['Colour'], ['Movement']]])
  })

  it('starts the row it was shown below the page', () => {
    expect(dropBank(BUSK_NEW_ROW_ID)).toEqual([[['Colour']], [['Movement']]])
  })
})

// ─── The rig shares the context ─────────────────────────────────────────

const rig: BuskRig = {
  rows: [
    {
      id: 1,
      uuid: 'r1',
      name: 'Wash',
      tiles: [
        { id: 1, uuid: 'ta', kind: 'GROUP', group: { name: 'A' } as never, cellMode: 'PIPS' },
        { id: 2, uuid: 'tb', kind: 'FIXTURE', patch: { id: 2, key: 'b', name: 'B' }, cellMode: 'PIPS' },
        { id: 3, uuid: 'tc', kind: 'FIXTURE', patch: { id: 3, key: 'c', name: 'C' }, cellMode: 'PIPS' },
      ],
    },
  ],
}

describe('the rig band on the one drag context', () => {
  it('lets a rig source land only on the rig, and a page source only on the page', () => {
    expect(canLandForTest('rig-palette', 'rig-tile')).toBe(true)
    expect(canLandForTest('rig-tile', 'rig-row-body')).toBe(true)
    expect(canLandForTest('rig-tile', 'rig-new-row')).toBe(true)
    expect(canLandForTest('rig-row', 'rig-row-gap')).toBe(true)
    expect(canLandForTest('rig-row', 'rig-tile')).toBe(false)
    expect(canLandForTest('rig-palette', 'pad')).toBe(false)
    expect(canLandForTest('rig-tile', 'bank-body')).toBe(false)
    expect(canLandForTest('palette', 'rig-tile')).toBe(false)
    expect(canLandForTest('pad', 'rig-row-body')).toBe(false)
    expect(canLandForTest('bank', 'rig-row-gap')).toBe(false)
  })

  it('is ignored by the cue-slot handler, which reads only a busk-palette drag', () => {
    expect(
      slotAssignmentFor({ type: 'rig-palette', record: { kind: 'group', group: { name: 'A' } }, name: 'A' }),
    ).toBeNull()
    expect(parseRigDragId('slot-1-2')).toBeNull()
  })

  it('draws the slot where the tile will land — a downward drag within a row', () => {
    // Tile A (index 0) lifted, pointer on the trailing half of tile C (index 2): the slot opens
    // after C, at index 3, with A still in place and ghosted.
    const target = resolveRigDropTarget({
      rig,
      source: 'rig-tile',
      activeId: rigTileId({ row: 0, tile: 0 }),
      overId: rigTileId({ row: 0, tile: 2 }),
      collisionIds: [rigTileId({ row: 0, tile: 2 }), rigRowBodyId(0)],
      activeRect: rect(160, 100),
      overRect: rect(100, 100),
    })
    expect(target).toEqual({ kind: 'tile', at: { row: 0, tile: 3 } })
    // And the drop lands A exactly there — after C, not one past it.
    const next = applyRigDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 0 } }, target!)
    expect(next!.rows![0].tiles!.map((t) => t.group?.name ?? t.patch?.name)).toEqual(['B', 'C', 'A'])
  })

  it('appends through the row body, and keeps an open slot while the pointer stays in that body', () => {
    const appended = resolveRigDropTarget({
      rig,
      source: 'rig-palette',
      activeId: 'rpal:group:Z',
      overId: rigRowBodyId(0),
      collisionIds: [rigRowBodyId(0)],
      activeRect: null,
      overRect: rect(0, 0),
    })
    expect(appended).toEqual({ kind: 'tile', at: { row: 0, tile: 3 } })
    const held = resolveRigDropTarget({
      rig,
      source: 'rig-palette',
      activeId: 'rpal:group:Z',
      overId: rigRowBodyId(0),
      collisionIds: [rigRowBodyId(0)],
      activeRect: null,
      overRect: rect(0, 0),
      current: { kind: 'tile', at: { row: 0, tile: 1 } },
    })
    expect(held).toEqual({ kind: 'tile', at: { row: 0, tile: 1 } })
  })

  it('answers nothing for a page droppable under a rig source', () => {
    expect(
      resolveRigDropTarget({
        rig,
        source: 'rig-tile',
        activeId: rigTileId({ row: 0, tile: 0 }),
        overId: PAD_1,
        collisionIds: [PAD_1, buskBankBodyId(BANK_0)],
        activeRect: rect(0, 0),
        overRect: rect(0, 0),
      }),
    ).toBeNull()
  })
})
