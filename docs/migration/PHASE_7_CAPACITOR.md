# Phase 7 — Capacitor prep

Not a Play Store release. No offline billing. Website cookie auth is unchanged.

## Auth split

| Client | How it authenticates |
|---|---|
| Browser (Angular default / laravel proxy) | HttpOnly cookies. Login JSON **does not** include JWTs. |
| Capacitor WebView | `X-ShopLedger-Client: capacitor` → login/signup/refresh JSON includes `data.tokens.{accessToken,refreshToken}`. Subsequent calls send `Authorization: Bearer`. Refresh/logout send `refreshToken` in the body. |

Website code never writes JWTs to `localStorage`. Native tokens stay in memory and, when the WebView injects Capacitor Preferences, in the OS plugin store.

## Angular

- `environment.capacitor.ts` — `nativeClient: true`, absolute `https://YOUR_API_HOST/api/v1` (replace before a device build).
- `npm run build:capacitor`
- `capacitor.config.json` — `webDir` is `dist/frontend/browser`.

To add the Android shell later (not done here):

```
cd frontend
npm i @capacitor/core @capacitor/cli @capacitor/preferences @capacitor/android
npx cap add android
npx cap sync
```

CORS must include the WebView origin (`capacitor://localhost`, `https://localhost`). See `.env.example`.

## Not claimed

- ESC/POS / hardware printers
- Offline queue
- A compiled `.apk` / device test

## Android app: PDFs, sharing, camera (2026-10-21)

Verified on the Pixel 6 API 36 emulator with a debug APK.

| Feature | How it works in the APK |
|---|---|
| Print bill / reprint / purchase voucher | WebView cannot open `blob:` URLs, so `shared/utils/file-output.ts` hands the PDF to the local `ShopLedgerFiles` plugin (`android/app/.../ShopLedgerFilesPlugin.java`, registered in `MainActivity`). It opens Android's print dialog (any installed printer service, or Save as PDF). Paper size is preselected from the PDF: ISO A4, or a custom 58/80 mm roll size for thermal receipts (used if the printer service supports it). |
| Share bill / voucher | Same plugin → Android share sheet (WhatsApp, Gmail, Drive, Print…). |
| CSV export | Share sheet in the app; browser download on the web. |
| Camera / gallery on Buy Used Phone | `<input capture>` opens the camera app (needs the `IMAGE_CAPTURE` `<queries>` entry in `AndroidManifest.xml` on Android 11+); the gallery button opens the photo picker. Photos are shrunk to ≤1600 px JPEG before upload. |
| Logo / signature previews | `img[appAuthSrc]` loads API images through HttpClient so the Bearer token is sent. |
| Session | If the 15-minute access token has expired when the app is reopened, startup renews it with the stored refresh token instead of showing the login screen. |
| AdMob banner | The native banner overlays the WebView; the shell adds bottom padding in the app so the last button can scroll clear. |

PDF downloads are verified (`%PDF` header + `%%EOF` trailer) and retried twice; PDF responses send `Content-Length`.

### LAN testing: do not expose `php artisan serve` directly

PHP's built-in server on Windows was observed silently dropping bytes when writing to non-loopback clients (phone/emulator over the LAN) — PDFs arrived with missing bytes and Android printed them without a signature. Loopback is reliable, so for device testing run:

```
cd backend-laravel
PHP_BINARY=C:\path\to\php.exe node scripts/lan-server.mjs   # LAN :8000 -> PHP 127.0.0.1:8010
```

The debug APK (`environment.capacitor.ts`) talks to `http://10.0.0.56:8000`. Production uses nginx/Apache + PHP-FPM over HTTPS and is not affected.
