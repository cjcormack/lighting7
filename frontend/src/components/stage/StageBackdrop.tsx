import { forwardRef, type HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/**
 * Shared stage map backdrop: dotted grid, UPSTAGE/DOWNSTAGE labels, crosshair
 * axes. Used by the patch-edit stage placer and the global stage panel.
 * Forwards extra div props (ref, onMouseDown, etc.) so consumers can attach
 * handlers and constrain sizing via className.
 */
export const StageBackdrop = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement>>(
  function StageBackdrop({ className, children, style, ...rest }, ref) {
    return (
      <div
        ref={ref}
        className={cn(
          'relative w-full border border-border rounded-md overflow-hidden bg-muted/40',
          className,
        )}
        style={{
          backgroundImage:
            'repeating-linear-gradient(0deg, rgba(127,127,127,0.06) 0 1px, transparent 1px 24px), repeating-linear-gradient(90deg, rgba(127,127,127,0.06) 0 1px, transparent 1px 24px)',
          ...style,
        }}
        {...rest}
      >
        <div className="absolute top-1.5 left-0 right-0 text-center text-[10px] uppercase tracking-[0.2em] text-muted-foreground/50 pointer-events-none">
          Upstage
        </div>
        <div className="absolute bottom-1.5 left-0 right-0 text-center text-[10px] uppercase tracking-[0.2em] text-muted-foreground/50 pointer-events-none">
          Downstage
        </div>
        <div className="absolute inset-x-0 top-1/2 h-px bg-foreground/10 pointer-events-none" />
        <div className="absolute inset-y-0 left-1/2 w-px bg-foreground/10 pointer-events-none" />
        {children}
      </div>
    )
  },
)
