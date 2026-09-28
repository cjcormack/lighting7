import { useCallback, useRef, useState } from 'react'
import { FileUp, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { pdfjs } from 'react-pdf'

export interface PickedScript {
  bytes: ArrayBuffer
  pageCount: number
  fileName: string
}

/**
 * PDF import surface. The file is read and page-counted locally via pdfjs, then
 * handed to the caller for upload — the server computes the content hash (the
 * script's identity; never the filename). Hashing is deliberately NOT done here:
 * crypto.subtle is unavailable on non-HTTPS origins, which is exactly how the
 * controller is reached over the LAN.
 */
export function ScriptUploadCard({
  title,
  description,
  uploading,
  error,
  onUpload,
}: {
  title: string
  description: string
  uploading: boolean
  /** Server-side upload/create failure surfaced by the caller. */
  error?: string | null
  onUpload: (script: PickedScript) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [readError, setReadError] = useState<string | null>(null)
  const [reading, setReading] = useState(false)

  const handleFile = useCallback(
    async (file: File) => {
      setReadError(null)
      setReading(true)
      try {
        const bytes = await file.arrayBuffer()
        // pdfjs consumes (and may detach) the buffer it's given — hand it a copy
        // so the original survives for the upload body.
        const doc = await pdfjs.getDocument({ data: bytes.slice(0) }).promise
        const pageCount = doc.numPages
        await doc.destroy()
        onUpload({ bytes, pageCount, fileName: file.name })
      } catch {
        setReadError('Could not read that file as a PDF.')
      } finally {
        setReading(false)
      }
    },
    [onUpload],
  )

  const busy = reading || uploading
  const shownError = readError ?? error

  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed p-8 text-center">
      <FileUp className="size-8 text-muted-foreground" />
      <div>
        <p className="font-medium">{title}</p>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void handleFile(file)
          e.target.value = ''
        }}
      />
      <Button onClick={() => inputRef.current?.click()} disabled={busy}>
        {busy ? <Loader2 className="size-4 animate-spin" /> : <FileUp className="size-4" />}
        {busy ? 'Importing…' : 'Choose PDF'}
      </Button>
      {shownError && <p className="text-sm text-red-500">{shownError}</p>}
    </div>
  )
}
