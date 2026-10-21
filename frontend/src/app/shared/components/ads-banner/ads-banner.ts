import { afterNextRender, Component } from '@angular/core';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-ads-banner',
  templateUrl: './ads-banner.html',
  styleUrl: './ads-banner.scss',
})
export class AdsBanner {
  protected readonly nativeClient = environment.nativeClient;
  protected readonly adsenseClient = environment.adsenseClient;
  protected readonly adsenseSlot = environment.adsenseSlot;
  protected readonly showAdsense = Boolean(this.adsenseClient && this.adsenseSlot && !this.nativeClient);

  constructor() {
    afterNextRender(() => this.loadAdsense());
  }

  private loadAdsense(): void {
    if (!this.showAdsense) {
      return;
    }
    const w = window as Window & { adsbygoogle?: unknown[] };
    const src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${this.adsenseClient}`;
    if (!document.querySelector(`script[src="${src}"]`)) {
      const script = document.createElement('script');
      script.async = true;
      script.src = src;
      script.crossOrigin = 'anonymous';
      document.head.appendChild(script);
    }
    w.adsbygoogle = w.adsbygoogle || [];
    w.adsbygoogle.push({});
  }
}
