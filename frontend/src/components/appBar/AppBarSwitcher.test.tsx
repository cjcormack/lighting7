// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectSummary } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'

/**
 * The app bar's switcher (navigation plan §4, §8 Session 3): the button names the project on the
 * rig and its hold; the menu orders *On the rig*, Shows, Venues; search and Enter open, ⌥Enter makes
 * current through the one confirmation; a `VENUE_MISSING` show offers no *Make current*; the desk's
 * name folds below 640px; and the confirmation names the incoming show's parks and its hold.
 */

let projects: ProjectSummary[] = []
let hold: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
const setCurrent = vi.fn(() => ({ unwrap: () => Promise.resolve() }))

vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectListQuery: () => ({ data: projects, refetch: vi.fn() }),
  useSetCurrentProjectMutation: () => [setCurrent, { isLoading: false }],
}))
vi.mock('@/store/venueHold', () => ({ useVenueHold: () => hold }))
vi.mock('@/store/rigSafety', async () => {
  const { NOTHING_SAFE } = await import('@/api/rigSafetyApi')
  return { useRigSafety: () => NOTHING_SAFE }
})
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/installs', () => ({ useInstallQuery: () => ({ data: { friendlyName: 'Hall desk' } }) }))
vi.mock('@/navigation', () => ({ useIsNavAdmin: () => true }))
vi.mock('@/CreateProjectDialog', () => ({
  default: ({ open, kind, onCreated }: { open: boolean; kind?: string; onCreated?: (p: { id: number }) => void }) =>
    open ? (
      <div data-testid="create">
        {kind}
        <button type="button" onClick={() => onCreated?.({ id: 99 })}>
          Create it
        </button>
      </div>
    ) : null,
}))

// cmdk measures its list; jsdom has no ResizeObserver.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const { AppBarSwitcher } = await import('./AppBarSwitcher')
const { DeskName } = await import('./DeskName')

const show = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  ...extra,
})

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname}</div>
}

function draw() {
  return render(
    <MemoryRouter initialEntries={['/projects/1/patches']}>
      <AppBarSwitcher />
      <Where />
    </MemoryRouter>,
  )
}

function openMenu() {
  fireEvent.click(document.querySelector('[data-app-switcher]')!)
  return screen.getByRole('dialog')
}

beforeEach(() => {
  setCurrent.mockClear()
  hold = { projectId: 18, held: true, pinned: 7, installed: 8, runAgainst: false }
  projects = [
    show(1, 'Default', { parks: 0 }),
    show(18, 'Sleeping Beauty', {
      isCurrent: true,
      venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed: 8, behind: 'OUTPUT' },
    }),
    show(26, 'TCH Test', { parks: 4, venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed: 8, behind: 'OUTPUT' } }),
    show(16, 'TCH venue', { kind: 'VENUE', venueSummary: { version: 8, shows: 2 } }),
    show(40, 'Old show', { venue: { uuid: 'x', id: null, name: null, version: 3 } }),
  ]
})

describe('the button', () => {
  it('names the project on the rig and its hold', () => {
    draw()
    const button = document.querySelector('[data-app-switcher]')!
    expect(button).toHaveAccessibleName('On the rig: Sleeping Beauty. Switch project')
    expect(button.getAttribute('data-rig-dot')).toBe('held')
    expect(button.textContent).toContain('Sleeping Beauty')
    const chip = document.querySelector('[data-pin-chip]')!
    expect(chip.textContent).toBe('TCH venue v7· held')
    fireEvent.click(chip)
    expect(screen.getByTestId('where').textContent).toBe('/projects/18')
  })

  it('reads green while sending, and tags a venue on the rig', () => {
    hold = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
    projects = projects.map((p) => ({ ...p, isCurrent: p.id === 16 }))
    draw()
    const button = document.querySelector('[data-app-switcher]')!
    expect(button.getAttribute('data-rig-dot')).toBe('sending')
    expect(button.textContent).toContain('Venue')
    expect(document.querySelector('[data-pin-chip]')).toBeNull()
  })
})

