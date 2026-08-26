import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/models/api-response.model';
import { PaymentMethod } from '../../billing/bill.model';
import { CustomerOutstanding, CustomerPaymentResult } from '../payment.model';
import { PaymentService } from '../payment.service';

export interface RecordCustomerPaymentDialogData {
  customerId: number;
  customerName: string;
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
  selector: 'app-record-customer-payment-dialog',
  imports: [
    ReactiveFormsModule,
    CurrencyPipe,
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  providers: [provideNativeDateAdapter()],
  templateUrl: './record-customer-payment-dialog.html',
})
export class RecordCustomerPaymentDialog {
  private readonly fb = inject(FormBuilder);
  private readonly paymentService = inject(PaymentService);
  private readonly dialogRef = inject(MatDialogRef<RecordCustomerPaymentDialog>);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly data = inject<RecordCustomerPaymentDialogData>(MAT_DIALOG_DATA);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly loadingOutstanding = signal(true);
  protected readonly outstanding = signal<CustomerOutstanding | null>(null);
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    amount: [0, [Validators.required, Validators.min(0.01)]],
    method: ['CASH' as PaymentMethod, Validators.required],
    notes: [''],
    paidAt: [new Date()],
  });

  constructor() {
    this.paymentService.getCustomerOutstanding(this.data.customerId).subscribe({
      next: (result) => {
        this.outstanding.set(result);
        this.form.controls.amount.setValue(result.totalOutstanding);
        this.loadingOutstanding.set(false);
      },
      error: () => this.loadingOutstanding.set(false),
    });
  }

  submit(): void {
    const outstanding = this.outstanding();
    if (!outstanding || outstanding.bills.length === 0) return;

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    if (raw.amount > outstanding.totalOutstanding) {
      this.snackBar.open(
        `Amount cannot exceed this customer's total outstanding balance of ${outstanding.totalOutstanding}.`,
        'Dismiss',
        { duration: 5000 },
      );
      return;
    }

    this.submitting.set(true);
    this.paymentService
      .recordForCustomer(this.data.customerId, {
        amount: raw.amount,
        method: raw.method,
        notes: raw.notes || null,
        paidAt: raw.paidAt.toISOString(),
      })
      .subscribe({
        next: (result: CustomerPaymentResult) => {
          this.submitting.set(false);
          this.dialogRef.close(result);
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
