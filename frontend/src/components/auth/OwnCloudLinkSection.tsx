import { useState } from "react"
import { Cloud, Loader2 } from "lucide-react"
import { toast } from "sonner"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { CLOUD_LINK_VIA_LABELS, useOwnCloudLinkQuery, useUnlinkOwnCloudAccountMutation } from "@/store/cloudLinks"

/**
 * The signed-in user's own Lighting7 Cloud link, on the Profile tab, with *Unlink* (lighting7
 * cloud-p1 plan D12). Drawn only while there is one: a desk that is not adopted has no links.
 */
export function OwnCloudLinkSection({ active }: { active: boolean }) {
  const { data } = useOwnCloudLinkQuery(undefined, { skip: !active, refetchOnMountOrArgChange: true })
  const [unlink, { isLoading }] = useUnlinkOwnCloudAccountMutation()
  const [confirm, setConfirm] = useState(false)
  const link = data?.link
  if (!link) return null

  const handleUnlink = async () => {
    try {
      await unlink().unwrap()
    } catch {
      // Reported by errorToastMiddleware.
      setConfirm(false)
      return
    }
    setConfirm(false)
    toast.success(`Unlinked from ${link.email}`)
  }

  return (
    <div className="space-y-2 border-t pt-4">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <Cloud className="size-4" />
        Lighting7 Cloud
      </h3>
      <p className="text-sm">
        {link.email}
        <span className="text-muted-foreground"> · {CLOUD_LINK_VIA_LABELS[link.linkedVia]}</span>
      </p>
      <Button variant="outline" className="w-full" disabled={isLoading} onClick={() => setConfirm(true)}>
        {isLoading && <Loader2 className="size-4 animate-spin" />}
        Unlink
      </Button>
      <AlertDialog open={confirm} onOpenChange={(next) => !next && setConfirm(false)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Unlink {link.email}?</AlertDialogTitle>
            <AlertDialogDescription>
              Any remote session you have open through Lighting7 Cloud ends now, and you can&apos;t open
              this desk through the cloud until you are linked again.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleUnlink()}>Unlink</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
