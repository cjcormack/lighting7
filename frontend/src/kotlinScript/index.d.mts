import type { FunctionComponent } from "react"
import type { KotlinEditorHandle, ReactKotlinPlaygroundProps } from "./component.mjs"

export type { KotlinEditorHandle, ReactKotlinPlaygroundProps }

/**
 * `component.mjs` with the `kotlin-playground` factory already bound, which is the
 * only form anything in `src/` mounts.
 */
declare const ReactKotlinPlayground: FunctionComponent<
  Omit<ReactKotlinPlaygroundProps, "playground">
>

export default ReactKotlinPlayground
