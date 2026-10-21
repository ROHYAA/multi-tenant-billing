package com.shopledger.app;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import com.getcapacitor.BridgeActivity;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

public class MainActivity extends BridgeActivity {
    private AdView adView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShopLedgerFilesPlugin.class);
        super.onCreate(savedInstanceState);
        MobileAds.initialize(this, status -> {});
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.admob_banner_id));
        adView.setAdSize(AdSize.BANNER);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        addContentView(adView, params);
        adView.loadAd(new AdRequest.Builder().build());
    }

    @Override
    public void onPause() {
        if (adView != null) {
            adView.pause();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    public void onDestroy() {
        if (adView != null) {
            adView.destroy();
        }
        super.onDestroy();
    }
}
