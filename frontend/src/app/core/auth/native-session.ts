import { Injectable } from '@angular/core';
import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { environment } from '../../../environments/environment';

export interface NativeTokens {
  accessToken: string;
  refreshToken: string;
  tokenType?: string;
}

const ACCESS_KEY = 'shopledger.native.access';
const REFRESH_KEY = 'shopledger.native.refresh';

/**
 * Capacitor / Android token holder. The website never writes JWTs to
 * localStorage; native uses in-memory plus Capacitor Preferences.
 */
@Injectable({ providedIn: 'root' })
export class NativeSession {
  private access: string | null = null;
  private refresh: string | null = null;

  isNative(): boolean {
    return environment.nativeClient || Capacitor.isNativePlatform();
  }

  accessToken(): string | null {
    return this.access;
  }

  refreshToken(): string | null {
    return this.refresh;
  }

  async hydrate(): Promise<void> {
    if (!this.isNative()) {
      return;
    }
    this.access = (await Preferences.get({ key: ACCESS_KEY })).value;
    this.refresh = (await Preferences.get({ key: REFRESH_KEY })).value;
  }

  async persist(tokens: NativeTokens | undefined | null): Promise<void> {
    if (!this.isNative() || !tokens?.accessToken || !tokens.refreshToken) {
      return;
    }
    this.access = tokens.accessToken;
    this.refresh = tokens.refreshToken;
    await Preferences.set({ key: ACCESS_KEY, value: tokens.accessToken });
    await Preferences.set({ key: REFRESH_KEY, value: tokens.refreshToken });
  }

  async clear(): Promise<void> {
    this.access = null;
    this.refresh = null;
    if (!this.isNative()) {
      return;
    }
    await Preferences.remove({ key: ACCESS_KEY });
    await Preferences.remove({ key: REFRESH_KEY });
  }
}
