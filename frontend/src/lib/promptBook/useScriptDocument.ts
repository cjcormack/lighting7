import { useCallback, useState } from 'react'
import type { PromptBookDetails } from '../../api/promptBooksApi'
import { scriptDocUrl } from '../../api/promptBooksApi'
import { useSetPromptBookMutation, useUploadScriptDocMutation } from '../../store/promptBooks'
import { formatError } from '../formatError'
import type { PickedScript } from '../../components/promptbook/ScriptUploadCard'

/**
 * The PDF behind the book: importing one, re-attaching a missing one, and the front-matter offset
 * that makes the page numbers match the script's own.
 *
 * The load states are the point. A PDF that fails to render is only **missing** if the store
 * actually 404s — anything else (backend restarting, network blip) is an **error** with a retry,
 * never the re-import card, because `setPromptBook` is a create-or-replace PUT and tempting the
 * operator into re-importing mid-show would replace the real book. `retryNonce` exists to remount
 * the viewer after a retry so per-page text-bounds/scanned classification never carry over stale.
 *
 * Identity is the file's content hash, computed server-side — which is what makes re-import
 * idempotent, and what lets it work on the plain-HTTP LAN origins where `crypto.subtle` does not
 * exist.
 */
export function useScriptDocument(projectId: number, book: PromptBookDetails | undefined) {
  const [uploadScriptDoc, { isLoading: uploading }] = useUploadScriptDocMutation()
  const [setPromptBook, { isLoading: settingBook }] = useSetPromptBookMutation()

  const [loadState, setLoadState] = useState<'ok' | 'missing' | 'error'>('ok')
  const [retryNonce, setRetryNonce] = useState(0)
  const [hashMismatch, setHashMismatch] = useState<string | null>(null)
  const [reuploadError, setReuploadError] = useState<string | null>(null)
  const [importError, setImportError] = useState<string | null>(null)

  const retry = useCallback(() => {
    setLoadState('ok')
    setRetryNonce((n) => n + 1)
  }, [])

  const reupload = useCallback(
    async (script: PickedScript) => {
      setReuploadError(null)
      try {
        const upload = await uploadScriptDoc({ projectId, bytes: script.bytes }).unwrap()
        if (book && upload.scriptHash !== book.scriptHash) {
          setHashMismatch(upload.scriptHash)
          return
        }
        setHashMismatch(null)
        retry()
      } catch (err) {
        setReuploadError(`Upload failed: ${formatError(err)}`)
      }
    },
    [uploadScriptDoc, projectId, book, retry],
  )

  // A PDF load failure is only "missing" if the store actually 404s; anything
  // else (backend restart, network blip) gets a retry path, not the re-import card.
  const onDocumentError = useCallback(() => {
    void fetch(scriptDocUrl(projectId, book?.scriptHash ?? ''), { method: 'HEAD' })
      .then((resp) => setLoadState(resp.status === 404 ? 'missing' : 'error'))
      .catch(() => setLoadState('error'))
  }, [projectId, book?.scriptHash])

  // Import the show's prompt book from a picked PDF (the empty-state flow). The same
  // route then shows the reader once the book exists — no navigation needed.
  const importBook = useCallback(
    async (script: PickedScript) => {
      setImportError(null)
      try {
        const upload = await uploadScriptDoc({ projectId, bytes: script.bytes }).unwrap()
        await setPromptBook({
          projectId,
          scriptHash: upload.scriptHash,
          pageCount: script.pageCount,
          scriptFileName: script.fileName,
        }).unwrap()
      } catch (err) {
        setImportError(`Import failed: ${formatError(err)}`)
      }
    },
    [uploadScriptDoc, setPromptBook, projectId],
  )

  // Change the front-matter (cover/title) page count. Reuses the create-or-replace PUT
  // (which keeps anchors/annotations) to persist just this field; the optimistic patch in
  // the mutation makes the stepper snappy. Clamped so at least one numbered page remains.
  const setCoverPages = useCallback(
    (n: number) => {
      if (!book) return
      const next = Math.max(0, Math.min(n, book.pageCount - 1))
      if (next === book.coverPages) return
      setPromptBook({
        projectId,
        scriptHash: book.scriptHash,
        pageCount: book.pageCount,
        scriptFileName: book.scriptFileName ?? undefined,
        coverPages: next,
      })
    },
    [book, setPromptBook, projectId],
  )

  return {
    loadState,
    retryNonce,
    retry,
    uploading,
    settingBook,
    hashMismatch,
    reuploadError,
    reupload,
    importError,
    importBook,
    onDocumentError,
    setCoverPages,
  }
}
