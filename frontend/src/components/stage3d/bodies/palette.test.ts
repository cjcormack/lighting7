import { describe, expect, it } from 'vitest'
import { FIXTURE_COLOURS } from '../../../lib/fixtureColours'
import { Color } from 'three'
import {
  HOUSING_ACTIVE_COLOR,
  HOUSING_ACTIVE_FILL,
  HOUSING_FILL,
  TAG_HOUSING_ALBEDO,
  TAG_HOUSING_COLORS,
} from './palette'

// index.css as text. Vitest serves a stylesheet import as empty, `?raw` included, and the app's
// tsconfig has no node types, so `fs` is reached through an untyped dynamic import.
const fs = (await import(/* @vite-ignore */ `node:${'fs'}`)) as { readFileSync: (path: URL, encoding: 'utf8') => string }
const css = fs.readFileSync(new URL('../../../index.css', import.meta.url), 'utf8')

describe('the housings’ colours', () => {
  it('are index.css’s dark --tag-<key>, the canvas being dark in either theme', () => {
    const dark = /\.dark\s*\{([\s\S]*?)\n\}/.exec(css)?.[1] ?? ''
    expect(dark).not.toBe('')
    for (const colour of FIXTURE_COLOURS) {
      const value = new RegExp(`--tag-${colour}:\\s*(#[0-9a-f]{6});`).exec(dark)?.[1]
      expect(value, colour).toBe(TAG_HOUSING_COLORS[colour])
    }
  })

  it('are each dimmer than a selected housing, which rides the same fill', () => {
    const luminance = (c: Color) => 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b
    const selected = luminance(new Color(HOUSING_ACTIVE_COLOR).multiplyScalar(HOUSING_ACTIVE_FILL / HOUSING_FILL))
    for (const colour of FIXTURE_COLOURS) {
      const tint = luminance(new Color(TAG_HOUSING_COLORS[colour]).multiplyScalar(TAG_HOUSING_ALBEDO))
      expect(tint, colour).toBeLessThan(selected)
    }
  })
})
