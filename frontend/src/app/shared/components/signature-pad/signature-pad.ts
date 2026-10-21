import { AfterViewInit, Component, ElementRef, input, output, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Finger/mouse signature box. Emits `inkChange` when it goes from empty to signed
 * (or back after Clear); call toBlob() to get a PNG of what was drawn.
 */
@Component({
  selector: 'app-signature-pad',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="flex flex-col gap-1">
      <canvas
        #canvas
        class="h-40 w-full touch-none rounded-lg border bg-white"
        style="border-color: var(--mat-sys-outline-variant)"
        (pointerdown)="start($event)"
        (pointermove)="move($event)"
        (pointerup)="end()"
        (pointerleave)="end()"
        (pointercancel)="end()"
      ></canvas>
      <div class="flex items-center justify-between">
        <span class="text-xs opacity-70">{{ hint() }}</span>
        <button mat-button type="button" [disabled]="!hasInk()" (click)="clear()">
          <mat-icon>backspace</mat-icon>
          Clear
        </button>
      </div>
    </div>
  `,
})
export class SignaturePad implements AfterViewInit {
  readonly hint = input('Sign inside the box');
  readonly inkChange = output<boolean>();

  protected readonly hasInk = signal(false);
  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private ctx: CanvasRenderingContext2D | null = null;
  private drawing = false;

  ngAfterViewInit(): void {
    const el = this.canvas().nativeElement;
    const ratio = window.devicePixelRatio || 1;
    el.width = Math.max(1, Math.round(el.clientWidth * ratio));
    el.height = Math.max(1, Math.round(el.clientHeight * ratio));
    this.ctx = el.getContext('2d');
    if (this.ctx) {
      this.ctx.scale(ratio, ratio);
      this.ctx.lineWidth = 2.4;
      this.ctx.lineCap = 'round';
      this.ctx.lineJoin = 'round';
      this.ctx.strokeStyle = '#13285a';
    }
  }

  protected start(event: PointerEvent): void {
    if (!this.ctx) return;
    this.canvas().nativeElement.setPointerCapture(event.pointerId);
    this.drawing = true;
    const { x, y } = this.point(event);
    this.ctx.beginPath();
    this.ctx.moveTo(x, y);
  }

  protected move(event: PointerEvent): void {
    if (!this.drawing || !this.ctx) return;
    const { x, y } = this.point(event);
    this.ctx.lineTo(x, y);
    this.ctx.stroke();
    if (!this.hasInk()) {
      this.hasInk.set(true);
      this.inkChange.emit(true);
    }
  }

  protected end(): void {
    this.drawing = false;
  }

  clear(): void {
    const el = this.canvas().nativeElement;
    this.ctx?.clearRect(0, 0, el.width, el.height);
    if (this.hasInk()) {
      this.hasInk.set(false);
      this.inkChange.emit(false);
    }
  }

  /** PNG of the signature on a white background, or null if nothing was drawn. */
  toBlob(): Promise<Blob | null> {
    if (!this.hasInk()) return Promise.resolve(null);
    const source = this.canvas().nativeElement;
    const out = document.createElement('canvas');
    out.width = source.width;
    out.height = source.height;
    const ctx = out.getContext('2d');
    if (!ctx) return Promise.resolve(null);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, out.width, out.height);
    ctx.drawImage(source, 0, 0);
    return new Promise((resolve) => out.toBlob(resolve, 'image/png'));
  }

  private point(event: PointerEvent): { x: number; y: number } {
    const rect = this.canvas().nativeElement.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }
}
