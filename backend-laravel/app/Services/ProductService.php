<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Product;
use App\Models\UsedPhonePurchase;
use App\Support\ApiResponse;
use Illuminate\Http\Request;

class ProductService
{
    public function create(array $data): array
    {
        $imei = $this->normalizeImei($data['imei'] ?? null);
        $this->assertUnique($data['name'], $imei);
        $product = Product::query()->create([
            'name' => $data['name'],
            'description' => $data['description'] ?? null,
            'price' => $data['price'],
            'tax_percentage' => $data['taxPercentage'] ?? 0,
            'hsn_sac_code' => $data['hsnSacCode'] ?? null,
            'imei' => $imei,
            'unit' => $data['unit'] ?? null,
            'is_active' => true,
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toResponse($product);
    }

    public function getById(int $id): array
    {
        return $this->toResponse($this->find($id));
    }

    public function list(Request $request): array
    {
        $query = Product::query();
        if ($search = trim((string) $request->query('search', ''))) {
            $like = '%'.strtolower($search).'%';
            $query->where(function ($inner) use ($like, $search) {
                $inner->whereRaw('LOWER(name) LIKE ?', [$like])
                    ->orWhere('imei', 'like', '%'.$search.'%');
            });
        }
        $query->orderByDesc('is_active')->orderBy('name');
        $page = max(0, (int) $request->query('page', 0));
        $size = max(1, min(100, (int) $request->query('size', 20)));
        $total = (clone $query)->count();
        $rows = $query->skip($page * $size)->take($size)->get();

        return ApiResponse::page($rows->map(fn (Product $p) => $this->toResponse($p))->values()->all(), $page, $size, $total);
    }

    public function listActive(): array
    {
        return Product::query()->where('is_active', true)->orderBy('name')->get()
            ->map(fn (Product $p) => $this->toResponse($p))->values()->all();
    }

    public function update(int $id, array $data): array
    {
        $product = $this->find($id);
        $nextName = array_key_exists('name', $data) && filled($data['name']) ? $data['name'] : $product->name;
        $nextImei = array_key_exists('imei', $data) ? $this->normalizeImei($data['imei']) : $product->imei;
        $this->assertUnique($nextName, $nextImei, $id);
        $map = [
            'name' => 'name',
            'description' => 'description',
            'price' => 'price',
            'taxPercentage' => 'tax_percentage',
            'hsnSacCode' => 'hsn_sac_code',
            'imei' => 'imei',
            'unit' => 'unit',
        ];
        if (array_key_exists('imei', $data)) {
            $data['imei'] = $nextImei;
        }
        foreach ($map as $in => $col) {
            if (array_key_exists($in, $data) && ($in !== 'name' || filled($data['name']))) {
                $product->{$col} = $data[$in];
            }
        }
        $product->save();

        return $this->toResponse($product);
    }

    public function deactivate(int $id): void
    {
        $product = $this->find($id);
        $product->is_active = false;
        $product->save();
    }

    public function reactivate(int $id): void
    {
        $product = $this->find($id);
        if ($product->used_phone_purchase_id) {
            $purchase = UsedPhonePurchase::query()->find($product->used_phone_purchase_id);
            if ($purchase && $purchase->status === 'SOLD') {
                throw ApiException::invalid('This used phone is already sold. Buy it in again from Used Phones to put it back in stock.');
            }
        }
        if ($product->imei) {
            $this->assertUnique($product->name, $product->imei, $id);
        }
        $product->is_active = true;
        $product->save();
    }

    public function entity(int $id): Product
    {
        return $this->find($id);
    }

    public function toResponse(Product $product): array
    {
        return [
            'id' => $product->id,
            'name' => $product->name,
            'description' => $product->description,
            'price' => (float) $product->price,
            'taxPercentage' => (float) $product->tax_percentage,
            'hsnSacCode' => $product->hsn_sac_code,
            'imei' => $product->imei,
            'unit' => $product->unit,
            'usedPhonePurchaseId' => $product->used_phone_purchase_id ? (int) $product->used_phone_purchase_id : null,
            'isActive' => (bool) $product->is_active,
            'createdAt' => optional($product->created_at)?->toISOString(),
            'updatedAt' => optional($product->updated_at)?->toISOString(),
        ];
    }

    private function normalizeImei(mixed $imei): ?string
    {
        $value = strtoupper(preg_replace('/[\s-]+/', '', (string) $imei) ?? '');
        if ($value === '') {
            return null;
        }
        if (! preg_match('/^[0-9]{14,16}$/', $value)) {
            throw ApiException::invalid('IMEI must be 14 to 16 digits');
        }

        return $value;
    }

    private function assertUnique(string $name, ?string $imei, ?int $ignoreId = null): void
    {
        if ($imei) {
            // A sold (inactive) phone may be bought back later, so only live stock must be unique.
            $q = Product::query()->where('imei', $imei)->where('is_active', true);
            if ($ignoreId) {
                $q->where('id', '!=', $ignoreId);
            }
            if ($q->exists()) {
                throw ApiException::alreadyExists('IMEI', $imei);
            }

            return;
        }
        $q = Product::query()->where('name', $name);
        if ($ignoreId) {
            $q->where('id', '!=', $ignoreId);
        }
        if ($q->exists()) {
            throw ApiException::alreadyExists('Product', $name);
        }
    }

    private function find(int $id): Product
    {
        $product = Product::query()->find($id);
        if (! $product) {
            throw ApiException::notFound('Product', $id);
        }

        return $product;
    }
}
