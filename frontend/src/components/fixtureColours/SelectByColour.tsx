import { useMemo } from 'react'
import { Command } from 'cmdk'
import { colourCounts, useFixtureColourIndex } from '@/hooks/useFixtureColourIndex'
import { FIXTURE_COLOUR_LABELS } from '@/lib/fixtureColours'
import { selectionForColour } from '@/lib/colourSelection'
import { setDeskSelection, useDeskSelectionQuery } from '@/store/selection'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import type { CueTarget } from '@/api/cuesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { useDeskFollow } from '@/lib/deskFollow'
import {
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from '@/components/ui/dropdown-menu'
import { Palette } from 'lucide-react'
import { useModifierHeld } from '@/components/stage3d/useShiftHeld'
import { ColourSwatch } from './ColourSwatches'

/**
 * ⌘K's *Select colour · Red* (D11): one item per colour some fixture on the rig carries, writing the
 * desk selection (`selection.set`), so every screen follows. Held ⌥ turns each into *Add to
 * selection · Red*. [visibleKeys] is the rig's lit fixtures — the palette's list, no infrastructure.
 */
export function SelectByColourItems({
  projectId,
  visibleKeys,
  itemClassName,
  groupClassName,
  onRun,
}: {
  projectId: number
  visibleKeys: ReadonlySet<string>
  itemClassName: string
  groupClassName: string
  onRun: (run: () => void) => void
}) {
  const index = useFixtureColourIndex(projectId)
  const { data: desk } = useDeskSelectionQuery()
  const { held: altHeld } = useModifierHeld('altKey')
  const colours = useMemo(() => colourCounts(index, visibleKeys), [index, visibleKeys])
  if (colours.length === 0) return null

  const select = (keys: readonly string[]) => {
    const next = selectionForColour(keys, { targets: desk?.targets ?? [], families: desk?.families ?? null }, altHeld)
    onRun(() => setDeskSelection(next.targets, next.families))
  }

  return (
    <Command.Group heading="Select by colour" className={groupClassName}>
      {colours.map(({ colour, keys }) => {
        const label = FIXTURE_COLOUR_LABELS[colour]
        return (
          <Command.Item
            key={colour}
            value={`colour-${colour}`}
            keywords={[
              `Select colour ${label}`,
              `select ${colour}`,
              `Add to selection ${label}`,
              `add ${colour}`,
              `colour:${colour}`,
            ]}
            onSelect={() => select(keys)}
            className={itemClassName}
          >
            <ColourSwatch colour={colour} className="size-3 rounded-[3px]" />
            <span className="flex-1 truncate">
              {altHeld ? 'Add to selection' : 'Select colour'} · {label}
            </span>
            <span className="text-xs text-muted-foreground">
              {altHeld ? '⌥' : `${keys.length} fixture${keys.length === 1 ? '' : 's'}`}
            </span>
          </Command.Item>
        )
      })}
    </Command.Group>
  )
}

/**
 * The programmer heads chip's *Select by colour ▸*: the same colours as ⌘K's, as a submenu, each
 * replacing the selection with that colour's fixtures, or adding them with ⌥. The host writes the
 * answer — the desk's selection while the window follows it, the list's own when it does not. The
 * ⌥ arm adds to the desk's heads and keeps its mask while following, as ⌘K's does; unlinked it adds
 * to [current], the list's own selection, which has no mask to keep.
 */
export function SelectByColourSub({
  projectId,
  current,
  onSelect,
}: {
  projectId: number
  current: readonly CueTarget[]
  onSelect: (targets: CueTarget[], families: readonly AttributeFamily[] | null) => void
}) {
  const following = useDeskFollow()
  const { data: desk } = useDeskSelectionQuery()
  const base = following ? { targets: desk?.targets ?? [], families: desk?.families ?? null } : { targets: current, families: null }
  const index = useFixtureColourIndex(projectId)
  const { data: fixtures } = useVisibleFixtureListQuery()
  const { held: altHeld } = useModifierHeld('altKey')
  const colours = useMemo(
    () => colourCounts(index, new Set((fixtures ?? []).map((f) => f.key))),
    [index, fixtures],
  )
  return (
    <DropdownMenuSub>
      <DropdownMenuSubTrigger disabled={colours.length === 0} className="gap-2 text-xs">
        <Palette className="size-3.5 text-muted-foreground" />
        Select by colour
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent>
        {colours.map(({ colour, keys }) => (
          <DropdownMenuItem
            key={colour}
            data-select-colour={colour}
            className="gap-2 text-xs"
            onSelect={() => {
              const next = selectionForColour(keys, base, altHeld)
              onSelect(next.targets, next.families)
            }}
          >
            <ColourSwatch colour={colour} className="size-3 rounded-[3px]" />
            <span className="flex-1">
              {altHeld ? 'Add ' : ''}
              {FIXTURE_COLOUR_LABELS[colour]}
            </span>
            <span className="text-[10.5px] text-muted-foreground">{keys.length}</span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  )
}
