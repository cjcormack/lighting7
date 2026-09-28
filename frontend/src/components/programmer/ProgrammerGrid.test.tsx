// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ProgrammerScope } from './ProgrammerScope'

/**
 * Row B's toolbar, drawn with the container stubbed out. `FixturesListContainer` owns a filter, a
 * row marquee and a cell marquee over three Redux scopes, and none of that is what these
 * assertions are about: they pin which of row B's controls exist per scope, and the classes the
 * chrome system is written in. The stand-in calls `renderToolbar` with inert parts, exactly as
 * the real one would, and records the placeholder it was handed.
 */
const state = { scope: { kind: 'local' } as ProgrammerScope }
const container = { filterPlaceholder: undefined as string | undefined }

vi.mock('@/components/fixtures-list/FixturesListContainer', () => ({
  FixturesListContainer: ({
    renderToolbar,
    filterPlaceholder,
  }: {
    renderToolbar: (parts: Record<string, unknown>) => React.ReactNode
    filterPlaceholder?: string
  }) => {
    container.filterPlaceholder = filterPlaceholder
    return (
      <div>
        {renderToolbar({
          filter: <input data-testid="filter" placeholder={filterPlaceholder} />,
          lit: <button type="button">Lit</button>,
          columns: <button type="button">Columns</button>,
          selection: null,
          cells: [],
          cellEntryKey: false,
          cellClearKey: false,
          templateTargets: [],
          targetFamilies: [],
          targetEmitters: [],
          marqueeDragging: false,
        })}
      </div>
    )
  },
}))
vi.mock('./ProgrammerScopeBand', () => ({ ProgrammerScopeBand: () => <div data-testid="band" /> }))
vi.mock('./SelectionBar', () => ({ SelectionBar: () => null }))
vi.mock('./LayerRowNotices', () => ({ LayerRowNotices: () => null }))
vi.mock('./LookRowStore', () => ({ useLookRowStore: () => null }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => state.scope }))

const { ProgrammerGrid } = await import('./ProgrammerGrid')
const { FIXTURE_FILTER_PLACEHOLDER_SHORT } = await import('@/lib/fixtureFilterCopy')
const { DEFAULT_COLUMN_VISIBILITY } = await import('@/components/fixtures-list/columns')

function draw() {
  return render(
    <ProgrammerGrid
      projectId={1}
      grouped={false}
      onGroupedChange={() => {}}
      columnVisibility={DEFAULT_COLUMN_VISIBILITY}
      onColumnVisibilityChange={() => {}}
    />,
  )
}

afterEach(() => {
  cleanup()
  state.scope = { kind: 'local' }
  container.filterPlaceholder = undefined
})

describe('ProgrammerGrid — row B', () => {
  it('draws the key button outside layer scope', () => {
    draw()
    expect(screen.getByRole('button', { name: 'Key to the cell colours' })).toBeTruthy()
  })

  it('draws no key button in layer scope — ownership tints are off there', () => {
    // The key explains the ownership tints and layer scope switches them off, so there is nothing
    // to explain; on a phone it is also the control that pushed the row past its right edge.
    state.scope = { kind: 'layer', layerId: 3 }
    draw()
    expect(screen.queryByRole('button', { name: 'Key to the cell colours' })).toBeNull()
    // The rest of the row is untouched by the scope.
    expect(screen.getByRole('button', { name: 'Filter fixtures' })).toBeTruthy()
    expect(screen.getByRole('button', { name: /Groups/ })).toBeTruthy()
  })

  it('hands the container the one-word placeholder, since the row keeps its field down to 360', () => {
    draw()
    expect(container.filterPlaceholder).toBe(FIXTURE_FILTER_PLACEHOLDER_SHORT)
    expect(FIXTURE_FILTER_PLACEHOLDER_SHORT).toBe('Filter…')
  })

  it('keeps the field from 360 of row B and lets it take the slack before the spacer', () => {
    draw()
    const field = screen.getByTestId('filter').parentElement!
    expect(field.className).toContain('@[360px]:flex')
    expect(field.className).toContain('flex-[999_1_0%]')
    expect(field.className).toContain('max-w-[340px]')
    expect(field.className).not.toMatch(/(^|\s)flex-1(\s|$)/)
    const icon = screen.getByRole('button', { name: 'Filter fixtures' })
    expect(icon.className).toContain('@[360px]:hidden')
    expect(icon.className).not.toContain('h-7')
  })

  it('is a 40px row with every control at the 32px tier', () => {
    const { container: root } = draw()
    const row = root.querySelector('.border-b.px-3')!
    expect(row.className).toContain('h-10')
    for (const name of [/Groups/, 'Filter fixtures', 'Key to the cell colours']) {
      expect(screen.getByRole('button', { name }).className).not.toContain('h-7')
    }
  })
})
