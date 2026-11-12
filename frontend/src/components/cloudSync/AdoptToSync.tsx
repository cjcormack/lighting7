import { Link } from "react-router"
import { Cloud } from "lucide-react"
import { Button } from "@/components/ui/button"

/** What an unadopted desk sees in place of the cloud's repos: a way to the Cloud tab. */
export function AdoptToSync({ what = "sync" }: { what?: string }) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border bg-muted/20 p-3">
      <Cloud className="size-4 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1 text-sm">Adopt this desk to {what}.</span>
      <Button asChild size="sm" variant="outline">
        <Link to="/install/cloud">Open the Cloud tab</Link>
      </Button>
    </div>
  )
}
