import { useEffect, useState } from 'react'
import { Loader2, Play, Trash2, Wrench } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  SheetBody,
  SheetClose,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  useUnsavedChanges,
} from '@/components/ui/sheet'
import { LazyScriptEditor } from '@/components/scripts/LazyScriptEditor'
import { ScriptCompileDialog, ScriptRunDialog } from '@/components/scripts/ScriptResultDialogs'
import { useCompileProjectScriptMutation, useCurrentProjectQuery, useRunProjectScriptMutation } from '@/store/projects'
import { useFxDefinitionQuery, useUpdateFxDefinitionMutation, type FxDefinition } from '@/store/fxDefinitions'
import { EFFECT_MODE_LABELS, effectModeToEditorType } from './fxLibraryModel'

/**
 * A **custom** definition's editor — its name, its script, and whether its instances start with
 * step timing on — what a custom row's pencil (or ⏎ over one) opens, and where Fork lands (D9).
 *
 * **Step timing is shown, not hidden.** A fork starts with `defaultStepTiming` false: the library
 * entry does not publish a built-in's own default, so the fork cannot copy it. [forkedFrom] names
 * the built-in when the sheet was opened by Fork, and the note under the toggle says so.
 *
 * Delete goes through the sheet's batch delete ([onDelete], `useFxDefinitionDelete`), so the editor
 * and the bar ask one question and report one way (library-sheets plan D13).
 */
