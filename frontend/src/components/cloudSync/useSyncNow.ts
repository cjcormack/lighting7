import { useEffect } from "react"
import { toast } from "sonner"
import { lightingApi } from "@/api/lightingApi"
import {
  useCloudSyncConfigQuery,
  useCloudSyncConflictsQuery,
  useCloudSyncRunMutation,
} from "@/store/cloudSync"
import { useOauthGithubIdentityQuery } from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"
import { venueSyncRefusal } from "@/lib/venueSyncRefusal"

/**
 * *Sync now* for one project: the run, what it toasts, and why it cannot run — the Sync tab's
 * Status panel and a show's home both press it. Every cloud-sync route is an admin's, so a caller
 * that may be an operator passes [skip].
 *
 * A venue ([isVenue]) reports the versions it fetched and pushed in the desk's own words.
 */
export function useSyncNow(projectId: number, { isVenue = false, skip = false }: { isVenue?: boolean; skip?: boolean } = {}) {
  const { data: config, refetch: refetchConfig } = useCloudSyncConfigQuery(projectId, { skip })
  // A run started elsewhere — another window, the auto-sync engine — moves *last synced* too.
  useEffect(() => {
    if (skip) return
    const sub = lightingApi.cloudSync.subscribeDone((event) => {
      if (event.projectId === projectId) refetchConfig()
    })
    return () => sub.unsubscribe()
  }, [skip, projectId, refetchConfig])
  const { data: conflictsData } = useCloudSyncConflictsQuery(projectId, { skip })
  const { data: identity } = useOauthGithubIdentityQuery(undefined, { skip })
  const [runSync, { isLoading: isSyncing }] = useCloudSyncRunMutation()

  // The global toaster says the same run's `cloudSyncDone` under this id; sharing it makes one toast.
  const id = `cloud-sync-${projectId}`
  const run = async () => {
    try {
      const result = await runSync(projectId).unwrap()
      if (isVenue) {
        // The desk names the versions (`Fetched v3. Pushed v4.`), which a commit count would not.
        if (result.outcome === "NO_OP") toast(result.message, { id })
        else toast.success(result.message, { id })
        return
      }
      switch (result.outcome) {
        case "NO_OP":
          toast("Already in sync — nothing to push or pull", { id })
          break
        case "PUSHED":
          toast.success(`Pushed ${result.pushed} commit(s) to remote`, { id })
          break
        case "FAST_FORWARDED":
          toast.success(`Pulled ${result.pulled} commit(s) from remote`, { id })
          break
        case "MERGED":
          toast.success(
            result.pushed + result.pulled > 0
              ? `Merged ${result.pushed + result.pulled} commit(s) cleanly`
              : "Merged with remote",
            { id },
          )
          break
        case "CONFLICTS_PENDING":
          toast.warning(
            `Found ${result.conflictCount ?? 0} conflict(s) — resolve them in the project's Sync tab`,
            { id, duration: 8000 },
          )
          break
      }
    } catch (err) {
      // `VENUE_MISSING` (this show's new pin is a version the desk lacks) and `VENUE_VERSION_TAKEN`
      // (two desks published one number) say what to do; anything else is the desk's sentence.
      const refusal = venueSyncRefusal(err)
      if (refusal) toast.error(refusal.title, { id, description: refusal.message })
      else toast.error(`Sync failed: ${formatError(err)}`, { id })
    }
  }

  // Sync-now needs all three prerequisites; the first failing one is the reason. Also blocked
  // while a conflict session is open, which the run would refuse with SESSION_PENDING.
  const sessionPending = conflictsData?.activeSession === true
  // A rejected OAuth identity is not a credential: `connected` alone would enable a "Sync now"
  // that can only fail. The PAT half still stands on its own.
  const oauthUsable = identity?.connected === true && identity.reauthRequired !== true
  const hasCredentials = oauthUsable || config?.tokenPresent === true
  const disabledReason = (() => {
    if (!config?.repoUrl) return "No repository attached — enable cloud sync first"
    if (identity?.reauthRequired === true && config?.tokenPresent !== true) {
      return "GitHub rejected this desk's authorisation — reconnect GitHub in Install Settings › Sync"
    }
    if (!hasCredentials) return "Connect GitHub or store a Personal Access Token"
    if (sessionPending) return "Resolve or abort the open conflict session first"
    return null
  })()

  return { config, run, isSyncing, disabledReason, enabled: disabledReason == null && !isSyncing }
}
