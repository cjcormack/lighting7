import { useEffect, useState } from "react"
import { Loader2, X } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { formatCountdown } from "@/lib/formatCountdown"
import {
  useCancelResetTokenMutation,
  useResetTokenHistoryQuery,
  type ResetTokenHistoryEntry,
  type ResetTokenStatus,
} from "@/store/users"

/** Matches `ResetQrSheet`'s cadence — an admin watching a phone across the room. */
const POLL_INTERVAL_MS = 2000

const STATUS_LABELS: Record<ResetTokenStatus, string> = {
  PENDING: "Live",
  USED: "Used",
  EXPIRED: "Expired",
  CANCELLED: "Cancelled",
}

/**
 * "Live" rather than "Pending" for PENDING: the admin is being asked whether a redeemable
 * link exists right now, and *live* answers that question in the word itself.
 */
const STATUS_VARIANTS: Record<ResetTokenStatus, "default" | "secondary" | "outline"> = {
  PENDING: "default",
  USED: "secondary",
  EXPIRED: "outline",
  CANCELLED: "outline",
}

function formatWhen(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    dateStyle: "short",
    timeStyle: "short",
  })
}

/**
 * A user's reset links and what became of them.
 *
 * This exists because closing `ResetQrSheet` no longer cancels the link it was showing
 * (`FU-AUTH-RESET-TOKEN-HISTORY`). That change traded a silent guarantee — "the link dies with
 * the sheet" — for a visible one, and this list is the visible half: a live link is on screen
 * with its time left, and revoking it is a deliberate act rather than a side effect of
 * navigating away.
 *
 * Renders nothing at all when there is no history, so a user who has never needed a reset
 * shows no chrome for a feature they have never used.
 */
export function ResetTokenHistory({ userId }: { userId: number }) {
  const [now, setNow] = useState(() => Date.now())
  const [cancelResetToken, { isLoading: isCancelling }] = useCancelResetTokenMutation()

  // Polled, not fetched once. The two events that change a row's status happen where this
  // client can't see them — the *phone* redeems the link, and expiry is just the clock passing
  // — so nothing invalidates `ResetTokenList` for either. Without a poll the row an admin is
  // watching stays badged "Live" after the operator has already used it, which is the exact
  // question the list exists to answer. Same cadence as the QR sheet's own poll.
  const { data: rows, isLoading } = useResetTokenHistoryQuery(
    { userId },
    { pollingInterval: POLL_INTERVAL_MS },
  )

  // A PENDING row whose clock has run out is EXPIRED, whatever the last poll said: the response
  // is up to a poll interval stale, and "Live · expires in 0:00" is worse than simply wrong —
  // it says a link is redeemable at the moment it stopped being one. The backend stays
  // authoritative for redemption; this is display only.
  const displayed = rows?.map((row) =>
    row.status === "PENDING" && Date.parse(row.expiresAt) <= now
      ? { ...row, status: "EXPIRED" as const }
      : row,
  )
  const hasLive = displayed?.some((row) => row.status === "PENDING") ?? false

  // Ticks only while a live row is on screen — a clock with nothing counting down is wasted
  // renders.
  useEffect(() => {
    if (!hasLive) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [hasLive])

  if (isLoading) {
    return (
      <div className="flex justify-center p-2">
        <Loader2 className="size-4 animate-spin" />
      </div>
    )
  }
  if (displayed == null || displayed.length === 0) return null

  return (
    <div className="space-y-2">
      <h4 className="text-xs font-medium text-muted-foreground">Reset links</h4>
      <ul className="space-y-1">
        {displayed.map((row) => (
          <ResetTokenRow
            key={row.id}
            row={row}
            now={now}
            busy={isCancelling}
            onCancel={() => void cancelResetToken({ userId, tokenId: row.id })}
          />
        ))}
      </ul>
    </div>
  )
}

function ResetTokenRow({
  row,
  now,
  busy,
  onCancel,
}: {
  row: ResetTokenHistoryEntry
  now: number
  busy: boolean
  onCancel: () => void
}) {
  const live = row.status === "PENDING"
  return (
    <li className="flex items-center gap-2 rounded-md border px-2 py-1.5 text-xs">
      <Badge variant={STATUS_VARIANTS[row.status]}>{STATUS_LABELS[row.status]}</Badge>
      <div className="min-w-0 flex-1 text-muted-foreground">
        <div className="truncate">
          {formatWhen(Date.parse(row.createdAt))}
          {row.createdByDisplayName != null && ` · by ${row.createdByDisplayName}`}
        </div>
        {live && (
          <div className="tabular-nums">
            Expires in {formatCountdown(Date.parse(row.expiresAt) - now)}
          </div>
        )}
      </div>
      {/* Only a live link can be revoked; the rest are already spent, and a Cancel button on
          them would imply otherwise. */}
      {live && (
        <Button variant="ghost" size="icon" disabled={busy} onClick={onCancel}>
          <X className="size-4" />
          <span className="sr-only">Cancel this reset link</span>
        </Button>
      )}
    </li>
  )
}
