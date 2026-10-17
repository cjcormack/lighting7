import { describe, expect, it } from 'vitest'
import type { CueStack } from '../api/cueStacksApi'
import type { VenueHoldState } from '../api/venueHoldApi'
import type { SyncConfig } from '../store/cloudSync'
import type { ProjectSummary } from '../api/projectApi'
import type { VenueHistory } from '../api/venueApi'
import {
  agoOf,
  draftLineOf,
  liveCuesOf,
  looseCuesOf,
  syncLineOf,
  venueLineOf,
  venueShowsOf,
  venueVersionLineOf,
} from './projectHome'

/** A project home's status lines (navigation plan §5.1, §5.2), in words. */

const idle: VenueHoldState = { projectId: null, held: false, pinned: null, installed: null, runAgainst: false }
const venue = (installed: number, behind: 'NONE' | 'DRAWING' | 'OUTPUT' = installed > 7 ? 'OUTPUT' : 'NONE') => ({
  uuid: 'v',
  id: 16,
  name: 'TCH venue',
  version: 7,
  installed,
  behind,
})

describe('the Venue line', () => {
  it('is null for a standalone show', () => {
    expect(venueLineOf({ id: 26, venue: null }, idle)).toBeNull()
  })

  it('reads the pin and the newer version, amber with the banner’s actions when behind on output', () => {
    const line = venueLineOf({ id: 26, venue: venue(8) }, idle)!
    expect(line.text).toBe('On TCH venue v7 · v8 published')
    expect(line.tone).toBe('amber')
    expect(line.banner?.kind).toBe('output')
  })

  it('is red and held on the rig while the desk holds it', () => {
    const line = venueLineOf({ id: 26, venue: venue(8) }, { projectId: 26, held: true, pinned: 7, installed: 8, runAgainst: false })!
    expect(line.text).toBe('On TCH venue v7 · held')
    expect(line.tone).toBe('red')
    expect(line.banner).toMatchObject({ kind: 'held', pinned: 7, installed: 8 })
  })

  it('is neutral for drawing only, and up to date', () => {
    expect(venueLineOf({ id: 26, venue: venue(8, 'DRAWING') }, idle)).toMatchObject({ tone: 'neutral', banner: { kind: 'drawing' } })
    expect(venueLineOf({ id: 26, venue: venue(7) }, idle)).toEqual({ text: 'On TCH venue v7', tone: 'neutral', banner: null })
  })

  it('is red for a version not on this desk', () => {
    const line = venueLineOf({ id: 40, venue: { uuid: 'x', id: null, name: 'Old hall', version: 3 } }, idle)!
    expect(line).toEqual({ text: 'On Old hall v3 · not on this desk', tone: 'red', banner: null })
  })
})

describe('the Live line', () => {
  const stack = (over: Partial<CueStack>): CueStack =>
    ({
      id: 1,
      name: 'Main',
      loop: false,
      sortOrder: 0,
      type: 'STACK',
      label: null,
      cues: [],
      activeCueId: null,
      nextCueId: null,
      activeEntryId: null,
      nextEntryId: null,
      standbyEntryId: null,
      canEdit: true,
      canDelete: true,
      ...over,
    }) as CueStack

  it('names each stack’s live cue, and skips separators and stopped stacks', () => {
    const cues = [
      { entryId: 5, id: 12, cueType: 'STANDARD', name: 'Opening', cueNumber: '12', sortOrder: 0 },
      { entryId: 6, id: 13, cueType: 'STANDARD', name: 'Storm', cueNumber: null, sortOrder: 1 },
    ] as unknown as CueStack['cues']
    expect(
      liveCuesOf([
        stack({ id: 1, cues, activeCueId: 12 }),
        stack({ id: 2, name: 'Act 2' }),
        stack({ id: 3, type: 'SEPARATOR', activeCueId: 12 }),
        stack({ id: 4, name: 'FX', cues, activeCueId: 13 }),
      ]),
    ).toEqual([
      { stackId: 1, stack: 'Main', cue: 'Q12 · Opening' },
      { stackId: 4, stack: 'FX', cue: 'Storm' },
    ])
  })
})

describe('the Sync line', () => {
  const now = Date.parse('2026-10-16T12:00:00Z')
  const config = (over: Partial<SyncConfig>): SyncConfig => ({
    branch: 'main',
    repoUrl: 'https://github.com/cjcormack/lighting7-tch-show.git',
    synced: true,
    autoSyncEnabled: false,
    autoSyncIntervalMs: null,
    lastSyncedSha: 'abc',
    lastSyncedAt: '2026-10-16T11:58:00Z',
    tokenPresent: false,
    linkedRepos: [],
    ...over,
  })

  it('names the repo and how long ago', () => {
    expect(syncLineOf(config({}), now)).toBe('Synced · cjcormack/lighting7-tch-show · 2 min ago')
    expect(syncLineOf(config({ lastSyncedAt: null }), now)).toBe('Synced · cjcormack/lighting7-tch-show · never synced')
  })

  it('reads Not synced without a repo', () => {
    expect(syncLineOf(config({ repoUrl: null, synced: false }), now)).toBe('Not synced')
    expect(syncLineOf(undefined, now)).toBe('Not synced')
  })

  it('counts minutes, then hours, then the date', () => {
    expect(agoOf('2026-10-16T11:59:40Z', now)).toBe('just now')
    // A sync that landed after the clock last ticked is ahead of it, and still just now.
    expect(agoOf('2026-10-16T12:00:30Z', now)).toBe('just now')
    expect(agoOf('2026-10-16T09:00:00Z', now)).toBe('3 h ago')
    expect(agoOf('2026-10-14T09:00:00Z', now)).toBe(new Date('2026-10-14T09:00:00Z').toLocaleDateString())
  })
})

