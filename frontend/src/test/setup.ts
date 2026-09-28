import { afterEach } from 'vitest'

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
 */
if (typeof document !== 'undefined') {
  await import('@testing-library/jest-dom/vitest')
  const { cleanup } = await import('@testing-library/react')
  afterEach(cleanup)
  Element.prototype.scrollIntoView ??= () => {}
}