export function EditFxDefinitionSheet({
  definitionId,
  forkedFrom,
  onDelete,
  isDeleting = false,
}: {
  definitionId: number
  /** The built-in this definition was just forked from — the sheet was opened by Fork. */
  forkedFrom?: string | null
  onDelete: (definition: FxDefinition) => void
  isDeleting?: boolean
}) {
  const { data: currentProject } = useCurrentProjectQuery()
  const { data: definition, isLoading, isFetching } = useFxDefinitionQuery(definitionId)

  const [
    runCompileMutation,
    { data: compileResult, isUninitialized: hasNotCompiled, isLoading: isCompiling, reset: resetCompile },
  ] = useCompileProjectScriptMutation()
  const [runRunMutation, { data: runResult, isUninitialized: hasNotRun, isLoading: isTesting, reset: resetRun }] =
    useRunProjectScriptMutation()
  const [runUpdateMutation, { isLoading: isSaving }] = useUpdateFxDefinitionMutation()

  const [edits, setEdits] = useState<{ name?: string; script?: string; defaultStepTiming?: boolean }>({})

  useEffect(() => {
    setEdits({})
  }, [definitionId])

  // `edits.script` holds whatever is in the editor, verbatim. The editor is a controlled component
  // now, so a field that collapsed back to `undefined` whenever the text happened to trim equal to
  // the saved script would revert the operator's typing — and their caret — under them. Whether
  // that text counts as a *change* is asked here instead of at the point it is stored.
  const hasChanged =
    edits.name !== undefined ||
    (edits.defaultStepTiming !== undefined && edits.defaultStepTiming !== definition?.defaultStepTiming) ||
    (edits.script !== undefined && edits.script.trim() !== definition?.script.trim())

  // Before the early returns: hooks cannot be skipped, and the sheet must know about the edit
  // whatever the query is doing.
  useUnsavedChanges(hasChanged)

  if (isLoading || (isFetching && definition == null)) {
    return (
      <>
        <SheetHeader>
          <SheetTitle>Edit FX</SheetTitle>
        </SheetHeader>
        <div className="flex justify-center p-8">
          <Loader2 className="size-6 animate-spin" />
        </div>
      </>
    )
  }

  if (!definition) {
    return (
      <>
        <SheetHeader>
          <SheetTitle>Edit FX</SheetTitle>
        </SheetHeader>
        <p className="p-4 text-destructive">Definition not found.</p>
      </>
    )
  }

  const editorType = effectModeToEditorType(definition.effectMode)
  const currentName = edits.name ?? definition.name
  const currentScript = edits.script ?? definition.script
  const currentStepTiming = edits.defaultStepTiming ?? definition.defaultStepTiming

  // Trimmed, as the sheet's rename is: a name of spaces alone is no name.
  const canSave = hasChanged && currentName.trim() !== '' && currentScript !== ''

  const handleCompile = () => {
    if (!currentProject) return
    runCompileMutation({ projectId: currentProject.id, script: currentScript, scriptType: editorType })
  }

  const handleTest = () => {
    if (!currentProject) return
    runRunMutation({ projectId: currentProject.id, script: currentScript, scriptType: editorType })
  }

  const handleSave = async () => {
    try {
      await runUpdateMutation({
        id: definitionId,
        name: currentName.trim(),
        script: currentScript,
        defaultStepTiming: currentStepTiming,
      }).unwrap()
      setEdits({})
    } catch {
      // Reported by errorToastMiddleware; the edits stay, so the sheet still reads dirty.
    }
  }

  return (
    <>
      <SheetHeader>
        <SheetTitle className="flex items-center gap-2">
          Edit FX
          <Badge variant="default">Custom</Badge>
          {definition.effectMode !== 'STANDARD' && (
            <Badge variant="outline" className="text-xs">
              {EFFECT_MODE_LABELS[definition.effectMode] ?? definition.effectMode}
            </Badge>
          )}
        </SheetTitle>
      </SheetHeader>

      <ScriptCompileDialog
        compileResult={compileResult}
        hasNotCompiled={hasNotCompiled}
        isCompiling={isCompiling}
        resetCompile={resetCompile}
      />
      <ScriptRunDialog runResult={runResult} hasNotRun={hasNotRun} isRunning={isTesting} resetRun={resetRun} />

      <SheetBody>
        <div className="space-y-1.5">
          <Label htmlFor="edit-fx-name">Name</Label>
          <Input
            id="edit-fx-name"
            value={currentName}
            onChange={(e) =>
              setEdits({ ...edits, name: e.target.value !== definition.name ? e.target.value : undefined })
            }
          />
        </div>

        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <input
              id="edit-fx-step-timing"
              type="checkbox"
              checked={currentStepTiming}
              onChange={(e) => setEdits({ ...edits, defaultStepTiming: e.target.checked })}
            />
            <Label htmlFor="edit-fx-step-timing">Step timing by default</Label>
          </div>
          <p className="text-xs text-muted-foreground" data-testid="fork-step-timing-note">
            {forkedFrom
              ? `Forked from ${forkedFrom}. A fork starts with step timing off — the library does not publish the built-in’s own default, so check it matches before you use this.`
              : 'New instances of this effect start with step timing on or off as set here.'}
          </p>
        </div>

        <div>
          <div className="mb-2 flex items-center justify-between">
            <Label>Script</Label>
            <div className="flex gap-1">
              <Button
                variant="outline"
                size="sm"
                disabled={currentScript === '' || isCompiling || !currentProject}
                onClick={handleCompile}
              >
                <Wrench className="size-3.5" />
                Compile
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={currentScript === '' || isTesting || !currentProject}
                onClick={handleTest}
              >
                <Play className="size-3.5" />
                Test
              </Button>
            </div>
          </div>
          <LazyScriptEditor
            script={{ name: currentName, script: currentScript }}
            id={definitionId}
            scriptType={editorType}
            onScriptChange={(code) => setEdits({ ...edits, script: code })}
          />
        </div>
      </SheetBody>

      <SheetFooter className="flex-row justify-between">
        <Button variant="destructive" onClick={() => onDelete(definition)} disabled={isDeleting}>
          <Trash2 className="size-3.5" />
          {isDeleting ? 'Deleting…' : 'Delete'}
        </Button>
        <div className="flex gap-2">
          {/* Through the sheet, not straight to a close callback: only a close that Radix drives
              reaches the unsaved-changes question. */}
          <SheetClose asChild>
            <Button variant="outline" disabled={isSaving}>
              Cancel
            </Button>
          </SheetClose>
          <Button disabled={!canSave || isSaving} onClick={handleSave}>
            {isSaving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </SheetFooter>
    </>
  )
}
