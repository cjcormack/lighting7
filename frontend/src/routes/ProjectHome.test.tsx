// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectDetail, ProjectSummary } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'
import type { CueStack } from '@/api/cueStacksApi'
import type { CueHoldsState } from '@/api/cueHoldsApi'
import type { HomeCounts } from '@/navigation'
import type { VenueHistory } from '@/api/venueApi'
import { TooltipProvider } from '@/components/ui/tooltip'

/**
 * A show's home (navigation plan §5.1, §8 Session 4): its sections are the sidebar's for the same
 * project, a held show offers *Review update* and *Run against*, the status block reads the venue,
 * sync and what is live, and off the rig the cards that need it are muted with D4's dot.
 *
 * A venue's home (§5.2, §8 Session 5): its version, its draft — counted as the band counts it — and
 * its sync, an admin's; the shows on it, each opened and made current from the list; and the
 * sidebar's sections for a venue, which have no Library.
 */

let projects: ProjectSummary[] = []
let isAdmin = true
let hold: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
let counts: HomeCounts = {}
let stacks: CueStack[] = []
let blind = false
let looseHolds: CueHoldsState = { projectId: null, cues: [] }
let looseCueList: { id: number; name: string }[] = []
const syncRun = vi.fn()
let venueHistory: VenueHistory | undefined
let listLoaded = true
const syncNowArgs: unknown[][] = []

const detailOf = (p: ProjectSummary): ProjectDetail => ({
  ...p,
  scriptCount: 3,
  lookCount: 0,
  cueCount: 35,
  cueStackCount: 2,
  stageWidthM: null,
  stageDepthM: null,
  stageHeightM: null,
})

vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectListQuery: () => ({ data: listLoaded ? projects : undefined, isLoading: !listLoaded, refetch: vi.fn() }),
  useProjectQuery: (id: number) => {
    const p = projects.find((x) => x.id === id)
    return { data: p ? detailOf(p) : undefined, isLoading: false, error: undefined }
  },
  useDeleteProjectMutation: () => [vi.fn(), { isLoading: false, error: undefined }],
}))
vi.mock('@/store/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/auth')>()),
  useAuthStatusQuery: () => ({ data: { user: { role: isAdmin ? 'ADMIN' : 'OPERATOR' } } }),
}))
vi.mock('@/store/oauthGithub', () => ({ useOAuthReauthState: () => ({ reauthRequired: false }) }))
vi.mock('@/store/venues', () => {
  // History is an admin's (the route answers an operator 403), as the band's hook says.
  const admin = () => isAdmin
  const isVenue = (id: number | undefined) => projects.find((p) => p.id === id)?.kind === 'VENUE'
  return {
    useVenueHistoryQuery: (_id: number, opts?: { skip?: boolean }) => ({ data: opts?.skip ? undefined : venueHistory }),
    useVenueHistory: (id: number | undefined) => ({ data: isVenue(id) && admin() ? venueHistory : undefined }),
    useVenueMode: (id: number | undefined) => ({ isVenue: isVenue(id), canEdit: isVenue(id) && admin() }),
    useDiscardVenueDraftMutation: () => [vi.fn(), { isLoading: false }],
  }
})
vi.mock('@/store/venueHold', () => ({ useVenueHold: () => hold, useRunVenueAgainstMutation: () => [vi.fn(), { isLoading: false }] }))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: looseCueList }) }))
vi.mock('@/store/cueHolds', () => ({ useCueHolds: () => looseHolds }))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: stacks }) }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => blind }))
vi.mock('@/components/projectHome/useHomeCounts', () => ({ useHomeCounts: () => counts }))
vi.mock('@/components/cloudSync/useSyncNow', () => ({
  useSyncNow: (...args: unknown[]) => (syncNowArgs.push(args), {
    config: {
      synced: true,
      repoUrl: 'https://github.com/cjcormack/lighting7-tch-show.git',
      lastSyncedAt: null,
    },
    run: syncRun,
    isSyncing: false,
    disabledReason: null,
    enabled: true,
  }),
}))
vi.mock('@/ProjectSwitchConfirmDialog', () => ({
  default: ({ project }: { project: { name: string } | null }) =>
    project ? <div data-testid="switch-confirm">{project.name}</div> : null,
}))
vi.mock('@/components/venue/VenueHistorySheet', () => ({
  VenueHistorySheet: ({ open }: { open: boolean }) => (open ? <div data-testid="history-sheet" /> : null),
}))
vi.mock('@/components/venue/PublishVenueSheet', () => ({
  PublishVenueSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="publish-sheet" /> : null),
}))
vi.mock('@/components/venue/VenueUpdateSheet', () => ({
  VenueUpdateSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="update-sheet" /> : null),
}))
vi.mock('@/components/venue/AttachVenueSheet', () => ({
  AttachVenueSheet: ({ open, showName }: { open: boolean; showName: string }) => (open ? <div data-testid="attach-sheet">{showName}</div> : null),
}))
vi.mock('@/components/venue/RunAgainstDialog', () => ({
  RunAgainstDialog: ({ open, pinned }: { open: boolean; pinned: number }) => (open ? <div data-testid="run-against">v{pinned}</div> : null),
}))

