import { useEffect, useState } from "react"
import { useNavigate } from "react-router"
import { AlertTriangle, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { lightingApi } from "@/api/lightingApi"
import { useProjectListQuery } from "@/store/projects"
import { useIsNavAdmin } from "@/navigation"
import { cloudRefusalOf, type CloudRefusal } from "@/lib/cloudSyncRefusal"

/**
 * App-wide warning that the cloud refused a project's sync for a reason
 * a retry will not fix (lighting7 cloud-p2 plan D4): out of storage, sync off, the repo gone. Read
 * from the activity log's failures, which auto-sync writes too, and cleared by the project's next
 * good run. Dismissing hides that project's refusal, across reloads, until a different one or a good
 * run comes.
 */
const DISMISSED_KEY = "lighting7:cloudSyncRefusalDismissed"

function readDismissed(): string | null {
  try {
    return window.localStorage.getItem(DISMISSED_KEY)
  } catch {
    return null
  }
}

function writeDismissed(key: string | null) {
  try {
    if (key == null) window.localStorage.removeItem(DISMISSED_KEY)
    else window.localStorage.setItem(DISMISSED_KEY, key)
  } catch {
    // Storage unavailable: the dismissal lasts for this page only.
  }
}

const keyOf = (projectId: number, code: string) => `${projectId}:${code}`

export function CloudSyncRefusalBanner() {
  const navigate = useNavigate()
  const isAdmin = useIsNavAdmin()
  const { data: projects } = useProjectListQuery(undefined, { skip: !isAdmin })
  const [refusal, setRefusal] = useState<(CloudRefusal & { projectId: number }) | null>(null)

  useEffect(() => {
    const raise = (found: CloudRefusal | null, projectId: number) => {
      if (!found) return
      const dismissed = readDismissed()
      if (dismissed === keyOf(projectId, found.code)) return
      if (dismissed?.startsWith(`${projectId}:`)) writeDismissed(null)
      setRefusal({ ...found, projectId })
    }
    const logged = lightingApi.cloudSync.subscribeLogAppended(({ projectId, entry }) => {
      if (entry.level !== "ERROR") return
      raise(cloudRefusalOf(entry.message), projectId)
    })
    const failed = lightingApi.cloudSync.subscribeFailed(({ projectId, errorCode, message }) => {
      raise(cloudRefusalOf(message, errorCode), projectId)
    })
    const done = lightingApi.cloudSync.subscribeDone(({ projectId }) => {
      if (readDismissed()?.startsWith(`${projectId}:`)) writeDismissed(null)
      setRefusal((current) => (current?.projectId === projectId ? null : current))
    })
    return () => {
      logged.unsubscribe()
      failed.unsubscribe()
      done.unsubscribe()
    }
  }, [])

  if (!isAdmin || refusal == null) return null
  const name = projects?.find((p) => p.id === refusal.projectId)?.name ?? `Project ${refusal.projectId}`
  const dismiss = () => {
    writeDismissed(keyOf(refusal.projectId, refusal.code))
    setRefusal(null)
  }

  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm"
    >
      <AlertTriangle className="size-4 shrink-0 text-destructive" />
      <span className="min-w-0 flex-1">
        <strong className="font-semibold">
          {name}: {refusal.title}.
        </strong>{" "}
        {refusal.message}
      </span>
      <Button size="sm" onClick={() => navigate(`/projects/${refusal.projectId}/settings/sync`)}>
        Open Sync
      </Button>
      <Button variant="ghost" size="icon" aria-label="Dismiss" title="Dismiss" onClick={dismiss}>
        <X className="size-4" />
      </Button>
    </div>
  )
}
