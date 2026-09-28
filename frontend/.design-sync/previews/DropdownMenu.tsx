import {
  Button,
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
} from 'lighting-desk-ui'
import { Copy, Eye, Pencil, Pin, Play, Trash2 } from 'lucide-react'

// A dropdown menu is the row-actions menu on a cue, a stack or a fixture.
// Rendered open and non-modal, so the card shows the list anchored under its
// trigger: a label, plain items with icons and shortcuts, a checkbox item, a
// separator, and a destructive item at the bottom.
export const CueActions = () => (
  <div className="flex h-[440px] w-full items-start justify-center pt-8">
    <DropdownMenu defaultOpen modal={false}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline">Cue 12 · Band walk-on</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-60" align="start" onCloseAutoFocus={(e) => e.preventDefault()}>
        <DropdownMenuLabel>Cue 12 · Band walk-on</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem>
          <Play />
          Go to cue
          <DropdownMenuShortcut>G</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Pencil />
          Edit in Programmer
          <DropdownMenuShortcut>E</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Copy />
          Duplicate
          <DropdownMenuShortcut>⌘D</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuCheckboxItem checked>
          <Pin />
          Pinned to Busk
        </DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem>
          <Eye />
          Show in Prompt Book
        </DropdownMenuCheckboxItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive">
          <Trash2 />
          Delete cue
          <DropdownMenuShortcut>⌫</DropdownMenuShortcut>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
)
