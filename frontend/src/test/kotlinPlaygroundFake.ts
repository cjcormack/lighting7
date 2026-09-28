// A stand-in for the `kotlin-playground` widget, for tests of the editor wrapper and its mounts.
// Not a test file (no `.test` suffix), so it isn't collected as a suite.
//
// The widget is faked rather than mocked away, because the behaviours the wrapper reasons about
// are the widget's own. All of the below is reproduced from `kotlin-playground@1.34.0`'s source
// (`dist/playground.js`), not from its README, which documents none of it:
//
//  • `create()` resolves with the instances it built — including on a failed `/versions` probe,
//    where it logs to the console and builds a highlight-only editor with *no* event functions.
//    That asymmetry is the only signal the read-only fallback gives, and is why "did `getInstance`
//    fire?" is the test for a live editor rather than "did the promise resolve?".
//  • the fold arithmetic: with `//sampleStart` / `//sampleEnd` present, CodeMirror holds the body
//    alone and `getCode()` puts the surrounding text back on the way to the server.
//  • the target node is marked initialized, which is what makes a second mount on it a no-op.

export const SAMPLE_START = '//sampleStart'
export const SAMPLE_END = '//sampleEnd'
export const INITED_ATTRIBUTE = 'data-kotlin-playground-initialized'

export class FakeCodeMirror {
  private text: string
  private handlers: Array<(cm: FakeCodeMirror) => void> = []
  cursor = { line: 3, ch: 7 }

  constructor(text: string) {
    this.text = text
  }

  getValue() { return this.text }
  getCursor() { return this.cursor }
  setCursor(cursor: { line: number; ch: number }) { this.cursor = cursor }

  /** CodeMirror reports a programmatic write as a `change` like any other. */
  setValue(text: string) {
    this.text = text
    this.emit()
  }

  on(event: string, fn: (cm: FakeCodeMirror) => void) {
    if (event === 'change') this.handlers.push(fn)
  }

  off(event: string, fn: (cm: FakeCodeMirror) => void) {
    if (event === 'change') this.handlers = this.handlers.filter((handler) => handler !== fn)
  }

  /** What a keystroke does: the text moves and `change` fires synchronously. */
  type(text: string) {
    this.text += text
    this.emit()
  }

  private emit() {
    for (const handler of [...this.handlers]) handler(this)
  }
}

/** The `ExecutableFragment` handed back through `getInstance`. It owns the editor. */
export class FakeFragment {
  readonly codemirror: FakeCodeMirror
  readonly prefix: string
  readonly suffix: string

  constructor(code: string) {
    const startIndex = code.indexOf(SAMPLE_START)
    const endIndex = code.indexOf(SAMPLE_END)

    let prefix = ''
    let suffix = ''
    let sample = code
    if (startIndex > -1 && endIndex > -1) {
      prefix = code.substring(0, startIndex)
      suffix = code.substring(endIndex + SAMPLE_END.length)
      sample = code.substring(startIndex + SAMPLE_START.length + 1, endIndex - 1)
    }
    if (suffix.endsWith('\n')) suffix = suffix.slice(0, -1)

    this.prefix = prefix
    this.suffix = suffix
    this.codemirror = new FakeCodeMirror(sample)
  }

  /** The full source the widget would send to the language service, fold markers stripped. */
  getCode() { return this.prefix + this.codemirror.getValue() + this.suffix }
}

/** The `ExecutableCode` the `playground()` promise resolves to. It, and only it, owns `destroy`. */
export class FakeExecutableCode {
  destroyed = false

  constructor(private readonly node: HTMLElement) {}

  destroy() {
    this.destroyed = true
    this.node.removeAttribute(INITED_ATTRIBUTE)
  }
}

export interface Mount {
  node: HTMLElement
  /** The text the widget read out of the target node — what the backend will be asked about. */
  initialCode: string
  /** `null` on the read-only fallback, which is constructed without the event functions. */
  fragment: FakeFragment | null
  executable: FakeExecutableCode
  options: Record<string, unknown>
}

/** Every mount the fake has served, oldest first. Reset it in `beforeEach`. */
export const mounts: Mount[] = []

/** Whether the widget's one-per-page `/versions` probe is answered. Reset it in `beforeEach`. */
export const probe = { versionsAvailable: true }

export function resetKotlinPlaygroundFake() {
  mounts.length = 0
  probe.versionsAvailable = true
}

export function fakePlayground(node: HTMLElement, options: Record<string, unknown>) {
  // The widget skips a node it has already taken over, which is why remounting is keyed rather
  // than re-rendered.
  if (node.getAttribute(INITED_ATTRIBUTE) === 'true') return Promise.resolve([])

  const initialCode = node.textContent ?? ''
  node.setAttribute(INITED_ATTRIBUTE, 'true')

  const executable = new FakeExecutableCode(node)
  let fragment: FakeFragment | null = null

  if (probe.versionsAvailable) {
    fragment = new FakeFragment(initialCode)
    const getInstance = options.getInstance as ((instance: unknown) => void) | undefined
    getInstance?.(fragment)
  }

  mounts.push({ node, initialCode, fragment, executable, options })
  return Promise.resolve([executable])
}