const { default: ProjectHome } = await import('./ProjectHome')
const { default: ProjectSwitcher } = await import('@/ProjectSwitcher')
const { VenueBand } = await import('@/components/venue/VenueBand')

const show = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  ...extra,
})

const SLEEPING_BEAUTY = show(18, 'Sleeping Beauty', {
  isCurrent: true,
  description: 'TCH, October',
  venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed: 8, behind: 'OUTPUT' },
})
const DEFAULT = show(1, 'Default')
const TCH_TEST = show(26, 'TCH Test', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed: 8, behind: 'OUTPUT' } })
// Pinned to a version the venue here has not got: the version, not the venue, is missing.
const NEXT_YEAR = show(41, 'Next year', { venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 9, installed: 8 } })
const TCH_VENUE = show(16, 'TCH venue', {
  kind: 'VENUE',
  venueSummary: { version: 8, publishedAt: '2026-10-14T12:00:00.000Z', shows: 3, behind: 2 },
})
const HISTORY: VenueHistory = {
  venueId: 16,
  name: 'TCH venue',
  installed: 8,
  draft: { changes: 3, outputChanges: 1 },
  versions: [
    { number: 8, note: 're-patched LX2', publishedAt: '2026-10-14T12:00:00.000Z', snapshotProjectId: 90 },
    { number: 7, note: 'first rig', publishedAt: '2026-10-01T10:00:00.000Z', snapshotProjectId: 80 },
  ],
  shows: [],
}

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>
}

function drawHome(id: number) {
  return render(
    <TooltipProvider>
      <MemoryRouter initialEntries={[`/projects/${id}`]}>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectHome />} />
          <Route path="*" element={null} />
        </Routes>
        <Where />
      </MemoryRouter>
    </TooltipProvider>,
  )
}

beforeEach(() => {
  isAdmin = true
  blind = false
  hold = { projectId: 18, held: true, pinned: 7, installed: 8, runAgainst: false }
  counts = { patches: 60, stacks: 2, cues: 35, looks: 9, palettes: 31, speedMasters: 4, surfaceBindings: 0 }
  stacks = []
  looseHolds = { projectId: null, cues: [] }
  looseCueList = []
  projects = [SLEEPING_BEAUTY, DEFAULT, TCH_VENUE, TCH_TEST, NEXT_YEAR]
  venueHistory = HISTORY
  listLoaded = true
  syncRun.mockClear()
  syncNowArgs.length = 0
})
afterEach(cleanup)

/** `[section, label, offRig]` for every row, in order. */
type Row = [string, string, boolean]