describe('the menu', () => {
  it('orders On the rig, then Shows and Venues, each by name', () => {
    draw()
    const menu = openMenu()
    const headings = [...menu.querySelectorAll('[cmdk-group-heading]')].map((h) => h.textContent)
    expect(headings).toEqual(['On the rig', 'Shows', 'Venues'])
    const rows = [...menu.querySelectorAll('[data-switcher-row]')].map((r) => Number(r.getAttribute('data-switcher-row')))
    expect(rows).toEqual([18, 1, 40, 26, 16])
    expect(within(menu).getByText('All projects…')).toBeInTheDocument()
    fireEvent.click(within(menu).getByText('New venue…'))
    expect(screen.getByTestId('create').textContent).toContain('VENUE')
  })

  it('opens the new project’s home once it is created', () => {
    draw()
    fireEvent.click(within(openMenu()).getByText('New show…'))
    fireEvent.click(screen.getByText('Create it'))
    expect(screen.getByTestId('where').textContent).toBe('/projects/99')
  })

  it('leaves Enter on a focused button to that button, not the highlighted row', () => {
    draw()
    const menu = openMenu()
    fireEvent.keyDown(within(menu).getByText('All projects…'), { key: 'Enter' })
    fireEvent.keyDown(within(menu.querySelector('[data-switcher-row="26"]') as HTMLElement).getByText('Make current'), {
      key: 'Enter',
    })
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/patches')
    expect(document.querySelector('[data-switch-confirm]')).toBeNull()
  })

  it('offers make current on every project but the one on the rig, and the reason on a show whose venue is missing', () => {
    draw()
    const menu = openMenu()
    const row = (id: number) => menu.querySelector(`[data-switcher-row="${id}"]`)!
    expect(within(row(18) as HTMLElement).queryByText('Make current')).toBeNull()
    expect(within(row(26) as HTMLElement).getByText('Make current')).toBeInTheDocument()
    expect(within(row(16) as HTMLElement).getByText('Make current')).toBeInTheDocument()
    expect(within(row(40) as HTMLElement).queryByText('Make current')).toBeNull()
    expect(row(40).textContent).toContain('Venue not here')
  })

  it('search and Enter open the first match’s home', () => {
    draw()
    const menu = openMenu()
    const input = within(menu).getByPlaceholderText('Find a show or venue')
    fireEvent.change(input, { target: { value: 'tch' } })
    expect(menu.querySelector('[data-switcher-row="18"]')).toBeNull()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(screen.getByTestId('where').textContent).toBe('/projects/26')
    expect(setCurrent).not.toHaveBeenCalled()
  })

  it('⌥Enter makes the first match current through the confirmation, which names its parks and its hold', async () => {
    draw()
    const menu = openMenu()
    const input = within(menu).getByPlaceholderText('Find a show or venue')
    fireEvent.change(input, { target: { value: 'tch' } })
    fireEvent.keyDown(input, { key: 'Enter', altKey: true })

    const dialog = document.querySelector('[data-switch-confirm]') as HTMLElement
    expect(within(dialog).getByText('Put TCH Test on the rig?')).toBeInTheDocument()
    expect(dialog.textContent).toContain(
      'Sleeping Beauty’s cues, programmer and effects stop, and every channel goes dark except those TCH Test parks (4).',
    )
    expect(dialog.querySelector('[data-switch-line="held"]')!.textContent).toBe(
      'TCH Test is behind TCH venue on output (v7, v8 published). It opens held: only parked channels reach the rig.',
    )
    expect(setCurrent).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Put TCH Test on the rig' }))
    expect(setCurrent).toHaveBeenCalledWith(26)
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/patches')
  })

  it('⌥Enter on a show whose venue is missing makes nothing current', () => {
    draw()
    const menu = openMenu()
    const input = within(menu).getByPlaceholderText('Find a show or venue')
    fireEvent.change(input, { target: { value: 'old' } })
    fireEvent.keyDown(input, { key: 'Enter', altKey: true })
    expect(document.querySelector('[data-switch-confirm]')).toBeNull()
  })
})

describe('the desk name', () => {
  it('is the install’s, hidden below 640px of header', () => {
    render(<DeskName />)
    const name = document.querySelector('[data-desk-name]')!
    expect(name.textContent).toBe('Hall desk')
    // jsdom evaluates no container query: the fold is the class pair.
    expect(name.className.split(' ')).toEqual(expect.arrayContaining(['hidden', '@[640px]:flex']))
  })
})
