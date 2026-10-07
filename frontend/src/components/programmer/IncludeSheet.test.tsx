// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IncludeResponse } from '@/store/programmerOps'

/**
 * The Include sheet's result line (scenery-programmer plan session 3): a cue or Look that only
 * moves scenery stages its rows into the programmer's scenery, and the line says so rather than
 * reading as nothing included.
 */
let result: Partial<IncludeResponse> | null = null

vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: [] }) }))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: [] }) }))
vi.mock('./useInclude', () => ({
  useInclude: () => ({ include: vi.fn(), isLoading: false, error: undefined, result, resetInclude: () => {} }),
}))

import { IncludeSheet } from './IncludeSheet'

afterEach(cleanup)

function line(): string {
  return screen.getByText(/^Included/).textContent ?? ''
}

describe('IncludeSheet result', () => {
  it('counts the scenery a cue brought with it', () => {
    result = { name: 'Q15', entriesWritten: 0, fxSpawned: 0, fxAlreadyRunning: 0, sceneryIncluded: 2 }
    render(<IncludeSheet open onOpenChange={() => {}} projectId={6} />)
    expect(line()).toBe('Included “Q15” — 0 values, 2 scenery changes.')
  })

  it('says nothing about scenery when there was none, or from a desk that does not send it', () => {
    result = { name: 'Q14', entriesWritten: 1, fxSpawned: 0, fxAlreadyRunning: 0 }
    render(<IncludeSheet open onOpenChange={() => {}} projectId={6} />)
    expect(line()).toBe('Included “Q14” — 1 value.')
  })
})
