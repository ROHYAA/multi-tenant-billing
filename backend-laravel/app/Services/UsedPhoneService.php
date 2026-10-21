<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\BillItem;
use App\Models\Customer;
use App\Models\NumberSeries;
use App\Models\Product;
use App\Models\UsedPhonePurchase;
use App\Support\ApiResponse;
use App\Support\Money;
use Illuminate\Http\Request;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;

/**
 * Second-hand phone buy-in register.
 *
 * Buying: records the seller (ID proof required), the device (IMEI required), the price paid,
 * issues a PUR-xxxx voucher and puts the phone into stock as a Product.
 * Selling: when a bill containing that phone is finalized, the purchase is marked SOLD and
 * linked to the bill and buyer; voiding the bill puts it back in stock.
 */
class UsedPhoneService
{
    public const ID_TYPES = ['AADHAAR', 'PAN', 'DRIVING_LICENCE', 'VOTER_ID', 'PASSPORT', 'OTHER'];

    public const PHOTO_PURPOSES = ['ID_PROOF', 'SELLER_PHOTO', 'PHONE_PHOTO', 'SELLER_SIGNATURE'];

    public function __construct(
        private NumberSeriesService $numbers,
        private AttachmentService $attachments,
    ) {}

    public function create(array $d): array
    {
        $imei1 = $this->imei((string) $d['imei1'], 'IMEI 1');
        $imei2 = filled($d['imei2'] ?? null) ? $this->imei((string) $d['imei2'], 'IMEI 2') : null;
        if ($imei2 !== null && $imei2 === $imei1) {
            throw ApiException::invalid('IMEI 2 must be different from IMEI 1');
        }
        $mobile = $this->mobile((string) $d['sellerMobile']);
        [$idType, $idNumber] = $this->idProof((string) $d['sellerIdType'], (string) $d['sellerIdNumber']);
        if (! ($d['declarationAccepted'] ?? false)) {
            throw ApiException::invalid('The seller must accept the ownership declaration before you buy the phone');
        }
        foreach (['idProofAttachmentId', 'sellerPhotoAttachmentId', 'phonePhotoAttachmentId', 'sellerSignatureAttachmentId'] as $key) {
            if (! empty($d[$key])) {
                $this->attachments->getById((int) $d[$key]);
            }
        }

        return DB::transaction(function () use ($d, $imei1, $imei2, $mobile, $idType, $idNumber) {
            $this->assertNotInStock(array_filter([$imei1, $imei2]));
            $this->ensurePurchaseSeries();

            $purchasePrice = Money::round($d['purchasePrice']);
            $sellingPrice = isset($d['sellingPrice']) && $d['sellingPrice'] !== null && $d['sellingPrice'] !== ''
                ? Money::round($d['sellingPrice'])
                : $purchasePrice;
            $brand = trim((string) $d['brand']);
            $model = trim((string) $d['model']);
            $storage = trim((string) ($d['storage'] ?? '')) ?: null;
            $color = trim((string) ($d['color'] ?? '')) ?: null;

            $product = Product::query()->create([
                'name' => trim(implode(' ', array_filter([$brand, $model, $storage]))).' (Used)',
                'description' => trim(implode(', ', array_filter([
                    $color,
                    ! empty($d['conditionGrade']) ? 'Condition: '.$d['conditionGrade'] : null,
                    $imei2 ? 'IMEI 2: '.$imei2 : null,
                ]))) ?: null,
                'price' => $sellingPrice,
                'tax_percentage' => Money::round($d['taxPercentage'] ?? 0),
                'hsn_sac_code' => '8517',
                'imei' => $imei1,
                'unit' => 'pcs',
                'is_active' => true,
                'deleted' => false,
                'version' => 0,
            ]);

            $row = UsedPhonePurchase::query()->create([
                'voucher_number' => $this->numbers->nextNumber('PURCHASE'),
                'status' => 'IN_STOCK',
                'product_id' => $product->id,
                'seller_name' => trim((string) $d['sellerName']),
                'seller_mobile' => $mobile,
                'seller_address' => trim((string) ($d['sellerAddress'] ?? '')) ?: null,
                'seller_id_type' => $idType,
                'seller_id_number' => $idNumber,
                'id_proof_attachment_id' => (int) $d['idProofAttachmentId'],
                'seller_photo_attachment_id' => $d['sellerPhotoAttachmentId'] ?? null,
                'phone_photo_attachment_id' => $d['phonePhotoAttachmentId'] ?? null,
                'seller_signature_attachment_id' => $d['sellerSignatureAttachmentId'] ?? null,
                'brand' => $brand,
                'model' => $model,
                'color' => $color,
                'storage' => $storage,
                'imei1' => $imei1,
                'imei2' => $imei2,
                'serial_number' => trim((string) ($d['serialNumber'] ?? '')) ?: null,
                'condition_grade' => $d['conditionGrade'] ?? null,
                'battery_health' => $d['batteryHealth'] ?? null,
                'accessories' => $this->accessories($d['accessories'] ?? []),
                'has_original_bill' => (bool) ($d['hasOriginalBill'] ?? false),
                'ceir_checked' => (bool) ($d['ceirChecked'] ?? false),
                'declaration_accepted' => true,
                'purchase_price' => $purchasePrice,
                'payment_method' => $d['paymentMethod'] ?? 'CASH',
                'selling_price' => $sellingPrice,
                'notes' => trim((string) ($d['notes'] ?? '')) ?: null,
                'purchased_at' => now(),
                'deleted' => false,
                'version' => 0,
            ]);

            $product->used_phone_purchase_id = $row->id;
            $product->save();

            return $this->toResponse($row->fresh());
        });
    }