function sidebarRows(id: number): Row[] {
  const { container } = render(
    <TooltipProvider>
      <MemoryRouter initialEntries={[`/projects/${id}`]}>
        <Routes>
          <Route path="/projects/:projectId" element={<ProjectSwitcher collapsed={false} />} />
        </Routes>
      </MemoryRouter>
    </TooltipProvider>,
  )
  const rows: Row[] = []
  let section: string | null = null
  // A child row has no card: the home lists each section's top rows.
  for (const el of container.querySelectorAll('[data-nav-section], button:not([data-nav-child])')) {
    if (el.hasAttribute('data-nav-section')) section = el.getAttribute('data-nav-section')
    else if (section != null && section !== 'desk') rows.push([section, el.textContent ?? '', el.hasAttribute('data-off-rig')])
  }
  cleanup()
  return rows
}

function homeRows(id: number): Row[] {
  drawHome(id)
  const rows: Row[] = []
  for (const section of document.querySelectorAll('[data-home-section]')) {
    for (const card of section.querySelectorAll('[data-home-card]')) {
      const label = card.querySelector('span > span.truncate')!.textContent ?? ''
      rows.push([section.getAttribute('data-home-section')!, label, card.hasAttribute('data-off-rig')])
    }
  }
  cleanup()
  return rows
}

describe('the sections', () => {
  it.each([
    ['on the rig, to an admin', 18, true],
    ['off the rig, to an admin', 1, true],
    ['off the rig, to an operator', 1, false],
  ])('match the sidebar’s for the same project, %s', (_, id, admin) => {
    isAdmin = admin
    const sidebar = sidebarRows(id)
    expect(sidebar.length).toBeGreaterThan(10)
    expect(homeRows(id)).toEqual(sidebar)
  })

  it('carry their counts: Show counts its stacks, Cues the project’s cues', () => {
    drawHome(18)
    const count = (id: string) => document.querySelector(`[data-home-card="${id}"] [data-home-count]`)?.textContent
    expect(count('program')).toBe('2 stacks')
    expect(count('cues')).toBe('35')
    expect(count('patches')).toBe('60')
    expect(count('palettes')).toBe('31')
    expect(count('surfaces')).toBe('0')
    expect(count('programmer')).toBeUndefined()
    fireEvent.click(document.querySelector('[data-home-card="looks"]')!)
    expect(screen.getByTestId('where').textContent).toBe('/projects/18/looks')
  })

  it('off the rig, mute the cards that need it with the hollow dot and the reason', () => {
    drawHome(1)
    const stage = document.querySelector('[data-home-card="stage-view"]')!
    expect(stage.hasAttribute('data-off-rig')).toBe(true)
    expect(stage.querySelector('[data-off-rig-dot]')).not.toBeNull()
    expect(stage.textContent).toContain('Needs the project on the rig')
    expect(document.querySelector('[data-home-card="patches"]')!.hasAttribute('data-off-rig')).toBe(false)
  })
})

describe('the header', () => {
  it('names the show and says it is on the rig, with Settings in its ⋮', () => {
    drawHome(18)
    const header = document.querySelector('[data-project-home-header]') as HTMLElement
    expect(within(header).getByText('Sleeping Beauty')).toBeInTheDocument()
    expect(within(header).getByText('TCH, October')).toBeInTheDocument()
    expect(header.querySelector('[data-on-rig]')!.textContent).toBe('On the rig')
    expect(header.querySelector('[data-make-current]')).toBeNull()
    expect(within(header).queryByText('Configure')).toBeNull()
  })

  it('offers Make current off the rig', () => {
    drawHome(1)
    expect(document.querySelector('[data-make-current]')!.textContent).toBe('Make current')
    expect(document.querySelector('[data-on-rig]')).toBeNull()
  })
})

