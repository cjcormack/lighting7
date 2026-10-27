import { describe, expect, it } from 'vitest'
import { webSocketUrlFor } from './webSocketUrl'

describe('webSocketUrlFor', () => {
  it('uses ws: for a page served over HTTP', () => {
    expect(webSocketUrlFor({ protocol: 'http:', host: 'localhost:8413' })).toBe('ws://localhost:8413/api')
  })

  it('uses wss: for a page served over HTTPS, as through the tunnel', () => {
    expect(webSocketUrlFor({ protocol: 'https:', host: 'desk.ngrok.app' })).toBe('wss://desk.ngrok.app/api')
  })
})
