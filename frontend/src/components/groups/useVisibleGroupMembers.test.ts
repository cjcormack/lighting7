// @vitest-environment jsdom
import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { GroupMember } from '@/api/groupsApi'

vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({
    data: [
      { key: 'par-1', infrastructure: false },
      { key: 'hazer-power', infrastructure: true },
      { key: 'bar-1', infrastructure: true },
    ],
  }),
}))

const { useVisibleGroupMembers } = await import('./useVisibleGroupMembers')

function member(fixtureKey: string, tags: string[] = []): GroupMember {
  return { fixtureKey, fixtureName: fixtureKey, index: 0, normalizedPosition: 0, panOffset: 0, tiltOffset: 0, symmetricInvert: false, tags }
}

describe('useVisibleGroupMembers', () => {
  it('drops a member on an infrastructure head — its cells included — and keeps the rest', () => {
    const members = [
      member('par-1'),
      member('hazer-power'),
      member('bar-1.element-0', ['element']),
      member('bar-1.element-1', ['element']),
    ]
    const { result } = renderHook(() => useVisibleGroupMembers(members))
    expect(result.current?.map((m) => m.fixtureKey)).toEqual(['par-1'])
  })

  it('passes undefined through, so a header falls back to the summary count while loading', () => {
    expect(renderHook(() => useVisibleGroupMembers(undefined)).result.current).toBeUndefined()
  })
})
