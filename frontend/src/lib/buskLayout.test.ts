import { describe, expect, it } from 'vitest'
import type { BuskPage, BuskPad } from '@/api/buskApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import {
  addBankColumn,
  allBanks,
  lastPadOfBank,
  addRow,
  applyDrop,
  buskBankBodyId,
  buskBankId,
  buskBankUnderId,
  buskGutterId,
  buskPadId,
  buskPageTabId,
  buskPaletteId,
  BUSK_NEW_ROW_ID,
  dropTargetFor,
  duplicateBank,
  libraryStarterLayout,
  nextBankName,
  normalisePage,
  parseBuskDragId,
  recordsOnPage,
  removeBank,
  removePad,
  setBank,
  setColumnWidth,
  toLayoutRequest,
} from './buskLayout'

// ─── Fixtures ───────────────────────────────────────────────────────────

let nextId = 100
function templatePad(name: string): BuskPad {
  const id = nextId++
  return {
    id,
    uuid: `pad-${id}`,
    kind: 'TEMPLATE',
    template: { id, name } as TemplateSummary,
  }
}

function bank(name: string, pads: BuskPad[], overrides: Partial<{ solo: boolean }> = {}) {
  const id = nextId++
  return { id, uuid: `bank-${id}`, name, solo: overrides.solo ?? false, flow: 'WRAP' as const, pads }
}

function column(width: number, banks: ReturnType<typeof bank>[]) {
  const id = nextId++
  return { id, uuid: `col-${id}`, width, banks }
}

/**
 * Row 0: column ½ [Movement: A, B, C] · column ¼ [Colour: D] over [Beam: E]
 * Row 1: column full [Cues: F]
 */
function samplePage(): BuskPage {
  return {
    id: 1,
    uuid: 'page-1',
    name: 'Ballads',
    sortOrder: 0,
    rows: [
      {
        columns: [
          column(6, [bank('Movement', [templatePad('A'), templatePad('B'), templatePad('C')], { solo: true })]),
          column(3, [bank('Colour', [templatePad('D')]), bank('Beam', [templatePad('E')])]),
        ],
      },
      { columns: [column(12, [bank('Cues', [templatePad('F')])])] },
    ],
  }
}

function padNames(page: BuskPage, row: number, col: number, bnk: number): string[] {
  return page.rows[row].columns[col].banks[bnk].pads.map((p) => p.template?.name ?? p.look?.name ?? '?')
}

function bankNames(page: BuskPage): string[][][] {
  return page.rows.map((r) => r.columns.map((c) => c.banks.map((b) => b.name)))
}

// ─── Drag ids ───────────────────────────────────────────────────────────

describe('the drag id grammar', () => {
  it('round-trips every form', () => {
    const at = { row: 1, column: 2, bank: 3, pad: 4 }
    expect(parseBuskDragId(buskPadId(at))).toEqual({ kind: 'pad', at })
    expect(parseBuskDragId(buskBankBodyId(at))).toEqual({ kind: 'bank-body', at: { row: 1, column: 2, bank: 3 } })
    expect(parseBuskDragId(buskBankId(at))).toEqual({ kind: 'bank', at: { row: 1, column: 2, bank: 3 } })
    expect(parseBuskDragId(buskBankUnderId(at))).toEqual({ kind: 'bank-under', at: { row: 1, column: 2, bank: 3 } })
    expect(parseBuskDragId(buskGutterId(1, 2))).toEqual({ kind: 'gutter', row: 1, column: 2 })
    expect(parseBuskDragId(BUSK_NEW_ROW_ID)).toEqual({ kind: 'new-row' })
    expect(parseBuskDragId(buskPaletteId('LOOK', 7))).toEqual({ kind: 'palette', recordKind: 'LOOK', id: 7 })
    expect(parseBuskDragId(buskPageTabId(3))).toEqual({ kind: 'page-tab', index: 3 })
  })

  it('answers null for an id belonging to something else', () => {
    // This is how the busk page and the cue-slot grid share one DndContext.
    expect(parseBuskDragId('slot-0-3')).toBeNull()
    expect(parseBuskDragId('slot-item-12')).toBeNull()
    expect(parseBuskDragId('t:7')).toBeNull()
    expect(parseBuskDragId('gbody:2')).toBeNull()
  })

  it('collapses a bank body to an append at the end of that bank', () => {
    const page = samplePage()
    const parsed = parseBuskDragId(buskBankBodyId({ row: 0, column: 0, bank: 0 }))!
    expect(dropTargetFor(parsed, page)).toEqual({ kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 3 } })
  })
})

