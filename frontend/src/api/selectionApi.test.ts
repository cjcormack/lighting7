import { describe, expect, it } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createSelectionWsApi, type DeskSelectionSnapshot } from './selectionApi'

/**
 * The `selection.*` wire as this side speaks it (multi-screen plan §3.2, lighting7 af3575a) —
 * pinned by frame, not by argument: `store/selection.test.tsx` spies on what the store hands this
 * module, and a renamed key here would have left that suite green while the desk ignored it.
 */
describe('createSelectionWsApi', () => {
  it('sends the whole fact on a set — targets and families, and no name', () => {
    const { conn, sent } = fakeWsConnection()
    createSelectionWsApi(conn).set([{ type: 'fixture', key: 'par-1' }], ['COLOUR'])
    expect(sent).toEqual([
      {
        type: 'selection.set',
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
      },
    ])
  })

  it('omits families for no mask, and for all four — the desk’s own spelling', () => {
    // `parseMaskGroupsLenient` folds empty and complete to null; sending it folded is what lets
    // the echo compare equal to what was sent.
    const { conn, sent } = fakeWsConnection()
    const api = createSelectionWsApi(conn)
    api.set([], null)
    api.set([], ['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])
    expect(sent.map((frame) => 'families' in frame)).toEqual([false, false])
  })

  it('sends a bare toggle and a bare clear — the announce names the window, not the write', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createSelectionWsApi(conn)
    api.toggle({ type: 'group', key: 'Front wash' })
    api.clear()
    expect(sent).toEqual([
      { type: 'selection.toggle', target: { type: 'group', key: 'Front wash' } },
      { type: 'selection.clear' },
    ])
  })

  it('sends a subselect as the mode name and nothing else — the desk keeps the mask and stamps the mover', () => {
    const { conn, sent } = fakeWsConnection()
    createSelectionWsApi(conn).subselect('ODD')
    expect(sent).toEqual([{ type: 'selection.subselect', mode: 'ODD' }])
  })

  it('never puts a source on the wire — the desk stamps it', () => {
    const { conn, sent } = fakeWsConnection()
    createSelectionWsApi(conn).set([{ type: 'fixture', key: 'par-1' }], null)
    expect(sent[0]).not.toHaveProperty('source')
    expect(sent[0]).not.toHaveProperty('sourceName')
  })

  it('decodes a state frame with the two fields absent as every attribute and nobody', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSelectionWsApi(conn)
    const seen: DeskSelectionSnapshot[] = []
    api.subscribe((snapshot) => seen.push(snapshot))
    frame({ type: 'selection.state', targets: [{ type: 'fixture', key: 'par-1' }] })
    frame({
      type: 'selection.state',
      targets: [{ type: 'fixture', key: 'par-1' }],
      families: ['COLOUR'],
      source: { kind: 'surface', name: 'Control surface' },
    })
    expect(seen).toEqual([
      { targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null },
      {
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
        source: { kind: 'surface', name: 'Control surface' },
      },
    ])
    // The untouched targets keep their identity across the second frame.
    expect(seen[1]!.targets).toBe(seen[0]!.targets)
    expect(api.getState()).toBe(seen[1])
  })
})
