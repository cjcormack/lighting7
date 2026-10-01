import { useState, useEffect } from 'react'
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
}

export function CueStackForm({
  open,
  onOpenChange,
  projectId,
  stack,
  scenery,
  onSave,
  isSaving,
}: CueStackFormProps) {
  const [name, setName] = useState('')
  const [loop, setLoop] = useState(false)
  const [setStackScenery] = useSetStackSceneryMutation()

  useEffect(() => {
    if (open) {
      if (stack) {
        setName(stack.name)
        setLoop(stack.loop)
      } else {
        setName('')
        setLoop(false)
      }
    }
  }, [open, stack])

  const handleSave = async () => {
    const input: CueStackInput = {
      name: name.trim(),
      loop,
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

  const isValid = name.trim().length > 0

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

          {/* Set for this stack (stage-view plan session 8). Saves itself, like Cue properties, rather
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