    public function getById(int $id): array
    {
        return $this->toResponse($this->find($id));
    }

    public function entity(int $id): UsedPhonePurchase
    {
        return $this->find($id);
    }

    public function list(Request $request): array
    {
        $query = UsedPhonePurchase::query();
        if ($status = strtoupper(trim((string) $request->query('status', '')))) {
            $query->where('status', $status);
        }
        if ($search = trim((string) $request->query('search', ''))) {
            $like = '%'.strtolower($search).'%';
            $query->where(function ($q) use ($like, $search) {
                $q->whereRaw('LOWER(voucher_number) LIKE ?', [$like])
                    ->orWhereRaw('LOWER(seller_name) LIKE ?', [$like])
                    ->orWhereRaw('LOWER(brand) LIKE ?', [$like])
                    ->orWhereRaw('LOWER(model) LIKE ?', [$like])
                    ->orWhere('seller_mobile', 'like', '%'.$search.'%')
                    ->orWhere('imei1', 'like', '%'.$search.'%')
                    ->orWhere('imei2', 'like', '%'.$search.'%');
            });
        }
        $page = max(0, (int) $request->query('page', 0));
        $size = max(1, min(100, (int) $request->query('size', 20)));
        $total = (clone $query)->count();
        $rows = $query->orderByDesc('purchased_at')->orderByDesc('id')->skip($page * $size)->take($size)->get();

        return ApiResponse::page($rows->map(fn ($r) => $this->toResponse($r))->values()->all(), $page, $size, $total);
    }

