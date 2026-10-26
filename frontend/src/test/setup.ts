import { afterEach, vi } from 'vitest'

/**
 * Global test setup — loaded for EVERY test file, in whatever environment that file
 * declared, so everything here has to be inert under the default `node` environment.
 *
 * What it buys a component test (`// @vitest-environment jsdom`):
 *  • `afterEach(cleanup)` — testing-library only registers this itself when vitest runs
 *    with `globals: true`, which this repo doesn't. Forgetting it doesn't fail the test
 *    that forgot; it leaves the render mounted so a LATER test's `screen` query matches
 *    the earlier DOM. Silent wrong-passes are worse than a missing teardown, so it is
 *    registered centrally rather than per file.
 *  • jest-dom matchers (`toBeVisible`, `toHaveTextContent`, …) on vitest's `expect`.
 *  • `Element.scrollIntoView`, which jsdom does not implement at all. Six components call it
 *    to keep a selected row or a revealed panel visible; without this, rendering any of them
 *    throws `not a function` from inside an effect, which reads as a component bug rather
 *    than as a missing browser API. A no-op is the right stub: scroll position is not
 *    something these tests can observe anyway.
 *  • Every store a test builds, in either environment, batches its notifications to the end of
 *    the tick (RTK's `autoBatch: { type: 'tick' }`), not to an animation frame raced against a
 *    100 ms timer as the app's store does. RTK Query dispatches a batched action from a 500 ms
 *    timer after a subscription changes, which `resetApiState` does not cancel, so a file can
 *    notify after its tests end. On a frame, the fallback timer can outlive jsdom and call the
 *    `cancelAnimationFrame` teardown deleted; under fake timers, the dispatch starts jsdom's frame
 *    loop on the fake clock, so no frame comes after `useRealTimers`. `storeBatching.test.tsx`
 *    forces both. Only `configureStore` is wrapped, and a caller's own `autoBatch` wins.
 */
vi.mock('@reduxjs/toolkit', async (importOriginal) => {
  const rtk = await importOriginal<typeof import('@reduxjs/toolkit')>()
  const configureStore = (options: Parameters<typeof rtk.configureStore>[0]) =>
    rtk.configureStore({
      ...options,
      enhancers: (getDefaultEnhancers) => {
        const tickBatched: typeof getDefaultEnhancers = (enhancerOptions) =>
          getDefaultEnhancers({
            ...enhancerOptions,
            autoBatch: enhancerOptions?.autoBatch ?? { type: 'tick' },
          })
        return options.enhancers ? options.enhancers(tickBatched) : tickBatched()
      },
    })
  return { ...rtk, configureStore: configureStore as typeof rtk.configureStore }
})

if (typeof document !== 'undefined') {
  await import('@testing-library/jest-dom/vitest')
  const { cleanup } = await import('@testing-library/react')
  afterEach(cleanup)
  // The pointer-focus tracker's listeners live for the module's life, so its record would carry
  // a press from one test into the next.
  const { resetPointerFocus } = await import('../components/sheet/pointerFocus')
  afterEach(resetPointerFocus)
  Element.prototype.scrollIntoView ??= () => {}
}
