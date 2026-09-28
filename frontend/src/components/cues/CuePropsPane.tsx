import { useCallback, useEffect, useState } from 'react'
import { useFieldAutosave } from '@/hooks/useFieldAutosave'
import { ChevronDown, Plus, Zap } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { CueTriggerEditor } from '@/components/cues/CueTriggerEditor'
import { TriggerSummary } from '@/components/cues/TriggerSummary'
import {
  usePatchProjectCueMutation,
} from '@/store/cues'
import { AddToBuskPageMenu } from '@/components/busking/AddToBuskPageMenu'
import type { Cue, CueTrigger, CueTriggerDetail } from '@/api/cuesApi'

interface CuePropsPaneProps {
  cue: Cue
  projectId: number
}

/**
 * Properties pane: name, cue#, fade in/out, easing, notes,
 * auto-advance, *Add to busk page*, script hooks. There is no busk pin: a pad is a place in a bank
 * the operator chose, so the control names a page and a bank rather than setting a flag — and the
 * same cue may have pads in several banks, which a flag could not say. Every field auto-saves via PATCH — selects and
 * toggles on change, text fields on blur, on Enter, and a beat after typing
 * stops (`useFieldAutosave`). There is no Save button and none is wanted.
 *
 * Local state mirrors the cue while typing so a PATCH goes out per pause
 * rather than per keystroke; the displayed value still reflects the live
 * `cue` prop once the save resolves.
 */
