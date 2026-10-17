// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectDetail, ProjectSummary } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'
import type { VenueHistory } from '@/api/venueApi'

/**
 * One set of project actions, four places (navigation plan §6, P3; §8 Sessions 4 and 5): the
 * Projects card's ⋮, the home's ⋮ and ⌘K's Project group offer the same set for one project — a
 * venue's too, to an admin and an operator — and a show whose venue version is not on this desk
 * offers no working *Make current* in any of the four, the switcher included. ⌘K's *History* and
 * *Publish vN…* for a viewed venue carry their reasons and open the sheets the band opens.
 */

let projects: ProjectSummary[] = []
let isAdmin = true
const looseHolds = { projectId: null, cues: [] }
const looseCueList: { id: number; name: string }[] = []
let hold: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
let venueHistory: VenueHistory | undefined

const detailOf = (p: ProjectSummary): ProjectDetail => ({
  ...p,
  scriptCount: 0,
  lookCount: 0,
  cueCount: 0,
  cueStackCount: 0,
  stageWidthM: null,
  stageDepthM: null,
  stageHeightM: null,
})

vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectListQuery: () => ({ data: projects, isLoading: false, error: undefined, refetch: vi.fn() }),
  useProjectQuery: (id: number) => {
    const p = projects.find((x) => x.id === id)
    return { data: p ? detailOf(p) : undefined, isLoading: false, error: undefined }
  },
  useCurrentProjectQuery: () => ({ data: projects.find((p) => p.isCurrent), isLoading: false }),
  useSetCurrentProjectMutation: () => [vi.fn(), { isLoading: false }],
  useDeleteProjectMutation: () => [vi.fn(), { isLoading: false, error: undefined }],
}))
vi.mock('@/navigation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/navigation')>()),
  useIsNavAdmin: () => isAdmin,
  useWindowCommands: () => [],
  useUniverseNavItems: () => [],
}))
vi.mock('@/store/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/auth')>()),
  useAuthStatusQuery: () => ({ data: { user: { role: isAdmin ? 'ADMIN' : 'OPERATOR' } } }),
}))
vi.mock('@/store/venues', () => ({
  useVenueHistoryQuery: (_id: number, opts?: { skip?: boolean }) => ({ data: opts?.skip ? undefined : venueHistory }),
  useVenueHistory: () => ({ data: isAdmin ? venueHistory : undefined }),
  useDiscardVenueDraftMutation: () => [vi.fn(), { isLoading: false }],
}))
vi.mock('@/store/cloudSync', () => ({ useCloudSyncConfigsQuery: () => ({ data: {} }) }))
vi.mock('@/store/oauthGithub', () => ({ useOauthGithubIdentityQuery: () => ({ data: undefined }) }))
vi.mock('@/store/venueHold', () => ({ useVenueHold: () => hold, useRunVenueAgainstMutation: () => [vi.fn(), { isLoading: false }] }))
vi.mock('@/store/rigSafety', async () => {
  const { NOTHING_SAFE } = await import('@/api/rigSafetyApi')
  return { useRigSafety: () => NOTHING_SAFE }
})
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/fixtures')>()),
  useVisibleFixtureListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/groups', () => ({ useGroupListQuery: () => ({ data: [] }) }))
vi.mock('@/store/park', () => ({ useGetParkStateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/channelMapping', () => ({ useGetChannelMappingListQuery: () => ({ data: {} }) }))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: looseCueList }) }))
vi.mock('@/store/cueHolds', () => ({ useCueHolds: () => looseHolds }))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('@/components/fx/selectionEffectTarget', () => ({ useWindowSelectionTargets: () => [], effectTargetOf: () => null }))
vi.mock('@/ProjectSwitcher', () => ({ useViewedProject: () => projects.find((p) => p.id === viewed) }))
vi.mock('@/components/projectHome/useHomeCounts', () => ({ useHomeCounts: () => ({}) }))
vi.mock('@/components/cloudSync/useSyncNow', () => ({
  useSyncNow: () => ({ config: undefined, run: vi.fn(), isSyncing: false, disabledReason: null, enabled: true }),
}))
// The dialogs an action opens: a marker naming the dialog and the project.
vi.mock('@/ProjectSwitchConfirmDialog', () => ({
  default: ({ project }: { project: { name: string } | null }) =>
    project ? <div data-testid="switch-confirm">{project.name}</div> : null,
}))
for (const path of ['@/CreateProjectDialog', '@/ImportProjectDialog', '@/CloneProjectDialog', '@/ExportProjectDialog', '@/DeleteProjectConfirmDialog']) {
  vi.doMock(path, () => ({ default: () => null }))
}
vi.mock('@/components/cloudSync/ImportFromRemoteDialog', () => ({ AddRemoteProjectDialog: () => null }))
vi.mock('@/components/venue/ExtractVenueSheet', () => ({ ExtractVenueSheet: () => null }))
vi.mock('@/components/venue/VenueUpdateSheet', () => ({ VenueUpdateSheet: () => null }))
vi.mock('@/components/venue/DetachVenueDialog', () => ({ DetachVenueDialog: () => null }))
vi.mock('@/components/venue/RunAgainstDialog', () => ({ RunAgainstDialog: () => null }))
vi.mock('@/components/venue/VenueHistorySheet', () => ({
  VenueHistorySheet: ({ open }: { open: boolean }) => (open ? <div data-testid="history-sheet" /> : null),
}))
vi.mock('@/components/venue/PublishVenueSheet', () => ({
  PublishVenueSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="publish-sheet" /> : null),
}))

// cmdk measures its list; jsdom has no ResizeObserver.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver
Element.prototype.scrollIntoView ??= () => {}

const { default: Projects } = await import('@/routes/Projects')
const { default: ProjectHome } = await import('@/routes/ProjectHome')
const { default: CommandPalette } = await import('@/components/CommandPalette')
const { AppBarSwitcher } = await import('@/components/appBar/AppBarSwitcher')

let viewed = 0

const show = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  ...extra,
})

