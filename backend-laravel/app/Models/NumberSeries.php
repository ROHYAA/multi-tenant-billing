<?php

namespace App\Models;

use App\Models\Concerns\Auditable;
use App\Models\Concerns\BelongsToShop;
use Illuminate\Database\Eloquent\Model;

class NumberSeries extends Model
{
    use Auditable, BelongsToShop;

    protected $guarded = ['id'];

    protected function casts(): array
    {
        return [
            'is_active' => 'boolean',
            'starting_number' => 'integer',
            'current_number' => 'integer',
        ];
    }
}