    /**
     * Everything the shop knows about one IMEI: earlier buy-ins and earlier sales.
     */
    public function imeiHistory(string $raw): array
    {
        $imei = preg_replace('/\D+/', '', $raw) ?? '';
        if ($imei === '') {
            throw ApiException::invalid('IMEI is required');
        }
        $purchases = UsedPhonePurchase::query()
            ->where(fn ($q) => $q->where('imei1', $imei)->orWhere('imei2', $imei))
            ->orderByDesc('purchased_at')
            ->get();
        $sales = BillItem::query()
            ->where('imei', $imei)
            ->orderByDesc('id')
            ->get()
            ->map(function (BillItem $item) {
                $bill = Bill::query()->find($item->bill_id);

                return $bill ? [
                    'billId' => $bill->id,
                    'invoiceNumber' => $bill->invoice_number,
                    'status' => $bill->status,
                    'customerName' => $bill->customer?->name,
                    'date' => optional($bill->created_at)?->toISOString(),
                    'amount' => Money::num($item->total),
                ] : null;
            })
            ->filter()
            ->values()
            ->all();
        $inStock = $purchases->contains(fn ($p) => $p->status === 'IN_STOCK')
            || Product::query()->where('imei', $imei)->where('is_active', true)->exists();

        return [
            'imei' => $imei,
            'validImei' => strlen($imei) === 15 && $this->luhn($imei),
            'inStock' => $inStock,
            'purchases' => $purchases->map(fn ($p) => $this->toResponse($p))->values()->all(),
            'sales' => $sales,
        ];
    }

    /**
     * Used-phone lines on a bill, paired with their purchase records (locked for update).
     *
     * @param  Collection<int, BillItem>  $items
     * @return list<array{item: BillItem, purchase: UsedPhonePurchase}>
     */
    public function matchBillItems(Collection $items): array
    {
        $matches = [];
        foreach ($items as $item) {
            $purchase = null;
            if ($item->product_id) {
                $purchase = UsedPhonePurchase::query()->where('product_id', $item->product_id)->lockForUpdate()->first();
            }
            if (! $purchase && $item->imei) {
                // Prefer the in-stock buy-in; otherwise the latest one, so re-selling a sold phone is caught.
                $purchase = UsedPhonePurchase::query()
                    ->where(fn ($q) => $q->where('imei1', $item->imei)->orWhere('imei2', $item->imei))
                    ->orderByRaw("CASE WHEN status = 'IN_STOCK' THEN 0 ELSE 1 END")
                    ->orderByDesc('purchased_at')
                    ->orderByDesc('id')
                    ->lockForUpdate()
                    ->first();
            }
            if ($purchase) {
                $matches[] = ['item' => $item, 'purchase' => $purchase];
            }
        }

        return $matches;
    }

    /**
     * Used phones must go to a named buyer with a mobile number, one phone per line.
     *
     * @param  list<array{item: BillItem, purchase: UsedPhonePurchase}>  $matches
     */
    public function assertSellable(?Customer $customer, array $matches): void
    {
        if ($matches === []) {
            return;
        }
        if (! $customer || $customer->is_walkin || trim((string) $customer->phone) === '') {
            throw ApiException::invalid('Used phone sales need the buyer\'s name and mobile number. Select or add the customer before billing.');
        }
        foreach ($matches as ['item' => $item, 'purchase' => $purchase]) {
            if (Money::cmp($item->quantity, '1') !== 0) {
                throw ApiException::invalid("Quantity for used phone {$purchase->brand} {$purchase->model} (IMEI {$purchase->imei1}) must be 1.");
            }
            if ($purchase->status !== 'IN_STOCK') {
                throw ApiException::invalid("Used phone {$purchase->brand} {$purchase->model} (IMEI {$purchase->imei1}) is already sold.");
            }
        }
    }

    /**
     * Called inside BillService::finalize's transaction.
     */
    public function markSold(Bill $bill, ?Customer $customer): void
    {
        $items = BillItem::query()->where('bill_id', $bill->id)->get();
        $matches = $this->matchBillItems($items);
        $this->assertSellable($customer, $matches);
        foreach ($matches as ['item' => $item, 'purchase' => $purchase]) {
            $purchase->status = 'SOLD';
            $purchase->sold_bill_id = $bill->id;
            $purchase->buyer_customer_id = $bill->customer_id;
            $purchase->sold_at = now();
            $purchase->save();

            if (! $item->product_id || ! $item->imei) {
                $item->product_id = $item->product_id ?: $purchase->product_id;
                $item->imei = $item->imei ?: $purchase->imei1;
                $item->save();
            }
            if ($purchase->product_id && ($product = Product::query()->find($purchase->product_id))) {
                $product->is_active = false;
                $product->save();
            }
        }
    }

