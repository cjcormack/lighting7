import {
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from '@/components/ui/dropdown-menu'
import {
  ContextMenuItem,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from '@/components/ui/context-menu'

/**
 * One menu entry drawn in either kind of menu a record's door is: a dropdown (a ⋯ button) or a
 * context menu (a right-click or long-press). *Copy OSC address* and *Assign to surface…* sit in both.
 */
export const MENU_PARTS = {
  dropdown: { Sub: DropdownMenuSub, Trigger: DropdownMenuSubTrigger, Content: DropdownMenuSubContent, Item: DropdownMenuItem },
  context: { Sub: ContextMenuSub, Trigger: ContextMenuSubTrigger, Content: ContextMenuSubContent, Item: ContextMenuItem },
} as const

export type MenuKind = keyof typeof MENU_PARTS
