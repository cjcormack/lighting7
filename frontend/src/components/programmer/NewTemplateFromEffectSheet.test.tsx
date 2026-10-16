// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveEffect, EffectLibraryEntry } from '@/store/fixtureFx'
import type { TemplateSummary } from '@/api/templatesApi'

/**
 * *Save as palette…* on the two step effects (effects plan §3.6): a step effect is banked by its
 * palettes' family, or as Beam on the setting it steps — never by its library category, which for
 * Palette Steps names no family and for Slot Steps names no setting.
 */
const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  // Stable, as RTK Query's is: the sheet clears its name on a change of `reset`.
  reset: () => {},
  templates: [] as TemplateSummary[],
  library: [] as EffectLibraryEntry[],
}))

vi.mock('@/store/templates', () => ({
  useCreateTemplateMutation: () => [mocks.create, { isLoading: false, error: undefined, reset: mocks.reset }],
  useTemplateListQuery: () => ({ data: mocks.templates }),
}))
vi.mock('@/store/fixtureFx', () => ({ useEffectLibraryQuery: () => ({ data: mocks.library }) }))
vi.mock('@/components/busking/AlsoAddToBuskRow', () => ({ AlsoAddToBuskRow: () => null }))
vi.mock('@/components/busking/useAddToBuskPage', () => ({ useAddToBuskPage: () => ({ placeAfterCreate: async () => {} }) }))
vi.mock('@/components/fx/SpeedMasterChip', () => ({ SpeedMasterChip: () => null }))

import { NewTemplateFromEffectSheet } from './NewTemplateFromEffectSheet'

const entry = (name: string, category: string) =>
  ({ name, category, outputType: 'STEPS', parameters: [], compatibleProperties: [], timingSource: 'BEAT' }) as unknown as EffectLibraryEntry

const palette = (uuid: string, family: TemplateSummary['family']) =>
  ({ id: 1, uuid, name: uuid, family, isGeneric: true, kind: 'value', rows: [] }) as unknown as TemplateSummary

const running = (over: Partial<ActiveEffect>) =>
  ({
    id: 7,
    effectType: 'SlotSteps',
    propertyName: 'gobo',
    beatDivision: 4,
    blendMode: 'OVERRIDE',
    phaseOffset: 0,
    parameters: {},
    timingSource: 'BEAT',
    programmerOwned: true,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }) as unknown as ActiveEffect

function open(effect: ActiveEffect) {
  render(
    <MemoryRouter initialEntries={['/projects/1/programmer']}>
      <Routes>
        <Route path="/projects/:projectId/programmer" element={<NewTemplateFromEffectSheet open onOpenChange={() => {}} effect={effect} />} />
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
  mocks.library = [entry('SlotSteps', 'beam'), entry('PaletteSteps', 'palette')]
  mocks.templates = [palette('a', 'POSITION'), palette('b', 'POSITION')]
})
afterEach(cleanup)

describe('NewTemplateFromEffectSheet on a step effect', () => {
  it('saves Slot Steps as a Beam palette naming the setting it steps', async () => {
    open(running({ effectType: 'SlotSteps', propertyName: 'gobo', parameters: { slots: 'GOBO_3,GOBO_5' } }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    expect(mocks.create.mock.calls[0][0].effect).toMatchObject({ effectType: 'SlotSteps', category: 'beam', propertyName: 'gobo' })
  })

  it("saves Palette Steps under its palettes' family, naming no property", async () => {
    open(running({ effectType: 'PaletteSteps', propertyName: 'position', parameters: { palettes: 'tmpl:a,tmpl:b' } }))
    await waitFor(() => expect(mocks.create).toHaveBeenCalled())
    expect(mocks.create.mock.calls[0][0].effect).toMatchObject({ effectType: 'PaletteSteps', category: 'position', propertyName: null })
  })
})
