/**
 * The images painted on scene cloths (scrim plan §3.3, §3.4): a content-addressed store per project
 * on the desk, each image named by the SHA-256 of its bytes. A drape's or a flat's `params.paint`
 * names up to two of them, `{front?, back?}` — `front` the downstage face. Only PNG and JPEG are
 * taken (P3).
 *
 * The bytes are served at `scene-images/{hash}`, and per-machine copies of them at
 * `?variant=display` (2048 px — the sheet's thumbnail and the cloth's texture), `detail` (4096 px, a
 * cloth with *Full detail*) and `mask` (the alpha at 256 px). Nothing here resolves an image: the
 * desk owns the store, and a hash this machine does not hold is simply missing from the list.
 */

export type SceneImageMediaType = 'image/png' | 'image/jpeg'

/** What one stored image is — the upload's answer and a row of the list. */
export interface SceneImageInfo {
  hash: string
  width: number
  height: number
  /** True when at least one pixel is not fully opaque; such pixels cut holes (D5). */
  hasAlpha: boolean
  mediaType: SceneImageMediaType
}

export type SceneImageVariant = 'display' | 'detail' | 'mask'

/** The two faces a cloth can be painted on. */
export type PaintSide = 'front' | 'back'

export interface ScenePaint {
  front?: string
  back?: string
}

/** The media types the desk takes, and the file picker's `accept`. */
export const SCENE_IMAGE_TYPES: readonly SceneImageMediaType[] = ['image/png', 'image/jpeg']

/** The desk's upload cap (25 MB) and largest side (8192 px), said before a file is sent. */
export const SCENE_IMAGE_MAX_BYTES = 25 * 1024 * 1024
export const SCENE_IMAGE_MAX_SIDE_PX = 8192

const HASH = /^[0-9a-f]{64}$/

/** An image's URL on the desk, or a derived copy of it. */
export function sceneImageUrl(projectId: number, hash: string, variant?: SceneImageVariant): string {
  const base = `/api/rest/projects/${projectId}/scene-images/${hash}`
  return variant ? `${base}?variant=${variant}` : base
}

/** A params document's paint, keeping only well-formed hashes. */
export function paintOf(params: Record<string, unknown>): ScenePaint {
  const raw = params.paint
  if (raw == null || typeof raw !== 'object') return {}
  const p = raw as Record<string, unknown>
  const side = (v: unknown) => (typeof v === 'string' && HASH.test(v) ? v : undefined)
  const out: ScenePaint = {}
  const front = side(p.front)
  const back = side(p.back)
  if (front) out.front = front
  if (back) out.back = back
  return out
}

/** [paint] with one side set or taken off; null when nothing is left, which the desk stores as absent. */
export function withPaintSide(paint: ScenePaint, side: PaintSide, hash: string | null): ScenePaint | null {
  const next: ScenePaint = { ...paint }
  if (hash) next[side] = hash
  else delete next[side]
  return next.front || next.back ? next : null
}

/** How far an image's aspect is from its cloth's before the sheet warns (§4). */
export const ASPECT_TOLERANCE = 0.02

/**
 * An image against the face it is stretched over: the two aspects, whether they are within
 * [ASPECT_TOLERANCE] of each other, and the height that would make them match. Null when the face
 * has no size to compare.
 */
export function aspectFit(
  image: Pick<SceneImageInfo, 'width' | 'height'>,
  widthM: number | null,
  heightM: number | null,
): { imageAspect: number; clothAspect: number; matches: boolean; heightForImage: number } | null {
  if (widthM == null || heightM == null || widthM <= 0 || heightM <= 0) return null
  if (image.width <= 0 || image.height <= 0) return null
  const imageAspect = image.width / image.height
  const clothAspect = widthM / heightM
  return {
    imageAspect,
    clothAspect,
    matches: Math.abs(clothAspect / imageAspect - 1) <= ASPECT_TOLERANCE,
    heightForImage: Math.round((widthM / imageAspect) * 1000) / 1000,
  }
}

/**
 * An aspect as a short ratio: `2 : 1`, `16 : 9` — the smallest whole numbers within 0.1 % of it, up
 * to 16 on the right — else two decimals against 1 (`2.37 : 1`).
 */
export function ratioLabel(aspect: number): string {
  for (let d = 1; d <= 16; d++) {
    const n = Math.round(aspect * d)
    if (n > 0 && Math.abs(n / d / aspect - 1) < 0.001) return `${n} : ${d}`
  }
  return `${aspect.toFixed(2)} : 1`
}

/** A file's media type as the desk wants it, or null when it is neither a PNG nor a JPEG. */
export function sceneImageTypeOf(file: Pick<File, 'type' | 'name'>): SceneImageMediaType | null {
  const type = file.type.toLowerCase()
  if (type === 'image/png') return 'image/png'
  if (type === 'image/jpeg' || type === 'image/jpg') return 'image/jpeg'
  // Some browsers leave the type blank for a dragged file; the name still says.
  const name = file.name.toLowerCase()
  if (name.endsWith('.png')) return 'image/png'
  if (name.endsWith('.jpg') || name.endsWith('.jpeg')) return 'image/jpeg'
  return null
}
