<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Model;

class Product extends Model
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id'];

    protected function casts(): array
    {
        return [
            'price' => 'decimal:2',
            'tax_percentage' => 'decimal:2',
            'is_active' => 'boolean',
            'deleted' => 'boolean',
        ];
    }
}