// ─── Dropping a pad ─────────────────────────────────────────────────────

describe('dropping a library record', () => {
  const record = { kind: 'LOOK' as const, look: { id: 42, name: 'Storm Wash' } as LookSummary }

  it('inserts before the pad it landed on', () => {
    const next = applyDrop(samplePage(), { kind: 'palette', record }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 1 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['A', 'Storm Wash', 'B', 'C'])
  })

  it('appends at the end of the bank', () => {
    const next = applyDrop(samplePage(), { kind: 'palette', record }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 3 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['A', 'B', 'C', 'Storm Wash'])
  })

  it('fills an empty bank, which is the only way into one', () => {
    const page = addRow(samplePage())
    const next = applyDrop(page, { kind: 'palette', record }, {
      kind: 'pad',
      at: { row: 2, column: 0, bank: 0, pad: 0 },
    })!
    expect(padNames(next, 2, 0, 0)).toEqual(['Storm Wash'])
  })

  it('mints a pad with no id, so the layout write creates it', () => {
    const next = applyDrop(samplePage(), { kind: 'palette', record }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 0 },
    })!
    const pads = toLayoutRequest(next).rows[0].columns[0].banks[0].pads
    expect(pads[0]).toEqual({ lookId: 42 })
    expect(pads[1].padId).toBeDefined()
  })

  it('will not land on a bank zone', () => {
    expect(applyDrop(samplePage(), { kind: 'palette', record }, { kind: 'new-row' })).toBeNull()
  })
})

describe('moving a pad', () => {
  it('moves down within a bank, landing in the gap the target names', () => {
    // A pad target is an **insertion point** counted over the document as it stands — index 2 is
    // the gap between B and C — not an `arrayMove` destination. Getting these two conventions
    // confused is what made a downward drag overshoot by one.
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 0 } }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 2 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['B', 'A', 'C'])
  })

  it('moves down to the end of the bank', () => {
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 0 } }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 3 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['B', 'C', 'A'])
  })

  it('answers null for the gap on either side of the pad being dragged', () => {
    const at = { row: 0, column: 0, bank: 0, pad: 1 }
    expect(applyDrop(samplePage(), { kind: 'pad', at }, { kind: 'pad', at })).toBeNull()
    expect(
      applyDrop(samplePage(), { kind: 'pad', at }, { kind: 'pad', at: { ...at, pad: 2 } }),
    ).toBeNull()
  })

  it('moves up within a bank, landing before the target', () => {
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 0, column: 0, bank: 0, pad: 2 } }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 0 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['C', 'A', 'B'])
  })

  it('moves between banks in the same column', () => {
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 0, column: 1, bank: 0, pad: 0 } }, {
      kind: 'pad',
      at: { row: 0, column: 1, bank: 1, pad: 0 },
    })!
    expect(padNames(next, 0, 1, 0)).toEqual([])
    expect(padNames(next, 0, 1, 1)).toEqual(['D', 'E'])
  })

  it('moves between rows', () => {
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 1, column: 0, bank: 0, pad: 0 } }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 0 },
    })!
    expect(padNames(next, 0, 0, 0)).toEqual(['F', 'A', 'B', 'C'])
    expect(padNames(next, 1, 0, 0)).toEqual([])
  })

  it('answers null when it would land where it already is', () => {
    const at = { row: 0, column: 0, bank: 0, pad: 1 }
    expect(applyDrop(samplePage(), { kind: 'pad', at }, { kind: 'pad', at })).toBeNull()
  })

  it('keeps a bank emptied of its last pad', () => {
    // The anti-symmetry rule: an empty column goes, an empty bank stays, or crossing off the last
    // pad would delete the bank the operator just made.
    const next = applyDrop(samplePage(), { kind: 'pad', at: { row: 0, column: 1, bank: 0, pad: 0 } }, {
      kind: 'pad',
      at: { row: 0, column: 0, bank: 0, pad: 0 },
    })!
    expect(bankNames(next)).toEqual([[['Movement'], ['Colour', 'Beam']], [['Cues']]])
    expect(padNames(next, 0, 1, 0)).toEqual([])
  })
})

