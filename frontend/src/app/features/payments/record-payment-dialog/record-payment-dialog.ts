import { CurrencyPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/models/api-response.model';
import { PaymentMethod } from '../../billing/bill.model';
import { Payment } from '../payment.model';
import { PaymentService } from '../payment.service';

export interface RecordPaymentDialogData {
  invoiceId: number;
  invoiceNumber: string;
}

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'CASH', label: 'Cash' },
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Card' },
  { value: 'NETBANKING', label: 'NetBanking' },
  { value: 'BANK_TRANSFER', label: 'Bank Transfer' },
  { value: 'CREDIT', label: 'Credit' },
];

@Component({
  selector: 'app-record-payment-dialog',
  imports: [
    ReactiveFormsModule,
    CurrencyPipe,
    MatButtonModule,
    MatChipsModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  providers: [provideNativeDateAdapter()],
  templateUrl: './record-payment-dialog.html',
})
export class RecordPaymentDialog {
  private readonly fb = inject(FormBuilder);
  private readonly paymentService = inject(PaymentService);
  private readonly dialogRef = inject(MatDialogRef<RecordPaymentDialog>);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly data = inject<RecordPaymentDialogData>(MAT_DIALOG_DATA);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly loadingOutstanding = signal(true);
  protected readonly outstandingBalance = signal(0);
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    amount: [0, [Validators.required, Validators.min(0.01)]],
    method: ['CASH' as PaymentMethod, Validators.required],
    notes: [''],
    paidAt: [new Date()],
  });

  constructor() {
    this.paymentService.getOutstandingBalance(this.data.invoiceId).subscribe({
      next: (balance) => {
        this.outstandingBalance.set(balance);
        this.form.controls.amount.setValue(balance);
        this.loadingOutstanding.set(false);
      },
      error: () => this.loadingOutstanding.set(false),
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    if (raw.amount > this.outstandingBalance()) {
      this.snackBar.open(
        `Amount cannot exceed the outstanding balance of ${this.outstandingBalance()}.`,
        'Dismiss',
        { duration: 5000 },
      );
      return;
    }

    this.submitting.set(true);
    this.paymentService
      .record(this.data.invoiceId, {
        amount: raw.amount,
        method: raw.method,
        notes: raw.notes || null,
        paidAt: raw.paidAt.toISOString(),
      })
      .subscribe({
        next: (payment: Payment) => {
          this.submitting.set(false);
          this.dialogRef.close(payment);
        },
        error: (err: ApiError) => {
          this.submitting.set(false);
          this.snackBar.open(err.message || 'Failed to record payment.', 'Dismiss', { duration: 6000 });
        },
      });
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
