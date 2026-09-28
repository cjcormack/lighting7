import { memo, useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Lock } from 'lucide-react'
import type {
  PropertyDescriptor,
  SliderPropertyDescriptor,
  ColourPropertyDescriptor,
  PositionPropertyDescriptor,
  SettingPropertyDescriptor,
} from '../../store/fixtures'
import {
  useSliderValue,
  usePositionValue,
  useSettingValue,
  useUpdateChannel,
  useUpdateFixturePosition,
  useUpdateFixtureColour,
} from '../../hooks/usePropertyValues'
import { useColourAppearance } from '../../hooks/useColourAppearance'
import { useVirtualDimmer } from '../../hooks/useVirtualDimmer'
import { useDimmerBrightness } from './GelSwatch'
import { SWATCH_FLOOR } from '@/lib/colourMath'
import { usePropertyParkStatus } from '../../hooks/usePropertyParkStatus'
import { cn } from '@/lib/utils'
import { ColourPickerPopover } from './ColourPickerPopover'
import { ColourChannelSlider } from './ColourChannelSlider'

/** Label metrics for this surface's channel rows; the colour class is appended per channel. */
const CHANNEL_LABEL = 'w-6 text-xs font-medium'

/** Small amber lock icon shown next to parked property names */
function ParkedIndicator() {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Lock className="size-3 text-amber-500 shrink-0" />
      </TooltipTrigger>
      <TooltipContent>Channel(s) parked — value locked</TooltipContent>
    </Tooltip>
  )
}

interface PropertyVisualizerProps {
  property: PropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
  /** Sibling dimmer, so colour/setting swatches can dim by dimmer × colour. */
  dimmerProp?: SliderPropertyDescriptor
}

/**
 * Colour swatch component showing combined RGB/RGBWAUV colour
 */
