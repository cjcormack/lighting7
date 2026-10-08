import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { lightingApi } from '../api/lightingApi'
import {
  createOverlayChannelSource,
  createProgrammerChannelSource,
  outputChannelSource,
  type ChannelSource,
  type DerivedChannelSource,
} from '../api/channelSource'
import { descriptorsByTarget, type DescriptorsByTarget } from '../lib/programmerChannels'
import { useFixtureLookup } from './useFixtureLookup'
import { useNextGoSourceState } from './useNextGoPreview'
import { StageSceneryContext } from './stageScenery'
import { useLiveScenery } from '../store/scenery'
import type { LiveScenery } from '../api/sceneryApi'
import { sceneryForSource } from '../lib/scenery'
import { useVisSource, type VisSource } from './useVisSource'

/**
 * Which [ChannelSource] the value hooks in this subtree read from.
 *
 * Defaults to the wire, so every surface that doesn't opt in — the fixtures sheet, the busking
 * pads, the fixture sheet — keeps showing real output with no change. Only the stage canvases
 * mount a provider. Same shape as `EditorContext`, which already switches these hooks between live
 * and Look-draft values.
 */
const ChannelSourceContext = createContext<ChannelSource>(outputChannelSource)

export function useChannelSource(): ChannelSource {
  return useContext(ChannelSourceContext)
}

/**
 * Point a subtree at an explicit source.
 *
 * [StageChannelSourceProvider] is what the app mounts; this is the plain version, for tests and
 * for any future surface that already knows which source it wants.
 */
export function ChannelSourceProvider({
  source,
  children,
}: {
  source: ChannelSource
  children: ReactNode
}) {
  return <ChannelSourceContext.Provider value={source}>{children}</ChannelSourceContext.Provider>
}

const EMPTY_DESCRIPTORS: DescriptorsByTarget = new Map()

/**
 * The programmer-backed source, alive only while something needs it.
 *
 * Created in an effect rather than a `useMemo`: the app runs under `StrictMode`, which
 * double-invokes render, and a source built during a discarded render would leak its programmer
 * subscription with nothing left holding a reference to dispose it.
 */
function useProgrammerSource(enabled: boolean): { source: DerivedChannelSource | null; settled: boolean } {
  const { fixtures } = useFixtureLookup()
  const descriptors = useMemo(
    () => (fixtures ? descriptorsByTarget(fixtures) : EMPTY_DESCRIPTORS),
    [fixtures],
  )
  // Read through a ref so the source itself never needs rebuilding when the patch changes —
  // re-creating it would drop and re-add every channel subscription in the stage.
  const descriptorsRef = useRef(descriptors)
  descriptorsRef.current = descriptors

  const [source, setSource] = useState<DerivedChannelSource | null>(null)

  useEffect(() => {
    if (!enabled) return
    const created = createProgrammerChannelSource(
      lightingApi.programmer,
      () => descriptorsRef.current,
    )
    setSource(created)
    return () => {
      created.dispose()
      setSource(null)
    }
  }, [enabled])

  // The fixture list usually arrives *after* the stage mounts, so the first build resolves
  // nothing. Recompute once it lands, and on any later patch change.
  useEffect(() => {
    source?.refresh()
  }, [source, descriptors])

  return { source, settled: !enabled || (source != null && fixtures != null) }
}

/** A resolved source, and whether it holds what it will hold — see [StageChannelSourceProvider]'s `onSettled`. */
interface ResolvedChannelSource {
  source: ChannelSource
  settled: boolean
  /** The scenery that goes with it: the desk's live scenery, or the Next GO preview's. */
  scenery: LiveScenery
}

/**
 * Resolve a [VisSource] to the channel source that renders it.
 *
 * Falls back to output while a derived source is still being built, which costs one frame after a
 * selector flip and nothing at all in steady state. Each case falls back on its *own* source
 * being absent — a single early-out would pin `nextGo` to the wire, since it never builds a
 * programmer source at all.
 */
function useResolvedChannelSource(visSource: VisSource): ResolvedChannelSource {
  const programmer = useProgrammerSource(
    visSource === 'outputProgrammer' || visSource === 'programmer',
  )
  const nextGo = useNextGoSourceState(visSource === 'nextGo')
  // Every source but Next GO draws the stage's own scenery — the desk already counts the
  // programmer's scenery and live Looks in what it resolves — and the two programmer sources lay
  // Blind's staged moves over it (scenery-programmer plan D12), as their channels preview the blind
  // programmer. Output draws live. Memoised, so a frame with nothing staged is the frame itself.
  const live = useLiveScenery()
  const scenery = useMemo(
    () => (visSource === 'nextGo' ? (nextGo.scenery ?? live) : sceneryForSource(live, visSource)),
    [visSource, nextGo.scenery, live],
  )
  const source = useMemo(() => {
    switch (visSource) {
      case 'output':
        return outputChannelSource
      case 'outputProgrammer':
        return programmer.source
          ? createOverlayChannelSource(outputChannelSource, programmer.source)
          : outputChannelSource
      case 'programmer':
        return programmer.source ?? outputChannelSource
      case 'nextGo':
        // Overlaid, not literal: the preview reports only the channels the cue asserts, so
        // everything it is silent about has to show the wire through.
        return nextGo.source
          ? createOverlayChannelSource(outputChannelSource, nextGo.source)
          : outputChannelSource
    }
  }, [visSource, programmer.source, nextGo.source])
  return { source, settled: programmer.settled && nextGo.settled, scenery }
}

/**
 * Point a stage canvas at whichever layer the operator selected — this window's vis source, or
 * [source] when a caller names one (`render_view`'s offscreen render draws the source the desk
 * asked for, and must neither read nor move this window's).
 *
 * [onSettled] is told whether the source holds what it will hold: a derived source falls back to
 * the wire while it is built, which the live canvas shows for a frame and a one-frame render must
 * wait out.
 *
 * Wrap **only the canvas**. The Stage route's docked `StageFixtureControlPanel` is a live editor
 * and has to keep reading real output, so this must not go around a subtree that contains it.
 */
export function StageChannelSourceProvider({
  source: named,
  onSettled,
  children,
}: {
  source?: VisSource
  onSettled?: (settled: boolean) => void
  children: ReactNode
}) {
  const windowSource = useVisSource()
  const { source, settled, scenery } = useResolvedChannelSource(named ?? windowSource)
  useEffect(() => {
    onSettled?.(settled)
  }, [onSettled, settled])
  return (
    <ChannelSourceProvider source={source}>
      <StageSceneryContext.Provider value={scenery}>{children}</StageSceneryContext.Provider>
    </ChannelSourceProvider>
  )
}