    /**
     * Called when a bill is voided: phones sold on it go back into stock.
     */
    public function releaseForBill(Bill $bill): void
    {
        $rows = UsedPhonePurchase::query()->where('sold_bill_id', $bill->id)->lockForUpdate()->get();
        foreach ($rows as $purchase) {
            $purchase->status = 'IN_STOCK';
            $purchase->sold_bill_id = null;
            $purchase->buyer_customer_id = null;
            $purchase->sold_at = null;
            $purchase->save();
            if ($purchase->product_id && ($product = Product::query()->find($purchase->product_id))) {
                $product->is_active = true;
                $product->save();
            }
        }
    }

    public function toResponse(UsedPhonePurchase $row): array
    {
        $bill = $row->sold_bill_id ? Bill::query()->find($row->sold_bill_id) : null;
        $buyer = $row->buyer_customer_id ? Customer::query()->find($row->buyer_customer_id) : null;

        return [
            'id' => (int) $row->id,
            'voucherNumber' => $row->voucher_number,
            'status' => $row->status,
            'productId' => $row->product_id ? (int) $row->product_id : null,
            'sellerName' => $row->seller_name,
            'sellerMobile' => $row->seller_mobile,
            'sellerAddress' => $row->seller_address,
            'sellerIdType' => $row->seller_id_type,
            'sellerIdNumber' => $row->seller_id_number,
            'idProofUrl' => $this->attachments->url($row->id_proof_attachment_id),
            'sellerPhotoUrl' => $this->attachments->url($row->seller_photo_attachment_id),
            'phonePhotoUrl' => $this->attachments->url($row->phone_photo_attachment_id),
            'sellerSignatureUrl' => $this->attachments->url($row->seller_signature_attachment_id),
            'brand' => $row->brand,
            'model' => $row->model,
            'color' => $row->color,
            'storage' => $row->storage,
            'imei1' => $row->imei1,
            'imei2' => $row->imei2,
            'serialNumber' => $row->serial_number,
            'conditionGrade' => $row->condition_grade,
            'batteryHealth' => $row->battery_health,
            'accessories' => $row->accessories ? explode(',', $row->accessories) : [],
            'hasOriginalBill' => (bool) $row->has_original_bill,
            'ceirChecked' => (bool) $row->ceir_checked,
            'purchasePrice' => Money::num($row->purchase_price),
            'sellingPrice' => $row->selling_price !== null ? Money::num($row->selling_price) : null,
            'paymentMethod' => $row->payment_method,
            'notes' => $row->notes,
            'purchasedAt' => optional($row->purchased_at)?->toISOString(),
            'soldAt' => optional($row->sold_at)?->toISOString(),
            'soldBillId' => $bill?->id,
            'soldInvoiceNumber' => $bill?->invoice_number,
            'buyerName' => $buyer?->name,
            'buyerMobile' => $buyer?->phone,
        ];
    }

    /**
     * Rejects a buy-in when the phone is already sitting in this shop's stock.
     *
     * @param  list<string>  $imeis
     */
    private function assertNotInStock(array $imeis): void
    {
        foreach ($imeis as $imei) {
            $inRegister = UsedPhonePurchase::query()
                ->where('status', 'IN_STOCK')
                ->where(fn ($q) => $q->where('imei1', $imei)->orWhere('imei2', $imei))
                ->lockForUpdate()
                ->first();
            if ($inRegister) {
                throw ApiException::invalid("IMEI {$imei} is already in stock (voucher {$inRegister->voucher_number}).");
            }
            if (Product::query()->where('imei', $imei)->where('is_active', true)->exists()) {
                throw ApiException::invalid("IMEI {$imei} is already in stock as an active product.");
            }
        }
    }

