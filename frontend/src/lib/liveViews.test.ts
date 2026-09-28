import { describe, expect, it } from 'vitest'
import { isLiveViewPath } from '@/lib/liveViews'

/**
 * The sidebar's default, per route group (space plan D7).
 *
 * `Layout` mounts the whole desk — every panel, the command palette, the DnD provider — so the
 * rule lives in `lib/liveViews.ts` and is pinned here rather than driven through a render: what
 * has to hold is *which routes are live views*, and a full mount would test forty other things to
 * say it. It is a module of its own for the React Refresh reason as well; a `Layout.tsx` that
 * exported a helper beside its component would stop hot-reloading.
 *
 * The matching is `pathHasSegment`, and the near-miss cases below are the ones this tree has
 * actually shipped: `/program` prefixes `/programmer`, and `/fx-library` contains `/fx`.
 */
describe('the sidebar default per route group', () => {
  it('starts collapsed on the four live views', () => {
    // 176px of width, on the four surfaces where it is worth more than a list of destinations —
    // and `ShowHeader`'s pill switcher is how those four move between each other anyway.
    expect(isLiveViewPath('/projects/6/programmer')).toBe(true)
    expect(isLiveViewPath('/projects/6/show')).toBe(true)
    expect(isLiveViewPath('/projects/6/prompt-book')).toBe(true)
    expect(isLiveViewPath('/projects/6/busk')).toBe(true)
  })

  it('follows a live view into its sub-routes', () => {
    // Drilling into a stack must not pop the sidebar back open mid-show.
    expect(isLiveViewPath('/projects/6/show/stacks/3')).toBe(true)
    expect(isLiveViewPath('/projects/6/programmer/fx')).toBe(true)
  })

  it('starts open everywhere else', () => {
    expect(isLiveViewPath('/projects/6/fixtures')).toBe(false)
    expect(isLiveViewPath('/projects/6/templates')).toBe(false)
    expect(isLiveViewPath('/install/diagnostics')).toBe(false)
    expect(isLiveViewPath('/')).toBe(false)
  })

  it('is segment-aware, not a prefix or a substring test', () => {
    // `/showreel` carries `show` as part of a longer segment, which is the shape `pathHasSegment`
    // exists to reject — the same class of collision as `/program` against `/programmer`, which
    // `lib/navMatch.ts` was extracted for. The busk view used to live at `/fx`, one hyphen from
    // `/fx-library`; it is matched on `/busk` now, so these cases pin the *rule* rather than that
    // retired pair.
    expect(isLiveViewPath('/projects/6/showreel')).toBe(false)
    expect(isLiveViewPath('/projects/6/buskers')).toBe(false)
    expect(isLiveViewPath('/projects/6/preshow')).toBe(false)
  })
})
