import { AbstractControl, UntypedFormGroup } from '@angular/forms';

/**
 * Maps ApiError.fieldErrors (backend bean-validation failures) onto the
 * matching reactive form controls so <mat-error> can display them exactly
 * like a client-side validation error. Every form in the app uses this
 * instead of hand-rolling its own field-error wiring.
 *
 * Usage in a submit handler's error callback:
 *   catchError((err: ApiError) => { applyServerErrors(form, err.fieldErrors); throw err; })
 * Template:
 *   <mat-error>{{ serverError(form.controls.email) }}</mat-error>
 */
export function applyServerErrors(form: UntypedFormGroup, fieldErrors: Record<string, string> | undefined): void {
  if (!fieldErrors) return;
  for (const [field, message] of Object.entries(fieldErrors)) {
    const control = form.get(field);
    if (control) {
      control.setErrors({ ...control.errors, server: message });
    }
  }
}

export function serverError(control: AbstractControl | null): string | null {
  return (control?.errors?.['server'] as string | undefined) ?? null;
}
