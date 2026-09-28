import { GitFork } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SheetBody, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LazyScriptEditor } from '@/components/scripts/LazyScriptEditor'
import type { EffectLibraryEntry, EffectParameterDef } from '@/store/fixtureFx'
import {
  EFFECT_MODE_LABELS,
  FX_SOURCE_LABELS,
  OUTPUT_TYPE_LABELS,
  displayName,
  effectModeToEditorType,
  type FxSource,
} from './fxLibraryModel'

/**
 * A library entry, read-only — what a **built-in** row opens (library-sheets plan §3.2), and what
 * every row opens off the running project, where nothing on the FX Library is live (D12). Its
 * parameters and its script, in the editor's read-only form.
 *
 * [onFork] draws *Fork* in the footer — the bar's verb for the row that is open, since a built-in is
 * changed only by forking it (D9). Absent where Fork is refused: off the running project, or for an
 * entry that is not a built-in.
 */
export function EffectDetailSheet({
  effect,
  source,
  onFork,
  forking = false,
}: {
  effect: EffectLibraryEntry
  source: FxSource
  onFork?: () => void
  forking?: boolean
}) {
  const name = displayName(effect.name)
  const editorType = effectModeToEditorType(effect.effectMode)

  return (
    <>
      <SheetHeader className="space-y-2">
        <SheetTitle className="flex flex-wrap items-center gap-2">
          {name}
          <Badge variant={source === 'builtIn' ? 'secondary' : 'default'}>{FX_SOURCE_LABELS[source]}</Badge>
          <Badge variant="outline">{OUTPUT_TYPE_LABELS[effect.outputType] ?? effect.outputType}</Badge>
          <Badge variant="outline" className="capitalize">
            {effect.category}
          </Badge>
          {effect.effectMode && effect.effectMode !== 'STANDARD' && (
            <Badge variant="outline" className="text-xs">
              {EFFECT_MODE_LABELS[effect.effectMode] ?? effect.effectMode}
            </Badge>
          )}
        </SheetTitle>
        {effect.compatibleProperties.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Drives:</span>
            {effect.compatibleProperties.map((prop) => (
              <Badge key={prop} variant="outline" className="text-xs">
                {prop}
              </Badge>
            ))}
          </div>
        )}
      </SheetHeader>

      <SheetBody>
        {effect.parameters.length > 0 && (
          <div>
            <p className="mb-2 text-sm font-medium text-muted-foreground">Parameters</p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Default</TableHead>
                  <TableHead className="hidden sm:table-cell">Description</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {effect.parameters.map((param: EffectParameterDef) => (
                  <TableRow key={param.name}>
                    <TableCell className="font-mono text-sm">{param.name}</TableCell>
                    <TableCell>{param.type}</TableCell>
                    <TableCell className="font-mono text-sm">{param.defaultValue}</TableCell>
                    <TableCell className="hidden text-muted-foreground sm:table-cell">{param.description}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}

        {effect.script && (
          <LazyScriptEditor
            script={{ name, script: effect.script }}
            id={`view-${effect.name}`}
            scriptType={editorType}
            readOnly
          />
        )}
      </SheetBody>

      {onFork && (
        <SheetFooter className="flex-row justify-end gap-2">
          <Button onClick={onFork} disabled={forking} title={`Make a custom copy of ${name} you can edit`}>
            <GitFork className="size-4" />
            {forking ? 'Forking…' : 'Fork'}
          </Button>
        </SheetFooter>
      )}
    </>
  )
}
