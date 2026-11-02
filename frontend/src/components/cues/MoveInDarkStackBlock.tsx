import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { cn } from '@/lib/utils'
import type { StackMoveInDarkPlan } from '@/api/cueStacksApi'
import {
  MOVE_IN_DARK_FAMILIES,
  familyLabel,
  stackDraftResult,
  type StackMoveInDarkDraft,
} from '@/lib/moveInDark'
import { DarkChip } from './moveInDarkMarks'

/**
 * The stack sheet's *Move in dark* block (lighting7 move-in-dark design §5.1), under Loop: the
 * switch, *When* `Early · Late`, Delay and Move time in seconds, *Moves* as three chips, and the
 * read-out of what the stored list will do, from the desk's static plan. The values are the form's
 * own until Save, like the name and Loop; the read-out is the stored list's, so it does not follow
 * an unsaved edit.
 */
export function MoveInDarkStackBlock({
  draft,
  onChange,
  plan,
  onShowCues,
  idBase,
}: {
  draft: StackMoveInDarkDraft
  onChange: (next: StackMoveInDarkDraft) => void
  /** The stored list's plan; absent for a stack not yet made. */
  plan?: StackMoveInDarkPlan
  /** Open the cue table on the cues the read-out counts; absent leaves the read-out a read-out. */
  onShowCues?: () => void
  idBase: string
}) {
  const result = stackDraftResult(draft)
  const problems = result.problems ?? {}
  const set = (patch: Partial<StackMoveInDarkDraft>) => onChange({ ...draft, ...patch })

  return (
    <div className="space-y-3 border-t pt-3" data-move-in-dark-block>
      <div className="flex items-center justify-between gap-3">
        <div>
          <Label id={`${idBase}-label`}>Move in dark</Label>
          <p className="text-xs text-muted-foreground">Move dark heads to the cue that lights them next</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={draft.on}
          aria-labelledby={`${idBase}-label`}
          onClick={() => set({ on: !draft.on })}
          className={cn(
            'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors',
            draft.on ? 'bg-primary' : 'bg-muted',
          )}
        >
          <span
            className={cn(
              'pointer-events-none inline-block size-5 rounded-full bg-background shadow-lg transition-transform',
              draft.on ? 'translate-x-5' : 'translate-x-0',
            )}
          />
        </button>
      </div>

      {draft.on && (
        <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-2">
          <Label className="text-xs text-muted-foreground">When</Label>
          <ToggleGroup
            type="single"
            size="sm"
            aria-label="When"
            value={draft.when}
            onValueChange={(next) => next && set({ when: next as StackMoveInDarkDraft['when'] })}
            className="justify-self-start"
          >
            <ToggleGroupItem value="EARLY" title="As soon as the head is dark, for the first cue ahead that lights it">
              Early
            </ToggleGroupItem>
            <ToggleGroupItem value="LATE" title="Only for the next GO">
              Late
            </ToggleGroupItem>
          </ToggleGroup>

          <Label htmlFor={`${idBase}-delay`} className="text-xs text-muted-foreground">
            Delay
          </Label>
          <TimeField id={`${idBase}-delay`} value={draft.delay} problem={problems.delay} onChange={(delay) => set({ delay })} />

          <Label htmlFor={`${idBase}-time`} className="text-xs text-muted-foreground">
            Move time
          </Label>
          <TimeField id={`${idBase}-time`} value={draft.time} problem={problems.time} onChange={(time) => set({ time })} />

          <Label className="text-xs text-muted-foreground">Moves</Label>
          <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Moves">
            {MOVE_IN_DARK_FAMILIES.map((family) => {
              const on = draft.families.includes(family)
              return (
                <button
                  key={family}
                  type="button"
                  aria-pressed={on}
                  onClick={() =>
                    set({
                      families: on
                        ? draft.families.filter((f) => f !== family)
                        : MOVE_IN_DARK_FAMILIES.filter((f) => f === family || draft.families.includes(f)),
                    })
                  }
                  className={cn(
                    'h-7 rounded-md border px-2.5 text-xs transition-colors',
                    on ? 'border-primary/60 bg-primary/15 text-foreground' : 'border-border text-muted-foreground hover:text-foreground',
                  )}
                >
                  {familyLabel(family)}
                </button>
              )
            })}
          </div>
          {problems.families && <p className="col-start-2 text-xs text-destructive">{problems.families}</p>}
        </div>
      )}

      {plan != null && <PlanReadout plan={plan} onShowCues={onShowCues} />}
    </div>
  )
}

function TimeField({ id, value, problem, onChange }: { id: string; value: string; problem?: string; onChange: (next: string) => void }) {
  return (
    <div className="space-y-1">
      <Input
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={problem != null}
        aria-describedby={problem != null ? `${id}-problem` : undefined}
        inputMode="decimal"
        className="h-8 w-24 font-mono text-xs"
        placeholder="1s"
      />
      {problem != null && (
        <p id={`${id}-problem`} className="text-xs text-destructive">
          {problem}
        </p>
      )}
    </div>
  )
}

/** `☾ 38 heads in 14 cues · ▲ 3 move lit · 2 cues off`, or why there is nothing to count. */
function PlanReadout({ plan, onShowCues }: { plan: StackMoveInDarkPlan; onShowCues?: () => void }) {
  if (!plan.rigLoaded) {
    return <p className="text-xs text-muted-foreground">What this stack moves is counted once its project is current.</p>
  }
  const { heads, cues, live, off } = plan.summary
  if (heads === 0 && live === 0 && off === 0) {
    return <p className="text-xs text-muted-foreground">No cue in this stack moves a head in the dark.</p>
  }
  const body = (
    <span className="flex flex-wrap items-center gap-1.5">
      {heads > 0 && (
        <DarkChip>
          {heads} head{heads === 1 ? '' : 's'} in {cues} cue{cues === 1 ? '' : 's'}
        </DarkChip>
      )}
      {live > 0 && <DarkChip lit>{live} move lit</DarkChip>}
      {off > 0 && (
        <span className="text-[11px] text-muted-foreground">
          {off} cue{off === 1 ? '' : 's'} off
        </span>
      )}
    </span>
  )
  return onShowCues ? (
    <button
      type="button"
      onClick={onShowCues}
      className="-mx-1 rounded px-1 py-0.5 text-left hover:bg-muted/60"
      title="Open the cue table on the cues these count"
      data-move-in-dark-readout
    >
      {body}
    </button>
  ) : (
    <div data-move-in-dark-readout>{body}</div>
  )
}
