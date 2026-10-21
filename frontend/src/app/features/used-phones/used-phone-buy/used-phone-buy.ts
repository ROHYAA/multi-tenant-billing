import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, DestroyRef, OnDestroy, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, debounceTime, distinctUntilChanged, firstValueFrom, of, switchMap } from 'rxjs';
import { ApiError } from '../../../core/models/api-response.model';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { SignaturePad } from '../../../shared/components/signature-pad/signature-pad';
import { applyServerErrors, serverError } from '../../../shared/forms/server-errors';
import { printPdf, shareFile } from '../../../shared/utils/file-output';
import { compressImage } from '../../../shared/utils/image-compress';
import {
  ACCESSORY_OPTIONS,
  BuyPaymentMethod,
  CONDITION_OPTIONS,
  ConditionGrade,
  CreateUsedPhoneRequest,
  ID_TYPE_OPTIONS,
  ImeiHistory,
  SellerIdType,
  UsedPhonePhotoPurpose,
  UsedPhonePurchase,
  isValidImei,
} from '../used-phone.model';
import { UsedPhoneService } from '../used-phone.service';

type PhotoPurpose = Exclude<UsedPhonePhotoPurpose, 'SELLER_SIGNATURE'>;

interface PhotoSlot {
  purpose: PhotoPurpose;
  label: string;
  icon: string;
  required: boolean;
  preview: string | null;
  attachmentId: number | null;
  uploading: boolean;
}

const EMPTY_PHOTOS: PhotoSlot[] = [
  { purpose: 'ID_PROOF', label: 'Seller ID proof', icon: 'badge', required: true, preview: null, attachmentId: null, uploading: false },
  { purpose: 'SELLER_PHOTO', label: 'Seller photo', icon: 'person', required: false, preview: null, attachmentId: null, uploading: false },
  { purpose: 'PHONE_PHOTO', label: 'Phone photo', icon: 'smartphone', required: false, preview: null, attachmentId: null, uploading: false },
];

const PAYMENT_OPTIONS: { value: BuyPaymentMethod; label: string }[] = [
  { value: 'CASH', label: 'Cash' },
  { value: 'UPI', label: 'UPI' },
  { value: 'BANK_TRANSFER', label: 'Bank transfer' },
  { value: 'CARD', label: 'Card' },
];

function imeiValidator(control: AbstractControl<string>): ValidationErrors | null {
  const value = (control.value ?? '').trim();
  if (!value) return null;
  return isValidImei(value) ? null : { imei: true };
}

