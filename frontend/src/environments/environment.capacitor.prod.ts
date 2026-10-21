export const environment = {
  production: true,
  /**
   * Public HTTPS Laravel API. Replace the host before `npm run cap:release`.
   * Capacitor's WebView origin is https://localhost — add that to CORS.
   */
  apiBaseUrl: 'https://REPLACE_WITH_YOUR_API_HOST/api/v1',
  nativeClient: true,
  adsenseClient: '',
  adsenseSlot: '',
};
