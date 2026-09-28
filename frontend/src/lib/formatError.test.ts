import { describe, it, expect } from 'vitest'
import { formatError } from './formatError'

describe('formatError', () => {
  it('prefers the backend ErrorResponse body', () => {
    expect(
      formatError({ status: 409, data: { error: 'That cue number is already used in this stack.' } }),
    ).toBe('That cue number is already used in this stack.')
  })

  it('trims whitespace off the server message', () => {
    expect(formatError({ status: 400, data: { error: '  bad input  ' } })).toBe('bad input')
  })

  it('describes a network failure in plain language', () => {
    // The pre-hardening version returned "[object Object]" here: FETCH_ERROR has no numeric
    // status and no data, so both branches of the old implementation missed.
    expect(formatError({ status: 'FETCH_ERROR', error: 'TypeError: Failed to fetch' })).toBe(
      'Could not reach the server',
    )
  })

  it('describes a timeout', () => {
    expect(formatError({ status: 'TIMEOUT_ERROR', error: 'AbortError' })).toBe(
      'The server took too long to respond',
    )
  })

  it('reports the original status on a parsing error', () => {
    expect(formatError({ status: 'PARSING_ERROR', originalStatus: 502, data: '<html>' })).toBe(
      'Unreadable response from the server (HTTP 502)',
    )
  })

  it('passes through a CUSTOM_ERROR message', () => {
    expect(formatError({ status: 'CUSTOM_ERROR', error: 'aborted by user' })).toBe(
      'aborted by user',
    )
  })

  it('uses a plain-text body when there is no structured error', () => {
    expect(formatError({ status: 500, data: 'boom' })).toBe('boom')
  })

  it('ignores an HTML error page body', () => {
    // A proxy's 502 page is noise, not a message worth showing.
    expect(formatError({ status: 502, data: '<html><body>Bad Gateway</body></html>' })).toBe(
      'Request failed (HTTP 502)',
    )
  })

  it('falls back to the status for a bodyless HTTP failure', () => {
    expect(formatError({ status: 500, data: undefined })).toBe('Request failed (HTTP 500)')
  })

  it('reads SerializedError.message', () => {
    expect(formatError({ name: 'TypeError', message: 'x is not a function' })).toBe(
      'x is not a function',
    )
  })

  it('handles an Error instance', () => {
    expect(formatError(new Error('kaboom'))).toBe('kaboom')
  })

  it('never returns [object Object]', () => {
    for (const input of [undefined, null, {}, { status: {} }, { data: {} }]) {
      expect(formatError(input)).toBe('Request failed')
    }
  })
})