describe('the status block', () => {
  it('a held show offers Review update and Run against, and Open venue', () => {
    drawHome(18)
    const venue = document.querySelector('[data-status-row="venue"]') as HTMLElement
    expect(venue.querySelector('[data-venue-line]')!.textContent).toBe('On TCH venue v7 · held')
    expect(venue.querySelector('[data-venue-line]')!.getAttribute('data-venue-line')).toBe('red')
    fireEvent.click(within(venue).getByRole('button', { name: 'Run against v7…' }))
    expect(screen.getByTestId('run-against').textContent).toBe('v7')
    fireEvent.click(within(venue).getByRole('button', { name: 'Review update' }))
    expect(screen.getByTestId('update-sheet')).toBeInTheDocument()
    fireEvent.click(within(venue).getByRole('button', { name: 'Open venue' }))
    expect(screen.getByTestId('where').textContent).toBe('/projects/16')
  })

  it('off the rig, behind on output: Review update, no Run against', () => {
    hold = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
    projects = projects.map((p) => (p.id === 18 ? { ...p, isCurrent: false } : p))
    drawHome(18)
    const venue = document.querySelector('[data-status-row="venue"]') as HTMLElement
    expect(venue.querySelector('[data-venue-line]')!.textContent).toBe('On TCH venue v7 · v8 published')
    expect(within(venue).getByRole('button', { name: 'Review update' })).toBeInTheDocument()
    expect(within(venue).queryByRole('button', { name: /Run against/ })).toBeNull()
    expect(document.querySelector('[data-status-row="live"]')).toBeNull()
  })

  it('a standalone show reads No venue, and its Attach to venue… opens the sheet', () => {
    drawHome(1)
    const venue = document.querySelector('[data-status-row="venue"]') as HTMLElement
    expect(venue.textContent).toContain('No venue')
    const attach = within(venue).getByRole('button', { name: 'Attach to venue…' }) as HTMLButtonElement
    expect(attach.disabled).toBe(false)
    expect(screen.queryByTestId('attach-sheet')).toBeNull()
    fireEvent.click(attach)
    expect(screen.getByTestId('attach-sheet').textContent).toBe('Default')
  })

  it('reads sync for an admin, with Sync now, and nothing to an operator', () => {
    drawHome(18)
    const sync = document.querySelector('[data-status-row="sync"]') as HTMLElement
    expect(sync.textContent).toContain('Synced · cjcormack/lighting7-tch-show · never synced')
    fireEvent.click(within(sync).getByRole('button', { name: 'Sync now' }))
    expect(syncRun).toHaveBeenCalled()
    cleanup()
    isAdmin = false
    drawHome(18)
    expect(document.querySelector('[data-status-row="sync"]')).toBeNull()
  })

  it('on the rig, reads the live cue per stack, Blind and the hold', () => {
    blind = true
    stacks = [
      {
        id: 3,
        name: 'Main',
        type: 'STACK',
        activeCueId: 12,
        cues: [{ entryId: 5, id: 12, cueType: 'STANDARD', name: 'Opening', cueNumber: '12' }],
      } as unknown as CueStack,
    ]
    drawHome(18)
    const live = document.querySelector('[data-status-row="live"]') as HTMLElement
    expect(live.querySelector('[data-live-cue="3"]')!.textContent).toBe('Main Q12 · Opening')
    expect(live.textContent).toContain('Blind')
    expect(live.textContent).toContain('Held by its venue')
  })

  it('reads a cue placed twice by its live entry, and the cues a press holds loose', () => {
    stacks = [
      {
        id: 3,
        name: 'Main',
        type: 'STACK',
        activeCueId: 12,
        activeEntryId: 9,
        cues: [
          { entryId: 5, id: 12, cueType: 'STANDARD', name: 'Opening', cueNumber: '12' },
          { entryId: 9, id: 12, cueType: 'STANDARD', name: 'Opening', cueNumber: '40' },
        ],
      } as unknown as CueStack,
    ]
    looseHolds = { projectId: 18, cues: [{ cueId: 70, priority: 1, holds: [{ kind: 'LOOSE', seq: 1 }] }] }
    looseCueList = [{ id: 70, name: 'Storm' }]
    drawHome(18)
    const live = document.querySelector('[data-status-row="live"]') as HTMLElement
    expect(live.querySelector('[data-live-cue="3"]')!.textContent).toBe('Main Q40 · Opening')
    expect(live.querySelector('[data-loose-cue]')!.textContent).toBe('Loose Storm')
  })

  it('says No cue live only when nothing holds a cue', () => {
    drawHome(18)
    expect(document.querySelector('[data-status-row="live"]')!.textContent).toContain('No cue live')
    cleanup()
    looseHolds = { projectId: 18, cues: [{ cueId: 70, priority: 1, holds: [{ kind: 'LOOSE', seq: 1 }] }] }
    looseCueList = [{ id: 70, name: 'Storm' }]
    drawHome(18)
    expect(document.querySelector('[data-status-row="live"]')!.textContent).not.toContain('No cue live')
  })
})

