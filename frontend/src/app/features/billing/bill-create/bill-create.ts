import { CurrencyPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { ApiError } from '../../../core/models/api-response.model';
import { CustomerFormDialog } from '../../customers/customer-form-dialog/customer-form-dialog';
import { Customer } from '../../customers/customer.model';
import { CustomerService } from '../../customers/customer.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { BillDraftStore } from '../bill-draft.store';
import { CreateBillRequest, PaymentMethod, Product } from '../bill.model';
import { BillService } from '../bill.service';

const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'CASH', label: 'Cash' },
  { value: 'UPI', label: 'UPI' },
  { value: 'CARD', label: 'Card' },
  { value: 'CREDIT', label: 'Credit' },
];

@Component({
  selector: 'app-bill-create',
  providers: [BillDraftStore],
  imports: [
    ReactiveFormsModule,
    CurrencyPipe,
    MatAutocompleteModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    PageHeader,
  ],
  templateUrl: './bill-create.html',
})
export class BillCreate {
  private readonly customerService = inject(CustomerService);
  private readonly billService = inject(BillService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly store = inject(BillDraftStore);
  protected readonly paymentMethods = PAYMENT_METHODS;

  // ── Customer search ──────────────────────────────────────────────────────
  protected readonly customerSearch = new FormControl('', { nonNullable: true });
  protected readonly customerResults = signal<Customer[]>([]);

  // ── Product search / item staging row ───────────────────────────────────
  protected readonly productSearch = new FormControl('', { nonNullable: true });
  private readonly allProducts = signal<Product[]>([]);
  private readonly productSearchText = toSignal(this.productSearch.valueChanges, { initialValue: '' });
  protected readonly filteredProducts = computed(() => {
    const term = this.productSearchText().trim().toLowerCase();
    if (!term) return [];
    const products = this.allProducts();
    return products
      .filter((p) => p.name.toLowerCase().includes(term))
      .sort((a, b) => {
        const aStarts = a.name.toLowerCase().startsWith(term) ? 0 : 1;
        const bStarts = b.name.toLowerCase().startsWith(term) ? 0 : 1;
        return aStarts - bStarts;
      })
      .slice(0, 8);
  });

  protected readonly stagingProduct = signal<Product | null>(null);
  protected readonly stagingDescription = signal('');
  protected readonly stagingQty = signal(1);
  protected readonly stagingRate = signal(0);
  protected readonly stagingCgstPercentage = signal(0);
  protected readonly stagingSgstPercentage = signal(0);
  protected readonly stagingAmount = computed(() => round2(this.stagingQty() * this.stagingRate()));

  protected readonly loadingCatalog = signal(true);
  protected readonly submitting = signal(false);
  protected readonly savedBillId = signal<number | null>(null);
  protected readonly printing = signal(false);

  constructor() {
    // Default to the Walk-in Customer so a cash sale needs zero customer interaction.
    this.customerService.getWalkIn().subscribe((customer) => this.store.setCustomer(customer));

    this.billService.listActiveProducts().subscribe({
      next: (products) => {
        this.allProducts.set(products);
        this.loadingCatalog.set(false);
      },
      error: () => this.loadingCatalog.set(false),
    });

    this.customerSearch.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((term) => {
          const trimmed = term.trim();
          if (!trimmed) return of([]);
          return this.customerService.list({ search: trimmed, size: 8 }).pipe(
            catchError(() => of({ content: [] as Customer[] })),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => this.customerResults.set('content' in page ? page.content : page));
  }

  // ── Customer ──────────────────────────────────────────────────────────────

  selectCustomer(customer: Customer): void {
    this.store.setCustomer(customer);
    this.customerSearch.setValue('');
    this.customerResults.set([]);
  }

  clearCustomer(): void {
    this.store.setCustomer(null);
  }

  addNewCustomer(): void {
    this.dialog
      .open(CustomerFormDialog, { width: '480px', data: {} })
      .afterClosed()
      .subscribe((customer: Customer | undefined) => {
        if (customer) this.selectCustomer(customer);
      });
  }

  // ── Item staging ──────────────────────────────────────────────────────────

  onProductSelected(event: MatAutocompleteSelectedEvent): void {
    const product = event.option.value as Product;
    this.stagingProduct.set(product);
    this.stagingDescription.set(product.name);
    this.stagingRate.set(product.price);
    // Catalog products only store one combined tax rate — split evenly as a starting point, editable either way.
    this.stagingCgstPercentage.set(round2(product.taxPercentage / 2));
    this.stagingSgstPercentage.set(round2(product.taxPercentage / 2));
    this.stagingQty.set(1);
    this.productSearch.setValue(product.name);
  }

  onProductSearchInput(): void {
    // Free-typed text (no catalog match chosen) becomes the manual item's description.
    const text = this.productSearch.value;
    if (this.stagingProduct()?.name !== text) {
      this.stagingProduct.set(null);
    }
    this.stagingDescription.set(text);
  }

  addToCart(): void {
    const description = this.stagingDescription().trim();
    const qty = this.stagingQty();
    const rate = this.stagingRate();
    if (!description || qty <= 0 || rate < 0) {
      this.snackBar.open('Enter an item name, quantity, and rate before adding.', 'Dismiss', { duration: 4000 });
      return;
    }

    const product = this.stagingProduct();
    this.store.addItem({
      productId: product?.id ?? null,
      description,
      quantity: qty,
      unitPrice: rate,
      cgstPercentage: this.stagingCgstPercentage(),
      sgstPercentage: this.stagingSgstPercentage(),
    });

    this.stagingProduct.set(null);
    this.stagingDescription.set('');
    this.stagingQty.set(1);
    this.stagingRate.set(0);
    this.stagingCgstPercentage.set(0);
    this.stagingSgstPercentage.set(0);
    this.productSearch.setValue('');
  }

  updateItemQty(localId: string, value: string): void {
    const qty = Number(value);
    if (Number.isFinite(qty) && qty > 0) this.store.updateItem(localId, { quantity: qty });
  }

  updateItemRate(localId: string, value: string): void {
    const rate = Number(value);
    if (Number.isFinite(rate) && rate >= 0) this.store.updateItem(localId, { unitPrice: rate });
  }

  updateItemCgst(localId: string, value: string): void {
    const cgstPercentage = Number(value);
    if (Number.isFinite(cgstPercentage) && cgstPercentage >= 0) this.store.updateItem(localId, { cgstPercentage });
  }

  updateItemSgst(localId: string, value: string): void {
    const sgstPercentage = Number(value);
    if (Number.isFinite(sgstPercentage) && sgstPercentage >= 0) this.store.updateItem(localId, { sgstPercentage });
  }

  removeItem(localId: string): void {
    this.store.removeItem(localId);
  }

  // ── Discount / payment ───────────────────────────────────────────────────

  updateDiscount(value: string): void {
    this.store.setDiscountAmount(Number(value));
  }

  selectPaymentMethod(method: PaymentMethod): void {
    this.store.setPaymentMethod(method);
  }

  // ── Save / Print ──────────────────────────────────────────────────────────

  save(): void {
    const customer = this.store.customer();
    if (!customer) {
      this.snackBar.open('Select a customer first.', 'Dismiss', { duration: 4000 });
      return;
    }
    if (this.store.items().length === 0) {
      this.snackBar.open('Add at least one item before saving.', 'Dismiss', { duration: 4000 });
      return;
    }

    const products = this.allProducts();
    const request: CreateBillRequest = {
      customerId: customer.id,
      discountAmount: this.store.discountAmount(),
      items: this.store.items().map((line) => {
        // Backend only stores one combined tax rate per line — CGST+SGST is a display/entry convenience.
        const taxPercentage = round2(line.cgstPercentage + line.sgstPercentage);
        const catalogProduct = line.productId ? products.find((p) => p.id === line.productId) : undefined;
        const matchesCatalog =
          catalogProduct && catalogProduct.price === line.unitPrice && catalogProduct.taxPercentage === taxPercentage;

        return matchesCatalog
          ? { productId: line.productId, quantity: line.quantity }
          : {
              productId: null,
              description: line.description,
              quantity: line.quantity,
              unitPrice: line.unitPrice,
              taxPercentage,
            };
      }),
    };

    this.submitting.set(true);
    this.billService
      .create(request)
      .pipe(
        switchMap((bill) => this.billService.finalize(bill.id).pipe(switchMap(() => of(bill)))),
        switchMap((bill) =>
          this.billService
            .recordPayment(bill.id, { amount: this.store.grandTotal(), method: this.store.paymentMethod() })
            .pipe(switchMap(() => of(bill))),
        ),
      )
      .subscribe({
        next: (bill) => {
          this.submitting.set(false);
          this.savedBillId.set(bill.id);
          this.snackBar.open(`Bill ${bill.invoiceNumber} saved successfully`, 'Dismiss', { duration: 5000 });
        },
        error: (err: ApiError) => {
          this.submitting.set(false);
          this.snackBar.open(err.message || 'Failed to save bill.', 'Dismiss', { duration: 6000 });
        },
      });
  }

  print(): void {
    const billId = this.savedBillId();
    if (!billId) return;

    this.printing.set(true);
    this.billService.downloadPdf(billId).subscribe({
      next: (blob) => {
        this.printing.set(false);
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
      },
      error: (err: ApiError) => {
        this.printing.set(false);
        this.snackBar.open(err.message || 'Failed to generate PDF.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  startNewBill(): void {
    this.store.reset();
    this.savedBillId.set(null);
    this.customerService.getWalkIn().subscribe((customer) => this.store.setCustomer(customer));
  }
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}
