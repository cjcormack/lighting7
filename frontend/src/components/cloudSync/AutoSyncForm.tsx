import { useEffect, useState } from "react"
import { toast } from "sonner"
import { AlertCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  useUpdateCloudSyncConfigMutation,
  AUTO_SYNC_MIN_INTERVAL_MS,
  type SyncConfig,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"

export const AUTO_SYNC_MIN_INTERVAL_SECONDS = AUTO_SYNC_MIN_INTERVAL_MS / 1000

const AUTO_SYNC_INTERVAL_HINT =
  `Minimum ${AUTO_SYNC_MIN_INTERVAL_SECONDS}s. The first tick fires after one full ` +
  `interval — recently-saved changes are not pushed mid-form-submit.`

/** Auto-sync toggle + interval, saved independently (advanced, defaults on). */
export function AutoSyncForm({ projectId, config }: { projectId: number; config: SyncConfig }) {
  const [updateConfig, { isLoading: isSaving }] = useUpdateCloudSyncConfigMutation()
  const [autoSyncEnabled, setAutoSyncEnabled] = useState(config.autoSyncEnabled)
  const [intervalSecondsStr, setIntervalSecondsStr] = useState(
    String(config.autoSyncIntervalMs != null
      ? Math.round(config.autoSyncIntervalMs / 1000)
      : AUTO_SYNC_MIN_INTERVAL_SECONDS),
  )

  // Reseed from server state (keyed on the values, not the object, so a refetch that
  // doesn't move these doesn't clobber an in-progress edit).
  useEffect(() => {
    setAutoSyncEnabled(config.autoSyncEnabled)
    setIntervalSecondsStr(String(config.autoSyncIntervalMs != null
      ? Math.round(config.autoSyncIntervalMs / 1000)
      : AUTO_SYNC_MIN_INTERVAL_SECONDS))
  }, [config.autoSyncEnabled, config.autoSyncIntervalMs])

  const intervalSecondsNum = Number(intervalSecondsStr)
  const intervalValid = Number.isFinite(intervalSecondsNum)
    && Number.isInteger(intervalSecondsNum)
    && intervalSecondsNum >= AUTO_SYNC_MIN_INTERVAL_SECONDS
  const intervalChanged = autoSyncEnabled
    && intervalValid
    && intervalSecondsNum * 1000 !== config.autoSyncIntervalMs
  const dirty = autoSyncEnabled !== config.autoSyncEnabled || intervalChanged

  const handleSave = async () => {
    if (autoSyncEnabled && !intervalValid) return
    try {
      await updateConfig({
        projectId,
        body: {
          autoSyncEnabled,
          autoSyncIntervalMs: autoSyncEnabled ? intervalSecondsNum * 1000 : null,
        },
      }).unwrap()
      toast.success("Auto-sync settings saved")
    } catch (err) {
      toast.error(`Failed to save auto-sync settings: ${formatError(err)}`)
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <input
          id="auto-sync-enabled"
          type="checkbox"
          checked={autoSyncEnabled}
          onChange={(e) => setAutoSyncEnabled(e.target.checked)}
        />
        <Label htmlFor="auto-sync-enabled" className="text-xs">
          Auto-sync periodically
        </Label>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-[160px_1fr] gap-4 items-start">
        <div className="space-y-1">
          <Label htmlFor="auto-sync-interval" className="text-xs">
            Interval (seconds)
          </Label>
          <Input
            id="auto-sync-interval"
            type="number"
            min={AUTO_SYNC_MIN_INTERVAL_SECONDS}
            step={1}
            value={intervalSecondsStr}
            onChange={(e) => setIntervalSecondsStr(e.target.value)}
            disabled={!autoSyncEnabled}
            className={autoSyncEnabled && !intervalValid ? "border-destructive" : undefined}
          />
        </div>
        <p className="text-xs text-muted-foreground self-end pb-1">{AUTO_SYNC_INTERVAL_HINT}</p>
      </div>
      {autoSyncEnabled && !intervalValid && (
        <p className="text-xs text-destructive flex items-center gap-1">
          <AlertCircle className="size-3" />
          Interval must be a whole number ≥ {AUTO_SYNC_MIN_INTERVAL_SECONDS} seconds.
        </p>
      )}
      <div className="flex justify-end">
        <Button
          size="sm"
          onClick={handleSave}
          disabled={!dirty || (autoSyncEnabled && !intervalValid) || isSaving}
        >
          {isSaving ? "Saving…" : "Save"}
        </Button>
      </div>
    </div>
  )
}
