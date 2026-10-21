<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class RefreshToken extends Model
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id'];

    protected function casts(): array
    {
        return [
            'expiry_date' => 'datetime',
            'revoked' => 'boolean',
            'deleted' => 'boolean',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
