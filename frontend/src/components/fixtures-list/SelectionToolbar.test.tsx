// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * Which of the toolbar's controls fold, and at which container width.
 *
 * jsdom evaluates no container query, so what is pinned is the class each control carries. That is
 * the whole of the assertion and it is enough: these are two shared constants, and the bug they
 * guard against is one control folding at a different width from the one beside it.
 *
 * Locate and Highlight go **early**, at 800, on a bar that carries a strip, which needs the width. Deselect never folds: Escape is a key, and on a phone
 * this and a tap on the grid's empty background are the only ways out of a selection.
 */
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('../../store/locate', () => ({
  useLocateStateQuery: () => ({ data: { targets: [] } }),
  useToggleLocateMutation: () => [vi.fn()],
}))
const storedLocate = { projectId: 3 as number | undefined, storeFromCurrent: vi.fn(), clearLocate: vi.fn(), busy: false }
vi.mock('@/hooks/useStoredLocateActions', () => ({ useStoredLocateActions: () => storedLocate }))

const { MID_FOLDED_CLASS, PHONE_FOLDED_CLASS, STRIP_MID_FOLDED_CLASS, SelectionToolbar, WORD_CLASS } = await import(
  './SelectionToolbar'
)
const { useRigVerbs } = await import('./RigVerbs')

/** The container owns the rig verbs; the toolbar draws them. */
function Harness() {
  const verbs = useRigVerbs([{ type: 'fixture', key: 'hex-1' }], [{ key: 'hex-1', attributes: [] }])
  return <SelectionToolbar verbs={verbs} onClear={() => {}} actions={<button type="button">Set</button>} />
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  storedLocate.projectId = 3
})

/** Radix's DropdownMenuTrigger opens on `pointerdown`, not `click`. */
function openLocateMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: 'Locate options' }), {
    button: 0,
    ctrlKey: false,
    pointerType: 'mouse',
  })
}

function toolbar() {
  return render(<Harness />)
}

describe('SelectionToolbar', () => {
  it('folds Locate and Highlight at 800 only on a bar with a strip, ahead of the phone arm', () => {
    // The gated form, not the bare one: on the plain lists this toolbar is the only place the two
    // verbs exist; the 800 fold applies only on a bar that carries a strip.
    toolbar()
    for (const word of ['Locate', 'Highlight']) {
      const button = screen.getByText(word).closest('button')
      expect(button, word).toHaveClass(STRIP_MID_FOLDED_CLASS)
      expect(button, word).not.toHaveClass(MID_FOLDED_CLASS)
      expect(button, word).not.toHaveClass(PHONE_FOLDED_CLASS)
    }
  })

  it('keeps Deselect and the selection’s own verbs at every width', () => {
    toolbar()
    const deselect = screen.getByTitle('Deselect all')
    expect(deselect).not.toHaveClass(MID_FOLDED_CLASS)
    expect(deselect).not.toHaveClass(PHONE_FOLDED_CLASS)
    expect(screen.getByText('Set')).toBeInTheDocument()
  })

  it('drops the two words earlier still, since a tooltip already carries them', () => {
    toolbar()
    expect(screen.getByText('Locate')).toHaveClass(WORD_CLASS.split(' ').at(-1)!)
  })

  it('offers Set locate from current and Clear locate over the selection, folding with Locate', () => {
    toolbar()
    const trigger = screen.getByRole('button', { name: 'Locate options' })
    expect(trigger).toHaveClass(STRIP_MID_FOLDED_CLASS)
    openLocateMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Set locate from current' }))
    expect(storedLocate.storeFromCurrent).toHaveBeenCalledWith([{ type: 'fixture', key: 'hex-1' }])
    openLocateMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear locate' }))
    expect(storedLocate.clearLocate).toHaveBeenCalledWith([{ type: 'fixture', key: 'hex-1' }])
  })

  it('withholds the locate menu until the current project is known', () => {
    storedLocate.projectId = undefined
    toolbar()
    expect(screen.getByRole('button', { name: 'Locate options' })).toBeDisabled()
  })
})
