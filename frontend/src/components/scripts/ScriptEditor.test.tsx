// @vitest-environment jsdom
import { StrictMode, useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EditorScriptType } from '@/store/scripts'
import {
  fakePlayground,
  mounts,
  probe,
  resetKotlinPlaygroundFake,
} from '@/test/kotlinPlaygroundFake'

/**
 * The editor subsystem's load-bearing conventions, none of which had a test before.
 *
 * What makes the documented cross-type poisoning landmine safe is not the code's structure but
 * four separate facts, each of which a plausible tidy-up would quietly break: the marker line goes
 * first, the wrap→unwrap round-trip is exact rather than lucky, `/api/script-editor` is assigned in
 * exactly one place, and read-only works by *omitting* an attribute. They are pinned here.
 *
 * The widget is faked rather than mocked away, because the behaviours the wrapper reasons about
 * are the widget's own — the fold arithmetic, the initialized-node guard, and above all the
 * silent read-only fallback on a failed `/versions` probe. The fake reproduces those from
 * `kotlin-playground@1.34.0`'s own source (`dist/playground.js`), not from its README.
 */

vi.mock('kotlin-playground', () => ({
  default: (node: HTMLElement, options: Record<string, never>) => fakePlayground(node, options),
}))

const { ScriptEditor } = await import('./ScriptEditor')
const { default: ReactKotlinPlayground } = await import('@/kotlinScript/index.mjs')

/** The one mount the tests below make, once the widget's promise has been through. */
async function onlyMount() {
  await waitFor(() => expect(mounts).toHaveLength(1))
  return mounts[0]
}

beforeEach(() => {
  resetKotlinPlaygroundFake()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('what the widget is handed', () => {
  it('puts the script-type marker first, above the fold', async () => {
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} scriptType="FX_APPLICATION" />)

    expect((await onlyMount()).initialCode).toBe(
      '//@lighting7-script-type=FX_APPLICATION\n//sampleStart\nval x = 1\n//sampleEnd\n',
    )
  })

  it('names the script type in the marker, one spelling per type', async () => {
    const types: EditorScriptType[] = [
      'GENERAL',
      'FX_DEFINITION',
      'FX_APPLICATION',
      'FX_CALC',
      'FX_CALC_STATEFUL',
      'FX_CALC_COMPOSITE',
    ]

    for (const scriptType of types) {
      resetKotlinPlaygroundFake()
      const { unmount } = render(
        <ScriptEditor script={{ name: 'S', script: 'body' }} scriptType={scriptType} />,
      )
      expect((await onlyMount()).initialCode.split('\n')[0]).toBe(
        `//@lighting7-script-type=${scriptType}`,
      )
      unmount()
    }
  })

  // The wrap and the unwrap are written by two different parties — this repo builds the string,
  // the widget takes it apart with its own index arithmetic — so "the body survives" is a claim
  // about their agreement, not about either one alone.
  it.each([
    ['a body with no trailing newline', 'val x = 1'],
    ['a body that ends in a newline', 'val x = 1\n'],
    ['a body with blank lines and indentation', 'fun f() {\n\n    println("hi")\n}\n'],
    ['a body containing a marker lookalike', 'val a = 1\n// //@lighting7-script-type=GENERAL\n'],
    ['an empty body', ''],
  ])('round-trips %s byte for byte', async (_name, body) => {
    render(<ScriptEditor script={{ name: 'S', script: body }} />)

    const { fragment } = await onlyMount()
    expect(fragment!.codemirror.getValue()).toBe(body)

    // And what the widget would send on: the marker line the backend needs, the body, and no
    // trace of the fold markers, which never leave the browser.
    expect(fragment!.getCode()).toBe(`//@lighting7-script-type=GENERAL\n${body}`)
  })

  it('mounts inside `.kotlin-editor`, which the Run-button-hiding CSS keys on', async () => {
    const { container } = render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)

    const wrapper = container.querySelector('.kotlin-editor')
    expect(wrapper).not.toBeNull()
    expect(wrapper!.contains((await onlyMount()).node)).toBe(true)
  })

  it('points every editor at the one server subtree', async () => {
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)

    expect((await onlyMount()).options.server).toBe('/api/script-editor')
  })
})

