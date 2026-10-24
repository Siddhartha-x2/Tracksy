import { useCallback, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';
const storageKey = 'tracksy-theme';
const validTheme = (value: string | null | undefined): value is Theme => value === 'light' || value === 'dark';

function savedTheme(): Theme | null {
  try { const value = localStorage.getItem(storageKey); return validTheme(value) ? value : null; }
  catch { return null; }
}
function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#16141a' : '#f7f5f0');
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(() => savedTheme() ?? 'light');

  useEffect(() => { applyTheme(theme); }, [theme]);
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== storageKey && event.key !== null) return;
      setTheme(validTheme(event.newValue) ? event.newValue : 'light');
    };
    window.addEventListener('storage', onStorage);
    return () => { window.removeEventListener('storage', onStorage); };
  }, []);

  const toggleTheme = useCallback(() => {
    const next = theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem(storageKey, next); } catch { /* Keep the choice for this visit. */ }
    applyTheme(next);
    setTheme(next);
  }, [theme]);

  return { theme, toggleTheme };
}
