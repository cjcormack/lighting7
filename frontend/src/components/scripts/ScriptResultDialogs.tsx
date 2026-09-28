import { AlertTriangle, XCircle, Info } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { CompileResult, RunResult } from "@/store/scripts"

export interface ScriptCompileDialogProps {
  compileResult?: CompileResult
  hasNotCompiled: boolean
  isCompiling: boolean
  resetCompile: () => void
}

export function ScriptCompileDialog({
  compileResult,
  hasNotCompiled,
  isCompiling,
  resetCompile,
}: ScriptCompileDialogProps) {
  const open = !hasNotCompiled

  let title: string
  let titleClass: string
  if (isCompiling) {
    title = "Compiling..."
    titleClass = ""
  } else if (compileResult?.success) {
    title = "Compilation Successful"
    titleClass = "text-green-600"
  } else {
    title = "Compilation Failed"
    titleClass = "text-destructive"
  }

  return (
    <Dialog open={open} onOpenChange={(open) => !open && resetCompile()}>
      <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className={titleClass}>{title}</DialogTitle>
        </DialogHeader>
        <div className="space-y-2">
          {compileResult?.messages.map((message, index) => (
            <div key={index} className="flex items-start gap-3 p-2">
              {message.severity === "ERROR" ? (
                <XCircle className="size-6 text-destructive flex-shrink-0" />
              ) : message.severity === "WARNING" ? (
                <AlertTriangle className="size-6 text-yellow-500 flex-shrink-0" />
              ) : (
                <Info className="size-6 flex-shrink-0" />
              )}
              <div>
                <p className="font-medium">{message.message}</p>
                {message.sourcePath && (
                  <p className="text-sm text-muted-foreground">
                    {message.sourcePath} {message.location}
                  </p>
                )}
              </div>
            </div>
          ))}
        </div>
        <DialogFooter>
          <Button onClick={resetCompile}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export interface ScriptRunDialogProps {
  runResult?: RunResult
  hasNotRun: boolean
  isRunning: boolean
  resetRun: () => void
}

export function ScriptRunDialog({
  runResult,
  hasNotRun,
  isRunning,
  resetRun,
}: ScriptRunDialogProps) {
  const open = !hasNotRun

  let title: string
  let titleClass: string
  if (isRunning) {
    title = "Running..."
    titleClass = ""
  } else if (runResult?.status === "success") {
    title = "Run Successful"
    titleClass = "text-green-600"
  } else {
    title = "Run Failed"
    titleClass = "text-destructive"
  }

  return (
    <Dialog open={open} onOpenChange={(open) => !open && resetRun()}>
      <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className={titleClass}>{title}</DialogTitle>
        </DialogHeader>
        <div className="space-y-2">
          {runResult?.result != null ? (
            <pre className="whitespace-pre-wrap p-2">{runResult.result}</pre>
          ) : runResult?.messages != null ? (
            runResult.messages.map((message, index) => (
              <div key={index} className="flex items-start gap-3 p-2">
                {message.severity === "ERROR" ? (
                  <XCircle className="size-6 text-destructive flex-shrink-0" />
                ) : message.severity === "WARNING" ? (
                  <AlertTriangle className="size-6 text-yellow-500 flex-shrink-0" />
                ) : (
                  <Info className="size-6 flex-shrink-0" />
                )}
                <div>
                  <p className="font-medium">{message.message}</p>
                  {message.sourcePath && (
                    <p className="text-sm text-muted-foreground">
                      {message.sourcePath} {message.location}
                    </p>
                  )}
                </div>
              </div>
            ))
          ) : null}
        </div>
        <DialogFooter>
          <Button onClick={resetRun}>Close</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
