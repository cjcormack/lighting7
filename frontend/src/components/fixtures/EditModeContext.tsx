import { createContext, useContext, useState, ReactNode } from 'react'

interface EditModeContextValue {
  isEditing: boolean
  setEditing: (editing: boolean) => void
  toggleEditing: () => void
}

const EditModeContext = createContext<EditModeContextValue>({
  isEditing: false,
  setEditing: () => {},
  toggleEditing: () => {},
})

export function EditModeProvider({ children }: { children: ReactNode }) {
  const [isEditing, setEditing] = useState(false)

  const toggleEditing = () => setEditing((prev) => !prev)

  return (
    <EditModeContext.Provider value={{ isEditing, setEditing, toggleEditing }}>
      {children}
    </EditModeContext.Provider>
  )
}

export function useEditMode() {
  return useContext(EditModeContext)
}
