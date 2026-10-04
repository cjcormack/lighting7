import { describe, expect, it } from 'vitest'

// Read as text through Vite's glob, as `edit/svgPlotRetired.test.ts` reads its sources.
const sources = import.meta.glob(['./FixtureModel.tsx', './useStageData.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const fixtureModel = sources['./FixtureModel.tsx']
const stageData = sources['./useStageData.ts']

/**
 * `FixtureModel` is a scene component, so it renders inside `render_view`'s capture root, which
 * bridges only the channel source into its tree (`CaptureCanvas.tsx`). A library read through a
 * store hook there finds no Redux context and the capture throws. So the two served libraries a
 * fixture is drawn from — lanterns and gels — are read once in `useStageData`, outside the canvas,
 * and passed down as props. This pins both halves, so a hook reaching back into the scene is caught
 * here rather than by the first capture of a rig with a fitted gel.
 */
describe('FixtureModel reads its libraries as props', () => {
  it('reads both sources', () => {
    expect(fixtureModel).toContain('export function FixtureModel')
    expect(stageData).toContain('export function useStageData')
  })

  it('calls neither library hook inside the scene', () => {
    expect(fixtureModel).not.toMatch(/\buseGelIndex\b/)
    expect(fixtureModel).not.toMatch(/\buseLanternIndex\b/)
  })

  it('takes both from the stage data, which reads them outside the canvas', () => {
    expect(stageData).toMatch(/\buseGelIndex\(\)/)
    expect(stageData).toMatch(/\buseLanternIndex\(\)/)
  })
})
