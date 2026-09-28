"use client"

import * as React from "react"
import * as SheetPrimitive from "@radix-ui/react-dialog"
import { XIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import { setSheetUnsaved } from "@/lib/unsavedSheets"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

type RegisterUnsaved = (id: symbol, unsaved: boolean) => void

const SheetUnsavedContext = React.createContext<RegisterUnsaved | null>(null)

/**
 * Declare, from inside a sheet, that there is work in it that closing would throw away.
 *
 * Escape, a click on the shaded area outside, and the X then ask before discarding, instead of
 * taking the sheet — and the edit — with them.
 *
 * Only a close that **Radix** drives is seen here. A Cancel button wired straight to the parent's
 * own `setOpen(false)` closes the sheet without passing through this at all; wrap it in
 * `SheetClose` (`<SheetClose asChild><Button …/></SheetClose>`) so that it does.
 *
 * It reports upwards through context rather than a prop so that the component holding the form
 * state is the one that answers the question. That is usually several levels below the `Sheet`
 * itself (the sheet's body is typically its own component), and a `dirty` prop threaded down to
 * it would have to be threaded back up again.
 *
 * Only works on a **controlled** sheet — one with both `open` and `onOpenChange`. An uncontrolled
 * sheet closes itself inside Radix, where there is nothing to intercept.
 */
function useUnsavedChanges(unsaved: boolean) {
  const register = React.useContext(SheetUnsavedContext)
  const [id] = React.useState(() => Symbol("sheet-unsaved"))

  React.useEffect(() => {
    register?.(id, unsaved)
    // Whatever was unsaved leaves with the component that was holding it.
    return () => register?.(id, false)
  }, [register, id, unsaved])
}

function Sheet({
  unsavedChanges = false,
  open,
  onOpenChange,
  children,
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Root> & {
  /**
   * Same meaning as [useUnsavedChanges], for a parent that already tracks its own dirty state.
   * The two combine: the sheet guards if either says there is something to lose.
   */
  unsavedChanges?: boolean
}) {
  const [unsavedIds, setUnsavedIds] = React.useState<ReadonlySet<symbol>>(() => new Set())
  const [confirming, setConfirming] = React.useState(false)

  const register = React.useCallback<RegisterUnsaved>((id, unsaved) => {
    setUnsavedIds((previous) => {
      if (unsaved === previous.has(id)) return previous
      const next = new Set(previous)
      if (unsaved) next.add(id)
      else next.delete(id)
      return next
    })
  }, [])

  const hasUnsaved = unsavedChanges || unsavedIds.size > 0

  // The same answer, summed across every open sheet in `lib/unsavedSheets.ts`, for a reader that is
  // nowhere near a sheet: a `windows.show` from another screen declines to navigate while it is
  // non-zero. Gated on `open` because a controlled sheet's `unsavedChanges` prop can stay true
  // after the sheet closed (the form state outlives the panel), and a closed sheet loses nothing.
  const [countId] = React.useState(() => Symbol("sheet-open-unsaved"))
  const dirty = open !== false && hasUnsaved
  React.useEffect(() => {
    setSheetUnsaved(countId, dirty)
    return () => setSheetUnsaved(countId, false)
  }, [countId, dirty])

  const handleOpenChange = (open: boolean) => {
    if (!open && hasUnsaved) {
      setConfirming(true)
      return
    }
    onOpenChange?.(open)
  }

  return (
    <SheetUnsavedContext.Provider value={register}>
      <SheetPrimitive.Root data-slot="sheet" open={open} onOpenChange={handleOpenChange} {...props}>
        {children}
      </SheetPrimitive.Root>

      <AlertDialog open={confirming} onOpenChange={(open) => { if (!open) setConfirming(false) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Discard changes?</AlertDialogTitle>
            <AlertDialogDescription>
              You have unsaved changes that will be lost.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep editing</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setConfirming(false)
                onOpenChange?.(false)
              }}
            >
              Discard
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </SheetUnsavedContext.Provider>
  )
}

function SheetTrigger({
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Trigger>) {
  return <SheetPrimitive.Trigger data-slot="sheet-trigger" {...props} />
}

function SheetClose({
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Close>) {
  return <SheetPrimitive.Close data-slot="sheet-close" {...props} />
}

function SheetPortal({
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Portal>) {
  return <SheetPrimitive.Portal data-slot="sheet-portal" {...props} />
}

function SheetOverlay({
  className,
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Overlay>) {
  return (
    <SheetPrimitive.Overlay
      data-slot="sheet-overlay"
      className={cn(
        "data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 fixed inset-0 z-50 bg-black/50",
        className
      )}
      {...props}
    />
  )
}

/**
 * Escape while the Kotlin editor's completion popup is open must close the popup, not the sheet.
 *
 * CodeMirror's `.CodeMirror-hints` list is a plain element appended to `<body>`, not a Radix
 * layer, so it is invisible to the dismissable-layer stack: Escape reached the sheet and threw
 * away whatever was being edited. Radix listens for Escape in the **capture** phase, so this runs
 * before CodeMirror removes the popup — looking for it in the DOM here is a reliable test, not a
 * race with the widget's own handler.
 */
function completionPopupIsOpen() {
  return document.querySelector(".CodeMirror-hints") !== null
}

function SheetContent({
  className,
  children,
  side = "right",
  onEscapeKeyDown,
  onInteractOutside,
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Content> & {
  side?: "top" | "right" | "bottom" | "left"
}) {
  return (
    <SheetPortal>
      <SheetOverlay />
      <SheetPrimitive.Content
        data-slot="sheet-content"
        onEscapeKeyDown={(event) => {
          if (completionPopupIsOpen()) {
            event.preventDefault()
            return
          }
          onEscapeKeyDown?.(event)
        }}
        onInteractOutside={(event) => {
          // Same popup, other input: it hangs off <body>, so picking a completion with the mouse
          // reads as a click outside the sheet.
          if ((event.target as Element | null)?.closest?.(".CodeMirror-hints")) {
            event.preventDefault()
            return
          }
          onInteractOutside?.(event)
        }}
        className={cn(
          "bg-background data-[state=open]:animate-in data-[state=closed]:animate-out fixed z-50 flex flex-col gap-4 shadow-lg transition ease-in-out data-[state=closed]:duration-300 data-[state=open]:duration-500",
          side === "right" &&
            "data-[state=closed]:slide-out-to-right data-[state=open]:slide-in-from-right inset-y-0 right-0 h-full w-full border-l sm:max-w-sm",
          side === "left" &&
            "data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left inset-y-0 left-0 h-full w-full border-r sm:max-w-sm",
          side === "top" &&
            "data-[state=closed]:slide-out-to-top data-[state=open]:slide-in-from-top inset-x-0 top-0 h-auto border-b",
          side === "bottom" &&
            "data-[state=closed]:slide-out-to-bottom data-[state=open]:slide-in-from-bottom inset-x-0 bottom-0 h-auto border-t",
          className
        )}
        {...props}
      >
        {children}
        {/* `top-4` is measured against `SheetHeader`'s own `p-4` and a 14px title — the header
            almost every sheet here uses. A sheet that deliberately draws a shorter header (the
            programmer rail's, at `py-2` around a 9px label) can move it with a scoped rule
            against this `data-slot`; the slot is a hook, and the offset above stays the default
            every other sheet is aligned to. See `PD-SHEET-CLOSE-ALIGN`. */}
        <SheetPrimitive.Close
          data-slot="sheet-close-x"
          className="ring-offset-background focus:ring-ring data-[state=open]:bg-secondary absolute top-4 right-4 rounded-xs opacity-70 transition-opacity hover:opacity-100 focus:ring-2 focus:ring-offset-2 focus:outline-hidden disabled:pointer-events-none"
        >
          <XIcon className="size-4" />
          <span className="sr-only">Close</span>
        </SheetPrimitive.Close>
      </SheetPrimitive.Content>
    </SheetPortal>
  )
}

function SheetBody({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-body"
      className={cn("flex-1 overflow-y-auto space-y-4 px-4 pb-4", className)}
      {...props}
    />
  )
}

function SheetHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-header"
      className={cn("flex flex-col gap-1.5 p-4", className)}
      {...props}
    />
  )
}

function SheetFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-footer"
      className={cn("mt-auto flex flex-col gap-2 p-4", className)}
      {...props}
    />
  )
}

function SheetTitle({
  className,
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Title>) {
  return (
    <SheetPrimitive.Title
      data-slot="sheet-title"
      className={cn("text-foreground font-semibold", className)}
      {...props}
    />
  )
}

function SheetDescription({
  className,
  ...props
}: React.ComponentProps<typeof SheetPrimitive.Description>) {
  return (
    <SheetPrimitive.Description
      data-slot="sheet-description"
      className={cn("text-muted-foreground text-sm", className)}
      {...props}
    />
  )
}

export {
  Sheet,
  SheetTrigger,
  SheetClose,
  SheetContent,
  SheetBody,
  SheetHeader,
  SheetFooter,
  SheetTitle,
  SheetDescription,
  useUnsavedChanges,
}
