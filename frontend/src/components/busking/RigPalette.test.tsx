// @vitest-environment jsdom
import { DndContext } from '@dnd-kit/core'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupSummary } from '@/api/groupsApi'
import type { Fixture } from '@/store/fixtures'

/**
 * The palette's Rig tab (busk-further plan D4, `Rig.dc.html`): every group and fixture once, a
 * search, a kind filter, *on rig* where a tile exists, a hidden patch dimmed rather than gone, and
 * a multi-head fixture's cells expanding under it as rows of their own.
 */

let groups: GroupSummary[] = []
let fixtures: Fixture[] = []
let patches: { id: number; key: string; displayName: string; stageHidden: boolean; infrastructure?: boolean; groups: { id: number; name: string }[] }[] = []

vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: groups }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: fixtures }),
  useFixtureTypeListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/patches', () => ({
  usePatchListQuery: () => ({ data: patches }),
  // The real hook's rule, over the test's own list — the Rig tab offers no infrastructure.
  useVisiblePatchListQuery: () => ({ data: patches.filter((p) => !p.infrastructure) }),
}))

import { RigPalette } from './RigPalette'

function draw(onRig = new Set<string>()) {
  return render(
    <DndContext>
      <RigPalette projectId={1} onRigKeys={onRig} />
    </DndContext>,
  )
}

const rowNames = () => Array.from(document.querySelectorAll('.min-h-10 .truncate:not(.text-\\[11px\\])')).map((el) => el.textContent)

beforeEach(() => {
  groups = [{ name: 'Front wash', memberCount: 6, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'NONE', compatibleLookIds: [] }]
  fixtures = [
    { key: 'bar-1', name: 'Bar L', typeKey: 'bar', elements: [0, 1].map((i) => ({ index: i, key: `bar-1.pixel-${i}`, displayName: `Cell ${i + 1}`, properties: [] })), groups: [] } as unknown as Fixture,
    { key: 'smoke', name: 'Smoke', typeKey: 'smoke', groups: [] } as unknown as Fixture,
  ]
  patches = [
    { id: 11, key: 'bar-1', displayName: 'Bar L', stageHidden: false, groups: [{ id: 1, name: 'Front wash' }] },
    { id: 12, key: 'smoke', displayName: 'Smoke', stageHidden: true, groups: [] },
  ]
})

afterEach(cleanup)

describe('the Rig tab', () => {
  it('lists every group then every fixture once, sectioned and counted', () => {
    draw()
    expect(screen.getByText('Groups · 1')).toBeInTheDocument()
    expect(screen.getByText('Fixtures · 2')).toBeInTheDocument()
    expect(rowNames()).toEqual(['Front wash', 'Bar L', 'Smoke'])
    expect(screen.getByText('3 not on the rig · a cell drags out as its own tile.')).toBeInTheDocument()
  })

  it('says `on rig` for a record with a tile, and counts the rest', () => {
    draw(new Set(['group:Front wash']))
    expect(screen.getAllByText('on rig')).toHaveLength(1)
    expect(screen.getByText(/^2 not on the rig/)).toBeInTheDocument()
  })

  it('offers no infrastructure patch at all', () => {
    patches = [
      { id: 11, key: 'bar-1', displayName: 'Bar L', stageHidden: false, groups: [] },
      { id: 13, key: 'hazer-power', displayName: 'Hazer power', stageHidden: false, infrastructure: true, groups: [] },
    ]
    draw()
    expect(rowNames()).toEqual(['Front wash', 'Bar L'])
    expect(screen.queryByText('Hazer power')).not.toBeInTheDocument()
  })

  it('keeps a group placeable when its only patched member is infrastructure', () => {
    // A group is explicit and still drives its members, so it is placed from the whole patch's ids
    // even though the member itself is never offered as a row.
    patches = [{ id: 13, key: 'hazer-power', displayName: 'Hazer power', stageHidden: false, infrastructure: true, groups: [{ id: 1, name: 'Front wash' }] }]
    draw()
    expect(screen.queryByText(/no patched member/)).not.toBeInTheDocument()
    expect(screen.queryByText('Hazer power')).not.toBeInTheDocument()
  })

  it('dims a stageHidden patch rather than hiding it', () => {
    draw()
    const smoke = screen.getByText('Smoke').closest('.min-h-10')!
    expect(smoke.className).toContain('opacity-50')
    expect(screen.getByText(/hidden · stageHidden$/)).toBeInTheDocument()
  })

  it('expands a multi-head fixture’s cells under it, each a draggable row of its own', () => {
    draw(new Set(['cell:bar-1.pixel-1']))
    expect(screen.queryByText('Cell 1')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show the cells of Bar L' }))
    expect(screen.getByText('Cell 1')).toBeInTheDocument()
    expect(screen.getByText('bar-1.pixel-0')).toBeInTheDocument()
    expect(screen.getByLabelText('Place Cell 2')).toBeInTheDocument()
    // The cell on the rig says so; its parent does not.
    expect(screen.getAllByText('on rig')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Hide the cells of Bar L' }))
    expect(screen.queryByText('Cell 1')).not.toBeInTheDocument()
  })

  it('offers a group with no patched member as a row that says why and drags nowhere', () => {
    groups = [...groups, { name: 'Empty group', memberCount: 0, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'NONE', compatibleLookIds: [] }]
    draw()
    expect(screen.getByText('no patched member · cannot be placed until it has one')).toBeInTheDocument()
    expect(screen.getByLabelText('Place Empty group')).toBeDisabled()
    expect(screen.getByLabelText('Place Front wash')).toBeEnabled()
    expect(screen.getByText(/^4 not on the rig/)).toBeInTheDocument()
  })

  it('filters by kind and by search', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Groups' }))
    expect(rowNames()).toEqual(['Front wash'])
    fireEvent.click(screen.getByRole('button', { name: 'All' }))
    fireEvent.change(screen.getByLabelText('Search targets'), { target: { value: 'smo' } })
    expect(rowNames()).toEqual(['Smoke'])
    fireEvent.change(screen.getByLabelText('Search targets'), { target: { value: 'zzz' } })
    expect(screen.getByText(/Nothing here matches/)).toBeInTheDocument()
  })
})
