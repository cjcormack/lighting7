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

/** `navigator.clipboard` is absent on a plain-http LAN origin, so a failure says so. */
export function copyOscAddress(address: string) {
  void (async () => {
    try {
      await navigator.clipboard.writeText(address)
      toast.success(`Copied ${address}`)
    } catch {
      toast.error('Could not copy the address')
    }
  })()
}
