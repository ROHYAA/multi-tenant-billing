import { Injectable, computed, signal } from '@angular/core';
import { Customer } from '../customers/customer.model';
import { CartLine, PaymentMethod } from './bill.model';

/**
 * Page-scoped cart state — provided at BillCreate's route, not root, so it's
 * created fresh per visit and discarded on navigation away (like a shopping
 * cart should behave). Everything here is local/in-memory until Save: no
 * network call happens while building the cart, which is what makes editing
 * feel instant (see the approved Billing UX design doc).
 */
@Injectable()
export class BillDraftStore {
  readonly customer = signal<Customer | null>(null);
  readonly items = signal<CartLine[]>([]);
  readonly discountAmount = signal<number>(0);
  readonly paymentMethod = signal<PaymentMethod>('CASH');

  readonly subtotal = computed(() =>
    round2(this.items().reduce((sum, line) => sum + line.quantity * line.unitPrice, 0)),
  );

  /** Each line's CGST% and SGST% are entered independently — summed here per line, not assumed equal. */
  readonly cgstAmount = computed(() =>
    round2(
      this.items().reduce((sum, line) => sum + (line.quantity * line.unitPrice * line.cgstPercentage) / 100, 0),
    ),
  );
  readonly sgstAmount = computed(() =>
    round2(
      this.items().reduce((sum, line) => sum + (line.quantity * line.unitPrice * line.sgstPercentage) / 100, 0),
    ),
  );
  readonly taxAmount = computed(() => round2(this.cgstAmount() + this.sgstAmount()));

  /** subtotal - discountAmount + taxAmount, clamped at 0 for display — mirrors BillService's server-side rule. */
  readonly grandTotal = computed(() =>
    Math.max(0, round2(this.subtotal() + this.taxAmount() - this.discountAmount())),
  );

  setCustomer(customer: Customer | null): void {
    this.customer.set(customer);
  }

  addItem(line: Omit<CartLine, 'localId'>): void {
    this.items.update((items) => [...items, { ...line, localId: crypto.randomUUID() }]);
  }

  removeItem(localId: string): void {
    this.items.update((items) => items.filter((line) => line.localId !== localId));
  }

  updateItem(localId: string, patch: Partial<Omit<CartLine, 'localId'>>): void {
    this.items.update((items) =>
      items.map((line) => (line.localId === localId ? { ...line, ...patch } : line)),
    );
  }

  setDiscountAmount(value: number): void {
    this.discountAmount.set(Number.isFinite(value) && value >= 0 ? value : 0);
  }

  setPaymentMethod(method: PaymentMethod): void {
    this.paymentMethod.set(method);
  }

  /** Clears the cart for the next bill — customer is reset separately (defaults to Walk-in). */
  reset(): void {
    this.items.set([]);
    this.discountAmount.set(0);
    this.paymentMethod.set('CASH');
  }
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}
