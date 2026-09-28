// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EffectLibraryEntry } from '../store/fixtureFx'
import type { FxDefinition } from '../store/fxDefinitions'

const createDefinition = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({ id: 99 }))
const updateDefinition = vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({}))
const deleteDefinition = vi.fn((_id: unknown): Promise<unknown> => Promise.resolve(undefined))
const toastInfo = vi.fn()
const toastError = vi.fn()
let isCurrent = true

vi.mock('sonner', () => ({
  toast: { error: (...a: unknown[]) => toastError(...a), info: (...a: unknown[]) => toastInfo(...a), success: () => {} },
}))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: () => <div /> }))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: isCurrent ? 6 : 1, name: 'Experiment' }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 6, name: 'Experiment', isCurrent }, isLoading: false }),
}))

function entry(name: string, over: Partial<EffectLibraryEntry> = {}): EffectLibraryEntry {
  return {
    name,
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '0', description: '' }],
    compatibleProperties: ['dimmer'],
    source: 'BUILT_IN',
    sourceDefinitionId: null,
    script: 'FxOutput.Slider(0u)',
    ...over,
  }
}

/** The board's shape: built-ins, one custom definition, one script's effect — and a fork already taken. */
const LIBRARY = [
  entry('Pulse'),
  entry('PulseCustom', { source: 'USER', sourceDefinitionId: 3 }),
  entry('RandomFlicker', { source: 'USER', sourceDefinitionId: 7, timingSource: 'WALL_CLOCK' }),
  entry('WarmFlicker', { category: 'colour', outputType: 'COLOUR', compatibleProperties: ['rgbColour'], source: 'USER', sourceDefinitionId: 12 }),
  entry('ColourChase', { category: 'colour', outputType: 'COLOUR', compatibleProperties: ['rgbColour'] }),
]

function definition(id: number, effectId: string, name: string): FxDefinition {
  return {
    id,
    effectId,
    name,
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    parameters: [],
    compatibleProperties: ['dimmer'],
    script: '',
    defaultStepTiming: false,
    timingSource: 'BEAT',
  }
}

const DEFINITIONS = [definition(3, 'PulseCustom', 'Pulse (Custom)'), definition(7, 'RandomFlicker', 'Random Flicker')]

vi.mock('../store/fixtureFx', () => ({
  useEffectLibraryQuery: () => ({ data: LIBRARY, isLoading: false }),
}))
vi.mock('../store/fxDefinitions', () => ({
  useFxDefinitionListQuery: () => ({ data: DEFINITIONS, isLoading: false }),
  useCreateFxDefinitionMutation: () => [(body: unknown) => ({ unwrap: () => createDefinition(body) }), { isLoading: false }],
  useUpdateFxDefinitionMutation: () => [(body: unknown) => ({ unwrap: () => updateDefinition(body) }), { isLoading: false }],
  useDeleteFxDefinitionMutation: () => [(id: unknown) => ({ unwrap: () => deleteDefinition(id) })],
}))
// The three sheets it opens are markers here: what matters is which one, with what.
vi.mock('../components/fxLibrary/EffectDetailSheet', () => ({
  EffectDetailSheet: ({ effect, source }: { effect: EffectLibraryEntry; source: string }) => (
    <div data-testid="detail">
      {effect.name}:{source}
    </div>
  ),
}))
vi.mock('../components/fxLibrary/EditFxDefinitionSheet', () => ({
  EditFxDefinitionSheet: ({ definitionId, forkedFrom }: { definitionId: number; forkedFrom?: string }) => (
    <div data-testid="edit">
      {definitionId}:{forkedFrom ?? ''}
    </div>
  ),
}))
vi.mock('../components/fxLibrary/NewFxDefinitionSheet', () => ({ NewFxDefinitionSheet: () => <div data-testid="new" /> }))

import { ProjectFxLibrary } from './FxLibrary'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname}</div>
}

