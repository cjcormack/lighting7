// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { BuskBank, BuskPad, BuskPage } from '@/api/buskApi'
import {
  buskAddBody,
  buskAddChoices,
  buskAddTargets,
  defaultBuskAddTarget,
  rememberBuskAddTarget,
  type BuskAddRecord,
} from './buskAdd'

/**
 * What *Add to busk page* offers, and what it remembers.
 *
 * The two rules with teeth are both about a menu never showing an item that cannot work or cannot
 * be told from its neighbour: a bank with no server id is unusable, and a page may legally hold two
 * banks with the same name (or none at all).
 */

function pad(kind: BuskPad['kind'], id: number): BuskPad {
  const summary = { id } as never
  if (kind === 'TEMPLATE') return { kind, template: summary }
  if (kind === 'LOOK') return { kind, look: summary }
  return { kind, cue: summary }
}

function bank(over: Partial<BuskBank> = {}): BuskBank {
  return { id: 1, name: 'Keys', solo: false, flow: 'WRAP', pads: [], ...over }
}

function page(id: number, name: string, banks: BuskBank[]): BuskPage {
  return { id, uuid: `u${id}`, name, sortOrder: id, rows: [{ columns: [{ width: 12, banks }] }] }
}

const amber: BuskAddRecord = { kind: 'TEMPLATE', id: 7, name: 'Amber Key' }

beforeEach(() => localStorage.clear())
afterEach(() => localStorage.clear())

describe('what the menu offers', () => {
  it('skips a bank the server has not minted an id for', () => {
    // A bank can exist client-side mid-gesture, because the busk view's draft uses these same
    // types. It cannot be appended to *by id*, so offering it would offer a dead item.
    const targets = buskAddTargets(
      [page(1, 'Act 1', [bank({ id: 1, name: 'Keys' }), bank({ id: undefined, name: 'Draft' })])],
      amber,
    )
    expect(targets[0].banks.map((b) => b.label)).toEqual(['Keys'])
  })

  it('names a nameless bank by its place, and disambiguates repeats', () => {
    // A bank's identity is its place, not its name, so the repeat is a legal page rather than a
    // corrupt one. The blank is not one any route mints — `newBank` requires a name and the server
    // refuses a blank — so this pins the defensive arm, for an import or a hand-edited database.
    const targets = buskAddTargets(
      [page(1, 'Act 1', [bank({ id: 1, name: '' }), bank({ id: 2, name: 'Wash' }), bank({ id: 3, name: 'Wash' })])],
      amber,
    )
    expect(targets[0].banks.map((b) => b.label)).toEqual(['Bank 1', 'Wash', 'Wash · 2'])
  })

  it('reports where the record already is, and counts pads', () => {
    const targets = buskAddTargets(
      [
        page(1, 'Act 1', [
          bank({ id: 1, name: 'Keys', pads: [pad('TEMPLATE', 7), pad('LOOK', 7)] }),
          bank({ id: 2, name: 'Wash', pads: [pad('TEMPLATE', 9)] }),
        ]),
      ],
      amber,
    )
    // A LOOK with id 7 is not the template with id 7 — the ids are per kind and the key carries it.
    expect(targets[0].banks.map((b) => [b.label, b.holdsRecord, b.padCount])).toEqual([
      ['Keys', true, 2],
      ['Wash', false, 1],
    ])
  })

  it('offers a page with no banks as a page with no banks, not as no page', () => {
    const targets = buskAddTargets([page(1, 'Empty', [])], amber)
    expect(targets).toEqual([{ pageId: 1, pageName: 'Empty', banks: [] }])
    expect(buskAddChoices(targets)).toEqual([])
  })
})

describe('the remembered target', () => {
  const pages = [
    page(1, 'Act 1', [bank({ id: 1, name: 'Keys' })]),
    page(2, 'Act 2', [bank({ id: 2, name: 'Wash' })]),
  ]
  const targets = () => buskAddTargets(pages, amber)

  it('falls back to the first bank when nothing is remembered', () => {
    expect(defaultBuskAddTarget(3, targets())?.bankId).toBe(1)
  })

  it('offers the bank a placement last landed in', () => {
    rememberBuskAddTarget(3, 2)
    expect(defaultBuskAddTarget(3, targets())).toMatchObject({ bankId: 2, pageName: 'Act 2' })
  })

  it('ignores a bank remembered for another project', () => {
    // Ids are per project, so a remembered id from another show names something arbitrary here.
    rememberBuskAddTarget(99, 2)
    expect(defaultBuskAddTarget(3, targets())?.bankId).toBe(1)
  })

  it('falls through silently when the remembered bank has been deleted', () => {
    rememberBuskAddTarget(3, 404)
    expect(defaultBuskAddTarget(3, targets())?.bankId).toBe(1)
  })

  it('offers nothing at all when there is nowhere to put a pad', () => {
    expect(defaultBuskAddTarget(3, buskAddTargets([page(1, 'Empty', [])], amber))).toBeNull()
  })
})

describe('the request body', () => {
  it('sets exactly one id, matching the kind', () => {
    expect(buskAddBody({ kind: 'TEMPLATE', id: 1, name: 'a' })).toEqual({ templateId: 1 })
    expect(buskAddBody({ kind: 'LOOK', id: 2, name: 'b' })).toEqual({ lookId: 2 })
    expect(buskAddBody({ kind: 'CUE', id: 3, name: 'c' })).toEqual({ cueId: 3 })
  })
})