export const ColourSwatch = memo(function ColourSwatch({
  property,
  fixtureKey,
  isEditing = false,
  dimmerProp,
}: {
  property: ColourPropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
  dimmerProp?: SliderPropertyDescriptor
}) {
  // Appearance (hue × perceptual level) drives the swatch; raw channel values
  // (r/g/b/w/a/uv, combinedCss) still feed the picker so it edits true colour.
  const colour = useColourAppearance(property, dimmerProp, SWATCH_FLOOR)
  const updateColour = useUpdateFixtureColour(property, fixtureKey)
  const { isAnyParked } = usePropertyParkStatus(property)

  const canEdit = isEditing && !isAnyParked
  const hasExtendedChannels = property.whiteChannel || property.amberChannel || property.uvChannel

  const hasActiveUv = colour.uv !== undefined && colour.uv > 0

  // Individual R/G/B sliders in cue mode must go via `updateColour` (one setProperty with
  // `rgbColour`); the backend rejects per-channel setChannel on R/G/B sub-channels.
  const setR = useCallback(
    (v: number) => updateColour(v, colour.g, colour.b, colour.w, colour.a, colour.uv),
    [updateColour, colour.g, colour.b, colour.w, colour.a, colour.uv],
  )
  const setG = useCallback(
    (v: number) => updateColour(colour.r, v, colour.b, colour.w, colour.a, colour.uv),
    [updateColour, colour.r, colour.b, colour.w, colour.a, colour.uv],
  )
  const setB = useCallback(
    (v: number) => updateColour(colour.r, colour.g, v, colour.w, colour.a, colour.uv),
    [updateColour, colour.r, colour.g, colour.w, colour.a, colour.uv],
  )
  // W/A/UV go through `updateColour` too, for the same reason R/G/B do: the programmer
  // holds one entry for the whole colour, so writing a component on its own would have to
  // re-freeze the others anyway.
  const setW = useCallback(
    (v: number) => updateColour(colour.r, colour.g, colour.b, v, colour.a, colour.uv),
    [updateColour, colour.r, colour.g, colour.b, colour.a, colour.uv],
  )
  const setA = useCallback(
    (v: number) => updateColour(colour.r, colour.g, colour.b, colour.w, v, colour.uv),
    [updateColour, colour.r, colour.g, colour.b, colour.w, colour.uv],
  )
  const setUv = useCallback(
    (v: number) => updateColour(colour.r, colour.g, colour.b, colour.w, colour.a, v),
    [updateColour, colour.r, colour.g, colour.b, colour.w, colour.a],
  )

  const swatchElement = (
    <div
      className={cn(
        'w-10 h-10 rounded border border-border shadow-inner',
        hasActiveUv && 'ring-2 ring-purple-500/50',
        isEditing && 'cursor-pointer hover:ring-2 hover:ring-primary/50 transition-shadow'
      )}
      style={{ backgroundColor: colour.appearanceCss }}
      title={isEditing ? 'Click to pick colour' : colour.combinedCss}
    />
  )

  return (
    <div className="py-2">
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium w-20 shrink-0 flex items-center gap-1">
          {property.displayName}
          {isAnyParked && <ParkedIndicator />}
        </span>
        <div className="relative shrink-0">
          {canEdit ? (
            <ColourPickerPopover
              r={colour.r}
              g={colour.g}
              b={colour.b}
              w={colour.w}
              a={colour.a}
              uv={colour.uv}
              combinedCss={colour.combinedCss}
              hasWhiteChannel={!!property.whiteChannel}
              hasAmberChannel={!!property.amberChannel}
              hasUvChannel={!!property.uvChannel}
              onColourChange={updateColour}
              // The picker-only form: the channel bank beside this swatch is the read-out and the
              // fields, so the editor draws neither its own read-out nor its footer here.
              footer={false}
              counts={false}
            >
              {swatchElement}
            </ColourPickerPopover>
          ) : (
            swatchElement
          )}
          {hasActiveUv && (
            <div
              className="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-purple-500 border border-background"
              title={`UV: ${colour.uv}`}
            />
          )}
        </div>
        <div className="flex flex-col gap-0.5 text-xs text-muted-foreground font-mono">
          <span>R:{colour.r} G:{colour.g} B:{colour.b}</span>
          {hasExtendedChannels && (
            <span>
              {colour.w !== undefined && `W:${colour.w} `}
              {colour.a !== undefined && `A:${colour.a} `}
              {colour.uv !== undefined && (
                <span className="text-purple-400">UV:{colour.uv}</span>
              )}
            </span>
          )}
        </div>
      </div>

      {canEdit && (
        <div className="mt-2 space-y-2 pl-[92px]">
          <ColourChannelSlider
            label="R"
            value={colour.r}
            onChange={setR}
            labelClassName={cn(CHANNEL_LABEL, 'text-red-500')}
          />
          <ColourChannelSlider
            label="G"
            value={colour.g}
            onChange={setG}
            labelClassName={cn(CHANNEL_LABEL, 'text-green-500')}
          />
          <ColourChannelSlider
            label="B"
            value={colour.b}
            onChange={setB}
            labelClassName={cn(CHANNEL_LABEL, 'text-blue-500')}
          />
          {property.whiteChannel && (
            <ColourChannelSlider
              label="W"
              value={colour.w ?? 0}
              onChange={setW}
              labelClassName={CHANNEL_LABEL}
            />
          )}
          {property.amberChannel && (
            <ColourChannelSlider
              label="A"
              value={colour.a ?? 0}
              onChange={setA}
              labelClassName={cn(CHANNEL_LABEL, 'text-amber-500')}
            />
          )}
          {property.uvChannel && (
            <ColourChannelSlider
              label="UV"
              value={colour.uv ?? 0}
              onChange={setUv}
              labelClassName={cn(CHANNEL_LABEL, 'text-purple-400')}
            />
          )}
        </div>
      )}
    </div>
  )
})


/**
 * Position indicator showing pan/tilt on a 2D grid
 */
