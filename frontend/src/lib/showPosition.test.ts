// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { getShowPosition, resetShowPositionStore, setShowPosition, showViewPath } from './showPosition'
import { navItems } from '../navigation'

function seed(raw: string) {
  sessionStorage.setItem('show.position', raw)
  resetShowPositionStore()
}

beforeEach(() => {
  sessionStorage.clear()
  resetShowPositionStore()
})

describe('showPosition (venue-visit plan S3)', () => {
  it('is empty on a fresh window, and the door goes to bare /show', () => {
    expect(getShowPosition(1)).toBeNull()
    expect(showViewPath(1)).toBe('/projects/1/show')
  })

  it('returns to the stack and the open cue, or the stack alone', () => {
    setShowPosition(1, { stackId: 10, cueId: 42 })
    expect(showViewPath(1)).toBe('/projects/1/show/stacks/10?cue=42')
    setShowPosition(1, { stackId: 10, cueId: null })
    expect(showViewPath(1)).toBe('/projects/1/show/stacks/10')
  })

  it('is kept per project', () => {
    setShowPosition(1, { stackId: 10, cueId: 42 })
    setShowPosition(2, { stackId: 20, cueId: null })
    expect(showViewPath(1)).toBe('/projects/1/show/stacks/10?cue=42')
    expect(showViewPath(2)).toBe('/projects/2/show/stacks/20')
    expect(showViewPath(3)).toBe('/projects/3/show')
  })

  it('forgets a project’s stack when the window goes to the stack list', () => {
    setShowPosition(1, { stackId: 10, cueId: 42 })
    setShowPosition(2, { stackId: 20, cueId: null })
    setShowPosition(1, null)
    expect(showViewPath(1)).toBe('/projects/1/show')
    expect(showViewPath(2)).toBe('/projects/2/show/stacks/20')
  })

  it('lives in this tab’s sessionStorage and survives a reload of it', () => {
    setShowPosition(1, { stackId: 10, cueId: 42 })
    expect(JSON.parse(sessionStorage.getItem('show.position')!)).toEqual({ '1': { stackId: 10, cueId: 42 } })
    expect(localStorage.getItem('show.position')).toBeNull()
    resetShowPositionStore()
    expect(getShowPosition(1)).toEqual({ stackId: 10, cueId: 42 })
  })

  it('reads stored junk as nothing, entry by entry', () => {
    seed('not json')
    expect(showViewPath(1)).toBe('/projects/1/show')
    seed('[1,2]')
    expect(showViewPath(1)).toBe('/projects/1/show')
    seed(JSON.stringify({
      '1': { stackId: 'ten', cueId: 4 },
      '2': { stackId: 20, cueId: 'x' },
      '3': { stackId: -1 },
      x: { stackId: 5 },
      '4': { stackId: 40, cueId: 41 },
    }))
    expect(showViewPath(1)).toBe('/projects/1/show')
    expect(showViewPath(2)).toBe('/projects/2/show/stacks/20')
    expect(showViewPath(3)).toBe('/projects/3/show')
    expect(showViewPath(4)).toBe('/projects/4/show/stacks/40?cue=41')
  })

  it('is what the sidebar’s and ⌘K’s Show entry opens, read at the press', () => {
    const show = navItems.find((item) => item.id === 'program')!
    expect(show.path(1)).toBe('/projects/1/show')
    setShowPosition(1, { stackId: 10, cueId: 42 })
    expect(show.path(1)).toBe('/projects/1/show/stacks/10?cue=42')
  })
})
