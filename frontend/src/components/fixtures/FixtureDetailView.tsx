import { useState, type ElementType, type ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Settings2, SlidersHorizontal } from 'lucide-react'
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
}: FixtureDetailViewProps) {
  const [internalIsEditing, setInternalIsEditing] = useState(false)
  const [viewMode, setViewMode] = useState<FixtureViewMode>('properties')

  // Use the forced edit state if provided, otherwise the internal toggle.
  const isEditing = externalIsEditing ?? internalIsEditing
  const showEditButton = externalIsEditing === undefined
  const hasElements = (fixture?.elements?.length ?? 0) > 0

  return (
    <>
      <SheetHeader>
        <div className="flex items-center justify-between pr-8">
          <div>
            <TitleComponent className="font-semibold text-foreground">
              {fixture?.name ?? 'Fixture'}
            </TitleComponent>
            {fixture && (fixture.manufacturer || fixture.model) && (
              <p className="text-sm text-muted-foreground">
                {[fixture.manufacturer, fixture.model].filter(Boolean).join(' ')}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2">
            <ToggleGroup
              type="single"
              value={viewMode}
              onValueChange={(value) => value && setViewMode(value as FixtureViewMode)}
              size="sm"
            >
              <ToggleGroupItem value="properties" aria-label="Show properties" title="Properties">
                <Settings2 className="h-4 w-4" />
              </ToggleGroupItem>
              <ToggleGroupItem value="channels" aria-label="Show channels" title="Channels">
                <SlidersHorizontal className="h-4 w-4" />
              </ToggleGroupItem>
            </ToggleGroup>
            {fixture && (
              <LocateButton type="fixture" targetKey={fixture.key} name={fixture.name} iconOnly />
            )}
            {fixture && <FixtureParkButton fixture={fixture} isEditing={isEditing} />}
            {showEditButton && (
              <Button
                variant={isEditing ? 'default' : 'outline'}
                size="sm"
                onClick={() => setInternalIsEditing(!internalIsEditing)}
              >
                {isEditing ? 'Done' : 'Edit'}
              </Button>
            )}
          </div>
        </div>

        {/* Capability badges */}
        {fixture && (
          <div className="flex flex-wrap gap-1 mt-2">
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
        {fixture && (
          <FixtureContent fixture={fixture} isEditing={isEditing} viewMode={viewMode} />
        )}
      </SheetBody>
    </>
  )
}
