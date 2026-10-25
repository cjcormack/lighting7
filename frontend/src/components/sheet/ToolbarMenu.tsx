import { createContext, useContext, useState, type ReactNode } from 'react'
import { Check, ChevronDown, ChevronRight } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { cn } from '@/lib/utils'

/**
 * A toolbar menu that is a **dropdown on a desk and a bottom sheet on a phone** (programmer-toolbar
 * plan D12): the programmer's heads chip and Release ▾. The items are written once, with the
 * `ToolbarMenu*` pieces below, which read which host they are in from context. In the sheet the
 * rows are the fixture sheet's phone sizes — 44px rows — and a press closes the sheet as a menu
 * item closes its menu.
 */
const SheetHost = createContext<{ close: () => void } | null>(null)

/**
 * Whether the row these menus sit on is a phone's — provided by the row that measures itself (the
 * programmer's row C), so a menu, and anything else that lays itself out differently on a phone,
 * reads one answer.
 */
export const ToolbarPhoneContext = createContext(false)

/** Is the toolbar row a phone's? False outside a row that says. */
export function useToolbarPhone(): boolean {
  return useContext(ToolbarPhoneContext)
}

/** Each row's height in the sheet: the finger size the fixture sheet's phone host uses. */
const SHEET_ROW_CLASS =
  'flex min-h-11 w-full items-center gap-2 rounded-md px-3 text-left text-sm hover:bg-accent disabled:pointer-events-none disabled:opacity-50'

export function ToolbarMenu({
  asSheet,
  title,
  trigger,
  align = 'start',
  contentClassName,
  children,
}: {
  /** The bottom-sheet arm. Defaults to the row's own answer ([useToolbarPhone]). */
  asSheet?: boolean
  /** The sheet's heading, and its accessible name. */
  title: string
  trigger: ReactNode
  align?: 'start' | 'end'
  contentClassName?: string
  children: ReactNode
}) {
  const [open, setOpen] = useState(false)
  const phone = useToolbarPhone()
  if (!(asSheet ?? phone)) {
    return (
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
        <DropdownMenuContent align={align} className={contentClassName}>
          {children}
        </DropdownMenuContent>
      </DropdownMenu>
    )
  }
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent side="bottom" className="max-h-[78dvh] gap-1 rounded-t-xl pb-3">
        <SheetHeader className="pb-1">
          <SheetTitle className="text-sm">{title}</SheetTitle>
        </SheetHeader>
        <SheetHost.Provider value={{ close: () => setOpen(false) }}>
          <div role="menu" aria-label={title} className="flex flex-col gap-0.5 overflow-y-auto px-2">
            {children}
          </div>
        </SheetHost.Provider>
      </SheetContent>
    </Sheet>
  )
}

export function ToolbarMenuItem({
  onSelect,
  disabled,
  title,
  className,
  children,
}: {
  onSelect: () => void
  disabled?: boolean
  title?: string
  className?: string
  children: ReactNode
}) {
  const sheet = useContext(SheetHost)
  if (sheet == null) {
    return (
      <DropdownMenuItem disabled={disabled} title={title} className={className} onSelect={onSelect}>
        {children}
      </DropdownMenuItem>
    )
  }
  return (
    <button
      type="button"
      role="menuitem"
      disabled={disabled}
      title={title}
      className={cn(SHEET_ROW_CLASS, className)}
      onClick={() => {
        onSelect()
        sheet.close()
      }}
    >
      {children}
    </button>
  )
}

export function ToolbarMenuCheckboxItem({
  checked,
  onCheckedChange,
  children,
}: {
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  children: ReactNode
}) {
  const sheet = useContext(SheetHost)
  if (sheet == null) {
    return (
      <DropdownMenuCheckboxItem checked={checked} onCheckedChange={onCheckedChange}>
        {children}
      </DropdownMenuCheckboxItem>
    )
  }
  return (
    <button
      type="button"
      role="menuitemcheckbox"
      aria-checked={checked}
      className={SHEET_ROW_CLASS}
      onClick={() => {
        onCheckedChange(!checked)
        sheet.close()
      }}
    >
      <span className="flex size-4 shrink-0 items-center justify-center">{checked && <Check className="size-4" />}</span>
      {children}
    </button>
  )
}

export function ToolbarMenuLabel({ children }: { children: ReactNode }) {
  const sheet = useContext(SheetHost)
  if (sheet == null) {
    return <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">{children}</DropdownMenuLabel>
  }
  return <div className="px-3 pt-2 pb-1 text-[11px] text-muted-foreground">{children}</div>
}

export function ToolbarMenuSeparator() {
  const sheet = useContext(SheetHost)
  if (sheet == null) return <DropdownMenuSeparator />
  return <div role="separator" className="my-1 h-px bg-border" />
}

/** A submenu on a desk; on a phone, a row that opens its items in place beneath it. */
export function ToolbarMenuSub({ label, icon, children }: { label: string; icon?: ReactNode; children: ReactNode }) {
  const sheet = useContext(SheetHost)
  const [expanded, setExpanded] = useState(false)
  if (sheet == null) {
    return (
      <DropdownMenuSub>
        <DropdownMenuSubTrigger>
          {icon}
          {label}
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent>{children}</DropdownMenuSubContent>
      </DropdownMenuSub>
    )
  }
  return (
    <>
      <button
        type="button"
        role="menuitem"
        aria-expanded={expanded}
        className={SHEET_ROW_CLASS}
        onClick={() => setExpanded((was) => !was)}
      >
        {icon}
        <span className="flex-1">{label}</span>
        {expanded ? <ChevronDown className="size-4 text-muted-foreground" /> : <ChevronRight className="size-4 text-muted-foreground" />}
      </button>
      {expanded && <div className="flex flex-col gap-0.5 pl-4">{children}</div>}
    </>
  )
}

/** Whether these pieces are drawn in the bottom sheet — for a row that lays itself out differently there. */
export function useToolbarMenuInSheet(): boolean {
  return useContext(SheetHost) != null
}
