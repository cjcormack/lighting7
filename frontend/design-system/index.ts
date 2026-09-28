// Barrel for the design-sync library build (design-system/vite.config.ts).
// Two tiers: the shadcn-style primitives in src/components/ui, and the app
// components that render without the Redux store or the router. Anything
// store-bound stays out — it cannot render standalone in a design tool.
import "./design-system.css"

// UI primitives
export * from "../src/components/ui/alert-dialog"
export * from "../src/components/ui/alert"
export * from "../src/components/ui/avatar"
export * from "../src/components/ui/badge"
export * from "../src/components/ui/button"
export * from "../src/components/ui/card"
export * from "../src/components/ui/context-menu"
export * from "../src/components/ui/dialog"
export * from "../src/components/ui/dropdown-menu"
export * from "../src/components/ui/form-fields"
export * from "../src/components/ui/input"
export * from "../src/components/ui/label"
export * from "../src/components/ui/popover"
export * from "../src/components/ui/select"
export * from "../src/components/ui/separator"
export * from "../src/components/ui/sheet"
export * from "../src/components/ui/slider"
export * from "../src/components/ui/table"
export * from "../src/components/ui/tabs"
export * from "../src/components/ui/textarea"
export * from "../src/components/ui/toggle-group"
export * from "../src/components/ui/tooltip"

// Generic app pieces
export { CollapsiblePanel } from "../src/components/CollapsiblePanel"
export { InlineEditField } from "../src/components/InlineEditField"
export { TruncateStart } from "../src/components/TruncateStart"
export { FeatureErrorBoundary } from "../src/components/FeatureErrorBoundary"
export { UnsetCellMark } from "../src/components/fixtures-list/cells/UnsetCellMark"
export { AuthScreenLayout } from "../src/components/auth/AuthScreenLayout"
export { default as ThemeToggle } from "../src/ThemeToggle"

// Live-view chrome
export { BuskLabel } from "../src/components/busking/BuskLabel"
export { ShowLockControl } from "../src/components/runner/ShowLockControl"
export { OffPlayheadBanner } from "../src/components/runner/OffPlayheadBanner"
export { OutOfOrderBanner } from "../src/components/runner/OutOfOrderBanner"
export { ShowMarkerRow } from "../src/components/runner/ShowMarkerRow"
export { MarkerRow } from "../src/components/runner/MarkerRow"
export { TimingBadge } from "../src/components/cues/TimingBadge"
export { TimingFields } from "../src/components/cues/TimingEditor"
export { LookNameBadge } from "../src/components/looks/LookNameBadge"
export { LookValueChip, LookPreviewSwatches } from "../src/components/looks/lookValueChips"
export { Section, AddBtn, RemoveBtn } from "../src/components/looks/paneChrome"

// Fixture controls
export { ColourPickerPopover } from "../src/components/fixtures/ColourPickerPopover"
export { ExtendedChannelSlider } from "../src/components/fixtures/ExtendedChannelSlider"
export { ColourChannelSlider } from "../src/components/fixtures/ColourChannelSlider"
export { GelPickerField } from "../src/components/patches/GelPickerField"
export { BeamAngleField } from "../src/components/patches/BeamAngleField"
export { GroupMembershipSection } from "../src/components/fixtures/GroupMembershipSection"

// Prompt book
export { PromptBookToolbar } from "../src/components/promptbook/PromptBookToolbar"
export { ToolPalette } from "../src/components/promptbook/ToolPalette"
export { FloatingSelectionToolbar } from "../src/components/promptbook/FloatingSelectionToolbar"

// Stage 2D
export { Stage2DHud } from "../src/components/stage2d/Stage2DHud"
export { StageShortcutsPopover } from "../src/components/stage2d/StageShortcutsPopover"