const ON_THE_RIG = show(18, 'Sleeping Beauty', { isCurrent: true })
const STANDALONE = show(1, 'Default')
const BEHIND = show(26, 'TCH Test', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed: 8, behind: 'OUTPUT' } })
const MISSING = show(40, 'Old show', { venue: { uuid: 'x', id: null, name: 'Old hall', version: 3 } })
const VENUE = show(16, 'TCH venue', { kind: 'VENUE', venueSummary: { version: 8, shows: 1 } })

beforeEach(() => {
  isAdmin = true
  hold = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
  projects = [ON_THE_RIG, STANDALONE, BEHIND, MISSING, VENUE]
  venueHistory = { venueId: 16, name: 'TCH venue', installed: 8, draft: { changes: 3, outputChanges: 1 }, versions: [] }
})
afterEach(cleanup)

/** Radix's trigger opens on `pointerdown`, not `click`. */
function openMenu(trigger: Element) {
  fireEvent.pointerDown(trigger, { button: 0, ctrlKey: false, pointerType: 'mouse' })
  return screen.getByRole('menu')
}

const offered = (root: ParentNode) =>
  [...root.querySelectorAll('[data-project-action]')].map((el) => el.getAttribute('data-project-action'))

function cardIds(project: ProjectSummary): (string | null)[] {
  render(
    <MemoryRouter>
      <Projects />
    </MemoryRouter>,
  )
  const menu = openMenu(screen.getByRole('button', { name: `${project.name} actions` }))
  const ids = offered(menu)
  cleanup()
  return ids
}

function homeIds(project: ProjectSummary): (string | null)[] {
  drawHome(project)
  const menu = openMenu(screen.getByRole('button', { name: `${project.name} actions` }))
  const ids = offered(menu)
  cleanup()
  return ids
}

function drawHome(project: ProjectSummary) {
  return render(
    <MemoryRouter initialEntries={[`/projects/${project.id}`]}>
      <Routes>
        <Route path="/projects/:projectId" element={<ProjectHome />} />
      </Routes>
    </MemoryRouter>,
  )
}

function openPalette() {
  render(
    <MemoryRouter>
      <CommandPalette />
    </MemoryRouter>,
  )
  act(() => {
    fireEvent.keyDown(document, { key: 'k', metaKey: true })
  })
  return screen.getByRole('dialog')
}

function paletteIds(project: ProjectSummary): (string | null)[] {
  viewed = project.id
  const dialog = openPalette()
  const ids = offered(dialog)
  cleanup()
  return ids
}

describe('the card, the home’s ⋮ and ⌘K offer the same set', () => {
  it.each([
    ['a standalone show', STANDALONE],
    ['a show behind its venue', BEHIND],
    ['a show whose venue is not here', MISSING],
    ['the show on the rig', ON_THE_RIG],
  ])('for %s', (_, project) => {
    const card = cardIds(project)
    expect(card.length).toBeGreaterThan(0)
    expect(homeIds(project)).toEqual(card)
    expect(paletteIds(project)).toEqual(card)
  })

  it('to an operator too', () => {
    isAdmin = false
    const card = cardIds(BEHIND)
    expect(card).not.toContain('export')
    expect(homeIds(BEHIND)).toEqual(card)
    expect(paletteIds(BEHIND)).toEqual(card)
  })

  it.each([
    ['to an admin', true, ['make-current', 'settings', 'clone', 'export', 'history', 'publish', 'delete']],
    ['to an operator', false, ['make-current', 'settings']],
  ])('for a venue, %s', (_, admin, expected) => {
    isAdmin = admin
    const card = cardIds(VENUE)
    expect(card).toEqual(expected)
    expect(homeIds(VENUE)).toEqual(card)
    expect(paletteIds(VENUE)).toEqual(card)
  })
})

