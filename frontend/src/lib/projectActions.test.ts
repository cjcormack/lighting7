import { describe, expect, it, vi } from 'vitest'
import type { ProjectSummary } from '../api/projectApi'
import {
  ATTACH_NOT_BUILT,
  menuGroupsOf,
  projectActions,
  type ProjectActionContext,
  type ProjectActionId,
  type ProjectActionPlace,
} from './projectActions'

/**
 * §6's table, pinned per kind, role and current state (navigation plan §8 Session 4): which actions
 * each project offers, in which group, and which are refused and why.
 */

const host = { navigate: vi.fn(), open: vi.fn() }

function ctx(over: Partial<ProjectActionContext> = {}): ProjectActionContext {
  return { place: 'card', isAdmin: true, venueHistory: null, host, ...over }
}

const show = (extra: Partial<ProjectSummary> = {}): ProjectSummary => ({
  id: 26,
  name: 'TCH Test',
  description: null,
  isCurrent: false,
  kind: 'SHOW',
  venue: null,
  ...extra,
})

const onVenue = (installed: number, extra: Partial<ProjectSummary> = {}) =>
  show({ venue: { uuid: 'v', id: 16, name: 'TCH venue', version: 7, installed, behind: installed > 7 ? 'OUTPUT' : 'NONE' }, ...extra })
const missing = show({ id: 40, name: 'Old show', venue: { uuid: 'x', id: null, name: 'Old hall', version: 3 } })
const venue = show({ id: 16, name: 'TCH venue', kind: 'VENUE' })

const visibleIds = (p: ProjectSummary, c: ProjectActionContext) =>
  projectActions(p, c)
    .filter((a) => a.visible)
    .map((a) => a.id)
const actionOf = (p: ProjectSummary, c: ProjectActionContext, id: ProjectActionId) =>
  projectActions(p, c).find((a) => a.id === id)!

describe('who sees what (§6)', () => {
  it('a standalone show, to an admin: make current, settings, clone, export, attach, extract, delete', () => {
    expect(visibleIds(show(), ctx())).toEqual(['make-current', 'settings', 'clone', 'export', 'attach', 'extract', 'delete'])
  })

  it('a standalone show, to an operator: no export and no extract', () => {
    expect(visibleIds(show(), ctx({ isAdmin: false }))).toEqual(['make-current', 'settings', 'clone', 'attach', 'delete'])
  })

  it('a show on a venue it is behind: update to the installed version and detach, no attach or extract', () => {
    const ids = visibleIds(onVenue(8), ctx())
    expect(ids).toEqual(['make-current', 'settings', 'clone', 'export', 'update-venue', 'detach', 'delete'])
    expect(actionOf(onVenue(8), ctx(), 'update-venue').label).toBe('Update venue to v8…')
  })

  it('a show up to date with its venue: detach only', () => {
    expect(visibleIds(onVenue(7), ctx({ isAdmin: false }))).toEqual(['make-current', 'settings', 'clone', 'detach', 'delete'])
  })

  it('a show whose venue version is not here: make current and clone refused with the reason, no venue actions', () => {
    const ids = visibleIds(missing, ctx())
    expect(ids).toEqual(['make-current', 'settings', 'clone', 'export', 'delete'])
    const make = actionOf(missing, ctx(), 'make-current')
    expect(make.enabled).toBe(false)
    expect(make.reason).toBe('Its venue, Old hall v3, isn’t on this desk')
    expect(make.refusalLabel).toBe('Venue not here')
    expect(actionOf(missing, ctx(), 'clone').enabled).toBe(false)
  })

  it('a venue, to an admin: history and publish, clone, export and delete', () => {
    const history = { installed: 8, draft: { changes: 3, outputChanges: 1 } }
    expect(visibleIds(venue, ctx({ venueHistory: history }))).toEqual([
      'make-current',
      'settings',
      'clone',
      'export',
      'history',
      'publish',
      'delete',
    ])
    expect(actionOf(venue, ctx({ venueHistory: history }), 'publish').label).toBe('Publish v9…')
  })

  it('a venue, to an operator: open it, change nothing', () => {
    expect(visibleIds(venue, ctx({ isAdmin: false }))).toEqual(['make-current', 'settings'])
  })

  it('publish is refused with nothing to publish, and while the draft is read', () => {
    const empty = actionOf(venue, ctx({ venueHistory: { installed: 8, draft: { changes: 0, outputChanges: 0 } } }), 'publish')
    expect(empty).toMatchObject({ enabled: false, reason: 'Nothing to publish' })
    expect(actionOf(venue, ctx({ venueHistory: undefined }), 'publish').enabled).toBe(false)
  })

  it('the project on the rig: no make current, and delete refused', () => {
    const current = show({ isCurrent: true })
    expect(visibleIds(current, ctx())).not.toContain('make-current')
    expect(actionOf(current, ctx(), 'delete')).toMatchObject({ visible: true, enabled: false })
    expect(actionOf(show(), ctx(), 'delete').enabled).toBe(true)
  })

  it('attach is a slot until the venue-attach plan builds it', () => {
    expect(actionOf(show(), ctx(), 'attach')).toMatchObject({ visible: true, enabled: false, reason: ATTACH_NOT_BUILT })
  })

  it('scripts is no project action: the Library section has it', () => {
    expect(projectActions(show(), ctx()).map((a) => a.id)).not.toContain('scripts')
  })
})

describe('open', () => {
  it.each<[ProjectActionPlace, boolean]>([
    ['card', false],
    ['home', false],
    ['switcher', true],
    ['palette', true],
  ])('is offered on the %s: %s', (place, offered) => {
    expect(actionOf(show(), ctx({ place }), 'open').visible).toBe(offered)
  })
})

describe('groups and runs', () => {
  it('groups rig, copy, venue, danger, in that order', () => {
    const groups = menuGroupsOf(projectActions(onVenue(8), ctx())).map((g) => g.map((a) => a.id))
    expect(groups).toEqual([['make-current', 'settings'], ['clone', 'export'], ['update-venue', 'detach'], ['delete']])
  })

  it('opens its dialog, or navigates', () => {
    host.open.mockClear()
    host.navigate.mockClear()
    actionOf(show(), ctx(), 'make-current').run()
    expect(host.open).toHaveBeenCalledWith('make-current')
    actionOf(show(), ctx(), 'settings').run()
    expect(host.navigate).toHaveBeenCalledWith('/projects/26/settings')
    actionOf(show(), ctx({ place: 'switcher' }), 'open').run()
    expect(host.navigate).toHaveBeenCalledWith('/projects/26')
  })
})
