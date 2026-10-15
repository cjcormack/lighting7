import { describe, expect, it } from 'vitest'
import { isLiveViewPath } from '@/lib/liveViews'

/**
 * Which routes are live views — where immersive hides the app (busk-chrome plan D7). They also
 * set the sidebar's default until venue-visit plan D4 made the sidebar one state everywhere.
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
describe('the live views', () => {
  it('are the four show surfaces', () => {
    // The four `ShowHeader`'s pill switcher moves between, and the four immersive applies to.
    expect(isLiveViewPath('/projects/6/programmer')).toBe(true)
    expect(isLiveViewPath('/projects/6/show')).toBe(true)
    expect(isLiveViewPath('/projects/6/prompt-book')).toBe(true)
    expect(isLiveViewPath('/projects/6/busk')).toBe(true)
  })

  it('follows a live view into its sub-routes', () => {
    // Drilling into a stack must not bring the app back over an immersive window mid-show.
    expect(isLiveViewPath('/projects/6/show/stacks/3')).toBe(true)
    expect(isLiveViewPath('/projects/6/programmer/fx')).toBe(true)
  })

  it('are nothing else', () => {
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
