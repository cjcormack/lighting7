import type { ElementType, ReactNode } from 'react'
import { Eraser } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { Fixture } from '@/store/fixtures'

export type SheetView = 'values' | 'channels' | 'focus' | 'members'

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

/** `Group · 6 × Hex 12-channel`, or `Group · 6 fixtures` where the members are of several models. */
export function groupModelLine(members: readonly Fixture[]): string {
  const models = new Set(members.map((m) => [m.manufacturer, m.model].filter(Boolean).join(' ') || m.typeKey))
  const count = members.length
  if (count > 0 && models.size === 1) return `Group · ${count} × ${[...models][0]}`
  return `Group · ${count} fixture${count === 1 ? '' : 's'}`
}

export const FIXTURE_VIEWS: readonly { id: SheetView; label: string }[] = [
  { id: 'values', label: 'Values' },
  { id: 'channels', label: 'Channels' },
]
export const FOCUS_VIEW = { id: 'focus' as const, label: 'Focus' }
export const GROUP_VIEWS: readonly { id: SheetView; label: string }[] = [
  { id: 'values', label: 'Values' },
  { id: 'members', label: 'Members' },
]

/**
 * One header in every host (Main board, note 1): the name and model, then a 40px chrome row — the
 * views (**Values · Channels**, **Focus** on the Stage panel; a group's **Values · Members**),
 * Locate, Park and **Release n**. There is no Edit / Done: the sheet is live whenever the desk is
 * connected (D2). The heads and mode badges moved into the model line.
 *
 * Release folds to its glyph below 400px of sheet, its count kept on its title — a container query
 * on the sheet, never the viewport (§4).
 */
export function SheetHeader({
  name,
  model,
  views,
  view,
  onView,
  titleComponent: Title = 'div',
  held,
  connected,
  onRelease,
  locate,
  park,
}: {
  name: string
  model: string
  views: readonly { id: SheetView; label: string }[]
  view: SheetView
  onView: (view: SheetView) => void
  titleComponent?: ElementType<{ className?: string; children?: ReactNode; title?: string }>
  /** What Release takes: values and local effects. */
  held: number
  connected: boolean
  onRelease: () => void
  /** Locate, over the strip's pick (`LocateTargetsButton`). */
  locate: ReactNode
  park: ReactNode
}) {
  const releaseTitle =
    held === 0
      ? `${name} holds nothing in the programmer`
      : `Release ${held} — every value and local effect ${name} holds in the programmer`
  return (
    <div className="flex flex-none flex-col">
      <div className="min-w-0 px-3 pt-3 pr-10 pb-1.5">
        <Title className="line-clamp-2 text-[15px] font-semibold break-words text-foreground" title={name}>
          {name}
        </Title>
        <p className="truncate text-xs text-muted-foreground" title={model}>
          {model}
        </p>
      </div>
      <div className="flex h-10 flex-none items-center gap-2 border-b px-3">
        <div role="tablist" aria-label="Sheet view" className="inline-flex gap-0.5 rounded-lg border bg-background p-0.5">
          {views.map((tab) => (
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
        {locate}
        {park}
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
