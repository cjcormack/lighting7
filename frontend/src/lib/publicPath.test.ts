import { describe, expect, it } from "vitest"
import { isPublicPath } from "./publicPath"

describe("isPublicPath", () => {
  it("matches a reset token, with or without a trailing slash", () => {
    expect(isPublicPath("/reset/abc")).toBe(true)
    expect(isPublicPath("/reset/abc/")).toBe(true)
  })

  it("matches a device token, with or without a trailing slash", () => {
    expect(isPublicPath("/device/abc")).toBe(true)
    expect(isPublicPath("/device/abc/")).toBe(true)
  })

  it("matches case-insensitively, guarding against phone-keyboard auto-capitalisation", () => {
    expect(isPublicPath("/Device/abc")).toBe(true)
  })

  it("rejects a bare prefix with no token", () => {
    expect(isPublicPath("/device/")).toBe(false)
    expect(isPublicPath("/device")).toBe(false)
  })

  it("rejects a path with an extra segment after the token", () => {
    expect(isPublicPath("/device/abc/def")).toBe(false)
  })
})
