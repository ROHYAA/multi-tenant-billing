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
import { Product } from '../product.model';
import { ProductService } from '../product.service';

export interface ProductFormDialogData {
  /** Present for edit, absent for create. */
  product?: Product;
}

@Component({
  selector: 'app-product-form-dialog',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './product-form-dialog.html',
})
export class ProductFormDialog {
  private readonly fb = inject(FormBuilder);
  private readonly productService = inject(ProductService);
  private readonly dialogRef = inject(MatDialogRef<ProductFormDialog>);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly data = inject<ProductFormDialogData>(MAT_DIALOG_DATA);

  protected readonly isEdit = !!this.data.product;

  protected readonly form = this.fb.nonNullable.group({
    name: [this.data.product?.name ?? '', [Validators.required, Validators.maxLength(255)]],
    description: [this.data.product?.description ?? ''],
    price: [this.data.product?.price ?? 0, [Validators.required, Validators.min(0)]],
    taxPercentage: [this.data.product?.taxPercentage ?? 0, [Validators.min(0)]],
    hsnSacCode: [this.data.product?.hsnSacCode ?? '', [Validators.maxLength(20)]],
    unit: [this.data.product?.unit ?? '', [Validators.maxLength(50)]],
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
      description: raw.description || null,
      price: raw.price,
      taxPercentage: raw.taxPercentage,
      hsnSacCode: raw.hsnSacCode || null,
      unit: raw.unit || null,
    };

    this.submitting.set(true);
    const save$ = this.isEdit
      ? this.productService.update(this.data.product!.id, request)
      : this.productService.create(request);

    save$.subscribe({
      next: (product) => {
        this.submitting.set(false);
        this.dialogRef.close(product);
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
