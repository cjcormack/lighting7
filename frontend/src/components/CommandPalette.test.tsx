// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CommandPage, ProgrammerCommand } from '@/components/programmer/commandDoors'

/**
 * ⌘K on the programmer (programmer-toolbar plan D13): the *Programmer* and *Selection* groups the
 * mounted doors offer, listed first on the programmer route and nowhere else; each item runs its
 * door's handler, an item that cannot act is missing, every hint is the door's, and the two
 * sub-pages list what their door built, a row pressed as a layer on ⏎ and as values on ⌥⏎.
 */

vi.mock('@/store/projects', () => ({ useProjectListQuery: () => ({ data: [] }) }))
vi.mock('@/store/auth', () => ({ useAuthStatusQuery: () => ({ data: undefined }) }))
vi.mock('@/store/fixtures', () => ({ useVisibleFixtureListQuery: () => ({ data: [] }) }))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/park', () => ({ useGetParkStateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/channelMapping', () => ({ useGetChannelMappingListQuery: () => ({ data: {} }) }))
vi.mock('@/navigation', () => ({
  useNavItems: () => [],
  useUniverseNavItems: () => [],
  usePaletteFamilyNavItems: () => [],
  programmerTableNavItems: [],
  useWindowCommands: () => [],
  filterNavItems: () => [],
  useIsNavAdmin: () => false,
  commandLabelOf: (item: { label: string }) => item.label,
  navItems: [],
}))
vi.mock('@/ProjectSwitcher', () => ({ useViewedProject: () => undefined }))
vi.mock('@/components/projectActions/ProjectActions', () => ({ useProjectActions: () => ({ actions: [], dialogs: null }) }))
vi.mock('@/components/fx/selectionFxTarget', () => ({
  useWindowFxPair: () => ({ targets: [], families: null }),
  fxTargetOf: () => null,
}))
vi.mock('@/components/fixtureColours/SelectByColour', () => ({ SelectByColourItems: () => null }))
vi.mock('@/ProjectSwitchConfirmDialog', () => ({ default: () => null }))
vi.mock('@/CreateProjectDialog', () => ({ default: () => null }))
vi.mock('@/ImportProjectDialog', () => ({ default: () => null }))

// cmdk measures its list; jsdom has no ResizeObserver.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver
Element.prototype.scrollIntoView ??= () => {}

const { default: CommandPalette } = await import('./CommandPalette')
const { registerCommandDoor } = await import('@/components/programmer/commandDoors')

const unregister: (() => void)[] = []
afterEach(() => {
  cleanup()
  while (unregister.length > 0) unregister.pop()!()
})

function door(name: Parameters<typeof registerCommandDoor>[0], commands: () => ProgrammerCommand[]) {
  unregister.push(registerCommandDoor(name, commands))
}

function openPalette(path = '/projects/3/programmer') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <CommandPalette />
    </MemoryRouter>,
  )
  act(() => {
    fireEvent.keyDown(document, { key: 'k', metaKey: true })
  })
  return screen.getByRole('dialog')
}

const headings = (dialog: HTMLElement) =>
  [...dialog.querySelectorAll('[cmdk-group-heading]')].map((el) => el.textContent)
const item = (dialog: HTMLElement, id: string) => dialog.querySelector(`[data-programmer-command="${id}"]`) as HTMLElement | null

const clear = vi.fn()
const deselect = vi.fn()

function programmerDoors() {
  door('clear', () => [
    { id: 'programmer.clear', group: 'programmer', label: 'Clear the programmer', hint: '6 values · over 1s', canAct: true, run: clear },
  ])
  door('blind', () => [{ id: 'programmer.blind', group: 'programmer', label: 'Blind on', hint: 'over 1s', canAct: false, run: vi.fn() }])
  door('set', () => [
    { id: 'selection.set', group: 'selection', label: 'Set the selected cells', hint: '6 cells', kbd: '⏎', canAct: true, run: vi.fn() },
  ])
  door('deselect', () => [{ id: 'selection.deselect', group: 'selection', label: 'Deselect', hint: '6 heads', canAct: true, run: deselect }])
}

