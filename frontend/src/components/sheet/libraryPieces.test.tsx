// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const toastError = vi.fn()
const toastInfo = vi.fn()
vi.mock('sonner', () => ({ toast: { error: (...a: unknown[]) => toastError(...a), info: (...a: unknown[]) => toastInfo(...a) } }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))

import { useBatchDelete, type DeleteOutcome } from './useBatchDelete'
import { BatchDeleteDialog } from './BatchDeleteDialog'
import { PartitionChips, LibraryRow } from './LibraryRow'
import { NumberCell } from './cells/NumberCell'
import { firstColumnCellProps } from './sheetModel'
import { resetEditorSurfaceMedia } from '../editor/EditorSurface'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

interface Item {
  id: number
  name: string
}
const A: Item = { id: 1, name: 'Master 1' }
const B: Item = { id: 2, name: 'Colour' }
const C: Item = { id: 3, name: 'Movement' }
const D: Item = { id: 4, name: 'Strobe' }

describe('useBatchDelete (library-sheets plan D13)', () => {
  function harness(outcomes: Record<number, DeleteOutcome<string>>, forced: Record<number, DeleteOutcome<string>> = {}) {
    const remove = vi.fn(async (item: Item, force: boolean) => (force ? (forced[item.id] ?? { kind: 'ok' as const }) : outcomes[item.id]))
    const onDeleted = vi.fn()
    const onKept = vi.fn()
    const hook = renderHook(() =>
      useBatchDelete<Item, string>({
        remove,
        name: (item) => item.name,
        skip: (item) => (item.id === 1 ? 'the global tempo' : null),
        noun: 'master',
        onDeleted,
        onKept,
        toastKey: 'test',
      }),
    )
    return { remove, onDeleted, onKept, ...hook }
  }

  it('skips by name before sending, sends the rest plain, and asks once about the in-use ones', async () => {
    const { remove, onDeleted, result } = harness({
      2: { kind: 'ok' },
      3: { kind: 'inUse', summary: '6 references' },
      4: { kind: 'inUse', summary: 'followed by Chase' },
    })
    await act(() => result.current.run([A, B, C, D]))
    expect(remove.mock.calls.map(([item, force]) => [item.id, force])).toEqual([
      [2, false],
      [3, false],
      [4, false],
    ])
    expect(toastInfo).toHaveBeenCalledWith('Master 1 skipped — the global tempo', expect.anything())
    expect(onDeleted).toHaveBeenCalledWith([B])
    expect(result.current.state).toEqual({
      total: 3,
      deleted: ['Colour'],
      inUse: [
        { item: C, summary: '6 references' },
        { item: D, summary: 'followed by Chase' },
      ],
    })
  })

  it('*Delete anyway* forces only the in-use ones', async () => {
    const { remove, onDeleted, result } = harness({ 2: { kind: 'ok' }, 3: { kind: 'inUse', summary: 'x' } })
    await act(() => result.current.run([B, C]))
    remove.mockClear()
    onDeleted.mockClear()
    await act(() => result.current.force())
    expect(remove.mock.calls.map(([item, force]) => [item.id, force])).toEqual([[3, true]])
    expect(onDeleted).toHaveBeenCalledWith([C])
    expect(result.current.state).toBeNull()
  })

  it('*Keep them* sends nothing and hands the in-use ones back', async () => {
    const { remove, onKept, result } = harness({ 3: { kind: 'inUse', summary: 'x' } })
    await act(() => result.current.run([C]))
    remove.mockClear()
    act(() => result.current.keep())
    expect(remove).not.toHaveBeenCalled()
    expect(onKept).toHaveBeenCalledWith([C])
    expect(result.current.state).toBeNull()
  })

  it('reports every other refusal itself, a throw included (D14)', async () => {
    const { result } = harness({ 2: { kind: 'refused', reason: 'protected' } })
    await act(() => result.current.run([B]))
    expect(toastError).toHaveBeenCalledWith('Colour was not deleted: protected', { id: 'batch-delete:test' })
    expect(result.current.state).toBeNull()
  })

  it('sends nothing when every record is skipped', async () => {
    const { remove, result } = harness({})
    await act(() => result.current.run([A]))
    expect(remove).not.toHaveBeenCalled()
  })

  it('the dialog names what went and what each in-use record is used by', () => {
    render(
      <BatchDeleteDialog<Item, string>
        state={{ total: 3, deleted: ['Colour'], inUse: [{ item: C, summary: '6 references · followed by Chase' }] }}
        noun="master"
        name={(item) => item.name}
        describe={(_item, summary) => summary}
        consequence="forcing unlinks them:"
        busy={false}
        onForce={() => {}}
        onKeep={() => {}}
      />,
    )
    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveTextContent('Delete 3 masters?')
    expect(dialog).toHaveTextContent('Colour was deleted. One is in use — forcing unlinks them:')
    expect(dialog).toHaveTextContent('Movement6 references · followed by Chase')
  })
})

