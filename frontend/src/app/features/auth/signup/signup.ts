import { Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, of } from 'rxjs';
import { ApiError } from '../../../core/models/api-response.model';
import { AuthService } from '../../../core/auth/auth';
import { applyServerErrors, serverError } from '../../../shared/forms/server-errors';
import { BusinessTypeOption } from '../../settings/shop-settings.model';
import { ShopSettingsService } from '../../settings/shop-settings.service';
import { PayNote } from '../../../shared/components/pay-note/pay-note';

@Component({
  selector: 'app-signup',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    PayNote,
  ],
  templateUrl: './signup.html',
  styleUrl: '../auth-page.scss',
})
export class Signup {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly settingsService = inject(ShopSettingsService);

  protected readonly businessTypes = signal<BusinessTypeOption[]>([]);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    businessType: [''],
  });

  protected readonly submitting = signal(false);
  protected readonly hidePassword = signal(true);
  protected readonly serverError = serverError;

  constructor() {
    this.settingsService.listBusinessTypes().pipe(catchError(() => of([] as BusinessTypeOption[]))).subscribe({
      next: (types) => this.businessTypes.set(types),
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    const { name, email, password, businessType } = this.form.getRawValue();
    const payload = businessType ? { name, email, password, businessType } : { name, email, password };
    this.authService.signup(payload).subscribe({
      next: () => void this.router.navigateByUrl('/dashboard'),
      error: (err: ApiError) => {
        this.submitting.set(false);
        applyServerErrors(this.form, err.fieldErrors);
        this.snackBar.open(err.message, 'Dismiss', { duration: 5000 });
      },
    });
  }
}