describe('the programmer’s groups', () => {
  it('come first on the programmer route, Programmer then Selection', () => {
    programmerDoors()
    const dialog = openPalette()
    expect(headings(dialog).slice(0, 2)).toEqual(['Programmer', 'Selection'])
    expect(item(dialog, 'programmer.clear')).not.toBeNull()
    expect(item(dialog, 'selection.set')).not.toBeNull()
  })

  it('are not drawn on any other route', () => {
    programmerDoors()
    const dialog = openPalette('/projects/3/show')
    expect(headings(dialog)).not.toContain('Programmer')
    expect(headings(dialog)).not.toContain('Selection')
    expect(dialog.querySelector('[data-programmer-command]')).toBeNull()
  })

  it('leave out a command that cannot act, rather than disabling it', () => {
    programmerDoors()
    const dialog = openPalette()
    expect(item(dialog, 'programmer.blind')).toBeNull()
  })

  it('name what each command acts on, and the key that does the same', () => {
    programmerDoors()
    const dialog = openPalette()
    expect(item(dialog, 'programmer.clear')).toHaveTextContent('6 values · over 1s')
    expect(item(dialog, 'selection.set')).toHaveTextContent('6 cells')
    expect(within(item(dialog, 'selection.set')!).getByText('⏎').tagName).toBe('KBD')
  })

  it('run the door’s handler and close', () => {
    programmerDoors()
    const dialog = openPalette()
    fireEvent.click(item(dialog, 'selection.deselect')!)
    expect(deselect).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('read each offer as the palette opens', () => {
    let hint = '2 heads'
    door('deselect', () => [{ id: 'selection.deselect', group: 'selection', label: 'Deselect', hint, canAct: true, run: deselect }])
    let dialog = openPalette()
    expect(item(dialog, 'selection.deselect')).toHaveTextContent('2 heads')
    cleanup()
    hint = '5 heads'
    dialog = openPalette()
    expect(item(dialog, 'selection.deselect')).toHaveTextContent('5 heads')
  })

  it('find a command by what it acts on', () => {
    programmerDoors()
    const dialog = openPalette()
    fireEvent.change(within(dialog).getByRole('combobox'), { target: { value: 'cells' } })
    expect(item(dialog, 'selection.set')).not.toBeNull()
    expect(item(dialog, 'programmer.clear')).toBeNull()
  })
})

describe('a door’s sub-page', () => {
  const press = vi.fn()
  const page: CommandPage = {
    title: 'Apply a palette',
    footer: '⏎ press as a layer · ⌥⏎ as values',
    empty: 'No palette fits what is selected.',
    sections: [
      {
        heading: 'Fits the selection',
        note: '6 heads · Colour',
        items: [
          { id: 'palette-1', label: 'Amber wash', hint: 'on', swatch: '#f59e0b', press: (asValues) => press('Amber wash', asValues) },
          { id: 'palette-2', label: 'CT blue', press: (asValues) => press('CT blue', asValues) },
        ],
      },
      {
        heading: 'Outside the Colour mask',
        items: [{ id: 'palette-3', label: 'Full', dimmed: 'Intensity — the selection is Colour', press: (asValues) => press('Full', asValues) }],
      },
    ],
  }

  function openPage() {
    door('palette', () => [
      { id: 'selection.applyPalette', group: 'selection', label: 'Apply a palette…', hint: '6 heads · Colour', canAct: true, page: () => page },
    ])
    const dialog = openPalette()
    fireEvent.click(item(dialog, 'selection.applyPalette')!)
    return dialog
  }

  const row = (dialog: HTMLElement, id: string) => dialog.querySelector(`[data-command-page-item="${id}"]`) as HTMLElement

  it('lists what its door built, each section under its heading, a dimmed row with its reason', () => {
    const dialog = openPage()
    expect(headings(dialog)).toEqual(['Fits the selection · 6 heads · Colour', 'Outside the Colour mask'])
    expect(row(dialog, 'palette-1')).toHaveTextContent('Amber wash')
    expect(row(dialog, 'palette-1')).toHaveTextContent('on')
    expect(row(dialog, 'palette-3')).toHaveTextContent('Intensity — the selection is Colour')
    expect(dialog).toHaveTextContent('⏎ press as a layer · ⌥⏎ as values')
  })

  it('presses a row as a layer on ⏎, and as values on ⌥⏎', () => {
    let dialog = openPage()
    const input = within(dialog).getByRole('combobox')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(press).toHaveBeenLastCalledWith('Amber wash', false)
    expect(screen.queryByRole('dialog')).toBeNull()
    cleanup()
    while (unregister.length > 0) unregister.pop()!()

    dialog = openPage()
    fireEvent.keyDown(within(dialog).getByRole('combobox'), { key: 'Enter', altKey: true })
    expect(press).toHaveBeenLastCalledWith('Amber wash', true)
  })

  it('presses as values on ⌥⏎ with the list focused, as a click leaves it, and as a layer on the next ⏎', () => {
    const dialog = openPage()
    const list = dialog.querySelector('[cmdk-list]') as HTMLElement
    fireEvent.keyDown(list, { key: 'Enter', altKey: true })
    expect(press).toHaveBeenLastCalledWith('Amber wash', true)
    cleanup()
    while (unregister.length > 0) unregister.pop()!()

    const again = openPage()
    fireEvent.keyDown(again.querySelector('[cmdk-list]') as HTMLElement, { key: 'Enter' })
    expect(press).toHaveBeenLastCalledWith('Amber wash', false)
  })

  it('says ⏎ once in its footer', () => {
    const dialog = openPage()
    expect(dialog.textContent).not.toMatch(/↵\s*select/)
    expect(dialog).toHaveTextContent('⏎ press as a layer · ⌥⏎ as values')
  })

  it('is left behind when ⌘K closes the palette, so the next open starts at the top', () => {
    const dialog = openPage()
    expect(dialog.querySelector('[data-command-page-item]')).not.toBeNull()
    act(() => {
      fireEvent.keyDown(document, { key: 'k', metaKey: true })
    })
    expect(screen.queryByRole('dialog')).toBeNull()
    act(() => {
      fireEvent.keyDown(document, { key: 'k', metaKey: true })
    })
    const reopened = screen.getByRole('dialog')
    expect(reopened.querySelector('[data-command-page-item]')).toBeNull()
    expect(item(reopened, 'selection.applyPalette')).not.toBeNull()
  })

  it('presses as values on an ⌥-click', () => {
    const dialog = openPage()
    fireEvent.pointerDown(row(dialog, 'palette-2'), { altKey: true })
    fireEvent.click(row(dialog, 'palette-2'))
    expect(press).toHaveBeenLastCalledWith('CT blue', true)
  })

  it('says so when its door has nothing to list', () => {
    door('look', () => [
      {
        id: 'programmer.addLook',
        group: 'programmer',
        label: 'Add a Look…',
        canAct: true,
        page: () => ({ title: 'Add a Look', footer: '', empty: 'No Looks yet. Record one from the programmer.', sections: [{ heading: 'Looks', items: [] }] }),
      },
    ])
    const dialog = openPalette()
    fireEvent.click(item(dialog, 'programmer.addLook')!)
    expect(dialog).toHaveTextContent('No Looks yet. Record one from the programmer.')
    expect(dialog).not.toHaveTextContent('No results found.')
  })
})
