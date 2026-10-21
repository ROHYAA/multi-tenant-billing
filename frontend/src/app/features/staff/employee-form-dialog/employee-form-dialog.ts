import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/models/api-response.model';
import { applyServerErrors, serverError } from '../../../shared/forms/server-errors';
import { StaffService } from '../staff.service';

@Component({
  selector: 'app-employee-form-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './employee-form-dialog.html',
})
export class EmployeeFormDialog {
  private readonly fb = inject(FormBuilder);
  private readonly staffService = inject(StaffService);
  private readonly dialogRef = inject(MatDialogRef<EmployeeFormDialog>);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly hidePassword = signal(true);
  protected readonly submitting = signal(false);
  protected readonly serverError = serverError;

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { name, email, password } = this.form.getRawValue();
    this.staffService.createEmployee({ name, email, password, roleName: 'EMPLOYEE' }).subscribe({
      next: (user) => this.dialogRef.close(user),
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
