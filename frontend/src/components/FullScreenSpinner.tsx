import { Loader2 } from "lucide-react"

/** The pre-app gates' cold-load state: nothing to show yet, and nothing behind it. */
export function FullScreenSpinner() {
  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-background">
      <Loader2 className="size-6 animate-spin text-muted-foreground" />
    </div>
  )
}
