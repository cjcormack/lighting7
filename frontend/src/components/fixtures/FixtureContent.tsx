import React, { useState, useMemo, useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { ChevronDown, ChevronRight } from 'lucide-react'
import {
  Fixture,
  ElementDescriptor,
  ColourPropertyDescriptor,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
  findColourSource,
  findDimmerProperty,
  useFixtureTypeListQuery,
} from '../../store/fixtures'
import type { GroupPropertyDescriptor, GroupColourPropertyDescriptor } from '../../api/groupsApi'
import { categoriseProperties } from '../../hooks/useTargetProperties'
import { useChannelValue } from '../../hooks/usePropertyValues'
import { useIsDeskConnected } from '../../store/status'
import { useUpdateChannel } from '../../hooks/usePropertyValues'
import { useSettingColourPreview } from '../../hooks/usePropertyValues'
import { useColourAppearance } from '../../hooks/useColourAppearance'
import { PropertyVisualizer, VirtualDimmerSlider } from './PropertyVisualizers'
import { GroupPropertyVisualizer, GroupVirtualDimmerSlider } from './GroupPropertyVisualizers'
import { GroupMembershipSection } from './GroupMembershipSection'
import { FxSection } from '../fx/FxSection'
import { FixtureBoundControlsRow } from '../surfaces/FixtureBoundControlsRow'
import { GelSwatch, useDimmerBrightness } from './GelSwatch'
import { SWATCH_FLOOR } from '@/lib/colourMath'
import { findGel } from '../../data/gels'
import { cn } from '@/lib/utils'

export type FixtureViewMode = 'properties' | 'channels'

interface FixtureContentProps {
  fixture: Fixture
  isEditing: boolean
  onGroupClick?: (groupName: string) => void
  /** Number of grid columns the card spans (affects channel layout) */
  cardSpan?: number
  /** Which view to display - controlled externally */
  viewMode: FixtureViewMode
}

export function FixtureContent({
  fixture,
  isEditing,
  onGroupClick,
  cardSpan = 1,
  viewMode,
}: FixtureContentProps) {
  const hasElements = (fixture.elements?.length ?? 0) > 0
  // Every editable control below writes to the programmer over the WebSocket, and `isEditing`
  // is already the one flag the whole subtree gates `canEdit` on — so narrowing it here makes
  // the entire detail view read-only while the desk is unreachable, without threading a second
  // flag through forty call sites. Same reasoning as the fixtures grid: the writes are
  // fire-and-forget, and the values are read back from the server, so a slider dragged against a
  // dead socket just springs back with nothing said.
  const deskConnected = useIsDeskConnected()
  const canWrite = isEditing && deskConnected

  if (viewMode === 'channels') {
    return <ChannelsView fixture={fixture} span={cardSpan} isEditing={canWrite} />
  }

  return (
    <PropertiesView
      fixture={fixture}
      hasElements={hasElements}
      isEditing={canWrite}
      onGroupClick={onGroupClick}
    />
  )
}

function PropertiesView({
  fixture,
  hasElements,
  isEditing,
  onGroupClick,
}: {
  fixture: Fixture
  hasElements: boolean
  isEditing: boolean
  onGroupClick?: (groupName: string) => void
}) {
  // Group fixture-level properties by category (memoized to avoid re-filtering on every render)
  const {
    colour: colourProps,
    position: positionProps,
    dimmer: dimmerProps,
    slider: otherSliders,
    setting: settingProps,
  } = useMemo(() => categoriseProperties(fixture.properties), [fixture.properties])

  // Categorize element group properties (all-heads virtual properties)
  const egp = useMemo(
    () => fixture.elementGroupProperties ? categoriseProperties(fixture.elementGroupProperties) : null,
    [fixture.elementGroupProperties],
  )

  const hasFixtureProperties =
    fixture.properties && fixture.properties.length > 0
  const hasElementGroupProperties =
    fixture.elementGroupProperties && fixture.elementGroupProperties.length > 0
  const hasAnyProperties = hasFixtureProperties || hasElementGroupProperties

  // Detect virtual dimmer need: has colour but no real dimmer at any level
  const hasRealDimmer = dimmerProps.length > 0 || (egp?.dimmer.length ?? 0) > 0
  const fixtureColourProp = colourProps.find((p) => p.type === 'colour') as ColourPropertyDescriptor | undefined
  const egpColourProp = egp?.colour.find((p) => p.type === 'colour') as GroupColourPropertyDescriptor | undefined
  // Show virtual dimmer for fixture-level colour, or element-group colour if no fixture-level colour
  const virtualDimmerColourProp = !hasRealDimmer ? fixtureColourProp : undefined
  const virtualDimmerGroupColourProp = !hasRealDimmer && !fixtureColourProp ? egpColourProp : undefined
  const hasVirtualDimmer = !!virtualDimmerColourProp || !!virtualDimmerGroupColourProp

  // Gel only falls in when no real colour source exists — coloured fixtures already
  // render their own swatches via PropertyVisualizer.
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const fixtureType = useMemo(
    () => fixtureTypes?.find((t) => t.typeKey === fixture.typeKey),
    [fixtureTypes, fixture.typeKey],
  )
  const dimmerSliderProp = dimmerProps[0] as SliderPropertyDescriptor | undefined
  const gel = !fixtureColourProp && !egpColourProp && fixtureType?.gelCompactDisplay && fixture.gelCode
    ? findGel(fixture.gelCode)
    : null

  return (
    <div className="space-y-4">
      {/* Surface bindings — small chip row for any controls mapped to this fixture */}
      <FixtureBoundControlsRow fixtureKey={fixture.key} />

      {/* Properties - fixture-level and element-group interleaved by category */}
      {(hasAnyProperties || hasVirtualDimmer) && (
        <div className={cn('space-y-1', hasElements && 'max-w-sm')}>
          {/* Colour properties first (most visually prominent) */}
          {colourProps.map((prop) => (
            <PropertyVisualizer
              key={prop.name}
              property={prop}
              fixtureKey={fixture.key}
              isEditing={isEditing}
              dimmerProp={dimmerSliderProp}
            />
          ))}
          {egp?.colour.map((prop) => (
            <AllHeadsProperty key={`egp-${prop.name}`} property={prop} isEditing={isEditing} />
          ))}

          {/* Position properties */}
          {positionProps.map((prop) => (
            <PropertyVisualizer
              key={prop.name}
              property={prop}
              fixtureKey={fixture.key}
              isEditing={isEditing}
            />
          ))}
          {egp?.position.map((prop) => (
            <AllHeadsProperty key={`egp-${prop.name}`} property={prop} isEditing={isEditing} />
          ))}

          {gel && (
            <GelSwatch
              gelHex={gel.color}
              dimmerProp={dimmerSliderProp}
              className="h-6 w-full"
            />
          )}

          {/* Dimmer properties */}
          {dimmerProps.map((prop) => (
            <PropertyVisualizer
              key={prop.name}
              property={prop}
              fixtureKey={fixture.key}
              isEditing={isEditing}
            />
          ))}
          {egp?.dimmer.map((prop) => (
            <AllHeadsProperty key={`egp-${prop.name}`} property={prop} isEditing={isEditing} />
          ))}

          {/* Virtual dimmer (colour but no real dimmer) */}
          {virtualDimmerColourProp && (
            <VirtualDimmerSlider
              colourProp={virtualDimmerColourProp}
              fixtureKey={fixture.key}
              isEditing={isEditing}
              nameExtra={virtualBadge}
            />
          )}
          {virtualDimmerGroupColourProp && (
            <GroupVirtualDimmerSlider
              colourProp={virtualDimmerGroupColourProp}
              isEditing={isEditing}
              nameExtra={virtualBadge}
            />
          )}

          {/* Other slider properties */}
          {otherSliders.map((prop) => (
            <PropertyVisualizer
              key={prop.name}
              property={prop}
              fixtureKey={fixture.key}
              isEditing={isEditing}
            />
          ))}
          {egp?.slider.map((prop) => (
            <AllHeadsProperty key={`egp-${prop.name}`} property={prop} isEditing={isEditing} />
          ))}

          {/* Setting properties */}
          {settingProps.map((prop) => (
            <PropertyVisualizer
              key={prop.name}
              property={prop}
              fixtureKey={fixture.key}
              isEditing={isEditing}
              dimmerProp={dimmerSliderProp}
            />
          ))}
          {egp?.setting.map((prop) => (
            <AllHeadsProperty key={`egp-${prop.name}`} property={prop} isEditing={isEditing} />
          ))}
        </div>
      )}

      {/* Groups section - show which groups this fixture belongs to */}
      {onGroupClick && fixture.groups && fixture.groups.length > 0 && (
        <GroupMembershipSection
          groups={fixture.groups}
          onGroupClick={onGroupClick}
        />
      )}

      {/* Effects section */}
      <FxSection fixture={fixture} />

      {/* Per-head properties */}
      {hasElements && fixture.elements && (
        <>
          {(hasAnyProperties || (fixture.groups && fixture.groups.length > 0)) && (
            <div className="border-t pt-3">
              <h4 className="text-sm font-medium text-muted-foreground mb-2">
                Heads ({fixture.elements.length})
              </h4>
            </div>
          )}
          <ElementsView elements={fixture.elements} isEditing={isEditing} fixtureDimmer={dimmerSliderProp} />
        </>
      )}

      {!hasAnyProperties && !hasElements && (
        <p className="text-sm text-muted-foreground">No properties available</p>
      )}
    </div>
  )
}

const virtualBadge = (
  <Badge
    variant="outline"
    className="ml-1 text-[10px] leading-tight px-1 py-0 text-muted-foreground align-middle"
  >
    Virtual
  </Badge>
)

/** Wrapper that renders a group property with a "Virtual" badge next to the name */
function AllHeadsProperty({
  property,
  isEditing,
}: {
  property: GroupPropertyDescriptor
  isEditing: boolean
}) {
  return (
    <GroupPropertyVisualizer property={property} isEditing={isEditing} nameExtra={virtualBadge} />
  )
}

/** Expandable accordion-style elements view */
function ElementsView({
  elements,
  isEditing,
  fixtureDimmer,
}: {
  elements: ElementDescriptor[]
  isEditing: boolean
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  const [expandedHead, setExpandedHead] = useState<string | null>(null)

  return (
    <div className="space-y-2">
      {/* Aggregate colour preview */}
      <HeadsAggregatePreview elements={elements} fixtureDimmer={fixtureDimmer} />

      {/* Expandable head sections */}
      {elements.map((element) => (
        <div key={element.key} className="border rounded">
          <button
            className="w-full p-2 flex items-center justify-between hover:bg-accent/50 transition-colors"
            onClick={() =>
              setExpandedHead(expandedHead === element.key ? null : element.key)
            }
          >
            <div className="flex items-center gap-2">
              <HeadColourPreview element={element} fixtureDimmer={fixtureDimmer} />
              <span className="text-sm font-medium">{element.displayName}</span>
            </div>
            {expandedHead === element.key ? (
              <ChevronDown className="size-4" />
            ) : (
              <ChevronRight className="size-4" />
            )}
          </button>

          {expandedHead === element.key && (
            <div className="p-3 pt-0 border-t">
              <PropertiesList
                properties={element.properties}
                fixtureKey={element.key}
                isEditing={isEditing}
                fixtureDimmer={fixtureDimmer}
              />
            </div>
          )}
        </div>
      ))}
    </div>
  )
}

/** Shows combined colour swatches for all heads in a row */
function HeadsAggregatePreview({
  elements,
  fixtureDimmer,
}: {
  elements: ElementDescriptor[]
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  return (
    <div className="flex gap-1 mb-3 p-2 bg-muted/50 rounded">
      <span className="text-xs text-muted-foreground mr-2 self-center">Preview:</span>
      {elements.map((element) => (
        <HeadColourPreview key={element.key} element={element} size="md" fixtureDimmer={fixtureDimmer} />
      ))}
    </div>
  )
}

function HeadColourPreview({
  element,
  size = 'sm',
  fixtureDimmer,
}: {
  element: ElementDescriptor
  size?: 'sm' | 'md'
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  const colourSource = findColourSource(element.properties)
  // A head dims by its own dimmer if it has one, otherwise the fixture's.
  const dimmerProp = findDimmerProperty(element.properties) ?? fixtureDimmer

  if (!colourSource) {
    return (
      <div
        className={cn(
          'rounded bg-muted',
          size === 'sm' ? 'w-6 h-6' : 'w-8 h-8'
        )}
        title={element.displayName}
      />
    )
  }

  if (colourSource.type === 'colour') {
    return <HeadColourDot colourProp={colourSource.property} dimmerProp={dimmerProp} title={element.displayName} size={size} />
  }

  return <HeadSettingColourDot settingProp={colourSource.property} dimmerProp={dimmerProp} title={element.displayName} size={size} />
}

function HeadColourDot({
  colourProp,
  dimmerProp,
  title,
  size,
}: {
  colourProp: ColourPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  title: string
  size: 'sm' | 'md'
}) {
  const appearance = useColourAppearance(colourProp, dimmerProp, SWATCH_FLOOR)
  return (
    <div
      className={cn(
        'rounded border',
        size === 'sm' ? 'w-6 h-6' : 'w-8 h-8'
      )}
      style={{ backgroundColor: appearance.appearanceCss }}
      title={title}
    />
  )
}

function HeadSettingColourDot({
  settingProp,
  dimmerProp,
  title,
  size,
}: {
  settingProp: SettingPropertyDescriptor
  dimmerProp?: SliderPropertyDescriptor
  title: string
  size: 'sm' | 'md'
}) {
  const colourPreview = useSettingColourPreview(settingProp)
  const dimmerBrightness = useDimmerBrightness(dimmerProp)
  return (
    <div
      className={cn(
        'rounded border',
        size === 'sm' ? 'w-6 h-6' : 'w-8 h-8'
      )}
      style={{
        backgroundColor: colourPreview ?? 'transparent',
        filter: colourPreview ? `brightness(${dimmerBrightness})` : undefined,
      }}
      title={title}
    />
  )
}

/** Renders properties organized by category */
function PropertiesList({
  properties,
  fixtureKey,
  isEditing,
  fixtureDimmer,
}: {
  properties: ElementDescriptor['properties']
  fixtureKey?: string
  isEditing: boolean
  fixtureDimmer?: SliderPropertyDescriptor
}) {
  if (!properties || properties.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">No properties available</p>
    )
  }

  // Group by category
  const {
    colour: colourProps,
    position: positionProps,
    dimmer: dimmerProps,
    slider: otherSliders,
    setting: settingProps,
  } = categoriseProperties(properties)

  // Virtual dimmer for elements with colour but no dimmer
  const elementColourProp = dimmerProps.length === 0
    ? colourProps.find((p) => p.type === 'colour') as ColourPropertyDescriptor | undefined
    : undefined

  // Colour/setting swatches dim by the head's own dimmer, else the fixture's.
  const elementDimmer = (dimmerProps[0] as SliderPropertyDescriptor | undefined) ?? fixtureDimmer

  return (
    <div className="space-y-1">
      {colourProps.map((prop) => (
        <PropertyVisualizer key={prop.name} property={prop} fixtureKey={fixtureKey} isEditing={isEditing} dimmerProp={elementDimmer} />
      ))}
      {positionProps.map((prop) => (
        <PropertyVisualizer key={prop.name} property={prop} fixtureKey={fixtureKey} isEditing={isEditing} />
      ))}
      {dimmerProps.map((prop) => (
        <PropertyVisualizer key={prop.name} property={prop} fixtureKey={fixtureKey} isEditing={isEditing} />
      ))}
      {elementColourProp && (
        <VirtualDimmerSlider
          colourProp={elementColourProp}
          fixtureKey={fixtureKey}
          isEditing={isEditing}
          nameExtra={virtualBadge}
        />
      )}
      {otherSliders.map((prop) => (
        <PropertyVisualizer key={prop.name} property={prop} fixtureKey={fixtureKey} isEditing={isEditing} />
      ))}
      {settingProps.map((prop) => (
        <PropertyVisualizer key={prop.name} property={prop} fixtureKey={fixtureKey} isEditing={isEditing} dimmerProp={elementDimmer} />
      ))}
    </div>
  )
}

function ChannelsView({
  fixture,
  span,
  isEditing,
}: {
  fixture: Fixture
  span: number
  isEditing: boolean
}) {
  // Max columns based on card span
  const maxColumns = Math.min(span, 3)

  // Split channels into columns for vertical ordering
  const channelCount = fixture.channels.length
  const rowsPerColumn = Math.ceil(channelCount / maxColumns)

  const columns: typeof fixture.channels[] = []
  for (let i = 0; i < maxColumns; i++) {
    columns.push(fixture.channels.slice(i * rowsPerColumn, (i + 1) * rowsPerColumn))
  }

  return (
    <div
      className={cn(
        'grid gap-x-4',
        'grid-cols-1',
        maxColumns >= 2 && 'md:grid-cols-2',
        maxColumns >= 3 && 'xl:grid-cols-3'
      )}
    >
      {columns.map((columnChannels, colIndex) => (
        <div key={colIndex} className="min-w-0">
          {columnChannels.map((channel) => (
            <ChannelSlider
              key={channel.channelNo}
              universe={fixture.universe}
              id={channel.channelNo}
              description={channel.description}
              isEditing={isEditing}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

const ChannelSlider = React.memo(function ChannelSlider({
  universe,
  id,
  description,
  isEditing,
}: {
  universe: number
  id: number
  description?: string
  isEditing: boolean
}) {
  const value = useChannelValue({ universe, channelNo: id })
  const percentage = Math.round((value / 255) * 100)

  const updateChannel = useUpdateChannel()

  const handleSliderChange = useCallback((values: number[]) => {
    if (values[0] !== undefined) {
      updateChannel({ universe, channelNo: id }, values[0])
    }
  }, [updateChannel, universe, id])

  const handleInputChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (event.target.value === '') {
      updateChannel({ universe, channelNo: id }, 0)
      return
    }
    const valueNumber = Number(event.target.value)
    if (isNaN(valueNumber)) return
    const clamped = Math.max(0, Math.min(255, valueNumber))
    updateChannel({ universe, channelNo: id }, clamped)
  }, [updateChannel, universe, id])

  return (
    <div className="py-1.5">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium w-6 shrink-0 text-muted-foreground">
          {id}
        </span>
        <span
          className="text-xs truncate w-16 sm:w-28 min-w-0"
          title={description}
        >
          {description || `Ch ${id}`}
        </span>
        {isEditing ? (
          <>
            <Slider
              className="flex-1 min-w-12 shrink-0"
              value={[value]}
              max={255}
              step={1}
              onValueChange={handleSliderChange}
            />
            <Input
              type="number"
              value={value}
              onChange={handleInputChange}
              min={0}
              max={255}
              className="w-12 sm:w-14 h-7 text-xs px-1 shrink-0"
            />
          </>
        ) : (
          <>
            <div className="flex-1 min-w-12 shrink-0 h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary transition-all"
                style={{ width: `${percentage}%` }}
              />
            </div>
            <span className="w-8 sm:w-10 text-xs text-right text-muted-foreground shrink-0">
              {value}
            </span>
          </>
        )}
      </div>
    </div>
  )
})
