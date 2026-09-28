import { toast } from 'sonner'
import { formatError } from '@/lib/formatError'

/**
 * Toast a refused sheet write **by code, keyed per column** (library-sheets plan D14).
 *
 * The library sheets' writes are mostly in `SILENT_ENDPOINTS`, because the dialogs they replaced
 * rendered their refusals inline; a sheet has no inline place, so the sheet says it — once, by the
 * code the desk answered with (`SPEED_MASTER_USAGE_TAKEN`, `SPEED_MASTER_FOLLOW_CYCLE`, a name
 * clash), in the sentence [messages] gives that code, and with the desk's own message as the
 * fallback. The middleware stays silent on those endpoints, or it would say it a second time,
 * generically. `rigWriteFailureMessage` in `store/busk.ts` is the pattern.
 *
 * [key] is the toast's id — `speed-masters:usage` — so a batch of four refused writes in one column
 * replaces rather than stacks, while a refusal in another column still gets its own say.
 */
export function reportSheetWriteFailure(
  err: unknown,
  {
    key,
    messages,
  }: {
    key: string
    /** Per-code sentences, handed the desk's own message. A code not listed shows that message. */
    messages?: Partial<Record<string, (message: string) => string>>
  },
): void {
  toast.error(sheetWriteFailureMessage(err, messages), { id: `sheet-write:${key}` })
}

/** The sentence [reportSheetWriteFailure] toasts — split out so a test can read it. */
export function sheetWriteFailureMessage(
  err: unknown,
  messages?: Partial<Record<string, (message: string) => string>>,
): string {
  const code = (err as { data?: { code?: string } } | null)?.data?.code
  const message = formatError(err)
  const phrase = code != null ? messages?.[code] : undefined
  return phrase ? phrase(message) : message
}
