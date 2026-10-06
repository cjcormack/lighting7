import { useState, type ElementType, type ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Crosshair, Settings2, SlidersHorizontal } from 'lucide-react'
import { SheetBody, SheetHeader } from '@/components/ui/sheet'
import { FixtureContent, FixtureViewMode } from './FixtureContent'
import { FixtureParkButton } from './FixtureParkButton'
import { LocateButton } from './LocateButton'
import type { Fixture } from '../../store/fixtures'

interface FixtureDetailViewProps {
  fixture: Fixture | null | undefined
  /** When provided, forces this edit state and hides the Edit button. */
  isEditing?: boolean
  /** Element used to render the fixture name — pass `SheetTitle` inside a Sheet
   *  (for Dialog a11y), or leave as a plain heading when docked inline. */
  titleComponent?: ElementType<{ className?: string; children?: ReactNode }>
  /**
   * A **Focus** view beside Properties and Channels — the Stage view's Focus tab (stage-view plan
   * session 7). Absent everywhere else: the fixture sheet has no lantern to focus.
   */
  focus?: ReactNode
}

/**
 * Header + body for a single fixture's live controls (colour, dimmer, position,
 * channels…). Shared by the slide-in `FixtureDetailModal` and the docked stage
 * control panel so both stay consistent. Uses the Sheet header/body spacing
 * primitives (plain divs) but does not itself require a Sheet/Dialog context.
 */
export function FixtureDetailView({
  fixture,
  isEditing: externalIsEditing,
  titleComponent: TitleComponent = 'div',
  focus,
}: FixtureDetailViewProps) {
  const [internalIsEditing, setInternalIsEditing] = useState(false)
  const [viewMode, setViewMode] = useState<FixtureViewMode | 'focus'>('properties')

  // Use the forced edit state if provided, otherwise the internal toggle.
  const isEditing = externalIsEditing ?? internalIsEditing
  const showEditButton = externalIsEditing === undefined
  const hasElements = (fixture?.elements?.length ?? 0) > 0
  const model = fixture ? [fixture.manufacturer, fixture.model].filter(Boolean).join(' ') : ''

  return (
    <>
      <SheetHeader>
        {/* The name and model have the row to themselves, clear of the host's close cross. */}
        <div className="min-w-0 pr-8">
          <TitleComponent className="line-clamp-2 break-words font-semibold text-foreground" title={fixture?.name}>
            {fixture?.name ?? 'Fixture'}
          </TitleComponent>
          {model && (
            <p className="truncate text-sm text-muted-foreground" title={model}>
              {model}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <ToggleGroup
            type="single"
            value={viewMode}
            onValueChange={(value) => value && setViewMode(value as FixtureViewMode | 'focus')}
            size="sm"
          >
            <ToggleGroupItem value="properties" aria-label="Show properties" title="Properties">
              <Settings2 className="h-4 w-4" />
            </ToggleGroupItem>
            <ToggleGroupItem value="channels" aria-label="Show channels" title="Channels">
              <SlidersHorizontal className="h-4 w-4" />
            </ToggleGroupItem>
            {focus != null && (
              <ToggleGroupItem value="focus" aria-label="Show focus" title="Focus">
                <Crosshair className="h-4 w-4" />
              </ToggleGroupItem>
            )}
          </ToggleGroup>
          {fixture && (
            <LocateButton type="fixture" targetKey={fixture.key} name={fixture.name} iconOnly />
          )}
          {fixture && <FixtureParkButton fixture={fixture} isEditing={isEditing} />}
          {showEditButton && (
            <Button
              variant={isEditing ? 'default' : 'outline'}
              size="sm"
              className="ml-auto"
              onClick={() => setInternalIsEditing(!internalIsEditing)}
            >
              {isEditing ? 'Done' : 'Edit'}
            </Button>
          )}
        </div>

        {/* Capability badges */}
        {fixture && (
          <div className="flex flex-wrap gap-1">
            {hasElements && (
              <Badge variant="secondary">{fixture.elements!.length} heads</Badge>
            )}
            {fixture.mode && (
              <Badge variant="outline">{fixture.mode.modeName}</Badge>
            )}
            {fixture.capabilities?.map((cap) => (
              <Badge key={cap} variant="outline" className="capitalize">
                {cap}
              </Badge>
            ))}
          </div>
        )}
      </SheetHeader>

      <SheetBody>
        {viewMode === 'focus' && focus != null
          ? focus
          : fixture && (
              <FixtureContent
                fixture={fixture}
                isEditing={isEditing}
                viewMode={viewMode === 'focus' ? 'properties' : viewMode}
              />
            )}
      </SheetBody>
    </>
  )
}
