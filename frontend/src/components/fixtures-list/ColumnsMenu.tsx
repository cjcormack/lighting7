import { Columns3 } from 'lucide-react'
import { labelUnlessCompact } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { usePersistentState } from '@/hooks/usePersistentState'
import { COLUMN_DEFS, DEFAULT_COLUMN_VISIBILITY, type ColumnKey } from './columns'

export type ColumnVisibility = Record<ColumnKey, boolean>

/**
 * Which value columns the grid shows.
 *
 * A hook rather than state inside `FixturesListContainer` because the programmer view renders the
 * *menu* in its action bar — a full-width band above the workspace — while the *table* lives inside
 * the container below it. One owner above both is the only arrangement that keeps them in step
 * without a portal or a render-during-render callback.
 *
 * `merge: true` matters: a stored preference from before a column existed must not hide the new one.
 */
export function useColumnVisibility() {
  return usePersistentState<ColumnVisibility>('fixturesList.columns', DEFAULT_COLUMN_VISIBILITY, {
    merge: true,
  })
}

/** Resolve the visibility map to the ordered list of columns to render. */
export function visibleColumnsFrom(visibility: ColumnVisibility): ColumnKey[] {
  return COLUMN_DEFS.filter((d) => visibility[d.key]).map((d) => d.key)
}

export function ColumnsMenu({
  visibility,
  onChange,
  compact = false,
}: {
  visibility: ColumnVisibility
  onChange: (next: ColumnVisibility) => void
  /**
   * Icon only, whatever the viewport. The programmer's short-height arm asks for it (space plan
   * D8): rows A and B are one line there, and this button's word is 60px of the room the source
   * box needs to name the cue you are about to overwrite. The `title` already carries it.
   */
  compact?: boolean
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" title="Choose visible columns">
          <Columns3 className="size-3.5" />
          <span className={labelUnlessCompact(compact, 'sm:inline')}>Columns</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {COLUMN_DEFS.map((def) => (
          <DropdownMenuCheckboxItem
            key={def.key}
            checked={visibility[def.key]}
            onCheckedChange={(checked) => onChange({ ...visibility, [def.key]: checked === true })}
            // Keep the menu open while toggling several columns.
            onSelect={(e) => e.preventDefault()}
          >
            {def.label}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
