// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FixturePatch } from '@/api/patchApi'

const types = [
  { typeKey: 'mac', capabilities: ['dimmer', 'position'] },
  { typeKey: 'par', capabilities: ['dimmer'] },
]
vi.mock('@/store/fixtures', () => ({ useFixtureTypeListQuery: () => ({ data: types }) }))
const actions = { projectId: 4 as number | undefined, storeFromCurrent: vi.fn(), clearLocate: vi.fn(), busy: false }
vi.mock('@/hooks/useStoredLocateActions', () => ({ useStoredLocateActions: () => actions }))

const { LocateBox } = await import('./LocateBox')

/**
 * The patch sheet's **Locate** box (venue-visit plan D6): each unit's stored pan and tilt on the
 * centre-relative scale, and *Set from current* / *Clear* — each one call to the desk, on the current
 * project only. Drawn only where the type moves, or a locate is still stored.
 */
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  actions.projectId = 4
})

function patch(over: Partial<FixturePatch> = {}): FixturePatch {
  return { id: 9, key: 'mac-1', fixtureTypeKey: 'mac', owner: 'SHOW', ...over } as FixturePatch
}

describe('LocateBox', () => {
  it('shows each unit’s stored locate on the centre scale and acts on the patch’s key', () => {
    render(<LocateBox projectId={4} patch={patch({ locate: [{ pan: 140, tilt: 96 }, { head: 0, pan: 128, tilt: 128 }] })} />)
    expect(screen.getByText('Fixture')).toBeInTheDocument()
    expect(screen.getByText('Pan +12 · Tilt -32')).toBeInTheDocument()
    expect(screen.getByText('Head 1')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Set from current/ }))
    expect(actions.storeFromCurrent).toHaveBeenCalledWith([{ type: 'fixture', key: 'mac-1' }])
    fireEvent.click(screen.getByRole('button', { name: /Clear/ }))
    expect(actions.clearLocate).toHaveBeenCalledWith([{ type: 'fixture', key: 'mac-1' }])
  })

  it('reads the centre with nothing stored, and Clear has nothing to do', () => {
    render(<LocateBox projectId={4} patch={patch()} />)
    expect(screen.getByText(/middle of its travel/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Clear/ })).toBeDisabled()
  })

  it('is inert on a project that is not the current one, and says why', () => {
    actions.projectId = 2
    render(<LocateBox projectId={4} patch={patch({ locate: [{ pan: 1, tilt: 2 }] })} />)
    const set = screen.getByRole('button', { name: /Set from current/ })
    expect(set).toBeDisabled()
    expect(set).toHaveAttribute('title', expect.stringContaining('Open this project first'))
  })

  it('is not drawn for a type that does not move and holds no locate', () => {
    const { container } = render(<LocateBox projectId={4} patch={patch({ fixtureTypeKey: 'par' })} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('says a venue fixture’s locate is this show’s own', () => {
    render(<LocateBox projectId={4} patch={patch({ owner: 'VENUE', locate: [{ pan: 1, tilt: 2 }] })} />)
    expect(screen.getByText(/this show’s own Focus/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Clear/ })).toHaveAttribute('title', expect.stringContaining('venue’s stays'))
  })
})
