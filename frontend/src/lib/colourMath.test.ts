import { describe, it, expect } from "vitest"
import {
  PERCEPTUAL_GAMMA,
  perceptualBrightness,
  colourMagnitude,
  computeCombinedCss,
  computeNormalizedHue,
  computeNormalizedHueCss,
  computeAppearanceCss,
  parseCssRgb,
  dimCssColour,
} from "./colourMath"

describe("perceptualBrightness", () => {
  it("is anchored at both ends (0 -> floor, 1 -> 1)", () => {
    expect(perceptualBrightness(0)).toBe(0)
    expect(perceptualBrightness(1)).toBe(1)
    expect(perceptualBrightness(0, 0.15)).toBe(0.15)
    expect(perceptualBrightness(1, 0.15)).toBe(1)
  })

  it("boosts low levels well above the linear value", () => {
    // The whole point: a linear 5% reads near-black; perceptually it's ~26%.
    expect(perceptualBrightness(0.05)).toBeCloseTo(0.2562, 3)
    // r:20 (magnitude 20/255) lands around 31% rather than 8%.
    expect(perceptualBrightness(20 / 255)).toBeCloseTo(0.3145, 3)
    // Mid level is lifted too.
    expect(perceptualBrightness(0.5)).toBeCloseTo(0.7297, 3)
  })

  it("matches the gamma definition floor + (1-floor) * level^(1/gamma)", () => {
    const level = 0.3
    const floor = 0.15
    const expected = floor + (1 - floor) * Math.pow(level, 1 / PERCEPTUAL_GAMMA)
    expect(perceptualBrightness(level, floor)).toBeCloseTo(expected, 12)
  })

  it("is monotonically increasing", () => {
    let prev = -1
    for (let i = 0; i <= 20; i++) {
      const v = perceptualBrightness(i / 20)
      expect(v).toBeGreaterThan(prev)
      prev = v
    }
  })

  it("clamps out-of-range levels", () => {
    expect(perceptualBrightness(-5)).toBe(0)
    expect(perceptualBrightness(-5, 0.2)).toBe(0.2)
    expect(perceptualBrightness(5)).toBe(1)
  })

  it("applies the floor proportionally to the remaining range", () => {
    // floor + (1-floor)*level^(1/gamma): the floor shifts, the curve fills the rest.
    expect(perceptualBrightness(0.5, 0.2)).toBeCloseTo(0.2 + 0.8 * 0.7297, 3)
  })
})

describe("colourMagnitude", () => {
  it("is the brightest emitter as a 0..1 fraction", () => {
    expect(colourMagnitude(20, 2, 0)).toBeCloseTo(20 / 255, 6)
    expect(colourMagnitude(255, 0, 0)).toBe(1)
    expect(colourMagnitude(0, 0, 0)).toBe(0)
  })

  it("counts white, amber and UV as emitters", () => {
    expect(colourMagnitude(0, 0, 0, 128)).toBeCloseTo(128 / 255, 6)
    expect(colourMagnitude(0, 0, 0, 0, 200)).toBeCloseTo(200 / 255, 6)
    expect(colourMagnitude(0, 0, 0, 0, 0, 50)).toBeCloseTo(50 / 255, 6)
  })

  it("clamps above 255", () => {
    expect(colourMagnitude(300, 0, 0)).toBe(1)
  })
})

describe("computeCombinedCss (raw hue-at-its-own-level, picker seed)", () => {
  it("passes plain RGB straight through", () => {
    expect(computeCombinedCss(255, 0, 0, undefined, undefined, undefined)).toBe("rgb(255, 0, 0)")
    expect(computeCombinedCss(20, 2, 0, undefined, undefined, undefined)).toBe("rgb(20, 2, 0)")
  })

  it("folds white toward 255", () => {
    expect(computeCombinedCss(0, 0, 0, 255, undefined, undefined)).toBe("rgb(255, 255, 255)")
  })

  it("folds amber into red/green (warm), leaving blue", () => {
    expect(computeCombinedCss(0, 0, 0, undefined, 255, undefined)).toBe("rgb(255, 191, 0)")
  })

  it("keeps a dim colour dim — this is the problem the appearance path fixes", () => {
    expect(computeCombinedCss(20, 2, 0, undefined, undefined, undefined)).toBe("rgb(20, 2, 0)")
  })
})

describe("computeNormalizedHueCss (pure hue at full brightness)", () => {
  it("scales the brightest emitter up to 255, preserving hue ratios", () => {
    // Dim orange becomes full-brightness orange (same r:g ratio).
    expect(computeNormalizedHueCss(20, 2, 0)).toBe("rgb(255, 26, 0)")
  })

  it("is level-invariant: same hue, different magnitude -> same result", () => {
    expect(computeNormalizedHueCss(20, 2, 0)).toBe(computeNormalizedHueCss(200, 20, 0))
  })

  it("leaves an already-full colour unchanged", () => {
    expect(computeNormalizedHueCss(255, 0, 0)).toBe("rgb(255, 0, 0)")
  })

  it("returns black when there is no colour", () => {
    expect(computeNormalizedHueCss(0, 0, 0)).toBe("rgb(0, 0, 0)")
  })

  it("normalises a dim white/amber/UV-only fixture to full", () => {
    expect(computeNormalizedHueCss(0, 0, 0, 20)).toBe("rgb(255, 255, 255)")
    expect(computeNormalizedHueCss(0, 0, 0, 0, 20)).toBe("rgb(255, 191, 0)")
  })

  it("preserves hue when white is mixed with RGB (folds before normalising)", () => {
    // red=100 + white=100 is a desaturated warm red, NOT neutral white:
    // fold -> rgb(161,100,100), then scale the brightest channel to 255.
    expect(computeNormalizedHueCss(100, 0, 0, 100)).toBe("rgb(255, 158, 158)")
  })
})