// ─── Dropping a bank ────────────────────────────────────────────────────

describe('dropping a bank', () => {
  it('stacks under another bank in its column', () => {
    const next = applyDrop(samplePage(), { kind: 'bank', at: { row: 0, column: 0, bank: 0 } }, {
      kind: 'bank-under',
      at: { row: 0, column: 1, bank: 0 },
    })!
    // The source column emptied, so it went, and the row is now one column wide.
    expect(bankNames(next)).toEqual([[['Colour', 'Movement', 'Beam']], [['Cues']]])
  })

  it('opens a new column at the gutter it was dropped in', () => {
    const next = applyDrop(samplePage(), { kind: 'bank', at: { row: 0, column: 1, bank: 1 } }, {
      kind: 'new-column',
      row: 0,
      column: 0,
    })!
    expect(bankNames(next)).toEqual([[['Beam'], ['Movement'], ['Colour']], [['Cues']]])
    expect(next.rows[0].columns[0].width).toBe(3)
  })

  it('starts a row below the page', () => {
    const next = applyDrop(samplePage(), { kind: 'bank', at: { row: 0, column: 1, bank: 1 } }, {
      kind: 'new-row',
    })!
    expect(bankNames(next)).toEqual([[['Movement'], ['Colour']], [['Cues']], [['Beam']]])
    expect(next.rows[2].columns[0].width).toBe(12)
  })

  it('takes the column with it when it was the last bank, and the row with the column', () => {
    const next = applyDrop(samplePage(), { kind: 'bank', at: { row: 1, column: 0, bank: 0 } }, {
      kind: 'bank-under',
      at: { row: 0, column: 0, bank: 0 },
    })!
    expect(bankNames(next)).toEqual([[['Movement', 'Cues'], ['Colour', 'Beam']]])
  })

  it('finds its anchor again after a lift that emptied a column before it', () => {
    // Row 0 column 0 is lifted away entirely; the anchor lives in what was column 1.
    const next = applyDrop(samplePage(), { kind: 'bank', at: { row: 0, column: 0, bank: 0 } }, {
      kind: 'bank-under',
      at: { row: 0, column: 1, bank: 1 },
    })!
    expect(bankNames(next)).toEqual([[['Colour', 'Beam', 'Movement']], [['Cues']]])
  })

  it('will not land on a pad', () => {
    expect(
      applyDrop(samplePage(), { kind: 'bank', at: { row: 0, column: 0, bank: 0 } }, {
        kind: 'pad',
        at: { row: 1, column: 0, bank: 0, pad: 0 },
      }),
    ).toBeNull()
  })

  it('answers null when the gutter beside its own column would leave it where it is', () => {
    const page = samplePage()
    expect(
      applyDrop(page, { kind: 'bank', at: { row: 1, column: 0, bank: 0 } }, {
        kind: 'new-column',
        row: 1,
        column: 0,
      }),
    ).toBeNull()
  })
})

// ─── Structure ──────────────────────────────────────────────────────────

