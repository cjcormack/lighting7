import { describe, expect, it } from "vitest"
import { cloudRefusalOf } from "./cloudSyncRefusal"

describe("cloudRefusalOf", () => {
  it("reads an activity-log line's code and keeps the cloud's words", () => {
    const refusal = cloudRefusalOf("SYNC_QUOTA_EXCEEDED: The org is at 500 MB. Ask the org's owner for more storage.")
    expect(refusal?.code).toBe("SYNC_QUOTA_EXCEEDED")
    expect(refusal?.message).toBe("The org is at 500 MB. Ask the org's owner for more storage.")
  })

  it("takes a failure's own code", () => {
    expect(cloudRefusalOf("The cloud repo was deleted: sync to a new one.", "REPO_NOT_FOUND")?.title).toBe(
      "The cloud repo is gone",
    )
  })

  it("is null for a failure that is not the cloud's refusal", () => {
    expect(cloudRefusalOf("AUTH_FAILED: GitHub said no")).toBeNull()
    expect(cloudRefusalOf("Something broke")).toBeNull()
  })
})
