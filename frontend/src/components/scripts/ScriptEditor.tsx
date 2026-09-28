import { useEffect, useRef, useState } from "react"
import { TriangleAlert } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { useIsDarkMode } from "@/hooks/useIsDarkMode"
import { EditorScriptType } from "@/store/scripts"
import ReactKotlinPlayground, { type KotlinEditorHandle } from "@/kotlinScript/index.mjs"

export interface ScriptEditorScript {
  name: string
  script: string
}

/**
 * Deliberately editor-only: no name field, no Compile/Run buttons, no footer.
 *
 * There used to be a second, fuller layout here behind a `compact` prop — a name Card, a footer
 * of actions — but every mount site passed `compact`, so none of it had been reachable for as
 * long as it had existed. Each caller renders its own Compile/Run pair anyway, and they differ in
 * label ("Run" vs "Test"), size and which mutation they fire, so there is nothing shared to hoist
 * back in here.
 */
export interface ScriptEditorProps {
  /** The script to display/edit */
  script: ScriptEditorScript

  /** Script ID for key generation (optional) */
  id?: number | string

  /** The script type, determines the Kotlin wrapper for syntax highlighting/autocomplete */
  scriptType?: EditorScriptType

  /** Whether the editor is in read-only mode */
  readOnly?: boolean

  /** Called when the script code changes (editable mode only) */
  onScriptChange?: (script: string) => void
}

/**
 * What the `kotlin-playground` widget is handed: the script-type marker, then the user's body
 * between the widget's own fold markers.
 *
 * Both kinds of marker are load-bearing, for different consumers:
 *
 * - `//sampleStart` / `//sampleEnd` belong to the **widget**. They fold the editor down to the
 *   body alone, make `onChange` hand back only the body, and offset every position the widget
 *   reports or asks about. It strips them from the text before it talks to the server, so the
 *   backend never sees them.
 * - `//@lighting7-script-type=` belongs to the **backend**. It is the only way the script type can
 *   reach `/api/script-editor`: the widget owns the request shape, and its base URL is a module-level
 *   global that every `playground()` call overwrites, so a per-type endpoint would have two
 *   editors of different types poisoning each other. It sits above `//sampleStart` so the fold
 *   hides it from the user, and first in the document so it wins over any line in the body that
 *   looks like one.
 *
 * There used to be a synthetic base class and an import list here, one per script type. They were
 * not presentation: the widget sends everything outside the fold markers verbatim, so what the
 * backend actually compiled was that stand-in, and its constructor signature had to be kept in
 * step with the real base class by hand. With only the marker line, `ScriptSourceWrapper` compiles
 * the body against the genuine `.kts` template for its type — which is the whole point of serving
 * the language services from the app's own compiler.
 */
function wrapForEditor(scriptType: EditorScriptType, body: string) {
  return `//@lighting7-script-type=${scriptType}\n//sampleStart\n${body}\n//sampleEnd\n`
}

export function ScriptEditor({
  script,
  id,
  scriptType = 'GENERAL',
  readOnly = false,
  onScriptChange,
}: ScriptEditorProps) {
  const isDarkMode = useIsDarkMode()
  const editorRef = useRef<KotlinEditorHandle | null>(null)

  /**
   * The body this editor last reported outwards. A `script.script` equal to it is our own value
   * coming back around through the caller's state, not a new one to write.
   */
  const reportedRef = useRef<string | null>(null)

  const [languageServiceDown, setLanguageServiceDown] = useState(false)

  /**
   * Bumped by Retry to force a remount. The widget nulls its cached compiler-version list when
   * the probe fails, so a fresh mount genuinely refetches rather than reusing the failure.
   */
  const [retryNonce, setRetryNonce] = useState(0)

  // The widget reads identity, script type and theme at construction only, so each needs a
  // remount rather than a re-render.
  const editorKey = `${id ?? "new"}-${scriptType}-${isDarkMode ? "dark" : "light"}-${retryNonce}`

  // A remount for any reason gets a fresh attempt at the language service, so the banner from the
  // last one must not outlive it.
  useEffect(() => {
    setLanguageServiceDown(false)
  }, [editorKey])

  const handleChange = (code: string) => {
    reportedRef.current = code
    onScriptChange?.(code)
  }

  // Write a changed `script.script` through to the live editor, which makes this a controlled
  // component like the name fields beside it. Without it the widget owns the text outright once
  // mounted — the mount path early-returns on an already-initialized node and nothing else pushed
  // a value — so ScriptForm's Reset put `editCode` back while the editor kept the edited body,
  // and the next keystroke handed that edited body straight back.
  useEffect(() => {
    if (script.script === reportedRef.current) return
    editorRef.current?.setBody(script.script)
  }, [script.script])

  return (
    <div className="space-y-2">
      {languageServiceDown && (
        <Alert variant="destructive">
          <TriangleAlert className="size-4" />
          <AlertDescription className="flex w-full items-center justify-between gap-2">
            <span>Language service unreachable — the editor is read-only.</span>
            <Button variant="outline" size="sm" onClick={() => setRetryNonce((nonce) => nonce + 1)}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <div className="kotlin-editor overflow-x-auto min-w-0">
        <ReactKotlinPlayground
          mode="kotlin"
          lines="true"
          onChange={readOnly ? undefined : handleChange}
          onInitFailure={() => setLanguageServiceDown(true)}
          // The probe failing at mount is only the first moment this can go. The widget's own
          // error channel is the later one — a desk restart mid-session, say — and it degrades the
          // same way, so it raises the same banner rather than a console line nobody is watching.
          onError={(errors) => {
            console.warn("[script-editor] the Kotlin playground reported an error", errors)
            setLanguageServiceDown(true)
          }}
          getEditor={(editor) => {
            editorRef.current = editor
            // The widget takes a moment to come up, and the effect below only fires when
            // `script.script` moves — so a change that landed while it was still initializing
            // would be dropped for good, leaving the editor showing text the caller no longer
            // holds. The handle's arrival is the other end of the same sync.
            if (editor && script.script !== reportedRef.current) editor.setBody(script.script)
          }}
          value={wrapForEditor(scriptType, script.script)}
          highlightOnFly="true"
          autocomplete={readOnly ? undefined : "true"}
          matchBrackets={readOnly ? undefined : "true"}
          // `highlightOnly` is presence-tested by the widget, so the read-only arm
          // must be `undefined` and not `"false"`; `autocomplete` and
          // `matchBrackets` above are compared `=== "true"` instead.
          highlightOnly={readOnly ? "true" : undefined}
          theme={isDarkMode ? "darcula" : "idea"}
          key={editorKey}
        />
      </div>
    </div>
  )
}
