// Type definitions for the prompt-book API — the operator's script view.
//
// A prompt-book binds one imported PDF script to the project's show:
//   • the script is identified by content hash (never filename),
//   • cue anchors pin cues to normalized regions on script pages,
//   • free annotations carry operator commentary (notes, cuts, freetext).
// The cue's lighting payload stays in the cue stack — anchors store only the binding.

/** A rectangle on a single script page, normalized to [0,1]. y grows downward. */
export interface Rect {
  /** 0-based page index within the PDF. */
  page: number
  x: number
  y: number
  w: number
  h: number
}

/** One-or-more rects — multiple rects span page breaks / wrapped lines. */
export type Region = Rect[]

export type AnnotationKind = 'NOTE' | 'STRIKETHROUGH' | 'FREETEXT'

/**
 * Severity/intent of a NOTE — drives the callout colour in the reader
 * (NOTE=blue, WARN=amber, SAFETY=red). Only meaningful for NOTE annotations;
 * null (or on non-NOTE kinds) reads as NOTE.
 */
export type NoteTone = 'NOTE' | 'WARN' | 'SAFETY'

export interface CueAnchorDto {
  cueId: number
  region: Region
  /** Cached display label ("LX 12") — rendering convenience, not identity. */
  label: string | null
}

/**
 * A cue's reading position in the project's prompt book — the anchor region reduced
 * server-side to its earliest rect (topmost line on the lowest page). Served by
 * GET projects/{id}/cue-locations for the Run view; the frontend turns `{page, y}`
 * into a "top of p. 9"-style label. Project-scoped and book-agnostic.
 */
export interface CueLocationDto {
  cueId: number
  page: number
  y: number
}

export interface AnnotationDto {
  id: number
  kind: AnnotationKind
  region: Region
  text: string | null
  color: string | null
  tone: NoteTone | null
}

export interface PromptBookDetails {
  scriptHash: string
  scriptFileName: string | null
  pageCount: number
  /**
   * Leading front-matter (cover/title) PDF pages before the script's printed page 1.
   * Cue page labels subtract this so the number shown matches the script's own
   * numbering. 0 = no cover page (the raw PDF index + 1).
   */
  coverPages: number
  anchors: CueAnchorDto[]
  annotations: AnnotationDto[]
  canEdit: boolean
}

export interface NewPromptBookRequest {
  scriptHash: string
  pageCount: number
  scriptFileName?: string
  /** Leading front-matter pages; omitted on import (defaults to 0), set later in the toolbar. */
  coverPages?: number
}

export interface UpsertAnchorRequest {
  region: Region
  label?: string
}

export interface AnnotationRequest {
  kind: AnnotationKind
  region: Region
  text?: string
  color?: string
  tone?: NoteTone
}

export interface ScriptUploadResponse {
  scriptHash: string
  sizeBytes: number
}

/**
 * URL for the stored script PDF. Content-addressed, so the response is served
 * immutable — feed this straight to react-pdf's `file` prop and let HTTP caching
 * do the rest. Kept out of RTK Query on purpose: it's binary, not state.
 */
export function scriptDocUrl(projectId: number, scriptHash: string): string {
  return `/api/rest/projects/${projectId}/prompt-book/scripts/${scriptHash}`
}
