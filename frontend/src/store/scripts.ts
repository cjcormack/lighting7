// Script types - endpoints are now in projects.ts

export type ScriptType = 'GENERAL' | 'FX_DEFINITION' | 'FX_APPLICATION' | 'FX_CALC' | 'FX_CALC_STATEFUL' | 'FX_CALC_COMPOSITE'

/** Editor wrapper types for FX calc scripts (used by FX Library, not regular scripts) */
export type FxCalcEditorType = 'FX_CALC' | 'FX_CALC_STATEFUL' | 'FX_CALC_COMPOSITE'

/** All editor types that ScriptEditor supports */
export type EditorScriptType = ScriptType

export type Script = ScriptDetails & {
  id: number
}

// Base script fields (used for create/update requests)
export type ScriptInput = {
  name: string
  script: string
  scriptType?: ScriptType
}

// Full script details including server-computed fields
export type ScriptDetails = ScriptInput & {
  scriptType: ScriptType
  // Usage tracking fields (server-provided)
  usedByProperties: string[]
  canDelete: boolean
  cannotDeleteReason: string | null
}

export type CompileResultMessage = {
  severity: 'INFO'|'WARNING'|'ERROR'
  message: string
  sourcePath?: string
  location?: string
}

export type CompileRequest = {
  script: string
  scriptType?: ScriptType
}

export type CompileResult = {
  success: boolean
  messages: CompileResultMessage[]
}

export type RunRequest = CompileRequest & {
  scriptId?: number
}

export type RunResult = {
  status: 'success'|'compileError'|'exception'
  messages: CompileResultMessage[]
  result?: string
}
