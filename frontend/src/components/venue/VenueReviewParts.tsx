import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { cn } from '@/lib/utils'
import type { UpdateAnswer, UpdateDecision } from '@/api/venueApi'

/**
 * The rows a venue review is drawn in — Update's sheet and Attach's: a labelled group with its count,
 * a line with its sign, and a decision with its answers.
 */

/** The words on a decision's answers (P8), in the board's order. */
export const ANSWER_LABELS: Record<UpdateAnswer, string> = {
  KEEP_BOTH: 'Keep both',
  READDRESS_MINE: 'Re-address mine',
  HIDE_VENUES: "Hide the venue's",
  REMOVE_MINE: 'Remove mine',
  KEEP_MINE: 'Keep mine',
  TAKE_VENUES: "Take venue's",
}

export function ReviewGroup({
  label,
  count,
  icon,
  tone,
  children,
}: {
  label: string
  count: number
  icon?: ReactNode
  tone?: 'red' | 'amber'
  children: ReactNode
}) {
  return (
    <div className="space-y-1.5" role="group" aria-label={label}>
      <div className="flex items-center gap-1.5">
        {icon}
        <EditorLabel
          className={cn(tone === 'red' && 'text-red-700 dark:text-red-400', tone === 'amber' && 'text-amber-700 dark:text-amber-400')}
        >
          {label}
        </EditorLabel>
        <span className="rounded bg-muted px-1.5 text-[10px] font-semibold tabular-nums">{count}</span>
      </div>
      <ul className="space-y-1">{children}</ul>
    </div>
  )
}

export function ReviewItemRow({
  sign,
  detail,
  tone,
  children,
}: {
  sign: string
  detail: ReactNode
  tone?: 'red'
  /** Controls at the row's end. */
  children?: ReactNode
}) {
  return (
    <li className={cn('flex items-start gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm', tone === 'red' && 'border-red-500/40')}>
      <span aria-hidden className={cn('w-3 shrink-0 text-center font-mono font-bold', tone === 'red' ? 'text-red-600 dark:text-red-400' : 'text-muted-foreground')}>
        {sign}
      </span>
      <span className="min-w-0 flex-1">{detail}</span>
      {children}
    </li>
  )
}

/** A decision: its message, what more to know, and its answers as a radio group. */
export function ReviewDecisionRow({
  message,
  label,
  answers,
  chosen,
  onChoose,
  answerLabel = (a) => ANSWER_LABELS[a],
  tone = 'red',
  children,
}: {
  message: ReactNode
  /** The radio group's accessible name. */
  label: string
  answers: readonly UpdateAnswer[]
  chosen: UpdateAnswer | undefined
  onChoose: (answer: UpdateAnswer) => void
  answerLabel?: (answer: UpdateAnswer) => string
  /** Red for one that must be answered; plain for one with a default already applied. */
  tone?: 'red' | 'plain'
  /** Lines under the message. */
  children?: ReactNode
}) {
  return (
    <li className={cn('space-y-1.5 rounded-md border bg-card px-2.5 py-2 text-sm', tone === 'red' && 'border-red-500/40')}>
      <div className="flex items-start gap-2">
        <span aria-hidden className={cn('w-3 shrink-0 text-center font-mono font-bold', tone === 'red' ? 'text-red-600 dark:text-red-400' : 'text-muted-foreground')}>
          {tone === 'red' ? '!' : '?'}
        </span>
        <span className="min-w-0">
          {message}
          {children}
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5 pl-5" role="radiogroup" aria-label={label}>
        {answers.map((a) => (
          <Button
            key={a}
            role="radio"
            aria-checked={chosen === a}
            size="sm"
            variant={chosen === a ? 'default' : 'outline'}
            className="h-7"
            onClick={() => onChoose(a)}
          >
            {answerLabel(a)}
          </Button>
        ))}
      </div>
    </li>
  )
}

/** A short line for a facet's value: a gel's code, an address; null for any other. */
export function facetValueOf(facet: string, value: Record<string, unknown> | null | undefined): string | null {
  if (value == null) return null
  if (facet === 'MEDIA' && typeof value.gelCode === 'string') return value.gelCode
  if (facet === 'ADDRESS' && typeof value.startChannel === 'number') return `ch ${value.startChannel}`
  return null
}

/** An address overlap (P8), as Update and Attach both ask it. */
export function OverlapDecisionRow({
  decision,
  chosen,
  onChoose,
}: {
  decision: UpdateDecision
  chosen: UpdateAnswer | undefined
  onChoose: (answer: UpdateAnswer) => void
}) {
  return (
    <ReviewDecisionRow
      message={decision.message}
      label={`Answer: ${decision.mine.name}`}
      answers={decision.answers}
      chosen={chosen}
      onChoose={onChoose}
      answerLabel={(a) =>
        a === 'READDRESS_MINE' && decision.suggestedAddress ? `${ANSWER_LABELS[a]} → ${decision.suggestedAddress}` : ANSWER_LABELS[a]
      }
    />
  )
}
