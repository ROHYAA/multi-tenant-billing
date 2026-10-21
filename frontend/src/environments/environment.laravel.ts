export const environment = {
  production: false,
  /**
   * Relative URL used with `ng serve --configuration=laravel` + proxy.laravel.json
   * so cookies stay first-party on localhost:4200. Do not point default
   * environment.ts here — that remains the Java API until cutover (Phase 9).
   */
  apiBaseUrl: '/api/v1',
  nativeClient: false,
  adsenseClient: '',
  adsenseSlot: '',
};
