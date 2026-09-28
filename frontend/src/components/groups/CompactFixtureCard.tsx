import { memo, useMemo } from 'react'
import {
  useFixtureListQuery,
  useFixtureTypeListQuery,
  findColourSource,
  findCompactPrimary,
  findCompactSecondary,
  findDimmerProperty,
  type Fixture,
  type PropertyDescriptor,
  type ColourPropertyDescriptor,
  type SliderPropertyDescriptor,
  type SettingPropertyDescriptor,
  type ElementDescriptor,
} from '../../store/fixtures'
import type { GroupSliderPropertyDescriptor, GroupColourPropertyDescriptor } from '../../api/groupsApi'
import { useFixtureEffectsQuery } from '@/store/fixtureFx'
import { AudioWaveform } from 'lucide-react'
import {
  useSliderValue,
  useSettingValue,
  useSettingColourPreview,
} from '../../hooks/usePropertyValues'
import { useColourAppearance } from '../../hooks/useColourAppearance'
import { SWATCH_FLOOR } from '@/lib/colourMath'
import { useGroupSliderValues } from '../../hooks/useGroupPropertyValues'
import { useVirtualDimmer, useGroupVirtualDimmer } from '../../hooks/useVirtualDimmer'
import { findGel } from '../../data/gels'
import { GelSwatch, useDimmerBrightness } from '../fixtures/GelSwatch'
import { cn } from '@/lib/utils'

// Fixed height for colour row to ensure consistent card heights
const COLOUR_ROW_HEIGHT = 'h-6'
// Fixed height for dimmer bar row
const DIMMER_ROW_HEIGHT = 'h-[22px]'

/** Desired pixel width per head square (including gap) */
const PX_PER_HEAD = 28
/** Card horizontal padding (p-2 = 8px each side) + border */
const CARD_PADDING = 18

/**
 * Compute a flex-basis (in px) that gives each head square enough room.
 * Used with flex-wrap to push multi-head cards onto their own row in
 * narrow containers while still allowing them to shrink responsively.
 * Returns undefined for non-multi-element fixtures.
 */
function basisForHeads(headCount: number): number | undefined {
  if (headCount <= 1) return undefined
  return headCount * PX_PER_HEAD + CARD_PADDING
}

interface CompactFixtureCardProps {
  fixtureKey: string
  fixtureName: string
  tags: string[]
  onClick: () => void
  /** Pass a pre-resolved fixture to avoid an internal useFixtureListQuery + .find() */
  fixture?: Fixture
}

/**
 * Get the aggregated dimmer property from element group properties
 */
function findGroupDimmerProperty(
  fixture: Fixture
): GroupSliderPropertyDescriptor | undefined {
  return fixture.elementGroupProperties?.find(
    (p) => p.type === 'slider' && p.category === 'dimmer'
  ) as GroupSliderPropertyDescriptor | undefined
}

/**
 * Get the fixture-level colour property (for virtual dimmer)
 */
function findColourProperty(
  properties: Fixture['properties']
): ColourPropertyDescriptor | undefined {
  return properties?.find((p) => p.type === 'colour') as ColourPropertyDescriptor | undefined
}

/**
 * Get the element-group colour property (for virtual dimmer)
 */
function findGroupColourProperty(
  fixture: Fixture
): GroupColourPropertyDescriptor | undefined {
  return fixture.elementGroupProperties?.find(
    (p) => p.type === 'colour'
  ) as GroupColourPropertyDescriptor | undefined
}

/**
 * Check if a fixture or its elements have any colour source
 */
function hasAnyColourSource(fixture: Fixture): boolean {
  // Check fixture-level properties
  if (fixture.properties && findColourSource(fixture.properties)) {
    return true
  }
  // Check element-level properties
  if (fixture.elements) {
    return fixture.elements.some((el) => findColourSource(el.properties))
  }
  return false
}

