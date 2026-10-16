import { useCallback, useEffect, useRef, useState } from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { useFieldAutosave } from '@/hooks/useFieldAutosave'
import { CueTriggerEditor } from '@/components/cues/CueTriggerEditor'
import { TriggerSummary } from '@/components/cues/TriggerSummary'
import { AddToBuskPageMenu } from '@/components/busking/AddToBuskPageMenu'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { usePatchProjectCueMutation } from '@/store/cues'
import type { Cue, CueTrigger, CueTriggerDetail } from '@/api/cuesApi'

/**
 * The editor's **Cue** tab: the cue's notes, its script hooks and the busk-page append — what the
 * header's name, number, fade, curve and follow leave (show-cue-editing plan D15). Every field
 * writes the cue as it is left; locked, the tab is a read-out (§4.6).
 */
export function CueTab({ cue, projectId, locked, idBase }: { cue: Cue; projectId: number; locked: boolean; idBase: string }) {
  const [patchCue] = usePatchProjectCueMutation()
  const [notes, setNotes] = useState(cue.notes ?? '')
  const notesId = `${idBase}-notes`
  const notesRef = useRef<HTMLTextAreaElement>(null)

  // Follow a change made elsewhere — the cue sheet's Notes cell, another editor, another window —
  // unless the operator is typing in this field, which would write the stale draft straight back.
  useEffect(() => {
    if (notesRef.current == null || document.activeElement !== notesRef.current) setNotes(cue.notes ?? '')
  }, [cue.notes])

  const commitNotes = useCallback(() => {
    const next = notes.trim() || null
    if (next === (cue.notes ?? null)) return
    patchCue({ projectId, cueId: cue.id, notes: next })
  }, [notes, cue.notes, cue.id, patchCue, projectId])
  // A beat after typing stops as well as on blur, so a note survives switching tabs or collapsing
  // the card — neither of which fires a blur.
  useFieldAutosave(notes, commitNotes)

  const [editingHook, setEditingHook] = useState<{ kind: 'add' } | { kind: 'edit'; index: number } | null>(null)
  const writeTriggers = useCallback(
    (next: CueTrigger[]) => patchCue({ projectId, cueId: cue.id, triggers: next }),
    [cue.id, patchCue, projectId],
  )
  const triggers = cue.triggers

  const handleHookConfirm = (t: CueTrigger) => {
    if (editingHook?.kind === 'add') {
      writeTriggers([...triggers.map(stripScriptName), t])
    } else if (editingHook?.kind === 'edit') {
      const updated = triggers.map(stripScriptName)
      updated[editingHook.index] = t
      writeTriggers(updated)
    }
    setEditingHook(null)
  }

  const handleHookRemove = () => {
    if (editingHook?.kind !== 'edit') return
    writeTriggers(triggers.map(stripScriptName).filter((_, i) => i !== editingHook.index))
    setEditingHook(null)
  }

  return (
    <div className="space-y-4" data-cue-tab={cue.id}>
      <div className="space-y-1.5">
        <EditorLabel>
          <label htmlFor={notesId}>Notes</label>
        </EditorLabel>
        {locked ? (
          <p className="whitespace-pre-wrap text-sm italic text-muted-foreground">{cue.notes || 'None.'}</p>
        ) : (
          <Textarea
            ref={notesRef}
            id={notesId}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            onBlur={commitNotes}
            placeholder="Performance note…"
            className="min-h-[60px] resize-y"
          />
        )}
      </div>

      <div className="space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <EditorLabel>Script hooks</EditorLabel>
          {!locked && (
            <Button variant="outline" size="sm" className="h-7 gap-1 text-xs" onClick={() => setEditingHook({ kind: 'add' })}>
              <Plus className="size-3" />
              Hook
            </Button>
          )}
        </div>
        {triggers.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">None. Hooks run FX Application scripts at cue lifecycle events.</p>
        ) : (
          triggers.map((trigger, index) => (
            <TriggerSummary
              key={`trigger-${index}`}
              trigger={trigger}
              onClick={locked ? undefined : () => setEditingHook({ kind: 'edit', index })}
              onRemove={locked ? undefined : () => writeTriggers(triggers.map(stripScriptName).filter((_, i) => i !== index))}
            />
          ))
        )}
      </div>

      {/* Hidden on a MARKER, which `goToCue` refuses server-side: a pad that could only ever fail.
          A busk pad fires its cue with no arming confirm, which is what the hint says. */}
      {cue.cueType !== 'MARKER' && !locked && (
        <div className="flex items-center justify-between gap-3">
          <div className="space-y-0.5">
            <EditorLabel>Busk pages</EditorLabel>
            <p className="text-xs text-muted-foreground">Give this cue a pad in a bank. Pressing it fires the cue immediately.</p>
          </div>
          <AddToBuskPageMenu projectId={projectId} record={{ kind: 'CUE', id: cue.id, name: cue.name }} />
        </div>
      )}

      <Sheet
        open={editingHook != null}
        onOpenChange={(open) => {
          if (!open) setEditingHook(null)
        }}
      >
        <SheetContent side="right" className="flex flex-col p-0 sm:max-w-lg" aria-describedby={undefined}>
          <SheetHeader className="sr-only">
            <SheetTitle>Script hook</SheetTitle>
          </SheetHeader>
          {editingHook && (
            <CueTriggerEditor
              projectId={projectId}
              mode="sheet"
              trigger={editingHook.kind === 'edit' ? triggers[editingHook.index] : null}
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
