import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { SheetClose, SheetHeader, SheetTitle, SheetBody, useUnsavedChanges } from '@/components/ui/sheet'
import { ChevronLeft, Trash2, Wrench, Play, Loader2 } from 'lucide-react'
import {
  useProjectScriptsQuery,
  useCreateProjectScriptMutation,
  useCompileProjectScriptMutation,
  useRunProjectScriptMutation,
} from '@/store/projects'
import { LazyScriptEditor } from '@/components/scripts/LazyScriptEditor'
import {
  ScriptCompileDialog,
  ScriptRunDialog,
} from '@/components/scripts/ScriptResultDialogs'
import type { CueTrigger, CueTriggerDetail, TriggerType } from '@/api/cuesApi'

const INLINE_TEMPLATE = `// FX Application script
// Available: show, fxEngine, scriptName, step, settings
`

interface CueTriggerEditorProps {
  projectId: number
  trigger?: CueTriggerDetail | null
  /**
   * 'sheet' uses `SheetTitle` (required for Radix Dialog a11y); 'inline' uses a plain `h3`
   * because there is no Dialog ancestor when this editor is embedded in a non-sheet surface.
   */
  mode?: 'sheet' | 'inline'
  onConfirm: (trigger: CueTrigger) => void
  onCancel: () => void
  onRemove?: () => void
}

/**
 * Editor for script hook triggers. Supports selecting an existing
 * FX_APPLICATION script or writing an inline script that gets created
 * on save.
 */
