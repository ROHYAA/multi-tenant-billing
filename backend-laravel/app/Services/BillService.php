<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\BillItem;
use App\Models\Customer;
use App\Models\OutboxEvent;
use App\Models\Payment;
use App\Support\ApiResponse;
use App\Support\Money;
use App\Support\SpringPage;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class BillService
{
    public function __construct(
        private CustomerService $customers,
        private ProductService $products,
        private NumberSeriesService $numbers,
        private UsedPhoneService $usedPhones,
    ) {}

    public function create(array $data): array
    {
        return DB::transaction(function () use ($data) {
            $customer = $this->customers->entity((int) $data['customerId']);
            $bill = Bill::query()->create([
                'invoice_number' => $this->numbers->nextNumber('INVOICE'),
                'customer_id' => $customer->id,
                'status' => 'DRAFT',
                'currency' => $data['currency'] ?? 'INR',
                'notes' => $data['notes'] ?? null,
                'discount_amount' => $data['discountAmount'] ?? 0,
                'subtotal' => 0,
                'tax_amount' => 0,
                'total_amount' => 0,
                'deleted' => false,
                'version' => 0,
            ]);
            foreach ($data['items'] as $item) {
                $this->persistItem($bill, $item);
            }
            $this->recalculate($bill);
            // Fail fast (before finalize) if a used phone is going to a walk-in or is already sold.
            $this->usedPhones->assertSellable(
                $customer,
                $this->usedPhones->matchBillItems(BillItem::query()->where('bill_id', $bill->id)->get())
            );

            return $this->toResponse($bill->fresh(), $customer);
        });
    }

    public function addItem(int $id, array $data): array
    {
        return DB::transaction(function () use ($id, $data) {
            $bill = $this->find($id);
            $this->assertDraft($bill);
            $this->persistItem($bill, $data);
            $this->recalculate($bill);
            $customer = $this->customers->entity((int) $bill->customer_id);

            return $this->toResponse($bill->fresh(), $customer);
        });
    }

    public function removeItem(int $id, int $itemId): array
    {
        return DB::transaction(function () use ($id, $itemId) {
            $bill = $this->find($id);
            $this->assertDraft($bill);
            $item = BillItem::query()->find($itemId);
            if (! $item) {
                throw ApiException::notFound('InvoiceItem', $itemId);
            }
            if ((int) $item->bill_id !== $id) {
                throw ApiException::resourceAccessDenied('Line item does not belong to this invoice');
            }
            $item->delete();
            $this->recalculate($bill);
            $customer = $this->customers->entity((int) $bill->customer_id);

            return $this->toResponse($bill->fresh(), $customer);
        });
    }

    public function finalize(int $id): array
    {
        return DB::transaction(function () use ($id) {
            $bill = $this->find($id);
            $this->assertDraft($bill);
            if (BillItem::query()->where('bill_id', $id)->count() === 0) {
                throw ApiException::invalid('Cannot finalize an invoice with no line items');
            }
            $customer = $this->customers->entity((int) $bill->customer_id);
            $this->usedPhones->markSold($bill, $customer);
            $bill->status = 'OPEN';
            $bill->due_date = now()->addDays(30);
            $bill->save();

            return $this->toResponse($bill->fresh(), $customer);
        });
    }

    public function send(int $id): array
    {
        $bill = $this->find($id);
        if ($bill->status !== 'OPEN') {
            throw ApiException::invalid('Only OPEN invoices can be sent. Current status: '.$bill->status);
        }
        $customer = $this->customers->entity((int) $bill->customer_id);
        OutboxEvent::query()->create([
            'event_type' => 'BILL_SENT',
            'aggregate_type' => 'Bill',
            'aggregate_id' => (string) $bill->id,
            'payload' => json_encode(['invoiceNumber' => $bill->invoice_number]),
            'event_class' => 'BILL_SENT',
            'status' => 'PENDING',
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toResponse($bill, $customer);
    }

    public function voidInvoice(int $id): array
    {
        return DB::transaction(function () use ($id) {
            $bill = $this->find($id);
            if ($bill->status === 'PAID') {
                throw ApiException::invalid('Cannot void a PAID invoice. Record a refund in Payments instead.');
            }
            if ($bill->status === 'VOID') {
                throw ApiException::invalid('Invoice is already voided.');
            }
            if (Payment::query()->where('bill_id', $id)->where('status', 'PENDING')->exists()) {
                throw ApiException::invalid('Cannot void an invoice with a pending credit payment against it. Wait for it to be confirmed or collected differently first.');
            }
            $bill->status = 'VOID';
            $bill->save();
            $this->usedPhones->releaseForBill($bill);

            return $this->toResponse($bill->fresh(), $this->customers->entity((int) $bill->customer_id));
        });
    }

    public function getById(int $id): array
    {
        $bill = $this->find($id);

        return $this->toResponse($bill, $this->customers->entity((int) $bill->customer_id));
    }

    public function findForPdf(int $id): Bill
    {
        $bill = $this->find($id);
        $bill->loadMissing('customer');

        return $bill;
    }

    public function list(Request $request): array
    {
        $query = Bill::query();
        if ($request->filled('customerId')) {
            $query->where('customer_id', (int) $request->query('customerId'));
        }
        if ($request->filled('status')) {
            $query->where('status', $request->query('status'));
        }
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');
        $content = $rows->map(fn (Bill $bill) => $this->toResponse($bill, $bill->customer))->values()->all();

        return ApiResponse::page($content, $page, $size, $total);
    }

    public function markPaid(int $id): void
    {
        $bill = $this->find($id);
        if ($bill->status === 'PAID') {
            return;
        }
        $bill->status = 'PAID';
        $bill->paid_at = now();
        $bill->save();
    }

    public function entityForUpdate(int $id): Bill
    {
        $bill = Bill::query()->lockForUpdate()->find($id);
        if (! $bill) {
            throw ApiException::notFound('Bill', $id);
        }

        return $bill;
    }

    public function openBillsForCustomerForUpdate(int $customerId)
    {
        return Bill::query()
            ->where('customer_id', $customerId)
            ->where('status', 'OPEN')
            ->orderBy('created_at')
            ->orderBy('id')
            ->lockForUpdate()
            ->get();
    }

    public function openBillsForCustomer(int $customerId)
    {
        return Bill::query()
            ->where('customer_id', $customerId)
            ->where('status', 'OPEN')
            ->orderBy('created_at')
            ->orderBy('id')
            ->get();
    }

    public function toResponse(Bill $bill, ?Customer $customer = null): array
    {
        $customer = $customer ?? $bill->customer;
        $items = BillItem::query()->where('bill_id', $bill->id)->orderBy('id')->get();

        return [
            'id' => $bill->id,
            'invoiceNumber' => $bill->invoice_number,
            'customerId' => $bill->customer_id,
            'customerName' => $customer?->name,
            'customerEmail' => $customer?->email,
            'status' => $bill->status,
            'subtotal' => Money::num($bill->subtotal),
            'taxAmount' => Money::num($bill->tax_amount),
            'discountAmount' => Money::num($bill->discount_amount),
            'totalAmount' => Money::num($bill->total_amount),
            'currency' => $bill->currency,
            'notes' => $bill->notes,
            'dueDate' => optional($bill->due_date)?->toISOString(),
            'paidAt' => optional($bill->paid_at)?->toISOString(),
            'items' => $items->map(fn (BillItem $item) => [
                'id' => $item->id,
                'productId' => $item->product_id,
                'description' => $item->description,
                'imei' => $item->imei,
                'quantity' => Money::num($item->quantity),
                'unitPrice' => Money::num($item->unit_price),
                'taxPercentage' => Money::num($item->tax_percentage),
                'taxAmount' => Money::num($item->tax_amount),
                'total' => Money::num($item->total),
            ])->values()->all(),
            'createdAt' => optional($bill->created_at)?->toISOString(),
            'updatedAt' => optional($bill->updated_at)?->toISOString(),
        ];
    }

    private function persistItem(Bill $bill, array $req): BillItem
    {
        $unitPrice = $req['unitPrice'] ?? null;
        $taxPercentage = $req['taxPercentage'] ?? 0;
        $description = $req['description'] ?? null;
        $productId = $req['productId'] ?? null;
        $imei = isset($req['imei']) ? strtoupper(preg_replace('/[\s-]+/', '', (string) $req['imei']) ?? '') : null;
        $imei = $imei !== '' ? $imei : null;
        if ($productId) {
            $product = $this->products->entity((int) $productId);
            if (! $product->is_active) {
                throw ApiException::invalid("Product '{$product->name}' is deactivated and cannot be added to an invoice.");
            }
            $unitPrice = $product->price;
            $taxPercentage = $product->tax_percentage;
            $description = $product->name;
            $imei = $imei ?: $product->imei;
        }
        if ($description === null || $description === '' || $unitPrice === null) {
            throw ApiException::invalid('description and unitPrice are required when productId is not set');
        }
        $lineBase = Money::mul($unitPrice, $req['quantity']);
        $taxAmount = Money::round(bcmul($lineBase, bcdiv((string) $taxPercentage, '100', 8), 8));
        $total = Money::add($lineBase, $taxAmount);

        return BillItem::query()->create([
            'bill_id' => $bill->id,
            'product_id' => $productId,
            'description' => $description,
            'imei' => $imei,
            'quantity' => $req['quantity'],
            'unit_price' => Money::round($unitPrice),
            'tax_percentage' => Money::round($taxPercentage),
            'tax_amount' => $taxAmount,
            'total' => $total,
            'deleted' => false,
            'version' => 0,
        ]);
    }

    private function recalculate(Bill $bill): void
    {
        $items = BillItem::query()->where('bill_id', $bill->id)->get();
        $subtotal = '0.00';
        $taxAmount = '0.00';
        foreach ($items as $item) {
            $lineBase = Money::mul($item->unit_price, $item->quantity);
            $subtotal = Money::add($subtotal, $lineBase);
            $taxAmount = Money::add($taxAmount, $item->tax_amount);
        }
        $discount = $bill->discount_amount ?? '0';
        $grand = Money::sub(Money::add($subtotal, $taxAmount), $discount);
        if (Money::cmp($grand, '0') < 0) {
            throw ApiException::invalid("Discount amount cannot exceed the bill's subtotal plus tax.");
        }
        $bill->subtotal = $subtotal;
        $bill->tax_amount = $taxAmount;
        $bill->total_amount = $grand;
        $bill->save();
    }

    private function assertDraft(Bill $bill): void
    {
        if ($bill->status !== 'DRAFT') {
            throw ApiException::invalid('This operation is only allowed on DRAFT invoices. Current status: '.$bill->status);
        }
    }

    private function find(int $id): Bill
    {
        $bill = Bill::query()->find($id);
        if (! $bill) {
            throw ApiException::notFound('Bill', $id);
        }

        return $bill;
    }
}
