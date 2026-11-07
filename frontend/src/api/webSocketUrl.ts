/**
 * The desk's WebSocket URL for a page served from `location`: `wss:` under HTTPS, `ws:` otherwise.
 */
export function webSocketUrlFor(location: Pick<Location, 'protocol' | 'host'>): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${location.host}/api`
}
