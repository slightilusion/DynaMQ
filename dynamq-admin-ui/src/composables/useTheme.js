import { ref, watchEffect } from 'vue'

const THEME_KEY = 'dynamq-theme'

// Check if user prefers dark mode
const getDefaultTheme = () => {
    const stored = localStorage.getItem(THEME_KEY)
    if (stored) return stored === 'dark'
    return window.matchMedia('(prefers-color-scheme: dark)').matches
}

const isDark = ref(getDefaultTheme())

// Apply theme to document
const applyTheme = (dark) => {
    document.documentElement.classList.toggle('dark', dark)
    localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light')
}

// Watch for changes
watchEffect(() => {
    applyTheme(isDark.value)
})

export function useTheme() {
    const toggleTheme = () => {
        isDark.value = !isDark.value
    }

    const setTheme = (dark) => {
        isDark.value = dark
    }

    return {
        isDark,
        toggleTheme,
        setTheme
    }
}
