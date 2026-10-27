/**
 * The desk's WebSocket URL for a page served from `location`: `wss:` under HTTPS, so a page
 * reached through the remote-access tunnel can open it, `ws:` otherwise.
 */
export function webSocketUrlFor(location: Pick<Location, 'protocol' | 'host'>): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${location.host}/api`
}