    private function ensurePurchaseSeries(): void
    {
        $exists = NumberSeries::query()->where('series_type', 'PURCHASE')->where('is_active', true)->exists();
        if (! $exists) {
            NumberSeries::query()->create([
                'series_type' => 'PURCHASE',
                'prefix' => 'PUR',
                'financial_year_format' => 'NONE',
                'starting_number' => 1,
                'current_number' => 0,
                'is_active' => true,
                'deleted' => false,
                'version' => 0,
            ]);
        }
    }

    /**
     * Phone IMEIs are 15 digits with a Luhn check digit (what *#06# shows).
     */
    private function imei(string $raw, string $label): string
    {
        $value = preg_replace('/[\s-]+/', '', $raw) ?? '';
        if (! preg_match('/^\d{15}$/', $value)) {
            throw ApiException::invalid("{$label} must be exactly 15 digits (dial *#06# on the phone to see it).");
        }
        if (! $this->luhn($value)) {
            throw ApiException::invalid("{$label} {$value} is not a valid IMEI. Please re-check the digits.");
        }

        return $value;
    }

    private function luhn(string $digits): bool
    {
        $sum = 0;
        $double = false;
        for ($i = strlen($digits) - 1; $i >= 0; $i--) {
            $d = (int) $digits[$i];
            if ($double) {
                $d *= 2;
                if ($d > 9) {
                    $d -= 9;
                }
            }
            $sum += $d;
            $double = ! $double;
        }

        return $sum % 10 === 0;
    }

    private function mobile(string $raw): string
    {
        $digits = preg_replace('/\D+/', '', $raw) ?? '';
        if (strlen($digits) === 12 && str_starts_with($digits, '91')) {
            $digits = substr($digits, 2);
        } elseif (strlen($digits) === 11 && str_starts_with($digits, '0')) {
            $digits = substr($digits, 1);
        }
        if (! preg_match('/^[6-9]\d{9}$/', $digits)) {
            throw ApiException::invalid('Seller mobile must be a valid 10-digit Indian mobile number.');
        }

        return $digits;
    }

    /**
     * Validates the ID number for its type. Aadhaar is stored masked (last 4 digits only).
     *
     * @return array{0:string,1:string}
     */
    private function idProof(string $type, string $raw): array
    {
        $type = strtoupper(trim($type));
        if (! in_array($type, self::ID_TYPES, true)) {
            throw ApiException::invalid('Unknown ID proof type.');
        }
        $value = strtoupper(preg_replace('/[\s-]+/', '', $raw) ?? '');
        $ok = match ($type) {
            'AADHAAR' => (bool) preg_match('/^(\d{12}|X{8}\d{4})$/', $value),
            'PAN' => (bool) preg_match('/^[A-Z]{5}\d{4}[A-Z]$/', $value),
            'PASSPORT' => (bool) preg_match('/^[A-Z]\d{7}$/', $value),
            default => (bool) preg_match('/^[A-Z0-9\/]{5,20}$/', $value),
        };
        if (! $ok) {
            $hint = match ($type) {
                'AADHAAR' => '12 digits',
                'PAN' => 'like ABCDE1234F',
                'PASSPORT' => 'like A1234567',
                default => '5 to 20 letters/digits',
            };
            throw ApiException::invalid("Seller ID number is not valid for {$type} ({$hint}).");
        }
        if ($type === 'AADHAAR') {
            $value = 'XXXX XXXX '.substr($value, -4);
        }

        return [$type, $value];
    }

    private function accessories(mixed $value): ?string
    {
        $list = is_array($value) ? $value : explode(',', (string) $value);
        $clean = array_values(array_unique(array_filter(array_map(
            fn ($v) => strtoupper(trim((string) $v)),
            $list
        ))));

        return $clean ? implode(',', $clean) : null;
    }

    private function find(int $id): UsedPhonePurchase
    {
        $row = UsedPhonePurchase::query()->find($id);
        if (! $row) {
            throw ApiException::notFound('UsedPhonePurchase', $id);
        }

        return $row;
    }
}