export const CompactFixtureCard = memo(function CompactFixtureCard({
  fixtureKey,
  fixtureName,
  onClick,
  fixture: fixtureProp,
}: CompactFixtureCardProps) {
  const { data: fixtureList } = useFixtureListQuery(undefined, { skip: !!fixtureProp })
  const fixture = fixtureProp ?? fixtureList?.find((f) => f.key === fixtureKey)
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const fixtureType = useMemo(
    () => (fixture && fixtureTypes ? fixtureTypes.find((t) => t.typeKey === fixture.typeKey) : undefined),
    [fixture, fixtureTypes]
  )
  const { data: effects } = useFixtureEffectsQuery(fixtureKey)
  const allEffects = [...(effects?.direct ?? []), ...(effects?.indirect ?? [])]
  const hasActiveFx = allEffects.length > 0
  const anyRunning = allEffects.some((e) => e.isRunning)

  if (!fixture) {
    return (
      <div
        className="p-2 border rounded cursor-pointer hover:bg-accent/50 transition-colors min-w-[100px] flex-1 max-w-[300px]"
        onClick={onClick}
      >
        <p className="text-sm font-medium truncate">{fixtureName}</p>
        {/* Placeholder for colour row */}
        <div className={cn('mt-1.5', COLOUR_ROW_HEIGHT)} />
        {/* Placeholder for dimmer row */}
        <div className={cn('mt-1.5', DIMMER_ROW_HEIGHT)} />
      </div>
    )
  }

  const dimmerProp = findDimmerProperty(fixture.properties)
  const groupDimmerProp = !dimmerProp ? findGroupDimmerProperty(fixture) : undefined
  const hasRealDimmer = !!dimmerProp || !!groupDimmerProp
  const hasElements = fixture.elements && fixture.elements.length > 0
  const hasColour = hasAnyColourSource(fixture)
  const headBasis = hasElements ? basisForHeads(fixture.elements!.length) : undefined

  // Virtual dimmer: colour but no real dimmer
  const virtualDimmerColourProp = !hasRealDimmer ? findColourProperty(fixture.properties) : undefined
  const virtualDimmerGroupColourProp = !hasRealDimmer && !virtualDimmerColourProp
    ? findGroupColourProperty(fixture) : undefined

  const hasDimmerRow = !!dimmerProp || !!groupDimmerProp || !!virtualDimmerColourProp || !!virtualDimmerGroupColourProp

  // Gel falls back to the colour-row slot only when no real colour source is present —
  // a coloured fixture's own swatch always wins.
  const gel = !hasColour && fixtureType?.gelCompactDisplay && fixture.gelCode
    ? findGel(fixture.gelCode)
    : null

  // Compact display fallbacks for fixtures without colour/dimmer
  const compactPrimary = !hasColour && !gel ? findCompactPrimary(fixture.properties) : undefined
  const compactSecondary = !hasDimmerRow ? findCompactSecondary(fixture.properties) : undefined

  return (
    <div
      className={cn(
        'p-2 border rounded cursor-pointer hover:bg-accent/50 transition-colors min-w-[100px] flex-1',
        !headBasis && 'max-w-[300px]'
      )}
      style={headBasis ? { flexBasis: headBasis } : undefined}
      onClick={onClick}
    >
      {/* Name + FX indicator */}
      <div className="flex items-center gap-1">
        <p className="text-sm font-medium truncate">{fixture.name}</p>
        {hasActiveFx && (
          <AudioWaveform className={cn('size-3 shrink-0', anyRunning ? 'text-primary' : 'text-muted-foreground/50')} />
        )}
      </div>

      {/* Colour indicator(s) - or compact primary fallback - or invisible placeholder */}
      <div className={cn('mt-1.5', COLOUR_ROW_HEIGHT)}>
        {hasColour ? (
          hasElements ? (
            <MultiHeadIndicator elements={fixture.elements!} fixtureDimmer={dimmerProp} />
          ) : (
            <SingleHeadIndicator fixture={fixture} />
          )
        ) : gel ? (
          <GelSwatch gelHex={gel.color} dimmerProp={dimmerProp} className="h-full w-full" />
        ) : compactPrimary ? (
          <CompactPropertyDisplay property={compactPrimary} />
        ) : null}
      </div>

      {/* Dimmer bar - or compact secondary fallback - or invisible placeholder */}
      <div className={cn('mt-1.5', DIMMER_ROW_HEIGHT)}>
        {dimmerProp ? (
          <DimmerBar dimmerProp={dimmerProp} />
        ) : groupDimmerProp ? (
          <GroupDimmerBar property={groupDimmerProp} />
        ) : virtualDimmerColourProp ? (
          <VirtualDimmerBar colourProp={virtualDimmerColourProp} />
        ) : virtualDimmerGroupColourProp ? (
          <GroupVirtualDimmerBar colourProp={virtualDimmerGroupColourProp} />
        ) : compactSecondary ? (
          <CompactPropertyDisplay property={compactSecondary} />
        ) : null}
      </div>
    </div>
  )
})

/**
 * Single fixture colour indicator with dimmer brightness - full width
 */