describe("computeNormalizedHue (numeric channels)", () => {
  it("returns the same channels the CSS helper formats", () => {
    expect(computeNormalizedHue(20, 2, 0)).toEqual({ r: 255, g: 26, b: 0 })
    expect(computeNormalizedHueCss(20, 2, 0)).toBe("rgb(255, 26, 0)")
  })

  it("returns zero channels for no colour", () => {
    expect(computeNormalizedHue(0, 0, 0)).toEqual({ r: 0, g: 0, b: 0 })
  })
})

describe("computeAppearanceCss (hue baked at a display brightness)", () => {
  it("at brightness 1 equals the normalised hue", () => {
    expect(computeAppearanceCss(20, 2, 0, undefined, undefined, undefined, 1)).toBe(
      computeNormalizedHueCss(20, 2, 0),
    )
  })

  it("renders a dim colour far brighter than its raw RGB", () => {
    // r:20 at its perceptual level: full-orange (255,26,0) * ~0.3145.
    const b = perceptualBrightness(colourMagnitude(20, 2, 0))
    expect(computeAppearanceCss(20, 2, 0, undefined, undefined, undefined, b)).toBe("rgb(80, 8, 0)")
    // ...vs the near-black raw value it replaces.
    expect(computeCombinedCss(20, 2, 0, undefined, undefined, undefined)).toBe("rgb(20, 2, 0)")
  })

  it("treats a colour-only fixture the same as a dimmer at the same level", () => {
    // Dimmerless r:20 (magnitude 0.078, dimmerFactor 1) ...
    const colourOnlyLevel = 1 * colourMagnitude(20, 2, 0)
    const colourOnly = computeAppearanceCss(
      20, 2, 0, undefined, undefined, undefined,
      perceptualBrightness(colourOnlyLevel),
    )
    // ... vs the same hue at full RGB behind a dimmer at 20 (dimmerFactor 0.078).
    const dimmerFactor = 20 / 255
    const dimmed = computeAppearanceCss(
      255, 26, 0, undefined, undefined, undefined,
      perceptualBrightness(dimmerFactor * colourMagnitude(255, 26, 0)),
    )
    expect(colourOnly).toBe(dimmed)
  })

  it("is black at zero brightness or with no colour", () => {
    expect(computeAppearanceCss(255, 0, 0, undefined, undefined, undefined, 0)).toBe("rgb(0, 0, 0)")
    expect(computeAppearanceCss(0, 0, 0, undefined, undefined, undefined, 1)).toBe("rgb(0, 0, 0)")
  })

  it("clamps brightness above 1", () => {
    expect(computeAppearanceCss(20, 2, 0, undefined, undefined, undefined, 5)).toBe(
      computeAppearanceCss(20, 2, 0, undefined, undefined, undefined, 1),
    )
  })
})

describe("parseCssRgb", () => {
  it("reads both forms a FixtureAppearance colour can take", () => {
    // Long hex and shorthand come from gels and wheel previews; rgb() comes from
    // computeNormalizedHueCss. Any caller that modifies the colour has to handle both.
    expect(parseCssRgb("#ff8000")).toEqual({ r: 255, g: 128, b: 0 })
    expect(parseCssRgb("#f80")).toEqual({ r: 255, g: 136, b: 0 })
    expect(parseCssRgb("rgb(1, 2, 3)")).toEqual({ r: 1, g: 2, b: 3 })
    expect(parseCssRgb("rgb(  1 ,2,  3 )")).toEqual({ r: 1, g: 2, b: 3 })
  })

  it("returns null rather than a wrong colour for anything else", () => {
    expect(parseCssRgb("#12345")).toBeNull()
    expect(parseCssRgb("#zzzzzz")).toBeNull()
    expect(parseCssRgb("hotpink")).toBeNull()
    expect(parseCssRgb("")).toBeNull()
  })
})

describe("dimCssColour", () => {
  it("scales either colour form to a brightness", () => {
    expect(dimCssColour("#ffffff", 0.5)).toBe("rgb(128, 128, 128)")
    expect(dimCssColour("rgb(200, 100, 0)", 0.5)).toBe("rgb(100, 50, 0)")
  })

  it("agrees with computeAppearanceCss on an already-normalised hue", () => {
    // The two have to match: the 3D and DOM surfaces bake brightness from channels via
    // computeAppearanceCss, the SVG plot bakes it from the hue string via this.
    const hue = computeNormalizedHueCss(255, 26, 0)
    expect(dimCssColour(hue, 0.4)).toBe(
      computeAppearanceCss(255, 26, 0, undefined, undefined, undefined, 0.4),
    )
  })

  it("is black at zero and clamps above one", () => {
    expect(dimCssColour("#ff0000", 0)).toBe("rgb(0, 0, 0)")
    expect(dimCssColour("#ff0000", 5)).toBe("rgb(255, 0, 0)")
  })

  it("passes an unrecognised colour through untouched", () => {
    // Better a fixture drawn in the wrong brightness than one drawn with an invalid fill.
    expect(dimCssColour("var(--color-foreground)", 0.5)).toBe("var(--color-foreground)")
  })
})
