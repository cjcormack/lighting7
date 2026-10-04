/**
 * The gel library (fixture optics plan D7): the desk's, served whole at `GET /gels` from
 * `src/main/resources/gels.json` and read once (`useGelIndex`). It lived here as a constant until
 * the backend needed it too — to turn a fitted gel into a colour for the template resolver's wheel
 * snap and for `describe_rig` — so there is one library, and the client only indexes it.
 */

/** A maker, as the library names it (`Lee`, `Rosco`). The brand chips are the library's own. */
export type GelBrand = string

export interface Gel {
  code: string
  name: string
  /** `#rrggbb`: an approximate swatch, never a measured transmission. */
  color: string
  brand: GelBrand
  /** The swatch is the plan's approximation, not the maker's swatch book (D15). */
  estimate?: boolean
}

export interface GelIndex {
  all: readonly Gel[]
  byCode: ReadonlyMap<string, Gel>
  /** The brands in library order, for the picker's chips. */
  brands: readonly GelBrand[]
}

export const EMPTY_GELS: GelIndex = { all: [], byCode: new Map(), brands: [] }

export function indexGels(list: readonly Gel[] | undefined): GelIndex {
  if (!list || list.length === 0) return EMPTY_GELS
  const byCode = new Map<string, Gel>()
  const brands: GelBrand[] = []
  for (const g of list) {
    byCode.set(g.code, g)
    if (!brands.includes(g.brand)) brands.push(g.brand)
  }
  return { all: list, byCode, brands }
}

/** The library's gel for `code`, or null — for no code, and for one the library does not hold. */
export function findGel(gels: GelIndex, code: string | null | undefined): Gel | null {
  if (!code) return null
  return gels.byCode.get(code) ?? null
}

export function searchGels(gels: GelIndex, query: string, brand: GelBrand | 'All'): Gel[] {
  const q = query.trim().toLowerCase()
  return gels.all.filter((g) => {
    if (brand !== 'All' && g.brand !== brand) return false
    if (!q) return true
    return g.code.toLowerCase().includes(q) || g.name.toLowerCase().includes(q)
  })
}
