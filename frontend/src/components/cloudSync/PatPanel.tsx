import { useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useSetCloudSyncCredentialsMutation,
  useClearCloudSyncCredentialsMutation,
  type SyncConfig,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"

/** Personal Access Token entry — the Advanced fallback when OAuth isn't usable. */
export function PatPanel({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const [setCredentials, { isLoading: isSettingPat }] = useSetCloudSyncCredentialsMutation()
  const [clearCredentials, { isLoading: isClearingPat }] = useClearCloudSyncCredentialsMutation()
  const [pat, setPat] = useState("")

  const handleSetPat = async () => {
    const trimmed = pat.trim()
    if (!trimmed) return
    try {
      await setCredentials({ projectId, pat: trimmed }).unwrap()
      setPat("")
      toast.success("Personal Access Token stored")
    } catch (err) {
      toast.error(`Failed to store PAT: ${formatError(err)}`)
    }
  }

  const handleClearPat = async () => {
    try {
      await clearCredentials(projectId).unwrap()
      toast.success("Personal Access Token cleared")
    } catch (err) {
      toast.error(`Failed to clear PAT: ${formatError(err)}`)
    }
  }

  return (
    <div className="space-y-2">
      <p className="text-xs text-muted-foreground">
        For headless rigs, GitHub Enterprise, or as an override when OAuth isn&rsquo;t
        configured. Stored in the OS keychain (or an encrypted file fallback). Needs{" "}
        <code className="text-xs">repo</code> scope. The token is never returned to
        the UI &mdash; clear and re-enter to rotate.
      </p>
      <div className="flex gap-2 items-end">
        <div className="space-y-1 flex-1">
          <Label htmlFor="sync-pat" className="sr-only">PAT</Label>
          <Input
            id="sync-pat"
            type="password"
            value={pat}
            onChange={(e) => setPat(e.target.value)}
            placeholder={config.tokenPresent ? "•••• stored — enter new to rotate" : "ghp_…"}
            autoComplete="off"
          />
        </div>
        <Button
          onClick={handleSetPat}
          disabled={!pat.trim() || isSettingPat || !config.repoUrl}
        >
          {isSettingPat ? "Storing…" : "Set token"}
        </Button>
        {config.tokenPresent && (
          <Button
            variant="outline"
            onClick={handleClearPat}
            disabled={isClearingPat}
          >
            {isClearingPat ? "Clearing…" : "Clear"}
          </Button>
        )}
      </div>
      {!config.repoUrl && (
        <p className="text-xs text-amber-600">
          Set a repository above before storing a token.
        </p>
      )}
    </div>
  )
}
