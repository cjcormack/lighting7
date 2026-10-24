import { GitFork } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SheetBody, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LazyScriptEditor } from '@/components/scripts/LazyScriptEditor'
import type { FxLibraryEntry, FxParameterDef } from '@/store/fixtureFx'
import {
  FX_MODE_LABELS,
  FX_SOURCE_LABELS,
  OUTPUT_TYPE_LABELS,
  displayName,
  fxModeToEditorType,
  type FxSource,
} from './fxLibraryModel'

/**
 * A library entry, read-only — what a **built-in** row opens (library-sheets plan §3.2), and what
 * every row opens off the running project, where nothing on the FX Library is live (D12). Its
 * parameters and its script, in the editor's read-only form.
 *
 * [onFork] draws *Fork* in the footer — the bar's verb for the row that is open, since a built-in is
 * changed only by forking it (D9). Absent where Fork is refused: off the running project, or for an
 * entry that is not a built-in. [forkRefusal] draws it disabled with its reason instead — a built-in
 * the desk will not fork (a step effect), where an absent button would leave the operator guessing.
 */
export function FxDetailSheet({
  effect,
  source,
  onFork,
  forkRefusal,
  forking = false,
}: {
  effect: FxLibraryEntry
  source: FxSource
  onFork?: () => void
  forkRefusal?: string | null
  forking?: boolean
}) {
  const name = displayName(effect.name)
  const editorType = fxModeToEditorType(effect.fxMode)

  return (
    <>
      <SheetHeader className="space-y-2">
        <SheetTitle className="flex flex-wrap items-center gap-2">
          {name}
          <Badge variant={source === 'builtIn' ? 'secondary' : 'default'}>{FX_SOURCE_LABELS[source]}</Badge>
          <Badge variant="outline">{OUTPUT_TYPE_LABELS[effect.outputType] ?? effect.outputType}</Badge>
          <Badge variant="outline" className="capitalize">
            {effect.family}
          </Badge>
          {effect.fxMode && effect.fxMode !== 'STANDARD' && (
            <Badge variant="outline" className="text-xs">
              {FX_MODE_LABELS[effect.fxMode] ?? effect.fxMode}
            </Badge>
          )}
        </SheetTitle>
        {effect.compatibleAttributes.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Drives:</span>
            {effect.compatibleAttributes.map((prop) => (
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
                {effect.parameters.map((param: FxParameterDef) => (
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

      {(onFork || forkRefusal) && (
        <SheetFooter className="flex-row items-center justify-end gap-2">
          {forkRefusal && <p className="mr-auto text-[11px] text-muted-foreground">{forkRefusal}</p>}
          <Button
            onClick={onFork}
            disabled={forking || forkRefusal != null || onFork == null}
            title={forkRefusal ?? `Make a custom copy of ${name} you can edit`}
          >
            <GitFork className="size-4" />
            {forking ? 'Forking…' : 'Fork'}
          </Button>
        </SheetFooter>
      )}
    </>
  )
}