describe('the document rules', () => {
  it('prunes an empty column and the row it leaves empty, and is idempotent', () => {
    const page = samplePage()
    const stripped = removeBank(page, { row: 1, column: 0, bank: 0 })
    expect(stripped.rows).toHaveLength(1)
    expect(normalisePage(stripped)).toEqual(stripped)
  })

  it('removes a pad without removing its bank', () => {
    const next = removePad(samplePage(), { row: 1, column: 0, bank: 0, pad: 0 })
    expect(bankNames(next)).toEqual([[['Movement'], ['Colour', 'Beam']], [['Cues']]])
  })

  it('edits a bank in place and leaves the rest alone', () => {
    const next = setBank(samplePage(), { row: 0, column: 1, bank: 0 }, { name: 'Key', solo: true, flow: 'COLUMN' })
    const edited = next.rows[0].columns[1].banks[0]
    expect([edited.name, edited.solo, edited.flow]).toEqual(['Key', true, 'COLUMN'])
    expect(next.rows[0].columns[1].banks[1].name).toBe('Beam')
  })

  it('sets a width on the column, not the bank', () => {
    const next = setColumnWidth(samplePage(), 0, 1, 9)
    expect(next.rows[0].columns[1].width).toBe(9)
  })

  it('duplicates a bank below itself with new pads of the same records', () => {
    const next = duplicateBank(samplePage(), { row: 0, column: 1, bank: 0 })
    expect(bankNames(next)).toEqual([[['Movement'], ['Colour', 'Colour', 'Beam']], [['Cues']]])
    const copied = toLayoutRequest(next).rows[0].columns[1].banks[1]
    expect(copied.bankId).toBeUndefined()
    expect(copied.pads[0].padId).toBeUndefined()
    expect(copied.pads[0].templateId).toBe(next.rows[0].columns[1].banks[0].pads[0].template!.id)
  })

  it('mints documents the server would accept for + Row and + Bank', () => {
    const withRow = toLayoutRequest(addRow(samplePage()))
    const row = withRow.rows[2]
    expect(row.columns).toHaveLength(1)
    expect(row.columns[0].width).toBe(12)
    expect(row.columns[0].banks).toHaveLength(1)
    // The server refuses a blank name, which is how neither button ever succeeded: the assertion
    // that was missing here.
    expect(row.columns[0].banks[0].name).toBe('Bank 1')

    const withBank = toLayoutRequest(addBankColumn(samplePage(), 1))
    expect(withBank.rows[1].columns).toHaveLength(2)
    expect(withBank.rows[1].columns[1].banks[0].pads).toEqual([])
    expect(withBank.rows[1].columns[1].banks[0].name).toBe('Bank 1')
  })

  it('names each new bank past the ones the page already has', () => {
    const twice = addBankColumn(addBankColumn(samplePage(), 0), 0)
    expect(twice.rows[0].columns.map((c) => c.banks[0].name)).toEqual([
      'Movement',
      'Colour',
      'Bank 1',
      'Bank 2',
    ])
    // The smallest free number, not the count: a deleted bank leaves its name reusable.
    const renamed = setBank(twice, { row: 0, column: 2, bank: 0 }, { name: 'Bank 3' })
    expect(nextBankName(renamed)).toBe('Bank 1')
  })

  it('carries ids where it has them, omits them where it does not, and never leaks uuid or localKey', () => {
    const page = applyDrop(samplePage(), { kind: 'palette', record: { kind: 'CUE', cue: { id: 5 } as never } }, {
      kind: 'pad',
      at: { row: 1, column: 0, bank: 0, pad: 0 },
    })!
    const serialised = JSON.stringify(toLayoutRequest(page))
    expect(serialised).not.toContain('uuid')
    expect(serialised).not.toContain('localKey')
    expect(toLayoutRequest(page).rows[1].columns[0].banks[0].pads[0]).toEqual({ cueId: 5 })
    expect(toLayoutRequest(page).rows[0].columns[0].columnId).toBeDefined()
  })

  it('names every record with a pad on the page', () => {
    const page = samplePage()
    const ids = [...recordsOnPage(page)]
    expect(ids).toHaveLength(6)
    expect(ids.every((key) => key.startsWith('template:'))).toBe(true)
  })
})

