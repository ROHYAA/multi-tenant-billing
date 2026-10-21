import { environment as javaEnv } from './environment';
import { environment as laravelEnv } from './environment.laravel';
import { environment as capacitorEnv } from './environment.capacitor';
import { environment as capacitorProdEnv } from './environment.capacitor.prod';

describe('API environments', () => {
  it('keeps the default build on the Java API', () => {
    expect(javaEnv.apiBaseUrl).toBe('http://localhost:8080/api/v1');
    expect(javaEnv.nativeClient).toBeFalse();
  });

  it('uses a relative Laravel URL for the proxy configuration', () => {
    expect(laravelEnv.apiBaseUrl).toBe('/api/v1');
    expect(laravelEnv.production).toBeFalse();
    expect(laravelEnv.nativeClient).toBeFalse();
  });

  it('uses Bearer mode for Capacitor against the emulator host loopback', () => {
    expect(capacitorEnv.nativeClient).toBeTrue();
    expect(capacitorEnv.apiBaseUrl).toBe('http://10.0.0.56:8000/api/v1');
  });

  it('requires HTTPS Bearer mode for a store/release APK', () => {
    expect(capacitorProdEnv.nativeClient).toBeTrue();
    expect(capacitorProdEnv.apiBaseUrl.startsWith('https://')).toBeTrue();
  });
});
