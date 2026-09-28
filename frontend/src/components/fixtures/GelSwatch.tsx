import type { SliderPropertyDescriptor } from '../../store/fixtures'
import { useSliderValue } from '../../hooks/usePropertyValues'
import { perceptualBrightness, SWATCH_FLOOR } from '@/lib/colourMath'
import { cn } from '@/lib/utils'

/**
 * Maps DMX 0-255 to a perceptual CSS brightness. The eye (and the display) are
 * non-linear, so a linear ramp crushes low levels to near-black even though the
 * real fixture is clearly lit; the gamma curve keeps dim colours legible. The
 * 0.15 floor keeps the swatch faintly visible at zero so the colour stays
 * recognisable when the fixture is dark.
 */
export function useDimmerBrightness(dimmerProp?: SliderPropertyDescriptor): number {
  const value = useSliderValue(
    dimmerProp ?? {
      type: 'slider',
      name: 'dummy',
      displayName: '',
      category: 'dimmer',
      channel: { universe: 0, channelNo: 0 },
      min: 0,
      max: 255,
    }
  )

  if (!dimmerProp) return 1.0
  return perceptualBrightness(value / 255, SWATCH_FLOOR)
}

export function GelSwatch({
  gelHex,
  dimmerProp,
  className,
}: {
  gelHex: string
  dimmerProp?: SliderPropertyDescriptor
  className?: string
}) {
  const dimmerValue = useDimmerBrightness(dimmerProp)

  return (
    <div className={cn('rounded-sm border border-border overflow-hidden', className)}>
      <div
        className="w-full h-full"
        style={{
          backgroundColor: gelHex,
          filter: `brightness(${dimmerValue})`,
        }}
      />
    </div>
  )
}
