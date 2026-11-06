import { describe, expect, it } from 'vitest'
import { cueRow, markerRow } from '@/test/cueEntries'
import { paneBlankText, paneContentOf } from './cuePaneContent'

const at = (entry: ReturnType<typeof cueRow> | ReturnType<typeof markerRow>) => ({ id: `entry:${entry.entryId}`, entry })

describe('paneContentOf', () => {
  it('is the one selected cue, a marker beside it aside', () => {
    const one = cueRow(2, { entryId: 102 })
    expect(paneContentOf([at(one)])).toEqual({ kind: 'cue', rowId: 'entry:102', cueId: 2, entryId: 102 })
    expect(paneContentOf([at(one), at(markerRow(103))])).toEqual({ kind: 'cue', rowId: 'entry:102', cueId: 2, entryId: 102 })
  })

  it('counts several cues, markers left out', () => {
    expect(paneContentOf([at(cueRow(1, { entryId: 101 })), at(markerRow(103)), at(cueRow(4, { entryId: 104 }))])).toEqual({ kind: 'several', count: 2 })
  })

  it('is nothing for no row, and a separator for markers alone', () => {
    expect(paneContentOf([])).toEqual({ kind: 'none' })
    expect(paneContentOf([at(markerRow(103))])).toEqual({ kind: 'markers', count: 1 })
  })
})

describe('paneBlankText', () => {
  it('says what the bar acts on in words', () => {
    expect(paneBlankText({ kind: 'several', count: 2 }).detail).toBe('The editor shows one cue at a time. The bar above acts on both.')
    expect(paneBlankText({ kind: 'several', count: 3 })).toEqual({
      title: '3 cues selected',
      detail: 'The editor shows one cue at a time. The bar above acts on all three.',
    })
    expect(paneBlankText({ kind: 'several', count: 14 }).detail).toBe('The editor shows one cue at a time. The bar above acts on all 14.')
  })

  it('names nothing selected and a separator', () => {
    expect(paneBlankText({ kind: 'none' })).toEqual({ title: 'No cue selected', detail: 'Select a cue to edit it here.' })
    expect(paneBlankText({ kind: 'markers', count: 1 })).toEqual({ title: 'A separator', detail: 'Rename it in its row.' })
  })
})
