import { Check, ChevronDown } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { BuskAddPage, BuskAddTarget } from '@/lib/buskAdd'

interface BuskPagePickerProps {
  label: string
  pages: BuskAddPage[]
  isLoading: boolean
  disabled?: boolean
  onPick: (target: BuskAddTarget) => void
  /** When a choice can be *un*made — the create sheets, where nothing is placed until Create. */
  onClear?: () => void
}

/**
 * The page → bank menu, and nothing else: it knows what the choices are and reports the one taken.
 *
 * Deliberately free of the store and of any mutation, because the two callers want different things
 * from a pick — {@link AddToBuskPageMenu} places the pad there and then, while a create sheet holds
 * the choice until the record it is placing exists.
 *
 * **One page flattens; several nest.** A submenu is the right shape for a two-level choice, but it
 * is friction when the second level is the only level, and most desks have one page.
 *
 * Every dead end is a *disabled item* rather than a missing one. A menu that opens empty, or a
 * trigger that does nothing, teaches nobody why.
 */
export function BuskPagePicker({
  label,
  pages,
  isLoading,
  disabled,
  onPick,
  onClear,
}: BuskPagePickerProps) {
  const single = pages.length === 1 ? pages[0] : null

  function items(page: BuskAddPage) {
    if (page.banks.length === 0) {
      // A page with no rows at all is legal server-side, so this is reachable, not defensive.
      return <DropdownMenuItem disabled>No banks on this page</DropdownMenuItem>
    }
    return page.banks.map((bank) => (
      <DropdownMenuItem key={bank.bankId} onSelect={() => onPick({
        pageId: page.pageId,
        pageName: page.pageName,
        bankId: bank.bankId,
        bankLabel: bank.label,
      })}>
        <span className="flex-1 truncate">{bank.label}</span>
        {/* Shown, never enforced: a record may sit on several pads by design, so this says
            "already here" rather than blocking the choice. */}
        {bank.holdsRecord && <Check className="size-3.5 text-muted-foreground" />}
        <span className="ml-1 text-[10px] tabular-nums text-muted-foreground">{bank.padCount}</span>
      </DropdownMenuItem>
    ))
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {/* Never disabled while loading: the menu opens on `pointerdown`, so a trigger that went
            disabled the moment the fetch started would go dead under the operator's finger with the
            menu already open. The wait is said inside the menu instead. */}
        <Button variant="outline" size="sm" disabled={disabled}>
          {label}
          <ChevronDown className="size-3.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-52">
        {isLoading && pages.length === 0 ? (
          <DropdownMenuItem disabled>Reading your busk pages…</DropdownMenuItem>
        ) : pages.length === 0 ? (
          // Told rather than linked: this control is rendered inside sheets and property panes, and
          // making every one of them a navigation source would buy a router dependency for an
          // affordance the view switcher already carries.
          <DropdownMenuItem disabled>No busk pages yet — make one on the Busk view</DropdownMenuItem>
        ) : (
          <>
            {onClear != null && (
              <>
                <DropdownMenuItem onSelect={onClear}>Don’t add a pad</DropdownMenuItem>
                <DropdownMenuSeparator />
              </>
            )}
            {single != null ? (
              <>
                <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">
                  {single.pageName}
                </DropdownMenuLabel>
                {items(single)}
              </>
            ) : (
              pages.map((page) => (
                <DropdownMenuSub key={page.pageId}>
                  <DropdownMenuSubTrigger>{page.pageName}</DropdownMenuSubTrigger>
                  <DropdownMenuSubContent className="min-w-44">{items(page)}</DropdownMenuSubContent>
                </DropdownMenuSub>
              ))
            )}
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
