import { describe, expect, it } from 'vitest'

/**
 * The add-layer sheet folded into the one picker (apply-model plan §5): the programmer's door in
 * session 5, the cue editor's in session 8. `AddLayerSheet` and its `LayerPicker` are gone, and
 * nothing may bring a host back — a cue's layer timing and masters are its settings popover's.
 */
const sources = import.meta.glob(['/src/**/*.{ts,tsx}', '!/src/**/addLayerSheetRetired.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('the add-layer sheet', () => {
  it('has no file and no host', () => {
    expect(Object.keys(sources).filter((path) => /\/(AddLayerSheet|LayerPicker)(\.test)?\.tsx$/.test(path))).toEqual([])
    const hosts = Object.entries(sources)
      .filter(([, source]) => /from ['"][^'"]*\/(AddLayerSheet|LayerPicker)['"]|mock\(['"][^'"]*\/(AddLayerSheet|LayerPicker)['"]/.test(source))
      .map(([path]) => path)
    expect(hosts).toEqual([])
  })
})
