import type { Ref } from 'react'
import { Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ToolbarMenu, ToolbarMenuItem, ToolbarMenuLabel } from '@/components/sheet/ToolbarMenu'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { cn } from '@/lib/utils'

/** The ⏎ key cap beside Set's word — the shape the selection bar's hints use. */
const KBD_CLASS = 'rounded border bg-muted/50 px-1 py-px font-mono text-[9.5px] leading-none text-muted-foreground'

/** One family Set can open on for a row selection, and why not where it cannot. */
export interface SetFamilyChoice {
  family: AttributeFamily
  /** Why the selected heads have no cell to set in it — no shown column of that family on any of them. */
  refused: string | null
}

/**
 * **Set** on the programmer's row C (programmer-toolbar plan D6): the cell popup, on cells or on
 * whole rows.
 *
 * With cells selected it is the sheet kit's Set — the grid's Enter with a button on it, opening the
 * first selected cell's editor at this button ([onSet], `toggleCellEditor`). With only rows selected
 * it first asks which attribute family: the choice selects those rows' cells in that family's
 * columns — a marquee, so the mask becomes that family — and opens the same popup ([onPickFamily]).
 * It is never disabled for the shape of the selection; the scope can still refuse it ([refused]),
 * with the words the keyboard's refusal uses.
 */
export function SetButton({
  cells,
  heads,
  setRef,
  onSet,
  families,
  onPickFamily,
  refused,
  wordClass,
  kbdClass,
}: {
  cells: number
  heads: number
  setRef?: Ref<HTMLButtonElement>
  onSet: () => void
  families: readonly SetFamilyChoice[]
  onPickFamily: (family: AttributeFamily) => void
  /** Why the scope refuses Set (Output, a palette layer), or null where it writes. */
  refused: string | null
  wordClass: string
  kbdClass: string
}) {
  const cellWord = `${cells} cell${cells === 1 ? '' : 's'}`
  const headWord = `${heads} head${heads === 1 ? '' : 's'}`
  const face = (props: { title: string; label: string; onClick?: () => void }) => (
    <Button
      ref={setRef}
      variant="outline"
      size="sm"
      disabled={refused != null}
      title={refused ?? props.title}
      aria-label={props.label}
      onClick={props.onClick}
    >
      <Pencil className="size-3.5" />
      <span className={wordClass}>Set</span>
      {cells > 0 && refused == null && <kbd className={cn(KBD_CLASS, kbdClass)}>⏎</kbd>}
    </Button>
  )
  if (cells > 0 || refused != null) {
    return face({
      title: `Set the ${cellWord} — palettes, the value, its FX (Enter)`,
      label: cells > 0 ? `Set the ${cellWord}` : `Set an attribute on the ${headWord}`,
      onClick: onSet,
    })
  }
  return (
    <ToolbarMenu
      title="Set which attribute?"
      contentClassName="w-[240px]"
      trigger={face({
        title: `Set an attribute on the ${headWord} — pick which first`,
        label: `Set an attribute on the ${headWord}`,
      })}
    >
      <ToolbarMenuLabel>{`${headWord}, no cells picked — which attribute?`}</ToolbarMenuLabel>
      {families.map(({ family, refused: why }) => (
        <ToolbarMenuItem
          key={family}
          disabled={why != null}
          title={why ?? undefined}
          onSelect={() => onPickFamily(family)}
        >
          {FAMILY_LABELS[family].singular}
        </ToolbarMenuItem>
      ))}
    </ToolbarMenu>
  )
}
