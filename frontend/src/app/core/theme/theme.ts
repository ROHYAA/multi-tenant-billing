import { Injectable, effect, signal } from '@angular/core';

const THEME_KEY = 'shopledger.theme';
type Theme = 'light' | 'dark';

/**
 * Single source of truth for the dark-mode toggle — flips a `dark` class on
 * <html>, which both Tailwind (darkMode: 'class') and Angular Material's
 * `color-scheme`-driven light-dark() tokens key off (see styles.scss).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly themeSignal = signal<Theme>(this.readInitialTheme());
  readonly theme = this.themeSignal.asReadonly();
  readonly isDark = () => this.themeSignal() === 'dark';

  constructor() {
    effect(() => {
      const theme = this.themeSignal();
      document.documentElement.classList.toggle('dark', theme === 'dark');
      localStorage.setItem(THEME_KEY, theme);
    });
  }

  toggle(): void {
    this.themeSignal.set(this.isDark() ? 'light' : 'dark');
  }

  setTheme(theme: Theme): void {
    this.themeSignal.set(theme);
  }

  private readInitialTheme(): Theme {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
