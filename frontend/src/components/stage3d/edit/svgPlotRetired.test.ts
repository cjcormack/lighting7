import { describe, expect, it } from 'vitest'

/**
 * Stage-view plan D1, session 5: the SVG plot (`components/stage2d/`) went once editing on the 3D
 * sections had parity. This pins that it stays gone — no file under it, and no module reaching for
 * it by path or by the names it exported — so a revert or a stale import cannot bring the second
 * renderer back unnoticed. Sources are read as text through Vite's glob, as `shortViewport.test.ts`
 * reads them, rather than through `node:fs`.
 */
// The design system's entry too: it re-exports app components, outside the app's own `tsc`, and it
// was where a stale `stage2d/` import survived the deletion.
const sources = import.meta.glob(['/src/**/*.{ts,tsx}', '/design-system/*.ts', '!/src/**/svgPlotRetired.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('the SVG plot is retired', () => {
  it('reads the source tree', () => {
    // A glob that matched nothing would pass every check below vacuously.
    expect(Object.keys(sources).length).toBeGreaterThan(500)
    expect(Object.keys(sources)).toContain('/src/routes/Stage.tsx')
    expect(Object.keys(sources)).toContain('/design-system/index.ts')
  })

  it('has no file left under components/stage2d', () => {
    expect(Object.keys(sources).filter((path) => path.includes('/components/stage2d/'))).toEqual([])
  })

  it('is imported by nothing, by path or by the names it exported', () => {
    const importer = /from\s+['"][^'"]*stage2d\//
    const names = /\b(Stage2DView|Stage2DShapes|Stage2DHud|EditHandles2D|RegionEditHandles2D|RiggingEndpointHandles2D|useSvgMetres|usePlaneDrag|useBodyDrag2D|renderer2d)\b/
    const offenders = Object.entries(sources)
      .filter(([, text]) => importer.test(text) || names.test(text))
      .map(([path]) => path)
    expect(offenders).toEqual([])
  })
})
