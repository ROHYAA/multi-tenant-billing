<?php

namespace App\Services;

use App\Exceptions\ApiException;
use App\Models\BusinessType;

class BusinessTypeCatalog
{
    public function listActive(): array
    {
        return BusinessType::query()
            ->where('is_active', true)
            ->orderBy('sort_order')
            ->orderBy('code')
            ->get()
            ->map(fn (BusinessType $type) => [
                'code' => $type->code,
                'label' => $type->label,
                'legacy' => (bool) $type->legacy,
            ])
            ->values()
            ->all();
    }

    public function assertActive(?string $code): void
    {
        if ($code === null || $code === '') {
            return;
        }
        $exists = BusinessType::query()->where('code', $code)->where('is_active', true)->exists();
        if (! $exists) {
            throw ApiException::invalid('Unknown or inactive business type: '.$code);
        }
    }
}
