// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProjectDetail, ProjectSummary } from '@/api/projectApi'
import type { VenueHoldState } from '@/api/venueHoldApi'
import type { CueStack } from '@/api/cueStacksApi'
import type { CueHoldsState } from '@/api/cueHoldsApi'
import type { HomeCounts } from '@/navigation'
import { TooltipProvider } from '@/components/ui/tooltip'

/**
 * A show's home (navigation plan §5.1, §8 Session 4): its sections are the sidebar's for the same
 * project, a held show offers *Review update* and *Run against*, the status block reads the venue,
 * sync and what is live, and off the rig the cards that need it are muted with D4's dot.
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
  useProjectListQuery: () => ({ data: projects, isLoading: false, refetch: vi.fn() }),
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
vi.mock('@/store/venues', () => ({ useVenueHistoryQuery: () => ({ data: undefined }) }))
vi.mock('@/store/venueHold', () => ({ useVenueHold: () => hold, useRunVenueAgainstMutation: () => [vi.fn(), { isLoading: false }] }))
vi.mock('@/store/cues', () => ({ useProjectCueListQuery: () => ({ data: looseCueList }) }))
vi.mock('@/store/cueHolds', () => ({ useCueHolds: () => looseHolds }))
vi.mock('@/store/cueStacks', () => ({ useProjectCueStackListQuery: () => ({ data: stacks }) }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => blind }))
vi.mock('@/components/projectHome/useHomeCounts', () => ({ useHomeCounts: () => counts }))
vi.mock('@/components/cloudSync/useSyncNow', () => ({
  useSyncNow: () => ({
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
vi.mock('@/ProjectSwitchConfirmDialog', () => ({ default: () => null }))
vi.mock('@/components/projectHome/VenueOverview', () => ({ VenueOverview: () => <div data-testid="venue-overview" /> }))
vi.mock('@/components/venue/VenueUpdateSheet', () => ({
  VenueUpdateSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="update-sheet" /> : null),
}))
vi.mock('@/components/venue/RunAgainstDialog', () => ({
  RunAgainstDialog: ({ open, pinned }: { open: boolean; pinned: number }) => (open ? <div data-testid="run-against">v{pinned}</div> : null),
}))

const { default: ProjectHome } = await import('./ProjectHome')
const { default: ProjectSwitcher } = await import('@/ProjectSwitcher')

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
  projects = [SLEEPING_BEAUTY, DEFAULT, show(16, 'TCH venue', { kind: 'VENUE' })]
  syncRun.mockClear()
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
  for (const el of container.querySelectorAll('[data-nav-section], button')) {
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

  it('carry their counts: Show counts stacks and the project’s cues', () => {
    drawHome(18)
    const count = (id: string) => document.querySelector(`[data-home-card="${id}"] [data-home-count]`)?.textContent
    expect(count('program')).toBe('2 stacks · 35 cues')
    expect(count('patches')).toBe('60')
    expect(count('templates')).toBe('31')
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

  it('a standalone show reads No venue, with the attach slot', () => {
    drawHome(1)
    const venue = document.querySelector('[data-status-row="venue"]') as HTMLElement
    expect(venue.textContent).toContain('No venue')
    expect((within(venue).getByRole('button', { name: 'Attach to venue…' }) as HTMLButtonElement).disabled).toBe(true)
    expect(venue.textContent).toContain('Not available yet')
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
