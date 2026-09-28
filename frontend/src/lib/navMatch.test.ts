// @vitest-environment jsdom
// jsdom provides `window`, which `navigation.ts` pulls in transitively via store/universes →
// api/lightingApi (it reads window.location at import time).
import { describe, expect, it } from 'vitest'
import { navItems } from '../navigation'
import { mostSpecificActiveId, pathHasSegment } from './navMatch'

/**
 * The sibling-route collision, pinned rather than described.
 *
 * `/programmer` and `/show` are two live views whose names would prefix each other under a naive
 * `startsWith`, and a `programmer` nav entry was left out of the sidebar once already on exactly
 * that reasoning. These assertions are what make adding it safe.
 */
describe('mostSpecificActiveId', () => {
  const nav = navItems.map(({ id, pathMatch }) => ({ id, pathMatch }))

  it('keeps the programmer and the show apart', () => {
    expect(mostSpecificActiveId(nav, '/projects/7/programmer')).toBe('programmer')
    expect(mostSpecificActiveId(nav, '/projects/7/show')).toBe('program')
  })

  it('keeps the busk view apart from the FX library', () => {
    // Busk was `/fx` until the busking-view plan's session 3, one hyphen from `/fx-library` — the
    // collision this module exists for. `/busk` shares a prefix with nothing, which is half the
    // reason for the rename.
    expect(mostSpecificActiveId(nav, '/projects/7/busk')).toBe('fx')
    expect(mostSpecificActiveId(nav, '/projects/7/fx-library')).toBe('fx-library')
  })

  it('keeps a drilled stack on the show entry', () => {
    expect(mostSpecificActiveId(nav, '/projects/7/show/stacks/5')).toBe('program')
  })

  it('prefers the longest match, so a child tab beats its parent', () => {
    expect(mostSpecificActiveId(nav, '/projects/7/settings')).toBe('project-settings')
    expect(mostSpecificActiveId(nav, '/projects/7/settings/surfaces')).toBe('surfaces')
    // The patch list left the settings page for a route of its own (list-shell-design).
    expect(mostSpecificActiveId(nav, '/projects/7/patches')).toBe('patches')
  })

  it('matches whole segments only', () => {
    // Not `startsWith`: a pathname is `/projects/7/looks`, never `/looks`, and a route merely
    // *containing* another's name must not light it up.
    expect(mostSpecificActiveId([{ id: 'a', pathMatch: '/run' }], '/projects/7/runner')).toBeNull()
    expect(mostSpecificActiveId([{ id: 'a', pathMatch: '/run' }], '/projects/7/run')).toBe('a')
    expect(mostSpecificActiveId([{ id: 'a', pathMatch: '/run' }], '/projects/7/run/x')).toBe('a')
  })

  it('returns null when nothing matches', () => {
    expect(mostSpecificActiveId(nav, '/projects')).toBeNull()
  })
})

/**
 * The FX-vs-FX-Library collision, which `Layout` got wrong for as long as it did this by hand:
 * an unanchored `/\/projects\/\d+\/fx/` fired on the library too, force-opening and disabling the
 * effects overview panel there. That lock is gone and the busking grid has moved to `/busk`, so
 * nothing in the app makes this exact match today — the assertions stay because the *shape* of the
 * mistake is what recurs, and the next hand-rolled match should have something to reuse.
 */
describe('pathHasSegment', () => {
  it('does not let a longer segment match a shorter one', () => {
    expect(pathHasSegment('/projects/7/fx-library', '/fx')).toBe(false)
    expect(pathHasSegment('/projects/7/fx', '/fx')).toBe(true)
    expect(pathHasSegment('/projects/7/fx/anything', '/fx')).toBe(true)
  })

  it('does not match a segment that is only a prefix of the path', () => {
    expect(pathHasSegment('/projects/7/programmer', '/program')).toBe(false)
  })
})
