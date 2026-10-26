// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveFx, FxLibraryEntry } from '@/store/fixtureFx'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * *Save as palette…* on the two step effects (effects plan §3.6): a step effect is banked by its
 * palettes' family, or as Beam on the setting it steps — never by its library category, which for
 * Palette Steps names no family and for Slot Steps names no setting.
 */
const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  // Stable, as RTK Query's is: the sheet clears its name on a change of `reset`.
  reset: () => {},
  templates: [] as PaletteSummary[],
  library: [] as FxLibraryEntry[],
}))

vi.mock('@/store/palettes', () => ({
  useCreatePaletteMutation: () => [mocks.create, { isLoading: false, error: undefined, reset: mocks.reset }],
  usePaletteListQuery: () => ({ data: mocks.templates }),
}))
vi.mock('@/store/fixtureFx', () => ({ useFxLibraryQuery: () => ({ data: mocks.library }) }))
vi.mock('@/components/busking/AlsoAddToBuskRow', () => ({ AlsoAddToBuskRow: () => null }))
vi.mock('@/components/busking/useAddToBuskPage', () => ({ useAddToBuskPage: () => ({ placeAfterCreate: async () => {} }) }))
vi.mock('@/components/fx/SpeedMasterChip', () => ({ SpeedMasterChip: () => null }))

import { NewPaletteFromFxSheet } from './NewPaletteFromFxSheet'

const entry = (name: string, family: string) =>
  ({ name, family, outputType: 'STEPS', parameters: [], compatibleAttributes: [], timingSource: 'BEAT' }) as unknown as FxLibraryEntry

const palette = (uuid: string, family: PaletteSummary['family']) =>
  ({ id: 1, uuid, name: uuid, family, isGeneric: true, kind: 'value', rows: [] }) as unknown as PaletteSummary

const running = (over: Partial<ActiveFx>) =>
  ({
    id: 7,
    fxType: 'SlotSteps',
    attribute: 'gobo',
    cycle: 4,
    blendMode: 'OVERRIDE',
    phaseOffset: 0,
    parameters: {},
    timingSource: 'BEAT',
    programmerOwned: true,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }) as unknown as ActiveFx

function open(effect: ActiveFx) {
  render(
    <MemoryRouter initialEntries={['/projects/1/programmer']}>
      <Routes>
        <Route path="/projects/:projectId/programmer" element={<NewPaletteFromFxSheet projectId={1} open onOpenChange={() => {}} effect={effect} />} />
      </Routes>
    </MemoryRouter>,
  )
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Steps' } })
  const create = screen.getByRole('button', { name: 'Create palette' })
  expect(create).not.toBeDisabled()
  fireEvent.click(create)
}

beforeEach(() => {
  mocks.create.mockReset().mockReturnValue({ unwrap: async () => ({ id: 3, name: 'Steps' }) })
  mocks.library = [entry('SlotSteps', 'steps'), entry('PaletteSteps', 'steps')]
  mocks.templates = [palette('a', 'POSITION'), palette('b', 'POSITION')]
})
afterEach(cleanup)

describe('NewPaletteFromFxSheet on a step effect', () => {
  it('saves Slot Steps as a Beam palette naming the setting it steps', async () => {
    open(running({ fxType: 'SlotSteps', attribute: 'gobo', parameters: { slots: 'GOBO_3,GOBO_5' } }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    // An FX reporting no Over starts the palette on Fixtures.
    expect(mocks.create.mock.calls[0][0].fx).toMatchObject({ fxType: 'SlotSteps', family: 'beam', attribute: 'gobo', elementMode: 'WHOLE' })
  })

  it('keeps the running FX\'s own Over', async () => {
    open(running({ fxType: 'SlotSteps', attribute: 'gobo', parameters: { slots: 'GOBO_3,GOBO_5' }, elementMode: 'FLAT' }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    expect(mocks.create.mock.calls[0][0].fx).toMatchObject({ elementMode: 'FLAT' })
  })

  it("saves Palette Steps under its palettes' family, naming no property", async () => {
    open(running({ fxType: 'PaletteSteps', attribute: 'position', parameters: { palettes: 'pal:a,pal:b' } }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    expect(mocks.create.mock.calls[0][0].fx).toMatchObject({ fxType: 'PaletteSteps', family: 'position', attribute: null })
  })
})