describe('read-only', () => {
  // The widget tests `data-highlight-only` for *presence*, special-casing only the literal
  // "nocursor". So `highlightOnly="false"` — the natural tidy-up — makes every editor read-only,
  // with no type error and nothing else to notice it.
  it('omits the attribute entirely when editable', async () => {
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)

    const { node } = await onlyMount()
    expect(node.hasAttribute('data-highlight-only')).toBe(false)
  })

  it('sets the attribute when read-only, and never to the string "false"', async () => {
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} readOnly />)

    const { node } = await onlyMount()
    expect(node.getAttribute('data-highlight-only')).toBe('true')
  })

  it('does not report changes from a read-only editor', async () => {
    const onScriptChange = vi.fn()
    render(
      <ScriptEditor script={{ name: 'S', script: 'val x = 1' }} readOnly onScriptChange={onScriptChange} />,
    )

    const { fragment } = await onlyMount()
    act(() => fragment!.codemirror.type(' // typed'))

    expect(onScriptChange).not.toHaveBeenCalled()
  })
})

describe('changes', () => {
  // The widget's own `onChange` is debounced 500 ms with no maxWait and no flush, so every
  // consumer's copy trailed the editor: type-then-Escape closed a sheet with no "Discard
  // changes?", and a Compile inside the window sent the previous text.
  it('reports a keystroke immediately, without waiting on a debounce', async () => {
    const onScriptChange = vi.fn()
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} onScriptChange={onScriptChange} />)

    const { fragment } = await onlyMount()
    act(() => fragment!.codemirror.type('0'))

    expect(onScriptChange).toHaveBeenCalledWith('val x = 10')
  })

  it('writes a changed script through to a live editor', async () => {
    const script = { name: 'S', script: 'edited' }
    const { rerender } = render(<ScriptEditor script={script} />)

    const { fragment } = await onlyMount()
    expect(fragment!.codemirror.getValue()).toBe('edited')

    rerender(<ScriptEditor script={{ name: 'S', script: 'original' }} />)

    expect(fragment!.codemirror.getValue()).toBe('original')
  })

  // The write above is a `setValue`, and CodeMirror reports that as a change. Left unsuppressed it
  // came straight back out as a user edit — which is how a Reset undid itself.
  it('does not report a write-through back out as an edit', async () => {
    const onScriptChange = vi.fn()
    const { rerender } = render(
      <ScriptEditor script={{ name: 'S', script: 'edited' }} onScriptChange={onScriptChange} />,
    )
    await onlyMount()

    rerender(<ScriptEditor script={{ name: 'S', script: 'original' }} onScriptChange={onScriptChange} />)

    expect(onScriptChange).not.toHaveBeenCalled()
  })

  // The widget takes a moment to come up, and the write-through effect only fires when
  // `script.script` moves — so a change landing in that window has no second chance at it.
  it('picks up a script that changed while the widget was still coming up', async () => {
    const { rerender } = render(<ScriptEditor script={{ name: 'S', script: 'V0' }} />)
    rerender(<ScriptEditor script={{ name: 'S', script: 'V1' }} />)

    const { fragment } = await onlyMount()
    expect(fragment!.codemirror.getValue()).toBe('V1')
  })

  it('keeps the caret where it was across a write-through', async () => {
    const { rerender } = render(<ScriptEditor script={{ name: 'S', script: 'edited' }} />)

    const { fragment } = await onlyMount()
    fragment!.codemirror.setCursor({ line: 2, ch: 4 })

    rerender(<ScriptEditor script={{ name: 'S', script: 'original' }} />)

    expect(fragment!.codemirror.getCursor()).toEqual({ line: 2, ch: 4 })
  })

  it('leaves the editor alone when the script comes back around unchanged', async () => {
    function Controlled() {
      const [code, setCode] = useState('val x = 1')
      return <ScriptEditor script={{ name: 'S', script: code }} onScriptChange={setCode} />
    }
    render(<Controlled />)

    const { fragment } = await onlyMount()
    fragment!.codemirror.setCursor({ line: 5, ch: 2 })
    act(() => fragment!.codemirror.type('!'))

    // A round trip out through the caller's state and back must not be mistaken for an external
    // revert — a re-`setValue` on every keystroke would fight the operator's typing.
    expect(fragment!.codemirror.getValue()).toBe('val x = 1!')
    expect(fragment!.codemirror.getCursor()).toEqual({ line: 5, ch: 2 })
  })
})

