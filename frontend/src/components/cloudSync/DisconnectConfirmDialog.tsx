import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { useCloudSyncDisconnectMutation } from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"

/** Confirmation for disconnecting cloud sync. */
export function DisconnectConfirmDialog({
  open,
  onOpenChange,
  projectId,
  repoLabel,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  repoLabel: string
}) {
  const [disconnect, { isLoading }] = useCloudSyncDisconnectMutation()

  const handleDisconnect = async () => {
    try {
      await disconnect(projectId).unwrap()
      toast.success("Disconnected from cloud sync")
      onOpenChange(false)
    } catch (err) {
      toast.error(`Disconnect failed: ${formatError(err)}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Disconnect cloud sync?</DialogTitle>
          <DialogDescription>
            This stops syncing <span className="font-mono">{repoLabel}</span> and turns off
            auto-sync. Your local project and its history are kept, and the repository is
            remembered so you can reconnect later.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={handleDisconnect} disabled={isLoading}>
            {isLoading ? "Disconnecting…" : "Disconnect"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
