<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\Bill;
use App\Models\Customer;
use App\Support\ApiResponse;
use App\Support\SpringPage;
use Illuminate\Http\Request;

class CustomerService
{
    public function create(array $data): array
    {
        if (! empty($data['email']) && Customer::query()->where('email', $data['email'])->exists()) {
            throw ApiException::alreadyExists('Customer', $data['email']);
        }

        $customer = Customer::query()->create([
            'name' => $data['name'],
            'email' => $data['email'] ?? null,
            'phone' => $data['phone'] ?? null,
            'address' => $data['address'] ?? null,
            'gstin' => $data['gstin'] ?? null,
            'is_walkin' => false,
            'deleted' => false,
            'version' => 0,
        ]);

        return $this->toResponse($customer);
    }

    public function getById(int $id): array
    {
        return $this->toResponse($this->find($id));
    }

    public function walkIn(): array
    {
        $customer = Customer::query()->where('is_walkin', true)->first();
        if (! $customer) {
            throw ApiException::notFound('Walk-in Customer', 'default');
        }

        return $this->toResponse($customer);
    }

    public function list(Request $request): array
    {
        $query = Customer::query();
        if ($search = trim((string) $request->query('search', ''))) {
            $like = '%'.strtolower($search).'%';
            $query->where(function ($q) use ($like) {
                $q->whereRaw('LOWER(name) LIKE ?', [$like])
                    ->orWhereRaw('LOWER(COALESCE(email, \'\')) LIKE ?', [$like]);
            });
        }
        [$rows, $page, $size, $total] = SpringPage::paginate($query, $request, 'created_at,desc');

        return ApiResponse::page($rows->map(fn (Customer $c) => $this->toResponse($c))->values()->all(), $page, $size, $total);
    }

    public function update(int $id, array $data): array
    {
        $customer = $this->find($id);
        if ($customer->is_walkin && isset($data['name']) && $data['name'] !== '' && $data['name'] !== $customer->name) {
            throw ApiException::resourceAccessDenied('The Walk-in Customer cannot be renamed.');
        }
        if (! empty($data['email']) && strcasecmp((string) $data['email'], (string) $customer->email) !== 0
            && Customer::query()->where('email', $data['email'])->where('id', '!=', $id)->exists()) {
            throw ApiException::alreadyExists('Customer', $data['email']);
        }
        foreach (['name', 'email', 'phone', 'address', 'gstin'] as $field) {
            if (array_key_exists($field, $data) && ($field !== 'name' || filled($data['name']))) {
                $customer->{$field} = $data[$field];
            }
        }
        $customer->save();

        return $this->toResponse($customer);
    }

    public function delete(int $id): void
    {
        $customer = $this->find($id);
        if ($customer->is_walkin) {
            throw ApiException::resourceAccessDenied('The Walk-in Customer cannot be deleted.');
        }
        $hasBills = Bill::query()->where('customer_id', $id)->where('status', '!=', 'VOID')->exists();
        if ($hasBills) {
            throw ApiException::invalid('Cannot delete customer with open or paid invoices. Void all invoices first.');
        }
        $customer->delete();
    }

    public function entity(int $id): Customer
    {
        return $this->find($id);
    }

    public function toResponse(Customer $customer): array
    {
        return [
            'id' => $customer->id,
            'name' => $customer->name,
            'email' => $customer->email,
            'phone' => $customer->phone,
            'address' => $customer->address,
            'gstin' => $customer->gstin,
            'isWalkin' => (bool) $customer->is_walkin,
            'createdAt' => optional($customer->created_at)?->toISOString(),
            'updatedAt' => optional($customer->updated_at)?->toISOString(),
        ];
    }

    private function find(int $id): Customer
    {
        $customer = Customer::query()->find($id);
        if (! $customer) {
            throw ApiException::notFound('Customer', $id);
        }

        return $customer;
    }
}