describe('a language service that is not there', () => {
  it('says so, instead of leaving a normal-looking editor that refuses every keystroke', async () => {
    probe.versionsAvailable = false
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Language service unreachable')
  })

  // The probe is only the mount-time entry to the same degradation; the widget's error channel is
  // the mid-session one, when the desk restarts under a live editor.
  it('says so too when a live editor reports an error', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)

    const { options } = await onlyMount()
    act(() => (options.onError as (errors: unknown) => void)(['boom']))

    expect(await screen.findByRole('alert')).toHaveTextContent('Language service unreachable')
  })

  it('stays quiet when the probe succeeded', async () => {
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)
    await onlyMount()

    expect(screen.queryByRole('alert')).toBeNull()
  })

  // The widget nulls its cached version list on failure, so a fresh mount really does refetch.
  it('remounts the editor on Retry, so the probe is made again', async () => {
    probe.versionsAvailable = false
    render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)
    await screen.findByRole('alert')

    probe.versionsAvailable = true
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(mounts).toHaveLength(2))
    expect(mounts[1].fragment).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('lifecycle', () => {
  it('destroys the widget on unmount', async () => {
    const { unmount } = render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} />)
    const { executable } = await onlyMount()

    unmount()

    expect(executable.destroyed).toBe(true)
  })

  it('destroys the previous widget when the editor is remounted', async () => {
    const { rerender } = render(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} id={1} />)
    await onlyMount()

    rerender(<ScriptEditor script={{ name: 'S', script: 'val x = 1' }} id={2} />)

    await waitFor(() => expect(mounts).toHaveLength(2))
    expect(mounts[0].executable.destroyed).toBe(true)
    expect(mounts[1].executable.destroyed).toBe(false)
  })

  // StrictMode rehearses an unmount and a remount on the same class instance in development, so
  // the wrapper's "have I been unmounted?" flag has to be a statement about now rather than a
  // latch — latched, dev gets an editor that mounts a widget and then refuses to talk to it.
  it('comes up live under StrictMode, which mounts it twice', async () => {
    const onScriptChange = vi.fn()
    render(
      <StrictMode>
        <ScriptEditor script={{ name: 'S', script: 'val x = 1' }} onScriptChange={onScriptChange} />
      </StrictMode>,
    )

    const { fragment, executable } = await onlyMount()
    expect(executable.destroyed).toBe(false)

    act(() => fragment!.codemirror.type('!'))
    expect(onScriptChange).toHaveBeenCalledWith('val x = 1!')
  })

  // The wrapper used to stash the instance on `window.playgroundInstance` and overwrite the
  // caller's `getInstance` doing it, which is why nothing could reach the live editor.
  it('hands the live editor to the caller rather than to a global', async () => {
    const getInstance = vi.fn()
    render(<ReactKotlinPlayground value="//sampleStart\nbody\n//sampleEnd\n" getInstance={getInstance} />)

    await waitFor(() => expect(getInstance).toHaveBeenCalledTimes(1))
    expect(getInstance).toHaveBeenCalledWith(mounts[0].fragment)
    expect((window as unknown as { playgroundInstance?: unknown }).playgroundInstance).toBeUndefined()
  })
})

describe('the one-server-per-page constraint', () => {
  /**
   * The widget's base URL is a module-level global that every `playground()` call overwrites, so
   * two editors of different script types on one page would poison each other's completions. The
   * design answer is that there is only ever one URL and the type travels in the source instead —
   * a second assignment anywhere would silently undo that.
   */
  it('assigns the editor server exactly once in the whole tree', () => {
    // `?raw` through Vite rather than `node:fs`, so the sweep runs against the same module graph
    // the app is built from and needs no Node types in the browser-targeted tsconfig.
    const sources = import.meta.glob('/src/**/*.{mjs,js,ts,tsx}', {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>

    const hits: string[] = []
    for (const [path, source] of Object.entries(sources)) {
      // Assignments only. Prose mentioning the route is fine — a second *assignment* is not.
      for (const _ of source.match(/=\s*["']\/api\/script-editor["']/g) ?? []) hits.push(path)
    }

    expect(hits).toEqual(['/src/kotlinScript/component.mjs'])
  })
})
