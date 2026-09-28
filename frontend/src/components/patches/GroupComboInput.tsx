import { useState, useRef, useEffect, useMemo } from 'react'
import { Input } from '@/components/ui/input'
import { Plus, X } from 'lucide-react'
import type { PatchGroup } from '@/api/patchApi'

interface GroupComboInputProps {
  value: string
  onChange: (name: string) => void
  groups: PatchGroup[]
  placeholder?: string
  /** Clear the input after a selection is made (useful for "add to list" patterns) */
  clearOnSelect?: boolean
}

/**
 * Typeahead input for group assignment.
 * - Filters existing groups as you type
 * - Shows "+ Create <name>" when no exact match
 * - Clear button to remove assignment
 */
export function GroupComboInput({ value, onChange, groups, placeholder = 'Type group name...', clearOnSelect = false }: GroupComboInputProps) {
  const [focused, setFocused] = useState(false)
  const [inputValue, setInputValue] = useState(value)
  const inputRef = useRef<HTMLInputElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // Sync external value changes
  useEffect(() => {
    setInputValue(value)
  }, [value])

  const query = inputValue.toLowerCase().trim()

  const suggestions = useMemo(() => {
    if (!query) return groups.slice(0, 8)
    return groups.filter(g => g.name.toLowerCase().includes(query))
  }, [groups, query])

  const exactMatch = groups.find(g => g.name.toLowerCase() === query)
  const showCreate = query.length > 0 && !exactMatch

  const handleSelect = (name: string) => {
    setInputValue(clearOnSelect ? '' : name)
    onChange(name)
    setFocused(false)
    inputRef.current?.blur()
  }

  const handleClear = () => {
    setInputValue('')
    onChange('')
    inputRef.current?.focus()
  }

  const handleBlur = (e: React.FocusEvent) => {
    // Don't close if clicking within the dropdown
    if (containerRef.current?.contains(e.relatedTarget as Node)) return
    setFocused(false)
    // Commit current value on blur
    if (inputValue !== value) {
      onChange(inputValue)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      setFocused(false)
      inputRef.current?.blur()
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (inputValue.trim()) {
        handleSelect(inputValue.trim())
      }
    }
  }

  const showDropdown = focused && (suggestions.length > 0 || showCreate)

  return (
    <div ref={containerRef} className="relative">
      <div className="relative">
        <Input
          ref={inputRef}
          value={inputValue}
          onChange={(e) => {
            setInputValue(e.target.value)
            if (!focused) setFocused(true)
          }}
          onFocus={() => setFocused(true)}
          onBlur={handleBlur}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          className="pr-8"
        />
        {inputValue && (
          <button
            type="button"
            tabIndex={-1}
            onClick={handleClear}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>

      {showDropdown && (
        <div className="absolute z-50 mt-1 w-full rounded-md border bg-popover shadow-md py-1 max-h-48 overflow-y-auto">
          {suggestions.map((g) => (
            <button
              key={g.id}
              type="button"
              tabIndex={-1}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => handleSelect(g.name)}
              className="flex items-center justify-between w-full px-3 py-1.5 text-sm text-left hover:bg-accent transition-colors"
            >
              <span>{g.name}</span>
              <span className="text-[10px] text-muted-foreground">{g.memberCount} fixture{g.memberCount !== 1 ? 's' : ''}</span>
            </button>
          ))}
          {showCreate && (
            <button
              type="button"
              tabIndex={-1}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => handleSelect(inputValue.trim())}
              className="flex items-center gap-1.5 w-full px-3 py-1.5 text-sm text-left hover:bg-accent transition-colors text-primary"
            >
              <Plus className="size-3.5" />
              <span>Create &ldquo;{inputValue.trim()}&rdquo;</span>
            </button>
          )}
        </div>
      )}
    </div>
  )
}