describe('PartitionChips — controlled, with counts, folding to a select', () => {
  it('draws All and each partition with its count, and reports a press without holding state', () => {
    const onChange = vi.fn()
    render(
      <PartitionChips
        label="Family"
        value="ALL"
        allCount={13}
        options={[
          { value: 'COLOUR', label: 'Colour', count: 4 },
          { value: 'BEAM', label: 'Beam', count: 4 },
        ]}
        onChange={onChange}
      />,
    )
    const nav = screen.getByRole('navigation', { name: 'Family' })
    const colour = Array.from(nav.querySelectorAll('button')).find((b) => b.textContent === 'Colour4')!
    expect(Array.from(nav.querySelectorAll('button')).map((b) => b.textContent)).toEqual(['All13', 'Colour4', 'Beam4'])
    fireEvent.click(colour)
    expect(onChange).toHaveBeenCalledWith('COLOUR')
    // Controlled: the press is reported, the pressed chip is still the one the host says.
    expect(nav.querySelector('[aria-pressed="true"]')?.textContent).toBe('All13')
    // The fold: the select sits beside the chips, the container query choosing between them.
    expect(screen.getByRole('combobox', { name: 'Family' })).toBeInTheDocument()
  })

  it('the library row draws the filter, the chips and the create verb', () => {
    const onFilter = vi.fn()
    render(<LibraryRow filter="" onFilterChange={onFilter} filterLabel="Filter by name" create={<button>New master</button>} />)
    fireEvent.change(screen.getByLabelText('Filter by name'), { target: { value: 'wash' } })
    expect(onFilter).toHaveBeenCalledWith('wash')
    expect(screen.getByRole('button', { name: 'New master' })).toBeInTheDocument()
  })
})

describe('NumberCell', () => {
  beforeEach(() => {
    resetEditorSurfaceMedia()
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  function open(value: number, onCommit = vi.fn(), skipped: string | null = null) {
    render(
      <NumberCell
        {...firstColumnCellProps<number>({ value, label: 'BPM', onCommit })}
        skipped={skipped}
        autoOpen
        min={20}
        max={300}
        unit="bpm"
      />,
    )
    return onCommit
  }

  it('commits on Enter, not as it is typed', async () => {
    const onCommit = open(120)
    const field = await screen.findByLabelText('BPM')
    fireEvent.change(field, { target: { value: '9' } })
    fireEvent.change(field, { target: { value: '90' } })
    expect(onCommit).not.toHaveBeenCalled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onCommit).toHaveBeenCalledTimes(1)
    expect(onCommit).toHaveBeenCalledWith(90)
  })

  it('refuses a number outside its range, naming the range, rather than clamping', async () => {
    const onCommit = open(120)
    const field = await screen.findByLabelText('BPM')
    fireEvent.change(field, { target: { value: '400' } })
    expect(screen.getByText('BPM is 20–300 bpm')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('an emptied box applies nothing — not the number that was deleted', async () => {
    const onCommit = open(120)
    const field = await screen.findByLabelText('BPM')
    fireEvent.change(field, { target: { value: '95' } })
    fireEvent.change(field, { target: { value: '' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled())
    expect(onCommit).not.toHaveBeenCalled()
  })

  it('names the skipped rows on its read-out', async () => {
    open(120, vi.fn(), 'M2 and M4 follow M1 · skipped')
    expect(await screen.findByText('M2 and M4 follow M1 · skipped')).toBeInTheDocument()
  })
})
