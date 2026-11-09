import { Radio } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  ContextMenuItem,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from '@/components/ui/context-menu'
import { useOscAddressesQuery } from '@/store/osc'
import { noOscAddressReason, oscAddressesFor, oscAddressVerb, type OscRecordKey } from '@/lib/oscAddresses'
import { isRemoteMode } from '@/lib/remoteMode'

const MENUS = {
  dropdown: { Sub: DropdownMenuSub, Trigger: DropdownMenuSubTrigger, Content: DropdownMenuSubContent, Item: DropdownMenuItem },
  context: { Sub: ContextMenuSub, Trigger: ContextMenuSubTrigger, Content: ContextMenuSubContent, Item: ContextMenuItem },
} as const

interface CopyOscAddressMenuProps {
  projectId: number
  /** The records whose addresses it lists, in this order — an entry's GO TO before its cue's loose hold. */
  records: readonly OscRecordKey[]
  /** Which menu it sits in. */
  menu: keyof typeof MENUS
}

/**
 * *Copy OSC address* (lighting7 OSC plan §4): a submenu of every address that reaches these records,
 * read from `GET /osc/addresses` as it opens, each row copying its address for QLab. A record with
 * none says why, in the desk's words. Absent in remote mode: OSC is the show network's, and the desk
 * refuses the listing on its public listener (D5).
 */
export function CopyOscAddressMenu({ projectId, records, menu }: CopyOscAddressMenuProps) {
  const { Sub, Trigger, Content } = MENUS[menu]
  if (isRemoteMode()) return null
  return (
    <Sub>
      <Trigger>
        <Radio className="size-3.5" />
        Copy OSC address
      </Trigger>
      <Content className="max-w-sm">
        <OscAddressItems projectId={projectId} records={records} menu={menu} />
      </Content>
    </Sub>
  )
}

/** The same list behind a button, for a sheet that has no menu to put it in. */
export function CopyOscAddressButton({ projectId, records }: Omit<CopyOscAddressMenuProps, 'menu'>) {
  if (isRemoteMode()) return null
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">
          <Radio className="size-3.5" />
          OSC address
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="max-w-sm">
        <OscAddressItems projectId={projectId} records={records} menu="dropdown" />
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Mounted only while the submenu is open, so the listing is read when it is wanted. */
function OscAddressItems({ projectId, records, menu }: CopyOscAddressMenuProps) {
  const { Item } = MENUS[menu]
  const { data, isLoading, isError } = useOscAddressesQuery(projectId, { refetchOnMountOrArgChange: true })
  if (data == null) {
    return <Item disabled>{isLoading ? 'Reading the addresses…' : isError ? 'Could not read the addresses' : 'No addresses'}</Item>
  }
  const rows = oscAddressesFor(data, records)
  if (rows.length === 0) {
    return <Item disabled>{noOscAddressReason(data, records) ?? 'No OSC address reaches this'}</Item>
  }
  return (
    <>
      {rows.map((row) => (
        <Item key={row.address} title={row.does} onSelect={() => copyOscAddress(row.address)}>
          <span className="min-w-14 shrink-0 whitespace-nowrap text-xs text-muted-foreground">{oscAddressVerb(row)}</span>
          <span className="truncate font-mono text-xs">{row.address}</span>
        </Item>
      ))}
    </>
  )
}

/**
 * Copy [address] for QLab. `navigator.clipboard` is absent on a plain-http LAN origin and may be
 * refused, so then the browser's own copy is tried, and failing that the address is selected in
 * [cell] and the toast says to copy it by hand.
 */
export function copyOscAddress(address: string, cell?: HTMLElement | null) {
  const fallback = () => {
    if (copyBySelection(address)) {
      toast.success(`Copied ${address}`)
    } else if (cell != null && selectContents(cell)) {
      toast.message('Press ⌘C to copy', { description: address })
    } else {
      toast.error(`Couldn’t copy ${address}`)
    }
  }
  if (navigator.clipboard?.writeText == null) {
    fallback()
    return
  }
  navigator.clipboard.writeText(address).then(() => toast.success(`Copied ${address}`), fallback)
}

/**
 * The pre-Clipboard-API copy, which a plain-http origin still allows inside a gesture. The text is
 * put on the `copy` event itself, since a menu's focus trap can take focus, and with it the
 * selection, back from the field; the field stays for a browser that enables copy only over one.
 */
function copyBySelection(text: string): boolean {
  if (typeof document.execCommand !== 'function') return false
  let written = false
  const onCopy = (event: ClipboardEvent) => {
    if (event.clipboardData == null) return
    event.clipboardData.setData('text/plain', text)
    event.preventDefault()
    written = true
  }
  const previous = document.activeElement as HTMLElement | null
  const field = document.createElement('textarea')
  field.value = text
  field.readOnly = true
  field.style.position = 'fixed'
  field.style.opacity = '0'
  document.body.appendChild(field)
  field.select()
  document.addEventListener('copy', onCopy, true)
  try {
    return document.execCommand('copy') && written
  } catch {
    return false
  } finally {
    document.removeEventListener('copy', onCopy, true)
    field.remove()
    previous?.focus?.()
  }
}

function selectContents(cell: HTMLElement): boolean {
  const selection = window.getSelection()
  if (selection == null) return false
  const range = document.createRange()
  range.selectNodeContents(cell)
  selection.removeAllRanges()
  selection.addRange(range)
  return true
}
