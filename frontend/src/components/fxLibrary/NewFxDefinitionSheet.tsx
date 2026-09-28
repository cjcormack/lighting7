import { useState } from 'react'
import { Plus, Play, Trash2, Wrench } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
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
import { useCreateFxDefinitionMutation } from '@/store/fxDefinitions'
import type { EffectParameterDef } from '@/store/fixtureFx'
import { FX_CATEGORY_LABELS, FX_CATEGORY_ORDER, effectModeToEditorType, uniqueEffectId } from './fxLibraryModel'

const PARAM_TYPES = ["ubyte", "int", "double", "float", "boolean", "colour", "colourList", "easingCurve", "string"]

const NEW_FX_TEMPLATE = `val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (sin(phase * 2 * PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
`

const NEW_FX_DEFAULTS = {
  category: "dimmer",
  outputType: "SLIDER",
  effectMode: "STANDARD",
  parameters: [
    { name: "min", type: "ubyte", defaultValue: "0", description: "Minimum value" },
    { name: "max", type: "ubyte", defaultValue: "255", description: "Maximum value" },
  ] as EffectParameterDef[],
}

/**
 * A new custom definition — the library row's *New effect*. Its `effectId` is the name without
 * spaces, **made unique across the library** ([uniqueEffectId], D9's rule): a new effect named
 * *Pulse* would otherwise take the built-in's id and replace it in the registry.
 */
