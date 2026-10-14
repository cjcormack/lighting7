import { useEffect, useMemo, useState, useSyncExternalStore } from "react"
import { useDispatch } from "react-redux"
import { AlertTriangle, Check, Loader2, Search } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { IdentityRow } from "@/components/cloudSync/IdentityRow"
import { formatError } from "@/lib/formatError"
import { errorCode, venueSyncRefusal } from "@/lib/venueSyncRefusal"
import { restApi } from "@/store/restApi"
import { useCloudSyncConfigsQuery, useCloudSyncImportMutation } from "@/store/cloudSync"
import { useFinishOnboardingMutation } from "@/store/installs"
import {
  useListGithubReposQuery,
  useOauthGithubIdentityQuery,
  type GithubRepo,
} from "@/store/oauthGithub"
import { useCurrentProjectQuery, useProjectListQuery, useUpdateProjectMutation } from "@/store/projects"
import {
  importedProjects,
  importOrder,
  onboardingImportsStore,
  onboardingStepStore,
  repoProjectName,
  uniqueProjectName,
  type ImportResult,
  type ImportResults,
} from "./onboardingModel"

/**
 * Where a new desk's first projects come from. With GitHub connected, the lighting repos it can
 * see are listed and the chosen ones imported as continuously synced projects
 * (`POST /cloud-sync/import`, venues first); finishing opens one of them, and the desk removes the
 * empty project it booted on. Without, or by choice, the desk keeps that project — named here — as
 * a blank one.
 */
