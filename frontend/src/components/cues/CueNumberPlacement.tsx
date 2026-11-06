import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import type { CueNumberOutOfOrder, CueNumberPlacement } from '@/api/cueStacksApi'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { canonicalCueNumber, CUE_NUMBER_RULE } from '@/lib/cueNumber'
import { toast } from 'sonner'

/** The desk's 409 `CUE_NUMBER_OUT_OF_ORDER` body inside a rejected request, or null for anything else. */
export function outOfOrderOf(error: unknown): CueNumberOutOfOrder | null {
  const e = error as { status?: unknown; data?: Partial<CueNumberOutOfOrder> } | null | undefined
  if (e?.status !== 409 || e.data?.code !== 'CUE_NUMBER_OUT_OF_ORDER' || typeof e.data.number !== 'string') return null
  return e.data as CueNumberOutOfOrder
}

/** Where a number reading out of order would sit, in the operator's words: "after Q3 and before Q2". */
export function outOfOrderWhere(ask: Pick<CueNumberOutOfOrder, 'previous' | 'next'>): string {
  const parts = [ask.previous != null && `after Q${ask.previous}`, ask.next != null && `before Q${ask.next}`].filter(Boolean)
  return parts.join(' and ')
}

/** Sends one cue-number write with the placement given, rejecting with the request's error. */
export type CueNumberSend = (placement: CueNumberPlacement) => Promise<unknown>

interface PendingAsk {
  ask: CueNumberOutOfOrder
  send: CueNumberSend
}

/**
 * A cue-number write that asks before it lands out of order. `writeNumber(send)` sends with
 * `placement: 'ASK'`; when the desk answers that the number reads out of order there, `dialog` asks
 * the operator whether to move the cue to its number's place (`BY_NUMBER`) or keep it where it is
 * (`HERE`), and sends again. Cancel writes nothing. A host that has gone by the time the answer
 * arrives (an editor closed with the field open) cannot ask, so the number is written where it is,
 * as before there was a question. Every other refusal is the error toast's.
 */
export function useCueNumberPlacement(): { writeNumber: (send: CueNumberSend) => void; dialog: ReactNode } {
  // The last ask stays after the dialog closes, so its text does not empty while it animates out.
  const [pending, setPending] = useState<PendingAsk | null>(null)
  const [open, setOpen] = useState(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const writeNumber = useCallback((send: CueNumberSend) => {
    send('ASK').catch((error: unknown) => {
      const ask = outOfOrderOf(error)
      if (!ask) return
      if (!mounted.current) {
        send('HERE').catch(ignoreReportedError)
        return
      }
      setPending({ ask, send })
      setOpen(true)
    })
  }, [])

  const choose = (placement: CueNumberPlacement) => {
    setOpen(false)
    pending?.send(placement).catch(ignoreReportedError)
  }

  const where = pending ? outOfOrderWhere(pending.ask) : ''
  const dialog = (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Q{pending?.ask.number} is out of order here</AlertDialogTitle>
          <AlertDialogDescription>
            {where ? `Where it is, it would come ${where}. ` : ''}
            Move the cue to its place in the stack, or keep it where it is?
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <Button variant="outline" onClick={() => choose('HERE')}>
            Keep it here
          </Button>
          <AlertDialogAction onClick={() => choose('BY_NUMBER')}>Move to its place</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )

  return { writeNumber, dialog }
}

/**
 * What a cue-number field's typed `next` comes to: its canonical spelling, null for blank (the entry
 * goes back to auto-numbering), or `undefined` — after toasting the rule — for one that is not a
 * cue number, which the field's `onCommit` then refuses.
 */
export function readTypedCueNumber(next: string): string | null | undefined {
  const trimmed = next.trim()
  if (trimmed === '') return null
  const canonical = canonicalCueNumber(trimmed)
  if (canonical == null) toast.error(CUE_NUMBER_RULE, { id: 'cue-number-invalid' })
  return canonical ?? undefined
}
