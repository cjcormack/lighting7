import { useMemo } from 'react'
import { Command } from 'cmdk'
import { colourCounts, useFixtureColourIndex } from '@/hooks/useFixtureColourIndex'
import { FIXTURE_COLOUR_LABELS } from '@/lib/fixtureColours'
import { selectionForColour } from '@/lib/colourSelection'
import { setDeskSelection, useDeskSelectionQuery } from '@/store/selection'
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
