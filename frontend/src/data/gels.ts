export type GelBrand = 'Lee' | 'Rosco'

export interface Gel {
  code: string
  name: string
  color: string
  brand: GelBrand
}

const LEE: Omit<Gel, 'brand'>[] = [
  { code: 'L004', name: 'Medium Bastard Amber', color: '#fbb27a' },
  { code: 'L007', name: 'Pale Yellow', color: '#fde88a' },
  { code: 'L022', name: 'Dark Amber', color: '#e88830' },
  { code: 'L079', name: 'Just Blue', color: '#1d3a8a' },
  { code: 'L106', name: 'Primary Red', color: '#d12028' },
  { code: 'L120', name: 'Deep Blue', color: '#1c2c8e' },
  { code: 'L132', name: 'Medium Blue', color: '#3060c8' },
  { code: 'L139', name: 'Primary Green', color: '#1b8c3a' },
  { code: 'L147', name: 'Apricot', color: '#f5b07b' },
  { code: 'L152', name: 'Pale Gold', color: '#f3d27a' },
  { code: 'L154', name: 'Pale Rose', color: '#f3b9c2' },
  { code: 'L162', name: 'Bastard Amber', color: '#fbcfa0' },
  { code: 'L181', name: 'Congo Blue', color: '#161a8e' },
  { code: 'L195', name: 'Zenith Blue', color: '#1f3aa0' },
  { code: 'L201', name: 'Full CT Blue', color: '#9bbede' },
  { code: 'L202', name: 'Half CT Blue', color: '#bfd7ec' },
  { code: 'L203', name: 'Quarter CT Blue', color: '#dbe7f2' },
  { code: 'L204', name: 'Full CT Orange', color: '#f9c089' },
  { code: 'L205', name: 'Half CT Orange', color: '#fbd7ad' },
  { code: 'L237', name: 'CID 1/2', color: '#e2eef9' },
]

const ROSCO: Omit<Gel, 'brand'>[] = [
  { code: 'R02', name: 'Bastard Amber', color: '#fbcc9a' },
  { code: 'R08', name: 'Pale Gold', color: '#f0c970' },
  { code: 'R26', name: 'Light Red', color: '#ee5b5e' },
  { code: 'R33', name: 'No Color Pink', color: '#fde0e6' },
  { code: 'R51', name: 'Surprise Pink', color: '#f5a8c2' },
  { code: 'R59', name: 'Indigo', color: '#3a2778' },
  { code: 'R64', name: 'Light Steel Blue', color: '#b0d2ec' },
  { code: 'R68', name: 'Sky Blue', color: '#65a8db' },
  { code: 'R80', name: 'Primary Blue', color: '#1c3aa0' },
  { code: 'R83', name: 'Medium Blue', color: '#2058b8' },
  { code: 'R90', name: 'Dark Yellow Green', color: '#5b8a2a' },
  { code: 'R91', name: 'Primary Green', color: '#1f8a3c' },
]

export const GEL_BRANDS: GelBrand[] = ['Lee', 'Rosco']

export const GELS: Gel[] = [
  ...LEE.map((g): Gel => ({ ...g, brand: 'Lee' })),
  ...ROSCO.map((g): Gel => ({ ...g, brand: 'Rosco' })),
]

export function findGel(code: string | null | undefined): Gel | null {
  if (!code) return null
  return GELS.find((g) => g.code === code) ?? null
}

export function searchGels(query: string, brand: GelBrand | 'All'): Gel[] {
  const q = query.trim().toLowerCase()
  return GELS.filter((g) => {
    if (brand !== 'All' && g.brand !== brand) return false
    if (!q) return true
    return g.code.toLowerCase().includes(q) || g.name.toLowerCase().includes(q)
  })
}
