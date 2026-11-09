// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/store/surfaces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/surfaces')>()
  return {
    ...actual,
    useWriteSurfaceSlotMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) })), {}],
    useClearSurfaceSlotMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve() })), {}],
  }
})
vi.mock('./useBindingChoices', async () => {
  const { EMPTY_RECORD_OPTIONS } = await import('./recordOptions')
  return {
    useBindingChoices: () => ({
      subjects: { groups: [], fixtures: [], records: EMPTY_RECORD_OPTIONS },
      attributesFor: () => [],
    }),
  }
})

import { SurfaceSheet, planFill, rowTakesSubject, sheetRows } from './SurfaceSheet'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME } from './surfaceFixtures'

/** Editor C: the mapping sheet over the map (surface-setup plan D12, §4). */

afterEach(cleanup)

const parts = MAP_FRAME.layers['layer-a']

describe('the rows', () => {
  it('groups every control part by region, one row per part', () => {
    const rows = sheetRows(FIXTURE_PROFILE, parts, CATALOGUE, false)
    const dividers = rows.filter((r) => r.divider).map((r) => r.divider)
    expect(dividers).toEqual(['Strips', 'Right'])
    expect(rows.filter((r) => r.controlId === 'enc-9').map((r) => r.part)).toEqual(['MAIN', 'PUSH'])
    expect(rows.some((r) => r.controlId.startsWith('strip-'))).toBe(false)
  })

  it('brings the dead to the top when asked', () => {
    const rows = sheetRows(FIXTURE_PROFILE, parts, CATALOGUE, true)
    expect(rows[0].divider).toBe('Dead')
    expect(rows[1].controlId).toBe('btn-4')
    expect(rows.filter((r) => r.controlId === 'btn-4')).toHaveLength(1)
  })
})

describe('Fill in order', () => {
  it('lays the list down one to a row, skipping a row that cannot take it and naming why', () => {
    const rows = sheetRows(FIXTURE_PROFILE, parts, CATALOGUE, false).filter((r) => !r.divider)
    const batch = ['btn-3:MAIN', 'enc-9:MAIN', 'enc-9:PUSH', 'btn-5:MAIN'].map((id) => rows.find((r) => r.id === id)!)
    const { writes, skipped } = planFill(batch, { key: 'GROUP', values: ['group:front', 'group:back'] })
    // The selection level and the locate push take groups; the GO takes a stack; the unbound button takes nothing.
    expect(writes.map((w) => [w.row.id, w.on])).toEqual([
      ['enc-9:MAIN', 'group:front'],
      ['enc-9:PUSH', 'group:back'],
    ])
    expect(skipped).toEqual(['Button 3 (GO) can’t take that', 'Button 5 does nothing yet'])
  })

  it('skips a control its job derives, whose subject is the job\'s item', () => {
    const rows = sheetRows(FIXTURE_PROFILE, parts, CATALOGUE, false).filter((r) => !r.divider)
    const fader = rows.find((r) => r.id === 'fader-1:MAIN')!
    expect(fader.resolved.from).toBe('JOB')
    expect(rowTakesSubject(fader, 'group:front')).toBe(false)
    expect(planFill([fader], { key: 'GROUP', values: ['group:front'] })).toEqual({ writes: [], skipped: ['Fader 1 follows its job'] })
  })

  it('fills the selection into every row that takes it', () => {
    const rows = sheetRows(FIXTURE_PROFILE, parts, CATALOGUE, false).filter((r) => !r.divider)
    const batch = ['enc-9:MAIN', 'enc-9:PUSH'].map((id) => rows.find((r) => r.id === id)!)
    expect(planFill(batch, { key: 'SELECTION', values: ['selection'] }).writes).toHaveLength(2)
  })
})

describe('SurfaceSheet', () => {
  it('draws each part\'s binding, where it writes and what it shows, from the map', () => {
    render(<SurfaceSheet projectId={1} profile={FIXTURE_PROFILE} frame={MAP_FRAME} catalogue={CATALOGUE} viewedLayer="layer-a" connected />)
    const row = document.querySelector('[data-row-id="btn-3:MAIN"]') as HTMLElement
    expect(row.textContent).toContain('GO')
    expect(row.textContent).toContain('Act 1')
    expect(row.textContent).toContain('Playback')
    expect(row.textContent).toContain('Lit while Act 1 is running.')
    const dead = document.querySelector('[data-row-id="btn-4:MAIN"]') as HTMLElement
    expect(dead.textContent).toContain('missing cue stack')
    const fromJob = document.querySelector('[data-row-id="fader-1:MAIN"]') as HTMLElement
    expect(fromJob.textContent).toContain('Job · Both')
    // Its On is a read-out of the job's item, not a cell of its own.
    expect(fromJob.textContent).toContain('Front wash')
    expect(fromJob.querySelector('[data-cell="on"]')).toBeNull()
  })

  it('sorts the dead first from its toolbar', () => {
    render(<SurfaceSheet projectId={1} profile={FIXTURE_PROFILE} frame={MAP_FRAME} catalogue={CATALOGUE} viewedLayer="layer-a" connected />)
    fireEvent.click(screen.getByRole('button', { name: 'Dead first' }))
    expect(screen.getByText('Dead')).toBeInTheDocument()
  })
})
