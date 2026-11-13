import { useEffect } from "react"
import { toast } from "sonner"
import { lightingApi } from "@/api/lightingApi"
import {
  useCloudSyncConfigQuery,
  useCloudSyncConflictsQuery,
  useCloudSyncRunMutation,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"
import { errorCode, venueSyncRefusal } from "@/lib/venueSyncRefusal"

const GITHUB_RETIRED_TITLE = "This project still syncs with GitHub"
const GITHUB_RETIRED_HINT = "The desk does not sync with GitHub — move it to the cloud from its Sync tab"
const NOT_A_CLOUD_REPO_HINT = "Its repository is not one of this desk's cloud's, so the desk cannot sync with it"

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
      else if (errorCode(err) === "GITHUB_RETIRED") toast.error(GITHUB_RETIRED_TITLE, { id, description: GITHUB_RETIRED_HINT })
      else toast.error(`Sync failed: ${formatError(err)}`, { id })
    }
  }

  // The first failing prerequisite is the reason. An open conflict session would refuse the run
  // with SESSION_PENDING.
  const sessionPending = conflictsData?.activeSession === true
  const disabledReason = (() => {
    if (!config?.repoUrl) return "No repository attached — enable cloud sync first"
    if (config.cloud !== true) return config.github === true ? GITHUB_RETIRED_HINT : NOT_A_CLOUD_REPO_HINT
    if (sessionPending) return "Resolve or abort the open conflict session first"
    return null
  })()

  return { config, run, isSyncing, disabledReason, enabled: disabledReason == null && !isSyncing }
}
