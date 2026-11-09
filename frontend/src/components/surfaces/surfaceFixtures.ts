import catalogueJson from '../../../../src/test/resources/surfaces/catalogue.fixture.json'
import mapJson from '../../../../src/test/resources/surfaces/map.fixture.json'
import type { CatalogueEntry, ControlSurfaceType, SurfaceMapFrame } from '@/store/surfaces'

/**
 * The desk's own answers, for this side's tests (surface-setup plan P5): the function catalogue as
 * `GET /surface-functions` serves it, and a map frame for a sample of bindings, both generated and
 * pinned by lighting7's `SurfaceCatalogueTest`. A test that draws from these draws what the desk
 * sends, so a change to the wire fails here as well as there.
 */
export const CATALOGUE = catalogueJson as unknown as CatalogueEntry[]
export const MAP_FRAME = mapJson as unknown as SurfaceMapFrame

/** The X-Touch Compact's controls the map fixture names, laid out as two small regions. */
export const FIXTURE_PROFILE: ControlSurfaceType = {
  typeKey: 'x-touch-compact-standard',
  vendor: 'Behringer',
  product: 'X-Touch Compact',
  portPattern: null,
  className: 'XTouchCompactStandard',
  banks: [
    { id: 'layer-a', name: 'A' },
    { id: 'layer-b', name: 'B' },
  ],
  strips: [{ id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: 'enc-1', flash: 'btn-1' }],
  layout: {
    regions: [
      {
        name: 'Strips',
        columns: 1,
        cells: [
          { controlId: 'enc-1', col: 0, row: 0 },
          { controlId: 'btn-1', col: 0, row: 1 },
          { controlId: 'fader-1', col: 0, row: 2 },
          { controlId: 'btn-25', col: 0, row: 3 },
        ],
      },
      {
        name: 'Right',
        columns: 2,
        cells: [
          { controlId: 'enc-9', col: 0, row: 0 },
          { controlId: 'btn-3', col: 0, row: 1 },
          { controlId: 'btn-4', col: 1, row: 1 },
          { controlId: 'btn-5', col: 0, row: 2 },
          { controlId: 'fader-9', col: 0, row: 3 },
          { controlId: 'btn-33', col: 1, row: 3 },
        ],
      },
    ],
  },
  controls: [
    { type: 'fader', controlId: 'fader-1', label: 'Fader 1', cc: 1, channel: 0, hasMotor: true, motorCc: 1, touchNote: null, touchCc: 101, resolution: 'SEVEN_BIT' },
    { type: 'fader', controlId: 'fader-9', label: 'Fader 9', cc: 9, channel: 0, hasMotor: true, motorCc: 9, touchNote: null, touchCc: 109, resolution: 'SEVEN_BIT' },
    { type: 'encoder', controlId: 'enc-1', label: 'Encoder 1', cc: 10, channel: 0, ringCc: 26, ringStyle: 'SINGLE_DOT', pushNote: 0, pushLed: 'ON_OFF' },
    { type: 'encoder', controlId: 'enc-9', label: 'Encoder 9', cc: 18, channel: 0, ringCc: 34, ringStyle: 'SINGLE_DOT', pushNote: 8, pushLed: 'ON_OFF' },
    { type: 'button', controlId: 'btn-1', label: 'Button 1', note: 16, channel: 0, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-3', label: 'Button 3', note: 18, channel: 0, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-4', label: 'Button 4', note: 19, channel: 0, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-5', label: 'Button 5', note: 20, channel: 0, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-25', label: 'Button 25', note: 40, channel: 0, ledFeedback: 'ON_OFF' },
    { type: 'button', controlId: 'btn-33', label: 'Button 33', note: 48, channel: 0, ledFeedback: 'ON_OFF' },
  ],
}