function SingleHeadIndicator({ fixture }: { fixture: Fixture }) {
  const colourSource = fixture.properties ? findColourSource(fixture.properties) : undefined
  const dimmerProp = findDimmerProperty(fixture.properties)

  if (colourSource?.type === 'colour') {
    return <ColourSquare colourProp={colourSource.property} dimmerProp={dimmerProp} fullWidth />
  }

  if (colourSource?.type === 'setting') {
    return <SettingColourSquare settingProp={colourSource.property} dimmerProp={dimmerProp} fullWidth />
  }

  return null
}

/**
 * Multi-head indicator showing all heads in a row - heads grow to fill space
 */
function MultiHeadIndicator({
  elements,
  fixtureDimmer,
}: {
  elements: ElementDescriptor[]
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  return (
    <div className="flex gap-1 h-full">
      {elements.map((element) => (
        <HeadSquare
          key={element.key}
          element={element}
          fixtureDimmer={fixtureDimmer}
        />
      ))}
    </div>
  )
}

/**
 * Individual head square with colour and dimmer brightness - grows to fill space
 */
function HeadSquare({
  element,
  fixtureDimmer,
}: {
  element: ElementDescriptor
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  const colourSource = findColourSource(element.properties)
  // Use element's own dimmer if it has one, otherwise use fixture dimmer
  const elementDimmer = findDimmerProperty(element.properties)
  const dimmerProp = elementDimmer ?? fixtureDimmer

  if (!colourSource) {
    // No colour source - render empty placeholder to maintain spacing
    return <div className="min-w-3 h-full flex-1 rounded-sm bg-muted border border-border" />
  }

  if (colourSource.type === 'colour') {
    return <ColourSquare colourProp={colourSource.property} dimmerProp={dimmerProp} grow />
  }

  return <SettingColourSquare settingProp={colourSource.property} dimmerProp={dimmerProp} grow />
}

/**
 * Colour square: the perceptual appearance (hue × dimmer × colour magnitude) is
 * baked into the background colour. Outer div holds the border/rounding; inner
 * div carries the colour.
 */
function ColourSquare({
  colourProp,
  dimmerProp,
  fullWidth,
  grow,
}: {
  colourProp: ColourPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  fullWidth?: boolean
  grow?: boolean
}) {
  // Appearance folds the colour magnitude in as a "virtual dimmer", so a
  // dimmerless fixture at r:20 reads as a legible dim colour, not near-black.
  const appearance = useColourAppearance(colourProp, dimmerProp, SWATCH_FLOOR)

  return (
    <div
      className={cn(
        'h-full rounded-sm border border-border overflow-hidden',
        fullWidth ? 'w-full' : grow ? 'min-w-3 flex-1' : 'w-6'
      )}
    >
      <div
        className="w-full h-full"
        style={{ backgroundColor: appearance.appearanceCss }}
      />
    </div>
  )
}

/**
 * Setting-based colour square with brightness
 * Uses a wrapper div for the border so it's not affected by brightness filter
 */
function SettingColourSquare({
  settingProp,
  dimmerProp,
  fullWidth,
  grow,
}: {
  settingProp: SettingPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  fullWidth?: boolean
  grow?: boolean
}) {
  const colourPreview = useSettingColourPreview(settingProp)
  const dimmerValue = useDimmerBrightness(dimmerProp)

  return (
    <div
      className={cn(
        'h-full rounded-sm border border-border overflow-hidden',
        fullWidth ? 'w-full' : grow ? 'min-w-3 flex-1' : 'w-6'
      )}
    >
      <div
        className="w-full h-full"
        style={{
          backgroundColor: colourPreview ?? '#888',
          filter: `brightness(${dimmerValue})`,
        }}
      />
    </div>
  )
}

/**
 * Renders a promoted property on the compact card.
 * Dispatches to the appropriate display based on property type.
 */
function CompactPropertyDisplay({ property }: { property: PropertyDescriptor }) {
  switch (property.type) {
    case 'setting':
      return <CompactSettingLabel settingProp={property} />
    case 'slider':
      return <CompactSliderBar sliderProp={property} />
    default:
      return null
  }
}

/**
 * Compact display for a setting property — shows the current mode name as a label
 */
function CompactSettingLabel({ settingProp }: { settingProp: SettingPropertyDescriptor }) {
  const { option } = useSettingValue(settingProp)

  return (
    <div className="flex items-center h-full">
      <span className="text-xs text-muted-foreground truncate">
        {option?.displayName ?? '—'}
      </span>
    </div>
  )
}

/**
 * Compact display for a slider property — shows a bar with the property's display name
 */
function CompactSliderBar({ sliderProp }: { sliderProp: SliderPropertyDescriptor }) {
  const value = useSliderValue(sliderProp)
  const range = sliderProp.max - sliderProp.min
  const pct = range > 0 ? Math.round(((value - sliderProp.min) / range) * 100) : 0

  return (
    <div className="flex items-center gap-1.5 h-full">
      <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden min-w-[40px]">
        <div
          className={cn(
            'h-full rounded-full transition-all',
            pct > 0 ? 'bg-primary' : 'bg-muted-foreground/30'
          )}
          style={{ width: `${Math.max(pct, 2)}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground tabular-nums w-7 text-right">
        {pct}%
      </span>
    </div>
  )
}

/**
 * Visual dimmer bar indicator
 */
function DimmerBar({ dimmerProp }: { dimmerProp: SliderPropertyDescriptor }) {
  const value = useSliderValue(dimmerProp)
  const pct = Math.round((value / 255) * 100)

  return (
    <div className="flex items-center gap-1.5 h-full">
      <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden min-w-[40px]">
        <div
          className={cn(
            'h-full rounded-full transition-all',
            pct > 0 ? 'bg-primary' : 'bg-muted-foreground/30'
          )}
          style={{ width: `${Math.max(pct, 2)}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground tabular-nums w-7 text-right">
        {pct}%
      </span>
    </div>
  )
}

/**
 * Visual dimmer bar for aggregated element group dimmer (shows range for mixed values)
 */
function GroupDimmerBar({ property }: { property: GroupSliderPropertyDescriptor }) {
  const { min, max, isUniform, displayText } = useGroupSliderValues(property)
  const minPct = Math.round((min / 255) * 100)
  const maxPct = Math.round((max / 255) * 100)

  return (
    <div className="flex items-center gap-1.5 h-full">
      <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden min-w-[40px] relative">
        {isUniform ? (
          <div
            className={cn(
              'h-full rounded-full transition-all',
              minPct > 0 ? 'bg-primary' : 'bg-muted-foreground/30'
            )}
            style={{ width: `${Math.max(minPct, 2)}%` }}
          />
        ) : (
          <div
            className="absolute h-full bg-primary/60 rounded-full transition-all"
            style={{ left: `${minPct}%`, width: `${Math.max(maxPct - minPct, 2)}%` }}
          />
        )}
      </div>
      <span className="text-xs text-muted-foreground tabular-nums w-7 text-right">
        {displayText}
      </span>
    </div>
  )
}

/**
 * Virtual dimmer bar for a single colour property — derives brightness from max(R,G,B)
 */
function VirtualDimmerBar({ colourProp }: { colourProp: ColourPropertyDescriptor }) {
  const { percentage } = useVirtualDimmer(colourProp)

  return (
    <div className="flex items-center gap-1.5 h-full">
      <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden min-w-[40px]">
        <div
          className={cn(
            'h-full rounded-full transition-all',
            percentage > 0 ? 'bg-primary' : 'bg-muted-foreground/30'
          )}
          style={{ width: `${Math.max(percentage, 2)}%` }}
        />
      </div>
      <span className="text-xs text-muted-foreground tabular-nums w-7 text-right">
        {percentage}%
      </span>
    </div>
  )
}

/**
 * Virtual dimmer bar for a group colour property — aggregates max(R,G,B) per member
 */
function GroupVirtualDimmerBar({ colourProp }: { colourProp: GroupColourPropertyDescriptor }) {
  const { min, max, isUniform, displayText } = useGroupVirtualDimmer(colourProp)
  const minPct = Math.round((min / 255) * 100)
  const maxPct = Math.round((max / 255) * 100)

  return (
    <div className="flex items-center gap-1.5 h-full">
      <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden min-w-[40px] relative">
        {isUniform ? (
          <div
            className={cn(
              'h-full rounded-full transition-all',
              minPct > 0 ? 'bg-primary' : 'bg-muted-foreground/30'
            )}
            style={{ width: `${Math.max(minPct, 2)}%` }}
          />
        ) : (
          <div
            className="absolute h-full bg-primary/60 rounded-full transition-all"
            style={{ left: `${minPct}%`, width: `${Math.max(maxPct - minPct, 2)}%` }}
          />
        )}
      </div>
      <span className="text-xs text-muted-foreground tabular-nums w-7 text-right">
        {displayText}
      </span>
    </div>
  )
}

/**
 * Compact card for multi-element fixtures showing aggregate view.
 * Uses a computed min-width based on head count so it naturally
 * wraps to its own row in narrow containers.
 */
interface MultiElementCompactCardProps {
  parentKey: string
  elementCount: number
  onClick: () => void
  /** Pass a pre-resolved fixture to avoid an internal useFixtureListQuery + .find() */
  fixture?: Fixture
}

export const MultiElementCompactCard = memo(function MultiElementCompactCard({
  parentKey,
  elementCount,
  onClick,
  fixture: fixtureProp,
}: MultiElementCompactCardProps) {
  const { data: fixtureList } = useFixtureListQuery(undefined, { skip: !!fixtureProp })
  const fixture = fixtureProp ?? fixtureList?.find((f) => f.key === parentKey)
  const { data: effects } = useFixtureEffectsQuery(parentKey)
  const allEffects = [...(effects?.direct ?? []), ...(effects?.indirect ?? [])]
  const hasActiveFx = allEffects.length > 0
  const anyRunning = allEffects.some((e) => e.isRunning)

  // Check if any elements have colour
  const hasColour = useMemo(() => {
    if (!fixture?.elements) return false
    return fixture.elements.some((el) => findColourSource(el.properties))
  }, [fixture?.elements])

  const actualHeadCount = fixture?.elements?.length ?? elementCount
  const headBasis = basisForHeads(actualHeadCount)

  if (!fixture) {
    return (
      <div
        className="p-2 border rounded cursor-pointer hover:bg-accent/50 transition-colors min-w-[100px] flex-1"
        style={headBasis ? { flexBasis: headBasis } : undefined}
        onClick={onClick}
      >
        <p className="text-sm font-medium truncate">{parentKey}</p>
        {/* Placeholder for colour row */}
        <div className={cn('mt-1.5', COLOUR_ROW_HEIGHT)} />
        {/* Placeholder for dimmer row */}
        <div className={cn('mt-1.5', DIMMER_ROW_HEIGHT)} />
      </div>
    )
  }

  const dimmerProp = findDimmerProperty(fixture.properties)
  const groupDimmerProp = !dimmerProp ? findGroupDimmerProperty(fixture) : undefined
  const hasRealDimmer = !!dimmerProp || !!groupDimmerProp

  // Virtual dimmer: colour but no real dimmer
  const virtualDimmerGroupColourProp = !hasRealDimmer
    ? findGroupColourProperty(fixture) : undefined

  const hasDimmerRow = !!dimmerProp || !!groupDimmerProp || !!virtualDimmerGroupColourProp

  // Compact display fallbacks for fixtures without colour/dimmer
  const compactPrimary = !hasColour ? findCompactPrimary(fixture.properties) : undefined
  const compactSecondary = !hasDimmerRow ? findCompactSecondary(fixture.properties) : undefined

  return (
    <div
      className="p-2 border rounded cursor-pointer hover:bg-accent/50 transition-colors min-w-[100px] flex-1"
      style={headBasis ? { flexBasis: headBasis } : undefined}
      onClick={onClick}
    >
      {/* Name + FX indicator */}
      <div className="flex items-center gap-1">
        <p className="text-sm font-medium truncate">{fixture.name}</p>
        {hasActiveFx && (
          <AudioWaveform className={cn('size-3 shrink-0', anyRunning ? 'text-primary' : 'text-muted-foreground/50')} />
        )}
      </div>

      {/* Head squares - or compact primary fallback - or invisible placeholder */}
      <div className={cn('mt-1.5', COLOUR_ROW_HEIGHT)}>
        {hasColour ? (
          <MultiHeadIndicator
            elements={fixture.elements ?? []}
            fixtureDimmer={dimmerProp}
          />
        ) : compactPrimary ? (
          <CompactPropertyDisplay property={compactPrimary} />
        ) : null}
      </div>

      {/* Dimmer bar - or compact secondary fallback - or invisible placeholder */}
      <div className={cn('mt-1.5', DIMMER_ROW_HEIGHT)}>
        {dimmerProp ? (
          <DimmerBar dimmerProp={dimmerProp} />
        ) : groupDimmerProp ? (
          <GroupDimmerBar property={groupDimmerProp} />
        ) : virtualDimmerGroupColourProp ? (
          <GroupVirtualDimmerBar colourProp={virtualDimmerGroupColourProp} />
        ) : compactSecondary ? (
          <CompactPropertyDisplay property={compactSecondary} />
        ) : null}
      </div>
    </div>
  )
})
