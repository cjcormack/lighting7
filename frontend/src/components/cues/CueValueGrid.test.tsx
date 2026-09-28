// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CookedRow } from '@/api/cuesApi'
import type { Fixture } from '@/store/fixtures'
import { makePixelBar } from '@/test/fixtureFactories'

const state: { fixtures: Fixture[]; rows: CookedRow[] } = { fixtures: [], rows: [] }

vi.mock('@/store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtures')>()),
  useFixtureListQuery: () => ({ data: state.fixtures }),
}))
vi.mock('@/store/cues', () => ({
  useProjectCueCookedQuery: () => ({
    data: { cueId: 1, rows: state.rows },
    isSuccess: true,
    isError: false,
  }),
}))

import { CueValueGrid } from './CueValueGrid'

afterEach(cleanup)

describe('CueValueGrid', () => {
  it('shows a head the cue holds under its fixture, and no other head', () => {
    // A cell-target row: the cook keys it by the head's element key, which is no fixture's key.
    state.fixtures = [makePixelBar('bar', 4)]
    state.rows = [
      { targetType: 'fixture', targetKey: 'bar.pixel-2', propertyName: 'rgbColour', value: '#b40000' },
    ]

    render(<CueValueGrid projectId={1} cueId={1} />)

    expect(screen.queryByText(/asserts no values/)).toBeNull()
    expect(screen.getByText('bar Head 3')).toBeTruthy()
    expect(screen.queryByText('bar Head 1')).toBeNull()
    expect(screen.queryByText('bar Head 4')).toBeNull()
  })
})
