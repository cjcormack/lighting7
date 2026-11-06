// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakePort, fixtureMessage, framedWindow } from '@/test/remoteFrameFakes'
import { isRemoteMode, setRemoteLinkForTests } from './lib/remoteMode'
import { REMOTE_PARENT_META } from './lib/remoteFrame'
import { runBoot } from './remoteBoot'

const SHELL = 'https://lighting7.net'

function addMeta(content: string) {
  const meta = document.createElement('meta')
  meta.name = REMOTE_PARENT_META
  meta.content = content
  document.head.append(meta)
}

beforeEach(() => {
  document.head.innerHTML = ''
  document.body.innerHTML = '<div id="root"></div>'
})
afterEach(() => {
  setRemoteLinkForTests(null)
  vi.useRealTimers()
})

describe('runBoot', () => {
  it('loads the app at once on a page that is not framed, even with the meta', async () => {
    addMeta(SHELL)
    const loadMain = vi.fn(async () => {})
    expect((await runBoot(window, loadMain)).kind).toBe('local')
    expect(loadMain).toHaveBeenCalledTimes(1)
    expect(isRemoteMode()).toBe(false)
  })

  it('loads the app at once on a framed page without the meta, and posts nothing', async () => {
    const { win, parent } = framedWindow()
    const loadMain = vi.fn(async () => {})
    expect((await runBoot(win, loadMain)).kind).toBe('local')
    expect(loadMain).toHaveBeenCalledTimes(1)
    expect(parent.posted).toEqual([])
  })

  it('waits for the shell’s port before loading the app, ignoring a port from another origin', async () => {
    addMeta(`${SHELL}/r/install-1`)
    const { win, parent, deliver } = framedWindow()
    const loadMain = vi.fn(async () => {
      // `lightingApi` reads the mode as it is built, so it must already be decided here.
      expect(isRemoteMode()).toBe(true)
    })
    const booting = runBoot(win, loadMain, 1_000)

    expect(parent.posted).toEqual([{ message: fixtureMessage((e) => e.message.type === 'hello'), targetOrigin: SHELL }])
    deliver({ type: 'port' }, { origin: 'https://evil.example', ports: [new FakePort()] })
    await Promise.resolve()
    expect(loadMain).not.toHaveBeenCalled()

    deliver({ type: 'port' }, { origin: SHELL, ports: [new FakePort()] })
    expect((await booting).kind).toBe('remote')
    expect(loadMain).toHaveBeenCalledTimes(1)
  })

  it('never loads the app when the shell does not answer, and says so', async () => {
    vi.useFakeTimers()
    addMeta(SHELL)
    const { win } = framedWindow()
    const loadMain = vi.fn(async () => {})
    const booting = runBoot(win, loadMain, 1_000)
    vi.advanceTimersByTime(1_000)

    expect((await booting).kind).toBe('no-shell')
    expect(loadMain).not.toHaveBeenCalled()
    expect(isRemoteMode()).toBe(false)
    expect(document.getElementById('root')).toHaveTextContent('Lighting7 Cloud did not answer')
  })

  it('takes a meta that is not a URL as no meta', async () => {
    addMeta('not a url')
    const { win, parent } = framedWindow()
    expect((await runBoot(win, async () => {})).kind).toBe('local')
    expect(parent.posted).toEqual([])
  })
})
