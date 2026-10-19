// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FixturePatch, PatchGroup } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'

const data = vi.hoisted(() => ({
  patches: [] as FixturePatch[],
  groups: [] as PatchGroup[],
  riggings: [] as RiggingDto[],
}))
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }))
vi.mock('../../ProjectSwitcher', () => ({ useViewedProject: () => ({ id: 1 }) }))
vi.mock('../../store/patches', () => ({
  useVisiblePatchListQuery: () => ({ data: data.patches, isLoading: false }),
  usePatchGroupListQuery: () => ({ data: data.groups }),
}))
vi.mock('../../store/riggings', () => ({ useRiggingListQuery: () => ({ data: data.riggings }) }))
vi.mock('../../hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtureByKey: new Map(), typeByKey: new Map() }) }))
vi.mock('../../hooks/useChannelSource', () => ({ StageChannelSourceProvider: ({ children }: { children: React.ReactNode }) => children }))
vi.mock('../fixtures/fixtureAppearance', () => ({
  FixtureAppearanceSource: ({ children }: { children: (a: { color: string; intensity: number }) => React.ReactNode }) =>
    children({ color: '#000', intensity: 0 }),
}))
vi.mock('../CollapsiblePanel', () => ({ CollapsiblePanel: ({ children }: { children: React.ReactNode }) => children }))
vi.mock('../../store/selection', () => ({
  useDeskSelection: () => [],
  setDeskSelection: vi.fn(),
  toggleDeskSelection: vi.fn(),
  clearDeskSelection: vi.fn(),
}))
vi.mock('../../lib/stageViewpoint', () => ({ setStageViewpoint: vi.fn() }))

import { PositionsPanel } from './PositionsPanel'

afterEach(cleanup)

function patch(id: number, key: string, displayName: string, colour: string | null): FixturePatch {
  return {
    id,
    uuid: `uuid-${key}`,
    key,
    displayName,
    fixtureTypeKey: 'generic-dimmer',
    startChannel: id,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 0,
    subnet: 0,
    sortOrder: id,
    groups: [{ id: 5, name: 'Front' }],
    stageX: id,
    stageY: 2,
    stageZ: 0,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    colour,
  } as FixturePatch
}

describe('the Positions panel’s chips (tables-colours plan D8, D9)', () => {
  it('mark a unit with its colour and a group chip with the group’s, beside the live swatch, and nothing for none or an unknown key', () => {
    data.patches = [patch(1, 'p1', 'Par 1', 'red'), patch(2, 'p2', 'Par 2', null), patch(3, 'p3', 'Par 3', 'magenta')]
    data.groups = [{ id: 5, name: 'Front', memberCount: 3, colour: 'lime' } as PatchGroup]
    render(<PositionsPanel isVisible />)
    const mark = (name: string) => screen.getByRole('button', { name }).querySelector<HTMLElement>('[data-chip-colour]')
    expect(mark('Par 1')?.dataset.chipColour).toBe('red')
    expect(mark('Par 1')?.className).toContain('fixture-tag-red')
    expect(mark('Par 1')?.querySelector('[data-tag-mark]')).toHaveAttribute('aria-label', 'Colour: Red')
    expect(mark('Par 2')).toBeNull()
    expect(mark('Par 3')).toBeNull()
    // The group chip is the group's own colour, never a member's.
    expect(screen.getByRole('button', { name: /Front/ }).querySelector<HTMLElement>('[data-chip-colour]')?.dataset.chipColour).toBe('lime')
  })
})
