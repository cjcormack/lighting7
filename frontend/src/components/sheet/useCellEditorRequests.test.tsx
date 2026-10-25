// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCellEditorRequests } from './useCellEditorRequests'

/** Set's open without its toggle: it opens when the first cell's row is on screen, else scrolls and says so. */
afterEach(() => {
  cleanup()
  document.body.innerHTML = ''
})

function setUp() {
  const onScrollTo = vi.fn()
  // `keyboardOpen` is a one-shot the hook clears on the next commit, so every render's is kept.
  const opens: unknown[] = []
  const hook = renderHook(() => {
    const requests = useCellEditorRequests<'dimmer'>({
      firstEditableCell: () => ({ rowId: 'fixture:a', col: 'dimmer' }),
      onScrollTo,
    })
    if (requests.keyboardOpen != null) opens.push(requests.keyboardOpen)
    return requests
  })
  return { hook, onScrollTo, opens }
}

describe('openCellEditorAtButton', () => {
  it('scrolls the first cell’s row into view and answers false when it is not mounted', () => {
    const { hook, onScrollTo } = setUp()
    let opened = true
    act(() => {
      opened = hook.result.current.openCellEditorAtButton()
    })
    expect(opened).toBe(false)
    expect(onScrollTo).toHaveBeenCalledWith('fixture:a')
  })

  it('opens at the button and answers true once the row is on screen', () => {
    const row = document.createElement('div')
    row.dataset.rowId = 'fixture:a'
    document.body.appendChild(row)
    const { hook, onScrollTo, opens } = setUp()
    let opened = false
    act(() => {
      opened = hook.result.current.openCellEditorAtButton()
    })
    expect(opened).toBe(true)
    expect(onScrollTo).not.toHaveBeenCalled()
    expect(opens[0]).toMatchObject({ rowId: 'fixture:a', col: 'dimmer', atButton: true })
  })
})