export function NewFxDefinitionSheet({
  takenIds,
  onCreated,
}: {
  /** Every id in the library and every definition's — `takenEffectIds`. */
  takenIds: readonly string[]
  onCreated: (id: number) => void
}) {
  const { data: currentProject } = useCurrentProjectQuery()
  const [runCreateMutation, { isLoading: isCreating }] = useCreateFxDefinitionMutation()
  const [
    runCompileMutation,
    { data: compileResult, isUninitialized: hasNotCompiled, isLoading: isCompiling, reset: resetCompile },
  ] = useCompileProjectScriptMutation()
  const [
    runRunMutation,
    { data: runResult, isUninitialized: hasNotRun, isLoading: isRunning, reset: resetRun },
  ] = useRunProjectScriptMutation()

  const [name, setName] = useState("")
  const trimmedName = name.trim()
  const [category, setCategory] = useState(NEW_FX_DEFAULTS.category)
  const [outputType, setOutputType] = useState(NEW_FX_DEFAULTS.outputType)
  const [effectMode, setEffectMode] = useState(NEW_FX_DEFAULTS.effectMode)
  const [scriptCode, setScriptCode] = useState(NEW_FX_TEMPLATE)
  const [parameters, setParameters] = useState<EffectParameterDef[]>(NEW_FX_DEFAULTS.parameters)

  // Everything the form starts with is a default nobody chose, so anything moved off one is work
  // worth asking about before Escape or a stray click outside takes the sheet away.
  useUnsavedChanges(
    name !== "" ||
    category !== NEW_FX_DEFAULTS.category ||
    outputType !== NEW_FX_DEFAULTS.outputType ||
    effectMode !== NEW_FX_DEFAULTS.effectMode ||
    scriptCode !== NEW_FX_TEMPLATE ||
    JSON.stringify(parameters) !== JSON.stringify(NEW_FX_DEFAULTS.parameters)
  )

  const editorType = effectModeToEditorType(effectMode)

  // Keyed off `outputType`, not `category`: the two are independent selects, and it is the output
  // type that decides which properties can actually take the effect's output. Deriving from the
  // category let Category = Position + the default Output Type = Slider (or Category = Colour with
  // any other output type) write a list the effect could never drive — the backend now rejects
  // that with a 400 naming `compatibleProperties`, a field this form doesn't expose.
  //
  // `position` rather than `["pan", "tilt"]`: a POSITION effect emits both axes at once, and an
  // axis by name resolves to a slider target that discards the pair — no light, no error. That
  // was backend sweep item A11, which narrowed the seven built-in position effects the same way.
  const compatibleProperties = (() => {
    switch (outputType) {
      case "COLOUR": return ["rgbColour"]
      case "POSITION": return ["position"]
      default: return ["dimmer"]
    }
  })()

  const handleCreate = async () => {
    try {
      const result = await runCreateMutation({
        effectId: uniqueEffectId(trimmedName.replace(/\s+/g, ""), takenIds).effectId,
        name: trimmedName,
        category,
        outputType,
        effectMode,
        parameters,
        compatibleProperties,
        script: scriptCode,
      }).unwrap()
      onCreated(result.id)
    } catch {
      // Reported by errorToastMiddleware — a compile failure is a 422 naming the diagnostics — and
      // the sheet stays open with the operator's work in it.
    }
  }

  const addParameter = () => {
    setParameters([...parameters, { name: "", type: "ubyte", defaultValue: "0", description: "" }])
  }

  const removeParameter = (index: number) => {
    setParameters(parameters.filter((_, i) => i !== index))
  }

  const updateParameter = (index: number, field: keyof EffectParameterDef, value: string) => {
    setParameters(parameters.map((p, i) => i === index ? { ...p, [field]: value } : p))
  }

  // Trimmed: a name of spaces alone would strip to an empty `effectId`.
  const canCreate = trimmedName !== "" && scriptCode !== ""

  return (
    <>
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

      <SheetHeader>
        <SheetTitle className="flex items-center gap-2">
          New FX
          <Badge variant="default">Custom</Badge>
        </SheetTitle>
      </SheetHeader>

      <SheetBody>
        {/* Metadata */}
        <div className="space-y-1.5">
          <Label htmlFor="new-fx-name">Name</Label>
          <Input
            id="new-fx-name"
            placeholder="My Custom Effect"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
          />
        </div>

        <div className="space-y-1.5">
          <Label>Category</Label>
          <Select value={category} onValueChange={setCategory}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {/* The library's own vocabulary, so the form offers every category the chips and
                  dividers draw — it listed four and left Controls out. */}
              {FX_CATEGORY_ORDER.map((c) => (
                <SelectItem key={c} value={c}>
                  {FX_CATEGORY_LABELS[c]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <Label>Output Type</Label>
          <Select value={outputType} onValueChange={setOutputType}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="SLIDER">Slider</SelectItem>
              <SelectItem value="COLOUR">Colour</SelectItem>
              <SelectItem value="POSITION">Position</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <Label>Mode</Label>
          <Select value={effectMode} onValueChange={setEffectMode}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="STANDARD">Standard</SelectItem>
              <SelectItem value="STATEFUL">Stateful</SelectItem>
              <SelectItem value="COMPOSITE">Composite</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Parameters */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <Label>Parameters</Label>
            <Button variant="outline" size="sm" onClick={addParameter}>
              <Plus className="size-3.5" />
              Add
            </Button>
          </div>
          {parameters.length === 0 && (
            <p className="text-sm text-muted-foreground">No parameters defined.</p>
          )}
          {parameters.map((param, i) => (
            <div key={i} className="grid grid-cols-[1fr_auto_1fr_1fr_auto] gap-2 items-end">
              <div>
                {i === 0 && <Label className="text-xs text-muted-foreground">Name</Label>}
                <Input
                  value={param.name}
                  onChange={(e) => updateParameter(i, "name", e.target.value)}
                  placeholder="name"
                  className="h-8 text-sm"
                />
              </div>
              <div>
                {i === 0 && <Label className="text-xs text-muted-foreground">Type</Label>}
                <Select value={param.type} onValueChange={(v) => updateParameter(i, "type", v)}>
                  <SelectTrigger className="h-8 text-sm w-[100px]"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {PARAM_TYPES.map((t) => (
                      <SelectItem key={t} value={t}>{t}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                {i === 0 && <Label className="text-xs text-muted-foreground">Default</Label>}
                <Input
                  value={param.defaultValue}
                  onChange={(e) => updateParameter(i, "defaultValue", e.target.value)}
                  placeholder="0"
                  className="h-8 text-sm"
                />
              </div>
              <div>
                {i === 0 && <Label className="text-xs text-muted-foreground">Description</Label>}
                <Input
                  value={param.description}
                  onChange={(e) => updateParameter(i, "description", e.target.value)}
                  placeholder="optional"
                  className="h-8 text-sm"
                />
              </div>
              <Button
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={() => removeParameter(i)}
              >
                <Trash2 className="size-3.5" />
              </Button>
            </div>
          ))}
        </div>

        {/* Script */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <Label>Script</Label>
            <div className="flex gap-1">
              <Button
                variant="outline"
                size="sm"
                disabled={scriptCode === "" || isCompiling || !currentProject}
                onClick={() => runCompileMutation({ projectId: currentProject!.id, script: scriptCode, scriptType: editorType })}
              >
                <Wrench className="size-3.5" />
                Compile
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={scriptCode === "" || isRunning || !currentProject}
                onClick={() => runRunMutation({ projectId: currentProject!.id, script: scriptCode, scriptType: editorType })}
              >
                <Play className="size-3.5" />
                Test
              </Button>
            </div>
          </div>
          <LazyScriptEditor
            script={{ name, script: scriptCode }}
            id="new-fx"
            scriptType={editorType}
            onScriptChange={setScriptCode}
          />
        </div>
      </SheetBody>

      <SheetFooter className="flex-row justify-end gap-2">
        <SheetClose asChild>
          <Button variant="outline">Cancel</Button>
        </SheetClose>
        <Button disabled={!canCreate || isCreating} onClick={handleCreate}>
          {isCreating ? "Creating..." : "Create"}
        </Button>
      </SheetFooter>
    </>
  )
}
