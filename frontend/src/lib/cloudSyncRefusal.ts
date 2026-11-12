import type { SyncErrorCode } from "@/store/cloudSync"

/**
 * The cloud's refusals a sync run can meet (lighting7 cloud-p2 plan D4), each with a title. The message
 * under the title is the desk's, in the cloud's words and saying what to do.
 */
export const CLOUD_REFUSALS: Partial<Record<SyncErrorCode, { title: string }>> = {
  CLOUD_NOT_ADOPTED: { title: "This desk isn't adopted by Lighting7 Cloud" },
  SYNC_OFF: { title: "Cloud sync is off for this desk's org" },
  REPO_NOT_FOUND: { title: "The cloud repo is gone" },
  SYNC_QUOTA_EXCEEDED: { title: "The org is out of cloud storage" },
  SYNC_STORAGE_UNAVAILABLE: { title: "The cloud can't store the push just now" },
}

export interface CloudRefusal {
  code: SyncErrorCode
  title: string
  /** The desk's words, the code's prefix taken off. */
  message: string
}

/** A refusal of the cloud's, from a failure's code and message or an activity-log line's `CODE: words`. */
export function cloudRefusalOf(message: string, code?: string | null): CloudRefusal | null {
  const prefixed = /^([A-Z_]+):\s*(.*)$/s.exec(message.trim())
  const found = (code ?? prefixed?.[1]) as SyncErrorCode | undefined
  const entry = found ? CLOUD_REFUSALS[found] : undefined
  if (!found || !entry) return null
  const words = prefixed && prefixed[1] === found ? prefixed[2] : message.trim()
  return { code: found, ...entry, message: words }
}
