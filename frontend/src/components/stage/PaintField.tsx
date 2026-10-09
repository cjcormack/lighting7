import { useRef, useState } from 'react'
import { ImageOff, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useUploadSceneImageMutation } from '@/store/sceneImages'
import {
  aspectFit,
  ratioLabel,
  SCENE_IMAGE_MAX_BYTES,
  SCENE_IMAGE_TYPES,
  sceneImageTypeOf,
  sceneImageUrl,
  type PaintSide,
  type SceneImageInfo,
} from '@/api/sceneImageApi'
import { formatError } from '@/lib/formatError'

/**
 * One painted face on the element sheet (scrim plan §4, D4): *Paint, front* or *Paint, back* on a
 * drape or a flat. A thumbnail of the desk's display copy (`?variant=display`), and Upload, Replace
 * and Remove. An upload goes to the desk's image store at once — it is content-addressed, so the
 * same file is stored once however often it is sent — and the hash it answers lands in the draft
 * like any other field, written with the form's Save. An image the store refuses (a type it does
 * not take, more than 25 MB or 8192 px a side) is said here, in the desk's words.
 *
 * Under the thumbnail, the aspect hint: the image against the face it is stretched over, a warning
 * past 2 % with *Match height to image*, and "Transparent pixels cut holes" when the image has any.
 * A hash this machine does not hold (a partial import) reads "Image missing on this machine", and
 * the cloth draws unpainted.
 *
 * The Stage view draws the paint since scrim plan session 2 (`stage3d/scene/paintTextures.ts` and
 * the surface shader's `PAINT`; `docs/stage-vis-engineering.md` §"Painted cloths").
 */
export function PaintField({
  projectId,
  side,
  hash,
  images,
  imagesLoaded,
  widthM,
  heightM,
  onChange,
  onMatchHeight,
}: {
  projectId: number
  side: PaintSide
  hash: string | null
  /** What the desk's store holds for this project, by hash. */
  images: ReadonlyMap<string, SceneImageInfo>
  /** Whether [images] has answered — until it has, an unknown hash is not yet "missing". */
  imagesLoaded: boolean
  widthM: number | null
  heightM: number | null
  onChange: (hash: string | null) => void
  onMatchHeight: (heightM: number) => void
}) {
  const id = `element-paint-${side}`
  const label = side === 'front' ? 'Paint, front' : 'Paint, back'
  const input = useRef<HTMLInputElement>(null)
  const [upload, { isLoading }] = useUploadSceneImageMutation()
  const [error, setError] = useState<string | null>(null)
  const [broken, setBroken] = useState<string | null>(null)
  // What the last upload answered: the list refetches after it, and until then this is the image.
  const [uploaded, setUploaded] = useState<SceneImageInfo | null>(null)

  const info = hash ? (images.get(hash) ?? (uploaded?.hash === hash ? uploaded : undefined)) : undefined
  const missing = hash != null && ((imagesLoaded && info == null) || broken === hash)
  const fit = info ? aspectFit(info, widthM, heightM) : null

  const choose = async (file: File | undefined) => {
    if (input.current) input.current.value = ''
    if (!file) return
    setError(null)
    const mediaType = sceneImageTypeOf(file)
    if (!mediaType) {
      setError(`${file.name} is not a PNG or a JPEG`)
      return
    }
    if (file.size > SCENE_IMAGE_MAX_BYTES) {
      setError(`${file.name} is over ${SCENE_IMAGE_MAX_BYTES / (1024 * 1024)} MB`)
      return
    }
    try {
      const stored = await upload({ projectId, bytes: await file.arrayBuffer(), mediaType }).unwrap()
      setBroken(null)
      setUploaded(stored)
      onChange(stored.hash)
    } catch (err) {
      setError(formatError(err))
    }
  }

  return (
    <div className="min-w-0 space-y-1" data-paint={side}>
      <div className="text-xs text-muted-foreground" id={`${id}-label`}>
        {label}
      </div>
      <div className="flex items-start gap-2">
        <div
          className="flex h-16 w-24 shrink-0 items-center justify-center overflow-hidden rounded-md border bg-muted/40"
          aria-labelledby={`${id}-label`}
        >
          {hash && !missing ? (
            <img
              src={sceneImageUrl(projectId, hash, 'display')}
              alt={`${label} image`}
              className="h-full w-full object-contain"
              onError={() => setBroken(hash)}
              data-paint-thumbnail={side}
            />
          ) : hash ? (
            <ImageOff className="size-5 text-muted-foreground" aria-hidden />
          ) : (
            <span className="text-[11px] text-muted-foreground">None</span>
          )}
        </div>
        <div className="flex min-w-0 flex-wrap gap-1.5">
          <input
            ref={input}
            id={id}
            type="file"
            accept={SCENE_IMAGE_TYPES.join(',')}
            className="hidden"
            aria-label={`${label}: choose an image`}
            onChange={(e) => void choose(e.target.files?.[0])}
          />
          <Button type="button" size="sm" variant="outline" disabled={isLoading} onClick={() => input.current?.click()}>
            <Upload className="mr-1 size-3.5" />
            {isLoading ? 'Uploading…' : hash ? 'Replace' : 'Upload'}
          </Button>
          {hash && (
            <Button
              type="button"
              size="sm"
              variant="ghost"
              disabled={isLoading}
              onClick={() => {
                setError(null)
                onChange(null)
              }}
            >
              Remove
            </Button>
          )}
        </div>
      </div>
      {missing && <p className="text-[11px] leading-snug text-amber-600 dark:text-amber-400">Image missing on this machine</p>}
      {info && fit && (
        <p className="text-[11px] leading-snug text-muted-foreground" data-paint-aspect={fit.matches ? 'match' : 'off'}>
          Image {ratioLabel(fit.imageAspect)}, cloth {widthM?.toFixed(1)} × {heightM?.toFixed(1)} m
          {fit.matches ? ' · aspects match' : null}
        </p>
      )}
      {info && fit && !fit.matches && (
        <div className="flex flex-wrap items-center gap-1.5" role="status">
          <p className="text-[11px] leading-snug text-amber-600 dark:text-amber-400">
            The image will stretch: its aspect is {Math.round(Math.abs(fit.clothAspect / fit.imageAspect - 1) * 100)} % off the
            cloth&apos;s.
          </p>
          <Button type="button" size="sm" variant="outline" className="h-6 px-2 text-xs" onClick={() => onMatchHeight(fit.heightForImage)}>
            Match height to image
          </Button>
        </div>
      )}
      {info?.hasAlpha && <p className="text-[11px] leading-snug text-muted-foreground">Transparent pixels cut holes</p>}
      {error && (
        <p className="text-[11px] leading-snug text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