describe('the first-open generator', () => {
  const templates = [
    { id: 1, name: 'Amber', family: 'COLOUR' },
    { id: 2, name: 'Downstage', family: 'POSITION' },
    { id: 3, name: 'Half Up', family: 'INTENSITY' },
  ] as TemplateSummary[]
  const looks = [
    { id: 9, name: 'Ballyhoo', hasDeferredEffects: true },
    { id: 10, name: 'Verse Base', hasDeferredEffects: false },
  ] as LookSummary[]

  it('builds a family bank per family, the buskable Looks and an empty Cues bank', () => {
    const layout = libraryStarterLayout(templates, looks)
    expect(layout.rows).toHaveLength(2)
    const families = layout.rows[0].columns.map((c) => c.banks[0].name)
    expect(families).toEqual(['Colour', 'Position', 'Beam', 'Intensity'])
    expect(layout.rows[0].columns[0].banks[0].pads).toEqual([{ templateId: 1 }])
    // Beam has no templates and still gets its bank — that is where the operator puts the first one.
    expect(layout.rows[0].columns[2].banks[0].pads).toEqual([])
    expect(layout.rows[1].columns[0].banks[0].pads).toEqual([{ lookId: 9 }])
    expect(layout.rows[1].columns[1].banks[0]).toMatchObject({ name: 'Cues', pads: [], flow: 'COLUMN' })
  })

  it('sets nothing solo', () => {
    const layout = libraryStarterLayout(templates, looks)
    const banks = layout.rows.flatMap((r) => r.columns.flatMap((c) => c.banks))
    expect(banks.every((b) => !b.solo)).toBe(true)
  })
})

// ─── The hand's Undo for a bank place ───────────────────────────────────

/**
 * `POST /busk/banks/{id}/pads` appends and answers the whole page, so the pad the hand's Undo has
 * to take back off is **the last one of that bank in the page the append returned**. There is no
 * remove-pad route; the inverse goes through the layout PUT like every other edit.
 */
describe('lastPadOfBank', () => {
  it('finds the appended pad’s address, whatever row and column the bank sits in', () => {
    const page = samplePage()
    const colourBankId = page.rows[0].columns[1].banks[0].id!
    expect(lastPadOfBank(page, colourBankId)).toEqual({ row: 0, column: 1, bank: 0, pad: 0 })

    const cuesBankId = page.rows[1].columns[0].banks[0].id!
    expect(lastPadOfBank(page, cuesBankId)).toEqual({ row: 1, column: 0, bank: 0, pad: 0 })
  })

  it('names the LAST pad, which is where an append puts one', () => {
    const page = samplePage()
    const movementId = page.rows[0].columns[0].banks[0].id!
    const at = lastPadOfBank(page, movementId)!
    expect(at.pad).toBe(2)
    expect(padNames(removePad(page, at), 0, 0, 0)).toEqual(['A', 'B'])
  })

  it('answers null for a bank id that is not on the page, and for an empty bank', () => {
    expect(lastPadOfBank(samplePage(), 9999)).toBeNull()
    const emptied = removePad(samplePage(), { row: 0, column: 1, bank: 0, pad: 0 })
    // `normalisePage` keeps an empty bank, so it is still there to be asked about.
    const colourBank = emptied.rows[0].columns[1].banks[0]
    expect(colourBank.pads).toHaveLength(0)
    expect(lastPadOfBank(emptied, colourBank.id!)).toBeNull()
  })
})

describe('allBanks', () => {
  it('walks every bank on the page, in row then column then bank order', () => {
    expect(allBanks(samplePage()).map((b) => b.name)).toEqual([
      'Movement',
      'Colour',
      'Beam',
      'Cues',
    ])
  })

  it('includes a bank with no pads — which is exactly what a place target usually is', () => {
    const emptied = removePad(samplePage(), { row: 0, column: 1, bank: 0, pad: 0 })
    expect(allBanks(emptied).map((b) => b.name)).toContain('Colour')
  })
})
