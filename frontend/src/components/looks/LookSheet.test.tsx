// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LookSummary } from '@/api/looksApi'

const saveLook = vi.fn((_args: unknown) => Promise.resolve({}))
const copyLook = vi.fn((_args: unknown): Promise<unknown> => Promise.resolve({}))
const deleteLook = vi.fn((_args: unknown): Promise<unknown> => Promise.resolve(undefined))
const include = vi.fn()
const pickUp = vi.fn()
const toastError = vi.fn()

vi.mock('sonner', () => ({
  toast: { error: (...a: unknown[]) => toastError(...a), info: () => {}, success: () => {} },
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
vi.mock('@/store/looks', () => ({
  useSaveLookMutation: () => [(args: unknown) => ({ unwrap: () => saveLook(args) })],
  useCopyLookMutation: () => [(args: unknown) => ({ unwrap: () => copyLook(args) })],
  useDeleteLookMutation: () => [(args: unknown) => ({ unwrap: () => deleteLook(args) })],
}))
vi.mock('@/components/programmer/useInclude', () => ({
  useInclude: () => ({ include: (...a: unknown[]) => include(...a), isLoading: false }),
}))
vi.mock('@/store/hand', () => ({ handPickUp: (...a: unknown[]) => pickUp(...a) }))
vi.mock('@/store/projects', () => ({
  useProjectListQuery: () => ({
    data: [
      { id: 1, name: 'Hamlet', isCurrent: true },
      { id: 2, name: 'Rehearsal Room', isCurrent: false },
    ],
  }),
}))

import { LookSheet, describeLookContents } from './LookSheet'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'

function look(id: number, over: Partial<LookSummary> = {}): LookSummary {
  return {
    id,
    uuid: `l${id}`,
    name: `L${id}`,
    notes: null,
    families: ['COLOUR'],
    rowCount: 8,
    effectCount: 0,
    targetCount: 8,
    hasDeferredEffects: false,
    preview: ['#ff9d4a'],
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

/** The board's first three rows, and one more. */
const LOOKS = [
  look(1, { name: 'Ballyhoo', families: ['POSITION'], preview: [], effectCount: 1, rowCount: 0, targetCount: 0, hasDeferredEffects: true, buskPageCount: 3 }),
  look(2, { name: 'Band Spots', families: ['INTENSITY', 'POSITION'], notes: 'Front line', layerCount: 2 }),
  look(3, { name: 'Blackout Front', families: ['INTENSITY'] }),
  look(4, { name: 'Warm Wash', families: ['COLOUR', 'INTENSITY'], notes: 'Act 1 base', layerCount: 5 }),
]

const BANDS: Record<string, [number, number]> = { notes: [580, 760] }
const CENTRE = (col: string) => (BANDS[col][0] + BANDS[col][1]) / 2

function stubLayout() {
  const rect = (left: number, right: number) =>
    ({ left, top: 0, right, bottom: 0, width: right - left, height: 0, x: left, y: 0, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-grid-name-header')) return rect(0, 200)
    const col = this.getAttribute('data-column-header')
    if (col && BANDS[col]) return rect(...BANDS[col])
    return rect(0, 1100)
  })
}

function draw(over: Partial<React.ComponentProps<typeof LookSheet>> = {}) {
  const onOpenLook = vi.fn()
  render(
    <LookSheet
      projectId={1}
      looks={LOOKS}
      library={LOOKS}
      isCurrentProject
      projectName="Hamlet"
      onOpenLook={onOpenLook}
      {...over}
    />,
  )
  return { onOpenLook }
}

function row(id: number): HTMLElement {
  return document.querySelector(`[data-row-id="look:${id}"]`) as HTMLElement
}

function selectRows(...ids: number[]) {
  ids.forEach((id, i) => fireEvent.click(row(id).querySelector('[data-first-column]')!, { metaKey: i > 0 }))
}

function dragNotes(from: number, to: number) {
  const start = row(LOOKS[from - 1].id)
  fireEvent.pointerDown(start, { button: 0, clientX: CENTRE('notes'), clientY: (from - 1) * 36 + 10 })
  fireEvent.pointerMove(start, { button: 0, buttons: 1, clientX: CENTRE('notes') + 4, clientY: (to - 1) * 36 + 26 })
  fireEvent.pointerUp(start, { button: 0, clientX: CENTRE('notes') + 4, clientY: (to - 1) * 36 + 26 })
  fireEvent.click(within(start).getAllByRole('button').find((b) => b.closest('[data-cell="notes"]'))!)
}

beforeEach(() => {
  stubLayout()
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  saveLook.mockClear()
  copyLook.mockReset()
  copyLook.mockImplementation(() => Promise.resolve({}))
  deleteLook.mockReset()
  deleteLook.mockImplementation(() => Promise.resolve(undefined))
  include.mockClear()
  pickUp.mockClear()
  toastError.mockClear()
})

describe('LookSheet', () => {
  it('reads out what a Look holds — families, contents, counts — and edits only its notes', () => {
    draw()
    expect(row(2)).toHaveTextContent('Intensity')
    expect(row(2)).toHaveTextContent('Position')
    expect(row(1)).toHaveTextContent('1 fx · effects follow the layer')
    // Notes is the one value cell; every other column is a read-out and takes no selection.
    expect(row(2).querySelector('[data-cell="notes"] button')).not.toBeNull()
    expect(row(2).querySelector('[data-cell="families"]')).toBeNull()
  })

  it('Set over Notes writes `PUT {notes}` alone — metadata, never rows', async () => {
    draw()
    dragNotes(1, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Set' }))
    const field = await screen.findByLabelText('Notes')
    fireEvent.change(field, { target: { value: 'Act 2' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(saveLook.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, lookId: 1, notes: 'Act 2' },
      { projectId: 1, lookId: 2, notes: 'Act 2' },
      { projectId: 1, lookId: 3, notes: 'Act 2' },
    ])
  })

  it('draws the board’s verbs over three rows, Include and Pick up disabled with the reason', () => {
    draw()
    selectRows(1, 2, 3)
    expect(screen.getByText('3 looks')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Include' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Include' })).toHaveAttribute('title', 'Include takes one look — select just one')
    expect(screen.getByRole('button', { name: 'Pick up' })).toBeDisabled()
    for (const name of ['Duplicate', 'Copy to…', 'Delete']) expect(screen.getByRole('button', { name })).toBeEnabled()
  })

  it('Include and Pick up act on the one selected Look', () => {
    draw()
    selectRows(2)
    fireEvent.click(screen.getByRole('button', { name: 'Include' }))
    expect(include).toHaveBeenCalledWith({ kind: 'LOOK', lookId: 2 })
    fireEvent.click(screen.getByRole('button', { name: 'Pick up' }))
    expect(pickUp).toHaveBeenCalledWith('LOOK', 2)
  })

  it('⏎ over one row opens it; the pencil does too', () => {
    const { onOpenLook } = draw()
    selectRows(3)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpenLook).toHaveBeenCalledWith(LOOKS[2])
    fireEvent.click(screen.getByRole('button', { name: 'Open Blackout Front' }))
    expect(onOpenLook).toHaveBeenCalledTimes(2)
  })

  it('Duplicate over three copies each through the copy route, (Copy n) against the batch', async () => {
    draw({ library: [...LOOKS, look(9, { name: 'Ballyhoo (Copy)' })] })
    selectRows(1, 2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(copyLook).toHaveBeenCalledTimes(3))
    expect(copyLook.mock.calls.map(([a]) => (a as { newName: string }).newName)).toEqual([
      'Ballyhoo (Copy 2)',
      'Band Spots (Copy)',
      'Blackout Front (Copy)',
    ])
  })

  it('Delete over three asks once: the one a cue layers, and the one with pads it never sent', async () => {
    deleteLook.mockImplementation((args) => {
      const { lookId, force } = args as { lookId: number; force: boolean }
      if (lookId === 2 && !force) {
        return Promise.reject({
          status: 409,
          data: { error: 'in use', code: 'LOOK_IN_USE', layerCount: 2, cueIds: [4, 9], cueNames: ['Q4', 'Q9'] },
        })
      }
      return Promise.resolve(undefined)
    })
    draw()
    selectRows(1, 2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(deleteLook.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 1, lookId: 2, force: false },
      { projectId: 1, lookId: 3, force: false },
    ])
    expect(dialog).toHaveTextContent('Blackout Front was deleted.')
    expect(dialog).toHaveTextContent('Ballyhoo')
    expect(dialog).toHaveTextContent('on 3 busk pages')
    expect(dialog).toHaveTextContent('2 cue layers — Q4, Q9')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep them' }))
    // *Keep them* leaves them selected.
    await waitFor(() => expect(screen.getByText('2 looks')).toBeInTheDocument())
    expect(deleteLook).toHaveBeenCalledTimes(2)
  })

  it('a padded Look that a cue also layers is sent plain on Delete anyway, and asks again before forcing', async () => {
    deleteLook.mockImplementation((args) => {
      const { force } = args as { force: boolean }
      return force
        ? Promise.resolve(undefined)
        : Promise.reject({
            status: 409,
            data: { error: 'in use', code: 'LOOK_IN_USE', layerCount: 1, cueIds: [7], cueNames: ['Q7'] },
          })
    })
    draw({ looks: [look(5, { name: 'Sunset', layerCount: 1, buskPageCount: 2 })] })
    selectRows(5)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    let dialog = await screen.findByRole('alertdialog')
    // Held on the plain pass for its pads: nothing sent yet.
    expect(deleteLook).not.toHaveBeenCalled()
    expect(dialog).toHaveTextContent('on 2 busk pages')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    // Sent plain, never forced past a use nobody was told about — the desk names the cue.
    await waitFor(() => expect(deleteLook).toHaveBeenCalledTimes(1))
    expect(deleteLook.mock.calls[0][0]).toEqual({ projectId: 1, lookId: 5, force: false })
    dialog = await screen.findByRole('alertdialog')
    await waitFor(() => expect(dialog).toHaveTextContent('1 cue layer — Q7 · on 2 busk pages'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete anyway' }))
    await waitFor(() => expect(deleteLook).toHaveBeenCalledTimes(2))
    expect(deleteLook.mock.calls[1][0]).toEqual({ projectId: 1, lookId: 5, force: true })
  })

  it('another project’s library: every value verb refused with the reason, Copy to… live, no Pick up', () => {
    const { onOpenLook } = draw({ projectId: 2, isCurrentProject: false, projectName: 'Rehearsal Room' })
    selectRows(2)
    const reason = 'Rehearsal Room’s library — copy it here to edit'
    for (const name of ['Include', 'Duplicate', 'Delete']) {
      expect(screen.getByRole('button', { name })).toBeDisabled()
      expect(screen.getByRole('button', { name })).toHaveAttribute('title', reason)
    }
    expect(screen.queryByRole('button', { name: 'Pick up' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy to…' })).toBeEnabled()
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpenLook).not.toHaveBeenCalled()
  })

  it('Copy to… names each record that failed and keeps it for a retry', async () => {
    copyLook.mockImplementation((args) =>
      (args as { lookId: number }).lookId === 2
        ? Promise.reject({ status: 409, data: { error: 'A look named Band Spots already exists' } })
        : Promise.resolve({}),
    )
    draw({ projectId: 2, isCurrentProject: false, projectName: 'Rehearsal Room' })
    selectRows(2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Copy to…' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Copy' }))
    expect(await screen.findByText(/was not copied — a look of that name is already in Hamlet/)).toBeInTheDocument()
    expect(copyLook.mock.calls.map(([a]) => a)).toEqual([
      { projectId: 2, lookId: 2, targetProjectId: 1, newName: undefined },
      { projectId: 2, lookId: 3, targetProjectId: 1, newName: undefined },
    ])
    // The one left is offered a new name, and a retry sends only it.
    fireEvent.change(screen.getByLabelText('New name (optional)'), { target: { value: 'Band Spots 2' } })
    copyLook.mockImplementation(() => Promise.resolve({}))
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }))
    await waitFor(() => expect(copyLook).toHaveBeenCalledTimes(3))
    expect(copyLook.mock.calls[2][0]).toEqual({ projectId: 2, lookId: 2, targetProjectId: 1, newName: 'Band Spots 2' })
  })
})

describe('describeLookContents', () => {
  it('says what a Look covers in one line, the effect count folded in', () => {
    expect(describeLookContents(LOOKS[3])).toBe('8 fixtures · 8 rows')
    expect(describeLookContents(look(5, { effectCount: 1, rowCount: 12, targetCount: 12 }))).toBe('12 fixtures · 12 rows · 1 fx')
    expect(describeLookContents(look(6, { targetCount: 0, effectCount: 0 }))).toBe('Empty')
  })
})
