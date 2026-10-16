import { useEffect, useState } from "react"
import { useNavigate, useParams } from "react-router"
import { toast } from "sonner"
import { Card } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Loader2 } from "lucide-react"
import { useInstallQuery, useUpdateInstallMutation } from "@/store/installs"
import { useAuthStatusQuery } from "@/store/auth"
import { formatError } from "@/lib/formatError"
import { UsersTab } from "@/components/users/UsersTab"
import { DiagnosticsContent } from "./Diagnostics"
import { CloudSyncHubBody } from "./CloudSync"
import { UpdatePanel } from "@/components/updates/UpdatePanel"
import { RemoteAccessPanel } from "@/components/remoteAccess/RemoteAccessPanel"
import { TelemetryCard } from "@/components/telemetry/TelemetryCard"
import { useShownTabs } from "@/navigation"

const TABS = ["general", "users", "sync", "remote", "updates", "diagnostics"] as const
type Tab = (typeof TABS)[number]

/**
 * The nav row each tab opens: a tab is offered exactly where its row is (navigation plan P2), so
 * Users, Sync and Remote access go from an operator, whom every call behind them refuses.
 */
const TAB_NAV_IDS: Record<Tab, string> = {
  general: "install-settings",
  users: "users",
  sync: "sync",
  remote: "remote-access",
  updates: "updates",
  diagnostics: "diagnostics",
}

function isTab(value: string | undefined): value is Tab {
  return TABS.includes(value as Tab)
}

export function InstallSettings() {
  const { tab } = useParams()
  const navigate = useNavigate()
  const shown = useShownTabs(TAB_NAV_IDS)
  // A tab its row hides, reached by URL, opens General — as the sidebar lights Install Settings.
  const activeTab: Tab = isTab(tab) && shown.has(tab) ? tab : "general"
  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === "ADMIN"

  const handleTabChange = (value: string) => {
    const next = isTab(value) ? value : "general"
    navigate(next === "general" ? "/install" : `/install/${next}`, { replace: true })
  }

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="p-4 space-y-3 border-b">
        <div>
          <h1 className="text-lg font-semibold">Install Settings</h1>
          <p className="text-sm text-muted-foreground">
            Settings for this machine running lighting7. Shared across all projects.
          </p>
        </div>
        <Tabs value={activeTab} onValueChange={handleTabChange}>
          <TabsList>
            <TabsTrigger value="general">General</TabsTrigger>
            {shown.has("users") && <TabsTrigger value="users">Users</TabsTrigger>}
            {shown.has("sync") && <TabsTrigger value="sync">Sync</TabsTrigger>}
            {shown.has("remote") && <TabsTrigger value="remote">Remote access</TabsTrigger>}
            {/* Shown to everyone: the version, and whether the desk is about to restart, are
                things anyone standing at it should be able to read. The actions inside are
                admin-only, disabled in the panel and enforced per-route by the backend. */}
            {shown.has("updates") && <TabsTrigger value="updates">Updates</TabsTrigger>}
            {shown.has("diagnostics") && <TabsTrigger value="diagnostics">Diagnostics</TabsTrigger>}
          </TabsList>
        </Tabs>
      </div>
      <div className="flex-1 overflow-y-auto min-h-0 p-4">
        {activeTab === "general" && (
          <div className="space-y-4">
            <GeneralTab />
            {/* Admin only, like Remote access: the PUT is `requireAdmin`. */}
            {isAdmin && <TelemetryCard />}
          </div>
        )}
        {activeTab === "users" && <UsersTab />}
        {activeTab === "sync" && <CloudSyncHubBody />}
        {activeTab === "remote" && <RemoteAccessPanel />}
        {activeTab === "updates" && <UpdatePanel />}
        {activeTab === "diagnostics" && <DiagnosticsContent />}
      </div>
    </div>
  )
}

function GeneralTab() {
  const { data: install, isLoading } = useInstallQuery()
  const [updateInstall, { isLoading: isUpdating }] = useUpdateInstallMutation()
  const [friendlyName, setFriendlyName] = useState("")

  // Seed once per install identity. Depending on the whole `install` object
  // would clobber in-progress edits whenever the cache refreshes.
  useEffect(() => {
    if (install) {
      setFriendlyName(install.friendlyName)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [install?.uuid])

  if (isLoading || !install) {
    return (
      <div className="flex justify-center p-4">
        <Loader2 className="size-5 animate-spin" />
      </div>
    )
  }

  const trimmed = friendlyName.trim()
  const dirty = trimmed !== install.friendlyName
  const isValid = trimmed.length > 0
  const createdAt = new Date(install.createdAt).toLocaleString()

  const handleSave = async () => {
    if (!isValid) return
    try {
      await updateInstall({ friendlyName: trimmed }).unwrap()
      toast.success("Install settings saved")
    } catch (err) {
      toast.error(`Failed to save: ${formatError(err)}`)
    }
  }

  return (
    <Card className="p-4 max-w-2xl space-y-4">
      <p className="text-sm text-muted-foreground">
        This identifies the machine running lighting7. The friendly name appears in
        cloud-sync attribution and in exported project metadata.
      </p>
      <div className="space-y-2">
        <Label htmlFor="install-friendly-name">Friendly name *</Label>
        <Input
          id="install-friendly-name"
          value={friendlyName}
          onChange={(e) => setFriendlyName(e.target.value)}
          maxLength={100}
        />
      </div>
      <div className="space-y-1">
        <Label className="text-muted-foreground">Install UUID</Label>
        <div className="font-mono text-xs break-all">{install.uuid}</div>
      </div>
      <div className="space-y-1">
        <Label className="text-muted-foreground">Created</Label>
        <div className="text-sm">{createdAt}</div>
      </div>
      <div className="flex justify-end">
        <Button onClick={handleSave} disabled={!dirty || !isValid || isUpdating}>
          {isUpdating ? "Saving…" : "Save"}
        </Button>
      </div>
    </Card>
  )
}