export const PositionIndicator = memo(function PositionIndicator({
  property,
  fixtureKey,
  isEditing = false,
}: {
  property: PositionPropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
}) {
  const position = usePositionValue(property)
  const updatePosition = useUpdateFixturePosition(property, fixtureKey)
  const { isAnyParked } = usePropertyParkStatus(property)

  const canEdit = isEditing && !isAnyParked

  const handleGridClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!canEdit) return

    const rect = e.currentTarget.getBoundingClientRect()
    const x = (e.clientX - rect.left) / rect.width
    const y = (e.clientY - rect.top) / rect.height

    const panValue = Math.round(property.panMin + x * (property.panMax - property.panMin))
    const tiltValue = Math.round(property.tiltMin + y * (property.tiltMax - property.tiltMin))

    updatePosition(
      Math.max(property.panMin, Math.min(property.panMax, panValue)),
      Math.max(property.tiltMin, Math.min(property.tiltMax, tiltValue)),
    )
  }

  return (
    <div className="py-2">
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium w-20 shrink-0 flex items-center gap-1">
          {property.displayName}
          {isAnyParked && <ParkedIndicator />}
        </span>
        <div
          className={cn(
            'w-16 h-16 border rounded relative bg-muted',
            canEdit && 'cursor-crosshair'
          )}
          onClick={handleGridClick}
        >
          {/* Grid lines */}
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="w-full h-px bg-border" />
          </div>
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="h-full w-px bg-border" />
          </div>
          {/* Position dot */}
          <div
            className="absolute w-3 h-3 bg-primary rounded-full -translate-x-1/2 -translate-y-1/2 border border-primary-foreground shadow"
            style={{
              left: `${position.panNormalized * 100}%`,
              top: `${position.tiltNormalized * 100}%`,
            }}
          />
        </div>
        <div className="text-xs text-muted-foreground">
          <div>Pan: {position.pan}</div>
          <div>Tilt: {position.tilt}</div>
        </div>
      </div>

      {canEdit && (
        <div className="mt-2 space-y-2 pl-[92px]">
          <div className="flex items-center gap-2">
            <span className="w-8 text-xs font-medium">Pan</span>
            <Slider
              value={[position.pan]}
              min={property.panMin}
              max={property.panMax}
              step={1}
              onValueChange={([v]) => updatePosition(v, position.tilt)}
              className="flex-1"
            />
            <span className="w-8 text-xs text-right text-muted-foreground">{position.pan}</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="w-8 text-xs font-medium">Tilt</span>
            <Slider
              value={[position.tilt]}
              min={property.tiltMin}
              max={property.tiltMax}
              step={1}
              onValueChange={([v]) => updatePosition(position.pan, v)}
              className="flex-1"
            />
            <span className="w-8 text-xs text-right text-muted-foreground">{position.tilt}</span>
          </div>
        </div>
      )}
    </div>
  )
})

/**
 * Slider property with value display
 */
export const SliderProperty = memo(function SliderProperty({
  property,
  fixtureKey,
  isEditing = false,
}: {
  property: SliderPropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
}) {
  const value = useSliderValue(property)
  const updateChannel = useUpdateChannel()
  const writeTarget = fixtureKey ? { fixtureKey, propertyName: property.name } : undefined
  const { isAnyParked } = usePropertyParkStatus(property)

  const percentage = Math.round(((value - property.min) / (property.max - property.min)) * 100)
  const canEdit = isEditing && !isAnyParked

  return (
    <div className="py-2">
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium w-20 shrink-0 flex items-center gap-1">
          {property.displayName}
          {isAnyParked && <ParkedIndicator />}
        </span>
        {canEdit ? (
          <>
            <Slider
              value={[value]}
              min={property.min}
              max={property.max}
              step={1}
              onValueChange={([v]) => updateChannel(property.channel, v, writeTarget)}
              className="flex-1"
            />
            <span className="w-[4.5rem] text-xs text-right text-muted-foreground whitespace-nowrap">
              {value} ({percentage}%)
            </span>
          </>
        ) : (
          <>
            <div className="flex-1 h-2 bg-muted rounded-full overflow-hidden">
              <div
                className={cn("h-full transition-all", isAnyParked ? "bg-amber-500" : "bg-primary")}
                style={{ width: `${percentage}%` }}
              />
            </div>
            <span className={cn("w-[4.5rem] text-xs text-right whitespace-nowrap", isAnyParked ? "text-amber-600 dark:text-amber-400 font-medium" : "text-muted-foreground")}>
              {value} ({percentage}%)
            </span>
          </>
        )}
      </div>
    </div>
  )
})

/**
 * Setting property with colour-coded dropdown
 */
