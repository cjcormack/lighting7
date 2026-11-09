import type { RgbColor } from 'react-colorful'

const toHex = ({ r, g, b }: RgbColor) => `#${[r, g, b].map((n) => n.toString(16).padStart(2, '0')).join('')}`
const fromHex = (hex: string): RgbColor => ({
  r: parseInt(hex.slice(1, 3), 16),
  g: parseInt(hex.slice(3, 5), 16),
  b: parseInt(hex.slice(5, 7), 16),
})

/**
 * react-colorful's `RgbColorPicker` as a text field: jsdom lays the real one out at zero, so a test
 * moves it with `fireEvent.change(picker, { target: { value: '#rrggbb' } })`. Use as
 * `vi.mock('react-colorful', async () => (await import('@/test/colourPickerMock')).colourPickerMock())`.
 */
export function colourPickerMock() {
  return {
    RgbColorPicker: ({ color, onChange }: { color: RgbColor; onChange: (color: RgbColor) => void }) => (
      <input aria-label="Colour picker" value={toHex(color)} onChange={(e) => onChange(fromHex(e.target.value))} />
    ),
  }
}
