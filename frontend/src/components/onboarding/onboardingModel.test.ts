// @vitest-environment jsdom
import { describe, expect, it } from "vitest"
import {
  cloudCandidate,
  importedProjects,
  importOrder,
  onboardingImportsStore,
  uniqueProjectName,
  type ImportResults,
} from "./onboardingModel"

describe("importOrder", () => {
  it("puts venues first and keeps the rest in their listed order", () => {
    const cloud = (name: string, kind: "SHOW" | "VENUE", syncedHere = false) =>
      cloudCandidate({
        repoId: name,
        url: `https://cloud.example/git/org/${name}.git`,
        name,
        kind,
        projectUuid: name,
        sizeBytes: 0,
        syncedHere,
      })
    const order = importOrder([cloud("Panto", "SHOW"), cloud("Hall", "VENUE", true), cloud("Gala", "SHOW"), cloud("Barn", "VENUE")])
    expect(order.map((r) => r.name)).toEqual(["Hall", "Barn", "Panto", "Gala"])
    expect(order[0]).toMatchObject({ kind: "VENUE", alreadyHere: true, branch: "main" })
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