describe('a venue’s home', () => {
  const status = (fact: string) => document.querySelector(`[data-status-row="${fact}"]`) as HTMLElement | null

  it('has no Library section, and its sections are the sidebar’s for the same venue', () => {
    for (const [onRig, admin] of [
      [false, true],
      [false, false],
      [true, true],
    ] as const) {
      isAdmin = admin
      projects = projects.map((p) => (p.id === 16 ? { ...p, isCurrent: onRig } : { ...p, isCurrent: false }))
      const sidebar = sidebarRows(16)
      const home = homeRows(16)
      expect(home).toEqual(sidebar)
      expect(new Set(home.map(([section]) => section))).toEqual(new Set(['run', 'rig', 'project']))
      expect(home.filter(([section]) => section === 'run').map(([, label]) => label)).toEqual(['Programmer', 'Stage'])
    }
  })

  it('reads its version, its draft and its sync to an admin, with History, Publish and Discard', () => {
    drawHome(16)
    expect(document.querySelector('[data-project-home]')!.getAttribute('data-project-home')).toBe('venue')
    expect(status('version')!.querySelector('[data-venue-version]')!.textContent).toBe('v8 · 14 Oct 2026 · “re-patched LX2”')
    expect(status('draft')!.querySelector('[data-venue-draft]')!.textContent).toBe('Draft: 3 changes (1 changes output)')
    expect(status('sync')!.textContent).toContain('Synced · cjcormack/lighting7-tch-show')
    expect(syncNowArgs.at(-1)).toEqual([16, { isVenue: true }])
    expect(within(status('draft')!).getByRole('button', { name: 'Discard' })).toBeInTheDocument()

    fireEvent.click(within(status('version')!).getByRole('button', { name: 'History' }))
    expect(screen.getByTestId('history-sheet')).toBeInTheDocument()
    fireEvent.click(within(status('draft')!).getByRole('button', { name: 'Publish v9…' }))
    expect(screen.getByTestId('publish-sheet')).toBeInTheDocument()
  })

  it('counts the draft as the band does', () => {
    render(
      <MemoryRouter initialEntries={['/projects/16']}>
        <VenueBand projectId={16} />
      </MemoryRouter>,
    )
    const band = screen.getByRole('status', { name: 'Venue mode' }).textContent!
    cleanup()
    for (const draft of [
      { changes: 3, outputChanges: 1 },
      { changes: 1, outputChanges: 0 },
    ]) {
      venueHistory = { ...HISTORY, draft }
      render(
        <MemoryRouter initialEntries={['/projects/16']}>
          <VenueBand projectId={16} />
        </MemoryRouter>,
      )
      const chip = screen.getByRole('status', { name: 'Venue mode' }).textContent!.match(/Draft · (\d+ changes?)/)![1]
      cleanup()
      drawHome(16)
      expect(status('draft')!.textContent).toContain(`Draft: ${chip}`)
      cleanup()
    }
    expect(band).toContain('3 changes')
  })

  it('refuses Publish with nothing to publish, and offers no Discard', () => {
    venueHistory = { ...HISTORY, draft: { changes: 0, outputChanges: 0 } }
    drawHome(16)
    const publish = within(status('draft')!).getByRole('button', { name: 'Publish v9…' }) as HTMLButtonElement
    expect(publish.disabled).toBe(true)
    expect(status('draft')!.querySelector('[data-publish-refusal]')!.textContent).toBe('Nothing to publish')
    expect(within(status('draft')!).queryByRole('button', { name: 'Discard' })).toBeNull()
  })

  it('to an operator: the version from the list, and no draft, Publish, Discard or Sync line', () => {
    isAdmin = false
    drawHome(16)
    expect(status('version')!.textContent).toContain('v8 · 14 Oct 2026')
    expect(within(status('version')!).queryByRole('button', { name: 'History' })).toBeNull()
    expect(status('draft')).toBeNull()
    expect(status('sync')).toBeNull()
    expect(screen.queryByRole('button', { name: /Publish/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Discard' })).toBeNull()
    // The shows are the operator's too.
    expect(document.querySelectorAll('[data-venue-show]')).toHaveLength(3)
  })

  it('lists the shows on it with their pins, and opens one’s home', () => {
    drawHome(16)
    const rows = [...document.querySelectorAll('[data-venue-show]')]
    expect(rows.map((r) => r.getAttribute('data-venue-show'))).toEqual(['41', '18', '26'])
    const held = document.querySelector('[data-venue-show="18"]') as HTMLElement
    expect(held.textContent).toContain('on v7')
    expect(held.querySelector('[data-venue-show-status]')!.textContent).toBe('held')
    expect(within(held).queryByRole('button', { name: 'Make current' })).toBeNull()
    const behind = document.querySelector('[data-venue-show="26"]') as HTMLElement
    expect(behind.querySelector('[data-venue-show-status="output"]')!.textContent).toBe('v8 published')
    fireEvent.click(within(behind).getByRole('button', { name: 'Open' }))
    expect(screen.getByTestId('where').textContent).toBe('/projects/26')
  })

  it('makes a show current through the confirmation', () => {
    drawHome(16)
    const behind = document.querySelector('[data-venue-show="26"]') as HTMLElement
    expect(screen.queryByTestId('switch-confirm')).toBeNull()
    fireEvent.click(within(behind).getByRole('button', { name: 'Make current' }))
    expect(screen.getByTestId('switch-confirm').textContent).toBe('TCH Test')
  })

  it('offers no working Make current for a show whose venue version is not here', () => {
    drawHome(16)
    const missing = document.querySelector('[data-venue-show="41"]') as HTMLElement
    expect(missing.querySelector('[data-venue-show-status="missing"]')!.textContent).toBe('not on this desk')
    expect(within(missing).queryByRole('button', { name: 'Make current' })).toBeNull()
    const refusal = missing.querySelector('[data-venue-show-refusal="make-current"]')!
    expect(refusal.textContent).toBe('Version not here')
    expect(refusal.getAttribute('title')).toBe('Its venue, TCH venue v9, isn’t on this desk')
    fireEvent.click(refusal)
    expect(screen.queryByTestId('switch-confirm')).toBeNull()
  })

  it('says it is reading, not that nothing is published or no show is on it, before the reads land', () => {
    listLoaded = false
    venueHistory = undefined
    drawHome(16)
    expect(status('version')!.querySelector('[data-venue-version]')!.textContent).toBe('Reading…')
    const shows = document.querySelector('[data-venue-shows]') as HTMLElement
    expect(shows.textContent).toContain('Reading…')
    expect(shows.textContent).not.toContain('No show is on this venue')
  })

  it('closes an open Discard when another window empties the draft, and does not reopen it when it refills', () => {
    // A fresh element each time: React skips a rerender of the identical one.
    const tree = () => (
      <TooltipProvider>
        <MemoryRouter initialEntries={['/projects/16']}>
          <Routes>
            <Route path="/projects/:projectId" element={<ProjectHome />} />
          </Routes>
        </MemoryRouter>
      </TooltipProvider>
    )
    const { rerender } = render(tree())
    fireEvent.click(within(status('draft')!).getByRole('button', { name: 'Discard' }))
    expect(screen.getByText('Discard the draft?')).toBeInTheDocument()
    venueHistory = { ...HISTORY, draft: { changes: 0, outputChanges: 0 } }
    rerender(tree())
    expect(screen.queryByText('Discard the draft?')).toBeNull()
    venueHistory = { ...HISTORY, draft: { changes: 1, outputChanges: 0 } }
    rerender(tree())
    expect(screen.queryByText('Discard the draft?')).toBeNull()
  })
})
