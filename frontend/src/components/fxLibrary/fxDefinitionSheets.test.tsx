// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import type { FxDefinition } from '@/store/fxDefinitions'

const createDefinition = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({ id: 5 }))
const updateDefinition = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({}))

const DEFINITION: FxDefinition = {
  id: 5,
  fxId: 'PulseCustom',
  name: 'Pulse (Custom)',
  family: 'intensity',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  parameters: [],
  compatibleProperties: ['dimmer'],
  script: 'FxOutput.Slider(0u)',
  defaultTiming: 'CYCLE',
  timingSource: 'BEAT',
}

vi.mock('@/components/scripts/LazyScriptEditor', () => ({ LazyScriptEditor: () => <div /> }))
vi.mock('@/components/scripts/ScriptResultDialogs', () => ({ ScriptCompileDialog: () => null, ScriptRunDialog: () => null }))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 6 } }),
  useCompileProjectScriptMutation: () => [vi.fn(), { isUninitialized: true, isLoading: false, reset: () => {} }],
  useRunProjectScriptMutation: () => [vi.fn(), { isUninitialized: true, isLoading: false, reset: () => {} }],
}))
vi.mock('@/store/fxDefinitions', () => ({
  useFxDefinitionQuery: () => ({ data: DEFINITION, isLoading: false, isFetching: false }),
  useCreateFxDefinitionMutation: () => [(body: unknown) => ({ unwrap: () => createDefinition(body) }), { isLoading: false }],
  useUpdateFxDefinitionMutation: () => [(body: unknown) => ({ unwrap: () => updateDefinition(body) }), { isLoading: false }],
}))

import { NewFxDefinitionSheet } from './NewFxDefinitionSheet'
import { EditFxDefinitionSheet } from './EditFxDefinitionSheet'

function inSheet(node: React.ReactNode) {
  return render(
    <Sheet open onOpenChange={() => {}}>
      <SheetContent>{node}</SheetContent>
    </Sheet>,
  )
}

afterEach(() => {
  cleanup()
  createDefinition.mockClear()
  updateDefinition.mockClear()
})

describe('NewFxDefinitionSheet', () => {
  it('refuses a name of spaces alone, and sends a trimmed name under an id made unique', async () => {
    const onCreated = vi.fn()
    inSheet(<NewFxDefinitionSheet takenIds={['Pulse', 'SineWave']} onCreated={onCreated} />)
    const name = screen.getByLabelText('Name')
    fireEvent.change(name, { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled()

    // A new effect called *Pulse* would take the built-in's id and replace it in the registry.
    fireEvent.change(name, { target: { value: ' Pulse ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(createDefinition).toHaveBeenCalledTimes(1))
    expect(createDefinition.mock.calls[0][0]).toMatchObject({ fxId: 'Pulse2', name: 'Pulse' })
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(5))
  })

  it('offers every category the library draws but Beam, Controls included', () => {
    inSheet(<NewFxDefinitionSheet takenIds={[]} onCreated={() => {}} />)
    fireEvent.click(screen.getAllByRole('combobox')[0])
    for (const label of ['Intensity', 'Colour', 'Position', 'Controls']) {
      expect(screen.getByRole('option', { name: label })).toBeInTheDocument()
    }
    // A beam effect drives one named property, which this form cannot name.
    expect(screen.queryByRole('option', { name: 'Beam' })).toBeNull()
  })
})

describe('EditFxDefinitionSheet', () => {
  it('says a fork starts per cycle, and saves the toggle with the name and script', async () => {
    inSheet(<EditFxDefinitionSheet definitionId={5} forkedFrom="Pulse" onDelete={() => {}} />)
    expect(screen.getByTestId('fork-step-timing-note')).toHaveTextContent('Forked from Pulse. A fork starts per cycle')
    expect(screen.getByRole('radio', { name: 'Per cycle' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('radio', { name: 'Per head' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(updateDefinition).toHaveBeenCalledTimes(1))
    expect(updateDefinition.mock.calls[0][0]).toEqual({
      id: 5,
      name: 'Pulse (Custom)',
      script: 'FxOutput.Slider(0u)',
      defaultTiming: 'HEAD',
    })
  })

  it('refuses to save a name of spaces alone, and trims the one it saves', async () => {
    inSheet(<EditFxDefinitionSheet definitionId={5} onDelete={() => {}} />)
    const name = screen.getByLabelText('Name')
    fireEvent.change(name, { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    fireEvent.change(name, { target: { value: '  Candle  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(updateDefinition).toHaveBeenCalledTimes(1))
    expect(updateDefinition.mock.calls[0][0]).toMatchObject({ name: 'Candle' })
  })

  it('hands Delete to the batch delete with the definition', () => {
    const onDelete = vi.fn()
    inSheet(<EditFxDefinitionSheet definitionId={5} onDelete={onDelete} />)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(onDelete).toHaveBeenCalledWith(DEFINITION)
  })
})
