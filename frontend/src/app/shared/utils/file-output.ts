import { Capacitor, registerPlugin } from '@capacitor/core';

/**
 * Print / share / save generated files on both platforms.
 *
 * Browser: open the PDF in a new tab (falls back to a download if pop-ups are blocked).
 * Android app: a WebView cannot open blob: URLs, so the bytes go to the native
 * ShopLedgerFiles plugin (android/app/.../ShopLedgerFilesPlugin.java), which uses
 * Android's print dialog (any printer, or "Save as PDF") and the share sheet (WhatsApp etc.).
 */
interface FilePayload {
  data: string;
  fileName: string;
  mimeType?: string;
  title?: string;
}

interface ShopLedgerFilesPlugin {
  printPdf(options: FilePayload): Promise<void>;
  share(options: FilePayload): Promise<void>;
  open(options: FilePayload): Promise<void>;
}

const ShopLedgerFiles = registerPlugin<ShopLedgerFilesPlugin>('ShopLedgerFiles');

export function isNativeApp(): boolean {
  return Capacitor.isNativePlatform();
}

/** Print or view a PDF: browser tab on the web, Android print dialog in the app. */
export async function printPdf(blob: Blob, fileName: string): Promise<void> {
  if (isNativeApp()) {
    await ShopLedgerFiles.printPdf({ data: await toBase64(blob), fileName, title: stripExtension(fileName) });
    return;
  }
  const url = URL.createObjectURL(blob);
  const win = window.open(url, '_blank');
  if (!win) {
    triggerDownload(url, fileName);
  }
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Share a file (e.g. send the bill PDF on WhatsApp). Falls back to download on desktop browsers. */
export async function shareFile(blob: Blob, fileName: string, mimeType = 'application/pdf'): Promise<void> {
  if (isNativeApp()) {
    await ShopLedgerFiles.share({ data: await toBase64(blob), fileName, mimeType, title: stripExtension(fileName) });
    return;
  }
  const file = new File([blob], fileName, { type: mimeType });
  if (typeof navigator.canShare === 'function' && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: stripExtension(fileName) });
      return;
    } catch (err) {
      if ((err as DOMException)?.name === 'AbortError') return;
    }
  }
  saveFileWeb(blob, fileName);
}

/** Save an export (CSV etc.): browser download, or the share sheet in the app (save to Files/Drive/WhatsApp). */
export async function saveFile(blob: Blob, fileName: string, mimeType: string): Promise<void> {
  if (isNativeApp()) {
    await ShopLedgerFiles.share({ data: await toBase64(blob), fileName, mimeType, title: fileName });
    return;
  }
  saveFileWeb(blob, fileName);
}

function saveFileWeb(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  triggerDownload(url, fileName);
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

function triggerDownload(url: string, fileName: string): void {
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
}

function stripExtension(fileName: string): string {
  return fileName.replace(/\.[^.]+$/, '');
}

function toBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result ?? '');
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('Could not read file'));
    reader.readAsDataURL(blob);
  });
}
