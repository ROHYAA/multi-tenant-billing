import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ApproveTenantRequest } from '../admin-tenant.model';

export interface PlanEntryDialogData {
  title: string;
  message: string;
  confirmLabel: string;
  /** Pre-fills the form when re-approving/reactivating a shop that already had a plan. */
  initialPlanName?: string | null;
  initialExpiresAt?: string | null;
}

/** Tomorrow at local midnight — matches the backend's @Future validation on subscriptionExpiresAt. */
function tomorrow(): Date {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(0, 0, 0, 0);
  return date;
}

@Component({
  selector: 'app-plan-entry-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  providers: [provideNativeDateAdapter()],
  templateUrl: './plan-entry-dialog.html',
})
export class PlanEntryDialog {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<PlanEntryDialog>);
  protected readonly data = inject<PlanEntryDialogData>(MAT_DIALOG_DATA);

  protected readonly minDate = tomorrow();
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    planName: [this.data.initialPlanName ?? '', [Validators.required, Validators.maxLength(100)]],
    expiresAt: [
      this.data.initialExpiresAt ? new Date(this.data.initialExpiresAt) : this.minDate,
      Validators.required,
    ],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: ApproveTenantRequest = {
      planName: raw.planName,
      subscriptionExpiresAt: raw.expiresAt.toISOString(),
    };
    this.dialogRef.close(request);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
