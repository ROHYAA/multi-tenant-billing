<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class UsedPhonePurchase extends Model
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id', 'shop_id'];

    protected function casts(): array
    {
        return [
            'purchase_price' => 'decimal:2',
            'selling_price' => 'decimal:2',
            'battery_health' => 'integer',
            'has_original_bill' => 'boolean',
            'ceir_checked' => 'boolean',
            'declaration_accepted' => 'boolean',
            'purchased_at' => 'datetime',
            'sold_at' => 'datetime',
        ];
    }

    public function soldBill(): BelongsTo
    {
        return $this->belongsTo(Bill::class, 'sold_bill_id');
    }

    public function buyer(): BelongsTo
    {
        return $this->belongsTo(Customer::class, 'buyer_customer_id');
    }
}
