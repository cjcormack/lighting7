import { useState, useRef, useEffect, useCallback } from "react"
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Lock } from "lucide-react"
import { useChannelValue } from "@/hooks/usePropertyValues"
import { useGetChannelMappingQuery } from "@/store/channelMapping"
import { useGetChannelParkStateQuery, useParkChannelMutation } from "@/store/park"
import { useUpdateChannelMutation } from "@/store/channels"
import { useIsDeskConnected } from "@/store/status"
import { DESK_OFFLINE_LABEL, WS_GESTURE_DROPPED_MESSAGE, WS_GESTURE_DROPPED_TOAST_ID } from "@/api/wsGesture"
import { toast } from "sonner"

interface ChannelValueDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: "park" | "set"
}

export function ChannelValueDialog({ open, onOpenChange, mode }: ChannelValueDialogProps) {
  // Both arms of this sheet write over the WebSocket. The Channels page already disables the
  // buttons that open it, but the command palette opens it too, so the guard belongs here as
  // well as there.
  const connected = useIsDeskConnected()
  const [universe, setUniverse] = useState("0")
  const [channel, setChannel] = useState("")
  const [value, setValue] = useState("")

  const universeRef = useRef<HTMLInputElement>(null)
  const channelRef = useRef<HTMLInputElement>(null)
  const valueRef = useRef<HTMLInputElement>(null)

  const [runParkChannel] = useParkChannelMutation()
  const [runUpdateChannel] = useUpdateChannelMutation()

  // Parse current inputs for the info panel
  const universeNum = Number(universe)
  const channelNum = Number(channel)
  const isValidChannel = !isNaN(universeNum) && !isNaN(channelNum) && channelNum >= 1 && channelNum <= 512

  // Live data. The level reads its WS subscription directly — it moves at frame rate, and a
  // cache entry per channel made a Redux dispatch of every frame; mapping and park state are
  // RTK Query entries that update on the far rarer frames of their own.
  const currentValue = useChannelValue(
    isValidChannel ? { universe: universeNum, channelNo: channelNum } : null
  )
  const { data: mapping } = useGetChannelMappingQuery(
    { universe: universeNum, channelNo: channelNum },
    { skip: !isValidChannel }
  )
  const { data: parkedValue } = useGetChannelParkStateQuery(
    { universe: universeNum, channelNo: channelNum },
    { skip: !isValidChannel }
  )

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setUniverse("0")
      setChannel("")
      setValue("")
      // Focus universe input after dialog renders
      requestAnimationFrame(() => {
        universeRef.current?.focus()
        universeRef.current?.select()
      })
    }
  }, [open])

  const handleSubmit = useCallback(
    (e?: React.FormEvent) => {
      e?.preventDefault()
      const u = Number(universe)
      const ch = Number(channel)
      const v = Number(value)
      if (isNaN(u) || isNaN(ch) || isNaN(v) || ch < 1 || ch > 512 || v < 0 || v > 255) return
      // Says so rather than returning quietly: the submit button is disabled, but the value
      // field's Enter shortcut reaches here directly, and the footer still advertises it.
      if (!connected) {
        toast.error(WS_GESTURE_DROPPED_MESSAGE, { id: WS_GESTURE_DROPPED_TOAST_ID })
        return
      }

      if (mode === "park") {
        runParkChannel({ universe: u, channelNo: ch, value: v })
      } else {
        runUpdateChannel({ universe: u, channelNo: ch, value: v })
      }
      onOpenChange(false)
    },
    [universe, channel, value, mode, connected, runParkChannel, runUpdateChannel, onOpenChange]
  )

  // Keyboard shortcut handlers for field advancement
  const handleUniverseKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "-") {
      e.preventDefault()
      channelRef.current?.focus()
      channelRef.current?.select()
    }
  }

  const handleChannelKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === ":" || e.key === "=") {
      e.preventDefault()
      valueRef.current?.focus()
      valueRef.current?.select()
    }
  }

  const handleValueKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault()
      handleSubmit()
    }
  }

  const isPark = mode === "park"
  const title = isPark ? "Park Channel at Value" : "Set Channel Value"
  const description = isPark
    ? "Lock a channel at a fixed DMX value (overrides all other output)"
    : "Set a channel to a DMX value"
  const buttonLabel = isPark ? "Park" : "Set"

  const valueNum = Number(value)
  const isValid =
    !isNaN(Number(universe)) &&
    !isNaN(channelNum) &&
    channelNum >= 1 &&
    channelNum <= 512 &&
    !isNaN(valueNum) &&
    valueNum >= 0 &&
    valueNum <= 255 &&
    connected

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
          <SheetDescription>{description}</SheetDescription>
        </SheetHeader>

        <SheetBody>
        <form onSubmit={handleSubmit} className="space-y-3">
          {/* Input row */}
          <div className="flex items-end gap-1.5">
            <div className="flex flex-col gap-1 min-w-0 flex-1">
              <label className="text-[10px] text-muted-foreground uppercase tracking-wider">Uni</label>
              <Input
                ref={universeRef}
                type="number"
                value={universe}
                onChange={(e) => setUniverse(e.target.value)}
                onKeyDown={handleUniverseKeyDown}
                min={0}
                className="h-9 text-sm text-center font-mono"
              />
            </div>
            <span className="text-muted-foreground pb-2 font-mono shrink-0">-</span>
            <div className="flex flex-col gap-1 min-w-0 flex-[2]">
              <label className="text-[10px] text-muted-foreground uppercase tracking-wider">Channel</label>
              <Input
                ref={channelRef}
                type="number"
                value={channel}
                onChange={(e) => setChannel(e.target.value)}
                onKeyDown={handleChannelKeyDown}
                min={1}
                max={512}
                placeholder="1-512"
                className="h-9 text-sm text-center font-mono"
              />
            </div>
            <span className="text-muted-foreground pb-2 font-mono shrink-0">=</span>
            <div className="flex flex-col gap-1 min-w-0 flex-[2]">
              <label className="text-[10px] text-muted-foreground uppercase tracking-wider">Value</label>
              <Input
                ref={valueRef}
                type="number"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                onKeyDown={handleValueKeyDown}
                min={0}
                max={255}
                placeholder="0-255"
                className="h-9 text-sm text-center font-mono"
              />
            </div>
          </div>

          {/* Submit + keyboard hint */}
          <div className="flex items-center justify-between gap-2">
            <p className="text-[10px] text-muted-foreground">
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">-</kbd> channel{" · "}
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">=</kbd> value{" · "}
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">Enter</kbd> submit
            </p>
            <Button
              type="submit"
              disabled={!isValid}
              title={connected ? undefined : DESK_OFFLINE_LABEL}
              className="shrink-0"
            >
              {buttonLabel}
            </Button>
          </div>

          {/* Channel info panel */}
          {isValidChannel && (
            <div className="rounded-md border bg-muted/50 px-3 py-2.5 space-y-1">
              <div className="flex items-center gap-2 text-sm">
                {mapping ? (
                  <>
                    <span className="font-medium">{mapping.fixtureName}</span>
                    {mapping.description && (
                      <>
                        <span className="text-muted-foreground">·</span>
                        <span className="text-muted-foreground">{mapping.description}</span>
                      </>
                    )}
                  </>
                ) : (
                  <span className="text-muted-foreground">Unmapped channel</span>
                )}
              </div>
              <div className="flex items-center gap-3 text-xs text-muted-foreground">
                <span>
                  Current: <span className="font-mono font-medium text-foreground">{currentValue}</span>
                </span>
                {parkedValue !== undefined ? (
                  <Badge className="bg-amber-500 text-white text-[10px] px-1.5 py-0 gap-1">
                    <Lock className="size-2.5" />
                    Parked at {parkedValue}
                  </Badge>
                ) : (
                  <span>Not parked</span>
                )}
              </div>
            </div>
          )}
        </form>
        </SheetBody>
      </SheetContent>
    </Sheet>
  )
}
