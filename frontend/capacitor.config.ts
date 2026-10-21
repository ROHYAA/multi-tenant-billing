import type { CapacitorConfig } from '@capacitor/cli';

const productionApk = process.env.SHOPLEDGER_CAP_PROD === '1';

const config: CapacitorConfig = {
  appId: 'com.shopledger.app',
  appName: 'ShopLedger',
  webDir: 'dist/frontend/browser',
  server: {
    androidScheme: 'https',
    cleartext: !productionApk,
  },
  android: {
    allowMixedContent: !productionApk,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2500,
      launchAutoHide: false,
      backgroundColor: '#0B2C52',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
    },
  },
};

export default config;