function draw() {
  render(
    <MemoryRouter initialEntries={['/projects/6/fx-library']}>
      <Routes>
        <Route path="/projects/:projectId/fx-library" element={<ProjectFxLibrary />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
}

function row(effectId: string): HTMLElement {
  return document.querySelector(`[data-row-id="fx:${effectId}"]`) as HTMLElement
}

function divider(category: string): HTMLElement | null {
  return document.querySelector(`[data-row-id="category:${category}"]`)
}

function selectRows(...ids: string[]) {
  ids.forEach((id, i) => fireEvent.click(row(id).querySelector('[data-first-column]')!, { metaKey: i > 0 }))
}

beforeEach(() => {
  isCurrent = true
  localStorage.clear()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  resetEditorSurfaceMedia()
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  createDefinition.mockClear()
  updateDefinition.mockClear()
  deleteDefinition.mockClear()
  toastInfo.mockClear()
  toastError.mockClear()
})

describe('FX Library sheet', () => {
  it('reads each row’s source off the definition list — Built-in, Custom, Script', () => {
    draw()
    expect(row('Pulse').querySelector('[data-source]')).toHaveAttribute('data-source', 'builtIn')
    expect(row('RandomFlicker').querySelector('[data-source]')).toHaveAttribute('data-source', 'custom')
    expect(row('WarmFlicker').querySelector('[data-source]')).toHaveAttribute('data-source', 'script')
    // A custom row is called by its definition's name; the rest by their ids as words.
    expect(row('PulseCustom')).toHaveTextContent('Pulse (Custom)')
    expect(row('WarmFlicker')).toHaveTextContent('Warm Flicker')
    expect(row('RandomFlicker')).toHaveTextContent('Clock')
  })

  it('groups the rows under category dividers under All, and a chip narrows them', () => {
    draw()
    expect(divider('dimmer')).toHaveTextContent('Dimmer · 3')
    expect(divider('colour')).toHaveTextContent('Colour · 2')
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Category' })).getByRole('button', { name: /Colour/ }))
    expect(divider('colour')).toBeNull()
    expect(row('Pulse')).toBeNull()
    expect(row('WarmFlicker')).not.toBeNull()
    expect(localStorage.getItem('fxLibrary.category')).toBe('colour')
  })

  it('opens each row’s own record: a custom row its definition, a script’s effect its script, a built-in its detail', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Open Random Flicker' }))
    expect(screen.getByTestId('edit')).toHaveTextContent('7:')
    fireEvent.keyDown(document.activeElement ?? window, { key: 'Escape' })
    cleanup()

    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Open Pulse' }))
    expect(screen.getByTestId('detail')).toHaveTextContent('Pulse:builtIn')
    cleanup()

    // §1's first bug: script 12 is not definition 12 — it opens the script.
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Open Warm Flicker’s script' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/projects/6/scripts/12')
  })

  it('renames a custom row with its name alone, and offers no rename on a built-in or a script’s effect', () => {
    draw()
    fireEvent.doubleClick(within(row('RandomFlicker')).getByText('Random Flicker'))
    const field = screen.getByLabelText('Effect name') as HTMLInputElement
    fireEvent.change(field, { target: { value: 'Candle' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(updateDefinition).toHaveBeenCalledWith({ id: 7, name: 'Candle' })
    expect(within(row('Pulse')).queryByTitle('Double-click to rename')).toBeNull()
    expect(within(row('WarmFlicker')).queryByTitle('Double-click to rename')).toBeNull()
  })

  it('Fork mints an id past the fork the library already holds, and opens the new definition in its editor', async () => {
    draw()
    selectRows('Pulse')
    fireEvent.click(screen.getByRole('button', { name: 'Fork' }))
    await waitFor(() => expect(createDefinition).toHaveBeenCalledTimes(1))
    expect(createDefinition.mock.calls[0][0]).toMatchObject({
      effectId: 'PulseCustom2',
      name: 'Pulse (Custom 2)',
      category: 'dimmer',
      outputType: 'SLIDER',
      effectMode: 'STANDARD',
      compatibleProperties: ['dimmer'],
      script: 'FxOutput.Slider(0u)',
      timingSource: 'BEAT',
      defaultStepTiming: false,
    })
    expect(await screen.findByTestId('edit')).toHaveTextContent('99:Pulse')
  })

  it('Fork takes one built-in — refused over two rows, or over a custom one, with the reason', () => {
    draw()
    selectRows('Pulse', 'ColourChase')
    expect(screen.getByRole('button', { name: 'Fork' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Fork' })).toHaveAttribute('title', 'Fork takes one effect — select just one')
    selectRows('RandomFlicker')
    expect(screen.getByRole('button', { name: 'Fork' })).toHaveAttribute(
      'title',
      'Only a built-in forks — edit this custom effect directly',
    )
  })

  it('Delete sends only the custom definitions, skipping the rest by name before anything is sent', async () => {
    draw()
    selectRows('Pulse', 'RandomFlicker', 'WarmFlicker', 'PulseCustom')
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteDefinition).toHaveBeenCalledTimes(2))
    // In visible order: *Pulse (Custom)* sorts before *Random Flicker*.
    expect(deleteDefinition.mock.calls.map(([id]) => id)).toEqual([3, 7])
    expect(toastInfo).toHaveBeenCalledTimes(1)
    const skipped = String(toastInfo.mock.calls[0][0])
    expect(skipped).toContain('Pulse: built-in effects cannot be deleted')
    expect(skipped).toContain('Warm Flicker: a script registers it')
  })

  it('refuses Delete where the selection holds no custom effect', () => {
    draw()
    selectRows('Pulse', 'WarmFlicker')
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
  })

  it('off the running project nothing is live: Fork, Delete and the rename refused, every row opens read-only', () => {
    isCurrent = false
    draw()
    selectRows('RandomFlicker')
    const reason = 'Not the running project — the FX Library shows and edits the running show’s effects'
    expect(screen.getByRole('button', { name: 'Fork' })).toHaveAttribute('title', reason)
    expect(screen.getByRole('button', { name: 'Fork' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(within(row('RandomFlicker')).queryByTitle('Double-click to rename')).toBeNull()
    expect(screen.queryByRole('button', { name: 'New effect' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Open Random Flicker' }))
    expect(screen.getByTestId('detail')).toHaveTextContent('RandomFlicker:custom')
  })
})
