import { useEffect, useState } from "react"

export function useIsDarkMode(): boolean {
  const [isDark, setIsDark] = useState(() =>
    document.documentElement.classList.contains("dark")
  )

  useEffect(() => {
    const observer = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        if (mutation.attributeName === "class") {
          setIsDark(document.documentElement.classList.contains("dark"))
        }
      }
    })

    observer.observe(document.documentElement, { attributes: true })

    return () => observer.disconnect()
  }, [])

  return isDark
}
