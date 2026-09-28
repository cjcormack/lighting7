import { useEffect, useRef } from "react"
import { useLocation } from "react-router"
import { toast } from "sonner"
import { lightingApi } from "@/api/lightingApi"
import { useProjectListQuery } from "@/store/projects"

/**
 * Global sync-event toaster. Mounted once at the Layout level so cross-tab and
 * cross-project sync events surface even when the user isn't on the sync page.
 *
 * The sync page already toasts on its own mutation results — this component skips
 * events for the project the user is currently viewing on `/sync/projects/{id}` to
 * avoid double-toasting in the common single-tab case.
 */
export function SyncNotifications() {
  const { data: projects } = useProjectListQuery()
  const location = useLocation()
  // Capture the current pathname in a ref so the WS subscription doesn't tear down
  // and rebind on every navigation.
  const pathnameRef = useRef(location.pathname)
  pathnameRef.current = location.pathname
  const projectsRef = useRef(projects)
  projectsRef.current = projects

  useEffect(() => {
    const projectName = (id: number) =>
      projectsRef.current?.find((p) => p.id === id)?.name ?? `project ${id}`

    const onSyncPageFor = (id: number) =>
      pathnameRef.current === `/sync/projects/${id}`

    const subDone = lightingApi.cloudSync.subscribeDone((event) => {
      if (onSyncPageFor(event.projectId)) return
      const name = projectName(event.projectId)
      const summary = event.message?.trim()
        ? event.message
        : `${event.outcome.toLowerCase().replace(/_/g, " ")} (${event.headSha.slice(0, 7)})`
      toast.success(`${name}: ${summary}`, { id: `cloud-sync-${event.projectId}` })
    })

    const subFailed = lightingApi.cloudSync.subscribeFailed((event) => {
      if (onSyncPageFor(event.projectId)) return
      toast.error(`${projectName(event.projectId)}: ${event.message}`, {
        id: `cloud-sync-${event.projectId}`,
        duration: 8000,
      })
    })

    const subConflicts = lightingApi.cloudSync.subscribeConflictsPending((event) => {
      if (onSyncPageFor(event.projectId)) return
      const name = projectName(event.projectId)
      toast.warning(
        `${name}: ${event.conflictCount} sync conflict(s) — open Cloud Sync to resolve`,
        {
          id: `cloud-sync-${event.projectId}`,
          duration: 10000,
        },
      )
    })

    return () => {
      subDone.unsubscribe()
      subFailed.unsubscribe()
      subConflicts.unsubscribe()
    }
  }, [])

  return null
}