@Component({
  selector: 'app-used-phone-buy',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    CurrencyPipe,
    DatePipe,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    PageHeader,
    SignaturePad,
  ],
  templateUrl: './used-phone-buy.html',
})
export class UsedPhoneBuy implements OnDestroy {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly usedPhones = inject(UsedPhoneService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly signaturePad = viewChild(SignaturePad);

  protected readonly idTypes = ID_TYPE_OPTIONS;
  protected readonly conditions = CONDITION_OPTIONS;
  protected readonly accessoryOptions = ACCESSORY_OPTIONS;
  protected readonly paymentOptions = PAYMENT_OPTIONS;
  protected readonly serverError = serverError;

  protected readonly form = this.fb.group({
    sellerName: ['', [Validators.required, Validators.maxLength(255)]],
    sellerMobile: ['', [Validators.required, Validators.pattern(/^(\+?91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}$/)]],
    sellerAddress: [''],
    sellerIdType: this.fb.control<SellerIdType>('AADHAAR', Validators.required),
    sellerIdNumber: ['', [Validators.required, Validators.maxLength(40)]],
    brand: ['', [Validators.required, Validators.maxLength(60)]],
    model: ['', [Validators.required, Validators.maxLength(120)]],
    color: [''],
    storage: [''],
    imei1: ['', [Validators.required, imeiValidator]],
    imei2: ['', imeiValidator],
    serialNumber: [''],
    conditionGrade: this.fb.control<ConditionGrade>('GOOD'),
    batteryHealth: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(100)]),
    accessories: this.fb.control<string[]>([]),
    hasOriginalBill: [false],
    ceirChecked: [false],
    purchasePrice: this.fb.control<number | null>(null, [Validators.required, Validators.min(0)]),
    sellingPrice: this.fb.control<number | null>(null, Validators.min(0)),
    taxPercentage: this.fb.control<number>(0, [Validators.min(0), Validators.max(28)]),
    paymentMethod: this.fb.control<BuyPaymentMethod>('CASH'),
    notes: [''],
    declarationAccepted: [false, Validators.requiredTrue],
  });

  protected readonly photos = signal<PhotoSlot[]>(EMPTY_PHOTOS.map((p) => ({ ...p })));
  protected readonly saving = signal(false);
  protected readonly saved = signal<UsedPhonePurchase | null>(null);
  protected readonly printing = signal(false);
  protected readonly imeiHistory = signal<ImeiHistory | null>(null);
  protected readonly checkingImei = signal(false);

  private readonly idType = toSignal(this.form.controls.sellerIdType.valueChanges, {
    initialValue: this.form.controls.sellerIdType.value,
  });
  protected readonly idHint = computed(() => this.idTypes.find((t) => t.value === this.idType())?.hint ?? '');

  private readonly prices = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  protected readonly expectedMargin = computed(() => {
    const { purchasePrice, sellingPrice } = this.prices();
    if (purchasePrice == null || sellingPrice == null) return null;
    return Number(sellingPrice) - Number(purchasePrice);
  });

  constructor() {
    this.form.controls.imei1.valueChanges
      .pipe(
        debounceTime(350),
        distinctUntilChanged(),
        switchMap((value) => {
          const imei = (value ?? '').replace(/[\s-]/g, '');
          if (!isValidImei(imei)) {
            this.imeiHistory.set(null);
            return of(null);
          }
          this.checkingImei.set(true);
          return this.usedPhones.imeiHistory(imei).pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((history) => {
        this.checkingImei.set(false);
        if (history) this.imeiHistory.set(history);
      });
  }

  ngOnDestroy(): void {
    this.revokePreviews();
  }

  // ── Photos ────────────────────────────────────────────────────────────────

  async onPhotoSelected(purpose: PhotoPurpose, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const original = input.files?.[0];
    input.value = '';
    if (!original) return;

    const file = await compressImage(original);
    const previous = this.slot(purpose)?.preview;
    if (previous) URL.revokeObjectURL(previous);
    this.patchSlot(purpose, { preview: URL.createObjectURL(file), uploading: true, attachmentId: null });

    this.usedPhones.uploadPhoto(purpose, file, file.name).subscribe({
      next: (photo) => this.patchSlot(purpose, { attachmentId: photo.id, uploading: false }),
      error: (err: ApiError) => {
        this.patchSlot(purpose, { uploading: false, preview: null });
        this.snackBar.open(err.message || 'Photo upload failed.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  removePhoto(purpose: PhotoPurpose): void {
    const previous = this.slot(purpose)?.preview;
    if (previous) URL.revokeObjectURL(previous);
    this.patchSlot(purpose, { preview: null, attachmentId: null, uploading: false });
  }

  // ── Accessories ───────────────────────────────────────────────────────────

  toggleAccessory(name: string, checked: boolean): void {
    const current = this.form.controls.accessories.value;
    this.form.controls.accessories.setValue(
      checked ? [...new Set([...current, name])] : current.filter((a) => a !== name),
    );
  }

  hasAccessory(name: string): boolean {
    return this.form.controls.accessories.value.includes(name);
  }

  // ── Save ──────────────────────────────────────────────────────────────────

  async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.snackBar.open('Please fill the required fields marked in red.', 'Dismiss', { duration: 5000 });
      return;
    }
    const slots = this.photos();
    if (slots.some((s) => s.uploading)) {
      this.snackBar.open('Wait for the photos to finish uploading.', 'Dismiss', { duration: 4000 });
      return;
    }
    const idProof = slots.find((s) => s.purpose === 'ID_PROOF');
    if (!idProof?.attachmentId) {
      this.snackBar.open('Add a photo of the seller’s ID proof — it is required for your records.', 'Dismiss', {
        duration: 6000,
      });
      return;
    }
    if (this.imeiHistory()?.inStock) {
      this.snackBar.open('This IMEI is already in your stock. Check the number on the phone (*#06#).', 'Dismiss', {
        duration: 6000,
      });
      return;
    }

    this.saving.set(true);
    let signatureId: number | null = null;
    try {
      const blob = await this.signaturePad()?.toBlob();
      if (blob) {
        signatureId = (await firstValueFrom(this.usedPhones.uploadPhoto('SELLER_SIGNATURE', blob, 'signature.png'))).id;
      }
    } catch {
      this.saving.set(false);
      this.snackBar.open('Could not save the seller signature. Try again.', 'Dismiss', { duration: 6000 });
      return;
    }

    const v = this.form.getRawValue();
    const photoId = (purpose: PhotoPurpose) => slots.find((s) => s.purpose === purpose)?.attachmentId ?? null;
    const request: CreateUsedPhoneRequest = {
      sellerName: v.sellerName.trim(),
      sellerMobile: v.sellerMobile.trim(),
      sellerAddress: v.sellerAddress.trim() || null,
      sellerIdType: v.sellerIdType,
      sellerIdNumber: v.sellerIdNumber.trim(),
      idProofAttachmentId: idProof.attachmentId,
      sellerPhotoAttachmentId: photoId('SELLER_PHOTO'),
      phonePhotoAttachmentId: photoId('PHONE_PHOTO'),
      sellerSignatureAttachmentId: signatureId,
      brand: v.brand.trim(),
      model: v.model.trim(),
      color: v.color.trim() || null,
      storage: v.storage.trim() || null,
      imei1: v.imei1.replace(/[\s-]/g, ''),
      imei2: v.imei2.replace(/[\s-]/g, '') || null,
      serialNumber: v.serialNumber.trim() || null,
      conditionGrade: v.conditionGrade,
      batteryHealth: v.batteryHealth,
      accessories: v.accessories,
      hasOriginalBill: v.hasOriginalBill,
      ceirChecked: v.ceirChecked,
      declarationAccepted: v.declarationAccepted,
      purchasePrice: Number(v.purchasePrice),
      sellingPrice: v.sellingPrice == null ? null : Number(v.sellingPrice),
      taxPercentage: Number(v.taxPercentage || 0),
      paymentMethod: v.paymentMethod,
      notes: v.notes.trim() || null,
    };

    this.usedPhones.create(request).subscribe({
      next: (purchase) => {
        this.saving.set(false);
        this.saved.set(purchase);
        this.snackBar.open(`Voucher ${purchase.voucherNumber} saved — phone added to stock.`, 'Dismiss', {
          duration: 5000,
        });
      },
      error: (err: ApiError) => {
        this.saving.set(false);
        applyServerErrors(this.form, err.fieldErrors);
        this.snackBar.open(err.message || 'Could not save the purchase.', 'Dismiss', { duration: 7000 });
      },
    });
  }

  printVoucher(): void {
    this.withVoucher((blob, fileName) => printPdf(blob, fileName));
  }

  shareVoucher(): void {
    this.withVoucher((blob, fileName) => shareFile(blob, fileName));
  }

  private withVoucher(action: (blob: Blob, fileName: string) => Promise<void>): void {
    const purchase = this.saved();
    if (!purchase) return;
    this.printing.set(true);
    this.usedPhones.voucherPdf(purchase.id).subscribe({
      next: (blob) => {
        action(blob, `${purchase.voucherNumber}.pdf`)
          .catch((err: unknown) =>
            this.snackBar.open((err as Error)?.message || 'Could not open the voucher.', 'Dismiss', { duration: 6000 }),
          )
          .finally(() => this.printing.set(false));
      },
      error: (err: ApiError) => {
        this.printing.set(false);
        this.snackBar.open(err.message || 'Failed to generate the voucher.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  buyAnother(): void {
    this.revokePreviews();
    this.photos.set(EMPTY_PHOTOS.map((p) => ({ ...p })));
    this.form.reset();
    this.signaturePad()?.clear();
    this.imeiHistory.set(null);
    this.saved.set(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  private slot(purpose: PhotoPurpose): PhotoSlot | undefined {
    return this.photos().find((s) => s.purpose === purpose);
  }

  private patchSlot(purpose: PhotoPurpose, patch: Partial<PhotoSlot>): void {
    this.photos.update((slots) => slots.map((s) => (s.purpose === purpose ? { ...s, ...patch } : s)));
  }

  private revokePreviews(): void {
    for (const slot of this.photos()) {
      if (slot.preview) URL.revokeObjectURL(slot.preview);
    }
  }
}
