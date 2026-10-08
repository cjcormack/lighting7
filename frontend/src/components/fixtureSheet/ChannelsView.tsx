import React, { useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import { Input } from '@/components/ui/input'
import { useChannelValue, useUpdateChannel } from '../../hooks/usePropertyValues'
import { cn } from '@/lib/utils'
import type { Fixture } from '../../store/fixtures'

/**
 * The fixture's raw channels — the sheet's **Channels** view, moved here from `FixtureContent`
 * unchanged (fixture-fx-sheets session 2). It writes raw channels, which the desk lifts into the
 * programmer where a property covers them; the property rows are the Values view's.
 *
 * [span] is the cards page's: how many grid columns the card spans, so a wide card lays its
 * channels out in columns.
 */
export function ChannelsView({
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
