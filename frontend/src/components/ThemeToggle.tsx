import type { Theme } from '../hooks/useTheme';
import { Icon } from './ui';

export default function ThemeToggle({ theme, onToggle }: { theme: Theme; onToggle: () => void }) {
  const label = theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode';
  return <button type="button" className="theme-toggle" onClick={onToggle} aria-label={label} aria-pressed={theme === 'dark'} title={label}>
    <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={19}/>
  </button>;
}