describe('the loose cues', () => {
  it('names each cue a press holds, and none a stack alone holds', () => {
    const holds = {
      projectId: 1,
      cues: [
        { cueId: 1, priority: 1, holds: [{ kind: 'STACK' as const, stackId: 3, entryId: 5 }] },
        { cueId: 2, priority: 2, holds: [{ kind: 'LOOSE' as const, seq: 1 }] },
        { cueId: 9, priority: 3, holds: [{ kind: 'LOOSE' as const, seq: 2 }] },
      ],
    }
    expect(looseCuesOf(holds, [{ id: 1, name: 'Opening' }, { id: 2, name: 'Storm' }])).toEqual(['Storm', 'a cue'])
  })
})

describe('a venue’s Version line', () => {
  const history: Pick<VenueHistory, 'installed' | 'versions'> = {
    installed: 8,
    versions: [
      { number: 8, note: 're-patched LX2', publishedAt: '2026-10-14T12:00:00.000Z', snapshotProjectId: 90 },
      { number: 7, note: 'first rig', publishedAt: '2026-10-01T10:00:00.000Z', snapshotProjectId: 80 },
    ],
  }

  it('reads the installed version, its date and its note from the history', () => {
    expect(venueVersionLineOf(history, { version: 7 })).toBe('v8 · 14 Oct 2026 · “re-patched LX2”')
  })

  it('reads the version and its date from the list’s summary without the history, which has the note', () => {
    expect(venueVersionLineOf(null, { version: 8, publishedAt: '2026-10-14T12:00:00.000Z' })).toBe('v8 · 14 Oct 2026')
  })

  it('says nothing is published before v1', () => {
    expect(venueVersionLineOf({ installed: null, versions: [] }, null)).toBe('Nothing published yet')
    expect(venueVersionLineOf(null, { version: null })).toBe('Nothing published yet')
    expect(venueVersionLineOf(null, undefined)).toBe('Nothing published yet')
  })
})

describe('a venue’s Draft line', () => {
  it('counts the changes and how many of them change output', () => {
    expect(draftLineOf({ installed: 8, draft: { changes: 3, outputChanges: 1 } })).toEqual({
      text: 'Draft: 3 changes (1 changes output)',
      tone: 'output',
    })
    expect(draftLineOf({ installed: 8, draft: { changes: 5, outputChanges: 2 } }).text).toBe('Draft: 5 changes (2 change output)')
  })

  it('is amber with drawing changes only, and quiet with none', () => {
    expect(draftLineOf({ installed: 8, draft: { changes: 1, outputChanges: 0 } })).toEqual({ text: 'Draft: 1 change', tone: 'drawing' })
    expect(draftLineOf({ installed: 8, draft: { changes: 0, outputChanges: 0 } })).toEqual({ text: 'Draft: no changes', tone: 'none' })
  })

  it('says a draft before v1 is unpublished', () => {
    expect(draftLineOf({ installed: null, draft: { changes: 6, outputChanges: 6 } }).text).toBe(
      'Draft: 6 changes (6 change output), unpublished',
    )
  })
})

describe('the shows on a venue', () => {
  const project = (id: number, name: string, extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
    id,
    name,
    description: null,
    isCurrent: false,
    kind: 'SHOW',
    ...extra,
  })
  const projects = [
    project(26, 'TCH Test', { venue: venue(8) }),
    project(18, 'Sleeping Beauty', { isCurrent: true, venue: venue(8) }),
    project(30, 'Panto', { venue: { ...venue(8), version: 8, behind: 'NONE' } }),
    project(41, 'Next year', { venue: { ...venue(8), version: 9 } }),
    project(1, 'Default'),
    project(50, 'Elsewhere', { venue: { ...venue(3), id: 60, name: 'Annex' } }),
    project(16, 'TCH venue', { kind: 'VENUE' }),
  ]
  const held: VenueHoldState = { projectId: 18, held: true, pinned: 7, installed: 8, runAgainst: false }

  it('lists the shows pinned to it, by name, each with its pin and how it stands', () => {
    expect(venueShowsOf(16, projects, held).map(({ project: _p, ...row }) => row)).toEqual([
      { id: 41, name: 'Next year', version: 9, isCurrent: false, status: 'not on this desk', tone: 'missing' },
      { id: 30, name: 'Panto', version: 8, isCurrent: false, status: null, tone: 'none' },
      { id: 18, name: 'Sleeping Beauty', version: 7, isCurrent: true, status: 'held', tone: 'held' },
      { id: 26, name: 'TCH Test', version: 7, isCurrent: false, status: 'v8 published', tone: 'output' },
    ])
  })

  it('lists nothing before the list loads', () => {
    expect(venueShowsOf(16, undefined, idle)).toEqual([])
  })
})