export function OnboardingProjectsStep({ onBack }: { onBack: () => void }) {
  const { data: identity, isLoading: identityLoading } = useOauthGithubIdentityQuery()
  const oauthUsable = identity?.connected === true && identity.reauthRequired !== true

  const results = useSyncExternalStore(
    onboardingImportsStore.subscribe,
    onboardingImportsStore.getSnapshot,
    onboardingImportsStore.getServerSnapshot,
  )
  const imported = importedProjects(results)
  const [openId, setOpenId] = useState<number | null>(null)
  const openProjectId = openId ?? imported[0]?.projectId

  const dispatch = useDispatch()
  const [finish, { isLoading: finishing, error: finishError }] = useFinishOnboardingMutation()
  // Finished from another window: the desk is past onboarding, so this one is too.
  const alreadyDone = errorCode(finishError) === "ONBOARDING_DONE"
  const { data: current } = useCurrentProjectQuery()
  const [updateProject, { isLoading: renaming }] = useUpdateProjectMutation()
  const [blankName, setBlankName] = useState<string | null>(null)
  const name = (blankName ?? current?.name ?? "").trim()

  const done = async (openProjectId?: number) => {
    try {
      const answer = await finish(openProjectId != null ? { openProjectId } : {}).unwrap()
      onboardingStepStore.set("diagnostics")
      onboardingImportsStore.set({})
      if (answer.removedPlaceholder) {
        toast.success(`Removed the empty "${answer.removedPlaceholder}" project this desk started with`)
      }
    } catch (err) {
      // Anything else is drawn below from the mutation's own error.
      if (errorCode(err) === "ONBOARDING_DONE") dispatch(restApi.util.invalidateTags(["Install"]))
    }
  }

  const startBlank = async () => {
    if (current != null && name !== "" && name !== current.name) {
      // A failure is errorToastMiddleware's to report.
      const renamed = await updateProject({ id: current.id, name }).unwrap().catch(() => null)
      if (renamed == null) return
    }
    await done()
  }

  const busy = finishing || renaming

  return (
    <div className="space-y-5">
      <section className="space-y-3">
        <h3 className="text-sm font-medium">From GitHub</h3>
        {identityLoading || identity == null ? (
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            Checking GitHub connection…
          </div>
        ) : !identity.oauthConfigured ? (
          <p className="text-sm text-muted-foreground">
            GitHub sign-in isn&apos;t configured on this desk, so projects can&apos;t be fetched
            here. Start with a blank project below; a repository can be attached to it later from
            its Sync settings.
          </p>
        ) : (
          <>
            <IdentityRow projectId={null} />
            {oauthUsable && (
              <RepoChecklist results={results} disabled={busy} />
            )}
          </>
        )}
      </section>

      {finishError != null && !alreadyDone && (
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertDescription>{formatError(finishError)}</AlertDescription>
        </Alert>
      )}

      {imported.length > 0 ? (
        <section className="space-y-2 border-t pt-4">
          <Label htmlFor="onboarding-open">Open first</Label>
          <Select
            value={openProjectId != null ? String(openProjectId) : ""}
            onValueChange={(v) => setOpenId(Number(v))}
          >
            <SelectTrigger id="onboarding-open" className="w-full">
              <SelectValue placeholder="Choose a project" />
            </SelectTrigger>
            <SelectContent>
              {imported.map((p) => (
                <SelectItem key={p.projectId} value={String(p.projectId)}>
                  {p.name}
                  {p.kind === "VENUE" ? " · venue" : ""}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <div className="flex justify-between gap-2 pt-2">
            <Button variant="outline" onClick={onBack} disabled={busy}>
              Back
            </Button>
            <Button onClick={() => void done(openProjectId)} disabled={busy || openProjectId == null}>
              {finishing && <Loader2 className="size-4 animate-spin" />}
              Finish
            </Button>
          </div>
        </section>
      ) : (
        <section className="space-y-2 border-t pt-4">
          <Label htmlFor="onboarding-blank-name">Or start with a blank project</Label>
          <Input
            id="onboarding-blank-name"
            value={blankName ?? current?.name ?? ""}
            onChange={(e) => setBlankName(e.target.value)}
            disabled={current == null || busy}
          />
          <div className="flex justify-between gap-2 pt-2">
            <Button variant="outline" onClick={onBack} disabled={busy}>
              Back
            </Button>
            <Button onClick={() => void startBlank()} disabled={busy || name === ""}>
              {busy && <Loader2 className="size-4 animate-spin" />}
              Start with a blank project
            </Button>
          </div>
        </section>
      )}
    </div>
  )
}

/**
 * The lighting repos GitHub lets this desk see, each a checkbox, and an Import that brings the
 * chosen ones in one at a time — venues first — drawing each repo's outcome on its own row.
 */
function RepoChecklist({ results, disabled }: { results: ImportResults; disabled: boolean }) {
  const [search, setSearch] = useState("")
  const [query, setQuery] = useState("")
  useEffect(() => {
    const t = window.setTimeout(() => setQuery(search.trim()), 200)
    return () => window.clearTimeout(t)
  }, [search])

  const { data: repos, isFetching, error } = useListGithubReposQuery({
    query: query || null,
    perPage: 100,
    lightingOnly: true,
  })
  const { data: syncConfigs } = useCloudSyncConfigsQuery()
  const { data: projects } = useProjectListQuery()
  const [importRepo] = useCloudSyncImportMutation()
  const [selected, setSelected] = useState<Map<string, GithubRepo>>(new Map())
  const [importing, setImporting] = useState(false)

  // Clone URLs already attached to a project here — a repo cannot be imported twice.
  const linked = useMemo(
    () =>
      new Set(
        Object.values(syncConfigs ?? {})
          .map((c) => c.repoUrl)
          .filter((u): u is string => !!u),
      ),
    [syncConfigs],
  )

  const toggle = (repo: GithubRepo) =>
    setSelected((prev) => {
      const next = new Map(prev)
      if (next.has(repo.cloneUrl)) next.delete(repo.cloneUrl)
      else next.set(repo.cloneUrl, repo)
      return next
    })

  const record = (url: string, result: ImportResult) =>
    onboardingImportsStore.set({ ...onboardingImportsStore.getSnapshot(), [url]: result })

  const runImport = async () => {
    setImporting(true)
    // Names this batch takes as it goes, so two repos called alike both import.
    const taken = new Set((projects ?? []).map((p) => p.name))
    for (const repo of importOrder([...selected.values()])) {
      const url = repo.cloneUrl
      record(url, { status: "importing" })
      const projectName = uniqueProjectName(repoProjectName(repo), taken)
      let result: ImportResult
      try {
        const answer = await importRepo({ repoUrl: url, branch: repo.defaultBranch, projectName }).unwrap()
        taken.add(answer.name)
        result = {
          status: "done",
          projectId: answer.projectId,
          name: answer.name,
          kind: repo.projectKind ?? "SHOW",
        }
      } catch (err) {
        // A show whose venue is not on this desk (`VENUE_MISSING`) says to import the venue first.
        const refusal = venueSyncRefusal(err)
        result = {
          status: "failed",
          error: refusal ? `${refusal.title}: ${refusal.message}` : formatError(err),
        }
      }
      record(url, result)
    }
    setSelected(new Map())
    setImporting(false)
  }

  const locked = importing || disabled

  return (
    <div className="space-y-2">
      <div className="relative">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          aria-label="Search repositories"
          placeholder="Search repositories…"
          className="pl-8"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      <div className="max-h-72 overflow-y-auto rounded-md border">
        {error != null ? (
          <p className="p-3 text-sm text-destructive">{formatError(error)}</p>
        ) : repos == null ? (
          <div className="flex items-center gap-2 p-3 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            Looking for lighting projects…
          </div>
        ) : repos.length === 0 ? (
          <p className="p-3 text-sm text-muted-foreground">
            {query
              ? "No lighting projects match that search."
              : "No lighting projects in the repositories GitHub lets this desk see."}
          </p>
        ) : (
          <ul className="divide-y">
            {repos.map((repo) => (
              <RepoRow
                key={repo.cloneUrl}
                repo={repo}
                result={results[repo.cloneUrl]}
                alreadyHere={linked.has(repo.cloneUrl)}
                checked={selected.has(repo.cloneUrl)}
                disabled={locked}
                onToggle={() => toggle(repo)}
              />
            ))}
          </ul>
        )}
      </div>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">
          {isFetching && repos != null ? "Refreshing…" : "Venues import before the shows built on them."}
        </span>
        <Button size="sm" onClick={() => void runImport()} disabled={locked || selected.size === 0}>
          {importing && <Loader2 className="size-4 animate-spin" />}
          {selected.size > 0 ? `Import ${selected.size}` : "Import"}
        </Button>
      </div>
    </div>
  )
}

function RepoRow({
  repo,
  result,
  alreadyHere,
  checked,
  disabled,
  onToggle,
}: {
  repo: GithubRepo
  result: ImportResult | undefined
  alreadyHere: boolean
  checked: boolean
  disabled: boolean
  onToggle: () => void
}) {
  const id = `onboarding-repo-${repo.fullName}`
  const settled = result?.status === "done" || result?.status === "importing" || alreadyHere
  return (
    <li className="flex items-start gap-3 px-3 py-2">
      <input
        id={id}
        type="checkbox"
        className="mt-1"
        checked={checked || result?.status === "done"}
        disabled={disabled || settled}
        onChange={onToggle}
      />
      <label htmlFor={id} className="min-w-0 flex-1 space-y-0.5">
        <span className="flex items-center gap-2 text-sm font-medium">
          <span className="truncate">{repoProjectName(repo)}</span>
          {repo.projectKind === "VENUE" && (
            <Badge variant="outline" className="text-[10px]">
              Venue
            </Badge>
          )}
        </span>
        <span className="block truncate font-mono text-xs text-muted-foreground">{repo.fullName}</span>
        {result?.status === "failed" && (
          <span className="block text-xs text-destructive">{result.error}</span>
        )}
      </label>
      <span className="shrink-0 pt-0.5 text-xs text-muted-foreground">
        {result?.status === "importing" ? (
          <Loader2 className="size-4 animate-spin" aria-label="Importing" />
        ) : result?.status === "done" ? (
          <span className="flex items-center gap-1 text-foreground">
            <Check className="size-4" />
            Imported
          </span>
        ) : alreadyHere ? (
          "Already on this desk"
        ) : null}
      </span>
    </li>
  )
}
