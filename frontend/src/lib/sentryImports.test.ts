import { describe, expect, it } from 'vitest'

const SOURCES = import.meta.glob<string>(['/src/**/*.ts', '/src/**/*.tsx'], { query: '?raw', import: 'default', eager: true })

describe('lib/sentry', () => {
  it('is imported by main.tsx alone, so no test can start Sentry', () => {
    const importers = Object.entries(SOURCES)
      .filter(([, text]) => /from\s+['"](?:\.{1,2}\/(?:\.\.\/)*lib|@\/lib|\.)\/sentry['"]/.test(text))
      .map(([path]) => path)
    expect(Object.keys(SOURCES).length).toBeGreaterThan(500)
    expect(importers).toEqual(['/src/main.tsx'])
  })

  it('keeps replay and feedback out of the entry chunk: only lib/sentry imports lib/sentryLazy, and dynamically', () => {
    const importers = Object.entries(SOURCES)
      .filter(([, text]) => /sentryLazy['"]/.test(text))
      .filter(([path]) => !path.endsWith('/sentryImports.test.ts'))
      .map(([path]) => path)
    expect(importers).toEqual(['/src/lib/sentry.ts'])
    const sentry = SOURCES['/src/lib/sentry.ts']
    expect(sentry).toMatch(/import\(\s*['"]\.\/sentryLazy['"]\s*\)/)
    expect(sentry).not.toMatch(/from\s+['"]\.\/sentryLazy['"]/)
  })
})
