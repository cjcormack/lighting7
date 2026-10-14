import type { ProjectKind } from "@/api/projectApi"
import type { GithubRepo } from "@/store/oauthGithub"
import { createSyncStore, sessionStorageArea } from "@/lib/syncStore"

/**
 * A new desk's onboarding (lighting7 `routes/installOnboarding.kt`): diagnostics, then where the
 * first projects come from — cloud sync, or the blank project the desk booted on.
 */
export type OnboardingStep = "diagnostics" | "projects"

/**
 * Per tab, so the GitHub web flow — which leaves the page and comes back to `/` — lands on the
 * step it left from.
 */
export const onboardingStepStore = createSyncStore<OnboardingStep>({
  key: "onboarding.step",
  fallback: "diagnostics",
  parse: (v) => (v === "projects" ? "projects" : "diagnostics"),
  storage: sessionStorageArea,
})

/** One repo's import, as the projects step draws it. */
export type ImportResult =
  | { status: "importing" }
  | { status: "done"; projectId: number; name: string; kind: ProjectKind }
  | { status: "failed"; error: string }

/** Each repo's import by clone URL. */
export type ImportResults = Readonly<Record<string, ImportResult>>

/**
 * Per tab, beside the step: an import has made a project on the desk, so Back or a reload must not
 * forget it, or there is nothing left to open it from. An import in flight when the page went is
 * not kept — its outcome was never seen here.
 */
export const onboardingImportsStore = createSyncStore<ImportResults>({
  key: "onboarding.imports",
  fallback: {},
  parse: (v) => {
    if (v == null || typeof v !== "object" || Array.isArray(v)) return {}
    const kept: Record<string, ImportResult> = {}
    for (const [url, r] of Object.entries(v as Record<string, unknown>)) {
      const result = r as Partial<{ status: string; projectId: unknown; name: unknown; kind: unknown; error: unknown }>
      if (result?.status === "done" && typeof result.projectId === "number" && typeof result.name === "string") {
        const kind = result.kind === "VENUE" ? "VENUE" : "SHOW"
        kept[url] = { status: "done", projectId: result.projectId, name: result.name, kind }
      } else if (result?.status === "failed" && typeof result.error === "string") {
        kept[url] = { status: "failed", error: result.error }
      }
    }
    return kept
  },
  storage: sessionStorageArea,
})

/** A repo's project name, as `ImportFromRemoteDialog` pre-fills it. */
export function repoProjectName(repo: GithubRepo): string {
  return repo.projectName ?? repo.name
}

/**
 * [base], or `base (2)`, `base (3)`… — the first not in [taken]. Project names are unique on the
 * desk, compared exactly as the importer does, and a repo named like the project the desk booted on
 * (or like another repo in the same batch) must still import.
 */
export function uniqueProjectName(base: string, taken: ReadonlySet<string>): string {
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) {
    const candidate = `${base} (${n})`
    if (!taken.has(candidate)) return candidate
  }
}

/**
 * The order to import [repos] in: venues first, since a show pinned to a venue is refused
 * (`VENUE_MISSING`) until its venue is on the desk. Otherwise the order they were listed in.
 */
export function importOrder(repos: readonly GithubRepo[]): GithubRepo[] {
  const venues = repos.filter((r) => r.projectKind === "VENUE")
  return [...venues, ...repos.filter((r) => r.projectKind !== "VENUE")]
}

/** The imported projects, shows before venues, each once. */
export function importedProjects(
  results: ImportResults,
): { projectId: number; name: string; kind: ProjectKind }[] {
  const done = Object.values(results).flatMap((r) => (r.status === "done" ? [r] : []))
  return [...done.filter((r) => r.kind !== "VENUE"), ...done.filter((r) => r.kind === "VENUE")].map(
    ({ projectId, name, kind }) => ({ projectId, name, kind }),
  )
}