describe('⌘K’s History and Publish for a viewed venue', () => {
  const item = (dialog: HTMLElement, id: string) => dialog.querySelector(`[data-project-action="${id}"]`) as HTMLElement

  it('carry their reasons while the history reads, and with nothing to publish', () => {
    venueHistory = undefined
    viewed = VENUE.id
    let dialog = openPalette()
    expect(item(dialog, 'history').getAttribute('aria-disabled')).toBe('true')
    expect(item(dialog, 'history').textContent).toContain('Reading the history…')
    expect(item(dialog, 'publish').textContent).toContain('Reading the draft…')
    cleanup()

    venueHistory = { venueId: 16, name: 'TCH venue', installed: 8, draft: { changes: 0, outputChanges: 0 }, versions: [] }
    dialog = openPalette()
    expect(item(dialog, 'publish').textContent).toContain('Publish v9…')
    expect(item(dialog, 'publish').getAttribute('aria-disabled')).toBe('true')
    expect(item(dialog, 'publish').textContent).toContain('Nothing to publish')
  })

  it('open the sheets the band and the home open', () => {
    viewed = VENUE.id
    fireEvent.click(item(openPalette(), 'history'))
    expect(screen.getByTestId('history-sheet')).toBeInTheDocument()
    cleanup()
    const dialog = openPalette()
    expect(item(dialog, 'publish').getAttribute('aria-disabled')).toBe('false')
    fireEvent.click(item(dialog, 'publish'))
    expect(screen.getByTestId('publish-sheet')).toBeInTheDocument()
  })

  it('are not offered to an operator', () => {
    isAdmin = false
    viewed = VENUE.id
    const dialog = openPalette()
    expect(item(dialog, 'history')).toBeNull()
    expect(item(dialog, 'publish')).toBeNull()
  })
})

describe('a show whose venue version is not here offers no working Make current', () => {
  it('on the Projects card', () => {
    render(
      <MemoryRouter>
        <Projects />
      </MemoryRouter>,
    )
    const menu = openMenu(screen.getByRole('button', { name: 'Old show actions' }))
    const item = menu.querySelector('[data-project-action="make-current"]')!
    expect(item.hasAttribute('data-disabled')).toBe(true)
    expect(item.textContent).toContain('Its venue, Old hall v3, isn’t on this desk')
    fireEvent.click(item)
    expect(screen.queryByTestId('switch-confirm')).toBeNull()
  })

  it('on its home, in the header and the ⋮', () => {
    drawHome(MISSING)
    const button = document.querySelector('[data-make-current]') as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(document.querySelector('[data-make-current-refusal]')!.textContent).toBe(
      'Its venue, Old hall v3, isn’t on this desk, so it can’t be made current.',
    )
    const menu = openMenu(screen.getByRole('button', { name: 'Old show actions' }))
    expect(menu.querySelector('[data-project-action="make-current"]')!.hasAttribute('data-disabled')).toBe(true)
  })

  it('in ⌘K, viewed or listed', () => {
    viewed = MISSING.id
    const dialog = openPalette()
    const item = dialog.querySelector('[data-project-action="make-current"]')!
    expect(item.getAttribute('aria-disabled')).toBe('true')
    expect(within(dialog).queryByText('Make Old show current')).toBeNull()
    cleanup()

    viewed = STANDALONE.id
    const listed = openPalette()
    expect(within(listed).queryByText('Make Old show current')).toBeNull()
    expect(within(listed).getByText('Make TCH Test current')).toBeInTheDocument()
  })

  it('in the switcher', () => {
    render(
      <MemoryRouter>
        <AppBarSwitcher />
      </MemoryRouter>,
    )
    fireEvent.click(document.querySelector('[data-app-switcher]')!)
    const row = document.querySelector('[data-switcher-row="40"]') as HTMLElement
    expect(within(row).queryByRole('button', { name: 'Make current' })).toBeNull()
    expect(row.querySelector('[data-switcher-refusal="make-current"]')!.textContent).toBe('Venue not here')
  })
})

describe('the switcher takes the rig group only', () => {
  it('Open and Make current', () => {
    render(
      <MemoryRouter>
        <AppBarSwitcher />
      </MemoryRouter>,
    )
    fireEvent.click(document.querySelector('[data-app-switcher]')!)
    const row = document.querySelector('[data-switcher-row="26"]') as HTMLElement
    expect(within(row).getAllByRole('button').map((b) => b.textContent)).toEqual(['Open', 'Make current'])
    const onRig = document.querySelector('[data-switcher-row="18"]') as HTMLElement
    expect(within(onRig).getAllByRole('button').map((b) => b.textContent)).toEqual(['Open'])
  })
})
