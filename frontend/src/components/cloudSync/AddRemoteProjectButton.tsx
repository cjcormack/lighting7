import { CloudDownload } from "lucide-react"
import { Button } from "@/components/ui/button"

/** Hub toolbar action opening *Import from the cloud*, which says itself what this desk lacks. */
export function AddRemoteProjectButton({ onClick }: { onClick: () => void }) {
  return (
    <Button variant="outline" size="sm" className="gap-1.5" onClick={onClick}>
      <CloudDownload className="size-3.5" />
      Add remote project
    </Button>
  )
}
