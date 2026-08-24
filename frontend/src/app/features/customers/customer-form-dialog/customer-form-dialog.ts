import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/models/api-response.model';
import { applyServerErrors, serverError } from '../../../shared/forms/server-errors';
import { Customer } from '../customer.model';
import { CustomerService } from '../customer.service';

/** Indian mobile number, optionally prefixed with +91 / 91 / 0 — e.g. 9876543210, +91 9876543210. */
const MOBILE_PATTERN = /^(\+91[\-\s]?|0)?[6-9]\d{9}$/;
/** Mirrors backend's CreateCustomerRequest.gstin @Pattern exactly. */
const GSTIN_PATTERN = /^[0-9A-Z]{15}$/;

export interface CustomerFormDialogData {
  /** Present for edit, absent for create. */
  customer?: Customer;
}

@Component({
  selector: 'app-customer-form-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './customer-form-dialog.html',
})
export class CustomerFormDialog {
  private readonly fb = inject(FormBuilder);
  private readonly customerService = inject(CustomerService);
  private readonly dialogRef = inject(MatDialogRef<CustomerFormDialog>);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly data = inject<CustomerFormDialogData>(MAT_DIALOG_DATA);

  protected readonly isEdit = !!this.data.customer;

  protected readonly form = this.fb.nonNullable.group({
    name: [this.data.customer?.name ?? '', [Validators.required, Validators.maxLength(255)]],
    email: [this.data.customer?.email ?? '', [Validators.email, Validators.maxLength(255)]],
    phone: [this.data.customer?.phone ?? '', [Validators.pattern(MOBILE_PATTERN)]],
    address: [this.data.customer?.address ?? ''],
    gstin: [this.data.customer?.gstin ?? '', [Validators.pattern(GSTIN_PATTERN)]],
  });

  protected readonly submitting = signal(false);
  protected readonly serverError = serverError;

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request = {
      name: raw.name,
      email: raw.email || null,
      phone: raw.phone || null,
      address: raw.address || null,
      gstin: raw.gstin ? raw.gstin.toUpperCase() : null,
    };

    this.submitting.set(true);
    const save$ = this.isEdit
      ? this.customerService.update(this.data.customer!.id, request)
      : this.customerService.create(request);

    save$.subscribe({
      next: (customer) => {
        this.submitting.set(false);
        this.dialogRef.close(customer);
      },
      error: (err: ApiError) => {
        this.submitting.set(false);
        applyServerErrors(this.form, err.fieldErrors);
        this.snackBar.open(err.message, 'Dismiss', { duration: 5000 });
      },
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
