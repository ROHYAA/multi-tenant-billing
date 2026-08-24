import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../../core/models/api-response.model';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { applyServerErrors, serverError } from '../../../shared/forms/server-errors';
import { BillTemplate, BusinessType, PaperSize } from '../shop-settings.model';
import { ShopSettingsService } from '../shop-settings.service';

const BUSINESS_TYPES: { value: BusinessType; label: string }[] = [
  { value: 'RETAIL', label: 'Retail' },
  { value: 'WHOLESALE', label: 'Wholesale' },
  { value: 'SERVICE', label: 'Service' },
  { value: 'RESTAURANT', label: 'Restaurant' },
  { value: 'PHARMACY', label: 'Pharmacy' },
  { value: 'OTHER', label: 'Other' },
];

const PAPER_SIZES: { value: PaperSize; label: string }[] = [
  { value: 'A4', label: 'A4' },
  { value: 'THERMAL_58MM', label: 'Thermal 58mm' },
  { value: 'THERMAL_80MM', label: 'Thermal 80mm' },
];

@Component({
  selector: 'app-shop-settings-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTabsModule,
    PageHeader,
  ],
  templateUrl: './shop-settings-page.html',
})
export class ShopSettingsPage {
  private readonly fb = inject(FormBuilder);
  private readonly settingsService = inject(ShopSettingsService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly businessTypes = BUSINESS_TYPES;
  protected readonly paperSizes = PAPER_SIZES;
  protected readonly billTemplates = signal<BillTemplate[]>([]);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly serverError = serverError;

  protected readonly logoUrl = signal<string | null>(null);
  protected readonly signatureUrl = signal<string | null>(null);
  protected readonly uploadingLogo = signal(false);
  protected readonly uploadingSignature = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    // Business Information
    businessName: ['', Validators.maxLength(255)],
    businessType: ['RETAIL' as BusinessType],
    address: [''],
    city: ['', Validators.maxLength(100)],
    state: ['', Validators.maxLength(100)],
    pincode: ['', Validators.pattern(/^[0-9]{6}$/)],
    mobile: ['', Validators.pattern(/^[+]?[0-9\-\s]{7,15}$/)],
    email: ['', Validators.email],
    gstin: ['', Validators.pattern(/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$/)],
    pan: ['', Validators.pattern(/^[A-Z]{5}[0-9]{4}[A-Z]{1}$/)],
    website: ['', Validators.maxLength(255)],
    upiId: ['', Validators.pattern(/^[\w.-]{2,49}@[\w]{2,49}$/)],
    watermarkText: ['', Validators.maxLength(50)],
    logoAttachmentId: [null as number | null],
    signatureAttachmentId: [null as number | null],

    // Bank Details
    bankName: ['', Validators.maxLength(255)],
    bankAccountNo: ['', Validators.maxLength(30)],
    bankIfsc: ['', Validators.pattern(/^[A-Z]{4}0[A-Z0-9]{6}$/)],
    bankBranch: ['', Validators.maxLength(255)],

    // Invoice & Regional Settings
    currency: ['INR', [Validators.required, Validators.minLength(3), Validators.maxLength(3)]],
    currencySymbol: ['₹', [Validators.required, Validators.maxLength(5)]],
    decimalPrecision: [2, [Validators.required, Validators.min(0), Validators.max(4)]],
    timezone: ['Asia/Kolkata', Validators.maxLength(50)],
    language: ['en-IN', Validators.maxLength(10)],
    dateFormat: ['dd/MM/yyyy', Validators.maxLength(20)],

    // Bill Settings
    paperSize: ['A4' as PaperSize, Validators.required],
    billTemplateId: [null as number | null, Validators.required],
    showLogo: [true],
    showGst: [true],
    showQrCode: [false],
    showCustomerAddress: [true],
    showAmountInWords: [true],
    showSignature: [false],

    // Footer Settings
    termsAndConditions: [''],
    warrantyText: [''],
    footerMessage: ['', Validators.maxLength(500)],

    // Printer Settings
    thermalWidth: [null as number | null],
    margin: [5, [Validators.required, Validators.min(0), Validators.max(50)]],
    fontSize: [10, [Validators.required, Validators.min(6), Validators.max(72)]],
  });

  constructor() {
    forkJoin({
      settings: this.settingsService.getSettings(),
      templates: this.settingsService.listBillTemplates(),
    }).subscribe({
      next: ({ settings, templates }) => {
        this.billTemplates.set(templates);
        this.form.patchValue(settings);
        this.logoUrl.set(this.settingsService.resolveAttachmentUrl(settings.logoUrl));
        this.signatureUrl.set(this.settingsService.resolveAttachmentUrl(settings.signatureUrl));
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loading.set(false);
        this.error.set(err.message || 'Failed to load shop settings.');
      },
    });
  }

  protected get isThermal(): boolean {
    return this.form.controls.paperSize.value !== 'A4';
  }

  onLogoSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;

    this.uploadingLogo.set(true);
    this.settingsService.uploadAttachment('LOGO', file).subscribe({
      next: (attachment) => {
        this.uploadingLogo.set(false);
        this.form.controls.logoAttachmentId.setValue(attachment.id);
        this.form.controls.showLogo.setValue(true);
        this.logoUrl.set(this.settingsService.resolveAttachmentUrl(attachment.url));
      },
      error: (err: ApiError) => {
        this.uploadingLogo.set(false);
        this.snackBar.open(err.message || 'Logo upload failed.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  onSignatureSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;

    this.uploadingSignature.set(true);
    this.settingsService.uploadAttachment('SIGNATURE', file).subscribe({
      next: (attachment) => {
        this.uploadingSignature.set(false);
        this.form.controls.signatureAttachmentId.setValue(attachment.id);
        this.form.controls.showSignature.setValue(true);
        this.signatureUrl.set(this.settingsService.resolveAttachmentUrl(attachment.url));
      },
      error: (err: ApiError) => {
        this.uploadingSignature.set(false);
        this.snackBar.open(err.message || 'Signature upload failed.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.snackBar.open('Please fix the highlighted fields before saving.', 'Dismiss', { duration: 4000 });
      return;
    }

    const raw = this.form.getRawValue();
    const request = {
      ...raw,
      address: raw.address || undefined,
      email: raw.email || undefined,
      gstin: raw.gstin || undefined,
      pan: raw.pan || undefined,
      website: raw.website || undefined,
      upiId: raw.upiId || undefined,
      watermarkText: raw.watermarkText || undefined,
      bankName: raw.bankName || undefined,
      bankAccountNo: raw.bankAccountNo || undefined,
      bankIfsc: raw.bankIfsc || undefined,
      bankBranch: raw.bankBranch || undefined,
      termsAndConditions: raw.termsAndConditions || undefined,
      warrantyText: raw.warrantyText || undefined,
      footerMessage: raw.footerMessage || undefined,
      thermalWidth: this.isThermal ? (raw.thermalWidth ?? undefined) : undefined,
      billTemplateId: raw.billTemplateId ?? undefined,
    };

    this.saving.set(true);
    this.settingsService.updateSettings(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.snackBar.open('Shop settings saved successfully', 'Dismiss', { duration: 4000 });
      },
      error: (err: ApiError) => {
        this.saving.set(false);
        applyServerErrors(this.form, err.fieldErrors);
        this.snackBar.open(err.message || 'Failed to save shop settings.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
