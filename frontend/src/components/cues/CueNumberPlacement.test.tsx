// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'

afterEach(cleanup)
import type { CueNumberPlacement } from '@/api/cueStacksApi'
import { outOfOrderOf, outOfOrderWhere, useCueNumberPlacement } from './CueNumberPlacement'

const outOfOrder = { status: 409, data: { error: 'out of order', code: 'CUE_NUMBER_OUT_OF_ORDER', number: '4.5', previous: null, next: '3' } }

function Harness({ send }: { send: (placement: CueNumberPlacement) => Promise<unknown> }) {
  const { writeNumber, dialog } = useCueNumberPlacement()
  return (
    <>
      <button onClick={() => writeNumber(send)}>write</button>
      {dialog}
    </>
  )
}

/** A send that refuses the first, asking, write as out of order and lands the rest. */
function asking() {
  return vi.fn((placement: CueNumberPlacement) => (placement === 'ASK' ? Promise.reject(outOfOrder) : Promise.resolve()))
}

async function write(send: ReturnType<typeof asking>) {
  render(<Harness send={send} />)
  await act(async () => {
    fireEvent.click(screen.getByText('write'))
  })
}

describe('useCueNumberPlacement', () => {
  it('writes the number where it is when its host is gone before the desk answers', async () => {
    let refuse: (e: unknown) => void = () => {}
    const send = vi.fn((placement: CueNumberPlacement) =>
      placement === 'ASK' ? new Promise((_, reject) => (refuse = reject)) : Promise.resolve(),
    )
    const { unmount } = render(<Harness send={send} />)
    fireEvent.click(screen.getByText('write'))
    unmount()
    await act(async () => {
      refuse(outOfOrder)
    })
    expect(send).toHaveBeenLastCalledWith('HERE')
  })

  it('reads the desk’s out-of-order answer and nothing else', () => {
    expect(outOfOrderOf(outOfOrder)?.number).toBe('4.5')
    expect(outOfOrderOf({ status: 409, data: { code: 'CUE_NUMBER_TAKEN' } })).toBeNull()
    expect(outOfOrderOf(undefined)).toBeNull()
    expect(outOfOrderWhere({ previous: '1', next: '3' })).toBe('after Q1 and before Q3')
    expect(outOfOrderWhere({ previous: null, next: '3' })).toBe('before Q3')
  })

  it('writes in order without asking', async () => {
    const send = vi.fn(() => Promise.resolve())
    await write(send)
    expect(send).toHaveBeenCalledExactlyOnceWith('ASK')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('asks when the number lands out of order, and Move sends it to its place', async () => {
    const send = asking()
    await write(send)
    expect(screen.getByRole('alertdialog')).toHaveTextContent('Q4.5 is out of order here')
    expect(screen.getByRole('alertdialog')).toHaveTextContent('before Q3')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Move to its place' }))
    })
    expect(send).toHaveBeenLastCalledWith('BY_NUMBER')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('Keep it here sends it where it is, and Cancel sends nothing more', async () => {
    const send = asking()
    await write(send)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Keep it here' }))
    })
    expect(send).toHaveBeenLastCalledWith('HERE')

    send.mockClear()
    await act(async () => {
      fireEvent.click(screen.getByText('write'))
    })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    })
    expect(send).toHaveBeenCalledExactlyOnceWith('ASK')
  })
})