export function CueTriggerEditor({
  projectId,
  trigger,
  mode = 'sheet',
  onConfirm,
  onCancel,
  onRemove,
}: CueTriggerEditorProps) {
  const { data: scripts } = useProjectScriptsQuery(projectId)

  const isEditing = trigger != null

  const [triggerType, setTriggerType] = useState<TriggerType>(trigger?.triggerType ?? 'ACTIVATION')
  const [delayMs, setDelayMs] = useState(trigger?.delayMs != null ? String(trigger.delayMs) : '')
  const [intervalMs, setIntervalMs] = useState(trigger?.intervalMs != null ? String(trigger.intervalMs) : '')
  const [randomWindowMs, setRandomWindowMs] = useState(trigger?.randomWindowMs != null ? String(trigger.randomWindowMs) : '')
  const [scriptId, setScriptId] = useState<number | null>(trigger?.scriptId ?? null)

  // Inline script state
  const [scriptMode, setScriptMode] = useState<'existing' | 'inline'>('existing')
  const [inlineName, setInlineName] = useState('')
  const [inlineCode, setInlineCode] = useState(INLINE_TEMPLATE)
  const [isCreating, setIsCreating] = useState(false)

  // Inert in `inline` mode, where there is no sheet to guard — the hook reports to whichever
  // Sheet encloses it, and to nothing at all when none does.
  useUnsavedChanges(
    triggerType !== (trigger?.triggerType ?? 'ACTIVATION') ||
    delayMs !== (trigger?.delayMs != null ? String(trigger.delayMs) : '') ||
    intervalMs !== (trigger?.intervalMs != null ? String(trigger.intervalMs) : '') ||
    randomWindowMs !== (trigger?.randomWindowMs != null ? String(trigger.randomWindowMs) : '') ||
    scriptId !== (trigger?.scriptId ?? null) ||
    scriptMode === 'inline' && (inlineName !== '' || inlineCode !== INLINE_TEMPLATE)
  )

  const [createScript] = useCreateProjectScriptMutation()
  const [
    runCompileMutation,
    { data: compileResult, isUninitialized: hasNotCompiled, isLoading: isCompiling, reset: resetCompile },
  ] = useCompileProjectScriptMutation()
  const [
    runRunMutation,
    { data: runResult, isUninitialized: hasNotRun, isLoading: isRunning, reset: resetRun },
  ] = useRunProjectScriptMutation()

  const fxApplicationScripts = scripts?.filter((s) => s.scriptType === 'FX_APPLICATION') ?? []

  /**
   * Backing out of the editor. In a sheet that has to go through the sheet itself, since only a
   * close Radix drives reaches the unsaved-changes question; inline there is no sheet to ask (and
   * no Radix context for `SheetClose` to read), so the caller's own handler stands. A plain
   * function rather than a component, so the button is not a fresh element type every render.
   */
  const dismiss = (button: React.ReactElement) =>
    mode === 'sheet' ? <SheetClose asChild>{button}</SheetClose> : button

  // Validation
  const isValid = (() => {
    if (scriptMode === 'existing' && scriptId == null) return false
    if (scriptMode === 'inline' && (!inlineName.trim() || !inlineCode.trim())) return false
    return true
  })()

  const buildTrigger = (resolvedScriptId: number): CueTrigger => ({
    triggerType,
    delayMs: delayMs ? Number(delayMs) : null,
    intervalMs: intervalMs ? Number(intervalMs) : null,
    randomWindowMs: randomWindowMs ? Number(randomWindowMs) : null,
    scriptId: resolvedScriptId,
    sortOrder: trigger?.sortOrder ?? 0,
  })

  const handleConfirm = async () => {
    if (!isValid) return

    if (scriptMode === 'existing') {
      if (scriptId == null) return
      onConfirm(buildTrigger(scriptId))
    } else {
      // Create the inline script first, then confirm with the new ID
      setIsCreating(true)
      try {
        const result = await createScript({
          projectId,
          name: inlineName.trim(),
          script: inlineCode,
          scriptType: 'FX_APPLICATION',
        }).unwrap()
        onConfirm(buildTrigger(result.id))
      } catch {
        // Creation failed — stay on the editor so the user can retry
        setIsCreating(false)
      }
    }
  }

  return (
    <>
      <SheetHeader>
        <div className="flex items-center gap-2">
          {dismiss(
            // No `onCancel` under a sheet: `asChild` would run it *as well as* the guarded close,
            // and it closes the parent's state directly.
            <Button variant="ghost" size="icon" className="size-8" onClick={mode === 'sheet' ? undefined : onCancel}>
              <ChevronLeft className="size-4" />
            </Button>
          )}
          {mode === 'inline' ? (
            <h3 className="text-foreground font-semibold">
              {isEditing ? 'Edit Script Hook' : 'Add Script Hook'}
            </h3>
          ) : (
            <SheetTitle>{isEditing ? 'Edit Script Hook' : 'Add Script Hook'}</SheetTitle>
          )}
        </div>
      </SheetHeader>

      <ScriptCompileDialog
        compileResult={compileResult}
        hasNotCompiled={hasNotCompiled}
        isCompiling={isCompiling}
        resetCompile={resetCompile}
      />
      <ScriptRunDialog
        runResult={runResult}
        hasNotRun={hasNotRun}
        isRunning={isRunning}
        resetRun={resetRun}
      />

      <SheetBody>
        {/* Trigger Type */}
        <div className="space-y-1.5">
          <Label>When</Label>
          <Select value={triggerType} onValueChange={(v) => setTriggerType(v as TriggerType)}>
            <SelectTrigger className="h-9">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ACTIVATION">On Activation</SelectItem>
              <SelectItem value="DEACTIVATION">On Deactivation</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Timing fields — always visible */}
        <div className="space-y-1.5">
          <Label htmlFor="trigger-delay">Delay (ms)</Label>
          <Input
            id="trigger-delay"
            type="number"
            min="0"
            step="100"
            value={delayMs}
            onChange={(e) => setDelayMs(e.target.value)}
            placeholder="e.g. 5000"
            className="h-9"
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="trigger-interval">Interval (ms)</Label>
          <p className="text-[11px] text-muted-foreground">
            Repeat at this interval. Leave empty for one-shot.
          </p>
          <Input
            id="trigger-interval"
            type="number"
            min="100"
            step="100"
            value={intervalMs}
            onChange={(e) => setIntervalMs(e.target.value)}
            placeholder="e.g. 40000"
            className="h-9"
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="trigger-random">Random window (ms)</Label>
          <p className="text-[11px] text-muted-foreground">
            Each interval varies by ± this amount for organic timing.
          </p>
          <Input
            id="trigger-random"
            type="number"
            min="0"
            step="100"
            value={randomWindowMs}
            onChange={(e) => setRandomWindowMs(e.target.value)}
            placeholder="e.g. 5000"
            className="h-9"
          />
        </div>

        {/* Script selection / inline editor */}
        <Tabs value={scriptMode} onValueChange={(v) => setScriptMode(v as 'existing' | 'inline')}>
          <div className="space-y-1.5">
            <Label>Script</Label>
            <TabsList className="w-full">
              <TabsTrigger value="existing" className="flex-1">Existing</TabsTrigger>
              <TabsTrigger value="inline" className="flex-1">Inline</TabsTrigger>
            </TabsList>
          </div>

          <TabsContent value="existing" className="mt-3 space-y-1.5">
            {fxApplicationScripts.length === 0 ? (
              <p className="text-[11px] text-muted-foreground">
                No FX Application scripts found. Create one in the Scripts page or use the Inline tab.
              </p>
            ) : (
              <Select
                value={scriptId != null ? String(scriptId) : ''}
                onValueChange={(v) => setScriptId(Number(v))}
              >
                <SelectTrigger className="h-9">
                  <SelectValue placeholder="Select a script..." />
                </SelectTrigger>
                <SelectContent>
                  {fxApplicationScripts.map((s) => (
                    <SelectItem key={s.id} value={String(s.id)}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </TabsContent>

          <TabsContent value="inline" className="mt-3 space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="inline-script-name">Script Name</Label>
              <Input
                id="inline-script-name"
                placeholder="e.g. Chase Dimmer Reset"
                value={inlineName}
                onChange={(e) => setInlineName(e.target.value)}
                className="h-9"
              />
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <Label>Code</Label>
                <div className="flex gap-1">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!inlineCode.trim() || isCompiling}
                    onClick={() => runCompileMutation({ projectId, script: inlineCode, scriptType: 'FX_APPLICATION' })}
                  >
                    <Wrench className="size-3.5" />
                    Compile
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!inlineCode.trim() || isRunning}
                    onClick={() => runRunMutation({ projectId, script: inlineCode, scriptType: 'FX_APPLICATION' })}
                  >
                    <Play className="size-3.5" />
                    Test
                  </Button>
                </div>
              </div>
              <LazyScriptEditor
                script={{ name: inlineName || 'Inline Hook', script: inlineCode }}
                id="trigger-inline"
                scriptType="FX_APPLICATION"
                onScriptChange={setInlineCode}
              />
            </div>
          </TabsContent>
        </Tabs>
      </SheetBody>

      {/* Footer (sub-view pattern) */}
      <div className="border-t p-4 flex items-center gap-2">
        {isEditing && onRemove && (
          <Button variant="destructive" size="sm" onClick={onRemove}>
            <Trash2 className="size-3.5 mr-1" />
            Delete
          </Button>
        )}
        <div className="flex-1" />
        {dismiss(
          <Button variant="outline" onClick={mode === 'sheet' ? undefined : onCancel}>Cancel</Button>
        )}
        <Button onClick={handleConfirm} disabled={!isValid || isCreating}>
          {isCreating && <Loader2 className="size-4 mr-2 animate-spin" />}
          {scriptMode === 'inline' && !isEditing ? 'Create & Add' : isEditing ? 'Save' : 'Add'}
        </Button>
      </div>
    </>
  )
}
