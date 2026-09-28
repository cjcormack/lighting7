/**
 * Best-effort RTK Query / fetch error → user-facing string.
 *
 * Callers get an untyped `FetchBaseQueryError | SerializedError` union, whose shape varies a lot:
 * an HTTP failure carries `{ status: number, data }`, a network failure carries a *string* status
 * (`FETCH_ERROR`) and no `data` at all, and a thrown error is `{ name, message }`. This normalises
 * every one of them into something worth showing a user, and never returns `[object Object]`.
 *
 * Note that RTK Query rejections are plain objects, never `Error` instances — `err instanceof
 * Error` is always false for them, so use this rather than reaching for `err.message`.
 */
export function formatError(err: unknown): string {
  if (err === null || err === undefined) return 'Request failed'
  if (typeof err === 'string') return err
  if (typeof err !== 'object') return String(err)

  const e = err as Record<string, unknown>

  // FetchBaseQueryError variants that never reached the server (or came back unreadable).
  // These have a *string* status and no useful body.
  if (typeof e.status === 'string') {
    switch (e.status) {
      case 'FETCH_ERROR':
        return 'Could not reach the server'
      case 'TIMEOUT_ERROR':
        return 'The server took too long to respond'
      case 'PARSING_ERROR':
        return typeof e.originalStatus === 'number'
          ? `Unreadable response from the server (HTTP ${e.originalStatus})`
          : 'Unreadable response from the server'
      case 'CUSTOM_ERROR':
        return typeof e.error === 'string' && e.error ? e.error : 'Request failed'
    }
  }

  // The backend's standard error DTO: { "error": "..." }
  const data = e.data
  if (data !== null && typeof data === 'object') {
    const message = (data as { error?: unknown }).error
    if (typeof message === 'string' && message.trim()) return message.trim()
  }

  // A plain-text body. Skip HTML — a proxy's 502 page is noise, not a message.
  if (typeof data === 'string') {
    const trimmed = data.trim()
    if (trimmed && !trimmed.startsWith('<')) return trimmed
  }

  // SerializedError (a thrown/rejected error rather than an HTTP failure).
  if (typeof e.message === 'string' && e.message.trim()) return e.message.trim()

  // An HTTP failure with an empty or unusable body.
  if (typeof e.status === 'number') return `Request failed (HTTP ${e.status})`

  return 'Request failed'
}
