import { describe, expect, it } from 'vitest'
import { checkFor, checkFromResult, lineCount, type ScriptCheck } from './scriptCheck'

describe('checkFromResult', () => {
  it('reads a clean compile as ✓, counting warnings', () => {
    expect(checkFromResult({ success: true, messages: [] })).toEqual({ status: 'ok', warnings: 0 })
    expect(
      checkFromResult({ success: true, messages: [{ severity: 'WARNING', message: 'unused', location: '3:1' }] }),
    ).toEqual({ status: 'ok', warnings: 1 })
  })

  it('reads a failure as the error count and the first error’s line', () => {
    expect(
      checkFromResult({
        success: false,
        messages: [
          { severity: 'WARNING', message: 'unused', location: '2:1' },
          { severity: 'ERROR', message: 'Unresolved reference: foo', location: '14:5' },
          { severity: 'ERROR', message: 'Type mismatch', location: '20:1' },
        ],
      }),
    ).toEqual({ status: 'error', errors: 2, line: 14, message: 'Unresolved reference: foo' })
  })

  it('still reads a failure with no located error as one error', () => {
    expect(checkFromResult({ success: false, messages: [] })).toEqual({
      status: 'error',
      errors: 1,
      line: null,
      message: 'Does not compile',
    })
  })
})

describe('checkFor', () => {
  const ok: ScriptCheck = { script: 'val a = 1', scriptType: 'GENERAL', status: 'ok', warnings: 0 }
  const checks = new Map([[4, ok]])

  it('answers while the script still has the text and type it compiled', () => {
    expect(checkFor({ id: 4, script: 'val a = 1', scriptType: 'GENERAL' }, checks)).toBe(ok)
  })

  it('reads *not checked* again once the script’s text — or its type — changes', () => {
    expect(checkFor({ id: 4, script: 'val a = 2', scriptType: 'GENERAL' }, checks)).toBeNull()
    expect(checkFor({ id: 4, script: 'val a = 1', scriptType: 'FX_APPLICATION' }, checks)).toBeNull()
    expect(checkFor({ id: 5, script: 'val a = 1', scriptType: 'GENERAL' }, checks)).toBeNull()
  })
})

describe('lineCount', () => {
  it('does not count a trailing newline as a line', () => {
    expect(lineCount('')).toBe(0)
    expect(lineCount('a')).toBe(1)
    expect(lineCount('a\nb\n')).toBe(2)
    expect(lineCount('a\n\nb')).toBe(3)
  })
})
