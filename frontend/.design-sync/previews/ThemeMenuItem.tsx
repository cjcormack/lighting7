import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
  ThemeMenuItem,
} from 'lighting-desk-ui'
import { CircleUser, Maximize2, MonitorSmartphone } from 'lucide-react'

// The theme control is a row of the user menu, among the other per-viewer items, so it is shown
// there: the menu rendered open and non-modal under its avatar trigger on a primary header strip,
// as it sits in Layout. The row reads the live theme and offers the other one.
export const InUserMenu = () => (
  <div className="h-[440px] w-full">
    <div className="flex h-12 items-center justify-between rounded-md bg-primary px-3 text-primary-foreground">
      <span className="text-sm font-semibold">Lighting Desk · Autumn Tour 2026</span>
      <DropdownMenu defaultOpen modal={false}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="text-primary-foreground hover:bg-primary-foreground/10"
            aria-label="Menu"
          >
            <CircleUser className="size-5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56" onCloseAutoFocus={(e) => e.preventDefault()}>
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            this window is <span className="text-foreground">Main</span>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem>
            <CircleUser className="size-4" />
            Profile…
          </DropdownMenuItem>
          <ThemeMenuItem />
          <DropdownMenuItem>
            <Maximize2 className="size-4" />
            Full screen
            <DropdownMenuShortcut>⇧F</DropdownMenuShortcut>
          </DropdownMenuItem>
          <DropdownMenuItem>
            <MonitorSmartphone className="size-4" />
            Screens…
            <DropdownMenuShortcut>2 windows</DropdownMenuShortcut>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  </div>
)