export const SettingProperty = memo(function SettingProperty({
  property,
  fixtureKey,
  isEditing = false,
  dimmerProp,
}: {
  property: SettingPropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
  dimmerProp?: SliderPropertyDescriptor
}) {
  const { option } = useSettingValue(property)
  const updateChannel = useUpdateChannel()
  const { isAnyParked } = usePropertyParkStatus(property)
  // Dim the current-selection preview by the (perceptual) dimmer; the dropdown
  // choice list stays full-brightness since those are options, not the value.
  const dimmerBrightness = useDimmerBrightness(dimmerProp)

  const canEdit = isEditing && !isAnyParked

  const handleChange = (value: string) => {
    const selectedOption = property.options.find((o) => o.name === value)
    if (selectedOption) {
      updateChannel(
        property.channel,
        selectedOption.level,
        fixtureKey ? { fixtureKey, propertyName: property.name } : undefined,
      )
    }
  }

  return (
    <div className="py-2">
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium w-20 shrink-0 flex items-center gap-1">
          {property.displayName}
          {isAnyParked && <ParkedIndicator />}
        </span>
        {canEdit ? (
          <Select value={option?.name} onValueChange={handleChange}>
            <SelectTrigger className="flex-1 h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {property.options.map((opt) => (
                <SelectItem key={opt.name} value={opt.name}>
                  <div className="flex items-center gap-2">
                    {opt.colourPreview && (
                      <div
                        className="w-4 h-4 rounded border"
                        style={{ backgroundColor: opt.colourPreview }}
                      />
                    )}
                    <span>{opt.displayName}</span>
                  </div>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        ) : (
          <div className="flex items-center gap-2">
            {option?.colourPreview && (
              <div
                className="w-5 h-5 rounded border"
                style={{
                  backgroundColor: option.colourPreview,
                  filter: `brightness(${dimmerBrightness})`,
                }}
              />
            )}
            <Badge variant="secondary" className="font-normal">
              {option?.displayName ?? 'Unknown'}
            </Badge>
          </div>
        )}
      </div>
    </div>
  )
})

/**
 * Property visualizer router - renders appropriate component based on property type
 */
export function PropertyVisualizer({ property, fixtureKey, isEditing = false, dimmerProp }: PropertyVisualizerProps) {
  switch (property.type) {
    case 'colour':
      return <ColourSwatch property={property} fixtureKey={fixtureKey} isEditing={isEditing} dimmerProp={dimmerProp} />
    case 'position':
      return <PositionIndicator property={property} fixtureKey={fixtureKey} isEditing={isEditing} />
    case 'slider':
      return <SliderProperty property={property} fixtureKey={fixtureKey} isEditing={isEditing} />
    case 'setting':
      return (
        <SettingProperty
          property={property}
          fixtureKey={fixtureKey}
          isEditing={isEditing}
          dimmerProp={dimmerProp}
        />
      )
  }
}

/**
 * Virtual dimmer slider for fixtures with colour but no dedicated dimmer.
 * Derives brightness from max(R,G,B) and scales RGB proportionally.
 */
export const VirtualDimmerSlider = memo(function VirtualDimmerSlider({
  colourProp,
  fixtureKey,
  isEditing = false,
  nameExtra,
}: {
  colourProp: ColourPropertyDescriptor
  fixtureKey?: string
  isEditing?: boolean
  nameExtra?: React.ReactNode
}) {
  const { value, percentage, setValue } = useVirtualDimmer(colourProp, fixtureKey)
  const { isAnyParked } = usePropertyParkStatus(colourProp)

  const canEdit = isEditing && !isAnyParked

  return (
    <div className="py-2">
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium w-20 shrink-0 flex items-center gap-1">
          Dimmer{nameExtra}
          {isAnyParked && <ParkedIndicator />}
        </span>
        {canEdit ? (
          <>
            <Slider
              value={[value]}
              min={0}
              max={255}
              step={1}
              onValueChange={([v]) => setValue(v)}
              className="flex-1"
            />
            <span className="w-[4.5rem] text-xs text-right text-muted-foreground whitespace-nowrap">
              {value} ({percentage}%)
            </span>
          </>
        ) : (
          <>
            <div className="flex-1 h-2 bg-muted rounded-full overflow-hidden">
              <div
                className={cn("h-full transition-all", isAnyParked ? "bg-amber-500" : "bg-primary")}
                style={{ width: `${percentage}%` }}
              />
            </div>
            <span className={cn("w-[4.5rem] text-xs text-right whitespace-nowrap", isAnyParked ? "text-amber-600 dark:text-amber-400 font-medium" : "text-muted-foreground")}>
              {value} ({percentage}%)
            </span>
          </>
        )}
      </div>
    </div>
  )
})