import { useState, useEffect, useId, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Sheet,
  SheetContent,
  SheetBody,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetFooter,
} from '@/components/ui/sheet'
import { Blinds, Loader2 } from 'lucide-react'
import { SceneryEditor } from '@/components/scenery/SceneryEditor'
import { useSetStackSceneryMutation } from '@/store/scenery'
import { useCueStackMoveInDarkQuery } from '@/store/cueStacks'
import { MOVE_IN_DARK_OFF, NEW_STACK_MOVE_IN_DARK, stackDraftOf, stackDraftResult, type StackMoveInDarkDraft } from '@/lib/moveInDark'
import { MoveInDarkStackBlock } from './MoveInDarkStackBlock'
import type { CueStack, CueStackInput } from '@/api/cueStacksApi'
import type { SceneryChange } from '@/api/sceneryApi'

const EMPTY_SCENERY: SceneryChange[] = []

interface CueStackFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  stack: CueStack | null
  /** The stack's set as the desk holds it now; edited in place and saved by itself. */
  scenery?: SceneryChange[]
  onSave: (input: CueStackInput) => Promise<void>
  isSaving: boolean
  /**
   * Open the cue table on the cues Move in dark's read-out counts (the sheet closes first). Absent,
   * the read-out only reads out.
   */
  onShowDarkCues?: (stackId: number) => void
}

export function CueStackForm({
  open,
  onOpenChange,
  projectId,
  stack,
  scenery,
  onSave,
  isSaving,
  onShowDarkCues,
}: CueStackFormProps) {
  const [name, setName] = useState('')
  const [loop, setLoop] = useState(false)
  const [setStackScenery] = useSetStackSceneryMutation()
  const runnable = stack == null || stack.type === 'STACK'
  // What the block opened on, so a Save that left it alone writes nothing, and one that left a time
  // alone writes it as stored: the fields show tenths of a second, and would round a stored 1.25 s.
  const [darkOpened, setDarkOpened] = useState<StackMoveInDarkDraft>(() => stackDraftOf(NEW_STACK_MOVE_IN_DARK))
  const [dark, setDark] = useState<StackMoveInDarkDraft>(darkOpened)
  const darkId = useId()
  const { data: darkPlan } = useCueStackMoveInDarkQuery(
    { projectId, stackId: stack?.id ?? 0 },
    { skip: !open || stack == null || !runnable },
  )

  // The fields start afresh when the sheet opens or names another stack, never when the same stack
  // is re-read: a host passing the live list's copy hands a new one on every GO.
  const latestStack = useRef(stack)
  useEffect(() => {
    latestStack.current = stack
  })
  const stackId = stack?.id ?? null
  useEffect(() => {
    const stack = latestStack.current
    if (open) {
      if (stack) {
        setName(stack.name)
        setLoop(stack.loop)
      } else {
        setName('')
        setLoop(false)
      }
      // A new stack starts on (Q2); one from a desk that sends no setting reads off.
      const opened = stackDraftOf(stack == null ? NEW_STACK_MOVE_IN_DARK : (stack.moveInDark ?? MOVE_IN_DARK_OFF))
      setDarkOpened(opened)
      setDark(opened)
    }
  }, [open, stackId])

  const darkResult = stackDraftResult(dark)
  const darkTouched = JSON.stringify(dark) !== JSON.stringify(darkOpened)

  const handleSave = async () => {
    const input: CueStackInput = {
      name: name.trim(),
      loop,
    }
    if (runnable && (stack == null || darkTouched)) {
      if (darkResult.value != null) {
        // A time the operator did not type over keeps its stored value, not its tenths-of-a-second face.
        const stored = stack?.moveInDark
        input.moveInDark = {
          ...darkResult.value,
          delayMs: stored != null && dark.delay === darkOpened.delay ? stored.delayMs : darkResult.value.delayMs,
          timeMs: stored != null && dark.time === darkOpened.time ? stored.timeMs : darkResult.value.timeMs,
        }
      } else if (!dark.on) {
        // Switched off over fields it now hides: off, and the stored rest kept.
        input.moveInDark = { on: false }
      }
    }
    // Keep the sheet open if the save failed — errorToastMiddleware says what went wrong, and
    // closing would discard the operator's edits behind the toast.
    try {
      await onSave(input)
    } catch {
      return
    }
    onOpenChange(false)
  }

  // Only fields on screen can hold Save back: switched off, the block hides them.
  const isValid = name.trim().length > 0 && !(runnable && dark.on && darkResult.value == null)

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{stack ? 'Edit Cue Stack' : 'New Cue Stack'}</SheetTitle>
          <SheetDescription>
            {stack
              ? 'Update stack settings.'
              : 'Create a stack to group cues for sequential playback.'}
          </SheetDescription>
        </SheetHeader>

        <SheetBody>
          {/* Name */}
          <div className="space-y-1.5">
            <Label htmlFor="stack-name">Name</Label>
            <Input
              id="stack-name"
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Act 1"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && isValid) handleSave()
              }}
            />
          </div>

          {/* Loop */}
          <div className="flex items-center justify-between">
            <div>
              <Label>Loop</Label>
              <p className="text-xs text-muted-foreground">Wrap from last cue to first</p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={loop}
              onClick={() => setLoop(!loop)}
              className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
                loop ? 'bg-primary' : 'bg-muted'
              }`}
            >
              <span
                className={`pointer-events-none inline-block size-5 rounded-full bg-background shadow-lg transition-transform ${
                  loop ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          {runnable && (
            <MoveInDarkStackBlock
              draft={dark}
              onChange={setDark}
              plan={darkPlan}
              idBase={darkId}
              onShowCues={
                stack != null && onShowDarkCues
                  ? () => {
                      onOpenChange(false)
                      onShowDarkCues(stack.id)
                    }
                  : undefined
              }
            />
          )}

          {/* Set for this stack (stage-view plan session 8). Saves itself, like the cue editor's Scenery tab, rather
              than waiting for Save — a new stack has no id to hang one on, so it appears once the
              stack exists. */}
          {stack != null && stack.type === 'STACK' && (
            <div className="space-y-2 border-t pt-3">
              <Label className="flex items-center gap-1.5">
                <Blinds className="size-3.5" />
                Set for this stack
              </Label>
              <p className="text-xs text-muted-foreground">
                While this stack is live these elements take these states, under anything its cues
                change. Stopping the stack lets them go.
              </p>
              <SceneryEditor
                projectId={projectId}
                scenery={scenery ?? EMPTY_SCENERY}
                withTime={false}
                addLabel="Add state"
                idPrefix={`stack-${stack.id}-set`}
                onSave={(items) => setStackScenery({ projectId, stackId: stack.id, scenery: items }).unwrap()}
              />
            </div>
          )}

          <p className="text-xs text-muted-foreground">
            Auto-advance and crossfade are configured per-cue in the cue editor.
          </p>
        </SheetBody>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isSaving}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={!isValid || isSaving}>
            {isSaving && <Loader2 className="size-4 mr-2 animate-spin" />}
            {stack ? 'Save' : 'Create'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