export function CuePropsPane({ cue, projectId }: CuePropsPaneProps) {
  const [patchCue] = usePatchProjectCueMutation()

  const [name, setName] = useState(cue.name)
  const [cueNumber, setCueNumber] = useState(cue.cueNumber ?? '')
  const [notes, setNotes] = useState(cue.notes ?? '')
  const [fadeMs, setFadeMs] = useState(
    cue.fadeDurationMs != null ? String(cue.fadeDurationMs) : '',
  )
  const [autoAdvanceDelay, setAutoAdvanceDelay] = useState(
    cue.autoAdvanceDelayMs != null ? String(cue.autoAdvanceDelayMs) : '',
  )

  // Re-sync whenever a field changes underneath us — the collapsed row edits name / cue# /
  // fade inline as well, so holding a stale local value here would write it straight back
  // on the next blur and silently revert that edit. A field the operator is currently
  // typing in is skipped, which is what keying on `cue.id` alone used to protect.
  useEffect(() => {
    const focused = document.activeElement?.id
    const fresh = (field: string) => focused !== `cue-${cue.id}-${field}`
    if (fresh('name')) setName(cue.name)
    if (fresh('num')) setCueNumber(cue.cueNumber ?? '')
    if (fresh('notes')) setNotes(cue.notes ?? '')
    if (fresh('fade')) setFadeMs(cue.fadeDurationMs != null ? String(cue.fadeDurationMs) : '')
    if (fresh('auto-delay')) {
      setAutoAdvanceDelay(cue.autoAdvanceDelayMs != null ? String(cue.autoAdvanceDelayMs) : '')
    }
  }, [
    cue.id,
    cue.name,
    cue.cueNumber,
    cue.notes,
    cue.fadeDurationMs,
    cue.autoAdvanceDelayMs,
  ])

  const commitName = useCallback(() => {
    const trimmed = name.trim()
    if (trimmed === '' || trimmed === cue.name) return
    patchCue({ projectId, cueId: cue.id, name: trimmed })
  }, [name, cue.id, cue.name, patchCue, projectId])

  const commitCueNumber = useCallback(() => {
    const next = cueNumber.trim() || null
    if (next === (cue.cueNumber ?? null)) return
    patchCue({ projectId, cueId: cue.id, cueNumber: next })
  }, [cueNumber, cue.cueNumber, cue.id, patchCue, projectId])

  const commitNotes = useCallback(() => {
    const next = notes.trim() || null
    if (next === (cue.notes ?? null)) return
    patchCue({ projectId, cueId: cue.id, notes: next })
  }, [notes, cue.notes, cue.id, patchCue, projectId])

  const commitFade = useCallback(() => {
    const raw = fadeMs.trim()
    const next = raw === '' ? null : Number(raw)
    if (raw !== '' && !Number.isFinite(next)) return
    if (next === (cue.fadeDurationMs ?? null)) return
    patchCue({ projectId, cueId: cue.id, fadeDurationMs: next as number | null })
  }, [fadeMs, cue.fadeDurationMs, cue.id, patchCue, projectId])

  const commitAutoAdvanceDelay = useCallback(() => {
    const raw = autoAdvanceDelay.trim()
    const next = raw === '' ? null : Number(raw)
    if (raw !== '' && !Number.isFinite(next)) return
    if (next === (cue.autoAdvanceDelayMs ?? null)) return
    patchCue({ projectId, cueId: cue.id, autoAdvanceDelayMs: next as number | null })
  }, [autoAdvanceDelay, cue.autoAdvanceDelayMs, cue.id, patchCue, projectId])

  const setFadeCurve = useCallback(
    (curve: string) => {
      if (curve === cue.fadeCurve) return
      patchCue({ projectId, cueId: cue.id, fadeCurve: curve })
    },
    [cue.fadeCurve, cue.id, patchCue, projectId],
  )

  // Every text field saves itself a beat after typing stops as well as on blur, so an edit
  // survives collapsing the card or switching pane tabs — neither of which fires a blur.
  useFieldAutosave(name, commitName)
  useFieldAutosave(cueNumber, commitCueNumber)
  useFieldAutosave(notes, commitNotes)
  useFieldAutosave(fadeMs, commitFade)
  useFieldAutosave(autoAdvanceDelay, commitAutoAdvanceDelay)

  /** Enter commits the single-line fields outright — no need to wait out the autosave pause. */
  const commitOnEnter = (commit: () => void) => (e: React.KeyboardEvent) => {
    if (e.key !== 'Enter') return
    e.preventDefault()
    commit()
  }

  // Read off the field being typed in, not the committed cue, so the easing control wakes up as
  // soon as a duration is entered rather than waiting for the blur that saves it. A blank field
  // (`Number('')` is 0) is a snap, same as an explicit zero.
  const hasFade = Number(fadeMs) > 0

  const setAutoAdvance = useCallback(
    (next: boolean) => {
      if (next === cue.autoAdvance) return
      patchCue({ projectId, cueId: cue.id, autoAdvance: next })
    },
    [cue.autoAdvance, cue.id, patchCue, projectId],
  )

  const [hooksExpanded, setHooksExpanded] = useState((cue.triggers ?? []).length > 0)
  const [editingHook, setEditingHook] = useState<
    { kind: 'add' } | { kind: 'edit'; index: number } | null
  >(null)

  const writeTriggers = useCallback(
    (next: CueTrigger[]) => {
      patchCue({ projectId, cueId: cue.id, triggers: next })
    },
    [cue.id, patchCue, projectId],
  )

  const handleHookConfirm = (t: CueTrigger) => {
    if (editingHook?.kind === 'add') {
      writeTriggers([
        ...cue.triggers.map(stripScriptName),
        t,
      ])
    } else if (editingHook?.kind === 'edit') {
      const updated = cue.triggers.map(stripScriptName)
      updated[editingHook.index] = t
      writeTriggers(updated)
    }
    setEditingHook(null)
  }

  const handleHookRemove = () => {
    if (editingHook?.kind !== 'edit') return
    const filtered = cue.triggers
      .map(stripScriptName)
      .filter((_, i) => i !== editingHook.index)
    writeTriggers(filtered)
    setEditingHook(null)
  }

  return (
    <div className="space-y-3">
      {/* Identity on one row — Cue # is three characters wide and looked adrift spanning the
          pane on its own. Easing belongs to the fade, so it lives in Transition below. */}
      <div className="grid grid-cols-[1fr_8rem] gap-2">
        <div className="space-y-1.5">
          <Label htmlFor={`cue-${cue.id}-name`}>Name</Label>
          <Input
            id={`cue-${cue.id}-name`}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={commitName}
            onKeyDown={commitOnEnter(commitName)}
            className="h-9"
          />
        </div>
        <div className="space-y-1.5">
          <div className="flex items-center gap-1.5">
            <Label htmlFor={`cue-${cue.id}-num`}>Cue #</Label>
            {cue.cueNumberAuto && (
              <Badge
                variant="outline"
                className="h-4 px-1 text-[9px] font-bold uppercase tracking-wider text-muted-foreground"
                title="Derived from this cue's position. Type a number to set it explicitly, or clear the field to go back to auto."
              >
                Auto
              </Badge>
            )}
          </div>
          <Input
            id={`cue-${cue.id}-num`}
            value={cueNumber}
            onChange={(e) => setCueNumber(e.target.value)}
            onBlur={commitCueNumber}
            onKeyDown={commitOnEnter(commitCueNumber)}
            placeholder="14A"
            className={cn('h-9', cue.cueNumberAuto && 'text-muted-foreground')}
          />
        </div>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor={`cue-${cue.id}-notes`}>Notes</Label>
        <Textarea
          id={`cue-${cue.id}-notes`}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          onBlur={commitNotes}
          placeholder="Performance note…"
          className="min-h-[60px] resize-y"
        />
      </div>

      <div className="border-t pt-3 space-y-3">
        <Label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
          Transition
        </Label>
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label htmlFor={`cue-${cue.id}-fade`}>Fade duration (ms)</Label>
            <Input
              id={`cue-${cue.id}-fade`}
              type="number"
              min="0"
              step="100"
              value={fadeMs}
              onChange={(e) => setFadeMs(e.target.value)}
              onBlur={commitFade}
              onKeyDown={commitOnEnter(commitFade)}
              placeholder="2000"
              className="h-9 font-mono"
            />
          </div>
          {/* Easing is the shape of the fade, so it sits beside the duration that gives it
              something to shape — and goes inert on a snap, where there is no fade to curve. */}
          <div className="space-y-1.5">
            <Label htmlFor={`cue-${cue.id}-curve`}>Easing</Label>
            <Select value={cue.fadeCurve} onValueChange={setFadeCurve} disabled={!hasFade}>
              <SelectTrigger
                id={`cue-${cue.id}-curve`}
                className="h-9"
                title={hasFade ? undefined : 'Set a fade duration to choose an easing curve'}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="LINEAR">Linear</SelectItem>
                <SelectItem value="SINE_IN_OUT">Sine In/Out</SelectItem>
                <SelectItem value="CUBIC_IN_OUT">Cubic In/Out</SelectItem>
                <SelectItem value="EASE_IN">Ease In</SelectItem>
                <SelectItem value="EASE_OUT">Ease Out</SelectItem>
                <SelectItem value="EASE_IN_OUT">Ease In/Out</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="flex items-center justify-between">
          <div className="space-y-0.5">
            <Label className="cursor-pointer" htmlFor={`cue-${cue.id}-auto`}>
              Auto-advance
            </Label>
            <p className="text-xs text-muted-foreground">
              Advance to next cue after delay
            </p>
          </div>
          <button
            id={`cue-${cue.id}-auto`}
            type="button"
            role="switch"
            aria-checked={cue.autoAdvance}
            onClick={() => setAutoAdvance(!cue.autoAdvance)}
            className={cn(
              'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors',
              cue.autoAdvance ? 'bg-primary' : 'bg-muted',
            )}
          >
            <span
              className={cn(
                'pointer-events-none inline-block size-5 rounded-full bg-background shadow-lg transition-transform',
                cue.autoAdvance ? 'translate-x-5' : 'translate-x-0',
              )}
            />
          </button>
        </div>

        {cue.autoAdvance && (
          <div className="space-y-1.5 pl-1">
            <Label htmlFor={`cue-${cue.id}-auto-delay`}>Delay (ms)</Label>
            <Input
              id={`cue-${cue.id}-auto-delay`}
              type="number"
              min="100"
              step="100"
              value={autoAdvanceDelay}
              onChange={(e) => setAutoAdvanceDelay(e.target.value)}
              onBlur={commitAutoAdvanceDelay}
              onKeyDown={commitOnEnter(commitAutoAdvanceDelay)}
              placeholder="5000"
              className="h-9 font-mono"
            />
          </div>
        )}

        {/* Where the busk pin used to be, and doing its job the way the layout does it: naming a
            bank rather than setting a flag. It says what pressing the pad will do, since a busk pad
            fires its cue with no arming confirm (`busking-view-plan.md` D9).

            Hidden on a MARKER, which `goToCue` refuses server-side, exactly as the pin was: the
            append route validates references and not cue type, so this client guard is still the
            only thing between an operator and a pad that could only ever fail. Hidden rather than
            disabled, because a lone greyed-out control reads as breakage rather than as "not for
            this kind of cue". */}
        {cue.cueType !== 'MARKER' && (
          <div className="flex items-center justify-between gap-3">
            <div className="space-y-0.5">
              <Label>Add to busk page</Label>
              <p className="text-xs text-muted-foreground">
                Give this cue a pad in a bank. Pressing it fires the cue immediately.
              </p>
            </div>
            <AddToBuskPageMenu
              projectId={projectId}
              record={{ kind: 'CUE', id: cue.id, name: cue.name }}
            />
          </div>
        )}
      </div>

      {/* Script hooks */}
      <div className="border-t pt-3 space-y-2">
        <div className="flex items-center justify-between">
          <button
            type="button"
            className="flex items-center gap-2 text-left"
            onClick={() => setHooksExpanded((v) => !v)}
          >
            <ChevronDown
              className={cn(
                'size-3.5 transition-transform',
                !hooksExpanded && '-rotate-90',
              )}
            />
            <Label className="flex items-center gap-1.5 cursor-pointer">
              <Zap className="size-3.5" />
              Script Hooks
              {cue.triggers.length > 0 && (
                <Badge variant="secondary" className="text-[10px] px-1.5 py-0 ml-1">
                  {cue.triggers.length}
                </Badge>
              )}
            </Label>
          </button>
          {hooksExpanded && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs gap-1"
              onClick={() => setEditingHook({ kind: 'add' })}
            >
              <Plus className="size-3" />
              Add Hook
            </Button>
          )}
        </div>

        {hooksExpanded && (
          <div className="space-y-2">
            {cue.triggers.length === 0 && (
              <p className="text-[11px] text-muted-foreground">
                No script hooks. Hooks run FX Application scripts at cue lifecycle events.
              </p>
            )}
            {cue.triggers.map((trigger, index) => (
              <TriggerSummary
                key={`trigger-${index}`}
                trigger={trigger}
                onClick={() => setEditingHook({ kind: 'edit', index })}
                onRemove={() => {
                  writeTriggers(
                    cue.triggers
                      .map(stripScriptName)
                      .filter((_, i) => i !== index),
                  )
                }}
              />
            ))}
          </div>
        )}
      </div>

      <Sheet
        open={editingHook != null}
        onOpenChange={(open) => {
          if (!open) setEditingHook(null)
        }}
      >
        <SheetContent
          side="right"
          className="sm:max-w-lg flex flex-col p-0"
          aria-describedby={undefined}
        >
          <SheetHeader className="sr-only">
            <SheetTitle>Script hook</SheetTitle>
          </SheetHeader>
          {editingHook && (
            <CueTriggerEditor
              projectId={projectId}
              mode="sheet"
              trigger={
                editingHook.kind === 'edit'
                  ? cue.triggers[editingHook.index]
                  : null
              }
              onConfirm={handleHookConfirm}
              onCancel={() => setEditingHook(null)}
              onRemove={editingHook.kind === 'edit' ? handleHookRemove : undefined}
            />
          )}
        </SheetContent>
      </Sheet>
    </div>
  )
}

function stripScriptName(t: CueTriggerDetail): CueTrigger {
  const { scriptName: _scriptName, ...rest } = t
  return rest
}
