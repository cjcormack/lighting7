import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { lightingApi } from "@/api/lightingApi"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { useIsDeskConnected } from "@/store/status"
import type { BankDefinition } from "@/store/surfaces"

interface BankSwitcherProps {
  deviceTypeKey: string
  banks: BankDefinition[]
  activeBank: string | null
}

/**
 * Bank switcher strip. Renders one button per declared bank plus a "global" option.
 * Sends `surfaceBank.set` to the server, which pushes the change back to every client.
 */
export function BankSwitcher({ deviceTypeKey, banks, activeBank }: BankSwitcherProps) {
  // `surfaceBank.set` is a wire write, and the active bank paints from the server's answer — so
  // offline these buttons look live and do nothing, exactly like blackout above them.
  const connected = useIsDeskConnected()

  if (banks.length === 0) {
    return (
      <div className="text-xs text-muted-foreground">
        No banks declared for this device.
      </div>
    )
  }
  return (
    <div className="flex items-center gap-2 flex-wrap">
      <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
        Active bank
      </span>
      <Button
        size="sm"
        variant={activeBank == null ? "default" : "outline"}
        onClick={() => lightingApi.surfaces.setBank(deviceTypeKey, null)}
        disabled={!connected}
        title={connected ? undefined : DESK_OFFLINE_LABEL}
      >
        <Badge variant="outline" className="mr-1">–</Badge>
        Global
      </Button>
      {banks.map((bank) => (
        <Button
          key={bank.id}
          size="sm"
          variant={activeBank === bank.id ? "default" : "outline"}
          onClick={() => lightingApi.surfaces.setBank(deviceTypeKey, bank.id)}
          disabled={!connected}
          title={connected ? undefined : DESK_OFFLINE_LABEL}
        >
          <Badge variant="outline" className="mr-1">{bank.id}</Badge>
          {bank.name}
        </Button>
      ))}
    </div>
  )
}
