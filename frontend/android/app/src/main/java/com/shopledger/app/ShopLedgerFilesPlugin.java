package com.shopledger.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PDFs and exports inside the Android app. A WebView cannot open blob: URLs in a new
 * window, so the web layer hands the bytes (base64) to this plugin, which writes them to
 * the app cache and then prints via Android's print system, shares via the share sheet,
 * or opens them in a viewer app.
 */
@CapacitorPlugin(name = "ShopLedgerFiles")
public class ShopLedgerFilesPlugin extends Plugin {

    @PluginMethod
    public void printPdf(PluginCall call) {
        File file = writeToCache(call);
        if (file == null) {
            return;
        }
        String jobName = call.getString("title", file.getName());
        getActivity().runOnUiThread(() -> {
            try {
                PrintManager printManager = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                printManager.print(jobName, new PdfFileAdapter(file, file.getName()), attributesFor(file));
                call.resolve();
            } catch (Exception e) {
                call.reject("Could not start printing: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void share(PluginCall call) {
        File file = writeToCache(call);
        if (file == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(call.getString("mimeType", "application/pdf"));
        send.putExtra(Intent.EXTRA_STREAM, uriFor(file));
        send.putExtra(Intent.EXTRA_SUBJECT, call.getString("title", file.getName()));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            getActivity().startActivity(Intent.createChooser(send, call.getString("title", "Share")));
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("No app available to share this file");
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        File file = writeToCache(call);
        if (file == null) {
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uriFor(file), call.getString("mimeType", "application/pdf"));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            getActivity().startActivity(Intent.createChooser(view, call.getString("title", "Open with")));
            call.resolve();
        } catch (ActivityNotFoundException e) {
            call.reject("NO_VIEWER");
        }
    }

    /**
     * Preselects the paper that matches the PDF's first page: A4 for invoices/vouchers,
     * a custom roll size (58mm / 80mm) for thermal receipts. Falls back to the printer default.
     */
    private PrintAttributes attributesFor(File file) {
        PrintAttributes.Builder builder = new PrintAttributes.Builder();
        try (
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer renderer = new PdfRenderer(fd);
            PdfRenderer.Page page = renderer.openPage(0)
        ) {
            int widthMils = Math.round(page.getWidth() * 1000f / 72f);
            int heightMils = Math.round(page.getHeight() * 1000f / 72f);
            PrintAttributes.MediaSize a4 = PrintAttributes.MediaSize.ISO_A4;
            if (Math.abs(widthMils - a4.getWidthMils()) < 150 && Math.abs(heightMils - a4.getHeightMils()) < 150) {
                builder.setMediaSize(a4);
            } else {
                int widthMm = Math.round(page.getWidth() / 72f * 25.4f);
                builder.setMediaSize(
                    new PrintAttributes.MediaSize("shopledger_" + widthMils + "x" + heightMils, "Receipt " + widthMm + "mm", widthMils, heightMils)
                );
                builder.setMinMargins(PrintAttributes.Margins.NO_MARGINS);
            }
        } catch (Exception ignored) {
            // Unknown size: let the print dialog choose.
        }
        return builder.build();
    }

    private Uri uriFor(File file) {
        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
    }

    private File writeToCache(PluginCall call) {
        String data = call.getString("data");
        String fileName = call.getString("fileName", "document.pdf");
        if (data == null || data.isEmpty()) {
            call.reject("No file data");
            return null;
        }
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(getContext().getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) {
            call.reject("Could not create cache folder");
            return null;
        }
        File file = new File(dir, safeName);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(Base64.decode(data, Base64.DEFAULT));
        } catch (IOException | IllegalArgumentException e) {
            call.reject("Could not save file: " + e.getMessage());
            return null;
        }
        return file;
    }

    /** Streams an existing PDF file to the Android print framework. */
    private static class PdfFileAdapter extends PrintDocumentAdapter {

        private final File file;
        private final String name;

        PdfFileAdapter(File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public void onLayout(
            PrintAttributes oldAttributes,
            PrintAttributes newAttributes,
            CancellationSignal cancellationSignal,
            LayoutResultCallback callback,
            Bundle extras
        ) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(
            PageRange[] pages,
            ParcelFileDescriptor destination,
            CancellationSignal cancellationSignal,
            WriteResultCallback callback
        ) {
            try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (cancellationSignal.isCanceled()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    out.write(buffer, 0, read);
                }
                callback.onWriteFinished(new PageRange[] { PageRange.ALL_PAGES });
            } catch (IOException e) {
                callback.onWriteFailed(e.getMessage());
            }
        }
    }
}
