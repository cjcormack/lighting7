import type { ElementType, ReactNode } from 'react'
import { Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { Fixture } from '@/store/fixtures'
import { FixtureParkButton } from '../fixtures/FixtureParkButton'
import { LocateButton } from '../fixtures/LocateButton'

export type SheetView = 'values' | 'channels' | 'focus'

/** `Robe ColorSpot 575 AT · Mode 2 (19-channel) · U1.201` — the board's model line. */
export function modelLine(fixture: Fixture): string {
  const model = [fixture.manufacturer, fixture.model].filter(Boolean).join(' ')
  const heads = fixture.elements?.length ? `${fixture.elements.length} heads` : null
  const address = `U${fixture.universe}.${String(fixture.firstChannel).padStart(3, '0')}`
  // A mode's name already says its footprint (`Mode 2 (19-channel)`); a type with one mode says it here.
  const footprint = fixture.mode ? fixture.mode.modeName : `${fixture.channelCount}-ch`
  return [model || null, heads, footprint, address]
    .filter(Boolean)
    .join(' · ')
}

/**
 * One header in every host (Main board, note 1): the name and model, then a 40px chrome row —
 * **Values · Channels** (and **Focus** on the Stage panel), Locate, Park and **Release n**. There is
 * no Edit / Done: the sheet is live whenever the desk is connected (D2). The heads and mode badges
 * moved into the model line.
 *
 * Release folds to its glyph below 400px of sheet, its count kept on its title — a container query
 * on the sheet, never the viewport (§4).
 */
export function SheetHeader({
  fixture,
  view,
  onView,
  hasFocus,
  titleComponent: Title = 'div',
  held,
  connected,
  onRelease,
}: {
  fixture: Fixture
  view: SheetView
  onView: (view: SheetView) => void
  hasFocus: boolean
  titleComponent?: ElementType<{ className?: string; children?: ReactNode; title?: string }>
  /** What Release takes: values and local effects. */
  held: number
  connected: boolean
  onRelease: () => void
}) {
  const tabs: { id: SheetView; label: string }[] = [
    { id: 'values', label: 'Values' },
    { id: 'channels', label: 'Channels' },
    ...(hasFocus ? [{ id: 'focus' as const, label: 'Focus' }] : []),
  ]
  const releaseTitle =
    held === 0
      ? `${fixture.name} holds nothing in the programmer`
      : `Release ${held} — every value and local effect ${fixture.name} holds in the programmer`
  return (
    <div className="flex flex-none flex-col">
      <div className="min-w-0 px-3 pt-3 pr-10 pb-1.5">
        <Title className="line-clamp-2 text-[15px] font-semibold break-words text-foreground" title={fixture.name}>
          {fixture.name}
        </Title>
        <p className="truncate text-xs text-muted-foreground" title={modelLine(fixture)}>
          {modelLine(fixture)}
        </p>
      </div>
      <div className="flex h-10 flex-none items-center gap-2 border-b px-3">
        <div role="tablist" aria-label="Sheet view" className="inline-flex gap-0.5 rounded-lg border bg-background p-0.5">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={view === tab.id}
              onClick={() => onView(tab.id)}
              className={cn(
                'h-[26px] rounded-md px-2 text-xs font-medium text-muted-foreground',
                view === tab.id && 'bg-muted text-foreground',
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <span className="flex-1" />
        <LocateButton type="fixture" targetKey={fixture.key} name={fixture.name} iconOnly />
        <FixtureParkButton fixture={fixture} isEditing={connected} iconOnly />
        <Button
          variant="outline"
          size="sm"
          data-release
          aria-label={`Release ${held}`}
          title={releaseTitle}
          disabled={!connected || held === 0}
          onClick={onRelease}
          className="h-8 gap-1.5 px-2.5"
        >
          <Eraser className="size-3.5" />
          <span className="@max-[400px]/sheet:hidden">Release</span>
          <span className="font-mono text-muted-foreground">{held}</span>
        </Button>
      </div>
    </div>
  )
}
