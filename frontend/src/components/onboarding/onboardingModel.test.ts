// @vitest-environment jsdom
import { describe, expect, it } from "vitest"
import type { GithubRepo } from "@/store/oauthGithub"
import {
  importedProjects,
  importOrder,
  onboardingImportsStore,
  repoProjectName,
  uniqueProjectName,
  type ImportResults,
} from "./onboardingModel"

function repo(name: string, projectKind: GithubRepo["projectKind"], projectName: string | null = name): GithubRepo {
  return {
    fullName: `me/${name}`,
    name,
    owner: "me",
    private: true,
    defaultBranch: "main",
    htmlUrl: `https://github.com/me/${name}`,
    cloneUrl: `https://github.com/me/${name}.git`,
    description: null,
    pushPermission: true,
    lightingProject: true,
    projectName,
    projectDescription: null,
    projectUuid: null,
    projectKind,
  }
}

describe("importOrder", () => {
  it("puts venues first and keeps the rest in their listed order", () => {
    const order = importOrder([repo("panto", "SHOW"), repo("hall", "VENUE"), repo("gala", null), repo("barn", "VENUE")])
    expect(order.map((r) => r.name)).toEqual(["hall", "barn", "panto", "gala"])
  })
})

describe("repoProjectName", () => {
  it("prefers project.json's name over the repo's", () => {
    expect(repoProjectName(repo("panto-2026", "SHOW", "Panto"))).toBe("Panto")
    expect(repoProjectName(repo("panto-2026", "SHOW", null))).toBe("panto-2026")
  })
})

describe("importedProjects", () => {
  it("lists the imported projects shows first, skipping failures and imports in flight", () => {
    const results: ImportResults = {
      a: { status: "done", projectId: 4, name: "Hall", kind: "VENUE" },
      b: { status: "failed", error: "nope" },
      c: { status: "done", projectId: 5, name: "Panto", kind: "SHOW" },
      d: { status: "importing" },
    }
    expect(importedProjects(results).map((p) => p.projectId)).toEqual([5, 4])
  })
})

describe("uniqueProjectName", () => {
  it("keeps a free name and numbers a taken one past every taken number", () => {
    expect(uniqueProjectName("Panto", new Set(["Default"]))).toBe("Panto")
    expect(uniqueProjectName("Default", new Set(["Default", "Default (2)"]))).toBe("Default (3)")
  })

  it("compares exactly, as the importer does", () => {
    expect(uniqueProjectName("default", new Set(["Default"]))).toBe("default")
  })
})

describe("onboardingImportsStore", () => {
  it("keeps finished and failed imports and drops one in flight", () => {
    window.sessionStorage.setItem(
      "onboarding.imports",
      JSON.stringify({
        a: { status: "done", projectId: 4, name: "Hall", kind: "VENUE" },
        b: { status: "failed", error: "nope" },
        c: { status: "importing" },
        d: { status: "done", projectId: "x" },
      }),
    )
    onboardingImportsStore.reset()
    expect(onboardingImportsStore.getSnapshot()).toEqual({
      a: { status: "done", projectId: 4, name: "Hall", kind: "VENUE" },
      b: { status: "failed", error: "nope" },
    })
  })
})
