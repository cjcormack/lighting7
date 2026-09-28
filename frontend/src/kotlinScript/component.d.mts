import { Component } from "react"

/**
 * The prop shape `component.mjs` forwards to the `kotlin-playground` widget.
 *
 * This file replaces a `propTypes` block that React 19 no longer validates, so it is
 * now the only description of the surface — and it is deliberately stricter than that
 * block was, because two of the widget's attributes are read by *presence* rather than
 * by value:
 *
 * - `highlightOnly` is `targetNode.hasAttribute(...)` unless the value is exactly
 *   `"nocursor"`. So `highlightOnly="false"` makes the editor read-only, and only
 *   omitting the prop makes it editable. It is typed here so the falsy case can only
 *   be `undefined` — the natural tidy-up `String(readOnly)` no longer compiles.
 * - `noneMarkers`, `indent`, `from` and `to` are likewise presence-tested, but the
 *   wrapper has no caller for them.
 *
 * Every other flag below is compared `=== "true"`, so `"false"` genuinely turns it off.
 */
/**
 * Imperative access to a live editor, handed out through `getEditor`.
 *
 * Both sides speak the *body* — the text between the widget's fold markers, which is what
 * `onChange` reports and what the consumer stores. The markers themselves belong to the widget
 * and are put back on the way to the server.
 */
export interface KotlinEditorHandle {
  /** Write a body into the editor without it echoing back out as a user edit. */
  setBody(code: string): void
}

export interface ReactKotlinPlaygroundProps {
  /** Class name for the wrapper `div` the widget mounts inside. */
  className?: string

  /** The `kotlin-playground` factory. `index.mjs` supplies this; callers do not. */
  playground?: (target: string | HTMLElement, ...args: unknown[]) => unknown

  /**
   * Fires on the keystroke, with the editor's body.
   *
   * This is the wrapper's own CodeMirror listener, not the widget's `onChange`, which is
   * debounced 500 ms with no flush — see the note at the top of `component.mjs`.
   */
  onChange?: (code: string) => void

  /**
   * The widget mounted, but not as a working editor: either its `/versions` probe failed and it
   * fell back to a highlight-only editor, or `playground()` rejected outright. The operator is
   * otherwise given an editor that silently refuses every keystroke.
   */
  onInitFailure?: (error?: unknown) => void

  /** Receives the imperative handle when the editor goes live, and `null` when it is torn down. */
  getEditor?: (editor: KotlinEditorHandle | null) => void

  // Event proxies. The wrapper forwards each of these straight through.
  onConsoleOpen?: () => void
  onConsoleClose?: () => void
  getInstance?: (instance: unknown) => void
  getJsCode?: (code: string) => void
  onRun?: () => void
  onError?: (errors: unknown) => void

  /** Initial editor content, including the widget's own `//sampleStart` fold markers. */
  value?: string

  version?: string
  args?: string | string[]
  targetPlatform?: "junit" | "canvas" | "js" | "java"

  /** Presence-tested — see the note above. Never pass `"false"`. */
  highlightOnly?: "true" | "nocursor"

  jsLibs?: string
  autoIndent?: boolean
  theme?: string
  mode?:
    | "kotlin"
    | "js"
    | "java"
    | "groovy"
    | "xml"
    | "c"
    | "shell"
    | "swift"
    | "obj-c"
  minCompilerVersion?: string
  autocomplete?: "true" | "false"
  highlightOnFly?: "true" | "false"
  indent?: number
  lines?: "true" | "false"
  from?: number
  to?: number
  outputHeight?: number
  matchBrackets?: "true" | "false"
  mobileShorterHeight?: number
}

export default class ReactKotlinPlayground extends Component<ReactKotlinPlaygroundProps> {}
