// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import type { ProgrammerScope } from './ProgrammerScope'
import type { LookSaveState } from './LookRowStore'

/**
 * The phone arm of row B's left end (`programmer-chrome-design`): below `@[600px]` the layer
 * pill is capped at 120 and carries `Unsaved` / `Saving…` as a dot, with the word kept `sr-only`
 * — and `Save failed` keeps its word at every width, because a failed write must never be a
 * casualty of width and a dot cannot say *failed*. jsdom lays nothing out, so what is pinned is
 * the classes each arm is written in.
 */
const state = {
  scope: { kind: 'layer', layerId: 7 } as ProgrammerScope,
  save: 'clean' as LookSaveState,
}
const LAYER: ProgrammerLayer = {
  layerId: 7,
  source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' },
  sortOrder: 1,
  enabled: true,
  targets: [],
  blendMode: 'OVERRIDE',
  amount: 1,
  stomp: false,
}

vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: [LAYER] }),
}))
vi.mock('./LookRowStore', () => ({ useLookSaveState: () => state.save }))
vi.mock('./FocusedTemplateLayer', () => ({ useFocusedTemplateLayer: () => null }))
vi.mock('./useLocalFamilyCounts', () => ({ useLocalValueCount: () => 0 }))
vi.mock('./ProgrammerScope', () => ({
  useProgrammerScope: () => state.scope,
  useProgrammerScopeActions: () => ({ setScope: () => {} }),
}))

const { ProgrammerScopeBand } = await import('./ProgrammerScopeBand')

afterEach(() => {
  cleanup()
  state.scope = { kind: 'layer', layerId: 7 }
  state.save = 'clean'
})

describe('ProgrammerScopeBand — the phone arm', () => {
  it('caps the layer pill at 120 below 600 and 220 above', () => {
    render(<ProgrammerScopeBand />)
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    expect(pill.className).toContain('max-w-[120px]')
    expect(pill.className).toContain('@[600px]:max-w-[220px]')
    expect(pill.className).toContain('relative')
  })

  it('shows Unsaved as a dot on the pill below 600, keeping the word for assistive tech', () => {
    state.save = 'dirty'
    render(<ProgrammerScopeBand />)
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    const dot = pill.querySelector('span[aria-hidden]')!
    expect(dot).not.toBeNull()
    expect(dot.className).toContain('@[600px]:hidden')
    const status = screen.getByRole('status')
    expect(status.textContent).toContain('Unsaved')
    expect(screen.getByText('Unsaved', { selector: '.sr-only' })).toBeTruthy()
    expect(screen.getByText('Unsaved', { selector: '[aria-hidden]' }).className).toContain(
      'hidden',
    )
  })

  it('does the same for Saving…', () => {
    state.save = 'saving'
    render(<ProgrammerScopeBand />)
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    expect(pill.querySelector('span[aria-hidden]')).not.toBeNull()
    expect(screen.getByText('Saving…', { selector: '.sr-only' })).toBeTruthy()
  })

  it('keeps the word for Save failed at every width, and draws no dot', () => {
    state.save = 'error'
    render(<ProgrammerScopeBand />)
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    expect(pill.querySelector('span[aria-hidden]')).toBeNull()
    const status = screen.getByRole('status')
    expect(status.className).toContain('text-destructive')
    expect(status.textContent).toBe('Save failed — the look is unchanged on the desk')
    expect(status.querySelector('.hidden')).toBeNull()
    expect(status.querySelector('.sr-only')).toBeNull()
  })

  it('takes the phone arm at every width when the row is folded', () => {
    // `compact` is the caller saying "the column is wide and the row is not" — the folded row
    // shares one 40px line with the whole of row A — so it wins over the query here for exactly
    // the reason it already wins over the two pills' `@[520px]`.
    state.save = 'dirty'
    render(<ProgrammerScopeBand compact />)
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    expect(pill.className).toContain('max-w-[120px]')
    expect(pill.className).not.toContain('@[600px]:max-w-[220px]')
    expect(pill.querySelector('span[aria-hidden]')!.className).not.toContain('@[600px]:hidden')
    expect(screen.getByText('Unsaved', { selector: '[aria-hidden]' }).className).not.toContain(
      '@[600px]:inline',
    )
    // Still announced: the dot is what a sighted operator sees, never what anyone is told.
    expect(screen.getByText('Unsaved', { selector: '.sr-only' })).toBeTruthy()
  })

  it('says nothing at all when the layer is clean', () => {
    render(<ProgrammerScopeBand />)
    expect(screen.queryByRole('status')).toBeNull()
    const pill = screen.getByRole('radio', { name: 'Show the focused layer' })
    expect(pill.querySelector('span[aria-hidden]')).toBeNull()
  })
})
